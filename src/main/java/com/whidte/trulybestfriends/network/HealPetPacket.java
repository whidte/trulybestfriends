package com.whidte.trulybestfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import com.whidte.trulybestfriends.network.PacketContext;

import java.util.UUID;
import java.util.function.Supplier;

public class HealPetPacket {
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

    public static void handle(HealPetPacket packet, PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) PetHealingManager.activate(player, packet.petUuid, packet.advanced);
        });
        context.setPacketHandled(true);
    }
}
