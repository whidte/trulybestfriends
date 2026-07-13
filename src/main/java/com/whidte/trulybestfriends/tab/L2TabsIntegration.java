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
 * Isolated L2Tabs integration layer.
 * Only loaded via Class.forName when L2Tabs is present at runtime.
 * When absent, the mod functions normally via keybinding.
 */
public final class L2TabsIntegration {
    private static final Reg REG = new Reg(trulybestfriends.MODID);
    private static final SR<TabToken<?, ?>> TAB_REG = SR.of(REG, L2Tabs.TABS.reg());

    public static final Val<TabToken<InvTabData, TrulyTab>> TRULY_TAB = TAB_REG.reg(
            "pets", () -> L2Tabs.GROUP.registerTab(
                    () -> TrulyTab::new,
                    Component.translatable("tab.trulybestfriends.pets")));

    private L2TabsIntegration() {}

    /** Forces static registration while NeoForge registries are still open. */
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
