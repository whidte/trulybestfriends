package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.file.Path;

/**
 * Client → Server: persist the last wheel-summoned member as
 * team color + slot number. The server answers with an authoritative
 * {@link TeamDataPacket}.
 */
public class SetLastSummonPacket implements CustomPacketPayload {
    public static final Type<SetLastSummonPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "set_last_summon"));
    public static final StreamCodec<FriendlyByteBuf, SetLastSummonPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> encode(packet, buf), SetLastSummonPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private final int colorIndex;
    private final int slot;

    public SetLastSummonPacket(int colorIndex, int slot) {
        this.colorIndex = colorIndex;
        this.slot = slot;
    }

    public static void encode(SetLastSummonPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.colorIndex);
        buf.writeVarInt(packet.slot);
    }

    public static SetLastSummonPacket decode(FriendlyByteBuf buf) {
        return new SetLastSummonPacket(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(SetLastSummonPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;
            int colorIndex = Math.max(0, Math.min(PetTeamData.TEAM_COLORS.size() - 1, packet.colorIndex));
            String color = PetTeamData.TEAM_COLORS.get(colorIndex);
            Path ownerDir = PetIOUtil.getOwnerDir(player);
            try {
                if (packet.slot >= 1 && packet.slot <= PetTeamData.GRID_SLOT_COUNT) {
                    PetTeamData.setLastSummon(ownerDir, color, packet.slot);
                }
                TeamDataPacket.sendToPlayer(player, PetTeamData.teamData(ownerDir));
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to persist last summon for {}: {}",
                        player.getGameProfile().getName(), e.getMessage());
            }
        });
    }
}
