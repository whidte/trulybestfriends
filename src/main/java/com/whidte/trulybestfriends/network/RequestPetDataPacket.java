package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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

    private static final String[] GUI_SYNC_NBT_FIELDS = {
            "CustomName",
            "OwnerUUID",
            "EntityType",
            "Health",
            "MaxHealth",
            "Attributes",
            "Pos",
            "Dimension",
            "Recalled",
            "Priority",
            "LastReviveTime"
    };

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
                if (petDir.toFile().exists()) {
                    File[] files = petDir.toFile().listFiles((d, n) -> n.endsWith(".nbt"));
                    if (files != null) {
                        for (File f : files) {
                            try {
                                CompoundTag nbt = net.minecraft.nbt.NbtIo.readCompressed(f);
                                String uuidStr = f.getName().replace(".nbt", "");
                                UUID uuid = UUID.fromString(uuidStr);
                                CompoundTag entry = new CompoundTag();
                                entry.putUUID("UUID", uuid);
                                entry.put("NBT", nbt);
                                list.add(entry);
                            } catch (Exception e) {
                                trulybestfriends.LOGGER.error("Failed to read pet file: {}", f, e);
                            }
                        }
                    }
                }
                SyncPetDataPacket reply = SyncPetDataPacket.fullList(list);
                trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
            } else {
                File nbtFile = petDir.resolve(packet.petUuid + ".nbt").toFile();
                if (!nbtFile.exists()) {
                    SyncPetDataPacket reply = SyncPetDataPacket.delete(packet.petUuid);
                    trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
                    return;
                }

                try {
                    CompoundTag storedNbt = net.minecraft.nbt.NbtIo.readCompressed(nbtFile);
                    CompoundTag liveNbt = getLoadedPetNbt(player, packet.petUuid, storedNbt);
                    SyncPetDataPacket reply = SyncPetDataPacket.update(
                            packet.petUuid,
                            liveNbt != null ? liveNbt : toGuiNbt(storedNbt));
                    trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
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

        CompoundTag nbt = toGuiNbt(entity, level, storedNbt);
        preserveStoredUiFields(storedNbt, nbt);
        return nbt;
    }

    private static CompoundTag toGuiNbt(Entity entity, ServerLevel level, CompoundTag storedNbt) {
        CompoundTag nbt = new CompoundTag();
        copyGuiSyncFields(storedNbt, nbt);
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

    private static CompoundTag toGuiNbt(CompoundTag source) {
        CompoundTag nbt = new CompoundTag();
        copyGuiSyncFields(source, nbt);
        return nbt;
    }

    private static void copyGuiSyncFields(CompoundTag source, CompoundTag target) {
        for (String key : GUI_SYNC_NBT_FIELDS) {
            copyIfPresent(source, target, key);
        }
    }

    private static void copyIfPresent(CompoundTag source, CompoundTag target, String key) {
        if (source.contains(key)) {
            target.put(key, source.get(key).copy());
        }
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
