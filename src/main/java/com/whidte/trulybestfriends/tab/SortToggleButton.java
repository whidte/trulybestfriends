package com.whidte.trulybestfriends.tab;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import static com.whidte.trulybestfriends.tab.TrulyConstants.BORDER_10;
import static com.whidte.trulybestfriends.tab.TrulyConstants.SORT_ASCENDING_ICON;
import static com.whidte.trulybestfriends.tab.TrulyConstants.SORT_BUTTON_SIZE;
import static com.whidte.trulybestfriends.tab.TrulyConstants.SORT_DESCENDING_ICON;

/** 位于深色列表框右上角的排序按钮：点击在升序与降序之间切换，图标与悬浮提示反映当前排序方向。 */
class SortToggleButton extends IconNavigationButton {
    SortToggleButton(int x, int y, TrulyScreen screen) {
        super(x, y, SORT_BUTTON_SIZE, SORT_BUTTON_SIZE, BORDER_10, screen,
                label(true), SORT_ASCENDING_ICON);
    }

    /** 悬浮提示复用既有的升序/降序文案，直接说明当前的排序方向。 */
    private static Component label(boolean ascending) {
        return Component.translatable(ascending
                ? "trulybestfriends.sort.ascending"
                : "trulybestfriends.sort.descending");
    }

    @Override
    protected ResourceLocation currentIcon() {
        return screen.isSortAscending() ? SORT_ASCENDING_ICON : SORT_DESCENDING_ICON;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        screen.toggleSortDirection();
        setMessage(label(screen.isSortAscending()));
    }
}
