package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import com.whidte.trulybestfriends.network.PacketContext;

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

    public static void handle(DeletePetDataPacket packet, PacketContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;

            if (trulybestfriends.deletePetData(player, packet.petUuid)) {
                PetSyncTracker.forgetPet(player.getUUID(), packet.petUuid);
                SyncPetDataPacket reply = SyncPetDataPacket.delete(packet.petUuid);
                SyncPetDataPacket.sendToPlayer(player, reply);
            }
        });
        ctx.setPacketHandled(true);
    }
}
