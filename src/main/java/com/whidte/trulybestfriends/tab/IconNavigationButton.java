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

/** 群体/单项模式导航按钮对共用的渲染和延迟悬浮提示行为。 */
abstract class IconNavigationButton extends AbstractWidget {
    protected final TrulyScreen screen;
    private final ResourceLocation icon;
    /** 图标在按钮内绘制的边长，同时作为源贴图的边长（按 1:1 绘制，不缩放）。 */
    private final int iconSize;
    /** 悬停高亮边框贴图，尺寸必须与 iconSize 一致。 */
    private final ResourceLocation hoverBorder;
    private final HoverDelay hoverDelay = new HoverDelay();

    IconNavigationButton(int x, int y, TrulyScreen screen, Component label, ResourceLocation icon) {
        this(x, y, SQUAD_SIZE, SQUAD_ICON_SIZE, SQUAD_BORDER, screen, label, icon);
    }

    IconNavigationButton(int x, int y, int size, int iconSize, ResourceLocation hoverBorder,
                         TrulyScreen screen, Component label, ResourceLocation icon) {
        super(x, y, size, size, label);
        this.screen = screen;
        this.icon = icon;
        this.iconSize = iconSize;
        this.hoverBorder = hoverBorder;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int iconX = getX() + (width - iconSize) / 2;
        int iconY = getY() + (height - iconSize) / 2;
        blitIcon(graphics, currentIcon(), iconX, iconY);
        renderIconOverlay(graphics);
        if (isHovered()) blitIcon(graphics, hoverBorder, iconX, iconY);
    }

    /** 当前应绘制的图标；需要按状态换图的按钮可覆写。 */
    protected ResourceLocation currentIcon() {
        return icon;
    }

    /** 图标之上、悬停高亮之下的可选叠加层。 */
    protected void renderIconOverlay(@NotNull GuiGraphics graphics) {}

    void renderTooltip(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoverDelay.isReady(visible && isHovered() ? this : null)) {
            graphics.renderTooltip(screen.font(), getMessage(), mouseX, mouseY);
        }
    }

    private void blitIcon(GuiGraphics graphics, ResourceLocation texture, int x, int y) {
        graphics.blit(texture, x, y, iconSize, iconSize,
                0, 0, iconSize, iconSize, iconSize, iconSize);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
