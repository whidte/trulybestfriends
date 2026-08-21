package com.whidte.trulybestfriends.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Registers {@code /tbf} commands and the manual-registration item interaction. */
public class ModCommands {
    private static final double PICK_REACH = 5.0D;
    private static final long CLEAR_CONFIRMATION_TIMEOUT_MS = 30_000L;
    private static final java.util.Map<UUID, Long> pendingClearConfirmations =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ModCommands() {}

    /** Called from the common mod initializer. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register(ModCommands::onRegisterCommands);
        UseEntityCallback.EVENT.register(ModCommands::onEntityInteract);
    }

    private static void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                           net.minecraft.commands.CommandBuildContext buildContext,
                                           net.minecraft.commands.Commands.CommandSelection environment) {
        dispatcher.register(
                Commands.literal("tbf")
                        .then(Commands.literal("load")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> loadPointedPet(ctx.getSource()))
                                .then(Commands.literal("master")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(ctx -> forceLoadPointedPet(ctx.getSource()))))
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
        );
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

    private static InteractionResult onEntityInteract(Player player, Level world, InteractionHand hand,
                                                      Entity target, EntityHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        ItemStack heldItem = serverPlayer.getItemInHand(hand);
        var heldItemId = BuiltInRegistries.ITEM.getKey(heldItem.getItem());
        if (heldItemId == null || !heldItemId.toString().equals(Config.manualRegisterItem)) {
            return InteractionResult.PASS;
        }

        CommandSourceStack source = serverPlayer.createCommandSourceStack();
        boolean shouldConsume = Config.consumeManualRegisterItem && !serverPlayer.getAbilities().instabuild;
        if (shouldConsume && heldItem.getCount() < Config.manualRegisterItemConsumeCount) {
            source.sendFailure(Component.translatable(
                    "trulybestfriends.load.not_enough_register_items", Config.manualRegisterItemConsumeCount));
        } else {
            int result = loadPet(source, serverPlayer, target, false);
            if (result > 0 && shouldConsume) {
                heldItem.shrink(Config.manualRegisterItemConsumeCount);
            }
        }
        return InteractionResult.SUCCESS;
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

        UUID ownerUUID = trulybestfriends.getCompatOwnerUUID(pointed);
        if (ownerUUID != null
                && !ownerUUID.equals(player.getUUID())
                && !source.hasPermission(2)) {
            source.sendFailure(Component.translatable("trulybestfriends.load.no_permission"));
            return 0;
        }

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

    private static Entity pickPointedEntity(ServerPlayer player) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 view = player.getLookAngle();
        Vec3 end = eye.add(view.scale(PICK_REACH));
        AABB box = player.getBoundingBox()
                .expandTowards(view.scale(PICK_REACH))
                .inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, end, box,
                entity -> !entity.isSpectator() && entity.isPickable() && !entity.equals(player),
                PICK_REACH * PICK_REACH);
        return hit == null ? null : hit.getEntity();
    }
}
