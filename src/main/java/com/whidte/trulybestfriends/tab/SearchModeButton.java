package com.whidte.trulybestfriends.tab;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.SEARCH_ICON;
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
        graphics.blitSprite(SEARCH_ICON, getX(), getY() + 1, getWidth(), getHeight());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
