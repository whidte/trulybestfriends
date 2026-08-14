package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → Server: request the current formation team data. */
public class RequestTeamDataPacket implements CustomPacketPayload {
    public static final Type<RequestTeamDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "request_team_data"));
    public static final StreamCodec<FriendlyByteBuf, RequestTeamDataPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> encode(packet, buf), RequestTeamDataPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void encode(RequestTeamDataPacket packet, FriendlyByteBuf buf) {}

    public static RequestTeamDataPacket decode(FriendlyByteBuf buf) {
        return new RequestTeamDataPacket();
    }

    public static void handle(RequestTeamDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;
            try {
                TeamDataPacket.sendToPlayer(player, PetTeamData.teamData(PetIOUtil.getOwnerDir(player)));
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to load team data for {}: {}",
                        player.getGameProfile().getName(), e.getMessage());
            }
        });
    }
}
