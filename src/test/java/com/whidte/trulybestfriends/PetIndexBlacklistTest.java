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

        require(trulybestfriends.addBlacklistEntry(index, blocked), "new UUID was not added");
        require(trulybestfriends.isBlacklistEntry(index, blocked), "added UUID was not recognized");
        require(!trulybestfriends.addBlacklistEntry(index, blocked), "duplicate UUID was added");
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

        CompoundTag state = new CompoundTag();
        state.putBoolean("Recalled", false);
        state.put("Healing", new CompoundTag());
        require(PetIndexState.setRecalled(state, true),
                "changed recalled state was not written");
        require(state.contains("Healing"),
                "updating recalled state removed healing data");

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

        System.out.println("PetIndexBlacklistTest: 15/15 passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
