package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server → Client: pushes pet data to the client.
 *
 * Three modes:
 *  - FULL_LIST: complete snapshot of all pets for the owner (sent on request or login)
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

    private final int mode;
    private final UUID petUuid;          // used by UPDATE / DELETE; null for FULL_LIST
    private final CompoundTag petNbt;    // used by FULL_LIST (wrapped) / UPDATE; null for DELETE
    private final ListTag fullList;      // used by FULL_LIST only; null otherwise
    private final long serverTime;

    // --- Constructors ---

    private SyncPetDataPacket(int mode, UUID petUuid, CompoundTag petNbt, ListTag fullList, long serverTime) {
        this.mode = mode;
        this.petUuid = petUuid;
        this.petNbt = petNbt;
        this.fullList = fullList;
        this.serverTime = serverTime;
    }

    /** Full list snapshot: each entry is a CompoundTag with "UUID" and "NBT". */
    public static SyncPetDataPacket fullList(ListTag list) {
        return new SyncPetDataPacket(MODE_FULL_LIST, null, null, list, System.currentTimeMillis());
    }

    /** Single pet update. */
    public static SyncPetDataPacket update(UUID uuid, CompoundTag nbt) {
        return new SyncPetDataPacket(MODE_UPDATE, uuid, nbt, null, System.currentTimeMillis());
    }

    /** Pet deletion notice. */
    public static SyncPetDataPacket delete(UUID uuid) {
        return new SyncPetDataPacket(MODE_DELETE, uuid, null, null, System.currentTimeMillis());
    }

    // --- Codec ---

    public static void encode(SyncPetDataPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.mode);
        switch (packet.mode) {
            case MODE_FULL_LIST -> {
                // writeNbt expects a CompoundTag; wrap the ListTag in a holder.
                CompoundTag holder = new CompoundTag();
                holder.put("List", packet.fullList);
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
                if (tag instanceof CompoundTag ct) {
                    if (ct.contains("List")) list = ct.getList("List", Tag.TAG_COMPOUND);
                    if (ct.contains("ServerTime")) serverTime = ct.getLong("ServerTime");
                }
                yield new SyncPetDataPacket(MODE_FULL_LIST, null, null, list, serverTime);
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
                yield new SyncPetDataPacket(MODE_UPDATE, uuid, nbt, null, serverTime);
            }
            case MODE_DELETE -> {
                UUID uuid = buf.readUUID();
                long serverTime = buf.readLong();
                yield new SyncPetDataPacket(MODE_DELETE, uuid, null, null, serverTime);
            }
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };
    }

    // --- Handler (client side) ---

    public static void handle(SyncPetDataPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TrulyScreen screen) {
                screen.applySyncPacket(packet);
            } else {
                // Cache for later use when the screen opens
                TrulyScreen.cacheSyncPacket(packet);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    // --- Accessors ---

    public int getMode() { return mode; }
    public UUID getPetUuid() { return petUuid; }
    public CompoundTag getPetNbt() { return petNbt; }
    public ListTag getFullList() { return fullList; }
    public long getServerTime() { return serverTime; }
}
