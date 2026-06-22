package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
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

            Path ownerDir = level.getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("trulybestfriends").resolve(player.getUUID().toString());
            File nbtFile = ownerDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) return;

            try {
                CompoundTag nbt = NbtIo.readCompressed(nbtFile);

                // Only revive if actually dead
                if (!nbt.contains("Health") || nbt.getFloat("Health") > 0) return;

                // Whitelisted entity types cannot be revived via this mod
                if (nbt.contains("EntityType") && Config.isNoReviveEntity(nbt.getString("EntityType"))) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .translatable("trulybestfriends.revive.not_revivable")
                            .withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }

                // Consume items (creative mode skips check)
                if (!player.isCreative() && !consumeItems(player)) return;

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
                NbtIo.writeCompressed(nbt, nbtFile);
                TeleportPetToPlayerPacket.summonFromDisk(nbt, packet.petUuid, player, level);

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

        // Find a safe spot (mirrors TeleportPetToPlayerPacket logic)
        float hw = bbW / 2f;
        int radius = Math.max(1, (int) Math.ceil(bbW));
        double px = player.getX(), py = player.getY(), pz = player.getZ();

        double safeX = px, safeY = py, safeZ = pz;
        boolean found = false;

        outer:
        for (int r = radius; r <= 6; r++) {
            for (int attempt = 0; attempt < 16; attempt++) {
                double angle = level.random.nextDouble() * Math.PI * 2;
                double x = px + Math.cos(angle) * r;
                double z = pz + Math.sin(angle) * r;
                double y = PetIOUtil.findSafeY(level, x, py, z, hw, bbH);
                AABB box = new AABB(x - hw, y, z - hw, x + hw, y + bbH, z + hw);
                if (level.noCollision(box) && !level.containsAnyLiquid(box)) {
                    safeX = x; safeY = y; safeZ = z;
                    found = true;
                    break outer;
                }
            }
        }

        if (!found) {
            safeX = px;
            safeY = PetIOUtil.findSafeY(level, px, py, pz, hw, bbH);
            safeZ = pz;
        }

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

    private static boolean consumeItems(ServerPlayer player) {
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(Config.reviveItem));
        if (item == null) return false;

        int needed = Config.reviveItemCount;
        int remaining = needed;

        // Count how many we have
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                remaining -= stack.getCount();
                if (remaining <= 0) break;
            }
        }
        if (remaining > 0) return false; // not enough

        // Consume
        remaining = needed;
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
}
