package com.whidte.trulybestfriends.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.whidte.trulybestfriends.network.PacketContext;
import com.whidte.trulybestfriends.network.PetWarningPacket;
import com.whidte.trulybestfriends.network.SyncPetDataPacket;
import com.whidte.trulybestfriends.network.TeamDataPacket;
import com.whidte.trulybestfriends.network.TrulyNetwork;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import com.whidte.trulybestfriends.trulybestfriends;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents implements ClientModInitializer {
    private static final KeyMapping OPEN_TAB_KEY = new KeyMapping(
            "key.trulybestfriends.open_tab",
            InputConstants.UNKNOWN.getValue(),
            "key.categories.trulybestfriends"
    );

    private static boolean summonKeyPrevDown;

    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(OPEN_TAB_KEY);
        KeyBindingHelper.registerKeyBinding(SummonKeyHandler.SUMMON_KEY);

        TrulyNetwork.initClient();
        TrulyNetwork.registerClientHandler(5,
                (PetWarningPacket packet, PacketContext ctx) -> ClientPacketHandlers.handle(packet, ctx));
        TrulyNetwork.registerClientHandler(9,
                (SyncPetDataPacket packet, PacketContext ctx) -> ClientPacketHandlers.handle(packet, ctx));
        TrulyNetwork.registerClientHandler(15,
                (TeamDataPacket packet, PacketContext ctx) -> ClientPacketHandlers.handle(packet, ctx));

        ClientTickEvents.END_CLIENT_TICK.register(ClientEvents::onClientTick);
        HudRenderCallback.EVENT.register(
                (graphics, tickDelta) -> SummonKeyHandler.renderBottle(graphics));

        if (FabricLoader.getInstance().isModLoaded("l2tabs")) {
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
    }

    private static void onClientTick(Minecraft minecraft) {
        // Poll the raw physical key state: KeyMapping#isDown is cleared by
        // KeyMapping#releaseAll the moment the wheel screen opens, which would
        // otherwise read as an instant release and close the wheel (flicker).
        boolean summonDown = SummonKeyHandler.isSummonKeyDown();
        if (summonDown != summonKeyPrevDown) {
            summonKeyPrevDown = summonDown;
            if (SummonKeyHandler.onKeyInput(summonDown ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE)
                    && isTabKeySharedWithSummon()) {
                OPEN_TAB_KEY.consumeClick();
            }
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && OPEN_TAB_KEY.consumeClick()
                && !isTabKeySharedWithSummon()) {
            openPetTab();
        }
        SummonKeyHandler.tick();
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
}
