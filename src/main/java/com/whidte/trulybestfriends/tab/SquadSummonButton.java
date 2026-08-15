package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.network.SummonTeamPacket;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

/** Center button for summoning every member of the selected team. */
class SquadSummonButton extends AbstractWidget {

    private static final Component LABEL = Component.translatable("trulybestfriends.action.squad_summon");
    private static final long HOVER_DELAY_MILLIS = 1000L;
    private static final int ICON_SIZE = 16;
    private static final int ICON_VISUAL_OFFSET_X = 0;
    private static final int ICON_VISUAL_OFFSET_Y = -1;
    private static final int FRAME_NORMAL_V = 0;
    private static final int FRAME_HOVERED_V = 20;
    private static final int FRAME_DISABLED_V = 40;
    private static final int COOLDOWN_TICKS = 5;

    private final TrulyScreen screen;
    private long hoverStartMillis = -1L;
    private long lastClickTick;

    SquadSummonButton(int x, int y, TrulyScreen screen) {
        super(x, y, SQUAD_SUMMON_BUTTON_SIZE, SQUAD_SUMMON_BUTTON_SIZE, LABEL);
        this.screen = screen;
    }

    private boolean isOnCooldown() {
        if (screen.getMinecraft().level == null) return true;
        long currentTick = screen.getMinecraft().level.getGameTime();
        return currentTick - lastClickTick < COOLDOWN_TICKS;
    }

    @Override
    protected void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.active = !screen.selectedTeamSlots().isEmpty() && !isOnCooldown();
        int frameV = !active ? FRAME_DISABLED_V
                : isHovered() ? FRAME_HOVERED_V : FRAME_NORMAL_V;
        graphics.blit(WIDGET_BUTTON, getX(), getY(), 0, frameV, width, height, 256, 256);

        int iconX = getX() + (width - ICON_SIZE) / 2 + ICON_VISUAL_OFFSET_X;
        int iconY = getY() + (height - ICON_SIZE) / 2 + ICON_VISUAL_OFFSET_Y;
        graphics.blit(SQUAD_SUMMON_ICON, iconX, iconY,
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
        if (screen.selectedTeamSlots().isEmpty() || isOnCooldown()) return;
        if (screen.getMinecraft().level != null) {
            lastClickTick = screen.getMinecraft().level.getGameTime();
        }
        PacketDistributor.sendToServer(new SummonTeamPacket(screen.selectedTeamIndex()));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
