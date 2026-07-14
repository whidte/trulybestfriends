package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.tab.TrulyScreen;
import net.minecraft.client.Minecraft;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server → client: tells the GUI to show a transient warning at the coordinate
 * display position for 3 seconds.  Used when summon/teleport/recall fails because the
 * pet is recalled, lost, or the summon queue is busy.
 */
public class PetWarningPacket implements CustomPacketPayload {
    public static final Type<PetWarningPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "pet_warning"));
    public static final StreamCodec<FriendlyByteBuf, PetWarningPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), PetWarningPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
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

    public static void send(ServerPlayer player, int type, UUID petUuid) {
        PacketDistributor.sendToPlayer(player, new PetWarningPacket(type, petUuid));
    }

    public static void handle(PetWarningPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TrulyScreen screen) {
                Component msg = Component.translatable(switch (packet.type) {
                    case 0 -> "trulybestfriends.teleport.recalled_warning";
                    case 2 -> "trulybestfriends.teleport.busy_warning";
                    case 3 -> "trulybestfriends.recall.lost_warning";
                    default -> "trulybestfriends.teleport.lost_warning";
                });
                screen.showWarning(msg, packet.petUuid);
            }
        });

    }
}
