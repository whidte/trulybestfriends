package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.compat.DeathInterceptionCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.ChunkPos;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PetPersistenceSmokeTest {
    private PetPersistenceSmokeTest() {}

    public static void main(String[] args) throws Exception {
        testAtomicNbtReplacement();
        testNbtReplacementWithOpenReader();
        testStoredChunkResolution();
        testSnapshotFieldPreservation();
        testTotemEffectNbtKey();
        testSummonClearsSittingState();
        testStoredDeathState();
        testStoredPetDeleteReleasePolicy();
        testPassengerTreesAreExcluded();
        testStoredDeathIsNotLost();
        testDirectDieCompatibilityGuard();
        testThreeStateInventoryRestore();
        testPriorityNormalization();
        testRegistrationTimestamp();
        testIdempotentSnapshotWrite();
        System.out.println("PetPersistenceSmokeTest: 15/15 passed");
    }

    private static void testRegistrationTimestamp() throws Exception {
        Path directory = Files.createTempDirectory("tbf-registered-at-");
        File target = directory.resolve("new-pet.nbt").toFile();
        File legacy = directory.resolve("legacy-pet.nbt").toFile();
        try {
            // 全新文件首次落盘时写入当前时间
            CompoundTag snapshot = new CompoundTag();
            snapshot.putInt("Value", 1);
            long before = System.currentTimeMillis();
            PetIOUtil.writePetSnapshot(target, snapshot, false);
            long after = System.currentTimeMillis();

            long registeredAt = PetIOUtil.registeredAtFrom(NbtFileIO.readCompressed(target));
            require(registeredAt >= before && registeredAt <= after,
                    "a brand new pet file was not stamped with the current time");
            require(!snapshot.contains(PetIOUtil.REGISTERED_AT_KEY),
                    "stamping modified the caller's snapshot");

            // 后续保存沿用文件里已有的时间戳，而不是刷新成当前时间
            CompoundTag updated = new CompoundTag();
            updated.putInt("Value", 2);
            PetIOUtil.writePetSnapshot(target, updated, false);
            require(PetIOUtil.registeredAtFrom(NbtFileIO.readCompressed(target)) == registeredAt,
                    "the registration stamp changed on a later save");

            // 升级前就存在、且不含时间戳的旧文件保持缺失
            CompoundTag legacyStored = new CompoundTag();
            legacyStored.putInt("Value", 3);
            NbtFileIO.writeCompressed(legacyStored, legacy);
            PetIOUtil.writePetSnapshot(legacy, new CompoundTag(), false);
            require(!NbtFileIO.readCompressed(legacy).contains(PetIOUtil.REGISTERED_AT_KEY),
                    "an existing file without a stamp was retroactively stamped");
            require(PetIOUtil.registeredAtFrom(NbtFileIO.readCompressed(legacy)) == 0L,
                    "an unstamped legacy file did not read as unknown");

            // 缺失时间戳的标签一律读作未知，不会因为取不到键而报错
            require(PetIOUtil.registeredAtFrom(new CompoundTag()) == 0L,
                    "a missing registration stamp did not read as unknown");
            require(PetIOUtil.registeredAtFrom(null) == 0L,
                    "a null tag did not read as an unknown registration time");
        } finally {
            Files.deleteIfExists(target.toPath());
            Files.deleteIfExists(legacy.toPath());
            Files.deleteIfExists(directory);
        }
    }

    /**
     * 同一个快照重复落盘不应改写文件，内容真的变了才写。
     *
     * <p>文件名刻意使用合法 UUID：`writePetSnapshot` 只有在
     * 文件名能解析出 UUID 时才会走到“载入旧文件并整树比对”这条分支，
     * 而既有测试用的都是 `pet.nbt` 之类，从未覆盖到这里。</p>
     */
    private static void testIdempotentSnapshotWrite() throws Exception {
        Path directory = Files.createTempDirectory("tbf-idempotent-");
        File target = directory.resolve(UUID.randomUUID() + ".nbt").toFile();
        try {
            CompoundTag snapshot = new CompoundTag();
            snapshot.putFloat("Health", 20.0f);
            PetIOUtil.writePetSnapshot(target, snapshot, false);

            CompoundTag first = NbtFileIO.readCompressed(target);
            require(first.getFloat("Health") == 20.0f, "the first snapshot was not written");
            require(first.getInt("Priority") == PetIOUtil.DEFAULT_PRIORITY,
                    "the default priority was not folded into the stored snapshot");

            // 内容未变化：只比对、不重写，修改时间应当原地不动
            Thread.sleep(100L);
            long stamp = target.lastModified();
            PetIOUtil.writePetSnapshot(target, snapshot, false);
            require(NbtFileIO.readCompressed(target).equals(first),
                    "an unchanged snapshot produced different content");
            require(target.lastModified() == stamp, "an unchanged snapshot rewrote the file");

            // 内容真的变了：必须落盘，且不能丢掉已存的优先级
            CompoundTag changed = new CompoundTag();
            changed.putFloat("Health", 7.0f);
            PetIOUtil.writePetSnapshot(target, changed, false);
            require(NbtFileIO.readCompressed(target).getFloat("Health") == 7.0f,
                    "a changed snapshot was not persisted");
            require(NbtFileIO.readCompressed(target).getInt("Priority") == PetIOUtil.DEFAULT_PRIORITY,
                    "a rewrite lost the stored priority");

            // 周期保存走的是保留收回标志的那条重载，其幂等性同样依赖这次比对
            Thread.sleep(100L);
            long stamp2 = target.lastModified();
            PetIOUtil.writePetSnapshotPreservingRecall(target, changed);
            require(target.lastModified() == stamp2,
                    "an unchanged snapshot rewrote the file on the recall-preserving path");
        } finally {
            Files.deleteIfExists(target.toPath());
            Files.deleteIfExists(directory);
        }
    }

    private static void testPriorityNormalization() {
        require(PetIOUtil.clampPriority(-1) == PetIOUtil.MIN_PRIORITY, "low priority was not clamped");
        require(PetIOUtil.clampPriority(99) == PetIOUtil.MAX_PRIORITY,
                "high priority was not clamped");
        require(PetIOUtil.priorityFrom(new CompoundTag()) == PetIOUtil.DEFAULT_PRIORITY,
                "missing priority did not use the default");
        CompoundTag stored = new CompoundTag();
        stored.putInt("Priority", 3);
        require(PetIOUtil.priorityFrom(stored) == 3, "stored priority changed");
    }

    private static void testStoredChunkResolution() {
        CompoundTag explicit = new CompoundTag();
        explicit.putInt("ChunkX", 12);
        explicit.putInt("ChunkZ", -4);
        require(new ChunkPos(12, -4).equals(PetIOUtil.getStoredChunk(explicit)),
                "explicit stored chunk coordinates were not resolved");

        CompoundTag positionOnly = new CompoundTag();
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(33.5));
        position.add(DoubleTag.valueOf(70.0));
        position.add(DoubleTag.valueOf(-0.5));
        positionOnly.put("Pos", position);
        require(new ChunkPos(2, -1).equals(PetIOUtil.getStoredChunk(positionOnly)),
                "entity position was not converted to chunk coordinates");
        require(PetIOUtil.getStoredChunk(new CompoundTag()) == null,
                "missing position data produced a chunk");
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

        CompoundTag released = PetDeathState.prepareForUntrackedRelease(snapshot);
        require(released.getFloat("Health") == 1.0F, "released death snapshot was not made alive");
        require(!PetDeathState.isStoredDead(released), "released death snapshot retained the storage marker");
        require(PetDeathState.isStoredDead(snapshot), "release preparation mutated the stored snapshot");

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

    private static void testStoredPetDeleteReleasePolicy() {
        CompoundTag normal = new CompoundTag();
        normal.putFloat("Health", 20.0F);
        CompoundTag recalled = normal.copy();
        recalled.putBoolean("Recalled", true);
        CompoundTag dead = normal.copy();
        PetDeathState.markStoredDead(dead);

        require(PetDeathState.shouldReleaseBeforeUntracking(recalled, false),
                "default policy stopped recalled pet release");
        require(PetDeathState.shouldReleaseBeforeUntracking(dead, false),
                "default policy stopped dead pet release");
        require(PetDeathState.shouldReleaseBeforeUntracking(normal, true),
                "direct-delete policy affected a normal world pet");
        require(!PetDeathState.shouldReleaseBeforeUntracking(recalled, true),
                "direct-delete policy released a recalled pet");
        require(!PetDeathState.shouldReleaseBeforeUntracking(dead, true),
                "direct-delete policy released a dead pet");

        CompoundTag legacyNoReviveDeath = new CompoundTag();
        legacyNoReviveDeath.putFloat("Health", 0.0F);
        require(!PetDeathState.shouldReleaseBeforeUntracking(legacyNoReviveDeath, false, true),
                "legacy no-revive death snapshot was released and duplicated");
        require(PetDeathState.shouldReleaseBeforeUntracking(legacyNoReviveDeath, false, false),
                "legacy revivable death snapshot stopped being released");
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

    private static void testNbtReplacementWithOpenReader() throws Exception {
        Path directory = Files.createTempDirectory("tbf-open-nbt-test-");
        File target = directory.resolve("pet.nbt").toFile();
        try {
            CompoundTag first = new CompoundTag();
            first.putInt("Value", 1);
            NbtFileIO.writeCompressed(first, target);

            CompoundTag second = new CompoundTag();
            second.putInt("Value", 2);
            try (FileInputStream ignored = new FileInputStream(target)) {
                NbtFileIO.writeCompressed(second, target);
            }

            require(NbtFileIO.readCompressed(target).getInt("Value") == 2,
                    "replacement failed while another reader held the target open");
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
        ListTag staleCurrentEffects = new ListTag();
        staleCurrentEffects.add(new CompoundTag());
        staleCurrentEffects.add(new CompoundTag());
        nbt.put("active_effects", staleCurrentEffects);

        ListTag totemEffects = new ListTag();
        totemEffects.add(new CompoundTag());
        totemEffects.add(new CompoundTag());
        totemEffects.add(new CompoundTag());

        RevivePetPacket.replaceActiveEffects(nbt, totemEffects);

        require(!nbt.contains("ActiveEffects"), "legacy active-effect key was retained");
        require(nbt.getList("active_effects", 10).size() == 3,
                "existing effects were not replaced by the 1.21 totem effects");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
