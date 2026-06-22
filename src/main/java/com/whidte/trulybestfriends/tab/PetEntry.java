package com.whidte.trulybestfriends.tab;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

import java.util.UUID;

class PetEntry extends AbstractWidget {
	private final int index;
	private final TrulyScreen screen;

	public PetEntry(int x, int y, int width, int height, Component message, int index, TrulyScreen screen) {
		super(x, y, width, height, message);
		this.index = index;
		this.screen = screen;
	}

	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		boolean isSelected = screen.selectedPetIndex == index;

		// Draw entry background from texture (top half = unselected, bottom half = selected)
		int vOffset = isSelected ? ENTRY_HEIGHT : 0;
		guiGraphics.blit(PET_ENTRY, getX(), getY(), ENTRY_WIDTH, ENTRY_HEIGHT, 0, vOffset, ENTRY_WIDTH, ENTRY_HEIGHT, ENTRY_WIDTH, ENTRY_HEIGHT * 2);

		var font = screen.getMinecraft().font;
		UUID uuid = screen.petUuids.get(index);
		CompoundTag nbt = screen.petNbtCache.get(uuid);
		boolean isDead = nbt != null && nbt.contains("Health") && nbt.getFloat("Health") <= 0;
		int textColor = isDead ? 0xFF5555 : (isSelected ? 0xFFFFFF : 0xA0A0A0);
		int textY = getY() + height - 9;

		// Create a temporary preview entity
		LivingEntity pet = createPetEntryEntity(uuid);
		if (pet != null) {
			// Render pet preview at top center
			float maxDim = Math.max(pet.getBbWidth(), pet.getBbHeight());
			float miniScale = maxDim > 0 ? Mth.clamp(BASE_SCALE * (28f / 50f) * (HORSE_MAX_DIM / maxDim), 3f, 28f) : BASE_SCALE * (28f / 50f);

			int miniX = getX() + width / 2;
			int miniY = getY() + (textY - getY()) / 2 + 7;

			Quaternionf quat = (new Quaternionf()).rotateZ((float) Math.PI);
			Quaternionf quatPitch = (new Quaternionf()).rotateX(DEFAULT_ROT_Y * 20.0F * ((float) Math.PI / 180F));
			quat.mul(quatPitch);

			pet.yBodyRot = 180.0F + DEFAULT_ROT_X * 20.0F;
			pet.setYRot(180.0F + DEFAULT_ROT_X * 40.0F);
			try {
				var xRotField = Entity.class.getDeclaredField("xRot");
				xRotField.setAccessible(true);
				xRotField.setFloat(pet, -DEFAULT_ROT_Y * 20.0F);
			} catch (Exception e) {
				pet.setXRot(-DEFAULT_ROT_Y * 20.0F);
			}
			pet.yHeadRot = pet.yBodyRot;
			pet.yHeadRotO = pet.yBodyRot;

			InventoryScreen.renderEntityInInventory(guiGraphics, miniX, miniY, (int) miniScale, quat, quatPitch, pet);

			// Discard immediately after render
			discardPreviewEntity(pet);
		}

		// Draw priority icon in top-left
		int priority = screen.petPriorities.getOrDefault(uuid, 6);
		priority = Math.max(1, Math.min(6, priority));
		if (priority <= 5 || Screen.hasShiftDown()) {
			int srcU = 198 + (priority - 1) * 8;
			int srcV = 22;
			guiGraphics.blit(WIDGETS_TEXTURE,
					getX() + 1, getY() + 1,
					srcU, srcV, 8, 8, 256, 256);
		}

		// Draw pet name text - centered horizontally, bottom-aligned
		int textWidth = font.width(getMessage());
		int availableWidth = width - 6;

		guiGraphics.enableScissor(getX() + 3, getY(), getX() + width - 3, getY() + height);

		if (textWidth <= availableWidth) {
			int textX = getX() + (width - textWidth) / 2;
			guiGraphics.drawString(font, getMessage(), textX, textY - 1, textColor);
		} else {
			long time = System.currentTimeMillis();
			int overflow = textWidth - availableWidth + 12;
			int cycleMs = 8000;
			long phase = time % cycleMs;

			int scrollOffset;
			if (phase < 2000) {
				scrollOffset = 0;
			} else if (phase < 6000) {
				scrollOffset = (int) (overflow * (phase - 2000) / 4000f);
			} else {
				scrollOffset = overflow;
			}

			guiGraphics.drawString(font, getMessage(),
					getX() + 3 - scrollOffset, textY - 1, textColor);
		}

		guiGraphics.disableScissor();
	}

	private LivingEntity createPetEntryEntity(UUID uuid) {
		if (screen.getMinecraft().level == null) return null;
		CompoundTag nbt = screen.petNbtCache.get(uuid);
		if (nbt == null) return null;
		String typeKey = nbt.getString("EntityType");
		if (typeKey.isEmpty()) return null;
		EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(typeKey));
		if (type == null) return null;
		Entity entity = type.create(screen.getMinecraft().level);
		if (entity == null) return null;
		try { entity.load(nbt); } catch (Exception e) { return null; }
		return entity instanceof LivingEntity le ? le : null;
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		if (Screen.hasShiftDown()) {
			// Shift+Click: cycle priority 6→5→4→3→2→1→6
			UUID uuid = screen.petUuids.get(index);
			int oldPrio = screen.petPriorities.getOrDefault(uuid, 6);
			int newPrio = oldPrio - 1;
			if (newPrio < 1) newPrio = 6;
			screen.petPriorities.put(uuid, newPrio);
			writePriorityToDisk(uuid, newPrio);
			screen.sortNeeded = true;
			return;
		}
		screen.selectedPetIndex = index;
		screen.glowButton.visible = true;
		screen.actionButton.visible = true;
		screen.summonToPlayerButton.visible = true;
		screen.adjustScaleForCurrentPet();
		screen.rotX = DEFAULT_ROT_X;
		screen.rotY = DEFAULT_ROT_Y;
	}

	private void writePriorityToDisk(UUID uuid, int priority) {
		// Optimistic local cache update so the UI re-sorts immediately.
		// The server is the source of truth and will push back the canonical
		// NBT via SyncPetDataPacket.update, which overwrites this cache entry.
		CompoundTag nbt = screen.petNbtCache.get(uuid);
		if (nbt != null) {
			nbt.putInt("Priority", priority);
		}
		com.whidte.trulybestfriends.trulybestfriends.CHANNEL.sendToServer(
				new com.whidte.trulybestfriends.network.SetPriorityPacket(uuid, priority));
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration) {
		this.defaultButtonNarrationText(narration);
	}
}
