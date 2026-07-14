package com.whidte.trulybestfriends.tab;

import net.minecraft.nbt.CompoundTag;

public final class PetListFilterSmokeTest {
    private PetListFilterSmokeTest() {}

    public static void main(String[] args) {
        CompoundTag wolf = new CompoundTag();
        wolf.putString("EntityType", "minecraft:wolf");

        require(TrulyScreen.matchesPetFilter(wolf, "", "", "Buddy"),
                "empty filters hid a pet");
        require(TrulyScreen.matchesPetFilter(wolf, "minecraft:wolf", "", "Buddy"),
                "matching species was rejected");
        require(!TrulyScreen.matchesPetFilter(wolf, "minecraft:cat", "", "Buddy"),
                "non-matching species was accepted");
        require(TrulyScreen.matchesPetFilter(wolf, "minecraft:wolf", "  bud  ", "Buddy"),
                "trimmed case-insensitive name search failed");
        require(!TrulyScreen.matchesPetFilter(wolf, "minecraft:wolf", "cat", "Buddy"),
                "non-matching name search was accepted");

        require(SpeciesDropdown.visibleOptionCount(14) == 14,
                "13 species plus the all option should fit without scrolling");
        require(SpeciesDropdown.maxScrollOffset(14) == 0,
                "the species dropdown scrolled before the 13-species limit");
        require(SpeciesDropdown.maxScrollOffset(15) == 1,
                "the species dropdown did not scroll after the 13-species limit");

        System.out.println("PetListFilterSmokeTest: passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
