package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.network.NetworkEvent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/** Client -> Server: recall all tracked pets within a given radius of the player. */
public class AreaRecallPacket {
    private final int range;

    public AreaRecallPacket(int range) {
        this.range = range;
    }

    public static void encode(AreaRecallPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.range);
    }

    public static AreaRecallPacket decode(FriendlyByteBuf buf) {
        return new AreaRecallPacket(buf.readVarInt());
    }

    public static void handle(AreaRecallPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            int range = net.minecraft.util.Mth.clamp(packet.range, 1, 16);
            int recalled = 0;

            Path ownerDir = PetIOUtil.getOwnerDir(player);
            if (!Files.exists(ownerDir)) {
                ctx.get().setPacketHandled(true);
                return;
            }

            File[] files = ownerDir.toFile().listFiles((f, n) -> n.endsWith(".nbt"));
            if (files == null) {
                ctx.get().setPacketHandled(true);
                return;
            }

            for (File nbtFile : files) {
                try {
                    UUID petUuid = UUID.fromString(nbtFile.getName().replace(".nbt", ""));

                    // Optimization: check if entity is loaded in the world FIRST,
                    // before reading the NBT file. Loaded entities can be filtered
                    // by their live state (health, distance) without disk I/O.
                    // Only unloaded pets require reading the file for Pos/Dimension.
                    Entity entity = level.getEntity(petUuid);
                    // Multipart sub-parts (e.g., dragon tail) are never tracked
                    // directly — skip them to avoid discarding a part without
                    // its parent, which would corrupt the multipart entity.
                    if (entity instanceof PartEntity<?>) continue;
                    if (entity instanceof LivingEntity living) {
                        // Skip untracked entities (data already cleared, e.g. clearOnDeath)
                        if (!trulybestfriends.isTrackedPet(petUuid)
                                || !trulybestfriends.isOwnedBy(living, player.getUUID())) continue;
                        // In world: use live state, skip dead pets
                        if (living.getHealth() <= 0) continue;
                        // In world: check range
                        if (living.distanceTo(player) <= range) {
                            // Force dismount before discarding to avoid stale passenger refs
                            living.ejectPassengers();
                            living.stopRiding();
                            if (RecallPetPacket.savePetToDisk(player.getUUID(), living, level)) {
                                living.discard();
                                recalled++;
                            }
                        }
                        continue;
                    }

                    // Entity not in world: must read NBT to check state and position
                    CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);

                    // Skip dead pets
                    if (nbt.contains("Health") && nbt.getFloat("Health") <= 0) continue;
                    // Skip already recalled pets
                    if (nbt.getBoolean("Recalled")) continue;

                    // Entity not in world: check shoulder
                    CompoundTag shoulderNbt = PetIOUtil.getShoulderEntity(player, petUuid);
                    if (shoulderNbt != null) {
                        trulybestfriends.flushPendingPetSaves(player.getUUID());
                        if (PetIOUtil.saveShoulderToDisk(player.getUUID(), shoulderNbt, level)) {
                            PetIOUtil.clearShoulderSlot(player, petUuid);
                            recalled++;
                        }
                        continue;
                    }

                    // Check NBT last known position
                    if (nbt.contains("Dimension") && nbt.contains("Pos")) {
                        String dim = nbt.getString("Dimension");
                        if (!dim.equals(level.dimension().location().toString())) continue;

                        var posList = nbt.getList("Pos", 6);
                        if (posList.size() >= 3) {
                            double dx = posList.getDouble(0) - player.getX();
                            double dy = posList.getDouble(1) - player.getY();
                            double dz = posList.getDouble(2) - player.getZ();
                            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= range) {
                                int chunkX = net.minecraft.util.Mth.floor(posList.getDouble(0)) >> 4;
                                int chunkZ = net.minecraft.util.Mth.floor(posList.getDouble(2)) >> 4;
                                nbt.putBoolean("Recalled", true);
                                NbtFileIO.writeCompressed(nbt, nbtFile);
                                if (trulybestfriends.queuePendingRemoval(
                                        player.getUUID(), petUuid, level, chunkX, chunkZ)) {
                                    recalled++;
                                } else {
                                    nbt.remove("Recalled");
                                    NbtFileIO.writeCompressed(nbt, nbtFile);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    trulybestfriends.LOGGER.error("AreaRecall: failed to process {}: {}", nbtFile.getName(), e.getMessage());
                }
            }

            if (recalled > 0) {
                player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
