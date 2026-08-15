package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/** Summons one tracked pet through the same paths used by the pet-screen summon button. */
public class SummonPetPacket {
    private final UUID petUuid;

    public SummonPetPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    public static void encode(SummonPetPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static SummonPetPacket decode(FriendlyByteBuf buf) {
        return new SummonPetPacket(buf.readUUID());
    }

    public static void handle(SummonPetPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            Path ownerDir = PetIOUtil.getOwnerDir(player);
            File nbtFile = ownerDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) return;
            try {
                CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);
                if (PetDeathState.isDeadSnapshot(nbt)) return;
                if (nbt.getBoolean("Recalled")) {
                    RecallPetPacket.handle(new RecallPetPacket(packet.petUuid), ctx);
                } else {
                    if (nbt.getBoolean("Lost")
                            || !nbt.contains("Pos")
                            || !nbt.contains("Dimension")) return;
                    TeleportPetToPlayerPacket.handle(new TeleportPetToPlayerPacket(packet.petUuid), ctx);
                }
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to summon pet {} for {}: {}",
                        packet.petUuid, player.getGameProfile().getName(), e.getMessage());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
