package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.compat.DeathInterceptionCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PetPersistenceSmokeTest {
    private PetPersistenceSmokeTest() {}

    public static void main(String[] args) throws Exception {
        testAtomicNbtReplacement();
        testSnapshotFieldPreservation();
        testTotemEffectNbtKey();
        testSummonClearsSittingState();
        testStoredDeathState();
        testPassengerTreesAreExcluded();
        testStoredDeathIsNotLost();
        testDirectDieCompatibilityGuard();
        testThreeStateInventoryRestore();
        System.out.println("PetPersistenceSmokeTest: 9/9 passed");
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

    private static void testStoredDeathState() throws Exception {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putFloat("Health", 20.0F);
        snapshot.putBoolean("Recalled", true);

        PetDeathState.markStoredDead(snapshot);
        require(PetDeathState.isStoredDead(snapshot), "stored-death marker was not written");
        require(PetDeathState.isDeadSnapshot(snapshot), "stored-dead snapshot was not recognized as dead");
        require(snapshot.getFloat("Health") == 0.0F, "stored-dead health was not normalized");
        require(!snapshot.contains("Recalled"), "stored-dead snapshot retained recalled state");

        PetDeathState.clear(snapshot);
        require(!PetDeathState.isStoredDead(snapshot), "stored-death marker was not cleared");
        require(PetDeathState.isDeadSnapshot(snapshot), "legacy health-based death compatibility was lost");

        Path directory = Files.createTempDirectory("tbf-death-state-test-");
        File target = directory.resolve("pet.nbt").toFile();
        try {
            CompoundTag recalled = new CompoundTag();
            recalled.putBoolean("Recalled", true);
            NbtFileIO.writeCompressed(recalled, target);

            PetDeathState.markStoredDead(snapshot);
            PetIOUtil.writePetSnapshotPreservingRecall(target, snapshot);
            CompoundTag stored = NbtFileIO.readCompressed(target);
            require(!stored.contains("Recalled"), "stored death inherited a stale recalled state");
        } finally {
            Files.deleteIfExists(target.toPath());
            Files.deleteIfExists(directory);
        }
    }

    private static void testPassengerTreesAreExcluded() {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("Marker", "root");
        ListTag passengers = new ListTag();
        CompoundTag passenger = new CompoundTag();
        passenger.putString("id", "minecraft:pig");
        passengers.add(passenger);
        snapshot.put("Passengers", passengers);

        CompoundTag root = PetEntitySnapshot.copyRootEntityOnly(snapshot);

        require(!root.contains("Passengers"), "passenger entity tree remained in the root snapshot");
        require("root".equals(root.getString("Marker")), "root entity data was removed with passengers");
        require(snapshot.contains("Passengers"), "passenger filtering mutated the rollback snapshot");
    }

    private static void testStoredDeathIsNotLost() {
        CompoundTag living = new CompoundTag();
        living.putFloat("Health", 20.0F);
        require(RequestPetDataPacket.shouldMarkLost(living, false),
                "an unloaded living pet was not marked lost");
        require(!RequestPetDataPacket.shouldMarkLost(living, true),
                "a loaded living pet was marked lost");

        CompoundTag storedDead = living.copy();
        PetDeathState.markStoredDead(storedDead);
        require(!RequestPetDataPacket.shouldMarkLost(storedDead, false),
                "a stored-dead pet was marked lost");

        CompoundTag legacyDead = new CompoundTag();
        legacyDead.putFloat("Health", 0.0F);
        require(!RequestPetDataPacket.shouldMarkLost(legacyDead, false),
                "a legacy dead snapshot was marked lost");
    }

    private static void testDirectDieCompatibilityGuard() {
        require(DeathInterceptionCompat.isDirectDieInterceptionSafe(LivingEntity.class),
                "the inherited LivingEntity.die implementation was not considered safe");
        require(!DeathInterceptionCompat.isDirectDieInterceptionSafe(TamableAnimal.class),
                "an overridden die implementation was considered safe to intercept");
    }

    private static void testThreeStateInventoryRestore() {
        List<String> backup = List.of("diamond:2", "gold_ingot:1");

        List<String> matched = new ArrayList<>(backup);
        int[] writes = {0};
        var matchedResult = TeleportPetToPlayerPacket.restoreInventoryIfSafe(
                matched.size(), matched::get, backup, (slot, stack) -> {
                    writes[0]++;
                    matched.set(slot, stack);
                }, String::isEmpty, String::equals, value -> value);
        require(matchedResult == TeleportPetToPlayerPacket.InventoryRestoreResult.MATCHED,
                "an identical live inventory was not recognized");
        require(writes[0] == 0, "an identical live inventory was rewritten");

        List<String> empty = new ArrayList<>(List.of("", ""));
        var restoredResult = TeleportPetToPlayerPacket.restoreInventoryIfSafe(
                empty.size(), empty::get, backup, empty::set,
                String::isEmpty, String::equals, value -> value);
        require(restoredResult == TeleportPetToPlayerPacket.InventoryRestoreResult.RESTORED,
                "an empty live inventory was not restored");
        require(empty.equals(backup),
                "the backup was not restored exactly");

        List<String> partial = new ArrayList<>(List.of(backup.get(0), ""));
        var conflictResult = TeleportPetToPlayerPacket.restoreInventoryIfSafe(
                partial.size(), partial::get, backup, partial::set,
                String::isEmpty, String::equals, value -> value);
        require(conflictResult == TeleportPetToPlayerPacket.InventoryRestoreResult.CONFLICT,
                "a partial live inventory was not treated as a conflict");
        require(partial.get(1).isEmpty(), "a conflict merged stale backup items into live slots");
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
