package com.whidte.trulybestfriends.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Set;
import java.util.UUID;

public final class PetTeamDataTest {
    private PetTeamDataTest() {}

    public static void main(String[] args) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID stale = UUID.randomUUID();

        CompoundTag source = new CompoundTag();
        CompoundTag teams = new CompoundTag();
        CompoundTag white = new CompoundTag();
        ListTag whiteMembers = new ListTag();
        whiteMembers.add(member(2, first));
        whiteMembers.add(member(2, second));
        whiteMembers.add(member(7, second));
        whiteMembers.add(member(3, stale));
        white.put("Members", whiteMembers);
        teams.put("white", white);
        CompoundTag green = new CompoundTag();
        ListTag greenMembers = new ListTag();
        greenMembers.add(member(1, first));
        greenMembers.add(member(1, second));
        green.put("Members", greenMembers);
        teams.put("green", green);
        source.put("Teams", teams);

        CompoundTag normalized = PetTeamData.normalize(source, 6, Set.of(first, second)::contains);
        require(normalized.getInt("Version") == 1, "schema version missing");
        require(normalized.getInt("Capacity") == 6, "configured capacity missing");
        CompoundTag normalizedTeams = normalized.getCompound("Teams");
        require(normalizedTeams.getAllKeys().containsAll(PetTeamData.TEAM_COLORS), "not all eight teams exist");
        require(normalizedTeams.getList("unused", Tag.TAG_COMPOUND).isEmpty(), "unexpected root member list");

        ListTag keptWhite = normalizedTeams.getCompound("white").getList("Members", Tag.TAG_COMPOUND);
        ListTag keptGreen = normalizedTeams.getCompound("green").getList("Members", Tag.TAG_COMPOUND);
        require(keptWhite.size() == 1, "invalid white members were retained");
        require(keptWhite.getCompound(0).getInt("Slot") == 2, "member slot was not retained");
        require(first.equals(keptWhite.getCompound(0).getUUID("UUID")), "member UUID was not retained");
        require(keptGreen.size() == 1 && second.equals(keptGreen.getCompound(0).getUUID("UUID")),
                "duplicate UUID or slot cleanup failed");
        require("purple".equals(normalizedTeams.getCompound("purple").getString("Color")),
                "team color metadata missing");

        CompoundTag withoutFirst = PetTeamData.normalize(normalized, 6, uuid -> !uuid.equals(first));
        require(withoutFirst.getCompound("Teams").getCompound("white")
                .getList("Members", Tag.TAG_COMPOUND).isEmpty(), "removed pet remained in a team");

        System.out.println("PetTeamDataTest: passed");
    }

    private static CompoundTag member(int slot, UUID uuid) {
        CompoundTag member = new CompoundTag();
        member.putInt("Slot", slot);
        member.putUUID("UUID", uuid);
        return member;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
