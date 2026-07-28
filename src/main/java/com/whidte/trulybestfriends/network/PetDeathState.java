package com.whidte.trulybestfriends.network;

import net.minecraft.nbt.CompoundTag;

/** Persistent state for pets intercepted before their normal death lifecycle. */
public final class PetDeathState {
    public static final String STATE_TAG = "TBF_State";
    public static final String DEAD_STORED = "dead_stored";

    private PetDeathState() {}

    public static void markStoredDead(CompoundTag nbt) {
        nbt.putString(STATE_TAG, DEAD_STORED);
        nbt.putFloat("Health", 0.0F);
        nbt.remove("Recalled");
    }

    public static void clear(CompoundTag nbt) {
        nbt.remove(STATE_TAG);
    }

    public static boolean isStoredDead(CompoundTag nbt) {
        return DEAD_STORED.equals(nbt.getString(STATE_TAG));
    }

    /** Accept pre-state-machine death snapshots so existing worlds remain revivable. */
    public static boolean isDeadSnapshot(CompoundTag nbt) {
        return isStoredDead(nbt)
                || (nbt.contains("Health") && nbt.getFloat("Health") <= 0.0F);
    }

    /** Whether deleting this stored snapshot should first restore it to the world. */
    public static boolean shouldReleaseBeforeUntracking(CompoundTag nbt, boolean deleteStoredPetsDirectly) {
        return shouldReleaseBeforeUntracking(nbt, deleteStoredPetsDirectly, false);
    }

    /**
     * @param noReviveEntity true for legacy unmarked death snapshots whose entity type is currently no-revive
     */
    public static boolean shouldReleaseBeforeUntracking(CompoundTag nbt, boolean deleteStoredPetsDirectly,
                                                        boolean noReviveEntity) {
        if (nbt != null && isDeadSnapshot(nbt) && noReviveEntity) {
            return false;
        }
        return nbt == null || !deleteStoredPetsDirectly
                || (!nbt.getBoolean("Recalled") && !isDeadSnapshot(nbt));
    }

    /** Creates a live release snapshot without mutating the revivable stored copy. */
    public static CompoundTag prepareForUntrackedRelease(CompoundTag nbt) {
        CompoundTag released = nbt.copy();
        clear(released);
        released.remove("Recalled");
        released.remove("DeathTime");
        released.putFloat("Health", 1.0F);
        return released;
    }
}
