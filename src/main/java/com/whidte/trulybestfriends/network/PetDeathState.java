package com.whidte.trulybestfriends.network;

import net.minecraft.nbt.CompoundTag;

/** 宠物在其正常死亡生命周期之前被拦截时的持久化状态。 */
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

    /** 接受状态机之前的死亡快照，使现有世界仍可复活。 */
    public static boolean isDeadSnapshot(CompoundTag nbt) {
        return isStoredDead(nbt)
                || (nbt.contains("Health") && nbt.getFloat("Health") <= 0.0F);
    }

    /** 删除此已存储快照时是否应先将其还原到世界中。 */
    public static boolean shouldReleaseBeforeUntracking(CompoundTag nbt, boolean deleteStoredPetsDirectly) {
        return shouldReleaseBeforeUntracking(nbt, deleteStoredPetsDirectly, false);
    }

    /**
     * @param noReviveEntity 对于其实体类型当前不可复活的旧版未标记死亡快照为 true
     */
    public static boolean shouldReleaseBeforeUntracking(CompoundTag nbt, boolean deleteStoredPetsDirectly,
                                                        boolean noReviveEntity) {
        if (nbt != null && isDeadSnapshot(nbt) && noReviveEntity) {
            return false;
        }
        return nbt == null || !deleteStoredPetsDirectly
                || (!nbt.getBoolean("Recalled") && !isDeadSnapshot(nbt));
    }

    /** 创建一个实时可用于释放的快照，同时不改动可复活的已存储副本。 */
    public static CompoundTag prepareForUntrackedRelease(CompoundTag nbt) {
        CompoundTag released = nbt.copy();
        clear(released);
        released.remove("Recalled");
        released.remove("DeathTime");
        released.putFloat("Health", 1.0F);
        return released;
    }
}
