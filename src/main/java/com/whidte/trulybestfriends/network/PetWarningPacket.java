package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.tab.TrulyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server → client: tells the GUI to show a transient warning at the coordinate
 * display position for 3 seconds.  Used when summon/teleport/recall fails because the
 * pet is recalled, lost, or the summon queue is busy.
 */
public class PetWarningPacket {
    /** 0 = recalled, 1 = lost (summon context), 2 = busy, 3 = lost (recall context) */
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

    public static void handle(PetWarningPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TrulyScreen screen) {
                Component msg;
                if (packet.type == 0) {
                    msg = Component.translatable("trulybestfriends.teleport.recalled_warning");
                } else if (packet.type == 2) {
                    msg = Component.translatable("trulybestfriends.teleport.busy_warning");
                } else if (packet.type == 3) {
                    msg = Component.translatable("trulybestfriends.recall.lost_warning");
                } else {
                    msg = Component.translatable("trulybestfriends.teleport.lost_warning");
                }
                screen.showWarning(msg, packet.petUuid);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
