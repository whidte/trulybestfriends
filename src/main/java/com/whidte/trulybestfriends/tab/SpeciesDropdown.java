package com.whidte.trulybestfriends.tab;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.WIDGETS_TEXTURE;

/** A compact species selector whose expanded list can show up to 13 species at once. */
final class SpeciesDropdown extends AbstractWidget {
    /** Maximum number of species shown before the expanded list becomes scrollable. */
    static final int MAX_VISIBLE_SPECIES = 13;
    /** Height of one option in the expanded list. */
    private static final int OPTION_HEIGHT = 10;
    /** Width of the expanded list scrollbar. */
    private static final int SCROLLBAR_WIDTH = 4;

    private final TrulyScreen screen;
    private final List<String> options;
    private final Function<String, Component> labelProvider;
    private final Consumer<String> selectionHandler;
    private String selected;
    private boolean expanded;
    private boolean draggingScrollbar;
    private int scrollOffset;

    SpeciesDropdown(int x, int y, int width, int height, TrulyScreen screen,
                    List<String> options, String selected,
                    Function<String, Component> labelProvider,
                    Consumer<String> selectionHandler) {
        super(x, y, width, height, labelProvider.apply(selected));
        this.screen = screen;
        this.options = List.copyOf(options);
        this.selected = selected;
        this.labelProvider = labelProvider;
        this.selectionHandler = selectionHandler;
    }

    void collapse() {
        expanded = false;
        draggingScrollbar = false;
    }

    @Override
    protected void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int textureY = isHeaderHovered(mouseX, mouseY) ? 86 : 66;
        int middleWidth = width - 10;
        graphics.blit(WIDGETS_TEXTURE, getX(), getY(), 5, height, 0, textureY, 5, 20, 256, 256);
        RenderHelper.tileBlitH(graphics, WIDGETS_TEXTURE, getX() + 5, getY(),
                middleWidth, height, 5, textureY, 190, 20, 256, 256);
        graphics.blit(WIDGETS_TEXTURE, getX() + 5 + middleWidth, getY(),
                5, height, 195, textureY, 5, 20, 256, 256);
        renderLabel(graphics, getMessage(), getX() + 3, getY() + 2, width - 13);
        renderArrow(graphics);

        if (expanded) renderOptions(graphics, mouseX, mouseY);
    }

    private void renderArrow(GuiGraphics graphics) {
        int centerX = getX() + width - 6;
        int top = getY() + 4;
        int startY = top + (expanded ? 2 : 0);
        int direction = expanded ? -1 : 1;
        for (int halfWidth = 2, row = 0; halfWidth >= 0; halfWidth--, row++) {
            int y = startY + row * direction;
            graphics.fill(centerX - halfWidth, y, centerX + halfWidth + 1, y + 1, 0xFFFFFFFF);
        }
    }

    private void renderOptions(GuiGraphics graphics, int mouseX, int mouseY) {
        int popupY = popupY();
        int popupHeight = popupHeight();
        graphics.fill(getX(), popupY, getX() + width, popupY + popupHeight, 0xF0101010);
        graphics.renderOutline(getX(), popupY, width, popupHeight, 0xFF808080);

        int rows = visibleOptionCount(options.size());
        int textWidth = width - (isScrollable() ? SCROLLBAR_WIDTH + 5 : 5);
        for (int row = 0; row < rows; row++) {
            int optionIndex = scrollOffset + row;
            if (optionIndex >= options.size()) break;
            int rowY = popupY + 1 + row * OPTION_HEIGHT;
            String value = options.get(optionIndex);
            if (value.equals(selected)) {
                graphics.fill(getX() + 1, rowY, getX() + width - 1, rowY + OPTION_HEIGHT, 0x60404040);
            }
            if (isOptionHovered(mouseX, mouseY, rowY)) {
                graphics.fill(getX() + 1, rowY, getX() + width - 1, rowY + OPTION_HEIGHT, 0x60FFFFFF);
            }
            renderLabel(graphics, labelProvider.apply(value), getX() + 3, rowY + 1, textWidth);
        }

        if (isScrollable()) renderScrollbar(graphics);
    }

    private void renderLabel(GuiGraphics graphics, Component label, int x, int y, int maxWidth) {
        graphics.enableScissor(x, y, x + Math.max(0, maxWidth), y + screen.font().lineHeight);
        graphics.drawString(screen.font(), label, x, y, 0xFFFFFF, false);
        graphics.disableScissor();
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int trackX = getX() + width - SCROLLBAR_WIDTH - 1;
        int trackY = popupY() + 1;
        int trackHeight = popupHeight() - 2;
        graphics.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackHeight, 0xFF202020);

        int thumbHeight = scrollbarThumbHeight(trackHeight);
        int thumbY = scrollbarThumbY(trackY, trackHeight, thumbHeight);
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xFFA0A0A0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0) return false;
        if (isHeaderHovered(mouseX, mouseY)) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            expanded = !expanded;
            if (expanded) ensureSelectedVisible();
            return true;
        }
        if (!expanded || !isOverPopup(mouseX, mouseY)) {
            collapse();
            return false;
        }
        if (isOverScrollbar(mouseX)) {
            draggingScrollbar = true;
            setScrollFromMouse(mouseY);
            return true;
        }

        int row = (int) ((mouseY - popupY() - 1) / OPTION_HEIGHT);
        int optionIndex = scrollOffset + row;
        if (row >= 0 && optionIndex < options.size()) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            selected = options.get(optionIndex);
            setMessage(labelProvider.apply(selected));
            collapse();
            selectionHandler.accept(selected);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double verticalDelta) {
        if (!expanded || !isOverPopup(mouseX, mouseY) || !isScrollable()) return false;
        setScrollOffset(scrollOffset + (verticalDelta > 0 ? -1 : 1));
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!draggingScrollbar || button != 0) return false;
        setScrollFromMouse(mouseY);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!draggingScrollbar || button != 0) return false;
        draggingScrollbar = false;
        return true;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && (isHeaderHovered(mouseX, mouseY) || expanded && isOverPopup(mouseX, mouseY));
    }

    private void ensureSelectedVisible() {
        int selectedIndex = options.indexOf(selected);
        int visibleRows = visibleOptionCount(options.size());
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + visibleRows) scrollOffset = selectedIndex - visibleRows + 1;
        setScrollOffset(scrollOffset);
    }

    private void setScrollFromMouse(double mouseY) {
        int trackY = popupY() + 1;
        int trackHeight = popupHeight() - 2;
        int thumbHeight = scrollbarThumbHeight(trackHeight);
        int travel = trackHeight - thumbHeight;
        if (travel <= 0) return;
        double ratio = (mouseY - trackY - thumbHeight / 2.0) / travel;
        setScrollOffset((int) Math.round(ratio * maxScrollOffset(options.size())));
    }

    private int scrollbarThumbHeight(int trackHeight) {
        return Math.max(8, trackHeight * visibleOptionCount(options.size()) / options.size());
    }

    private int scrollbarThumbY(int trackY, int trackHeight, int thumbHeight) {
        int maxScroll = maxScrollOffset(options.size());
        return trackY + (trackHeight - thumbHeight) * scrollOffset / maxScroll;
    }

    private void setScrollOffset(int value) {
        scrollOffset = Mth.clamp(value, 0, maxScrollOffset(options.size()));
    }

    private boolean isHeaderHovered(double mouseX, double mouseY) {
        return contains(mouseX, mouseY, getX(), getY(), width, height);
    }

    private boolean isOverPopup(double mouseX, double mouseY) {
        return contains(mouseX, mouseY, getX(), popupY(), width, popupHeight());
    }

    private boolean isOverScrollbar(double mouseX) {
        return isScrollable() && mouseX >= getX() + width - SCROLLBAR_WIDTH - 1;
    }

    private boolean isOptionHovered(double mouseX, double mouseY, int rowY) {
        int right = getX() + width - (isScrollable() ? SCROLLBAR_WIDTH + 1 : 1);
        return contains(mouseX, mouseY, getX() + 1, rowY, right - getX() - 1, OPTION_HEIGHT);
    }

    private static boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private boolean isScrollable() {
        return options.size() - 1 > MAX_VISIBLE_SPECIES;
    }

    private int popupY() {
        return getY() + height;
    }

    private int popupHeight() {
        return visibleOptionCount(options.size()) * OPTION_HEIGHT + 2;
    }

    static int visibleOptionCount(int optionCount) {
        return Math.min(optionCount, MAX_VISIBLE_SPECIES + 1);
    }

    static int maxScrollOffset(int optionCount) {
        return Math.max(0, optionCount - visibleOptionCount(optionCount));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
