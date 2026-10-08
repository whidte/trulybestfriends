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

/** 通过与宠物界面召唤按钮相同的路径召唤一只已追踪的宠物。 */
public class SummonPetPacket implements CustomPacketPayload {
    public static final Type<SummonPetPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(trulybestfriends.MODID, "summon_pet"));
    public static final StreamCodec<FriendlyByteBuf, SummonPetPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> encode(packet, buf), SummonPetPacket::decode);

    private final UUID petUuid;

    public SummonPetPacket(UUID petUuid) {
        this.petUuid = petUuid;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(SummonPetPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
    }

    public static SummonPetPacket decode(FriendlyByteBuf buf) {
        return new SummonPetPacket(buf.readUUID());
    }

    public static void handle(SummonPetPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
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
                    RecallPetPacket.handle(new RecallPetPacket(packet.petUuid), context);
                } else {
                    TeleportPetToPlayerPacket.handle(new TeleportPetToPlayerPacket(packet.petUuid), context);
                }
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to summon pet {} for {}: {}",
                        packet.petUuid, player.getGameProfile().getName(), e.getMessage());
            }
        });
    }
}
