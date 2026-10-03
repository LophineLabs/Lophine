// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemPredicateArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.Enchantment;

public final class OrgFinderCommands {
    private OrgFinderCommands() {
    }

    private static final SuggestionProvider<CommandSourceStack> RADII = (context, builder) -> SharedSuggestionProvider.suggest(new String[]{"64", "128", "256", "512"}, builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext access) {
        var root = Commands.literal("finder").requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.commandFinder));
        var blocks = Commands.argument("blockState", BlockPredicateArgument.blockPredicate(access)).executes(context -> world(context, OrgFinderService.Kind.BLOCK, 64, false));
        blocks.then(Commands.argument("radius", IntegerArgumentType.integer(0, 512)).suggests(RADII).executes(context -> world(context, OrgFinderService.Kind.BLOCK, IntegerArgumentType.getInteger(context, "radius"), false)));
        blocks.then(Commands.literal("from").then(Commands.argument("from", BlockPosArgument.blockPos()).then(Commands.literal("to").then(Commands.argument("to", BlockPosArgument.blockPos()).executes(context -> world(context, OrgFinderService.Kind.BLOCK, 0, true))))));
        root.then(Commands.literal("block").requires(source -> OrgServerPermissions.allowed(source, "finder.block")).then(blocks));
        var items = Commands.argument("itemStack", ItemPredicateArgument.itemPredicate(access)).executes(context -> world(context, OrgFinderService.Kind.ITEM, 64, false));
        items.then(Commands.argument("radius", IntegerArgumentType.integer(0, 512)).suggests(RADII).executes(context -> world(context, OrgFinderService.Kind.ITEM, IntegerArgumentType.getInteger(context, "radius"), false)));
        var itemFrom = Commands.literal("from");
        itemFrom.then(Commands.argument("from", BlockPosArgument.blockPos())
                .then(Commands.literal("to").then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(context -> world(context, OrgFinderService.Kind.ITEM, 0, true)))));
        itemFrom.then(Commands.literal("offline_player").requires(source -> OrgServerPermissions.allowed(source, "finder.item.from.offline_player")).executes(context -> OrgFinderFiles.start(context, false)));
        items.then(itemFrom);
        root.then(Commands.literal("item").requires(source -> OrgServerPermissions.allowed(source, "finder.item")).then(items));
        var trade = Commands.argument("itemStack", ItemPredicateArgument.itemPredicate(access)).executes(context -> world(context, OrgFinderService.Kind.TRADE, 64, false));
        trade.then(Commands.argument("radius", IntegerArgumentType.integer(0, 512)).suggests(RADII).executes(context -> world(context, OrgFinderService.Kind.TRADE, IntegerArgumentType.getInteger(context, "radius"), false)));
        var books = Commands.argument("enchantment", ResourceArgument.resource(access, Registries.ENCHANTMENT)).executes(context -> world(context, OrgFinderService.Kind.BOOK, 64, false));
        books.then(Commands.argument("radius", IntegerArgumentType.integer(0, 512)).suggests(RADII).executes(context -> world(context, OrgFinderService.Kind.BOOK, IntegerArgumentType.getInteger(context, "radius"), false)));
        root.then(Commands.literal("trade").requires(source -> OrgServerPermissions.allowed(source, "finder.trade")).then(Commands.literal("item").then(trade)).then(Commands.literal("enchanted_book").then(books)));
        root.then(Commands.literal("worldEater").requires(source -> OrgHiddenPlayerActions.enabled() && OrgServerPermissions.allowed(source, "finder.worldEater")).then(Commands.argument("from", BlockPosArgument.blockPos()).then(Commands.argument("to", BlockPosArgument.blockPos()).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            try {
                var bounds = OrgFinderBounds.of(player.level(), BlockPosArgument.getBlockPos(context, "from"), BlockPosArgument.getBlockPos(context, "to"));
                var completion = CarpetAsyncCommandResults.defer(context.getSource());
                OrgFinderService.start(context.getSource(), player, bounds, OrgFinderService.Kind.BLOCK, block -> OrgFinderService.worldEater((net.minecraft.server.level.ServerLevel) block.getLevel(), block.getPos()), null, null, state -> !state.isAir(), OrgFinderText.finder("world_eater.head"), "")
                        .whenComplete((ignored, failure) -> completion.complete(failure == null, failure == null ? 1 : 0));
                return 1;
            } catch (IllegalArgumentException | IllegalStateException failure) {
                throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(OrgFinderService.commandFailure(failure)).create();
            }
        }))));
        root.then(Commands.literal("xp").requires(source -> OrgHiddenPlayerActions.enabled()).then(Commands.literal("from").then(Commands.literal("offline_player").executes(context -> OrgFinderFiles.start(context, true)))));
        root.then(Commands.literal("stop").executes(context -> {
            if (!OrgFinderService.stop(context.getSource().getPlayerOrException()))
                throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(OrgFinderText.finder("not_canceled")).create();
            return 1;
        }));
        dispatcher.register(root);
    }

    private static int world(CommandContext<CommandSourceStack> context, OrgFinderService.Kind kind, int radius, boolean area) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        try {
            var bounds = area ? (kind == OrgFinderService.Kind.BLOCK ? OrgFinderBounds.of(player.level(), BlockPosArgument.getBlockPos(context, "from"), BlockPosArgument.getBlockPos(context, "to")) : OrgFinderBounds.ofEntities(player.level(), BlockPosArgument.getBlockPos(context, "from"), BlockPosArgument.getBlockPos(context, "to"))) : OrgFinderBounds.radius(player.level(), player.blockPosition(), radius);
            var block = kind == OrgFinderService.Kind.BLOCK ? BlockPredicateArgument.getBlockPredicate(context, "blockState") : null;
            var item = kind == OrgFinderService.Kind.ITEM || kind == OrgFinderService.Kind.TRADE ? itemPredicate(context) : null;
            Holder<Enchantment> enchantment = kind == OrgFinderService.Kind.BOOK ? ResourceArgument.getEnchantment(context, "enchantment") : null;
            var completion = CarpetAsyncCommandResults.defer(context.getSource());
            String input = OrgFinderText.argument(context, kind == OrgFinderService.Kind.BLOCK ? "blockState" : "itemStack");
            Component label = kind == OrgFinderService.Kind.BOOK ? enchantment.value().description() : kind == OrgFinderService.Kind.BLOCK ? OrgFinderText.blockLabel(input) : OrgFinderText.itemLabel(input, item);
            OrgFinderService.start(context.getSource(), player, bounds, kind, block, item, enchantment, block == null ? state -> !state.isAir() : OrgFinderPalette.of(block), label, input)
                    .whenComplete((ignored, failure) -> completion.complete(failure == null, failure == null ? 1 : 0));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(OrgFinderService.commandFailure(failure)).create();
        }
    }

    static java.util.function.Predicate<net.minecraft.world.item.ItemStack> itemPredicate(CommandContext<CommandSourceStack> context) {
        var nativePredicate = ItemPredicateArgument.getItemPredicate(context, "itemStack");
        String input = OrgFinderText.argument(context, "itemStack");
        return input.equals("*") || input.equals("*[]") ? stack -> !stack.isEmpty() && nativePredicate.test(stack) : nativePredicate;
    }
}
