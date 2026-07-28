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
        require(index.getList(PetIndexBlacklist.KEY, 8).size() == 1,
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

        System.out.println("PetIndexBlacklistTest: 13/13 passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
