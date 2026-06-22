package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client requests teleport to pet's last known position. Server validates permissions and dimension. */
public class TeleportToPetPacket {
    private final String dimKey;
    private final double x, y, z;

    public TeleportToPetPacket(String dimKey, double x, double y, double z) {
        this.dimKey = dimKey;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static void encode(TeleportToPetPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.dimKey);
        buf.writeDouble(packet.x);
        buf.writeDouble(packet.y);
        buf.writeDouble(packet.z);
    }

    public static TeleportToPetPacket decode(FriendlyByteBuf buf) {
        return new TeleportToPetPacket(buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public static void handle(TeleportToPetPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
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

            // Teleport
            player.teleportTo(targetLevel, packet.x, packet.y, packet.z, player.getYRot(), player.getXRot());
            player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
        });
        ctx.get().setPacketHandled(true);
    }
}
