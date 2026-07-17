package com.whidte.trulybestfriends.mixin;

import com.whidte.trulybestfriends.compat.FtbTeamsCompat;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Unique
    private boolean trulybestfriends$allowKilledRemoval;

    @Inject(method = "isAlliedTo", at = @At("RETURN"), cancellable = true)
    private void trulybestfriends$checkFtbTeamsAlly(Entity other, CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValue() && FtbTeamsCompat.areAllied((Entity) (Object) this, other)) {
            result.setReturnValue(true);
        }
    }

    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private void trulybestfriends$storeBeforeKill(CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        if (entity instanceof LivingEntity living
                && trulybestfriends.tryStoreFatalPet(living, living.damageSources().genericKill())) {
            callback.cancel();
            return;
        }
        trulybestfriends$allowKilledRemoval = true;
    }

    @Inject(method = "kill", at = @At("RETURN"))
    private void trulybestfriends$finishKill(CallbackInfo callback) {
        trulybestfriends$allowKilledRemoval = false;
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void trulybestfriends$storeBeforeKilledRemoval(Entity.RemovalReason reason, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        if (reason == Entity.RemovalReason.KILLED
                && !trulybestfriends$allowKilledRemoval
                && entity instanceof LivingEntity living
                && trulybestfriends.tryStoreFatalPet(living, living.damageSources().genericKill())) {
            callback.cancel();
        }
    }
}
