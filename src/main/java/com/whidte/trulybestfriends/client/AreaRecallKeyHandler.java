package com.whidte.trulybestfriends.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.network.AreaRecallPacket;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 用于群体收回的毫秒级按住状态：轻点按默认半径收回，
 * 按住则浮出瓶子动画并可用滚轮调节收回半径。
 */
public final class AreaRecallKeyHandler {
    /** 短于该时长视为轻点，直接按默认半径收回。 */
    private static final long HOLD_START_MILLIS = 300L;
    private static final int BOTTLE_SIZE = 16;
    /** 半径数字相对瓶子左侧的间距（GUI 像素）。 */
    private static final int NUMBER_GAP = 3;
    private static final ResourceLocation ABSORPTION_BOTTLE = ResourceLocation.fromNamespaceAndPath(
            "truly_best_friends", "textures/gui/absorption_bottle.png");

    public static final KeyMapping AREA_RECALL_KEY = new KeyMapping(
            "key.trulybestfriends.area_recall",
            InputConstants.UNKNOWN.getValue(),
            "key.categories.trulybestfriends");

    private enum State { IDLE, HOLDING }

    private static State state = State.IDLE;
    private static long pressStartMillis;
    private static int recallRange = Config.areaRecallDefaultRange;
    private static String activePlayerKey;
    private static boolean keyHeld;

    private AreaRecallKeyHandler() {}

    /** 当本处理器接管了该事件时返回 true（调用方应取消该事件）。 */
    public static boolean onKeyInput(int keyCode, int scanCode, int action) {
        if (!matchesKey(keyCode, scanCode)) return false;
        if (action == GLFW.GLFW_PRESS) {
            keyHeld = true;
            if (canBeginPress()) {
                beginPress();
                return true;
            }
            return false;
        }
        if (action == GLFW.GLFW_RELEASE) {
            keyHeld = false;
            if (state == State.HOLDING) {
                releaseKey();
                return true;
            }
        }
        return false;
    }

    private static boolean canBeginPress() {
        Minecraft minecraft = Minecraft.getInstance();
        return state == State.IDLE
                && minecraft.player != null
                && minecraft.getConnection() != null
                && minecraft.screen == null;
    }

    private static void beginPress() {
        Minecraft minecraft = Minecraft.getInstance();
        if (state != State.IDLE || minecraft.player == null || minecraft.getConnection() == null
                || minecraft.screen != null) return;
        refreshPlayer(minecraft.player);
        // 每次按下都回到配置的默认半径，只有按住期间滚动滚轮才会改变它。
        recallRange = Mth.clamp(Config.areaRecallDefaultRange, 1, 16);
        pressStartMillis = Util.getMillis();
        state = State.HOLDING;
    }

    private static void releaseKey() {
        long heldMillis = Util.getMillis() - pressStartMillis;
        state = State.IDLE;
        // 轻点：按默认半径收回；按住：按滚轮调好的半径收回。
        sendAreaRecall(heldMillis < HOLD_START_MILLIS
                ? Mth.clamp(Config.areaRecallDefaultRange, 1, 16) : recallRange);
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            state = State.IDLE;
            activePlayerKey = null;
            keyHeld = false;
            return;
        }
        refreshPlayer(minecraft.player);
        // 按住期间玩家自己打开了别的界面：放弃本次操作。
        if (state == State.HOLDING && minecraft.screen != null) {
            state = State.IDLE;
            return;
        }
        if (state == State.HOLDING && !keyHeld) {
            state = State.IDLE;
        }
    }

    /** 按住期间接管移动输入，避免松开前角色移动。 */
    public static void applyMovementInput(Input input) {
        if (state != State.HOLDING) return;
        Minecraft minecraft = Minecraft.getInstance();
        boolean up = isMovementKeyDown(minecraft.options.keyUp);
        boolean down = isMovementKeyDown(minecraft.options.keyDown);
        boolean left = isMovementKeyDown(minecraft.options.keyLeft);
        boolean right = isMovementKeyDown(minecraft.options.keyRight);
        input.up = up;
        input.down = down;
        input.left = left;
        input.right = right;
        input.forwardImpulse = movementImpulse(up, down);
        input.leftImpulse = movementImpulse(left, right);
        input.jumping = isMovementKeyDown(minecraft.options.keyJump);
    }

    private static float movementImpulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }

    private static boolean isMovementKeyDown(KeyMapping keyMapping) {
        InputConstants.Key key = keyMapping.getKey();
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(window, key.getValue());
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return keyMapping.isDown();
    }

    /** 按住状态下滚动滚轮调节收回半径。 */
    public static boolean onMouseScroll(double verticalDelta) {
        if (state != State.HOLDING) return false;
        if (verticalDelta == 0.0D) return false;
        recallRange = Mth.clamp(recallRange + (verticalDelta > 0.0D ? 1 : -1), 1, 16);
        return true;
    }

    /** 渲染屏幕右侧的瓶子贴图，以及其左侧的当前半径数字。 */
    public static void renderBottle(GuiGraphics graphics) {
        if (state != State.HOLDING) return;
        int x = Math.max(0, graphics.guiWidth() - Config.summonBottleRightOffset - BOTTLE_SIZE);
        int y = Math.max(0, Math.min(graphics.guiHeight() - BOTTLE_SIZE,
                (graphics.guiHeight() - BOTTLE_SIZE) / 2 + Config.summonBottleVerticalOffset));
        graphics.blit(ABSORPTION_BOTTLE, x, y, 0, 0, BOTTLE_SIZE, BOTTLE_SIZE, BOTTLE_SIZE, BOTTLE_SIZE);
        renderRangeNumber(graphics, x, y);
    }

    /** 半径数字紧贴瓶子左侧，末影人粒子同款的紫红色。 */
    private static void renderRangeNumber(GuiGraphics graphics, int bottleX, int bottleY) {
        Component text = Component.literal(Integer.toString(recallRange));
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(text);
        graphics.drawString(font, text, bottleX - NUMBER_GAP - textWidth,
                bottleY + (BOTTLE_SIZE - font.lineHeight) / 2, 0xB762D1, true);
    }

    private static void sendAreaRecall(int range) {
        PacketDistributor.sendToServer(new AreaRecallPacket(Mth.clamp(range, 1, 16)));
    }

    /** 当前是否处于按住状态（供范围粒子渲染读取）。 */
    public static boolean isHolding() {
        return state == State.HOLDING;
    }

    /** 当前正在调节的半径。 */
    public static int currentRange() {
        return recallRange;
    }

    public static boolean matchesKey(int keyCode, int scanCode) {
        return AREA_RECALL_KEY.matches(keyCode, scanCode);
    }

    private static void refreshPlayer(LocalPlayer player) {
        String key = player.getUUID() + "/" + (player.level() == null
                ? "" : player.level().dimension().location().toString());
        if (!key.equals(activePlayerKey)) {
            activePlayerKey = key;
            state = State.IDLE;
        }
    }
}
