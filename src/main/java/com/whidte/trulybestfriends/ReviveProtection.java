package com.whidte.trulybestfriends;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side registry for the short absolute-damage immunity granted after revival. */
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
