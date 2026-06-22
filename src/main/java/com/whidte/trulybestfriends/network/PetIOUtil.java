package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

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

    /** Resolve the per-owner pet storage directory under {world}/trulybestfriends/{ownerUuid}. */
    public static Path getOwnerDir(ServerLevel level, UUID playerUuid) {
        Path worldPath = level.getServer().getWorldPath(LevelResource.ROOT);
        return worldPath.resolve("trulybestfriends").resolve(playerUuid.toString());
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

    // ---- Shoulder entity helpers ----

    /** Return the shoulder NBT matching petUuid, or null if not on either shoulder. */
    public static CompoundTag getShoulderEntity(ServerPlayer player, UUID petUuid) {
        CompoundTag left = player.getShoulderEntityLeft();
        if (left.contains("UUID") && left.getUUID("UUID").equals(petUuid)) return left;
        CompoundTag right = player.getShoulderEntityRight();
        if (right.contains("UUID") && right.getUUID("UUID").equals(petUuid)) return right;
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
    public static void saveShoulderToDisk(UUID playerUuid, CompoundTag shoulderNbt, ServerLevel level) {
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
            int existingPriority = 6;
            if (nbtFile.exists()) {
                try {
                    CompoundTag oldNbt = NbtIo.readCompressed(nbtFile);
                    if (oldNbt.contains("Priority")) {
                        existingPriority = Math.max(1, Math.min(6, oldNbt.getInt("Priority")));
                    }
                } catch (IOException ignored) {}
            }
            shoulderNbt.putInt("Priority", existingPriority);
            NbtIo.writeCompressed(shoulderNbt, nbtFile);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to save shoulder pet: {}", e.getMessage());
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
