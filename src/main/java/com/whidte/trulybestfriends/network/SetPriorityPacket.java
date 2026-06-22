package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client → Server: set a pet's Priority field.
 *
 * Replaces the old client-side disk write in PetEntry.writePriorityToDisk,
 * which was broken in multiplayer (client wrote to local saves dir, server
 * never knew about the change). Server now owns the write and pushes the
 * updated NBT back to the client via SyncPetDataPacket.update.
 */
public class SetPriorityPacket {
    private final UUID petUuid;
    private final int priority;

    public SetPriorityPacket(UUID petUuid, int priority) {
        this.petUuid = petUuid;
        this.priority = priority;
    }

    public static void encode(SetPriorityPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
        buf.writeVarInt(packet.priority);
    }

    public static SetPriorityPacket decode(FriendlyByteBuf buf) {
        return new SetPriorityPacket(buf.readUUID(), buf.readVarInt());
    }

    public static void handle(SetPriorityPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // Clamp to valid range [1, 6]
            int priority = Math.max(1, Math.min(6, packet.priority));

            Path petDir = player.serverLevel().getServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("trulybestfriends")
                    .resolve(player.getUUID().toString());

            File nbtFile = petDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) {
                // Pet was deleted — notify client so it can remove the entry
                SyncPetDataPacket reply = SyncPetDataPacket.delete(packet.petUuid);
                trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
                return;
            }

            try {
                CompoundTag nbt = NbtIo.readCompressed(nbtFile);
                nbt.putInt("Priority", priority);
                NbtIo.writeCompressed(nbt, nbtFile);

                // Push updated NBT back to client so its cache stays in sync
                SyncPetDataPacket reply = SyncPetDataPacket.update(packet.petUuid, nbt);
                trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to update priority for {}: {}", packet.petUuid, e.getMessage());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
