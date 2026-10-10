// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.leavesmc.leaves.bot.ServerBot;

import java.util.function.Consumer;

public final class OrgHiddenActionCommands {
    private OrgHiddenActionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var player = Commands.argument("player", EntityArgument.player());
        if (OrgHiddenPlayerActions.enabled()) {
            player.then(Commands.literal("plant").executes(context -> target(context, OrgHiddenPlayerActions::setPlant)));
            var go = Commands.literal("goto");
            go.then(Commands.literal("block").then(Commands.argument("target", BlockPosArgument.blockPos()).executes(context -> {
                var block = BlockPosArgument.getBlockPos(context, "target");
                return target(context, actor -> OrgHiddenPlayerActions.setGotoBlock(actor, block));
            })));
            go.then(Commands.literal("entity").then(Commands.argument("target", EntityArgument.entity()).executes(context -> {
                var entity = EntityArgument.getEntity(context, "target");
                return target(context, actor -> OrgHiddenPlayerActions.setGotoEntity(actor, entity));
            })));
            player.then(go);
            var bedrock = Commands.literal("bedrock");
            bedrock.then(Commands.literal("cuboid").then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("to", BlockPosArgument.blockPos()).executes(context -> bedrock(context, false, false, false))
                            .then(options(false)))));
            bedrock.then(Commands.literal("cylinder").then(Commands.argument("center", BlockPosArgument.blockPos())
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 1024))
                            .then(Commands.argument("height", IntegerArgumentType.integer(1, 1024)).executes(context -> bedrock(context, true, false, false))
                                    .then(options(true))))));
            player.then(bedrock);
        }
        if (OrgHiddenPlayerActions.debug()) player.then(Commands.literal("raise")
                .executes(context -> target(context, actor -> OrgHiddenPlayerActions.raise(actor, "Manually triggered debug exception")))
                .then(Commands.argument("message", StringArgumentType.string()).executes(context -> target(context,
                        actor -> OrgHiddenPlayerActions.raise(actor, StringArgumentType.getString(context, "message"))))));
        if (OrgHiddenPlayerActions.enabled() || OrgHiddenPlayerActions.debug())
            dispatcher.register(Commands.literal("playerAction").requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.commandPlayerAction)).then(player));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Boolean> options(boolean cylinder) {
        return Commands.argument("ai", BoolArgumentType.bool()).requires(source -> OrgServerPermissions.allowed(source, "playerAction.player.bedrock.ai")).executes(context -> bedrock(context, cylinder, BoolArgumentType.getBool(context, "ai"), false))
                .then(Commands.argument("timedMaterialRecycling", BoolArgumentType.bool()).executes(context ->
                        bedrock(context, cylinder, BoolArgumentType.getBool(context, "ai"), BoolArgumentType.getBool(context, "timedMaterialRecycling"))));
    }

    private static int bedrock(CommandContext<CommandSourceStack> context, boolean cylinder, boolean ai, boolean recycle) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (cylinder) {
            var center = BlockPosArgument.getBlockPos(context, "center");
            int radius = IntegerArgumentType.getInteger(context, "radius"), height = IntegerArgumentType.getInteger(context, "height");
            return target(context, actor -> OrgHiddenPlayerActions.setBedrockCylinder(actor, center, radius, height, ai, recycle), true);
        }
        var from = BlockPosArgument.getBlockPos(context, "from");
        var to = BlockPosArgument.getBlockPos(context, "to");
        return target(context, actor -> OrgHiddenPlayerActions.setBedrockCuboid(actor, from, to, ai, recycle), true);
    }

    private static int target(CommandContext<CommandSourceStack> context, Consumer<ServerPlayer> action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return target(context, action, false);
    }

    private static int target(CommandContext<CommandSourceStack> context, Consumer<ServerPlayer> action, boolean bedrock) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        if (!(player instanceof ServerBot)) throw OrgFakePlayerActionCommands.notFakePlayerException(player);
        return OrgMenuNativeEffects.command(context.getSource(), () -> TisCommandContinuations.then(OrgMenuNativeEffects.run(player, () -> {
            if (player.isRemoved() || player.isDeadOrDying()) return false;
            action.accept(player);
            return true;
        }), success -> {
            ServerPlayer sender = context.getSource().getPlayer();
            if (!success || !bedrock || sender == null)
                return java.util.concurrent.CompletableFuture.completedFuture(success);
            return OrgMenuNativeEffects.run(sender, () -> {
                if (!sender.isRemoved())
                    sender.sendOverlayMessage(Component.translatableWithFallback("carpet-org-addition.command.playerAction.bedrock.share",
                            OrgRuleTranslations.text("carpet-org-addition.command.playerAction.bedrock.share", "Please do not share the automated bedrock-breaking functionality with others")));
                return true;
            });
        }), "The hidden fake-player action could not be assigned");
    }
}
