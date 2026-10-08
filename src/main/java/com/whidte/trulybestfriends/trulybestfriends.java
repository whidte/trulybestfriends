package com.whidte.trulybestfriends;

import com.mojang.logging.LogUtils;
import com.whidte.trulybestfriends.client.ClientPacketHandlers;
import com.whidte.trulybestfriends.network.AreaRecallPacket;
import com.whidte.trulybestfriends.network.DeletePetDataPacket;
import com.whidte.trulybestfriends.network.DirectTeleportPetToPlayerPacket;
import com.whidte.trulybestfriends.network.HealPetPacket;
import com.whidte.trulybestfriends.network.PetIOUtil;
import com.whidte.trulybestfriends.network.PetPresenceProbe;
import com.whidte.trulybestfriends.network.NbtFileIO;
import com.whidte.trulybestfriends.network.OpenPetScreenPacket;
import com.whidte.trulybestfriends.network.PetEntitySnapshot;
import com.whidte.trulybestfriends.network.PetDeathState;
import com.whidte.trulybestfriends.network.PetHealingManager;
import com.whidte.trulybestfriends.network.PetTeamData;
import com.whidte.trulybestfriends.network.PetSyncTracker;
import com.whidte.trulybestfriends.network.PetWarningPacket;
import com.whidte.trulybestfriends.network.RecallPetPacket;
import com.whidte.trulybestfriends.network.ReleaseRecalledPetPacket;
import com.whidte.trulybestfriends.network.RequestPetDataPacket;
import com.whidte.trulybestfriends.network.RequestTeamDataPacket;
import com.whidte.trulybestfriends.network.RevivePetPacket;
import com.whidte.trulybestfriends.network.SetLastSummonPacket;
import com.whidte.trulybestfriends.network.SetPriorityPacket;
import com.whidte.trulybestfriends.network.SetTeamMemberPacket;
import com.whidte.trulybestfriends.network.SummonTeamPacket;
import com.whidte.trulybestfriends.network.SummonPetPacket;
import com.whidte.trulybestfriends.network.SyncPetDataPacket;
import com.whidte.trulybestfriends.network.TeamDataPacket;
import com.whidte.trulybestfriends.network.TeleportPetToPlayerPacket;
import com.whidte.trulybestfriends.network.TeleportToPetPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.advancements.Advancement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.AnimalTameEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod(value = trulybestfriends.MODID)
public class trulybestfriends {
    public static final String MODID = "trulybestfriends";
    public static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private static final String PETS_INDEX_FILE = "pets_index.nbt";
    private static final String BLACKLISTED_UUIDS_KEY = PetIndexBlacklist.KEY;
    private static final ResourceLocation TRULY_BEST_FRIENDS_ADVANCEMENT = ResourceLocation.fromNamespaceAndPath("minecraft", "husbandry/tame_an_animal");
    private static final int LOCAL_SYNC_CHUNK_RADIUS = 2;
    private static final Map<String, List<UUID>> indexCache = new ConcurrentHashMap<>();
    private static final Set<UUID> trackedPetUUIDs = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> blacklistedPetUUIDs = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, UUID> forcedTrackingOwners = new ConcurrentHashMap<>();
    private static final Set<PendingRemoval> pendingRemovals = ConcurrentHashMap.newKeySet();
    private static final Map<ForcedChunk, Integer> forcedChunkReferences = new ConcurrentHashMap<>();
    private static final Set<ForcedChunk> chunksForcedByMod = ConcurrentHashMap.newKeySet();
    private static final Set<LocalSyncCandidate> localSyncCandidates = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, PendingPetSave> pendingPetSaves = new ConcurrentHashMap<>();
    /** 每个宠物最近一次捕获的实体状态；用于跳过冗余的 NBT 序列化。 */
    private static final Map<UUID, PetStateSignature> petStateSignatures = new ConcurrentHashMap<>();
    /** 针对“宠物 NBT 存在于其它主人目录下”的短时负缓存。 */
    private static final Map<UUID, Long> noForeignOwnerFileUntil = new ConcurrentHashMap<>();
    /** 已确认的“无其它主人文件”结果的可信时长。 */
    private static final long FOREIGN_OWNER_CHECK_TTL_NANOS = 10_000_000_000L;
    private static final Set<EntityNbtSaveFailure> reportedEntityNbtSaveFailures = ConcurrentHashMap.newKeySet();
    private static volatile boolean petIndexLoaded;
    private static final long PENDING_REMOVAL_TIMEOUT_TICKS = 100L;

    /** 宠物死亡时刻（内存，不持久化）。key=petUUID, value=System.currentTimeMillis()。
     *  用于复活冷却计算，避免写盘后被 syncAllPets 反复刷新导致冷却永远不结束。
     *  服务器重启后清空 → 重启前的死亡宠物无冷却，可立即复活（符合"不保存到磁盘"的设计）。 */
    private static final Map<UUID, Long> petDeathTimes = new ConcurrentHashMap<>();

    private static final String PROTOCOL_VERSION = "5";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private int syncTickCounter = 0;
    private int localSyncTickCounter = 0;
    private int performanceModeSyncTickCounter = 0;
    private int saveTickCounter = 0;
    private int bossRecallTickCounter = 0;
    private static final int BOSS_RECALL_INTERVAL_TICKS = 20;

    private static trulybestfriends INSTANCE;

    public trulybestfriends(FMLJavaModLoadingContext context) {
        INSTANCE = this;
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
        context.getModEventBus().addListener(this::commonSetup);
        com.whidte.trulybestfriends.client.ModParticleTypes.attach(context.getModEventBus());
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
        OK,
        NOT_A_PET,
        UNKNOWN_OWNER,
        TYPE_BLACKLISTED,
        LIMIT_REACHED,
        UNBLACKLIST_FAILED,
        SAVE_FAILED,
    }

    /** 对指定实体执行与自动注册一致的判定与读取流程，供 {@code /tbf load} 使用。 */
    public static LoadResult tryLoadPet(Entity entity, ServerLevel level) {
        if (INSTANCE == null) return LoadResult.SAVE_FAILED;
        if (!(entity instanceof LivingEntity living)) return LoadResult.NOT_A_PET;
        UUID ownerUUID = getCompatOwnerUUID(living);
        if (ownerUUID == null) return LoadResult.NOT_A_PET;
        return tryLoadPet(living, ownerUUID, level, false);
    }

    /**
     * 使用正常的 /tbf load 策略，但归属取自执行指令的玩家，
     * 而非 OwnableEntity 或配置的主人 NBT 路径。
     */
    public static LoadResult tryForceLoadPet(Entity entity, ServerPlayer owner, ServerLevel level) {
        if (INSTANCE == null) return LoadResult.SAVE_FAILED;
        if (!(entity instanceof LivingEntity living)
                || entity instanceof Player
                || entity instanceof PartEntity<?>) {
            return LoadResult.NOT_A_PET;
        }
        return tryLoadPet(living, owner.getUUID(), level, true);
    }

    private static LoadResult tryLoadPet(LivingEntity living, UUID ownerUUID, ServerLevel level,
                                         boolean forceTracking) {
        if (!isKnownPlayer(level.getServer(), ownerUUID)) return LoadResult.UNKNOWN_OWNER;
        ResourceLocation entityType = ForgeRegistries.ENTITY_TYPES.getKey(living.getType());
        String entityTypeKey = entityType != null ? entityType.toString() : null;
        if (entityTypeKey != null && Config.isAutoRegisterBlacklisted(entityTypeKey)) {
            return LoadResult.TYPE_BLACKLISTED;
        }
        if (INSTANCE.countOwnerPets(level, ownerUUID) >= Config.maxPets) return LoadResult.LIMIT_REACHED;

        if (isPetUUIDBlacklisted(level, living.getUUID())) {
            if (!unblacklistPetUUID(level, living.getUUID())) return LoadResult.UNBLACKLIST_FAILED;
        }

        if (!INSTANCE.savePetData(ownerUUID, living, level)) return LoadResult.SAVE_FAILED;
        if (forceTracking && !persistForcedTrackingOwner(level, living.getUUID(), ownerUUID)) {
            pendingPetSaves.remove(living.getUUID());
            return LoadResult.SAVE_FAILED;
        }
        INSTANCE.updatePetIndex(living, ownerUUID);
        flushPendingPetSaves(ownerUUID);
        return LoadResult.OK;
    }

    private static boolean persistForcedTrackingOwner(ServerLevel level, UUID petUUID, UUID ownerUUID) {
        loadPetIndex(level);
        UUID previousOwner = forcedTrackingOwners.get(petUUID);
        try {
            Path modDir = PetIOUtil.getModDir(level);
            Files.createDirectories(modDir);
            File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();
            CompoundTag indexTag = indexFile.exists() ? NbtFileIO.readCompressed(indexFile) : new CompoundTag();
            ForcedTrackingWhitelist.put(indexTag, petUUID, ownerUUID);
            NbtFileIO.writeCompressed(indexTag, indexFile);
            forcedTrackingOwners.put(petUUID, ownerUUID);
            trackedPetUUIDs.add(petUUID);
            return true;
        } catch (IOException e) {
            if (previousOwner == null) forcedTrackingOwners.remove(petUUID);
            else forcedTrackingOwners.put(petUUID, previousOwner);
            LOGGER.error("Failed to persist forced tracking entry for {}: {}", petUUID, e.getMessage());
            return false;
        }
    }

    /** 用完全还原后的实时实体快照替换所有加入时排队的待处理保存。 */
    public static boolean persistRestoredPet(UUID ownerUUID, Entity pet, ServerLevel level) {
        if (INSTANCE == null || !INSTANCE.savePetData(ownerUUID, pet, level)) return false;
        // 在主人目录之间迁移宠物时，savePetData 内部可能已经落盘了该快照。
        if (!pendingPetSaves.containsKey(pet.getUUID())) return true;
        if (flushPendingPetSave(pet.getUUID())) return true;
        pendingPetSaves.remove(pet.getUUID());
        return false;
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        CHANNEL.registerMessage(0, HealPetPacket.class, HealPetPacket::encode, HealPetPacket::decode, HealPetPacket::handle);
        CHANNEL.registerMessage(1, RecallPetPacket.class, RecallPetPacket::encode, RecallPetPacket::decode, RecallPetPacket::handle);
        CHANNEL.registerMessage(2, TeleportToPetPacket.class, TeleportToPetPacket::encode, TeleportToPetPacket::decode, TeleportToPetPacket::handle);
        CHANNEL.registerMessage(3, TeleportPetToPlayerPacket.class, TeleportPetToPlayerPacket::encode, TeleportPetToPlayerPacket::decode, TeleportPetToPlayerPacket::handle);
        CHANNEL.registerMessage(4, AreaRecallPacket.class, AreaRecallPacket::encode, AreaRecallPacket::decode, AreaRecallPacket::handle);
        // 服务端→客户端的处理器位于仅客户端可用的 ClientPacketHandlers 类中。
        // dist 守卫可避免该客户端类在专用服务端上被加载，
        // 因为专用服务端不存在 net.minecraft.client.*。
        CHANNEL.registerMessage(5, PetWarningPacket.class, PetWarningPacket::encode, PetWarningPacket::decode,
                (packet, ctx) -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) ClientPacketHandlers.handle(packet, ctx);
                });
        CHANNEL.registerMessage(6, RequestPetDataPacket.class, RequestPetDataPacket::encode, RequestPetDataPacket::decode, RequestPetDataPacket::handle);
        CHANNEL.registerMessage(7, RevivePetPacket.class, RevivePetPacket::encode, RevivePetPacket::decode, RevivePetPacket::handle);
        CHANNEL.registerMessage(8, SetPriorityPacket.class, SetPriorityPacket::encode, SetPriorityPacket::decode, SetPriorityPacket::handle);
        CHANNEL.registerMessage(9, SyncPetDataPacket.class, SyncPetDataPacket::encode, SyncPetDataPacket::decode,
                (packet, ctx) -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) ClientPacketHandlers.handle(packet, ctx);
                });
        CHANNEL.registerMessage(10, DeletePetDataPacket.class, DeletePetDataPacket::encode, DeletePetDataPacket::decode, DeletePetDataPacket::handle);
        CHANNEL.registerMessage(11, ReleaseRecalledPetPacket.class, ReleaseRecalledPetPacket::encode, ReleaseRecalledPetPacket::decode, ReleaseRecalledPetPacket::handle);
        CHANNEL.registerMessage(12, DirectTeleportPetToPlayerPacket.class, DirectTeleportPetToPlayerPacket::encode, DirectTeleportPetToPlayerPacket::decode, DirectTeleportPetToPlayerPacket::handle);
        CHANNEL.registerMessage(13, RequestTeamDataPacket.class, RequestTeamDataPacket::encode, RequestTeamDataPacket::decode, RequestTeamDataPacket::handle);
        CHANNEL.registerMessage(14, SetTeamMemberPacket.class, SetTeamMemberPacket::encode, SetTeamMemberPacket::decode, SetTeamMemberPacket::handle);
        CHANNEL.registerMessage(15, TeamDataPacket.class, TeamDataPacket::encode, TeamDataPacket::decode,
                (packet, ctx) -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) ClientPacketHandlers.handle(packet, ctx);
                });
        CHANNEL.registerMessage(16, SummonTeamPacket.class, SummonTeamPacket::encode, SummonTeamPacket::decode, SummonTeamPacket::handle);
        CHANNEL.registerMessage(17, SummonPetPacket.class, SummonPetPacket::encode, SummonPetPacket::decode, SummonPetPacket::handle);
        CHANNEL.registerMessage(18, SetLastSummonPacket.class, SetLastSummonPacket::encode, SetLastSummonPacket::decode, SetLastSummonPacket::handle);
        CHANNEL.registerMessage(19, OpenPetScreenPacket.class, OpenPetScreenPacket::encode, OpenPetScreenPacket::decode,
                (packet, ctx) -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) ClientPacketHandlers.handle(packet, ctx);
                });
    }

    @SubscribeEvent
    public void onAnimalTamed(AnimalTameEvent event) {
        Entity animal = event.getAnimal();
        if (animal.level().isClientSide() || Config.performanceMode) return;
        UUID owner = getCompatOwnerUUID(animal);
        if (owner != null) {
            if (isPetUUIDBlacklisted((ServerLevel) animal.level(), animal.getUUID())) return;
            ResourceLocation entityType = ForgeRegistries.ENTITY_TYPES.getKey(animal.getType());
            if (entityType != null && Config.isAutoRegisterBlacklisted(entityType.toString())) return;
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
        if (Config.performanceMode) {
            if (!petIndexLoaded) loadPetIndex(level);
            if (!trackedPetUUIDs.contains(entity.getUUID())) return;
        }
        if (TeleportPetToPlayerPacket.isReleasingUntrackedDeath(entity.getUUID())) return;
        if (discardIfStoredDead(entity, level)) return;
        if (discardIfRecalled(entity, level)) return;
        UUID ownerUUID = getCompatOwnerUUID(entity);
        if (Config.performanceMode) {
            if (ownerUUID != null && entity instanceof LivingEntity living) {
                PetHealingManager.onEntityLoaded(living, ownerUUID);
            }
            return;
        }
        if (ownerUUID != null) {
            // 在（重新）加入时保存已追踪的宠物——覆盖跨维度传送门旅行
            // （例如末地传送门）中实体以相同 UUID 被重新创建的情况。
            // registerUntrackedOwnedPet 负责首次注册与索引更新。
            if (trackedPetUUIDs.contains(entity.getUUID())
                    || registerUntrackedOwnedPet(entity, ownerUUID, level)) {
                boolean healingSnapshotPersisted = false;
                if (entity instanceof LivingEntity living) {
                    healingSnapshotPersisted = PetHealingManager.onEntityLoaded(living, ownerUUID);
                }
                if (!healingSnapshotPersisted) savePetData(ownerUUID, entity, level);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityMount(EntityMountEvent event) {
        if (!event.isMounting()
                || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntityMounting() instanceof ServerPlayer player)
                || !(event.getEntityBeingMounted() instanceof LivingEntity mount)) return;
        UUID mountUUID = mount.getUUID();
        if (isTrackedPet(mountUUID) && isOwnedBy(mount, player.getUUID())) {
            updatePetRideableState(level, mountUUID);
        }
    }

    @SubscribeEvent
    public void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity living)) return;
        boolean tracked = trackedPetUUIDs.contains(living.getUUID());
        if (Config.performanceMode && !tracked) return;
        UUID ownerUUID = getCompatOwnerUUID(living);
        if (ownerUUID == null) return;

        // 在区块卸载前捕获最后的实时状态。其它移除原因
        // 有专门的持久化路径，或可能在别处重新创建该实体。
        if (tracked && living.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            savePetData(ownerUUID, living, (ServerLevel) event.getLevel());
        }
        PetHealingManager.onEntityUnloaded(living, ownerUUID);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            try {
                PetTeamData.ensureAndPrune(PetIOUtil.getOwnerDir(player));
            } catch (IOException e) {
                LOGGER.error("Failed to initialize team data for {}: {}",
                        player.getUUID(), e.getMessage(), e);
            }
            if (Config.enableLoginLoadDiagnostics) loadPlayerPetsData(player);
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
        PetHealingManager.load(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        flushPendingPetSaves();
        PetHealingManager.shutdown();
        ReviveProtection.clear(event.getServer());
        petDeathTimes.clear();  // 死亡时刻仅在内存，不持久化
        pendingRemovals.clear();
        PetSyncTracker.clearAll();
        TeleportPetToPlayerPacket.clearPendingSummons();
        PetPresenceProbe.clear();
        chunksForcedByMod.forEach(chunk ->
                chunk.level().setChunkForced(chunk.chunkX(), chunk.chunkZ(), false));
        forcedChunkReferences.clear();
        chunksForcedByMod.clear();
        localSyncCandidates.clear();
        pendingPetSaves.clear();
        petStateSignatures.clear();
        noForeignOwnerFileUntil.clear();
        indexCache.clear();
        trackedPetUUIDs.clear();
        blacklistedPetUUIDs.clear();
        forcedTrackingOwners.clear();
        reportedEntityNbtSaveFailures.clear();
        petIndexLoaded = false;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            saveTickCounter++;
            processPendingRemovals(event.getServer());
            TeleportPetToPlayerPacket.tickPendingSummons(event.getServer());
            PetPresenceProbe.tick(event.getServer());
            ReviveProtection.tick(event.getServer());
            PetHealingManager.tick(event.getServer());

            if (Config.performanceMode) {
                syncTickCounter = 0;
                localSyncTickCounter = 0;
                if (!localSyncCandidates.isEmpty()) localSyncCandidates.clear();
                performanceModeSyncTickCounter++;
                if (performanceModeSyncTickCounter >= Config.performanceModeSyncIntervalTicks) {
                    performanceModeSyncTickCounter = 0;
                    syncTrackedPets(event.getServer());
                }
            } else {
                performanceModeSyncTickCounter = 0;
                if (Config.syncIntervalTicks > 0) syncTickCounter++;
                else syncTickCounter = 0;
                localSyncTickCounter++;
                processLocalSyncCandidates(event.getServer());
                if (localSyncTickCounter >= Config.localSyncIntervalTicks) {
                    localSyncTickCounter = 0;
                    collectLocalSyncCandidates(event.getServer());
                }
                if (Config.syncIntervalTicks > 0 && syncTickCounter >= Config.syncIntervalTicks) {
                    syncTickCounter = 0;
                    syncAllPets(event.getServer());
                }
            }
            if (Config.bossFightPetLimit >= 0) {
                bossRecallTickCounter++;
                if (bossRecallTickCounter >= BOSS_RECALL_INTERVAL_TICKS) {
                    bossRecallTickCounter = 0;
                    checkBossRecalls(event.getServer());
                }
            }
            if (saveTickCounter >= Config.savePetDataCooldownTicks) {
                saveTickCounter = 0;
                flushPendingPetSaves();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onLivingIncomingDamage(LivingAttackEvent event) {
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
        PetHealingManager.clear(entity.getUUID());

        UUID owner = getCompatOwnerUUID(entity);
        if (owner == null) return;
        ServerLevel level = (ServerLevel) entity.level();
        ResourceLocation entityType = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
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

    /** 在 Forge 发布 LivingDeathEvent 之前，由 LivingEntity#die 调用。 */
    public static boolean tryStoreFatalPet(LivingEntity entity) {
        return tryStoreFatalPet(entity, null);
    }

    /** 存储一只未进入 LivingEntity#die 即被移除的致死宠物。 */
    public static boolean tryStoreFatalPet(LivingEntity entity, DamageSource directDeathSource) {
        if (INSTANCE == null || entity.level().isClientSide()
                || !trackedPetUUIDs.contains(entity.getUUID())) return false;

        ReviveProtection.remove(entity.getUUID());
        PetHealingManager.clear(entity.getUUID());
        UUID owner = getCompatOwnerUUID(entity);
        if (owner == null) return false;
        ServerLevel level = (ServerLevel) entity.level();
        ResourceLocation entityType = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
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

    /**
     * 丢弃与某个宠物 UUID 关联的所有内存缓存条目。
     *
     * <p>每当宠物被取消追踪、删除或清除时都必须调用，
     * 以免过期的状态签名抑制同一 UUID 之后重新注册时的写入。</p>
     */
    private static void forgetPetCaches(UUID petUuid) {
        petDeathTimes.remove(petUuid);
        petStateSignatures.remove(petUuid);
        noForeignOwnerFileUntil.remove(petUuid);
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
        PetHealingManager.onPetSaved(pet.getUUID(), ownerUUID);

        // 低成本预检：capture() 会序列化整棵实体 NBT 树，而周期性同步会在固定
        // 的 tick 间隔对每个已加载宠物调用它。当实体的可观测状态（坐标、生命值、
        // 名称、坐下标记）自上次捕获以来未变化时，之前的待处理快照依然准确，
        // 重新序列化纯属浪费。
        PetStateSignature signature = storedDead ? null : PetStateSignature.of(pet);
        if (signature != null) {
            PetStateSignature previous = petStateSignatures.get(pet.getUUID());
            if (signature.equals(previous) && pendingPetSaves.containsKey(pet.getUUID())) {
                return true;
            }
        }

        String entityTypeKey = ForgeRegistries.ENTITY_TYPES.getKey(pet.getType()).toString();
        CompoundTag nbt;
        try {
            nbt = PetEntitySnapshot.capture(pet, ownerUUID, level);
            if (storedDead) PetDeathState.markStoredDead(nbt);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to capture pet snapshot for {}: {}", pet.getUUID(), e.getMessage(), e);
            return false;
        }
        // 仅在捕获成功后才记录签名，这样序列化失败
        // 就不会抑制下一轮的重新尝试。
        if (signature != null) petStateSignatures.put(pet.getUUID(), signature);
        else petStateSignatures.remove(pet.getUUID());
        // LastDeathTime 完全不由磁盘管理——改由服务器内存 Map (petDeathTimes) 记录，
        // 在封存死亡时写入，通过网络同步注入给客户端。不写盘避免被 syncAllPets 反复刷新。
        Path worldPath = level.getServer().getWorldPath(LevelResource.ROOT);
        pendingPetSaves.put(pet.getUUID(), new PendingPetSave(
                ownerUUID,
                pet.getUUID(),
                worldPath,
                nbt,
                resolvePlayerName(level, ownerUUID),
                entityTypeKey));

        if (hasPetFileInOtherOwnerDir(PetIOUtil.getModDir(level), ownerUUID, pet.getUUID())) {
            flushPendingPetSaves(ownerUUID);
        }
        return true;
    }

    /**
     * 实体的外部可见状态的低成本、少分配指纹。
     * 用于跳过冗余的 {@link PetEntitySnapshot#capture} 调用。
     *
     * <p>坐标被量化到 1/16 方块，这样站立不动时的抖动
     * 不会使该检查失效，而任何有意义的移动仍会强制
     * 重新捕获。</p>
     */
    private record PetStateSignature(long x, long y, long z, float yRot, float health,
                                     float maxHealth, int nameHash, boolean sitting, boolean noAi) {
        static PetStateSignature of(Entity entity) {
            float health = entity instanceof LivingEntity living ? living.getHealth() : 0.0F;
            float maxHealth = entity instanceof LivingEntity living
                    ? (float) living.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                    : 0.0F;
            boolean sitting = entity instanceof net.minecraft.world.entity.TamableAnimal tamable
                    && tamable.isOrderedToSit();
            boolean noAi = entity instanceof net.minecraft.world.entity.Mob mob && mob.isNoAi();
            int nameHash = entity.hasCustomName() && entity.getCustomName() != null
                    ? entity.getCustomName().getString().hashCode() : 0;
            return new PetStateSignature(
                    Math.round(entity.getX() * 16.0),
                    Math.round(entity.getY() * 16.0),
                    Math.round(entity.getZ() * 16.0),
                    entity.getYRot(),
                    health,
                    maxHealth,
                    nameHash,
                    sitting,
                    noAi);
        }
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
                    removePetFromTeam(oldOwnerFile.getParent(), pending.petUUID());
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

    private record EntityNbtSaveFailure(UUID entityUUID, String exceptionType, String exceptionMessage) {}

    private static boolean hasPetFileInOtherOwnerDir(Path modDir, UUID currentOwnerUUID, UUID petUUID) {
        // 主人变更很少发生，但该检查在每轮保存中都会运行，否则
        // 每次都要列出所有主人目录。对已确认的“无过期文件”结果
        // 记住一小段时间；正结果始终会重新检查，以便迁移仍能及时发生。
        long now = System.nanoTime();
        Long cachedUntil = noForeignOwnerFileUntil.get(petUUID);
        if (cachedUntil != null && now < cachedUntil) return false;

        try {
            boolean found = findPetFileInOtherOwnerDir(modDir, currentOwnerUUID, petUUID) != null;
            if (found) noForeignOwnerFileUntil.remove(petUUID);
            else noForeignOwnerFileUntil.put(petUUID, now + FOREIGN_OWNER_CHECK_TTL_NANOS);
            return found;
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
     * 清除某个宠物的所有已存储 NBT 数据与内存缓存。
     * 用于 clear-on-death 实体死亡时——它不应留下任何痕迹。
     */
    private static void clearPetDataAndCache(Entity entity, UUID ownerUUID, ServerLevel level) {
        UUID petUUID = entity.getUUID();
        PetHealingManager.clear(petUUID);
        // 从磁盘删除 NBT 文件
        Path modDir = PetIOUtil.getModDir(level);
        Path ownerDir = modDir.resolve(ownerUUID.toString());
        Path petFile = ownerDir.resolve(petUUID + ".nbt");
        try {
            Files.deleteIfExists(petFile);
            removePetFromTeam(ownerDir, petUUID);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete pet NBT for {}: {}", petUUID, e.getMessage());
        }
        // 清理内存缓存
        pendingPetSaves.remove(petUUID);
        removePendingRemovals(ownerUUID, petUUID);
        trackedPetUUIDs.remove(petUUID);
        forgetPetCaches(petUUID);
        try {
            removePetFromIndex(modDir, petUUID);
        } catch (IOException e) {
            LOGGER.warn("Failed to remove pet from index for {}: {}", petUUID, e.getMessage());
        }
        LOGGER.debug("Cleared pet data and cache for {} (clear-on-death)", petUUID);
    }

    public static boolean deletePetData(ServerPlayer player, UUID petUUID) {
        flushPendingPetSaves(player.getUUID());
        PendingPetSave pending = pendingPetSaves.get(petUUID);
        Path petFile = PetIOUtil.getOwnerDir(player).resolve(petUUID + ".nbt");
        boolean ownsPendingSave = pending != null && player.getUUID().equals(pending.ownerUUID());
        boolean isShoulderPet = PetIOUtil.getShoulderEntity(player, petUUID) != null;
        boolean isLoadedOwnedPet = isLoadedOwnedPet(player, petUUID);
        if (!Files.exists(petFile) && !ownsPendingSave && !isShoulderPet && !isLoadedOwnedPet) return false;

        CompoundTag storedSnapshot = null;
        if (Files.exists(petFile)) {
            try {
                storedSnapshot = NbtFileIO.readCompressed(petFile.toFile());
            } catch (IOException e) {
                LOGGER.error("Failed to inspect pet NBT before deletion for {}: {}", petUUID, e.getMessage());
                return false;
            }
        }

        CompoundTag deadSnapshot = storedSnapshot != null && PetDeathState.isDeadSnapshot(storedSnapshot)
                ? storedSnapshot : null;
        boolean noReviveSnapshot = storedSnapshot != null
                && Config.isNoReviveEntity(storedSnapshot.getString("EntityType"));
        Entity releasedDeadEntity = null;
        if (PetDeathState.shouldReleaseBeforeUntracking(
                storedSnapshot, Config.deleteStoredPetsDirectly, noReviveSnapshot)) {
            if (deadSnapshot != null) {
                UUID releaseHint = PetIOUtil.ownerFromPetFile(petFile.toFile());
                releasedDeadEntity = TeleportPetToPlayerPacket.releaseDeadPetForUntracking(
                        deadSnapshot, petUUID, player, player.serverLevel(),
                        releaseHint != null ? releaseHint : player.getUUID());
                if (releasedDeadEntity == null) {
                    LOGGER.warn("Aborted deletePetData for {}: dead pet could not be released", petUUID);
                    return false;
                }
            } else if (!RecallPetPacket.releaseRecalledPet(player, petUUID, player.serverLevel())) {
                LOGGER.warn("Aborted deletePetData for {}: recalled pet could not be released", petUUID);
                return false;
            }
        }

        pendingPetSaves.remove(petUUID);
        PetHealingManager.clear(petUUID);
        removePendingRemovals(player.getUUID(), petUUID);
        localSyncCandidates.removeIf(candidate -> candidate.entityUUID().equals(petUUID));
        TeleportPetToPlayerPacket.cancelPendingSummons(player.getUUID(), petUUID);
        ReviveProtection.remove(petUUID);
        trackedPetUUIDs.remove(petUUID);
        forgetPetCaches(petUUID);

        try {
            Path modDir = PetIOUtil.getModDir(player);
            removePetTracking(modDir, petUUID);
            deletePetFiles(modDir, petUUID);
            killReleasedPetWithVoidDamage(releasedDeadEntity);
            return true;
        } catch (IOException e) {
            if (releasedDeadEntity != null) releasedDeadEntity.discard();
            LOGGER.error("Failed to delete pet data for {}: {}", petUUID, e.getMessage());
            return false;
        }
    }

    /**
     * 移除属于某个玩家的所有已存储宠物条目，但不将其 UUID 加入黑名单。
     * 已加载的宠物会保留在世界中，并可能被正常的追踪流程重新注册。
     *
     * @return 被清除的不同宠物 UUID 数量；当存储无法更新时为 {@code -1}
     */
    public static int clearAllPetData(ServerPlayer player) {
        UUID ownerUUID = player.getUUID();
        Path modDir = PetIOUtil.getModDir(player);
        Path ownerDir = PetIOUtil.getOwnerDir(player);
        File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();
        Set<UUID> petUUIDs = new HashSet<>();
        List<Path> petFiles = new ArrayList<>();

        try {
            CompoundTag indexTag = indexFile.exists()
                    ? NbtFileIO.readCompressed(indexFile)
                    : new CompoundTag();
            collectIndexedPetUUIDs(indexTag.getCompound(player.getGameProfile().getName()), petUUIDs);

            for (PendingPetSave pending : pendingPetSaves.values()) {
                if (ownerUUID.equals(pending.ownerUUID())) petUUIDs.add(pending.petUUID());
            }
            for (Map.Entry<UUID, UUID> forcedEntry : forcedTrackingOwners.entrySet()) {
                if (ownerUUID.equals(forcedEntry.getValue())) petUUIDs.add(forcedEntry.getKey());
            }
            for (PendingRemoval pending : pendingRemovals) {
                if (ownerUUID.equals(pending.ownerUUID())) petUUIDs.add(pending.petUUID());
            }
            for (ServerLevel level : player.getServer().getAllLevels()) {
                for (Entity entity : level.getEntities().getAll()) {
                    if (trackedPetUUIDs.contains(entity.getUUID()) && isOwnedBy(entity, ownerUUID)) {
                        petUUIDs.add(entity.getUUID());
                    }
                }
            }
            if (Files.isDirectory(ownerDir)) {
                try (var files = Files.list(ownerDir)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        String fileName = file.getFileName().toString();
                        if (!PetIOUtil.isPetDataFileName(fileName)) continue;
                        petFiles.add(file);
                        try {
                            petUUIDs.add(UUID.fromString(fileName.substring(0, fileName.length() - 4)));
                        } catch (IllegalArgumentException ignored) {
                            // 该指令仍会移除格式错误或遗留的 NBT 文件名。
                        }
                    }
                }
            }

            // 先停止排队的工作，使其无法在该指令清理文件时重新创建它们。
            pendingPetSaves.entrySet().removeIf(entry -> ownerUUID.equals(entry.getValue().ownerUUID()));
            for (PendingRemoval pending : new ArrayList<>(pendingRemovals)) {
                if (ownerUUID.equals(pending.ownerUUID())) removePendingRemoval(pending);
            }
            TeleportPetToPlayerPacket.cancelPendingSummons(ownerUUID);

            for (UUID petUUID : petUUIDs) {
                removePetIndexEntry(indexTag, petUUID);
                ForcedTrackingWhitelist.remove(indexTag, petUUID);
            }
            indexTag.remove(player.getGameProfile().getName());
            if (indexFile.exists() || !indexTag.isEmpty()) {
                NbtFileIO.writeCompressed(indexTag, indexFile);
            }
            for (Path petFile : petFiles) Files.deleteIfExists(petFile);
            removeMissingPetsFromTeams(ownerDir);

            PetHealingManager.clearAll(petUUIDs);
            localSyncCandidates.removeIf(candidate -> petUUIDs.contains(candidate.entityUUID()));
            for (UUID petUUID : petUUIDs) {
                pendingPetSaves.remove(petUUID);
                ReviveProtection.remove(petUUID);
                trackedPetUUIDs.remove(petUUID);
                forcedTrackingOwners.remove(petUUID);
                forgetPetCaches(petUUID);
            }
            indexCache.values().forEach(uuids -> uuids.removeAll(petUUIDs));
            indexCache.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            PetSyncTracker.clearPlayer(ownerUUID);
            return petUUIDs.size();
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to clear all pet data for {}: {}", ownerUUID, e.getMessage(), e);
            return -1;
        }
    }

    private static void collectIndexedPetUUIDs(CompoundTag playerTag, Set<UUID> destination) {
        for (String typeKey : playerTag.getAllKeys()) {
            if (!playerTag.contains(typeKey, Tag.TAG_COMPOUND)) continue;
            CompoundTag typeTag = playerTag.getCompound(typeKey);
            for (String uuidString : typeTag.getAllKeys()) {
                try {
                    destination.add(UUID.fromString(uuidString));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private static void killReleasedPetWithVoidDamage(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return;
        DamageSource voidDamage = living.damageSources().fellOutOfWorld();
        living.invulnerableTime = 0;
        living.hurt(voidDamage, Float.MAX_VALUE);
        if (!living.isDeadOrDying()) {
            living.setHealth(0.0F);
            living.die(voidDamage);
        }
    }

    /** 检查某个宠物 UUID 当前是否被追踪（内存中）。 */
    public static boolean isTrackedPet(UUID petUUID) {
        return trackedPetUUIDs.contains(petUUID);
    }

    /**
     * 检查某个 UUID 是否对应一位曾在本服务器游玩过的玩家。
     * 若玩家当前在线，或磁盘上存在 playerdata 文件，则视为“已知”。
     * 这可以防止保存那些 "Owner" NBT 字段并不对应真实玩家的宠物
     * （例如使用非标准归属字段的模组实体）。
     */
    public static boolean isKnownPlayer(net.minecraft.server.MinecraftServer server, UUID uuid) {
        if (server.getPlayerList().getPlayer(uuid) != null) return true;
        Path playerData = server.getWorldPath(LevelResource.ROOT)
                .resolve("playerdata")
                .resolve(uuid + ".dat");
        return Files.exists(playerData);
    }

    /**
     * 从任何可驯服/宠物实体解析出主人 UUID，即使它
     * 未实现 {@link OwnableEntity}。这可覆盖 Ice &amp; Fire 龙
     * 及其它使用基于 NBT 归属的模组。
     */
    public static UUID getCompatOwnerUUID(Entity entity) {
        // 只有生物实体才能成为宠物。这会过滤掉弹射物
        // （投掷的药水、箭……）和 AreaEffectCloud，它们都会在 NBT 中
        // 保存一个 "Owner" UUID，代表投掷者/创建者，
        // 而非宠物归属关系。
        if (!(entity instanceof LivingEntity)) return null;
        // 多部件子部件（例如 Ice & Fire 龙的尾/翼）从不会被
        // 直接追踪——只追踪父实体。父实体是一个拥有自身 UUID 的
        // 独立 Entity，会自行被 onEntityJoinLevel / syncAllPets
        // 处理。此处返回 null 会让所有追踪入口点
        // 跳过子部件。
        if (entity instanceof PartEntity<?>) return null;
        UUID forcedOwner = forcedTrackingOwners.get(entity.getUUID());
        if (forcedOwner != null) return forcedOwner;
        return getEntityOwnerUUID(entity);
    }

    /**
     * 实体自身报告的归属，忽略强制追踪覆盖。从
     * {@link #getCompatOwnerUUID} 拆分出来，以便调用方（例如快照修复）能区分
     * “该实体确实知道自己的主人”与“TBF 为它记录了一个主人”。
     */
    public static UUID getEntityOwnerUUID(Entity entity) {
        if (!(entity instanceof LivingEntity)) return null;
        if (entity instanceof PartEntity<?>) return null;
        // 快速通道：标准的原版/Forge 归属接口
        if (entity instanceof OwnableEntity ownable) {
            UUID ownerUUID = ownable.getOwnerUUID();
            if (ownerUUID != null) return ownerUUID;
        }
        CompoundTag nbt;
        try {
            nbt = entity.saveWithoutId(new CompoundTag());
        } catch (RuntimeException exception) {
            reportEntityNbtSaveFailure(entity, exception);
            return null;
        }
        // 接下来是 TBF 记录的主人，解析时不查询配置，因此在 ownerNbtFields
        // 被清空或裁剪后仍能工作。这里实际上只可能出现纯字符串形式：
        // 快照的 TBF_OwnerUUID 在加载时会被丢弃，因为 Forge/NeoForge
        // 会还原命名字段（外加嵌套的 ForgeData compound）并丢弃未知的根键。
        UUID ownRecord = TbfOwnerTag.read(nbt);
        if (ownRecord != null) return ownRecord;
        // 兼容性：从配置的顶层或嵌套 NBT 路径读取归属。
        return OwnerNbtResolver.resolve(nbt, Config.ownerNbtPaths);
    }

    /**
     * 从一个<b>存储快照 NBT</b>（而非活体实体）解析归属，
     * 顺序与 {@link #getEntityOwnerUUID(Entity)} 的 NBT 部分一致：
     * 先读 TBF 自己的 {@code TBF_OwnerUUID}，再回退到配置的归属路径。
     *
     * <p>用于校验「纯磁盘」宠物是否真的属于某个玩家——这类宠物没有
     * 实体可供查询，只能读快照。解析不查询 {@code OwnableEntity}
     * 接口，因为快照里没有实体对象。</p>
     *
     * @return 快照中记录的主人 UUID；无法解析时返回 {@code null}。
     */
    public static UUID getSnapshotOwnerUUID(CompoundTag nbt) {
        if (nbt == null) return null;
        UUID ownRecord = TbfOwnerTag.read(nbt);
        if (ownRecord != null) return ownRecord;
        return OwnerNbtResolver.resolve(nbt, Config.ownerNbtPaths);
    }

    private static void reportEntityNbtSaveFailure(Entity entity, RuntimeException exception) {
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }

        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        String entityType = typeId != null ? typeId.toString() : entity.getType().toString();
        String exceptionType = rootCause.getClass().getName();
        String exceptionName = rootCause.getClass().getSimpleName();
        if (exceptionName.isEmpty()) exceptionName = exceptionType;
        String exceptionMessage = rootCause.getMessage();
        if (exceptionMessage == null || exceptionMessage.isBlank()) exceptionMessage = "<no message>";

        EntityNbtSaveFailure failure = new EntityNbtSaveFailure(
                entity.getUUID(), exceptionType, exceptionMessage);
        if (!reportedEntityNbtSaveFailures.add(failure)) return;

        LOGGER.error("Failed to serialize NBT for entity {} ({}, {}); skipping entity: {}: {}",
                entity.getName().getString(), entityType, entity.getUUID(),
                exceptionType, exceptionMessage, exception);

        MinecraftServer server = entity.getServer();
        if (!Config.enableLoginLoadDiagnostics || server == null) return;

        Component message = Component.translatable(
                        "trulybestfriends.diagnostics.entity_nbt_save_failed",
                        entity.getName(), entityType, entity.getUUID().toString(),
                        exceptionName, exceptionMessage)
                .withStyle(ChatFormatting.RED);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(message);
        }
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
        if (!indexFile.exists()) {
            forcedTrackingOwners.remove(petUUID);
            return;
        }

        CompoundTag indexTag = NbtFileIO.readCompressed(indexFile);
        boolean changed = removePetIndexEntry(indexTag, petUUID);
        changed |= ForcedTrackingWhitelist.remove(indexTag, petUUID);
        if (changed) {
            NbtFileIO.writeCompressed(indexTag, indexFile);
        }
        forcedTrackingOwners.remove(petUUID);
    }

    private static void removePetTracking(Path modDir, UUID petUUID) throws IOException {
        Files.createDirectories(modDir);
        File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();
        CompoundTag indexTag = indexFile.exists() ? NbtFileIO.readCompressed(indexFile) : new CompoundTag();
        removePetIndexEntry(indexTag, petUUID);
        boolean blacklisted = ForcedTrackingWhitelist.applyRemovalBlacklistPolicy(indexTag, petUUID);
        NbtFileIO.writeCompressed(indexTag, indexFile);
        forcedTrackingOwners.remove(petUUID);
        if (blacklisted) blacklistedPetUUIDs.add(petUUID);
        else blacklistedPetUUIDs.remove(petUUID);
    }

    private static void deletePetFiles(Path modDir, UUID petUUID) throws IOException {
        if (!Files.exists(modDir)) return;
        String fileName = petUUID + ".nbt";
        try (var entries = Files.list(modDir)) {
            for (Path ownerDir : entries.filter(Files::isDirectory).toList()) {
                Files.deleteIfExists(ownerDir.resolve(fileName));
                removePetFromTeam(ownerDir, petUUID);
            }
        }
    }

    private static void removePetFromTeam(Path ownerDir, UUID petUUID) {
        try {
            PetTeamData.removePet(ownerDir, petUUID);
        } catch (IOException e) {
            LOGGER.warn("Failed to remove pet {} from team data in {}: {}",
                    petUUID, ownerDir, e.getMessage());
        }
    }

    private static void removeMissingPetsFromTeams(Path ownerDir) {
        try {
            PetTeamData.ensureAndPrune(ownerDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to prune team data in {}: {}", ownerDir, e.getMessage());
        }
    }

    static boolean addBlacklistEntry(CompoundTag indexTag, UUID petUUID) {
        return PetIndexBlacklist.add(indexTag, petUUID);
    }

    static boolean isBlacklistEntry(CompoundTag indexTag, UUID petUUID) {
        return PetIndexBlacklist.contains(indexTag, petUUID);
    }

    private static void removeBlacklistEntry(CompoundTag indexTag, UUID petUUID) {
        PetIndexBlacklist.remove(indexTag, petUUID);
        blacklistedPetUUIDs.remove(petUUID);
    }

    private static boolean removePetIndexEntry(CompoundTag indexTag, UUID petUUID) {
        String uuid = petUUID.toString();
        boolean changed = false;
        for (String playerName : new ArrayList<>(indexTag.getAllKeys())) {
            if (BLACKLISTED_UUIDS_KEY.equals(playerName)
                    || ForcedTrackingWhitelist.KEY.equals(playerName)) continue;
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
            CompoundTag state = PetIndexState.find(indexTag, petUUID);
            if (state != null && PetIndexState.setRecalled(state, recalled)) {
                NbtFileIO.writeCompressed(indexTag, indexFile);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to update recalled state in pet index for {}: {}", petUUID, e.getMessage());
        }
    }

    public static boolean isPetRideable(ServerLevel level, UUID petUUID) {
        return getRideablePetUUIDs(level).contains(petUUID);
    }

    public static Set<UUID> getRideablePetUUIDs(ServerLevel level) {
        File indexFile = PetIOUtil.getModDir(level).resolve(PETS_INDEX_FILE).toFile();
        if (!indexFile.exists()) return Set.of();
        try {
            Set<UUID> rideable = new HashSet<>();
            PetIndexState.visit(NbtFileIO.readCompressed(indexFile), (uuid, state) -> {
                if (state.getBoolean("Rideable")) rideable.add(uuid);
                return false;
            });
            return rideable;
        } catch (IOException e) {
            LOGGER.error("Failed to read rideable pet states: {}", e.getMessage());
            return Set.of();
        }
    }

    private static void updatePetRideableState(ServerLevel level, UUID petUUID) {
        File indexFile = PetIOUtil.getModDir(level).resolve(PETS_INDEX_FILE).toFile();
        if (!indexFile.exists()) return;
        try {
            CompoundTag indexTag = NbtFileIO.readCompressed(indexFile);
            CompoundTag state = PetIndexState.find(indexTag, petUUID);
            if (state != null && PetIndexState.setRideable(state)) {
                NbtFileIO.writeCompressed(indexTag, indexFile);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to update rideable state for {}: {}", petUUID, e.getMessage());
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
        CompoundTag state;
        if (alreadyInPlace) {
            state = typeTag.getCompound(uuid);
        } else {
            removePetIndexEntry(indexTag, petUUID);
            playerTag = indexTag.getCompound(playerName);
            typeTag = playerTag.getCompound(typeKey);
            state = new CompoundTag();
        }

        if (!PetIndexState.setRecalled(state, recalled)) return false;
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
            String typeKey = ForgeRegistries.ENTITY_TYPES.getKey(pet.getType()).toString();
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

        ResourceLocation entityType = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
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
            entity.discard();
            pendingRemoval.ifPresent(trulybestfriends::removePendingRemoval);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to read recalled pet NBT for {}: {}", petUUID, e.getMessage());
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
                Map<UUID, UUID> loadedForcedOwners = ForcedTrackingWhitelist.readAll(indexTag);
                loadedForcedOwners.keySet().removeAll(blacklistedPetUUIDs);
                forcedTrackingOwners.putAll(loadedForcedOwners);
                trackedPetUUIDs.addAll(forcedTrackingOwners.keySet());
                for (String playerName : indexTag.getAllKeys()) {
                    if (BLACKLISTED_UUIDS_KEY.equals(playerName)
                            || ForcedTrackingWhitelist.KEY.equals(playerName)) continue;
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
        return PetIOUtil.findEntity(player.getServer(), petUUID,
                entity -> isOwnedBy(entity, player.getUUID())) != null;
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
                    : ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString();
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

    private void syncAllPets(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof LivingEntity)) continue;
                syncOwnedEntity(entity, level);
            }
        }
    }

    private void syncTrackedPets(MinecraftServer server) {
        // 性能模式只需刷新已追踪的宠物；按 UUID 查询，避免遍历所有已加载实体。
        for (UUID petUUID : new ArrayList<>(trackedPetUUIDs)) {
            Entity entity = PetIOUtil.findEntity(server, petUUID);
            if (!(entity instanceof LivingEntity)) continue;
            UUID ownerUUID = getCompatOwnerUUID(entity);
            if (ownerUUID != null) savePetData(ownerUUID, entity, (ServerLevel) entity.level());
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
                        new BlockPos(chunk.getMinBlockX(), levelMinY(level), chunk.getMinBlockZ()),
                        new BlockPos(chunk.getMaxBlockX(), levelMaxY(level), chunk.getMaxBlockZ()));
                for (Entity entity : level.getEntities(null, area)) {
                    // 在此处预过滤，而不是在 processLocalSyncCandidates 中：
                    // 区块扫描会返回所有实体（物品、生物、弹射物），
                    // 若把它们全部入队，就意味着每个都会在下一 tick 被重新解析
                    // 并拒绝。只有真正可能成为宠物的实体
                    // 才值得入队。
                    if (!isPetSyncCandidate(entity)) continue;
                    localSyncCandidates.add(new LocalSyncCandidate(dimension, entity.getUUID()));
                }
            }
        }
    }

    /**
     * 当某实体有可能是一只被追踪的宠物、因而值得加入本地同步队列时返回 true。
     *
     * <p>会跳过非生物实体、多部件子部件，以及既未被追踪
     * 也无法解析出主人的实体。</p>
     */
    private boolean isPetSyncCandidate(Entity entity) {
        if (!(entity instanceof LivingEntity)) return false;
        if (entity instanceof PartEntity<?>) return false;
        if (trackedPetUUIDs.contains(entity.getUUID())) return true;
        return getCompatOwnerUUID(entity) != null;
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

    // === Boss 防群殴收回 ===

    private void checkBossRecalls(MinecraftServer server) {
        int configured = Config.bossFightPetLimit;
        if (configured < 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!hasVisibleBossBar(server, player)) continue;
            Set<UUID> teamMembers = currentTeamMemberUuids(player);
            int limit = Math.min(configured, Math.max(0, Config.maxPets - teamMembers.size()));
            recallPetsForBoss(player, limit, teamMembers);
        }
    }

    private boolean hasVisibleBossBar(MinecraftServer server, ServerPlayer player) {
        return server.getCustomBossEvents().getEvents().stream()
                .anyMatch(event -> event.isVisible() && event.getPlayers().contains(player));
    }

    private Set<UUID> currentTeamMemberUuids(ServerPlayer player) {
        Set<UUID> uuids = new HashSet<>();
        try {
            CompoundTag data = PetTeamData.teamData(PetIOUtil.getOwnerDir(player));
            String color = data.getString("SelectedTeam");
            if (!PetTeamData.TEAM_COLORS.contains(color)) color = PetTeamData.TEAM_COLORS.get(0);
            ListTag members = data.getCompound("Teams")
                    .getCompound(color).getList("Members", Tag.TAG_COMPOUND);
            for (Tag raw : members) {
                CompoundTag member = (CompoundTag) raw;
                if (member.hasUUID("UUID")) uuids.add(member.getUUID("UUID"));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read team members for boss recall: {}", e.getMessage());
        }
        return uuids;
    }

    private void recallPetsForBoss(ServerPlayer player, int limit, Set<UUID> teamMembers) {
        ServerLevel level = player.serverLevel();
        List<LivingEntity> candidates = new ArrayList<>();
        ChunkPos center = player.chunkPosition();
        for (int x = center.x - LOCAL_SYNC_CHUNK_RADIUS; x <= center.x + LOCAL_SYNC_CHUNK_RADIUS; x++) {
            for (int z = center.z - LOCAL_SYNC_CHUNK_RADIUS; z <= center.z + LOCAL_SYNC_CHUNK_RADIUS; z++) {
                if (!level.hasChunk(x, z)) continue;
                AABB area = new AABB(
                        x << 4, level.getMinBuildHeight(), z << 4,
                        (x << 4) + 16, level.getMaxBuildHeight(), (z << 4) + 16);
                for (Entity entity : level.getEntities(null, area)) {
                    if (entity instanceof LivingEntity living && living.isAlive()
                            && trackedPetUUIDs.contains(living.getUUID())
                            && isOwnedBy(living, player.getUUID())
                            && !teamMembers.contains(living.getUUID())
                            && living.getFirstPassenger() == null) {
                        candidates.add(living);
                    }
                }
            }
        }
        if (candidates.size() <= limit) return;
        Collections.shuffle(candidates);
        int toRecall = candidates.size() - limit;
        List<Component> recalledNames = new ArrayList<>();
        for (int i = 0; i < toRecall; i++) {
            LivingEntity pet = candidates.get(i);
            Component name = pet.getDisplayName().copy();
            if (recallLoadedPetForBoss(player, pet, level)) {
                recalledNames.add(name);
            }
        }
        if (!recalledNames.isEmpty()) {
            player.displayClientMessage(Component.translatable(
                    "trulybestfriends.boss.space_disorder", joinNames(recalledNames)), false);
        }
    }

    private boolean recallLoadedPetForBoss(ServerPlayer owner, LivingEntity pet, ServerLevel level) {
        pet.ejectPassengers();
        pet.stopRiding();
        if (!RecallPetPacket.savePetToDisk(owner.getUUID(), pet, level)) return false;
        pet.playSound(SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
        pet.discard();
        return true;
    }

    private Component joinNames(List<Component> names) {
        net.minecraft.network.chat.MutableComponent result = Component.empty();
        Component separator = Component.translatable("trulybestfriends.boss.name_separator");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) result.append(separator);
            result.append(names.get(i));
        }
        return result;
    }

    private boolean hasTrulyBestFriendsAdvancement(MinecraftServer server, ServerPlayer player) {
        Advancement advancement = server.getAdvancements().getAdvancement(TRULY_BEST_FRIENDS_ADVANCEMENT);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private void loadPlayerPetsData(ServerPlayer player) {
        try {
            Path ownerDir = PetIOUtil.getOwnerDir(player);

            if (Files.exists(ownerDir)) {
                int[] counts = new int[2]; // [0]=成功, [1]=失败
                try (var files = Files.list(ownerDir)) {
                    files.filter(PetIOUtil::isPetDataFile).forEach(file -> {
                        try {
                            NbtFileIO.readCompressed(file.toFile());
                            counts[0]++;
                        } catch (IOException e) {
                            counts[1]++;
                            LOGGER.error("Failed to load pet data {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
                }
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
		try (var files = Files.list(ownerDir)) {
			return (int) files.filter(PetIOUtil::isPetDataFile).count();
		} catch (IOException e) {
			return 0;
		}
	}
}
