package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import java.util.function.Supplier;

public class DeletePetDataPacket implements CustomPacketPayload {
    public static final Type<DeletePetDataPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "delete_pet_data"));
    public static final StreamCodec<FriendlyByteBuf, DeletePetDataPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), DeletePetDataPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private final UUID petUuid;

    public DeletePetDataPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(DeletePetDataPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static DeletePetDataPacket decode(FriendlyByteBuf buf) {
        return new DeletePetDataPacket(buf.readUUID());
    }

    public static void handle(DeletePetDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;

            if (trulybestfriends.deletePetData(player, packet.petUuid)) {
                SyncPetDataPacket reply = SyncPetDataPacket.delete(packet.petUuid);
                PacketDistributor.sendToPlayer(player, reply);
            }
        });

    }
}
