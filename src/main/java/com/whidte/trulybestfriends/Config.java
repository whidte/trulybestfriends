package com.whidte.trulybestfriends;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * JSON-based replacement for the Forge {@code ForgeConfigSpec} configuration.
 * Values are read once at startup from {@code config/trulybestfriends.json}
 * and kept in static fields. Runtime list additions (via {@code /tbf}
 * commands) write the whole file back to disk.
 */
public class Config
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("trulybestfriends.json");

    public static final boolean PERFORMANCE_MODE_DEFAULT = false;
    public static final int PERFORMANCE_MODE_SYNC_INTERVAL_TICKS_DEFAULT = 5;
    public static final int BOSS_FIGHT_PET_LIMIT_DEFAULT = -1;
    public static final java.util.List<String> OWNER_NBT_FIELDS_DEFAULT = java.util.Arrays.asList(
            "Owner",
            "OwnerUUID",
            "ForgeCaps.mob_controller:mob_control.ControllerUUID"
    );
    public static final int SYNC_INTERVAL_TICKS_DEFAULT = 103;
    public static final int LOCAL_SYNC_INTERVAL_TICKS_DEFAULT = 5;
    public static final int SAVE_PET_DATA_COOLDOWN_TICKS_DEFAULT = 100;
    public static final double RECALL_RANGE_DEFAULT = 16.0;
    public static final int RECALL_COOLDOWN_MS_DEFAULT = 3000;
    public static final int MAX_PETS_DEFAULT = 64;
    public static final boolean DELETE_STORED_PETS_DIRECTLY_DEFAULT = false;
    public static final int AREA_RECALL_DEFAULT_RANGE_DEFAULT = 8;
    public static final int MAX_PENDING_SUMMONS_DEFAULT = 6;
    public static final int SUMMON_BOTTLE_RIGHT_OFFSET_DEFAULT = 8;
    public static final int SUMMON_BOTTLE_VERTICAL_OFFSET_DEFAULT = 0;
    public static final String REVIVE_ITEM_DEFAULT = "minecraft:totem_of_undying";
    public static final String MANUAL_REGISTER_ITEM_DEFAULT = "minecraft:feather";
    public static final boolean CONSUME_MANUAL_REGISTER_ITEM_DEFAULT = false;
    public static final int MANUAL_REGISTER_ITEM_CONSUME_COUNT_DEFAULT = 1;
    public static final int REVIVE_ITEM_COUNT_DEFAULT = 1;
    public static final int REVIVE_COOLDOWN_SECONDS_DEFAULT = 120;
    public static final int HEAL_HUNGER_COST_DEFAULT = 3;
    public static final int ADVANCED_HEAL_HUNGER_COST_DEFAULT = 9;
    public static final int HEAL_PULSE_INTERVAL_TICKS_DEFAULT = 50;
    public static final int ADVANCED_HEAL_PULSE_INTERVAL_TICKS_DEFAULT = 25;
    public static final int HEAL_DURATION_PER_CLICK_TICKS_DEFAULT = 300;
    public static final int HEAL_MAX_DURATION_TICKS_DEFAULT = 1200;
    public static final double HEAL_FLAT_AMOUNT_DEFAULT = 1.0;
    public static final double HEAL_MAX_HEALTH_FRACTION_DEFAULT = 0.01;
    public static final boolean ENABLE_LOGIN_LOAD_DIAGNOSTICS_DEFAULT = false;

    public static final java.util.List<String> AUTO_REGISTER_BLACKLIST_DEFAULT = java.util.Arrays.asList(
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
    );

    public static final java.util.List<String> NO_REVIVE_WHITELIST_DEFAULT = java.util.Arrays.asList(
            "modulargolems:metal_golem",
            "modulargolems:humanoid_golem",
            "modulargolems:dog_golem"
    );

    public static final java.util.List<String> CLEAR_ON_DEATH_WHITELIST_DEFAULT = java.util.Arrays.asList(
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
    );

    public static final java.util.List<String> ownerNbtFields = new java.util.ArrayList<>(java.util.Arrays.asList(
            "Owner", "OwnerUUID"));
    static volatile java.util.List<String[]> ownerNbtPaths = OwnerNbtResolver.parsePaths(ownerNbtFields);
    public static boolean performanceMode;
    public static int performanceModeSyncIntervalTicks;
    public static int bossFightPetLimit;
    public static int syncIntervalTicks;
    public static int localSyncIntervalTicks;
    public static int savePetDataCooldownTicks;
    public static double recallRange;
    public static int recallCooldownMs;
    public static int maxPets;
    public static boolean deleteStoredPetsDirectly;
    public static int areaRecallDefaultRange;
    public static int maxPendingSummons;
    public static int summonBottleRightOffset;
    public static int summonBottleVerticalOffset;
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

    /** Whether reviving a dead pet requires the configured item. */
    public static boolean isReviveItemRequired() {
        return reviveItem == null || !reviveItem.isBlank();
    }

    public enum EntityTypeList {
        AUTO_REGISTER_BLACKLIST,
        NO_REVIVE_WHITELIST,
        CLEAR_ON_DEATH_WHITELIST
    }

    private Config() {}

    /** Loads the JSON config file (or defaults) into the static fields. Idempotent. */
    public static void load() {
        JsonObject root = readFile();
        ownerNbtFields.clear();
        ownerNbtFields.addAll(stringList(root, "ownerNbtFields", OWNER_NBT_FIELDS_DEFAULT));
        ownerNbtPaths = OwnerNbtResolver.parsePaths(ownerNbtFields);

        performanceMode = bool(root, "performanceMode", PERFORMANCE_MODE_DEFAULT);
        performanceModeSyncIntervalTicks = clampedInt(root, "performanceModeSyncIntervalTicks",
                PERFORMANCE_MODE_SYNC_INTERVAL_TICKS_DEFAULT, 1, 1200);
        bossFightPetLimit = clampedInt(root, "bossFightPetLimit", BOSS_FIGHT_PET_LIMIT_DEFAULT, -1, 512);
        syncIntervalTicks = clampedInt(root, "syncIntervalTicks", SYNC_INTERVAL_TICKS_DEFAULT, 0, 1200);
        localSyncIntervalTicks = clampedInt(root, "localSyncIntervalTicks", LOCAL_SYNC_INTERVAL_TICKS_DEFAULT, 1, 100);
        savePetDataCooldownTicks = clampedInt(root, "savePetDataCooldownTicks",
                SAVE_PET_DATA_COOLDOWN_TICKS_DEFAULT, 1, 1200);
        recallRange = clampedDouble(root, "recallRange", RECALL_RANGE_DEFAULT, -1.0, 64.0);
        recallCooldownMs = clampedInt(root, "recallCooldownMs", RECALL_COOLDOWN_MS_DEFAULT, 250, 30000);
        maxPets = clampedInt(root, "maxPets", MAX_PETS_DEFAULT, 1, 512);
        deleteStoredPetsDirectly = bool(root, "deleteStoredPetsDirectly", DELETE_STORED_PETS_DIRECTLY_DEFAULT);
        areaRecallDefaultRange = clampedInt(root, "areaRecallDefaultRange", AREA_RECALL_DEFAULT_RANGE_DEFAULT, 1, 16);
        maxPendingSummons = clampedInt(root, "maxPendingSummons", MAX_PENDING_SUMMONS_DEFAULT, 1, 8);
        summonBottleRightOffset = clampedInt(root, "summonBottleRightOffset",
                SUMMON_BOTTLE_RIGHT_OFFSET_DEFAULT, 0, 4096);
        summonBottleVerticalOffset = clampedInt(root, "summonBottleVerticalOffset",
                SUMMON_BOTTLE_VERTICAL_OFFSET_DEFAULT, -4096, 4096);
        reviveItem = string(root, "reviveItem", REVIVE_ITEM_DEFAULT);
        manualRegisterItem = validItemId(root, "manualRegisterItem", MANUAL_REGISTER_ITEM_DEFAULT);
        consumeManualRegisterItem = bool(root, "consumeManualRegisterItem", CONSUME_MANUAL_REGISTER_ITEM_DEFAULT);
        manualRegisterItemConsumeCount = clampedInt(root, "manualRegisterItemConsumeCount",
                MANUAL_REGISTER_ITEM_CONSUME_COUNT_DEFAULT, 1, 64);
        reviveItemCount = clampedInt(root, "reviveItemCount", REVIVE_ITEM_COUNT_DEFAULT, 1, 64);
        reviveCooldownSeconds = clampedInt(root, "reviveCooldownSeconds", REVIVE_COOLDOWN_SECONDS_DEFAULT, 0, 86400);
        healHungerCost = clampedInt(root, "healHungerCost", HEAL_HUNGER_COST_DEFAULT, 0, 20);
        advancedHealHungerCost = clampedInt(root, "advancedHealHungerCost", ADVANCED_HEAL_HUNGER_COST_DEFAULT, 0, 20);
        healPulseIntervalTicks = clampedInt(root, "healPulseIntervalTicks", HEAL_PULSE_INTERVAL_TICKS_DEFAULT, 1, 1200);
        advancedHealPulseIntervalTicks = clampedInt(root, "advancedHealPulseIntervalTicks",
                ADVANCED_HEAL_PULSE_INTERVAL_TICKS_DEFAULT, 1, 1200);
        healDurationPerClickTicks = clampedInt(root, "healDurationPerClickTicks",
                HEAL_DURATION_PER_CLICK_TICKS_DEFAULT, 1, 72000);
        healMaxDurationTicks = clampedInt(root, "healMaxDurationTicks", HEAL_MAX_DURATION_TICKS_DEFAULT, 1, 72000);
        healFlatAmount = clampedDouble(root, "healFlatAmount", HEAL_FLAT_AMOUNT_DEFAULT, 0.0, 1000000.0);
        healMaxHealthFraction = clampedDouble(root, "healMaxHealthFraction",
                HEAL_MAX_HEALTH_FRACTION_DEFAULT, 0.0, 1.0);
        enableLoginLoadDiagnostics = bool(root, "enableLoginLoadDiagnostics", ENABLE_LOGIN_LOAD_DIAGNOSTICS_DEFAULT);

        autoRegisterBlacklist.clear();
        autoRegisterBlacklist.addAll(stringList(root, "autoRegisterBlacklist", AUTO_REGISTER_BLACKLIST_DEFAULT));

        noReviveWhitelist.clear();
        noReviveWhitelist.addAll(stringList(root, "noReviveWhitelist", NO_REVIVE_WHITELIST_DEFAULT));

        clearOnDeathWhitelist.clear();
        clearOnDeathWhitelist.addAll(stringList(root, "clearOnDeathWhitelist", CLEAR_ON_DEATH_WHITELIST_DEFAULT));
    }

    /** Adds an entity type to the selected runtime list and persists the JSON config. */
    public static synchronized boolean addEntityType(EntityTypeList list, String entityTypeId) {
        String key = switch (list) {
            case AUTO_REGISTER_BLACKLIST -> "autoRegisterBlacklist";
            case NO_REVIVE_WHITELIST -> "noReviveWhitelist";
            case CLEAR_ON_DEATH_WHITELIST -> "clearOnDeathWhitelist";
        };
        java.util.Set<String> runtimeValues = switch (list) {
            case AUTO_REGISTER_BLACKLIST -> autoRegisterBlacklist;
            case NO_REVIVE_WHITELIST -> noReviveWhitelist;
            case CLEAR_ON_DEATH_WHITELIST -> clearOnDeathWhitelist;
        };

        JsonObject root = readFile();
        JsonArray updated = root.has(key) && root.get(key).isJsonArray()
                ? root.get(key).getAsJsonArray()
                : new JsonArray();
        for (JsonElement element : updated) {
            if (element.isJsonPrimitive() && element.getAsString().equals(entityTypeId)) return false;
        }
        updated.add(entityTypeId);
        root.add(key, updated);
        writeFile(root);
        runtimeValues.add(entityTypeId);
        return true;
    }

    private static JsonObject readFile() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                JsonElement element = JsonParser.parseString(Files.readString(CONFIG_FILE));
                if (element.isJsonObject()) return element.getAsJsonObject();
            }
        } catch (IOException | RuntimeException e) {
            trulybestfriends.LOGGER.error("Failed to read config file {}: {}", CONFIG_FILE, e.getMessage());
        }
        return new JsonObject();
    }

    private static void writeFile(JsonObject root) {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, GSON.toJson(root));
        } catch (IOException e) {
            trulybestfriends.LOGGER.error("Failed to write config file {}: {}", CONFIG_FILE, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            try {
                return root.get(key).getAsBoolean();
            } catch (RuntimeException ignored) {}
        }
        return fallback;
    }

    private static int clampedInt(JsonObject root, String key, int fallback, int min, int max) {
        int value = fallback;
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            try {
                value = root.get(key).getAsInt();
            } catch (RuntimeException ignored) {}
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double clampedDouble(JsonObject root, String key, double fallback, double min, double max) {
        double value = fallback;
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            try {
                value = root.get(key).getAsDouble();
            } catch (RuntimeException ignored) {}
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String string(JsonObject root, String key, String fallback) {
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            return root.get(key).getAsString();
        }
        return fallback;
    }

    private static String validItemId(JsonObject root, String key, String fallback) {
        String value = string(root, key, fallback);
        return ResourceLocation.tryParse(value) != null ? value : fallback;
    }

    private static java.util.List<String> stringList(JsonObject root, String key,
                                                     java.util.List<String> fallback) {
        if (!root.has(key) || !root.get(key).isJsonArray()) return new java.util.ArrayList<>(fallback);
        java.util.List<String> result = new java.util.ArrayList<>();
        for (JsonElement element : root.get(key).getAsJsonArray()) {
            if (element.isJsonPrimitive()) result.add(element.getAsString());
        }
        return result;
    }

    /**
     * Get the display name for a dimension in the currently selected language.
     * Falls back to the raw dimension id when no language entry exists.
     */
    @Environment(EnvType.CLIENT)
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
