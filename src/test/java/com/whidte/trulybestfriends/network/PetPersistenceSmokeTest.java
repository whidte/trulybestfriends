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
        testTotemEffectNbtKey();
        testSummonClearsSittingState();
        System.out.println("PetPersistenceSmokeTest: 7/7 passed");
    }

    private static void testSummonClearsSittingState() {
        CompoundTag original = new CompoundTag();
        original.putBoolean("Sitting", true);
        original.putString("Marker", "preserved");

        CompoundTag prepared = TeleportPetToPlayerPacket.prepareSummonSnapshot(original);

        require(!prepared.getBoolean("Sitting"), "summoned pet remained ordered to sit");
        require(original.getBoolean("Sitting"), "summon preparation mutated the rollback snapshot");
        require("preserved".equals(prepared.getString("Marker")), "summon preparation lost unrelated NBT");
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

        require("saddle".equals(nbt.getCompound("SaddleItem").getString("Marker")),
                "slot 0 was not migrated to SaddleItem");
        require("armor".equals(nbt.getCompound("ArmorItem").getString("Marker")),
                "slot 1 was not migrated to ArmorItem");
        ListTag items = nbt.getList("Items", 10);
        require(items.size() == 2, "expected two chest items");
        require((items.getCompound(0).getByte("Slot") & 255) == 2,
                "vanilla 1.20.1 must retain absolute chest slot 2");
        require((items.getCompound(1).getByte("Slot") & 255) == 16,
                "vanilla 1.20.1 must retain absolute chest slot 16");
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

        require("saddle".equals(nbt.getCompound("SaddleItem").getString("Marker")),
                "item-handler saddle fallback was not restored");
        ListTag items = nbt.getList("Items", 10);
        require(items.size() == 1 && (items.getCompound(0).getByte("Slot") & 255) == 2,
                "item-handler chest fallback was not converted to vanilla slot 2");
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

    private static void testTotemEffectNbtKey() {
        CompoundTag nbt = new CompoundTag();
        ListTag legacyEffects = new ListTag();
        legacyEffects.add(new CompoundTag());
        nbt.put("ActiveEffects", legacyEffects);
        ListTag totemEffects = new ListTag();
        totemEffects.add(new CompoundTag());
        totemEffects.add(new CompoundTag());
        totemEffects.add(new CompoundTag());

        RevivePetPacket.replaceActiveEffects(nbt, totemEffects);

        require(nbt.getList("ActiveEffects", 10).size() == 3,
                "existing effects were not replaced by the 1.20.1 totem effects");
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
