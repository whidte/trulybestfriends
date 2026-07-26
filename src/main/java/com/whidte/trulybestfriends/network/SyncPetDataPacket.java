package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Server → Client: pushes pet data to the client.
 *
 * Three modes:
 *  - FULL_LIST: one complete pet snapshot per packet
 *  - UPDATE:    single pet NBT updated (sent periodically for the selected pet, or on change)
 *  - DELETE:    a pet was removed (file deleted / pet permanently lost)
 *
 * This replaces the client-side disk polling in TrulyScreen.refreshSelectedFromDisk
 * and PetDataLoader.loadAll, fixing multiplayer correctness (client cannot read
 * server saves) and removing disk I/O contention in singleplayer.
 */
public class SyncPetDataPacket {
    public static final int MODE_FULL_LIST = 0;
    public static final int MODE_UPDATE = 1;
    public static final int MODE_DELETE = 2;
    /** Transport-only mode used when a logical packet exceeds the wire limit. */
    private static final int MODE_FRAGMENT = 3;
    public static final int MAX_FULL_LIST_ENTRIES = 1;
    /** Maximum encoded payload size for one custom packet (30 KiB). */
    public static final int MAX_PACKET_BYTES = 30 * 1024;
    /* Leave room for the fragment header and the custom-channel envelope. */
    private static final int WIRE_OVERHEAD_RESERVE_BYTES = 256;
    private static final int MAX_LOGICAL_PACKET_BYTES = MAX_PACKET_BYTES - WIRE_OVERHEAD_RESERVE_BYTES;
    private static final int MAX_FRAGMENT_CHUNK_BYTES = MAX_LOGICAL_PACKET_BYTES;
    private static final int MAX_FRAGMENT_COUNT = 4096;
    private static final Map<UUID, FragmentAccumulator> CLIENT_FRAGMENTS = new ConcurrentHashMap<>();

    private final int mode;
    private final UUID petUuid;          // used by UPDATE / DELETE; null for FULL_LIST
    private final CompoundTag petNbt;    // used by FULL_LIST (wrapped) / UPDATE; null for DELETE
    private final ListTag fullList;      // used by FULL_LIST only; null otherwise
    private final long serverTime;
    private final boolean firstBatch;
    private final boolean lastBatch;
    private final UUID fragmentId;
    private final int originalMode;
    private final int fragmentIndex;
    private final int fragmentCount;
    private final byte[] fragmentData;

    // --- Constructors ---

    private SyncPetDataPacket(int mode, UUID petUuid, CompoundTag petNbt, ListTag fullList, long serverTime,
                              boolean firstBatch, boolean lastBatch) {
        this(mode, petUuid, petNbt, fullList, serverTime, firstBatch, lastBatch,
                null, -1, -1, -1, null);
    }

    private SyncPetDataPacket(int mode, UUID petUuid, CompoundTag petNbt, ListTag fullList, long serverTime,
                              boolean firstBatch, boolean lastBatch, UUID fragmentId, int originalMode,
                              int fragmentIndex, int fragmentCount, byte[] fragmentData) {
        this.mode = mode;
        this.petUuid = petUuid;
        this.petNbt = petNbt;
        this.fullList = fullList;
        this.serverTime = serverTime;
        this.firstBatch = firstBatch;
        this.lastBatch = lastBatch;
        this.fragmentId = fragmentId;
        this.originalMode = originalMode;
        this.fragmentIndex = fragmentIndex;
        this.fragmentCount = fragmentCount;
        this.fragmentData = fragmentData;
    }

    /** Split a full snapshot into ordered packets containing exactly one pet each. */
    public static List<SyncPetDataPacket> fullListBatches(ListTag list) {
        List<SyncPetDataPacket> packets = new ArrayList<>();
        if (list.isEmpty()) {
            packets.add(fullListBatch(new ListTag(), true, true));
            return packets;
        }

        for (int offset = 0; offset < list.size(); offset += MAX_FULL_LIST_ENTRIES) {
            ListTag batch = new ListTag();
            int end = Math.min(offset + MAX_FULL_LIST_ENTRIES, list.size());
            for (int i = offset; i < end; i++) {
                batch.add(list.get(i).copy());
            }
            packets.add(fullListBatch(batch, offset == 0, end == list.size()));
        }
        return packets;
    }

    private static SyncPetDataPacket fullListBatch(ListTag list, boolean firstBatch, boolean lastBatch) {
        if (list.size() > MAX_FULL_LIST_ENTRIES) {
            throw new IllegalArgumentException("Full-list batch exceeds " + MAX_FULL_LIST_ENTRIES + " entries");
        }
        return new SyncPetDataPacket(MODE_FULL_LIST, null, null, list, System.currentTimeMillis(),
                firstBatch, lastBatch);
    }

    /** Single pet update. */
    public static SyncPetDataPacket update(UUID uuid, CompoundTag nbt) {
        return new SyncPetDataPacket(MODE_UPDATE, uuid, nbt, null, System.currentTimeMillis(), true, true);
    }

    /** Pet deletion notice. */
    public static SyncPetDataPacket delete(UUID uuid) {
        return new SyncPetDataPacket(MODE_DELETE, uuid, null, null, System.currentTimeMillis(), true, true);
    }

    /** Sends one logical packet, splitting its encoded form when necessary. */
    public static void sendToPlayer(ServerPlayer player, SyncPetDataPacket packet) {
        for (SyncPetDataPacket wirePacket : packet.splitForWire()) {
            trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), wirePacket);
        }
    }

    /** Returns this packet or ordered transport fragments small enough for the wire. */
    static List<SyncPetDataPacket> splitForWire(SyncPetDataPacket packet) {
        return packet.splitForWire();
    }

    private List<SyncPetDataPacket> splitForWire() {
        if (mode == MODE_FRAGMENT) return List.of(this);

        byte[] encoded = encodeLogical(this);
        if (encoded.length <= MAX_LOGICAL_PACKET_BYTES) return List.of(this);

        int count = (encoded.length + MAX_FRAGMENT_CHUNK_BYTES - 1) / MAX_FRAGMENT_CHUNK_BYTES;
        if (count > MAX_FRAGMENT_COUNT) {
            throw new IllegalArgumentException("Sync packet requires too many fragments: " + count);
        }

        UUID id = UUID.randomUUID();
        List<SyncPetDataPacket> fragments = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int start = index * MAX_FRAGMENT_CHUNK_BYTES;
            int end = Math.min(start + MAX_FRAGMENT_CHUNK_BYTES, encoded.length);
            byte[] chunk = java.util.Arrays.copyOfRange(encoded, start, end);
            fragments.add(new SyncPetDataPacket(MODE_FRAGMENT, null, null, null, 0L,
                    false, false, id, mode, index, count, chunk));
        }
        return fragments;
    }

    private static byte[] encodeLogical(SyncPetDataPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode(packet, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    // --- Codec ---

    public static void encode(SyncPetDataPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.mode);
        switch (packet.mode) {
            case MODE_FULL_LIST -> {
                // writeNbt expects a CompoundTag; wrap the ListTag in a holder.
                CompoundTag holder = new CompoundTag();
                holder.put("List", packet.fullList);
                holder.putLong("ServerTime", packet.serverTime);
                holder.putBoolean("FirstBatch", packet.firstBatch);
                holder.putBoolean("LastBatch", packet.lastBatch);
                buf.writeNbt(holder);
            }
            case MODE_UPDATE -> {
                buf.writeUUID(packet.petUuid);
                CompoundTag holder = new CompoundTag();
                holder.put("NBT", packet.petNbt);
                holder.putLong("ServerTime", packet.serverTime);
                buf.writeNbt(holder);
            }
            case MODE_DELETE -> {
                buf.writeUUID(packet.petUuid);
                buf.writeLong(packet.serverTime);
            }
            case MODE_FRAGMENT -> {
                buf.writeUUID(packet.fragmentId);
                buf.writeVarInt(packet.originalMode);
                buf.writeVarInt(packet.fragmentIndex);
                buf.writeVarInt(packet.fragmentCount);
                buf.writeByteArray(packet.fragmentData);
            }
            default -> throw new IllegalArgumentException("Unknown mode: " + packet.mode);
        }
    }

    public static SyncPetDataPacket decode(FriendlyByteBuf buf) {
        int mode = buf.readVarInt();
        return switch (mode) {
            case MODE_FULL_LIST -> {
                Tag tag = buf.readNbt();
                ListTag list = new ListTag();
                long serverTime = System.currentTimeMillis();
                boolean firstBatch = true;
                boolean lastBatch = true;
                if (tag instanceof CompoundTag ct) {
                    if (ct.contains("List")) list = ct.getList("List", Tag.TAG_COMPOUND);
                    if (ct.contains("ServerTime")) serverTime = ct.getLong("ServerTime");
                    if (ct.contains("FirstBatch")) firstBatch = ct.getBoolean("FirstBatch");
                    if (ct.contains("LastBatch")) lastBatch = ct.getBoolean("LastBatch");
                }
                yield new SyncPetDataPacket(MODE_FULL_LIST, null, null, list, serverTime,
                        firstBatch, lastBatch);
            }
            case MODE_UPDATE -> {
                UUID uuid = buf.readUUID();
                Tag tag = buf.readNbt();
                CompoundTag nbt = new CompoundTag();
                long serverTime = System.currentTimeMillis();
                if (tag instanceof CompoundTag ct) {
                    if (ct.contains("NBT")) {
                        nbt = ct.getCompound("NBT");
                        if (ct.contains("ServerTime")) serverTime = ct.getLong("ServerTime");
                    } else {
                        nbt = ct;
                    }
                }
                yield new SyncPetDataPacket(MODE_UPDATE, uuid, nbt, null, serverTime, true, true);
            }
            case MODE_DELETE -> {
                UUID uuid = buf.readUUID();
                long serverTime = buf.readLong();
                yield new SyncPetDataPacket(MODE_DELETE, uuid, null, null, serverTime, true, true);
            }
            case MODE_FRAGMENT -> {
                UUID fragmentId = buf.readUUID();
                int originalMode = buf.readVarInt();
                int fragmentIndex = buf.readVarInt();
                int fragmentCount = buf.readVarInt();
                if (originalMode < MODE_FULL_LIST || originalMode > MODE_DELETE
                        || fragmentCount <= 0 || fragmentCount > MAX_FRAGMENT_COUNT
                        || fragmentIndex < 0 || fragmentIndex >= fragmentCount) {
                    throw new IllegalArgumentException("Invalid sync packet fragment metadata");
                }
                byte[] data = buf.readByteArray(MAX_FRAGMENT_CHUNK_BYTES);
                yield new SyncPetDataPacket(MODE_FRAGMENT, null, null, null, 0L,
                        false, false, fragmentId, originalMode, fragmentIndex, fragmentCount, data);
            }
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };
    }

    // --- Handler (client side) ---

    public static void handle(SyncPetDataPacket packet, Supplier<NetworkEvent.Context> ctx) {
        final SyncPetDataPacket received = packet;
        ctx.get().enqueueWork(() -> {
            SyncPetDataPacket applyPacket = received;
            if (applyPacket.mode == MODE_FRAGMENT) {
                SyncPetDataPacket complete = collectFragment(applyPacket);
                if (complete == null) return;
                applyPacket = complete;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TrulyScreen screen) {
                screen.applySyncPacket(applyPacket);
            } else {
                // Cache for later use when the screen opens
                TrulyScreen.cacheSyncPacket(applyPacket);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    static SyncPetDataPacket collectFragment(SyncPetDataPacket packet) {
        FragmentAccumulator accumulator = CLIENT_FRAGMENTS.compute(packet.fragmentId, (id, existing) -> {
            if (existing == null || existing.count != packet.fragmentCount
                    || existing.originalMode != packet.originalMode) {
                return new FragmentAccumulator(packet.originalMode, packet.fragmentCount);
            }
            return existing;
        });
        if (!accumulator.add(packet.fragmentIndex, packet.fragmentData)) {
            CLIENT_FRAGMENTS.remove(packet.fragmentId, accumulator);
            return null;
        }
        if (!accumulator.complete()) return null;

        CLIENT_FRAGMENTS.remove(packet.fragmentId, accumulator);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(accumulator.join()));
        try {
            SyncPetDataPacket complete = decode(buffer);
            if (complete.mode != packet.originalMode) return null;
            return complete;
        } finally {
            buffer.release();
        }
    }

    private static final class FragmentAccumulator {
        private final int originalMode;
        private final int count;
        private final byte[][] chunks;
        private int received;
        private int totalBytes;

        private FragmentAccumulator(int originalMode, int count) {
            this.originalMode = originalMode;
            this.count = count;
            this.chunks = new byte[count][];
        }

        private synchronized boolean add(int index, byte[] data) {
            if (index < 0 || index >= count || data == null || data.length > MAX_FRAGMENT_CHUNK_BYTES) {
                return false;
            }
            if (chunks[index] == null) {
                chunks[index] = data;
                received++;
                totalBytes += data.length;
            }
            return true;
        }

        private synchronized boolean complete() {
            return received == count;
        }

        private synchronized byte[] join() {
            byte[] result = new byte[totalBytes];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            return result;
        }
    }

    // --- Accessors ---

    public int getMode() { return mode; }
    public UUID getPetUuid() { return petUuid; }
    public CompoundTag getPetNbt() { return petNbt; }
    public ListTag getFullList() { return fullList; }
    public long getServerTime() { return serverTime; }
    public boolean isFirstBatch() { return firstBatch; }
    public boolean isLastBatch() { return lastBatch; }
    public boolean isFragment() { return mode == MODE_FRAGMENT; }
    public int getFragmentIndex() { return fragmentIndex; }
    public int getFragmentCount() { return fragmentCount; }
}
