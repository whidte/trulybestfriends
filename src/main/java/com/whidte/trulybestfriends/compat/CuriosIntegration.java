package com.whidte.trulybestfriends.compat;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import top.theillusivec4.curios.api.event.CurioDropsEvent;

final class CuriosIntegration {
    private CuriosIntegration() {}

    static void register() {
        NeoForge.EVENT_BUS.register(CuriosIntegration.class);
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
