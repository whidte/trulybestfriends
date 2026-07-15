package com.whidte.trulybestfriends.compat;

import com.whidte.trulybestfriends.trulybestfriends;
import com.whidte.trulybestfriends.network.PetIOUtil;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Optional FTB Teams alliance bridge for entities tracked by this mod. */
public final class FtbTeamsCompat {
    private static final boolean LOADED = ModList.get().isLoaded("ftbteams");
    private static final Map<OwnerCacheKey, UUID> STORED_OWNERS = new ConcurrentHashMap<>();
    private static final Set<Path> LOADED_WORLDS = ConcurrentHashMap.newKeySet();

    private FtbTeamsCompat() {}

    public static boolean areAllied(Entity first, Entity second) {
        if (!LOADED || second == null || first.level().isClientSide()) return false;

        UUID firstPetOwner = recordedPetOwner(first);
        UUID secondPetOwner = recordedPetOwner(second);
        if (firstPetOwner == null && secondPetOwner == null) return false;

        UUID firstOwner = allianceIdentity(first, firstPetOwner);
        UUID secondOwner = allianceIdentity(second, secondPetOwner);
        if (firstOwner == null || secondOwner == null) return false;
        if (firstOwner.equals(secondOwner)) return true;
        return Loaded.arePlayersAllied(firstOwner, secondOwner);
    }

    private static UUID allianceIdentity(Entity entity, UUID recordedOwner) {
        if (entity instanceof Player) return entity.getUUID();
        return recordedOwner;
    }

    private static UUID recordedPetOwner(Entity entity) {
        if (entity instanceof Player) return null;

        if (trulybestfriends.isTrackedPet(entity.getUUID())) {
            UUID liveOwner = trulybestfriends.getCompatOwnerUUID(entity);
            return liveOwner != null ? liveOwner : findStoredOwner(entity);
        }

        UUID storedOwner = findStoredOwner(entity);
        if (storedOwner == null) return null;
        UUID liveOwner = trulybestfriends.getCompatOwnerUUID(entity);
        return liveOwner != null ? liveOwner : storedOwner;
    }

    private static UUID findStoredOwner(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return null;
        Path modDir = PetIOUtil.getModDir(level).toAbsolutePath().normalize();
        UUID petUUID = entity.getUUID();
        loadWorldOwners(modDir);

        OwnerCacheKey cacheKey = new OwnerCacheKey(modDir, petUUID);
        UUID cachedOwner = STORED_OWNERS.get(cacheKey);
        if (cachedOwner != null && Files.isRegularFile(petFile(modDir, cachedOwner, petUUID))) {
            return cachedOwner;
        }
        STORED_OWNERS.remove(cacheKey);

        if (cachedOwner == null && !trulybestfriends.isTrackedPet(petUUID)) return null;
        return scanStoredOwner(modDir, petUUID, cacheKey);
    }

    private static void loadWorldOwners(Path modDir) {
        if (!LOADED_WORLDS.add(modDir) || !Files.isDirectory(modDir)) return;

        try (var ownerDirs = Files.list(modDir)) {
            for (Path ownerDir : ownerDirs.filter(Files::isDirectory).toList()) {
                UUID ownerUUID;
                try {
                    ownerUUID = UUID.fromString(ownerDir.getFileName().toString());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                try (var petFiles = Files.list(ownerDir)) {
                    for (Path path : petFiles.filter(Files::isRegularFile).toList()) {
                        String fileName = path.getFileName().toString();
                        if (!fileName.endsWith(".nbt")) continue;
                        try {
                            UUID petUUID = UUID.fromString(fileName.substring(0, fileName.length() - 4));
                            STORED_OWNERS.put(new OwnerCacheKey(modDir, petUUID), ownerUUID);
                        } catch (IllegalArgumentException ignored) {}
                    }
                } catch (IOException e) {
                    trulybestfriends.LOGGER.warn("Failed to scan FTB Teams pet owners in {}: {}",
                            ownerDir, e.getMessage());
                }
            }
        } catch (IOException e) {
            trulybestfriends.LOGGER.warn("Failed to load FTB Teams pet owner cache from {}: {}",
                    modDir, e.getMessage());
        }
    }

    private static UUID scanStoredOwner(Path modDir, UUID petUUID, OwnerCacheKey cacheKey) {
        if (!Files.isDirectory(modDir)) return null;
        try (var ownerDirs = Files.list(modDir)) {
            for (Path ownerDir : ownerDirs.filter(Files::isDirectory).toList()) {
                UUID ownerUUID;
                try {
                    ownerUUID = UUID.fromString(ownerDir.getFileName().toString());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (!Files.isRegularFile(petFile(modDir, ownerUUID, petUUID))) continue;
                STORED_OWNERS.put(cacheKey, ownerUUID);
                return ownerUUID;
            }
        } catch (IOException e) {
            trulybestfriends.LOGGER.warn("Failed to resolve stored owner for tracked entity {}: {}",
                    petUUID, e.getMessage());
        }
        return null;
    }

    private static Path petFile(Path modDir, UUID ownerUUID, UUID petUUID) {
        return PetIOUtil.getOwnerDir(modDir, ownerUUID).resolve(petUUID + ".nbt");
    }

    private static final class Loaded {
        private static boolean arePlayersAllied(UUID first, UUID second) {
            FTBTeamsAPI.API api = FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) return false;
            var manager = api.getManager();
            return manager.getTeamForPlayerID(first)
                    .map(team -> team.getRankForPlayer(second).isAllyOrBetter())
                    .orElse(false)
                    || manager.getTeamForPlayerID(second)
                    .map(team -> team.getRankForPlayer(first).isAllyOrBetter())
                    .orElse(false);
        }
    }

    private record OwnerCacheKey(Path modDir, UUID petUUID) {}
}
