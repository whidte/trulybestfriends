package com.whidte.trulybestfriends.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the last pet NBT actually sent to each player. */
public final class PetSyncTracker {
    private static final Map<UUID, Map<UUID, byte[]>> LAST_SENT_FINGERPRINTS = new ConcurrentHashMap<>();

    private PetSyncTracker() {}

    /** A full-list request starts a fresh client screen and therefore replaces its baseline. */
    public static void replaceFullSnapshot(UUID playerUuid, Map<UUID, CompoundTag> snapshot) {
        Map<UUID, byte[]> fingerprints = new ConcurrentHashMap<>();
        snapshot.forEach((petUuid, nbt) -> fingerprints.put(petUuid, fingerprint(nbt)));
        LAST_SENT_FINGERPRINTS.put(playerUuid, fingerprints);
    }

    /** Records the candidate and returns true only when it differs from the previous payload. */
    public static boolean shouldSendUpdate(UUID playerUuid, UUID petUuid, CompoundTag candidate) {
        byte[] candidateFingerprint = fingerprint(candidate);
        byte[] previous = LAST_SENT_FINGERPRINTS
                .computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<>())
                .put(petUuid, candidateFingerprint);
        return !Arrays.equals(candidateFingerprint, previous);
    }

    public static void forgetPet(UUID playerUuid, UUID petUuid) {
        Map<UUID, byte[]> playerCache = LAST_SENT_FINGERPRINTS.get(playerUuid);
        if (playerCache != null) playerCache.remove(petUuid);
    }

    public static void clearPlayer(UUID playerUuid) {
        LAST_SENT_FINGERPRINTS.remove(playerUuid);
    }

    public static void clearAll() {
        LAST_SENT_FINGERPRINTS.clear();
    }

    private static byte[] fingerprint(CompoundTag nbt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream output = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                NbtIo.write(nbt, output);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Unable to fingerprint pet NBT", e);
        }
    }
}
