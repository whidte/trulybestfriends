package com.whidte.trulybestfriends.compat;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.event.CurioDropsEvent;

final class CuriosIntegration {
    private static final String NBT_KEY = "TBF_CuriosInventory";

    private CuriosIntegration() {}

    static void register() {
        NeoForge.EVENT_BUS.register(CuriosIntegration.class);
    }

    static void backup(Entity entity, CompoundTag destination) {
        if (entity instanceof LivingEntity living) {
            CuriosApi.getCuriosInventory(living).ifPresent(handler ->
                    destination.put(NBT_KEY, handler.writeTag()));
        }
    }

    static void restoreAfterSpawn(Entity entity, CompoundTag source) {
        if (entity instanceof LivingEntity living && source.contains(NBT_KEY, 10)) {
            CuriosApi.getCuriosInventory(living).ifPresent(handler ->
                    handler.readTag(source.getCompound(NBT_KEY)));
        }
    }

    @SubscribeEvent
    public static void onCurioDrops(CurioDropsEvent event) {
        LivingEntity entity = event.getEntity();
        var entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (trulybestfriends.isTrackedPet(entity.getUUID())
                && entityType != null
                && !Config.isNoReviveEntity(entityType.toString())
                && !IafCompat.isDeadMob(entity)) {
            event.getDrops().clear();
        }
    }
}
