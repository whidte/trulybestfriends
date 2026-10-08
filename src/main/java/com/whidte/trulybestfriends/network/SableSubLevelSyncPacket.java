package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 服务端 → 客户端：在传送之后同步 Sable SubLevel 追踪状态。
 *
 * <p>当玩家传送到位于 Sable SubLevel 内的宠物时，由 {@link TeleportToPetPacket#handle} 发送。
 * 服务端的 {@code teleportTo} 会更新玩家位置，但不会
 * 同步 Sable 的 SubLevel 追踪——客户端需要这个
 * 单独的数据包来“进入”SubLevel 并渲染其内部。</p>
 *
 * <p>仿照 WaystonesSable 的 {@code SableTeleportPayload}：客户端调用
 * {@code player.moveTo()} 来确认世界空间位置，然后
 * 调用 {@code sable$setTrackingSubLevel()} 进入 SubLevel，并
 * 调用 {@code setOldPosNoMovement()} 防止橡皮筋回弹。</p>
 *
 * <p>如果客户端未加载 Sable，处理器会静默地什么都不做。</p>
 */
public class SableSubLevelSyncPacket implements CustomPacketPayload {
    public static final Type<SableSubLevelSyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "sable_sublevel_sync"));
    public static final StreamCodec<FriendlyByteBuf, SableSubLevelSyncPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), SableSubLevelSyncPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private final UUID subLevelId;  // null = 无 SubLevel（清除追踪）
    private final double worldX, worldY, worldZ;

    public SableSubLevelSyncPacket(UUID subLevelId, double worldX, double worldY, double worldZ) {
        this.subLevelId = subLevelId;
        this.worldX = worldX;
        this.worldY = worldY;
        this.worldZ = worldZ;
    }

    public static void encode(SableSubLevelSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.subLevelId != null);
        if (packet.subLevelId != null) buf.writeUUID(packet.subLevelId);
        buf.writeDouble(packet.worldX);
        buf.writeDouble(packet.worldY);
        buf.writeDouble(packet.worldZ);
    }

    public static SableSubLevelSyncPacket decode(FriendlyByteBuf buf) {
        UUID subLevelId = buf.readBoolean() ? buf.readUUID() : null;
        return new SableSubLevelSyncPacket(subLevelId, buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public UUID getSubLevelId() { return subLevelId; }
    public double getWorldX() { return worldX; }
    public double getWorldY() { return worldY; }
    public double getWorldZ() { return worldZ; }
}
