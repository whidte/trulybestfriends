package com.whidte.trulybestfriends;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;
import java.util.Locale;

@Mod.EventBusSubscriber(modid = trulybestfriends.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue PERFORMANCE_MODE = BUILDER
            .comment("若为 true，则禁用来自驯服事件、实体加入、附近扫描和全量扫描的宠物自动注册。",
                    "宠物必须通过 /tbf load 或手动注册物品来注册。")
            .define("performanceMode", false);

    public static final ForgeConfigSpec.IntValue PERFORMANCE_MODE_SYNC_INTERVAL_TICKS = BUILDER
            .comment("性能模式下，按已追踪的 UUID 更新已加载宠物的间隔（单位：tick）。")
            .defineInRange("performanceModeSyncIntervalTicks", 5, 1, 1200);

    public static final ForgeConfigSpec.IntValue BOSS_FIGHT_PET_LIMIT = BUILDER
            .comment("防围殴：当玩家能看到 Boss 血条时，每 20 tick 随机收回",
                    "该玩家在 LOCAL_SYNC_CHUNK_RADIUS 区块内的有主宠物，直到剩余数量降到该值。",
                    "玩家当前队伍中的宠物、正被骑乘的宠物以及未被追踪的宠物不在此列。",
                    "-1 表示完全禁用该检查。高于 (maxPets - 当前队伍规模) 的值会被钳制",
                    "到该上限（0-512，默认 -1）。")
            .defineInRange("bossFightPetLimit", -1, -1, 512);

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> OWNER_NBT_FIELDS = BUILDER
            .comment("用于在未实现 OwnableEntity 的生物实体上查找主人 UUID 的 NBT 路径。",
                    "使用点号遍历嵌套复合标签，例如 ForgeData.Owner。路径按顺序依次检查。",
                    "最后一个字段可以包含 UUID 标签或 UUID 字符串。路径段区分大小写。")
            .defineListAllowEmpty("ownerNbtFields", java.util.Arrays.asList(
                    "Owner",
                    "OwnerUUID",
                    "ForgeCaps.mob_controller:mob_control.ControllerUUID"
            ), s -> s instanceof String path && OwnerNbtResolver.isValidPath(path));

    public static final ForgeConfigSpec.IntValue SYNC_INTERVAL_TICKS = BUILDER
            .comment("对所有已加载的有主实体进行全量兜底扫描并缓存其最新宠物数据的间隔（单位：tick）。",
                    "设为 0 则禁用全量扫描。")
            .defineInRange("syncIntervalTicks", 103, 0, 1200);

    public static final ForgeConfigSpec.IntValue LOCAL_SYNC_INTERVAL_TICKS = BUILDER
            .comment("对已完成 Truly Best Friends 进度的玩家周围附近实体进行扫描的间隔（单位：tick）")
            .defineInRange("localSyncIntervalTicks", 5, 1, 100);

    public static final ForgeConfigSpec.IntValue SAVE_PET_DATA_COOLDOWN_TICKS = BUILDER
            .comment("将缓存的宠物数据落盘到磁盘的间隔（单位：tick）。玩家退出登录和服务器停止时总是立即落盘。")
            .defineInRange("savePetDataCooldownTicks", 100, 1, 1200);

    public static final ForgeConfigSpec.DoubleValue RECALL_RANGE = BUILDER
            .comment("把宠物收回存储的最大距离（方块）。设为 -1 表示距离不限")
            .defineInRange("recallRange", 16.0, -1.0, 64.0);

    public static final ForgeConfigSpec.IntValue RECALL_COOLDOWN_MS = BUILDER
            .comment("收回/召唤操作之间的冷却，单位为毫秒（最小 250 毫秒 = 5 tick，以确保实体清理完成）")
            .defineInRange("recallCooldownMs", 3000, 250, 30000);

    public static final ForgeConfigSpec.IntValue MAX_PETS = BUILDER
            .comment("玩家一次可追踪的宠物数量上限（1-512，默认 64）")
            .defineInRange("maxPets", 64, 1, 512);

    public static final ForgeConfigSpec.BooleanValue DELETE_STORED_PETS_DIRECTLY = BUILDER
            .comment("若为 true，从追踪中删除已收回或已死亡的宠物会永久移除其存储数据，",
                    "而不把实体释放到世界中。默认 false 保留现有的释放行为。")
            .define("deleteStoredPetsDirectly", false);

    public static final ForgeConfigSpec.IntValue AREA_RECALL_DEFAULT_RANGE = BUILDER
            .comment("按住 Shift 进行区域收回时的默认范围（方块）。可用滚轮调节（1-16）。")
            .defineInRange("areaRecallDefaultRange", 8, 1, 16);

    public static final ForgeConfigSpec.IntValue MAX_PENDING_SUMMONS = BUILDER
            .comment("每名玩家针对未加载区块中宠物的同时待处理召唤数量上限。",
                    "同时也定义八个编队队伍中每队编号成员槽位的数量。",
                    "队伍槽位编号从 1 到该值（1-8，默认 6）。实际待处理上限 = 该值 + 2 缓冲。")
            .defineInRange("maxPendingSummons", 6, 1, 8);

    public static final ForgeConfigSpec.IntValue SUMMON_BOTTLE_RIGHT_OFFSET = BUILDER
            .comment("召唤键瓶子动画与屏幕右边缘之间的间距（GUI 像素）。")
            .defineInRange("summonBottleRightOffset", 8, 0, 4096);

    public static final ForgeConfigSpec.IntValue SUMMON_BOTTLE_VERTICAL_OFFSET = BUILDER
            .comment("召唤键瓶子动画相对屏幕中心的垂直偏移（GUI 像素）。")
            .defineInRange("summonBottleVerticalOffset", 0, -4096, 4096);

    public static final ForgeConfigSpec.ConfigValue<String> REVIVE_ITEM = BUILDER
            .comment("复活死亡宠物所需的物品 ID（例如 \"minecraft:totem_of_undying\"）。",
                    "将其设为空字符串（reviveItem = \"\"）表示不需要物品。",
                    "为空时，物品提示会被隐藏，冷却结束后即可立即复活。")
            .define("reviveItem", "minecraft:totem_of_undying");

    public static final ForgeConfigSpec.ConfigValue<String> MANUAL_REGISTER_ITEM = BUILDER
            .comment("用于通过右键点击实体来手动注册宠物的物品。",
                    "该注册使用与 /tbf load 相同的检查和行为。")
            .define("manualRegisterItem", "minecraft:feather",
                    value -> value instanceof String && ResourceLocation.tryParse((String) value) != null);

    public static final ForgeConfigSpec.BooleanValue CONSUME_MANUAL_REGISTER_ITEM = BUILDER
            .comment("若为 true，手动注册宠物成功时会消耗配置数量的物品。",
                    "注册失败或玩家处于创造模式时不消耗物品。")
            .define("consumeManualRegisterItem", false);

    public static final ForgeConfigSpec.IntValue MANUAL_REGISTER_ITEM_CONSUME_COUNT = BUILDER
            .comment("成功注册后消耗的持有手动注册物品数量。")
            .defineInRange("manualRegisterItemConsumeCount", 1, 1, 64);

    public static final ForgeConfigSpec.IntValue REVIVE_ITEM_COUNT = BUILDER
            .comment("复活死亡宠物所需的复活物品数量。",
                    "当 reviveItem 为空时忽略。")
            .defineInRange("reviveItemCount", 1, 1, 64);

    public static final ForgeConfigSpec.IntValue REVIVE_COOLDOWN_SECONDS = BUILDER
            .comment("复活宠物后、再次复活可用之前的冷却（单位：秒）。")
            .defineInRange("reviveCooldownSeconds", 120, 0, 86400);

    public static final ForgeConfigSpec.IntValue HEAL_HUNGER_COST = BUILDER
            .comment("开始或延长宠物治疗时消耗的饱食度点数。创造模式玩家无需消耗。")
            .defineInRange("healHungerCost", 3, 0, 20);

    public static final ForgeConfigSpec.IntValue ADVANCED_HEAL_HUNGER_COST = BUILDER
            .comment("Shift-点击高级宠物治疗消耗的饱食度点数。创造模式玩家无需消耗。")
            .defineInRange("advancedHealHungerCost", 9, 0, 20);

    public static final ForgeConfigSpec.IntValue HEAL_PULSE_INTERVAL_TICKS = BUILDER
            .comment("宠物治疗脉冲之间的间隔（单位：tick）。")
            .defineInRange("healPulseIntervalTicks", 50, 1, 1200);

    public static final ForgeConfigSpec.IntValue ADVANCED_HEAL_PULSE_INTERVAL_TICKS = BUILDER
            .comment("高级宠物治疗脉冲之间的间隔（单位：tick）。")
            .defineInRange("advancedHealPulseIntervalTicks", 25, 1, 1200);

    public static final ForgeConfigSpec.IntValue HEAL_DURATION_PER_CLICK_TICKS = BUILDER
            .comment("一次点击增加的治疗时长。")
            .defineInRange("healDurationPerClickTicks", 300, 1, 72000);

    public static final ForgeConfigSpec.IntValue HEAL_MAX_DURATION_TICKS = BUILDER
            .comment("最大剩余治疗时长。会使结果超过该值的一次点击将被拒绝。")
            .defineInRange("healMaxDurationTicks", 1200, 1, 72000);

    public static final ForgeConfigSpec.DoubleValue HEAL_FLAT_AMOUNT = BUILDER
            .comment("每次脉冲恢复的固定生命值。")
            .defineInRange("healFlatAmount", 1.0, 0.0, 1000000.0);

    public static final ForgeConfigSpec.DoubleValue HEAL_MAX_HEALTH_FRACTION = BUILDER
            .comment("每次脉冲恢复的宠物当前最大生命值的比例（0.01 = 1%）。")
            .defineInRange("healMaxHealthFraction", 0.01, 0.0, 1.0);

    public static final ForgeConfigSpec.BooleanValue ENABLE_LOGIN_LOAD_DIAGNOSTICS = BUILDER
            .comment("若为 true，则在登录时校验宠物 .nbt 文件并在聊天中报告实体 NBT 序列化失败。仅用于调试。")
            .define("enableLoginLoadDiagnostics", false);

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> AUTO_REGISTER_BLACKLIST = BUILDER
            .comment("即使属于 OwnableEntity 也不应被自动注册为宠物的实体类型。",
                    "格式：实体 id，例如 \"minecraft:wolf\"，或命名空间通配符，例如 \"some_mod:*\"。",
                    "这只会阻止未来的自动注册，不会移除已追踪的宠物。")
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
                    "irons_spellbooks:root",
                    "touhou_little_maid:broom",
                    "touhou_little_maid:chair"
            ), s -> s instanceof String && (((String) s).contains(":") || ((String) s).endsWith(":*")));

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> NO_REVIVE_WHITELIST = BUILDER
            .comment("会保留死亡掉落且无法通过本模组复活的实体类型。",
                    "格式：实体 id，例如 \"minecraft:villager\"，或命名空间通配符，例如 \"some_mod:*\"。",
                    "这些类型的宠物仍会被追踪，但死亡时会正常掉落战利品，且它们的复活按钮会被禁用。")
            .defineListAllowEmpty("noReviveWhitelist", java.util.Arrays.asList(
                    "modulargolems:metal_golem",
                    "modulargolems:humanoid_golem",
                    "modulargolems:dog_golem"
            ), s -> s instanceof String && ((String) s).contains(":"));

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CLEAR_ON_DEATH_WHITELIST = BUILDER
            .comment("死亡时表现得像 noReviveWhitelist 实体，并且额外",
                    "将其存储的 NBT 数据和内存缓存完全清除的实体类型。",
                    "用于一次性或仅可召唤的实体，这些实体在死亡后不应留下任何痕迹。",
                    "格式：实体 id，例如 \"minecraft:horse\"，或命名空间通配符，例如 \"some_mod:*\"。")
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

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PRESENCE_PROBE_WHITELIST = BUILDER
            .comment("需要做「存在性精确探测」的实体类型。",
                    "玩家每次打开宠物标签页时，这些类型的宠物（未收回、未死亡、不在肩上）会被逐个查实",
                    "到底在不在世界上：确认已不在世界上的，会从宠物列表、磁盘 NBT 文件与内存缓存中一并删除。",
                    "格式：实体 id，例如 \"minecraft:wolf\"，或命名空间通配符，例如 \"some_mod:*\"。",
                    "留空（默认）表示完全关闭该探测。")
            .defineListAllowEmpty("presenceProbeWhitelist", java.util.Arrays.asList(),
                    s -> s instanceof String && ((String) s).contains(":"));

    static final ForgeConfigSpec SPEC = BUILDER.build();

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
    /** 保留死亡掉落且无法复活的实体类型 id。 */
    public static java.util.Set<String> noReviveWhitelist = new java.util.HashSet<>();
    /** 死亡时额外清除 NBT 数据和内存缓存的实体类型 id。同时按不可复活处理。 */
    public static java.util.Set<String> clearOnDeathWhitelist = new java.util.HashSet<>();
    /** 需要在打开标签页时做存在性精确探测的实体类型 id；确证不在世界上的会被清理。 */
    public static java.util.Set<String> presenceProbeWhitelist = new java.util.HashSet<>();

    /** 复活死亡宠物是否需要配置的物品。 */
    public static boolean isReviveItemRequired() {
        return reviveItem == null || !reviveItem.isBlank();
    }

    public enum EntityTypeList {
        AUTO_REGISTER_BLACKLIST,
        NO_REVIVE_WHITELIST,
        CLEAR_ON_DEATH_WHITELIST
    }

    /** 将实体类型加入所选的运行时列表并持久化公共配置。 */
    public static synchronized boolean addEntityType(EntityTypeList list, String entityTypeId) {
        ForgeConfigSpec.ConfigValue<List<? extends String>> configValue = switch (list) {
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

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        ownerNbtFields.clear();
        ownerNbtFields.addAll(OWNER_NBT_FIELDS.get());
        ownerNbtPaths = OwnerNbtResolver.parsePaths(ownerNbtFields);

        performanceMode = PERFORMANCE_MODE.get();
        performanceModeSyncIntervalTicks = PERFORMANCE_MODE_SYNC_INTERVAL_TICKS.get();
        bossFightPetLimit = BOSS_FIGHT_PET_LIMIT.get();
        syncIntervalTicks = SYNC_INTERVAL_TICKS.get();
        localSyncIntervalTicks = LOCAL_SYNC_INTERVAL_TICKS.get();
        savePetDataCooldownTicks = SAVE_PET_DATA_COOLDOWN_TICKS.get();
        recallRange = RECALL_RANGE.get();
        recallCooldownMs = RECALL_COOLDOWN_MS.get();
        maxPets = MAX_PETS.get();
        deleteStoredPetsDirectly = DELETE_STORED_PETS_DIRECTLY.get();
        areaRecallDefaultRange = AREA_RECALL_DEFAULT_RANGE.get();
        maxPendingSummons = MAX_PENDING_SUMMONS.get();
        summonBottleRightOffset = SUMMON_BOTTLE_RIGHT_OFFSET.get();
        summonBottleVerticalOffset = SUMMON_BOTTLE_VERTICAL_OFFSET.get();
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

        presenceProbeWhitelist.clear();
        presenceProbeWhitelist.addAll(PRESENCE_PROBE_WHITELIST.get());

    }

    /**
     * 获取维度在当前所选语言下的显示名称。
     * 当不存在语言条目时，兜底使用原始维度 id。
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

    /**
     * 检查某个实体类型 id 是否在自动注册黑名单中。
     *
     * <p>名单条目支持命名空间通配符，例如 {@code some_mod:*}。</p>
     */
    public static boolean isAutoRegisterBlacklisted(String entityTypeKey) {
        return EntityTypeListMatcher.matches(autoRegisterBlacklist, entityTypeKey);
    }

    /**
     * 检查某个实体类型 id 是否在不可复活白名单中。
     * 此类实体保留死亡掉落且无法通过本模组复活。
     * clearOnDeathWhitelist 中的实体也按不可复活处理。
     *
     * <p>两个名单的条目都支持命名空间通配符，例如 {@code some_mod:*}。</p>
     */
    public static boolean isNoReviveEntity(String entityTypeKey) {
        return EntityTypeListMatcher.matches(noReviveWhitelist, entityTypeKey)
                || EntityTypeListMatcher.matches(clearOnDeathWhitelist, entityTypeKey);
    }

    /**
     * 检查某个实体类型 id 是否在死亡清除白名单中。
     * 此类实体表现得像不可复活实体，并且死亡时会清除其 NBT 数据和缓存。
     *
     * <p>名单条目支持命名空间通配符，例如 {@code some_mod:*}。</p>
     */
    public static boolean isClearOnDeathEntity(String entityTypeKey) {
        return EntityTypeListMatcher.matches(clearOnDeathWhitelist, entityTypeKey);
    }

    /**
     * 检查某个实体类型 id 是否需要在打开标签页时做存在性精确探测。
     * 命中者若被确证不在世界上，会被从列表、磁盘与内存缓存中删除。
     *
     * <p>名单条目支持命名空间通配符，例如 {@code some_mod:*}。</p>
     */
    public static boolean isPresenceProbeEntity(String entityTypeKey) {
        return EntityTypeListMatcher.matches(presenceProbeWhitelist, entityTypeKey);
    }
}
