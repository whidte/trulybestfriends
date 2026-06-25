package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

public class DeletePetDataPacket {
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

    public static void handle(DeletePetDataPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (trulybestfriends.deletePetData(player, packet.petUuid)) {
                SyncPetDataPacket reply = SyncPetDataPacket.delete(packet.petUuid);
                trulybestfriends.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
