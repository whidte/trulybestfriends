package com.whidte.trulybestfriends.tab;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

/** Selects which of the eight color-coded pet teams is active. */
class TeamSelectorButton extends AbstractWidget {

    private static final long HOVER_DELAY_MILLIS = 1000L;
    private static final int ICON_SIZE = 16;
    private static final int[] CELL_TEAMS = {
            1, 0, 4,
            2, -1, 5,
            3, 7, 6
    };
    private static final ItemStack[] TEAM_BANNERS = {
            new ItemStack(Items.WHITE_BANNER),
            new ItemStack(Items.PURPLE_BANNER),
            new ItemStack(Items.RED_BANNER),
            new ItemStack(Items.BLUE_BANNER),
            new ItemStack(Items.GREEN_BANNER),
            new ItemStack(Items.BLACK_BANNER),
            new ItemStack(Items.ORANGE_BANNER),
            new ItemStack(Items.YELLOW_BANNER)
    };
    private static final String[] TEAM_NAMES = {
            "trulybestfriends.team.white",
            "trulybestfriends.team.purple",
            "trulybestfriends.team.red",
            "trulybestfriends.team.blue",
            "trulybestfriends.team.green",
            "trulybestfriends.team.black",
            "trulybestfriends.team.orange",
            "trulybestfriends.team.yellow"
    };

    private final TrulyScreen screen;
    private boolean expanded;
    private int hoveredTeam = -1;
    private int previousHoveredTeam = -1;
    private long hoverStartMillis = -1L;

    TeamSelectorButton(int x, int y, TrulyScreen screen) {
        super(x, y, TEAM_SELECTOR_BUTTON_SIZE, TEAM_SELECTOR_BUTTON_SIZE,
                TEAM_BANNERS[screen.selectedTeamIndex()].getHoverName());
        this.screen = screen;
    }

    @Override
    protected void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (expanded && !isInsidePanel(mouseX, mouseY)) {
            collapse();
        }
        hoveredTeam = teamAt(mouseX, mouseY);
        if (expanded) {
            int panelX = panelX();
            int panelY = panelY();
            graphics.blit(TEAM_SELECTOR_BACKGROUND, panelX, panelY,
                    0, 0, TEAM_SELECTOR_PANEL_WIDTH, TEAM_SELECTOR_PANEL_HEIGHT,
                    TEAM_SELECTOR_PANEL_WIDTH, TEAM_SELECTOR_PANEL_HEIGHT);

            for (int cell = 0; cell < CELL_TEAMS.length; cell++) {
                int team = CELL_TEAMS[cell];
                if (team < 0) continue;
                int column = cell % 3;
                int row = cell / 3;
                graphics.renderItem(TEAM_BANNERS[team],
                        panelX + TEAM_SELECTOR_GRID_X + 1 + column * TEAM_SELECTOR_CELL_WIDTH,
                        panelY + TEAM_SELECTOR_GRID_Y + 1 + row * TEAM_SELECTOR_CELL_HEIGHT);
            }

            int hoveredCell = cellAt(mouseX, mouseY);
            if (hoveredCell >= 0 && CELL_TEAMS[hoveredCell] >= 0) {
                renderSlotHighlight(graphics,
                        panelX + TEAM_SELECTOR_GRID_X + 1 + (hoveredCell % 3) * TEAM_SELECTOR_CELL_WIDTH,
                        panelY + TEAM_SELECTOR_GRID_Y + 1 + (hoveredCell / 3) * TEAM_SELECTOR_CELL_HEIGHT);
            }
        }

        boolean centerHovered = mouseX >= getX() && mouseX < getX() + width
                && mouseY >= getY() && mouseY < getY() + height;
        int frameV = !active ? TEAM_SELECTOR_FRAME_DISABLED_V
                : centerHovered ? TEAM_SELECTOR_FRAME_HOVERED_V : TEAM_SELECTOR_FRAME_NORMAL_V;
        graphics.blit(WIDGET_BUTTON, getX(), getY(), 0, frameV, width, height, 256, 256);
        graphics.renderItem(TEAM_BANNERS[screen.selectedTeamIndex()],
                getX() + (width - ICON_SIZE) / 2,
                getY() + (height - ICON_SIZE) / 2);
        if (!expanded) {
            graphics.drawCenteredString(screen.font(),
                    Component.translatable("trulybestfriends.team_selector.current"),
                    getX() + width / 2, getY() - 10, 0xFFFFFF);
        }
    }

    void renderTooltip(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        if (!visible || hoveredTeam < 0) {
            previousHoveredTeam = -1;
            hoverStartMillis = -1L;
            return;
        }

        long now = Util.getMillis();
        if (hoveredTeam != previousHoveredTeam) {
            previousHoveredTeam = hoveredTeam;
            hoverStartMillis = now;
        }
        if (now - hoverStartMillis >= HOVER_DELAY_MILLIS) {
            graphics.renderTooltip(screen.font(), Component.translatable(TEAM_NAMES[hoveredTeam]), mouseX, mouseY);
        }
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (!visible) return false;
        if (expanded) {
            return isInsidePanel(mouseX, mouseY);
        }
        return super.isMouseOver(mouseX, mouseY);
    }

    private boolean isInsidePanel(double mouseX, double mouseY) {
        return mouseX >= panelX() && mouseX < panelX() + TEAM_SELECTOR_PANEL_WIDTH
                && mouseY >= panelY() && mouseY < panelY() + TEAM_SELECTOR_PANEL_HEIGHT;
    }

    private boolean isInsideButton(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX < getX() + width
                && mouseY >= getY() && mouseY < getY() + height;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!expanded) {
            expanded = true;
            return;
        }

        int team = optionTeamAt(mouseX, mouseY);
        if (team >= 0) {
            screen.selectTeam(team);
            setMessage(TEAM_BANNERS[team].getHoverName());
        }
        expanded = false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || button != 0) return false;
        if (!expanded) {
            if (!isInsideButton(mouseX, mouseY)) return false;
            onClick(mouseX, mouseY);
            return true;
        }
        if (!isInsidePanel(mouseX, mouseY)) return false;
        onClick(mouseX, mouseY);
        return true;
    }

    boolean isExpanded() {
        return expanded;
    }

    void collapse() {
        expanded = false;
    }

    private int teamAt(double mouseX, double mouseY) {
        if (mouseX >= getX() && mouseX < getX() + width
                && mouseY >= getY() && mouseY < getY() + height) {
            return screen.selectedTeamIndex();
        }
        return expanded ? optionTeamAt(mouseX, mouseY) : -1;
    }

    private int optionTeamAt(double mouseX, double mouseY) {
        int cell = cellAt(mouseX, mouseY);
        return cell >= 0 ? CELL_TEAMS[cell] : -1;
    }

    private int cellAt(double mouseX, double mouseY) {
        int relativeX = (int) Math.floor(mouseX) - panelX() - TEAM_SELECTOR_GRID_X;
        int relativeY = (int) Math.floor(mouseY) - panelY() - TEAM_SELECTOR_GRID_Y;
        if (relativeX < 0 || relativeX >= TEAM_SELECTOR_CELL_WIDTH * 3
                || relativeY < 0 || relativeY >= TEAM_SELECTOR_CELL_HEIGHT * 3) {
            return -1;
        }
        int column = relativeX / TEAM_SELECTOR_CELL_WIDTH;
        int row = relativeY / TEAM_SELECTOR_CELL_HEIGHT;
        return row * 3 + column;
    }

    /** Vanilla container-screen slot highlight overlaid on the hovered banner. */
    private void renderSlotHighlight(GuiGraphics graphics, int x, int y) {
        RenderSystem.disableDepthTest();
        RenderSystem.colorMask(true, true, true, false);
        graphics.fillGradient(x, y, x + 16, y + 16, -2130706433, -2130706433);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableDepthTest();
    }

    private int panelX() {
        return getX() - TEAM_SELECTOR_GRID_X - TEAM_SELECTOR_CELL_WIDTH;
    }

    private int panelY() {
        return getY() - TEAM_SELECTOR_GRID_Y - TEAM_SELECTOR_CELL_HEIGHT;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
