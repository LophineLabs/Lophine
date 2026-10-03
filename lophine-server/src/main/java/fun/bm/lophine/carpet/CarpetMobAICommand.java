// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;
import static net.minecraft.commands.arguments.ResourceArgument.getSummonableEntityType;
import static net.minecraft.commands.arguments.ResourceArgument.resource;

public class CarpetMobAICommand
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext commandBuildContext)
    {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("track").
                requires((player) -> CarpetCommandPermissions.canUse(player, fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandTrackAI)).
                then(argument("entity type", resource(commandBuildContext, Registries.ENTITY_TYPE)).

                        suggests( (c, b) -> suggest(CarpetMobAI.availbleTypes(c.getSource()), b)).
                        then(literal("clear").executes( (c) ->
                                {
                                    CarpetMobAI.clearTracking(c.getSource().getServer(), getSummonableEntityType(c, "entity type").value());
                                    return 1;
                                }
                        )).
                        then(argument("aspect", StringArgumentType.word()).
                                suggests( (c, b) -> suggest(CarpetMobAI.availableFor(getSummonableEntityType(c, "entity type").value()),b)).
                                executes( (c) -> {
                                    CarpetMobAI.startTracking(
                                            getSummonableEntityType(c, "entity type").value(),
                                            CarpetMobAI.TrackingType.valueOf(StringArgumentType.getString(c, "aspect").toUpperCase())
                                    );
                                    return 1;
                                })));
        dispatcher.register(command);
    }
}