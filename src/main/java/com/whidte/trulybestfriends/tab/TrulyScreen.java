package com.whidte.trulybestfriends.tab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.compat.SableCompat;
import com.whidte.trulybestfriends.network.PetTeamData;
import com.whidte.trulybestfriends.network.PetIOUtil;
import com.whidte.trulybestfriends.network.RequestTeamDataPacket;
import com.whidte.trulybestfriends.network.SetTeamMemberPacket;
import com.whidte.trulybestfriends.network.TeamDataPacket;
import com.whidte.trulybestfriends.trulybestfriends;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Quaternionf;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;
import static com.whidte.trulybestfriends.tab.RenderHelper.*;

public class TrulyScreen extends Screen {

	protected int leftPos, topPos, imageWidth, imageHeight;

	// --- 状态 ---
	List<UUID> petUuids = new ArrayList<>();
	Map<UUID, CompoundTag> petNbtCache = new LinkedHashMap<>();
	private final Map<UUID, LivingEntity> previewEntities = new java.util.HashMap<>();
	Map<UUID, Integer> petPriorities = new LinkedHashMap<>();
	Map<UUID, Long> cooldowns = new java.util.HashMap<>();
	long serverTimeOffsetMs = 0L;
	int selectedPetIndex = -1;
	int scrollOffset = 0;
	float currentScale = 17;
	/** 当前选中实体自动计算出的缩放值；滚轮缩放的
	 * 边界按比例由此值推导，使每个
	 * 实体（微小宠物或巨型龙）都拥有相同的相对缩放范围。 */
	float referenceScale = 17;
	float rotX = DEFAULT_ROT_X;
	float rotY = DEFAULT_ROT_Y;
	boolean isDraggingEntity = false;
	boolean isDraggingScrollbar = false;
	boolean sortNeeded = false;
	/** 列表当前的排序依据。 */
	private ListSortMode sortMode = ListSortMode.PRIORITY;
	/** 列表当前的排序方向：true 为升序（优先级数值小的在前，或注册时间早的在前）。 */
	private boolean sortAscending = true;
	int tickCounter = 0;
	UUID deletePromptUuid;
	HealButton healButton;
	DeleteButton deleteButton;
	ActionButton actionButton;
	SummonToPlayerButton summonToPlayerButton;
	private SpeciesDropdown speciesFilterButton;
	SortModeDropdown sortModeDropdown;
	SortToggleButton sortButton;
	SquadButton squadButton;
	DetailsButton detailsButton;
	SquadSummonButton squadSummonButton;
	TeamSelectorButton teamSelectorButton;
	boolean squadMode = false;
	private int selectedTeamIndex;
	private final Map<String, Map<Integer, UUID>> teamMembers = new java.util.HashMap<>();
	private int teamCapacity = Config.maxPendingSummons;
	private boolean squadDragPending;
	private boolean squadDragConsumed;
	private boolean squadDragging;
	private boolean squadDragFromGrid;
	private UUID squadDragUuid;
	private int squadDragSourceSlot = -1;
	private double squadDragStartX;
	private double squadDragStartY;
	private final HoverDelay emptySlotHoverDelay = new HoverDelay();
	private EditBox searchBox;
	private SearchModeButton searchModeButton;
	private boolean searchMode;
	private String speciesFilter = "";
	private String searchQuery = "";
	String tpDimKey;
	int tpX, tpY, tpZ;
	UUID tpSubLevelId;
	boolean coordsHovered;
	int areaRecallRange = Config.areaRecallDefaultRange;
	Component warningText;
	long warningUntil;
	UUID warningUuid;

	/** 界面打开前收到的全量列表批次。在 init() 时按顺序应用。 */
	private static final List<com.whidte.trulybestfriends.network.SyncPetDataPacket> pendingSyncPackets = new ArrayList<>();

	/** 界面关闭期间收到的最新队伍数据。在下一次 init() 时应用。 */
	private static TeamDataPacket pendingTeamData;

	/** 当前分批全量列表快照之前的缓存状态。 */
	private Map<UUID, CompoundTag> fullListPreviousNbt;
	private Map<UUID, CompoundTag> pendingFullListNbt;
	private Map<UUID, Integer> pendingFullListPriorities;

	/** 调用 saveSelectionThenReload 时被选中的宠物 UUID。
     *  供 applySyncPacket(MODE_FULL_LIST) 用于还原选中项。 */
    private UUID lastRequestedSelection;

    /** 界面首次 init 时要选中的宠物 UUID。由「已被收回」消息里点击名字打开时写入，
     *  在 saveSelectionThenReload 里消费一次后清空，避免影响后续的列表刷新。 */
    private UUID initialSelection;

    public Object tabManager;

	// --- 构造函数 ---
	public TrulyScreen(Component title) {
		this(title, null);
	}

	/**
	 * @param initialSelection 打开后要选中的宠物；为 null 时沿用列表首项。
	 */
	public TrulyScreen(Component title, UUID initialSelection) {
		super(title);
		this.imageWidth = 176;
		this.imageHeight = 166;
		this.initialSelection = initialSelection;
	}

	net.minecraft.client.gui.Font font() {
		return this.font;
	}

	/** 为 L2Tabs TabManager 暴露受保护的 addRenderableWidget。 */
	public <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry> T addWidgetPublic(T widget) {
		return this.addRenderableWidget(widget);
	}

	// === 选中辅助方法 ===

	UUID getSelectedUuid() {
		if (selectedPetIndex < 0 || selectedPetIndex >= petUuids.size()) return null;
		return petUuids.get(selectedPetIndex);
	}

	/** 由 PetWarningPacket（客户端线程）调用，在坐标位置显示限时警告。 */
	public void showWarning(Component msg, UUID uuid) {
		this.warningText = msg;
		this.warningUntil = System.currentTimeMillis() + 3000;
		this.warningUuid = uuid;
	}

	/**
	 * 由 OpenPetScreenPacket（客户端线程）调用，把列表选中项切到指定宠物。
	 *
	 * <p>界面已经开着时用它，比重建整个界面更稳妥（不会把搜索/筛选状态清掉）。
	 * 若该宠物还不在当前列表里（例如首次打开时全量列表快照尚未到达），
	 * 就记下来交给 {@code applySyncPacket} 通过 {@link #lastRequestedSelection} 还原。</p>
	 */
	public void selectPet(UUID uuid) {
		if (uuid == null) return;
		int index = petUuids.indexOf(uuid);
		// 被当前的搜索/物种筛选挡住了：清掉筛选再找一次，
		// 否则玩家点了名字却毫无反应。
		if (index < 0 && isListFiltered()) {
			clearListFilters();
			index = petUuids.indexOf(uuid);
		}
		if (index < 0) {
			initialSelection = uuid;
			lastRequestedSelection = uuid;
			return;
		}
		selectedPetIndex = index;
		scrollOffset = (index / COLUMNS) * COLUMNS;
		snapScrollOffset();
		finishPetListUpdate(false, true);
	}

	/** 列表当前是否被搜索或物种筛选收窄过。 */
	private boolean isListFiltered() {
		return searchMode || !speciesFilter.isEmpty() || !searchQuery.isEmpty();
	}

	/** 清空搜索词与物种筛选（不改搜索/筛选模式本身）。 */
	private void clearListFilters() {
		speciesFilter = "";
		searchQuery = "";
		if (searchBox != null) searchBox.setValue("");
		applyPetFilters();
		// 物种筛选按钮的文字由它自己维护，程序化改值后要重建一次才不会显示旧值。
		if (!searchMode) refreshSpeciesFilterButton();
	}

	CompoundTag getSelectedNbt() {
		UUID uuid = getSelectedUuid();
		return uuid != null ? petNbtCache.get(uuid) : null;
	}

	boolean isTrackedPet(UUID uuid) {
		return uuid != null && petNbtCache.containsKey(uuid);
	}

	boolean canSwapToSelectedPet() {
		UUID targetUuid = getSelectedUuid();
		CompoundTag nbt = getSelectedNbt();
		var player = getMinecraft().player;
		if (targetUuid == null || nbt == null || player == null
				|| !nbt.getBoolean("Rideable") || isPetOnShoulder(targetUuid)
				|| !(player.getVehicle() instanceof LivingEntity mount)) return false;
		return !targetUuid.equals(mount.getUUID()) && isTrackedPet(mount.getUUID());
	}

	boolean hasSelection() {
		return getSelectedUuid() != null;
	}

	boolean isSelectedPetDead() {
		CompoundTag nbt = getSelectedNbt();
		return nbt != null && nbt.contains("Health") && nbt.getFloat("Health") <= 0;
	}

	boolean isSelectedPetRecalled() {
		CompoundTag nbt = getSelectedNbt();
		return nbt != null && nbt.getBoolean("Recalled");
	}

	boolean isButtonCooldownActive(long lastClickTick) {
		return minecraft.level == null
				|| minecraft.level.getGameTime() - lastClickTick < BUTTON_COOLDOWN_TICKS;
	}

	long currentGameTick() {
		return minecraft.level != null ? minecraft.level.getGameTime() : 0L;
	}

	boolean isSelectedPetLost() {
		CompoundTag nbt = getSelectedNbt();
		return nbt != null && (nbt.getBoolean("Lost") || !nbt.contains("Pos") || !nbt.contains("Dimension"));
	}

	/** 数据损坏（无 Pos 或 Dimension）的宠物：单击直接删除，无需两步确认。
	 *  与 isSelectedPetLost() 的区别：仅检查数据完整性，不包含 Lost 标志。
	 *  NBT 的 Lost=true 仅表示实体当前未加载（可能在卸载区块中），数据本身可能完好，
	 *  不应跳过确认。 */
	boolean isSelectedPetDataCorrupted() {
		CompoundTag nbt = getSelectedNbt();
		return nbt != null && (!nbt.contains("Pos") || !nbt.contains("Dimension"));
	}

	LivingEntity getPreviewEntity(UUID uuid) {
		LivingEntity cached = previewEntities.get(uuid);
		if (cached != null) return cached;

		CompoundTag nbt = petNbtCache.get(uuid);
		if (nbt == null || getMinecraft().level == null) return null;
		EntityType<?> type = getEntityType(nbt.getString("EntityType"));
		if (type == null) return null;
		Entity entity = type.create(getMinecraft().level);
		if (!(entity instanceof LivingEntity livingEntity)) return null;
		try {
			livingEntity.load(nbt);
		} catch (Exception e) {
			livingEntity.discard();
			return null;
		}
		previewEntities.put(uuid, livingEntity);
		return livingEntity;
	}

	private void invalidatePreviewEntity(UUID uuid) {
		LivingEntity entity = previewEntities.remove(uuid);
		if (entity != null) discardPreviewEntity(entity);
	}

	private void clearPreviewEntities() {
		for (LivingEntity entity : previewEntities.values()) {
			discardPreviewEntity(entity);
		}
		previewEntities.clear();
	}

	// === 生命周期 ===

	@Override
	public void init() {
		super.init();
		speciesFilterButton = null;
		searchBox = null;
		searchModeButton = null;
		this.leftPos = (this.width - this.imageWidth) / 2;
		this.topPos = (this.height - this.imageHeight) / 2;

		// L2Tabs 标签栏集成
		if (net.neoforged.fml.ModList.get().isLoaded("l2tabs")) {
			try {
				Class.forName("com.whidte.trulybestfriends.tab.L2TabsIntegration")
					.getMethod("createTabManager", TrulyScreen.class)
					.invoke(null, this);
			} catch (Exception e) {
				trulybestfriends.LOGGER.warn("L2Tabs tab bar init failed: {}", e.toString());
			}
		}

		saveSelectionThenReload();
		addButtons();
		addListControls();
	}

	@Override
	public void removed() {
		// 清理 PetEntry 渲染遗留的预览实体
		super.removed();
	}

	private void saveSelectionThenReload() {
		UUID selectedUuid = getSelectedUuid();
		// 首次 init 时还没有选中项：若本次是「点击已被收回的宠物名」打开的，
		// 就用那一只作为初始选中项。只消费一次，后续刷新不受影响。
		if (selectedUuid == null && initialSelection != null) selectedUuid = initialSelection;
		initialSelection = null;
		lastRequestedSelection = selectedUuid;

		if (pendingTeamData != null) {
			applyTeamData(pendingTeamData);
			pendingTeamData = null;
		}

		petUuids.clear();
		petNbtCache.clear();
		clearPreviewEntities();
		petPriorities.clear();
		selectedPetIndex = -1;
		scrollOffset = 0;

		// 1. 应用任何已缓存的全量列表快照（界面关闭期间收到）。
		if (!pendingSyncPackets.isEmpty()) {
			List<com.whidte.trulybestfriends.network.SyncPetDataPacket> packets = new ArrayList<>(pendingSyncPackets);
			pendingSyncPackets.clear();
			for (com.whidte.trulybestfriends.network.SyncPetDataPacket packet : packets) {
				applySyncPacket(packet);
			}
		}

		// 2. 单人模式：同时从磁盘加载以即时反馈（服务端是
		//    同一进程，无需网络往返）。
		Minecraft mc = getMinecraft();
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null && petNbtCache.isEmpty()) {
			PetDataLoader.loadAll(mc, petNbtCache, petPriorities);
		}

		rebuildFilteredPetUuids(selectedUuid, true);
		rebuildPetWidgets();

		// 3. 始终向服务端请求一份全新的全量列表快照（单人
		//    与多人模式都适用）。回复会通过
		//    applySyncPacket() 更新缓存。在单人模式下这会用
		//    权威的服务端版本覆盖磁盘加载的数据。
		if (mc.player != null && mc.getConnection() != null) {
			PacketDistributor.sendToServer(
					com.whidte.trulybestfriends.network.RequestPetDataPacket.requestFullList());
			PacketDistributor.sendToServer(new RequestTeamDataPacket());
		}

		if (hasSelection()) {
			adjustScaleForCurrentPet();
		}
	}

	private void addButtons() {
		healButton = this.addRenderableWidget(
				new HealButton(this.leftPos + HEAL_X, this.topPos + HEAL_Y, this));
		deleteButton = this.addRenderableWidget(
				new DeleteButton(this.leftPos + DELETE_X, this.topPos + DELETE_Y, this));
		actionButton = this.addRenderableWidget(
				new ActionButton(this.leftPos + ACTION_X, this.topPos + ACTION_Y, this));
		summonToPlayerButton = this.addRenderableWidget(new SummonToPlayerButton(
				this.leftPos + SUMMON_TO_PLAYER_X, this.topPos + SUMMON_TO_PLAYER_Y, SUMMON_TO_PLAYER_W, this));
		squadButton = this.addRenderableWidget(new SquadButton(
				this.leftPos + SQUAD_X, this.topPos + SQUAD_Y, this));
		detailsButton = this.addRenderableWidget(new DetailsButton(
				this.leftPos + SQUAD_X, this.topPos + SQUAD_Y, this));
		squadSummonButton = this.addRenderableWidget(new SquadSummonButton(
				this.leftPos + SQUAD_SUMMON_X, this.topPos + SQUAD_SUMMON_Y, this));
		teamSelectorButton = this.addRenderableWidget(new TeamSelectorButton(
				this.leftPos + TEAM_SELECTOR_X, this.topPos + TEAM_SELECTOR_Y, this));
		updateButtonVisibility();
	}

	private void addListControls() {
		searchBox = new EditBox(
				font(),
				this.leftPos + LIST_MODE_CONTROL_OFFSET_X,
				this.topPos + LIST_CONTROLS_OFFSET_Y,
				LIST_MODE_CONTROL_WIDTH,
				LIST_CONTROL_HEIGHT,
				Component.translatable("trulybestfriends.search.name"));
		searchBox.setHint(Component.translatable("trulybestfriends.search.hint"));
		searchBox.setMaxLength(64);
		searchBox.setValue(searchQuery);
		searchBox.setResponder(value -> {
			searchQuery = value;
			applyPetFilters();
		});
		searchBox.setVisible(searchMode);
		this.addRenderableWidget(searchBox);
		refreshSpeciesFilterButton();

		searchModeButton = new SearchModeButton(
				this.leftPos + SEARCH_TOGGLE_OFFSET_X,
				this.topPos + LIST_CONTROLS_OFFSET_Y,
				this);
		this.addRenderableWidget(searchModeButton);

		sortModeDropdown = new SortModeDropdown(
				this.leftPos + SORT_MODE_SELECTOR_X,
				this.topPos + LIST_CONTROLS_OFFSET_Y,
				SORT_MODE_SELECTOR_WIDTH,
				LIST_CONTROL_HEIGHT,
				this,
				sortMode,
				this::setSortMode);
		this.addRenderableWidget(sortModeDropdown);

		sortButton = new SortToggleButton(
				this.leftPos + SORT_BUTTON_X, this.topPos + SORT_BUTTON_Y, this);
		this.addRenderableWidget(sortButton);
	}

	private void refreshSpeciesFilterButton() {
		if (searchBox == null) return;
		if (speciesFilterButton != null) removeWidget(speciesFilterButton);

		List<String> species = getAvailableSpeciesFilters();
		speciesFilterButton = new SpeciesDropdown(
				this.leftPos + LIST_MODE_CONTROL_OFFSET_X,
				this.topPos + LIST_CONTROLS_OFFSET_Y,
				LIST_MODE_CONTROL_WIDTH,
				LIST_CONTROL_HEIGHT,
				this,
				species,
				speciesFilter,
				this::getSpeciesFilterLabel,
				value -> {
					speciesFilter = value;
					applyPetFilters();
				});
		speciesFilterButton.visible = !searchMode;
		this.addRenderableWidget(speciesFilterButton);
	}

	void toggleListControlMode() {
		searchMode = !searchMode;
		if (searchBox != null) {
			searchBox.setVisible(searchMode);
			if (!searchMode) searchBox.setFocused(false);
		}
		if (speciesFilterButton != null) {
			speciesFilterButton.visible = !searchMode;
			if (searchMode) speciesFilterButton.collapse();
		}
		applyPetFilters();
	}

	Component listControlToggleLabel() {
		return Component.translatable(searchMode
				? "trulybestfriends.filter.toggle"
				: "trulybestfriends.search.toggle");
	}

	private List<String> getAvailableSpeciesFilters() {
		List<String> result = new ArrayList<>();
		result.add("");
		for (CompoundTag nbt : petNbtCache.values()) {
			String key = nbt.getString("EntityType");
			if (!key.isEmpty() && !result.contains(key)) result.add(key);
		}
		if (!speciesFilter.isEmpty() && !result.contains(speciesFilter)) result.add(speciesFilter);
		result.subList(1, result.size()).sort(Comparator.comparing(
				key -> getSpeciesFilterLabel(key).getString(), String.CASE_INSENSITIVE_ORDER));
		return result;
	}

	private Component getSpeciesFilterLabel(String key) {
		if (key == null || key.isEmpty()) {
			return Component.translatable("trulybestfriends.filter.all");
		}
		EntityType<?> type = getEntityType(key);
		return type != null ? type.getDescription() : Component.literal(key);
	}

	private static EntityType<?> getEntityType(String key) {
		ResourceLocation resource = ResourceLocation.tryParse(key);
		return resource != null ? BuiltInRegistries.ENTITY_TYPE.get(resource) : null;
	}

	private void normalizeSpeciesFilter() {
		if (speciesFilter.isEmpty()) return;
		boolean available = petNbtCache.values().stream()
				.anyMatch(nbt -> speciesFilter.equals(nbt.getString("EntityType")));
		if (!available) speciesFilter = "";
	}

	private void applyPetFilters() {
		UUID previousSelection = getSelectedUuid();
		rebuildFilteredPetUuids(previousSelection, true);
		if (!java.util.Objects.equals(previousSelection, getSelectedUuid())) deletePromptUuid = null;
		finishPetListUpdate(false, true);
	}

	private void finishPetListUpdate(boolean refreshSpecies, boolean adjustScale) {
		rebuildPetWidgets();
		if (refreshSpecies) refreshSpeciesFilterButton();
		updateButtonVisibility();
		if (adjustScale && hasSelection()) adjustScaleForCurrentPet();
	}

	private void rebuildFilteredPetUuids(UUID preferredSelection, boolean revealSelection) {
		int previousScrollOffset = scrollOffset;
		int previousSelectedIndex = selectedPetIndex;
		petUuids.clear();
		String activeSpeciesFilter = searchMode ? "" : speciesFilter;
		String activeSearchQuery = searchMode ? searchQuery : "";
		for (Map.Entry<UUID, CompoundTag> entry : petNbtCache.entrySet()) {
			String displayName = getPetDisplayName(entry.getKey()).getString();
			if (matchesPetFilter(entry.getValue(), activeSpeciesFilter, activeSearchQuery, displayName)) {
				petUuids.add(entry.getKey());
			}
		}
		sortPetUuids();

		selectedPetIndex = preferredSelection != null ? petUuids.indexOf(preferredSelection) : -1;
		if (selectedPetIndex < 0) {
			selectedPetIndex = petUuids.isEmpty() ? -1 : 0;
			scrollOffset = 0;
		} else if (revealSelection) {
			scrollOffset = (selectedPetIndex / COLUMNS) * COLUMNS;
			snapScrollOffset();
		} else {
			scrollOffset = previousScrollOffset;
			snapScrollOffset();
			if (selectedPetIndex != previousSelectedIndex
					&& (selectedPetIndex < scrollOffset || selectedPetIndex >= scrollOffset + MAX_VISIBLE)) {
				scrollOffset = (selectedPetIndex / COLUMNS) * COLUMNS;
				snapScrollOffset();
			}
		}
	}

	static boolean matchesPetFilter(CompoundTag nbt, String speciesFilter,
	                                String searchQuery, String displayName) {
		if (nbt == null) return false;
		if (speciesFilter != null && !speciesFilter.isEmpty()
				&& !speciesFilter.equals(nbt.getString("EntityType"))) return false;
		String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
		return query.isEmpty() || (displayName != null
				&& displayName.toLowerCase(Locale.ROOT).contains(query));
	}

	// === 实时刷新 ===

	@Override
	public void tick() {
		super.tick();
		tickCounter++;
		if (tickCounter % REFRESH_INTERVAL == 0) {
			refreshSelectedFromDisk();
			cleanExpiredCooldowns();
		}
	}

	private void refreshSelectedFromDisk() {
		// 已由服务端驱动的同步取代：向服务端请求所选宠物的
		// 最新 NBT。服务端以 SyncPetDataPacket（更新/删除）回复，
		// 在 applySyncPacket() 中处理。无客户端磁盘 I/O。
		UUID selUuid = getSelectedUuid();
		if (selUuid == null) return;
		Minecraft mc = getMinecraft();
		if (mc.player == null || mc.getConnection() == null) return;
		PacketDistributor.sendToServer(
				com.whidte.trulybestfriends.network.RequestPetDataPacket.requestSelected(selUuid));
	}

	/** 应用服务端推送的同步数据包。当此界面打开时由 SyncPetDataPacket.handle
	 *  调用。 */
	public void applySyncPacket(com.whidte.trulybestfriends.network.SyncPetDataPacket packet) {
		serverTimeOffsetMs = packet.getServerTime() - System.currentTimeMillis();
		switch (packet.getMode()) {
			case com.whidte.trulybestfriends.network.SyncPetDataPacket.MODE_FULL_LIST -> {
				if (packet.isFirstBatch() || pendingFullListNbt == null) {
					fullListPreviousNbt = new LinkedHashMap<>(petNbtCache);
					pendingFullListNbt = new LinkedHashMap<>();
					pendingFullListPriorities = new LinkedHashMap<>();
				}
				for (Tag raw : packet.getFullList()) {
					if (raw instanceof CompoundTag entry && entry.hasUUID("UUID")) {
						UUID uuid = entry.getUUID("UUID");
						CompoundTag nbt = entry.getCompound("NBT");
						pendingFullListNbt.put(uuid, nbt);
						pendingFullListPriorities.put(uuid, PetIOUtil.priorityFrom(nbt));
					}
				}
				if (!packet.isLastBatch()) break;

				Map<UUID, CompoundTag> previousNbt = fullListPreviousNbt;
				for (UUID uuid : new ArrayList<>(previewEntities.keySet())) {
					if (!pendingFullListNbt.containsKey(uuid)
							|| !java.util.Objects.equals(pendingFullListNbt.get(uuid), previousNbt.get(uuid))) {
						invalidatePreviewEntity(uuid);
					}
				}
				UUID prevSelected = getSelectedUuid();
				if (prevSelected == null) prevSelected = lastRequestedSelection;
				petNbtCache.clear();
				petNbtCache.putAll(pendingFullListNbt);
				petPriorities.clear();
				petPriorities.putAll(pendingFullListPriorities);
				fullListPreviousNbt = null;
				pendingFullListNbt = null;
				pendingFullListPriorities = null;
				normalizeSpeciesFilter();
				rebuildFilteredPetUuids(prevSelected, true);
				finishPetListUpdate(true, true);
			}
			case com.whidte.trulybestfriends.network.SyncPetDataPacket.MODE_UPDATE -> {
				UUID uuid = packet.getPetUuid();
				UUID previousSelection = getSelectedUuid();
				CompoundTag nbt = packet.getPetNbt();
				CompoundTag oldNbt = petNbtCache.get(uuid);
				String oldSpecies = oldNbt != null ? oldNbt.getString("EntityType") : "";
				CompoundTag merged = oldNbt != null ? oldNbt.copy() : new CompoundTag();
				for (String key : nbt.getAllKeys()) {
					merged.put(key, nbt.get(key));
				}
				boolean previewChanged = !merged.equals(oldNbt);
				if (previewChanged) {
					invalidatePreviewEntity(uuid);
				}
				petNbtCache.put(uuid, merged);
				petPriorities.put(uuid, PetIOUtil.priorityFrom(merged));
				rebuildFilteredPetUuids(previousSelection, false);
				finishPetListUpdate(
						!oldSpecies.equals(merged.getString("EntityType")),
						!java.util.Objects.equals(previousSelection, getSelectedUuid()));
				if (previewChanged && uuid.equals(getSelectedUuid())) {
					refreshScaleForCurrentPetPreservingZoom();
				}
			}
			case com.whidte.trulybestfriends.network.SyncPetDataPacket.MODE_DELETE -> {
				UUID uuid = packet.getPetUuid();
				UUID previousSelection = getSelectedUuid();
				boolean deletedSelectedPet = uuid.equals(previousSelection);
				int deletedIndex = petUuids.indexOf(uuid);
				petNbtCache.remove(uuid);
				invalidatePreviewEntity(uuid);
				petPriorities.remove(uuid);
				cooldowns.remove(uuid);
				if (uuid.equals(warningUuid)) {
					warningUuid = null;
					warningText = null;
					warningUntil = 0L;
				}
				if (uuid.equals(deletePromptUuid)) deletePromptUuid = null;
				normalizeSpeciesFilter();
				rebuildFilteredPetUuids(deletedSelectedPet ? null : previousSelection, false);
				if (deletedSelectedPet) {
					selectedPetIndex = petUuids.isEmpty() ? -1 : Math.min(Math.max(deletedIndex, 0), petUuids.size() - 1);
					scrollOffset = selectedPetIndex < 0 ? 0 : (selectedPetIndex / COLUMNS) * COLUMNS;
					snapScrollOffset();
					if (hasSelection()) adjustScaleForCurrentPet();
				}
				finishPetListUpdate(true, false);
			}
		}
	}

	/** 缓存界面未打开期间收到的同步数据包。
	 *  在下一次 init() 时应用。 */
	public static void cacheSyncPacket(com.whidte.trulybestfriends.network.SyncPetDataPacket packet) {
		// 只有全量列表快照值得为下一次界面打开而缓存；
		// 界面关闭期间的更新/删除已过期，可以丢弃。
		if (packet.getMode() == com.whidte.trulybestfriends.network.SyncPetDataPacket.MODE_FULL_LIST) {
			if (packet.isFirstBatch()) pendingSyncPackets.clear();
			else if (pendingSyncPackets.isEmpty()) return;
			pendingSyncPackets.add(packet);
		}
	}

	long currentServerTimeMillis() {
		return System.currentTimeMillis() + serverTimeOffsetMs;
	}

	void updateButtonVisibility() {
		boolean has = hasSelection() && !squadMode;
		if (healButton != null) healButton.visible = has;
		if (deleteButton != null) deleteButton.visible = has;
		if (actionButton != null) actionButton.visible = has;
		if (summonToPlayerButton != null) summonToPlayerButton.visible = has;
		if (squadButton != null) squadButton.visible = !squadMode;
		if (detailsButton != null) detailsButton.visible = squadMode;
		if (squadSummonButton != null) squadSummonButton.visible = squadMode;
		if (teamSelectorButton != null) teamSelectorButton.visible = squadMode;
	}

	void enterSquadMode() {
		squadMode = true;
		isDraggingEntity = false;
		updateButtonVisibility();
	}

	void exitSquadMode() {
		squadMode = false;
		if (teamSelectorButton != null) teamSelectorButton.collapse();
		updateButtonVisibility();
	}

	int selectedTeamIndex() {
		return selectedTeamIndex;
	}

	void selectTeam(int teamIndex) {
		selectedTeamIndex = Mth.clamp(teamIndex, 0, 7);
		if (getMinecraft().player != null && getMinecraft().getConnection() != null) {
			PacketDistributor.sendToServer(SetTeamMemberPacket.select(selectedTeamIndex));
		}
	}

	// === 编队队伍数据 ===

	/** 用权威的服务端快照替换缓存的编队队伍数据。 */
	public void applyTeamData(TeamDataPacket packet) {
		CompoundTag data = packet.teamData();
		if (data.contains("Capacity")) {
			teamCapacity = Math.max(1, data.getInt("Capacity"));
		}
		if (data.contains("SelectedTeam", Tag.TAG_STRING)) {
			int index = PetTeamData.TEAM_COLORS.indexOf(data.getString("SelectedTeam"));
			if (index >= 0) selectedTeamIndex = index;
		}
		Map<String, Map<Integer, UUID>> rebuilt = new java.util.HashMap<>();
		CompoundTag teams = data.contains("Teams", Tag.TAG_COMPOUND)
				? data.getCompound("Teams") : new CompoundTag();
		for (String color : PetTeamData.TEAM_COLORS) {
			ListTag members = teams.getCompound(color).getList("Members", Tag.TAG_COMPOUND);
			Map<Integer, UUID> slots = new java.util.HashMap<>();
			for (Tag tag : members) {
				CompoundTag member = (CompoundTag) tag;
				if (member.hasUUID("UUID")) {
					slots.put(member.getInt("Slot"), member.getUUID("UUID"));
				}
			}
			rebuilt.put(color, slots);
		}
		teamMembers.clear();
		teamMembers.putAll(rebuilt);
	}

	/** 缓存界面关闭期间收到的队伍数据。 */
	public static void cacheTeamData(TeamDataPacket packet) {
		pendingTeamData = packet;
	}

	private String selectedTeamColor() {
		return PetTeamData.TEAM_COLORS.get(selectedTeamIndex);
	}

	Map<Integer, UUID> selectedTeamSlots() {
		return teamMembers.get(selectedTeamColor());
	}

	private UUID squadMemberAtSlot(int slot) {
		Map<Integer, UUID> slots = selectedTeamSlots();
		return slots != null ? slots.get(slot) : null;
	}

	/** 返回鼠标下方的编队槽位，若没有则返回 -1。 */
	private int squadSlotAt(double mouseX, double mouseY) {
		int gridX = this.leftPos + SQUAD_GRID_X;
		int gridY = this.topPos + SQUAD_GRID_Y;
		for (int cell = 0; cell < SQUAD_CELL_SLOTS.length; cell++) {
			if (SQUAD_CELL_SLOTS[cell] < 0) continue;
			int column = cell % 3;
			int row = cell / 3;
			int x = gridX + column * SQUAD_GRID_STEP;
			int y = gridY + row * SQUAD_GRID_STEP;
			if (mouseX >= x && mouseX < x + SQUAD_GRID_SLOT_SIZE
					&& mouseY >= y && mouseY < y + SQUAD_GRID_SLOT_SIZE) {
				return SQUAD_CELL_SLOTS[cell];
			}
		}
		return -1;
	}

	private PetEntry squadPetEntryAt(double mouseX, double mouseY) {
		for (GuiEventListener child : this.children()) {
			if (child instanceof PetEntry entry && entry.isMouseOver(mouseX, mouseY)) return entry;
		}
		return null;
	}

	private void assignSquadMemberLocally(String color, int slot, UUID uuid) {
		Map<Integer, UUID> slots = teamMembers.computeIfAbsent(color, k -> new java.util.HashMap<>());
		slots.entrySet().removeIf(entry -> uuid.equals(entry.getValue()) && entry.getKey() != slot);
		slots.put(slot, uuid);
	}

	private void moveSquadMemberLocally(String color, int fromSlot, int toSlot) {
		Map<Integer, UUID> slots = teamMembers.get(color);
		if (slots == null) return;
		UUID from = slots.get(fromSlot);
		if (from == null) return;
		UUID to = slots.get(toSlot);
		if (to == null) slots.remove(fromSlot);
		else slots.put(fromSlot, to);
		slots.put(toSlot, from);
	}

	private void removeSquadMemberLocally(String color, UUID uuid) {
		Map<Integer, UUID> slots = teamMembers.get(color);
		if (slots == null) return;
		slots.entrySet().removeIf(entry -> uuid.equals(entry.getValue()));
	}

	private void resolveSquadDrop(double mouseX, double mouseY) {
		int slot = squadSlotAt(mouseX, mouseY);
		String color = selectedTeamColor();
		if (slot < 0) {
			if (squadDragFromGrid && squadDragSourceSlot >= 0) {
				removeSquadMemberLocally(color, squadDragUuid);
				PacketDistributor.sendToServer(
						SetTeamMemberPacket.remove(selectedTeamIndex, squadDragUuid));
			}
		} else if (squadDragFromGrid) {
			if (squadDragSourceSlot >= 0 && squadDragSourceSlot != slot) {
				moveSquadMemberLocally(color, squadDragSourceSlot, slot);
				PacketDistributor.sendToServer(
						SetTeamMemberPacket.move(selectedTeamIndex, squadDragSourceSlot, slot));
			}
		} else if (!squadDragUuid.equals(squadMemberAtSlot(slot))) {
			Map<Integer, UUID> slots = selectedTeamSlots();
			int count = slots != null ? slots.size() : 0;
			boolean member = slots != null && slots.containsValue(squadDragUuid);
			boolean occupied = squadMemberAtSlot(slot) != null;
			if (count < teamCapacity || member || occupied) {
				assignSquadMemberLocally(color, slot, squadDragUuid);
			}
			PacketDistributor.sendToServer(
					SetTeamMemberPacket.assign(selectedTeamIndex, slot, squadDragUuid));
		}
		squadDragging = false;
		squadDragFromGrid = false;
		squadDragUuid = null;
		squadDragSourceSlot = -1;
	}

	private void cleanExpiredCooldowns() {
		// 冷却时间不会超过 recallCooldownMs；保留 2 倍作为时钟偏差的安全余量。
		long cutoff = System.currentTimeMillis() - (Config.recallCooldownMs * 2L);
		cooldowns.values().removeIf(t -> t < cutoff);
	}

	// === 缩放 ===

	void adjustScaleForCurrentPet() {
		UUID uuid = getSelectedUuid();
		if (uuid == null) return;
		LivingEntity entity = getPreviewEntity(uuid);
		if (entity == null) return;
		// 存储原始自动计算的缩放值，作为滚轮缩放的参考
		// 边界。currentScale 被重置为该值，这样切换宠物时会丢弃
		// 上一只宠物的手动缩放。
		this.referenceScale = computePreviewScale(entity, BASE_SCALE);
		this.currentScale = this.referenceScale;
	}

	private void refreshScaleForCurrentPetPreservingZoom() {
		UUID uuid = getSelectedUuid();
		if (uuid == null) return;
		LivingEntity entity = getPreviewEntity(uuid);
		if (entity == null) return;

		float newReferenceScale = computePreviewScale(entity, BASE_SCALE);
		float zoomRatio = referenceScale > 0.0f && Float.isFinite(currentScale)
				? currentScale / referenceScale
				: 1.0f;
		this.referenceScale = newReferenceScale;
		this.currentScale = Mth.clamp(
				newReferenceScale * zoomRatio,
				newReferenceScale * 0.5f,
				newReferenceScale * 2.0f);
	}

	/**
	 * 计算
	 * {@link InventoryScreen#renderEntityInInventory} 的缩放参数，采用
	 * Ice &amp; Fire 的 {@code GuiDragon} 所使用的做法。
	 *
	 * <p>Forge 的 {@link Entity#getScale()} 反映实体的可视缩放
	 * 属性（例如 IaF 龙在更高阶段返回较大的值，
	 * 末影龙返回约 1.0 但其模型本身就已超大）。将
	 * 基础尺寸除以该缩放值得到的参数能让任何实体
	 * 保持在预览区域内，包括那些碰撞箱本身
	 * 远小于实际模型的多部件实体。</p>
	 *
	 * <p>对于普通实体（{@code getScale() == 1.0}），我们使用其站立
	 * 尺寸结合经典的碰撞箱启发式。使用当前
	 * 碰撞箱会让睡觉或蹲伏的宠物在
	 * 预览中显得大很多，因为这些姿势会暂时降低实体高度。</p>
	 *
	 * <p>此处不应用固定的数值钳制 —— 调用方将结果
	 * 存储为 {@code referenceScale}，由滚轮缩放处理器
	 * 围绕它按比例进行钳制。</p>
	 *
	 * @param entity  预览实体（不能为 null）
	 * @param baseSize  用于普通实体的参考尺寸
	 * @return 原始自动计算的缩放值（未钳制）
	 */
	static float computePreviewScale(LivingEntity entity, float baseSize) {
		float scale = entity.getScale();
		boolean multipart = entity.getParts() != null && entity.getParts().length > 0;
		if (scale > 1.0001f) {
			// 已缩放实体（IaF 龙……）：使用 IaF 公式。
			return baseSize / scale;
		}
		// 普通实体：使用稳定的站立尺寸，而非
		// 依赖姿势的当前碰撞箱。
		var standingDimensions = entity.getDimensions(Pose.STANDING);
		float maxDim = Math.max(standingDimensions.width(), standingDimensions.height());
		if (maxDim <= 0) return baseSize;
		float computed = baseSize * (HORSE_MAX_DIM / maxDim);
		// getScale() == 1 的多部件实体（例如末影龙）
		// 其碰撞箱非常大，会让该启发式算出一个
		// 极小的值。应用一个下限，使模型保持可见。
		if (multipart) {
			return Math.max(computed, baseSize * 0.25f);
		}
		return computed;
	}

	// === 滚动 / 排序 ===

	private int getMaxScrollOffset() {
		return Math.max(0, (petUuids.size() - 1) / COLUMNS - (MAX_VISIBLE / COLUMNS - 1)) * COLUMNS;
	}

	private void snapScrollOffset() {
		scrollOffset = (scrollOffset / COLUMNS) * COLUMNS;
		scrollOffset = Mth.clamp(scrollOffset, 0, getMaxScrollOffset());
	}

	private void rebuildPetWidgets() {
		var toRemove = new ArrayList<GuiEventListener>();
		for (var child : this.children()) {
			if (child instanceof PetEntry) toRemove.add(child);
		}
		toRemove.forEach(this::removeWidget);

		int listX = this.leftPos + LIST_PANEL_OFFSET_X;
		int listY = this.topPos + LIST_PANEL_OFFSET_Y;
		int endIdx = Math.min(scrollOffset + MAX_VISIBLE, petUuids.size());

		for (int i = scrollOffset; i < endIdx; i++) {
			int pos = i - scrollOffset;
			int col = pos % COLUMNS;
			int row = pos / COLUMNS;
			UUID uuid = petUuids.get(i);
			Component name = getPetDisplayName(uuid);
			this.addRenderableWidget(new PetEntry(
					listX + col * ENTRY_COLUMN_STEP, listY + row * ENTRY_ROW_STEP,
					ENTRY_WIDTH, ENTRY_HEIGHT, name, i, this));
		}
	}

	private void sortPetUuids() {
		petUuids.sort(petSortComparator(sortMode, sortAscending,
				uuid -> PetIOUtil.clampPriority(
						petPriorities.getOrDefault(uuid, PetIOUtil.DEFAULT_PRIORITY)),
				petNbtCache::get));
	}

	/**
	 * 按给定排序依据与方向构造宠物排序器。
	 *
	 * <p>抽成静态纯函数以便脱离界面状态直接测试；两个取值函数分别提供
	 * 宠物的优先级与快照标签，这样比较器本身不依赖任何界面字段。</p>
	 */
	static <T> Comparator<T> petSortComparator(ListSortMode mode, boolean ascending,
	                                          ToIntFunction<T> priorityOf,
	                                          Function<T, CompoundTag> nbtOf) {
		Comparator<T> byPriority = Comparator.comparingInt(priorityOf);
		Comparator<T> comparator = switch (mode) {
			case PRIORITY -> byPriority;
			// 缺失注册时间戳的旧宠物读作 0，统一排到“最早”的一端；
			// 时间戳相同时退化为按优先级排列，避免呈现出无意义的哈希顺序。
			case REGISTERED_AT -> Comparator
					.comparingLong((T pet) -> PetIOUtil.registeredAtFrom(nbtOf.apply(pet)))
					.thenComparing(byPriority);
			// 生命值比例越低越“需要治疗”，升序时排在最前；
			// 比例相同时同样退化为按优先级排列（例如一群满血宠物）。
			case HEALTH -> Comparator
					.comparingDouble((T pet) -> (double) PetIOUtil.healthRatioFrom(nbtOf.apply(pet)))
					.thenComparing(byPriority);
		};
		// reversed() 只反转方向：比较结果相同时仍为 0，稳定排序会保留它们原有的相对顺序。
		return ascending ? comparator : comparator.reversed();
	}

	/** 当前是否按升序排列列表。 */
	boolean isSortAscending() {
		return sortAscending;
	}

	/** 在升序与降序之间切换。 */
	void toggleSortDirection() {
		sortAscending = !sortAscending;
		resortPetList();
	}

	/** 切换排序依据并立即重排列表。 */
	void setSortMode(ListSortMode mode) {
		if (mode == null || mode == sortMode) return;
		sortMode = mode;
		resortPetList();
	}

	/** 按当前排序设置重新过滤并重排列表，按 UUID 保留当前选中项，然后回到列表顶部。 */
	private void resortPetList() {
		applyPetFilters();
		scrollOffset = 0;
		rebuildPetWidgets();
	}

	void onShiftReleased() {
		sortPetUuids();
		scrollOffset = 0;
		rebuildPetWidgets();
	}

	Component getPetDisplayName(UUID uuid) {
		CompoundTag nbt = petNbtCache.get(uuid);
		if (nbt == null) return Component.literal("???");
		return PetDataLoader.displayName(minecraft, nbt);
	}

	// ============================
	//        渲染
	// ============================

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// 先绘制面板，再在其上叠加自定义控件与覆盖层。
		this.renderBackground(g, mouseX, mouseY, partialTick);
		g.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
		if (!squadMode) {
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			g.blit(PET_PREVIEW_BACKGROUND,
					this.leftPos + PET_PREVIEW_BACKGROUND_X,
					this.topPos + PET_PREVIEW_BACKGROUND_Y,
					0, 0,
					PET_PREVIEW_BACKGROUND_SIZE, PET_PREVIEW_BACKGROUND_SIZE,
					PET_PREVIEW_BACKGROUND_SIZE, PET_PREVIEW_BACKGROUND_SIZE);
		} else {
			renderSquadGrid(g, mouseX, mouseY);
		}
		PetEntry selectedEntry = null;
		List<net.minecraft.client.gui.components.Renderable> dropdowns = new ArrayList<>();
		for (GuiEventListener listener : this.children()) {
			if (listener instanceof SpeciesDropdown || listener instanceof SortModeDropdown) {
				dropdowns.add((net.minecraft.client.gui.components.Renderable) listener);
			} else if (listener instanceof PetEntry entry && entry.isSelected()) {
				selectedEntry = entry;
			} else if (listener instanceof DeleteButton) {
				// 在下方实体预览之上渲染，这样大型模型不会盖住 X。
			} else if (listener instanceof net.minecraft.client.gui.components.Renderable renderable) {
				renderable.render(g, mouseX, mouseY, partialTick);
			}
		}
		if (selectedEntry != null) selectedEntry.render(g, mouseX, mouseY, partialTick);

		if (sortNeeded && !net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
			sortNeeded = false;
			onShiftReleased();
		}

		renderScrollBar(g);

		if (hasSelection() && !squadMode) {
			renderPetPreview(g);
			renderAtDepth(g, PET_INFO_OVERLAY_Z, () -> {
				renderHealthBar(g);
				renderPetInfo(g);
				renderPetLocation(g, mouseX, mouseY);
			});
		}
		if (deleteButton != null && deleteButton.visible) {
			renderAtDepth(g, PET_INFO_OVERLAY_Z + 1,
					() -> deleteButton.render(g, mouseX, mouseY, partialTick));
		}

		// 保持展开的下拉框位于宠物条目和宠物列表滚动条之上。
		for (net.minecraft.client.gui.components.Renderable dropdown : dropdowns) {
			renderAtDepth(g, LIST_DROPDOWN_OVERLAY_Z,
					() -> dropdown.render(g, mouseX, mouseY, partialTick));
		}

		// L2Tabs 悬浮提示覆盖层（必须在子控件之后渲染）
		if (tabManager != null) {
			try {
				tabManager.getClass()
						.getMethod("onToolTipRender", GuiGraphics.class, int.class, int.class)
						.invoke(tabManager, g, mouseX, mouseY);
			} catch (ReflectiveOperationException ignored) {
				// 可选的 L2Tabs 版本并非都暴露悬浮提示钩子。
			}
		}

		if (squadButton != null) {
			squadButton.renderTooltip(g, mouseX, mouseY);
		}
		if (sortButton != null) {
			sortButton.renderTooltip(g, mouseX, mouseY);
		}
		if (detailsButton != null) {
			detailsButton.renderTooltip(g, mouseX, mouseY);
		}
		if (squadSummonButton != null) {
			squadSummonButton.renderTooltip(g, mouseX, mouseY);
		}
		if (teamSelectorButton != null) {
			teamSelectorButton.renderTooltip(g, mouseX, mouseY);
		}
		if (squadDragging && squadDragUuid != null) {
			LivingEntity dragged = getPreviewEntity(squadDragUuid);
			if (dragged != null) {
				renderMiniPet(g, mouseX, mouseY + 8,
						BASE_SCALE * SQUAD_PET_SCALE_RATIO, dragged);
			}
		}
		renderEmptySlotTooltip(g, mouseX, mouseY);
	}

	private static void renderAtDepth(GuiGraphics graphics, double depth, Runnable renderer) {
		graphics.pose().pushPose();
		graphics.pose().translate(0.0, 0.0, depth);
		try {
			renderer.run();
			graphics.flush();
		} finally {
			graphics.pose().popPose();
		}
	}

	/** 空编队槽位的悬停悬浮提示：添加提示，或红色的队伍已满警告。 */
	private void renderEmptySlotTooltip(GuiGraphics g, int mouseX, int mouseY) {
		Map<Integer, UUID> members = selectedTeamSlots();
		int slot = squadMode ? squadSlotAt(mouseX, mouseY) : -1;
		boolean empty = slot >= 0 && (members == null || !members.containsKey(slot));
		Integer hovered = empty ? slot : null;
		if (!emptySlotHoverDelay.isReady(hovered)) return;

		int count = members != null ? members.size() : 0;
		if (count >= teamCapacity) {
			g.renderTooltip(font, Component.translatable("trulybestfriends.squad.slot_hint_full")
					.withStyle(net.minecraft.ChatFormatting.RED), mouseX, mouseY);
		} else if (hasSelection()) {
			List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
			lines.add(Component.translatable("trulybestfriends.squad.slot_hint_add").getVisualOrderText());
			lines.add(Component.translatable("trulybestfriends.squad.slot_hint_drag").getVisualOrderText());
			g.renderTooltip(font, lines, mouseX, mouseY);
		}
	}

	private void renderSquadGrid(GuiGraphics g, int mouseX, int mouseY) {
		int gridX = this.leftPos + SQUAD_GRID_X;
		int gridY = this.topPos + SQUAD_GRID_Y;
		Map<Integer, UUID> members = selectedTeamSlots();
		boolean teamFull = members != null && members.size() >= teamCapacity;
		boolean showPlus = hasSelection() && members != null;
		int hoveredSlot = squadSlotAt(mouseX, mouseY);
		for (int cell = 0; cell < SQUAD_CELL_SLOTS.length; cell++) {
			if (SQUAD_CELL_SLOTS[cell] < 0) continue;
			int column = cell % 3;
			int row = cell / 3;
			int x = gridX + column * SQUAD_GRID_STEP;
			int y = gridY + row * SQUAD_GRID_STEP;
			g.blit(SQUAD_SLOT,
					x, y,
					0, 0,
					SQUAD_GRID_SLOT_SIZE, SQUAD_GRID_SLOT_SIZE,
					SQUAD_GRID_SLOT_SIZE, SQUAD_GRID_SLOT_SIZE);
			if (members != null) {
				UUID uuid = members.get(SQUAD_CELL_SLOTS[cell]);
				if (uuid == null) {
					if (teamFull) {
						g.blit(PLACEHOLDER,
								x + 1, y + 1,
								16, 16,
								0, 0,
								18, 18,
								18, 18);
					} else if (showPlus && SQUAD_CELL_SLOTS[cell] == hoveredSlot) {
						g.blit(PLUS_SIGN,
								x + (SQUAD_GRID_SLOT_SIZE - 16) / 2,
								y + (SQUAD_GRID_SLOT_SIZE - 16) / 2,
								0, 0, 16, 16, 16, 16);
					}
				} else {
					LivingEntity pet = getPreviewEntity(uuid);
					if (pet != null) {
						renderMiniPet(g,
								x + SQUAD_GRID_SLOT_SIZE / 2,
								y + SQUAD_GRID_SLOT_SIZE - 4,
								BASE_SCALE * SQUAD_PET_SCALE_RATIO, pet);
					}
				}
			}
		}
	}

	private void renderScrollBar(GuiGraphics g) {
		int barX = this.leftPos + SCROLLBAR_OFFSET_X;
		int barY = this.topPos + LIST_PANEL_OFFSET_Y;
		int barH = LIST_PANEL_HEIGHT;
		int barW = SCROLLBAR_WIDTH;

		int thumbH = SCROLLBAR_THUMB_HEIGHT;
		boolean canScroll = petUuids.size() > MAX_VISIBLE;
		int thumbY = barY;
		ResourceLocation thumbSprite = SCROLLBAR_THUMB_DISABLED;
		if (canScroll) {
			float scrollRatio = (float) scrollOffset / Math.max(1, getMaxScrollOffset());
			thumbY += (int) ((barH - thumbH) * scrollRatio);
			thumbSprite = SCROLLBAR_THUMB;
		}

		g.blitSprite(thumbSprite, barX, thumbY, barW, thumbH);
	}

	private void renderPetPreview(GuiGraphics g) {
		tpDimKey = null;
		tpSubLevelId = null;
		coordsHovered = false;

		UUID uuid = getSelectedUuid();
		if (uuid == null) return;
		LivingEntity entity = getPreviewEntity(uuid);
		if (entity == null) return;

		int ex = this.leftPos + ENTITY_PREVIEW_OFFSET_X;
		int ey = this.topPos + ENTITY_PREVIEW_OFFSET_Y;

		// 对于多部件 / 已缩放实体（IaF 龙、末影龙……）
		// 其模型动画由 yBodyRot 驱动，需要完整转一圈
		//（360°）模型才会重新对齐 —— 这就是
		//“必须拖拽 720° 才看起来正常”的现象。Ice & Fire 自己的
		// GuiDragon 通过完全不触碰 yBodyRot / yHeadRot /
		// setYRot（将它们保持在默认的 0°）并仅依赖
		// 传给 renderEntityInInventory 的四元数来避免这一点。
		//
		// 我们复刻这一做法：对于多部件实体（通过 getParts()
		// 或 getScale() > 1 检测）我们将 yBodyRot 保持为 0°，并把用户的
		// 水平拖拽（rotX）经由 rotateY 折入四元数，使
		// 模型保持其规范姿态，而整个渲染出的实体
		// 仍随鼠标旋转。普通宠物保持原来的
		// 由 yBodyRot 驱动的行为。
		boolean multipart = isMultipartPreview(entity);
		Quaternionf quat;
		Quaternionf quatPitch;
		if (multipart) {
			// 从实体模型头部位置自动检测 Y 基准偏移。
			// 标准模型（头部在 -Z）需要 0；非标准模型（头部在 +Z，
			// 例如 Ice & Fire 龙）需要 PI 才能面向相机。
			float yBase = detectMultipartYBase(entity);
			float pitch = multipartPitchRadians(rotY);
			quat = buildMultipartPose(
					yBase - rotX * 20f * ((float) Math.PI / 180f), pitch);
			quatPitch = new Quaternionf().rotateX(pitch);
		} else {
			quat = new Quaternionf().rotateZ((float) Math.PI);
			quatPitch = new Quaternionf().rotateX(rotY * 20f * ((float) Math.PI / 180f));
			quat.mul(quatPitch);
		}

		applyPreviewRotation(entity, multipart, rotX, rotY, true);

		renderEntityInInventory(g, ex, ey, currentScale, quat, quatPitch, entity);
	}

	private void renderHealthBar(GuiGraphics g) {
		CompoundTag nbt = getSelectedNbt();
		if (nbt == null) return;
		float currentHealth = nbt.contains("Health") ? nbt.getFloat("Health") : 0;
		float maxHealth = nbt.contains("MaxHealth") ? nbt.getFloat("MaxHealth") : 0;

		// 若 MaxHealth 缺失或为零，尝试从原版的
		// Attributes 列表恢复。优先使用顶层 MaxHealth（由
		// savePetData 从 getAttributeValue 写入）而非 Attributes.Base，因为
		// Base 保存的是未驯服时的基础值（例如 40 HP 的已驯服狼为 20）。
		if (maxHealth <= 0 && nbt.contains("Attributes")) {
			for (Tag tag : nbt.getList("Attributes", 10)) {
				CompoundTag attr = (CompoundTag) tag;
				if ("minecraft:generic.max_health".equals(attr.getString("Name"))) {
					maxHealth = attr.getFloat("Base");
					break;
				}
			}
		}

		// 防范缺失 / 最大生命值为零的边界情况（未注册 MAX_HEALTH 的
		// 模组生物，或来自旧版模组的 NBT）。
		if (maxHealth <= 0) maxHealth = 20;
		if (currentHealth < 0) currentHealth = 0;

		// 将比例钳制到 [0, 1]，使血条永不溢出其背景。
		float healthRatio = Mth.clamp(currentHealth / maxHealth, 0f, 1f);

		int hx = this.leftPos + HEART_X;
		int hy = this.topPos + HEART_Y;

		g.blitSprite(HEART_CONTAINER, hx, hy, 9, 9);
		if (currentHealth > 0) g.blitSprite(HEART_FULL, hx, hy, 9, 9);

		int bx = hx + 11;
		int by = hy + 2;
		int total = BAR_MIDDLE_WIDTH + 10;
		renderThreePartHealthBar(g, HEALTH_BAR_BACKGROUND, bx, by, total);

		int filled = Math.max(0, (int) (total * healthRatio));
		renderThreePartHealthBar(g, HEALTH_BAR_PROGRESS, bx, by, filled);
	}

	private static void renderThreePartHealthBar(GuiGraphics g, ResourceLocation sprite,
	                                             int x, int y, int visibleWidth) {
		final int capWidth = 5;
		final int spriteWidth = 182;
		final int spriteHeight = 5;
		int width = Mth.clamp(visibleWidth, 0, BAR_MIDDLE_WIDTH + capWidth * 2);

		int leftWidth = Math.min(capWidth, width);
		if (leftWidth > 0) {
			g.blitSprite(sprite, spriteWidth, spriteHeight, 0, 0,
					x, y, leftWidth, spriteHeight);
		}

		int middleWidth = Math.min(BAR_MIDDLE_WIDTH, Math.max(0, width - capWidth));
		if (middleWidth > 0) {
			g.blitSprite(sprite, spriteWidth, spriteHeight, capWidth, 0,
					x + capWidth, y, middleWidth, spriteHeight);
		}

		int rightWidth = Math.max(0, width - capWidth - BAR_MIDDLE_WIDTH);
		if (rightWidth > 0) {
			g.blitSprite(sprite, spriteWidth, spriteHeight, spriteWidth - capWidth, 0,
					x + capWidth + BAR_MIDDLE_WIDTH, y, rightWidth, spriteHeight);
		}
	}

	private void renderPetInfo(GuiGraphics g) {
		int lx = this.leftPos + PET_INFO_OFFSET_X;
		UUID uuid = getSelectedUuid();
		int nameY = this.topPos + NAME_Y;
		drawClippedScrollingString(g, getPetDisplayName(uuid), lx, nameY,
				PET_NAME_MAX_WIDTH, 0x000000);
	}

	private void renderPetLocation(GuiGraphics g, int mouseX, int mouseY) {
		CompoundTag nbt = getSelectedNbt();
		if (nbt == null) return;

		int lx = this.leftPos + PET_INFO_OFFSET_X;
		int ly = this.topPos + LOCATION_Y;

		// 死亡宠物：显示带物品图标的复活信息
		if (nbt.contains("Health") && nbt.getFloat("Health") <= 0) {
			coordsHovered = false;
			tpDimKey = null;
			tpSubLevelId = null;
			// 白名单实体类型无法复活：显示警告而非物品图标
			if (nbt.contains("EntityType") && Config.isNoReviveEntity(nbt.getString("EntityType"))) {
				Component warning = Component.translatable("trulybestfriends.revive.not_revivable")
						.withStyle(net.minecraft.ChatFormatting.RED);
				int infoRight = this.leftPos + this.imageWidth - 4;
				int maxW = Math.min(infoRight - lx, 82);
				drawClippedScrollingString(g, warning, lx, ly, maxW, 0xFF0000);
				return;
			}
			if (!Config.isReviveItemRequired()) return;
			var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(Config.reviveItem));
			if (item != null) {
				int maxTextWidth = this.imageWidth - PET_INFO_OFFSET_X - 4;
				g.flush();
				g.enableScissor(lx, ly - 2, lx + maxTextWidth, ly + 14);
				try {
					Component prefixText = Component.translatable("trulybestfriends.revive.prefix");
					drawString(g, prefixText, lx, ly, 0x000000);
					int itemX = lx + font().width(prefixText) + 2;
					ItemStack icon = new ItemStack(item, Config.reviveItemCount);
					g.renderItem(icon, itemX, ly - 2);
					g.renderItemDecorations(font(), icon, itemX, ly - 2);
					Component suffixText = Component.translatable("trulybestfriends.revive.suffix");
					drawString(g, suffixText, itemX + 20, ly, 0x000000);
					g.flush();
				} finally {
					g.disableScissor();
				}
			}
			return;
		}

		if (!nbt.contains("Pos")) return;

		UUID uuid = getSelectedUuid();
		if (uuid != null && isPetOnShoulder(uuid)) {
			coordsHovered = false;
			tpDimKey = null;
			tpSubLevelId = null;
			int maxTextWidth = this.imageWidth - PET_INFO_OFFSET_X - 4;
			drawClippedScrollingString(g,
					Component.translatable("trulybestfriends.shoulder.on_shoulder"),
					lx, ly, maxTextWidth, 0x000000);
			return;
		}

		var pos = nbt.getList("Pos", 6);
		if (pos.size() < 3) return;

		int x = (int) Math.round(pos.getDouble(0));
		int y = (int) Math.round(pos.getDouble(1));
		int z = (int) Math.round(pos.getDouble(2));
		String dimKey = nbt.contains("Dimension") ? nbt.getString("Dimension") : "";
		boolean isRecalled = nbt.getBoolean("Recalled");
		int infoRight = this.leftPos + this.imageWidth - 4;
		int maxTextWidth = infoRight - lx;

		// 第 1 行 (ly)：世界名称，对已收回的宠物显示 "已收回"
		if (isRecalled) {
			coordsHovered = false;
			tpDimKey = null;
			tpSubLevelId = null;
			drawClippedScrollingString(g,
					Component.translatable("trulybestfriends.coords.recalled"),
					lx, ly, maxTextWidth, 0xAA5555);
		} else {
			Component dimText;
			if (!dimKey.isEmpty()) {
				String name = Config.getDimensionDisplayName(dimKey);
				dimText = Component.literal(name != null ? name : dimKey);
			} else {
				dimText = Component.translatable("trulybestfriends.location.unknown");
			}
			drawClippedScrollingString(g, dimText, lx, ly, maxTextWidth, 0x000000);
		}

		// 第 2 行 (ly + 10)：限时警告或坐标
		if (warningText != null && System.currentTimeMillis() < warningUntil
				&& warningUuid != null && warningUuid.equals(getSelectedUuid())) {
			int warnColor = isRecalled ? 0xFFFF55 : 0xFF5555;
			drawClippedScrollingString(g, warningText, lx, ly + 10, maxTextWidth, warnColor);
			return;
		}

		if (isRecalled) return;

		String coordStr = x + " " + y + " " + z;
		boolean canTp = !dimKey.isEmpty() && minecraft.player != null && minecraft.player.isCreative();
		int coordVisibleWidth = Math.min(font().width(coordStr), maxTextWidth);
		coordsHovered = canTp && mouseX >= lx && mouseX <= lx + coordVisibleWidth
				&& mouseY >= ly + 10 && mouseY <= ly + 20;
		int color = coordsHovered ? 0xFFAA00 : 0x000000;
		drawClippedScrollingString(g, Component.literal(coordStr), lx, ly + 10,
				maxTextWidth, color);

		tpDimKey = dimKey;
		tpX = x; tpY = y; tpZ = z;
		tpSubLevelId = SableCompat.readSubLevelId(nbt);

		if (coordsHovered) {
			g.renderTooltip(font(), Component.translatable("trulybestfriends.teleport.hint"), mouseX, mouseY);
		}
	}

	boolean isPetOnShoulder(UUID petUuid) {
		if (minecraft.player == null) return false;
		CompoundTag left = minecraft.player.getShoulderEntityLeft();
		if (left.contains("UUID") && left.getUUID("UUID").equals(petUuid)) return true;
		CompoundTag right = minecraft.player.getShoulderEntityRight();
		return right.contains("UUID") && right.getUUID("UUID").equals(petUuid);
	}

	private void drawString(GuiGraphics g, Component text, int x, int y, int color) {
		RenderHelper.drawString(g, font(), text, x, y, color);
	}

	private void drawScrollingString(GuiGraphics g, Component text, int x, int y, int maxWidth, int color) {
		RenderHelper.drawScrollingString(g, font(), text, x, y, maxWidth, color);
	}

	private void drawClippedScrollingString(GuiGraphics g, Component text,
	                                        int x, int y, int maxWidth, int color) {
		if (maxWidth <= 0) return;
		g.flush();
		g.enableScissor(x, y, x + maxWidth, y + 10);
		try {
			drawScrollingString(g, text, x, y, maxWidth, color);
			g.flush();
		} finally {
			g.disableScissor();
		}
	}

	// ============================
	//        鼠标输入
	// ============================

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalDelta, double verticalDelta) {
		if (speciesFilterButton != null
				&& speciesFilterButton.mouseScrolled(mouseX, mouseY, horizontalDelta, verticalDelta)) {
			return true;
		}
		if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
			areaRecallRange = Mth.clamp(areaRecallRange + (verticalDelta > 0 ? 1 : -1), 1, 16);
			return true;
		}
		if (hasSelection() && isOverEntityPreview(mouseX, mouseY)) {
			// 按比例缩放：每一格都乘以/除以一个固定
			// 因子，因此每步的相对变化与
			// 当前缩放值无关且恒定。边界与当前
			// 实体自动计算的 referenceScale 成正比，所以巨型龙（较小的
			// referenceScale）和微小宠物（较大的 referenceScale）各自获得
			// 相同的相对缩放范围，而不是共用固定数值。
			float factor = verticalDelta > 0 ? 1.1f : (1f / 1.1f);
			currentScale = Mth.clamp(currentScale * factor, referenceScale * 0.5f, referenceScale * 2.0f);
			return true;
		}
		if (petUuids.size() > MAX_VISIBLE && isOverList(mouseX, mouseY)) {
			if (verticalDelta > 0) scrollOffset = Math.max(0, scrollOffset - COLUMNS);
			else { scrollOffset += COLUMNS; snapScrollOffset(); }
			rebuildPetWidgets();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalDelta, verticalDelta);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (teamSelectorButton != null && teamSelectorButton.isExpanded()) {
			if (teamSelectorButton.mouseClicked(mx, my, button)) return true;
			teamSelectorButton.collapse();
		}
		if (button == 0 && squadMode) {
			int slot = squadSlotAt(mx, my);
			if (slot >= 0) {
				UUID member = squadMemberAtSlot(slot);
				if (member != null) {
					squadDragPending = true;
					squadDragConsumed = true;
					squadDragging = false;
					squadDragFromGrid = true;
					squadDragUuid = member;
					squadDragSourceSlot = slot;
					squadDragStartX = mx;
					squadDragStartY = my;
					return true;
				}
				Map<Integer, UUID> slots = selectedTeamSlots();
				if (hasSelection() && slots != null && slots.size() < teamCapacity) {
					UUID uuid = getSelectedUuid();
					if (uuid != null) {
						assignSquadMemberLocally(selectedTeamColor(), slot, uuid);
						PacketDistributor.sendToServer(
								SetTeamMemberPacket.assign(selectedTeamIndex, slot, uuid));
						return true;
					}
				}
			}
			PetEntry entry = squadPetEntryAt(mx, my);
			if (entry != null && entry.petUuid() != null) {
				UUID uuid = entry.petUuid();
				squadDragPending = true;
				squadDragConsumed = false;
				squadDragging = false;
				squadDragFromGrid = false;
				squadDragUuid = uuid;
				squadDragSourceSlot = -1;
				squadDragStartX = mx;
				squadDragStartY = my;
			}
		}
		if (speciesFilterButton != null && speciesFilterButton.mouseClicked(mx, my, button)) {
			// 两个下拉框互斥展开，避免同时弹出两个列表。
			if (sortModeDropdown != null) sortModeDropdown.collapse();
			return true;
		}
		if (sortModeDropdown != null && sortModeDropdown.mouseClicked(mx, my, button)) return true;
		if (button == 0 && hasSelection() && isOverEntityPreview(mx, my)) {
			isDraggingEntity = true;
			return true;
		}
		if (button == 0 && petUuids.size() > MAX_VISIBLE && clickScrollbar(mx, my)) return true;
		if (button == 0 && coordsHovered && tpDimKey != null && !tpDimKey.isEmpty()) {
			PacketDistributor.sendToServer(new com.whidte.trulybestfriends.network.TeleportToPetPacket(
					tpDimKey, tpX, tpY, tpZ, tpSubLevelId));
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (squadDragging) return true;
		if (squadDragPending && button == 0) {
			double deltaX = mx - squadDragStartX;
			double deltaY = my - squadDragStartY;
			if (deltaX * deltaX + deltaY * deltaY > 16.0) {
				squadDragging = true;
				squadDragPending = false;
				squadDragConsumed = false;
				return true;
			}
		}
		if (speciesFilterButton != null && speciesFilterButton.mouseDragged(mx, my, button, dx, dy)) return true;
		if (isDraggingScrollbar) {
			dragScrollbar(my);
			return true;
		}
		if (isDraggingEntity) {
			float sens = -Mth.clamp(BASE_DRAG_SENSITIVITY * ((float) REFERENCE_WINDOW_WIDTH / this.minecraft.getWindow().getWidth()), 0.25f, 0.5f);
			rotX += (float) dx * sens;
			rotY = Mth.clamp(rotY + (float) dy * sens, -75f, -71f);
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (button == 0) {
			if (squadDragging) {
				resolveSquadDrop(mx, my);
				return true;
			}
			if (squadDragPending) {
				squadDragPending = false;
				if (squadDragConsumed) {
					squadDragConsumed = false;
					squadDragFromGrid = false;
					squadDragUuid = null;
					squadDragSourceSlot = -1;
					return true;
				}
			}
		}
		if (speciesFilterButton != null && speciesFilterButton.mouseReleased(mx, my, button)) return true;
		if (button == 0) {
			if (isDraggingEntity) { isDraggingEntity = false; return true; }
			if (isDraggingScrollbar) { isDraggingScrollbar = false; return true; }
		}
		return super.mouseReleased(mx, my, button);
	}

	private boolean isOverEntityPreview(double mx, double my) {
		if (squadMode) return false;
		int ex = this.leftPos + ENTITY_PREVIEW_OFFSET_X;
		int ey = this.topPos + ENTITY_PREVIEW_OFFSET_Y;
		int s = 50;
		return mx >= ex - s / 2 && mx <= ex + s / 2 && my >= ey - s + 10 && my <= ey + 10;
	}

	private boolean isOverList(double mx, double my) {
		int lx = this.leftPos + LIST_PANEL_OFFSET_X;
		int ly = this.topPos + LIST_PANEL_OFFSET_Y;
		return mx >= lx && mx <= lx + LIST_PANEL_WIDTH && my >= ly && my <= ly + LIST_PANEL_HEIGHT;
	}

	private boolean clickScrollbar(double mx, double my) {
		int barX = this.leftPos + SCROLLBAR_OFFSET_X;
		int barY = this.topPos + LIST_PANEL_OFFSET_Y;
		int barH = LIST_PANEL_HEIGHT;
		if (mx < barX || mx >= barX + SCROLLBAR_WIDTH || my < barY || my >= barY + barH) return false;

		int thumbH = SCROLLBAR_THUMB_HEIGHT;
		float scrollRatio = (float) scrollOffset / Math.max(1, getMaxScrollOffset());
		int thumbY = barY + (int) ((barH - thumbH) * scrollRatio);

		if (my >= thumbY && my < thumbY + thumbH) {
			isDraggingScrollbar = true;
			return true;
		}
		if (my < thumbY) scrollOffset = Math.max(0, scrollOffset - MAX_VISIBLE);
		else scrollOffset += MAX_VISIBLE;
		snapScrollOffset();
		rebuildPetWidgets();
		return true;
	}

	private void dragScrollbar(double my) {
		int barY = this.topPos + LIST_PANEL_OFFSET_Y;
		int barH = LIST_PANEL_HEIGHT;
		int thumbH = SCROLLBAR_THUMB_HEIGHT;
		int maxThumbY = barH - thumbH;
		if (maxThumbY > 0) {
			float progress = Mth.clamp(((float) my - barY - thumbH / 2f) / maxThumbY, 0f, 1f);
			scrollOffset = Math.round(progress * getMaxScrollOffset());
			snapScrollOffset();
			rebuildPetWidgets();
		}
	}

	// ============================
	//        杂项
	// ============================

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
