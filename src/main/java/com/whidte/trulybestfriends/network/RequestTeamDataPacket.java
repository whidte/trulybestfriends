package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import com.whidte.trulybestfriends.network.PacketContext;

import java.util.function.Supplier;

/** Client → Server: request the current formation team data. */
public class RequestTeamDataPacket {

    public static void encode(RequestTeamDataPacket packet, FriendlyByteBuf buf) {}

    public static RequestTeamDataPacket decode(FriendlyByteBuf buf) {
        return new RequestTeamDataPacket();
    }

    public static void handle(RequestTeamDataPacket packet, PacketContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            try {
                TeamDataPacket.sendToPlayer(player, PetTeamData.teamData(PetIOUtil.getOwnerDir(player)));
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to load team data for {}: {}",
                        player.getGameProfile().getName(), e.getMessage());
            }
        });
        ctx.setPacketHandled(true);
    }
}
