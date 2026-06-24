package com.whidte.trulybestfriends.tab;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Shared layout constants, textures, and utility helpers used across the pet tab UI classes. */
final class TrulyConstants {

	private TrulyConstants() {}

	// --- Layout ---
	static final int MAX_VISIBLE = 8;
	static final int COLUMNS = 2;
	static final int ENTRY_WIDTH = 40;
	static final int ENTRY_HEIGHT = 37;
	static final int ENTRY_GAP_X = 2;
	static final int LIST_PANEL_OFFSET_X = 86;
	static final int LIST_PANEL_OFFSET_Y = 10;
	static final int LIST_PANEL_HEIGHT = (MAX_VISIBLE / COLUMNS) * ENTRY_HEIGHT;
	static final int LIST_PANEL_WIDTH = COLUMNS * (ENTRY_WIDTH + ENTRY_GAP_X) - ENTRY_GAP_X + 4;
	static final int SCROLLBAR_RIGHT_OFFSET = 8;
	static final int SCROLLBAR_WIDTH = 4;
	static final int ENTITY_PREVIEW_OFFSET_X = 35;
	static final int ENTITY_PREVIEW_OFFSET_Y = 50;
	static final int GLOW_X = 61;
	static final int GLOW_Y = 13;
	static final int ACTION_X = 61;
	static final int ACTION_Y = 37;
	static final int HEART_X = 7;
	static final int HEART_Y = 62;
	static final int BAR_MIDDLE_WIDTH = 50;
	static final int NAME_Y = 74;
	static final int SPECIES_Y = 84;
	static final int LOCATION_Y = 98;
	static final int SUMMON_TO_PLAYER_X = 7;
	static final int SUMMON_TO_PLAYER_Y = 134;
	static final int SUMMON_TO_PLAYER_W = 60;

	// --- Scale / Rotation ---
	static final float BASE_SCALE = 17f;
	static final float DEFAULT_ROT_X = -37f;
	static final float DEFAULT_ROT_Y = -73f;
	static final float HORSE_MAX_DIM = 1.6f;
	static final float BASE_DRAG_SENSITIVITY = 0.25f;
	static final int REFERENCE_WINDOW_WIDTH = 1920;
	static final int REFRESH_INTERVAL = 20;
	static final int GLOW_BUTTON_SIZE = 18;

	// --- Textures ---
	static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/empty.png");
	static final ResourceLocation GLOWING_ICON = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/mob_effect/glowing.png");
	static final ResourceLocation BEACON_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/container/beacon.png");
	static final ResourceLocation WIDGET_BUTTON = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/widget_button.png");
	static final ResourceLocation WIDGETS_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/widgets.png");
	static final ResourceLocation ICONS_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/icons.png");
	static final ResourceLocation BARS_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/bars.png");
	static final ResourceLocation SCROLLBAR = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/scrollbar.png");
	static final ResourceLocation SCROLLBAR_THUMB = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/scrollbar_thumb.png");
	static final ResourceLocation PET_ENTRY = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/pet_entry.png");

	// --- Utility ---

	/** Discard a preview entity from the client world. */
	static void discardPreviewEntity(LivingEntity entity) {
		if (entity != null && entity.isAlive()) {
			entity.discard();
		}
	}

	/** Set xRot bypassing the per-entity xRotO syncing. */
	static void setXRotUnclamped(Entity entity, float value) {
		try {
			var f = Entity.class.getDeclaredField("xRot");
			f.setAccessible(true);
			f.setFloat(entity, value);
		} catch (Exception e) {
			entity.setXRot(value);
		}
	}
}
