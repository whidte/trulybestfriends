package com.whidte.trulybestfriends.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = trulybestfriends.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    private static final KeyMapping OPEN_TAB_KEY = new KeyMapping(
            "key.trulybestfriends.open_tab",
            InputConstants.UNKNOWN.getValue(),
            "key.categories.trulybestfriends"
    );

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.addListener(ClientEvents::onKeyInput);
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded("l2tabs")) {
                try {
                    Class.forName("com.whidte.trulybestfriends.tab.L2TabsIntegration")
                            .getMethod("register")
                            .invoke(null);
                    trulybestfriends.LOGGER.info("L2Tabs detected - inventory tab registered.");
                } catch (Exception e) {
                    trulybestfriends.LOGGER.warn("L2Tabs present but integration failed: {}", e.toString());
                }
            } else {
                trulybestfriends.LOGGER.info("L2Tabs not installed - use keybinding to open pet screen.");
            }
        });
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_TAB_KEY);
    }

    private static void onKeyInput(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && OPEN_TAB_KEY.consumeClick()) {
            minecraft.setScreen(new TrulyScreen(Component.translatable("tab.trulybestfriends.pets")));
        }
    }
}
