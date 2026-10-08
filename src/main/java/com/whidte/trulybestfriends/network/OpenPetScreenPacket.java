package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 服务端 → 客户端：打开宠物标签页并选中指定宠物。
 *
 * <p>用于「&lt;名&gt; 已被收回」这类聊天消息里的宠物名——名字上挂了
 * {@code ClickEvent.Action.RUN_COMMAND}，点击后由服务端的
 * {@code /tbf open &lt;uuid&gt;} 校验归属并回发本包。打开界面只能由客户端完成，
 * 而点击事件又只能执行命令，所以这里必须绕一次服务端。</p>
 */
public class OpenPetScreenPacket implements CustomPacketPayload {
    public static final Type<OpenPetScreenPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "open_pet_screen"));
    public static final StreamCodec<FriendlyByteBuf, OpenPetScreenPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), OpenPetScreenPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private final UUID petUuid;

    public OpenPetScreenPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(OpenPetScreenPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static OpenPetScreenPacket decode(FriendlyByteBuf buf) {
        return new OpenPetScreenPacket(buf.readUUID());
    }

    public static void send(ServerPlayer player, UUID petUuid) {
        PacketDistributor.sendToPlayer(player, new OpenPetScreenPacket(petUuid));
    }

    public UUID getPetUuid() { return petUuid; }
}
