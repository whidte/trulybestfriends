package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.network.AreaRecallPacket;
import com.whidte.trulybestfriends.network.RecallPetPacket;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

class ActionButton extends AbstractWidget {
	private static final ResourceLocation ICON_RECALL = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/empty_bottle.png");
	private static final ResourceLocation ICON_SUMMON = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/world_in_a_bottle.png");
	private static final ResourceLocation ICON_AREA_RECALL = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/absorption_bottle.png");
	private static final int ICON_SIZE = 16;

	private final TrulyScreen screen;

	public ActionButton(int x, int y, TrulyScreen screen) {
		super(x, y, 20, 20, Component.empty());
		this.screen = screen;
	}

	private boolean isPetDead() {
		CompoundTag nbt = screen.getSelectedNbt();
		return nbt != null && nbt.contains("Health") && nbt.getFloat("Health") <= 0;
	}

	private boolean isPetRecalled() {
		CompoundTag nbt = screen.getSelectedNbt();
		return nbt != null && nbt.getBoolean("Recalled");
	}

	private long getLastClickTime() {
		UUID uuid = screen.getSelectedUuid();
		return uuid != null ? screen.cooldowns.getOrDefault(uuid, 0L) : 0;
	}

	private void setLastClickTime(long time) {
		UUID uuid = screen.getSelectedUuid();
		if (uuid != null) {
			screen.cooldowns.put(uuid, time);
		}
	}

	private Component getDynamicTooltip() {
		if (isPetDead()) {
			return Component.translatable("trulybestfriends.action.dead").setStyle(Style.EMPTY.withColor(ChatFormatting.RED));
		}
		long remaining = getLastClickTime() + Config.recallCooldownMs - System.currentTimeMillis();
		if (remaining > 0) {
			double secs = remaining / 1000.0;
			return Component.translatable("trulybestfriends.action.cooldown", String.format("%.1f", secs))
					.setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY));
		}
		boolean recalled = isPetRecalled();
		if (recalled) {
			return Component.translatable("trulybestfriends.action.summon");
		}
		UUID uuid = screen.getSelectedUuid();
		CompoundTag nbt = screen.getSelectedNbt();
		if (Config.recallRange >= 0 && nbt != null && !screen.isPetOnShoulder(uuid) && isOutOfRange(nbt)) {
			return Component.translatable("trulybestfriends.recall.out_of_range")
					.setStyle(Style.EMPTY.withColor(ChatFormatting.RED));
		}
		return Component.translatable("trulybestfriends.action.recall");
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		if (!screen.hasSelection()) return;

		boolean shiftHeld = Screen.hasShiftDown();
		boolean dead = isPetDead();
		boolean recalled = isPetRecalled();
		this.active = shiftHeld || !dead;

		int frameV;
		if (dead && !shiftHeld) {
			frameV = 40;
		} else {
			frameV = isHovered() ? 20 : 0;
		}
		guiGraphics.blit(WIDGET_BUTTON, getX(), getY(), 0, frameV, 20, 20, 256, 256);

		// Center 16x16 icon
		ResourceLocation icon;
		if (shiftHeld) {
			icon = ICON_AREA_RECALL;
		} else if (recalled) {
			icon = ICON_SUMMON;
		} else {
			icon = ICON_RECALL;
		}
		int iconX = getX() + (width - ICON_SIZE) / 2;
		int iconY = getY() + (height - ICON_SIZE) / 2;
		guiGraphics.blit(icon, iconX, iconY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

		if (mouseX >= getX() && mouseX <= getX() + width && mouseY >= getY() && mouseY <= getY() + height) {
			Component tooltip = shiftHeld ? getAreaRecallTooltip() : getDynamicTooltip();
			guiGraphics.renderTooltip(screen.font(), tooltip, mouseX, mouseY);
		}
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		if (!screen.hasSelection()) return;

		// Shift-held: area recall — ignores cooldown and dead state
		if (Screen.hasShiftDown()) {
			trulybestfriends.CHANNEL.sendToServer(new AreaRecallPacket(screen.areaRecallRange));
			return;
		}

		if (isPetDead()) return;
		long now = System.currentTimeMillis();
		if (now - getLastClickTime() < Config.recallCooldownMs) return;
		UUID uuid = screen.getSelectedUuid();
		CompoundTag nbt = screen.getSelectedNbt();
		boolean currentlyRecalled = nbt != null && nbt.getBoolean("Recalled");

		if (!currentlyRecalled && nbt != null && Config.recallRange >= 0
				&& !screen.isPetOnShoulder(uuid) && isOutOfRange(nbt)) {
			return;
		}

		setLastClickTime(now);
		trulybestfriends.CHANNEL.sendToServer(new RecallPetPacket(uuid));

		if (nbt != null) {
			if (currentlyRecalled) {
				nbt.remove("Recalled");
			} else {
				nbt.putBoolean("Recalled", true);
			}
		}
	}

	private boolean isOutOfRange(CompoundTag nbt) {
		var player = screen.getMinecraft().player;
		if (player == null) return true;

		if (nbt.contains("Dimension")) {
			String dim = nbt.getString("Dimension");
			if (!dim.equals(player.level().dimension().location().toString())) return true;
		}

		if (nbt.contains("Pos")) {
			var posList = nbt.getList("Pos", 6);
			if (posList.size() >= 3) {
				double dx = posList.getDouble(0) - player.getX();
				double dy = posList.getDouble(1) - player.getY();
				double dz = posList.getDouble(2) - player.getZ();
				if (Math.sqrt(dx * dx + dy * dy + dz * dz) > Config.recallRange) return true;
			}
		}

		return false;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration) {
		this.defaultButtonNarrationText(narration);
	}

	private Component getAreaRecallTooltip() {
		return Component.translatable("trulybestfriends.action.area_recall", screen.areaRecallRange);
	}
}
