package com.whidte.trulybestfriends.tab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.whidte.trulybestfriends.network.PetIOUtil;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.whidte.trulybestfriends.network.NbtFileIO;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 处理宠物 NBT 数据的全部磁盘 I/O。
 * 由 TrulyScreen 用于从世界存档目录加载和刷新宠物数据。
 * 实体的创建推迟到 TrulyScreen/PetEntry 按需进行。
 */
final class PetDataLoader {

	private PetDataLoader() {}

	static Component displayName(Minecraft minecraft, CompoundTag nbt) {
		if (nbt.contains("CustomName") && minecraft.level != null) {
			try {
				return Component.Serializer.fromJson(
						nbt.getString("CustomName"), minecraft.level.registryAccess());
			} catch (Exception ignored) {}
		}
		ResourceLocation id = ResourceLocation.tryParse(nbt.getString("EntityType"));
		var type = id != null ? BuiltInRegistries.ENTITY_TYPE.get(id) : null;
		return type != null ? type.getDescription() : Component.literal("???");
	}

	/** 解析特定于主人的宠物存档目录。
	 *  在多人游戏中返回 null（客户端无法访问服务端存档）；请使用
	 *  RequestPetDataPacket / SyncPetDataPacket 进行多人游戏数据同步。 */
	static Path getPetSaveDir(Minecraft mc) {
		if (mc.player == null) return null;
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			Path worldPath = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
			return PetIOUtil.getOwnerDir(PetIOUtil.getModDir(worldPath), mc.player.getUUID());
		}
		// 多人游戏：客户端无法读取服务端存档。数据必须通过
		// SyncPetDataPacket（服务端 -> 客户端）送达。返回 null，让调用方跳过磁盘 I/O。
		return null;
	}

	/** 完整重载：从磁盘填充缓存和优先级。实体的创建被推迟。 */
	static void loadAll(Minecraft mc, Map<UUID, CompoundTag> cache, Map<UUID, Integer> priorities) {
		cache.clear();
		priorities.clear();

		Path petDir = getPetSaveDir(mc);
		if (petDir == null || !Files.exists(petDir)) return;

		try (var files = Files.list(petDir)) {
			files.filter(PetIOUtil::isPetDataFile).forEach(file -> {
				try {
					CompoundTag nbt = NbtFileIO.readCompressed(file.toFile());
					String uuidStr = file.getFileName().toString().replace(".nbt", "");
					UUID uuid = UUID.fromString(uuidStr);
					cache.put(uuid, nbt);

					priorities.put(uuid, PetIOUtil.priorityFrom(nbt));
				} catch (Exception e) {
					trulybestfriends.LOGGER.error("Failed to read pet file: {}", file);
				}
			});
		} catch (IOException e) {
			trulybestfriends.LOGGER.error("Failed to list pet files", e);
		}
	}
}
