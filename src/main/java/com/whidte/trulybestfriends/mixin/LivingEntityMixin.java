package com.whidte.trulybestfriends.mixin;

import com.whidte.trulybestfriends.compat.DeathInterceptionCompat;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LivingEntity.class, priority = 100)
public abstract class LivingEntityMixin {
    @Inject(method = "checkTotemDeathProtection", at = @At("RETURN"), cancellable = true)
    private void trulybestfriends$storeAfterDeathProtection(
            DamageSource source, CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue()
                && trulybestfriends.tryStoreFatalPet((LivingEntity) (Object) this)) {
            callback.setReturnValue(true);
        }
    }

    /** Direct-die fallback, limited to entities that do not override die. */
    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void trulybestfriends$storeBeforeDeath(DamageSource source, CallbackInfo callback) {
        if (DeathInterceptionCompat.tryStoreDirectDieFallback((LivingEntity) (Object) this)) {
            callback.cancel();
        }
    }
}
