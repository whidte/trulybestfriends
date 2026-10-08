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

/** 存储在每个宠物快照旁边的、按玩家持久化的编队数据。 */
public final class PetTeamData {
    public static final String FILE_NAME = "team.nbt";
    public static final List<String> TEAM_COLORS = List.of(
            "white", "purple", "red", "blue", "green", "black", "orange", "yellow");
    /** 3x3 编队网格所暴露的最高编号槽位。 */
    public static final int GRID_SLOT_COUNT = 8;
    private static final int VERSION = 1;

    private PetTeamData() {}

    /** 把来自网络的不可信索引解析为某个已配置的队伍颜色。 */
    public static String colorAt(int index) {
        return TEAM_COLORS.get(Math.max(0, Math.min(TEAM_COLORS.size() - 1, index)));
    }

    public static boolean isValidSlot(int slot) {
        return slot >= 1 && slot <= GRID_SLOT_COUNT;
    }

    /** 返回成员 UUID，而不向数据包处理器暴露持久化的 NBT 布局。 */
    public static List<UUID> memberUuids(CompoundTag data, String color) {
        List<UUID> members = new ArrayList<>();
        for (Tag tag : teamMembers(data, color)) {
            CompoundTag member = (CompoundTag) tag;
            if (member.hasUUID("UUID")) members.add(member.getUUID("UUID"));
        }
        return members;
    }

    /** 当文件不存在时创建它，并移除过期或结构无效的成员。 */
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

    /** 从该主人文件中的所有编队中移除某个宠物。 */
    public static synchronized void removePet(Path ownerDir, UUID petUuid) throws IOException {
        File file = ownerDir.resolve(FILE_NAME).toFile();
        if (!file.exists()) return;
        CompoundTag existing = NbtFileIO.readCompressed(file);
        CompoundTag normalized = normalize(existing, Config.maxPendingSummons,
                uuid -> !uuid.equals(petUuid) && Files.isRegularFile(ownerDir.resolve(uuid + ".nbt")));
        if (!normalized.equals(existing)) NbtFileIO.writeCompressed(normalized, file);
    }

    /** 以规范化方式读取队伍文件，文件不存在时创建它。 */
    public static synchronized CompoundTag teamData(Path ownerDir) throws IOException {
        Files.createDirectories(ownerDir);
        CompoundTag stored = readRaw(ownerDir);
        return commit(ownerDir, stored, stored);
    }

    /** 把宠物放入某个颜色队伍的一个编号槽位，踢出先前的
     *  占用者。该宠物可保留其在其他队伍中的成员身份。 */
    public static synchronized CompoundTag setMember(Path ownerDir, String color, int slot, UUID uuid) throws IOException {
        CompoundTag stored = teamData(ownerDir);
        ListTag currentMembers = teamMembers(stored, color);
        if (!isValidSlot(slot) || uuid == null
                || !Files.isRegularFile(ownerDir.resolve(uuid + ".nbt"))) {
            return commit(ownerDir, stored, stored);
        }

        boolean alreadyMember = false;
        boolean slotOccupied = false;
        for (Tag tag : currentMembers) {
            CompoundTag member = (CompoundTag) tag;
            alreadyMember |= member.hasUUID("UUID") && uuid.equals(member.getUUID("UUID"));
            slotOccupied |= member.getInt("Slot") == slot;
        }
        if (currentMembers.size() >= stored.getInt("Capacity") && !alreadyMember && !slotOccupied) {
            return commit(ownerDir, stored, stored);
        }

        CompoundTag updated = stored.copy();
        removeFromTeam(updated, color, uuid);
        CompoundTag team = teamTag(updated, color);
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
        return commit(ownerDir, stored, updated);
    }

    /** 把成员移动到另一个编号槽位，若该槽位有占用者则与之交换。 */
    public static synchronized CompoundTag moveMember(Path ownerDir, String color, int fromSlot, int toSlot) throws IOException {
        if (!isValidSlot(fromSlot) || !isValidSlot(toSlot) || fromSlot == toSlot) {
            return teamData(ownerDir);
        }
        CompoundTag stored = readRaw(ownerDir);
        CompoundTag updated = stored.copy();
        CompoundTag team = teamTag(updated, color);
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
        return commit(ownerDir, stored, updated);
    }

    /** 从某个颜色队伍中移除某个宠物。 */
    public static synchronized CompoundTag removeMember(Path ownerDir, String color, UUID uuid) throws IOException {
        if (uuid == null) return teamData(ownerDir);
        CompoundTag stored = readRaw(ownerDir);
        CompoundTag updated = stored.copy();
        removeFromTeam(updated, color, uuid);
        return commit(ownerDir, stored, updated);
    }

    /** 持久化当前选中的队伍颜色。 */
    public static synchronized CompoundTag setSelectedTeam(Path ownerDir, String color) throws IOException {
        CompoundTag stored = readRaw(ownerDir);
        CompoundTag updated = stored.copy();
        updated.putString("SelectedTeam", color);
        return commit(ownerDir, stored, updated);
    }

    /** 把最后一次通过轮盘召唤的成员持久化为队伍颜色 + 槽位编号。 */
    public static synchronized CompoundTag setLastSummon(Path ownerDir, String color, int slot) throws IOException {
        if (!isValidSlot(slot)) return teamData(ownerDir);
        CompoundTag stored = readRaw(ownerDir);
        CompoundTag updated = stored.copy();
        CompoundTag lastSummon = new CompoundTag();
        lastSummon.putString("Color", color);
        lastSummon.putInt("Slot", slot);
        updated.put("LastSummon", lastSummon);
        return commit(ownerDir, stored, updated);
    }

    private static CompoundTag readRaw(Path ownerDir) throws IOException {
        File file = ownerDir.resolve(FILE_NAME).toFile();
        return file.exists() ? NbtFileIO.readCompressed(file) : new CompoundTag();
    }

    /**
     * 把改动规范化后写回文件，并返回规范化结果。
     *
     * <p>{@code stored} 必须是「改动之前文件里的内容」，{@code updated} 才是
     * 改动后的版本：是否需要写盘，看的是规范化结果与磁盘上的旧内容是否一致。
     * 这里曾经比较的是「已被就地改写的内存 tag」自己——只要改动本身就是规范
     * 形态（把 SelectedTeam 换成另一个合法颜色、把新成员追加在末尾、写入合法
     * 的 LastSummon），比对就相等、写盘被跳过，文件停留在旧值。而每个数据包
     * 处理器都是「先改、再用 {@link #teamData} 从文件读回权威快照」，读回旧值
     * 后客户端会把刚切过去的队伍又拉回原来那支（表现为「点旗帜闪一下就回到
     * 原队」）。</p>
     */
    private static CompoundTag commit(Path ownerDir, CompoundTag stored, CompoundTag updated) throws IOException {
        Path file = ownerDir.resolve(FILE_NAME);
        CompoundTag normalized = normalize(updated, Config.maxPendingSummons,
                uuid -> Files.isRegularFile(ownerDir.resolve(uuid + ".nbt")));
        if (!Files.isRegularFile(file) || !normalized.equals(stored)) {
            NbtFileIO.writeCompressed(normalized, file.toFile());
        }
        return normalized;
    }

    private static void removeFromTeam(CompoundTag raw, String color, UUID uuid) {
        CompoundTag team = teamTag(raw, color);
        ListTag members = new ListTag();
        for (Tag tag : team.getList("Members", Tag.TAG_COMPOUND)) {
            CompoundTag member = (CompoundTag) tag;
            if (!uuid.equals(member.getUUID("UUID"))) members.add(member);
        }
        team.put("Members", members);
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

    private static ListTag teamMembers(CompoundTag data, String color) {
        return data.getCompound("Teams").getCompound(color).getList("Members", Tag.TAG_COMPOUND);
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
        if (!isValidSlot(lastSlot)) {
            lastSlot = 0;
        }
        lastSummon.putString("Color", lastColor);
        lastSummon.putInt("Slot", lastSlot);
        normalized.put("LastSummon", lastSummon);

        CompoundTag sourceTeams = source.contains("Teams", Tag.TAG_COMPOUND)
                ? source.getCompound("Teams") : new CompoundTag();
        CompoundTag teams = new CompoundTag();

        for (String color : TEAM_COLORS) {
            CompoundTag sourceTeam = sourceTeams.contains(color, Tag.TAG_COMPOUND)
                    ? sourceTeams.getCompound(color) : new CompoundTag();
            ListTag sourceMembers = sourceTeam.getList("Members", Tag.TAG_COMPOUND);
            List<Member> members = new ArrayList<>();
            Set<Integer> occupiedSlots = new HashSet<>();
            Set<UUID> teamPets = new HashSet<>();

            for (int i = 0; i < sourceMembers.size(); i++) {
                CompoundTag member = sourceMembers.getCompound(i);
                if (!member.hasUUID("UUID")) continue;
                UUID uuid = member.getUUID("UUID");
                int slot = member.getInt("Slot");
                if (!isValidSlot(slot) || occupiedSlots.contains(slot)
                        || teamPets.contains(uuid) || !isTrackedByOwner.test(uuid)) continue;
                occupiedSlots.add(slot);
                teamPets.add(uuid);
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
