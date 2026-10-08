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
 * 客户端 → 服务端：召唤某一阵型队伍中所有可召唤的成员。
 *
 * 复用现有的召唤路径（{@link RecallPetPacket} /
 * {@link TeleportPetToPlayerPacket}），不做骑乘交换。已死亡的宠物
 * 会被静默跳过；未加载的成员会走强制加载的召唤路径。
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
                    // 不设 "Lost" 过滤：未加载的成员仍可通过
                    // TeleportPetToPlayerPacket 的强制加载路径被召唤。
                    if (!nbt.contains("Pos") || !nbt.contains("Dimension")) continue;
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
