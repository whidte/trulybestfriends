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
 * 隔离的 L2Tabs 集成层。
 * 仅在运行时存在 L2Tabs 时通过 Class.forName 加载。
 * 不存在时，模组通过按键绑定正常工作。
 */
public class L2TabsIntegration {

    public static TabToken<TrulyTabL2> TRULY_TAB;

    public static void register() {
        TRULY_TAB = TabRegistry.registerTab(500, TrulyTabL2::new,
                () -> Items.LEAD,
                Component.translatable("tab.trulybestfriends.pets"));
    }

    /**
     * 为 TrulyScreen 创建并初始化 TabManager，使 L2Tabs
     * 标签栏（物品栏、属性、饰品等）显示在宠物面板之上。
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