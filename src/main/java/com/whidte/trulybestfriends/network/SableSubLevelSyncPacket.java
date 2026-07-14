package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.compat.SableCompat;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server → client: syncs Sable SubLevel tracking state after a teleport.
 *
 * <p>Sent by {@link TeleportToPetPacket#handle} when a player teleports to a pet
 * inside a Sable SubLevel. The server's {@code teleportTo} updates the player's
 * position but does not sync Sable's SubLevel tracking — the client needs this
 * separate packet to "enter" the SubLevel and render its interior.</p>
 *
 * <p>Mirrors WaystonesSable's {@code SableTeleportPayload}: the client calls
 * {@code player.moveTo()} to confirm the world-space position, then
 * {@code sable$setTrackingSubLevel()} to enter the SubLevel, and
 * {@code setOldPosNoMovement()} to prevent rubber-banding.</p>
 *
 * <p>If Sable is not loaded on the client, the handler silently does nothing.</p>
 */
public class SableSubLevelSyncPacket implements CustomPacketPayload {
    public static final Type<SableSubLevelSyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "sable_sublevel_sync"));
    public static final StreamCodec<FriendlyByteBuf, SableSubLevelSyncPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), SableSubLevelSyncPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private final UUID subLevelId;  // null = no SubLevel (clear tracking)
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

    public static void handle(SableSubLevelSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            SableCompat.applyClientTracking(player, packet.subLevelId,
                    packet.worldX, packet.worldY, packet.worldZ);
        });
    }
}
