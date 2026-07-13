package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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
public class SetPriorityPacket implements CustomPacketPayload {
    public static final Type<SetPriorityPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "set_priority"));
    public static final StreamCodec<FriendlyByteBuf, SetPriorityPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), SetPriorityPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
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

    public static void handle(SetPriorityPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;

            // Clamp to valid range [1, 6]
            int priority = Math.max(1, Math.min(6, packet.priority));

            Path petDir = PetIOUtil.getOwnerDir(player);

            File nbtFile = petDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) {
                // Pet was deleted — notify client so it can remove the entry
                SyncPetDataPacket reply = SyncPetDataPacket.delete(packet.petUuid);
                PacketDistributor.sendToPlayer(player, reply);
                return;
            }

            try {
                CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);
                nbt.putInt("Priority", priority);
                NbtFileIO.writeCompressed(nbt, nbtFile);

                // Push updated NBT back to client so its cache stays in sync
                SyncPetDataPacket reply = SyncPetDataPacket.update(packet.petUuid, nbt);
                PacketDistributor.sendToPlayer(player, reply);
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to update priority for {}: {}", packet.petUuid, e.getMessage());
            }
        });

    }
}
