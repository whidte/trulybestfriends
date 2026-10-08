package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端 → 客户端：完整且规范化的阵型队伍数据
 * （颜色 → 带编号的成员槽位）。
 */
public class TeamDataPacket implements CustomPacketPayload {
    public static final Type<TeamDataPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "team_data"));
    public static final StreamCodec<FriendlyByteBuf, TeamDataPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> encode(packet, buf), TeamDataPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private final CompoundTag teamData;

    public TeamDataPacket(CompoundTag teamData) {
        this.teamData = teamData;
    }

    public CompoundTag teamData() {
        return teamData;
    }

    public static void encode(TeamDataPacket packet, FriendlyByteBuf buf) {
        buf.writeNbt(packet.teamData);
    }

    public static TeamDataPacket decode(FriendlyByteBuf buf) {
        return new TeamDataPacket(buf.readNbt());
    }

    public static void sendToPlayer(ServerPlayer player, CompoundTag teamData) {
        PacketDistributor.sendToPlayer(player, new TeamDataPacket(teamData));
    }
}
