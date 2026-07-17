package com.whidte.trulybestfriends.compat;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Conservative compatibility rules for intercepting modded death flows. */
public final class DeathInterceptionCompat {
    private static final ClassValue<Boolean> USES_BASE_DIE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> entityClass) {
            try {
                return entityClass.getMethod("die", DamageSource.class).getDeclaringClass() == LivingEntity.class;
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return false;
            }
        }
    };

    private DeathInterceptionCompat() {}

    public static boolean tryStoreDirectDieFallback(LivingEntity entity) {
        return isDirectDieInterceptionSafe(entity.getClass())
                && trulybestfriends.tryStoreFatalPet(entity);
    }

    public static boolean isDirectDieInterceptionSafe(Class<?> entityClass) {
        return LivingEntity.class.isAssignableFrom(entityClass) && USES_BASE_DIE.get(entityClass);
    }
}
