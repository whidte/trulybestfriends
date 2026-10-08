package com.whidte.trulybestfriends.tab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.whidte.trulybestfriends.network.PetIOUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 验证列表排序依据（优先级 / 注册时间 / 当前生命值）与升降序方向。 */
public final class PetSortSmokeTest {
    private PetSortSmokeTest() {}

    public static void main(String[] args) {
        testSortModeLookup();
        testPrioritySort();
        testRegisteredAtSort();
        testRegisteredAtTieBreak();
        testHealthRatioHelper();
        testHealthSort();
        testHealthSortTieBreak();
        System.out.println("PetSortSmokeTest: passed");
    }

    private static void testSortModeLookup() {
        for (ListSortMode mode : ListSortMode.values()) {
            require(ListSortMode.byKey(mode.key()) == mode,
                    "sort mode lookup failed for " + mode.key());
        }
        require(ListSortMode.byKey("trulybestfriends.sort.mode.unknown") == ListSortMode.PRIORITY,
                "an unknown sort mode key did not fall back to priority");
        require(ListSortMode.byKey(null) == ListSortMode.PRIORITY,
                "a null sort mode key did not fall back to priority");
        require(ListSortMode.values().length == 3,
                "the selector should support priority, registration time and health");
    }

    private static void testPrioritySort() {
        Map<String, CompoundTag> pets = new LinkedHashMap<>();
        pets.put("high", pet(1, null));
        pets.put("default", pet(null, null));
        pets.put("low", pet(4, null));

        require(sorted(pets, ListSortMode.PRIORITY, true).equals(List.of("high", "low", "default")),
                "ascending priority sort did not put the highest priority first");
        require(sorted(pets, ListSortMode.PRIORITY, false).equals(List.of("default", "low", "high")),
                "descending priority sort did not reverse the list");
    }

    private static void testRegisteredAtSort() {
        Map<String, CompoundTag> pets = new LinkedHashMap<>();
        pets.put("legacy", pet(null, null));
        pets.put("newest", pet(null, 3000L));
        pets.put("oldest", pet(null, 1000L));
        pets.put("middle", pet(null, 2000L));

        require(sorted(pets, ListSortMode.REGISTERED_AT, true)
                        .equals(List.of("legacy", "oldest", "middle", "newest")),
                "ascending registration time sort did not start with the oldest pet");
        require(sorted(pets, ListSortMode.REGISTERED_AT, false)
                        .equals(List.of("newest", "middle", "oldest", "legacy")),
                "descending registration time sort did not put the newest pet first");
    }

    private static void testRegisteredAtTieBreak() {
        Map<String, CompoundTag> pets = new LinkedHashMap<>();
        pets.put("sameStampLowPriority", pet(4, 1000L));
        pets.put("sameStampHighPriority", pet(1, 1000L));
        pets.put("newer", pet(6, 2000L));

        require(sorted(pets, ListSortMode.REGISTERED_AT, true)
                        .equals(List.of("sameStampHighPriority", "sameStampLowPriority", "newer")),
                "equal registration timestamps were not broken by priority");
        require(sorted(pets, ListSortMode.REGISTERED_AT, false)
                        .equals(List.of("newer", "sameStampLowPriority", "sameStampHighPriority")),
                "reversing did not flip the tie-broken registration time order");
    }

    /** 生命值比例的取值口径：顶层 MaxHealth、Attributes 回退、兜底与钳制。 */
    private static void testHealthRatioHelper() {
        require(PetIOUtil.healthRatioFrom(null) == 0.0F,
                "a missing snapshot should read as an empty health bar");

        requireClose(PetIOUtil.healthRatioFrom(health(10.0F, 20.0F)), 0.5F,
                "10 of 20 health should be a half full bar");
        requireClose(PetIOUtil.healthRatioFrom(health(0.0F, 20.0F)), 0.0F,
                "a dead pet should sit at the empty end");

        // 顶层 MaxHealth 优先于 Attributes.Base：Attributes.Base 是未驯服时的基础值，
        // 若误用它，一只满血的已驯服狼（40 上限 / Base 20）会被算成 200%。
        CompoundTag bothKeys = health(40.0F, 40.0F);
        addMaxHealthAttribute(bothKeys, 20.0F);
        requireClose(PetIOUtil.healthRatioFrom(bothKeys), 1.0F,
                "the top level MaxHealth should win over the Attributes base value");

        // MaxHealth 缺失时才回退到 Attributes，并兼容省略命名空间的简写键名。
        CompoundTag attributeOnly = new CompoundTag();
        attributeOnly.putFloat("Health", 10.0F);
        addMaxHealthAttribute(attributeOnly, 40.0F);
        requireClose(PetIOUtil.healthRatioFrom(attributeOnly), 0.25F,
                "a missing MaxHealth should fall back to the Attributes entry");

        CompoundTag legacyAttribute = new CompoundTag();
        legacyAttribute.putFloat("Health", 10.0F);
        addLegacyMaxHealthAttribute(legacyAttribute, 40.0F);
        requireClose(PetIOUtil.healthRatioFrom(legacyAttribute), 0.25F,
                "the namespace-less max health attribute name should also be recognised");

        // 两者都拿不到上限时兜底为 20，避免除零并把比例放大成无穷。
        CompoundTag noMaxHealth = new CompoundTag();
        noMaxHealth.putFloat("Health", 10.0F);
        requireClose(PetIOUtil.healthRatioFrom(noMaxHealth), 0.5F,
                "an unknown max health should fall back to the default");

        // 超出满血与负生命值都必须被钳制，保证与界面上那条血条同序。
        requireClose(PetIOUtil.healthRatioFrom(health(40.0F, 20.0F)), 1.0F,
                "an over-healed pet should be clamped to a full bar");
        requireClose(PetIOUtil.healthRatioFrom(health(-5.0F, 20.0F)), 0.0F,
                "a negative health value should be clamped to an empty bar");

        CompoundTag noHealthKey = new CompoundTag();
        requireClose(PetIOUtil.healthRatioFrom(noHealthKey), 0.0F,
                "a snapshot without a Health key should read as an empty bar");
    }

    private static void testHealthSort() {
        Map<String, CompoundTag> pets = new LinkedHashMap<>();
        pets.put("full", health(20.0F, 20.0F));
        pets.put("hurt", health(5.0F, 20.0F));
        pets.put("dead", health(0.0F, 20.0F));
        pets.put("half", health(10.0F, 20.0F));

        require(sorted(pets, ListSortMode.HEALTH, true)
                        .equals(List.of("dead", "hurt", "half", "full")),
                "ascending health sort did not put the most injured pet first");
        require(sorted(pets, ListSortMode.HEALTH, false)
                        .equals(List.of("full", "half", "hurt", "dead")),
                "descending health sort did not put the healthiest pet first");
    }

    /** 比例相同时退化为按优先级排列，避免呈现出无意义的哈希顺序。 */
    private static void testHealthSortTieBreak() {
        Map<String, CompoundTag> pets = new LinkedHashMap<>();
        pets.put("halfLowPriority", petWithHealth(4, 10.0F, 20.0F));
        pets.put("halfHighPriority", petWithHealth(1, 10.0F, 20.0F));
        pets.put("fullHighPriority", petWithHealth(1, 20.0F, 20.0F));
        // 多只死亡宠物比例都为 0，此时方向与破平局规则都要仍然成立。
        pets.put("deadLowPriority", petWithHealth(5, 0.0F, 20.0F));
        pets.put("deadHighPriority", petWithHealth(2, 0.0F, 20.0F));

        require(sorted(pets, ListSortMode.HEALTH, true).equals(List.of(
                        "deadHighPriority", "deadLowPriority",
                        "halfHighPriority", "halfLowPriority", "fullHighPriority")),
                "equal health ratios were not broken by priority");
        // reversed() 反的是整条链，破平局的优先级顺序也会一起翻过来。
        require(sorted(pets, ListSortMode.HEALTH, false).equals(List.of(
                        "fullHighPriority", "halfLowPriority", "halfHighPriority",
                        "deadLowPriority", "deadHighPriority")),
                "reversing did not flip the tie-broken health order");
    }

    /** 构造一只宠物的快照标签：priority 与 registeredAt 为 null 时对应字段留空。 */
    private static CompoundTag pet(Integer priority, Long registeredAt) {
        CompoundTag nbt = new CompoundTag();
        if (priority != null) nbt.putInt("Priority", priority);
        if (registeredAt != null) nbt.putLong(PetIOUtil.REGISTERED_AT_KEY, registeredAt);
        return nbt;
    }

    /** 构造一只带生命值与优先级的宠物快照标签。 */
    private static CompoundTag petWithHealth(int priority, float health, float maxHealth) {
        CompoundTag nbt = health(health, maxHealth);
        nbt.putInt("Priority", priority);
        return nbt;
    }

    /** 构造只含 Health / MaxHealth 的生命值快照标签。 */
    private static CompoundTag health(float current, float max) {
        CompoundTag nbt = new CompoundTag();
        nbt.putFloat("Health", current);
        nbt.putFloat("MaxHealth", max);
        return nbt;
    }

    private static void addMaxHealthAttribute(CompoundTag nbt, float base) {
        addAttribute(nbt, "minecraft:generic.max_health", base);
    }

    private static void addLegacyMaxHealthAttribute(CompoundTag nbt, float base) {
        addAttribute(nbt, "generic.max_health", base);
    }

    private static void addAttribute(CompoundTag nbt, String name, float base) {
        CompoundTag attribute = new CompoundTag();
        attribute.putString("Name", name);
        attribute.putFloat("Base", base);
        ListTag attributes = nbt.contains("Attributes", Tag.TAG_LIST)
                ? nbt.getList("Attributes", Tag.TAG_COMPOUND) : new ListTag();
        attributes.add(attribute);
        nbt.put("Attributes", attributes);
    }

    /** 按给定排序依据与方向排列宠物名称，返回名称列表。 */
    private static List<String> sorted(Map<String, CompoundTag> pets, ListSortMode mode, boolean ascending) {
        List<String> names = new ArrayList<>(pets.keySet());
        Comparator<String> comparator = TrulyScreen.petSortComparator(mode, ascending,
                name -> PetIOUtil.priorityFrom(pets.get(name)),
                pets::get);
        names.sort(comparator);
        return names;
    }

    private static void requireClose(float actual, float expected, String message) {
        if (Math.abs(actual - expected) > 1.0E-6F) {
            throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
