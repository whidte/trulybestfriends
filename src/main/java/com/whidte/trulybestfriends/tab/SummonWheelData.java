package com.whidte.trulybestfriends.tab;

import com.whidte.trulybestfriends.network.PetTeamData;
import com.whidte.trulybestfriends.network.SyncPetDataPacket;
import com.whidte.trulybestfriends.network.TeamDataPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Client-side snapshots needed by the summon wheel while the pet screen is closed. */
public final class SummonWheelData {
    private static final Map<UUID, CompoundTag> PETS = new LinkedHashMap<>();
    private static Map<UUID, CompoundTag> pendingFullList;
    private static CompoundTag teamData = new CompoundTag();

    private SummonWheelData() {}

    public static void applySyncPacket(SyncPetDataPacket packet) {
        switch (packet.getMode()) {
            case SyncPetDataPacket.MODE_FULL_LIST -> {
                if (packet.isFirstBatch() || pendingFullList == null) {
                    pendingFullList = new LinkedHashMap<>();
                }
                for (Tag raw : packet.getFullList()) {
                    if (raw instanceof CompoundTag entry && entry.hasUUID("UUID")) {
                        pendingFullList.put(entry.getUUID("UUID"), entry.getCompound("NBT").copy());
                    }
                }
                if (packet.isLastBatch()) {
                    PETS.clear();
                    PETS.putAll(pendingFullList);
                    pendingFullList = null;
                }
            }
            case SyncPetDataPacket.MODE_UPDATE -> {
                UUID uuid = packet.getPetUuid();
                CompoundTag merged = PETS.containsKey(uuid) ? PETS.get(uuid).copy() : new CompoundTag();
                CompoundTag update = packet.getPetNbt();
                for (String key : update.getAllKeys()) merged.put(key, update.get(key).copy());
                PETS.put(uuid, merged);
            }
            case SyncPetDataPacket.MODE_DELETE -> PETS.remove(packet.getPetUuid());
        }
    }

    public static void applyTeamData(TeamDataPacket packet) {
        teamData = packet.teamData().copy();
    }

    static CompoundTag petNbt(UUID uuid) {
        return PETS.get(uuid);
    }

    static boolean isSummonable(UUID uuid) {
        CompoundTag nbt = PETS.get(uuid);
        if (nbt == null || nbt.contains("Health") && nbt.getFloat("Health") <= 0.0F) return false;
        if (nbt.getBoolean("Recalled")) return true;
        return !nbt.getBoolean("Lost")
                && nbt.contains("Pos")
                && nbt.contains("Dimension");
    }

    static Map<Integer, UUID> selectedTeamSlots() {
        return teamSlots(selectedTeamColor());
    }

    /** Team color currently selected in the formation tab. */
    public static String selectedTeamColor() {
        String color = teamData.getString("SelectedTeam");
        if (!PetTeamData.TEAM_COLORS.contains(color)) color = PetTeamData.TEAM_COLORS.get(0);
        return color;
    }

    static Map<Integer, UUID> teamSlots(String color) {
        Map<Integer, UUID> result = new LinkedHashMap<>();
        CompoundTag teams = teamData.contains("Teams", Tag.TAG_COMPOUND)
                ? teamData.getCompound("Teams") : new CompoundTag();
        ListTag members = teams.getCompound(color).getList("Members", Tag.TAG_COMPOUND);
        for (Tag raw : members) {
            CompoundTag member = (CompoundTag) raw;
            if (member.hasUUID("UUID")) result.put(member.getInt("Slot"), member.getUUID("UUID"));
        }
        return result;
    }

    /** Resolves the persisted last summon (team color + slot) to a pet UUID, or null. */
    public static UUID resolveLastSummon() {
        CompoundTag lastSummon = teamData.contains("LastSummon", Tag.TAG_COMPOUND)
                ? teamData.getCompound("LastSummon") : null;
        if (lastSummon == null) return null;
        String color = lastSummon.getString("Color");
        int slot = lastSummon.getInt("Slot");
        if (!PetTeamData.TEAM_COLORS.contains(color) || slot < 1) return null;
        return teamSlots(color).get(slot);
    }
}
