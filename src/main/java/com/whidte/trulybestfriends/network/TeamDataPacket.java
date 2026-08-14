package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * Server → Client: full normalized formation team data
 * (color → numbered member slots).
 */
public class TeamDataPacket {
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
        trulybestfriends.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player), new TeamDataPacket(teamData));
    }
}
