package com.whidte.trulybestfriends.compat;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;

/**
 * Loader-neutral replacement for Forge's {@code PartEntity}: detects multipart
 * sub-parts (e.g. Ice &amp; Fire dragon tail/wing) via their reflective
 * {@code getParent()} accessor so they are never tracked or discarded directly.
 */
public final class PartEntityCompat {
    private static final ClassValue<Method> GET_PARENT = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> entityClass) {
            try {
                Method method = entityClass.getMethod("getParent");
                return Entity.class.isAssignableFrom(method.getReturnType()) ? method : null;
            } catch (ReflectiveOperationException | SecurityException e) {
                return null;
            }
        }
    };

    private PartEntityCompat() {}

    public static boolean isPartEntity(Entity entity) {
        if (entity == null) return false;
        Method getParent = GET_PARENT.get(entity.getClass());
        if (getParent == null) return false;
        try {
            return getParent.invoke(entity) != null;
        } catch (ReflectiveOperationException | SecurityException e) {
            return false;
        }
    }

    /** Reflective replacement for Forge's {@code Entity#getParts()}; empty when unsupported. */
    public static Entity[] getParts(Entity entity) {
        if (entity == null) return new Entity[0];
        try {
            Method getParts = entity.getClass().getMethod("getParts");
            Object result = getParts.invoke(entity);
            return result instanceof Entity[] parts ? parts : new Entity[0];
        } catch (ReflectiveOperationException | SecurityException e) {
            return new Entity[0];
        }
    }
}
