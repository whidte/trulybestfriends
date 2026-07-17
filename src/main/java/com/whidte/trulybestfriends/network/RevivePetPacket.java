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

/** Client -> Server: consume items from player inventory and revive a dead pet. */
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

                // Only revive if actually dead
                if (!PetDeathState.isDeadSnapshot(nbt)) return;

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

                // Update saved position to a safe spot near the player FIRST,
                // so the pet appears at the player's location when summoned.
                nbt.putString("Dimension", level.dimension().location().toString());
                writeSafePosNearPlayer(nbt, player, level);

                // Revive at 1 HP and clear death markers
                nbt.putFloat("Health", 1.0f);
                nbt.remove("Recalled");
                PetDeathState.clear(nbt);
                nbt.remove("DeathTime");
                nbt.remove("HurtTime");
                nbt.putBoolean("NoAI", false);

                // Apply totem-of-undying status effects
                applyTotemEffects(nbt);

                // Persist the revived NBT, then summon the pet directly into the world
                NbtFileIO.writeCompressed(nbt, nbtFile);
                trulybestfriends.updatePetRecalledState(level, packet.petUuid, false);
                if (!TeleportPetToPlayerPacket.summonFromDisk(nbt, packet.petUuid, player, level)) {
                    try {
                        NbtFileIO.writeCompressed(deadSnapshot, nbtFile);
                        trulybestfriends.updatePetRecalledState(
                                level, packet.petUuid, deadSnapshot.getBoolean("Recalled"));
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
    static void applyTotemEffects(CompoundTag nbt) {
        // Mirror LivingEntity#checkTotemDeathProtection effects:
        //   Regeneration II,  45s (900 ticks)
        //   Absorption II,     5s (100 ticks)
        //   Fire Resistance,  40s (800 ticks)
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

    /** Clear all pre-death effects and grant a fresh set of totem effects. */
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
