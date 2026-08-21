package com.whidte.trulybestfriends.client;

import com.whidte.trulybestfriends.network.PetWarningPacket;
import com.whidte.trulybestfriends.network.SyncPetDataPacket;
import com.whidte.trulybestfriends.network.TeamDataPacket;
import com.whidte.trulybestfriends.network.PacketContext;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import com.whidte.trulybestfriends.tab.SummonWheelData;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-only handlers for server → client packets.
 *
 * <p>This class must never be loaded on a dedicated server: it references
 * {@code net.minecraft.client.*} classes which do not exist there. It is only
 * reached through the client-side packet registrations in
 * {@link ClientEvents#onInitializeClient()}.</p>
 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {
    }

    public static void handle(PetWarningPacket packet, PacketContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TrulyScreen screen) {
                Component msg = Component.translatable(switch (packet.getType()) {
                    case 0 -> "trulybestfriends.teleport.recalled_warning";
                    case 2 -> "trulybestfriends.teleport.busy_warning";
                    case 3 -> "trulybestfriends.recall.lost_warning";
                    case 4 -> "trulybestfriends.ride_swap.no_space_warning";
                    default -> "trulybestfriends.teleport.lost_warning";
                });
                screen.showWarning(msg, packet.getPetUuid());
            }
        });
        ctx.setPacketHandled(true);
    }

    public static void handle(SyncPetDataPacket packet, PacketContext ctx) {
        final SyncPetDataPacket received = packet;
        ctx.enqueueWork(() -> {
            SyncPetDataPacket applyPacket = received;
            if (applyPacket.getMode() == SyncPetDataPacket.MODE_FRAGMENT) {
                SyncPetDataPacket complete = SyncPetDataPacket.collectFragment(applyPacket);
                if (complete == null) return;
                applyPacket = complete;
            }
            Minecraft mc = Minecraft.getInstance();
            SummonWheelData.applySyncPacket(applyPacket);
            if (mc.screen instanceof TrulyScreen screen) {
                screen.applySyncPacket(applyPacket);
            } else {
                TrulyScreen.cacheSyncPacket(applyPacket);
            }
        });
        ctx.setPacketHandled(true);
    }

    public static void handle(TeamDataPacket packet, PacketContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            SummonWheelData.applyTeamData(packet);
            if (mc.screen instanceof TrulyScreen screen) {
                screen.applyTeamData(packet);
            } else {
                TrulyScreen.cacheTeamData(packet);
            }
        });
        ctx.setPacketHandled(true);
    }
}
