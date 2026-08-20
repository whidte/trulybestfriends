package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Client → Server: persist the last wheel-summoned member as
 * team color + slot number. The server answers with an authoritative
 * {@link TeamDataPacket}.
 */
public class SetLastSummonPacket {
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

    public static void handle(SetLastSummonPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            String color = PetTeamData.colorAt(packet.colorIndex);
            Path ownerDir = PetIOUtil.getOwnerDir(player);
            try {
                PetTeamData.setLastSummon(ownerDir, color, packet.slot);
                TeamDataPacket.sendToPlayer(player, PetTeamData.teamData(ownerDir));
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to persist last summon for {}: {}",
                        player.getGameProfile().getName(), e.getMessage());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
