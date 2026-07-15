package com.whidte.trulybestfriends.mixin;

import com.whidte.trulybestfriends.compat.FtbTeamsCompat;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "isAlliedTo", at = @At("RETURN"), cancellable = true)
    private void trulybestfriends$checkFtbTeamsAlly(Entity other, CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValue() && FtbTeamsCompat.areAllied((Entity) (Object) this, other)) {
            result.setReturnValue(true);
        }
    }
}
