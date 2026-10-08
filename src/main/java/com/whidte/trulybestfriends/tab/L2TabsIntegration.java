package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.trulybestfriends;
import dev.xkmc.l2core.init.reg.simple.Reg;
import dev.xkmc.l2core.init.reg.simple.SR;
import dev.xkmc.l2core.init.reg.simple.Val;
import dev.xkmc.l2tabs.init.L2Tabs;
import dev.xkmc.l2tabs.tabs.core.TabBase;
import dev.xkmc.l2tabs.tabs.core.TabManager;
import dev.xkmc.l2tabs.tabs.core.TabToken;
import dev.xkmc.l2tabs.tabs.inventory.InvTabData;
import dev.xkmc.l2tabs.tabs.inventory.ScreenWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/**
 * 隔离的 L2Tabs 集成层。
 * 仅在运行时存在 L2Tabs 时通过 Class.forName 加载。
 * 不存在时，模组通过按键绑定正常工作。
 */
public final class L2TabsIntegration {
    private static final Reg REG = new Reg(trulybestfriends.MODID);
    private static final SR<TabToken<?, ?>> TAB_REG = SR.of(REG, L2Tabs.TABS.reg());

    public static final Val<TabToken<InvTabData, TrulyTab>> TRULY_TAB = TAB_REG.reg(
            "pets", () -> L2Tabs.GROUP.registerTab(
                    () -> TrulyTab::new,
                    Component.translatable("tab.trulybestfriends.pets")));

    private L2TabsIntegration() {}

    /** 在 NeoForge 注册表仍处于开放状态时强制进行静态注册。 */
    public static void register() {}

    public static void validateRegistration() {
        if (TRULY_TAB.get() == null) {
            throw new IllegalStateException("The Truly Best Friends L2Tabs token was not registered");
        }
    }

    public static TabManager<InvTabData> createTabManager(TrulyScreen screen) {
        TabManager<InvTabData> manager = new TabManager<>(ScreenWrapper.of(screen), new InvTabData());
        manager.init(screen::addWidgetPublic, TRULY_TAB.get());
        screen.tabManager = manager;
        return manager;
    }

    public static final class TrulyTab extends TabBase<InvTabData, TrulyTab> {
        public TrulyTab(int index, TabToken<InvTabData, TrulyTab> token,
                        TabManager<InvTabData> manager, Component title) {
            super(index, token, manager, title);
        }

        @Override
        public void onTabClicked() {
            Minecraft.getInstance().setScreen(new TrulyScreen(getMessage()));
        }

        @Override
        protected void renderIcon(GuiGraphics graphics) {
            graphics.renderItem(Items.LEAD.getDefaultInstance(), getX() + 5, getY() + 8);
        }
    }
}
