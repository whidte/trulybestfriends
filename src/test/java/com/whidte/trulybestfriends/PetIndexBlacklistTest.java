package com.whidte.trulybestfriends;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public final class PetIndexBlacklistTest {
    private PetIndexBlacklistTest() {}

    public static void main(String[] args) {
        CompoundTag index = new CompoundTag();
        CompoundTag existingPlayerData = new CompoundTag();
        existingPlayerData.putString("Marker", "preserved");
        index.put("Player", existingPlayerData);
        UUID blocked = UUID.randomUUID();

        require(PetIndexBlacklist.add(index, blocked), "new UUID was not added");
        require(PetIndexBlacklist.contains(index, blocked), "added UUID was not recognized");
        require(!PetIndexBlacklist.add(index, blocked), "duplicate UUID was added");
        require("preserved".equals(index.getCompound("Player").getString("Marker")),
                "adding a blacklist entry damaged existing pet-index data");
        require(index.getList("TBF_BlacklistedUUIDs", 8).size() == 1,
                "blacklist did not persist as one UUID string");

        UUID forcedPet = UUID.randomUUID();
        UUID forcedOwner = UUID.randomUUID();
        require(ForcedTrackingWhitelist.put(index, forcedPet, forcedOwner),
                "new forced-tracking entry was not added");
        require(forcedOwner.equals(ForcedTrackingWhitelist.get(index, forcedPet)),
                "forced-tracking owner was not resolved");
        require(forcedOwner.equals(ForcedTrackingWhitelist.readAll(index).get(forcedPet)),
                "forced-tracking entry was not restored from the index");
        require(!ForcedTrackingWhitelist.put(index, forcedPet, forcedOwner),
                "unchanged forced-tracking entry was rewritten");
        require(ForcedTrackingWhitelist.remove(index, forcedPet),
                "forced-tracking entry was not removed");
        require(ForcedTrackingWhitelist.get(index, forcedPet) == null,
                "removed forced-tracking entry was still resolved");

        UUID normallyTrackedPet = UUID.randomUUID();
        require(ForcedTrackingWhitelist.applyRemovalBlacklistPolicy(index, normallyTrackedPet),
                "normally tracked pet did not request blacklisting on removal");
        require(PetIndexBlacklist.contains(index, normallyTrackedPet),
                "normally tracked pet was not blacklisted on removal");

        UUID forceTrackedRemoval = UUID.randomUUID();
        require(ForcedTrackingWhitelist.put(index, forceTrackedRemoval, forcedOwner),
                "forced-tracking removal fixture was not added");
        require(!ForcedTrackingWhitelist.applyRemovalBlacklistPolicy(index, forceTrackedRemoval),
                "force-tracked pet requested blacklisting on removal");
        require(!PetIndexBlacklist.contains(index, forceTrackedRemoval),
                "force-tracked pet was blacklisted on removal");
        require(ForcedTrackingWhitelist.get(index, forceTrackedRemoval) == null,
                "force-tracking entry remained after removal");

        CompoundTag state = new CompoundTag();
        state.putBoolean("Recalled", false);
        state.put("Healing", new CompoundTag());
        require(PetIndexState.setRecalled(state, true),
                "changed recalled state was not written");
        require(state.contains("Healing"),
                "updating recalled state removed healing data");
        require(PetIndexState.setRideable(state),
                "new rideable state was not written");
        require(state.getBoolean("Rideable"),
                "rideable state was not persisted");
        require(!PetIndexState.setRideable(state),
                "unchanged rideable state was rewritten");
        require(state.contains("Healing") && state.getBoolean("Recalled"),
                "updating rideable state damaged existing pet state");

        UUID indexedPet = UUID.randomUUID();
        CompoundTag type = new CompoundTag();
        type.put(indexedPet.toString(), state);
        existingPlayerData.put("minecraft:wolf", type);
        require(PetIndexState.find(index, indexedPet) == state,
                "nested pet state was not found");
        int[] visits = {0};
        PetIndexState.visit(index, (uuid, visitedState) -> {
            visits[0]++;
            return false;
        });
        require(visits[0] == 1, "index traversal included metadata or skipped pet state");

        System.out.println("PetIndexBlacklistTest: 25/25 passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
