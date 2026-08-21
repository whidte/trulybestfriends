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
 * Bottom-left button. Normal mode: summons recalled pet. Dead mode: revives dead pet (costs items).
 * Uses widgets.png texture regions scaled to 20px height via 10-param blit.
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

    /** Whitelisted entity types cannot be revived via this mod. */
    private boolean isPetNotRevivable() {
        CompoundTag nbt = screen.getSelectedNbt();
        return nbt != null && nbt.contains("EntityType")
                && Config.isNoReviveEntity(nbt.getString("EntityType"));
    }

    private boolean isPetOnShoulder() {
        java.util.UUID uuid = screen.getSelectedUuid();
        return uuid != null && screen.isPetOnShoulder(uuid);
    }

    /** Check the recall/summon cooldown (Config.recallCooldownMs), shared with ActionButton */
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

    /** Check if the local player has enough revive items in inventory. Creative players always pass. */
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
            v = 46; // disabled — cannot be revived
        } else if (dead && !hasItems) {
            v = 46; // disabled style — lack items
        } else if (dead && (cooldown || reviveCooldown)) {
            v = 46; // cooldown
        } else if (dead) {
            v = isHovered() ? 86 : 66; // revive available
        } else if (recalled && recallCooldown) {
            v = 46; // recall cooldown active
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
            // Whitelisted entity types cannot be revived
            if (isPetNotRevivable()) return;
            if (screen.isButtonCooldownActive(lastClickTick)) return;
            if (getReviveCooldownRemainingMs() > 0) return;
            if (!hasReviveItems()) return;
            lastClickTick = screen.currentGameTick();
            // Optimistic update: mark pet as alive with 1 HP in cache immediately
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

        // Recalled pet: release via RecallPetPacket (same as ActionButton),
        // gated by Config.recallCooldownMs
        if (screen.isSelectedPetRecalled()) {
            if (isRecallCooldownActive()) return;
            long now = System.currentTimeMillis();
            java.util.UUID uuid = screen.getSelectedUuid();
            if (uuid != null) {
                screen.cooldowns.put(uuid, now);
            }
            // Optimistic update: clear Recalled flag
            CompoundTag nbt = screen.getSelectedNbt();
            if (nbt != null) {
                nbt.remove("Recalled");
            }
            trulybestfriends.CHANNEL.sendToServer(directTeleport
                    ? new ReleaseRecalledPetPacket(screen.getSelectedUuid())
                    : new RecallPetPacket(screen.getSelectedUuid()));
            // Unloaded ("lost") pets can be summoned too, so always refresh quick-summon.
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
