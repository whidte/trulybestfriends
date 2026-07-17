package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.compat.SableCompat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
        return copyRootEntityOnly(nbt);
    }

    public static Entity restore(CompoundTag snapshot, UUID expectedUuid, ServerLevel level) {
        CompoundTag entityNbt = copyRootEntityOnly(snapshot);
        if (!entityNbt.contains("id", 8)) {
            String legacyType = entityNbt.getString("EntityType");
            if (legacyType.isEmpty()) return null;
            entityNbt.putString("id", legacyType);
        }
        Entity entity = EntityType.loadEntityRecursive(entityNbt, level, loaded -> loaded);
        if (entity != null) entity.setUUID(expectedUuid);
        return entity;
    }

    static CompoundTag copyRootEntityOnly(CompoundTag snapshot) {
        CompoundTag root = snapshot.copy();
        root.remove("Passengers");
        return root;
    }
}
