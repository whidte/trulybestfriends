package com.whidte.trulybestfriends.compat;

import net.minecraftforge.fml.ModList;

public final class CuriosCompat {
    private static final boolean LOADED = ModList.get().isLoaded("curios");

    private CuriosCompat() {}

    public static void register() {
        if (LOADED) CuriosIntegration.register();
    }
}
