package com.whidte.trulybestfriends.tab;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.SQUAD_BORDER;
import static com.whidte.trulybestfriends.tab.TrulyConstants.SQUAD_ICON_SIZE;
import static com.whidte.trulybestfriends.tab.TrulyConstants.SQUAD_SIZE;

/** Shared rendering and delayed tooltip behavior for the squad/details navigation pair. */
abstract class IconNavigationButton extends AbstractWidget {
    private static final int SOURCE_ICON_SIZE = 8;

    protected final TrulyScreen screen;
    private final ResourceLocation icon;
    private final HoverDelay hoverDelay = new HoverDelay();

    IconNavigationButton(int x, int y, TrulyScreen screen, Component label, ResourceLocation icon) {
        super(x, y, SQUAD_SIZE, SQUAD_SIZE, label);
        this.screen = screen;
        this.icon = icon;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int iconX = getX() + (width - SQUAD_ICON_SIZE) / 2;
        int iconY = getY() + (height - SQUAD_ICON_SIZE) / 2;
        blitIcon(graphics, icon, iconX, iconY);
        if (isHovered()) blitIcon(graphics, SQUAD_BORDER, iconX, iconY);
    }

    void renderTooltip(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoverDelay.isReady(visible && isHovered() ? this : null)) {
            graphics.renderTooltip(screen.font(), getMessage(), mouseX, mouseY);
        }
    }

    private static void blitIcon(GuiGraphics graphics, ResourceLocation texture, int x, int y) {
        graphics.blit(texture, x, y, SQUAD_ICON_SIZE, SQUAD_ICON_SIZE,
                0, 0, SOURCE_ICON_SIZE, SOURCE_ICON_SIZE, SOURCE_ICON_SIZE, SOURCE_ICON_SIZE);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
