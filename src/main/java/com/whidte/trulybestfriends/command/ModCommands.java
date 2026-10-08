package com.whidte.trulybestfriends.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.network.OpenPetScreenPacket;
import com.whidte.trulybestfriends.network.PetIOUtil;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.ToDoubleFunction;

/**
 * 注册 {@code /tbf} 命令。
 *
 * <p>{@code /tbf load} — 将执行玩家指向的实体重新读取为宠物，走的是与
 * 自动实体加入路径相同的注册检查（可通过 {@link Config#ownerNbtFields}
 * 解析出主人、主人是已知玩家、实体类型不在
 * {@link Config#isAutoRegisterBlacklisted} 中、主人的宠物数低于
 * {@link Config#maxPets}）。若该实体的 UUID 当前位于读取黑名单中
 * （例如其数据此前被删除），则先移除黑名单条目再正常读取该实体；
 * 否则直接读取。这是还原实体仍加载于世界中的宠物时的恢复路径。</p>
 *
 * <p>{@code /tbf open <uuid>} — 让执行者的客户端打开宠物标签页并选中该宠物。
 * 供「&lt;名&gt; 已被收回」这类消息里可点击的宠物名调用；只允许自己的宠物。
 * uuid 参数支持补全：会列出附近实体的 UUID，准星所指的那只排最前，方便查看。</p>
 */
@EventBusSubscriber(modid = trulybestfriends.MODID)
public class ModCommands {

    /** 实体射线检测的最大距离（与创造模式交互距离一致）。 */
    private static final double PICK_REACH = 5.0D;
    /** 补全 {@code /tbf open} 的 uuid 时，收集附近实体的半径。 */
    private static final double SUGGEST_RADIUS = 8.0D;
    /** 补全 {@code /tbf open} 的 uuid 时，最多列出多少只实体。 */
    private static final int SUGGEST_LIMIT = 8;
    private static final long CLEAR_CONFIRMATION_TIMEOUT_MS = 30_000L;
    private static final java.util.Map<java.util.UUID, Long> pendingClearConfirmations =
            new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("tbf")
                        .then(Commands.literal("load")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> loadPointedPet(ctx.getSource()))
                                .then(Commands.literal("master")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(ctx -> forceLoadPointedPet(ctx.getSource())))
                        )
                        .then(Commands.literal("autoRegisterBlacklist")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> addPointedEntityType(ctx.getSource(),
                                        Config.EntityTypeList.AUTO_REGISTER_BLACKLIST)))
                        .then(Commands.literal("noReviveWhitelist")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> addPointedEntityType(ctx.getSource(),
                                        Config.EntityTypeList.NO_REVIVE_WHITELIST)))
                        .then(Commands.literal("clearOnDeathWhitelist")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> addPointedEntityType(ctx.getSource(),
                                        Config.EntityTypeList.CLEAR_ON_DEATH_WHITELIST)))
                        .then(Commands.literal("clear")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> requestClear(ctx.getSource()))
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> confirmClear(ctx.getSource()))))
                        // 「已被收回」消息里点击宠物名会执行这条命令。
                        // 不要求 OP：普通玩家本来就有权查看自己的宠物标签页。
                        .then(Commands.literal("open")
                                .then(Commands.argument("pet", UuidArgument.uuid())
                                        // UuidArgument 自身不提供任何补全，这里补上「附近实体」。
                                        .suggests(ModCommands::suggestNearbyEntityUuids)
                                        .executes(ctx -> openPetTab(ctx.getSource(),
                                                UuidArgument.getUuid(ctx, "pet")))))
        );
    }

    /**
     * 让执行者的客户端打开宠物标签页并选中指定宠物。
     *
     * <p>打开界面只能由客户端完成，而点击事件只能执行命令，所以走这一趟服务端。
     * 这里校验该宠物确实属于执行者（磁盘快照或肩上实体），否则任意 UUID——
     * 包括他人的宠物——都能让界面产生响应。</p>
     */
    private static int openPetTab(CommandSourceStack source, UUID petUuid) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean owned = PetIOUtil.getOwnerDir(player).resolve(petUuid + ".nbt").toFile().exists()
                || PetIOUtil.getShoulderEntity(player, petUuid) != null;
        if (!owned) {
            source.sendFailure(Component.translatable("trulybestfriends.command.open.unknown_pet"));
            return 0;
        }
        OpenPetScreenPacket.send(player, petUuid);
        return 1;
    }

    /**
     * 为 {@code /tbf open} 的 uuid 参数提供补全：列出附近实体（含准星所指）的 UUID。
     *
     * <p>准星所指的那只排最前，其余按与玩家视点的距离升序；玩家实体被排除，
     * 因为它的 UUID 对 {@code /tbf open} 永远无效。每项都带悬浮提示，
     * 写明名字、实体类型与距离，是本人宠物时额外标注——这样不用真的执行一次
     * 就知道哪只点下去会成功。</p>
     *
     * <p>玩家已经敲了一截 UUID 时，只留下前缀匹配的项。</p>
     */
    private static CompletableFuture<Suggestions> suggestNearbyEntityUuids(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return builder.buildFuture();

        Entity crosshair = pickPointedEntity(player, SUGGEST_RADIUS);
        if (crosshair instanceof Player) crosshair = null;

        Vec3 eye = player.getEyePosition(1.0F);
        List<Entity> candidates = player.serverLevel().getEntitiesOfClass(
                Entity.class,
                player.getBoundingBox().inflate(SUGGEST_RADIUS),
                entity -> !entity.isSpectator() && entity.isPickable()
                        && !entity.equals(player) && !(entity instanceof Player));

        String remaining = builder.getRemainingLowerCase();
        for (Entity entity : orderForSuggestion(crosshair, candidates,
                candidate -> candidate.distanceToSqr(eye), SUGGEST_LIMIT)) {
            String uuid = entity.getUUID().toString();
            // UUID 恒为小写，所以直接与已敲入的小写前缀比较。
            if (!remaining.isEmpty() && !uuid.startsWith(remaining)) continue;
            builder.suggest(uuid, suggestionTooltip(entity, player, entity == crosshair));
        }
        return builder.buildFuture();
    }

    /**
     * 决定补全列表的顺序与数量：准星目标排最前，其余按 {@code distance} 升序，最多 {@code limit} 只。
     *
     * <p>不依赖任何 Minecraft 运行时，所以可以直接在裸 JVM 冒烟测试里验证
     * （见 {@code ModCommandSuggestionSmokeTest}）。{@code crosshair} 不在
     * {@code candidates} 里时只是没人被提前，其余排序不受影响。</p>
     */
    static <T> List<T> orderForSuggestion(T crosshair, List<T> candidates,
                                          ToDoubleFunction<T> distance, int limit) {
        List<T> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.<T, Boolean>comparing(candidate -> candidate != crosshair)
                .thenComparingDouble(distance));
        if (limit >= 0 && ordered.size() > limit) {
            return new ArrayList<>(ordered.subList(0, limit));
        }
        return ordered;
    }

    /** 补全项的悬浮提示：准星标记 · 名字 · 实体类型 · 距离（本人宠物额外标注）。 */
    private static Component suggestionTooltip(Entity entity, ServerPlayer viewer, boolean crosshair) {
        MutableComponent tooltip = Component.empty();
        if (crosshair) {
            tooltip.append(Component.translatable("trulybestfriends.command.open.suggest.crosshair"))
                    .append(Component.literal(" · "));
        }
        tooltip.append(entity.getDisplayName())
                .append(Component.literal(" · "))
                .append(Component.literal(entityTypeId(entity)))
                .append(Component.literal(" · "))
                .append(Component.translatable("trulybestfriends.command.open.suggest.distance",
                        // 用 distanceToSqr(Vec3)（Sable 会 @Overwrite 它）；别用 distanceToSqr(Entity)
                        // ——那个重载 Sable 没有改写，实体在子级 plot 内时会给出极端数字。
                        Math.round(Math.sqrt(entity.distanceToSqr(viewer.position())))));

        UUID owner = trulybestfriends.getCompatOwnerUUID(entity);
        if (owner != null && owner.equals(viewer.getUUID())) {
            tooltip.append(Component.literal(" · "))
                    .append(Component.translatable("trulybestfriends.command.open.suggest.own_pet"));
        }
        return tooltip;
    }

    /** 实体类型的注册 id；取不到时退回该类型自身的描述文本。 */
    private static String entityTypeId(Entity entity) {
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null ? id.toString() : entity.getType().getDescription().getString();
    }

    private static int addPointedEntityType(CommandSourceStack source, Config.EntityTypeList list)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity pointed = pickPointedEntity(player);
        if (pointed == null) {
            source.sendFailure(Component.translatable("trulybestfriends.command.no_entity"));
            return 0;
        }

        var entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(pointed.getType());
        if (entityTypeId == null) {
            source.sendFailure(Component.translatable("trulybestfriends.command.unknown_entity_type"));
            return 0;
        }

        String id = entityTypeId.toString();
        String listKey = switch (list) {
            case AUTO_REGISTER_BLACKLIST -> "trulybestfriends.command.list.auto_register_blacklist";
            case NO_REVIVE_WHITELIST -> "trulybestfriends.command.list.no_revive_whitelist";
            case CLEAR_ON_DEATH_WHITELIST -> "trulybestfriends.command.list.clear_on_death_whitelist";
        };
        try {
            if (!Config.addEntityType(list, id)) {
                source.sendFailure(Component.translatable(
                        "trulybestfriends.command.list.already_present", id, Component.translatable(listKey)));
                return 0;
            }
        } catch (RuntimeException e) {
            trulybestfriends.LOGGER.error("Failed to add {} to {}", id, list, e);
            source.sendFailure(Component.translatable("trulybestfriends.command.list.save_failed", id));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "trulybestfriends.command.list.added", id, Component.translatable(listKey)), true);
        return 1;
    }

    private static int requestClear(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        long now = System.currentTimeMillis();
        pendingClearConfirmations.entrySet().removeIf(entry -> entry.getValue() < now);
        pendingClearConfirmations.put(player.getUUID(), now + CLEAR_CONFIRMATION_TIMEOUT_MS);

        Component confirm = Component.translatable("trulybestfriends.command.clear.confirm")
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tbf clear confirm"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("trulybestfriends.command.clear.confirm_hover"))));
        source.sendSuccess(() -> Component.translatable(
                "trulybestfriends.command.clear.warning", confirm), false);
        return 1;
    }

    private static int confirmClear(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Long expiresAt = pendingClearConfirmations.remove(player.getUUID());
        if (expiresAt == null || expiresAt < System.currentTimeMillis()) {
            source.sendFailure(Component.translatable("trulybestfriends.command.clear.expired"));
            return 0;
        }

        int cleared = trulybestfriends.clearAllPetData(player);
        if (cleared < 0) {
            source.sendFailure(Component.translatable("trulybestfriends.command.clear.failed"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "trulybestfriends.command.clear.success", cleared), false);
        return 1;
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var heldItemId = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (heldItemId == null || !heldItemId.toString().equals(Config.manualRegisterItem)) return;

        CommandSourceStack source = player.createCommandSourceStack();
        boolean shouldConsume = Config.consumeManualRegisterItem && !player.getAbilities().instabuild;
        if (shouldConsume && event.getItemStack().getCount() < Config.manualRegisterItemConsumeCount) {
            source.sendFailure(Component.translatable(
                    "trulybestfriends.load.not_enough_register_items", Config.manualRegisterItemConsumeCount));
        } else {
            int result = loadPet(source, player, event.getTarget(), false);
            if (result > 0 && shouldConsume) {
                event.getItemStack().shrink(Config.manualRegisterItemConsumeCount);
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static int loadPointedPet(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        Entity pointed = pickPointedEntity(player);
        if (pointed == null) {
            source.sendFailure(Component.translatable("trulybestfriends.load.no_entity"));
            return 0;
        }

        return loadPet(source, player, pointed, true);
    }

    private static int forceLoadPointedPet(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity pointed = pickPointedEntity(player);
        if (pointed == null) {
            source.sendFailure(Component.translatable("trulybestfriends.load.no_entity"));
            return 0;
        }
        if (pointed instanceof Player) {
            source.sendFailure(Component.translatable("trulybestfriends.load.master.not_living"));
            return 0;
        }

        trulybestfriends.LoadResult result = trulybestfriends.tryForceLoadPet(pointed, player, player.serverLevel());
        return reportLoadResult(source, pointed, result,
                "trulybestfriends.load.master.success",
                "trulybestfriends.load.master.not_living", true);
    }

    private static int loadPet(CommandSourceStack source, ServerPlayer player, Entity pointed,
                               boolean informAdmins) {
        ServerLevel level = player.serverLevel();

        // 权限检查：注册自己的宠物无需 OP；注册别人的宠物需要 OP（等级 ≥2）。
        // 无法解析 owner 的情况留待 tryLoadPet 返回 NOT_A_PET 反馈，不在此拦截。
        java.util.UUID ownerUUID = trulybestfriends.getCompatOwnerUUID(pointed);
        if (ownerUUID != null
                && !ownerUUID.equals(player.getUUID())
                && !source.hasPermission(2)) {
            source.sendFailure(Component.translatable("trulybestfriends.load.no_permission"));
            return 0;
        }

        // 走原模组的正常读取判定与流程（见 trulybestfriends#tryLoadPet）。
        trulybestfriends.LoadResult result = trulybestfriends.tryLoadPet(pointed, level);
        return reportLoadResult(source, pointed, result,
                "trulybestfriends.load.success", "trulybestfriends.load.not_a_pet", informAdmins);
    }

    private static int reportLoadResult(CommandSourceStack source, Entity entity,
                                        trulybestfriends.LoadResult result, String successKey,
                                        String notPetKey, boolean informAdmins) {
        if (result == trulybestfriends.LoadResult.OK) {
            Component entityName = entity.getDisplayName().copy().withStyle(style -> style.withHoverEvent(
                    new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(entity.getUUID().toString()))));
            source.sendSuccess(() -> Component.translatable(successKey, entityName), informAdmins);
            return 1;
        }

        Component failure = switch (result) {
            case NOT_A_PET -> Component.translatable(notPetKey);
            case UNKNOWN_OWNER -> Component.translatable("trulybestfriends.load.unknown_owner");
            case TYPE_BLACKLISTED -> Component.translatable("trulybestfriends.load.type_blacklisted");
            case LIMIT_REACHED -> Component.translatable("trulybestfriends.load.limit_reached", Config.maxPets);
            case UNBLACKLIST_FAILED -> Component.translatable("trulybestfriends.load.unblacklist_failed");
            case SAVE_FAILED -> Component.translatable("trulybestfriends.load.save_failed");
            case OK -> throw new IllegalStateException("handled above");
        };
        source.sendFailure(failure);
        return 0;
    }

    /** 沿玩家视线进行实体射线检测（距离取 {@link #PICK_REACH}）。 */
    private static Entity pickPointedEntity(ServerPlayer player) {
        return pickPointedEntity(player, PICK_REACH);
    }

    /** 沿玩家视线进行实体射线检测，返回命中的实体；未命中或超出 reach 返回 null。 */
    private static Entity pickPointedEntity(ServerPlayer player, double reach) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 view = player.getLookAngle();
        Vec3 end = eye.add(view.scale(reach));
        AABB box = player.getBoundingBox()
                .expandTowards(view.scale(reach))
                .inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, end, box,
                e -> !e.isSpectator() && e.isPickable() && !e.equals(player),
                reach * reach);
        return hit == null ? null : hit.getEntity();
    }
}
