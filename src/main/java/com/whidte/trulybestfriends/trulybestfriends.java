package com.whidte.trulybestfriends;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.whidte.trulybestfriends.network.AreaRecallPacket;
import com.whidte.trulybestfriends.network.GlowPetPacket;
import com.whidte.trulybestfriends.network.PetWarningPacket;
import com.whidte.trulybestfriends.network.RecallPetPacket;
import com.whidte.trulybestfriends.network.RequestPetDataPacket;
import com.whidte.trulybestfriends.network.RevivePetPacket;
import com.whidte.trulybestfriends.network.SetPriorityPacket;
import com.whidte.trulybestfriends.network.SyncPetDataPacket;
import com.whidte.trulybestfriends.network.TeleportPetToPlayerPacket;
import com.whidte.trulybestfriends.network.TeleportToPetPacket;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.AnimalTameEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
@Mod(value = trulybestfriends.MODID)
public class trulybestfriends {
    public static final String MODID = "trulybestfriends";
    public static final String NAME = "Truly Best Friends Forever";
    public static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private static final String PETS_INDEX_FILE = "pets_index.nbt";
    private static final Map<String, List<UUID>> indexCache = new ConcurrentHashMap<>();
    private static final Set<UUID> trackedPetUUIDs = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, String> shoulderPetTypes = new ConcurrentHashMap<>(); // UUID -> entity type key

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

	public static final KeyMapping OPEN_TAB_KEY = new KeyMapping(
			"key.trulybestfriends.open_tab",
			InputConstants.UNKNOWN.getValue(),
			"key.categories.trulybestfriends"
	);

    private int tickCounter = 0;

    public trulybestfriends(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
        context.getModEventBus().addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        CHANNEL.registerMessage(0, GlowPetPacket.class, GlowPetPacket::encode, GlowPetPacket::decode, GlowPetPacket::handle);
        CHANNEL.registerMessage(1, RecallPetPacket.class, RecallPetPacket::encode, RecallPetPacket::decode, RecallPetPacket::handle);
        CHANNEL.registerMessage(2, TeleportToPetPacket.class, TeleportToPetPacket::encode, TeleportToPetPacket::decode, TeleportToPetPacket::handle);
        CHANNEL.registerMessage(3, TeleportPetToPlayerPacket.class, TeleportPetToPlayerPacket::encode, TeleportPetToPlayerPacket::decode, TeleportPetToPlayerPacket::handle);
        CHANNEL.registerMessage(4, AreaRecallPacket.class, AreaRecallPacket::encode, AreaRecallPacket::decode, AreaRecallPacket::handle);
        CHANNEL.registerMessage(5, PetWarningPacket.class, PetWarningPacket::encode, PetWarningPacket::decode, PetWarningPacket::handle);
        CHANNEL.registerMessage(6, RequestPetDataPacket.class, RequestPetDataPacket::encode, RequestPetDataPacket::decode, RequestPetDataPacket::handle);
        CHANNEL.registerMessage(7, RevivePetPacket.class, RevivePetPacket::encode, RevivePetPacket::decode, RevivePetPacket::handle);
        CHANNEL.registerMessage(8, SetPriorityPacket.class, SetPriorityPacket::encode, SetPriorityPacket::decode, SetPriorityPacket::handle);
        CHANNEL.registerMessage(9, SyncPetDataPacket.class, SyncPetDataPacket::encode, SyncPetDataPacket::decode, SyncPetDataPacket::handle);
    }

    @SubscribeEvent
    public void onAnimalTamed(AnimalTameEvent event) {
        Entity animal = event.getAnimal();
        if (!animal.level().isClientSide() && animal instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null) {
            UUID owner = ownable.getOwnerUUID();
            if (countOwnerPets((ServerLevel) animal.level(), owner) >= Config.maxPets) {
                ServerPlayer ownerPlayer = animal.level().getServer().getPlayerList().getPlayer(owner);
                if (ownerPlayer != null) {
                    ownerPlayer.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable("trulybestfriends.limit.reached", Config.maxPets)
                                    .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
                return;
            }
            savePetData(owner, animal, (ServerLevel) animal.level());
            updatePetIndex(animal);
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && Config.enableLoginLoadDiagnostics) {
            loadPlayerPetsData(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tickCounter++;
            if (tickCounter >= Config.syncIntervalTicks) {
                tickCounter = 0;
                syncAllPets(event.getServer());
                trackShoulderPets(event.getServer());
            }
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        if (!entity.level().isClientSide()
                && trackedPetUUIDs.contains(entity.getUUID())
                && entity instanceof OwnableEntity ownable
                && ownable.getOwnerUUID() != null) {
            savePetData(ownable.getOwnerUUID(), entity, (ServerLevel) entity.level());
        }
    }

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        if (trackedPetUUIDs.contains(event.getEntity().getUUID())) {
            event.getDrops().clear();
        }
    }

    private void savePetData(UUID ownerUUID, Entity pet, ServerLevel level) {
        try {
            Path worldPath = level.getServer().getWorldPath(LevelResource.ROOT);
            Path modDir = worldPath.resolve("trulybestfriends");
            Files.createDirectories(modDir);

            Path ownerDir = modDir.resolve(ownerUUID.toString());
            Files.createDirectories(ownerDir);

            File nbtFile = ownerDir.resolve(pet.getUUID() + ".nbt").toFile();

            // Preserve Priority from existing file
            int existingPriority = 6;
            boolean existingRecalled = false;
            if (nbtFile.exists()) {
                try {
                    CompoundTag oldNbt = NbtIo.readCompressed(nbtFile);
                    if (oldNbt.contains("Priority")) {
                        existingPriority = Math.max(1, Math.min(6, oldNbt.getInt("Priority")));
                    }
                    existingRecalled = oldNbt.getBoolean("Recalled");
                } catch (IOException ignored) {}
            }

            CompoundTag nbt = new CompoundTag();
            pet.saveWithoutId(nbt);
            com.whidte.trulybestfriends.network.TeleportPetToPlayerPacket.backupChestInventory(pet, nbt);
            nbt.putString("OwnerUUID", ownerUUID.toString());
            nbt.putString("EntityType", ForgeRegistries.ENTITY_TYPES.getKey(pet.getType()).toString());
            nbt.putString("Dimension", level.dimension().location().toString());
            nbt.putInt("Priority", existingPriority);
            if (existingRecalled) nbt.putBoolean("Recalled", true);

            NbtIo.writeCompressed(nbt, nbtFile);
        } catch (IOException e) {
            LOGGER.error("Failed to save pet data for {}: {}", pet.getUUID(), e.getMessage());
        }
    }

    private void updatePetIndex(Entity pet) {
        try {
            ServerLevel level = (ServerLevel) pet.level();
            Path modDir = level.getServer().getWorldPath(LevelResource.ROOT).resolve("trulybestfriends");
            File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();

            CompoundTag indexTag = new CompoundTag();
            if (indexFile.exists()) {
                indexTag = NbtIo.readCompressed(indexFile);
            }

            String typeKey = ForgeRegistries.ENTITY_TYPES.getKey(pet.getType()).toString();
            UUID petUUID = pet.getUUID();
            String uuidStr = petUUID.toString();

            ListTag uuidList = indexTag.getList(typeKey, Tag.TAG_STRING);
            boolean alreadyExists = false;
            for (Tag tag : uuidList) {
                if (tag instanceof StringTag st && uuidStr.equals(st.getAsString())) {
                    alreadyExists = true;
                    break;
                }
            }
            if (!alreadyExists) {
                uuidList.add(StringTag.valueOf(uuidStr));
                indexTag.put(typeKey, uuidList);
                NbtIo.writeCompressed(indexTag, indexFile);

                indexCache.computeIfAbsent(typeKey, k -> new ArrayList<>()).add(petUUID);
            }

            trackedPetUUIDs.add(petUUID);
        } catch (IOException e) {
            LOGGER.error("Failed to update pet index for {}: {}", pet.getUUID(), e.getMessage());
        }
    }

    public static Map<String, List<UUID>> loadPetIndex(ServerLevel level) {
        if (!indexCache.isEmpty()) return indexCache;

        try {
            Path modDir = level.getServer().getWorldPath(LevelResource.ROOT).resolve("trulybestfriends");
            File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();

            if (indexFile.exists()) {
                CompoundTag indexTag = NbtIo.readCompressed(indexFile);
                for (String key : indexTag.getAllKeys()) {
                    ListTag uuidList = indexTag.getList(key, Tag.TAG_STRING);
                    List<UUID> uuids = new ArrayList<>();
                    for (Tag rawTag : uuidList) {
                        if (rawTag instanceof StringTag stringTag) {
                            try {
                                UUID uuid = UUID.fromString(stringTag.getAsString());
                                uuids.add(uuid);
                                trackedPetUUIDs.add(uuid);
                            } catch (IllegalArgumentException e) {
                                LOGGER.warn("Invalid UUID in pet index: {}", stringTag.getAsString());
                            }
                        }
                    }
                    indexCache.put(key, uuids);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load pet index: {}", e.getMessage());
        }
        return indexCache;
    }

    private void syncAllPets(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof OwnableEntity ownable
                        && ownable.getOwnerUUID() != null) {
                    if (!trackedPetUUIDs.contains(entity.getUUID())) {
                        UUID owner = ownable.getOwnerUUID();
                        if (countOwnerPets(level, owner) >= Config.maxPets) continue;
                        updatePetIndex(entity);
                    }
                    // If pet was recalled while chunk was unloaded, complete the removal now
                    Path modDir = level.getServer().getWorldPath(LevelResource.ROOT)
                            .resolve("trulybestfriends")
                            .resolve(ownable.getOwnerUUID().toString());
                    File nbtFile = modDir.resolve(entity.getUUID() + ".nbt").toFile();
                    if (nbtFile.exists()) {
                        try {
                            CompoundTag existing = NbtIo.readCompressed(nbtFile);
                            if (existing.getBoolean("Recalled")) {
                                entity.discard();
                                continue; // don't save over the Recalled NBT
                            }
                        } catch (IOException ignored) {}
                    }
                    savePetData(ownable.getOwnerUUID(), entity, level);
                }
            }
        }
    }

    private void trackShoulderPets(MinecraftServer server) {
        Map<UUID, String> currentShoulder = new java.util.HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (var shoulder : new CompoundTag[]{player.getShoulderEntityLeft(), player.getShoulderEntityRight()}) {
                if (shoulder.contains("UUID") && !shoulder.isEmpty()) {
                    UUID uuid = shoulder.getUUID("UUID");
                    String typeKey = shoulder.getString("id");
                    currentShoulder.put(uuid, typeKey);
                }
            }
        }

        // Detect newly dismounted shoulder pets
        for (var entry : shoulderPetTypes.entrySet()) {
            UUID oldUuid = entry.getKey();
            if (!currentShoulder.containsKey(oldUuid)) {
                // Shoulder pet dismounted - find the new entity
                String typeKey = entry.getValue();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    Entity found = findNearbyPetOfType(player, typeKey);
                    if (found != null && !trackedPetUUIDs.contains(found.getUUID())) {
                        replacePetUuidInIndex((ServerLevel) player.level(), oldUuid, found);
                        trackedPetUUIDs.remove(oldUuid);
                        trackedPetUUIDs.add(found.getUUID());
                        LOGGER.debug("Shoulder pet dismounted: {} -> {}", oldUuid, found.getUUID());
                        break;
                    }
                }
            }
        }

        // Update current shoulder state
        shoulderPetTypes.clear();
        shoulderPetTypes.putAll(currentShoulder);
    }

    private Entity findNearbyPetOfType(ServerPlayer player, String typeKey) {
        ResourceLocation rl = ResourceLocation.tryParse(typeKey);
        if (rl == null) return null;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(rl);
        if (type == null) return null;

        for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(5))) {
            if (entity.getType() == type
                    && entity instanceof OwnableEntity ownable
                    && player.getUUID().equals(ownable.getOwnerUUID())) {
                return entity;
            }
        }
        return null;
    }

    private void replacePetUuidInIndex(ServerLevel level, UUID oldUuid, Entity newEntity) {
        try {
            Path modDir = level.getServer().getWorldPath(LevelResource.ROOT).resolve("trulybestfriends");
            File indexFile = modDir.resolve(PETS_INDEX_FILE).toFile();
            if (!indexFile.exists()) return;

            CompoundTag indexTag = NbtIo.readCompressed(indexFile);
            String typeKey = ForgeRegistries.ENTITY_TYPES.getKey(newEntity.getType()).toString();
            String oldStr = oldUuid.toString();
            String newStr = newEntity.getUUID().toString();

            ListTag uuidList = indexTag.getList(typeKey, Tag.TAG_STRING);
            for (int i = 0; i < uuidList.size(); i++) {
                if (uuidList.getString(i).equals(oldStr)) {
                    uuidList.set(i, StringTag.valueOf(newStr));
                    indexTag.put(typeKey, uuidList);
                    NbtIo.writeCompressed(indexTag, indexFile);

                    // Update cache
                    List<UUID> cachedList = indexCache.get(typeKey);
                    if (cachedList != null) {
                        int idx = cachedList.indexOf(oldUuid);
                        if (idx >= 0) cachedList.set(idx, newEntity.getUUID());
                    }
                    return;
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to replace pet UUID in index: {}", e.getMessage());
        }
    }

    private void loadPlayerPetsData(ServerPlayer player) {
        try {
            ServerLevel level = (ServerLevel) player.level();
            Path modDir = level.getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("trulybestfriends")
                    .resolve(player.getUUID().toString());

            if (Files.exists(modDir)) {
                int[] counts = new int[2]; // [0]=success, [1]=failed
                Files.list(modDir).filter(p -> p.toString().endsWith(".nbt")).forEach(file -> {
                    try {
                        NbtIo.readCompressed(file.toFile());
                        counts[0]++;
                    } catch (IOException e) {
                        counts[1]++;
                        LOGGER.error("Failed to load pet data {}: {}", file.getFileName(), e.getMessage());
                    }
                });
                if (counts[0] + counts[1] > 0) {
                    LOGGER.info("Loaded {} pet data file(s), {} failed", counts[0], counts[1]);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load player pets data: {}", e.getMessage());
        }
    }

    @SubscribeEvent
	public static void onClientSetup(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			if (net.minecraftforge.fml.ModList.get().isLoaded("l2tabs")) {
				try {
					Class.forName("com.whidte.trulybestfriends.tab.L2TabsIntegration")
						.getMethod("register")
						.invoke(null);
					LOGGER.info("L2Tabs detected - inventory tab registered.");
				} catch (Exception e) {
					LOGGER.warn("L2Tabs present but integration failed: {}", e.toString());
				}
			} else {
				LOGGER.info("L2Tabs not installed - use keybinding to open pet screen.");
			}
		});
	}

	private int countOwnerPets(ServerLevel level, UUID ownerUUID) {
		Path ownerDir = level.getServer().getWorldPath(LevelResource.ROOT)
				.resolve("trulybestfriends")
				.resolve(ownerUUID.toString());
		if (!Files.exists(ownerDir)) return 0;
		try {
			return (int) Files.list(ownerDir).filter(p -> p.toString().endsWith(".nbt")).count();
		} catch (IOException e) {
			return 0;
		}
	}

	@SubscribeEvent
	public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(OPEN_TAB_KEY);
	}
}

@Mod.EventBusSubscriber(modid = trulybestfriends.MODID, value = Dist.CLIENT)
class TrulyKeyHandler {
	@SubscribeEvent
	public static void onKeyInput(InputEvent.Key event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		if (trulybestfriends.OPEN_TAB_KEY.consumeClick()) {
			mc.setScreen(new TrulyScreen(Component.translatable("tab.trulybestfriends.pets")));
		}
	}
}
