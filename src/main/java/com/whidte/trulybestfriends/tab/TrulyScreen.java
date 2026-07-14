package com.whidte.trulybestfriends.tab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Quaternionf;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;
import static com.whidte.trulybestfriends.tab.RenderHelper.*;

public class TrulyScreen extends Screen {

	protected int leftPos, topPos, imageWidth, imageHeight;

	// --- State ---
	List<UUID> petUuids = new ArrayList<>();
	Map<UUID, CompoundTag> petNbtCache = new LinkedHashMap<>();
	private final Map<UUID, LivingEntity> previewEntities = new java.util.HashMap<>();
	Map<UUID, Integer> petPriorities = new LinkedHashMap<>();
	Map<UUID, Long> cooldowns = new java.util.HashMap<>();
	long serverTimeOffsetMs = 0L;
	int selectedPetIndex = -1;
	int scrollOffset = 0;
	float currentScale = 17;
	/** Auto-computed scale for the currently selected entity; scroll-zoom
	 * bounds are derived proportionally from this value so that every
	 * entity (tiny pet or huge dragon) has the same relative zoom range. */
	float referenceScale = 17;
	float rotX = DEFAULT_ROT_X;
	float rotY = DEFAULT_ROT_Y;
	boolean isDraggingEntity = false;
	boolean isDraggingScrollbar = false;
	boolean sortNeeded = false;
	int tickCounter = 0;
	long lastGlowClickTime = 0;
	UUID deletePromptUuid;
	GlowButton glowButton;
	ActionButton actionButton;
	SummonToPlayerButton summonToPlayerButton;
	private SpeciesDropdown speciesFilterButton;
	private EditBox searchBox;
	private SearchModeButton searchModeButton;
	private boolean searchMode;
	private String speciesFilter = "";
	private String searchQuery = "";
	String tpDimKey;
	int tpX, tpY, tpZ;
	boolean coordsHovered;
	int areaRecallRange = Config.areaRecallDefaultRange;
	Component warningText;
	long warningUntil;
	UUID warningUuid;

	/** Full-list batches received before the screen was open. Applied in order on init(). */
	private static final List<com.whidte.trulybestfriends.network.SyncPetDataPacket> pendingSyncPackets = new ArrayList<>();

	/** Cache state from before the current batched full-list snapshot. */
	private Map<UUID, CompoundTag> fullListPreviousNbt;
	private Map<UUID, CompoundTag> pendingFullListNbt;
	private Map<UUID, Integer> pendingFullListPriorities;

	/** The pet UUID that was selected when saveSelectionThenReload was called.
     *  Used by applySyncPacket(MODE_FULL_LIST) to restore the selection. */
    private UUID lastRequestedSelection;

    /** L2Tabs TabManager, set via L2TabsIntegration.createTabManager() when L2Tabs is present. */
    public dev.xkmc.l2tabs.tabs.core.TabManager tabManager;

	// --- Constructor ---
	public TrulyScreen(Component title) {
		super(title);
		this.imageWidth = 176;
		this.imageHeight = 166;
	}

	net.minecraft.client.gui.Font font() {
		return this.font;
	}

	/** Expose protected addRenderableWidget for L2Tabs TabManager. */
	public <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry> T addWidgetPublic(T widget) {
		return this.addRenderableWidget(widget);
	}

	// === Selection helpers ===

	UUID getSelectedUuid() {
		if (selectedPetIndex < 0 || selectedPetIndex >= petUuids.size()) return null;
		return petUuids.get(selectedPetIndex);
	}

	/** Called from PetWarningPacket (client thread) to show a timed warning at the coordinates position. */
	public void showWarning(Component msg, UUID uuid) {
		this.warningText = msg;
		this.warningUntil = System.currentTimeMillis() + 3000;
		this.warningUuid = uuid;
	}

	CompoundTag getSelectedNbt() {
		UUID uuid = getSelectedUuid();
		return uuid != null ? petNbtCache.get(uuid) : null;
	}

	boolean hasSelection() {
		return getSelectedUuid() != null;
	}

	boolean isSelectedPetInactive() {
		CompoundTag nbt = getSelectedNbt();
		return nbt != null && (nbt.getBoolean("Recalled") || isSelectedPetDead() || isSelectedPetLost());
	}

	boolean isSelectedPetDead() {
		CompoundTag nbt = getSelectedNbt();
		return nbt != null && nbt.contains("Health") && nbt.getFloat("Health") <= 0;
	}

	boolean isSelectedPetLost() {
		CompoundTag nbt = getSelectedNbt();
		return nbt != null && (nbt.getBoolean("Lost") || !nbt.contains("Pos") || !nbt.contains("Dimension"));
	}

	/** 数据损坏（无 Pos 或 Dimension）的宠物：单击直接删除，无需两步确认。
	 *  与 isSelectedPetLost() 的区别：仅检查数据完整性，不包含 Lost 标志。
	 *  Lost=true 仅表示实体当前未加载（可能在卸载区块中），数据本身可能完好，
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
		String typeKey = nbt.getString("EntityType");
		if (typeKey.isEmpty()) return null;
		EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(typeKey));
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

	// === Lifecycle ===

	@Override
	public void init() {
		super.init();
		speciesFilterButton = null;
		searchBox = null;
		searchModeButton = null;
		this.leftPos = (this.width - this.imageWidth) / 2;
		this.topPos = (this.height - this.imageHeight) / 2;

		// L2Tabs tab bar integration
		if (net.minecraftforge.fml.ModList.get().isLoaded("l2tabs")) {
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
		// Clean up any preview entities left behind from PetEntry renders
		super.removed();
	}

	private void saveSelectionThenReload() {
		UUID selectedUuid = getSelectedUuid();
		lastRequestedSelection = selectedUuid;

		petUuids.clear();
		petNbtCache.clear();
		clearPreviewEntities();
		petPriorities.clear();
		selectedPetIndex = -1;
		scrollOffset = 0;

		// 1. Apply any cached full-list snapshot (arrived while screen was closed).
		if (!pendingSyncPackets.isEmpty()) {
			List<com.whidte.trulybestfriends.network.SyncPetDataPacket> packets = new ArrayList<>(pendingSyncPackets);
			pendingSyncPackets.clear();
			for (com.whidte.trulybestfriends.network.SyncPetDataPacket packet : packets) {
				applySyncPacket(packet);
			}
		}

		// 2. Singleplayer: also load from disk for instant feedback (server is
		//    same process, no network round-trip needed).
		Minecraft mc = getMinecraft();
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null && petNbtCache.isEmpty()) {
			PetDataLoader.loadAll(mc, petNbtCache, petPriorities);
		}

		rebuildFilteredPetUuids(selectedUuid, true);
		rebuildPetWidgets();

		// 3. Always ask the server for a fresh full-list snapshot (works for both
		//    singleplayer and multiplayer). The reply updates the cache via
		//    applySyncPacket(). In singleplayer this overwrites the disk-loaded
		//    data with the authoritative server version.
		if (mc.player != null && mc.getConnection() != null) {
			com.whidte.trulybestfriends.trulybestfriends.CHANNEL.sendToServer(
					com.whidte.trulybestfriends.network.RequestPetDataPacket.requestFullList());
		}

		if (hasSelection()) {
			adjustScaleForCurrentPet();
		}
	}

	private void addButtons() {
		boolean has = hasSelection();
		glowButton = new GlowButton(this.leftPos + GLOW_X, this.topPos + GLOW_Y, this);
		glowButton.visible = has;
		this.addRenderableWidget(glowButton);

		actionButton = new ActionButton(this.leftPos + ACTION_X, this.topPos + ACTION_Y, this);
		actionButton.visible = has;
		this.addRenderableWidget(actionButton);

		summonToPlayerButton = new SummonToPlayerButton(
				this.leftPos + SUMMON_TO_PLAYER_X, this.topPos + SUMMON_TO_PLAYER_Y, SUMMON_TO_PLAYER_W, this);
		summonToPlayerButton.visible = has;
		this.addRenderableWidget(summonToPlayerButton);
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
		ResourceLocation resource = ResourceLocation.tryParse(key);
		EntityType<?> type = resource != null ? ForgeRegistries.ENTITY_TYPES.getValue(resource) : null;
		return type != null ? type.getDescription() : Component.literal(key);
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
		rebuildPetWidgets();
		updateButtonVisibility();
		if (hasSelection()) adjustScaleForCurrentPet();
	}

	private void rebuildFilteredPetUuids(UUID preferredSelection, boolean revealSelection) {
		int previousScrollOffset = scrollOffset;
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
			if (selectedPetIndex < scrollOffset || selectedPetIndex >= scrollOffset + MAX_VISIBLE) {
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

	// === Real-time refresh ===

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
		// Replaced by server-driven sync: ask the server for the latest NBT of
		// the selected pet.  Server replies with SyncPetDataPacket (update/delete),
		// handled in applySyncPacket().  No client disk I/O.
		UUID selUuid = getSelectedUuid();
		if (selUuid == null) return;
		Minecraft mc = getMinecraft();
		if (mc.player == null || mc.getConnection() == null) return;
		com.whidte.trulybestfriends.trulybestfriends.CHANNEL.sendToServer(
				com.whidte.trulybestfriends.network.RequestPetDataPacket.requestSelected(selUuid));
	}

	/** Apply a server-pushed sync packet. Called from SyncPetDataPacket.handle
	 *  when this screen is open. */
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
						int priority = nbt.contains("Priority") ? nbt.getInt("Priority") : 6;
						pendingFullListPriorities.put(uuid, Math.max(1, Math.min(6, priority)));
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
				rebuildPetWidgets();
				refreshSpeciesFilterButton();
				updateButtonVisibility();
				if (hasSelection()) adjustScaleForCurrentPet();
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
				if (!merged.equals(oldNbt)) {
					invalidatePreviewEntity(uuid);
				}
				petNbtCache.put(uuid, merged);
				int priority = merged.contains("Priority") ? merged.getInt("Priority") : 6;
				priority = Math.max(1, Math.min(6, priority));
				petPriorities.put(uuid, priority);
				rebuildFilteredPetUuids(previousSelection, false);
				rebuildPetWidgets();
				if (!oldSpecies.equals(merged.getString("EntityType"))) refreshSpeciesFilterButton();
				updateButtonVisibility();
				if (!java.util.Objects.equals(previousSelection, getSelectedUuid()) && hasSelection()) {
					adjustScaleForCurrentPet();
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
				if (uuid.equals(deletePromptUuid)) deletePromptUuid = null;
				normalizeSpeciesFilter();
				rebuildFilteredPetUuids(deletedSelectedPet ? null : previousSelection, false);
				if (deletedSelectedPet) {
					selectedPetIndex = petUuids.isEmpty() ? -1 : Math.min(Math.max(deletedIndex, 0), petUuids.size() - 1);
					scrollOffset = selectedPetIndex < 0 ? 0 : (selectedPetIndex / COLUMNS) * COLUMNS;
					snapScrollOffset();
					if (hasSelection()) adjustScaleForCurrentPet();
				}
				rebuildPetWidgets();
				refreshSpeciesFilterButton();
				updateButtonVisibility();
			}
		}
	}

	/** Cache a sync packet that arrived while the screen was not open.
	 *  Applied on next init(). */
	public static void cacheSyncPacket(com.whidte.trulybestfriends.network.SyncPetDataPacket packet) {
		// Only full-list snapshots are worth caching for the next screen open;
		// updates/deletes for a closed screen are stale and can be dropped.
		if (packet.getMode() == com.whidte.trulybestfriends.network.SyncPetDataPacket.MODE_FULL_LIST) {
			if (packet.isFirstBatch()) pendingSyncPackets.clear();
			else if (pendingSyncPackets.isEmpty()) return;
			pendingSyncPackets.add(packet);
		}
	}

	long currentServerTimeMillis() {
		return System.currentTimeMillis() + serverTimeOffsetMs;
	}

	private void updateButtonVisibility() {
		boolean has = hasSelection();
		if (glowButton != null) glowButton.visible = has;
		if (actionButton != null) actionButton.visible = has;
		if (summonToPlayerButton != null) summonToPlayerButton.visible = has;
	}

	private void cleanExpiredCooldowns() {
		// Cooldowns never exceed recallCooldownMs; keep 2x as safety margin for clock skew.
		long cutoff = System.currentTimeMillis() - (Config.recallCooldownMs * 2L);
		cooldowns.values().removeIf(t -> t < cutoff);
	}

	// === Scale ===

	void adjustScaleForCurrentPet() {
		UUID uuid = getSelectedUuid();
		if (uuid == null) return;
		LivingEntity entity = getPreviewEntity(uuid);
		if (entity == null) return;
		// Store the raw auto-computed scale as the reference for scroll-zoom
		// bounds.  currentScale is reset to it so switching pets discards the
		// previous pet's manual zoom.
		this.referenceScale = computePreviewScale(entity, BASE_SCALE);
		this.currentScale = this.referenceScale;
	}

	/**
	 * Computes the scale parameter for
	 * {@link InventoryScreen#renderEntityInInventory}, following the approach
	 * used by Ice &amp; Fire's {@code GuiDragon}.
	 *
	 * <p>Forge's {@link Entity#getScale()} reflects the entity's visual scale
	 * attribute (e.g., IaF dragons return a large value at higher stages, the
	 * Ender Dragon returns ~1.0 but its model is already oversized).  Dividing
	 * a base size by this scale yields a parameter that keeps any entity
	 * inside the preview area, including multipart entities whose bounding
	 * box alone is far smaller than their actual model.</p>
	 *
	 * <p>For ordinary entities ({@code getScale() == 1.0}) we fall back to the
	 * classic bounding-box heuristic so existing pets render unchanged.</p>
	 *
	 * <p>No fixed numeric clamp is applied here — the caller stores the result
	 * as {@code referenceScale} and the scroll-zoom handler clamps
	 * proportionally around it.</p>
	 *
	 * @param entity  the preview entity (must not be null)
	 * @param baseSize  the reference size used for ordinary entities
	 * @return the raw auto-computed scale (unclamped)
	 */
	static float computePreviewScale(LivingEntity entity, float baseSize) {
		float scale = entity.getScale();
		boolean multipart = entity.getParts() != null && entity.getParts().length > 0;
		if (scale > 1.0001f) {
			// Scaled entity (IaF dragon, ...): use the IaF formula.
			return baseSize / scale;
		}
		// Ordinary entity: keep the bounding-box heuristic.
		float maxDim = Math.max(entity.getBbWidth(), entity.getBbHeight());
		if (maxDim <= 0) return baseSize;
		float computed = baseSize * (HORSE_MAX_DIM / maxDim);
		// Multipart entities whose getScale() == 1 (e.g. the Ender Dragon)
		// have very large bounding boxes that make the heuristic produce a
		// tiny value.  Apply a floor so the model stays visible.
		if (multipart) {
			return Math.max(computed, baseSize * 0.25f);
		}
		return computed;
	}

	// === Scroll / Sorting ===

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
					listX + col * (ENTRY_WIDTH + ENTRY_GAP_X), listY + row * ENTRY_ROW_STEP,
					ENTRY_WIDTH, ENTRY_HEIGHT, name, i, this));
		}
	}

	private void sortPetUuids() {
		petUuids.sort(Comparator.comparingInt(uuid ->
				Math.max(1, Math.min(6, petPriorities.getOrDefault(uuid, 6)))));
	}

	void onShiftReleased() {
		sortPetUuids();
		scrollOffset = 0;
		rebuildPetWidgets();
	}

	Component getPetDisplayName(UUID uuid) {
		CompoundTag nbt = petNbtCache.get(uuid);
		if (nbt == null) return Component.literal("???");
		if (nbt.contains("CustomName")) {
			try {
				return Component.Serializer.fromJson(nbt.getString("CustomName"));
			} catch (Exception ignored) {}
		}
		String typeKey = nbt.getString("EntityType");
		if (!typeKey.isEmpty()) {
			var type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(typeKey));
			if (type != null) return type.getDescription();
		}
		return Component.literal("???");
	}

	// ============================
	//        RENDER
	// ============================

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// Draw the panel first, then layer custom widgets and overlays on top.
		this.renderBackground(g);
		g.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
		renderListBackground(g);
		PetEntry selectedEntry = null;
		SpeciesDropdown speciesDropdown = null;
		for (GuiEventListener listener : this.children()) {
			if (listener instanceof SpeciesDropdown dropdown) {
				speciesDropdown = dropdown;
			} else if (listener instanceof PetEntry entry && entry.isSelected()) {
				selectedEntry = entry;
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

		if (hasSelection()) {
			renderPetPreview(g);
			g.pose().pushPose();
			g.pose().translate(0.0, 0.0, PET_INFO_OVERLAY_Z);
			try {
				renderHealthBar(g);
				renderPetInfo(g);
				renderPetLocation(g, mouseX, mouseY);
				g.flush();
			} finally {
				g.pose().popPose();
			}
		}

		// Keep the expanded dropdown above pet entries and the pet-list scrollbar.
		if (speciesDropdown != null) {
			g.pose().pushPose();
			g.pose().translate(0.0, 0.0, SPECIES_DROPDOWN_OVERLAY_Z);
			try {
				speciesDropdown.render(g, mouseX, mouseY, partialTick);
				g.flush();
			} finally {
				g.pose().popPose();
			}
		}

		// L2Tabs tooltip overlay (must render after children)
		if (tabManager != null) {
			tabManager.onToolTipRender(g, mouseX, mouseY);
		}
	}

	private void renderListBackground(GuiGraphics g) {
		int x = this.leftPos + LIST_PANEL_OFFSET_X;
		int y = this.topPos + LIST_PANEL_OFFSET_Y;
		int right = this.leftPos + this.imageWidth - SCROLLBAR_RIGHT_OFFSET;
		g.fill(x, y, right, y + LIST_PANEL_HEIGHT, 0x20000000);

		int controlsY = this.topPos + LIST_CONTROLS_OFFSET_Y;
		g.fill(x, controlsY, x + LIST_PANEL_WIDTH, controlsY + LIST_CONTROL_HEIGHT, 0x20000000);
	}

	private void renderScrollBar(GuiGraphics g) {
		if (petUuids.size() <= MAX_VISIBLE) return;

		int barX = this.leftPos + this.imageWidth - SCROLLBAR_RIGHT_OFFSET;
		int barY = this.topPos + LIST_PANEL_OFFSET_Y;
		int barH = LIST_PANEL_HEIGHT;
		int barW = SCROLLBAR_WIDTH;

		// Track: 3-segment blit (top border, stretched middle, bottom border)
		g.blit(SCROLLBAR, barX, barY, barW, 1, 0, 0, 4, 1, 4, 3);
		g.blit(SCROLLBAR, barX, barY + 1, barW, barH - 2, 0, 1, 4, 1, 4, 3);
		g.blit(SCROLLBAR, barX, barY + barH - 1, barW, 1, 0, 2, 4, 1, 4, 3);

		float ratio = (float) MAX_VISIBLE / petUuids.size();
		int thumbH = Math.max(8, (int) (barH * ratio));
		float scrollRatio = (float) scrollOffset / Math.max(1, getMaxScrollOffset());
		int thumbY = barY + (int) ((barH - thumbH) * scrollRatio);

		// Thumb: 3-segment blit (top, stretched middle, bottom)
		g.blit(SCROLLBAR_THUMB, barX, thumbY, barW, 1, 0, 0, 4, 1, 4, 3);
		g.blit(SCROLLBAR_THUMB, barX, thumbY + 1, barW, thumbH - 2, 0, 1, 4, 1, 4, 3);
		g.blit(SCROLLBAR_THUMB, barX, thumbY + thumbH - 1, barW, 1, 0, 2, 4, 1, 4, 3);
	}

	private void renderPetPreview(GuiGraphics g) {
		tpDimKey = null;
		coordsHovered = false;

		UUID uuid = getSelectedUuid();
		if (uuid == null) return;
		LivingEntity entity = getPreviewEntity(uuid);
		if (entity == null) return;

		int ex = this.leftPos + ENTITY_PREVIEW_OFFSET_X;
		int ey = this.topPos + ENTITY_PREVIEW_OFFSET_Y;

		// For multipart / scaled entities (IaF dragons, Ender Dragon, ...)
		// the model animation is driven by yBodyRot and a single full turn
		// (360°) is required before the model lines up again — hence the
		// "need to drag 720° before it looks right" symptom.  Ice & Fire's
		// own GuiDragon avoids this by NOT touching yBodyRot / yHeadRot /
		// setYRot at all (leaving them at the default 0°) and relying solely
		// on the quaternion passed to renderEntityInInventory.
		//
		// We replicate that: for multipart entities (detected via getParts()
		// or getScale() > 1) we leave yBodyRot at 0° and fold the user's
		// horizontal drag (rotX) into the quaternion via rotateY, so the
		// model stays in its canonical pose while the whole rendered entity
		// still rotates with the mouse.  Ordinary pets keep the original
		// yBodyRot-driven behaviour.
		boolean multipart = entity.getScale() > 1.0001f
				|| (entity.getParts() != null && entity.getParts().length > 0);
		Quaternionf quat;
		Quaternionf quatPitch;
		if (multipart) {
			// Auto-detect Y base offset from the entity's model head position.
			// Standard models (head at -Z) need 0; non-standard (head at +Z,
			// e.g. Ice & Fire dragons) need PI to face the camera.
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

		if (!multipart) {
			entity.yBodyRot = 180f + rotX * 20f;
			entity.setYRot(180f + rotX * 40f);
			setXRotUnclamped(entity, -rotY * 20f);
			entity.yHeadRot = entity.yBodyRot;
			entity.yHeadRotO = entity.yBodyRot;
		} else {
			// Reset all rotation fields to 0° so the model always starts
			// from its canonical facing, regardless of the yBodyRot / yRot
			// values saved in NBT (which reflect the entity's last heading
			// in the world and would otherwise vary per open).
			entity.yBodyRot = 0f;
			entity.yBodyRotO = 0f;
			entity.setYRot(0f);
			entity.yRotO = 0f;
			entity.yHeadRot = 0f;
			entity.yHeadRotO = 0f;
		}

		renderEntityInInventory(g, ex, ey, currentScale, quat, quatPitch, entity);
	}

	private void renderHealthBar(GuiGraphics g) {
		CompoundTag nbt = getSelectedNbt();
		if (nbt == null) return;
		float currentHealth = nbt.contains("Health") ? nbt.getFloat("Health") : 0;
		float maxHealth = nbt.contains("MaxHealth") ? nbt.getFloat("MaxHealth") : 0;

		// If MaxHealth is missing or zero, try to recover from the vanilla
		// Attributes list.  Prefer the top-level MaxHealth (written by
		// savePetData from getAttributeValue) over Attributes.Base, because
		// Base holds the untamed base value (e.g. 20 for a 40-HP tamed wolf).
		if (maxHealth <= 0 && nbt.contains("Attributes")) {
			for (Tag tag : nbt.getList("Attributes", 10)) {
				CompoundTag attr = (CompoundTag) tag;
				if ("minecraft:generic.max_health".equals(attr.getString("Name"))) {
					maxHealth = attr.getFloat("Base");
					break;
				}
			}
		}

		// Guard against missing / zero-max-health edge cases (modded creatures
		// that don't register MAX_HEALTH, or NBT from older mod versions).
		if (maxHealth <= 0) maxHealth = 20;
		if (currentHealth < 0) currentHealth = 0;

		// Clamp ratio to [0, 1] so the bar never overflows its background.
		float healthRatio = Mth.clamp(currentHealth / maxHealth, 0f, 1f);

		int hx = this.leftPos + HEART_X;
		int hy = this.topPos + HEART_Y;

		g.blit(ICONS_TEXTURE, hx, hy, 16, 0, 9, 9, 256, 256);
		if (currentHealth > 0) g.blit(ICONS_TEXTURE, hx, hy, 52, 0, 9, 9, 256, 256);

		int bx = hx + 11;
		int by = hy + 2;
		int midW = BAR_MIDDLE_WIDTH;
		int srcW = 172;
		int total = 5 + midW + 5;

		g.blit(BARS_TEXTURE, bx, by, 5, 5, 0, 20, 5, 5, 256, 256);
		tileBlitH(g, BARS_TEXTURE, bx + 5, by, midW, 5, 5, 20, srcW, 5, 256, 256);
		g.blit(BARS_TEXTURE, bx + 5 + midW, by, 5, 5, 177, 20, 5, 5, 256, 256);

		int filled = Math.max(0, (int) (total * healthRatio));
		if (filled <= 0) return;

		int left = Math.min(5, filled);
		g.blit(BARS_TEXTURE, bx, by, left, 5, 0, 25, 5, 5, 256, 256);

		if (filled > 5) {
			int midFilled = Math.min(midW, filled - 5);
			tileBlitH(g, BARS_TEXTURE, bx + 5, by, midFilled, 5, 5, 25, srcW, 5, 256, 256);
		}

		int right = filled - 5 - midW;
		if (right > 0) {
			g.blit(BARS_TEXTURE, bx + 5 + midW, by, right, 5, 177, 25, 5, 5, 256, 256);
		}
	}

	private void renderPetInfo(GuiGraphics g) {
		CompoundTag nbt = getSelectedNbt();

		int lx = this.leftPos + HEART_X;
		UUID uuid = getSelectedUuid();
		int scissorWidth = this.leftPos + this.imageWidth - lx - 4;

		// Name: prefix fixed, only the name suffix scrolls when it exceeds 50px
		Component namePrefix = Component.translatable("trulybestfriends.info.name", Component.literal(""));
		Component nameSuffix = getPetDisplayName(uuid);
		int namePrefixW = this.font().width(namePrefix);
		int nameSuffixW = this.font().width(nameSuffix);
		int nameSuffixMaxW = 50;
		int nameY = this.topPos + NAME_Y;
		int nameSuffixX = lx + namePrefixW;
		drawString(g, namePrefix, lx, nameY, 0x000000);
		if (nameSuffixW <= nameSuffixMaxW) {
			drawString(g, nameSuffix, nameSuffixX, nameY, 0x000000);
		} else {
			// Scroll the name within [nameSuffixX, nameSuffixX + 50]
			long t = System.currentTimeMillis();
			int overflow = nameSuffixW - nameSuffixMaxW + 12;
			int cycle = 8000;
			long phase = t % cycle;
			int scroll;
			if (phase < 2000) {
				scroll = 0;
			} else if (phase < 6000) {
				scroll = (int) (overflow * (phase - 2000) / 4000f);
			} else {
				scroll = overflow;
			}
			g.flush();
			g.enableScissor(nameSuffixX, nameY, nameSuffixX + nameSuffixMaxW, nameY + 10);
			drawString(g, nameSuffix, nameSuffixX - scroll, nameY, 0x000000);
			g.flush();
			g.disableScissor();
		}

		// Species
		Component speciesName;
		if (nbt != null) {
			String typeKey = nbt.getString("EntityType");
			if (!typeKey.isEmpty()) {
				var entityType = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(typeKey));
				speciesName = entityType != null ? entityType.getDescription() : Component.literal(typeKey);
			} else {
				speciesName = Component.translatable("trulybestfriends.location.unknown");
			}
		} else {
			speciesName = Component.translatable("trulybestfriends.location.unknown");
		}
		Component speciesLabel = Component.translatable("trulybestfriends.info.species", speciesName);
		int speciesPrefixW = this.font().width(Component.translatable("trulybestfriends.info.species", Component.literal("")));
		int speciesMaxW = speciesPrefixW + 55;
		int speciesY = this.topPos + SPECIES_Y;
		g.enableScissor(lx, speciesY, lx + scissorWidth, speciesY + 10);
		drawScrollingString(g, speciesLabel, lx, speciesY, speciesMaxW, 0x000000);
		g.disableScissor();
	}

	private void renderPetLocation(GuiGraphics g, int mouseX, int mouseY) {
		CompoundTag nbt = getSelectedNbt();
		if (nbt == null) return;

		int lx = this.leftPos + HEART_X;
		int ly = this.topPos + LOCATION_Y;

		// Dead pet: show revive info with item icon
		if (nbt.contains("Health") && nbt.getFloat("Health") <= 0) {
			coordsHovered = false;
			tpDimKey = null;
			// Whitelisted entity types cannot be revived: show warning instead of item icon
			if (nbt.contains("EntityType") && Config.isNoReviveEntity(nbt.getString("EntityType"))) {
				Component warning = Component.translatable("trulybestfriends.revive.not_revivable")
						.withStyle(net.minecraft.ChatFormatting.RED);
				int infoRight = this.leftPos + LIST_PANEL_OFFSET_X - 4;
				int maxW = Math.min(infoRight - lx, 72);
				for (var line : font().split(warning, maxW)) {
					g.drawString(font(), line, lx, ly, 0xFF0000);
					ly += font().lineHeight;
				}
				return;
			}
			var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(Config.reviveItem));
			if (item != null) {
				Component prefixText = Component.translatable("trulybestfriends.revive.prefix");
				drawString(g, prefixText, lx, ly, 0x000000);
				int itemX = lx + font().width(prefixText) + 2;
				ItemStack icon = new ItemStack(item, Config.reviveItemCount);
				g.renderItem(icon, itemX, ly - 2);
				g.renderItemDecorations(font(), icon, itemX, ly - 2);
				Component suffixText = Component.translatable("trulybestfriends.revive.suffix");
				drawString(g, suffixText, itemX + 20, ly, 0x000000);
			}
			return;
		}

		if (!nbt.contains("Pos")) return;

		UUID uuid = getSelectedUuid();
		if (uuid != null && isPetOnShoulder(uuid)) {
			coordsHovered = false;
			tpDimKey = null;
			drawString(g, Component.translatable("trulybestfriends.shoulder.on_shoulder"),
					this.leftPos + HEART_X, this.topPos + LOCATION_Y, 0x000000);
			return;
		}

		var pos = nbt.getList("Pos", 6);
		if (pos.size() < 3) return;

		int x = (int) Math.round(pos.getDouble(0));
		int y = (int) Math.round(pos.getDouble(1));
		int z = (int) Math.round(pos.getDouble(2));
		String dimKey = nbt.contains("Dimension") ? nbt.getString("Dimension") : "";
		boolean isRecalled = nbt.getBoolean("Recalled");
		int infoRight = this.leftPos + LIST_PANEL_OFFSET_X - 4;
		int maxTextWidth = infoRight - lx;

		// Line 1 (ly): world name, or "已收回" for recalled pets
		if (isRecalled) {
			coordsHovered = false;
			tpDimKey = null;
			drawString(g, Component.translatable("trulybestfriends.coords.recalled"), lx, ly, 0xAA5555);
		} else {
			Component dimText;
			if (!dimKey.isEmpty()) {
				String name = Config.getDimensionDisplayName(dimKey);
				dimText = Component.literal(name != null ? name : dimKey);
			} else {
				dimText = Component.translatable("trulybestfriends.location.unknown");
			}
			g.enableScissor(lx, ly, lx + maxTextWidth, ly + 10);
			drawScrollingString(g, dimText, lx, ly, maxTextWidth, 0x000000);
			g.disableScissor();
		}

		// Line 2 (ly + 10): timed warning or coordinates
		if (warningText != null && System.currentTimeMillis() < warningUntil
				&& warningUuid != null && warningUuid.equals(getSelectedUuid())) {
			int wrapWidth = Math.min(maxTextWidth, 72);
			var lines = font().split(warningText, wrapWidth);
			int warnColor = isRecalled ? 0xFFFF55 : 0xFF5555;
			int lineY = ly + 10;
			int totalHeight = lines.size() * 10;
			g.enableScissor(lx, ly + 10, lx + maxTextWidth, ly + 10 + totalHeight);
			for (var line : lines) {
				font().drawInBatch(line, lx, lineY, warnColor, false,
						g.pose().last().pose(), g.bufferSource(),
						net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
				lineY += 10;
			}
			g.disableScissor();
			return;
		}

		if (isRecalled) return;

		String coordStr = x + " " + y + " " + z;
		boolean canTp = !dimKey.isEmpty() && minecraft.player != null && minecraft.player.isCreative();
		coordsHovered = canTp && mouseX >= lx && mouseX <= lx + font().width(coordStr)
				&& mouseY >= ly + 10 && mouseY <= ly + 20;
		int color = coordsHovered ? 0xFFAA00 : 0x000000;
		drawString(g, Component.literal(coordStr), lx, ly + 10, color);

		tpDimKey = dimKey;
		tpX = x; tpY = y; tpZ = z;

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

	// ============================
	//        MOUSE INPUT
	// ============================

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (speciesFilterButton != null && speciesFilterButton.mouseScrolled(mouseX, mouseY, delta)) {
			return true;
		}
		if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
			areaRecallRange = Mth.clamp(areaRecallRange + (delta > 0 ? 1 : -1), 1, 16);
			return true;
		}
		if (hasSelection() && isOverEntityPreview(mouseX, mouseY)) {
			// Proportional zoom: each notch multiplies/divides by a fixed
			// factor so the relative change per step is constant regardless
			// of the current scale.  Bounds are proportional to the current
			// entity's auto-computed referenceScale, so a huge dragon (small
			// referenceScale) and a tiny pet (large referenceScale) each get
			// the same relative zoom range instead of sharing fixed numbers.
			float factor = delta > 0 ? 1.1f : (1f / 1.1f);
			currentScale = Mth.clamp(currentScale * factor, referenceScale * 0.5f, referenceScale * 2.0f);
			return true;
		}
		if (petUuids.size() > MAX_VISIBLE && isOverList(mouseX, mouseY)) {
			if (delta > 0) scrollOffset = Math.max(0, scrollOffset - COLUMNS);
			else { scrollOffset += COLUMNS; snapScrollOffset(); }
			rebuildPetWidgets();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (speciesFilterButton != null && speciesFilterButton.mouseClicked(mx, my, button)) return true;
		if (button == 0 && hasSelection() && isOverEntityPreview(mx, my)) {
			isDraggingEntity = true;
			return true;
		}
		if (button == 0 && petUuids.size() > MAX_VISIBLE && clickScrollbar(mx, my)) return true;
		if (button == 0 && coordsHovered && tpDimKey != null && !tpDimKey.isEmpty()) {
			trulybestfriends.CHANNEL.sendToServer(new com.whidte.trulybestfriends.network.TeleportToPetPacket(
					tpDimKey, tpX, tpY, tpZ));
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
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
		if (speciesFilterButton != null && speciesFilterButton.mouseReleased(mx, my, button)) return true;
		if (button == 0) {
			if (isDraggingEntity) { isDraggingEntity = false; return true; }
			if (isDraggingScrollbar) { isDraggingScrollbar = false; return true; }
		}
		return super.mouseReleased(mx, my, button);
	}

	private boolean isOverEntityPreview(double mx, double my) {
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
		int barX = this.leftPos + this.imageWidth - SCROLLBAR_RIGHT_OFFSET;
		int barY = this.topPos + LIST_PANEL_OFFSET_Y;
		int barH = LIST_PANEL_HEIGHT;
		if (mx < barX || mx > barX + SCROLLBAR_WIDTH || my < barY || my > barY + barH) return false;

		float ratio = (float) MAX_VISIBLE / petUuids.size();
		int thumbH = Math.max(8, (int) (barH * ratio));
		float scrollRatio = (float) scrollOffset / Math.max(1, getMaxScrollOffset());
		int thumbY = barY + (int) ((barH - thumbH) * scrollRatio);

		if (my >= thumbY && my <= thumbY + thumbH) {
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
		float ratio = (float) MAX_VISIBLE / petUuids.size();
		int thumbH = Math.max(8, (int) (barH * ratio));
		int maxThumbY = barH - thumbH;
		if (maxThumbY > 0) {
			float progress = Mth.clamp(((float) my - barY - thumbH / 2f) / maxThumbY, 0f, 1f);
			scrollOffset = Math.round(progress * getMaxScrollOffset());
			snapScrollOffset();
			rebuildPetWidgets();
		}
	}

	// ============================
	//        MISC
	// ============================

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
