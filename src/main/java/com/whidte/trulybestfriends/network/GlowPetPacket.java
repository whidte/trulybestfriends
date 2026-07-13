package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;

import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public class GlowPetPacket implements CustomPacketPayload {
    public static final Type<GlowPetPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "glow_pet"));
    public static final StreamCodec<FriendlyByteBuf, GlowPetPacket> STREAM_CODEC = StreamCodec.of((buf, packet) -> encode(packet, buf), GlowPetPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private final UUID petUuid;

    public GlowPetPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(GlowPetPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static GlowPetPacket decode(FriendlyByteBuf buf) {
        return new GlowPetPacket(buf.readUUID());
    }

    public static void handle(GlowPetPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player != null) {
                ServerLevel level = player.serverLevel();
                Entity entity = level.getEntity(packet.petUuid);
                if (entity instanceof net.minecraft.world.entity.LivingEntity living
                        && trulybestfriends.isTrackedPet(packet.petUuid)
                        && trulybestfriends.isOwnedBy(living, player.getUUID())) {
                    living.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false));
                }
            }
        });

    }
}
