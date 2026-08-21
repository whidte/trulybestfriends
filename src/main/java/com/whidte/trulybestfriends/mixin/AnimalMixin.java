package com.whidte.trulybestfriends.mixin;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Posts the Forge {@code AnimalTameEvent} equivalent after a vanilla tame completes. */
@Mixin(TamableAnimal.class)
public abstract class AnimalMixin {
    @Inject(method = "tame", at = @At("RETURN"))
    private void trulybestfriends$afterTame(Player player, CallbackInfo callback) {
        trulybestfriends.onAnimalTamed((TamableAnimal) (Object) this);
    }
}
