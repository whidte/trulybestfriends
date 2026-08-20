package com.whidte.trulybestfriends.tab;

import net.minecraft.network.chat.Component;

import static com.whidte.trulybestfriends.tab.TrulyConstants.DETAILS_ICON;

/** Icon-only button shown in squad mode to return to the standard tab view. */
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
