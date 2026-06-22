package com.whidte.trulybestfriends.tab;

import dev.xkmc.l2tabs.tabs.core.BaseTab;
import dev.xkmc.l2tabs.tabs.core.TabManager;
import dev.xkmc.l2tabs.tabs.core.TabRegistry;
import dev.xkmc.l2tabs.tabs.core.TabToken;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Isolated L2Tabs integration layer.
 * Only loaded via Class.forName when L2Tabs is present at runtime.
 * When absent, the mod functions normally via keybinding.
 */
public class L2TabsIntegration {

    public static TabToken<TrulyTabL2> TRULY_TAB;

    public static void register() {
        TRULY_TAB = TabRegistry.registerTab(500, TrulyTabL2::new,
                () -> Items.LEAD,
                Component.translatable("tab.trulybestfriends.pets"));
    }

    /**
     * Create and initialise a TabManager for a TrulyScreen so that L2Tabs
     * tab bar (inventory, attributes, curios, etc.) shows on top of the pet panel.
     */
    public static TabManager createTabManager(TrulyScreen screen) {
        TabManager manager = new TabManager(screen);
        manager.init(screen::addWidgetPublic, TRULY_TAB);
        screen.tabManager = manager;
        return manager;
    }

    public static class TrulyTabL2 extends BaseTab<TrulyTabL2> {

        public TrulyTabL2(TabToken<TrulyTabL2> token, TabManager manager, ItemStack stack, Component title) {
            super(token, manager, stack, title);
        }

        @Override
        public void onTabClicked() {
            Minecraft.getInstance().setScreen(new TrulyScreen(this.getMessage()));
        }
    }
}