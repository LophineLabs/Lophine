/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Adapted from Carpet TIS Addition, Fallen_Breath and contributors.
 * Upstream revision: 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
 */
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

public final class TisUtilityCommands {
    private TisUtilityCommands() {
    }

    public static boolean canCheat(final CommandSourceStack source) {
        return !GeneralCompatConfig.opPlayerNoCheat || !(source.getEntity() instanceof ServerPlayer);
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("removeentity")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandRemoveEntity))
                .then(Commands.argument("target", EntityArgument.entities())
                        .executes(context -> removeEntities(context.getSource(), EntityArgument.getEntities(context, "target")))));
        var duration = Commands.argument("duration", IntegerArgumentType.integer(0, 60_000));
        for (String unit : new String[]{"s", "ms", "us"}) {
            duration.then(Commands.literal(unit).executes(context -> sleep(context.getSource(),
                    IntegerArgumentType.getInteger(context, "duration"), unit)));
        }
        dispatcher.register(Commands.literal("sleep")
                .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandSleep))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("/sleep <duration 0..60000> <s|ms|us>; pauses the executing tick thread."), false);
                    return 0;
                })
                .then(duration));
    }

    private static int removeEntities(final CommandSourceStack source, final Collection<? extends Entity> targets) {
        return TisEntityRemovalCommand.execute(source, targets);
    }

    private static int sleep(final CommandSourceStack source, final int duration, final String unit) throws CommandSyntaxException {
        try {
            switch (unit) {
                case "s" -> Thread.sleep(duration * 1000L);
                case "ms" -> Thread.sleep(duration);
                case "us" -> Thread.sleep(duration / 1000L, duration % 1000);
                default -> throw new IllegalArgumentException(unit);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            source.sendFailure(Component.literal("Sleep interrupted."));
        }
        return 0;
    }
}
