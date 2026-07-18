package com.whidte.trulybestfriends.tab;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.whidte.trulybestfriends.tab.RenderHelper.detectMultipartYBase;
import static com.whidte.trulybestfriends.tab.RenderHelper.buildMultipartPose;
import static com.whidte.trulybestfriends.tab.RenderHelper.multipartPitchRadians;
import static com.whidte.trulybestfriends.tab.RenderHelper.renderEntityInInventory;
import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

class PetEntry extends AbstractWidget {
	private static final Quaternionf NORMAL_QUAT = new Quaternionf().rotateZ((float) Math.PI)
			.rotateX(DEFAULT_ROT_Y * 20.0F * ((float) Math.PI / 180F));
	private static final Quaternionf NORMAL_QUAT_PITCH = new Quaternionf().rotateX(DEFAULT_ROT_Y * 20.0F * ((float) Math.PI / 180F));
	private static final Quaternionf MULTIPART_QUAT_PITCH = new Quaternionf().rotateX(-DEFAULT_ROT_Y * 20.0F * ((float) Math.PI / 180F));
	private static final Map<String, Quaternionf> MULTIPART_QUATS = new ConcurrentHashMap<>();

	private final int index;
	private final TrulyScreen screen;

	public PetEntry(int x, int y, int width, int height, Component message, int index, TrulyScreen screen) {
		super(x, y, width, height, message);
		this.index = index;
		this.screen = screen;
	}

	boolean isSelected() {
		return screen.selectedPetIndex == index;
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		boolean isSelected = isSelected();
		int vOffset = isSelected ? ENTRY_HEIGHT : 0;
		guiGraphics.blit(PET_ENTRY, getX(), getY(), ENTRY_WIDTH, ENTRY_HEIGHT,
				0, vOffset, ENTRY_TEXTURE_WIDTH, ENTRY_HEIGHT,
				ENTRY_TEXTURE_WIDTH, ENTRY_HEIGHT * 2);

		var font = screen.getMinecraft().font;
		UUID uuid = screen.petUuids.get(index);
		CompoundTag nbt = screen.petNbtCache.get(uuid);
		boolean isDead = nbt != null && nbt.contains("Health") && nbt.getFloat("Health") <= 0;
		int textColor = isDead ? 0xFF5555 : (isSelected ? 0xFFFFFF : 0xA0A0A0);
		int textY = getY() + height - 9;

		LivingEntity pet = screen.getPreviewEntity(uuid);
		if (pet != null) {
			float miniScale = TrulyScreen.computePreviewScale(pet, BASE_SCALE * LIST_ENTRY_SCALE_RATIO);
			int miniX = getX() + width / 2;
			int miniY = getY() + (textY - getY()) / 2 + 7;
			boolean multipart = pet.getScale() > 1.0001f || (pet.getParts() != null && pet.getParts().length > 0);
			Quaternionf quat;
			Quaternionf quatPitch;
			if (multipart) {
				String typeKey = pet.getType().builtInRegistryHolder().key().location().toString();
				quat = MULTIPART_QUATS.computeIfAbsent(typeKey, ignored ->
						buildMultipartPose(
								detectMultipartYBase(pet) - DEFAULT_ROT_X * 20.0F * ((float) Math.PI / 180F),
								multipartPitchRadians(DEFAULT_ROT_Y)));
				quatPitch = MULTIPART_QUAT_PITCH;
			} else {
				quat = NORMAL_QUAT;
				quatPitch = NORMAL_QUAT_PITCH;
			}

			if (!multipart) {
				pet.yBodyRot = 180.0F + DEFAULT_ROT_X * 20.0F;
				pet.setYRot(180.0F + DEFAULT_ROT_X * 40.0F);
				pet.setXRot(-DEFAULT_ROT_Y * 20.0F);
				pet.yHeadRot = pet.yBodyRot;
				pet.yHeadRotO = pet.yBodyRot;
			} else {
				pet.yBodyRot = 0f;
				pet.yBodyRotO = 0f;
				pet.setYRot(0f);
				pet.yRotO = 0f;
				pet.yHeadRot = 0f;
				pet.yHeadRotO = 0f;
			}

			guiGraphics.enableScissor(getX() + 1, getY() + 1, getX() + width - 1, textY - 1);
			try {
				renderEntityInInventory(guiGraphics, miniX, miniY, miniScale, quat, quatPitch, pet);
			} finally {
				guiGraphics.disableScissor();
			}
		}

		int priority = Math.max(1, Math.min(6, screen.petPriorities.getOrDefault(uuid, 6)));
		if (priority <= 5 || Screen.hasShiftDown()) {
			int srcU = 198 + (priority - 1) * 8;
			guiGraphics.blit(WIDGETS_TEXTURE, getX() + 1, getY() + 1, srcU, 22, 8, 8, 256, 256);
		}

		int textWidth = font.width(getMessage());
		int availableWidth = width - 6;
		guiGraphics.enableScissor(getX() + 3, getY(), getX() + width - 3, getY() + height);
		if (textWidth <= availableWidth) {
			guiGraphics.drawString(font, getMessage(), getX() + (width - textWidth) / 2, textY - 1, textColor);
		} else {
			int scrollOffset = RenderHelper.scrollingOffset(textWidth - availableWidth + 12);
			guiGraphics.drawString(font, getMessage(), getX() + 3 - scrollOffset, textY - 1, textColor);
		}
		guiGraphics.disableScissor();
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		if (Screen.hasShiftDown()) {
			UUID uuid = screen.petUuids.get(index);
			int oldPrio = screen.petPriorities.getOrDefault(uuid, 6);
			int newPrio = oldPrio - 1;
			if (newPrio < 1) newPrio = 6;
			screen.petPriorities.put(uuid, newPrio);
			writePriorityToDisk(uuid, newPrio);
			screen.sortNeeded = true;
			return;
		}
		if (screen.selectedPetIndex != index) screen.deletePromptUuid = null;
		screen.selectedPetIndex = index;
		screen.glowButton.visible = true;
		screen.deleteButton.visible = true;
		screen.actionButton.visible = true;
		screen.summonToPlayerButton.visible = true;
		screen.adjustScaleForCurrentPet();
		screen.rotX = DEFAULT_ROT_X;
		screen.rotY = DEFAULT_ROT_Y;
	}

	private void writePriorityToDisk(UUID uuid, int priority) {
		CompoundTag nbt = screen.petNbtCache.get(uuid);
		if (nbt != null) nbt.putInt("Priority", priority);
		com.whidte.trulybestfriends.trulybestfriends.CHANNEL.sendToServer(
				new com.whidte.trulybestfriends.network.SetPriorityPacket(uuid, priority));
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration) {
		this.defaultButtonNarrationText(narration);
	}
}
