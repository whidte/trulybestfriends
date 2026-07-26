package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.network.HealPetPacket;
import com.whidte.trulybestfriends.network.PetHealingManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static com.whidte.trulybestfriends.tab.TrulyConstants.*;

class HealButton extends AbstractWidget {
    private final TrulyScreen screen;

    HealButton(int x, int y, TrulyScreen screen) {
        super(x, y, 20, 20, Component.translatable("trulybestfriends.heal.label"));
        this.screen = screen;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!screen.hasSelection()) return;
        boolean advanced = Screen.hasShiftDown();
        Component disabledReason = disabledReason(advanced);
        this.active = disabledReason == null;
        int frameV = active ? (isHovered() ? 20 : 0) : 40;
        graphics.blit(WIDGET_BUTTON, getX(), getY(), 0, frameV, 20, 20, 256, 256);
        graphics.blit(REGENERATION_ICON, getX() + 1, getY() + 1, 0, 0,
                HEAL_BUTTON_SIZE, HEAL_BUTTON_SIZE, HEAL_BUTTON_SIZE, HEAL_BUTTON_SIZE);
        if (mouseX >= getX() && mouseX <= getX() + width
                && mouseY >= getY() && mouseY <= getY() + height) {
            graphics.renderComponentTooltip(screen.font(), tooltip(disabledReason, advanced), mouseX, mouseY);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        boolean advanced = Screen.hasShiftDown();
        if (!screen.hasSelection() || disabledReason(advanced) != null) return;
        PacketDistributor.sendToServer(new HealPetPacket(screen.getSelectedUuid(), advanced));
    }

    private Component disabledReason(boolean advanced) {
        CompoundTag nbt = screen.getSelectedNbt();
        if (nbt == null) return Component.translatable("trulybestfriends.heal.disabled.corrupted");
        if (screen.isSelectedPetDead()) return Component.translatable("trulybestfriends.action.dead");
        if (screen.isSelectedPetDataCorrupted()) {
            return Component.translatable("trulybestfriends.heal.disabled.corrupted");
        }
        int remaining = PetHealingManager.getRemainingTicks(nbt, advanced);
        int added = PetHealingManager.getDurationPerClick(nbt);
        int maximum = PetHealingManager.getMaxDuration(nbt);
        if ((long) remaining + added > maximum) {
            return Component.translatable("trulybestfriends.heal.disabled.maximum");
        }
        Player player = screen.getMinecraft().player;
        int cost = PetHealingManager.getHungerCost(nbt, advanced);
        if (player != null && !player.getAbilities().instabuild
                && player.getFoodData().getFoodLevel() < cost) {
            return Component.translatable("trulybestfriends.heal.disabled.hunger", cost);
        }
        return null;
    }

    private List<Component> tooltip(Component disabledReason, boolean advanced) {
        CompoundTag nbt = screen.getSelectedNbt();
        List<Component> lines = new ArrayList<>();
        if (nbt == null) {
            lines.add(Component.translatable("trulybestfriends.heal.label"));
            if (disabledReason != null) {
                lines.add(disabledReason.copy().withStyle(net.minecraft.ChatFormatting.RED));
            }
            return lines;
        }

        int remaining = PetHealingManager.getRemainingTicks(nbt, advanced);
        int cost = PetHealingManager.getHungerCost(nbt, advanced);
        int durationSeconds = (PetHealingManager.getDurationPerClick(nbt) + 19) / 20;
        lines.add(Component.translatable("trulybestfriends.heal.summary.cost", cost));
        Component recoveryName = Component.translatable("effect.minecraft.regeneration")
                .append(" ")
                .append(Component.translatable(advanced ? "potion.potency.1" : "potion.potency.0"))
                .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(
                        net.minecraft.network.chat.TextColor.fromRgb(0xCD5CAB)));
        lines.add(Component.translatable(
                "trulybestfriends.heal.summary.duration", durationSeconds, recoveryName));
        if (disabledReason != null) {
            lines.add(disabledReason.copy().withStyle(net.minecraft.ChatFormatting.RED));
        }
        if (remaining > 0) {
            lines.add(Component.translatable("trulybestfriends.heal.remaining", (remaining + 19) / 20));
        }
        return lines;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        this.defaultButtonNarrationText(narration);
    }
}
