package com.whidte.trulybestfriends.tab;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.whidte.trulybestfriends.client.SummonKeyHandler;
import com.whidte.trulybestfriends.network.PetTeamData;
import com.whidte.trulybestfriends.network.SummonTeamPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.whidte.trulybestfriends.tab.RenderHelper.buildMultipartPose;
import static com.whidte.trulybestfriends.tab.RenderHelper.detectMultipartYBase;
import static com.whidte.trulybestfriends.tab.RenderHelper.multipartPitchRadians;
import static com.whidte.trulybestfriends.tab.RenderHelper.renderEntityInInventory;
import static com.whidte.trulybestfriends.tab.TrulyConstants.BASE_SCALE;
import static com.whidte.trulybestfriends.tab.TrulyConstants.DEFAULT_ROT_X;
import static com.whidte.trulybestfriends.tab.TrulyConstants.DEFAULT_ROT_Y;
import static com.whidte.trulybestfriends.tab.TrulyConstants.LIST_ENTRY_SCALE_RATIO;

/** Hold-to-select radial screen for the eight members of the current formation team. */
public final class SummonWheelScreen extends Screen {
    private static final ResourceLocation WHEEL = ResourceLocation.fromNamespaceAndPath(
            "truly_best_friends", "textures/gui/wheel.png");
    private static final ResourceLocation POINTER = ResourceLocation.fromNamespaceAndPath(
            "truly_best_friends", "textures/gui/wheel_pointer.png");
    private static final ResourceLocation IMPERIAL_ORDER = ResourceLocation.fromNamespaceAndPath(
            "truly_best_friends", "textures/gui/imperial_order.png");
    private static final ResourceLocation WORLD_IN_A_BOTTLE = ResourceLocation.fromNamespaceAndPath(
            "truly_best_friends", "textures/gui/world_in_a_bottle.png");
    private static final ResourceLocation RELEASE_BOTTLE = ResourceLocation.fromNamespaceAndPath(
            "truly_best_friends", "textures/gui/release_bottle.png");
    private static final int WHEEL_SIZE = 90;
    private static final int IMPERIAL_ORDER_SIZE = 50;
    private static final int CENTER_BOTTLE_SIZE = 16;
    private static final int SLOT_SIZE = 40;
    private static final int DEAD_ZONE_RADIUS = 18;
    private static final int CENTER_NAME_MAX_WIDTH = 44;
    private static final int[] DIRECTION_SLOTS = {1, 5, 6, 7, 8, 4, 3, 2};
    private static final int[][] SLOT_OFFSETS = {
            {0, -62}, {50, -50}, {62, 0}, {50, 50},
            {0, 62}, {-50, 50}, {-62, 0}, {-50, -50}
    };
    private static final int[][] POINTER_CLIPS = {
            {33, 0, 57, 18}, {57, 15, 76, 32}, {72, 33, 90, 57}, {57, 57, 76, 75},
            {33, 72, 57, 90}, {14, 57, 32, 75}, {0, 33, 18, 57}, {14, 15, 32, 32}
    };
    private static final Quaternionf NORMAL_QUAT = new Quaternionf().rotateZ((float) Math.PI)
            .rotateX(DEFAULT_ROT_Y * 20.0F * ((float) Math.PI / 180F));
    private static final Quaternionf NORMAL_QUAT_PITCH = new Quaternionf()
            .rotateX(DEFAULT_ROT_Y * 20.0F * ((float) Math.PI / 180F));
    private static final Quaternionf MULTIPART_QUAT_PITCH = new Quaternionf()
            .rotateX(-DEFAULT_ROT_Y * 20.0F * ((float) Math.PI / 180F));
    private static final Map<String, Quaternionf> MULTIPART_QUATS = new ConcurrentHashMap<>();

    private final Map<UUID, LivingEntity> previewEntities = new HashMap<>();
    private final KeyMapping[] movementKeys;
    private final boolean[] movementKeysHeldOnOpen;
    private int selectedDirection = -1;
    private boolean completed;

    public SummonWheelScreen() {
        super(Component.translatable("key.trulybestfriends.summon_wheel"));
        Minecraft minecraft = Minecraft.getInstance();
        movementKeys = new KeyMapping[]{
                minecraft.options.keyUp, minecraft.options.keyDown,
                minecraft.options.keyLeft, minecraft.options.keyRight,
                minecraft.options.keyJump
        };
        movementKeysHeldOnOpen = new boolean[movementKeys.length];
        for (int i = 0; i < movementKeys.length; i++) {
            movementKeysHeldOnOpen[i] = movementKeys[i].isDown();
        }
    }

    @Override
    protected void init() {
        super.init();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.BOOK_PAGE_TURN, 1.0F, 1.0F);
        }
        for (int i = 0; i < movementKeys.length; i++) {
            movementKeys[i].setDown(movementKeysHeldOnOpen[i]);
        }
        syncMovementKeys();
    }

    @Override
    public void tick() {
        super.tick();
        syncMovementKeys();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        int centerY = height / 2;
        Map<Integer, UUID> members = SummonWheelData.selectedTeamSlots();
        selectedDirection = directionAt(mouseX - centerX, mouseY - centerY, members);

        for (int direction = 0; direction < DIRECTION_SLOTS.length; direction++) {
            UUID uuid = members.get(DIRECTION_SLOTS[direction]);
            if (uuid == null) continue;
            int slotX = centerX + SLOT_OFFSETS[direction][0] - SLOT_SIZE / 2;
            int slotY = centerY + SLOT_OFFSETS[direction][1] - SLOT_SIZE / 2;
            LivingEntity entity = previewEntity(uuid);
            if (entity != null) {
                if (direction == selectedDirection) {
                    renderSelectedBorder(graphics, slotX, slotY);
                }
                float brightness = direction == selectedDirection ? 1.0F : 0.35F;
                RenderSystem.setShaderColor(brightness, brightness, brightness, 1.0F);
                try {
                    renderPet(graphics, slotX, slotY, entity);
                } finally {
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                }
            }
        }

        int wheelX = centerX - WHEEL_SIZE / 2;
        int wheelY = centerY - WHEEL_SIZE / 2;
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 100.0F);
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            try {
                graphics.blit(IMPERIAL_ORDER,
                        centerX - IMPERIAL_ORDER_SIZE / 2, centerY - IMPERIAL_ORDER_SIZE / 2,
                        0, 0, IMPERIAL_ORDER_SIZE, IMPERIAL_ORDER_SIZE,
                        IMPERIAL_ORDER_SIZE, IMPERIAL_ORDER_SIZE);
            } finally {
                RenderSystem.disableBlend();
            }
            RenderSystem.setShaderColor(0.35F, 0.35F, 0.35F, 1.0F);
            graphics.blit(POINTER, wheelX, wheelY, 0, 0, WHEEL_SIZE, WHEEL_SIZE, WHEEL_SIZE, WHEEL_SIZE);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (selectedDirection >= 0) {
                int[] clip = POINTER_CLIPS[selectedDirection];
                graphics.enableScissor(wheelX + clip[0], wheelY + clip[1], wheelX + clip[2], wheelY + clip[3]);
                try {
                    graphics.blit(POINTER, wheelX, wheelY, 0, 0,
                            WHEEL_SIZE, WHEEL_SIZE, WHEEL_SIZE, WHEEL_SIZE);
                } finally {
                    graphics.disableScissor();
                }
            }
            graphics.blit(WHEEL, wheelX, wheelY, 0, 0, WHEEL_SIZE, WHEEL_SIZE, WHEEL_SIZE, WHEEL_SIZE);
            if (selectedDirection >= 0) {
                UUID selected = members.get(DIRECTION_SLOTS[selectedDirection]);
                if (selected != null) {
                    LivingEntity entity = previewEntity(selected);
                    if (entity != null) {
                        renderPet(graphics, centerX - SLOT_SIZE / 2,
                                centerY - SLOT_SIZE / 2 - 10, entity);
                    }
                    renderCenterName(graphics, petName(selected), centerX, centerY);
                    renderHalfSizeCentered(graphics,
                            Component.translatable("trulybestfriends.wheel.release_summon"),
                            centerX, centerY + 11, 0xAAAAAA);
                    renderHalfSizeCentered(graphics,
                            Component.translatable("trulybestfriends.wheel.cancel"),
                            centerX, centerY + 86, 0xFFFFFF);
                }
            } else {
                boolean bottleHovered = isOverBottle(mouseX, mouseY);
                graphics.blit(bottleHovered ? RELEASE_BOTTLE : WORLD_IN_A_BOTTLE,
                        centerX - CENTER_BOTTLE_SIZE / 2, centerY - CENTER_BOTTLE_SIZE / 2 - 10,
                        0, 0, CENTER_BOTTLE_SIZE, CENTER_BOTTLE_SIZE,
                        CENTER_BOTTLE_SIZE, CENTER_BOTTLE_SIZE);
                renderHalfSizeCentered(graphics,
                        Component.translatable(bottleHovered
                                ? "trulybestfriends.wheel.summon_team"
                                : "trulybestfriends.wheel.select_pet"),
                        centerX, centerY + 11, 0xAAAAAA);
                renderHalfSizeCentered(graphics,
                        Component.translatable("trulybestfriends.wheel.cancel"),
                        centerX, centerY + 86, 0xFFFFFF);
            }
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            graphics.pose().popPose();
        }
    }

    private int directionAt(double dx, double dy, Map<Integer, UUID> members) {
        if (dx * dx + dy * dy < DEAD_ZONE_RADIUS * DEAD_ZONE_RADIUS) return -1;
        double clockwiseFromNorth = Math.atan2(dx, -dy);
        int direction = Math.floorMod((int) Math.floor(
                (clockwiseFromNorth + Math.PI / 8.0) / (Math.PI / 4.0)), 8);
        return members.containsKey(DIRECTION_SLOTS[direction]) ? direction : -1;
    }

    /** Display name matching the pet list: CustomName when present, else entity type name. */
    private Component petName(UUID uuid) {
        CompoundTag nbt = SummonWheelData.petNbt(uuid);
        if (nbt == null) return Component.empty();
        if (nbt.contains("CustomName") && minecraft != null && minecraft.level != null) {
            try {
                return Component.Serializer.fromJson(nbt.getString("CustomName"), minecraft.level.registryAccess());
            } catch (Exception ignored) {}
        }
        String typeKey = nbt.getString("EntityType");
        if (!typeKey.isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(typeKey);
            EntityType<?> type = id != null ? BuiltInRegistries.ENTITY_TYPE.get(id) : null;
            if (type != null) return type.getDescription();
        }
        return Component.literal("???");
    }

    /** Pet-list style name rendering inside the wheel center: scrolls when too long. */
    private void renderCenterName(GuiGraphics graphics, Component name, int centerX, int centerY) {
        int textWidth = font.width(name);
        int nameX = centerX - CENTER_NAME_MAX_WIDTH / 2;
        int nameY = centerY + 2;
        graphics.enableScissor(nameX, nameY - 1, nameX + CENTER_NAME_MAX_WIDTH, nameY + 10);
        try {
            if (textWidth <= CENTER_NAME_MAX_WIDTH) {
                graphics.drawCenteredString(font, name, centerX, nameY, 0xFFFFFF);
            } else {
                int scrollOffset = RenderHelper.scrollingOffset(textWidth - CENTER_NAME_MAX_WIDTH + 12);
                graphics.drawString(font, name, nameX - scrollOffset, nameY, 0xFFFFFF);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    /** Half-size centered hint text (pose-scaled 0.5x). */
    private void renderHalfSizeCentered(GuiGraphics graphics, Component text, int centerX, int y, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y, 0.0F);
        graphics.pose().scale(0.5F, 0.5F, 1.0F);
        graphics.drawCenteredString(font, text, 0, 0, color);
        graphics.pose().popPose();
    }

    private LivingEntity previewEntity(UUID uuid) {
        LivingEntity cached = previewEntities.get(uuid);
        if (cached != null) return cached;
        CompoundTag nbt = SummonWheelData.petNbt(uuid);
        if (nbt == null || minecraft == null || minecraft.level == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(nbt.getString("EntityType"));
        EntityType<?> type = id != null ? BuiltInRegistries.ENTITY_TYPE.get(id) : null;
        Entity entity = type != null ? type.create(minecraft.level) : null;
        if (!(entity instanceof LivingEntity living)) return null;
        try {
            living.load(nbt);
        } catch (Exception e) {
            living.discard();
            return null;
        }
        previewEntities.put(uuid, living);
        return living;
    }

    /** 1px white border matching the pet-list selected frame, drawn under the pet. */
    private static void renderSelectedBorder(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + SLOT_SIZE, y + 1, 0xFFFFFFFF);
        graphics.fill(x, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, 0xFFFFFFFF);
        graphics.fill(x, y, x + 1, y + SLOT_SIZE, 0xFFFFFFFF);
        graphics.fill(x + SLOT_SIZE - 1, y, x + SLOT_SIZE, y + SLOT_SIZE, 0xFFFFFFFF);
    }

    private static void renderPet(GuiGraphics graphics, int x, int y, LivingEntity pet) {
        float scale = TrulyScreen.computePreviewScale(pet, BASE_SCALE * LIST_ENTRY_SCALE_RATIO);
        boolean multipart = pet.getScale() > 1.0001F || (pet.getParts() != null && pet.getParts().length > 0);
        Quaternionf pose;
        Quaternionf cameraOrientation;
        if (multipart) {
            String typeKey = pet.getType().builtInRegistryHolder().key().location().toString();
            pose = MULTIPART_QUATS.computeIfAbsent(typeKey, ignored -> buildMultipartPose(
                    detectMultipartYBase(pet) - DEFAULT_ROT_X * 20.0F * ((float) Math.PI / 180F),
                    multipartPitchRadians(DEFAULT_ROT_Y)));
            cameraOrientation = MULTIPART_QUAT_PITCH;
            pet.yBodyRot = pet.yBodyRotO = 0.0F;
            pet.setYRot(0.0F);
            pet.yRotO = pet.yHeadRot = pet.yHeadRotO = 0.0F;
        } else {
            pose = NORMAL_QUAT;
            cameraOrientation = NORMAL_QUAT_PITCH;
            pet.yBodyRot = 180.0F + DEFAULT_ROT_X * 20.0F;
            pet.setYRot(180.0F + DEFAULT_ROT_X * 40.0F);
            pet.setXRot(-DEFAULT_ROT_Y * 20.0F);
            pet.yHeadRot = pet.yHeadRotO = pet.yBodyRot;
        }

        graphics.enableScissor(x, y, x + SLOT_SIZE, y + SLOT_SIZE);
        try {
            renderEntityInInventory(graphics, x + SLOT_SIZE / 2, y + 30,
                    scale, pose, cameraOrientation, pet);
        } finally {
            graphics.disableScissor();
        }
    }

    public void finishSelection() {
        if (completed) return;
        completed = true;
        Map<Integer, UUID> members = SummonWheelData.selectedTeamSlots();
        UUID selected = selectedDirection >= 0 ? members.get(DIRECTION_SLOTS[selectedDirection]) : null;
        int slot = selected != null ? DIRECTION_SLOTS[selectedDirection] : -1;
        if (selected != null && !SummonWheelData.isSummonable(selected)) selected = null;
        SummonKeyHandler.completeWheel(this, selected, slot);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1) {
            completed = true;
            SummonKeyHandler.completeWheel(this, null, -1);
            return true;
        }
        if (button == 0) {
            if (selectedDirection >= 0) {
                finishSelection();
                return true;
            }
            if (isOverBottle(mouseX, mouseY)) {
                completed = true;
                int colorIndex = PetTeamData.TEAM_COLORS.indexOf(SummonWheelData.selectedTeamColor());
                PacketDistributor.sendToServer(new SummonTeamPacket(colorIndex));
                SummonKeyHandler.completeWheel(this, null, -1);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isOverBottle(double mouseX, double mouseY) {
        int centerX = width / 2;
        int centerY = height / 2;
        return mouseX >= centerX - CENTER_BOTTLE_SIZE / 2 && mouseX < centerX + CENTER_BOTTLE_SIZE / 2
                && mouseY >= centerY - CENTER_BOTTLE_SIZE / 2 - 10
                && mouseY < centerY + CENTER_BOTTLE_SIZE / 2 - 10;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        setMovementKeyState(keyCode, scanCode, true);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        setMovementKeyState(keyCode, scanCode, false);
        if (SummonKeyHandler.matchesKey(keyCode, scanCode)) {
            finishSelection();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void setMovementKeyState(int keyCode, int scanCode, boolean down) {
        for (KeyMapping movementKey : movementKeys) {
            if (movementKey.matches(keyCode, scanCode)) movementKey.setDown(down);
        }
    }

    private void syncMovementKeys() {
        if (minecraft == null) return;
        long window = minecraft.getWindow().getWindow();
        for (KeyMapping movementKey : movementKeys) {
            InputConstants.Key key = movementKey.getKey();
            if (key.getType() == InputConstants.Type.KEYSYM) {
                movementKey.setDown(InputConstants.isKeyDown(window, key.getValue()));
            } else if (key.getType() == InputConstants.Type.MOUSE) {
                movementKey.setDown(GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS);
            }
        }
    }

    @Override
    public void onClose() {
        if (!completed) {
            completed = true;
            SummonKeyHandler.completeWheel(this, null, -1);
        }
    }

    @Override
    public void removed() {
        for (LivingEntity entity : previewEntities.values()) entity.discard();
        previewEntities.clear();
        SummonKeyHandler.wheelRemoved(this);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
