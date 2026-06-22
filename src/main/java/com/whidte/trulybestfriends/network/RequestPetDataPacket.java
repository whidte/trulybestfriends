package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client → Server: requests pet data.
 *
 * Two request modes:
 *  - REQUEST_FULL_LIST: client just opened the UI (or wants a refresh), server
 *                       replies with a SyncPetDataPacket.fullList containing all pets.
 *  - REQUEST_SELECTED:  client wants the latest NBT for a specific pet (selected),
 *                       server replies with SyncPetDataPacket.update or .delete.
 *
 * This packet is the replacement for client-side disk reads in PetDataLoader and
 * TrulyScreen.refreshSelectedFromDisk.
 */
public class RequestPetDataPacket {
    public static final int REQUEST_FULL_LIST = 0;
    public static final int REQUEST_SELECTED = 1;

    private final int mode;
    private final UUID petUuid;  // only for REQUEST_SELECTED

    public RequestPetDataPacket(int mode, UUID petUuid) {
        this.mode = mode;
        this.petUuid = petUuid;
    }

    public static RequestPetDataPacket requestFullList() {
        return new RequestPetDataPacket(REQUEST_FULL_LIST, null);
    }

    public static RequestPetDataPacket requestSelected(UUID uuid) {
        return new RequestPetDataPacket(REQUEST_SELECTED, uuid);
    }

    public static void encode(RequestPetDataPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.mode);
        if (packet.mode == REQUEST_SELECTED) {
            buf.writeUUID(packet.petUuid);
        }
    }

    public static RequestPetDataPacket decode(FriendlyByteBuf buf) {
        int mode = buf.readVarInt();
        return mode == REQUEST_SELECTED
                ? requestSelected(buf.readUUID())
                : requestFullList();
    }

    public static void handle(RequestPetDataPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            Path petDir = player.serverLevel().getServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("trulybestfriends")
                    .resolve(player.getUUID().toString());

            if (packet.mode == REQUEST_FULL_LIST) {
                ListTag list = new ListTag();
                if (petDir.toFile().exists()) {
                    File[] files = petDir.toFile().listFiles((d, n) -> n.endsWith(".nbt"));
                    if (files != null) {
                        for (File f : files) {
                            try {
                                CompoundTag nbt = net.minecraft.nbt.NbtIo.readCompressed(f);
                                String uuidStr = f.getName().replace(".nbt", "");
                                UUID uuid = UUID.fromString(uuidStr);
                                CompoundTag entry = new CompoundTag();
                                entry.putUUID("UUID", uuid);
                                entry.put("NBT", nbt);
                                list.add(entry);
                            } catch (Exception e) {
                                trulybestfriends.LOGGER.error("Failed to read pet file: {}", f, e);
                            }
                        }
                    }
                }
                SyncPetDataPacket reply = SyncPetDataPacket.fullList(list);
                trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
            } else {
                // REQUEST_SELECTED
                File nbtFile = petDir.resolve(packet.petUuid + ".nbt").toFile();
                if (!nbtFile.exists()) {
                    SyncPetDataPacket reply = SyncPetDataPacket.delete(packet.petUuid);
                    trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
                } else {
                    try {
                        CompoundTag nbt = net.minecraft.nbt.NbtIo.readCompressed(nbtFile);
                        SyncPetDataPacket reply = SyncPetDataPacket.update(packet.petUuid, nbt);
                        trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
                    } catch (Exception e) {
                        trulybestfriends.LOGGER.error("Failed to read pet file for {}: {}", packet.petUuid, e.getMessage());
                    }
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
