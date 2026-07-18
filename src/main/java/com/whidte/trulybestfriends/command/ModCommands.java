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
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/** Registers {@code /tbf} commands. */
@Mod.EventBusSubscriber(modid = trulybestfriends.MODID)
public class ModCommands {
    private static final double PICK_REACH = 5.0D;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("tbf")
                        .then(Commands.literal("load")
                                .executes(ctx -> loadPointedPet(ctx.getSource())))
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

        UUID ownerUUID = trulybestfriends.getCompatOwnerUUID(pointed);
        if (ownerUUID != null
                && !ownerUUID.equals(player.getUUID())
                && !source.hasPermission(2)) {
            source.sendFailure(Component.literal(
                    "You do not have permission to load another player's pet (requires OP)."));
            return 0;
        }

        boolean wasBlacklisted = trulybestfriends.isPetUUIDBlacklisted(level, pointed.getUUID());
        String entityName = pointed.getDisplayName().getString();
        UUID petUUID = pointed.getUUID();

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
