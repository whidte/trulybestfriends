package com.whidte.trulybestfriends.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

public final class PetTeamDataTest {
    private PetTeamDataTest() {}

    public static void main(String[] args) throws Exception {
        testMutationsReachTheFile();
        require("white".equals(PetTeamData.colorAt(-1)), "negative color index not clamped");
        require("yellow".equals(PetTeamData.colorAt(99)), "high color index not clamped");
        require(PetTeamData.isValidSlot(1) && PetTeamData.isValidSlot(8)
                        && !PetTeamData.isValidSlot(0) && !PetTeamData.isValidSlot(9),
                "slot validation changed");

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
        require(PetTeamData.memberUuids(normalized, "white").equals(java.util.List.of(first, second)),
                "member UUID projection changed");
        require(keptGreen.size() == 1 && first.equals(keptGreen.getCompound(0).getUUID("UUID")),
                "multi-team membership was not retained");
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

    /**
     * 落盘回归：{@code commit()} 曾把「规范化结果」与「已被就地改写的内存 tag」
     * 相比较，于是只要改动本身已经是规范形态（例如把 SelectedTeam 换成另一个
     * 合法颜色），比对就相等、写盘被跳过，改动只剩下返回值里有。
     *
     * <p>而每个数据包处理器都是「先改、再用 {@code teamData(ownerDir)} 从文件
     * 读回权威快照」。写盘被跳过时读回的是旧值，客户端于是把刚切过去的队伍又
     * 拉回原来那支——表现就是「点旗帜闪一下就回到原队」。</p>
     */
    private static void testMutationsReachTheFile() throws Exception {
        Path ownerDir = Files.createTempDirectory("tbf-team-persist-");
        File file = ownerDir.resolve(PetTeamData.FILE_NAME).toFile();
        UUID pet = UUID.randomUUID();
        try {
            // 被追踪的宠物必须有同名 .nbt 快照，否则规范化会把成员剔除。
            NbtFileIO.writeCompressed(new CompoundTag(), ownerDir.resolve(pet + ".nbt").toFile());

            // 首次读取会建立规范化的队伍文件。
            require("white".equals(PetTeamData.teamData(ownerDir).getString("SelectedTeam")),
                    "a fresh team file did not default to white");

            // 切到另一支队伍：必须真的写进文件，也必须能被下一次读回。
            PetTeamData.setSelectedTeam(ownerDir, "purple");
            require("purple".equals(NbtFileIO.readCompressed(file).getString("SelectedTeam")),
                    "selecting a team was not persisted to disk");
            require("purple".equals(PetTeamData.teamData(ownerDir).getString("SelectedTeam")),
                    "the authoritative read-back did not see the selected team");

            // 入队：新成员追加在末尾，恰好与规范化排序一致，同样不能丢。
            PetTeamData.setMember(ownerDir, "white", 1, pet);
            require(PetTeamData.memberUuids(PetTeamData.teamData(ownerDir), "white").contains(pet),
                    "assigning a member was not persisted to disk");

            // 轮盘召唤记录：合法颜色 + 合法槽位，同样不能丢。
            PetTeamData.setLastSummon(ownerDir, "red", 2);
            require("red".equals(PetTeamData.teamData(ownerDir)
                            .getCompound("LastSummon").getString("Color")),
                    "the last-summon record was not persisted to disk");
        } finally {
            try (var files = Files.list(ownerDir)) {
                for (Path path : files.toList()) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(ownerDir);
        }
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
