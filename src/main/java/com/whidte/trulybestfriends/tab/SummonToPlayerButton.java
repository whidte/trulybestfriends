package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.network.TeleportPetToPlayerPacket;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Button in bottom-left corner that teleports the selected recalled pet to the player's position.
 * Uses widgets.png texture regions scaled to 20px height via 10-param blit.
 */
class SummonToPlayerButton extends AbstractWidget {
    private static final int TEX_W = 256;
    private static final int TEX_H = 256;
    private static final int SRC_CAP = 5;
    private static final int SRC_MID = 190;
    private static final int SRC_H = 20;
    private static final int COOLDOWN_TICKS = 5;

    private static final int COLOR_DISABLED = 0x555555;
    private static final int COLOR_NORMAL = 0xFFFFFF;
    private static final int COLOR_HOVERED = 0xFFFF55;

    private final TrulyScreen screen;
    private long lastClickTick;

    public SummonToPlayerButton(int x, int y, int width, TrulyScreen screen) {
        super(x, y, width, SRC_H, Component.empty());
        this.screen = screen;
    }

    private boolean isPetDead() {
        CompoundTag nbt = screen.getSelectedNbt();
        return nbt != null && nbt.contains("Health") && nbt.getFloat("Health") <= 0;
    }

    private boolean isPetOnShoulder() {
        java.util.UUID uuid = screen.getSelectedUuid();
        return uuid != null && screen.isPetOnShoulder(uuid);
    }

    private boolean isOnCooldown() {
        if (screen.getMinecraft().level == null) return true;
        long currentTick = screen.getMinecraft().level.getGameTime();
        return currentTick - lastClickTick < COOLDOWN_TICKS;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!screen.hasSelection()) return;

        boolean dead = isPetDead();
        boolean onShoulder = isPetOnShoulder();
        this.active = !dead && !onShoulder && !isOnCooldown();

        int v;
        if (dead || onShoulder || isOnCooldown()) {
            v = 46;
        } else if (isHovered() && this.active) {
            v = 86;
        } else {
            v = 66;
        }

        int midW = width - SRC_CAP * 2;
        ResourceLocation tex = TrulyScreen.WIDGETS_TEXTURE;

        g.blit(tex, getX(), getY(), SRC_CAP, SRC_H, 0, v, SRC_CAP, SRC_H, TEX_W, TEX_H);
        tileBlitH(g, tex, getX() + SRC_CAP, getY(), midW, SRC_H, SRC_CAP, v, SRC_MID, SRC_H, TEX_W, TEX_H);
        g.blit(tex, getX() + SRC_CAP + midW, getY(), SRC_CAP, SRC_H, 195, v, SRC_CAP, SRC_H, TEX_W, TEX_H);

        Component label = Component.translatable("trulybestfriends.summon_to_player.label");
        int color;
        if (dead || onShoulder || isOnCooldown()) {
            color = COLOR_DISABLED;
        } else if (isHovered()) {
            color = COLOR_HOVERED;
        } else {
            color = COLOR_NORMAL;
        }
        var font = screen.font();
        int textX = getX() + (width - font.width(label)) / 2;
        int textY = getY() + (height - font.lineHeight) / 2;
        g.drawString(font, label, textX, textY, color);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!screen.hasSelection() || isPetDead() || isPetOnShoulder() || isOnCooldown()) return;
        if (screen.getMinecraft().level != null) {
            lastClickTick = screen.getMinecraft().level.getGameTime();
        }
        trulybestfriends.CHANNEL.sendToServer(new TeleportPetToPlayerPacket(screen.getSelectedUuid()));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
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
