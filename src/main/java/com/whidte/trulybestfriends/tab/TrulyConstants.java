package com.whidte.trulybestfriends.tab;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** 宠物标签页各界面类共用的布局常量、纹理和工具辅助方法。 */
final class TrulyConstants {

	private TrulyConstants() {}

	// --- 布局 ---
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
	/** 模式切换按钮与筛选或搜索控件之间的水平间距。 */
	static final int LIST_CONTROL_GAP_X = 2;
	/** 放大镜模式切换按钮相对于标签页面板左边缘的 X 偏移。 */
	static final int SEARCH_TOGGLE_OFFSET_X = LIST_PANEL_OFFSET_X;
	/** 物种筛选器或搜索框相对于标签页面板左边缘的共享 X 偏移。 */
	static final int LIST_MODE_CONTROL_OFFSET_X = SEARCH_TOGGLE_OFFSET_X + SEARCH_TOGGLE_SIZE + LIST_CONTROL_GAP_X;
	/** 物种筛选器或搜索框的共享宽度。 */
	static final int LIST_MODE_CONTROL_WIDTH = 60;
	/** 主面板内深色列表框的左右内边缘相对于标签页面板左边缘的 X 坐标（右值为不含）。 */
	static final int LIST_BOX_LEFT = 5;
	static final int LIST_BOX_RIGHT = 171;
	/** 排序方向按钮的边长，同时也是其贴图的原始尺寸。 */
	static final int SORT_BUTTON_SIZE = 10;
	/** 排序按钮与深色列表框右内边缘之间保留的留白。 */
	static final int SORT_BUTTON_MARGIN_RIGHT = 1;
	/** 排序方向按钮相对于标签页面板左边缘的 X 坐标。 */
	static final int SORT_BUTTON_X = LIST_BOX_RIGHT - SORT_BUTTON_MARGIN_RIGHT - SORT_BUTTON_SIZE;
	/** 排序方向按钮相对于标签页面板上边缘的 Y 坐标，与物种筛选器同高并在其内垂直居中。 */
	static final int SORT_BUTTON_Y = LIST_CONTROLS_OFFSET_Y + (LIST_CONTROL_HEIGHT - SORT_BUTTON_SIZE) / 2;
	/** 排序依据选择器的宽度，与物种筛选器保持一致以对齐同一排控件。 */
	static final int SORT_MODE_SELECTOR_WIDTH = LIST_MODE_CONTROL_WIDTH;
	/** 排序依据选择器与排序方向按钮之间的水平间距。 */
	static final int SORT_MODE_SELECTOR_GAP_X = 2;
	/** 排序依据选择器相对于标签页面板左边缘的 X 坐标，紧贴排序方向按钮左侧。 */
	static final int SORT_MODE_SELECTOR_X = SORT_BUTTON_X - SORT_MODE_SELECTOR_GAP_X - SORT_MODE_SELECTOR_WIDTH;
	/** 让左侧已选宠物信息始终显示在实体预览上方的 Z 深度。 */
	static final int PET_INFO_OVERLAY_Z = 100;
	/** 让物种下拉栏始终显示在所有宠物列表实体预览上方的 Z 深度。 */
	static final int LIST_DROPDOWN_OVERLAY_Z = 200;
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
	/** 标签页右上角的正方形删除控件。 */
	static final int DELETE_X = 157;
	static final int DELETE_Y = 5;
	static final int DELETE_BUTTON_SIZE = 14;
	/** 滚动宠物名称与删除控件之间的水平间距。 */
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

	/** 群体图标按钮的尺寸（正方形点击区域）。 */
	static final int SQUAD_SIZE = 8;
	/** 群体图标在点击区域内绘制的尺寸（由 8x8 源图缩放而来）。 */
	static final int SQUAD_ICON_SIZE = 8;
	/** 群体按钮相对于标签页面板左边缘的 X 偏移（位于召唤按钮右侧）。 */
	static final int SQUAD_X = SUMMON_TO_PLAYER_X + SUMMON_TO_PLAYER_W + 2;
	/** 群体按钮相对于标签页面板上边缘的 Y 偏移。 */
	static final int SQUAD_Y = SUMMON_TO_PLAYER_Y + (20 - SQUAD_SIZE) / 2;
	/** 顶部居中的 3x3 群体网格布局，向右偏移 6px、向下偏移 2px。 */
	static final int SQUAD_GRID_SLOT_SIZE = 18;
	static final int SQUAD_GRID_GAP = 4;
	static final int SQUAD_GRID_STEP = SQUAD_GRID_SLOT_SIZE + SQUAD_GRID_GAP;
	static final int SQUAD_GRID_SIZE = SQUAD_GRID_SLOT_SIZE * 3 + SQUAD_GRID_GAP * 2;
	static final int SQUAD_GRID_X = (176 - SQUAD_GRID_SIZE) / 2 + 6;
	static final int SQUAD_GRID_Y = 5 + 2;
	/** 群体网格单元格到编队成员槽位的映射（中心单元格未使用）。 */
	static final int[] SQUAD_CELL_SLOTS = {2, 1, 5, 3, -1, 6, 4, 8, 7};
	/** 群体召唤按钮放在单项按钮左侧 3px 处，并与单项按钮垂直居中对齐。 */
	static final int SQUAD_SUMMON_BUTTON_SIZE = 20;
	static final int SQUAD_SUMMON_X = SQUAD_X - 3 - SQUAD_SUMMON_BUTTON_SIZE;
	static final int SQUAD_SUMMON_Y = SQUAD_Y + (SQUAD_SIZE - SQUAD_SUMMON_BUTTON_SIZE) / 2;
	/** 队伍选择按钮，占据展开的 3x3 面板中心单元格，
	 *  展开面板相对于标签页角落向右内缩 4px、向下内缩 4px。 */
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

	// --- 缩放 / 旋转 ---
	/** 普通宠物模型在界面中渲染时使用的基础缩放值。 */
	static final float BASE_SCALE = 17f;
	/** 列表条目中的宠物预览相对于基础缩放值的比例。 */
	static final float LIST_ENTRY_SCALE_RATIO = 26f / 50f;
	/** 群体槽位宠物渲染缩放相对于宠物列表条目缩放的比例。 */
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
	/** 防止客户端操作按钮在相邻 tick 上重复发送数据包。 */
	static final int BUTTON_COOLDOWN_TICKS = 5;
	/** 生命恢复按钮的正方形边长。 */
	static final int HEAL_BUTTON_SIZE = 18;
	// --- 纹理 ---
	/** 标签页主面板背景纹理。 */
	static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/empty.png");
	/** 从主面板中拆分出的左上宠物预览背景。 */
	static final ResourceLocation PET_PREVIEW_BACKGROUND =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/pet_preview_background.png");
	/** 原版配方书纹理，其中包含列表模式切换按钮使用的搜索图标。 */
	static final ResourceLocation RECIPE_BOOK_TEXTURE =
			ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/recipe_book.png");
	/** 原版生命恢复效果图标纹理。 */
	static final ResourceLocation REGENERATION_ICON = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/mob_effect/regeneration.png");
	/** 为 1.20.1 打包附带的原版 1.21.1 Realms 关闭按钮 sprite 副本。 */
	static final ResourceLocation DELETE_ICON_TEXTURE = ResourceLocation.fromNamespaceAndPath(
			"truly_best_friends", "textures/gui/cross_button.png");
	static final ResourceLocation DELETE_ICON_HIGHLIGHTED_TEXTURE = ResourceLocation.fromNamespaceAndPath(
			"truly_best_friends", "textures/gui/cross_button_highlighted.png");
	static final int DELETE_ICON_SOURCE_SIZE = 14;
	/** 模组内通用操作按钮的自定义纹理。 */
	static final ResourceLocation WIDGET_BUTTON = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/widget_button.png");
	/** 原版通用控件纹理。 */
	static final ResourceLocation WIDGETS_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/widgets.png");
	/** 原版 HUD 图标纹理，其中包含生命值心形图标。 */
	static final ResourceLocation ICONS_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/icons.png");
	/** 原版状态条纹理，其中包含三段式首领生命条。 */
	static final ResourceLocation BARS_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/bars.png");
	/** 1.20.1 村民交易界面纹理，其中包含 6x27 的滚动滑块。 */
	static final ResourceLocation VILLAGER_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/container/villager2.png");
	/** 宠物列表条目纹理，纵向包含普通态和选中态。 */
	static final ResourceLocation PET_ENTRY = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/pet_entry.png");
	/** 群体按钮图标。 */
	static final ResourceLocation SQUAD_ICON =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/squad.png");
	/** 群体按钮的悬停高亮边框。 */
	static final ResourceLocation SQUAD_BORDER =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/border.png");
	/** 单项模式按钮图标。 */
	static final ResourceLocation DETAILS_ICON =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/details.png");
	/** 列表升序排列按钮图标。 */
	static final ResourceLocation SORT_ASCENDING_ICON =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/sort_ascending.png");
	/** 列表降序排列按钮图标。 */
	static final ResourceLocation SORT_DESCENDING_ICON =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/sort_descending.png");
	/** 10x10 图标按钮使用的悬停高亮边框。 */
	static final ResourceLocation BORDER_10 =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/border_10.png");
	/** 群体编队槽位纹理。 */
	static final ResourceLocation SQUAD_SLOT =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/slot.png");
	/** 用于已选队伍和不可用空群体槽位的浅色填充。 */
	static final ResourceLocation PLACEHOLDER =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/placeholder.png");
	/** 当列表中选中宠物且队伍未满时显示的空槽位添加图标。 */
	static final ResourceLocation PLUS_SIGN =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/plus_sign.png");
	/** 由原版 bundle 悬浮提示拼装而成的固定 3x3 队伍选择器背景。 */
	static final ResourceLocation TEAM_SELECTOR_BACKGROUND =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/bundle_tooltip_3x3.png");
	/** 群体召唤按钮图标。 */
	static final ResourceLocation SQUAD_SUMMON_ICON =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/release_bottle.png");

	// --- 工具 ---

	/** 从客户端世界中丢弃一个预览实体。 */
	static void discardPreviewEntity(LivingEntity entity) {
		if (entity != null && entity.isAlive()) {
			entity.discard();
		}
	}

	/** 设置 xRot，绕过每个实体的 xRotO 同步。 */
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
