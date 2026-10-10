// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class CarpetInfoCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext) {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("info").
                requires((player) -> CarpetCommandPermissions.canUse(player, fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandInfo)).
                then(literal("block").
                        then(argument("block position", BlockPosArgument.blockPos()).
                                executes((c) -> infoBlock(
                                        c.getSource(),
                                        BlockPosArgument.getSpawnablePos(c, "block position"), null)).
                                then(literal("grep").
                                        then(argument("regexp", greedyString()).
                                                executes((c) -> infoBlock(
                                                        c.getSource(),
                                                        BlockPosArgument.getSpawnablePos(c, "block position"),
                                                        getString(c, "regexp")))))));

        dispatcher.register(command);
    }

    public static void printBlock(List<Component> messages, CommandSourceStack source, String grep) {
        CarpetMessenger.m(source, "");
        if (grep != null) {
            Pattern p = Pattern.compile(grep);
            CarpetMessenger.m(source, messages.get(0));
            for (int i = 1; i < messages.size(); i++) {
                Component line = messages.get(i);
                Matcher m = p.matcher(line.getString());
                if (m.find()) {
                    CarpetMessenger.m(source, line);
                }
            }
        } else {
            CarpetMessenger.send(source, messages);
        }
    }

    public static int infoBlock(CommandSourceStack source, BlockPos pos, String grep) {
        final Pattern filter;
        try {
            filter = grep == null ? null : Pattern.compile(grep);
        } catch (java.util.regex.PatternSyntaxException invalid) {
            CarpetMessenger.m(source, "r Invalid regular expression: " + invalid.getDescription());
            return 0;
        }
        var level = source.getLevel();
        boolean unrestricted = Commands.LEVEL_GAMEMASTERS.check(source.permissions());
        if (!unrestricted && level.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            CarpetMessenger.m(source, "r Chunk is not loaded");
            return 0;
        }
        return OrgCommandNativeEffects.command(source, 1, () -> TisCommandContinuations.then(
                OrgCommandNativeEffects.area(level, (pos.getX() - 16) >> 4, (pos.getZ() - 16) >> 4,
                        (pos.getX() + 16) >> 4, (pos.getZ() + 16) >> 4, () -> {
                            var messages = CarpetBlockInfo.blockInfo(pos.immutable(), level);
                            return filter == null ? messages : messages.stream().filter(line -> filter.matcher(line.getString()).find()).toList();
                        }), messages -> TisCommandContinuations.then(CarpetMessenger.sendAsync(source, messages), ignored -> java.util.concurrent.CompletableFuture.completedFuture(1))));
    }

    public static java.util.concurrent.CompletableFuture<List<Component>> reportAsync(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        BlockPos original = pos.immutable();
        return OrgCommandNativeEffects.area(level, (original.getX() - 16) >> 4, (original.getZ() - 16) >> 4,
                (original.getX() + 16) >> 4, (original.getZ() + 16) >> 4, () -> CarpetBlockInfo.blockInfo(original, level));
    }
}
