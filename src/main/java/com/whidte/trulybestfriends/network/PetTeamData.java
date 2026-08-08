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
            "white", "green", "black", "orange", "yellow", "blue", "red", "purple");
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

    static CompoundTag normalize(CompoundTag source, int configuredCapacity, Predicate<UUID> isTrackedByOwner) {
        int capacity = Math.max(1, configuredCapacity);
        CompoundTag normalized = source.copy();
        normalized.putInt("Version", VERSION);
        normalized.putInt("Capacity", capacity);

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
                if (slot < 1 || slot > capacity || occupiedSlots.contains(slot)
                        || assignedPets.contains(uuid) || !isTrackedByOwner.test(uuid)) continue;
                occupiedSlots.add(slot);
                assignedPets.add(uuid);
                members.add(new Member(slot, uuid));
            }

            members.sort(Comparator.comparingInt(Member::slot));
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
