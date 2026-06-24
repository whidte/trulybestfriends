package com.whidte.trulybestfriends;

import java.util.Objects;

/**
 * Standalone smoke test for dimension translation key generation.
 * Does NOT depend on Minecraft/Forge — pure Java.
 * Run with: javac ConfigDimensionTest.java && java ConfigDimensionTest
 */
public class ConfigDimensionTest {

    public static void main(String[] args) {
        int passed = 0, total = 0;

        passed += test("overworld 原版键", "minecraft:overworld", "dimension.minecraft.overworld"); total++;
        passed += test("nether 原版键", "minecraft:the_nether", "dimension.minecraft.the_nether"); total++;
        passed += test("nether 大小写兼容", "minecraft:the_Nether", "dimension.minecraft.the_nether"); total++;
        passed += test("end 原版键", "minecraft:the_end", "dimension.minecraft.the_end"); total++;
        passed += test("普通模组维度", "aether:the_aether", "dimension.aether.the_aether"); total++;
        passed += test("多级路径模组维度", "example:foo/bar", "dimension.example.foo.bar"); total++;
        passed += test("空字符串返回 null", "", null); total++;
        passed += test("非法维度返回 null", "bad id", null); total++;

        int failed = total - passed;
        System.out.println();
        System.out.println("=== " + passed + "/" + total + " passed" + (failed > 0 ? ", " + failed + " FAILED" : "") + " ===");
        if (failed > 0) System.exit(1);
    }

    private static String lookup(String dimKey) {
        if (dimKey == null || dimKey.isEmpty()) return null;

        String normalizedDimKey = dimKey.toLowerCase(java.util.Locale.ROOT);
        return switch (normalizedDimKey) {
            case "minecraft:overworld" -> "dimension.minecraft.overworld";
            case "minecraft:the_nether" -> "dimension.minecraft.the_nether";
            case "minecraft:the_end" -> "dimension.minecraft.the_end";
            default -> {
                int separator = normalizedDimKey.indexOf(':');
                if (separator <= 0 || separator == normalizedDimKey.length() - 1 || normalizedDimKey.contains(" ")) yield null;
                String namespace = normalizedDimKey.substring(0, separator);
                String path = normalizedDimKey.substring(separator + 1).replace('/', '.');
                yield "dimension." + namespace + "." + path;
            }
        };
    }

    private static int test(String desc, String dimKey, String expected) {
        String result = lookup(dimKey);
        boolean ok = Objects.equals(result, expected);
        System.out.println((ok ? "[PASS]" : "[FAIL]") + " " + desc
                + " → " + (result != null ? "\"" + result + "\"" : "null"));
        return ok ? 1 : 0;
    }
}
