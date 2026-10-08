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

/** 追踪实际发送给每个玩家的最后一份宠物 NBT。 */
public final class PetSyncTracker {
    private static final Map<UUID, Map<UUID, byte[]>> LAST_SENT_FINGERPRINTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法创建 SHA-256 摘要器", e);
        }
    });

    private PetSyncTracker() {}

    /** 全列表请求会开启一个全新的客户端界面，因此会替换其基线。 */
    public static void replaceFullSnapshot(UUID playerUuid, Map<UUID, CompoundTag> snapshot) {
        Map<UUID, byte[]> fingerprints = new ConcurrentHashMap<>();
        snapshot.forEach((petUuid, nbt) -> fingerprints.put(petUuid, fingerprint(nbt)));
        LAST_SENT_FINGERPRINTS.put(playerUuid, fingerprints);
    }

    /** 记录候选内容，仅当它与此前的数据包不同时才返回 true。 */
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
            MessageDigest digest = SHA256.get();
            digest.reset();
            try (DataOutputStream output = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                NbtIo.write(nbt, output);
            }
            return digest.digest();
        } catch (IOException e) {
            throw new IllegalStateException("无法计算宠物 NBT 指纹", e);
        }
    }
}
