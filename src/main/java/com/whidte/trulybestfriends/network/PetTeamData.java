package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Persistent per-player formation data stored beside the pet snapshots. */
public final class PetTeamData {
    public static final String FILE_NAME = "team.nbt";
    public static final List<String> TEAM_COLORS = List.of(
            "white", "purple", "red", "blue", "green", "black", "orange", "yellow");
    /** Highest numbered slot exposed by the 3x3 formation grid. */
    public static final int GRID_SLOT_COUNT = 8;
    private static final int VERSION = 1;

    private PetTeamData() {}

    /** Creates the file when absent and removes stale or structurally invalid members. */
    public static synchronized void ensureAndPrune(Path ownerDir) throws IOException {
        Files.createDirectories(ownerDir);
        File file = ownerDir.resolve(FILE_NAME).toFile();
        CompoundTag existing = file.exists() ? NbtFileIO.readCompressed(file) : new CompoundTag();
        CompoundTag normalized = normalize(existing, Config.maxPendingSummons,
                uuid -> Files.isRegularFile(ownerDir.resolve(uuid + ".nbt")));
        if (!file.exists() || !normalized.equals(existing)) {
            NbtFileIO.writeCompressed(normalized, file);
        }
    }

    /** Removes a pet from every formation in this owner's file. */
    public static synchronized void removePet(Path ownerDir, UUID petUuid) throws IOException {
        File file = ownerDir.resolve(FILE_NAME).toFile();
        if (!file.exists()) return;
        CompoundTag existing = NbtFileIO.readCompressed(file);
        CompoundTag normalized = normalize(existing, Config.maxPendingSummons,
                uuid -> !uuid.equals(petUuid) && Files.isRegularFile(ownerDir.resolve(uuid + ".nbt")));
        if (!normalized.equals(existing)) NbtFileIO.writeCompressed(normalized, file);
    }

    /** Reads the team file normalized, creating it when absent. */
    public static synchronized CompoundTag teamData(Path ownerDir) throws IOException {
        Files.createDirectories(ownerDir);
        return commit(ownerDir, readRaw(ownerDir));
    }

    /** Places a pet into a numbered slot of one color team, kicking the previous
     *  occupant and removing the pet from every other team it belonged to. */
    public static synchronized CompoundTag setMember(Path ownerDir, String color, int slot, UUID uuid) throws IOException {
        CompoundTag raw = readRaw(ownerDir);
        removeFromAllTeams(raw, uuid);
        CompoundTag team = teamTag(raw, color);
        ListTag members = new ListTag();
        for (Tag tag : team.getList("Members", Tag.TAG_COMPOUND)) {
            CompoundTag member = (CompoundTag) tag;
            if (member.getInt("Slot") != slot) members.add(member);
        }
        CompoundTag entry = new CompoundTag();
        entry.putInt("Slot", slot);
        entry.putUUID("UUID", uuid);
        members.add(entry);
        team.put("Members", members);
        return commit(ownerDir, raw);
    }

    /** Moves a member to another numbered slot, swapping with the occupant when present. */
    public static synchronized CompoundTag moveMember(Path ownerDir, String color, int fromSlot, int toSlot) throws IOException {
        if (fromSlot == toSlot) return teamData(ownerDir);
        CompoundTag raw = readRaw(ownerDir);
        CompoundTag team = teamTag(raw, color);
        CompoundTag from = null;
        CompoundTag to = null;
        for (Tag tag : team.getList("Members", Tag.TAG_COMPOUND)) {
            CompoundTag member = (CompoundTag) tag;
            int memberSlot = member.getInt("Slot");
            if (memberSlot == fromSlot) from = member;
            else if (memberSlot == toSlot) to = member;
        }
        if (from == null) return teamData(ownerDir);
        if (to == null) {
            from.putInt("Slot", toSlot);
        } else {
            from.putInt("Slot", toSlot);
            to.putInt("Slot", fromSlot);
        }
        return commit(ownerDir, raw);
    }

    /** Removes a pet from one color team. */
    public static synchronized CompoundTag removeMember(Path ownerDir, String color, UUID uuid) throws IOException {
        CompoundTag raw = readRaw(ownerDir);
        CompoundTag team = teamTag(raw, color);
        ListTag members = new ListTag();
        for (Tag tag : team.getList("Members", Tag.TAG_COMPOUND)) {
            CompoundTag member = (CompoundTag) tag;
            if (!uuid.equals(member.getUUID("UUID"))) members.add(member);
        }
        team.put("Members", members);
        return commit(ownerDir, raw);
    }

    /** Persists the currently selected team color. */
    public static synchronized CompoundTag setSelectedTeam(Path ownerDir, String color) throws IOException {
        CompoundTag raw = readRaw(ownerDir);
        raw.putString("SelectedTeam", color);
        return commit(ownerDir, raw);
    }

    /** Persists the last wheel-summoned member as team color + slot number. */
    public static synchronized CompoundTag setLastSummon(Path ownerDir, String color, int slot) throws IOException {
        CompoundTag raw = readRaw(ownerDir);
        CompoundTag lastSummon = new CompoundTag();
        lastSummon.putString("Color", color);
        lastSummon.putInt("Slot", slot);
        raw.put("LastSummon", lastSummon);
        return commit(ownerDir, raw);
    }

    private static CompoundTag readRaw(Path ownerDir) throws IOException {
        File file = ownerDir.resolve(FILE_NAME).toFile();
        return file.exists() ? NbtFileIO.readCompressed(file) : new CompoundTag();
    }

    private static CompoundTag commit(Path ownerDir, CompoundTag raw) throws IOException {
        CompoundTag normalized = normalize(raw, Config.maxPendingSummons,
                uuid -> Files.isRegularFile(ownerDir.resolve(uuid + ".nbt")));
        NbtFileIO.writeCompressed(normalized, ownerDir.resolve(FILE_NAME).toFile());
        return normalized;
    }

    private static void removeFromAllTeams(CompoundTag raw, UUID uuid) {
        for (String color : TEAM_COLORS) {
            CompoundTag team = teamTag(raw, color);
            ListTag members = new ListTag();
            for (Tag tag : team.getList("Members", Tag.TAG_COMPOUND)) {
                CompoundTag member = (CompoundTag) tag;
                if (!uuid.equals(member.getUUID("UUID"))) members.add(member);
            }
            team.put("Members", members);
        }
    }

    private static CompoundTag teamTag(CompoundTag raw, String color) {
        CompoundTag teams;
        if (raw.contains("Teams", Tag.TAG_COMPOUND)) {
            teams = raw.getCompound("Teams");
        } else {
            teams = new CompoundTag();
            raw.put("Teams", teams);
        }
        CompoundTag team;
        if (teams.contains(color, Tag.TAG_COMPOUND)) {
            team = teams.getCompound(color);
        } else {
            team = new CompoundTag();
            team.putString("Color", color);
            teams.put(color, team);
        }
        return team;
    }

    static CompoundTag normalize(CompoundTag source, int configuredCapacity, Predicate<UUID> isTrackedByOwner) {
        int capacity = Math.max(1, configuredCapacity);
        CompoundTag normalized = source.copy();
        normalized.putInt("Version", VERSION);
        normalized.putInt("Capacity", capacity);

        String selectedTeam = source.contains("SelectedTeam", Tag.TAG_STRING)
                ? source.getString("SelectedTeam") : "";
        if (!TEAM_COLORS.contains(selectedTeam)) {
            selectedTeam = TEAM_COLORS.get(0);
        }
        normalized.putString("SelectedTeam", selectedTeam);

        CompoundTag sourceLastSummon = source.contains("LastSummon", Tag.TAG_COMPOUND)
                ? source.getCompound("LastSummon") : new CompoundTag();
        CompoundTag lastSummon = new CompoundTag();
        String lastColor = sourceLastSummon.getString("Color");
        if (!TEAM_COLORS.contains(lastColor)) {
            lastColor = "";
        }
        int lastSlot = sourceLastSummon.getInt("Slot");
        if (lastSlot < 1 || lastSlot > GRID_SLOT_COUNT) {
            lastSlot = 0;
        }
        lastSummon.putString("Color", lastColor);
        lastSummon.putInt("Slot", lastSlot);
        normalized.put("LastSummon", lastSummon);

        CompoundTag sourceTeams = source.contains("Teams", Tag.TAG_COMPOUND)
                ? source.getCompound("Teams") : new CompoundTag();
        CompoundTag teams = new CompoundTag();
        Set<UUID> assignedPets = new HashSet<>();

        for (String color : TEAM_COLORS) {
            CompoundTag sourceTeam = sourceTeams.contains(color, Tag.TAG_COMPOUND)
                    ? sourceTeams.getCompound(color) : new CompoundTag();
            ListTag sourceMembers = sourceTeam.getList("Members", Tag.TAG_COMPOUND);
            List<Member> members = new ArrayList<>();
            Set<Integer> occupiedSlots = new HashSet<>();

            for (int i = 0; i < sourceMembers.size(); i++) {
                CompoundTag member = sourceMembers.getCompound(i);
                if (!member.hasUUID("UUID")) continue;
                UUID uuid = member.getUUID("UUID");
                int slot = member.getInt("Slot");
                if (slot < 1 || slot > GRID_SLOT_COUNT || occupiedSlots.contains(slot)
                        || assignedPets.contains(uuid) || !isTrackedByOwner.test(uuid)) continue;
                occupiedSlots.add(slot);
                assignedPets.add(uuid);
                members.add(new Member(slot, uuid));
            }

            members.sort(Comparator.comparingInt(Member::slot));
            if (members.size() > capacity) {
                members = new ArrayList<>(members.subList(0, capacity));
            }
            ListTag memberTags = new ListTag();
            for (Member member : members) {
                CompoundTag memberTag = new CompoundTag();
                memberTag.putInt("Slot", member.slot());
                memberTag.putUUID("UUID", member.uuid());
                memberTags.add(memberTag);
            }

            CompoundTag team = new CompoundTag();
            team.putString("Color", color);
            team.put("Members", memberTags);
            teams.put(color, team);
        }
        normalized.put("Teams", teams);
        return normalized;
    }

    private record Member(int slot, UUID uuid) {}
}
