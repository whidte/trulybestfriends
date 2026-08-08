package com.whidte.trulybestfriends.tab;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

/** Icon-only button placed to the right of the summon button. */
class SquadButton extends AbstractWidget {

    private final TrulyScreen screen;

    public SquadButton(int x, int y, TrulyScreen screen) {
        super(x, y, SQUAD_SIZE, SQUAD_SIZE, Component.empty());
        this.screen = screen;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draw the squad icon, scaled from 8x8 to SQUAD_ICON_SIZE, centered in the hitbox.
        int iconX = getX() + (width - SQUAD_ICON_SIZE) / 2;
        int iconY = getY() + (height - SQUAD_ICON_SIZE) / 2;
        g.blit(SQUAD_ICON, iconX, iconY,
                SQUAD_ICON_SIZE, SQUAD_ICON_SIZE,
                0, 0,
                8, 8,
                8, 8);

        // Hover highlight ring
        if (isHovered()) {
            g.blit(SQUAD_BORDER, iconX, iconY,
                    SQUAD_ICON_SIZE, SQUAD_ICON_SIZE,
                    0, 0,
                    8, 8,
                    8, 8);
            g.renderTooltip(screen.font(),
                    Component.translatable("trulybestfriends.squad.tooltip"),
                    mouseX, mouseY);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        // Squad view not yet implemented
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
