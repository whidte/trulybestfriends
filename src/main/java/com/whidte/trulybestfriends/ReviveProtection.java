package com.whidte.trulybestfriends;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 服务端注册表，用于管理复活后授予的短暂绝对伤害免疫。 */
public final class ReviveProtection {
    private static final Map<UUID, Protection> PROTECTIONS = new ConcurrentHashMap<>();

    private ReviveProtection() {}

    public static void grant(LivingEntity entity, int durationTicks) {
        MinecraftServer server = entity.getServer();
        if (server == null || durationTicks <= 0) return;
        long expiresAtTick = server.overworld().getGameTime() + durationTicks;
        PROTECTIONS.put(entity.getUUID(), new Protection(server, expiresAtTick));
    }

    public static boolean blocksDamage(LivingEntity entity) {
        MinecraftServer server = entity.getServer();
        if (server == null) return false;
        Protection protection = PROTECTIONS.get(entity.getUUID());
        if (protection == null || protection.server() != server) return false;

        long currentTick = server.overworld().getGameTime();
        if (!isActive(currentTick, protection.expiresAtTick())) {
            PROTECTIONS.remove(entity.getUUID(), protection);
            return false;
        }
        return true;
    }

    public static void tick(MinecraftServer server) {
        // 空表时 removeIf 没有副作用；提前返回同时消除 ConcurrentHashMap
        // 容量不收缩（表永不缩小）导致的固定扫描开销与每 tick 的对象分配。
        if (PROTECTIONS.isEmpty()) return;
        long currentTick = server.overworld().getGameTime();
        PROTECTIONS.entrySet().removeIf(entry ->
                entry.getValue().server() == server
                        && !isActive(currentTick, entry.getValue().expiresAtTick()));
    }

    public static void remove(UUID entityUuid) {
        PROTECTIONS.remove(entityUuid);
    }

    public static void clear(MinecraftServer server) {
        PROTECTIONS.entrySet().removeIf(entry -> entry.getValue().server() == server);
    }

    static boolean isActive(long currentTick, long expiresAtTick) {
        return currentTick < expiresAtTick;
    }

    private record Protection(MinecraftServer server, long expiresAtTick) {}
}
