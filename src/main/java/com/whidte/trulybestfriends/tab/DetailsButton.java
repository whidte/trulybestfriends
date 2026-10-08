package com.whidte.trulybestfriends.tab;

import net.minecraft.network.chat.Component;

import static com.whidte.trulybestfriends.tab.TrulyConstants.DETAILS_ICON;

/** 在群体模式下显示、用于返回标准标签页视图的纯图标按钮。 */
class DetailsButton extends IconNavigationButton {
    private static final Component LABEL = Component.translatable("trulybestfriends.details.tooltip");

    public DetailsButton(int x, int y, TrulyScreen screen) {
        super(x, y, screen, LABEL, DETAILS_ICON);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        screen.exitSquadMode();
    }
}
