package com.whidte.trulybestfriends.tab;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.SEARCH_TOGGLE_SIZE;

/** Toggles the shared list control between species filtering and name search. */
final class SearchModeButton extends AbstractButton {
    private final TrulyScreen screen;

    SearchModeButton(int x, int y, TrulyScreen screen) {
        super(x, y, SEARCH_TOGGLE_SIZE, SEARCH_TOGGLE_SIZE, screen.listControlToggleLabel());
        this.screen = screen;
        updateLabel();
    }

    @Override
    public void onPress() {
        screen.toggleListControlMode();
        updateLabel();
    }

    private void updateLabel() {
        setMessage(screen.listControlToggleLabel());
    }

    @Override
    protected void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX() + 2;
        int y = getY() + 2;
        int color = 0xFFFFFFFF;
        graphics.fill(x + 1, y, x + 4, y + 1, color);
        graphics.fill(x, y + 1, x + 1, y + 4, color);
        graphics.fill(x + 4, y + 1, x + 5, y + 4, color);
        graphics.fill(x + 1, y + 4, x + 4, y + 5, color);
        graphics.fill(x + 4, y + 4, x + 6, y + 6, color);
        graphics.fill(x + 6, y + 6, x + 8, y + 8, color);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
