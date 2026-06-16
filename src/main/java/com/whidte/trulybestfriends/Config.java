package com.whidte.trulybestfriends;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = trulybestfriends.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue SYNC_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks for reading stored pet data and syncing loaded pet data back to disk")
            .defineInRange("syncIntervalTicks", 5, 1, 100);

    public static final ForgeConfigSpec.DoubleValue RECALL_RANGE = BUILDER
            .comment("Maximum distance (blocks) for recalling a pet back into storage. Set to -1 for unlimited range")
            .defineInRange("recallRange", 16.0, -1.0, 64.0);

    public static final ForgeConfigSpec.IntValue RECALL_COOLDOWN_MS = BUILDER
            .comment("Cooldown in milliseconds between recall/summon actions")
            .defineInRange("recallCooldownMs", 3000, 0, 30000);

    public static final ForgeConfigSpec.IntValue MAX_PETS = BUILDER
            .comment("Maximum number of pets a player can have tracked at once (1-128, default 64)")
            .defineInRange("maxPets", 64, 1, 128);

    public static final ForgeConfigSpec.BooleanValue ENABLE_LOGIN_LOAD_DIAGNOSTICS = BUILDER
            .comment("If true, validates all pet .nbt files on player login and logs counts. Debug only.")
            .define("enableLoginLoadDiagnostics", false);

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DIMENSION_NAMES = BUILDER
            .comment("Dimension display names. Format: \"dimension_id|lang_code|Name\". Separate entries per language.")
            .defineListAllowEmpty("dimensionNames", java.util.Arrays.asList(
                    "minecraft:overworld|en_us|Overworld",
                    "minecraft:overworld|zh_cn|主世界",
                    "minecraft:the_nether|en_us|The Nether",
                    "minecraft:the_nether|zh_cn|下界",
                    "minecraft:the_end|en_us|The End",
                    "minecraft:the_end|zh_cn|末地"
            ), s -> s instanceof String && ((String) s).contains("|"));

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int syncIntervalTicks;
    public static double recallRange;
    public static int recallCooldownMs;
    public static int maxPets;
    public static boolean enableLoginLoadDiagnostics;
    /** dimKey -> (langCode -> displayName) */
    public static Map<String, Map<String, String>> customDimensionNames = new LinkedHashMap<>();

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        syncIntervalTicks = SYNC_INTERVAL_TICKS.get();
        recallRange = RECALL_RANGE.get();
        recallCooldownMs = RECALL_COOLDOWN_MS.get();
        maxPets = MAX_PETS.get();
        enableLoginLoadDiagnostics = ENABLE_LOGIN_LOAD_DIAGNOSTICS.get();

        customDimensionNames.clear();
        for (String entry : DIMENSION_NAMES.get()) {
            String[] parts = entry.split("\\|", 3);
            if (parts.length == 3) {
                customDimensionNames
                        .computeIfAbsent(parts[0], k -> new HashMap<>())
                        .put(parts[1], parts[2]);
            }
        }
    }

    /**
     * Get the display name for a dimension in the currently selected language.
     * Falls back: current lang → en_us → first available → null.
     */
    public static String getDimensionDisplayName(String dimKey) {
        String currentLang = net.minecraft.client.Minecraft.getInstance()
                .getLanguageManager().getSelected();
        return getDimensionDisplayName(dimKey, currentLang);
    }

    /**
     * Testable overload: get display name given an explicit language code.
     * Falls back: currentLang → en_us → first available → null.
     */
    static String getDimensionDisplayName(String dimKey, String currentLang) {
        Map<String, String> langMap = customDimensionNames.get(dimKey);
        if (langMap == null || langMap.isEmpty()) return null;

        String name = langMap.get(currentLang);
        if (name != null) return name;

        name = langMap.get("en_us");
        if (name != null) return name;

        return langMap.values().iterator().next();
    }
}
