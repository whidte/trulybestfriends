package com.whidte.trulybestfriends.tab;

import net.minecraft.network.chat.Component;

import static com.whidte.trulybestfriends.tab.TrulyConstants.SQUAD_ICON;

/** 放置在召唤按钮右侧的纯图标按钮。 */
class SquadButton extends IconNavigationButton {
    private static final Component LABEL = Component.translatable("trulybestfriends.squad.tooltip");

    public SquadButton(int x, int y, TrulyScreen screen) {
        super(x, y, screen, LABEL, SQUAD_ICON);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        screen.enterSquadMode();
    }
}
