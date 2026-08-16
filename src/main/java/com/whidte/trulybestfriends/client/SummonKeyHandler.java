package com.whidte.trulybestfriends.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Axis;
import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.network.PetTeamData;
import com.whidte.trulybestfriends.network.RequestPetDataPacket;
import com.whidte.trulybestfriends.network.RequestTeamDataPacket;
import com.whidte.trulybestfriends.network.SetLastSummonPacket;
import com.whidte.trulybestfriends.network.SummonPetPacket;
import com.whidte.trulybestfriends.tab.SummonWheelData;
import com.whidte.trulybestfriends.tab.SummonWheelScreen;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/** Millisecond-precise hold state for repeat summon, bottle transition, and radial selection. */
public final class SummonKeyHandler {
    private static final long ROTATION_START_MILLIS = 300L;
    private static final long RELEASE_TEXTURE_MILLIS = 390L;
    private static final long WHEEL_START_MILLIS = 400L;
    private static final int BOTTLE_SIZE = 16;
    private static final ResourceLocation WORLD_IN_A_BOTTLE = ResourceLocation.fromNamespaceAndPath(
            "truly_best_friends", "textures/gui/world_in_a_bottle.png");
    private static final ResourceLocation RELEASE_BOTTLE = ResourceLocation.fromNamespaceAndPath(
            "truly_best_friends", "textures/gui/release_bottle.png");

    public static final KeyMapping SUMMON_KEY = new KeyMapping(
            "key.trulybestfriends.summon_wheel",
            InputConstants.UNKNOWN.getValue(),
            "key.categories.trulybestfriends");

    private enum State { IDLE, HOLDING, WHEEL }

    private static State state = State.IDLE;
    private static long pressStartMillis;
    private static UUID lastSummonedPet;
    private static UUID activePlayer;
    private static boolean keyHeld;

    private SummonKeyHandler() {}

    /** Returns true when this handler took ownership of the event (the caller should cancel it). */
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
            if (state == State.HOLDING || state == State.WHEEL) {
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
        refreshPlayer(minecraft.player.getUUID());
        requestSnapshots();
        if (lastSummonedPet == null) {
            lastSummonedPet = SummonWheelData.resolveLastSummon();
        }
        if (ClientEvents.isTabKeySharedWithSummon()) {
            pressStartMillis = Util.getMillis();
            state = State.HOLDING;
        } else if (lastSummonedPet == null) {
            openWheel();
        } else {
            pressStartMillis = Util.getMillis();
            state = State.HOLDING;
        }
    }

    private static void releaseKey() {
        if (state == State.HOLDING) {
            long heldMillis = Util.getMillis() - pressStartMillis;
            state = State.IDLE;
            if (heldMillis < ROTATION_START_MILLIS) {
                if (ClientEvents.isTabKeySharedWithSummon()) {
                    ClientEvents.openPetTab();
                } else if (lastSummonedPet != null) {
                    sendSummon(lastSummonedPet);
                }
            }
        } else if (state == State.WHEEL) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof SummonWheelScreen wheel) wheel.finishSelection();
            else state = State.IDLE;
        }
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            state = State.IDLE;
            activePlayer = null;
            lastSummonedPet = null;
            keyHeld = false;
            return;
        }
        refreshPlayer(minecraft.player.getUUID());
        if (state == State.HOLDING && minecraft.screen != null) {
            state = State.IDLE;
        }
        if (state == State.HOLDING
                && keyHeld
                && Util.getMillis() - pressStartMillis >= WHEEL_START_MILLIS) {
            openWheel();
        } else if (state == State.WHEEL && !keyHeld
                && minecraft.screen instanceof SummonWheelScreen wheel) {
            wheel.finishSelection();
        }
    }

    public static void applyMovementInput(Input input) {
        if (state != State.HOLDING && state != State.WHEEL) return;
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

    public static void renderBottle(GuiGraphics graphics) {
        if (state != State.HOLDING && state != State.WHEEL) return;
        long heldMillis = Util.getMillis() - pressStartMillis;
        boolean wheel = state == State.WHEEL;
        if (state == State.HOLDING) {
            if (keyHeld && heldMillis >= WHEEL_START_MILLIS) {
                openWheel();
                if (state != State.WHEEL) return;
                wheel = true;
            } else if (heldMillis >= WHEEL_START_MILLIS) {
                return;
            }
        }
        int x = Math.max(0, graphics.guiWidth() - Config.summonBottleRightOffset - BOTTLE_SIZE);
        int y = Math.max(0, Math.min(graphics.guiHeight() - BOTTLE_SIZE,
                (graphics.guiHeight() - BOTTLE_SIZE) / 2 + Config.summonBottleVerticalOffset));
        float rotation;
        ResourceLocation texture;
        if (wheel || heldMillis >= RELEASE_TEXTURE_MILLIS) {
            rotation = 0.0F;
            texture = RELEASE_BOTTLE;
        } else {
            rotation = heldMillis < ROTATION_START_MILLIS
                    ? 0.0F
                    : 180.0F * (heldMillis - ROTATION_START_MILLIS)
                    / (RELEASE_TEXTURE_MILLIS - ROTATION_START_MILLIS);
            texture = WORLD_IN_A_BOTTLE;
        }

        graphics.pose().pushPose();
        graphics.pose().translate(x + BOTTLE_SIZE / 2.0F, y + BOTTLE_SIZE / 2.0F, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(-rotation));
        graphics.pose().translate(-BOTTLE_SIZE / 2.0F, -BOTTLE_SIZE / 2.0F, 0.0F);
        graphics.blit(texture, 0, 0, 0, 0, BOTTLE_SIZE, BOTTLE_SIZE, BOTTLE_SIZE, BOTTLE_SIZE);
        graphics.pose().popPose();
    }

    private static void openWheel() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null
                || minecraft.screen != null) {
            state = State.IDLE;
            return;
        }
        state = State.WHEEL;
        minecraft.setScreen(new SummonWheelScreen());
    }

    private static void requestSnapshots() {
        trulybestfriends.CHANNEL.sendToServer(RequestPetDataPacket.requestFullList());
        trulybestfriends.CHANNEL.sendToServer(new RequestTeamDataPacket());
    }

    public static void rememberSummon(UUID petUuid) {
        Minecraft minecraft = Minecraft.getInstance();
        if (petUuid != null && minecraft.player != null) {
            activePlayer = minecraft.player.getUUID();
            lastSummonedPet = petUuid;
        }
    }

    private static void sendSummon(UUID petUuid) {
        rememberSummon(petUuid);
        trulybestfriends.CHANNEL.sendToServer(new SummonPetPacket(petUuid));
    }

    public static boolean matchesKey(int keyCode, int scanCode) {
        return SUMMON_KEY.matches(keyCode, scanCode);
    }

    public static void completeWheel(SummonWheelScreen wheel, UUID selectedPet, int selectedSlot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == wheel) minecraft.setScreen(null);
        state = State.IDLE;
        if (selectedPet != null) {
            sendSummon(selectedPet);
            if (selectedSlot >= 1) {
                int colorIndex = PetTeamData.TEAM_COLORS.indexOf(SummonWheelData.selectedTeamColor());
                trulybestfriends.CHANNEL.sendToServer(new SetLastSummonPacket(colorIndex, selectedSlot));
            }
        }
    }

    public static void wheelRemoved(SummonWheelScreen wheel) {
        if (Minecraft.getInstance().screen != wheel && state == State.WHEEL) state = State.IDLE;
    }

    private static void refreshPlayer(UUID playerUuid) {
        if (!playerUuid.equals(activePlayer)) {
            activePlayer = playerUuid;
            lastSummonedPet = null;
            state = State.IDLE;
        }
    }
}
