package com.whidte.trulybestfriends.compat;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.event.CurioDropsEvent;

final class CuriosIntegration {
    private CuriosIntegration() {}

    static void register() {
        MinecraftForge.EVENT_BUS.register(CuriosIntegration.class);
    }

    @SubscribeEvent
    public static void onCurioDrops(CurioDropsEvent event) {
        LivingEntity entity = event.getEntity();
        var entityType = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (trulybestfriends.isTrackedPet(entity.getUUID())
                && entityType != null
                && !Config.isNoReviveEntity(entityType.toString())
                && !IafCompat.isDeadMob(entity)) {
            event.getDrops().clear();
        }
    }
}
