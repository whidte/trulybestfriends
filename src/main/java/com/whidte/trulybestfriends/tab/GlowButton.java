package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.network.GlowPetPacket;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

class GlowButton extends AbstractWidget {
	private final TrulyScreen screen;

	public GlowButton(int x, int y, TrulyScreen screen) {
		super(x, y, 20, 20, Component.empty());
		this.screen = screen;
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		if (!screen.hasSelection()) return;
		int frameV = isHovered() ? 20 : 0;
		guiGraphics.blit(TrulyScreen.WIDGET_BUTTON, getX(), getY(), 0, frameV, 20, 20, 256, 256);
		guiGraphics.blit(TrulyScreen.GLOWING_ICON, getX() + 1, getY() + 1, 0, 0, TrulyScreen.GLOW_BUTTON_SIZE, TrulyScreen.GLOW_BUTTON_SIZE, TrulyScreen.GLOW_BUTTON_SIZE, TrulyScreen.GLOW_BUTTON_SIZE);

		if (isHovered()) {
			guiGraphics.renderTooltip(screen.font(), Component.translatable("trulybestfriends.glow.tooltip"), mouseX, mouseY);
		}
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		if (!screen.hasSelection()) return;
		long now = System.currentTimeMillis();
		if (now - screen.lastGlowClickTime >= 3000) {
			screen.lastGlowClickTime = now;
			trulybestfriends.CHANNEL.sendToServer(new GlowPetPacket(screen.getSelectedUuid()));
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration) {
		this.defaultButtonNarrationText(narration);
	}
}
