package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.client.SummonKeyHandler;
import com.whidte.trulybestfriends.network.DirectTeleportPetToPlayerPacket;
import com.whidte.trulybestfriends.network.RecallPetPacket;
import com.whidte.trulybestfriends.network.ReleaseRecalledPetPacket;
import com.whidte.trulybestfriends.network.RevivePetPacket;
import com.whidte.trulybestfriends.network.TeleportPetToPlayerPacket;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;
import static com.whidte.trulybestfriends.tab.RenderHelper.*;

/**
 * 左下角按钮。普通模式：召唤已收回的宠物。死亡模式：复活已死亡的宠物（消耗物品）。
 * 通过 10 参数 blit 缩放 widgets.png 中的纹理区域至 20px 高度。
 */
class SummonToPlayerButton extends AbstractWidget {
    private static final int TEX_W = 256;
    private static final int TEX_H = 256;
    private static final int SRC_CAP = 5;
    private static final int SRC_MID = 190;
    private static final int SRC_H = 20;
    private static final int COLOR_DISABLED = 0x555555;
    private static final int COLOR_NORMAL = 0xFFFFFF;
    private static final int COLOR_HOVERED = 0xFFFF55;
    private static final int COLOR_REVIVE_OK = 0x55FF55;

    private final TrulyScreen screen;
    private long lastClickTick;

    public SummonToPlayerButton(int x, int y, int width, TrulyScreen screen) {
        super(x, y, width, SRC_H, Component.empty());
        this.screen = screen;
    }

    /** 白名单中的实体类型无法通过本模组复活。 */
    private boolean isPetNotRevivable() {
        CompoundTag nbt = screen.getSelectedNbt();
        return nbt != null && nbt.contains("EntityType")
                && Config.isNoReviveEntity(nbt.getString("EntityType"));
    }

    private boolean isPetOnShoulder() {
        java.util.UUID uuid = screen.getSelectedUuid();
        return uuid != null && screen.isPetOnShoulder(uuid);
    }

    /** 检查收回/召唤冷却（Config.recallCooldownMs），与 ActionButton 共用 */
    private boolean isRecallCooldownActive() {
        java.util.UUID uuid = screen.getSelectedUuid();
        if (uuid == null) return true;
        long last = screen.cooldowns.getOrDefault(uuid, 0L);
        return System.currentTimeMillis() - last < Config.recallCooldownMs;
    }

    private long getReviveCooldownRemainingMs() {
        CompoundTag nbt = screen.getSelectedNbt();
        if (nbt == null || Config.reviveCooldownSeconds <= 0 || !nbt.contains("LastDeathTime")) return 0;
        long remaining = nbt.getLong("LastDeathTime") + Config.reviveCooldownSeconds * 1000L - screen.currentServerTimeMillis();
        return Math.max(0, remaining);
    }

    /** 检查本地玩家背包中是否有足够的复活物品。创造模式玩家始终通过。 */
    private boolean hasReviveItems() {
        var player = screen.getMinecraft().player;
        if (player == null) return false;
        if (!Config.isReviveItemRequired()) return true;
        if (player.isCreative()) return true;
        var item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(Config.reviveItem));
        if (item == null) return false;
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count >= Config.reviveItemCount;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!screen.hasSelection()) return;

        boolean dead = screen.isSelectedPetDead();
        boolean onShoulder = isPetOnShoulder();
        boolean recalled = screen.isSelectedPetRecalled();
        boolean cooldown = screen.isButtonCooldownActive(lastClickTick);
        boolean recallCooldown = isRecallCooldownActive();
        boolean hasItems = hasReviveItems();
        boolean notRevivable = dead && isPetNotRevivable();
        long reviveCooldownRemainingMs = dead ? getReviveCooldownRemainingMs() : 0;
        boolean reviveCooldown = reviveCooldownRemainingMs > 0;

        if (notRevivable) {
            this.active = false;
        } else if (dead) {
            this.active = hasItems && !cooldown && !reviveCooldown;
        } else if (recalled) {
            this.active = !recallCooldown;
        } else {
            this.active = !onShoulder && !cooldown;
        }

        int v;
        if (notRevivable) {
            v = 46; // 禁用——无法复活
        } else if (dead && !hasItems) {
            v = 46; // 禁用样式——物品不足
        } else if (dead && (cooldown || reviveCooldown)) {
            v = 46; // 冷却
        } else if (dead) {
            v = isHovered() ? 86 : 66; // 可复活
        } else if (recalled && recallCooldown) {
            v = 46; // 收回冷却进行中
        } else if (onShoulder || cooldown) {
            v = 46;
        } else if (isHovered() && this.active) {
            v = 86;
        } else {
            v = 66;
        }

        int midW = width - SRC_CAP * 2;
        ResourceLocation tex = WIDGETS_TEXTURE;

        g.blit(tex, getX(), getY(), SRC_CAP, SRC_H, 0, v, SRC_CAP, SRC_H, TEX_W, TEX_H);
        tileBlitH(g, tex, getX() + SRC_CAP, getY(), midW, SRC_H, SRC_CAP, v, SRC_MID, SRC_H, TEX_W, TEX_H);
        g.blit(tex, getX() + SRC_CAP + midW, getY(), SRC_CAP, SRC_H, 195, v, SRC_CAP, SRC_H, TEX_W, TEX_H);

        Component label;
        if (notRevivable) {
            label = Component.translatable("trulybestfriends.revive.not_revivable");
        } else if (dead && reviveCooldown) {
            label = Component.translatable("trulybestfriends.revive.cooldown", (reviveCooldownRemainingMs + 999) / 1000);
        } else if (dead) {
            label = Component.translatable("trulybestfriends.revive.label");
        } else if (screen.canSwapToSelectedPet() && !Screen.hasShiftDown()) {
            label = Component.translatable("trulybestfriends.ride_swap.label");
        } else {
            label = Component.translatable("trulybestfriends.summon_to_player.label");
        }

        int color;
        if (notRevivable) {
            color = COLOR_DISABLED;
        } else if (dead) {
            if (!hasItems || cooldown || reviveCooldown) {
                color = COLOR_DISABLED;
            } else {
                color = COLOR_REVIVE_OK;
            }
        } else if (recalled && recallCooldown) {
            color = COLOR_DISABLED;
        } else if (onShoulder || cooldown) {
            color = COLOR_DISABLED;
        } else if (isHovered()) {
            color = COLOR_HOVERED;
        } else {
            color = COLOR_NORMAL;
        }

        var font = screen.font();
        int maxTextWidth = width - 2;
        var lines = font.split(label, maxTextWidth);
        int totalHeight = lines.size() * font.lineHeight;
        int lineY = getY() + (height - totalHeight) / 2;
        for (var line : lines) {
            int textX = getX() + (width - font.width(line)) / 2;
            g.drawString(font, line, textX, lineY, color);
            lineY += font.lineHeight;
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!screen.hasSelection()) return;
        boolean directTeleport = Screen.hasShiftDown() && screen.canSwapToSelectedPet();

        if (screen.isSelectedPetDead()) {
            // 白名单中的实体类型无法复活
            if (isPetNotRevivable()) return;
            if (screen.isButtonCooldownActive(lastClickTick)) return;
            if (getReviveCooldownRemainingMs() > 0) return;
            if (!hasReviveItems()) return;
            lastClickTick = screen.currentGameTick();
            // 乐观更新：立即在缓存中把宠物标记为存活并设置 1 点生命值
            CompoundTag nbt = screen.getSelectedNbt();
            if (nbt != null) {
                nbt.putFloat("Health", 1.0f);
                nbt.remove("Recalled");
                nbt.remove("TBF_State");
                nbt.remove("DeathTime");
                nbt.remove("HurtTime");
            }
            trulybestfriends.CHANNEL.sendToServer(new RevivePetPacket(screen.getSelectedUuid()));
            return;
        }

        // 已收回的宠物：通过 RecallPetPacket 释放（与 ActionButton 相同），
        // 受 Config.recallCooldownMs 约束
        if (screen.isSelectedPetRecalled()) {
            if (isRecallCooldownActive()) return;
            long now = System.currentTimeMillis();
            java.util.UUID uuid = screen.getSelectedUuid();
            if (uuid != null) {
                screen.cooldowns.put(uuid, now);
            }
            // 乐观更新：清除 Recalled 标记
            CompoundTag nbt = screen.getSelectedNbt();
            if (nbt != null) {
                nbt.remove("Recalled");
            }
            trulybestfriends.CHANNEL.sendToServer(directTeleport
                    ? new ReleaseRecalledPetPacket(screen.getSelectedUuid())
                    : new RecallPetPacket(screen.getSelectedUuid()));
            // 未加载（“丢失”）的宠物也可以召唤，因此始终刷新快速召唤。
            SummonKeyHandler.rememberSummon(screen.getSelectedUuid());
            return;
        }

        if (isPetOnShoulder() || screen.isButtonCooldownActive(lastClickTick)) return;
        lastClickTick = screen.currentGameTick();
        trulybestfriends.CHANNEL.sendToServer(directTeleport
                ? new DirectTeleportPetToPlayerPacket(screen.getSelectedUuid())
                : new TeleportPetToPlayerPacket(screen.getSelectedUuid()));
        SummonKeyHandler.rememberSummon(screen.getSelectedUuid());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
