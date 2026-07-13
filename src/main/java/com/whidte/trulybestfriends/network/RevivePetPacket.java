package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.compat.IafCompat;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/** Client -> Server: consume items from player inventory and revive a dead pet. */
public class RevivePetPacket {
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

                // Only revive if actually dead
                if (!nbt.contains("Health") || nbt.getFloat("Health") > 0) return;

                // Whitelisted entity types cannot be revived via this mod
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

                // Validate first; consume only after the revive has actually succeeded.
                if (!player.isCreative() && !hasItems(player)) return;
                CompoundTag deadSnapshot = nbt.copy();

                // Ice and Fire 联动：龙等 IDeadMob 实体死亡后变成同 UUID 尸体长期驻留世界。
                // 若尸体已加载在玩家所在维度，原地治愈（setModelDead(false)+setDeathStage(0)），
                // 避免 summonFromDisk 因 UUID 冲突失败。详见 IafCompat。
                if (IafCompat.isLoaded()
                        && reviveCorpseInPlace(player.server, packet.petUuid, player, level, nbt, nbtFile)) {
                    if (!player.isCreative()) consumeItems(player);
                    trulybestfriends.clearPetDeathTime(packet.petUuid);
                    player.playNotifySound(net.minecraft.sounds.SoundEvents.TOTEM_USE,
                            net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
                    return;
                }

                // Update saved position to a safe spot near the player FIRST,
                // so the pet appears at the player's location when summoned.
                nbt.putString("Dimension", level.dimension().location().toString());
                writeSafePosNearPlayer(nbt, player, level);

                // Revive at 1 HP and clear death markers
                nbt.putFloat("Health", 1.0f);
                nbt.remove("Recalled");
                nbt.remove("DeathTime");
                nbt.remove("HurtTime");
                nbt.putBoolean("NoAI", false);

                // Apply totem-of-undying status effects
                applyTotemEffects(nbt);

                // Persist the revived NBT, then summon the pet directly into the world
                NbtFileIO.writeCompressed(nbt, nbtFile);
                if (!TeleportPetToPlayerPacket.summonFromDisk(nbt, packet.petUuid, player, level)) {
                    try {
                        NbtFileIO.writeCompressed(deadSnapshot, nbtFile);
                    } catch (IOException rollbackError) {
                        trulybestfriends.LOGGER.error("Failed to roll back revive for {}: {}",
                                packet.petUuid, rollbackError.getMessage(), rollbackError);
                    }
                    return;
                }
                discardLoadedCopiesOutside(player.server, packet.petUuid, level);
                if (!player.isCreative()) consumeItems(player);
                trulybestfriends.clearPetDeathTime(packet.petUuid);

                player.playNotifySound(net.minecraft.sounds.SoundEvents.TOTEM_USE,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
            } catch (IOException e) {
                trulybestfriends.LOGGER.error("Failed to revive pet: {}", e.getMessage());
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Compute a safe position near the player and write it into the pet's NBT Pos list.
     * Creates a temporary entity to get accurate bounding-box dimensions.
     */
    private static void writeSafePosNearPlayer(CompoundTag nbt, ServerPlayer player, ServerLevel level) {
        float bbW = 0.6f;
        float bbH = 1.8f;

        // Try to create a temp entity for accurate dimensions
        String typeKey = nbt.getString("EntityType");
        if (!typeKey.isEmpty()) {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(typeKey));
            if (type != null) {
                Entity temp = type.create(level);
                if (temp instanceof LivingEntity le) {
                    bbW = le.getBbWidth();
                    bbH = le.getBbHeight();
                }
                if (temp != null) temp.discard();
            }
        }

        float halfWidth = bbW / 2f;
        int radius = Math.max(1, (int) Math.ceil(bbW));
        Vec3 safePosition = PetIOUtil.findSafePositionNearPlayer(
                level, player, halfWidth, bbH, radius, 6, 16);
        double safeX = safePosition != null ? safePosition.x : player.getX();
        double safeY = safePosition != null
                ? safePosition.y
                : PetIOUtil.findSafeY(level, player.getX(), player.getY(), player.getZ(), halfWidth, bbH);
        double safeZ = safePosition != null ? safePosition.z : player.getZ();

        net.minecraft.nbt.ListTag pos = new net.minecraft.nbt.ListTag();
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(safeX));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(safeY));
        pos.add(net.minecraft.nbt.DoubleTag.valueOf(safeZ));
        nbt.put("Pos", pos);
    }

    /** Write the vanilla totem-of-undying status effects into the pet's NBT. */
    private static void applyTotemEffects(CompoundTag nbt) {
        // Mirror LivingEntity#checkTotemDeathProtection effects:
        //   Regeneration II,  45s (900 ticks)
        //   Absorption II,     5s (100 ticks)
        //   Fire Resistance,  40s (800 ticks)
        ListTag activeEffects = nbt.getList("ActiveEffects", 10);

        // Clear existing effects (vanilla totem removes all cureable effects)
        activeEffects.clear();

        activeEffects.add(saveEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1)));
        activeEffects.add(saveEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1)));
        activeEffects.add(saveEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0)));

        nbt.put("ActiveEffects", activeEffects);
    }

    private static CompoundTag saveEffect(MobEffectInstance instance) {
        return instance.save(new CompoundTag());
    }

    /**
     * Ice and Fire 联动：原地治愈已加载的 IDeadMob 尸体。
     *
     * <p>IaF 龙死亡后变同 UUID 尸体长期驻留世界，{@code summonFromDisk} 因 UUID 冲突复活失败。
     * 本方法跨维度查找已加载的尸体，若在玩家所在维度则原地治愈（清除 IaF 尸体状态 +
     * 通用复活 + 图腾效果 + 传送 + 写盘），返回 true 让 caller 跳过 summonFromDisk。</p>
     *
     * <p>跨维度的尸体无法安全传送（changeDimension 复杂且易丢实体），改为 discard 尸体后
     * 返回 false，让 caller 走原 summonFromDisk 路径在玩家维度新建实体（尸体已清，无冲突）。</p>
     *
     * @return true=已原地复活（caller 跳过原路径）；false=未处理（caller 走原路径）
     */
    private static boolean reviveCorpseInPlace(MinecraftServer server, UUID petUuid,
                                               ServerPlayer player, ServerLevel playerLevel,
                                               CompoundTag originalNbt, File nbtFile) {
        if (!IafCompat.isLoaded()) return false;

        // 跨所有维度查找已加载的同 UUID 实体
        LivingEntity corpse = null;
        for (ServerLevel sl : server.getAllLevels()) {
            Entity e = sl.getEntity(petUuid);
            if (e instanceof LivingEntity le && IafCompat.isMobDead(le)) {
                corpse = le;
                break;
            }
        }
        if (corpse == null) return false;  // 尸体未加载，走原路径

        // 跨维度尸体：discard 后走原路径在玩家维度新建（避免 changeDimension 复杂性）
        if (corpse.level() != playerLevel) {
            trulybestfriends.LOGGER.info("IaF corpse {} is cross-dimension; falling back to transactional summon", petUuid);
            return false;
        }

        // 1. 清除 IaF 尸体状态（setModelDead(false) + setDeathStage(0)）
        if (!IafCompat.clearCorpseState(corpse)) {
            trulybestfriends.LOGGER.warn("IaF clearCorpseState failed for {}, falling back to summon", petUuid);
            return false;
        }

        // 2. 通用复活：HP=1，清死亡标记
        //    deathTime/hurtTime 是 LivingEntity 的 public int 字段，直接赋值。
        //    deathTime=0 后 vanilla aiStep 不再调 tickDeath，IaF 不会把 modelDead 设回 true。
        corpse.setHealth(1.0f);
        corpse.deathTime = 0;
        corpse.hurtTime = 0;
        if (corpse instanceof Mob mob) mob.setNoAi(false);

        // 3. 图腾效果（直接加到实体，区别于原路径对 nbt 操作）
        corpse.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
        corpse.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
        corpse.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));

        // 4. 传送到玩家旁（同维度，直接 teleportTo）
        teleportCorpseToPlayer(corpse, player, playerLevel);

        // 5. 写回磁盘 NBT，保持盘/世界一致
        try {
            TeleportPetToPlayerPacket.restoreChestInventory(corpse, originalNbt);
            com.whidte.trulybestfriends.compat.CuriosCompat.restoreAfterSpawn(corpse, originalNbt);
            CompoundTag saved = PetEntitySnapshot.capture(corpse, player.getUUID(), playerLevel);
            if (originalNbt.contains("Priority")) {
                saved.putInt("Priority", Math.max(1, Math.min(6, originalNbt.getInt("Priority"))));
            }
            saved.remove("Recalled");
            saved.remove("LastDeathTime");  // 清死亡时间，避免复活冷却误判
            NbtFileIO.writeCompressed(saved, nbtFile);
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("reviveCorpseInPlace: failed to write nbt for {}: {}", petUuid, e.getMessage());
        }

        trulybestfriends.LOGGER.info("IaF corpse {} revived in place near player {}", petUuid, player.getName().getString());
        return true;
    }

    /** 同维度内把实体传送到玩家旁的安全位置。 */
    private static void teleportCorpseToPlayer(LivingEntity entity, ServerPlayer player, ServerLevel level) {
        int radius = Math.max(1, (int) Math.ceil(entity.getBbWidth()));
        Vec3 safePosition = PetIOUtil.findSafePositionNearPlayer(level, player, entity, radius, 6, 16);
        if (safePosition != null) {
            entity.teleportTo(safePosition.x, safePosition.y, safePosition.z);
            entity.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
            return;
        }
        // 兜底：直接传送到玩家坐标
        entity.teleportTo(player.getX(), player.getY(), player.getZ());
        entity.playSound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
    }

    private static boolean hasItems(ServerPlayer player) {
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
