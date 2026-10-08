package com.whidte.trulybestfriends.client;

import com.whidte.trulybestfriends.compat.SableCompat;
import com.whidte.trulybestfriends.network.OpenPetScreenPacket;
import com.whidte.trulybestfriends.network.PetWarningPacket;
import com.whidte.trulybestfriends.network.SableSubLevelSyncPacket;
import com.whidte.trulybestfriends.network.SyncPetDataPacket;
import com.whidte.trulybestfriends.network.TeamDataPacket;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import com.whidte.trulybestfriends.tab.SummonWheelData;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 仅客户端的服务端 → 客户端数据包处理器。
 *
 * <p>本类绝不能加载到专用服务端上：它引用了
 * {@code net.minecraft.client.*} 类，而这些类在专用服务端并不存在。它只能
 * 通过
 * {@link com.whidte.trulybestfriends.trulybestfriends#registerPayloads} 中
 * 受 dist 保护的 lambda 到达。</p>
 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {
    }

    public static void handle(PetWarningPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
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
    }

    public static void handle(SableSubLevelSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            SableCompat.applyClientTracking(player, packet.getSubLevelId(),
                    packet.getWorldX(), packet.getWorldY(), packet.getWorldZ());
        });
    }

    public static void handle(SyncPetDataPacket packet, IPayloadContext context) {
        final SyncPetDataPacket received = packet;
        context.enqueueWork(() -> {
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
    }

    public static void handle(TeamDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            SummonWheelData.applyTeamData(packet);
            if (mc.screen instanceof TrulyScreen screen) {
                screen.applyTeamData(packet);
            } else {
                TrulyScreen.cacheTeamData(packet);
            }
        });
    }

    public static void handle(OpenPetScreenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TrulyScreen screen) {
                // 标签页已经开着：直接切换选中项，不要重建整个界面。
                screen.selectPet(packet.getPetUuid());
            } else {
                mc.setScreen(new TrulyScreen(
                        Component.translatable("tab.trulybestfriends.pets"), packet.getPetUuid()));
            }
        });
    }
}
