package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import com.whidte.trulybestfriends.network.PacketContext;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client → Server: summon every summonable member of one formation team.
 *
 * Reuses the existing summon paths ({@link RecallPetPacket} /
 * {@link TeleportPetToPlayerPacket}) without ride-swap. Dead and lost
 * pets are skipped silently.
 */
public class SummonTeamPacket {
    private final int colorIndex;

    public SummonTeamPacket(int colorIndex) {
        this.colorIndex = colorIndex;
    }

    public static void encode(SummonTeamPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.colorIndex);
    }

    public static SummonTeamPacket decode(FriendlyByteBuf buf) {
        return new SummonTeamPacket(buf.readVarInt());
    }

    public static void handle(SummonTeamPacket packet, PacketContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            String color = PetTeamData.colorAt(packet.colorIndex);
            Path ownerDir = PetIOUtil.getOwnerDir(player);
            try {
                CompoundTag data = PetTeamData.teamData(ownerDir);
                for (UUID uuid : PetTeamData.memberUuids(data, color)) {
                    File nbtFile = ownerDir.resolve(uuid + ".nbt").toFile();
                    if (!nbtFile.exists()) continue;
                    CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);
                    if (PetDeathState.isDeadSnapshot(nbt)) continue;
                    if (nbt.getBoolean("Lost") || !nbt.contains("Pos") || !nbt.contains("Dimension")) continue;
                    if (nbt.getBoolean("Recalled")) {
                        RecallPetPacket.handleWithoutRideSwap(uuid, ctx);
                    } else {
                        TeleportPetToPlayerPacket.handleWithoutRideSwap(uuid, ctx);
                    }
                }
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to summon team {} for {}: {}",
                        color, player.getGameProfile().getName(), e.getMessage());
            }
        });
        ctx.setPacketHandled(true);
    }
}
