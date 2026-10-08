package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.ReviveProtection;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/** 客户端 -> 服务端：从玩家背包中消耗物品并复活一只死亡宠物。 */
public class RevivePetPacket {
    private static final byte TOTEM_ACTIVATION_EVENT = 35;
    private static final int REVIVE_INVULNERABILITY_TICKS = 20;
    private final UUID petUuid;

    public RevivePetPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(RevivePetPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static RevivePetPacket decode(FriendlyByteBuf buf) {
        return new RevivePetPacket(buf.readUUID());
    }

    public static void handle(RevivePetPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            trulybestfriends.flushPendingPetSaves(player.getUUID());

            Path ownerDir = PetIOUtil.getOwnerDir(player);
            File nbtFile = ownerDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) return;

            try {
                CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);

                // 仅在确实死亡时才复活
                if (!PetDeathState.isDeadSnapshot(nbt)) return;

                // 白名单中的实体类型无法通过本模组复活
                if (nbt.contains("EntityType") && Config.isNoReviveEntity(nbt.getString("EntityType"))) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .translatable("trulybestfriends.revive.not_revivable")
                            .withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }

                long now = System.currentTimeMillis();
                long reviveCooldownMs = Config.reviveCooldownSeconds * 1000L;
                // 复活冷却由服务器内存 Map (petDeathTimes) 计算，不读磁盘 NBT。
                // 服务器重启后记录清空 → 无冷却（符合"不保存到磁盘"的设计）。
                Long deathTime = trulybestfriends.getPetDeathTime(packet.petUuid);
                if (reviveCooldownMs > 0 && deathTime != null
                        && now - deathTime < reviveCooldownMs) {
                    return;
                }

                // 先校验；仅在复活确实成功后才消耗。
                if (!player.isCreative() && !hasItems(player)) return;
                CompoundTag deadSnapshot = nbt.copy();

                // 首先把保存的位置更新为玩家附近的安全点，
                // 这样召唤时宠物会出现在玩家的位置。
                nbt.putString("Dimension", level.dimension().location().toString());
                writeSafePosNearPlayer(nbt, player, level);

                // 以 1 点生命值复活，并清除死亡标记
                nbt.putFloat("Health", 1.0f);
                nbt.remove("Recalled");
                PetDeathState.clear(nbt);
                nbt.remove("DeathTime");
                nbt.remove("HurtTime");
                nbt.putBoolean("NoAI", false);

                // 应用不死图腾的状态效果
                applyTotemEffects(nbt);

                // 持久化复活后的 NBT，然后把宠物直接召唤进世界
                PetIOUtil.writePetState(nbtFile, nbt, level, packet.petUuid);
                UUID ownerHint = PetIOUtil.ownerFromPetFile(nbtFile);
                if (!TeleportPetToPlayerPacket.summonFromDisk(nbt, packet.petUuid, player, level,
                        ownerHint != null ? ownerHint : player.getUUID())) {
                    try {
                        PetIOUtil.writePetState(nbtFile, deadSnapshot, level, packet.petUuid);
                    } catch (IOException rollbackError) {
                        trulybestfriends.LOGGER.error("Failed to roll back revive for {}: {}",
                                packet.petUuid, rollbackError.getMessage(), rollbackError);
                    }
                    return;
                }
                discardLoadedCopiesOutside(player.server, packet.petUuid, level);
                if (!player.isCreative()) consumeItems(player);
                trulybestfriends.clearPetDeathTime(packet.petUuid);
                completeRevive(level, packet.petUuid);
            } catch (IOException e) {
                trulybestfriends.LOGGER.error("Failed to revive pet: {}", e.getMessage());
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 计算玩家附近的安全位置，并将其写入宠物的 NBT Pos 列表。
     * 创建一个临时实体以获取准确的碰撞箱尺寸。
     */
    private static void writeSafePosNearPlayer(CompoundTag nbt, ServerPlayer player, ServerLevel level) {
        float bbW = 0.6f;
        float bbH = 1.8f;
        Entity tempEntity = null;

        // 尝试创建临时实体以获取准确尺寸
        String typeKey = nbt.getString("EntityType");
        if (!typeKey.isEmpty()) {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(typeKey));
            if (type != null) {
                tempEntity = type.create(level);
                if (tempEntity instanceof LivingEntity le) {
                    bbW = le.getBbWidth();
                    bbH = le.getBbHeight();
                }
            }
        }

        float halfWidth = bbW / 2f;
        int radius = Math.max(1, (int) Math.ceil(bbW));
        Vec3 safePosition = tempEntity != null
                ? PetIOUtil.findSafePositionNearPlayer(level, player, tempEntity, radius, 6, 16)
                : PetIOUtil.findSafePositionNearPlayer(level, player, halfWidth, bbH, radius, 6, 16);
        if (tempEntity != null) tempEntity.discard();

        // 与召唤兜底保持一致：不存在有效可站立位置时不要向上移动。
        double safeX = safePosition != null ? safePosition.x : player.getX();
        double safeY = safePosition != null
                ? safePosition.y
                : player.getY();
        double safeZ = safePosition != null ? safePosition.z : player.getZ();

        net.minecraft.nbt.ListTag pos = new net.minecraft.nbt.ListTag();
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(safeX));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(safeY));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(safeZ));
        nbt.put("Pos", pos);
    }

    /** 把原版不死图腾的状态效果写入宠物的 NBT。 */
    static void applyTotemEffects(CompoundTag nbt) {
        // 对应 LivingEntity#checkTotemDeathProtection 的效果：
        //   生命恢复 II， 45s（900 ticks）
        //   伤害吸收 II， 5s（100 ticks）
        //   抗火，40s（800 ticks）
        ListTag activeEffects = new ListTag();

        activeEffects.add(saveEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1)));
        activeEffects.add(saveEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1)));
        activeEffects.add(saveEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0)));

        replaceActiveEffects(nbt, activeEffects);
    }

    static void replaceActiveEffects(CompoundTag nbt, ListTag activeEffects) {
        nbt.remove("ActiveEffects");
        nbt.put("ActiveEffects", activeEffects);
    }

    private static void completeRevive(ServerLevel level, UUID petUuid) {
        Entity revived = level.getEntity(petUuid);
        if (revived instanceof LivingEntity living) {
            applyFreshTotemEffects(living);
            ReviveProtection.grant(living, REVIVE_INVULNERABILITY_TICKS);
            level.broadcastEntityEvent(living, TOTEM_ACTIVATION_EVENT);
        } else {
            trulybestfriends.LOGGER.warn("Revived pet {} was not available for post-revive protection", petUuid);
        }
    }

    /** 清除所有死亡前效果，并授予一套全新的图腾效果。 */
    private static void applyFreshTotemEffects(LivingEntity living) {
        living.removeAllEffects();
        living.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
        living.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
        living.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
    }

    private static CompoundTag saveEffect(MobEffectInstance instance) {
        return instance.save(new CompoundTag());
    }

    private static boolean hasItems(ServerPlayer player) {
        if (!Config.isReviveItemRequired()) return true;
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(Config.reviveItem));
        if (item == null) return false;

        int remaining = Config.reviveItemCount;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                remaining -= stack.getCount();
                if (remaining <= 0) break;
            }
        }
        return remaining <= 0;
    }

    private static boolean consumeItems(ServerPlayer player) {
        if (!Config.isReviveItemRequired()) return true;
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(Config.reviveItem));
        if (item == null || !hasItems(player)) return false;

        int remaining = Config.reviveItemCount;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                if (remaining <= 0) break;
            }
        }
        return true;
    }

    private static void discardLoadedCopiesOutside(MinecraftServer server, UUID petUuid, ServerLevel targetLevel) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level == targetLevel) continue;
            Entity duplicate = level.getEntity(petUuid);
            if (duplicate != null) duplicate.discard();
        }
    }
}
