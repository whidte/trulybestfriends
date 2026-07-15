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
	static final int COLUMNS = 2;
	/** 单个宠物条目在界面中的显示宽度，单位为像素。 */
	static final int ENTRY_WIDTH = 40;
	/** pet_entry.png 中单个条目的原始纹理宽度，单位为像素。 */
	static final int ENTRY_TEXTURE_WIDTH = 40;
	/** 单个宠物条目的完整显示高度，与 pet_entry.png 中单帧高度一致。 */
	static final int ENTRY_HEIGHT = 37;
	/** 相邻两行宠物条目的纵向步长，比完整高度少 1 像素。 */
	static final int ENTRY_ROW_STEP = ENTRY_HEIGHT - 1;
	/** 同一行两个宠物条目之间的水平间距。 */
	static final int ENTRY_GAP_X = 2;
	/** 宠物列表左边缘相对于标签页面板左边缘的 X 偏移。 */
	static final int LIST_PANEL_OFFSET_X = 86;
	/** 宠物列表上边缘相对于标签页面板上边缘的 Y 偏移。 */
	static final int LIST_PANEL_OFFSET_Y = 17;
	/** 宠物列表区域总高度，包含首行完整高度和后续各行的纵向步长。 */
	static final int LIST_PANEL_HEIGHT = ENTRY_HEIGHT + (MAX_VISIBLE / COLUMNS - 1) * ENTRY_ROW_STEP;
	/** 宠物列表区域总宽度，包含所有列、列间距和右侧留白。 */
	static final int LIST_PANEL_WIDTH = COLUMNS * (ENTRY_WIDTH + ENTRY_GAP_X) - ENTRY_GAP_X + 4;
	/** 滚动条左边缘相对于标签页面板右边缘向左的距离。 */
	static final int SCROLLBAR_RIGHT_OFFSET = 8;
	/** 宠物列表滚动条的显示宽度。 */
	static final int SCROLLBAR_WIDTH = 4;
	/** 物种筛选器和搜索框相对于标签页面板上边缘的 Y 偏移。 */
	static final int LIST_CONTROLS_OFFSET_Y = 4;
	/** 顶部列表模式控件的统一高度。 */
	static final int LIST_CONTROL_HEIGHT = 12;
	/** 放大镜模式切换按钮的正方形边长。 */
	static final int SEARCH_TOGGLE_SIZE = 12;
	/** 放大镜模式切换按钮相对于标签页面板左边缘的 X 偏移。 */
	static final int SEARCH_TOGGLE_OFFSET_X = LIST_PANEL_OFFSET_X;
	/** 物种筛选器或搜索框相对于标签页面板左边缘的共享 X 偏移。 */
	static final int LIST_MODE_CONTROL_OFFSET_X = SEARCH_TOGGLE_OFFSET_X + SEARCH_TOGGLE_SIZE + ENTRY_GAP_X;
	/** 物种筛选器或搜索框占用放大镜右侧剩余空间后的共享宽度。 */
	static final int LIST_MODE_CONTROL_WIDTH = LIST_PANEL_WIDTH - SEARCH_TOGGLE_SIZE - ENTRY_GAP_X;
	/** 让左侧已选宠物信息始终显示在实体预览上方的 Z 深度。 */
	static final int PET_INFO_OVERLAY_Z = 100;
	/** 让物种下拉栏始终显示在所有宠物列表实体预览上方的 Z 深度。 */
	static final int SPECIES_DROPDOWN_OVERLAY_Z = 200;
	/** 左侧已选宠物预览锚点相对于标签页面板左边缘的 X 偏移。 */
	static final int ENTITY_PREVIEW_OFFSET_X = 35;
	/** 左侧已选宠物预览锚点相对于标签页面板上边缘的 Y 偏移。 */
	static final int ENTITY_PREVIEW_OFFSET_Y = 50;
	/** 发光按钮相对于标签页面板左边缘的 X 坐标。 */
	static final int GLOW_X = 61;
	/** 发光按钮相对于标签页面板上边缘的 Y 坐标。 */
	static final int GLOW_Y = 13;
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
	/** 宠物名称信息相对于标签页面板上边缘的 Y 坐标。 */
	static final int NAME_Y = 74;
	/** 宠物物种信息相对于标签页面板上边缘的 Y 坐标。 */
	static final int SPECIES_Y = 84;
	/** 宠物位置或状态信息相对于标签页面板上边缘的 Y 坐标。 */
	static final int LOCATION_Y = 98;
	/** 召唤至玩家按钮相对于标签页面板左边缘的 X 坐标。 */
	static final int SUMMON_TO_PLAYER_X = 7;
	/** 召唤至玩家按钮相对于标签页面板上边缘的 Y 坐标。 */
	static final int SUMMON_TO_PLAYER_Y = 134;
	/** 召唤至玩家按钮的宽度。 */
	static final int SUMMON_TO_PLAYER_W = 60;

	// --- Scale / Rotation ---
	/** 普通宠物模型在界面中渲染时使用的基础缩放值。 */
	static final float BASE_SCALE = 17f;
	/** 列表条目中的宠物预览相对于基础缩放值的比例。 */
	static final float LIST_ENTRY_SCALE_RATIO = 25f / 50f;
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
	/** 发光按钮的正方形边长。 */
	static final int GLOW_BUTTON_SIZE = 18;

	// --- Textures ---
	/** 标签页主面板背景纹理。 */
	static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/empty.png");
	/** 原版透明背景放大镜 GUI sprite。 */
	static final ResourceLocation SEARCH_ICON = ResourceLocation.withDefaultNamespace("icon/search");
	/** 原版发光效果图标纹理。 */
	static final ResourceLocation GLOWING_ICON = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/mob_effect/glowing.png");
	/** 模组内通用操作按钮的自定义纹理。 */
	static final ResourceLocation WIDGET_BUTTON = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/widget_button.png");
	/** 宠物列表滚动条轨道纹理。 */
	static final ResourceLocation SCROLLBAR = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/scrollbar.png");
	/** 宠物列表滚动条滑块纹理。 */
	static final ResourceLocation SCROLLBAR_THUMB = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/scrollbar_thumb.png");
	/** 宠物列表条目纹理，纵向包含普通态和选中态。 */
	static final ResourceLocation PET_ENTRY = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/pet_entry.png");
	/** 删除宠物数据按钮使用的原版取消图标。 */
	static final ResourceLocation DELETE_ICON = ResourceLocation.withDefaultNamespace("container/beacon/cancel");
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
