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
	static final ResourceLocation WIDGET_BUTTON = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/widget_button.png");
	static final ResourceLocation SCROLLBAR = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/scrollbar.png");
	static final ResourceLocation SCROLLBAR_THUMB = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/scrollbar_thumb.png");
	static final ResourceLocation PET_ENTRY = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/pet_entry.png");
	static final ResourceLocation DELETE_ICON = ResourceLocation.withDefaultNamespace("container/beacon/cancel");
	static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
	static final ResourceLocation BUTTON_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
	static final ResourceLocation BUTTON_DISABLED = ResourceLocation.withDefaultNamespace("widget/button_disabled");
	static final ResourceLocation HEART_CONTAINER = ResourceLocation.withDefaultNamespace("hud/heart/container");
	static final ResourceLocation HEART_FULL = ResourceLocation.withDefaultNamespace("hud/heart/full");
	static final ResourceLocation HEALTH_BAR_BACKGROUND = ResourceLocation.withDefaultNamespace("boss_bar/red_background");
	static final ResourceLocation HEALTH_BAR_PROGRESS = ResourceLocation.withDefaultNamespace("boss_bar/red_progress");
	private static final ResourceLocation[] PRIORITY_SPRITES = {
			ResourceLocation.withDefaultNamespace("notification/1"),
			ResourceLocation.withDefaultNamespace("notification/2"),
			ResourceLocation.withDefaultNamespace("notification/3"),
			ResourceLocation.withDefaultNamespace("notification/4"),
			ResourceLocation.withDefaultNamespace("notification/5"),
			ResourceLocation.withDefaultNamespace("notification/more")
	};

	// --- Utility ---
	static ResourceLocation prioritySprite(int priority) {
		return PRIORITY_SPRITES[Math.max(1, Math.min(6, priority)) - 1];
	}

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
