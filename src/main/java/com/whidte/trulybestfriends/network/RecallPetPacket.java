package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class RecallPetPacket implements CustomPacketPayload {
    public static final Type<RecallPetPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "recall_pet"));
    public static final StreamCodec<FriendlyByteBuf, RecallPetPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), RecallPetPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private final UUID petUuid;

    public RecallPetPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(RecallPetPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static RecallPetPacket decode(FriendlyByteBuf buf) {
        return new RecallPetPacket(buf.readUUID());
    }

    /** 服务端根据实际世界状态决定动作，而非客户端猜测。
     *  与 TeleportPetToPlayerPacket 的严格实体存在性检查保持一致：
     *  先搜索宠物所存储的维度，再回退到区块强制加载。 */
    public static void handle(RecallPetPacket packet, IPayloadContext context) {
        handle(packet, context, true);
    }

    static void handleWithoutRideSwap(UUID petUuid, IPayloadContext context) {
        handle(new RecallPetPacket(petUuid), context, false);
    }

    private static void handle(RecallPetPacket packet, IPayloadContext context, boolean allowRideSwap) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;
            ServerLevel playerLevel = player.serverLevel();

            // --- 情况 1：宠物存活于玩家当前所在维度 ---
            Entity entity = playerLevel.getEntity(packet.petUuid);

            // 多部件子部件（例如末影龙的尾巴）从不被直接追踪
            // ——拒绝收回它们，以免在没有其父级的情况下丢弃某个部件，
            // 从而破坏多部件实体。
            if (entity instanceof PartEntity<?>) return;

            if (entity instanceof LivingEntity living && living.isAlive()) {
                // 如果实体已被取消追踪，说明其数据已被清除
                // （例如 clearOnDeath 白名单、手动删除）。拒绝收回，
                // 以防重新创建本应消失的宠物。
                if (!trulybestfriends.isTrackedPet(packet.petUuid)
                        || !trulybestfriends.isOwnedBy(living, player.getUUID())) return;

                if (Config.recallRange < 0 || entity.distanceTo(player) <= Config.recallRange) {
                    // RECALL：宠物存活于世界中 → 保存并移除
                    // 强制下坐骑：在保存前弹出所有乘客并让宠物从其载具上下来，
                    // 否则保存的 NBT / 乘客引用会过期。
                    living.ejectPassengers();
                    living.stopRiding();
                    if (savePetToDisk(player.getUUID(), living, playerLevel)) {
                        living.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
                        living.discard();
                    }
                }
                return;
            }

            // 实体存在于当前维度，但不是生物/存活实体 → 不做任何事
            if (entity != null) return;

            // --- 不在玩家当前维度：先检查肩上，再检查磁盘 ---
            var shoulderNbt = PetIOUtil.getShoulderEntity(player, packet.petUuid);
            if (shoulderNbt != null) {
                trulybestfriends.flushPendingPetSaves(player.getUUID());
                if (PetIOUtil.saveShoulderToDisk(player.getUUID(), shoulderNbt, playerLevel)) {
                    PetIOUtil.clearShoulderSlot(player, packet.petUuid);
                    player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
                }
                return;
            }

            // --- 检查磁盘 NBT ---
            Path ownerDir = PetIOUtil.getOwnerDir(player);
            File nbtFile = ownerDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) return;  // 从未被追踪，不做任何事

            CompoundTag nbt;
            try {
                nbt = NbtFileIO.readCompressed(nbtFile);
            } catch (IOException e) {
                trulybestfriends.LOGGER.error("Failed to read pet NBT for recall: {}", e.getMessage());
                return;
            }

            // --- SUMMON 路径：宠物已被收回至磁盘 → 释放回世界 ---
            if (nbt.getBoolean("Recalled")) {
                if (trulybestfriends.isPendingRemoval(player.getUUID(), packet.petUuid)) {
                    PetWarningPacket.send(player, 2, packet.petUuid);
                    return;
                }
                if (allowRideSwap && !PetDeathState.isDeadSnapshot(nbt)
                        && TeleportPetToPlayerPacket.trySwapRecalledPet(
                        player, packet.petUuid, nbt, nbtFile, playerLevel)) {
                    return;
                }
                if (!releaseRecalledPet(player, packet.petUuid, playerLevel)) {
                    PetWarningPacket.send(player, 3, packet.petUuid);
                }
                return;
            }

            // --- RECALL 路径：宠物按说存活于某处（磁盘上未标记 Recalled）---
            // 死亡的宠物无法被收回（请改用复活）
            if (PetDeathState.isDeadSnapshot(nbt)) return;

            // 从 NBT 解析宠物最后已知的维度
            ServerLevel resolved = PetIOUtil.getLevel(player.server, nbt.getString("Dimension"));
            ServerLevel petLevel = resolved != null ? resolved : playerLevel;

            // --- 情况 2：宠物存活于其存储的维度（与玩家相同或不同）---
            Entity petEntity = petLevel.getEntity(packet.petUuid);
            if (petEntity instanceof LivingEntity living && living.isAlive()) {
                if (!trulybestfriends.isTrackedPet(packet.petUuid)
                        || !trulybestfriends.isOwnedBy(living, player.getUUID())) return;

                // 范围检查：同一维度使用真实距离；跨维度
                // 则允许收回（玩家明确选择从另一个维度收回）
                if (Config.recallRange >= 0 && petLevel == playerLevel) {
                    if (living.distanceTo(player) > Config.recallRange) return;
                }

                living.ejectPassengers();
                living.stopRiding();
                if (savePetToDisk(player.getUUID(), living, petLevel)) {
                    living.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
                    living.discard();
                }
                return;
            }

            // --- 情况 3：在存储维度中未找到宠物 → 检查区块加载状态 ---
            // 区块按**未投影**的原始 Pos / ChunkX、ChunkZ 取：子级内的实体在父维度中就是登记在
            // Sable 的 plot 网格那一格上（Sable 判定「在不在子级内」用的也是 entity.chunkPosition()），
            // 所以这里不需要 projectToWorld——投影后的全局坐标反而会指向错误的区块。
            var storedChunk = PetIOUtil.getStoredChunk(nbt);
            if (storedChunk == null) return;  // 没有位置信息
            int cx = storedChunk.x;
            int cz = storedChunk.z;

            // 如果区块确实已加载，却未找到实体，说明宠物确实
            // 不存在（已被移除/死亡）。警告玩家并保持磁盘
            // 条目不变 —— 玩家可用删除模式手动清理。
            if (petLevel.hasChunk(cx, cz)) {
                trulybestfriends.LOGGER.debug("Recall: pet {} not found in loaded chunk {},{}", packet.petUuid, cx, cz);
                PetWarningPacket.send(player, 3, packet.petUuid);
                return;
            }

            // --- 情况 4：区块未加载 → 强制加载并排队移除 ---
            // 使用 NBT Pos 做范围检查（这是我们唯一拥有的位置信息）。
            // 判定必须走 Entity#distanceToSqr(double,double,double)：Sable 用 @Overwrite 把它换成
            // SableCompanion.distanceSquaredWithSubLevels——把两侧坐标都投影到全局空间再算距离。
            // 自己手算 dx/dy/dz 在坐标位于子级 plot 网格内时会得到极端值，从而误判为「太远」
            // 而静默拒收。未安装 Sable 时这就是普通距离，行为不变。
            if (Config.recallRange >= 0 && nbt.contains("Pos")) {
                var posList = nbt.getList("Pos", 6);
                if (posList.size() >= 3
                        && player.distanceToSqr(posList.getDouble(0), posList.getDouble(1), posList.getDouble(2))
                        > Config.recallRange * Config.recallRange) {
                    return;
                }
            }

            trulybestfriends.flushPendingPetSaves(player.getUUID());
            nbt.putBoolean("Recalled", true);
            try {
                PetIOUtil.writePetState(nbtFile, nbt, petLevel, packet.petUuid);
            } catch (IOException e) {
                trulybestfriends.LOGGER.error("Failed to write Recalled flag for {}: {}", packet.petUuid, e.getMessage());
                return;
            }

            if (!trulybestfriends.queuePendingRemoval(player.getUUID(), packet.petUuid, petLevel, cx, cz)) {
                nbt.remove("Recalled");
                try {
                    PetIOUtil.writePetState(nbtFile, nbt, petLevel, packet.petUuid);
                } catch (IOException rollbackError) {
                    trulybestfriends.LOGGER.error("Failed to roll back queued recall for {}: {}",
                            packet.petUuid, rollbackError.getMessage(), rollbackError);
                }
                PetWarningPacket.send(player, 2, packet.petUuid);
                return;
            }

            player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
        });

    }

    public static boolean savePetToDisk(UUID playerUuid, LivingEntity pet, ServerLevel level) {
        return savePetToDisk(playerUuid, pet, level, true);
    }

    static boolean savePetToDisk(UUID playerUuid, LivingEntity pet, ServerLevel level, boolean recalled) {
        try {
            Path ownerDir = PetIOUtil.getOwnerDir(level, playerUuid);
            Files.createDirectories(ownerDir);

            File nbtFile = ownerDir.resolve(pet.getUUID() + ".nbt").toFile();

            CompoundTag nbt = PetEntitySnapshot.capture(pet, playerUuid, level);
            PetIOUtil.writePetSnapshot(nbtFile, nbt, recalled);
            trulybestfriends.updatePetRecalledState(level, pet.getUUID(), recalled);
            return true;
        } catch (IOException | RuntimeException e) {
            trulybestfriends.LOGGER.error("Failed to save pet {}: {}", pet.getUUID(), e.getMessage(), e);
            return false;
        }
    }

    private static boolean summonPet(ServerPlayer player, UUID petUuid, ServerLevel level,
                                     CompoundTag nbt, File nbtFile) {
        UUID ownerHint = PetIOUtil.ownerFromPetFile(nbtFile);
        return TeleportPetToPlayerPacket.summonFromDisk(nbt, petUuid, player, level,
                ownerHint != null ? ownerHint : player.getUUID());
    }

    /**
     * 把一只已收回的宠物释放回世界：清除磁盘上的 {@code Recalled}
     * 标志，并在玩家附近召唤该实体。由
     * {@link #handle}（切换动作）及 {@code trulybestfriends.deletePetData}
     * 使用（删除前释放，以免实体随其 NBT 文件一起消失）。
     *
     * <p>返回值：
     * <ul>
     *   <li>{@code true} —— 宠物磁盘上没有 NBT、不处于 {@code Recalled} 状态、
     *       是死亡快照（仅清除了标志），或已成功
     *       召唤回世界。</li>
     *   <li>{@code false} —— NBT 读取失败、{@code isPendingRemoval} 阻止了
     *       释放、{@code Recalled} 标志无法清除，或召唤
     *       失败（此时标志会回滚为 {@code true}）。</li>
     * </ul>
     *
     * <p>注意：此方法<b>不</b>检查 {@code isPendingRemoval} —— 需要区分该情形的
     * 调用方（例如 {@link #handle}）必须在调用此方法前自行检查。</p>
     */
    public static boolean releaseRecalledPet(ServerPlayer player, UUID petUuid, ServerLevel level) {
        Path ownerDir = PetIOUtil.getOwnerDir(player);
        File nbtFile = ownerDir.resolve(petUuid + ".nbt").toFile();
        if (!nbtFile.exists()) return true;

        CompoundTag nbt;
        try {
            nbt = NbtFileIO.readCompressed(nbtFile);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to read pet NBT for release: {}", e.getMessage());
            return false;
        }

        if (!nbt.getBoolean("Recalled")) return true;

        CompoundTag recalledSnapshot = nbt.copy();
        try {
            if (PetDeathState.isDeadSnapshot(nbt)) {
                // 死亡宠物：只清除过期的 Recalled 标志，不要召唤一具尸体
                nbt.remove("Recalled");
                PetIOUtil.writePetState(nbtFile, nbt, level, petUuid);
                return true;
            }
            nbt.remove("Recalled");
            PetIOUtil.writePetState(nbtFile, nbt, level, petUuid);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to clear Recalled flag for {}: {}", petUuid, e.getMessage());
            return false;
        }

        if (summonPet(player, petUuid, level, nbt, nbtFile)) {
            player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
            return true;
        }

        // 召唤失败：回滚 Recalled 标志，以免宠物丢失
        try {
            PetIOUtil.writePetState(nbtFile, recalledSnapshot, level, petUuid);
        } catch (IOException rollbackError) {
            trulybestfriends.LOGGER.error("Failed to roll back recalled state for {}: {}",
                    petUuid, rollbackError.getMessage(), rollbackError);
        }
        return false;
    }
}
