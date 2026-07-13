package com.whidte.trulybestfriends.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PetPersistenceSmokeTest {
    private PetPersistenceSmokeTest() {}

    public static void main(String[] args) throws Exception {
        testLegacyHorseSlots();
        testItemHandlerHorseFallback();
        testValidVanillaItemsWin();
        testAtomicNbtReplacement();
        testSnapshotFieldPreservation();
        System.out.println("PetPersistenceSmokeTest: 5/5 passed");
    }

    private static void testLegacyHorseSlots() {
        CompoundTag nbt = new CompoundTag();
        nbt.putBoolean("ChestedHorse", true);
        ListTag backup = new ListTag();
        backup.add(itemAt(0, "saddle"));
        backup.add(itemAt(1, "armor"));
        backup.add(itemAt(2, "first_chest_slot"));
        backup.add(itemAt(16, "last_chest_slot"));
        nbt.put("TBF_ChestItems", backup);

        PetEntitySnapshot.migrateLegacyChestedHorseItems(nbt);

        ListTag items = nbt.getList("TBF_ChestItems", 10);
        require(items.size() == 4, "the 1.21 absolute-slot backup was modified");
        require((items.getCompound(2).getByte("Slot") & 255) == 2,
                "first chest slot changed during migration");
        require((items.getCompound(3).getByte("Slot") & 255) == 16,
                "last chest slot changed during migration");
        require(!nbt.contains("SaddleItem") && !nbt.contains("Items"),
                "1.20 slot semantics leaked into the 1.21 snapshot");
    }

    private static void testValidVanillaItemsWin() {
        CompoundTag nbt = new CompoundTag();
        nbt.putBoolean("ChestedHorse", true);
        ListTag vanilla = new ListTag();
        vanilla.add(itemAt(4, "vanilla"));
        nbt.put("Items", vanilla);
        ListTag backup = new ListTag();
        backup.add(itemAtInt(2, "backup"));
        nbt.put("TBF_ItemHandlerItems", backup);

        PetEntitySnapshot.migrateLegacyChestedHorseItems(nbt);

        ListTag result = nbt.getList("Items", 10);
        require(result.size() == 1 && "vanilla".equals(result.getCompound(0).getString("Marker")),
                "legacy backup overwrote valid vanilla Items");
    }

    private static void testItemHandlerHorseFallback() {
        CompoundTag nbt = new CompoundTag();
        nbt.putBoolean("ChestedHorse", true);
        ListTag backup = new ListTag();
        backup.add(itemAtInt(0, "saddle"));
        backup.add(itemAtInt(2, "chest_item"));
        nbt.put("TBF_ItemHandlerItems", backup);

        PetEntitySnapshot.migrateLegacyChestedHorseItems(nbt);

        ListTag items = nbt.getList("TBF_ChestItems", 10);
        require(items.size() == 2, "item-handler fallback was not converted");
        require(items.getCompound(0).getInt("Slot") == 0
                        && items.getCompound(1).getInt("Slot") == 2,
                "item-handler absolute slots changed during conversion");
    }

    private static void testAtomicNbtReplacement() throws Exception {
        Path directory = Files.createTempDirectory("tbf-nbt-test-");
        File target = directory.resolve("pet.nbt").toFile();
        try {
            CompoundTag first = new CompoundTag();
            first.putInt("Value", 1);
            NbtFileIO.writeCompressed(first, target);

            CompoundTag second = new CompoundTag();
            second.putInt("Value", 2);
            NbtFileIO.writeCompressed(second, target);

            require(NbtFileIO.readCompressed(target).getInt("Value") == 2,
                    "atomic replacement did not persist the new value");
            try (var files = Files.list(directory)) {
                require(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                        "temporary NBT file was not cleaned up");
            }
        } finally {
            Files.deleteIfExists(target.toPath());
            Files.deleteIfExists(directory);
        }
    }

    private static void testSnapshotFieldPreservation() throws Exception {
        Path directory = Files.createTempDirectory("tbf-snapshot-test-");
        File target = directory.resolve("pet.nbt").toFile();
        try {
            CompoundTag stored = new CompoundTag();
            stored.putInt("Priority", 3);
            stored.putBoolean("Recalled", true);
            NbtFileIO.writeCompressed(stored, target);

            CompoundTag snapshot = new CompoundTag();
            snapshot.putInt("Value", 7);
            snapshot.putLong("LastDeathTime", 123L);
            PetIOUtil.writePetSnapshotPreservingRecall(target, snapshot);

            CompoundTag preserved = NbtFileIO.readCompressed(target);
            require(preserved.getInt("Priority") == 3, "stored priority was not preserved");
            require(preserved.getBoolean("Recalled"), "stored recalled state was not preserved");
            require(!preserved.contains("LastDeathTime"), "transient death time was persisted");

            PetIOUtil.writePetSnapshot(target, snapshot, false);
            CompoundTag released = NbtFileIO.readCompressed(target);
            require(released.getInt("Priority") == 3, "priority changed while clearing recalled state");
            require(!released.contains("Recalled"), "explicit recalled state was not cleared");
        } finally {
            Files.deleteIfExists(target.toPath());
            Files.deleteIfExists(directory);
        }
    }

    private static CompoundTag itemAt(int slot, String marker) {
        CompoundTag item = new CompoundTag();
        item.putByte("Slot", (byte) slot);
        item.putString("Marker", marker);
        return item;
    }

    private static CompoundTag itemAtInt(int slot, String marker) {
        CompoundTag item = new CompoundTag();
        item.putInt("Slot", slot);
        item.putString("Marker", marker);
        return item;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
