package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.compat.SableCompat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;

/** Captures and restores a complete entity tree while retaining legacy TBF metadata. */
public final class PetEntitySnapshot {
    private PetEntitySnapshot() {}

    public static CompoundTag capture(Entity entity, UUID ownerUUID, ServerLevel level) {
        CompoundTag nbt = new CompoundTag();
        if (!entity.saveAsPassenger(nbt)) {
            entity.saveWithoutId(nbt);
            nbt.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        }

        if (entity instanceof LivingEntity living) {
            nbt.putFloat("MaxHealth", (float) living.getAttributeValue(Attributes.MAX_HEALTH));
        }
        TeleportPetToPlayerPacket.backupChestInventory(entity, nbt);
        nbt.putString("OwnerUUID", ownerUUID.toString());
        nbt.putString("EntityType", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        nbt.putString("Dimension", level.dimension().location().toString());
        SableCompat.captureSubLevelInfo(nbt, level, entity.position());
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

    /** Converts the legacy capability backup into the 1.21 absolute-slot backup. */
    static void migrateLegacyChestedHorseItems(CompoundTag nbt) {
        if (!nbt.getList("TBF_ChestItems", 10).isEmpty()) return;
        ListTag legacyItems = nbt.getList("TBF_ItemHandlerItems", 10);
        if (!legacyItems.isEmpty()) nbt.put("TBF_ChestItems", legacyItems.copy());
    }
}
