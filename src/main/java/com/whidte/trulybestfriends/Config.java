package com.whidte.trulybestfriends;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = trulybestfriends.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue SYNC_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks for reading stored pet data and syncing loaded pet data back to disk")
            .defineInRange("syncIntervalTicks", 5, 1, 100);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int syncIntervalTicks;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        syncIntervalTicks = SYNC_INTERVAL_TICKS.get();
    }
}
