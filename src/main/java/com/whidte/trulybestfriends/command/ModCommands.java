package com.whidte.trulybestfriends.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Registers {@code /tbf} commands.
 *
 * <p>{@code /tbf load} — re-reads the entity the executing player is
 * pointing at as a pet, going through the same registration checks used
 * by the automatic entity-join path (resolvable owner via
 * {@link Config#ownerNbtFields}, owner is a known player, entity type not
 * in {@link Config#isAutoRegisterBlacklisted}, owner below
 * {@link Config#maxPets}).  If the entity's UUID is currently in the read
 * blacklist (e.g. because its data was previously deleted), the blacklist
 * entry is removed first and the entity is then read normally; otherwise
 * it is read directly.  This is the recovery path for restoring a pet
 * whose entity is still loaded in the world.</p>
 */
@EventBusSubscriber(modid = trulybestfriends.MODID)
public class ModCommands {

    /** 实体射线检测的最大距离（与创造模式交互距离一致）。 */
    private static final double PICK_REACH = 5.0D;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("tbf")
                        .then(Commands.literal("load")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> loadPointedPet(ctx.getSource()))
                        )
        );
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var heldItemId = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (heldItemId == null || !heldItemId.toString().equals(Config.manualRegisterItem)) return;

        loadPet(player.createCommandSourceStack(), player, event.getTarget(), false);
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

        Component entityName = pointed.getDisplayName();
        java.util.UUID petUUID = pointed.getUUID();

        // 走原模组的正常读取判定与流程（见 trulybestfriends#tryLoadPet）。
        trulybestfriends.LoadResult result = trulybestfriends.tryLoadPet(pointed, level);
        switch (result) {
            case OK -> {
                source.sendSuccess(() -> Component.translatable(
                        "trulybestfriends.load.success", entityName, petUUID.toString()), informAdmins);
                return 1;
            }
            case NOT_A_PET -> source.sendFailure(Component.translatable("trulybestfriends.load.not_a_pet"));
            case UNKNOWN_OWNER -> source.sendFailure(Component.translatable("trulybestfriends.load.unknown_owner"));
            case TYPE_BLACKLISTED -> source.sendFailure(Component.translatable("trulybestfriends.load.type_blacklisted"));
            case LIMIT_REACHED -> source.sendFailure(Component.translatable(
                    "trulybestfriends.load.limit_reached", Config.maxPets));
            case UNBLACKLIST_FAILED -> source.sendFailure(Component.translatable(
                    "trulybestfriends.load.unblacklist_failed"));
            case SAVE_FAILED -> source.sendFailure(Component.translatable("trulybestfriends.load.save_failed"));
        }
        return 0;
    }

    /** 沿玩家视线进行实体射线检测，返回命中的实体；未命中或超出范围返回 null。 */
    private static Entity pickPointedEntity(ServerPlayer player) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 view = player.getLookAngle();
        Vec3 end = eye.add(view.scale(PICK_REACH));
        AABB box = player.getBoundingBox()
                .expandTowards(view.scale(PICK_REACH))
                .inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, end, box,
                e -> !e.isSpectator() && e.isPickable() && !e.equals(player),
                PICK_REACH * PICK_REACH);
        return hit == null ? null : hit.getEntity();
    }
}
