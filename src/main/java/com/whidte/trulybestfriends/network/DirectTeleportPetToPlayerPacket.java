package com.whidte.trulybestfriends.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** 以常规方式传送一只已释放的宠物，不尝试骑乘交换。 */
public class DirectTeleportPetToPlayerPacket {
    private final UUID petUuid;

    public DirectTeleportPetToPlayerPacket(UUID petUuid) {
        this.petUuid = petUuid;
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

    public static void handle(DirectTeleportPetToPlayerPacket packet, Supplier<NetworkEvent.Context> ctx) {
        TeleportPetToPlayerPacket.handleWithoutRideSwap(packet.petUuid, ctx);
    }
}
