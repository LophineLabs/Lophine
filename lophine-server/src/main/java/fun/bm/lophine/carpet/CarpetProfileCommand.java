// SPDX-License-Identifier: LGPL-3.0-only
// Command grammar adapted from fabric-carpet f358000b175ddbcf1dd0bc59641c715fb0545664.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class CarpetProfileCommand {
    private CarpetProfileCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("profile")
            .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandProfile))
            .executes(context -> healthReport(context.getSource(), 100))
            .then(Commands.literal("health").executes(context -> healthReport(context.getSource(), 100))
                .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 24000))
                    .executes(context -> healthReport(context.getSource(), IntegerArgumentType.getInteger(context, "ticks")))))
            .then(Commands.literal("entities").executes(context -> healthEntities(context.getSource(), 100))
                .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 24000))
                    .executes(context -> healthEntities(context.getSource(), IntegerArgumentType.getInteger(context, "ticks"))))));
    }

    public static int healthReport(CommandSourceStack source, int ticks) {
        return CarpetProfileService.request(source, ticks, false);
    }

    public static int healthEntities(CommandSourceStack source, int ticks) {
        return CarpetProfileService.request(source, ticks, true);
    }
}
