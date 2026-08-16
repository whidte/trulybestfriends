package com.whidte.trulybestfriends.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
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
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, TrulyClient::onKeyInput);
        NeoForge.EVENT_BUS.addListener(TrulyClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TrulyClient::onMovementInputUpdate);
        NeoForge.EVENT_BUS.addListener(TrulyClient::onRenderGui);
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
        event.register(SummonKeyHandler.SUMMON_KEY);
    }

    private static void onKeyInput(InputEvent.Key event) {
        if (SummonKeyHandler.onKeyInput(event.getKey(), event.getScanCode(), event.getAction())) {
            if (isTabKeySharedWithSummon()) {
                OPEN_TAB_KEY.consumeClick();
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && OPEN_TAB_KEY.consumeClick()
                && !isTabKeySharedWithSummon()) {
            openPetTab();
        }
    }

    /** Opens the pet tab screen when the player has no screen open. */
    public static void openPetTab() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        minecraft.setScreen(new TrulyScreen(Component.translatable("tab.trulybestfriends.pets")));
    }

    /** True when the summon wheel key and the pet tab key are bound to the same physical key. */
    public static boolean isTabKeySharedWithSummon() {
        return OPEN_TAB_KEY.same(SummonKeyHandler.SUMMON_KEY);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        SummonKeyHandler.tick();
    }

    private static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        SummonKeyHandler.applyMovementInput(event.getInput());
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        SummonKeyHandler.renderBottle(event.getGuiGraphics());
    }
}
