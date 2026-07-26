package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public class HealPetPacket implements CustomPacketPayload {
    public static final Type<HealPetPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "heal_pet"));
    public static final StreamCodec<FriendlyByteBuf, HealPetPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> encode(packet, buf), HealPetPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private final UUID petUuid;
    private final boolean advanced;

    public HealPetPacket(UUID petUuid, boolean advanced) {
        this.petUuid = petUuid;
        this.advanced = advanced;
    }

    public static void encode(HealPetPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
        buf.writeBoolean(packet.advanced);
    }

    public static HealPetPacket decode(FriendlyByteBuf buf) {
        return new HealPetPacket(buf.readUUID(), buf.readBoolean());
    }

    public static void handle(HealPetPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PetHealingManager.activate(player, packet.petUuid, packet.advanced);
            }
        });
    }
}
