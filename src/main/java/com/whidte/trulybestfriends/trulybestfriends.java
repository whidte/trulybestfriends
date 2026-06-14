package com.whidte.trulybestfriends;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.whidte.trulybestfriends.network.GlowPetPacket;
import com.whidte.trulybestfriends.tab.TrulyScreen;
import com.whidte.trulybestfriends.tab.TrulyTab;
import dev.xkmc.l2tabs.tabs.core.TabRegistry;
import dev.xkmc.l2tabs.tabs.core.TabToken;
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
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.item.Items;
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
import net.minecraftforge.fml.loading.FMLEnvironment;
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

    public static TabToken<TrulyTab> TRULY_TAB;

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
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
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        CHANNEL.registerMessage(0, GlowPetPacket.class, GlowPetPacket::encode, GlowPetPacket::decode, GlowPetPacket::handle);
    }

    @SubscribeEvent
    public void onAnimalTamed(AnimalTameEvent event) {
        Entity animal = event.getAnimal();
        if (!animal.level().isClientSide() && animal instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null) {
            savePetData(ownable.getOwnerUUID(), animal, (ServerLevel) animal.level());
            updatePetIndex(animal);
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
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

            CompoundTag nbt = new CompoundTag();
            pet.saveWithoutId(nbt);
            nbt.putString("OwnerUUID", ownerUUID.toString());
            nbt.putString("EntityType", ForgeRegistries.ENTITY_TYPES.getKey(pet.getType()).toString());

            File nbtFile = ownerDir.resolve(pet.getUUID() + ".nbt").toFile();
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
                        updatePetIndex(entity);
                    }
                    savePetData(ownable.getOwnerUUID(), entity, level);
                }
            }
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
			TRULY_TAB = TabRegistry.registerTab(500, TrulyTab::new,
					() -> Items.LEAD,
					Component.translatable("tab.trulybestfriends.pets"));
		});
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
}/*@Mod(trulybestfriends.MODID)
public class trulybestfriends
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "trulybestfriends";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "examplemod" namespace
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "examplemod" namespace
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "examplemod" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "examplemod:example_block", combining the namespace and path
    public static final RegistryObject<Block> EXAMPLE_BLOCK = BLOCKS.register("example_block", () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE)));
    // Creates a new BlockItem with the id "examplemod:example_block", combining the namespace and path
    public static final RegistryObject<Item> EXAMPLE_BLOCK_ITEM = ITEMS.register("example_block", () -> new BlockItem(EXAMPLE_BLOCK.get(), new Item.Properties()));

    // Creates a new food item with the id "examplemod:example_id", nutrition 1 and saturation 2
    public static final RegistryObject<Item> EXAMPLE_ITEM = ITEMS.register("example_item", () -> new Item(new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEat().nutrition(1).saturationMod(2f).build())));

    // Creates a creative tab with the id "examplemod:example_tab" for the example item, that is placed after the combat tab
    public static final RegistryObject<CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(EXAMPLE_ITEM.get()); // Add the example item to the tab. For your own tabs, this method is preferred over the event
            }).build());

    public trulybestfriends(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.logDirtBlock)
            LOGGER.info("DIRT BLOCK >> {}", ForgeRegistries.BLOCKS.getKey(Blocks.DIRT));

        LOGGER.info(Config.magicNumberIntroduction + Config.magicNumber);

        Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item.toString()));
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS)
            event.accept(EXAMPLE_BLOCK_ITEM);
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }
}
    */
