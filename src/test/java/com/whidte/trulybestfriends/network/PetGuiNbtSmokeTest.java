package com.whidte.trulybestfriends.network;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PetGuiNbtSmokeTest {
    private PetGuiNbtSmokeTest() {}

    public static void main(String[] args) {
        testFullNbtPassengerFiltering();
        testFullListBatching();
        testUnchangedUpdateDeduplication();
        System.out.println("PetGuiNbtSmokeTest: passed");
    }

    private static void testFullNbtPassengerFiltering() {
        CompoundTag storedNbt = new CompoundTag();
        storedNbt.putString("EntityType", "minecraft:donkey");
        storedNbt.putFloat("Health", 20.0f);
        storedNbt.putFloat("MaxHealth", 30.0f);
        storedNbt.putInt("Priority", 3);
        storedNbt.putInt("Variant", 769);
        storedNbt.putString("variant", "minecraft:calico");
        storedNbt.putString("Type", "red");
        storedNbt.putByte("CollarColor", (byte) 14);
        storedNbt.putString("ModSkin", "ice_dragon_blue");
        storedNbt.putUUID("Owner", UUID.randomUUID());
        storedNbt.put("ArmorItem", largeCompound("VisualHorseArmor"));
        storedNbt.put("Passengers", largeList("PassengerData"));
        storedNbt.put("Items", largeList("InventoryData"));
        storedNbt.put("TBF_ItemHandlerItems", largeList("BackupInventoryData"));
        storedNbt.put("ForgeCaps", largeCompound("ForgeCapabilityData"));
        storedNbt.put("CuriosInventory", largeCompound("CuriosData"));

        CompoundTag clientNbt = RequestPetDataPacket.toClientNbt(storedNbt);

        require("minecraft:donkey".equals(clientNbt.getString("EntityType")),
                "entity type was not copied");
        require(clientNbt.getFloat("Health") == 20.0f, "health was not copied");
        require(clientNbt.getFloat("MaxHealth") == 30.0f, "max health was not copied");
        require(clientNbt.getInt("Priority") == 3, "priority was not copied");
        require(clientNbt.getInt("Variant") == 769, "numeric texture variant was not copied");
        require("minecraft:calico".equals(clientNbt.getString("variant")),
                "resource-location texture variant was not copied");
        require("red".equals(clientNbt.getString("Type")), "entity texture type was not copied");
        require(clientNbt.getByte("CollarColor") == 14, "collar color was not copied");
        require("ice_dragon_blue".equals(clientNbt.getString("ModSkin")),
                "modded scalar skin field was not copied");
        require(clientNbt.hasUUID("Owner"), "tame owner UUID was not copied");
        require(clientNbt.contains("ArmorItem"), "visual horse armor was not copied");
        require(!clientNbt.contains("Passengers"), "passenger entity tree leaked into client sync");
        require(clientNbt.contains("Items"), "vanilla inventory was not copied");
        require(clientNbt.contains("TBF_ItemHandlerItems"), "inventory backup was not copied");
        require(clientNbt.contains("ForgeCaps"), "Forge capabilities were not copied");
        require(clientNbt.contains("CuriosInventory"), "Curios data was not copied");
        require(storedNbt.contains("Items"), "creating GUI NBT modified the stored snapshot");
        require(storedNbt.contains("Passengers"), "filtering modified the stored snapshot");
    }

    private static void testFullListBatching() {
        requireBatchSizes(0, 0);
        requireSingleEntryPackets(16);
        requireSingleEntryPackets(17);
        List<SyncPetDataPacket> packets = requireSingleEntryPackets(33);

        require(packets.get(0).isFirstBatch(), "first packet was not marked as the first batch");
        require(!packets.get(0).isLastBatch(), "first packet was incorrectly marked as the last batch");
        require(!packets.get(1).isFirstBatch(), "middle packet was incorrectly marked as the first batch");
        require(!packets.get(1).isLastBatch(), "middle packet was incorrectly marked as the last batch");
        SyncPetDataPacket lastPacket = packets.get(packets.size() - 1);
        require(!lastPacket.isFirstBatch(), "last packet was incorrectly marked as the first batch");
        require(lastPacket.isLastBatch(), "last packet was not marked as the last batch");

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SyncPetDataPacket.encode(packets.get(1), buffer);
            SyncPetDataPacket decoded = SyncPetDataPacket.decode(buffer);
            require(decoded.getFullList().size() == 1, "codec changed the batch size");
            require(!decoded.isFirstBatch() && !decoded.isLastBatch(),
                    "codec changed the middle-batch markers");
        } finally {
            buffer.release();
        }
    }

    private static void testUnchangedUpdateDeduplication() {
        UUID playerUuid = UUID.randomUUID();
        UUID petUuid = UUID.randomUUID();
        CompoundTag baseline = new CompoundTag();
        baseline.putFloat("Health", 20.0f);

        PetSyncTracker.clearAll();
        PetSyncTracker.replaceFullSnapshot(playerUuid, Map.of(petUuid, baseline));
        require(!PetSyncTracker.shouldSendUpdate(playerUuid, petUuid, baseline.copy()),
                "unchanged NBT was not deduplicated");

        CompoundTag changed = baseline.copy();
        changed.putFloat("Health", 19.0f);
        require(PetSyncTracker.shouldSendUpdate(playerUuid, petUuid, changed),
                "changed NBT was incorrectly deduplicated");
        require(!PetSyncTracker.shouldSendUpdate(playerUuid, petUuid, changed.copy()),
                "repeated changed NBT was sent twice");

        PetSyncTracker.clearPlayer(playerUuid);
        require(PetSyncTracker.shouldSendUpdate(playerUuid, petUuid, changed),
                "clearing a player did not clear its sync baseline");
        PetSyncTracker.clearAll();
    }

    private static List<SyncPetDataPacket> requireSingleEntryPackets(int entryCount) {
        int[] expectedSizes = new int[entryCount];
        java.util.Arrays.fill(expectedSizes, 1);
        return requireBatchSizes(entryCount, expectedSizes);
    }

    private static List<SyncPetDataPacket> requireBatchSizes(int entryCount, int... expectedSizes) {
        ListTag entries = new ListTag();
        for (int i = 0; i < entryCount; i++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Index", i);
            entries.add(entry);
        }

        List<SyncPetDataPacket> packets = SyncPetDataPacket.fullListBatches(entries);
        int expectedPacketCount = expectedSizes.length == 0 ? 1 : expectedSizes.length;
        require(packets.size() == expectedPacketCount,
                "unexpected packet count for " + entryCount + " entries: " + packets.size());
        for (int i = 0; i < expectedSizes.length; i++) {
            require(packets.get(i).getFullList().size() == expectedSizes[i],
                    "unexpected batch size at index " + i + " for " + entryCount + " entries");
            require(packets.get(i).getFullList().size() <= SyncPetDataPacket.MAX_FULL_LIST_ENTRIES,
                    "packet contained more than one pet");
        }
        if (expectedSizes.length == 0) {
            require(packets.get(0).getFullList().isEmpty(), "empty snapshot contained entries");
            require(packets.get(0).isFirstBatch() && packets.get(0).isLastBatch(),
                    "empty snapshot was not a complete single batch");
        }
        return packets;
    }

    private static ListTag largeList(String marker) {
        ListTag list = new ListTag();
        list.add(largeCompound(marker));
        return list;
    }

    private static CompoundTag largeCompound(String marker) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Marker", marker.repeat(1024));
        return tag;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
