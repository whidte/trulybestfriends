package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared utilities for pet I/O operations extracted from the various packet handlers.
 * Centralizes: owner directory resolution, safe-Y search, shoulder-entity manipulation,
 * and reflection caching for private shoulder methods.
 */
public final class PetIOUtil {

    private PetIOUtil() {}

    // ---- Owner directory ----

    public static Path getModDir(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("trulybestfriends");
    }

    public static Path getModDir(ServerPlayer player) {
        return player.server.getWorldPath(LevelResource.ROOT).resolve("trulybestfriends");
    }

    public static Path getModDir(Path worldPath) {
        return worldPath.resolve("trulybestfriends");
    }

    /** Resolve the per-owner pet storage directory under {world}/trulybestfriends/{ownerUuid}. */
    public static Path getOwnerDir(Path modDir, UUID playerUuid) {
        return modDir.resolve(playerUuid.toString());
    }

    public static Path getOwnerDir(ServerLevel level, UUID playerUuid) {
        return getOwnerDir(getModDir(level), playerUuid);
    }

    public static Path getOwnerDir(ServerPlayer player) {
        return getModDir(player).resolve(player.getUUID().toString());
    }

    // ---- Safe Y search ----

    /** Find a safe Y near (x, yBase, z) for the given entity, scanning upward up to 5 blocks. */
    public static double findSafeY(ServerLevel level, double x, double yBase, double z, Entity entity) {
        float hw = entity instanceof LivingEntity le ? le.getBbWidth() / 2f : 0.3f;
        float h = entity instanceof LivingEntity le ? le.getBbHeight() : 1.8f;
        return findSafeY(level, x, yBase, z, hw, h, entity);
    }

    /** Find a safe Y near (x, yBase, z) given explicit half-width and height. */
    public static double findSafeY(ServerLevel level, double x, double yBase, double z, float hw, float h) {
        return findSafeY(level, x, yBase, z, hw, h, null);
    }

    private static double findSafeY(ServerLevel level, double x, double yBase, double z, float hw, float h, Entity entity) {
        for (int dy = 0; dy <= 5; dy++) {
            double y = yBase + dy;
            AABB box = new AABB(x - hw, y, z - hw, x + hw, y + h, z + hw);
            // RevivePetPacket passes no entity (uses noCollision(box) overload);
            // other callers pass the entity (uses noCollision(entity, box) overload).
            boolean safe = (entity != null)
                    ? level.noCollision(entity, box) && !level.containsAnyLiquid(box)
                    : level.noCollision(box) && !level.containsAnyLiquid(box);
            if (safe) return y;
        }
        return yBase;
    }

    public static Vec3 findSafePositionNearPlayer(ServerLevel level, ServerPlayer player, Entity entity,
                                                   int minRadius, int maxRadius, int attemptsPerRadius) {
        float halfWidth = entity instanceof LivingEntity living ? living.getBbWidth() / 2f : 0.3f;
        float height = entity instanceof LivingEntity living ? living.getBbHeight() : 1.8f;
        return findSafePositionNearPlayer(level, player, halfWidth, height,
                minRadius, maxRadius, attemptsPerRadius, entity);
    }

    public static Vec3 findSafePositionNearPlayer(ServerLevel level, ServerPlayer player,
                                                   float halfWidth, float height,
                                                   int minRadius, int maxRadius, int attemptsPerRadius) {
        return findSafePositionNearPlayer(level, player, halfWidth, height,
                minRadius, maxRadius, attemptsPerRadius, null);
    }

    private static Vec3 findSafePositionNearPlayer(ServerLevel level, ServerPlayer player,
                                                    float halfWidth, float height,
                                                    int minRadius, int maxRadius, int attemptsPerRadius,
                                                    Entity entity) {
        for (int radius = Math.max(1, minRadius); radius <= maxRadius; radius++) {
            for (int attempt = 0; attempt < attemptsPerRadius; attempt++) {
                double angle = level.random.nextDouble() * Math.PI * 2;
                double x = player.getX() + Math.cos(angle) * radius;
                double z = player.getZ() + Math.sin(angle) * radius;
                double y = entity != null
                        ? findSafeY(level, x, player.getY(), z, entity)
                        : findSafeY(level, x, player.getY(), z, halfWidth, height);
                AABB box = new AABB(x - halfWidth, y, z - halfWidth,
                        x + halfWidth, y + height, z + halfWidth);
                boolean clear = entity != null
                        ? level.noCollision(entity, box)
                        : level.noCollision(box);
                if (clear && !level.containsAnyLiquid(box)) return new Vec3(x, y, z);
            }
        }
        return null;
    }

    public static void writePetSnapshot(File nbtFile, CompoundTag snapshot, boolean recalled) throws IOException {
        writePetSnapshot(nbtFile, snapshot, recalled, false);
    }

    public static void writePetSnapshotPreservingRecall(File nbtFile, CompoundTag snapshot) throws IOException {
        writePetSnapshot(nbtFile, snapshot, false, true);
    }

    private static void writePetSnapshot(File nbtFile, CompoundTag snapshot,
                                         boolean recalled, boolean preserveRecalled) throws IOException {
        CompoundTag oldNbt = null;
        if (nbtFile.exists()) {
            try {
                oldNbt = NbtFileIO.readCompressed(nbtFile);
            } catch (IOException ignored) {}
        }

        int priority = oldNbt != null && oldNbt.contains("Priority")
                ? Math.max(1, Math.min(6, oldNbt.getInt("Priority")))
                : 6;
        boolean recalledValue = preserveRecalled && oldNbt != null
                ? oldNbt.getBoolean("Recalled")
                : recalled;

        CompoundTag nbt = snapshot.copy();
        nbt.putInt("Priority", priority);
        if (recalledValue) nbt.putBoolean("Recalled", true);
        else nbt.remove("Recalled");
        nbt.remove("LastDeathTime");

        if (oldNbt == null || !oldNbt.equals(nbt)) {
            NbtFileIO.writeCompressed(nbt, nbtFile);
        }
    }

    // ---- Shoulder entity helpers ----

    /** Return the shoulder NBT matching petUuid, or null if not on either shoulder. */
    public static CompoundTag getShoulderEntity(ServerPlayer player, UUID petUuid) {
        return findShoulderEntity(player.getShoulderEntityLeft(), player.getShoulderEntityRight(), petUuid);
    }

    static CompoundTag findShoulderEntity(CompoundTag left, CompoundTag right, UUID petUuid) {
        if (left.hasUUID("UUID") && left.getUUID("UUID").equals(petUuid)) return left;
        if (right.hasUUID("UUID") && right.getUUID("UUID").equals(petUuid)) return right;
        return null;
    }

    /** Clear the shoulder slot (left or right) that currently holds petUuid. */
    public static void clearShoulderSlot(ServerPlayer player, UUID petUuid) {
        CompoundTag left = player.getShoulderEntityLeft();
        if (left.contains("UUID") && left.getUUID("UUID").equals(petUuid)) {
            setShoulderEntity(player, true, new CompoundTag());
            return;
        }
        setShoulderEntity(player, false, new CompoundTag());
    }

    /** Save a shoulder pet's NBT to disk under the owner's directory, marking it Recalled. */
    public static boolean saveShoulderToDisk(UUID playerUuid, CompoundTag shoulderNbt, ServerLevel level) {
        try {
            Path ownerDir = getOwnerDir(level, playerUuid);
            Files.createDirectories(ownerDir);

            CompoundTag snapshot = shoulderNbt.copy();
            UUID uuid = snapshot.getUUID("UUID");
            String typeKey = snapshot.getString("id");
            snapshot.putString("EntityType", typeKey);
            snapshot.putString("OwnerUUID", playerUuid.toString());
            snapshot.putString("Dimension", level.dimension().location().toString());
            snapshot.putBoolean("Recalled", true);

            File nbtFile = ownerDir.resolve(uuid + ".nbt").toFile();
            writePetSnapshot(nbtFile, snapshot, true);
            trulybestfriends.updatePetRecalledState(level, uuid, true);
            return true;
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to save shoulder pet: {}", e.getMessage());
            return false;
        }
    }

    // ---- Reflection cache for setShoulderEntityLeft / setShoulderEntityRight ----

    /** Sentinel Method used to mark "this side has no setter" in SHOULDER_SETTER_CACHE. */
    private static final Method NO_METHOD = sentinelMethod();

    private static Method sentinelMethod() {
        try {
            // Any readily available method; never actually invoked.
            return Object.class.getDeclaredMethod("getClass");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /** Cache key: true = left, false = right. Value: the setter Method, or NO_METHOD if absent. */
    private static final ConcurrentHashMap<Boolean, Method> SHOULDER_SETTER_CACHE = new ConcurrentHashMap<>();

    /**
     * Invoke the private setShoulderEntityLeft/Right method on the player.
     * Uses a cached Method to avoid repeated getDeclaredMethod + setAccessible calls.
     */
    public static void setShoulderEntity(ServerPlayer player, boolean left, CompoundTag tag) {
        try {
            Method method = SHOULDER_SETTER_CACHE.computeIfAbsent(left, key -> {
                String name = key ? "setShoulderEntityLeft" : "setShoulderEntityRight";
                try {
                    Method m = net.minecraft.world.entity.player.Player.class.getDeclaredMethod(name, CompoundTag.class);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException e) {
                    trulybestfriends.LOGGER.error("Shoulder setter not found: {}", name, e);
                    return NO_METHOD;
                }
            });
            if (method == NO_METHOD) return;
            method.invoke(player, tag);
        } catch (Exception e) {
            trulybestfriends.LOGGER.error("Failed to set shoulder entity: {}", e.getMessage());
        }
    }
}
