package com.whidte.trulybestfriends.mixin;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds the movement keys through the summon-wheel input filter (MovementInputUpdateEvent equivalent). */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void trulybestfriends$applyWheelInput(boolean slowDown, float movementMultiplier, CallbackInfo callback) {
        com.whidte.trulybestfriends.client.SummonKeyHandler.applyMovementInput(
                (KeyboardInput) (Object) this);
    }
}
