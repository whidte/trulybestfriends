package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class RecallPetPacket implements CustomPacketPayload {
    public static final Type<RecallPetPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "recall_pet"));
    public static final StreamCodec<FriendlyByteBuf, RecallPetPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), RecallPetPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
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

    /** Server determines action based on actual world state, not client guess.
     *  Mirrors TeleportPetToPlayerPacket's strict entity-existence checks:
     *  searches the pet's stored dimension before falling back to chunk force-load. */
    public static void handle(RecallPetPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;
            ServerLevel playerLevel = player.serverLevel();

            // --- Case 1: pet is alive in player's current dimension ---
            Entity entity = playerLevel.getEntity(packet.petUuid);

            // Multipart sub-parts (e.g., dragon tail) are never tracked
            // directly — refuse to recall them to avoid discarding a part
            // without its parent, which would corrupt the multipart entity.
            if (entity instanceof PartEntity<?>) return;

            if (entity instanceof LivingEntity living && living.isAlive()) {
                // If the entity is no longer tracked, its data was already cleared
                // (e.g. clearOnDeath whitelist, manual delete). Refuse recall to
                // prevent recreating a pet that should be gone.
                if (!trulybestfriends.isTrackedPet(packet.petUuid)
                        || !trulybestfriends.isOwnedBy(living, player.getUUID())) return;

                if (Config.recallRange < 0 || entity.distanceTo(player) <= Config.recallRange) {
                    // RECALL: pet is alive in world → save and remove
                    // Force dismount: eject all passengers and dismount the pet from its vehicle
                    // before saving, otherwise the saved NBT / passenger references become stale.
                    living.ejectPassengers();
                    living.stopRiding();
                    if (savePetToDisk(player.getUUID(), living, playerLevel)) {
                        living.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
                        living.discard();
                    }
                }
                return;
            }

            // Entity exists in current dim but is not a living/alive entity → do nothing
            if (entity != null) return;

            // --- Not in player's current dimension: check shoulder, then disk ---
            var shoulderNbt = PetIOUtil.getShoulderEntity(player, packet.petUuid);
            if (shoulderNbt != null) {
                trulybestfriends.flushPendingPetSaves(player.getUUID());
                if (PetIOUtil.saveShoulderToDisk(player.getUUID(), shoulderNbt, playerLevel)) {
                    PetIOUtil.clearShoulderSlot(player, packet.petUuid);
                    player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
                }
                return;
            }

            // --- Check disk NBT ---
            Path ownerDir = PetIOUtil.getOwnerDir(player);
            File nbtFile = ownerDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) return;  // never tracked, do nothing

            CompoundTag nbt;
            try {
                nbt = NbtFileIO.readCompressed(nbtFile);
            } catch (IOException e) {
                trulybestfriends.LOGGER.error("Failed to read pet NBT for recall: {}", e.getMessage());
                return;
            }

            // --- SUMMON path: pet was recalled to disk → release back into world ---
            if (nbt.getBoolean("Recalled")) {
                if (trulybestfriends.isPendingRemoval(player.getUUID(), packet.petUuid)) {
                    PetWarningPacket.send(player, 2, packet.petUuid);
                    return;
                }
                CompoundTag recalledSnapshot = nbt.copy();
                try {
                    if (PetDeathState.isDeadSnapshot(nbt)) {
                        // Dead pet: just clear the stale Recalled flag, don't summon a corpse
                        nbt.remove("Recalled");
                        NbtFileIO.writeCompressed(nbt, nbtFile);
                        trulybestfriends.updatePetRecalledState(playerLevel, packet.petUuid, false);
                        return;
                    }
                    nbt.remove("Recalled");
                    NbtFileIO.writeCompressed(nbt, nbtFile);
                    trulybestfriends.updatePetRecalledState(playerLevel, packet.petUuid, false);
                } catch (IOException e) {
                    trulybestfriends.LOGGER.error("Failed to clear Recalled flag for {}: {}", packet.petUuid, e.getMessage());
                    return;
                }
                if (summonPet(player, packet.petUuid, playerLevel, nbt)) {
                    player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                            net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
                } else {
                    try {
                        NbtFileIO.writeCompressed(recalledSnapshot, nbtFile);
                        trulybestfriends.updatePetRecalledState(playerLevel, packet.petUuid, true);
                    } catch (IOException rollbackError) {
                        trulybestfriends.LOGGER.error("Failed to roll back recalled state for {}: {}",
                                packet.petUuid, rollbackError.getMessage(), rollbackError);
                    }
                    PetWarningPacket.send(player, 3, packet.petUuid);
                }
                return;
            }

            // --- RECALL path: pet is supposedly alive somewhere (not Recalled on disk) ---
            // Dead pets cannot be recalled (use revive instead)
            if (PetDeathState.isDeadSnapshot(nbt)) return;

            // Resolve the pet's last known dimension from NBT
            ServerLevel petLevel = playerLevel;
            if (nbt.contains("Dimension")) {
                String dim = nbt.getString("Dimension");
                ResourceLocation dimRl = ResourceLocation.tryParse(dim);
                if (dimRl != null) {
                    var dimKey = net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, dimRl);
                    ServerLevel resolved = player.server.getLevel(dimKey);
                    if (resolved != null) petLevel = resolved;
                }
            }

            // --- Case 2: pet is alive in its stored dimension (same or different from player) ---
            Entity petEntity = petLevel.getEntity(packet.petUuid);
            if (petEntity instanceof LivingEntity living && living.isAlive()) {
                if (!trulybestfriends.isTrackedPet(packet.petUuid)
                        || !trulybestfriends.isOwnedBy(living, player.getUUID())) return;

                // Range check: same dimension uses real distance; cross-dimension
                // allows recall (player explicitly chose to recall from another dimension)
                if (Config.recallRange >= 0 && petLevel == playerLevel) {
                    if (living.distanceTo(player) > Config.recallRange) return;
                }

                living.ejectPassengers();
                living.stopRiding();
                if (savePetToDisk(player.getUUID(), living, petLevel)) {
                    living.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
                    living.discard();
                }
                return;
            }

            // --- Case 3: pet not found in stored dimension → check chunk load status ---
            int cx = Integer.MIN_VALUE;
            int cz = Integer.MIN_VALUE;
            if (nbt.contains("ChunkX", 99) && nbt.contains("ChunkZ", 99)) {
                cx = nbt.getInt("ChunkX");
                cz = nbt.getInt("ChunkZ");
            } else if (nbt.contains("Pos", 9)) {
                var posList = nbt.getList("Pos", 6);
                if (posList.size() >= 3) {
                    cx = net.minecraft.util.Mth.floor(posList.getDouble(0)) >> 4;
                    cz = net.minecraft.util.Mth.floor(posList.getDouble(2)) >> 4;
                }
            }
            if (cx == Integer.MIN_VALUE || cz == Integer.MIN_VALUE) return;  // no position info

            // If the chunk IS loaded but the entity wasn't found, the pet truly
            // doesn't exist (was removed/died). Warn the player and keep the disk
            // entry intact — player can use the delete mode to clean it up manually.
            if (petLevel.hasChunk(cx, cz)) {
                trulybestfriends.LOGGER.debug("Recall: pet {} not found in loaded chunk {},{}", packet.petUuid, cx, cz);
                PetWarningPacket.send(player, 3, packet.petUuid);
                return;
            }

            // --- Case 4: chunk is unloaded → force-load and queue removal ---
            // Range check using NBT Pos (the only position info we have)
            if (Config.recallRange >= 0 && nbt.contains("Pos")) {
                var posList = nbt.getList("Pos", 6);
                if (posList.size() >= 3) {
                    double dx = posList.getDouble(0) - player.getX();
                    double dy = posList.getDouble(1) - player.getY();
                    double dz = posList.getDouble(2) - player.getZ();
                    if (Math.sqrt(dx * dx + dy * dy + dz * dz) > Config.recallRange) return;
                }
            }

            trulybestfriends.flushPendingPetSaves(player.getUUID());
            nbt.putBoolean("Recalled", true);
            try {
                NbtFileIO.writeCompressed(nbt, nbtFile);
                trulybestfriends.updatePetRecalledState(petLevel, packet.petUuid, true);
            } catch (IOException e) {
                trulybestfriends.LOGGER.error("Failed to write Recalled flag for {}: {}", packet.petUuid, e.getMessage());
                return;
            }

            if (!trulybestfriends.queuePendingRemoval(player.getUUID(), packet.petUuid, petLevel, cx, cz)) {
                nbt.remove("Recalled");
                try {
                    NbtFileIO.writeCompressed(nbt, nbtFile);
                    trulybestfriends.updatePetRecalledState(petLevel, packet.petUuid, false);
                } catch (IOException rollbackError) {
                    trulybestfriends.LOGGER.error("Failed to roll back queued recall for {}: {}",
                            packet.petUuid, rollbackError.getMessage(), rollbackError);
                }
                PetWarningPacket.send(player, 2, packet.petUuid);
                return;
            }

            player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
        });

    }

    static boolean savePetToDisk(UUID playerUuid, LivingEntity pet, ServerLevel level) {
        return savePetToDisk(playerUuid, pet, level, true);
    }

    static boolean savePetToDisk(UUID playerUuid, LivingEntity pet, ServerLevel level, boolean recalled) {
        try {
            Path ownerDir = PetIOUtil.getOwnerDir(level, playerUuid);
            Files.createDirectories(ownerDir);

            File nbtFile = ownerDir.resolve(pet.getUUID() + ".nbt").toFile();

            CompoundTag nbt = PetEntitySnapshot.capture(pet, playerUuid, level);
            PetIOUtil.writePetSnapshot(nbtFile, nbt, recalled);
            trulybestfriends.updatePetRecalledState(level, pet.getUUID(), recalled);
            return true;
        } catch (IOException | RuntimeException e) {
            trulybestfriends.LOGGER.error("Failed to save pet {}: {}", pet.getUUID(), e.getMessage(), e);
            return false;
        }
    }

    private static boolean summonPet(ServerPlayer player, UUID petUuid, ServerLevel level, CompoundTag nbt) {
        return TeleportPetToPlayerPacket.summonFromDisk(nbt, petUuid, player, level);
    }
}
