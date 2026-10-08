package com.whidte.trulybestfriends.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code /tbf open} 补全列表的排序与截断规则冒烟测试。
 *
 * <p>只测 {@link ModCommands#orderForSuggestion} 这个纯函数——它不触碰任何
 * Minecraft 注册表、实体或网络类，所以可以在裸 JVM 下运行。真正收集附近实体
 * 的那一段需要 {@code ServerLevel}，无法在这里覆盖。</p>
 */
public final class ModCommandSuggestionSmokeTest {
    private ModCommandSuggestionSmokeTest() {}

    public static void main(String[] args) {
        testCrosshairComesFirst();
        testSortedByDistance();
        testInputListIsNotMutated();
        testLimit();
        testMissingCrosshairDoesNotLeak();
        testNegativeLimitKeepsEverything();
        System.out.println("ModCommandSuggestionSmokeTest: passed");
    }

    /** 准星目标必须排最前，即使它是离玩家最远的那只。 */
    private static void testCrosshairComesFirst() {
        List<String> ordered = ModCommands.orderForSuggestion(
                "far", List.of("near", "far", "mid"),
                ModCommandSuggestionSmokeTest::distanceOf, 8);
        require(List.of("far", "near", "mid").equals(ordered),
                "crosshair target was not moved to the front: " + ordered);
    }

    /** 没有准星目标时，其余一律按距离升序。 */
    private static void testSortedByDistance() {
        List<String> ordered = ModCommands.orderForSuggestion(
                null, List.of("c", "a", "b"),
                ModCommandSuggestionSmokeTest::distanceOf, 8);
        require(List.of("a", "b", "c").equals(ordered), "ordering mismatch: " + ordered);
    }

    /** 不能改动调用方传入的列表（补全可能在同一 tick 内被多次调用）。 */
    private static void testInputListIsNotMutated() {
        List<String> source = new ArrayList<>(Arrays.asList("c", "a", "b"));
        ModCommands.orderForSuggestion(null, source, ModCommandSuggestionSmokeTest::distanceOf, 2);
        require(List.of("c", "a", "b").equals(source),
                "candidate list was mutated in place: " + source);
    }

    /** 超过上限时只保留最靠前的若干项，且准星目标不会被截掉。 */
    private static void testLimit() {
        List<String> ordered = ModCommands.orderForSuggestion(
                "z", List.of("a", "b", "c", "z"), name -> name.charAt(0), 2);
        require(ordered.size() == 2, "limit was not applied: " + ordered);
        require("z".equals(ordered.get(0)), "crosshair target was cut off: " + ordered);
        require("a".equals(ordered.get(1)), "nearest candidate missing after the cut: " + ordered);
    }

    /** 准星没命中任何候选实体时，既不能抛异常，也不能把该实体混进列表。 */
    private static void testMissingCrosshairDoesNotLeak() {
        List<String> ordered = ModCommands.orderForSuggestion(
                "absent", List.of("b", "a"), name -> name.charAt(0), 8);
        require(List.of("a", "b").equals(ordered), "ordering mismatch: " + ordered);
    }

    /** 负数上限表示不限量。 */
    private static void testNegativeLimitKeepsEverything() {
        List<String> ordered = ModCommands.orderForSuggestion(
                null, List.of("a", "b", "c"), name -> name.charAt(0), -1);
        require(ordered.size() == 3, "negative limit should keep everything: " + ordered);
    }

    /** 距离排序用的假距离：a 最近，b 次之，c 最远，far 故意放到最后。 */
    private static double distanceOf(String name) {
        return switch (name) {
            case "a", "near" -> 1.0;
            case "b", "mid" -> 4.0;
            default -> 25.0;
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
