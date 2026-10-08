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

/** 通过与宠物界面召唤按钮相同的路径召唤一只已追踪的宠物。 */
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
                // 无条件委托给宠物界面召唤按钮的路径：
                // TeleportPetToPlayerPacket 会自行处理已加载实体、跨维度
                // 查找、未加载区块的强制加载以及骑乘交换。
                if (nbt.getBoolean("Recalled")) {
                    RecallPetPacket.handle(new RecallPetPacket(packet.petUuid), ctx);
                } else {
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
