package com.whidte.trulybestfriends.tab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.whidte.trulybestfriends.network.GlowPetPacket;
import com.whidte.trulybestfriends.trulybestfriends;
import dev.xkmc.l2tabs.tabs.contents.BaseTextScreen;
import dev.xkmc.l2tabs.tabs.core.TabManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Quaternionf;

public class TrulyScreen extends BaseTextScreen {

	private static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/empty.png");

	private List<LivingEntity> pets = new ArrayList<>();
	private Map<UUID, CompoundTag> petNbtCache = new LinkedHashMap<>();
	private LivingEntity selectedPet;
	private int selectedPetIndex = -1;
	private int scrollOffset = 0;
	private float currentScale = 17;
	private float rotX = DEFAULT_ROT_X;
	private float rotY = DEFAULT_ROT_Y;
	private boolean isDraggingEntity = false;
	private boolean isDraggingScrollbar = false;
	private long lastGlowClickTime = 0;
	private boolean isGlowButtonPressed = false;
	private static final int MAX_VISIBLE = 8;
	private static final int COLUMNS = 2;
	private static final int ENTRY_WIDTH = 40;
	private static final int ENTRY_HEIGHT = 37;
	private static final int ENTRY_GAP_X = 2;
	private static final int LIST_PANEL_OFFSET_X = 86;
	private static final int LIST_PANEL_OFFSET_Y = 10;
	private static final int LIST_PANEL_HEIGHT = (MAX_VISIBLE / COLUMNS) * ENTRY_HEIGHT; // 200
	private static final int LIST_PANEL_WIDTH = COLUMNS * (ENTRY_WIDTH + ENTRY_GAP_X) - ENTRY_GAP_X + 4; // 86
	private static final int SCROLLBAR_RIGHT_OFFSET = 8;
	private static final int SCROLLBAR_WIDTH = 4;
	private static final int ENTITY_PREVIEW_OFFSET_X = 35;
	private static final int ENTITY_PREVIEW_OFFSET_Y = 50;
	private static final ResourceLocation GLOWING_ICON = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/mob_effect/glowing.png");
	private static final ResourceLocation WIDGET_BUTTON = ResourceLocation.fromNamespaceAndPath("truly_best_friends", "textures/gui/widget_button.png");
	private static final int GLOW_BUTTON_SIZE = 18;
	private static final int GLOW_BUTTON_OFFSET_X = 62;
	private static final int GLOW_BUTTON_OFFSET_Y = -14;
	private static final float BASE_SCALE = 17f;
	private static final float DEFAULT_ROT_X = -37f;
	private static final float DEFAULT_ROT_Y = -73f;
	private static final float HORSE_MAX_DIM = 1.6f;
	private static final float BASE_DRAG_SENSITIVITY = 0.25f;
	private static final int REFERENCE_WINDOW_WIDTH = 1920;

	public TrulyScreen(Component title) {
		super(title, TEXTURE);
		this.imageWidth = 176;
		this.imageHeight = 166;
	}

	@Override
	public void init() {
		super.init();
		TabManager manager = new TabManager(this);
		manager.init(this::addRenderableWidget, trulybestfriends.TRULY_TAB);

		// Save selected pet UUID before clearing
		UUID selectedUuid = selectedPet != null ? selectedPet.getUUID() : null;

		pets.clear();
		selectedPet = null;
		selectedPetIndex = -1;
		scrollOffset = 0;

		loadPetsFromSave();

		// Restore selection after reload
		if (selectedUuid != null) {
			for (int i = 0; i < pets.size(); i++) {
				if (pets.get(i).getUUID().equals(selectedUuid)) {
					selectedPet = pets.get(i);
					selectedPetIndex = i;
					// Ensure scroll offset shows the selected pet
					scrollOffset = (i / COLUMNS) * COLUMNS;
					snapScrollOffset();
					break;
				}
			}
		}

		// Auto-select first pet if no selection
		if (selectedPet == null && !pets.isEmpty()) {
			selectedPet = pets.get(0);
			selectedPetIndex = 0;
			adjustScaleForPet(selectedPet);
		}

		refreshPetList();
	}

	private void loadPetsFromSave() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;

		pets.clear();
		petNbtCache.clear();

		try {
			Path petDir = getPetSaveDir(mc);
			if (petDir != null && Files.exists(petDir)) {
				Files.list(petDir).filter(p -> p.toString().endsWith(".nbt")).forEach(file -> {
					try {
						CompoundTag nbt = NbtIo.readCompressed(file.toFile());
						String uuidStr = file.getFileName().toString().replace(".nbt", "");
						UUID uuid = UUID.fromString(uuidStr);
						petNbtCache.put(uuid, nbt);

						LivingEntity entity = createEntityFromNbt(mc, nbt);
						if (entity != null) {
							pets.add(entity);
						}
					} catch (Exception e) {
						trulybestfriends.LOGGER.error("Failed to read pet file: {}", file);
					}
				});
			}
		} catch (IOException e) {
			trulybestfriends.LOGGER.error("Failed to list pet files", e);
		}
	}

	private Path getPetSaveDir(Minecraft mc) {
		// Singleplayer: read from world save directory
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			Path worldPath = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
			return worldPath.resolve("trulybestfriends").resolve(mc.player.getUUID().toString());
		}
		// Multiplayer: fallback to local game directory
		// The server sends data to the client, but for now try local path
		Path gameDir = mc.gameDirectory.toPath();
		// Try to find the world save
		Path savesDir = gameDir.resolve("saves");
		if (Files.exists(savesDir)) {
			try {
				for (File worldDir : savesDir.toFile().listFiles(File::isDirectory)) {
					Path petDir = worldDir.toPath().resolve("trulybestfriends").resolve(mc.player.getUUID().toString());
					if (Files.exists(petDir)) {
						return petDir;
					}
				}
			} catch (Exception ignored) {}
		}
		return null;
	}

	private LivingEntity createEntityFromNbt(Minecraft mc, CompoundTag nbt) {
		if (mc.level == null) return null;

		String typeKey = nbt.getString("EntityType");
		if (typeKey.isEmpty()) return null;

		EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(typeKey));
		if (type == null) return null;

		Entity entity = type.create(mc.level);
		if (entity == null) return null;

		try {
			entity.load(nbt);
		} catch (Exception e) {
			trulybestfriends.LOGGER.error("Failed to load entity from NBT: {}", e.getMessage());
			return null;
		}

		if (entity instanceof LivingEntity living) {
			return living;
		}
		return null;
	}

	private void adjustScaleForPet(LivingEntity pet) {
		float maxDim = Math.max(pet.getBbWidth(), pet.getBbHeight());
		this.currentScale = maxDim > 0 ? Mth.clamp(BASE_SCALE * (HORSE_MAX_DIM / maxDim), 5f, 50f) : BASE_SCALE;
	}

	private int getMaxScrollOffset() {
		return Math.max(0, (pets.size() - 1) / COLUMNS - (MAX_VISIBLE / COLUMNS - 1)) * COLUMNS;
	}

	private void snapScrollOffset() {
		scrollOffset = (scrollOffset / COLUMNS) * COLUMNS;
		scrollOffset = Mth.clamp(scrollOffset, 0, getMaxScrollOffset());
	}

	private void refreshPetList() {
		// Remove old pet entries
		var toRemove = new ArrayList<GuiEventListener>();
		for (var child : this.children()) {
			if (child instanceof PetEntry) {
				toRemove.add(child);
			}
		}
		toRemove.forEach(this::removeWidget);

		int listX = this.leftPos + LIST_PANEL_OFFSET_X;
		int listY = this.topPos + LIST_PANEL_OFFSET_Y;

		int endIdx = Math.min(scrollOffset + MAX_VISIBLE, pets.size());
		for (int i = scrollOffset; i < endIdx; i++) {
			int pos = i - scrollOffset;
			int col = pos % COLUMNS;
			int row = pos / COLUMNS;
			LivingEntity pet = pets.get(i);
			Component name = pet.hasCustomName() ? pet.getCustomName() : pet.getType().getDescription();
			this.addRenderableWidget(new PetEntry(
					listX + col * (ENTRY_WIDTH + ENTRY_GAP_X), listY + row * ENTRY_HEIGHT,
					ENTRY_WIDTH, ENTRY_HEIGHT, name, i, this));
		}
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		// Darken right side for depth separation
		int darkX = this.leftPos + LIST_PANEL_OFFSET_X;
		int darkY = this.topPos + LIST_PANEL_OFFSET_Y;
		int darkHeight = LIST_PANEL_HEIGHT;
		guiGraphics.fill(darkX, darkY, this.leftPos + this.imageWidth - SCROLLBAR_RIGHT_OFFSET, darkY + darkHeight, 0x20000000);

		guiGraphics.drawString(this.font, this.title, this.leftPos + 8, this.topPos + 8, 4210752, false);

		// Render scroll bar on the right side of the pet list
		if (pets.size() > MAX_VISIBLE) {
			int barX = this.leftPos + this.imageWidth - SCROLLBAR_RIGHT_OFFSET;
			int barY = this.topPos + LIST_PANEL_OFFSET_Y;
			int barHeight = LIST_PANEL_HEIGHT;
			int barWidth = SCROLLBAR_WIDTH;

			// Scroll bar track with 3D groove effect
			guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF000000);
			guiGraphics.fill(barX + 1, barY + 1, barX + barWidth - 1, barY + barHeight - 1, 0xFF2D2D2D);

			// Scroll bar thumb with 3D beveled button effect
			float ratio = (float) MAX_VISIBLE / pets.size();
			int thumbHeight = Math.max(8, (int) (barHeight * ratio));
			float scrollRatio = (float) scrollOffset / Math.max(1, getMaxScrollOffset());
			int thumbY = barY + (int) ((barHeight - thumbHeight) * scrollRatio);

			// Full thumb shadow
			guiGraphics.fill(barX, thumbY, barX + barWidth, thumbY + thumbHeight, 0xFF000000);
			// Top-left highlight
			guiGraphics.fill(barX, thumbY, barX + barWidth - 1, thumbY + thumbHeight - 1, 0xFFFFFFFF);
			// Thumb body
			guiGraphics.fill(barX + 1, thumbY + 1, barX + barWidth - 1, thumbY + thumbHeight - 1, 0xFF8B8B8B);
		}

		// Render selected pet, matching horse inventory GUI style
		// Position and scale match AbstractHorseScreen, but with fixed angles
		if (selectedPet != null) {
			int entityX = this.leftPos + ENTITY_PREVIEW_OFFSET_X;
			int entityY = this.topPos + ENTITY_PREVIEW_OFFSET_Y;

			// Build quaternion for pitch rotation (same as InventoryScreen internal logic)
			Quaternionf quat = (new Quaternionf()).rotateZ((float) Math.PI);
			Quaternionf quatPitch = (new Quaternionf()).rotateX(rotY * 20.0F * ((float) Math.PI / 180F));
			quat.mul(quatPitch);

			// Save entity rotations
			float savedXRot = selectedPet.getXRot();
			float savedYRot = selectedPet.getYRot();
			float savedYBodyRot = selectedPet.yBodyRot;
			float savedYHeadRot = selectedPet.yHeadRot;
			float savedYHeadRotO = selectedPet.yHeadRotO;

			// Set entity yaw
			selectedPet.yBodyRot = 180.0F + rotX * 20.0F;
			selectedPet.setYRot(180.0F + rotX * 40.0F);
			// Bypass xRot clamp via reflection on official-mapped field
			try {
				var xRotField = Entity.class.getDeclaredField("xRot");
				xRotField.setAccessible(true);
				xRotField.setFloat(selectedPet, -rotY * 20.0F);
			} catch (Exception e) {
				selectedPet.setXRot(-rotY * 20.0F);
			}
			selectedPet.yHeadRot = selectedPet.yBodyRot;
			selectedPet.yHeadRotO = selectedPet.yBodyRot;

			// Render using the 6-param version
			InventoryScreen.renderEntityInInventory(guiGraphics, entityX, entityY, (int) currentScale, quat, quatPitch, selectedPet);

			// Restore rotations
			selectedPet.yBodyRot = savedYBodyRot;
			selectedPet.setYRot(savedYRot);
			try {
				var xRotField = Entity.class.getDeclaredField("xRot");
				xRotField.setAccessible(true);
				xRotField.setFloat(selectedPet, savedXRot);
			} catch (Exception e) {
				selectedPet.setXRot(savedXRot);
			}
			selectedPet.yHeadRot = savedYHeadRot;
			selectedPet.yHeadRotO = savedYHeadRotO;

			// Glowing effect button
			int glowBtnX = this.leftPos + GLOW_BUTTON_OFFSET_X;
			int glowBtnY = this.topPos + ENTITY_PREVIEW_OFFSET_Y + GLOW_BUTTON_OFFSET_Y;
			int frameV = isGlowButtonPressed ? 20 : 0;
			guiGraphics.blit(WIDGET_BUTTON, glowBtnX - 1, glowBtnY - 1, 0, frameV, 20, 20, 256, 256);
			guiGraphics.blit(GLOWING_ICON, glowBtnX, glowBtnY, 0, 0, GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE, GLOW_BUTTON_SIZE);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		// Entity zoom when mouse is over the frame
		if (selectedPet != null) {
			int entityX = this.leftPos + ENTITY_PREVIEW_OFFSET_X;
			int entityY = this.topPos + ENTITY_PREVIEW_OFFSET_Y;
			int boxSize = 50;
			if (mouseX >= entityX - boxSize / 2 && mouseX <= entityX + boxSize / 2
					&& mouseY >= entityY - boxSize + 10 && mouseY <= entityY + 10) {
				if (delta > 0) {
					currentScale = Math.min(50, currentScale + 1);
				} else {
					currentScale = Math.max(5, currentScale - 1);
				}
				return true;
			}
		}

		if (pets.size() > MAX_VISIBLE) {
			int listX = this.leftPos + LIST_PANEL_OFFSET_X;
			int listY = this.topPos + LIST_PANEL_OFFSET_Y;
			int listWidth = LIST_PANEL_WIDTH;
			int listHeight = LIST_PANEL_HEIGHT;

			if (mouseX >= listX && mouseX <= listX + listWidth
					&& mouseY >= listY && mouseY <= listY + listHeight) {
				if (delta > 0) {
					scrollOffset = Math.max(0, scrollOffset - COLUMNS);
				} else {
					scrollOffset = scrollOffset + COLUMNS;
					snapScrollOffset();
				}
				refreshPetList();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && selectedPet != null) {
			// Glowing effect button (checked before entity drag to avoid overlap)
			int glowBtnX = this.leftPos + GLOW_BUTTON_OFFSET_X;
			int glowBtnY = this.topPos + ENTITY_PREVIEW_OFFSET_Y + GLOW_BUTTON_OFFSET_Y;
			if (mouseX >= glowBtnX - 1 && mouseX <= glowBtnX + GLOW_BUTTON_SIZE + 1
					&& mouseY >= glowBtnY - 1 && mouseY <= glowBtnY + GLOW_BUTTON_SIZE + 1) {
				isGlowButtonPressed = true;
				long now = System.currentTimeMillis();
				if (now - lastGlowClickTime >= 3000) {
					lastGlowClickTime = now;
					trulybestfriends.CHANNEL.sendToServer(new GlowPetPacket(selectedPet.getUUID()));
				}
				return true;
			}

			int entityX = this.leftPos + ENTITY_PREVIEW_OFFSET_X;
			int entityY = this.topPos + ENTITY_PREVIEW_OFFSET_Y;
			int boxSize = 50;
			if (mouseX >= entityX - boxSize / 2 && mouseX <= entityX + boxSize / 2
					&& mouseY >= entityY - boxSize + 10 && mouseY <= entityY + 10) {
				isDraggingEntity = true;
				return true;
			}
		}

		// Scrollbar drag detection
		if (button == 0 && pets.size() > MAX_VISIBLE) {
			int barX = this.leftPos + this.imageWidth - SCROLLBAR_RIGHT_OFFSET;
			int barY = this.topPos + LIST_PANEL_OFFSET_Y;
			int barWidth = SCROLLBAR_WIDTH;
			int barHeight = LIST_PANEL_HEIGHT;

			if (mouseX >= barX && mouseX <= barX + barWidth
					&& mouseY >= barY && mouseY <= barY + barHeight) {
				// Calculate thumb position and click on it directly
				float ratio = (float) MAX_VISIBLE / pets.size();
				int thumbHeight = Math.max(8, (int) (barHeight * ratio));
				float scrollRatio = (float) scrollOffset / Math.max(1, getMaxScrollOffset());
				int thumbY = barY + (int) ((barHeight - thumbHeight) * scrollRatio);

				// Check if clicking on the thumb
				if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
					isDraggingScrollbar = true;
					return true;
				}

				// Click on track above/below thumb: jump scroll
				if (mouseY < thumbY) {
					scrollOffset = Math.max(0, scrollOffset - MAX_VISIBLE);
				} else {
					scrollOffset = scrollOffset + MAX_VISIBLE;
				}
				snapScrollOffset();
				refreshPetList();
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (isDraggingScrollbar) {
			int barY = this.topPos + LIST_PANEL_OFFSET_Y;
			int barHeight = LIST_PANEL_HEIGHT;
			float ratio = (float) MAX_VISIBLE / pets.size();
			int thumbHeight = Math.max(8, (int) (barHeight * ratio));
			int maxThumbY = barHeight - thumbHeight;

			if (maxThumbY > 0) {
				float progress = Mth.clamp(((float) mouseY - barY - thumbHeight / 2f) / maxThumbY, 0f, 1f);
				scrollOffset = Math.round(progress * getMaxScrollOffset());
				snapScrollOffset();
				refreshPetList();
			}
			return true;
		}

		if (isDraggingEntity) {
			float sensitivity = -Mth.clamp(BASE_DRAG_SENSITIVITY * ((float) REFERENCE_WINDOW_WIDTH / this.minecraft.getWindow().getWidth()), 0.25f, 0.5f);
			rotX += (float) dragX * sensitivity;
			rotY = Mth.clamp(rotY + (float) dragY * sensitivity, -75f, -71f);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 0) {
			if (isDraggingEntity) {
				isDraggingEntity = false;
				return true;
			}
			if (isDraggingScrollbar) {
				isDraggingScrollbar = false;
				return true;
			}
			isGlowButtonPressed = false;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static class PetEntry extends AbstractWidget {
		private final int index;
		private final TrulyScreen screen;

		public PetEntry(int x, int y, int width, int height, Component message, int index, TrulyScreen screen) {
			super(x, y, width, height, message);
			this.index = index;
			this.screen = screen;
		}

		@Override
		public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
			boolean isSelected = screen.selectedPetIndex == index;

			// Draw selection highlight border
			if (isSelected) {
				guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFFFFFFFF);
				guiGraphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0xFF000000);
			} else {
				guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFF555555);
				guiGraphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0xFF1A1A1A);
			}

			var font = screen.getMinecraft().font;
			LivingEntity pet = screen.pets.get(index);
			int textColor = pet.getHealth() <= 0 ? 0xFF5555 : (isSelected ? 0xFFFFFF : 0xA0A0A0);
			int textY = getY() + height - 9;

			// Render pet preview at top center (28×28 area, default angles, no drag/zoom)
			float maxDim = Math.max(pet.getBbWidth(), pet.getBbHeight());
			float miniScale = maxDim > 0 ? Mth.clamp(BASE_SCALE * (28f / 50f) * (HORSE_MAX_DIM / maxDim), 3f, 28f) : BASE_SCALE * (28f / 50f);

			int miniX = getX() + width / 2;
			int miniY = getY() + (textY - getY()) / 2 + 7;

			Quaternionf quat = (new Quaternionf()).rotateZ((float) Math.PI);
			Quaternionf quatPitch = (new Quaternionf()).rotateX(DEFAULT_ROT_Y * 20.0F * ((float) Math.PI / 180F));
			quat.mul(quatPitch);

			float savedXRot = pet.getXRot();
			float savedYRot = pet.getYRot();
			float savedYBodyRot = pet.yBodyRot;
			float savedYHeadRot = pet.yHeadRot;
			float savedYHeadRotO = pet.yHeadRotO;

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

			pet.yBodyRot = savedYBodyRot;
			pet.setYRot(savedYRot);
			try {
				var xRotField = Entity.class.getDeclaredField("xRot");
				xRotField.setAccessible(true);
				xRotField.setFloat(pet, savedXRot);
			} catch (Exception e) {
				pet.setXRot(savedXRot);
			}
			pet.yHeadRot = savedYHeadRot;
			pet.yHeadRotO = savedYHeadRotO;

			// Draw pet name text - centered horizontally, bottom-aligned
			int textWidth = font.width(getMessage());
			int availableWidth = width - 6; // 3px padding per side

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

		@Override
		public void onClick(double mouseX, double mouseY) {
			screen.selectedPetIndex = index;
			screen.selectedPet = screen.pets.get(index);
			screen.adjustScaleForPet(screen.selectedPet);
			screen.rotX = DEFAULT_ROT_X;
			screen.rotY = DEFAULT_ROT_Y;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput narration) {
			this.defaultButtonNarrationText(narration);
		}
	}
}