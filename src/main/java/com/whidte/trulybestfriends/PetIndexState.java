package com.whidte.trulybestfriends;

import net.minecraft.nbt.CompoundTag;

/** Mutates fields stored inside one pet UUID node without replacing sibling data. */
public final class PetIndexState {
    private PetIndexState() {}

    public static boolean setRecalled(CompoundTag state, boolean recalled) {
        if (state.contains("Recalled") && state.getBoolean("Recalled") == recalled) return false;
        state.putBoolean("Recalled", recalled);
        return true;
    }
}
