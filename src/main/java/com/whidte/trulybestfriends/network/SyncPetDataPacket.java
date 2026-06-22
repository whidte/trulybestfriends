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

    // --- Constructors ---

    private SyncPetDataPacket(int mode, UUID petUuid, CompoundTag petNbt, ListTag fullList) {
        this.mode = mode;
        this.petUuid = petUuid;
        this.petNbt = petNbt;
        this.fullList = fullList;
    }

    /** Full list snapshot: each entry is a CompoundTag with "UUID" and "NBT". */
    public static SyncPetDataPacket fullList(ListTag list) {
        return new SyncPetDataPacket(MODE_FULL_LIST, null, null, list);
    }

    /** Single pet update. */
    public static SyncPetDataPacket update(UUID uuid, CompoundTag nbt) {
        return new SyncPetDataPacket(MODE_UPDATE, uuid, nbt, null);
    }

    /** Pet deletion notice. */
    public static SyncPetDataPacket delete(UUID uuid) {
        return new SyncPetDataPacket(MODE_DELETE, uuid, null, null);
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
                buf.writeNbt(packet.petNbt);
            }
            case MODE_DELETE -> buf.writeUUID(packet.petUuid);
            default -> throw new IllegalArgumentException("Unknown mode: " + packet.mode);
        }
    }

    public static SyncPetDataPacket decode(FriendlyByteBuf buf) {
        int mode = buf.readVarInt();
        return switch (mode) {
            case MODE_FULL_LIST -> {
                Tag tag = buf.readNbt();
                ListTag list = new ListTag();
                if (tag instanceof CompoundTag ct && ct.contains("List")) {
                    list = ct.getList("List", Tag.TAG_COMPOUND);
                }
                yield fullList(list);
            }
            case MODE_UPDATE -> {
                UUID uuid = buf.readUUID();
                Tag tag = buf.readNbt();
                CompoundTag nbt = tag instanceof CompoundTag ct ? ct : new CompoundTag();
                yield update(uuid, nbt);
            }
            case MODE_DELETE -> delete(buf.readUUID());
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
}
