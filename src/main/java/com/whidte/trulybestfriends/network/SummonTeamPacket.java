package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Client → Server: summon every summonable member of one formation team.
 *
 * Reuses the existing summon paths ({@link RecallPetPacket} /
 * {@link TeleportPetToPlayerPacket}) without ride-swap. Dead and lost
 * pets are skipped silently.
 */
public class SummonTeamPacket implements CustomPacketPayload {
    public static final Type<SummonTeamPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "summon_team"));
    public static final StreamCodec<FriendlyByteBuf, SummonTeamPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> encode(packet, buf), SummonTeamPacket::decode);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

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

    public static void handle(SummonTeamPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
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
                        RecallPetPacket.handleWithoutRideSwap(uuid, context);
                    } else {
                        TeleportPetToPlayerPacket.handleWithoutRideSwap(uuid, context);
                    }
                }
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to summon team {} for {}: {}",
                        color, player.getGameProfile().getName(), e.getMessage());
            }
        });
    }
}
