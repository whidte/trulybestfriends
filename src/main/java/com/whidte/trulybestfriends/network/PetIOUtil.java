package com.whidte.trulybestfriends.network;

import com.whidte.trulybestfriends.TbfOwnerTag;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 从各个数据包处理器中提取出的宠物 I/O 操作共享工具。
 * 集中处理：主人目录解析、安全 Y 搜索、肩上实体操作，
 * 以及私有肩上方法的反射缓存。
 */
public final class PetIOUtil {
	public static final int MIN_PRIORITY = 1;
	public static final int MAX_PRIORITY = 6;
	public static final int DEFAULT_PRIORITY = MAX_PRIORITY;

    private PetIOUtil() {}

	public static int clampPriority(int priority) {
		return Math.max(MIN_PRIORITY, Math.min(MAX_PRIORITY, priority));
	}

	public static int priorityFrom(CompoundTag nbt) {
		return nbt != null && nbt.contains("Priority")
				? clampPriority(nbt.getInt("Priority")) : DEFAULT_PRIORITY;
	}

	/** 记录宠物首次注册进模组的时间戳（毫秒）所用的 NBT 键。 */
	public static final String REGISTERED_AT_KEY = "TBF_RegisteredAt";

	/** 读取宠物的注册时间戳；键缺失时返回 0，表示注册时间未知。 */
	public static long registeredAtFrom(CompoundTag nbt) {
		return nbt != null && nbt.contains(REGISTERED_AT_KEY)
				? nbt.getLong(REGISTERED_AT_KEY) : 0L;
	}

	/**
	 * 为即将落盘的快照补齐注册时间戳：优先沿用旧文件里已有的值，
	 * 只有在文件尚不存在（即首次注册）时才写入当前时间。
	 *
	 * <p>升级前就已存在、且不含该键的旧文件保持缺失，
	 * 这样旧宠物会统一归入“注册时间未知”（读取为 0，排在最早一端），
	 * 而不会被升级那一刻刷成同一批“新宠物”。
	 * 另外，只要旧文件里已有该键就一定原样沿用，
	 * 因此内容未变化的重复保存仍能与旧文件逐键相等，
	 * 不会因为补写时间戳而触发多余的重写。</p>
	 */
	static void applyRegisteredAt(File nbtFile, CompoundTag nbt, CompoundTag oldNbt) {
		if (nbt.contains(REGISTERED_AT_KEY)) return;
		long carried = registeredAtFrom(oldNbt);
		if (carried > 0L) {
			nbt.putLong(REGISTERED_AT_KEY, carried);
		} else if (!nbtFile.exists()) {
			nbt.putLong(REGISTERED_AT_KEY, System.currentTimeMillis());
		}
	}

	/** 最大生命值属性的注册名（1.21.1 及更早的写法）。 */
	private static final String MAX_HEALTH_ATTRIBUTE = "minecraft:generic.max_health";
	/** 部分上游模组省略命名空间时写入的简写形式。 */
	private static final String LEGACY_MAX_HEALTH_ATTRIBUTE = "generic.max_health";
	/** 无法从快照推断最大生命值时的兜底值（与原版多数生物一致）。 */
	public static final float DEFAULT_MAX_HEALTH = 20.0F;

	/** 读取宠物快照的当前生命值；键缺失视为 0。 */
	public static float healthFrom(CompoundTag nbt) {
		return nbt != null && nbt.contains("Health") ? Math.max(0.0F, nbt.getFloat("Health")) : 0.0F;
	}

	/**
	 * 读取宠物快照的最大生命值。
	 *
	 * <p>优先使用顶层 MaxHealth（由快照捕获时从 getAttributeValue 写入）。
	 * 该键缺失或为零时才回退扫描原版 Attributes 列表——因为 Attributes.Base
	 * 记录的是未驯服时的基础值（例如已驯服的狼为 20 而非 40），
	 * 只应作为最后手段。两者都拿不到时返回 {@link #DEFAULT_MAX_HEALTH}。</p>
	 */
	public static float maxHealthFrom(CompoundTag nbt) {
		if (nbt == null) return DEFAULT_MAX_HEALTH;
		float maxHealth = nbt.contains("MaxHealth") ? nbt.getFloat("MaxHealth") : 0.0F;
		if (maxHealth <= 0.0F && nbt.contains("Attributes")) {
			for (Tag raw : nbt.getList("Attributes", Tag.TAG_COMPOUND)) {
				CompoundTag attribute = (CompoundTag) raw;
				String name = attribute.getString("Name");
				if (MAX_HEALTH_ATTRIBUTE.equals(name) || LEGACY_MAX_HEALTH_ATTRIBUTE.equals(name)) {
					maxHealth = attribute.getFloat("Base");
					break;
				}
			}
		}
		return maxHealth > 0.0F ? maxHealth : DEFAULT_MAX_HEALTH;
	}

	/**
	 * 当前生命值占最大生命值的比例，钳制在 [0, 1]。
	 *
	 * <p>口径刻意与界面上那条血条（{@code renderHealthBar}）保持一致：
	 * 超出满血的值一律按 1 处理，因此“按生命值排序”与玩家肉眼看到的血条
	 * 长度永远同序。已死亡（Health 为 0）的宠物比例为 0，排在“最需要治疗”的一端。</p>
	 */
	public static float healthRatioFrom(CompoundTag nbt) {
		float ratio = healthFrom(nbt) / maxHealthFrom(nbt);
		return Math.max(0.0F, Math.min(1.0F, ratio));
	}

    /** 写入宠物快照，并使收回状态索引与其保持同步。 */
    public static void writePetState(File file, CompoundTag nbt,
                                     ServerLevel level, UUID petUuid) throws IOException {
        NbtFileIO.writeCompressed(nbt, file);
        trulybestfriends.updatePetRecalledState(level, petUuid, nbt.getBoolean("Recalled"));
    }

    // ---- 主人目录 ----

    public static Path getModDir(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("trulybestfriends");
    }

    public static Path getModDir(ServerPlayer player) {
        return player.server.getWorldPath(LevelResource.ROOT).resolve("trulybestfriends");
    }

    public static Path getModDir(Path worldPath) {
        return worldPath.resolve("trulybestfriends");
    }

    /** 解析 {world}/trulybestfriends/{ownerUuid} 下每个主人的宠物存储目录。 */
    public static Path getOwnerDir(Path modDir, UUID playerUuid) {
        return modDir.resolve(playerUuid.toString());
    }

    public static Path getOwnerDir(ServerLevel level, UUID playerUuid) {
        return getOwnerDir(getModDir(level), playerUuid);
    }

    public static Path getOwnerDir(ServerPlayer player) {
        return getModDir(player).resolve(player.getUUID().toString());
    }

    /**
     * 已存储宠物路径中记录的主人 UUID：宠物存放于
     * {@code {world}/trulybestfriends/{ownerUuid}/{petUuid}.nbt}，因此父目录名就是
     * TBF 为该宠物记录的主人。当快照不再携带主人时，用作最后手段的主人提示。
     *
     * @return 主人 UUID；当路径不遵循该布局时返回 {@code null}。
     */
    public static UUID ownerFromPetFile(File petFile) {
        if (petFile == null) return null;
        File parent = petFile.getParentFile();
        if (parent == null) return null;
        try {
            return UUID.fromString(parent.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 仅对以 UUID 命名的宠物快照返回 true，排除 team.nbt 之类的元数据文件。 */
    public static boolean isPetDataFileName(String fileName) {
        if (fileName == null || !fileName.endsWith(".nbt")) return false;
        try {
            UUID.fromString(fileName.substring(0, fileName.length() - 4));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isPetDataFile(Path path) {
        return Files.isRegularFile(path) && isPetDataFileName(path.getFileName().toString());
    }

    public static Entity findEntity(MinecraftServer server, UUID entityUuid) {
        return findEntity(server, entityUuid, entity -> true);
    }

    public static Entity findEntity(MinecraftServer server, UUID entityUuid, Predicate<Entity> filter) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityUuid);
            if (entity != null && filter.test(entity)) return entity;
        }
        return null;
    }

    public static ServerLevel getLevel(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    static ChunkPos getStoredChunk(CompoundTag nbt) {
        if (nbt.contains("ChunkX", Tag.TAG_ANY_NUMERIC)
                && nbt.contains("ChunkZ", Tag.TAG_ANY_NUMERIC)) {
            return new ChunkPos(nbt.getInt("ChunkX"), nbt.getInt("ChunkZ"));
        }
        if (!nbt.contains("Pos", Tag.TAG_LIST)) return null;
        var position = nbt.getList("Pos", Tag.TAG_DOUBLE);
        return position.size() >= 3
                ? new ChunkPos(net.minecraft.util.Mth.floor(position.getDouble(0)) >> 4,
                        net.minecraft.util.Mth.floor(position.getDouble(2)) >> 4)
                : null;
    }

    // ---- 安全 Y 搜索 ----

    /** 为给定实体在 (x, yBase, z) 附近寻找安全的 Y，向上最多扫描 5 格。 */
    public static double findSafeY(ServerLevel level, double x, double yBase, double z, Entity entity) {
        float hw = entity instanceof LivingEntity le ? le.getBbWidth() / 2f : 0.3f;
        float h = entity instanceof LivingEntity le ? le.getBbHeight() : 1.8f;
        return findSafeY(level, x, yBase, z, hw, h, entity);
    }

    /** 在给定显式半宽和高度的情况下，寻找 (x, yBase, z) 附近安全的 Y。 */
    public static double findSafeY(ServerLevel level, double x, double yBase, double z, float hw, float h) {
        return findSafeY(level, x, yBase, z, hw, h, null);
    }

    private static double findSafeY(ServerLevel level, double x, double yBase, double z, float hw, float h, Entity entity) {
        for (int dy = 0; dy <= 5; dy++) {
            double y = yBase + dy;
            AABB box = new AABB(x - hw, y, z - hw, x + hw, y + h, z + hw);
            // RevivePetPacket 不传实体（使用 noCollision(box) 重载）；
            // 其他调用方传入实体（使用 noCollision(entity, box) 重载）。
            boolean safe = (entity != null)
                    ? level.noCollision(entity, box) && !level.containsAnyLiquid(box)
                    : level.noCollision(box) && !level.containsAnyLiquid(box);
            if (safe) return y;
        }
        return yBase;
    }

    public static Vec3 findSafePositionNearPlayer(ServerLevel level, ServerPlayer player, Entity entity,
                                                   int minRadius, int maxRadius, int attemptsPerRadius) {
        float halfWidth = entity instanceof LivingEntity living ? living.getBbWidth() / 2f : 0.3f;
        float height = entity instanceof LivingEntity living ? living.getBbHeight() : 1.8f;
        return findSafePositionNearPlayer(level, player, halfWidth, height,
                minRadius, maxRadius, attemptsPerRadius, entity);
    }

    public static Vec3 findSafePositionNearPlayer(ServerLevel level, ServerPlayer player,
                                                   float halfWidth, float height,
                                                   int minRadius, int maxRadius, int attemptsPerRadius) {
        return findSafePositionNearPlayer(level, player, halfWidth, height,
                minRadius, maxRadius, attemptsPerRadius, null);
    }

    private static Vec3 findSafePositionNearPlayer(ServerLevel level, ServerPlayer player,
                                                    float halfWidth, float height,
                                                    int minRadius, int maxRadius, int attemptsPerRadius,
                                                    Entity entity) {
        for (int radius = Math.max(1, minRadius); radius <= maxRadius; radius++) {
            for (int attempt = 0; attempt < attemptsPerRadius; attempt++) {
                double angle = level.random.nextDouble() * Math.PI * 2;
                double x = player.getX() + Math.cos(angle) * radius;
                double z = player.getZ() + Math.sin(angle) * radius;
                for (int verticalDistance = 0; verticalDistance <= 5; verticalDistance++) {
                    int directionStart = verticalDistance == 0 ? 1 : -1;
                    int directionEnd = 1;
                    for (int direction = directionStart; direction <= directionEnd; direction += 2) {
                        double y = player.getY() + direction * verticalDistance;
                        if (!hasClearVerticalPath(level, entity, x, y, z, halfWidth, height, player.getY())) {
                            continue;
                        }
                        AABB box = new AABB(x - halfWidth, y, z - halfWidth,
                                x + halfWidth, y + height, z + halfWidth);
                        boolean clear = entity != null
                                ? level.noCollision(entity, box)
                                : level.noCollision(box);
                        if (!clear || level.containsAnyLiquid(box)) continue;

                        // 召唤/传送路径会传入还原后/存活的 Mob，因此使用
                        // 与原版宠物传送相同的方块级评估器。
                        if (entity instanceof Mob mob
                                && WalkNodeEvaluator.getPathTypeStatic(mob, BlockPos.containing(x, y, z))
                                != PathType.WALKABLE) continue;

                        return new Vec3(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    /** 防止垂直传送穿过地板或天花板进入另一层。 */
    private static boolean hasClearVerticalPath(ServerLevel level, Entity entity,
                                                 double x, double y, double z,
                                                 float halfWidth, float height,
                                                 double playerY) {
        double minY;
        double maxY;
        if (y < playerY) {
            minY = y + height;
            maxY = playerY;
        } else {
            minY = playerY;
            maxY = y;
        }
        if (maxY - minY <= 1.0E-3) return true;

        AABB passage = new AABB(x - halfWidth, minY, z - halfWidth,
                x + halfWidth, maxY, z + halfWidth);
        boolean clear = entity != null
                ? level.noCollision(entity, passage)
                : level.noCollision(passage);
        return clear && !level.containsAnyLiquid(passage);
    }

    public static void writePetSnapshot(File nbtFile, CompoundTag snapshot, boolean recalled) throws IOException {
        writePetSnapshot(nbtFile, snapshot, recalled, false);
    }

    public static void writePetSnapshotPreservingRecall(File nbtFile, CompoundTag snapshot) throws IOException {
        writePetSnapshot(nbtFile, snapshot, false, true);
    }

    private static void writePetSnapshot(File nbtFile, CompoundTag snapshot,
                                         boolean recalled, boolean preserveRecalled) throws IOException {
        CompoundTag oldNbt = null;
        if (nbtFile.exists()) {
            try {
                oldNbt = NbtFileIO.readCompressed(nbtFile);
            } catch (IOException ignored) {}
        }

        int priority = priorityFrom(oldNbt);
        boolean recalledValue = !PetDeathState.isStoredDead(snapshot)
                && (preserveRecalled && oldNbt != null ? oldNbt.getBoolean("Recalled") : recalled);

        CompoundTag nbt = snapshot.copy();
        nbt.putInt("Priority", priority);
        if (recalledValue) nbt.putBoolean("Recalled", true);
        else nbt.remove("Recalled");
        nbt.remove("LastDeathTime");
        applyRegisteredAt(nbtFile, nbt, oldNbt);

        // 内容已在磁盘上时跳过写入。这里“读一次旧文件 + 整树比较”是有意的重复写入防线：
        // 落盘内容并不等同于调用方给的快照（要补 Priority、可能剔除 Recalled 与 LastDeathTime、
        // 可能补写注册时间），而且其它落盘路径（SetPriorityPacket、writePetState 的各个调用方）
        // 也会改写同一个文件，所以“快照没变”不足以证明磁盘内容没变，必须真的比对一次。
        if (oldNbt != null && oldNbt.equals(nbt)) return;

        NbtFileIO.writeCompressed(nbt, nbtFile);
    }

    // ---- 肩上实体辅助方法 ----

    /** 返回与 petUuid 匹配的肩上 NBT，若不在任一肩上则返回 null。 */
    public static CompoundTag getShoulderEntity(ServerPlayer player, UUID petUuid) {
        return findShoulderEntity(player.getShoulderEntityLeft(), player.getShoulderEntityRight(), petUuid);
    }

    static CompoundTag findShoulderEntity(CompoundTag left, CompoundTag right, UUID petUuid) {
        if (left.hasUUID("UUID") && left.getUUID("UUID").equals(petUuid)) return left;
        if (right.hasUUID("UUID") && right.getUUID("UUID").equals(petUuid)) return right;
        return null;
    }

    /** 清除当前持有 petUuid 的肩上槽位（左或右）。 */
    public static void clearShoulderSlot(ServerPlayer player, UUID petUuid) {
        CompoundTag left = player.getShoulderEntityLeft();
        if (left.contains("UUID") && left.getUUID("UUID").equals(petUuid)) {
            setShoulderEntity(player, true, new CompoundTag());
            return;
        }
        CompoundTag right = player.getShoulderEntityRight();
        if (right.contains("UUID") && right.getUUID("UUID").equals(petUuid)) {
            setShoulderEntity(player, false, new CompoundTag());
        }
    }

    /** 将肩上宠物的 NBT 保存到主人的目录下，并标记为 Recalled。 */
    public static boolean saveShoulderToDisk(UUID playerUuid, CompoundTag shoulderNbt, ServerLevel level) {
        try {
            Path ownerDir = getOwnerDir(level, playerUuid);
            Files.createDirectories(ownerDir);

            CompoundTag snapshot = shoulderNbt.copy();
            UUID uuid = snapshot.getUUID("UUID");
            String typeKey = snapshot.getString("id");
            snapshot.putString("EntityType", typeKey);
            TbfOwnerTag.write(snapshot, playerUuid);
            snapshot.putString("Dimension", level.dimension().location().toString());
            snapshot.putBoolean("Recalled", true);

            File nbtFile = ownerDir.resolve(uuid + ".nbt").toFile();
            writePetSnapshot(nbtFile, snapshot, true);
            trulybestfriends.updatePetRecalledState(level, uuid, true);
            return true;
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to save shoulder pet: {}", e.getMessage());
            return false;
        }
    }

    // ---- setShoulderEntityLeft / setShoulderEntityRight 的反射缓存 ----

    /** 用于在 SHOULDER_SETTER_CACHE 中标记“该侧没有 setter”的哨兵 Method。 */
    private static final Method NO_METHOD = sentinelMethod();

    private static Method sentinelMethod() {
        try {
            // 任意一个现成可用的方法；实际从不会被调用。
            return Object.class.getDeclaredMethod("getClass");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /** 缓存键：true = 左，false = 右。值：setter Method，若不存在则为 NO_METHOD。 */
    private static final ConcurrentHashMap<Boolean, Method> SHOULDER_SETTER_CACHE = new ConcurrentHashMap<>();

    /**
     * 在玩家上调用私有的 setShoulderEntityLeft/Right 方法。
     * 使用缓存的 Method 以避免重复的 getDeclaredMethod + setAccessible 调用。
     */
    public static void setShoulderEntity(ServerPlayer player, boolean left, CompoundTag tag) {
        try {
            Method method = SHOULDER_SETTER_CACHE.computeIfAbsent(left, key -> {
                String name = key ? "setShoulderEntityLeft" : "setShoulderEntityRight";
                try {
                    Method m = net.minecraft.world.entity.player.Player.class.getDeclaredMethod(name, CompoundTag.class);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException e) {
                    trulybestfriends.LOGGER.error("Shoulder setter not found: {}", name, e);
                    return NO_METHOD;
                }
            });
            if (method == NO_METHOD) return;
            method.invoke(player, tag);
        } catch (Exception e) {
            trulybestfriends.LOGGER.error("Failed to set shoulder entity: {}", e.getMessage());
        }
    }
}
