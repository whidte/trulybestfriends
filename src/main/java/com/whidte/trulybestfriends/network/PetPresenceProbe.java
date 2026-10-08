package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 独立的「存在性探测」队列。为配置项 {@code presenceProbeWhitelist} 里列出的实体类型查实
 * 其宠物到底在不在世界上。
 *
 * <p>玩家每次打开宠物标签页（{@code RequestPetDataPacket} 的全量列表分支）时调用
 * {@link #requestFor(ServerPlayer)}，把该玩家名下命中名单、且「本该在世界里」的宠物排进队列；
 * 每个 tick 由 {@code trulybestfriends.onServerTick} 驱动 {@link #tick(MinecraftServer)} 逐个复查。
 * <b>确证不在世界上</b>的宠物会被从宠物列表、磁盘 NBT 与内存缓存中一并删除。</p>
 *
 * <h2>为什么需要队列</h2>
 * <p>{@code level.getEntity(uuid)} 只看得见<b>已加载</b>的实体——未加载区块里的实体根本不在
 * {@code entityManager} 里，查询必然为 null，与「真不存在」不可区分。所以「区块是否已加载」是唯一的分水岭：
 * 区块已加载却找不到 ⇒ 确证不存在；区块未加载 ⇒ 状态未知，必须强制加载区块并跨 tick 复查
 * （实体加载是异步的，同 tick 拿不到）。这与 {@code pendingSummons} / {@code pendingRemovals} 同源。</p>
 *
 * <h2>与另外两条队列的区别</h2>
 * <p>这里<b>不改动世界</b>：不传送、不召唤、不 discard 实体。唯一的副作用是「强制加载区块」，
 * 以及确证缺失后的数据删除。</p>
 *
 * <h2>判定为什么保守</h2>
 * <p>删除是破坏性操作，所以即使区块当下已加载，也要连续观察满 {@link #MIN_CONFIRM_TICKS} 个 tick
 * 仍找不到实体才认定「确证不在」——避开「区块刚好在本 tick 加载、实体尚未加入实体管理器」的抢跑窗口。
 * 超过 {@link #MAX_PROBE_TICKS} 仍无结论则放弃并<b>保留数据</b>（失败方向是「留着」而不是「删掉」）。</p>
 */
public final class PetPresenceProbe {

    /** 存在性探测的三态结论，外加「确认窗口未满」的过渡态。 */
    enum Presence {
        /** 已加载且找得到实体：确实在世界上。 */
        IN_WORLD,
        /** 区块已加载，但确认窗口还没走完：暂不下结论。 */
        PENDING,
        /** 区块未加载：查询必然为空，状态未知，需要强制加载后复查。 */
        UNLOADED,
        /** 区块已加载、且观察满确认窗口仍找不到实体：确证不在世界上。 */
        MISSING
    }

    /** 即使区块当下已加载，也要连续观察这么久才敢认定「确证不在」。20 tick = 1 秒。 */
    static final int MIN_CONFIRM_TICKS = 20;

    /** 单只宠物最多探测多久；超时视为状态未知、保留数据。100 tick = 5 秒（与召唤队列同量级）。 */
    static final int MAX_PROBE_TICKS = 100;

    // 所有操作都在服务端主线程执行，普通列表即可避免 CopyOnWrite 的整表复制。
    private static final List<Probe> pendingProbes = new ArrayList<>();

    /** 本轮已清理数量，按主人聚合；队列排空后汇总成一条聊天提示。 */
    private static final Map<UUID, Integer> cleanedByPlayer = new ConcurrentHashMap<>();

    private PetPresenceProbe() {}

    /**
     * 三态判定。纯函数：只吃「实体是否已加载 / 区块是否已加载 / 已观察多少 tick」，
     * 不依赖任何世界状态，可直接单测（见 {@code PetPresenceProbeSmokeTest}）。
     */
    static Presence classify(boolean entityLoaded, boolean chunkLoaded, int ticksElapsed) {
        if (entityLoaded) return Presence.IN_WORLD;
        if (!chunkLoaded) return Presence.UNLOADED;
        return ticksElapsed >= MIN_CONFIRM_TICKS ? Presence.MISSING : Presence.PENDING;
    }

    /**
     * 玩家打开宠物标签页时调用：把该玩家名下命中名单、且本该在世界里的宠物排进探测队列。
     *
     * <p>调用点必须在本轮待落盘快照刷完之后（{@code RequestPetDataPacket} 已经这么做了），
     * 否则读到的是过期快照。</p>
     */
    public static void requestFor(ServerPlayer player) {
        requestFor(player, null);
    }

    /**
     * 与 {@link #requestFor(ServerPlayer)} 相同，但可复用调用方已解析好的宠物快照。
     *
     * <p>打开宠物界面时 {@code RequestPetDataPacket} 刚把同一批文件全部读过一遍，
     * 把结果传进来即可省掉第二轮读盘；传入 null 或某个 UUID 缺失时自动回退到读盘，
     * 行为与原实现一致。</p>
     */
    public static void requestFor(ServerPlayer player, Map<UUID, CompoundTag> preRead) {
        if (Config.presenceProbeWhitelist.isEmpty()) return;

        File[] files = PetIOUtil.getOwnerDir(player).toFile()
                .listFiles((dir, name) -> PetIOUtil.isPetDataFileName(name));
        if (files == null) return;

        int limit = Math.max(1, Config.maxPets);
        int queued = 0;
        for (File file : files) {
            if (queued >= limit) break;

            UUID petUuid = petUuidOf(file);
            if (petUuid == null || isPending(player.getUUID(), petUuid)) continue;

            CompoundTag nbt = preRead != null ? preRead.get(petUuid) : null;
            if (nbt == null) {
                try {
                    nbt = NbtFileIO.readCompressed(file);
                } catch (IOException e) {
                    trulybestfriends.LOGGER.warn("Presence probe: failed to read pet file {}: {}",
                            file, e.getMessage());
                    continue;
                }
            }

            if (!shouldProbe(player, petUuid, nbt)) continue;

            ServerLevel petLevel = PetIOUtil.getLevel(player.server, nbt.getString("Dimension"));
            if (petLevel == null) continue;
            // 区块坐标用**未投影**的原始 Pos / ChunkX、ChunkZ：子级内的实体在父维度里就是登记在
            // plot 网格那一格上（与 RecallPetPacket / TeleportPetToPlayerPacket 同一口径）。
            ChunkPos storedChunk = PetIOUtil.getStoredChunk(nbt);
            if (storedChunk == null) continue;
            // 已经找得到实体就没必要探测。
            if (PetIOUtil.findEntity(player.server, petUuid) != null) continue;

            pendingProbes.add(new Probe(petUuid, player.getUUID(), petLevel,
                    storedChunk.x, storedChunk.z, nbt.getString("EntityType")));
            queued++;
        }
    }

    /** 只有「本该在世界里」的宠物才值得探测。 */
    private static boolean shouldProbe(ServerPlayer player, UUID petUuid, CompoundTag nbt) {
        // 已收回的宠物按设计就没有世界实体，不是「消失」。
        if (nbt.getBoolean("Recalled")) return false;
        // 死亡快照也没有世界实体，等玩家复活，别当成缺失删掉。
        if (PetDeathState.isDeadSnapshot(nbt)) return false;
        if (!Config.isPresenceProbeEntity(nbt.getString("EntityType"))) return false;
        // 被玩家扛在肩上的宠物不在世界里是正常的。
        return PetIOUtil.getShoulderEntity(player, petUuid) == null;
    }

    /** 每个 tick 由 {@code trulybestfriends.onServerTick} 调用。 */
    public static void tick(MinecraftServer server) {
        tickProbes(server);
        flushCleanupNotices(server);
    }

    private static void tickProbes(MinecraftServer server) {
        if (pendingProbes.isEmpty()) return;

        // 每 tick 至多强制加载一个区块：这是唯一会惊动区块系统的操作，串起来做更温和。
        // 排队等名额的探测不计入超时预算，否则列表很长时后面的宠物会被白白超时放弃。
        boolean chunkRetainedThisTick = false;

        for (Iterator<Probe> iterator = pendingProbes.iterator(); iterator.hasNext();) {
            Probe probe = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(probe.ownerUuid);
            if (player == null) {
                // 玩家已登出：释放区块并放弃本轮探测（数据不变）。
                finish(iterator, probe);
                continue;
            }
            if (probe.level.getServer() != server) {
                finish(iterator, probe);
                continue;
            }

            boolean entityLoaded = PetIOUtil.findEntity(server, probe.petUuid) != null;
            boolean chunkLoaded = probe.level.hasChunk(probe.chunkX, probe.chunkZ);

            // 区块未加载、且我们还没强制加载过它：申请名额（同一 tick 只发一个）。
            if (!chunkLoaded && !probe.chunkRetained) {
                if (chunkRetainedThisTick) continue;
                trulybestfriends.retainForcedChunk(probe.level, probe.chunkX, probe.chunkZ);
                probe.chunkRetained = true;
                chunkRetainedThisTick = true;
            }

            probe.ticksAlive++;
            Presence presence = classify(entityLoaded, chunkLoaded, probe.ticksAlive);

            if (presence == Presence.IN_WORLD) {
                finish(iterator, probe);
                continue;
            }
            if (presence == Presence.MISSING) {
                removeMissingPet(player, probe);
                finish(iterator, probe);
                continue;
            }
            // PENDING / UNLOADED：继续等。UNLOADED 时区块已在上面申请加载，
            // 但快照里的区块坐标可能已过期（宠物又走远了），所以失败方向是保留数据。
            if (probe.ticksAlive >= MAX_PROBE_TICKS) finish(iterator, probe);
        }
    }

    /**
     * 确证不在世界上：走与手动删除按钮完全相同的流程
     * （{@code deletePetData} 负责磁盘 NBT、索引、队伍与内存缓存），
     * 再补上客户端列表条的删除与同步追踪器的遗忘。
     */
    private static void removeMissingPet(ServerPlayer player, Probe probe) {
        if (!trulybestfriends.deletePetData(player, probe.petUuid)) {
            trulybestfriends.LOGGER.warn("Presence probe: could not remove missing pet {}", probe.petUuid);
            return;
        }
        PetSyncTracker.forgetPet(player.getUUID(), probe.petUuid);
        SyncPetDataPacket.sendToPlayer(player, SyncPetDataPacket.delete(probe.petUuid));
        cleanedByPlayer.merge(probe.ownerUuid, 1, Integer::sum);
        trulybestfriends.LOGGER.info(
                "Presence probe: removed pet {} ({}) because it no longer exists in the world",
                probe.petUuid, probe.typeKey);
    }

    /** 队列排空后，把本轮的清理结果汇总成一条聊天提示。 */
    private static void flushCleanupNotices(MinecraftServer server) {
        if (cleanedByPlayer.isEmpty()) return;

        for (UUID ownerUuid : new ArrayList<>(cleanedByPlayer.keySet())) {
            if (pendingProbes.stream().anyMatch(probe -> probe.ownerUuid.equals(ownerUuid))) continue;

            Integer cleaned = cleanedByPlayer.remove(ownerUuid);
            ServerPlayer player = server.getPlayerList().getPlayer(ownerUuid);
            if (cleaned == null || cleaned <= 0 || player == null) continue;
            player.displayClientMessage(
                    Component.translatable("trulybestfriends.probe.cleaned", cleaned), false);
        }
    }

    private static boolean isPending(UUID ownerUuid, UUID petUuid) {
        return pendingProbes.stream().anyMatch(probe ->
                probe.ownerUuid.equals(ownerUuid) && probe.petUuid.equals(petUuid));
    }

    private static UUID petUuidOf(File file) {
        String name = file.getName();
        try {
            return UUID.fromString(name.substring(0, name.length() - 4));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void finish(Iterator<Probe> iterator, Probe probe) {
        if (probe.chunkRetained) {
            trulybestfriends.releaseForcedChunk(probe.level, probe.chunkX, probe.chunkZ);
        }
        iterator.remove();
    }

    /** 服务端停止时丢弃全部队列并释放强制加载的区块。 */
    public static void clear() {
        for (Probe probe : pendingProbes) {
            if (probe.chunkRetained) {
                trulybestfriends.releaseForcedChunk(probe.level, probe.chunkX, probe.chunkZ);
            }
        }
        pendingProbes.clear();
        cleanedByPlayer.clear();
    }

    private static final class Probe {
        final UUID petUuid;
        final UUID ownerUuid;
        final ServerLevel level;
        final int chunkX;
        final int chunkZ;
        /** 仅用于日志与提示。 */
        final String typeKey;
        int ticksAlive;
        boolean chunkRetained;

        Probe(UUID petUuid, UUID ownerUuid, ServerLevel level, int chunkX, int chunkZ, String typeKey) {
            this.petUuid = petUuid;
            this.ownerUuid = ownerUuid;
            this.level = level;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.typeKey = typeKey;
        }
    }
}
