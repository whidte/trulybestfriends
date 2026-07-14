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

/** Client requests teleport to pet's last known position. Server validates permissions and dimension.
 *  If the pet is inside a Sable SubLevel, the server transforms local coordinates to world
 *  coordinates before teleporting and sends a {@link SableSubLevelSyncPacket} so the client
 *  can apply SubLevel tracking. */
public class TeleportToPetPacket implements CustomPacketPayload {
    public static final Type<TeleportToPetPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "teleport_to_pet"));
    public static final StreamCodec<FriendlyByteBuf, TeleportToPetPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), TeleportToPetPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private final String dimKey;
    private final double x, y, z;
    private final UUID subLevelId;  // null when pet is not in a Sable SubLevel

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

            // Only OP (level >= 2) in creative mode can teleport
            if (!player.hasPermissions(2) || !player.isCreative()) return;

            // Resolve the dimension
            ServerLevel targetLevel = null;
            ResourceLocation dimRl = ResourceLocation.tryParse(packet.dimKey);
            if (dimRl != null) {
                for (ServerLevel sl : player.server.getAllLevels()) {
                    if (sl.dimension().location().equals(dimRl)) {
                        targetLevel = sl;
                        break;
                    }
                }
            }
            if (targetLevel == null) return; // unknown dimension

            // Transform SubLevel-local coordinates to world coordinates.
            // When Sable is loaded and the pet is inside a SubLevel, the stored Pos
            // is in the SubLevel's local coordinate space. projectToWorld converts
            // it to the parent dimension's world space so teleportTo lands correctly.
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

            // Teleport
            player.teleportTo(targetLevel, targetX, targetY, targetZ, player.getYRot(), player.getXRot());
            player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);

            // Send SubLevel tracking sync to the client. The server's teleportTo
            // updates the player's position but does not sync Sable's SubLevel
            // tracking state — the client needs a separate packet to "enter" the
            // SubLevel and render its interior.
            if (SableCompat.isLoaded() && packet.subLevelId != null) {
                PacketDistributor.sendToPlayer(player,
                        new SableSubLevelSyncPacket(packet.subLevelId, targetX, targetY, targetZ));
            }
        });

    }
}
