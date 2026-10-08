package com.whidte.trulybestfriends.tab;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.BUTTON;
import static com.whidte.trulybestfriends.tab.TrulyConstants.BUTTON_HIGHLIGHTED;

/**
 * 宠物列表排序依据的紧凑选择器，在“优先级”和“注册时间”之间切换。
 *
 * <p>选项数量固定且很少，所以本控件有意不含滚动条与跑马灯逻辑；
 * 展开的选项列表向下弹出，由界面统一提升到列表条目之上的绘制深度。</p>
 */
final class SortModeDropdown extends AbstractWidget {
	/** 展开列表中单个选项的高度。 */
	private static final int OPTION_HEIGHT = 10;
	/** 标题与选项文字的左侧内缩量。 */
	private static final int TEXT_INSET_X = 3;
	/** 文字右侧为下拉箭头预留的宽度。 */
	private static final int ARROW_RESERVED_WIDTH = 10;

	private final TrulyScreen screen;
	private final Consumer<ListSortMode> selectionHandler;
	private ListSortMode selected;
	private boolean expanded;

	SortModeDropdown(int x, int y, int width, int height, TrulyScreen screen,
	                 ListSortMode selected, Consumer<ListSortMode> selectionHandler) {
		super(x, y, width, height, selected.label());
		this.screen = screen;
		this.selected = selected;
		this.selectionHandler = selectionHandler;
	}

	/** 收起展开的选项列表。 */
	void collapse() {
		expanded = false;
	}

	@Override
	protected void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		ResourceLocation sprite = isHeaderHovered(mouseX, mouseY) ? BUTTON_HIGHLIGHTED : BUTTON;
		graphics.blitSprite(sprite, getX(), getY(), width, height);
		renderClippedText(graphics, getMessage().getString(), getY() + 2);
		renderArrow(graphics);

		if (expanded) renderOptions(graphics, mouseX, mouseY);
	}

	/** 下拉箭头：展开时朝上，收起时朝下。 */
	private void renderArrow(GuiGraphics graphics) {
		int centerX = getX() + width - 6;
		int startY = getY() + 4 + (expanded ? 2 : 0);
		int direction = expanded ? -1 : 1;
		for (int halfWidth = 2, row = 0; halfWidth >= 0; halfWidth--, row++) {
			int y = startY + row * direction;
			graphics.fill(centerX - halfWidth, y, centerX + halfWidth + 1, y + 1, 0xFFFFFFFF);
		}
	}

	private void renderOptions(GuiGraphics graphics, int mouseX, int mouseY) {
		ListSortMode[] modes = ListSortMode.values();
		int popupY = popupY();
		int popupHeight = popupHeight();
		graphics.fill(getX(), popupY, getX() + width, popupY + popupHeight, 0xF0101010);
		graphics.renderOutline(getX(), popupY, width, popupHeight, 0xFF808080);

		for (int row = 0; row < modes.length; row++) {
			int rowY = popupY + 1 + row * OPTION_HEIGHT;
			if (isOptionHovered(mouseX, mouseY, rowY)) {
				graphics.fill(getX() + 1, rowY, getX() + width - 1, rowY + OPTION_HEIGHT, 0x60FFFFFF);
			} else if (modes[row] == selected) {
				graphics.fill(getX() + 1, rowY, getX() + width - 1, rowY + OPTION_HEIGHT, 0x60404040);
			}
			renderClippedText(graphics, modes[row].label().getString(), rowY + 1);
		}
	}

	/** 绘制一行内缩文字，超出右侧箭头保留区的部分被裁剪掉。 */
	private void renderClippedText(GuiGraphics graphics, String text, int y) {
		int textX = getX() + TEXT_INSET_X;
		int maxWidth = Math.max(0, width - TEXT_INSET_X - ARROW_RESERVED_WIDTH);
		graphics.enableScissor(textX, y, textX + maxWidth, y + screen.font().lineHeight);
		graphics.drawString(screen.font(), text, textX, y, 0xFFFFFF, false);
		graphics.disableScissor();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!visible || !active || button != 0) return false;
		if (isHeaderHovered(mouseX, mouseY)) {
			playDownSound(Minecraft.getInstance().getSoundManager());
			expanded = !expanded;
			return true;
		}
		if (!expanded || !isOverPopup(mouseX, mouseY)) {
			collapse();
			return false;
		}

		int row = (int) ((mouseY - popupY() - 1) / OPTION_HEIGHT);
		if (row >= 0 && row < ListSortMode.values().length) {
			playDownSound(Minecraft.getInstance().getSoundManager());
			select(ListSortMode.values()[row]);
		}
		return true;
	}

	/** 采纳一个新的排序依据：同步显示名称、收起列表并通知界面重排。 */
	void select(ListSortMode mode) {
		selected = mode;
		setMessage(mode.label());
		collapse();
		selectionHandler.accept(mode);
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return visible && (isHeaderHovered(mouseX, mouseY) || expanded && isOverPopup(mouseX, mouseY));
	}

	private boolean isHeaderHovered(double mouseX, double mouseY) {
		return contains(mouseX, mouseY, getX(), getY(), width, height);
	}

	private boolean isOverPopup(double mouseX, double mouseY) {
		return contains(mouseX, mouseY, getX(), popupY(), width, popupHeight());
	}

	private boolean isOptionHovered(double mouseX, double mouseY, int rowY) {
		return contains(mouseX, mouseY, getX() + 1, rowY, width - 2, OPTION_HEIGHT);
	}

	private static boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	private int popupY() {
		return getY() + height;
	}

	private int popupHeight() {
		return ListSortMode.values().length * OPTION_HEIGHT + 2;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration) {
		defaultButtonNarrationText(narration);
	}
}
