package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
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
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/** Server-side: teleport a recalled pet from disk NBT to the player's current position. */
public class TeleportPetToPlayerPacket {
    private final UUID petUuid;

    public TeleportPetToPlayerPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(TeleportPetToPlayerPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static TeleportPetToPlayerPacket decode(FriendlyByteBuf buf) {
        return new TeleportPetToPlayerPacket(buf.readUUID());
    }

    public static void handle(TeleportPetToPlayerPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();

            // Case 1: pet is alive in the world — teleport it directly
            Entity entity = level.getEntity(packet.petUuid);
            if (entity instanceof LivingEntity living && living.isAlive()) {
                teleportEntityToPlayer(living, player, level);
                return;
            }

            // Case 2: pet is recalled (on disk) — summon at player
            Path ownerDir = getOwnerDir(level, player.getUUID());
            File nbtFile = ownerDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) return;

            try {
                CompoundTag nbt = NbtIo.readCompressed(nbtFile);

                if (nbt.contains("Health") && nbt.getFloat("Health") <= 0) return;

                nbt.remove("Recalled");
                nbt.putString("Dimension", level.dimension().location().toString());
                NbtIo.writeCompressed(nbt, nbtFile);

                summonFromDisk(nbt, packet.petUuid, player, level);
            } catch (IOException e) {
                trulybestfriends.LOGGER.error("Failed to teleport pet to player: {}", e.getMessage());
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void teleportEntityToPlayer(LivingEntity entity, ServerPlayer player, ServerLevel level) {
        // Try safe positions near player
        float bbWidth = entity.getBbWidth();
        int radius = Math.max(1, (int) Math.ceil(bbWidth));
        for (int r = radius; r <= 6; r++) {
            for (int attempt = 0; attempt < 16; attempt++) {
                double angle = level.random.nextDouble() * Math.PI * 2;
                double x = player.getX() + Math.cos(angle) * r;
                double z = player.getZ() + Math.sin(angle) * r;
                double y = findSafeY(level, x, player.getY(), z, entity);

                entity.setPos(x, y, z);
                AABB box = entity.getBoundingBox();
                if (level.noCollision(entity, box) && !level.containsAnyLiquid(box)) {
                    entity.teleportTo(x, y, z);
                    entity.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
                    return;
                }
            }
        }
        entity.teleportTo(player.getX(), player.getY(), player.getZ());
        entity.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
    }

    private static void summonFromDisk(CompoundTag nbt, UUID petUuid, ServerPlayer player, ServerLevel level) {
        String typeKey = nbt.getString("EntityType");
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(typeKey));
        if (type == null) return;

        Entity entity = type.create(level);
        if (entity == null) return;
        entity.load(nbt);
        entity.setUUID(petUuid);

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
