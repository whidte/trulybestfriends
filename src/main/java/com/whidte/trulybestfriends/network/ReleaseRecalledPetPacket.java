package com.whidte.trulybestfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import com.whidte.trulybestfriends.network.PacketContext;

import java.util.UUID;
import java.util.function.Supplier;

/** Releases a recalled pet without attempting a ride swap. */
public class ReleaseRecalledPetPacket {
    private final UUID petUuid;

    public ReleaseRecalledPetPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(ReleaseRecalledPetPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static ReleaseRecalledPetPacket decode(FriendlyByteBuf buf) {
        return new ReleaseRecalledPetPacket(buf.readUUID());
    }

    UUID petUuid() {
        return petUuid;
    }

    public static void handle(ReleaseRecalledPetPacket packet, PacketContext ctx) {
        RecallPetPacket.handleWithoutRideSwap(packet.petUuid, ctx);
    }
}
