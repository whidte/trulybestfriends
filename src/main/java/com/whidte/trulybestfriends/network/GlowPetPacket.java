package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class GlowPetPacket {
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

    public static void handle(GlowPetPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
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
        ctx.get().setPacketHandled(true);
    }
}
