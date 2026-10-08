package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.compat.SableCompat;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** 客户端请求传送到宠物最后已知的位置。服务端校验权限和维度。
 *  如果宠物位于 Sable SubLevel 内，服务端会在传送前把本地坐标转换为世界
 *  坐标，并发送 {@link SableSubLevelSyncPacket}，以便客户端
 *  应用 SubLevel 追踪。 */
public class TeleportToPetPacket implements CustomPacketPayload {
    public static final Type<TeleportToPetPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "teleport_to_pet"));
    public static final StreamCodec<FriendlyByteBuf, TeleportToPetPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), TeleportToPetPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private final String dimKey;
    private final double x, y, z;
    private final UUID subLevelId;  // 当宠物不在 Sable SubLevel 中时为 null

    public TeleportToPetPacket(String dimKey, double x, double y, double z, UUID subLevelId) {
        this.dimKey = dimKey;
        this.x = x;
        this.y = y;
        this.z = z;
        this.subLevelId = subLevelId;
    }

    public static void encode(TeleportToPetPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.dimKey);
        buf.writeDouble(packet.x);
        buf.writeDouble(packet.y);
        buf.writeDouble(packet.z);
        buf.writeBoolean(packet.subLevelId != null);
        if (packet.subLevelId != null) buf.writeUUID(packet.subLevelId);
    }

    public static TeleportToPetPacket decode(FriendlyByteBuf buf) {
        String dimKey = buf.readUtf();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        UUID subLevelId = buf.readBoolean() ? buf.readUUID() : null;
        return new TeleportToPetPacket(dimKey, x, y, z, subLevelId);
    }

    public static void handle(TeleportToPetPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player == null) return;

            // 只有创造模式下的 OP（权限等级 >= 2）才能传送
            if (!player.hasPermissions(2) || !player.isCreative()) return;

            // 解析维度
            ServerLevel targetLevel = PetIOUtil.getLevel(player.server, packet.dimKey);
            if (targetLevel == null) return; // 未知维度

            // 把 SubLevel 本地坐标转换为世界坐标。
            // 当加载了 Sable 且宠物位于 SubLevel 内时，存储的 Pos
            // 处于 SubLevel 的本地坐标空间。projectToWorld 会将其
            // 转换为父维度的世界空间，使 teleportTo 落在正确位置。
            double targetX = packet.x;
            double targetY = packet.y;
            double targetZ = packet.z;
            if (SableCompat.isLoaded() && packet.subLevelId != null) {
                Vec3 worldPos = SableCompat.projectToWorld(targetLevel, new Vec3(packet.x, packet.y, packet.z));
                if (worldPos != null) {
                    targetX = worldPos.x;
                    targetY = worldPos.y;
                    targetZ = worldPos.z;
                }
            }

            // 传送
            player.teleportTo(targetLevel, targetX, targetY, targetZ, player.getYRot(), player.getXRot());
            player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);

            // 向客户端发送 SubLevel 追踪同步。服务端的 teleportTo
            // 会更新玩家位置，但不会同步 Sable 的 SubLevel
            // 追踪状态——客户端需要单独的数据包来“进入”
            // SubLevel 并渲染其内部。
            if (SableCompat.isLoaded() && packet.subLevelId != null) {
                PacketDistributor.sendToPlayer(player,
                        new SableSubLevelSyncPacket(packet.subLevelId, targetX, targetY, targetZ));
            }
        });

    }
}
