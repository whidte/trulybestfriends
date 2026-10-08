package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.function.Supplier;

/** 服务端：把已释放（未收回）的宠物传送到玩家当前位置。 */
public class TeleportPetToPlayerPacket {
    private enum RideSwapResult { SUCCESS, NO_SPACE, FAILED }

    private static final Set<UUID> UNTRACKED_DEATH_RELEASES = ConcurrentHashMap.newKeySet();
    private final UUID petUuid;

    public TeleportPetToPlayerPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(TeleportPetToPlayerPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static TeleportPetToPlayerPacket decode(FriendlyByteBuf buf) {
        return new TeleportPetToPlayerPacket(buf.readUUID());
    }

    public static void handle(TeleportPetToPlayerPacket packet, Supplier<NetworkEvent.Context> ctx) {
        handle(packet, ctx, true);
    }

    static void handleWithoutRideSwap(UUID petUuid, Supplier<NetworkEvent.Context> ctx) {
        handle(new TeleportPetToPlayerPacket(petUuid), ctx, false);
    }

    private static void handle(TeleportPetToPlayerPacket packet, Supplier<NetworkEvent.Context> ctx,
                               boolean allowRideSwap) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel playerLevel = player.serverLevel();
            LivingEntity rideSwapMount = allowRideSwap
                    ? getRideSwapMount(player, packet.petUuid, null)
                    : null;
            UUID rideSwapMountUuid = rideSwapMount != null ? rideSwapMount.getUUID() : null;

            // 情况 1：宠物在玩家当前维度中存活——直接传送它
            Entity entity = playerLevel.getEntity(packet.petUuid);
            if (entity instanceof LivingEntity living && living.isAlive()) {
                if (!trulybestfriends.isTrackedPet(packet.petUuid)
                        || !trulybestfriends.isOwnedBy(living, player.getUUID())) return;
                if (rideSwapMountUuid != null) {
                    RideSwapResult result = swapToLoadedTarget(player, living, rideSwapMountUuid);
                    if (result != RideSwapResult.SUCCESS) {
                        PetWarningPacket.send(player, result == RideSwapResult.NO_SPACE ? 4 : 1, packet.petUuid);
                    }
                    return;
                }
                teleportEntityToPlayer(living, player, playerLevel);
                return;
            }

            // 读取 NBT 以进行跨维度查找
            java.nio.file.Path ownerDir = PetIOUtil.getOwnerDir(player);
            File nbtFile = ownerDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) {
                PetWarningPacket.send(player, 1, packet.petUuid); // 丢失 / 无数据
                return;
            }

            CompoundTag nbt;
            try {
                nbt = NbtFileIO.readCompressed(nbtFile);
            } catch (IOException e) {
                PetWarningPacket.send(player, 1, packet.petUuid);
                return;
            }

            // 过滤：已收回或已死亡的宠物不应被召唤
            if (nbt.getBoolean("Recalled")) {
                PetWarningPacket.send(player, 0, packet.petUuid); // 已收回
                return;
            }
            if (PetDeathState.isDeadSnapshot(nbt)) {
                PetWarningPacket.send(player, 1, packet.petUuid); // 死亡
                return;
            }

            // 从 NBT 解析宠物所在维度（无需扫描所有维度）
            ServerLevel petLevel = resolvePetLevel(player.server, nbt);
            if (petLevel == null) {
                PetWarningPacket.send(player, 1, packet.petUuid); // 维度未知
                return;
            }

            // 情况 2：宠物在另一个维度中存活（或同一维度中已加载的区块）——
            // 找到并丢弃原实体，然后在玩家位置从磁盘召唤
            Entity petEntity = petLevel.getEntity(packet.petUuid);
            if (petEntity instanceof LivingEntity living && living.isAlive()) {
                if (!trulybestfriends.isTrackedPet(packet.petUuid)
                        || !trulybestfriends.isOwnedBy(living, player.getUUID())) return;
                if (rideSwapMountUuid != null) {
                    RideSwapResult result = swapFromOtherLevel(player, living, petLevel, rideSwapMountUuid);
                    if (result != RideSwapResult.SUCCESS) {
                        PetWarningPacket.send(player, result == RideSwapResult.NO_SPACE ? 4 : 1, packet.petUuid);
                    }
                    return;
                }
                if (!RecallPetPacket.savePetToDisk(player.getUUID(), living, petLevel, false)) {
                    PetWarningPacket.send(player, 1, packet.petUuid);
                    return;
                }
                try {
                    CompoundTag freshSnapshot = NbtFileIO.readCompressed(nbtFile);
                    if (summonFromDisk(freshSnapshot, packet.petUuid, player, playerLevel,
                            ownerHintFor(nbtFile, player))) {
                        living.discard();
                    } else {
                        PetWarningPacket.send(player, 1, packet.petUuid);
                    }
                } catch (IOException e) {
                    trulybestfriends.LOGGER.error("Failed to reload pet snapshot {}: {}",
                            packet.petUuid, e.getMessage());
                }
                return;
            }

            // 情况 3：宠物位于未加载的区块中——强制加载并推迟到下一个 tick。
            // setChunkForced 无法在同一 tick 内返回实体（实体加载是异步的），
            // 因此我们把请求排入队列，并在 onServerTick 中处理。
            // ChunkX/ChunkZ 由实体的 Pos 推导而来（原版 NBT 只存储
            // 世界坐标，不存储区块坐标）。
            var storedChunk = PetIOUtil.getStoredChunk(nbt);
            if (storedChunk != null) {
                int cx = storedChunk.x;
                int cz = storedChunk.z;
                // 如果区块已加载但未找到该实体，
                // 说明它确实不存在——不要浪费待处理队列中的时间。
                if (petLevel.hasChunk(cx, cz)) {
                    PetWarningPacket.send(player, 1, packet.petUuid);
                    return;
                }

                // 每名玩家的限制：防止快速重复召唤导致队列无限增长
                long playerPending = pendingSummons.stream()
                        .filter(p -> p.playerUuid.equals(player.getUUID()))
                        .count();
                if (playerPending >= maxPendingPerPlayer()) {
                    PetWarningPacket.send(player, 2, packet.petUuid);
                    return;
                }
                boolean alreadyPending = pendingSummons.stream().anyMatch(pending ->
                        pending.playerUuid.equals(player.getUUID())
                                && pending.petUuid.equals(packet.petUuid));
                if (alreadyPending) {
                    PetWarningPacket.send(player, 2, packet.petUuid);
                    return;
                }
                trulybestfriends.flushPendingPetSaves(player.getUUID());
                trulybestfriends.retainForcedChunk(petLevel, cx, cz);
                pendingSummons.add(new PendingSummon(
                        packet.petUuid, player.getUUID(), cx, cz, petLevel, rideSwapMountUuid));
            } else {
                PetWarningPacket.send(player, 1, packet.petUuid);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void teleportEntityToPlayer(LivingEntity entity, ServerPlayer player, ServerLevel level) {
        standPetUp(entity);

        // 如果宠物是 Mob、距离在 16 格以内且能寻路到玩家——
        // 就让它走到玩家附近的安全点，而不是传送。
        if (entity instanceof Mob mob && mob.isAlive()) {
            double distance = entity.position().distanceTo(player.position());
            if (distance <= 16.0) {
                BlockPos safePos = findSafeBlockNearPlayer(level, player, entity);
                if (safePos != null) {
                    PathNavigation nav = mob.getNavigation();
                    Path path = nav.createPath(safePos, 1);
                    if (path != null && path.canReach()) {
                        nav.moveTo(path, 1.2);
                        return;
                    }
                }
            }
        }

        // 兜底：直接传送
        int radius = Math.max(1, (int) Math.ceil(entity.getBbWidth()));
        Vec3 safePosition = PetIOUtil.findSafePositionNearPlayer(level, player, entity, radius, 6, 16);
        if (safePosition != null) {
            entity.teleportTo(safePosition.x, safePosition.y, safePosition.z);
            entity.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
            return;
        }
        entity.teleportTo(player.getX(), player.getY(), player.getZ());
        entity.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
    }

    private static LivingEntity getRideSwapMount(ServerPlayer player, UUID targetPetUuid,
                                                  UUID expectedMountUuid) {
        if (!trulybestfriends.isTrackedPet(targetPetUuid)
                || !trulybestfriends.isPetRideable(player.serverLevel(), targetPetUuid)) return null;
        if (!(player.getVehicle() instanceof LivingEntity mount)
                || mount.getUUID().equals(targetPetUuid)
                || expectedMountUuid != null && !mount.getUUID().equals(expectedMountUuid)
                || !trulybestfriends.isTrackedPet(mount.getUUID())
                || !trulybestfriends.isOwnedBy(mount, player.getUUID())) return null;
        return mount;
    }

    private static RideSwapResult swapToLoadedTarget(ServerPlayer player, LivingEntity target,
                                                      UUID expectedMountUuid) {
        LivingEntity currentMount = getRideSwapMount(player, target.getUUID(), expectedMountUuid);
        if (currentMount == null) return RideSwapResult.FAILED;
        if (!hasRideSwapSpace(player.serverLevel(), target, currentMount.position())) {
            return RideSwapResult.NO_SPACE;
        }
        Vec3 oldPosition = target.position();
        float oldYRot = target.getYRot();
        float oldXRot = target.getXRot();
        forceTrackedTeleport(player.serverLevel(), target, currentMount.position(),
                currentMount.getYRot(), currentMount.getXRot());
        if (finishRideSwap(player, currentMount, target)) return RideSwapResult.SUCCESS;
        forceTrackedTeleport(player.serverLevel(), target, oldPosition, oldYRot, oldXRot);
        return RideSwapResult.FAILED;
    }

    private static void forceTrackedTeleport(ServerLevel level, LivingEntity target, Vec3 position,
                                             float yRot, float xRot) {
        level.getChunkSource().removeEntity(target);
        try {
            target.teleportTo(position.x, position.y, position.z);
            target.setYRot(yRot);
            target.setXRot(xRot);
            target.setYHeadRot(yRot);
        } finally {
            // 重建追踪器，这样即使是低于阈值的移动也会在乘客更新之前
            // 作为绝对生成位置发送出去。
            level.getChunkSource().addEntity(target);
        }
    }

    private static RideSwapResult swapFromOtherLevel(ServerPlayer player, LivingEntity originalTarget,
                                                     ServerLevel targetLevel, UUID expectedMountUuid) {
        LivingEntity currentMount = getRideSwapMount(player, originalTarget.getUUID(), expectedMountUuid);
        if (currentMount == null) return RideSwapResult.FAILED;
        if (!hasRideSwapSpace(player.serverLevel(), originalTarget, currentMount.position())) {
            return RideSwapResult.NO_SPACE;
        }
        if (!RecallPetPacket.savePetToDisk(player.getUUID(), originalTarget, targetLevel, false)) {
            return RideSwapResult.FAILED;
        }

        File nbtFile = PetIOUtil.getOwnerDir(player).resolve(originalTarget.getUUID() + ".nbt").toFile();
        try {
            CompoundTag snapshot = NbtFileIO.readCompressed(nbtFile);
            Entity restored = summonFromDiskAt(snapshot, originalTarget.getUUID(), player,
                    player.serverLevel(), currentMount.position(), currentMount.getYRot(),
                    currentMount.getXRot(), ownerHintFor(nbtFile, player));
            if (restored == null) return RideSwapResult.FAILED;
            if (finishRideSwap(player, currentMount, restored)) {
                originalTarget.discard();
                return RideSwapResult.SUCCESS;
            }
            restored.discard();
            restoreRideSwapTargetSnapshot(nbtFile, snapshot, originalTarget.getUUID(), targetLevel);
            return RideSwapResult.FAILED;
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to prepare ride swap target {}: {}",
                    originalTarget.getUUID(), e.getMessage());
            return RideSwapResult.FAILED;
        }
    }

    private static boolean hasRideSwapSpace(ServerLevel level, Entity target, Vec3 position) {
        AABB box = target.getBoundingBox().move(
                position.x - target.getX(), position.y - target.getY(), position.z - target.getZ());
        return level.getWorldBorder().isWithinBounds(box)
                && !level.getBlockCollisions(target, box.deflate(1.0E-7D)).iterator().hasNext();
    }

    private static void restoreRideSwapTargetSnapshot(File nbtFile, CompoundTag snapshot,
                                                       UUID petUuid, ServerLevel level) {
        try {
            PetIOUtil.writePetState(nbtFile, snapshot, level, petUuid);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to roll back ride swap target {}: {}",
                    petUuid, e.getMessage(), e);
        }
    }

    private static boolean finishRideSwap(ServerPlayer player, LivingEntity currentMount, Entity target) {
        ServerLevel level = player.serverLevel();
        if (!RecallPetPacket.savePetToDisk(player.getUUID(), currentMount, level, true)) return false;
        if (!player.startRiding(target, true)) {
            RecallPetPacket.savePetToDisk(player.getUUID(), currentMount, level, false);
            return false;
        }
        currentMount.ejectPassengers();
        currentMount.stopRiding();
        currentMount.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
        currentMount.discard();
        target.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
        return true;
    }

    public static boolean trySwapRecalledPet(ServerPlayer player, UUID petUuid, CompoundTag recalledNbt,
                                             File nbtFile, ServerLevel level) {
        LivingEntity currentMount = getRideSwapMount(player, petUuid, null);
        if (currentMount == null) return false;

        CompoundTag releasedNbt = recalledNbt.copy();
        releasedNbt.remove("Recalled");
        UUID ownerHint = ownerHintFor(nbtFile, player);
        Entity spaceProbe = PetEntitySnapshot.restore(prepareSummonSnapshot(releasedNbt), petUuid, level, ownerHint);
        if (spaceProbe != null) standPetUp(spaceProbe);
        if (spaceProbe != null && !hasRideSwapSpace(level, spaceProbe, currentMount.position())) {
            PetWarningPacket.send(player, 4, petUuid);
            return true;
        }
        try {
            PetIOUtil.writePetState(nbtFile, releasedNbt, level, petUuid);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to prepare recalled ride swap target {}: {}",
                    petUuid, e.getMessage());
            PetWarningPacket.send(player, 3, petUuid);
            return true;
        }

        Entity restored = summonFromDiskAt(releasedNbt, petUuid, player, level,
                currentMount.position(), currentMount.getYRot(), currentMount.getXRot(), ownerHint);
        if (restored != null && finishRideSwap(player, currentMount, restored)) return true;
        if (restored != null) restored.discard();
        try {
            PetIOUtil.writePetState(nbtFile, recalledNbt, level, petUuid);
        } catch (IOException rollbackError) {
            trulybestfriends.LOGGER.error("Failed to roll back recalled ride swap target {}: {}",
                    petUuid, rollbackError.getMessage(), rollbackError);
        }
        PetWarningPacket.send(player, 3, petUuid);
        return true;
    }

    /**
     * 为宠物寻找玩家附近一个安全且可寻路的 BlockPos 供其走过去。
     * 围绕玩家位置以不断扩大的环形进行搜索。
     */
    private static BlockPos findSafeBlockNearPlayer(ServerLevel level, ServerPlayer player, Entity entity) {
        Vec3 safePosition = PetIOUtil.findSafePositionNearPlayer(level, player, entity, 1, 4, 12);
        return safePosition != null ? BlockPos.containing(safePosition) : null;
    }

    /**
     * 为已存储的宠物记录的 TBF 主人，当快照本身不再携带主人时用作还原提示。
     * 宠物以 {@code {modDir}/{ownerUuid}/{petUuid}.nbt} 形式存储，因此
     * 父目录名就是其主人；对于不遵循该布局的路径，则以操作的玩家作为兜底。
     */
    private static UUID ownerHintFor(File nbtFile, ServerPlayer player) {
        UUID fromDirectory = PetIOUtil.ownerFromPetFile(nbtFile);
        return fromDirectory != null ? fromDirectory : player.getUUID();
    }

    static boolean summonFromDisk(CompoundTag nbt, UUID petUuid, ServerPlayer player, ServerLevel level,
                                  UUID ownerHint) {
        CompoundTag summonNbt = prepareSummonSnapshot(nbt);
        Entity entity = PetEntitySnapshot.restore(summonNbt, petUuid, level, ownerHint);
        if (entity == null) return false;
        restoreChestInventory(entity, summonNbt);
        standPetUp(entity);

        float bbWidth = entity instanceof LivingEntity living ? living.getBbWidth() : 0.6f;
        int radius = Math.max(1, (int) Math.ceil(bbWidth));
        Vec3 safePosition = PetIOUtil.findSafePositionNearPlayer(level, player, entity, radius, 6, 16);
        if (safePosition != null) {
            entity.setPos(safePosition.x, safePosition.y, safePosition.z);
            if (!level.tryAddFreshEntityWithPassengers(entity)) return false;
            finishRestoredEntity(entity, summonNbt, player, level);
            if (entity instanceof LivingEntity living) {
                living.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
            }
            return true;
        }
        entity.setPos(player.getX(), player.getY(), player.getZ());
        if (level.tryAddFreshEntityWithPassengers(entity)) {
            finishRestoredEntity(entity, summonNbt, player, level);
            return true;
        }
        return false;
    }

    private static Entity summonFromDiskAt(CompoundTag nbt, UUID petUuid, ServerPlayer player,
                                           ServerLevel level, Vec3 position, float yRot, float xRot,
                                           UUID ownerHint) {
        CompoundTag summonNbt = prepareSummonSnapshot(nbt);
        Entity entity = PetEntitySnapshot.restore(summonNbt, petUuid, level, ownerHint);
        if (entity == null) return null;
        restoreChestInventory(entity, summonNbt);
        standPetUp(entity);
        entity.setPos(position);
        entity.setYRot(yRot);
        entity.setXRot(xRot);
        if (!level.tryAddFreshEntityWithPassengers(entity)) return null;
        finishRestoredEntity(entity, summonNbt, player, level);
        return entity;
    }

    private static void finishRestoredEntity(Entity entity, CompoundTag nbt,
                                               ServerPlayer player, ServerLevel level) {
        // 部分容器能力只有在 onAddedToWorld 之后才会暴露。重复
        // 它们的还原，然后替换 EntityJoinLevelEvent 所排队的空快照。
        restoreChestInventory(entity, nbt);
        if (!trulybestfriends.persistRestoredPet(player.getUUID(), entity, level)) {
            trulybestfriends.LOGGER.error("Failed to persist restored container snapshot for {}", entity.getUUID());
        }
    }

    /** 通过 Forge 的稳定能力 API 还原模组实体的物品栏。 */
    public static void restoreChestInventory(Entity entity, CompoundTag nbt) {
        if (entity instanceof AbstractHorse) return;
        if (!nbt.contains("TBF_ItemHandlerSize", 3)
                && !nbt.contains("TBF_ItemHandlerItems", 9)) return;

        entity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().ifPresent(handler -> {
            if (!(handler instanceof IItemHandlerModifiable modifiable)) return;
            try {
                InventoryRestoreResult result = restoreItemHandler(modifiable, nbt);
                if (result == InventoryRestoreResult.CONFLICT) {
                    trulybestfriends.LOGGER.warn(
                            "Skipped item-handler backup for {} because its live inventory is nonempty and differs",
                            entity.getClass().getSimpleName());
                }
            } catch (RuntimeException e) {
                trulybestfriends.LOGGER.error("Failed to restore item handler for {}: {}",
                        entity.getClass().getSimpleName(), e.getMessage(), e);
            }
        });
    }

    static InventoryRestoreResult restoreItemHandler(IItemHandlerModifiable handler, CompoundTag nbt) {
        int savedSize = nbt.getInt("TBF_ItemHandlerSize");
        int restorableSlots = Math.min(Math.max(savedSize, 0), handler.getSlots());
        List<ItemStack> backup = emptyInventory(restorableSlots);
        net.minecraft.nbt.ListTag items = nbt.getList("TBF_ItemHandlerItems", 10);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemTag = items.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot < 0 || slot >= restorableSlots) continue;
            ItemStack stack = ItemStack.of(itemTag);
            if (!stack.isEmpty()) backup.set(slot, stack);
        }
        return restoreInventoryIfSafe(restorableSlots, handler::getStackInSlot,
                backup, handler::setStackInSlot, ItemStack::isEmpty,
                (current, expected) -> current.isEmpty() && expected.isEmpty()
                        || (current.getCount() == expected.getCount()
                        && ItemStack.isSameItemSameTags(current, expected)),
                ItemStack::copy);
    }

    private static List<ItemStack> emptyInventory(int size) {
        List<ItemStack> inventory = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) inventory.add(ItemStack.EMPTY);
        return inventory;
    }

    enum InventoryRestoreResult {
        MATCHED,
        RESTORED,
        CONFLICT
    }

    static <T> InventoryRestoreResult restoreInventoryIfSafe(
            int slotCount, IntFunction<T> liveStack, List<T> backup,
            BiConsumer<Integer, T> setStack, Predicate<T> isEmpty,
            BiPredicate<T, T> stacksMatch, UnaryOperator<T> copyStack) {
        boolean liveIsEmpty = true;
        boolean matchesBackup = true;
        for (int slot = 0; slot < slotCount; slot++) {
            T current = liveStack.apply(slot);
            if (!isEmpty.test(current)) liveIsEmpty = false;
            if (!stacksMatch.test(current, backup.get(slot))) matchesBackup = false;
        }

        if (matchesBackup) return InventoryRestoreResult.MATCHED;
        if (!liveIsEmpty) return InventoryRestoreResult.CONFLICT;

        for (int slot = 0; slot < slotCount; slot++) {
            T stack = backup.get(slot);
            if (!isEmpty.test(stack)) setStack.accept(slot, copyStack.apply(stack));
        }
        return InventoryRestoreResult.RESTORED;
    }

    /** 备份模组物品栏能力；原版马匹数据仍保持权威。 */
    public static void backupChestInventory(Entity entity, CompoundTag nbt) {
        if (entity instanceof AbstractHorse) return;
        entity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().ifPresent(handler ->
                backupItemHandler(handler, nbt));
    }

    static void backupItemHandler(IItemHandler handler, CompoundTag nbt) {
        try {
            net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                CompoundTag tag = new CompoundTag();
                tag.putInt("Slot", slot);
                stack.save(tag);
                items.add(tag);
            }
            nbt.putInt("TBF_ItemHandlerSize", handler.getSlots());
            nbt.put("TBF_ItemHandlerItems", items);
        } catch (RuntimeException e) {
            trulybestfriends.LOGGER.error("Failed to back up item handler for {}: {}",
                    handler.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    /** 使用 NBT 的 Dimension 字段解析宠物最后一次保存时所在的 ServerLevel。 */
    private static ServerLevel resolvePetLevel(MinecraftServer server, CompoundTag nbt) {
        if (!nbt.contains("Dimension", 8)) return null;
        return PetIOUtil.getLevel(server, nbt.getString("Dimension"));
    }

    // ---- 用于未加载区块中宠物的待处理召唤队列 ----

    private static final List<PendingSummon> pendingSummons = new CopyOnWriteArrayList<>();
    private static final int MAX_PENDING_ATTEMPTS = 100; // 20 TPS 下约 5 秒
    /** 每名玩家同时待处理召唤的最大数量。= Config.maxPendingSummons + 2 的缓冲。 */
    private static int maxPendingPerPlayer() {
        return Config.maxPendingSummons + 2;
    }

    private static class PendingSummon {
        final UUID petUuid;
        final UUID playerUuid;
        final int chunkX;
        final int chunkZ;
        final ServerLevel petLevel;
        final UUID rideSwapMountUuid;
        int attempts;

        PendingSummon(UUID petUuid, UUID playerUuid, int chunkX, int chunkZ, ServerLevel petLevel,
                      UUID rideSwapMountUuid) {
            this.petUuid = petUuid;
            this.playerUuid = playerUuid;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.petLevel = petLevel;
            this.rideSwapMountUuid = rideSwapMountUuid;
        }
    }

    /**
     * 每个 tick 由 trulybestfriends.onServerTick 调用。
     * 处理未加载区块中宠物的待处理召唤请求。
     * 一旦区块加载且找到原实体，就将其丢弃，并在玩家位置
     * 从磁盘召唤该宠物。
     */
    public static void tickPendingSummons(MinecraftServer server) {
        if (pendingSummons.isEmpty()) return;

        for (PendingSummon pending : pendingSummons) {
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerUuid);

            if (player == null) {
                // 玩家已登出——清理强制加载的区块并取消
                finishPendingSummon(pending);
                continue;
            }

            Entity entity = pending.petLevel.getEntity(pending.petUuid);
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                failPendingSummon(pending, player);
                continue;
            }
            if (!trulybestfriends.isOwnedBy(living, player.getUUID())) {
                finishPendingSummon(pending);
                continue;
            }
            LivingEntity rideSwapMount = pending.rideSwapMountUuid != null
                    ? getRideSwapMount(player, pending.petUuid, pending.rideSwapMountUuid)
                    : null;
            if (rideSwapMount != null) {
                RideSwapResult result = pending.petLevel == player.serverLevel()
                        ? swapToLoadedTarget(player, living, pending.rideSwapMountUuid)
                        : swapFromOtherLevel(player, living, pending.petLevel, pending.rideSwapMountUuid);
                if (result == RideSwapResult.SUCCESS) {
                    finishPendingSummon(pending);
                } else if (result == RideSwapResult.NO_SPACE) {
                    finishPendingSummon(pending);
                    PetWarningPacket.send(player, 4, pending.petUuid);
                } else {
                    failPendingSummon(pending, player);
                }
                continue;
            }
            if (pending.petLevel == player.serverLevel()) {
                if (recreateAfterForcedLoad(living, player, pending.petLevel)) finishPendingSummon(pending);
                else failPendingSummon(pending, player);
                continue;
            }
            // 区块已加载——丢弃原实体并在玩家处召唤
            if (RecallPetPacket.savePetToDisk(player.getUUID(), living, pending.petLevel, false)
                    && completeSummon(player, pending.petUuid)) {
                living.discard();
                finishPendingSummon(pending);
            } else {
                failPendingSummon(pending, player);
            }
        }
    }

    private static void failPendingSummon(PendingSummon pending, ServerPlayer player) {
        if (++pending.attempts < MAX_PENDING_ATTEMPTS) return;
        finishPendingSummon(pending, player);
    }

    private static void finishPendingSummon(PendingSummon pending) {
        finishPendingSummon(pending, null);
    }

    private static void finishPendingSummon(PendingSummon pending, ServerPlayer warningRecipient) {
        trulybestfriends.releaseForcedChunk(pending.petLevel, pending.chunkX, pending.chunkZ);
        if (warningRecipient != null) PetWarningPacket.send(warningRecipient, 1, pending.petUuid);
        pendingSummons.remove(pending);
    }

    /**
     * 重新添加从强制加载区块中载入的宠物，使附近客户端收到全新的
     * 追踪/生成序列。仅进行坐标传送可能使实体一直从其旧区段被追踪，
     * 直到该区段卸载之后才结束。
     */
    private static boolean recreateAfterForcedLoad(LivingEntity original, ServerPlayer player, ServerLevel level) {
        UUID petUuid = original.getUUID();
        if (!RecallPetPacket.savePetToDisk(player.getUUID(), original, level, false)) return false;

        File nbtFile = PetIOUtil.getOwnerDir(player).resolve(petUuid + ".nbt").toFile();
        CompoundTag snapshot;
        try {
            snapshot = NbtFileIO.readCompressed(nbtFile);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to read forced-chunk snapshot for {}: {}", petUuid, e.getMessage());
            return false;
        }

        original.discard();
        UUID ownerHint = ownerHintFor(nbtFile, player);
        if (summonFromDisk(snapshot, petUuid, player, level, ownerHint)) return true;

        trulybestfriends.LOGGER.error("Failed to re-add forced-chunk pet {}; restoring its original snapshot", petUuid);
        if (!restoreAtStoredPosition(snapshot, petUuid, player, level, ownerHint)) {
            trulybestfriends.LOGGER.error("Failed to restore pet {} after a failed forced-chunk teleport", petUuid);
        }
        return false;
    }

    public static boolean isReleasingUntrackedDeath(UUID petUuid) {
        return UNTRACKED_DEATH_RELEASES.contains(petUuid);
    }

    /** 以存活状态添加一个已存储的死亡宠物，使取消追踪能在施加致命伤害之前完成。 */
    public static Entity releaseDeadPetForUntracking(CompoundTag nbt, UUID petUuid,
                                                     ServerPlayer player, ServerLevel level,
                                                     UUID ownerHint) {
        CompoundTag releaseNbt = PetDeathState.prepareForUntrackedRelease(nbt);
        Entity entity = PetEntitySnapshot.restore(releaseNbt, petUuid, level, ownerHint);
        if (!(entity instanceof LivingEntity living)) return null;
        restoreChestInventory(entity, releaseNbt);
        living.setHealth(1.0F);

        int radius = Math.max(1, (int) Math.ceil(living.getBbWidth()));
        Vec3 safePosition = PetIOUtil.findSafePositionNearPlayer(level, player, living, radius, 6, 16);
        entity.setPos(safePosition != null ? safePosition : player.position());

        UNTRACKED_DEATH_RELEASES.add(petUuid);
        try {
            if (!level.tryAddFreshEntityWithPassengers(entity)) return null;
        } finally {
            UNTRACKED_DEATH_RELEASES.remove(petUuid);
        }
        restoreChestInventory(entity, releaseNbt);
        living.setHealth(1.0F);
        return entity;
    }

    static CompoundTag prepareSummonSnapshot(CompoundTag nbt) {
        CompoundTag snapshot = nbt.copy();
        if (snapshot.getBoolean("Sitting")) snapshot.putBoolean("Sitting", false);
        return snapshot;
    }

    private static void standPetUp(Entity entity) {
        if (entity instanceof TamableAnimal tamable) {
            tamable.setOrderedToSit(false);
            tamable.setInSittingPose(false);
        }
    }

    private static boolean restoreAtStoredPosition(CompoundTag snapshot, UUID petUuid,
                                                    ServerPlayer player, ServerLevel level, UUID ownerHint) {
        Entity restored = PetEntitySnapshot.restore(snapshot, petUuid, level, ownerHint);
        if (restored == null) return false;
        restoreChestInventory(restored, snapshot);
        if (!level.tryAddFreshEntityWithPassengers(restored)) return false;
        finishRestoredEntity(restored, snapshot, player, level);
        return true;
    }

    /** 读取 NBT 并在玩家当前位置从磁盘召唤宠物。 */
    private static boolean completeSummon(ServerPlayer player, UUID petUuid) {
        ServerLevel playerLevel = player.serverLevel();
        java.nio.file.Path ownerDir = PetIOUtil.getOwnerDir(player);
        File nbtFile = ownerDir.resolve(petUuid + ".nbt").toFile();
        if (!nbtFile.exists()) return false;
        try {
            CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);
            return summonFromDisk(nbt, petUuid, player, playerLevel, ownerHintFor(nbtFile, player));
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to complete summon for {}: {}", petUuid, e.getMessage());
            return false;
        }
    }

    public static void clearPendingSummons() {
        pendingSummons.clear();
    }

    /** 当宠物被显式取消追踪/删除时，取消其排队的召唤。 */
    public static void cancelPendingSummons(UUID playerUuid, UUID petUuid) {
        for (PendingSummon pending : new ArrayList<>(pendingSummons)) {
            if (pending.playerUuid.equals(playerUuid) && pending.petUuid.equals(petUuid)) {
                finishPendingSummon(pending);
            }
        }
    }

    /** 取消属于某一名玩家的所有排队召唤。 */
    public static void cancelPendingSummons(UUID playerUuid) {
        for (PendingSummon pending : new ArrayList<>(pendingSummons)) {
            if (pending.playerUuid.equals(playerUuid)) finishPendingSummon(pending);
        }
    }
}
