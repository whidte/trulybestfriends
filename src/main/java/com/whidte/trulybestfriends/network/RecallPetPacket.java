package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

public class RecallPetPacket {
    private final UUID petUuid;

    public RecallPetPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(RecallPetPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static RecallPetPacket decode(FriendlyByteBuf buf) {
        return new RecallPetPacket(buf.readUUID());
    }

    /** Server determines action based on actual world state, not client guess */
    public static void handle(RecallPetPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();

            Entity entity = level.getEntity(packet.petUuid);

            if (entity instanceof LivingEntity living && (Config.recallRange < 0 || entity.distanceTo(player) <= Config.recallRange)) {
                // RECALL: pet is alive in world → save and remove
                savePetToDisk(player.getUUID(), living, level);
                living.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
                living.discard();
                return;
            }

            // Entity exists but out of range or not living → do nothing
            if (entity != null) return;

            // Entity not in world: check shoulder
            var shoulderNbt = getShoulderEntity(player, packet.petUuid);
            if (shoulderNbt != null) {
                saveShoulderToDisk(player.getUUID(), shoulderNbt, level);
                clearShoulderSlot(player, packet.petUuid);
                player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
                return;
            }

            // Pet not in world and not on shoulder: check disk NBT
            Path ownerDir = getOwnerDir(level, player.getUUID());
            File nbtFile = ownerDir.resolve(packet.petUuid + ".nbt").toFile();
            if (nbtFile.exists()) {
                try {
                    CompoundTag nbt = net.minecraft.nbt.NbtIo.readCompressed(nbtFile);
                    if (nbt.getBoolean("Recalled")) {
                        // SUMMON: only if pet is not dead
                        if (nbt.contains("Health") && nbt.getFloat("Health") <= 0) {
                            nbt.remove("Recalled"); // clear stale flag
                            net.minecraft.nbt.NbtIo.writeCompressed(nbt, nbtFile);
                            return;
                        }
                        nbt.remove("Recalled");
                        net.minecraft.nbt.NbtIo.writeCompressed(nbt, nbtFile);
                        summonPet(player, packet.petUuid, level, nbt);
                        player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
                    } else {
                        // Pet not loaded: only mark recalled if alive AND last known pos is within range
                        if (nbt.contains("Health") && nbt.getFloat("Health") <= 0) return;

                        // Check last known dimension
                        if (nbt.contains("Dimension")) {
                            String dim = nbt.getString("Dimension");
                            if (!dim.equals(level.dimension().location().toString())) return;
                        }

                        // Check last known position distance
                        if (nbt.contains("Pos")) {
                            var posList = nbt.getList("Pos", 6);
                            if (posList.size() >= 3) {
                                double dx = posList.getDouble(0) - player.getX();
                                double dy = posList.getDouble(1) - player.getY();
                                double dz = posList.getDouble(2) - player.getZ();
                                if (Config.recallRange >= 0 && Math.sqrt(dx * dx + dy * dy + dz * dz) > Config.recallRange) return;
                            }
                        }

                        nbt.putBoolean("Recalled", true);
                        net.minecraft.nbt.NbtIo.writeCompressed(nbt, nbtFile);
                        player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
                    }
                } catch (IOException e) {
                    trulybestfriends.LOGGER.error("Failed to read pet NBT for toggle: {}", e.getMessage());
                }
            }
            // else: no disk NBT → never tracked, do nothing
        });
        ctx.get().setPacketHandled(true);
    }

    private static CompoundTag getShoulderEntity(ServerPlayer player, UUID petUuid) {
        CompoundTag left = player.getShoulderEntityLeft();
        if (left.contains("UUID") && left.getUUID("UUID").equals(petUuid)) {
            return left;
        }
        CompoundTag right = player.getShoulderEntityRight();
        if (right.contains("UUID") && right.getUUID("UUID").equals(petUuid)) {
            return right;
        }
        return null;
    }

    private static void clearShoulderSlot(ServerPlayer player, UUID petUuid) {
        CompoundTag left = player.getShoulderEntityLeft();
        if (left.contains("UUID") && left.getUUID("UUID").equals(petUuid)) {
            setShoulderEntity(player, true, new CompoundTag());
            return;
        }
        setShoulderEntity(player, false, new CompoundTag());
    }

    private static void setShoulderEntity(ServerPlayer player, boolean left, CompoundTag tag) {
        try {
            String name = left ? "setShoulderEntityLeft" : "setShoulderEntityRight";
            var method = net.minecraft.world.entity.player.Player.class.getDeclaredMethod(name, CompoundTag.class);
            method.setAccessible(true);
            method.invoke(player, tag);
        } catch (Exception e) {
            trulybestfriends.LOGGER.error("Failed to clear shoulder entity: {}", e.getMessage());
        }
    }

    private static void saveShoulderToDisk(UUID playerUuid, CompoundTag shoulderNbt, ServerLevel level) {
        try {
            Path ownerDir = getOwnerDir(level, playerUuid);
            Files.createDirectories(ownerDir);

            UUID uuid = shoulderNbt.getUUID("UUID");
            String typeKey = shoulderNbt.getString("id");
            shoulderNbt.putString("EntityType", typeKey);
            shoulderNbt.putString("OwnerUUID", playerUuid.toString());
            shoulderNbt.putString("Dimension", level.dimension().location().toString());
            shoulderNbt.putBoolean("Recalled", true);

            File nbtFile = ownerDir.resolve(uuid + ".nbt").toFile();
            // Preserve Priority from existing file, default 6
            int existingPriority = 6;
            if (nbtFile.exists()) {
                try {
                    CompoundTag oldNbt = net.minecraft.nbt.NbtIo.readCompressed(nbtFile);
                    if (oldNbt.contains("Priority")) {
                        existingPriority = Math.max(1, Math.min(6, oldNbt.getInt("Priority")));
                    }
                } catch (IOException ignored) {}
            }
            shoulderNbt.putInt("Priority", existingPriority);

            net.minecraft.nbt.NbtIo.writeCompressed(shoulderNbt, nbtFile);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to save shoulder pet: {}", e.getMessage());
        }
    }

    private static void savePetToDisk(UUID playerUuid, LivingEntity pet, ServerLevel level) {
        try {
            Path ownerDir = getOwnerDir(level, playerUuid);
            Files.createDirectories(ownerDir);

            File nbtFile = ownerDir.resolve(pet.getUUID() + ".nbt").toFile();

            // Preserve Priority from existing file
            int existingPriority = 6;
            if (nbtFile.exists()) {
                try {
                    CompoundTag oldNbt = net.minecraft.nbt.NbtIo.readCompressed(nbtFile);
                    if (oldNbt.contains("Priority")) {
                        existingPriority = Math.max(1, Math.min(6, oldNbt.getInt("Priority")));
                    }
                } catch (IOException ignored) {}
            }

            CompoundTag nbt = new CompoundTag();
            pet.saveWithoutId(nbt);
            nbt.putString("OwnerUUID", playerUuid.toString());
            nbt.putString("EntityType", ForgeRegistries.ENTITY_TYPES.getKey(pet.getType()).toString());
            nbt.putString("Dimension", level.dimension().location().toString());
            nbt.putInt("Priority", existingPriority);
            nbt.putBoolean("Recalled", true);

            net.minecraft.nbt.NbtIo.writeCompressed(nbt, nbtFile);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to save pet: {}", e.getMessage());
        }
    }

    private static void summonPet(ServerPlayer player, UUID petUuid, ServerLevel level, CompoundTag nbt) {
        String typeKey = nbt.getString("EntityType");
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(typeKey));
        if (type == null) return;

        Entity entity = type.create(level);
        if (entity == null) return;
        entity.load(nbt);
        entity.setUUID(petUuid);

        // Find safe position near player (wolf-style teleport)
        float bbWidth = entity instanceof LivingEntity le ? le.getBbWidth() : 0.6f;
        int radius = Math.max(1, (int) Math.ceil(bbWidth));
        for (int r = radius; r <= 6; r++) {
            for (int attempt = 0; attempt < 16; attempt++) {
                double angle = level.random.nextDouble() * Math.PI * 2;
                double dx = Math.cos(angle) * r;
                double dz = Math.sin(angle) * r;
                double x = player.getX() + dx;
                double z = player.getZ() + dz;
                double y = findSafeY(level, x, player.getY(), z, entity);

                entity.setPos(x, y, z);
                AABB box = entity.getBoundingBox();
                if (level.noCollision(entity, box) && !level.containsAnyLiquid(box)) {
                    level.addFreshEntity(entity);
                    if (entity instanceof LivingEntity le) {
                        le.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
                    }
                    return;
                }
            }
        }
        // Fallback: spawn right at player's feet
        entity.setPos(player.getX(), player.getY(), player.getZ());
        level.addFreshEntity(entity);
    }

    private static double findSafeY(ServerLevel level, double x, double yBase, double z, Entity entity) {
        float hw = entity instanceof LivingEntity le ? le.getBbWidth() / 2f : 0.3f;
        float h = entity instanceof LivingEntity le ? le.getBbHeight() : 1.8f;
        for (int dy = 0; dy <= 5; dy++) {
            double y = yBase + dy;
            AABB box = new AABB(x - hw, y, z - hw, x + hw, y + h, z + hw);
            if (level.noCollision(entity, box) && !level.containsAnyLiquid(box)) {
                return y;
            }
        }
        return yBase;
    }

    private static Path getOwnerDir(ServerLevel level, UUID playerUuid) {
        Path worldPath = level.getServer().getWorldPath(LevelResource.ROOT);
        return worldPath.resolve("trulybestfriends").resolve(playerUuid.toString());
    }
}
