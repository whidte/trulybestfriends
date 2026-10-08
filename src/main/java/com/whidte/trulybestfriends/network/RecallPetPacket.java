package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.network.NetworkEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

public class RecallPetPacket {
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
    public static void handle(RecallPetPacket packet, Supplier<NetworkEvent.Context> ctx) {
        handle(packet, ctx, true);
    }

    static void handleWithoutRideSwap(UUID petUuid, Supplier<NetworkEvent.Context> ctx) {
        handle(new RecallPetPacket(petUuid), ctx, false);
    }

    private static void handle(RecallPetPacket packet, Supplier<NetworkEvent.Context> ctx,
                               boolean allowRideSwap) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
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
            // 使用 NBT Pos 做范围检查（这是我们唯一拥有的位置信息）
            if (Config.recallRange >= 0 && nbt.contains("Pos")) {
                var posList = nbt.getList("Pos", 6);
                if (posList.size() >= 3) {
                    double dx = posList.getDouble(0) - player.getX();
                    double dy = posList.getDouble(1) - player.getY();
                    double dz = posList.getDouble(2) - player.getZ();
                    if (Math.sqrt(dx * dx + dy * dy + dz * dz) > Config.recallRange) return;
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
        ctx.get().setPacketHandled(true);
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
     * 把一只已收回的宠物释放回世界。磁盘标志在召唤前被清除，
     * 若召唤失败则恢复。
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

        try {
            PetIOUtil.writePetState(nbtFile, recalledSnapshot, level, petUuid);
        } catch (IOException rollbackError) {
            trulybestfriends.LOGGER.error("Failed to roll back recalled state for {}: {}",
                    petUuid, rollbackError.getMessage(), rollbackError);
        }
        return false;
    }
}
