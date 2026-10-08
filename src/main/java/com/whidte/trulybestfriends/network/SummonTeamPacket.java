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

/**
 * 客户端 → 服务端：召唤某一阵型队伍中所有可召唤的成员。
 *
 * 复用现有的召唤路径（{@link RecallPetPacket} /
 * {@link TeleportPetToPlayerPacket}），不做骑乘交换。已死亡的宠物
 * 会被静默跳过；未加载的成员会走强制加载的召唤路径。
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
