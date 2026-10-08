package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 服务端 → 客户端：告诉界面在坐标
 * 显示位置处展示 3 秒的瞬时警告。用于召唤/传送/收回因
 * 宠物已被收回、已丢失，或召唤队列繁忙而失败时。
 */
public class PetWarningPacket {
    /** 0 = 已收回，1 = 已丢失（召唤），2 = 繁忙，3 = 已丢失（收回），4 = 无交换空间 */
    private final int type;
    private final UUID petUuid;

    public PetWarningPacket(int type, UUID petUuid) {
        this.type = type;
        this.petUuid = petUuid;
    }

    public static void encode(PetWarningPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.type);
        buf.writeUUID(packet.petUuid);
    }

    public static PetWarningPacket decode(FriendlyByteBuf buf) {
        return new PetWarningPacket(buf.readVarInt(), buf.readUUID());
    }

    public static void send(ServerPlayer player, int type, UUID petUuid) {
        trulybestfriends.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player), new PetWarningPacket(type, petUuid));
    }

    public int getType() { return type; }
    public UUID getPetUuid() { return petUuid; }
}
