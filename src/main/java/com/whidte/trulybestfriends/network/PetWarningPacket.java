package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Server → client: tells the GUI to show a transient warning at the coordinate
 * display position for 3 seconds.  Used when summon/teleport/recall fails because the
 * pet is recalled, lost, or the summon queue is busy.
 */
public class PetWarningPacket {
    /** 0 = recalled, 1 = lost (summon), 2 = busy, 3 = lost (recall), 4 = no swap space */
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
        trulybestfriends.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player), new PetWarningPacket(type, petUuid));
    }

    public int getType() { return type; }
    public UUID getPetUuid() { return petUuid; }
}
