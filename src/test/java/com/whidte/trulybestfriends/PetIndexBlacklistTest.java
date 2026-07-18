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

        System.out.println("PetIndexBlacklistTest: 5/5 passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
