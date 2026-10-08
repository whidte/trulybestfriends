package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.TbfOwnerTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求宠物数据。
 *
 * 两种请求模式：
 *  - REQUEST_FULL_LIST：客户端刚打开界面（或想要刷新），服务端
 *                       回以包含全部宠物的 SyncPetDataPacket.fullList。
 *  - REQUEST_SELECTED： 客户端想要某个特定宠物（当前选中）的最新 NBT，
 *                       服务端回以 SyncPetDataPacket.update 或 .delete。
 *
 * 此数据包是 PetDataLoader 与
 * TrulyScreen.refreshSelectedFromDisk 中客户端磁盘读取的替代品。
 */
public class RequestPetDataPacket {
    public static final int REQUEST_FULL_LIST = 0;
    public static final int REQUEST_SELECTED = 1;

    private final int mode;
    private final UUID petUuid;  // 仅用于 REQUEST_SELECTED

    public RequestPetDataPacket(int mode, UUID petUuid) {
        this.mode = mode;
        this.petUuid = petUuid;
    }

    public static RequestPetDataPacket requestFullList() {
        return new RequestPetDataPacket(REQUEST_FULL_LIST, null);
    }

    public static RequestPetDataPacket requestSelected(UUID uuid) {
        return new RequestPetDataPacket(REQUEST_SELECTED, uuid);
    }

    public static void encode(RequestPetDataPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.mode);
        if (packet.mode == REQUEST_SELECTED) {
            buf.writeUUID(packet.petUuid);
        }
    }

    public static RequestPetDataPacket decode(FriendlyByteBuf buf) {
        int mode = buf.readVarInt();
        return mode == REQUEST_SELECTED
                ? requestSelected(buf.readUUID())
                : requestFullList();
    }

    public static void handle(RequestPetDataPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            Path petDir = PetIOUtil.getOwnerDir(player);

            if (packet.mode == REQUEST_FULL_LIST) {
                trulybestfriends.flushPendingPetSaves(player.getUUID());
                ListTag list = new ListTag();
                Map<UUID, CompoundTag> sentSnapshot = new HashMap<>();
                // 同一轮已解析的原始快照，供存在性探测复用，避免重复读盘。
                Map<UUID, CompoundTag> storedSnapshot = new HashMap<>();
                Set<UUID> rideablePetUuids = trulybestfriends.getRideablePetUUIDs(player.serverLevel());
                if (petDir.toFile().exists()) {
                    File[] files = petDir.toFile().listFiles((d, n) -> PetIOUtil.isPetDataFileName(n));
                    if (files != null) {
                        int limit = Math.max(1, Config.maxPets);
                        for (File f : files) {
                            if (list.size() >= limit) break;
                            try {
                                CompoundTag storedNbt = NbtFileIO.readCompressed(f);
                                String fileName = f.getName();
                                String uuidStr = fileName.substring(0, fileName.length() - 4);
                                UUID uuid = UUID.fromString(uuidStr);
                                CompoundTag replyNbt = toClientNbt(storedNbt);
                                // 存储时已死亡的宠物有意不设世界实体。
                                // 只有未加载的存活宠物才被视为丢失。
                                replyNbt.putBoolean("Lost",
                                        shouldMarkLost(storedNbt, isPetLoaded(player, uuid)));
                                // 注入内存中的死亡时刻（不写盘），供客户端计算复活冷却
                                trulybestfriends.injectDeathTimeIntoNbt(uuid, replyNbt);
                                PetHealingManager.decorateClientNbt(
                                        player.server, player.getUUID(), uuid, storedNbt, replyNbt);
                                replyNbt.putBoolean("Rideable", rideablePetUuids.contains(uuid));
                                CompoundTag entry = new CompoundTag();
                                entry.putUUID("UUID", uuid);
                                entry.put("NBT", replyNbt);
                                list.add(entry);
                                sentSnapshot.put(uuid, replyNbt);
                                storedSnapshot.put(uuid, storedNbt);
                            } catch (Exception e) {
                                trulybestfriends.LOGGER.error("Failed to read pet file: {}", f, e);
                            }
                        }
                    }
                }
                PetSyncTracker.replaceFullSnapshot(player.getUUID(), sentSnapshot);
                for (SyncPetDataPacket reply : SyncPetDataPacket.fullListBatches(list)) {
                    SyncPetDataPacket.sendToPlayer(player, reply);
                }
                // 列表已经发出去了，再开始存在性精确探测：命中 presenceProbeWhitelist 的宠物会被逐个查实，
                // 确证不在世界上的随后以 SyncPetDataPacket.delete 从这个列表里消失。
                PetPresenceProbe.requestFor(player, storedSnapshot);
            } else {
                File nbtFile = petDir.resolve(packet.petUuid + ".nbt").toFile();
                if (!nbtFile.exists()) {
                    PetSyncTracker.forgetPet(player.getUUID(), packet.petUuid);
                    SyncPetDataPacket reply = SyncPetDataPacket.delete(packet.petUuid);
                    SyncPetDataPacket.sendToPlayer(player, reply);
                    return;
                }

                try {
                    CompoundTag storedNbt = NbtFileIO.readCompressed(nbtFile);
                    CompoundTag liveNbt = getLoadedPetNbt(player, packet.petUuid, storedNbt);
                    CompoundTag replyNbt = liveNbt != null ? liveNbt : toClientNbt(storedNbt);
                    // 显式写入 false 可清除客户端过期状态，因为更新会合并键。
                    replyNbt.putBoolean("Lost", shouldMarkLost(storedNbt, liveNbt != null));
                    // 注入内存中的死亡时刻（不写盘），供客户端计算复活冷却
                    trulybestfriends.injectDeathTimeIntoNbt(packet.petUuid, replyNbt);
                    PetHealingManager.decorateClientNbt(
                            player.server, player.getUUID(), packet.petUuid, storedNbt, replyNbt);
                    decorateRideableState(player, packet.petUuid, replyNbt);
                    if (PetSyncTracker.shouldSendUpdate(player.getUUID(), packet.petUuid, replyNbt)) {
                        SyncPetDataPacket reply = SyncPetDataPacket.update(packet.petUuid, replyNbt);
                        SyncPetDataPacket.sendToPlayer(player, reply);
                    }
                } catch (Exception e) {
                    trulybestfriends.LOGGER.error("Failed to read pet file for {}: {}", packet.petUuid, e.getMessage());
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static CompoundTag getLoadedPetNbt(ServerPlayer player, UUID petUuid, CompoundTag storedNbt) {
        CompoundTag shoulderNbt = PetIOUtil.getShoulderEntity(player, petUuid);
        if (shoulderNbt != null) {
            CompoundTag nbt = toClientNbt(shoulderNbt);
            preserveStoredUiFields(storedNbt, nbt);
            TbfOwnerTag.write(nbt, player.getUUID());
            String typeKey = shoulderNbt.getString("id");
            if (!typeKey.isEmpty()) nbt.putString("EntityType", typeKey);
            nbt.putString("Dimension", player.serverLevel().dimension().location().toString());
            return nbt;
        }

        ServerLevel storedLevel = getStoredLevel(player, storedNbt);
        CompoundTag nbt = storedLevel != null ? getLoadedPetNbtFromLevel(player, petUuid, storedLevel, storedNbt) : null;
        if (nbt != null) return nbt;

        for (ServerLevel level : player.server.getAllLevels()) {
            if (level == storedLevel) continue;
            nbt = getLoadedPetNbtFromLevel(player, petUuid, level, storedNbt);
            if (nbt != null) return nbt;
        }
        return null;
    }

    static CompoundTag createUpdateNbt(ServerPlayer player, UUID petUuid, CompoundTag storedNbt) {
        CompoundTag liveNbt = getLoadedPetNbt(player, petUuid, storedNbt);
        CompoundTag replyNbt = liveNbt != null ? liveNbt : toClientNbt(storedNbt);
        replyNbt.putBoolean("Lost", shouldMarkLost(storedNbt, liveNbt != null));
        trulybestfriends.injectDeathTimeIntoNbt(petUuid, replyNbt);
        PetHealingManager.decorateClientNbt(
                player.server, player.getUUID(), petUuid, storedNbt, replyNbt);
        decorateRideableState(player, petUuid, replyNbt);
        return replyNbt;
    }

    private static void decorateRideableState(ServerPlayer player, UUID petUuid, CompoundTag nbt) {
        nbt.putBoolean("Rideable", trulybestfriends.isPetRideable(player.serverLevel(), petUuid));
    }

    static boolean shouldMarkLost(CompoundTag storedNbt, boolean loaded) {
        return !loaded && !PetDeathState.isDeadSnapshot(storedNbt);
    }

    /** 检查某个宠物实体当前是否已加载且归属于该玩家
     *  （在任意服务端世界中）。用于在完整列表快照中设置 "Lost" 标志，
     *  而无需构建完整界面 NBT 的开销。 */
    private static boolean isPetLoaded(ServerPlayer player, UUID petUuid) {
        if (PetIOUtil.getShoulderEntity(player, petUuid) != null) return true;
        return PetIOUtil.findEntity(player.server, petUuid, entity ->
                entity instanceof OwnableEntity ownable
                        && player.getUUID().equals(ownable.getOwnerUUID())) != null;
    }

    private static ServerLevel getStoredLevel(ServerPlayer player, CompoundTag storedNbt) {
        return PetIOUtil.getLevel(player.server, storedNbt.getString("Dimension"));
    }

    private static CompoundTag getLoadedPetNbtFromLevel(ServerPlayer player, UUID petUuid, ServerLevel level, CompoundTag storedNbt) {
        Entity entity = level.getEntity(petUuid);
        if (entity == null
                || !(entity instanceof OwnableEntity ownable)
                || !player.getUUID().equals(ownable.getOwnerUUID())) {
            return null;
        }

        CompoundTag nbt = toClientNbt(entity, level, storedNbt);
        preserveStoredUiFields(storedNbt, nbt);
        return nbt;
    }

    private static CompoundTag toClientNbt(Entity entity, ServerLevel level, CompoundTag storedNbt) {
        CompoundTag nbt = toClientNbt(storedNbt);
        // 优先使用实时的 CustomName，而非过期的磁盘值（宠物可能自上次保存以来
        // 已被重命名，或磁盘 NBT 早于追踪开始）。
        if (entity.hasCustomName()) {
            nbt.putString("CustomName", Component.Serializer.toJson(entity.getCustomName()));
        } else {
            nbt.remove("CustomName");
        }
        // 把 TBF 记录的主人带到客户端界面，使用 TBF 自己的键，读取新
        // 键或重命名前快照的旧字符串。
        nbt.putString(TbfOwnerTag.KEY, TbfOwnerTag.readString(storedNbt));
        nbt.putString("EntityType", ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString());
        nbt.putString("Dimension", level.dimension().location().toString());

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(entity.getX()));
        pos.add(DoubleTag.valueOf(entity.getY()));
        pos.add(DoubleTag.valueOf(entity.getZ()));
        nbt.put("Pos", pos);

        if (entity instanceof LivingEntity living) {
            nbt.putFloat("Health", living.getHealth());
            nbt.putFloat("MaxHealth", (float) living.getAttributeValue(Attributes.MAX_HEALTH));
            CompoundTag maxHealth = new CompoundTag();
            maxHealth.putString("Name", "minecraft:generic.max_health");
            maxHealth.putFloat("Base", (float) living.getAttributeValue(Attributes.MAX_HEALTH));
            ListTag attributes = new ListTag();
            attributes.add(maxHealth);
            nbt.put("Attributes", attributes);
        }
        return nbt;
    }

    static CompoundTag toClientNbt(CompoundTag source) {
        CompoundTag nbt = source.copy();
        // 渲染或管理被追踪的宠物并不需要实体树和存储的背包内容。
        // 保留 ArmorItem、ArmorItems、HandItems 和 SaddleItem 等
        // 视觉装备字段，以供界面预览。
        nbt.remove("Passengers");
        nbt.remove("Items");
        nbt.remove("Inventory");
        nbt.remove("TBF_ChestSize");
        nbt.remove("TBF_ChestItems");
        nbt.remove("TBF_ItemHandlerSize");
        nbt.remove("TBF_ItemHandlerItems");
        return nbt;
    }

    private static void preserveStoredUiFields(CompoundTag storedNbt, CompoundTag nbt) {
        if (storedNbt.contains("Priority")) {
            nbt.putInt("Priority", PetIOUtil.clampPriority(storedNbt.getInt("Priority")));
        }
        if (storedNbt.getBoolean("Recalled")) {
            nbt.putBoolean("Recalled", true);
        }
        // 注册时间只存在于宠物文件里，实时实体 NBT 不会带，
        // 因此从肩上实体拼装客户端 NBT 时需要单独补上，否则列表按注册时间排序时会丢失依据。
        if (storedNbt.contains(PetIOUtil.REGISTERED_AT_KEY)) {
            nbt.putLong(PetIOUtil.REGISTERED_AT_KEY, storedNbt.getLong(PetIOUtil.REGISTERED_AT_KEY));
        }
    }
}
