package com.whidte.trulybestfriends;

import java.util.*;

/**
 * Standalone smoke test for dimension name matching logic.
 * Does NOT depend on Minecraft/Forge — pure Java.
 * Run with: javac ConfigDimensionTest.java && java ConfigDimensionTest
 */
public class ConfigDimensionTest {

    // Replicates Config.customDimensionNames structure: dimKey -> (langCode -> displayName)
    private static final Map<String, Map<String, String>> data = new LinkedHashMap<>();

    public static void main(String[] args) {
        setupDefaultData();

        int passed = 0, total = 0;
        passed += test("zh_cn 匹配 overworld", "minecraft:overworld", "zh_cn", "主世界"); total++;
        passed += test("zh_cn 匹配 the_nether", "minecraft:the_nether", "zh_cn", "下界"); total++;
        passed += test("zh_cn 匹配 the_end", "minecraft:the_end", "zh_cn", "末地"); total++;
        passed += test("en_us 匹配 overworld", "minecraft:overworld", "en_us", "Overworld"); total++;
        passed += test("en_us 匹配 the_nether", "minecraft:the_nether", "en_us", "The Nether"); total++;
        passed += test("en_us 匹配 the_end", "minecraft:the_end", "en_us", "The End"); total++;

        passed += test("ja_jp 无匹配 → 回退 en_us", "minecraft:overworld", "ja_jp", "Overworld"); total++;
        passed += test("ja_jp 无匹配 → 回退 en_us", "minecraft:the_nether", "ja_jp", "The Nether"); total++;
        passed += test("ja_jp 无匹配 → 回退 en_us", "minecraft:the_end", "ja_jp", "The End"); total++;
        passed += test("fr_fr 无匹配 → 回退 en_us", "minecraft:overworld", "fr_fr", "Overworld"); total++;

        passed += test("未知维度返回 null", "aether:the_aether", "zh_cn", null); total++;
        passed += test("空字符串返回 null", "", "zh_cn", null); total++;

        // Custom dimension with only zh_cn → fallback to first available
        data.computeIfAbsent("aether:the_aether", k -> new HashMap<>()).put("zh_cn", "天境");
        passed += test("zh_cn 自定义维度", "aether:the_aether", "zh_cn", "天境"); total++;
        passed += test("fr_fr 自定义无 en_us → 首个可用", "aether:the_aether", "fr_fr", "天境"); total++;

        // Add en_us — now fr_fr falls back to en_us
        data.get("aether:the_aether").put("en_us", "The Aether");
        passed += test("fr_fr 自定义有 en_us → 回退 en_us", "aether:the_aether", "fr_fr", "The Aether"); total++;

        // de_de with only en_us available — goes to en_us
        data.computeIfAbsent("twilightforest:twilight_forest", k -> new HashMap<>()).put("en_us", "Twilight Forest");
        passed += test("de_de only en_us → en_us", "twilightforest:twilight_forest", "de_de", "Twilight Forest"); total++;

        int failed = total - passed;
        System.out.println();
        System.out.println("=== " + passed + "/" + total + " passed" + (failed > 0 ? ", " + failed + " FAILED" : "") + " ===");
        if (failed > 0) System.exit(1);
    }

    private static void setupDefaultData() {
        data.clear();
        data.computeIfAbsent("minecraft:overworld", k -> new HashMap<>())
                .putAll(Map.of("en_us", "Overworld", "zh_cn", "主世界"));
        data.computeIfAbsent("minecraft:the_nether", k -> new HashMap<>())
                .putAll(Map.of("en_us", "The Nether", "zh_cn", "下界"));
        data.computeIfAbsent("minecraft:the_end", k -> new HashMap<>())
                .putAll(Map.of("en_us", "The End", "zh_cn", "末地"));
    }

    // Exact same logic as Config.getDimensionDisplayName(dimKey, lang)
    private static String lookup(String dimKey, String lang) {
        Map<String, String> langMap = data.get(dimKey);
        if (langMap == null || langMap.isEmpty()) return null;

        String name = langMap.get(lang);
        if (name != null) return name;

        name = langMap.get("en_us");
        if (name != null) return name;

        return langMap.values().iterator().next();
    }

    private static int test(String desc, String dimKey, String lang, String expected) {
        String result = lookup(dimKey, lang);
        boolean ok = Objects.equals(result, expected);
        System.out.println((ok ? "[PASS]" : "[FAIL]") + " " + desc
                + " (" + lang + ") → " + (result != null ? "\"" + result + "\"" : "null"));
        return ok ? 1 : 0;
    }
}
