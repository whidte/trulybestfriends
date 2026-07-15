package com.whidte.trulybestfriends;

import com.mojang.logging.LogUtils;
import com.whidte.trulybestfriends.network.AreaRecallPacket;
import com.whidte.trulybestfriends.network.DeletePetDataPacket;
import com.whidte.trulybestfriends.network.GlowPetPacket;
import com.whidte.trulybestfriends.network.PetIOUtil;
import com.whidte.trulybestfriends.network.NbtFileIO;
import com.whidte.trulybestfriends.network.PetSyncTracker;
import com.whidte.trulybestfriends.network.PetWarningPacket;
import com.whidte.trulybestfriends.network.PetEntitySnapshot;
import com.whidte.trulybestfriends.network.RecallPetPacket;
import com.whidte.trulybestfriends.network.RequestPetDataPacket;
import com.whidte.trulybestfriends.network.SableSubLevelSyncPacket;
import com.whidte.trulybestfriends.network.RevivePetPacket;
import com.whidte.trulybestfriends.network.SetPriorityPacket;
import com.whidte.trulybestfriends.network.SyncPetDataPacket;
import com.whidte.trulybestfriends.network.TeleportPetToPlayerPacket;
import com.whidte.trulybestfriends.network.TeleportToPetPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod(trulybestfriends.MODID)
public class trulybestfriends {
    public static final String MODID = "trulybestfriends";
    public static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private static final String PETS_INDEX_FILE = "pets_index.nbt";
    private static final String PET_STATES_KEY = "PetStates";
    private static final ResourceLocation TRULY_BEST_FRIENDS_ADVANCEMENT = ResourceLocation.fromNamespaceAndPath("minecraft", "husbandry/tame_an_animal");
    private static final int LOCAL_SYNC_CHUNK_RADIUS = 2;
    private static final Map<String, List<UUID>> indexCache = new ConcurrentHashMap<>();
    private static final Set<UUID> trackedPetUUIDs = ConcurrentHashMap.newKeySet();
    private static final Set<PendingRemoval> pendingRemovals = ConcurrentHashMap.newKeySet();
    private static final Map<ForcedChunk, Integer> forcedChunkReferences = new ConcurrentHashMap<>();
    private static final Set<ForcedChunk> chunksForcedByMod = ConcurrentHashMap.newKeySet();
    private static final Set<LocalSyncCandidate> localSyncCandidates = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, String> shoulderPetTypes = new ConcurrentHashMap<>(); // UUID -> entity type key
    private static final Map<UUID, PendingPetSave> pendingPetSaves = new ConcurrentHashMap<>();
    private static final Set<UUID> persistedDeathSnapshots = ConcurrentHashMap.newKeySet();
    private static final long PENDING_REMOVAL_TIMEOUT_TICKS = 100L;

    /** 宠物死亡时刻（内存，不持久化）。key=petUUID, value=System.currentTimeMillis()。
     *  用于复活冷却计算，避免写盘后被 syncAllPets 反复刷新导致冷却永远不结束。
     *  服务器重启后清空 → 重启前的死亡宠物无冷却，可立即复活（符合"不保存到磁盘"的设计）。 */
    private static final Map<UUID, Long> petDeathTimes = new ConcurrentHashMap<>();

    private int syncTickCounter = 0;
    private int localSyncTickCounter = 0;
    private int saveTickCounter = 0;

    private static trulybestfriends INSTANCE;

    public trulybestfriends(IEventBus modEventBus, ModContainer modContainer) {
        INSTANCE = this;
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(this);
        com.whidte.trulybestfriends.compat.CuriosCompat.register();
        modEventBus.addListener(Config::onLoad);
        modEventBus.addListener(this::registerPayloads);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClientIntegration(modEventBus);
        }
    }

    private static void registerClientIntegration(IEventBus modEventBus) {
        try {
            Class.forName("com.whidte.trulybestfriends.client.TrulyClient")
                    .getMethod("register", IEventBus.class)
                    .invoke(null, modEventBus);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Client integration could not be registered", e);
        }
    }

    /**
     * Force-track an entity as a pet for the given owner, bypassing the
     * normal taming/ownership checks.  Used by the {@code /tbf trackdragon}
     * command to add non-tameable entities (e.g. the Ender Dragon) for
     * preview/testing purposes.
     */
    public static void forceTrackPet(ServerPlayer owner, Entity pet) {
        if (!(pet.level() instanceof ServerLevel level)) return;
        INSTANCE.updatePetIndex(pet);
        INSTANCE.savePetData(owner.getUUID(), pet, level);
        flushPendingPetSaves(owner.getUUID());
    }

    /** Replace a join-time pending save with the fully restored live snapshot. */
    public static boolean persistRestoredPet(UUID ownerUUID, Entity pet, ServerLevel level) {
        return INSTANCE != null
                && INSTANCE.savePetData(ownerUUID, pet, level)
                && flushPendingPetSave(pet.getUUID());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(GlowPetPacket.TYPE, GlowPetPacket.STREAM_CODEC, GlowPetPacket::handle);
        registrar.playToServer(RecallPetPacket.TYPE, RecallPetPacket.STREAM_CODEC, RecallPetPacket::handle);
        registrar.playToServer(TeleportToPetPacket.TYPE, TeleportToPetPacket.STREAM_CODEC, TeleportToPetPacket::handle);
        registrar.playToServer(TeleportPetToPlayerPacket.TYPE, TeleportPetToPlayerPacket.STREAM_CODEC, TeleportPetToPlayerPacket::handle);
        registrar.playToServer(AreaRecallPacket.TYPE, AreaRecallPacket.STREAM_CODEC, AreaRecallPacket::handle);
        registrar.playToClient(PetWarningPacket.TYPE, PetWarningPacket.STREAM_CODEC, PetWarningPacket::handle);
        registrar.playToServer(RequestPetDataPacket.TYPE, RequestPetDataPacket.STREAM_CODEC, RequestPetDataPacket::handle);
        registrar.playToServer(RevivePetPacket.TYPE, RevivePetPacket.STREAM_CODEC, RevivePetPacket::handle);
        registrar.playToServer(SetPriorityPacket.TYPE, SetPriorityPacket.STREAM_CODEC, SetPriorityPacket::handle);
        registrar.playToClient(SableSubLevelSyncPacket.TYPE, SableSubLevelSyncPacket.STREAM_CODEC, SableSubLevelSyncPacket::handle);
        registrar.playToClient(SyncPetDataPacket.TYPE, SyncPetDataPacket.STREAM_CODEC, SyncPetDataPacket::handle);
        registrar.playToServer(DeletePetDataPacket.TYPE, DeletePetDataPacket.STREAM_CODEC, DeletePetDataPacket::handle);
    }

    @SubscribeEvent
    public void onAnimalTamed(AnimalTameEvent event) {
        Entity animal = event.getAnimal();
        if (animal.level().isClientSide()) return;
        UUID owner = getCompatOwnerUUID(animal);
        if (owner != null) {
            if (countOwnerPets((ServerLevel) animal.level(), owner) >= Config.maxPets) {
                ServerPlayer ownerPlayer = animal.level().getServer().getPlayerList().getPlayer(owner);
                if (ownerPlayer != null) {
                    ownerPlayer.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("trulybestfriends.limit.reached", Config.maxPets)
                                    .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
                return;
            }
            savePetData(owner, animal, (ServerLevel) animal.level());
            updatePetIndex(animal);
            flushPendingPetSaves(owner);
        }
    }

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (discardIfRecalled(entity, level)) return;
        UUID ownerUUID = getCompatOwnerUUID(entity);
        if (ownerUUID != null) {
            // Save tracked pets on (re)join — covers cross-dimension portal travel
            // (e.g. End portal) where the entity is recreated with the same UUID.
            // registerUntrackedOwnedPet handles first-time registration + index update.
            if (trackedPetUUIDs.contains(entity.getUUID())
                    || registerUntrackedOwnedPet(entity, ownerUUID, level)) {
                savePetData(ownerUUID, entity, level);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && Config.enableLoginLoadDiagnostics) {
            loadPlayerPetsData(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            flushPendingPetSaves(player.getUUID());
            PetSyncTracker.clearPlayer(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        loadPetIndex(event.getServer().overworld());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        flushPendingPetSaves();
        ReviveProtection.clear(event.getServer());
        petDeathTimes.clear();  // 死亡时刻仅在内存，不持久化
        persistedDeathSnapshots.clear();
        pendingRemovals.clear();
        PetSyncTracker.clearAll();
        TeleportPetToPlayerPacket.clearPendingSummons();
        chunksForcedByMod.forEach(chunk ->
                chunk.level().setChunkForced(chunk.chunkX(), chunk.chunkZ(), false));
        forcedChunkReferences.clear();
        chunksForcedByMod.clear();
        indexCache.clear();
        trackedPetUUIDs.clear();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
            if (Config.syncIntervalTicks > 0) syncTickCounter++;
            else syncTickCounter = 0;
            localSyncTickCounter++;
            saveTickCounter++;
            processLocalSyncCandidates(event.getServer());
            processPendingRemovals(event.getServer());
            TeleportPetToPlayerPacket.tickPendingSummons(event.getServer());
            ReviveProtection.tick(event.getServer());
            if (localSyncTickCounter >= Config.localSyncIntervalTicks) {
                localSyncTickCounter = 0;
                collectLocalSyncCandidates(event.getServer());
                trackShoulderPets(event.getServer());
            }
            if (Config.syncIntervalTicks > 0 && syncTickCounter >= Config.syncIntervalTicks) {
                syncTickCounter = 0;
                syncAllPets(event.getServer());
            }
            if (saveTickCounter >= Config.savePetDataCooldownTicks) {
                saveTickCounter = 0;
                flushPendingPetSaves();
            }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (!event.getEntity().level().isClientSide()
                && ReviveProtection.blocksDamage(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        if (!entity.level().isClientSide()) ReviveProtection.remove(entity.getUUID());
        if (!entity.level().isClientSide()
                && trackedPetUUIDs.contains(entity.getUUID())) {
            UUID owner = getCompatOwnerUUID(entity);
            if (owner == null) return;
            persistedDeathSnapshots.remove(entity.getUUID());
            // 记录死亡时刻到内存（不写盘），用于复活冷却计算。
            petDeathTimes.put(entity.getUUID(), System.currentTimeMillis());
            ResourceLocation entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (entityType != null && Config.isClearOnDeathEntity(entityType.toString())) {
                clearPetDataAndCache(entity, owner, (ServerLevel) entity.level());
            } else {
                if (savePetData(owner, entity, (ServerLevel) entity.level())
                        && flushPendingPetSave(entity.getUUID())) {
                    persistedDeathSnapshots.add(entity.getUUID());
                }
            }
        }
    }

    /** 获取宠物死亡时刻（内存，不持久化）。返回 null 表示无记录（未死亡或服务器重启后）。 */
    public static Long getPetDeathTime(UUID petUuid) {
        return petDeathTimes.get(petUuid);
    }

    /** 复活成功后清除死亡时刻记录。 */
    public static void clearPetDeathTime(UUID petUuid) {
        petDeathTimes.remove(petUuid);
        persistedDeathSnapshots.remove(petUuid);
    }

    /** 将内存中的死亡时刻注入到 NBT（仅用于网络同步给客户端，不写盘）。
     *  客户端读 NBT 的 LastDeathTime 字段计算冷却剩余时间。 */
    public static void injectDeathTimeIntoNbt(UUID petUuid, CompoundTag nbt) {
        Long deathTime = petDeathTimes.get(petUuid);
        if (deathTime != null) {
            nbt.putLong("LastDeathTime", deathTime);
        } else {
            nbt.remove("LastDeathTime");
        }
    }

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        Entity entity = event.getEntity();
        boolean snapshotPersisted = persistedDeathSnapshots.contains(entity.getUUID());
        ResourceLocation entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (trackedPetUUIDs.contains(entity.getUUID())
                && snapshotPersisted
                && entityType != null
                && !Config.isNoReviveEntity(entityType.toString())) {
            // Ice and Fire 联动：IDeadMob 实体（龙、独眼巨人等）死亡后变尸体长期驻留，
            // 玩家右键采集血液/鳞片/骨头。清空掉落会破坏 IaF 的采集机制。
            // 这类实体仍可复活（IafCompat 原地治愈），区别于 noReviveWhitelist
            //（那个是"保留掉落+禁止复活"，这里是"保留掉落+允许复活"）。
            if (com.whidte.trulybestfriends.compat.IafCompat.isDeadMob(entity)) {
                return;
            }
            event.getDrops().clear();
        }
    }

    private boolean savePetData(UUID ownerUUID, Entity pet, ServerLevel level) {
        if (pet instanceof LivingEntity living
                && !living.isAlive()
                && persistedDeathSnapshots.contains(pet.getUUID())) {
            return true;
        }
        if (!isKnownPlayer(level.getServer(), ownerUUID)) {
            LOGGER.warn("Skipping pet save: owner UUID {} is not a known player", ownerUUID);
            return false;
        }
        CompoundTag nbt;
        try {
            nbt = PetEntitySnapshot.capture(pet, ownerUUID, level);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to capture pet snapshot for {}: {}", pet.getUUID(), e.getMessage(), e);
            return false;
        }
        // LastDeathTime 完全不由磁盘管理——改由服务器内存 Map (petDeathTimes) 记录，
        // 在 onLivingDeath 时写入，通过网络同步注入给客户端。不写盘避免被 syncAllPets 反复刷新。
        Path worldPath = level.getServer().getWorldPath(LevelResource.ROOT);
        pendingPetSaves.put(pet.getUUID(), new PendingPetSave(
                ownerUUID,
                pet.getUUID(),
                worldPath,
                nbt));

        if (hasPetFileInOtherOwnerDir(PetIOUtil.getModDir(level), ownerUUID, pet.getUUID())) {
            flushPendingPetSaves(ownerUUID);
        }
        return true;
    }

    public static void flushPendingPetSaves() {
        flushPendingPetSaves(null);
    }

    public static void flushPendingPetSaves(UUID ownerUUID) {
        for (PendingPetSave pending : new ArrayList<>(pendingPetSaves.values())) {
            if (ownerUUID != null && !ownerUUID.equals(pending.ownerUUID())) continue;
            if (writePetData(pending)) {
                pendingPetSaves.remove(pending.petUUID(), pending);
            }
        }
    }

    private static boolean flushPendingPetSave(UUID petUUID) {
        PendingPetSave pending = pendingPetSaves.get(petUUID);
        if (pending == null || !writePetData(pending)) return false;
        pendingPetSaves.remove(petUUID, pending);
        return true;
    }

    private static boolean writePetData(PendingPetSave pending) {
        try {
            Path modDir = PetIOUtil.getModDir(pending.worldPath());
            Files.createDirectories(modDir);

            Path ownerDir = PetIOUtil.getOwnerDir(modDir, pending.ownerUUID());
            Files.createDirectories(ownerDir);

            File nbtFile = ownerDir.resolve(pending.petUUID() + ".nbt").toFile();
            if (!nbtFile.exists()) {
                Path oldOwnerFile = findPetFileInOtherOwnerDir(modDir, pending.ownerUUID(), pending.petUUID());
                if (oldOwnerFile != null) {
                    Files.move(oldOwnerFile, nbtFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.debug("Moved pet NBT {} to new owner {}", pending.petUUID(), pending.ownerUUID());
                }
            }

            PetIOUtil.writePetSnapshotPreservingRecall(nbtFile, pending.nbt());
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save pet data for {}: {}", pending.petUUID(), e.getMessage());
            return false;
        }
    }

    private record PendingPetSave(UUID ownerUUID, UUID petUUID, Path worldPath, CompoundTag nbt) {}

    private static final class PendingRemoval {
        private final UUID ownerUUID;
        private final UUID petUUID;
        private final ServerLevel level;
        private final int chunkX;
        private final int chunkZ;
        private long expiresAtTick;

        private PendingRemoval(UUID ownerUUID, UUID petUUID, ServerLevel level,
                               int chunkX, int chunkZ, long expiresAtTick) {
            this.ownerUUID = ownerUUID;
            this.petUUID = petUUID;
            this.level = level;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.expiresAtTick = expiresAtTick;
        }

        UUID ownerUUID() { return ownerUUID; }
        UUID petUUID() { return petUUID; }
        ServerLevel level() { return level; }
        int chunkX() { return chunkX; }
        int chunkZ() { return chunkZ; }
    }

    private record ForcedChunk(ServerLevel level, int chunkX, int chunkZ) {}

    private record LocalSyncCandidate(ResourceKey<Level> dimension, UUID entityUUID) {}

    private static boolean hasPetFileInOtherOwnerDir(Path modDir, UUID currentOwnerUUID, UUID petUUID) {
        try {
            return findPetFileInOtherOwnerDir(modDir, currentOwnerUUID, petUUID) != null;
        } catch (IOException e) {
            LOGGER.error("Failed to check old owner pet file for {}: {}", petUUID, e.getMessage());
            return false;
        }
    }

    public static boolean queuePendingRemoval(UUID ownerUUID, UUID petUUID, ServerLevel level, int chunkX, int chunkZ) {
        if (pendingRemovals.stream().anyMatch(pending ->
                pending.ownerUUID().equals(ownerUUID) && pending.petUUID().equals(petUUID))) {
            return true;
        }
        long ownerPending = pendingRemovals.stream()
                .filter(pending -> pending.ownerUUID().equals(ownerUUID))
                .count();
        if (ownerPending >= Config.maxPendingSummons + 2L) return false;

        pendingRemovals.add(new PendingRemoval(ownerUUID, petUUID, level, chunkX, chunkZ,
                level.getGameTime() + PENDING_REMOVAL_TIMEOUT_TICKS));
        retainForcedChunk(level, chunkX, chunkZ);
        return true;
    }

    public static boolean isPendingRemoval(UUID ownerUUID, UUID petUUID) {
        return pendingRemovals.stream().anyMatch(pending ->
                pending.ownerUUID().equals(ownerUUID) && pending.petUUID().equals(petUUID));
    }

    public static void retainForcedChunk(ServerLevel level, int chunkX, int chunkZ) {
        ForcedChunk key = new ForcedChunk(level, chunkX, chunkZ);
        forcedChunkReferences.compute(key, (ignored, references) -> {
            if (references == null) {
                if (!level.getForcedChunks().contains(ChunkPos.asLong(chunkX, chunkZ))) {
                    level.setChunkForced(chunkX, chunkZ, true);
                    chunksForcedByMod.add(key);
                }
                return 1;
            }
            return references + 1;
        });
    }

    public static void releaseForcedChunk(ServerLevel level, int chunkX, int chunkZ) {
        ForcedChunk key = new ForcedChunk(level, chunkX, chunkZ);
        forcedChunkReferences.computeIfPresent(key, (ignored, references) -> {
            if (references <= 1) {
                if (chunksForcedByMod.remove(key)) {
                    level.setChunkForced(chunkX, chunkZ, false);
                }
                return null;
            }
            return references - 1;
        });
    }

    /**
     * Clear all stored NBT data and in-memory cache for a pet.
     * Used when a clear-on-death entity dies — it should leave no trace.
     */
    private static void clearPetDataAndCache(Entity entity, UUID ownerUUID, ServerLevel level) {
        UUID petUUID = entity.getUUID();
        // Remove NBT file from disk
        Path modDir = PetIOUtil.getModDir(level);
        Path ownerDir = modDir.resolve(ownerUUID.toString());
        Path petFile = ownerDir.resolve(petUUID + ".nbt");
        try {
            Files.deleteIfExists(petFile);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete pet NBT for {}: {}", petUUID, e.getMessage());
        }
        // Clean in-memory caches
        pendingPetSaves.remove(petUUID);
        removePendingRemovals(ownerUUID, petUUID);
        trackedPetUUIDs.remove(petUUID);
        shoulderPetTypes.remove(petUUID);
        petDeathTimes.remove(petUUID);
        persistedDeathSnapshots.remove(petUUID);
        try {
            removePetFromIndex(modDir, petUUID);
        } catch (IOException e) {
            LOGGER.warn("Failed to remove pet from index for {}: {}", petUUID, e.getMessage());
        }
        LOGGER.debug("Cleared pet data and cache for {} (clear-on-death)", petUUID);
    }

    public static boolean deletePetData(ServerPlayer player, UUID petUUID) {
        try {
            flushPendingPetSaves(player.getUUID());
            Path modDir = PetIOUtil.getModDir(player);
            Path petFile = PetIOUtil.getOwnerDir(player).resolve(petUUID + ".nbt");
            if (!Files.deleteIfExists(petFile)) return false;
            pendingPetSaves.remove(petUUID);
            removePendingRemovals(player.getUUID(), petUUID);
            trackedPetUUIDs.remove(petUUID);
            petDeathTimes.remove(petUUID);
            persistedDeathSnapshots.remove(petUUID);
            removePetFromIndex(modDir, petUUID);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to delete pet data for {}: {}", petUUID, e.getMessage());
            return false;
        }
    }

    /** Check whether a pet UUID is currently tracked (in-memory). */
    public static boolean isTrackedPet(UUID petUUID) {
        return trackedPetUUIDs.contains(petUUID);
    }

    /**
     * Check whether a UUID corresponds to a player who has played on this
     * server.  A player is "known" if they are currently online or have a
     * playerdata file on disk.  This prevents saving pets whose "Owner"
     * NBT field does not correspond to a real player (e.g. mod entities
     * with non-standard ownership fields).
     */
    public static boolean isKnownPlayer(net.minecraft.server.MinecraftServer server, UUID uuid) {
        if (server.getPlayerList().getPlayer(uuid) != null) return true;
        Path playerData = server.getWorldPath(LevelResource.ROOT)
                .resolve("playerdata")
                .resolve(uuid + ".dat");
        return Files.exists(playerData);
    }

    /**
     * Resolve an owner UUID from any tameable/pet entity, even if it does
     * not implement {@link OwnableEntity}.  This covers Ice &amp; Fire dragons
     * and other mods that use NBT-based ownership.
     */
    public static UUID getCompatOwnerUUID(Entity entity) {
        // Only living entities can be pets.  This filters out projectiles
        // (thrown potions, arrows, ...) and AreaEffectCloud, which all save
        // an "Owner" UUID in their NBT representing the thrower/creator —
        // not a pet ownership relationship.
        if (!(entity instanceof LivingEntity)) return null;
        // Multipart sub-parts (e.g., Ice & Fire dragon tail/wing) are never
        // tracked directly — only the parent entity is tracked.  The parent
        // is a separate Entity with its own UUID and will be processed by
        // onEntityJoinLevel / syncAllPets on its own.  Returning null here
        // causes all tracking entry points to skip sub-parts.
        if (entity instanceof PartEntity<?>) return null;
        // Fast path: standard vanilla/Forge ownership interface
        if (entity instanceof OwnableEntity ownable) {
            return ownable.getOwnerUUID();
        }
        // Compatibility: read ownership from configured top-level NBT fields.
        CompoundTag nbt = entity.saveWithoutId(new CompoundTag());
        for (String field : Config.ownerNbtFields) {
            if (nbt.hasUUID(field)) {
                return nbt.getUUID(field);
            }
            if (nbt.contains(field, Tag.TAG_STRING)) {
                try {
                    return UUID.fromString(nbt.getString(field));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    public static boolean isOwnedBy(Entity entity, UUID ownerUUID) {
        return ownerUUID != null && ownerUUID.equals(getCompatOwnerUUID(entity));
    }

    private static void removePetFromIndex(Path modDir, UUID petUUID) throws IOException {
        File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();
        if (!indexFile.exists()) return;

        CompoundTag indexTag = NbtFileIO.readCompressed(indexFile);
        String uuidStr = petUUID.toString();
        boolean changed = false;
        for (String typeKey : new ArrayList<>(indexTag.getAllKeys())) {
            if (PET_STATES_KEY.equals(typeKey)) continue;
            ListTag uuidList = indexTag.getList(typeKey, Tag.TAG_STRING);
            for (int i = uuidList.size() - 1; i >= 0; i--) {
                if (uuidStr.equals(uuidList.getString(i))) {
                    uuidList.remove(i);
                    changed = true;
                }
            }
            if (uuidList.isEmpty()) {
                indexTag.remove(typeKey);
                indexCache.remove(typeKey);
            } else {
                indexTag.put(typeKey, uuidList);
                List<UUID> cachedList = indexCache.get(typeKey);
                if (cachedList != null) cachedList.remove(petUUID);
            }
        }
        CompoundTag petStates = indexTag.getCompound(PET_STATES_KEY);
        if (petStates.contains(uuidStr)) {
            petStates.remove(uuidStr);
            indexTag.put(PET_STATES_KEY, petStates);
            changed = true;
        }
        if (changed) NbtFileIO.writeCompressed(indexTag, indexFile);
    }

    public static void updatePetRecalledState(ServerLevel level, UUID petUUID, boolean recalled) {
        File indexFile = PetIOUtil.getModDir(level).resolve(PETS_INDEX_FILE).toFile();
        if (!indexFile.exists()) return;

        try {
            CompoundTag indexTag = NbtFileIO.readCompressed(indexFile);
            if (putPetState(indexTag, petUUID, recalled)) {
                NbtFileIO.writeCompressed(indexTag, indexFile);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to update recalled state in pet index for {}: {}", petUUID, e.getMessage());
        }
    }

    private static boolean putPetState(CompoundTag indexTag, UUID petUUID, boolean recalled) {
        CompoundTag petStates = indexTag.getCompound(PET_STATES_KEY);
        String uuid = petUUID.toString();
        CompoundTag oldState = petStates.getCompound(uuid);
        if (oldState.contains("Recalled") && oldState.getBoolean("Recalled") == recalled) return false;

        CompoundTag state = new CompoundTag();
        state.putBoolean("Recalled", recalled);
        petStates.put(uuid, state);
        indexTag.put(PET_STATES_KEY, petStates);
        return true;
    }

    private static Path findPetFileInOtherOwnerDir(Path modDir, UUID currentOwnerUUID, UUID petUUID) throws IOException {
        if (!Files.exists(modDir)) return null;

        String currentOwner = currentOwnerUUID.toString();
        String fileName = petUUID + ".nbt";
        try (var ownerDirs = Files.list(modDir)) {
            return ownerDirs
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().equals(currentOwner))
                    .map(path -> path.resolve(fileName))
                    .filter(Files::exists)
                    .findFirst()
                    .orElse(null);
        }
    }

    private void updatePetIndex(Entity pet) {
        try {
            ServerLevel level = (ServerLevel) pet.level();
            Path modDir = PetIOUtil.getModDir(level);
            File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();

            CompoundTag indexTag = new CompoundTag();
            if (indexFile.exists()) {
                indexTag = NbtFileIO.readCompressed(indexFile);
            }

            String typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).toString();
            UUID petUUID = pet.getUUID();
            String uuidStr = petUUID.toString();

            ListTag uuidList = indexTag.getList(typeKey, Tag.TAG_STRING);
            boolean alreadyExists = false;
            for (Tag tag : uuidList) {
                if (tag instanceof StringTag st && uuidStr.equals(st.getAsString())) {
                    alreadyExists = true;
                    break;
                }
            }
            if (!alreadyExists) {
                uuidList.add(StringTag.valueOf(uuidStr));
                indexTag.put(typeKey, uuidList);
                indexCache.computeIfAbsent(typeKey, k -> new ArrayList<>()).add(petUUID);
            }
            if (!alreadyExists || !indexTag.getCompound(PET_STATES_KEY).contains(uuidStr)) {
                putPetState(indexTag, petUUID, false);
                NbtFileIO.writeCompressed(indexTag, indexFile);
            }

            trackedPetUUIDs.add(petUUID);
        } catch (IOException e) {
            LOGGER.error("Failed to update pet index for {}: {}", pet.getUUID(), e.getMessage());
        }
    }

    private boolean registerUntrackedOwnedPet(Entity entity, ServerLevel level) {
        UUID ownerUUID = getCompatOwnerUUID(entity);
        return ownerUUID != null && registerUntrackedOwnedPet(entity, ownerUUID, level);
    }

    private boolean registerUntrackedOwnedPet(Entity entity, UUID ownerUUID, ServerLevel level) {
        if (trackedPetUUIDs.contains(entity.getUUID())) return false;
        if (!isKnownPlayer(level.getServer(), ownerUUID)) return false;

        ResourceLocation entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (entityType != null && Config.isAutoRegisterBlacklisted(entityType.toString())) return false;

        if (countOwnerPets(level, ownerUUID) >= Config.maxPets) return false;
        updatePetIndex(entity);
        return true;
    }

    private Optional<PendingRemoval> findPendingRemoval(UUID ownerUUID, UUID petUUID) {
        return pendingRemovals.stream()
                .filter(pending -> pending.ownerUUID().equals(ownerUUID) && pending.petUUID().equals(petUUID))
                .findFirst();
    }

    private static void removePendingRemoval(PendingRemoval pendingRemoval) {
        if (pendingRemovals.remove(pendingRemoval)) {
            releaseForcedChunk(pendingRemoval.level(), pendingRemoval.chunkX(), pendingRemoval.chunkZ());
        }
    }

    private static void removePendingRemovals(UUID ownerUUID, UUID petUUID) {
        for (PendingRemoval pending : new ArrayList<>(pendingRemovals)) {
            if (pending.ownerUUID().equals(ownerUUID) && pending.petUUID().equals(petUUID)) {
                removePendingRemoval(pending);
            }
        }
    }

    private void processPendingRemovals(MinecraftServer server) {
        for (PendingRemoval pending : new ArrayList<>(pendingRemovals)) {
            if (pending.level().getServer() != server) {
                removePendingRemoval(pending);
                continue;
            }

            Entity loaded = pending.level().getEntity(pending.petUUID());
            if (loaded != null) {
                if (isOwnedBy(loaded, pending.ownerUUID())
                        && discardIfRecalled(loaded, pending.level())) {
                    continue;
                }
                if (!pendingRemovals.contains(pending)) continue;
            }
            if (pending.level().getGameTime() < pending.expiresAtTick) continue;

            File nbtFile = PetIOUtil.getOwnerDir(pending.level(), pending.ownerUUID())
                    .resolve(pending.petUUID() + ".nbt")
                    .toFile();
            try {
                if (nbtFile.exists()) {
                    CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);
                    if (nbt.getBoolean("Recalled")) {
                        nbt.remove("Recalled");
                        NbtFileIO.writeCompressed(nbt, nbtFile);
                        updatePetRecalledState(pending.level(), pending.petUUID(), false);
                    }
                }
                removePendingRemoval(pending);
                ServerPlayer player = server.getPlayerList().getPlayer(pending.ownerUUID());
                if (player != null) {
                    PetWarningPacket.send(player, 3, pending.petUUID());
                }
            } catch (IOException e) {
                pending.expiresAtTick = pending.level().getGameTime() + PENDING_REMOVAL_TIMEOUT_TICKS;
                LOGGER.error("Failed to roll back timed-out recall for {}: {}",
                        pending.petUUID(), e.getMessage());
            }
        }
    }

    private boolean discardIfRecalled(Entity entity, ServerLevel level) {
        UUID ownerUUID = getCompatOwnerUUID(entity);
        if (ownerUUID == null) return false;

        UUID petUUID = entity.getUUID();
        Path ownerDir = PetIOUtil.getOwnerDir(level, ownerUUID);
        File nbtFile = ownerDir.resolve(petUUID + ".nbt").toFile();
        if (!nbtFile.exists()) return false;

        try {
            CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);
            Optional<PendingRemoval> pendingRemoval = findPendingRemoval(ownerUUID, petUUID);
            if (!nbt.getBoolean("Recalled")) {
                pendingRemoval.ifPresent(trulybestfriends::removePendingRemoval);
                return false;
            }
            if (!savePetData(ownerUUID, entity, level) || !flushPendingPetSave(petUUID)) {
                LOGGER.error("Keeping recalled pet {} loaded because its final snapshot could not be persisted", petUUID);
                return false;
            }
            if (!savePetData(ownerUUID, entity, level) || !flushPendingPetSave(petUUID)) {
                LOGGER.error("Keeping recalled pet {} loaded because its final snapshot could not be persisted", petUUID);
                return false;
            }
            entity.discard();
            pendingRemoval.ifPresent(trulybestfriends::removePendingRemoval);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to read recalled pet NBT for {}: {}", petUUID, e.getMessage());
            return false;
        }
    }

    public static Map<String, List<UUID>> loadPetIndex(ServerLevel level) {
        try {
            Path modDir = PetIOUtil.getModDir(level);
            File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();

            if (indexFile.exists()) {
                CompoundTag indexTag = NbtFileIO.readCompressed(indexFile);
                indexCache.clear();
                trackedPetUUIDs.clear();
                for (String key : indexTag.getAllKeys()) {
                    if (PET_STATES_KEY.equals(key)) continue;
                    ListTag uuidList = indexTag.getList(key, Tag.TAG_STRING);
                    List<UUID> uuids = new ArrayList<>();
                    for (Tag rawTag : uuidList) {
                        if (rawTag instanceof StringTag stringTag) {
                            try {
                                UUID uuid = UUID.fromString(stringTag.getAsString());
                                uuids.add(uuid);
                                trackedPetUUIDs.add(uuid);
                            } catch (IllegalArgumentException e) {
                                LOGGER.warn("Invalid UUID in pet index: {}", stringTag.getAsString());
                            }
                        }
                    }
                    indexCache.put(key, uuids);
                }
                refreshPetStatesFromDisk(indexTag, modDir);
                NbtFileIO.writeCompressed(indexTag, indexFile);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load pet index: {}", e.getMessage());
        }
        return indexCache;
    }

    private static void refreshPetStatesFromDisk(CompoundTag indexTag, Path modDir) throws IOException {
        Map<UUID, Boolean> recalledByUuid = new HashMap<>();
        if (Files.exists(modDir)) {
            try (var ownerDirs = Files.list(modDir)) {
                for (Path ownerDir : ownerDirs.filter(Files::isDirectory).toList()) {
                    try (var petFiles = Files.list(ownerDir)) {
                        for (Path petFile : petFiles.filter(path -> path.getFileName().toString().endsWith(".nbt")).toList()) {
                            String fileName = petFile.getFileName().toString();
                            try {
                                UUID petUUID = UUID.fromString(fileName.substring(0, fileName.length() - 4));
                                if (trackedPetUUIDs.contains(petUUID)) {
                                    recalledByUuid.put(petUUID,
                                            NbtFileIO.readCompressed(petFile.toFile()).getBoolean("Recalled"));
                                }
                            } catch (IllegalArgumentException | IOException e) {
                                LOGGER.warn("Failed to refresh pet index state from {}: {}", petFile, e.getMessage());
                            }
                        }
                    }
                }
            }
        }

        CompoundTag oldStates = indexTag.getCompound(PET_STATES_KEY);
        indexTag.remove(PET_STATES_KEY);
        for (UUID petUUID : trackedPetUUIDs) {
            String uuid = petUUID.toString();
            boolean recalled = recalledByUuid.getOrDefault(petUUID,
                    oldStates.getCompound(uuid).getBoolean("Recalled"));
            putPetState(indexTag, petUUID, recalled);
        }
    }

    private void syncAllPets(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                syncOwnedEntity(entity, level);
            }
        }
    }

    private void collectLocalSyncCandidates(MinecraftServer server) {
        Map<ServerLevel, Set<ChunkPos>> chunksByLevel = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!hasTrulyBestFriendsAdvancement(server, player)) continue;

            ServerLevel level = player.serverLevel();
            ChunkPos center = player.chunkPosition();
            Set<ChunkPos> chunks = chunksByLevel.computeIfAbsent(level, ignored -> new HashSet<>());
            for (int x = center.x - LOCAL_SYNC_CHUNK_RADIUS; x <= center.x + LOCAL_SYNC_CHUNK_RADIUS; x++) {
                for (int z = center.z - LOCAL_SYNC_CHUNK_RADIUS; z <= center.z + LOCAL_SYNC_CHUNK_RADIUS; z++) {
                    chunks.add(new ChunkPos(x, z));
                }
            }
        }

        for (Map.Entry<ServerLevel, Set<ChunkPos>> entry : chunksByLevel.entrySet()) {
            ServerLevel level = entry.getKey();
            ResourceKey<Level> dimension = level.dimension();
            for (ChunkPos chunk : entry.getValue()) {
                AABB area = new AABB(
                        chunk.getMinBlockX(), levelMinY(level), chunk.getMinBlockZ(),
                        chunk.getMaxBlockX() + 1, levelMaxY(level) + 1, chunk.getMaxBlockZ() + 1);
                for (Entity entity : level.getEntities(null, area)) {
                    localSyncCandidates.add(new LocalSyncCandidate(dimension, entity.getUUID()));
                }
            }
        }
    }

    private int levelMinY(ServerLevel level) {
        return level.getMinBuildHeight();
    }

    private int levelMaxY(ServerLevel level) {
        return level.getMaxBuildHeight() - 1;
    }

    private void processLocalSyncCandidates(MinecraftServer server) {
        for (LocalSyncCandidate candidate : new ArrayList<>(localSyncCandidates)) {
            localSyncCandidates.remove(candidate);
            ServerLevel level = server.getLevel(candidate.dimension());
            if (level == null) continue;
            Entity entity = level.getEntity(candidate.entityUUID());
            if (entity != null) {
                syncOwnedEntity(entity, level);
            }
        }
    }

    private void syncOwnedEntity(Entity entity, ServerLevel level) {
        UUID ownerUUID = getCompatOwnerUUID(entity);
        if (ownerUUID != null) {
            if (!trackedPetUUIDs.contains(entity.getUUID()) && !registerUntrackedOwnedPet(entity, ownerUUID, level)) return;
            savePetData(ownerUUID, entity, level);
        }
    }

    private boolean hasTrulyBestFriendsAdvancement(MinecraftServer server, ServerPlayer player) {
        AdvancementHolder advancement = server.getAdvancements().get(TRULY_BEST_FRIENDS_ADVANCEMENT);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private void trackShoulderPets(MinecraftServer server) {
        Map<UUID, String> currentShoulder = new java.util.HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (var shoulder : new CompoundTag[]{player.getShoulderEntityLeft(), player.getShoulderEntityRight()}) {
                if (shoulder.contains("UUID") && !shoulder.isEmpty()) {
                    UUID uuid = shoulder.getUUID("UUID");
                    String typeKey = shoulder.getString("id");
                    currentShoulder.put(uuid, typeKey);
                }
            }
        }

        // Detect newly dismounted shoulder pets
        for (var entry : shoulderPetTypes.entrySet()) {
            UUID oldUuid = entry.getKey();
            if (!currentShoulder.containsKey(oldUuid)) {
                // Shoulder pet dismounted - find the new entity
                String typeKey = entry.getValue();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    Entity found = findNearbyPetOfType(player, typeKey);
                    if (found != null && !trackedPetUUIDs.contains(found.getUUID())) {
                        replacePetUuidInIndex((ServerLevel) player.level(), oldUuid, found);
                        trackedPetUUIDs.remove(oldUuid);
                        trackedPetUUIDs.add(found.getUUID());
                        LOGGER.debug("Shoulder pet dismounted: {} -> {}", oldUuid, found.getUUID());
                        break;
                    }
                }
            }
        }

        // Update current shoulder state
        shoulderPetTypes.clear();
        shoulderPetTypes.putAll(currentShoulder);
    }

    private Entity findNearbyPetOfType(ServerPlayer player, String typeKey) {
        ResourceLocation rl = ResourceLocation.tryParse(typeKey);
        if (rl == null) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(rl);
        if (type == null) return null;

        for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(5))) {
            if (entity.getType() == type
                    && player.getUUID().equals(getCompatOwnerUUID(entity))) {
                return entity;
            }
        }
        return null;
    }

    private void replacePetUuidInIndex(ServerLevel level, UUID oldUuid, Entity newEntity) {
        try {
            Path modDir = PetIOUtil.getModDir(level);
            File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();
            if (!indexFile.exists()) return;

            CompoundTag indexTag = NbtFileIO.readCompressed(indexFile);
            String typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(newEntity.getType()).toString();
            String oldStr = oldUuid.toString();
            String newStr = newEntity.getUUID().toString();

            ListTag uuidList = indexTag.getList(typeKey, Tag.TAG_STRING);
            for (int i = 0; i < uuidList.size(); i++) {
                if (uuidList.getString(i).equals(oldStr)) {
                    uuidList.set(i, StringTag.valueOf(newStr));
                    indexTag.put(typeKey, uuidList);
                    CompoundTag petStates = indexTag.getCompound(PET_STATES_KEY);
                    petStates.remove(oldStr);
                    indexTag.put(PET_STATES_KEY, petStates);
                    putPetState(indexTag, newEntity.getUUID(), false);
                    NbtFileIO.writeCompressed(indexTag, indexFile);

                    // Update cache
                    List<UUID> cachedList = indexCache.get(typeKey);
                    if (cachedList != null) {
                        int idx = cachedList.indexOf(oldUuid);
                        if (idx >= 0) cachedList.set(idx, newEntity.getUUID());
                    }
                    return;
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to replace pet UUID in index: {}", e.getMessage());
        }
    }

    private void loadPlayerPetsData(ServerPlayer player) {
        try {
            Path ownerDir = PetIOUtil.getOwnerDir(player);

            if (Files.exists(ownerDir)) {
                int[] counts = new int[2]; // [0]=success, [1]=failed
                Files.list(ownerDir).filter(p -> p.toString().endsWith(".nbt")).forEach(file -> {
                    try {
                        NbtFileIO.readCompressed(file.toFile());
                        counts[0]++;
                    } catch (IOException e) {
                        counts[1]++;
                        LOGGER.error("Failed to load pet data {}: {}", file.getFileName(), e.getMessage());
                    }
                });
                if (counts[0] + counts[1] > 0) {
                    LOGGER.info("Loaded {} pet data file(s), {} failed", counts[0], counts[1]);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load player pets data: {}", e.getMessage());
        }
    }

	private int countOwnerPets(ServerLevel level, UUID ownerUUID) {
		Path ownerDir = PetIOUtil.getOwnerDir(level, ownerUUID);
		if (!Files.exists(ownerDir)) return 0;
		try {
			return (int) Files.list(ownerDir).filter(p -> p.toString().endsWith(".nbt")).count();
		} catch (IOException e) {
			return 0;
		}
	}
}
