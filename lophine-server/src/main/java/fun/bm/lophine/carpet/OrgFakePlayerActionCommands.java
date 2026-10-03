// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemPredicateArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.bot.ServerBot;

public final class OrgFakePlayerActionCommands {
    private OrgFakePlayerActionCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext access) {
        var player = Commands.argument("player", EntityArgument.player());
        player.then(Commands.literal("stop").executes(context -> assign(context, OrgFakePlayerActions.Action.simple("stop", List.of()))));
        player.then(Commands.literal("info").executes(context -> target(context, actor -> {
            var hidden = OrgHiddenPlayerActions.get(actor);
            if(hidden.get("name").getAsString().equals("stop"))for(Component line:OrgPlayerActionInfo.info(actor,OrgFakePlayerActions.get(actor)))message(context.getSource(),line);
            else for(Component line:OrgHiddenPlayerActions.info(actor))message(context.getSource(),line);
        })));
        player.then(Commands.literal("fishing").executes(context -> assign(context, OrgFakePlayerActions.Action.simple("fishing", List.of()))));
        player.then(Commands.literal("empty").executes(context -> assign(context, OrgFakePlayerActions.Action.simple("empty", List.of(OrgFakePlayerActions.ANY))))
            .then(Commands.argument("filter", ItemPredicateArgument.itemPredicate(access)).executes(context -> assign(context, OrgFakePlayerActions.Action.simple("empty", List.of(filter(context, "filter")))))));
        player.then(Commands.literal("fill").executes(context -> fill(context, OrgFakePlayerActions.ANY, true, false))
            .then(Commands.argument("filter", ItemPredicateArgument.itemPredicate(access)).executes(context -> fill(context, filter(context, "filter"), true, false))
                .then(Commands.argument("dropOther", BoolArgumentType.bool()).executes(context -> fill(context, filter(context, "filter"), BoolArgumentType.getBool(context, "dropOther"), false))
                    .then(Commands.argument("moreContainer", BoolArgumentType.bool()).executes(context -> fill(context, filter(context, "filter"), BoolArgumentType.getBool(context, "dropOther"), BoolArgumentType.getBool(context, "moreContainer")))))));
        var craft = Commands.literal("craft");
        for (String kind : List.of("one", "four", "nine")) craft.then(Commands.literal(kind).then(Commands.argument("item", ItemPredicateArgument.itemPredicate(access)).executes(context -> {
            var filters = new OrgFakePlayerActions.Filter[kind.equals("nine") ? 9 : 4]; Arrays.fill(filters, kind.equals("one") ? OrgFakePlayerActions.EMPTY : filter(context, "item")); filters[0] = filter(context, "item");
            return assign(context, OrgFakePlayerActions.Action.simple(filters.length == 9 ? "craft_table" : "craft_inventory", List.of(filters)));
        })));
        craft.then(Commands.literal("inventory").then(recipeArguments(access, 4)));
        craft.then(Commands.literal("crafting_table").then(recipeArguments(access, 9)));
        craft.then(Commands.literal("gui").executes(context -> gui(context, false)));
        player.then(craft);
        player.then(Commands.literal("trade").then(Commands.argument("index", IntegerArgumentType.integer(1)).executes(context -> trade(context, false)).then(Commands.literal("void_trade").executes(context -> trade(context, true)))));
        player.then(Commands.literal("rename").then(Commands.argument("item", ItemPredicateArgument.itemPredicate(access)).then(Commands.argument("name", StringArgumentType.string()).executes(context -> assign(context,
            new OrgFakePlayerActions.Action("rename", List.of(filter(context, "item")), 0, StringArgumentType.getString(context, "name"), null, false, false, false, Vec3.ZERO, Vec3.ZERO))))));
        player.then(Commands.literal("enchanting").then(Commands.argument("itemStack", ItemPredicateArgument.itemPredicate(access)).then(Commands.argument("enchantment", ResourceArgument.resource(access, Registries.ENCHANTMENT)).executes(context -> assign(context,
            new OrgFakePlayerActions.Action("enchanting", List.of(filter(context, "itemStack")), 0, "", ResourceArgument.getEnchantment(context, "enchantment"), false, false, false, Vec3.ZERO, Vec3.ZERO))))));
        player.then(Commands.literal("stonecutting")
            .then(Commands.literal("gui").executes(context -> gui(context, true)))
            .then(Commands.literal("item").then(Commands.argument("item", ItemPredicateArgument.itemPredicate(access)).then(Commands.argument("button", IntegerArgumentType.integer(1)).executes(context -> assign(context,
                new OrgFakePlayerActions.Action("stonecutting", List.of(filter(context, "item")), IntegerArgumentType.getInteger(context, "button") - 1, "", null, false, false, false, Vec3.ZERO, Vec3.ZERO)))))));
        player.then(sorting(access));
        var enchantment = Commands.argument("enchantment", ResourceArgument.resource(access, Registries.ENCHANTMENT));
        enchantment.executes(context -> librarian(context, -1, 64));
        enchantment.then(Commands.argument("level", IntegerArgumentType.integer(1)).then(Commands.argument("price", IntegerArgumentType.integer(1, 64)).executes(context -> librarian(context, IntegerArgumentType.getInteger(context, "level"), IntegerArgumentType.getInteger(context, "price")))));
        enchantment.then(Commands.literal("max").executes(context -> librarian(context, -1, 64)).then(Commands.argument("price", IntegerArgumentType.integer(1, 64)).executes(context -> librarian(context, -1, IntegerArgumentType.getInteger(context, "price")))));
        player.then(Commands.literal("librarian").then(Commands.argument("jobSite", BlockPosArgument.blockPos()).then(enchantment)));
        dispatcher.register(Commands.literal("playerAction").requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.commandPlayerAction)).then(player));
    }
    private static RequiredArgumentBuilder<CommandSourceStack, ItemPredicateArgument.Result> recipeArguments(CommandBuildContext access, int size) {
        RequiredArgumentBuilder<CommandSourceStack, ItemPredicateArgument.Result> child = null;
        for (int index = size; index > 0; index--) {
            var argument = Commands.argument("item" + index, ItemPredicateArgument.itemPredicate(access));
            if (child == null) argument.executes(context -> { var filters = new ArrayList<OrgFakePlayerActions.Filter>(); for (int i = 1; i <= size; i++) filters.add(filter(context, "item" + i)); return assign(context, OrgFakePlayerActions.Action.simple(size == 4 ? "craft_inventory" : "craft_table", filters)); });
            else argument.then(child);
            child = argument;
        }
        return child;
    }
    private static LiteralArgumentBuilder<CommandSourceStack> sorting(CommandBuildContext access) {
        return sorting(access,OrgCommandSettings.sortingItemLimit());
    }
    static LiteralArgumentBuilder<CommandSourceStack> sorting(CommandBuildContext access,int configuredMaximum) {
        var sorting = Commands.literal("sorting"); RequiredArgumentBuilder<CommandSourceStack, ItemPredicateArgument.Result> child = null;
        // Upstream's configurable limit defaults to 16; each 'or' adds a native item predicate.
        for (int count = Math.clamp(configuredMaximum,1,256); count > 0; count--) {
            final int length = count;
            var node = Commands.argument("item" + count, ItemPredicateArgument.itemPredicate(access));
            node.then(Commands.literal("at").then(Commands.argument("this", Vec3Argument.vec3()).then(Commands.argument("other", Vec3Argument.vec3()).executes(context -> {
                var filters = new ArrayList<OrgFakePlayerActions.Filter>(); for (int i = 1; i <= length; i++) filters.add(filter(context, "item" + i));
                return assign(context, new OrgFakePlayerActions.Action("sorting", filters, 0, "", null, false, false, false, Vec3Argument.getVec3(context, "this"), Vec3Argument.getVec3(context, "other")));
            }))));
            if (child != null) node.then(Commands.literal("or").then(child)); child = node;
        }
        return sorting.then(child);
    }
    static OrgFakePlayerActions.Filter filter(CommandContext<CommandSourceStack> context, String name) {
        String text = context.getNodes().stream().filter(node -> node.getNode().getName().equals(name)).map(node -> node.getRange().get(context.getInput())).findFirst().orElseThrow();
        return text.equals("minecraft:air") || text.equals("air") ? OrgFakePlayerActions.EMPTY : new OrgFakePlayerActions.Filter(text, ItemPredicateArgument.getItemPredicate(context, name));
    }
    private static int fill(CommandContext<CommandSourceStack> context, OrgFakePlayerActions.Filter filter, boolean dropOther, boolean more) throws CommandSyntaxException {
        return assign(context, new OrgFakePlayerActions.Action("fill", List.of(filter), 0, "", null, dropOther, more, false, Vec3.ZERO, Vec3.ZERO));
    }
    private static int trade(CommandContext<CommandSourceStack> context, boolean voidTrade) throws CommandSyntaxException {
        return assign(context, new OrgFakePlayerActions.Action("trade", List.of(), IntegerArgumentType.getInteger(context, "index") - 1, "", null, false, false, voidTrade, Vec3.ZERO, Vec3.ZERO));
    }
    private static int librarian(CommandContext<CommandSourceStack> context, int level, int price) throws CommandSyntaxException {
        var enchantment = ResourceArgument.getEnchantment(context, "enchantment");
        return assign(context, new OrgFakePlayerActions.Action("librarian", List.of(), 0, "", enchantment, false, false, false, Vec3.ZERO, Vec3.ZERO,
            new OrgFakePlayerActions.Librarian(BlockPosArgument.getBlockPos(context, "jobSite"), level == -1 ? enchantment.value().getMaxLevel() : level, price)));
    }
    private static int assign(CommandContext<CommandSourceStack> context, OrgFakePlayerActions.Action action) throws CommandSyntaxException { return target(context, actor -> OrgFakePlayerActions.set(actor, action)); }
    private static int target(CommandContext<CommandSourceStack> context, java.util.function.Consumer<ServerPlayer> action) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        if (!(target instanceof ServerBot)) throw notFakePlayerException(target);
        return OrgMenuNativeEffects.command(context.getSource(),()->OrgMenuNativeEffects.run(target,()->{
            if(target.isRemoved()||target.isDeadOrDying())return false;action.accept(target);return true;
        }),"The fake-player action could not be assigned");
    }
    private static int gui(CommandContext<CommandSourceStack> context, boolean stone) throws CommandSyntaxException {
        ServerPlayer viewer = context.getSource().getPlayerOrException(); ServerPlayer target = EntityArgument.getPlayer(context, "player");
        if (!(target instanceof ServerBot)) throw notFakePlayerException(target);
        UUID id = target.getUUID();return OrgMenuNativeEffects.command(context.getSource(),()->OrgFakePlayerRecipeMenus.openAsync(viewer,id,stone),"The fake-player recipe menu could not open");
    }
    static com.mojang.brigadier.exceptions.CommandSyntaxException notFakePlayerException(ServerPlayer player) {
        String pattern=OrgRuleTranslations.text("carpet-org-addition.operation.not_fake_player","%s is not a fake player");
        return new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal(String.format(java.util.Locale.ROOT,pattern,player.getGameProfile().name()))).create();
    }
    static void message(CommandSourceStack source, Component text) {
        ServerPlayer viewer = source.getPlayer(); if (viewer == null) source.sendSuccess(() -> text, false);
        else OrgMenuNativeEffects.run(viewer,()->{viewer.sendSystemMessage(text);return null;});
    }
}
