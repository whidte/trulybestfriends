package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.network.DeletePetDataPacket;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

/** Borderless delete control shared by alive, recalled, lost and dead pets. */
final class DeleteButton extends AbstractWidget {
    private final TrulyScreen screen;

    DeleteButton(int x, int y, TrulyScreen screen) {
        super(x, y, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT, Component.empty());
        this.screen = screen;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!screen.hasSelection()) return;
        ResourceLocation texture = isHovered() ? DELETE_ICON_HIGHLIGHTED_TEXTURE : DELETE_ICON_TEXTURE;
        graphics.blit(texture, getX(), getY(), width, height, 0, 0,
                DELETE_ICON_SOURCE_SIZE, DELETE_ICON_SOURCE_SIZE,
                DELETE_ICON_SOURCE_SIZE, DELETE_ICON_SOURCE_SIZE);
        if (isHovered()) {
            UUID selected = screen.getSelectedUuid();
            Component tooltip = selected != null && selected.equals(screen.deletePromptUuid)
                    ? Component.translatable("trulybestfriends.delete.confirm")
                    : (screen.isSelectedPetDead() || screen.isSelectedPetDataCorrupted()
                            ? Component.translatable("trulybestfriends.delete.lost")
                            : Component.translatable("trulybestfriends.delete.tooltip"));
            graphics.renderTooltip(screen.font(), tooltip, mouseX, mouseY);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!screen.hasSelection()) return;
        UUID uuid = screen.getSelectedUuid();
        if (screen.isSelectedPetDead() || screen.isSelectedPetDataCorrupted()) {
            trulybestfriends.CHANNEL.sendToServer(new DeletePetDataPacket(uuid));
            return;
        }
        boolean armed = uuid.equals(screen.deletePromptUuid);
        if (Screen.hasShiftDown() && armed) {
            trulybestfriends.CHANNEL.sendToServer(new DeletePetDataPacket(uuid));
            return;
        }
        if (!armed && !Screen.hasShiftDown()) screen.deletePromptUuid = uuid;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        this.defaultButtonNarrationText(narration);
    }
}
