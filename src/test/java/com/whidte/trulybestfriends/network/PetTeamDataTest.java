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
        CompoundTag withMoreCapacity = PetTeamData.normalize(source, 8, Set.of(first, second)::contains);
        require(withMoreCapacity.getInt("Capacity") == 8, "max capacity not honored");
        require("white".equals(normalized.getString("SelectedTeam")), "default selected team missing");

        source.putString("SelectedTeam", "purple");
        CompoundTag reselected = PetTeamData.normalize(source, 6, Set.of(first, second)::contains);
        require("purple".equals(reselected.getString("SelectedTeam")), "selected team not persisted");
        source.putString("SelectedTeam", "not_a_color");
        CompoundTag reselectedInvalid = PetTeamData.normalize(source, 6, Set.of(first, second)::contains);
        require("white".equals(reselectedInvalid.getString("SelectedTeam")), "invalid selected team not reset");
        require(normalized.getCompound("LastSummon").getString("Color").isEmpty()
                        && normalized.getCompound("LastSummon").getInt("Slot") == 0,
                "default last summon not empty");

        source.put("LastSummon", lastSummon("purple", 5));
        CompoundTag withLast = PetTeamData.normalize(source, 6, Set.of(first, second)::contains);
        require("purple".equals(withLast.getCompound("LastSummon").getString("Color"))
                        && withLast.getCompound("LastSummon").getInt("Slot") == 5,
                "last summon not persisted");
        source.put("LastSummon", lastSummon("not_a_color", 99));
        CompoundTag invalidLast = PetTeamData.normalize(source, 6, Set.of(first, second)::contains);
        require(invalidLast.getCompound("LastSummon").getString("Color").isEmpty()
                        && invalidLast.getCompound("LastSummon").getInt("Slot") == 0,
                "invalid last summon not reset");
        CompoundTag normalizedTeams = normalized.getCompound("Teams");
        require(normalizedTeams.getAllKeys().containsAll(PetTeamData.TEAM_COLORS), "not all eight teams exist");
        require(normalizedTeams.getList("unused", Tag.TAG_COMPOUND).isEmpty(), "unexpected root member list");

        ListTag keptWhite = normalizedTeams.getCompound("white").getList("Members", Tag.TAG_COMPOUND);
        ListTag keptGreen = normalizedTeams.getCompound("green").getList("Members", Tag.TAG_COMPOUND);
        require(keptWhite.size() == 2, "valid white members were retained");
        require(keptWhite.getCompound(0).getInt("Slot") == 2, "member slot was not retained");
        require(first.equals(keptWhite.getCompound(0).getUUID("UUID")), "member UUID was not retained");
        require(keptWhite.getCompound(1).getInt("Slot") == 7, "high grid slot was not retained");
        require(second.equals(keptWhite.getCompound(1).getUUID("UUID")), "second member UUID was not retained");
        require(keptGreen.isEmpty(), "cross-team duplicate UUID was not removed");
        require("purple".equals(normalizedTeams.getCompound("purple").getString("Color")),
                "team color metadata missing");

        CompoundTag withoutFirst = PetTeamData.normalize(normalized, 6, uuid -> !uuid.equals(first));
        ListTag whiteAfterRemoval = withoutFirst.getCompound("Teams").getCompound("white")
                .getList("Members", Tag.TAG_COMPOUND);
        require(whiteAfterRemoval.size() == 1
                        && second.equals(whiteAfterRemoval.getCompound(0).getUUID("UUID")),
                "removed pet remained in a team");

        java.util.Set<UUID> crowdedUuids = new java.util.HashSet<>();
        ListTag crowdedMembers = new ListTag();
        for (int slot = 1; slot <= 7; slot++) {
            UUID uuid = UUID.randomUUID();
            crowdedUuids.add(uuid);
            crowdedMembers.add(member(slot, uuid));
        }
        CompoundTag crowdedTeam = new CompoundTag();
        crowdedTeam.put("Members", crowdedMembers);
        CompoundTag crowdedTeams = new CompoundTag();
        crowdedTeams.put("white", crowdedTeam);
        CompoundTag crowdedSource = new CompoundTag();
        crowdedSource.put("Teams", crowdedTeams);
        CompoundTag crowded = PetTeamData.normalize(crowdedSource, 6, crowdedUuids::contains);
        require(crowded.getCompound("Teams").getCompound("white")
                .getList("Members", Tag.TAG_COMPOUND).size() == 6, "team member count cap not enforced");

        System.out.println("PetTeamDataTest: passed");
    }

    private static CompoundTag member(int slot, UUID uuid) {
        CompoundTag member = new CompoundTag();
        member.putInt("Slot", slot);
        member.putUUID("UUID", uuid);
        return member;
    }

    private static CompoundTag lastSummon(String color, int slot) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Color", color);
        tag.putInt("Slot", slot);
        return tag;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
