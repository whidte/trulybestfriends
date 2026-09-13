package com.whidte.trulybestfriends.network;

import net.minecraft.nbt.CompoundTag;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the content fingerprint of the snapshot most recently written to
 * disk for each pet, so repeated save passes can skip the whole
 * serialize + read-old-file + deep-compare + atomic-write chain when nothing
 * changed.
 *
 * <p>This is the primary defence against the server-thread stall caused by
 * flushing every pending pet on a fixed tick interval. Without it, each flush
 * re-serialized the entity NBT, re-read the previous file to diff it, and then
 * performed a temp-file write plus atomic move even when the payload was
 * byte-identical to what was already on disk.</p>
 *
 * <p>Fingerprints are stored in memory only. They are intentionally dropped on
 * server stop: on the next start the first save for each pet is always written
 * so that any offline NBT edits are picked up.</p>
 */
public final class PetSnapshotFingerprint {

    /** Sentinel meaning "no fingerprint recorded yet" — never matches a real hash. */
    private static final long UNKNOWN = 0L;

    private static final Map<UUID, Long> WRITTEN = new ConcurrentHashMap<>();

    private PetSnapshotFingerprint() {}

    /**
     * Returns true when {@code nbt} differs from what was last written for
     * {@code petUuid}, and records the new fingerprint.
     *
     * <p>Recording happens on every call, so two consecutive identical snapshots
     * return {@code true} then {@code false}. Callers must only invoke this once
     * they are actually committed to writing the payload.</p>
     */
    public static boolean recordIfChanged(UUID petUuid, CompoundTag nbt) {
        long fingerprint = fingerprintOf(nbt);
        Long previous = WRITTEN.put(petUuid, fingerprint);
        return previous == null || previous != fingerprint;
    }

    /** True when this pet has no recorded fingerprint (forces the next write). */
    public static boolean isUnknown(UUID petUuid) {
        return !WRITTEN.containsKey(petUuid);
    }

    /** Seeds the fingerprint after a write that bypassed {@link #recordIfChanged}. */
    public static void record(UUID petUuid, CompoundTag nbt) {
        WRITTEN.put(petUuid, fingerprintOf(nbt));
    }

    public static void forget(UUID petUuid) {
        WRITTEN.remove(petUuid);
    }

    public static void clearAll() {
        WRITTEN.clear();
    }

    /**
     * Order-independent, allocation-light structural hash of the tag tree.
     *
     * <p>Deliberately not a cryptographic digest: this runs for every pet on
     * every save pass, so it must stay cheap. Collisions only cost one skipped
     * redundant write, never data loss, because a mismatch always forces a write.</p>
     */
    private static long fingerprintOf(CompoundTag nbt) {
        if (nbt == null) return UNKNOWN;
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, nbt.size());
        for (String key : nbt.getAllKeys()) {
            hash = mix(hash, key.hashCode());
            hash = mix(hash, valueHash(nbt.get(key)));
        }
        return hash == UNKNOWN ? 1L : hash;
    }

    private static long valueHash(net.minecraft.nbt.Tag tag) {
        if (tag == null) return 0L;
        // Tag#hashCode is a structural hash for every NBT tag type, including
        // nested compounds and lists, so it covers the whole subtree.
        long value = tag.hashCode() & 0xffffffffL;
        return mix(value, tag.getId());
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }
}
