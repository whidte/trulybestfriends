package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.network.DeletePetDataPacket;
import com.whidte.trulybestfriends.network.GlowPetPacket;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

class GlowButton extends AbstractWidget {
	private final TrulyScreen screen;

	public GlowButton(int x, int y, TrulyScreen screen) {
		super(x, y, 20, 20, Component.empty());
		this.screen = screen;
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		if (!screen.hasSelection()) return;
		boolean deleteMode = screen.isSelectedPetInactive();
		int frameV = isHovered() ? 20 : 0;
		guiGraphics.blit(WIDGET_BUTTON, getX(), getY(), 0, frameV, 20, 20, 256, 256);
		if (deleteMode) {
			guiGraphics.blit(BEACON_TEXTURE, getX() + 1, getY() + 1, 112, 220, GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE, 256, 256);
		} else {
			guiGraphics.blit(GLOWING_ICON, getX() + 1, getY() + 1, 0, 0, GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE);
		}

		if (isHovered()) {
			guiGraphics.renderTooltip(screen.font(), getTooltip(deleteMode), mouseX, mouseY);
		}
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		if (!screen.hasSelection()) return;
		if (screen.isSelectedPetInactive()) {
			boolean armed = screen.getSelectedUuid().equals(screen.deletePromptUuid);
			if (Screen.hasShiftDown() && armed) {
				trulybestfriends.CHANNEL.sendToServer(new DeletePetDataPacket(screen.getSelectedUuid()));
			} else if (!armed) {
				screen.deletePromptUuid = screen.getSelectedUuid();
			}
			return;
		}

		long now = System.currentTimeMillis();
		if (now - screen.lastGlowClickTime >= 3000) {
			screen.lastGlowClickTime = now;
			trulybestfriends.CHANNEL.sendToServer(new GlowPetPacket(screen.getSelectedUuid()));
		}
	}

	private Component getTooltip(boolean deleteMode) {
		if (!deleteMode) return Component.translatable("trulybestfriends.glow.tooltip");
		if (screen.getSelectedUuid().equals(screen.deletePromptUuid)) {
			return Component.translatable("trulybestfriends.delete.confirm");
		}
		return Component.translatable("trulybestfriends.delete.tooltip");
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration) {
		this.defaultButtonNarrationText(narration);
	}
}
