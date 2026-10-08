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
 * 客户端 → 服务端：设置宠物的 Priority 字段。
 *
 * 取代了 PetEntry.writePriorityToDisk 中旧的客户端磁盘写入，
 * 那在多人游戏下是坏的（客户端写入本地存档目录，服务端
 * 从不知道这一变更）。现在由服务端负责写入，并通过
 * SyncPetDataPacket.update 把更新后的 NBT 推回客户端。
 */
public class SetPriorityPacket {
    private final UUID petUuid;
    private final int priority;

    public SetPriorityPacket(UUID petUuid, int priority) {
        this.petUuid = petUuid;
        this.priority = priority;
    }

    public static void encode(SetPriorityPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.petUuid);
        buf.writeVarInt(packet.priority);
    }

    public static SetPriorityPacket decode(FriendlyByteBuf buf) {
        return new SetPriorityPacket(buf.readUUID(), buf.readVarInt());
    }

    public static void handle(SetPriorityPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 限制到有效范围 [1, 6]
            int priority = PetIOUtil.clampPriority(packet.priority);

            Path petDir = PetIOUtil.getOwnerDir(player);

            File nbtFile = petDir.resolve(packet.petUuid + ".nbt").toFile();
            if (!nbtFile.exists()) {
                PetSyncTracker.forgetPet(player.getUUID(), packet.petUuid);
                // 宠物已被删除——通知客户端以便其移除该条目
                SyncPetDataPacket reply = SyncPetDataPacket.delete(packet.petUuid);
                SyncPetDataPacket.sendToPlayer(player, reply);
                return;
            }

            try {
                CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);
                nbt.putInt("Priority", priority);
                NbtFileIO.writeCompressed(nbt, nbtFile);

                // 把更新后的 NBT 推回客户端，使其缓存保持同步
                CompoundTag replyNbt = RequestPetDataPacket.createUpdateNbt(
                        player, packet.petUuid, nbt);
                if (PetSyncTracker.shouldSendUpdate(player.getUUID(), packet.petUuid, replyNbt)) {
                    SyncPetDataPacket reply = SyncPetDataPacket.update(packet.petUuid, replyNbt);
                    SyncPetDataPacket.sendToPlayer(player, reply);
                }
            } catch (Exception e) {
                trulybestfriends.LOGGER.error("Failed to update priority for {}: {}", packet.petUuid, e.getMessage());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
