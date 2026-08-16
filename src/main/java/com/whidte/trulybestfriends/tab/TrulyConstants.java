package com.whidte.trulybestfriends.tab;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Shared layout constants, textures, and utility helpers used across the pet tab UI classes. */
final class TrulyConstants {

	private TrulyConstants() {}

	// --- Layout ---
	/** 宠物列表一页最多显示的条目数量。 */
	static final int MAX_VISIBLE = 8;
	/** 宠物列表每行显示的列数。 */
	static final int COLUMNS = 4;
	/** 单个宠物条目在界面中的显示宽度，单位为像素。 */
	static final int ENTRY_WIDTH = 40;
	/** pet_entry.png 中单个条目的原始纹理宽度，单位为像素。 */
	static final int ENTRY_TEXTURE_WIDTH = 40;
	/** 单个宠物条目的完整显示高度，与 pet_entry.png 中单帧高度一致。 */
	static final int ENTRY_HEIGHT = 37;
	/** 相邻两行宠物条目的纵向步长，比完整高度少 1 像素。 */
	static final int ENTRY_ROW_STEP = ENTRY_HEIGHT - 1;
	/** 相邻宠物条目的横向步长；比条目宽度少 1 像素以共用边框。 */
	static final int ENTRY_COLUMN_STEP = ENTRY_WIDTH - 1;
	/** 宠物列表左边缘相对于标签页面板左边缘的 X 偏移。 */
	static final int LIST_PANEL_OFFSET_X = 6;
	/** 宠物列表上边缘相对于标签页面板上边缘的 Y 偏移。 */
	static final int LIST_PANEL_OFFSET_Y = 88;
	/** 宠物列表区域总高度，包含首行完整高度和后续各行的纵向步长。 */
	static final int LIST_PANEL_HEIGHT = ENTRY_HEIGHT + (MAX_VISIBLE / COLUMNS - 1) * ENTRY_ROW_STEP;
	/** 宠物列表区域总宽度，包含所有列、列间距和右侧留白。 */
	static final int LIST_PANEL_WIDTH = ENTRY_WIDTH + (COLUMNS - 1) * ENTRY_COLUMN_STEP + 4;
	/** 村民滚动条在右移后的深色列表框内的 X 偏移。 */
	static final int SCROLLBAR_OFFSET_X = 164;
	/** 村民交易界面滚动条的原生宽度。 */
	static final int SCROLLBAR_WIDTH = 6;
	/** 村民交易界面滚动滑块的原生高度。 */
	static final int SCROLLBAR_THUMB_HEIGHT = 27;
	/** 物种筛选器和搜索框相对于标签页面板上边缘的 Y 偏移。 */
	static final int LIST_CONTROLS_OFFSET_Y = 75;
	/** 顶部列表模式控件的统一高度。 */
	static final int LIST_CONTROL_HEIGHT = 12;
	/** 放大镜模式切换按钮的正方形边长。 */
	static final int SEARCH_TOGGLE_SIZE = 12;
	/** Horizontal gap between the mode toggle and the filter or search field. */
	static final int LIST_CONTROL_GAP_X = 2;
	/** 放大镜模式切换按钮相对于标签页面板左边缘的 X 偏移。 */
	static final int SEARCH_TOGGLE_OFFSET_X = LIST_PANEL_OFFSET_X;
	/** 物种筛选器或搜索框相对于标签页面板左边缘的共享 X 偏移。 */
	static final int LIST_MODE_CONTROL_OFFSET_X = SEARCH_TOGGLE_OFFSET_X + SEARCH_TOGGLE_SIZE + LIST_CONTROL_GAP_X;
	/** 物种筛选器或搜索框的共享宽度。 */
	static final int LIST_MODE_CONTROL_WIDTH = 60;
	/** 让左侧已选宠物信息始终显示在实体预览上方的 Z 深度。 */
	static final int PET_INFO_OVERLAY_Z = 100;
	/** 让物种下拉栏始终显示在所有宠物列表实体预览上方的 Z 深度。 */
	static final int SPECIES_DROPDOWN_OVERLAY_Z = 200;
	/** 左侧已选宠物预览锚点相对于标签页面板左边缘的 X 偏移。 */
	static final int ENTITY_PREVIEW_OFFSET_X = 35;
	/** 左侧已选宠物预览锚点相对于标签页面板上边缘的 Y 偏移。 */
	static final int ENTITY_PREVIEW_OFFSET_Y = 50;
	/** 左上宠物预览背景相对于标签页面板的坐标与尺寸。 */
	static final int PET_PREVIEW_BACKGROUND_X = 10;
	static final int PET_PREVIEW_BACKGROUND_Y = 10;
	static final int PET_PREVIEW_BACKGROUND_SIZE = 50;
	/** 生命恢复按钮相对于标签页面板左边缘的 X 坐标。 */
	static final int HEAL_X = 61;
	/** 生命恢复按钮相对于标签页面板上边缘的 Y 坐标。 */
	static final int HEAL_Y = 13;
	/** 收回或释放按钮相对于标签页面板左边缘的 X 坐标。 */
	static final int ACTION_X = 61;
	/** 收回或释放按钮相对于标签页面板上边缘的 Y 坐标。 */
	static final int ACTION_Y = 37;
	/** 生命值心形图标相对于标签页面板左边缘的 X 坐标。 */
	static final int HEART_X = 7;
	/** 生命值心形图标相对于标签页面板上边缘的 Y 坐标。 */
	static final int HEART_Y = 62;
	/** 三段式生命条中间可伸缩部分的宽度。 */
	static final int BAR_MIDDLE_WIDTH = 50;
	/** 已选宠物文字详情相对于标签页面板左边缘的 X 坐标。 */
	static final int PET_INFO_OFFSET_X = 90;
	/** Square delete control aligned to the upper-right of the tab panel. */
	static final int DELETE_X = 157;
	static final int DELETE_Y = 5;
	static final int DELETE_BUTTON_SIZE = 14;
	/** Horizontal gap between a scrolling pet name and the delete control. */
	static final int PET_NAME_DELETE_GAP = 2;
	static final int PET_NAME_MAX_WIDTH = DELETE_X - PET_INFO_OFFSET_X - PET_NAME_DELETE_GAP;
	/** 宠物名称信息相对于标签页面板上边缘的 Y 坐标。 */
	static final int NAME_Y = 14;
	/** 宠物位置或状态信息相对于标签页面板上边缘的 Y 坐标。 */
	static final int LOCATION_Y = 28;
	/** 召唤至玩家按钮相对于标签页面板左边缘的 X 坐标。 */
	static final int SUMMON_TO_PLAYER_X = 90;
	/** 召唤至玩家按钮相对于标签页面板上边缘的 Y 坐标。 */
	static final int SUMMON_TO_PLAYER_Y = 49;
	/** 召唤至玩家按钮的宽度。 */
	static final int SUMMON_TO_PLAYER_W = 60;
	/** Squad icon button size (square hitbox). */
	static final int SQUAD_SIZE = 8;
	/** Squad icon drawn size inside the hitbox (scaled from 8x8 source). */
	static final int SQUAD_ICON_SIZE = 8;
	/** Squad button X offset relative to the tab panel left edge (right of summon). */
	static final int SQUAD_X = SUMMON_TO_PLAYER_X + SUMMON_TO_PLAYER_W + 2;
	/** Squad button Y offset relative to the tab panel top edge. */
	static final int SQUAD_Y = SUMMON_TO_PLAYER_Y + (20 - SQUAD_SIZE) / 2;
	/** Top-center 3x3 squad grid layout, shifted 6px right and 2px down. */
	static final int SQUAD_GRID_SLOT_SIZE = 18;
	static final int SQUAD_GRID_GAP = 4;
	static final int SQUAD_GRID_STEP = SQUAD_GRID_SLOT_SIZE + SQUAD_GRID_GAP;
	static final int SQUAD_GRID_SIZE = SQUAD_GRID_SLOT_SIZE * 3 + SQUAD_GRID_GAP * 2;
	static final int SQUAD_GRID_X = (176 - SQUAD_GRID_SIZE) / 2 + 6;
	static final int SQUAD_GRID_Y = 5 + 2;
	/** Squad grid cells mapped to formation member slots (center cell unused). */
	static final int[] SQUAD_CELL_SLOTS = {2, 1, 5, 3, -1, 6, 4, 8, 7};
	/** 群体召唤按钮放在单项按钮左侧 3px 处，并与单项按钮垂直居中对齐。 */
	static final int SQUAD_SUMMON_BUTTON_SIZE = 20;
	static final int SQUAD_SUMMON_X = SQUAD_X - 3 - SQUAD_SUMMON_BUTTON_SIZE;
	static final int SQUAD_SUMMON_Y = SQUAD_Y + (SQUAD_SIZE - SQUAD_SUMMON_BUTTON_SIZE) / 2;
	/** Team selector button occupying the center cell of the expanded 3x3 panel,
	 *  with the expanded panel inset 4px right and 4px down from the tab page corner. */
	static final int TEAM_SELECTOR_BUTTON_SIZE = 18;
	static final int TEAM_SELECTOR_PANEL_WIDTH = 58;
	static final int TEAM_SELECTOR_PANEL_HEIGHT = 63;
	static final int TEAM_SELECTOR_GRID_X = 2;
	static final int TEAM_SELECTOR_GRID_Y = 2;
	static final int TEAM_SELECTOR_CELL_WIDTH = 18;
	static final int TEAM_SELECTOR_CELL_HEIGHT = 20;
	static final int TEAM_SELECTOR_X = TEAM_SELECTOR_GRID_X + TEAM_SELECTOR_CELL_WIDTH + 4;
	static final int TEAM_SELECTOR_Y = TEAM_SELECTOR_GRID_Y + TEAM_SELECTOR_CELL_HEIGHT + 4;
	static final int TEAM_SELECTOR_FRAME_NORMAL_V = 60;
	static final int TEAM_SELECTOR_FRAME_HOVERED_V = 78;
	static final int TEAM_SELECTOR_FRAME_DISABLED_V = 96;

	// --- Scale / Rotation ---
	/** 普通宠物模型在界面中渲染时使用的基础缩放值。 */
	static final float BASE_SCALE = 17f;
	/** 列表条目中的宠物预览相对于基础缩放值的比例。 */
	static final float LIST_ENTRY_SCALE_RATIO = 26f / 50f;
	/** Squad slot pet rendering scale relative to the pet-list entry scale. */
	static final float SQUAD_PET_SCALE_RATIO = LIST_ENTRY_SCALE_RATIO / 2f;
	/** 宠物模型默认的水平旋转输入值，用于确定初始朝向。 */
	static final float DEFAULT_ROT_X = -37f;
	/** 宠物模型默认的垂直旋转输入值，用于确定初始俯仰角。 */
	static final float DEFAULT_ROT_Y = -73f;
	/** 自动计算普通宠物预览缩放时采用的马匹最大尺寸基准。 */
	static final float HORSE_MAX_DIM = 1.6f;
	/** 鼠标拖动宠物模型时使用的基础旋转灵敏度。 */
	static final float BASE_DRAG_SENSITIVITY = 0.25f;
	/** 计算不同窗口宽度下拖动灵敏度时使用的参考窗口宽度。 */
	static final int REFERENCE_WINDOW_WIDTH = 1920;
	/** 请求刷新已选宠物数据的间隔，单位为游戏刻。 */
	static final int REFRESH_INTERVAL = 20;
	/** 生命恢复按钮的正方形边长。 */
	static final int HEAL_BUTTON_SIZE = 18;
	// --- Textures ---
	/** 标签页主面板背景纹理。 */
	static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/empty.png");
	/** 从主面板中拆分出的左上宠物预览背景。 */
	static final ResourceLocation PET_PREVIEW_BACKGROUND =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/pet_preview_background.png");
	/** 原版透明背景放大镜 GUI sprite。 */
	static final ResourceLocation SEARCH_ICON = ResourceLocation.withDefaultNamespace("icon/search");
	/** 原版生命恢复效果图标纹理。 */
	static final ResourceLocation REGENERATION_ICON = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/mob_effect/regeneration.png");
	/** Vanilla Realms close-button sprites used by the delete control. */
	static final ResourceLocation DELETE_ICON = ResourceLocation.withDefaultNamespace("widget/cross_button");
	static final ResourceLocation DELETE_ICON_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/cross_button_highlighted");
	/** 模组内通用操作按钮的自定义纹理。 */
	static final ResourceLocation WIDGET_BUTTON = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/widget_button.png");
	/** Squad button icon. */
	static final ResourceLocation SQUAD_ICON =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/squad.png");
	/** Squad button hover highlight ring. */
	static final ResourceLocation SQUAD_BORDER =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/border.png");
	/** 单项模式按钮图标。 */
	static final ResourceLocation DETAILS_ICON =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/details.png");
	/** Squad formation slot texture. */
	static final ResourceLocation SQUAD_SLOT =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/slot.png");
	/** Light slot fill used for the selected team and unavailable empty squad slots. */
	static final ResourceLocation PLACEHOLDER =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/placeholder.png");
	/** Empty-slot add icon shown when a list pet is selected and the team is not full. */
	static final ResourceLocation PLUS_SIGN =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/plus_sign.png");
	/** Vanilla bundle tooltip assembled into a fixed 3x3 team selector background. */
	static final ResourceLocation TEAM_SELECTOR_BACKGROUND =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/bundle_tooltip_3x3.png");
	/** Squad summon button icon. */
	static final ResourceLocation SQUAD_SUMMON_ICON =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/release_bottle.png");
	/** 村民交易界面使用的原版滚动滑块 sprite。 */
	static final ResourceLocation SCROLLBAR_THUMB = ResourceLocation.withDefaultNamespace("container/villager/scroller");
	/** 村民交易界面在内容无需滚动时使用的禁用滑块 sprite。 */
	static final ResourceLocation SCROLLBAR_THUMB_DISABLED = ResourceLocation.withDefaultNamespace("container/villager/scroller_disabled");
	/** 宠物列表条目纹理，纵向包含普通态和选中态。 */
	static final ResourceLocation PET_ENTRY = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/pet_entry.png");
	/** 原版按钮的默认状态 sprite。 */
	static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
	/** 原版按钮的悬停或高亮状态 sprite。 */
	static final ResourceLocation BUTTON_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
	/** 原版按钮的禁用状态 sprite。 */
	static final ResourceLocation BUTTON_DISABLED = ResourceLocation.withDefaultNamespace("widget/button_disabled");
	/** 生命值心形图标的空容器 sprite。 */
	static final ResourceLocation HEART_CONTAINER = ResourceLocation.withDefaultNamespace("hud/heart/container");
	/** 生命值大于零时覆盖显示的完整心形 sprite。 */
	static final ResourceLocation HEART_FULL = ResourceLocation.withDefaultNamespace("hud/heart/full");
	/** 三段式生命条使用的红色背景 sprite。 */
	static final ResourceLocation HEALTH_BAR_BACKGROUND = ResourceLocation.withDefaultNamespace("boss_bar/red_background");
	/** 三段式生命条使用的红色进度 sprite。 */
	static final ResourceLocation HEALTH_BAR_PROGRESS = ResourceLocation.withDefaultNamespace("boss_bar/red_progress");
	/** 优先级 1 至 6 对应的原版通知图标，数组下标比优先级小 1。 */
	private static final ResourceLocation[] PRIORITY_SPRITES = {
			ResourceLocation.withDefaultNamespace("notification/1"),
			ResourceLocation.withDefaultNamespace("notification/2"),
			ResourceLocation.withDefaultNamespace("notification/3"),
			ResourceLocation.withDefaultNamespace("notification/4"),
			ResourceLocation.withDefaultNamespace("notification/5"),
			ResourceLocation.withDefaultNamespace("notification/more")
	};

	// --- Utility ---
	/** 根据 1 至 6 的宠物优先级返回对应的通知图标。 */
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
