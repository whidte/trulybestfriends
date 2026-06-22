package com.whidte.trulybestfriends.tab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Handles all disk I/O for pet NBT data.
 * Used by TrulyScreen to load and refresh pet data from the world save directory.
 * Entity creation is deferred to TrulyScreen/PetEntry on demand.
 */
final class PetDataLoader {

	private PetDataLoader() {}

	/** Resolve the owner-specific pet save directory.
	 *  Returns null in multiplayer (client cannot access server saves); use
	 *  RequestPetDataPacket / SyncPetDataPacket for multiplayer data sync. */
	static Path getPetSaveDir(Minecraft mc) {
		if (mc.player == null) return null;
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			Path worldPath = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
			return worldPath.resolve("trulybestfriends").resolve(mc.player.getUUID().toString());
		}
		// Multiplayer: client cannot read server saves. Data must arrive via
		// SyncPetDataPacket (server -> client). Return null so callers skip disk I/O.
		return null;
	}

	/** Full reload: populate cache and priorities from disk. Entity creation is deferred. */
	static void loadAll(Minecraft mc, Map<UUID, CompoundTag> cache, Map<UUID, Integer> priorities) {
		cache.clear();
		priorities.clear();

		Path petDir = getPetSaveDir(mc);
		if (petDir == null || !Files.exists(petDir)) return;

		try {
			Files.list(petDir).filter(p -> p.toString().endsWith(".nbt")).forEach(file -> {
				try {
					CompoundTag nbt = NbtIo.readCompressed(file.toFile());
					String uuidStr = file.getFileName().toString().replace(".nbt", "");
					UUID uuid = UUID.fromString(uuidStr);
					cache.put(uuid, nbt);

					int priority = nbt.contains("Priority") ? nbt.getInt("Priority") : 6;
					priorities.put(uuid, Math.max(1, Math.min(6, priority)));
				} catch (Exception e) {
					trulybestfriends.LOGGER.error("Failed to read pet file: {}", file);
				}
			});
		} catch (IOException e) {
			trulybestfriends.LOGGER.error("Failed to list pet files", e);
		}
	}
}
