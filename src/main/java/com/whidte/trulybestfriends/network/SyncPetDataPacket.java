package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
public class SyncPetDataPacket implements CustomPacketPayload {
    public static final Type<SyncPetDataPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "sync_pet_data"));
    public static final StreamCodec<FriendlyByteBuf, SyncPetDataPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), SyncPetDataPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static final int MODE_FULL_LIST = 0;
    public static final int MODE_UPDATE = 1;
    public static final int MODE_DELETE = 2;
    public static final int MAX_FULL_LIST_ENTRIES = 1;

    private final int mode;
    private final UUID petUuid;          // used by UPDATE / DELETE; null for FULL_LIST
    private final CompoundTag petNbt;    // used by FULL_LIST (wrapped) / UPDATE; null for DELETE
    private final ListTag fullList;      // used by FULL_LIST only; null otherwise
    private final long serverTime;
    private final boolean firstBatch;
    private final boolean lastBatch;

    // --- Constructors ---

    private SyncPetDataPacket(int mode, UUID petUuid, CompoundTag petNbt, ListTag fullList, long serverTime,
                              boolean firstBatch, boolean lastBatch) {
        this.mode = mode;
        this.petUuid = petUuid;
        this.petNbt = petNbt;
        this.fullList = fullList;
        this.serverTime = serverTime;
        this.firstBatch = firstBatch;
        this.lastBatch = lastBatch;
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
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };
    }

    // --- Handler (client side) ---

    public static void handle(SyncPetDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TrulyScreen screen) {
                screen.applySyncPacket(packet);
            } else {
                // Cache for later use when the screen opens
                TrulyScreen.cacheSyncPacket(packet);
            }
        });

    }

    // --- Accessors ---

    public int getMode() { return mode; }
    public UUID getPetUuid() { return petUuid; }
    public CompoundTag getPetNbt() { return petNbt; }
    public ListTag getFullList() { return fullList; }
    public long getServerTime() { return serverTime; }
    public boolean isFirstBatch() { return firstBatch; }
    public boolean isLastBatch() { return lastBatch; }
}
