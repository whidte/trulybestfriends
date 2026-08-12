package com.whidte.trulybestfriends.tab;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

/** Center button for releasing every member of the selected squad. */
class AreaReleaseButton extends AbstractWidget {

    private static final Component LABEL = Component.translatable("trulybestfriends.action.area_release");
    private static final long HOVER_DELAY_MILLIS = 1000L;
    private static final int ICON_SIZE = 16;

    private final TrulyScreen screen;
    private long hoverStartMillis = -1L;

    AreaReleaseButton(int x, int y, TrulyScreen screen) {
        super(x, y, AREA_RELEASE_BUTTON_SIZE, AREA_RELEASE_BUTTON_SIZE, LABEL);
        this.screen = screen;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int frameV = isHovered() ? 20 : 0;
        graphics.blit(WIDGET_BUTTON, getX(), getY(), 0, frameV, width, height, 256, 256);

        int iconX = getX() + (width - ICON_SIZE) / 2;
        int iconY = getY() + (height - ICON_SIZE) / 2;
        graphics.blit(AREA_RELEASE_ICON, iconX, iconY,
                0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
    }

    void renderTooltip(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        if (visible && isHovered()) {
            long now = Util.getMillis();
            if (hoverStartMillis < 0L) {
                hoverStartMillis = now;
            }
            if (now - hoverStartMillis >= HOVER_DELAY_MILLIS) {
                graphics.renderTooltip(screen.font(), LABEL, mouseX, mouseY);
            }
        } else {
            hoverStartMillis = -1L;
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        // Reserved for the area release action.
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
