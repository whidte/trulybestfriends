package com.whidte.trulybestfriends;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Locale;

public class Config
{
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue PERFORMANCE_MODE = BUILDER
            .comment("If true, disables automatic pet registration from tame events, entity joins, nearby scans, and full scans.",
                    "Pets must be registered with /tbf load or the manual registration item.")
            .define("performanceMode", false);

    public static final ModConfigSpec.IntValue PERFORMANCE_MODE_SYNC_INTERVAL_TICKS = BUILDER
            .comment("In performance mode, interval in ticks for updating loaded pets by their already tracked UUIDs.")
            .defineInRange("performanceModeSyncIntervalTicks", 5, 1, 1200);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> OWNER_NBT_FIELDS = BUILDER
            .comment("NBT paths used to find an owner UUID on living entities that do not implement OwnableEntity.",
                    "Use dots to traverse nested compounds, for example ForgeData.Owner. Paths are checked in order.",
                    "The final field may contain either a UUID tag or a UUID string. Path segments are case-sensitive.")
            .defineListAllowEmpty("ownerNbtFields", java.util.Arrays.asList(
                    "Owner",
                    "OwnerUUID"
            ), s -> s instanceof String path && OwnerNbtResolver.isValidPath(path));

    public static final ModConfigSpec.IntValue SYNC_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks for full fallback scan of all loaded owned entities and caching their latest pet data.",
                    "Set to 0 to disable the full scan.")
            .defineInRange("syncIntervalTicks", 103, 0, 1200);

    public static final ModConfigSpec.IntValue LOCAL_SYNC_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks for scanning nearby entities around players who completed the Truly Best Friends advancement")
            .defineInRange("localSyncIntervalTicks", 5, 1, 100);

    public static final ModConfigSpec.IntValue SAVE_PET_DATA_COOLDOWN_TICKS = BUILDER
            .comment("Interval in ticks for flushing cached pet data to disk. Player logout and server stop always flush immediately.")
            .defineInRange("savePetDataCooldownTicks", 100, 1, 1200);

    public static final ModConfigSpec.DoubleValue RECALL_RANGE = BUILDER
            .comment("Maximum distance (blocks) for recalling a pet back into storage. Set to -1 for unlimited range")
            .defineInRange("recallRange", 16.0, -1.0, 64.0);

    public static final ModConfigSpec.IntValue RECALL_COOLDOWN_MS = BUILDER
            .comment("Cooldown in milliseconds between recall/summon actions (min 250ms = 5 ticks to ensure entity cleanup completes)")
            .defineInRange("recallCooldownMs", 3000, 250, 30000);

    public static final ModConfigSpec.IntValue MAX_PETS = BUILDER
            .comment("Maximum number of pets a player can have tracked at once (1-512, default 64)")
            .defineInRange("maxPets", 64, 1, 512);

    public static final ModConfigSpec.BooleanValue DELETE_STORED_PETS_DIRECTLY = BUILDER
            .comment("If true, deleting a recalled or dead pet from tracking permanently removes its stored data",
                    "without releasing the entity into the world. Default false preserves the existing release behavior.")
            .define("deleteStoredPetsDirectly", false);

    public static final ModConfigSpec.IntValue AREA_RECALL_DEFAULT_RANGE = BUILDER
            .comment("Default range (blocks) for area recall when holding Shift. Adjustable with scroll wheel (1-16).")
            .defineInRange("areaRecallDefaultRange", 8, 1, 16);

    public static final ModConfigSpec.IntValue MAX_PENDING_SUMMONS = BUILDER
            .comment("Max simultaneous pending summons per player for pets in unloaded chunks. NOTE: this value will also serve as the upper limit on the number of pets summonable at once in the future formation/party mode. Effective pending cap = this value + 2 buffer (1-32, default 6).")
            .defineInRange("maxPendingSummons", 6, 1, 32);

    public static final ModConfigSpec.ConfigValue<String> REVIVE_ITEM = BUILDER
            .comment("Item ID required to revive a dead pet (e.g. \"minecraft:totem_of_undying\").")
            .define("reviveItem", "minecraft:totem_of_undying");

    public static final ModConfigSpec.ConfigValue<String> MANUAL_REGISTER_ITEM = BUILDER
            .comment("Item used to manually register a pet by right-clicking the entity.",
                    "The registration uses the same checks and behavior as /tbf load.")
            .define("manualRegisterItem", "minecraft:feather",
                    value -> value instanceof String && ResourceLocation.tryParse((String) value) != null);

    public static final ModConfigSpec.BooleanValue CONSUME_MANUAL_REGISTER_ITEM = BUILDER
            .comment("If true, a successful manual pet registration consumes the configured number of items.",
                    "Items are not consumed when registration fails or when the player is in creative mode.")
            .define("consumeManualRegisterItem", false);

    public static final ModConfigSpec.IntValue MANUAL_REGISTER_ITEM_CONSUME_COUNT = BUILDER
            .comment("Number of held manual registration items consumed after a successful registration.")
            .defineInRange("manualRegisterItemConsumeCount", 1, 1, 64);

    public static final ModConfigSpec.IntValue REVIVE_ITEM_COUNT = BUILDER
            .comment("Number of revive items required to revive a dead pet.")
            .defineInRange("reviveItemCount", 1, 1, 64);

    public static final ModConfigSpec.IntValue REVIVE_COOLDOWN_SECONDS = BUILDER
            .comment("Cooldown in seconds after reviving a pet before another revive can be used.")
            .defineInRange("reviveCooldownSeconds", 120, 0, 86400);

    public static final ModConfigSpec.IntValue HEAL_HUNGER_COST = BUILDER
            .comment("Food points consumed when starting or extending pet healing. Creative players pay no cost.")
            .defineInRange("healHungerCost", 3, 0, 20);

    public static final ModConfigSpec.IntValue ADVANCED_HEAL_HUNGER_COST = BUILDER
            .comment("Food points consumed by Shift-click advanced pet healing. Creative players pay no cost.")
            .defineInRange("advancedHealHungerCost", 9, 0, 20);

    public static final ModConfigSpec.IntValue HEAL_PULSE_INTERVAL_TICKS = BUILDER
            .comment("Ticks between pet healing pulses.")
            .defineInRange("healPulseIntervalTicks", 50, 1, 1200);

    public static final ModConfigSpec.IntValue ADVANCED_HEAL_PULSE_INTERVAL_TICKS = BUILDER
            .comment("Ticks between advanced pet healing pulses.")
            .defineInRange("advancedHealPulseIntervalTicks", 25, 1, 1200);

    public static final ModConfigSpec.IntValue HEAL_DURATION_PER_CLICK_TICKS = BUILDER
            .comment("Healing duration added by one click.")
            .defineInRange("healDurationPerClickTicks", 300, 1, 72000);

    public static final ModConfigSpec.IntValue HEAL_MAX_DURATION_TICKS = BUILDER
            .comment("Maximum remaining healing duration. A click that would exceed this value is rejected.")
            .defineInRange("healMaxDurationTicks", 1200, 1, 72000);

    public static final ModConfigSpec.DoubleValue HEAL_FLAT_AMOUNT = BUILDER
            .comment("Flat health restored by each pulse.")
            .defineInRange("healFlatAmount", 1.0, 0.0, 1000000.0);

    public static final ModConfigSpec.DoubleValue HEAL_MAX_HEALTH_FRACTION = BUILDER
            .comment("Fraction of the pet's current maximum health restored by each pulse (0.01 = 1%).")
            .defineInRange("healMaxHealthFraction", 0.01, 0.0, 1.0);

    public static final ModConfigSpec.BooleanValue ENABLE_LOGIN_LOAD_DIAGNOSTICS = BUILDER
            .comment("If true, validates all pet .nbt files on player login and logs counts. Debug only.")
            .define("enableLoginLoadDiagnostics", false);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> AUTO_REGISTER_BLACKLIST = BUILDER
            .comment("Entity types that should not be automatically registered as pets even if they are OwnableEntity.",
                    "Format: entity id such as \"minecraft:wolf\", or namespace wildcard such as \"some_mod:*\".",
                    "This only blocks future automatic registration and does not remove already tracked pets.")
            .defineListAllowEmpty("autoRegisterBlacklist", java.util.Arrays.asList(
                    "irons_spellbooks:spectral_steed",
                    "irons_spellbooks:summoned_vex",
                    "irons_spellbooks:summoned_zombie",
                    "irons_spellbooks:summoned_skeleton",
                    "irons_spellbooks:summoned_polar_bear",
                    "irons_spellbooks:summoned_sword",
                    "irons_spellbooks:summoned_claymore",
                    "irons_spellbooks:summoned_rapier",
                    "irons_spellbooks:spectral_hammer",
                    "irons_spellbooks:wisp",
                    "touhou_little_maid:broom",
                    "touhou_little_maid:chair"
            ), s -> s instanceof String && (((String) s).contains(":") || ((String) s).endsWith(":*")));

    public static final ModConfigSpec.ConfigValue<List<? extends String>> NO_REVIVE_WHITELIST = BUILDER
            .comment("Entity types that keep their death drops and cannot be revived via this mod.",
                    "Format: entity id, e.g. \"minecraft:villager\". Pets of these types will still be tracked,",
                    "but on death they drop loot normally and the revive button is disabled for them.")
            .defineListAllowEmpty("noReviveWhitelist", java.util.Arrays.asList(
                    "modulargolems:metal_golem",
                    "modulargolems:humanoid_golem",
                    "modulargolems:dog_golem"
            ), s -> s instanceof String && ((String) s).contains(":"));

    public static final ModConfigSpec.ConfigValue<List<? extends String>> CLEAR_ON_DEATH_WHITELIST = BUILDER
            .comment("Entity types that, on death, behave like noReviveWhitelist entities AND additionally",
                    "have their stored NBT data and in-memory cache completely cleared.",
                    "Use this for disposable or summon-only entities that should leave no trace after death.",
                    "Format: entity id, e.g. \"minecraft:horse\".")
            .defineListAllowEmpty("clearOnDeathWhitelist", java.util.Arrays.asList(
                    "touhou_little_maid:maid",
                    "goety:vex_servant",
                    "goety:wither_skeleton_servant",
                    "goety:border_wraith_servant",
                    "goety:haunted_armor_servant",
                    "goety:blackguard_servant",
                    "goety:vanguard_servant",
                    "goety:doppelganger",
                    "goety:guardian_servant",
                    "goety:stone_ministrosity",
                    "goety:redstone_ministrosity",
                    "goety:ice_golem",
                    "goety:blaze_servant",
                    "goety:inferno",
                    "goety:mini_ghast",
                    "goety:ghast_servant",
                    "goety:malghast",
                    "goety:blastling_servant",
                    "goety:snareling_servant",
                    "goety:watchling_servant",
                    "goety:haunted_skull",
                    "goety:phantom_servant",
                    "goety:reaper_servant",
                    "goety:wraith_servant",
                    "goety:muck_wraith_servant",
                    "goety:zombie_servant",
                    "goety:zombie_villager_servant",
                    "goety:husk_servant",
                    "goety:drowned_servant",
                    "goety:frozen_zombie_servant",
                    "goety:jungle_zombie_servant",
                    "goety:frayed_servant",
                    "goety:zpiglin_servant",
                    "goety:zpiglin_brute_servant",
                    "goety:zombie_vindicator",
                    "goety:skeleton_servant",
                    "goety:stray_servant",
                    "goety:mossy_skeleton_servant",
                    "goety:sunken_skeleton_servant",
                    "goety:rattled_servant",
                    "goety:skeleton_pillager",
                    "goety:carrion_fly",
                    "goety:carrion_maggot",
                    "goety:black_wolf",
                    "goety:skeleton_wolf",
                    "goety:winter_wolf",
                    "goety:stormhound",
                    "goety:hellhound",
                    "goety:twilight_goat",
                    "goety:snapper",
                    "goety:bear_servant",
                    "goety:polar_bear_servant",
                    "goety:hoglin_servant",
                    "goety:gnasher",
                    "goety:leapleaf",
                    "goety:slime_servant",
                    "goety:magma_cube_servant",
                    "goety:crypt_slime_servant",
                    "goety:tropical_slime_servant",
                    "goety:whisperer",
                    "goety:wavewhisperer"
            ), s -> s instanceof String && ((String) s).contains(":"));


    static final ModConfigSpec SPEC = BUILDER.build();

    public static final java.util.List<String> ownerNbtFields = new java.util.ArrayList<>(java.util.Arrays.asList(
            "Owner", "OwnerUUID"));
    static volatile java.util.List<String[]> ownerNbtPaths = OwnerNbtResolver.parsePaths(ownerNbtFields);
    public static boolean performanceMode;
    public static int performanceModeSyncIntervalTicks;
    public static int syncIntervalTicks;
    public static int localSyncIntervalTicks;
    public static int savePetDataCooldownTicks;
    public static double recallRange;
    public static int recallCooldownMs;
    public static int maxPets;
    public static boolean deleteStoredPetsDirectly;
    public static int areaRecallDefaultRange;
    public static int maxPendingSummons;
    public static String reviveItem;
    public static String manualRegisterItem;
    public static boolean consumeManualRegisterItem;
    public static int manualRegisterItemConsumeCount;
    public static int reviveItemCount;
    public static int reviveCooldownSeconds;
    public static int healHungerCost;
    public static int advancedHealHungerCost;
    public static int healPulseIntervalTicks;
    public static int advancedHealPulseIntervalTicks;
    public static int healDurationPerClickTicks;
    public static int healMaxDurationTicks;
    public static double healFlatAmount;
    public static double healMaxHealthFraction;
    public static boolean enableLoginLoadDiagnostics;
    public static java.util.Set<String> autoRegisterBlacklist = new java.util.HashSet<>();
    /** Entity type ids that keep death drops and cannot be revived. */
    public static java.util.Set<String> noReviveWhitelist = new java.util.HashSet<>();
    /** Entity type ids that, on death, additionally clear NBT data and in-memory cache. Also treated as no-revive. */
    public static java.util.Set<String> clearOnDeathWhitelist = new java.util.HashSet<>();

    public enum EntityTypeList {
        AUTO_REGISTER_BLACKLIST,
        NO_REVIVE_WHITELIST,
        CLEAR_ON_DEATH_WHITELIST
    }

    /** Adds an entity type to the selected runtime list and persists the common config. */
    public static synchronized boolean addEntityType(EntityTypeList list, String entityTypeId) {
        ModConfigSpec.ConfigValue<List<? extends String>> configValue = switch (list) {
            case AUTO_REGISTER_BLACKLIST -> AUTO_REGISTER_BLACKLIST;
            case NO_REVIVE_WHITELIST -> NO_REVIVE_WHITELIST;
            case CLEAR_ON_DEATH_WHITELIST -> CLEAR_ON_DEATH_WHITELIST;
        };
        java.util.Set<String> runtimeValues = switch (list) {
            case AUTO_REGISTER_BLACKLIST -> autoRegisterBlacklist;
            case NO_REVIVE_WHITELIST -> noReviveWhitelist;
            case CLEAR_ON_DEATH_WHITELIST -> clearOnDeathWhitelist;
        };

        List<String> updated = new java.util.ArrayList<>(configValue.get());
        if (updated.contains(entityTypeId)) return false;
        updated.add(entityTypeId);
        configValue.set(updated);
        configValue.save();
        runtimeValues.add(entityTypeId);
        return true;
    }

    static void onLoad(final ModConfigEvent event)
    {
        ownerNbtFields.clear();
        ownerNbtFields.addAll(OWNER_NBT_FIELDS.get());
        ownerNbtPaths = OwnerNbtResolver.parsePaths(ownerNbtFields);

        performanceMode = PERFORMANCE_MODE.get();
        performanceModeSyncIntervalTicks = PERFORMANCE_MODE_SYNC_INTERVAL_TICKS.get();
        syncIntervalTicks = SYNC_INTERVAL_TICKS.get();
        localSyncIntervalTicks = LOCAL_SYNC_INTERVAL_TICKS.get();
        savePetDataCooldownTicks = SAVE_PET_DATA_COOLDOWN_TICKS.get();
        recallRange = RECALL_RANGE.get();
        recallCooldownMs = RECALL_COOLDOWN_MS.get();
        maxPets = MAX_PETS.get();
        deleteStoredPetsDirectly = DELETE_STORED_PETS_DIRECTLY.get();
        areaRecallDefaultRange = AREA_RECALL_DEFAULT_RANGE.get();
        maxPendingSummons = MAX_PENDING_SUMMONS.get();
        reviveItem = REVIVE_ITEM.get();
        manualRegisterItem = MANUAL_REGISTER_ITEM.get();
        consumeManualRegisterItem = CONSUME_MANUAL_REGISTER_ITEM.get();
        manualRegisterItemConsumeCount = MANUAL_REGISTER_ITEM_CONSUME_COUNT.get();
        reviveItemCount = REVIVE_ITEM_COUNT.get();
        reviveCooldownSeconds = REVIVE_COOLDOWN_SECONDS.get();
        healHungerCost = HEAL_HUNGER_COST.get();
        advancedHealHungerCost = ADVANCED_HEAL_HUNGER_COST.get();
        healPulseIntervalTicks = HEAL_PULSE_INTERVAL_TICKS.get();
        advancedHealPulseIntervalTicks = ADVANCED_HEAL_PULSE_INTERVAL_TICKS.get();
        healDurationPerClickTicks = HEAL_DURATION_PER_CLICK_TICKS.get();
        healMaxDurationTicks = HEAL_MAX_DURATION_TICKS.get();
        healFlatAmount = HEAL_FLAT_AMOUNT.get();
        healMaxHealthFraction = HEAL_MAX_HEALTH_FRACTION.get();
        enableLoginLoadDiagnostics = ENABLE_LOGIN_LOAD_DIAGNOSTICS.get();

        autoRegisterBlacklist.clear();
        autoRegisterBlacklist.addAll(AUTO_REGISTER_BLACKLIST.get());

        noReviveWhitelist.clear();
        noReviveWhitelist.addAll(NO_REVIVE_WHITELIST.get());

        clearOnDeathWhitelist.clear();
        clearOnDeathWhitelist.addAll(CLEAR_ON_DEATH_WHITELIST.get());

    }

    /**
     * Get the display name for a dimension in the currently selected language.
     * Falls back to the raw dimension id when no language entry exists.
     */
    @OnlyIn(Dist.CLIENT)
    public static String getDimensionDisplayName(String dimKey) {
        String translationKey = getDimensionTranslationKey(dimKey);
        if (translationKey != null && I18n.exists(translationKey)) {
            return I18n.get(translationKey);
        }
        return formatDimensionId(dimKey);
    }

    private static String formatDimensionId(String dimKey) {
        if (dimKey == null || dimKey.isEmpty()) return null;

        String normalizedDimKey = dimKey.toLowerCase(Locale.ROOT);
        ResourceLocation id = ResourceLocation.tryParse(normalizedDimKey);
        String path = id != null ? id.getPath() : normalizedDimKey;
        String[] words = path.replace('/', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.isEmpty() ? dimKey : result.toString();
    }

    static String getDimensionTranslationKey(String dimKey) {
        if (dimKey == null || dimKey.isEmpty()) return null;

        String normalizedDimKey = dimKey.toLowerCase(Locale.ROOT);
        return switch (normalizedDimKey) {
            case "minecraft:overworld" -> "dimension.minecraft.overworld";
            case "minecraft:the_nether" -> "dimension.minecraft.the_nether";
            case "minecraft:the_end" -> "dimension.minecraft.the_end";
            default -> {
                ResourceLocation id = ResourceLocation.tryParse(normalizedDimKey);
                yield id != null ? "dimension." + id.getNamespace() + "." + id.getPath().replace('/', '.') : null;
            }
        };
    }

    public static boolean isAutoRegisterBlacklisted(String entityTypeKey) {
        if (entityTypeKey == null || entityTypeKey.isEmpty()) return false;
        if (autoRegisterBlacklist.contains(entityTypeKey)) return true;
        ResourceLocation id = ResourceLocation.tryParse(entityTypeKey);
        return id != null && autoRegisterBlacklist.contains(id.getNamespace() + ":*");
    }

    /**
     * Check whether an entity type id is in the no-revive whitelist.
     * Such entities keep their death drops and cannot be revived via this mod.
     * Entities in clearOnDeathWhitelist are also treated as no-revive.
     */
    public static boolean isNoReviveEntity(String entityTypeKey) {
        return entityTypeKey != null && (noReviveWhitelist.contains(entityTypeKey) || clearOnDeathWhitelist.contains(entityTypeKey));
    }

    /**
     * Check whether an entity type id is in the clear-on-death whitelist.
     * Such entities behave like no-revive AND have their NBT data + cache cleared on death.
     */
    public static boolean isClearOnDeathEntity(String entityTypeKey) {
        return entityTypeKey != null && clearOnDeathWhitelist.contains(entityTypeKey);
    }
}
