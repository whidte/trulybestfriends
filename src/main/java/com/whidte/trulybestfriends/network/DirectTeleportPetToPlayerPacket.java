package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Teleports a released pet normally without attempting a ride swap. */
public class DirectTeleportPetToPlayerPacket implements CustomPacketPayload {
    public static final Type<DirectTeleportPetToPlayerPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "direct_teleport_pet_to_player"));
    public static final StreamCodec<FriendlyByteBuf, DirectTeleportPetToPlayerPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> encode(packet, buf), DirectTeleportPetToPlayerPacket::decode);

    private final UUID petUuid;

    public DirectTeleportPetToPlayerPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(DirectTeleportPetToPlayerPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static DirectTeleportPetToPlayerPacket decode(FriendlyByteBuf buf) {
        return new DirectTeleportPetToPlayerPacket(buf.readUUID());
    }

    UUID petUuid() {
        return petUuid;
    }

    public static void handle(DirectTeleportPetToPlayerPacket packet, IPayloadContext context) {
        TeleportPetToPlayerPacket.handleWithoutRideSwap(packet.petUuid, context);
    }
}
