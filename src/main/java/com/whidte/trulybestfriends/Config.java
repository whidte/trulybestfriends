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

    public static final ModConfigSpec.ConfigValue<List<? extends String>> OWNER_NBT_FIELDS = BUILDER
            .comment("Top-level NBT field names used to find an owner UUID on living entities that do not implement OwnableEntity.",
                    "Fields are checked in order and may contain either a UUID tag or a UUID string. Names are case-sensitive.")
            .defineListAllowEmpty("ownerNbtFields", java.util.Arrays.asList(
                    "Owner",
                    "OwnerUUID"
            ), s -> s instanceof String && !((String) s).isEmpty());

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
            .comment("Maximum number of pets a player can have tracked at once (1-128, default 64)")
            .defineInRange("maxPets", 64, 1, 128);

    public static final ModConfigSpec.IntValue AREA_RECALL_DEFAULT_RANGE = BUILDER
            .comment("Default range (blocks) for area recall when holding Shift. Adjustable with scroll wheel (1-16).")
            .defineInRange("areaRecallDefaultRange", 8, 1, 16);

    public static final ModConfigSpec.IntValue MAX_PENDING_SUMMONS = BUILDER
            .comment("Max simultaneous pending summons per player for pets in unloaded chunks. NOTE: this value will also serve as the upper limit on the number of pets summonable at once in the future formation/party mode. Effective pending cap = this value + 2 buffer (1-32, default 6).")
            .defineInRange("maxPendingSummons", 6, 1, 32);

    public static final ModConfigSpec.ConfigValue<String> REVIVE_ITEM = BUILDER
            .comment("Item ID required to revive a dead pet (e.g. \"minecraft:totem_of_undying\").")
            .define("reviveItem", "minecraft:totem_of_undying");

    public static final ModConfigSpec.IntValue REVIVE_ITEM_COUNT = BUILDER
            .comment("Number of revive items required to revive a dead pet.")
            .defineInRange("reviveItemCount", 1, 1, 64);

    public static final ModConfigSpec.IntValue REVIVE_COOLDOWN_SECONDS = BUILDER
            .comment("Cooldown in seconds after reviving a pet before another revive can be used.")
            .defineInRange("reviveCooldownSeconds", 120, 0, 86400);

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
                    "touhou_little_maid:chair"
            ), s -> s instanceof String && (((String) s).contains(":") || ((String) s).endsWith(":*")));

    public static final ModConfigSpec.ConfigValue<List<? extends String>> NO_REVIVE_WHITELIST = BUILDER
            .comment("Entity types that keep their death drops and cannot be revived via this mod.",
                    "Format: entity id, e.g. \"minecraft:villager\". Pets of these types will still be tracked,",
                    "but on death they drop loot normally and the revive button is disabled for them.")
            .defineListAllowEmpty("noReviveWhitelist", java.util.Arrays.asList(
                    "touhou_little_maid:maid",
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
                    "goety:vex_servant",
                    "goety:wither_skeleton_servant",
                    "goety:border_wraith_servant",
                    "goety:haunted_armor_servant",
                    "goety:blackguard_servant",
                    "goety:vanguard_servant"
            ), s -> s instanceof String && ((String) s).contains(":"));


    static final ModConfigSpec SPEC = BUILDER.build();

    public static final java.util.List<String> ownerNbtFields = new java.util.ArrayList<>(java.util.Arrays.asList(
            "Owner", "OwnerUUID"));
    public static int syncIntervalTicks;
    public static int localSyncIntervalTicks;
    public static int savePetDataCooldownTicks;
    public static double recallRange;
    public static int recallCooldownMs;
    public static int maxPets;
    public static int areaRecallDefaultRange;
    public static int maxPendingSummons;
    public static String reviveItem;
    public static int reviveItemCount;
    public static int reviveCooldownSeconds;
    public static boolean enableLoginLoadDiagnostics;
    public static java.util.Set<String> autoRegisterBlacklist = new java.util.HashSet<>();
    /** Entity type ids that keep death drops and cannot be revived. */
    public static java.util.Set<String> noReviveWhitelist = new java.util.HashSet<>();
    /** Entity type ids that, on death, additionally clear NBT data and in-memory cache. Also treated as no-revive. */
    public static java.util.Set<String> clearOnDeathWhitelist = new java.util.HashSet<>();

    static void onLoad(final ModConfigEvent event)
    {
        ownerNbtFields.clear();
        ownerNbtFields.addAll(OWNER_NBT_FIELDS.get());

        syncIntervalTicks = SYNC_INTERVAL_TICKS.get();
        localSyncIntervalTicks = LOCAL_SYNC_INTERVAL_TICKS.get();
        savePetDataCooldownTicks = SAVE_PET_DATA_COOLDOWN_TICKS.get();
        recallRange = RECALL_RANGE.get();
        recallCooldownMs = RECALL_COOLDOWN_MS.get();
        maxPets = MAX_PETS.get();
        areaRecallDefaultRange = AREA_RECALL_DEFAULT_RANGE.get();
        maxPendingSummons = MAX_PENDING_SUMMONS.get();
        reviveItem = REVIVE_ITEM.get();
        reviveItemCount = REVIVE_ITEM_COUNT.get();
        reviveCooldownSeconds = REVIVE_COOLDOWN_SECONDS.get();
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
