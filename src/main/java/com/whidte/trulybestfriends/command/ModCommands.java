package com.whidte.trulybestfriends.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.whidte.trulybestfriends.trulybestfriends;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Registers {@code /tbf} commands.
 *
 * <p>{@code /tbf trackdragon} — creates a virtual Ender Dragon entity and
 * saves it as a tracked pet for the executing player.  Since the Ender
 * Dragon cannot be tamed in vanilla, this is the only way to add one to
 * the pet tab for preview/testing of multipart-entity rendering.</p>
 */
@Mod.EventBusSubscriber(modid = trulybestfriends.MODID)
public class ModCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("tbf")
                        .then(Commands.literal("trackdragon")
                                .executes(ctx -> trackDragon(ctx.getSource()))
                        )
        );
    }

    private static int trackDragon(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();

        // Look up the Ender Dragon entity type from the registry.
        EntityType<?> dragonType = ForgeRegistries.ENTITY_TYPES.getValue(
                ResourceLocation.tryParse("minecraft:ender_dragon"));
        if (dragonType == null) {
            source.sendFailure(Component.literal("Ender Dragon entity type not found."));
            return 0;
        }

        // Create an Ender Dragon entity without adding it to the world.
        // Its NBT will be saved as a pet data file; the entity itself is
        // discarded immediately afterwards.
        Entity dragon = dragonType.create(level);
        if (dragon == null) {
            source.sendFailure(Component.literal("Failed to create Ender Dragon entity."));
            return 0;
        }

        // Set a position so saveWithoutId has valid coordinates.
        dragon.setPos(player.getX(), player.getY(), player.getZ());

        if (!(dragon instanceof LivingEntity)) {
            source.sendFailure(Component.literal("Ender Dragon is not a LivingEntity."));
            dragon.discard();
            return 0;
        }

        trulybestfriends.forceTrackPet(player, dragon);
        dragon.discard();

        source.sendSuccess(() -> Component.literal("Ender Dragon added to your pet tab."), true);
        return 1;
    }
}
