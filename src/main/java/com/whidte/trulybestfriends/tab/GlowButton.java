package com.whidte.trulybestfriends.tab;

import net.neoforged.neoforge.network.PacketDistributor;

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
			guiGraphics.blitSprite(DELETE_ICON, getX() + 1, getY() + 1, GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE);
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
			// 死亡且实体已消失（Lost），或数据损坏（无 Pos/Dimension）：
			// 单击直接删除，无需确认。这类宠物无法通过放出/复活恢复（实体已不在世界）。
			if (screen.isSelectedPetDead() || screen.isSelectedPetDataCorrupted()) {
				PacketDistributor.sendToServer(new DeletePetDataPacket(screen.getSelectedUuid()));
				return;
			}
			// 已收回 / 实体未加载（Lost 但存活）的宠物：两步 Shift+点击确认。
			// 这些宠物仍可通过放出或区块加载恢复，不应一键删除。
			boolean armed = screen.getSelectedUuid().equals(screen.deletePromptUuid);
			if (Screen.hasShiftDown() && armed) {
				PacketDistributor.sendToServer(new DeletePetDataPacket(screen.getSelectedUuid()));
			} else if (!armed && !Screen.hasShiftDown()) {
				screen.deletePromptUuid = screen.getSelectedUuid();
			}
			return;
		}

		long now = System.currentTimeMillis();
		if (now - screen.lastGlowClickTime >= 3000) {
			screen.lastGlowClickTime = now;
			PacketDistributor.sendToServer(new GlowPetPacket(screen.getSelectedUuid()));
		}
	}

	private Component getTooltip(boolean deleteMode) {
		if (!deleteMode) return Component.translatable("trulybestfriends.glow.tooltip");
		if (screen.isSelectedPetDead() || screen.isSelectedPetDataCorrupted()) {
			return Component.translatable("trulybestfriends.delete.lost");
		}
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
