package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.compat.CuriosCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/** Captures and restores a complete Forge 1.20.1 entity tree. */
public final class PetEntitySnapshot {
    private PetEntitySnapshot() {}

    public static CompoundTag capture(Entity entity, UUID ownerUUID, ServerLevel level) {
        CompoundTag nbt = new CompoundTag();
        if (!entity.saveAsPassenger(nbt)) {
            entity.saveWithoutId(nbt);
            nbt.putString("id", ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString());
        }

        if (entity instanceof LivingEntity living) {
            nbt.putFloat("MaxHealth", (float) living.getAttributeValue(Attributes.MAX_HEALTH));
        }
        TeleportPetToPlayerPacket.backupChestInventory(entity, nbt);
        CuriosCompat.backup(entity, nbt);
        nbt.putString("OwnerUUID", ownerUUID.toString());
        nbt.putString("EntityType", ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString());
        nbt.putString("Dimension", level.dimension().location().toString());
        return nbt;
    }

    public static Entity restore(CompoundTag snapshot, UUID expectedUuid, ServerLevel level) {
        CompoundTag entityNbt = snapshot.copy();
        if (!entityNbt.contains("id", 8)) {
            String legacyType = entityNbt.getString("EntityType");
            if (legacyType.isEmpty()) return null;
            entityNbt.putString("id", legacyType);
        }
        migrateLegacyChestedHorseItems(entityNbt);

        Entity entity = EntityType.loadEntityRecursive(entityNbt, level, loaded -> loaded);
        if (entity != null) entity.setUUID(expectedUuid);
        return entity;
    }

    /** Converts the old absolute-slot backup into vanilla 1.20.1 chested-horse NBT. */
    static void migrateLegacyChestedHorseItems(CompoundTag nbt) {
        ListTag backupItems = nbt.getList("TBF_ChestItems", 10);
        if (backupItems.isEmpty()) {
            backupItems = nbt.getList("TBF_ItemHandlerItems", 10);
        }
        if (backupItems.isEmpty()) return;

        boolean hasVanillaChestItems = nbt.contains("Items", 9)
                && !nbt.getList("Items", 10).isEmpty();
        ListTag vanillaItems = new ListTag();
        for (int i = 0; i < backupItems.size(); i++) {
            CompoundTag item = backupItems.getCompound(i).copy();
            int absoluteSlot = item.getInt("Slot");
            if (absoluteSlot == 0 && !nbt.contains("SaddleItem", 10)) {
                item.remove("Slot");
                nbt.put("SaddleItem", item);
            } else if (absoluteSlot == 1 && !nbt.contains("ArmorItem", 10)) {
                item.remove("Slot");
                nbt.put("ArmorItem", item);
            } else if (absoluteSlot >= 2
                    && nbt.getBoolean("ChestedHorse")
                    && !hasVanillaChestItems) {
                // AbstractChestedHorse persists chest inventory using the absolute
                // inventory slots 2..16, not chest-relative slots 0..14.
                item.putByte("Slot", (byte) absoluteSlot);
                vanillaItems.add(item);
            }
        }
        if (!hasVanillaChestItems && !vanillaItems.isEmpty()) {
            nbt.put("Items", vanillaItems);
        }
    }
}
