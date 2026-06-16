package com.whidte.trulybestfriends.tab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import dev.xkmc.l2tabs.tabs.contents.BaseTextScreen;
import dev.xkmc.l2tabs.tabs.core.TabManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Quaternionf;

public class TrulyScreen extends BaseTextScreen {

	private static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/empty.png");

	// --- Constants ---
	private static final int MAX_VISIBLE = 8;
	private static final int COLUMNS = 2;
	private static final int ENTRY_WIDTH = 40;
	private static final int ENTRY_HEIGHT = 37;
	private static final int ENTRY_GAP_X = 2;
	private static final int LIST_PANEL_OFFSET_X = 86;
	private static final int LIST_PANEL_OFFSET_Y = 10;
	private static final int LIST_PANEL_HEIGHT = (MAX_VISIBLE / COLUMNS) * ENTRY_HEIGHT;
	private static final int LIST_PANEL_WIDTH = COLUMNS * (ENTRY_WIDTH + ENTRY_GAP_X) - ENTRY_GAP_X + 4;
	private static final int SCROLLBAR_RIGHT_OFFSET = 8;
	private static final int SCROLLBAR_WIDTH = 4;
	private static final int ENTITY_PREVIEW_OFFSET_X = 35;
	private static final int ENTITY_PREVIEW_OFFSET_Y = 50;
	private static final int GLOW_X = 61;
	private static final int GLOW_Y = 13;
	private static final int ACTION_X = 61;
	private static final int ACTION_Y = 37;
	private static final int HEART_X = 7;
	private static final int HEART_Y = 62;
	private static final int BAR_MIDDLE_WIDTH = 50;
	private static final int NAME_Y = 74;
	private static final int SPECIES_Y = 84;
	private static final int LOCATION_Y = 98;
	static final float BASE_SCALE = 17f;
	static final float DEFAULT_ROT_X = -37f;
	static final float DEFAULT_ROT_Y = -73f;
	static final float HORSE_MAX_DIM = 1.6f;
	private static final float BASE_DRAG_SENSITIVITY = 0.25f;
	private static final int REFERENCE_WINDOW_WIDTH = 1920;
	private static final int REFRESH_INTERVAL = 20;
	static final int GLOW_BUTTON_SIZE = 18;
	private static final int SUMMON_TO_PLAYER_X = 7;
	private static final int SUMMON_TO_PLAYER_Y = 124;
	private static final int SUMMON_TO_PLAYER_W = 60;

	// --- Textures ---
	static final ResourceLocation GLOWING_ICON = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/mob_effect/glowing.png");
	static final ResourceLocation WIDGET_BUTTON = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/widget_button.png");
	static final ResourceLocation WIDGETS_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/widgets.png");
	private static final ResourceLocation ICONS_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/icons.png");
	private static final ResourceLocation BARS_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/bars.png");

	// --- State ---
	List<UUID> petUuids = new ArrayList<>();
	Map<UUID, CompoundTag> petNbtCache = new LinkedHashMap<>();
	Map<UUID, Integer> petPriorities = new LinkedHashMap<>();
	Map<UUID, Long> cooldowns = new java.util.HashMap<>();
	int selectedPetIndex = -1;
	int scrollOffset = 0;
	float currentScale = 17;
	float rotX = DEFAULT_ROT_X;
	float rotY = DEFAULT_ROT_Y;
	boolean isDraggingEntity = false;
	boolean isDraggingScrollbar = false;
	boolean sortNeeded = false;
	int tickCounter = 0;
	long lastGlowClickTime = 0;
	GlowButton glowButton;
	ActionButton actionButton;
	SummonToPlayerButton summonToPlayerButton;
	String tpDimKey;
	int tpX, tpY, tpZ;
	boolean coordsHovered;

	// --- Constructor ---
	public TrulyScreen(Component title) {
		super(title, TEXTURE);
		this.imageWidth = 176;
		this.imageHeight = 166;
	}

	net.minecraft.client.gui.Font font() {
		return this.font;
	}

	// === Selection helpers ===

	UUID getSelectedUuid() {
		if (selectedPetIndex < 0 || selectedPetIndex >= petUuids.size()) return null;
		return petUuids.get(selectedPetIndex);
	}

	CompoundTag getSelectedNbt() {
		UUID uuid = getSelectedUuid();
		return uuid != null ? petNbtCache.get(uuid) : null;
	}

	boolean hasSelection() {
		return getSelectedUuid() != null;
	}

	/** Create a temporary client-side LivingEntity from the selected NBT. Caller must discard when done. */
	LivingEntity createPreviewEntity() {
		CompoundTag nbt = getSelectedNbt();
		if (nbt == null || getMinecraft().level == null) return null;
		String typeKey = nbt.getString("EntityType");
		if (typeKey.isEmpty()) return null;
		EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(typeKey));
		if (type == null) return null;
		Entity entity = type.create(getMinecraft().level);
		if (entity == null) return null;
		try { entity.load(nbt); } catch (Exception e) { return null; }
		return entity instanceof LivingEntity le ? le : null;
	}

	/** Discard a preview entity from the client world. */
	static void discardPreviewEntity(LivingEntity entity) {
		if (entity != null && entity.isAlive()) {
			entity.discard();
		}
	}

	// === Lifecycle ===

	@Override
	public void init() {
		super.init();
		TabManager manager = new TabManager(this);
		manager.init(this::addRenderableWidget, trulybestfriends.TRULY_TAB);

		saveSelectionThenReload();
		addButtons();
	}

	@Override
	public void removed() {
		// Clean up any preview entities left behind from PetEntry renders
		super.removed();
	}

	private void saveSelectionThenReload() {
		UUID selectedUuid = getSelectedUuid();

		petUuids.clear();
		petNbtCache.clear();
		petPriorities.clear();
		selectedPetIndex = -1;
		scrollOffset = 0;

		PetDataLoader.loadAll(getMinecraft(), petNbtCache, petPriorities);
		petUuids.addAll(petNbtCache.keySet());
		sortPetUuids();
		rebuildPetWidgets();

		if (selectedUuid != null) {
			restoreSelection(selectedUuid);
		}
		if (!hasSelection() && !petUuids.isEmpty()) {
			selectedPetIndex = 0;
		}
		if (hasSelection()) {
			adjustScaleForCurrentPet();
		}
	}

	private void restoreSelection(UUID uuid) {
		selectedPetIndex = petUuids.indexOf(uuid);
		if (selectedPetIndex >= 0) {
			scrollOffset = (selectedPetIndex / COLUMNS) * COLUMNS;
			snapScrollOffset();
			rebuildPetWidgets();
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
		UUID selUuid = getSelectedUuid();
		if (selUuid == null) return;

		Minecraft mc = getMinecraft();
		if (mc.player == null || mc.level == null) return;

		Path petDir = PetDataLoader.getPetSaveDir(mc);
		if (petDir == null || !Files.exists(petDir)) return;

		java.io.File nbtFile = petDir.resolve(selUuid + ".nbt").toFile();
		if (!nbtFile.exists()) {
			// File was deleted — remove from lists
			petUuids.remove(selUuid);
			petNbtCache.remove(selUuid);
			petPriorities.remove(selUuid);
			selectedPetIndex = -1;
			if (!petUuids.isEmpty()) selectedPetIndex = 0;
			boolean has = hasSelection();
			if (glowButton != null) glowButton.visible = has;
			if (actionButton != null) actionButton.visible = has;
			if (summonToPlayerButton != null) summonToPlayerButton.visible = has;
			sortPetUuids();
			rebuildPetWidgets();
			return;
		}

		try {
			CompoundTag nbt = NbtIo.readCompressed(nbtFile);
			petNbtCache.put(selUuid, nbt);

			int priority = nbt.contains("Priority") ? nbt.getInt("Priority") : 6;
			priority = Math.max(1, Math.min(6, priority));
			Integer old = petPriorities.put(selUuid, priority);
			if (old == null || old != priority) {
				sortPetUuids();
				rebuildPetWidgets();
			}
		} catch (Exception e) {
			trulybestfriends.LOGGER.error("Failed to refresh pet file for {}: {}", selUuid, e.getMessage());
		}
	}

	private void cleanExpiredCooldowns() {
		long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
		cooldowns.values().removeIf(t -> t < cutoff);
	}

	// === Scale ===

	void adjustScaleForCurrentPet() {
		LivingEntity entity = createPreviewEntity();
		if (entity == null) return;
		float maxDim = Math.max(entity.getBbWidth(), entity.getBbHeight());
		this.currentScale = maxDim > 0 ? Mth.clamp(BASE_SCALE * (HORSE_MAX_DIM / maxDim), 5f, 50f) : BASE_SCALE;
		discardPreviewEntity(entity);
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
					listX + col * (ENTRY_WIDTH + ENTRY_GAP_X), listY + row * ENTRY_HEIGHT,
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
			var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(typeKey));
			if (type != null) return type.getDescription();
		}
		return Component.literal("???");
	}

	// ============================
	//        RENDER
	// ============================

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);

		if (sortNeeded && !net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
			sortNeeded = false;
			onShiftReleased();
		}

		renderListBackground(g);
		renderScrollBar(g);

		if (hasSelection()) {
			renderPetPreview(g);
			renderHealthBar(g);
			renderPetInfo(g);
			renderPetLocation(g, mouseX, mouseY);
		}
	}

	private void renderListBackground(GuiGraphics g) {
		int x = this.leftPos + LIST_PANEL_OFFSET_X;
		int y = this.topPos + LIST_PANEL_OFFSET_Y;
		int right = this.leftPos + this.imageWidth - SCROLLBAR_RIGHT_OFFSET;
		g.fill(x, y, right, y + LIST_PANEL_HEIGHT, 0x20000000);
	}

	private void renderScrollBar(GuiGraphics g) {
		if (petUuids.size() <= MAX_VISIBLE) return;

		int barX = this.leftPos + this.imageWidth - SCROLLBAR_RIGHT_OFFSET;
		int barY = this.topPos + LIST_PANEL_OFFSET_Y;
		int barH = LIST_PANEL_HEIGHT;
		int barW = SCROLLBAR_WIDTH;

		g.fill(barX, barY, barX + barW, barY + barH, 0xFF000000);
		g.fill(barX + 1, barY + 1, barX + barW - 1, barY + barH - 1, 0xFF2D2D2D);

		float ratio = (float) MAX_VISIBLE / petUuids.size();
		int thumbH = Math.max(8, (int) (barH * ratio));
		float scrollRatio = (float) scrollOffset / Math.max(1, getMaxScrollOffset());
		int thumbY = barY + (int) ((barH - thumbH) * scrollRatio);

		g.fill(barX, thumbY, barX + barW, thumbY + thumbH, 0xFF000000);
		g.fill(barX, thumbY, barX + barW - 1, thumbY + thumbH - 1, 0xFFFFFFFF);
		g.fill(barX + 1, thumbY + 1, barX + barW - 1, thumbY + thumbH - 1, 0xFF8B8B8B);
	}

	private void renderPetPreview(GuiGraphics g) {
		tpDimKey = null;
		coordsHovered = false;

		LivingEntity entity = createPreviewEntity();
		if (entity == null) return;

		int ex = this.leftPos + ENTITY_PREVIEW_OFFSET_X;
		int ey = this.topPos + ENTITY_PREVIEW_OFFSET_Y;

		Quaternionf quat = (new Quaternionf()).rotateZ((float) Math.PI);
		Quaternionf quatPitch = (new Quaternionf()).rotateX(rotY * 20f * ((float) Math.PI / 180f));
		quat.mul(quatPitch);

		entity.yBodyRot = 180f + rotX * 20f;
		entity.setYRot(180f + rotX * 40f);
		setXRotUnclamped(entity, -rotY * 20f);
		entity.yHeadRot = entity.yBodyRot;
		entity.yHeadRotO = entity.yBodyRot;

		InventoryScreen.renderEntityInInventory(g, ex, ey, (int) currentScale, quat, quatPitch, entity);

		discardPreviewEntity(entity);
	}

	private static void setXRotUnclamped(Entity entity, float value) {
		try {
			var f = Entity.class.getDeclaredField("xRot");
			f.setAccessible(true);
			f.setFloat(entity, value);
		} catch (Exception e) {
			entity.setXRot(value);
		}
	}

	private void renderHealthBar(GuiGraphics g) {
		CompoundTag nbt = getSelectedNbt();
		if (nbt == null) return;
		float currentHealth = nbt.contains("Health") ? nbt.getFloat("Health") : 20;
		float maxHealth = 20;

		if (nbt.contains("Attributes")) {
			for (Tag tag : nbt.getList("Attributes", 10)) {
				CompoundTag attr = (CompoundTag) tag;
				if ("minecraft:generic.max_health".equals(attr.getString("Name"))) {
					maxHealth = attr.getFloat("Base");
					break;
				}
			}
		}
		float healthRatio = Math.max(0, currentHealth / maxHealth);

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
		if (nbt == null) return;

		int lx = this.leftPos + HEART_X;
		UUID uuid = getSelectedUuid();
		int scissorWidth = this.leftPos + this.imageWidth - lx - 4;

		// Name (scrolls when name part exceeds ~5 Chinese chars)
		Component nameLabel = Component.translatable("trulybestfriends.info.name", getPetDisplayName(uuid));
		int labelPrefixW = this.font().width(Component.translatable("trulybestfriends.info.name", Component.literal("")));
		int nameMaxW = labelPrefixW + 60; // ~5 full-width Chinese characters
		int nameY = this.topPos + NAME_Y;
		g.enableScissor(lx, nameY, lx + scissorWidth, nameY + 10);
		drawScrollingString(g, nameLabel, lx, nameY, nameMaxW, 0x000000);
		g.disableScissor();

		// Species
		String typeKey = nbt.getString("EntityType");
		Component speciesName;
		if (!typeKey.isEmpty()) {
			var entityType = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(typeKey));
			speciesName = entityType != null ? entityType.getDescription() : Component.literal(typeKey);
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
		if (nbt == null || !nbt.contains("Pos")) return;
		if (nbt.contains("Health") && nbt.getFloat("Health") <= 0) return;

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

		int lx = this.leftPos + HEART_X;
		int ly = this.topPos + LOCATION_Y;
		int x = (int) Math.round(pos.getDouble(0));
		int y = (int) Math.round(pos.getDouble(1));
		int z = (int) Math.round(pos.getDouble(2));
		String dimKey = nbt.contains("Dimension") ? nbt.getString("Dimension") : "";
		boolean isRecalled = nbt.getBoolean("Recalled");

		Component dimText;
		if (!dimKey.isEmpty()) {
			String name = Config.getDimensionDisplayName(dimKey);
			dimText = Component.literal(name != null ? name : dimKey);
		} else {
			dimText = Component.translatable("trulybestfriends.location.unknown");
		}
		int maxTextWidth = this.leftPos + this.imageWidth - lx - 4;
		g.enableScissor(lx, ly, lx + maxTextWidth, ly + 10);
		drawString(g, dimText, lx, ly, 0x000000);
		g.disableScissor();

		if (isRecalled) {
			coordsHovered = false;
			tpDimKey = null;
			drawString(g, Component.translatable("trulybestfriends.coords.recalled"), lx, ly + 10, 0xAA5555);
			return;
		}

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
		this.font().drawInBatch(text.getVisualOrderText(), x, y, color, false,
				g.pose().last().pose(), g.bufferSource(),
				net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
	}

	private void drawScrollingString(GuiGraphics g, Component text, int x, int y, int maxWidth, int color) {
		int textWidth = this.font().width(text);
		if (textWidth <= maxWidth) {
			drawString(g, text, x, y, color);
			return;
		}
		long time = System.currentTimeMillis();
		int overflow = textWidth - maxWidth + 12;
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
		drawString(g, text, x - scrollOffset, y, color);
	}

	// ============================
	//        MOUSE INPUT
	// ============================

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (hasSelection() && isOverEntityPreview(mouseX, mouseY)) {
			currentScale = Mth.clamp(currentScale + (delta > 0 ? 1 : -1), 5, 50);
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

	private static void tileBlitH(GuiGraphics g, ResourceLocation tex, int x, int y, int drawW, int drawH,
	                              int u, int v, int srcW, int srcH, int texW, int texH) {
		int drawn = 0;
		while (drawn < drawW) {
			int chunk = Math.min(srcW, drawW - drawn);
			g.blit(tex, x + drawn, y, chunk, drawH, u, v, chunk, srcH, texW, texH);
			drawn += chunk;
		}
	}
}
