package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import com.whidte.trulybestfriends.Config;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.network.PacketDistributor;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client → Server: requests pet data.
 *
 * Two request modes:
 *  - REQUEST_FULL_LIST: client just opened the UI (or wants a refresh), server
 *                       replies with a SyncPetDataPacket.fullList containing all pets.
 *  - REQUEST_SELECTED:  client wants the latest NBT for a specific pet (selected),
 *                       server replies with SyncPetDataPacket.update or .delete.
 *
 * This packet is the replacement for client-side disk reads in PetDataLoader and
 * TrulyScreen.refreshSelectedFromDisk.
 */
public class RequestPetDataPacket {
    public static final int REQUEST_FULL_LIST = 0;
    public static final int REQUEST_SELECTED = 1;

    private final int mode;
    private final UUID petUuid;  // only for REQUEST_SELECTED

    public RequestPetDataPacket(int mode, UUID petUuid) {
        this.mode = mode;
        this.petUuid = petUuid;
    }

    public static RequestPetDataPacket requestFullList() {
        return new RequestPetDataPacket(REQUEST_FULL_LIST, null);
    }

    public static RequestPetDataPacket requestSelected(UUID uuid) {
        return new RequestPetDataPacket(REQUEST_SELECTED, uuid);
    }

    public static void encode(RequestPetDataPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.mode);
        if (packet.mode == REQUEST_SELECTED) {
            buf.writeUUID(packet.petUuid);
        }
    }

    public static RequestPetDataPacket decode(FriendlyByteBuf buf) {
        int mode = buf.readVarInt();
        return mode == REQUEST_SELECTED
                ? requestSelected(buf.readUUID())
                : requestFullList();
    }

    public static void handle(RequestPetDataPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            Path petDir = PetIOUtil.getOwnerDir(player);

            if (packet.mode == REQUEST_FULL_LIST) {
                trulybestfriends.flushPendingPetSaves(player.getUUID());
                ListTag list = new ListTag();
                Map<UUID, CompoundTag> sentSnapshot = new HashMap<>();
                if (petDir.toFile().exists()) {
                    File[] files = petDir.toFile().listFiles((d, n) -> n.endsWith(".nbt"));
                    if (files != null) {
                        int limit = Math.max(1, Config.maxPets);
                        for (File f : files) {
                            if (list.size() >= limit) break;
                            try {
                                CompoundTag storedNbt = NbtFileIO.readCompressed(f);
                                String uuidStr = f.getName().replace(".nbt", "");
                                UUID uuid = UUID.fromString(uuidStr);
                                CompoundTag replyNbt = toClientNbt(storedNbt);
                                // Mark pets whose entity is not currently loaded in any
                                // level as "Lost" so the client can switch the glow
                                // button into delete mode.
                                replyNbt.putBoolean("Lost", !isPetLoaded(player, uuid));
                                // 注入内存中的死亡时刻（不写盘），供客户端计算复活冷却
                                trulybestfriends.injectDeathTimeIntoNbt(uuid, replyNbt);
                                CompoundTag entry = new CompoundTag();
                                entry.putUUID("UUID", uuid);
                                entry.put("NBT", replyNbt);
                                list.add(entry);
                                sentSnapshot.put(uuid, replyNbt);
                            } catch (Exception e) {
                                trulybestfriends.LOGGER.error("Failed to read pet file: {}", f, e);
                            }
                        }
                    }
                }
                PetSyncTracker.replaceFullSnapshot(player.getUUID(), sentSnapshot);
                for (SyncPetDataPacket reply : SyncPetDataPacket.fullListBatches(list)) {
                    trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
                }
            } else {
                File nbtFile = petDir.resolve(packet.petUuid + ".nbt").toFile();
                if (!nbtFile.exists()) {
                    PetSyncTracker.forgetPet(player.getUUID(), packet.petUuid);
                    SyncPetDataPacket reply = SyncPetDataPacket.delete(packet.petUuid);
                    trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
                    return;
                }

                try {
                    CompoundTag storedNbt = NbtFileIO.readCompressed(nbtFile);
                    CompoundTag liveNbt = getLoadedPetNbt(player, packet.petUuid, storedNbt);
                    CompoundTag replyNbt = liveNbt != null ? liveNbt : toClientNbt(storedNbt);
                    // Set the "Lost" flag so the client can switch the glow button
                    // into delete mode when the entity is not loaded.  Explicitly
                    // setting false when loaded clears any stale "Lost" flag from
                    // a previous response (applySyncPacket merges keys, it does
                    // not remove them).
                    replyNbt.putBoolean("Lost", liveNbt == null);
                    // 注入内存中的死亡时刻（不写盘），供客户端计算复活冷却
                    trulybestfriends.injectDeathTimeIntoNbt(packet.petUuid, replyNbt);
                    if (PetSyncTracker.shouldSendUpdate(player.getUUID(), packet.petUuid, replyNbt)) {
                        SyncPetDataPacket reply = SyncPetDataPacket.update(packet.petUuid, replyNbt);
                        trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
                    }
                } catch (Exception e) {
                    trulybestfriends.LOGGER.error("Failed to read pet file for {}: {}", packet.petUuid, e.getMessage());
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static CompoundTag getLoadedPetNbt(ServerPlayer player, UUID petUuid, CompoundTag storedNbt) {
        ServerLevel storedLevel = getStoredLevel(player, storedNbt);
        CompoundTag nbt = storedLevel != null ? getLoadedPetNbtFromLevel(player, petUuid, storedLevel, storedNbt) : null;
        if (nbt != null) return nbt;

        for (ServerLevel level : player.server.getAllLevels()) {
            if (level == storedLevel) continue;
            nbt = getLoadedPetNbtFromLevel(player, petUuid, level, storedNbt);
            if (nbt != null) return nbt;
        }
        return null;
    }

    static CompoundTag createUpdateNbt(ServerPlayer player, UUID petUuid, CompoundTag storedNbt) {
        CompoundTag liveNbt = getLoadedPetNbt(player, petUuid, storedNbt);
        CompoundTag replyNbt = liveNbt != null ? liveNbt : toClientNbt(storedNbt);
        // Explicit false clears a stale Lost flag in the client's merged cache.
        replyNbt.putBoolean("Lost", liveNbt == null);
        trulybestfriends.injectDeathTimeIntoNbt(petUuid, replyNbt);
        return replyNbt;
    }

    /** Checks whether a pet entity is currently loaded and owned by the player
     *  in any server level.  Used to set the "Lost" flag in full-list snapshots
     *  without the overhead of building the full GUI NBT. */
    private static boolean isPetLoaded(ServerPlayer player, UUID petUuid) {
        for (ServerLevel level : player.server.getAllLevels()) {
            Entity entity = level.getEntity(petUuid);
            if (entity instanceof OwnableEntity ownable
                    && player.getUUID().equals(ownable.getOwnerUUID())) {
                return true;
            }
        }
        return false;
    }

    private static ServerLevel getStoredLevel(ServerPlayer player, CompoundTag storedNbt) {
        String dimension = storedNbt.getString("Dimension");
        if (dimension.isEmpty()) return null;
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        if (location == null) return null;
        return player.server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    private static CompoundTag getLoadedPetNbtFromLevel(ServerPlayer player, UUID petUuid, ServerLevel level, CompoundTag storedNbt) {
        Entity entity = level.getEntity(petUuid);
        if (entity == null
                || !(entity instanceof OwnableEntity ownable)
                || !player.getUUID().equals(ownable.getOwnerUUID())) {
            return null;
        }

        CompoundTag nbt = toClientNbt(entity, level, storedNbt);
        preserveStoredUiFields(storedNbt, nbt);
        return nbt;
    }

    private static CompoundTag toClientNbt(Entity entity, ServerLevel level, CompoundTag storedNbt) {
        CompoundTag nbt = toClientNbt(storedNbt);
        // Prefer live CustomName over stale disk value (pet may have been renamed
        // since the last save, or disk NBT may predate tracking).
        if (entity.hasCustomName()) {
            nbt.putString("CustomName", Component.Serializer.toJson(entity.getCustomName()));
        } else {
            nbt.remove("CustomName");
        }
        nbt.putString("OwnerUUID", storedNbt.getString("OwnerUUID"));
        nbt.putString("EntityType", ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString());
        nbt.putString("Dimension", level.dimension().location().toString());

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(entity.getX()));
        pos.add(DoubleTag.valueOf(entity.getY()));
        pos.add(DoubleTag.valueOf(entity.getZ()));
        nbt.put("Pos", pos);

        if (entity instanceof LivingEntity living) {
            nbt.putFloat("Health", living.getHealth());
            nbt.putFloat("MaxHealth", (float) living.getAttributeValue(Attributes.MAX_HEALTH));
            CompoundTag maxHealth = new CompoundTag();
            maxHealth.putString("Name", "minecraft:generic.max_health");
            maxHealth.putFloat("Base", (float) living.getAttributeValue(Attributes.MAX_HEALTH));
            ListTag attributes = new ListTag();
            attributes.add(maxHealth);
            nbt.put("Attributes", attributes);
        }
        return nbt;
    }

    static CompoundTag toClientNbt(CompoundTag source) {
        CompoundTag nbt = source.copy();
        // Passenger entity trees can recursively contain complete entities and
        // are not needed to render or manage the tracked pet itself.
        nbt.remove("Passengers");
        return nbt;
    }

    private static void preserveStoredUiFields(CompoundTag storedNbt, CompoundTag nbt) {
        if (storedNbt.contains("Priority")) {
            nbt.putInt("Priority", Math.max(1, Math.min(6, storedNbt.getInt("Priority"))));
        }
        if (storedNbt.getBoolean("Recalled")) {
            nbt.putBoolean("Recalled", true);
        }
    }
}
