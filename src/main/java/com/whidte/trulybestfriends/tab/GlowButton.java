package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.network.GlowPetPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

class GlowButton extends AbstractWidget {
    private final TrulyScreen screen;

    GlowButton(int x, int y, TrulyScreen screen) {
        super(x, y, 20, 20, Component.empty());
        this.screen = screen;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!screen.hasSelection()) return;
        int frameV = isHovered() ? 20 : 0;
        graphics.blit(WIDGET_BUTTON, getX(), getY(), 0, frameV, 20, 20, 256, 256);
        graphics.blit(GLOWING_ICON, getX() + 1, getY() + 1, 0, 0,
                GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE);
        if (isHovered()) {
            graphics.renderTooltip(screen.font(), Component.translatable("trulybestfriends.glow.tooltip"), mouseX, mouseY);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!screen.hasSelection()) return;
        long now = System.currentTimeMillis();
        if (now - screen.lastGlowClickTime >= 3000) {
            screen.lastGlowClickTime = now;
            PacketDistributor.sendToServer(new GlowPetPacket(screen.getSelectedUuid()));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        this.defaultButtonNarrationText(narration);
    }
}
