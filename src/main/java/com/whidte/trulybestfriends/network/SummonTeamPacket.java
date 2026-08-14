package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

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

    public static void handle(SummonTeamPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            int colorIndex = Math.max(0, Math.min(PetTeamData.TEAM_COLORS.size() - 1, packet.colorIndex));
            String color = PetTeamData.TEAM_COLORS.get(colorIndex);
            Path ownerDir = PetIOUtil.getOwnerDir(player);
            try {
                CompoundTag data = PetTeamData.teamData(ownerDir);
                ListTag members = data.getCompound("Teams")
                        .getCompound(color).getList("Members", Tag.TAG_COMPOUND);
                for (Tag tag : members) {
                    CompoundTag member = (CompoundTag) tag;
                    if (!member.hasUUID("UUID")) continue;
                    UUID uuid = member.getUUID("UUID");
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
        ctx.get().setPacketHandled(true);
    }
}
