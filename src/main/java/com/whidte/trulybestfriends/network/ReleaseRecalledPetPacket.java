package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Releases a recalled pet without attempting a ride swap. */
public class ReleaseRecalledPetPacket implements CustomPacketPayload {
    public static final Type<ReleaseRecalledPetPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "release_recalled_pet"));
    public static final StreamCodec<FriendlyByteBuf, ReleaseRecalledPetPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> encode(packet, buf), ReleaseRecalledPetPacket::decode);

    private final UUID petUuid;

    public ReleaseRecalledPetPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
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

    public static void handle(ReleaseRecalledPetPacket packet, IPayloadContext context) {
        RecallPetPacket.handleWithoutRideSwap(packet.petUuid, context);
    }
}
