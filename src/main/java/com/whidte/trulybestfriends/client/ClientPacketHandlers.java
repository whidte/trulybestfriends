package com.whidte.trulybestfriends.client;

import com.whidte.trulybestfriends.network.PetWarningPacket;
import com.whidte.trulybestfriends.network.SyncPetDataPacket;
import com.whidte.trulybestfriends.network.TeamDataPacket;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client-only handlers for server → client packets.
 *
 * <p>This class must never be loaded on a dedicated server: it references
 * {@code net.minecraft.client.*} classes which do not exist there. It is only
 * reached through the dist-guarded lambdas in
 * {@link com.whidte.trulybestfriends.trulybestfriends#commonSetup}.</p>
 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {
    }

    public static void handle(PetWarningPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
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
        ctx.get().setPacketHandled(true);
    }

    public static void handle(SyncPetDataPacket packet, Supplier<NetworkEvent.Context> ctx) {
        final SyncPetDataPacket received = packet;
        ctx.get().enqueueWork(() -> {
            SyncPetDataPacket applyPacket = received;
            if (applyPacket.getMode() == SyncPetDataPacket.MODE_FRAGMENT) {
                SyncPetDataPacket complete = SyncPetDataPacket.collectFragment(applyPacket);
                if (complete == null) return;
                applyPacket = complete;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TrulyScreen screen) {
                screen.applySyncPacket(applyPacket);
            } else {
                TrulyScreen.cacheSyncPacket(applyPacket);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public static void handle(TeamDataPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TrulyScreen screen) {
                screen.applyTeamData(packet);
            } else {
                TrulyScreen.cacheTeamData(packet);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
