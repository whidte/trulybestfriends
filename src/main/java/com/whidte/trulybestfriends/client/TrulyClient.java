package com.whidte.trulybestfriends.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class TrulyClient {
    private static final KeyMapping OPEN_TAB_KEY = new KeyMapping(
            "key.trulybestfriends.open_tab",
            InputConstants.UNKNOWN.getValue(),
            "key.categories.trulybestfriends"
    );

    private TrulyClient() {
    }

    public static void register(IEventBus modEventBus) {
        registerL2TabsIntegration();
        modEventBus.addListener(TrulyClient::onClientSetup);
        modEventBus.addListener(TrulyClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(TrulyClient::onKeyInput);
    }

    private static void registerL2TabsIntegration() {
        if (!ModList.get().isLoaded("l2tabs")) return;
        try {
            Class.forName("com.whidte.trulybestfriends.tab.L2TabsIntegration")
                    .getMethod("register")
                    .invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("L2Tabs is installed but its integration could not be registered", e);
        }
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded("l2tabs")) {
                try {
                    Class.forName("com.whidte.trulybestfriends.tab.L2TabsIntegration")
                            .getMethod("validateRegistration")
                            .invoke(null);
                    trulybestfriends.LOGGER.info("L2Tabs detected - inventory tab registered.");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("L2Tabs token validation failed", e);
                }
            } else {
                trulybestfriends.LOGGER.info("L2Tabs not installed - use keybinding to open pet screen.");
            }
        });
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_TAB_KEY);
    }

    private static void onKeyInput(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && OPEN_TAB_KEY.consumeClick()) {
            minecraft.setScreen(new TrulyScreen(Component.translatable("tab.trulybestfriends.pets")));
        }
    }
}
