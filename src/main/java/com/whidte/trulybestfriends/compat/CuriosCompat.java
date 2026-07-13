package com.whidte.trulybestfriends.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

public final class CuriosCompat {
    private static final boolean LOADED = ModList.get().isLoaded("curios");

    private CuriosCompat() {}

    public static void register() {
        if (LOADED) CuriosIntegration.register();
    }

    public static void backup(Entity entity, CompoundTag destination) {
        if (LOADED) CuriosIntegration.backup(entity, destination);
    }

    public static void restoreAfterSpawn(Entity entity, CompoundTag source) {
        if (LOADED) CuriosIntegration.restoreAfterSpawn(entity, source);
    }
}
