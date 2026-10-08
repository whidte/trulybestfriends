package com.whidte.trulybestfriends;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 验证实体类型名单的匹配规则：精确 id、命名空间通配符，以及畸形输入。
 * 三级名单（自动注册黑名单 / 不可复活白名单 / 死亡清除白名单）共用同一份实现。
 */
public final class EntityTypeListMatcherSmokeTest {
    private EntityTypeListMatcherSmokeTest() {}

    public static void main(String[] args) {
        int checks = 0;

        // -- 精确 id --
        Set<String> exact = setOf("minecraft:wolf", "irons_spellbooks:spectral_steed");
        require(EntityTypeListMatcher.matches(exact, "minecraft:wolf"), "exact id did not match");
        require(!EntityTypeListMatcher.matches(exact, "minecraft:cat"), "unlisted exact id matched");
        checks += 2;

        // -- 命名空间通配符 --
        Set<String> wildcard = setOf("goety:*");
        require(EntityTypeListMatcher.matches(wildcard, "goety:zombie_servant"),
                "namespace wildcard did not match a member of its namespace");
        require(EntityTypeListMatcher.matches(wildcard, "goety:any/nested_path"),
                "namespace wildcard did not match a nested path");
        require(!EntityTypeListMatcher.matches(wildcard, "minecraft:wolf"),
                "namespace wildcard leaked into another namespace");
        checks += 3;

        // -- 通配符不得按前缀误伤：goety:* 不覆盖 goety_additions --
        require(!EntityTypeListMatcher.matches(setOf("goety:*"), "goety_additions:black_wolf"),
                "namespace wildcard matched a namespace that merely shares a prefix");
        require(!EntityTypeListMatcher.matches(setOf("a:*"), "ab:x"),
                "short namespace wildcard matched a longer namespace");
        checks += 2;

        // -- 全部按字面量比较：大小写不同一律不命中 --
        require(!EntityTypeListMatcher.matches(setOf("some_mod:*"), "Some_Mod:thing"),
                "a namespace with uppercase characters still produced a wildcard match");
        require(!EntityTypeListMatcher.matches(setOf("Some_Mod:*"), "some_mod:thing"),
                "a wildcard entry written with uppercase still matched");
        require(EntityTypeListMatcher.matches(setOf("Some_Mod:thing"), "Some_Mod:thing"),
                "a literal entry did not match its literal id");
        checks += 3;

        // -- 精确条目与通配条目可以共存 --
        Set<String> mixed = setOf("minecraft:wolf", "goety:*");
        require(EntityTypeListMatcher.matches(mixed, "minecraft:wolf"), "exact entry stopped matching");
        require(EntityTypeListMatcher.matches(mixed, "goety:reaper_servant"), "wildcard entry stopped matching");
        require(!EntityTypeListMatcher.matches(mixed, "minecraft:cat"), "unlisted id matched in a mixed list");
        checks += 3;

        // -- 畸形输入保持无害 --
        require(!EntityTypeListMatcher.matches(null, "minecraft:wolf"), "null list was treated as a match");
        require(!EntityTypeListMatcher.matches(Collections.emptySet(), "minecraft:wolf"),
                "empty list was treated as a match");
        require(!EntityTypeListMatcher.matches(wildcard, null), "null id was treated as a match");
        require(!EntityTypeListMatcher.matches(wildcard, ""), "empty id was treated as a match");
        require(!EntityTypeListMatcher.matches(wildcard, "bad id"), "malformed id was matched");
        require(!EntityTypeListMatcher.matches(wildcard, "goety"), "namespace-less id was matched");
        require(!EntityTypeListMatcher.matches(wildcard, ":goety"), "id without a namespace was matched");
        checks += 7;

        // -- 命名空间提取 --
        require("goety".equals(EntityTypeListMatcher.namespaceOf("goety:zombie_servant")),
                "namespace extraction returned the wrong namespace");
        require("minecraft".equals(EntityTypeListMatcher.namespaceOf("wolf")),
                "namespace-less id did not fall back to the vanilla namespace");
        require(EntityTypeListMatcher.namespaceOf("bad id") == null,
                "malformed id produced a namespace");
        require(EntityTypeListMatcher.namespaceOf("Goety:x") == null,
                "uppercase namespace was accepted");
        checks += 4;

        System.out.println("EntityTypeListMatcherSmokeTest: " + checks + "/" + checks + " passed");
    }

    private static Set<String> setOf(String... entries) {
        return new HashSet<>(java.util.Arrays.asList(entries));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
