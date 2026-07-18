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
import com.whidte.trulybestfriends.network.PetDeathState;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
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
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
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
    private static final String BLACKLISTED_UUIDS_KEY = "TBF_BlacklistedUUIDs";
    private static final ResourceLocation TRULY_BEST_FRIENDS_ADVANCEMENT = ResourceLocation.fromNamespaceAndPath("minecraft", "husbandry/tame_an_animal");
    private static final int LOCAL_SYNC_CHUNK_RADIUS = 2;
    private static final Map<String, List<UUID>> indexCache = new ConcurrentHashMap<>();
    private static final Set<UUID> trackedPetUUIDs = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> blacklistedPetUUIDs = ConcurrentHashMap.newKeySet();
    private static final Set<PendingRemoval> pendingRemovals = ConcurrentHashMap.newKeySet();
    private static final Map<ForcedChunk, Integer> forcedChunkReferences = new ConcurrentHashMap<>();
    private static final Set<ForcedChunk> chunksForcedByMod = ConcurrentHashMap.newKeySet();
    private static final Set<LocalSyncCandidate> localSyncCandidates = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, PendingPetSave> pendingPetSaves = new ConcurrentHashMap<>();
    private static volatile boolean petIndexLoaded;
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

    /** 判断指定 UUID 当前是否在读取黑名单中。 */
    public static boolean isPetUUIDBlacklisted(ServerLevel level, UUID petUUID) {
        loadPetIndex(level);
        return blacklistedPetUUIDs.contains(petUUID);
    }

    /**
     * 从读取黑名单中移除指定 UUID 并写回索引文件。
     * 返回 true 表示该 UUID 之前确实在黑名单中并已被移除，
     * 返回 false 表示本来就不在黑名单（无需操作）或写盘失败。
     */
    public static boolean unblacklistPetUUID(ServerLevel level, UUID petUUID) {
        if (!isPetUUIDBlacklisted(level, petUUID)) return false;
        try {
            Path modDir = PetIOUtil.getModDir(level);
            File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();
            if (indexFile.exists()) {
                CompoundTag indexTag = NbtFileIO.readCompressed(indexFile);
                removeBlacklistEntry(indexTag, petUUID);
                NbtFileIO.writeCompressed(indexTag, indexFile);
            } else {
                blacklistedPetUUIDs.remove(petUUID);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to remove blacklist entry for {}: {}", petUUID, e.getMessage());
            return false;
        }
    }

    /** {@link #tryLoadPet} 的判定与读取结果。 */
    public enum LoadResult {
        /** 读取成功。 */
        OK,
        /** 不是 {@link LivingEntity}，或通过 {@link Config#ownerNbtFields} 无法解析出 owner UUID。 */
        NOT_A_PET,
        /** owner UUID 不对应任何已知玩家（既不在线也无 playerdata 文件）。 */
        UNKNOWN_OWNER,
        /** 实体类型在 {@link Config#isAutoRegisterBlacklisted} 列表中。 */
        TYPE_BLACKLISTED,
        /** owner 已达 {@link Config#maxPets} 上限。 */
        LIMIT_REACHED,
        /** 在读取黑名单中但移除条目失败。 */
        UNBLACKLIST_FAILED,
        /** 保存宠物数据失败。 */
        SAVE_FAILED,
    }

    /**
     * 对指定实体走原模组的正常读取判定与流程（与
     * {@link #registerUntrackedOwnedPet} 一致），用于 {@code /tbf load} 指令。
     *
     * <p>判定依次为：必须是 {@link LivingEntity}、能通过 {@link Config#ownerNbtFields}
     * 解析出 owner UUID、owner 是已知玩家、实体类型不在 {@link Config#isAutoRegisterBlacklisted}
     * 列表中、owner 未达 {@link Config#maxPets} 上限。判定通过后，若 UUID 在读取黑名单中
     * 则先移除该条目，再保存数据并更新索引（{@code updatePetIndex} 内部也会兜底移除黑名单）。</p>
     */
    public static LoadResult tryLoadPet(Entity entity, ServerLevel level) {
        if (INSTANCE == null) return LoadResult.SAVE_FAILED;
        if (!(entity instanceof LivingEntity living)) return LoadResult.NOT_A_PET;
        UUID ownerUUID = getCompatOwnerUUID(living);
        if (ownerUUID == null) return LoadResult.NOT_A_PET;
        if (!isKnownPlayer(level.getServer(), ownerUUID)) return LoadResult.UNKNOWN_OWNER;
        ResourceLocation entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String entityTypeKey = entityType != null ? entityType.toString() : null;
        if (entityTypeKey != null && Config.isAutoRegisterBlacklisted(entityTypeKey)) {
            return LoadResult.TYPE_BLACKLISTED;
        }
        if (INSTANCE.countOwnerPets(level, ownerUUID) >= Config.maxPets) return LoadResult.LIMIT_REACHED;

        if (isPetUUIDBlacklisted(level, living.getUUID())) {
            if (!unblacklistPetUUID(level, living.getUUID())) return LoadResult.UNBLACKLIST_FAILED;
        }

        if (!INSTANCE.savePetData(ownerUUID, living, level)) return LoadResult.SAVE_FAILED;
        INSTANCE.updatePetIndex(living, ownerUUID);
        flushPendingPetSaves(ownerUUID);
        return LoadResult.OK;
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
            if (isPetUUIDBlacklisted((ServerLevel) animal.level(), animal.getUUID())) return;
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
            updatePetIndex(animal, owner);
            flushPendingPetSaves(owner);
        }
    }

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (discardIfStoredDead(entity, level)) return;
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
        pendingRemovals.clear();
        PetSyncTracker.clearAll();
        TeleportPetToPlayerPacket.clearPendingSummons();
        chunksForcedByMod.forEach(chunk ->
                chunk.level().setChunkForced(chunk.chunkX(), chunk.chunkZ(), false));
        forcedChunkReferences.clear();
        chunksForcedByMod.clear();
        localSyncCandidates.clear();
        pendingPetSaves.clear();
        indexCache.clear();
        trackedPetUUIDs.clear();
        blacklistedPetUUIDs.clear();
        petIndexLoaded = false;
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
        if (entity.level().isClientSide() || !trackedPetUUIDs.contains(entity.getUUID())) return;

        UUID owner = getCompatOwnerUUID(entity);
        if (owner == null) return;
        ServerLevel level = (ServerLevel) entity.level();
        ResourceLocation entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String entityTypeKey = entityType != null ? entityType.toString() : null;

        if (Config.isClearOnDeathEntity(entityTypeKey)) {
            clearPetDataAndCache(entity, owner, level);
            return;
        }
        if (Config.isNoReviveEntity(entityTypeKey)) {
            savePetData(owner, entity, level);
            flushPendingPetSave(entity.getUUID());
        }
    }

    /** Called from LivingEntity#die before NeoForge posts LivingDeathEvent. */
    public static boolean tryStoreFatalPet(LivingEntity entity) {
        return tryStoreFatalPet(entity, null);
    }

    /** Stores a fatal pet removed without entering LivingEntity#die. */
    public static boolean tryStoreFatalPet(LivingEntity entity, DamageSource directDeathSource) {
        if (INSTANCE == null || entity.level().isClientSide()
                || !trackedPetUUIDs.contains(entity.getUUID())) return false;

        ReviveProtection.remove(entity.getUUID());
        UUID owner = getCompatOwnerUUID(entity);
        if (owner == null) return false;
        ServerLevel level = (ServerLevel) entity.level();
        ResourceLocation entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String entityTypeKey = entityType != null ? entityType.toString() : null;
        if (Config.isNoReviveEntity(entityTypeKey)) return false;

        if (!INSTANCE.savePetData(owner, entity, level, true) || !flushPendingPetSave(entity.getUUID())) {
            pendingPetSaves.remove(entity.getUUID());
            LOGGER.error("Pet {} will follow normal death because its stored-death snapshot could not be persisted",
                    entity.getUUID());
            return false;
        }

        petDeathTimes.put(entity.getUUID(), System.currentTimeMillis());
        removePendingRemovals(owner, entity.getUUID());
        updatePetRecalledState(level, entity.getUUID(), false);
        sendStoredDeathMessage(entity, owner, level, directDeathSource);
        entity.ejectPassengers();
        entity.stopRiding();
        entity.discard();
        return true;
    }

    private static void sendStoredDeathMessage(LivingEntity entity, UUID ownerUUID, ServerLevel level,
                                               DamageSource directDeathSource) {
        if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_SHOWDEATHMESSAGES)) return;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUUID);
        if (owner != null) {
            owner.sendSystemMessage(directDeathSource != null
                    ? directDeathSource.getLocalizedDeathMessage(entity)
                    : entity.getCombatTracker().getDeathMessage());
        }
    }

    /** 获取宠物死亡时刻（内存，不持久化）。返回 null 表示无记录（未死亡或服务器重启后）。 */
    public static Long getPetDeathTime(UUID petUuid) {
        return petDeathTimes.get(petUuid);
    }

    /** 复活成功后清除死亡时刻记录。 */
    public static void clearPetDeathTime(UUID petUuid) {
        petDeathTimes.remove(petUuid);
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

    private boolean savePetData(UUID ownerUUID, Entity pet, ServerLevel level) {
        return savePetData(ownerUUID, pet, level, false);
    }

    private boolean savePetData(UUID ownerUUID, Entity pet, ServerLevel level, boolean storedDead) {
        if (!isKnownPlayer(level.getServer(), ownerUUID)) {
            LOGGER.warn("Skipping pet save: owner UUID {} is not a known player", ownerUUID);
            return false;
        }
        CompoundTag nbt;
        try {
            nbt = PetEntitySnapshot.capture(pet, ownerUUID, level);
            if (storedDead) PetDeathState.markStoredDead(nbt);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to capture pet snapshot for {}: {}", pet.getUUID(), e.getMessage(), e);
            return false;
        }
        // LastDeathTime 完全不由磁盘管理——改由服务器内存 Map (petDeathTimes) 记录，
        // 在封存死亡时写入，通过网络同步注入给客户端。不写盘避免被 syncAllPets 反复刷新。
        Path worldPath = level.getServer().getWorldPath(LevelResource.ROOT);
        pendingPetSaves.put(pet.getUUID(), new PendingPetSave(
                ownerUUID,
                pet.getUUID(),
                worldPath,
                nbt,
                resolvePlayerName(level, ownerUUID),
                BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).toString()));

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
            boolean ownerChanged = false;
            if (!nbtFile.exists()) {
                Path oldOwnerFile = findPetFileInOtherOwnerDir(modDir, pending.ownerUUID(), pending.petUUID());
                if (oldOwnerFile != null) {
                    Files.move(oldOwnerFile, nbtFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    ownerChanged = true;
                    LOGGER.debug("Moved pet NBT {} to new owner {}", pending.petUUID(), pending.ownerUUID());
                }
            }

            PetIOUtil.writePetSnapshotPreservingRecall(nbtFile, pending.nbt());
            if (ownerChanged) {
                boolean recalled = NbtFileIO.readCompressed(nbtFile).getBoolean("Recalled");
                updatePetIndexEntry(modDir, pending.ownerName(), pending.typeKey(), pending.petUUID(), recalled);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save pet data for {}: {}", pending.petUUID(), e.getMessage());
            return false;
        }
    }

    private record PendingPetSave(UUID ownerUUID, UUID petUUID, Path worldPath, CompoundTag nbt,
                                  String ownerName, String typeKey) {}

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
        petDeathTimes.remove(petUUID);
        try {
            removePetFromIndex(modDir, petUUID);
        } catch (IOException e) {
            LOGGER.warn("Failed to remove pet from index for {}: {}", petUUID, e.getMessage());
        }
        LOGGER.debug("Cleared pet data and cache for {} (clear-on-death)", petUUID);
    }

    public static boolean deletePetData(ServerPlayer player, UUID petUUID) {
        // If the pet is currently recalled to disk, release it back into the world
        // before deleting its NBT file — otherwise the entity would vanish together
        // with the data. Abort the whole deletion if the release fails (e.g. summon
        // failed and the Recalled flag was rolled back) so the player can retry.
        if (!RecallPetPacket.releaseRecalledPet(player, petUUID, player.serverLevel())) {
            LOGGER.warn("Aborted deletePetData for {}: recalled pet could not be released", petUUID);
            return false;
        }

        PendingPetSave pending = pendingPetSaves.get(petUUID);
        Path petFile = PetIOUtil.getOwnerDir(player).resolve(petUUID + ".nbt");
        boolean ownsPendingSave = pending != null && player.getUUID().equals(pending.ownerUUID());
        boolean isShoulderPet = PetIOUtil.getShoulderEntity(player, petUUID) != null;
        boolean isLoadedOwnedPet = isLoadedOwnedPet(player, petUUID);
        if (!Files.exists(petFile) && !ownsPendingSave && !isShoulderPet && !isLoadedOwnedPet) return false;

        // Stop queued writes and summons before touching disk so stale data
        // cannot recreate the entry after this deletion.
        pendingPetSaves.remove(petUUID);
        removePendingRemovals(player.getUUID(), petUUID);
        localSyncCandidates.removeIf(candidate -> candidate.entityUUID().equals(petUUID));
        TeleportPetToPlayerPacket.cancelPendingSummons(player.getUUID(), petUUID);
        ReviveProtection.remove(petUUID);
        trackedPetUUIDs.remove(petUUID);
        petDeathTimes.remove(petUUID);

        try {
            Path modDir = PetIOUtil.getModDir(player);
            blacklistPetUUID(modDir, petUUID);
            deletePetFiles(modDir, petUUID);
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

    private static String resolvePlayerName(ServerLevel level, UUID ownerUUID) {
        ServerPlayer onlineOwner = level.getServer().getPlayerList().getPlayer(ownerUUID);
        if (onlineOwner != null) return onlineOwner.getGameProfile().getName();
        return level.getServer().getProfileCache().get(ownerUUID)
                .map(profile -> profile.getName())
                .orElse(ownerUUID.toString());
    }

    private static void removePetFromIndex(Path modDir, UUID petUUID) throws IOException {
        File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();
        if (!indexFile.exists()) return;

        CompoundTag indexTag = NbtFileIO.readCompressed(indexFile);
        if (removePetIndexEntry(indexTag, petUUID)) {
            NbtFileIO.writeCompressed(indexTag, indexFile);
        }
    }

    private static void blacklistPetUUID(Path modDir, UUID petUUID) throws IOException {
        blacklistedPetUUIDs.add(petUUID);
        Files.createDirectories(modDir);
        File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();
        CompoundTag indexTag = indexFile.exists() ? NbtFileIO.readCompressed(indexFile) : new CompoundTag();
        removePetIndexEntry(indexTag, petUUID);
        addBlacklistEntry(indexTag, petUUID);
        NbtFileIO.writeCompressed(indexTag, indexFile);
    }

    private static void deletePetFiles(Path modDir, UUID petUUID) throws IOException {
        if (!Files.exists(modDir)) return;
        String fileName = petUUID + ".nbt";
        try (var entries = Files.list(modDir)) {
            for (Path ownerDir : entries.filter(Files::isDirectory).toList()) {
                Files.deleteIfExists(ownerDir.resolve(fileName));
            }
        }
    }

    static boolean addBlacklistEntry(CompoundTag indexTag, UUID petUUID) {
        ListTag blacklist = indexTag.getList(BLACKLISTED_UUIDS_KEY, Tag.TAG_STRING);
        String uuid = petUUID.toString();
        for (int i = 0; i < blacklist.size(); i++) {
            if (uuid.equals(blacklist.getString(i))) return false;
        }
        blacklist.add(StringTag.valueOf(uuid));
        indexTag.put(BLACKLISTED_UUIDS_KEY, blacklist);
        return true;
    }

    static boolean isBlacklistEntry(CompoundTag indexTag, UUID petUUID) {
        String uuid = petUUID.toString();
        ListTag blacklist = indexTag.getList(BLACKLISTED_UUIDS_KEY, Tag.TAG_STRING);
        for (int i = 0; i < blacklist.size(); i++) {
            if (uuid.equals(blacklist.getString(i))) return true;
        }
        return false;
    }

    private static void removeBlacklistEntry(CompoundTag indexTag, UUID petUUID) {
        ListTag blacklist = indexTag.getList(BLACKLISTED_UUIDS_KEY, Tag.TAG_STRING);
        String uuid = petUUID.toString();
        for (int i = blacklist.size() - 1; i >= 0; i--) {
            if (uuid.equals(blacklist.getString(i))) blacklist.remove(i);
        }
        if (blacklist.isEmpty()) indexTag.remove(BLACKLISTED_UUIDS_KEY);
        else indexTag.put(BLACKLISTED_UUIDS_KEY, blacklist);
        blacklistedPetUUIDs.remove(petUUID);
    }

    private static boolean removePetIndexEntry(CompoundTag indexTag, UUID petUUID) {
        String uuid = petUUID.toString();
        boolean changed = false;
        for (String playerName : new ArrayList<>(indexTag.getAllKeys())) {
            if (!indexTag.contains(playerName, Tag.TAG_COMPOUND)) continue;
            CompoundTag playerTag = indexTag.getCompound(playerName);
            for (String typeKey : new ArrayList<>(playerTag.getAllKeys())) {
                if (!playerTag.contains(typeKey, Tag.TAG_COMPOUND)) continue;
                CompoundTag typeTag = playerTag.getCompound(typeKey);
                if (typeTag.contains(uuid, Tag.TAG_COMPOUND)) {
                    typeTag.remove(uuid);
                    changed = true;
                    List<UUID> cached = indexCache.get(typeKey);
                    if (cached != null) cached.remove(petUUID);
                }
                if (typeTag.isEmpty()) playerTag.remove(typeKey);
                else playerTag.put(typeKey, typeTag);
            }
            if (playerTag.isEmpty()) indexTag.remove(playerName);
            else indexTag.put(playerName, playerTag);
        }
        return changed;
    }

    public static void updatePetRecalledState(ServerLevel level, UUID petUUID, boolean recalled) {
        File indexFile = PetIOUtil.getModDir(level).resolve(PETS_INDEX_FILE).toFile();
        if (!indexFile.exists()) return;

        try {
            CompoundTag indexTag = NbtFileIO.readCompressed(indexFile);
            String uuid = petUUID.toString();
            for (String playerName : indexTag.getAllKeys()) {
                if (!indexTag.contains(playerName, Tag.TAG_COMPOUND)) continue;
                CompoundTag playerTag = indexTag.getCompound(playerName);
                for (String typeKey : playerTag.getAllKeys()) {
                    if (!playerTag.contains(typeKey, Tag.TAG_COMPOUND)) continue;
                    CompoundTag typeTag = playerTag.getCompound(typeKey);
                    if (!typeTag.contains(uuid, Tag.TAG_COMPOUND)) continue;
                    CompoundTag state = typeTag.getCompound(uuid);
                    if (state.contains("Recalled") && state.getBoolean("Recalled") == recalled) return;
                    state.putBoolean("Recalled", recalled);
                    typeTag.put(uuid, state);
                    playerTag.put(typeKey, typeTag);
                    indexTag.put(playerName, playerTag);
                    NbtFileIO.writeCompressed(indexTag, indexFile);
                    return;
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to update recalled state in pet index for {}: {}", petUUID, e.getMessage());
        }
    }

    private static void updatePetIndexEntry(Path modDir, String playerName, String typeKey,
                                            UUID petUUID, boolean recalled) throws IOException {
        File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();
        CompoundTag indexTag = indexFile.exists() ? NbtFileIO.readCompressed(indexFile) : new CompoundTag();
        if (putPetIndexEntry(indexTag, playerName, typeKey, petUUID, recalled)) {
            NbtFileIO.writeCompressed(indexTag, indexFile);
        }
    }

    private static boolean putPetIndexEntry(CompoundTag indexTag, String playerName, String typeKey,
                                            UUID petUUID, boolean recalled) {
        removeBlacklistEntry(indexTag, petUUID);
        String uuid = petUUID.toString();
        CompoundTag playerTag = indexTag.getCompound(playerName);
        CompoundTag typeTag = playerTag.getCompound(typeKey);
        boolean alreadyInPlace = typeTag.contains(uuid, Tag.TAG_COMPOUND);
        if (alreadyInPlace) {
            CompoundTag state = typeTag.getCompound(uuid);
            if (state.contains("Recalled") && state.getBoolean("Recalled") == recalled) return false;
        } else {
            removePetIndexEntry(indexTag, petUUID);
            playerTag = indexTag.getCompound(playerName);
            typeTag = playerTag.getCompound(typeKey);
        }

        CompoundTag state = new CompoundTag();
        state.putBoolean("Recalled", recalled);
        typeTag.put(uuid, state);
        playerTag.put(typeKey, typeTag);
        indexTag.put(playerName, playerTag);
        List<UUID> cached = indexCache.computeIfAbsent(typeKey, ignored -> new ArrayList<>());
        if (!cached.contains(petUUID)) cached.add(petUUID);
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

    private void updatePetIndex(Entity pet, UUID ownerUUID) {
        try {
            ServerLevel level = (ServerLevel) pet.level();
            Path modDir = PetIOUtil.getModDir(level);
            String typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).toString();
            UUID petUUID = pet.getUUID();
            updatePetIndexEntry(modDir, resolvePlayerName(level, ownerUUID), typeKey, petUUID, false);
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
        if (isPetUUIDBlacklisted(level, entity.getUUID())) return false;
        if (!isKnownPlayer(level.getServer(), ownerUUID)) return false;

        ResourceLocation entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (entityType != null && Config.isAutoRegisterBlacklisted(entityType.toString())) return false;

        if (countOwnerPets(level, ownerUUID) >= Config.maxPets) return false;
        updatePetIndex(entity, ownerUUID);
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

    private boolean discardIfStoredDead(Entity entity, ServerLevel level) {
        UUID ownerUUID = getCompatOwnerUUID(entity);
        if (ownerUUID == null) return false;

        UUID petUUID = entity.getUUID();
        File nbtFile = PetIOUtil.getOwnerDir(level, ownerUUID).resolve(petUUID + ".nbt").toFile();
        if (!nbtFile.exists()) return false;

        try {
            CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);
            boolean explicitlyStored = PetDeathState.isStoredDead(nbt);
            String typeKey = nbt.contains("EntityType")
                    ? nbt.getString("EntityType")
                    : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (!explicitlyStored
                    && (!PetDeathState.isDeadSnapshot(nbt) || Config.isNoReviveEntity(typeKey))) {
                return false;
            }

            if (!explicitlyStored) {
                PetDeathState.markStoredDead(nbt);
                NbtFileIO.writeCompressed(nbt, nbtFile);
            }
            trackedPetUUIDs.add(petUUID);
            removePendingRemovals(ownerUUID, petUUID);
            entity.ejectPassengers();
            entity.stopRiding();
            entity.discard();
            LOGGER.info("Discarded loaded copy of stored-dead pet {}", petUUID);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to reconcile stored-dead pet {}: {}", petUUID, e.getMessage());
            return false;
        }
    }

    public static synchronized Map<String, List<UUID>> loadPetIndex(ServerLevel level) {
        if (petIndexLoaded) return indexCache;

        try {
            Path modDir = PetIOUtil.getModDir(level);
            File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();

            if (indexFile.exists()) {
                CompoundTag indexTag = NbtFileIO.readCompressed(indexFile);
                ListTag blacklist = indexTag.getList(BLACKLISTED_UUIDS_KEY, Tag.TAG_STRING);
                for (int i = 0; i < blacklist.size(); i++) {
                    try {
                        blacklistedPetUUIDs.add(UUID.fromString(blacklist.getString(i)));
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Invalid UUID in pet blacklist: {}", blacklist.getString(i));
                    }
                }
                for (String playerName : indexTag.getAllKeys()) {
                    if (BLACKLISTED_UUIDS_KEY.equals(playerName)) continue;
                    if (!indexTag.contains(playerName, Tag.TAG_COMPOUND)) continue;
                    CompoundTag playerTag = indexTag.getCompound(playerName);
                    for (String typeKey : playerTag.getAllKeys()) {
                        if (!playerTag.contains(typeKey, Tag.TAG_COMPOUND)) continue;
                        CompoundTag typeTag = playerTag.getCompound(typeKey);
                        List<UUID> uuids = indexCache.computeIfAbsent(typeKey, ignored -> new ArrayList<>());
                        for (String uuidString : typeTag.getAllKeys()) {
                            if (!typeTag.contains(uuidString, Tag.TAG_COMPOUND)) continue;
                            try {
                                UUID uuid = UUID.fromString(uuidString);
                                if (!uuids.contains(uuid)) uuids.add(uuid);
                                trackedPetUUIDs.add(uuid);
                            } catch (IllegalArgumentException e) {
                                LOGGER.warn("Invalid UUID in pet index: {}", uuidString);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load pet index: {}", e.getMessage());
        }
        petIndexLoaded = true;
        return indexCache;
    }

    private static boolean isLoadedOwnedPet(ServerPlayer player, UUID petUUID) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            Entity entity = level.getEntity(petUUID);
            if (entity != null && isOwnedBy(entity, player.getUUID())) return true;
        }
        return false;
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
