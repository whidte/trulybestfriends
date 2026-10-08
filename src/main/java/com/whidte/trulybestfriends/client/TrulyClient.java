package com.whidte.trulybestfriends.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.whidte.trulybestfriends.tab.SummonWheelData;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
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
        modEventBus.addListener(TrulyClient::onRegisterParticleProviders);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, TrulyClient::onKeyInput);
        NeoForge.EVENT_BUS.addListener(TrulyClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TrulyClient::onMovementInputUpdate);
        NeoForge.EVENT_BUS.addListener(TrulyClient::onRenderGui);
        NeoForge.EVENT_BUS.addListener(TrulyClient::onMouseScroll);
        NeoForge.EVENT_BUS.addListener(AreaRecallRangeRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(TrulyClient::onClientLoggingOut);
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
        event.register(AreaRecallKeyHandler.AREA_RECALL_KEY);
    }

    /**
     * 短命末影人粒子只在客户端本地生成，注册提供者即可。
     *
     * <p>注意：{@code registerSpriteSet} 会用「注册表键」把贴图集登记进
     * {@code ParticleEngine.spriteSets}，而贴图集真正被填充（{@code rebind}）依赖
     * `assets/<注册表命名空间>/particles/short_portal.json`。注册表命名空间恒为
     * {@link trulybestfriends#MODID}（即 {@code trulybestfriends}），
     * **不是** 贴图资源用的 {@code truly_best_friends}。两者搞混会导致 JSON 找不到、
     * 提供者里的 {@code SpriteSet} 一直是 null，进而崩在 `List.size()` 上。</p>
     */
    private static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticleTypes.shortPortal(), ShortPortalParticle.Provider::new);
    }

    private static void onKeyInput(InputEvent.Key event) {
        if (AreaRecallKeyHandler.onKeyInput(event.getKey(), event.getScanCode(), event.getAction())) {
            return;
        }
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

    private static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (AreaRecallKeyHandler.onMouseScroll(event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    /** 在玩家未打开任何界面时打开宠物标签页界面。 */
    public static void openPetTab() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        minecraft.setScreen(new TrulyScreen(Component.translatable("tab.trulybestfriends.pets")));
    }

    /** 当召唤轮盘键与宠物标签页键绑定到同一个物理按键时返回 true。 */
    public static boolean isTabKeySharedWithSummon() {
        return OPEN_TAB_KEY.same(SummonKeyHandler.SUMMON_KEY);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        SummonKeyHandler.tick();
        AreaRecallKeyHandler.tick();
    }

    /** 断开连接时清空仅客户端持有的静态快照，避免跨服务器残留过期宠物数据。 */
    private static void onClientLoggingOut(
            net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        SummonWheelData.clear();
        TrulyScreen.clearPendingSyncState();
    }


    private static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        SummonKeyHandler.applyMovementInput(event.getInput());
        AreaRecallKeyHandler.applyMovementInput(event.getInput());
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        SummonKeyHandler.renderBottle(event.getGuiGraphics());
        AreaRecallKeyHandler.renderBottle(event.getGuiGraphics());
    }
}
