package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.network.NetworkEvent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 客户端 -> 服务端：收回玩家给定半径内的所有已追踪宠物。 */
public class AreaRecallPacket {
    private final int range;

    public AreaRecallPacket(int range) {
        this.range = range;
    }

    public static void encode(AreaRecallPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.range);
    }

    public static AreaRecallPacket decode(FriendlyByteBuf buf) {
        return new AreaRecallPacket(buf.readVarInt());
    }

    public static void handle(AreaRecallPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            int range = net.minecraft.util.Mth.clamp(packet.range, 1, 16);
            // 收集本次实际收回的宠物（名字 + UUID），结束后合并成一条消息发给主人。
            // UUID 是给点击事件用的——名字上挂 /tbf open <uuid>。
            List<RecalledPet> recalled = new ArrayList<>();
            UUID ownerUuid = player.getUUID();

            Path ownerDir = PetIOUtil.getOwnerDir(player);
            if (!Files.exists(ownerDir)) {
                ctx.get().setPacketHandled(true);
                return;
            }

            File[] files = ownerDir.toFile().listFiles((f, n) -> PetIOUtil.isPetDataFileName(n));
            if (files == null) {
                ctx.get().setPacketHandled(true);
                return;
            }

            for (File nbtFile : files) {
                try {
                    UUID petUuid = UUID.fromString(nbtFile.getName().replace(".nbt", ""));

                    // 优化：先检查实体是否已加载到世界中，
                    // 再读取 NBT 文件。已加载的实体可以依据其
                    // 实时状态（生命值、距离）过滤，无需磁盘 I/O。
                    // 只有未加载的宠物才需要读取文件来获取 Pos/Dimension。
                    Entity entity = level.getEntity(petUuid);
                    // 多部件子部件（例如末影龙的尾巴）从不被直接追踪
                    // ——跳过它们，以免在没有父部件的情况下丢弃某个部件，
                    // 从而破坏多部件实体。
                    if (entity instanceof PartEntity<?>) continue;
                    if (entity instanceof LivingEntity living) {
                        // 跳过未追踪的实体（数据已被清除，例如 clearOnDeath）
                        if (!trulybestfriends.isTrackedPet(petUuid)
                                || !trulybestfriends.isOwnedBy(living, player.getUUID())) continue;
                        // 已在世界中：使用实时状态，跳过已死亡的宠物
                        if (living.getHealth() <= 0) continue;
                        // 已在世界中：检查范围
                        if (living.distanceTo(player) <= range) {
                            // 丢弃前强制下骑，以避免残留的乘客引用
                            living.ejectPassengers();
                            living.stopRiding();
                            Component petName = living.getDisplayName();
                            if (RecallPetPacket.savePetToDisk(player.getUUID(), living, level)) {
                                living.discard();
                                recalled.add(new RecalledPet(petUuid, petName));
                            }
                        }
                        continue;
                    }

                    // 实体不在世界中：必须读取 NBT 以检查状态和位置
                    CompoundTag nbt = NbtFileIO.readCompressed(nbtFile);

                    // 只收回自己的宠物：若快照里记有主人且不是本人，跳过。
                    if (!ownedByPlayer(nbt, player.getUUID())) continue;

                    // 跳过已死亡的宠物
                    if (PetDeathState.isDeadSnapshot(nbt)) continue;
                    // 跳过已收回的宠物
                    if (nbt.getBoolean("Recalled")) continue;

                    // 实体不在世界中：检查肩上
                    CompoundTag shoulderNbt = PetIOUtil.getShoulderEntity(player, petUuid);
                    if (shoulderNbt != null) {
                        if (!ownedByPlayer(shoulderNbt, player.getUUID())) continue;
                        trulybestfriends.flushPendingPetSaves(player.getUUID());
                        Component shoulderName = petNameFromNbt(shoulderNbt);
                        if (PetIOUtil.saveShoulderToDisk(player.getUUID(), shoulderNbt, level)) {
                            PetIOUtil.clearShoulderSlot(player, petUuid);
                            recalled.add(new RecalledPet(petUuid, shoulderName));
                        }
                        continue;
                    }

                    // 检查 NBT 中记录的最后已知位置
                    if (nbt.contains("Dimension") && nbt.contains("Pos")) {
                        String dim = nbt.getString("Dimension");
                        if (!dim.equals(level.dimension().location().toString())) continue;

                        var posList = nbt.getList("Pos", 6);
                        if (posList.size() >= 3) {
                            double dx = posList.getDouble(0) - player.getX();
                            double dy = posList.getDouble(1) - player.getY();
                            double dz = posList.getDouble(2) - player.getZ();
                            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= range) {
                                int chunkX = net.minecraft.util.Mth.floor(posList.getDouble(0)) >> 4;
                                int chunkZ = net.minecraft.util.Mth.floor(posList.getDouble(2)) >> 4;
                                Component petName = petNameFromNbt(nbt);
                                nbt.putBoolean("Recalled", true);
                                PetIOUtil.writePetState(nbtFile, nbt, level, petUuid);
                                if (trulybestfriends.queuePendingRemoval(
                                        player.getUUID(), petUuid, level, chunkX, chunkZ)) {
                                    recalled.add(new RecalledPet(petUuid, petName));
                                } else {
                                    nbt.remove("Recalled");
                                    PetIOUtil.writePetState(nbtFile, nbt, level, petUuid);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    trulybestfriends.LOGGER.error("AreaRecall: failed to process {}: {}", nbtFile.getName(), e.getMessage());
                }
            }

            if (!recalled.isEmpty()) {
                // 只发给宠物主人（按归属 UUID 找在线玩家）；收回者通常就是主人。
                ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUuid);
                if (owner != null) {
                    owner.sendSystemMessage(recallMessage(recalled));
                }
                player.playNotifySound(net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.0f);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /** 本次被收回的一只宠物：显示名 + UUID（UUID 用于生成点击命令）。 */
    record RecalledPet(UUID uuid, Component name) {}

    /** 合并成「<名>、<名>… 已被收回」的单条系统消息；每个名字都可左键点击。 */
    static Component recallMessage(List<RecalledPet> recalled) {
        var merged = Component.empty();
        for (int i = 0; i < recalled.size(); i++) {
            if (i > 0) merged.append(Component.literal("、"));
            merged.append(clickableName(recalled.get(i)));
        }
        merged.append(Component.literal(" 已被收回"));
        return merged;
    }

    /**
     * 给宠物名挂上「点击打开标签页并选中它」的行为。
     *
     * <p>用 {@code RUN_COMMAND} 而不是别的点击动作，是因为点击事件只能执行命令，
     * 而打开界面只能由客户端完成——所以命令在服务端校验归属后，再回发
     * {@link OpenPetScreenPacket} 让客户端开界面。</p>
     *
     * <p>外面包一层 {@code Component.empty().append(...)} 是为了拿到可变的组件，
     * 同时也避免改动实体显示名本身的样式（自定义名可能自带颜色）。</p>
     *
     * <p><b>这里刻意不加 {@code HoverEvent} 悬浮提示</b>：{@code HoverEvent} 的静态初始化
     * 会触碰 {@code BuiltInRegistries}，导致本类无法再被裸 JVM 的冒烟测试覆盖
     * （见 {@code AreaRecallMessageSmokeTest}）。所以改用绿色 + 下划线这种
     * Minecraft 里约定俗成的「可点击」外观来表达可点性——与
     * {@code /tbf clear confirm} 的呈现方式一致。</p>
     */
    private static Component clickableName(RecalledPet pet) {
        return Component.empty().append(pet.name()).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, openCommand(pet.uuid()))));
    }

    /** 「点击打开标签页」所执行的命令。 */
    static String openCommand(UUID petUuid) {
        return "/tbf open " + petUuid;
    }

    /**
     * 校验一份存储快照是否属于该玩家：只有快照<b>明确记录了主人</b>且不是本人时才拒绝。
     * 若快照无法解析出主人（例如模组把归属写在未配置的字段里），则退回「文件在谁的
     * 目录下就归谁」的既有约定，返回 true 以免误伤合法宠物。
     */
    private static boolean ownedByPlayer(CompoundTag nbt, UUID playerUuid) {
        UUID recorded = trulybestfriends.getSnapshotOwnerUUID(nbt);
        return recorded == null || recorded.equals(playerUuid);
    }

    /** 从快照 NBT 解析服务端显示名：优先 CustomName，否则退回实体类型描述。 */
    private static Component petNameFromNbt(CompoundTag nbt) {
        if (nbt.contains("CustomName")) {
            try {
                Component custom = Component.Serializer.fromJson(nbt.getString("CustomName"));
                if (custom != null) return custom;
            } catch (Exception ignored) {}
        }
        ResourceLocation id = ResourceLocation.tryParse(nbt.getString("EntityType"));
        var type = id != null ? BuiltInRegistries.ENTITY_TYPE.get(id) : null;
        return type != null ? type.getDescription() : Component.literal("???");
    }
}
