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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

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
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> loadPointedPet(ctx.getSource())))
        );
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var heldItemId = ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem());
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

        UUID ownerUUID = trulybestfriends.getCompatOwnerUUID(pointed);
        if (ownerUUID != null
                && !ownerUUID.equals(player.getUUID())
                && !source.hasPermission(2)) {
            source.sendFailure(Component.translatable("trulybestfriends.load.no_permission"));
            return 0;
        }

        Component entityName = pointed.getDisplayName();
        UUID petUUID = pointed.getUUID();

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
