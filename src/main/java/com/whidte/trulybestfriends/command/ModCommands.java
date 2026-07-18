package com.whidte.trulybestfriends.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.whidte.trulybestfriends.Config;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

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
                                .executes(ctx -> loadPointedPet(ctx.getSource()))
                        )
        );
    }

    private static int loadPointedPet(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();

        Entity pointed = pickPointedEntity(player);
        if (pointed == null) {
            source.sendFailure(Component.literal("No entity in view."));
            return 0;
        }

        // 权限检查：注册自己的宠物无需 OP；注册别人的宠物需要 OP（等级 ≥2）。
        // 无法解析 owner 的情况留待 tryLoadPet 返回 NOT_A_PET 反馈，不在此拦截。
        java.util.UUID ownerUUID = trulybestfriends.getCompatOwnerUUID(pointed);
        if (ownerUUID != null
                && !ownerUUID.equals(player.getUUID())
                && !source.hasPermission(2)) {
            source.sendFailure(Component.literal(
                    "You do not have permission to load another player's pet (requires OP)."));
            return 0;
        }

        // 记录读取前的黑名单状态，用于在成功消息里附带"已移除黑名单条目"提示。
        boolean wasBlacklisted = trulybestfriends.isPetUUIDBlacklisted(level, pointed.getUUID());
        String entityName = pointed.getDisplayName().getString();
        java.util.UUID petUUID = pointed.getUUID();

        // 走原模组的正常读取判定与流程（见 trulybestfriends#tryLoadPet）。
        trulybestfriends.LoadResult result = trulybestfriends.tryLoadPet(pointed, level);
        switch (result) {
            case OK -> {
                if (wasBlacklisted) {
                    source.sendSuccess(() -> Component.literal(
                            "Removed blacklist entry for " + entityName + " (" + petUUID + ")."), true);
                }
                source.sendSuccess(() -> Component.literal(
                        "Loaded " + entityName + " (" + petUUID + ") into the pet tab."), true);
                return 1;
            }
            case NOT_A_PET -> source.sendFailure(Component.literal(
                    "Pointed entity is not a readable pet (must be a living entity with an Owner NBT field)."));
            case UNKNOWN_OWNER -> source.sendFailure(Component.literal(
                    "Pointed entity's owner is not a known player on this server."));
            case TYPE_BLACKLISTED -> source.sendFailure(Component.literal(
                    "Pointed entity type is in autoRegisterBlacklist and cannot be loaded."));
            case LIMIT_REACHED -> source.sendFailure(Component.literal(
                    "Owner has reached maxPets limit (" + Config.maxPets + ")."));
            case UNBLACKLIST_FAILED -> source.sendFailure(Component.literal(
                    "Entity is blacklisted but the entry could not be removed."));
            case SAVE_FAILED -> source.sendFailure(Component.literal(
                    "Failed to save pet data for pointed entity."));
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
