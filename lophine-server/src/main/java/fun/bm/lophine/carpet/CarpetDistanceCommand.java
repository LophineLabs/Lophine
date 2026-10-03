// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from gnembon/fabric-carpet f358000b175ddbcf1dd0bc59641c715fb0545664, carpet.commands.DistanceCommand.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class CarpetDistanceCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("distance").
                requires((player) -> CarpetCommandPermissions.canUse(player, GeneralCompatConfig.commandDistance)).
                then(literal("from").
                        executes((c) -> CarpetDistanceCalculator.setStart(c.getSource(), c.getSource().getPosition())).
                        then(argument("from", Vec3Argument.vec3()).
                                executes((c) -> CarpetDistanceCalculator.setStart(
                                        c.getSource(),
                                        Vec3Argument.getVec3(c, "from"))).
                                then(literal("to").
                                        executes((c) -> CarpetDistanceCalculator.distance(
                                                c.getSource(),
                                                Vec3Argument.getVec3(c, "from"),
                                                c.getSource().getPosition())).
                                        then(argument("to", Vec3Argument.vec3()).
                                                executes((c) -> CarpetDistanceCalculator.distance(
                                                        c.getSource(),
                                                        Vec3Argument.getVec3(c, "from"),
                                                        Vec3Argument.getVec3(c, "to")
                                                )))))).
                then(literal("to").
                        executes((c) -> CarpetDistanceCalculator.setEnd(c.getSource(), c.getSource().getPosition())).
                        then(argument("to", Vec3Argument.vec3()).
                                executes((c) -> CarpetDistanceCalculator.setEnd(c.getSource(), Vec3Argument.getVec3(c, "to")))));
        dispatcher.register(command);
    }
}
