package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：把最近一次通过轮盘召唤的成员持久化为
 * 队伍颜色 + 槽位编号。服务端以权威的
 * {@link TeamDataPacket} 回应。
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
