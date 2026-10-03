// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.AmsNetworkProtocol;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Original AMS management trees; every target player mutation runs on that player's owner.
 */
public final class AmsManagementCommands {
    private static final class Interval {
        final UUID player;
        final int ticks;
        int elapsed;

        Interval(UUID player, int ticks) {
            this.player = player;
            this.ticks = ticks;
        }

        UUID player() {
            return player;
        }

        int ticks() {
            return ticks;
        }
    }

    private static final Map<String, Interval> BROADCASTS = new ConcurrentHashMap<>();
    private static final Map<String, String> WIKIS = Map.ofEntries(
            Map.entry("Carpet-AMS-Addition", "https://carpet.mcams.club/"),
            Map.entry("Carpet-ORG-Addition", "https://github.com/fcsailboat/Carpet-Org-Addition/"),
            Map.entry("Carpet-TIS-Addition", "https://carpet.tis.world/"),
            Map.entry("Carpet-Extra", "https://github.com/gnembon/carpet-extra/"),
            Map.entry("Carpet-Fixes", "https://github.com/fxmorin/carpet-fixes/wiki/"),
            Map.entry("Carpet-TCTC-Addition", "https://github.com/The-Cat-Town-Craft/Carpet-TCTC-Addition/"),
            Map.entry("Carpet-Sky-Addition", "https://github.com/jsorrell/CarpetSkyAdditions/blob/HEAD/README.md/"),
            Map.entry("Carpet-PVP", "https://github.com/TheobaldTheBird/CarpetPVP/"),
            Map.entry("Carpet-Addons-Not-Found", "https://github.com/Gilly7CE/Carpet-Addons-Not-Found/wiki/"),
            Map.entry("Carpet-MCT-Addition", "https://github.com/MCTown/Carpet-MCT-Addition/"),
            Map.entry("Carpet-Extra-Extras", "https://github.com/Thedustbustr/Carpet-Extra-Extras/"),
            Map.entry("Gugle-Carpet-Addition", "https://github.com/Gu-ZT/gugle-carpet-addition/"));

    private AmsManagementCommands() {
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String command, java.util.function.Supplier<String> rule) {
        return Commands.literal(command).requires(source -> CarpetCommandPermissions.canUse(source, rule.get()));
    }

    private static int tell(CommandSourceStack source, String key, Object... args) {
        AmsNativeCommandEffects.reply(source, () -> CarpetMessenger.send(source, java.util.List.of(AmsTranslations.message(source, "command." + key, args))));
        return 1;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext access) {
        var anti = root("customAntiFireItems", () -> GeneralCompatConfig.commandCustomAntiFireItems);
        for (boolean add : new boolean[]{true, false})
            anti.then(Commands.literal(add ? "add" : "remove")
                    .then(Commands.argument("item", ItemArgument.item(access)).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                        var item = ItemArgument.getItem(ctx, "item").item().value();
                        String id = BuiltInRegistries.ITEM.getKey(item).toString();
                        boolean changed = add ? AmsManagementSettings.ANTI_FIRE.add(id) : AmsManagementSettings.ANTI_FIRE.remove(id);
                        AmsManagementSettings.saveSet(ctx.getSource().getServer(), "custom_anti_fire_items", AmsManagementSettings.ANTI_FIRE);
                        return tell(ctx.getSource(), "customAntiFireItems." + (changed ? add ? "add" : "remove" : add ? "already_exists" : "not_found"), item.getName(new net.minecraft.world.item.ItemStack(item)));
                    }))));
        anti.then(Commands.literal("removeAll").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            AmsManagementSettings.ANTI_FIRE.clear();
            AmsManagementSettings.saveSet(ctx.getSource().getServer(), "custom_anti_fire_items", AmsManagementSettings.ANTI_FIRE);
            return tell(ctx.getSource(), "customAntiFireItems.removeAll");
        })));
        anti.then(Commands.literal("list").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> list(ctx.getSource(), "customAntiFireItems", AmsManagementSettings.ANTI_FIRE))));
        anti.then(Commands.literal("help").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> help(ctx.getSource(), "customAntiFireItems.help", "add", "remove", "removeAll", "list"))));
        dispatcher.register(anti);
        var movable = root("customMovableBlock", () -> GeneralCompatConfig.commandCustomMovableBlock);
        for (boolean add : new boolean[]{true, false})
            movable.then(Commands.literal(add ? "add" : "remove")
                    .then(Commands.argument("block", BlockStateArgument.block(access)).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                        var state = BlockStateArgument.getBlock(ctx, "block").getState();
                        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        boolean changed = add ? AmsManagementSettings.MOVABLE.add(id) : AmsManagementSettings.MOVABLE.remove(id);
                        AmsManagementSettings.saveSet(ctx.getSource().getServer(), "custom_movable_block", AmsManagementSettings.MOVABLE);
                        return tell(ctx.getSource(), "customMovableBlock." + (changed ? add ? "add" : "remove" : add ? "already_exists" : "not_found"), state.getBlock().getName());
                    }))));
        movable.then(Commands.literal("removeAll").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            AmsManagementSettings.MOVABLE.clear();
            AmsManagementSettings.saveSet(ctx.getSource().getServer(), "custom_movable_block", AmsManagementSettings.MOVABLE);
            return tell(ctx.getSource(), "customMovableBlock.removeAll");
        })));
        movable.then(Commands.literal("list").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> list(ctx.getSource(), "customMovableBlock", AmsManagementSettings.MOVABLE))));
        movable.then(Commands.literal("help").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> help(ctx.getSource(), "customMovableBlock.help", "set", "remove", "removeAll", "list"))));
        dispatcher.register(movable);
        blocks(dispatcher, access, false);
        blocks(dispatcher, access, true);
        dispatcher.register(root("anvilInteractionDisabled", () -> GeneralCompatConfig.commandAnvilInteractionDisabled)
                .then(Commands.argument("boolean", BoolArgumentType.bool()).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                    AmsManagementSettings.anvilDisabled = BoolArgumentType.getBool(ctx, "boolean");
                    boolean disabled = AmsManagementSettings.anvilDisabled;
                    var source = ctx.getSource();
                    AmsNativeCommandEffects.reply(source, () -> source.sendSuccess(() -> AmsTranslations.message(source, "command.anvilInteractionDisabled." + (disabled ? "disable" : "enable")), true));
                    AmsNativeCommandEffects.effect(() -> AmsUpdateSuppressor.saveForceMode(source.getServer()));
                    return 1;
                }))));
        leaders(dispatcher);
        portal(dispatcher);
        permissions(dispatcher);
        var playerPose = root("playerPose", () -> GeneralCompatConfig.commandSetPlayerPose);
        playerPose.then(Commands.argument("player", EntityArgument.player())
                .then(Commands.literal("set").then(Commands.argument("pose", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(AmsManagementSettings.poseNames(), b))
                        .executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> pose(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "pose"))))))
                .then(Commands.literal("stop").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> pose(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), null)))));
        playerPose.then(Commands.literal("help").executes(ctx -> AmsNativeCommandEffects.command(ctx,
                ignored -> help(ctx.getSource(), "playerPose", "set_help", "stop_help"))));
        dispatcher.register(playerPose);
        dispatcher.register(root("@", () -> GeneralCompatConfig.commandAtSomeOnePlayer)
                .then(Commands.argument("targetPlayer", EntityArgument.player()).then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                            ServerPlayer sender = ctx.getSource().getPlayerOrException(), target = EntityArgument.getPlayer(ctx, "targetPlayer");
                            String text = StringArgumentType.getString(ctx, "text");
                            AmsNativeCommandEffects.receipt(at(ctx.getSource(), sender, target, text));
                            return 1;
                        })))));
        dispatcher.register(root("carpetExtensionModWikiHyperlink", () -> GeneralCompatConfig.commandCarpetExtensionModWikiHyperlink)
                .then(Commands.argument("extensionName", StringArgumentType.string()).suggests((ctx, b) -> SharedSuggestionProvider.suggest(WIKIS.keySet(), b))
                        .executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                            String name = StringArgumentType.getString(ctx, "extensionName"), url = WIKIS.getOrDefault(name, name);
                            Component hover = AmsTranslations.message(ctx.getSource(), "command.carpetExtensionModWikiHyperlink.click_to_jump")
                                    .append(Component.literal(url)).withStyle(ChatFormatting.YELLOW);
                            Component link = Component.literal(url).withStyle(style -> style.withColor(ChatFormatting.GREEN)
                                    .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url))).withHoverEvent(new HoverEvent.ShowText(hover)));
                            AmsNativeCommandEffects.reply(ctx.getSource(), () -> CarpetMessenger.send(ctx.getSource(), java.util.List.of(AmsTranslations.message(ctx.getSource(), "command.carpetExtensionModWikiHyperlink.click_to_jump").withStyle(ChatFormatting.AQUA).append(link))));
                            return 1;
                        }))));
    }

    private static void blocks(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext access, boolean hardness) {
        String command = hardness ? "customBlockHardness" : "customBlockBlastResistance", file = hardness ? "custom_block_hardness" : "custom_block_blast_resistance";
        Map<BlockState, Float> values = hardness ? AmsManagementSettings.HARDNESS : AmsManagementSettings.RESISTANCE;
        var node = root(command, () -> hardness ? GeneralCompatConfig.commandCustomBlockHardness : GeneralCompatConfig.commandCustomBlockBlastResistance);
        node.then(Commands.literal("set").then(Commands.argument("block", BlockStateArgument.block(access))
                .then(Commands.argument("value", FloatArgumentType.floatArg()).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                    var state = BlockStateArgument.getBlock(ctx, "block").getState();
                    float value = FloatArgumentType.getFloat(ctx, "value");
                    Float previous = values.put(state, value);
                    AmsManagementSettings.saveBlocks(ctx.getSource().getServer(), file, values);
                    if (hardness)
                        AmsNativeCommandEffects.effect(() -> AmsNativeCommandEffects.global(ctx.getSource().getServer(), () -> {
                            AmsNetworkProtocol.syncHardness();
                            return (Void) null;
                        }));
                    return previous == null ? tell(ctx.getSource(), command + ".set", state.getBlock().getName(), value)
                            : tell(ctx.getSource(), command + ".modify_set", state.getBlock().getName(), previous, state.getBlock().getName(), value);
                })))));
        node.then(Commands.literal("remove").then(Commands.argument("block", BlockStateArgument.block(access)).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            var state = BlockStateArgument.getBlock(ctx, "block").getState();
            Float removed = values.remove(state);
            boolean changed = removed != null;
            if (!changed) {
                tell(ctx.getSource(), command + ".not_found", state.getBlock().getName());
                return 0;
            }
            AmsManagementSettings.saveBlocks(ctx.getSource().getServer(), file, values);
            if (hardness)
                AmsNativeCommandEffects.effect(() -> AmsNativeCommandEffects.global(ctx.getSource().getServer(), () -> {
                    AmsNetworkProtocol.syncHardness();
                    return (Void) null;
                }));
            return tell(ctx.getSource(), command + ".remove", state.getBlock().getName(), removed);
        }))));
        node.then(Commands.literal("removeAll").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            values.clear();
            AmsManagementSettings.saveBlocks(ctx.getSource().getServer(), file, values);
            if (hardness)
                AmsNativeCommandEffects.effect(() -> AmsNativeCommandEffects.global(ctx.getSource().getServer(), () -> {
                    AmsNetworkProtocol.syncHardness();
                    return (Void) null;
                }));
            return tell(ctx.getSource(), command + ".removeAll");
        })));
        node.then(Commands.literal("list").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            tell(ctx.getSource(), command + ".list");
            values.entrySet().stream().sorted(java.util.Comparator.comparing(e -> e.getKey().toString())).forEach(e -> AmsNativeCommandEffects.reply(ctx.getSource(), () -> CarpetMessenger.send(ctx.getSource(), java.util.List.of(Component.literal(e.getKey() + ": " + e.getValue())))));
            return 1;
        })));
        node.then(Commands.literal("help").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> hardness
                ? help(ctx.getSource(), command + ".help", "set", "remove", "removeAll", "list", "get_default_hardness")
                : help(ctx.getSource(), command + ".help", "set", "remove", "removeAll", "list"))));
        if (hardness)
            node.then(Commands.literal("getDefaultHardness").then(Commands.argument("block", BlockStateArgument.block(access)).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                var state = BlockStateArgument.getBlock(ctx, "block").getState();
                return tell(ctx.getSource(), command + ".default_hardness", state.getBlock().getName(), state.getBlock().defaultDestroyTime());
            }))));
        dispatcher.register(node);
    }

    private static int list(CommandSourceStack source, String command, Set<String> values) {
        tell(source, command + ".list");
        values.stream().sorted().forEach(value -> AmsNativeCommandEffects.reply(source, () -> CarpetMessenger.send(source, java.util.List.of(Component.literal(value)))));
        return 1;
    }

    private static int help(CommandSourceStack source, String command, String... actions) {
        return help(source, true, command, actions);
    }

    private static int helpWithoutFinalBreak(CommandSourceStack source, String command, String... actions) {
        return help(source, false, command, actions);
    }

    private static int help(CommandSourceStack source, boolean finalBreak, String command, String... actions) {
        var message = Component.empty();
        for (int i = 0; i < actions.length; i++) {
            message.append(AmsTranslations.message(source, "command." + command + "." + actions[i]));
            if (finalBreak || i + 1 < actions.length) message.append("\n");
        }
        message.withStyle(ChatFormatting.GRAY);
        AmsNativeCommandEffects.reply(source, () -> CarpetMessenger.send(source, java.util.List.of(message)));
        return 1;
    }

    private static int pose(CommandSourceStack source, ServerPlayer target, String pose) {
        var actual = AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target, () -> {
            if (pose == null) AmsManagementSettings.POSES.remove(target.getUUID());
            else {
                AmsManagementSettings.POSES.put(target.getUUID(), pose);
                target.setShiftKeyDown(true);
            }
            return target.getUUID();
        }), id -> AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(source.getServer(), () -> {
            AmsNetworkProtocol.syncPoses(id);
            return (Void) null;
        }), ignored ->
                AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target, () -> {
                    target.setShiftKeyDown(true);
                    return (Void) null;
                }), unused ->
                        AmsNativeCommandEffects.then(AmsNativeCommandEffects.pause(source.getServer(), 100L), done -> AmsNativeCommandEffects.owned(target, () -> {
                            target.setShiftKeyDown(false);
                            return (Void) null;
                        })))));
        AmsNativeCommandEffects.receipt(actual);
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<Void> at(CommandSourceStack source, ServerPlayer sender, ServerPlayer target, String text) {
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(sender, () -> sender.getGameProfile().name()), name ->
                AmsNativeCommandEffects.then(AmsNativeCommandEffects.source(source, () -> AmsTranslations.message(source, "command.at.title", name).withStyle(ChatFormatting.AQUA)), title ->
                        AmsNativeCommandEffects.then(AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target, () -> AmsNativeCommandEffects.packet(target, new ClientboundSetTitleTextPacket(AmsTranslations.translate(title, target)))), packet -> packet), ignored ->
                                AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(source.getServer(), () -> {
                                    CarpetMessenger.print_server_message(source.getServer(), Component.literal("<" + name + "> " + text));
                                    return (Void) null;
                                }), unused ->
                                        AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target, () -> {
                                            target.level().playSound(null, target.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1, 1);
                                            return target.getGameProfile().name();
                                        }), targetName ->
                                                AmsNativeCommandEffects.global(source.getServer(), () -> {
                                                    CarpetMessenger.print_server_message(source.getServer(), Component.literal(name + " @ " + targetName).withStyle(ChatFormatting.GRAY));
                                                    return (Void) null;
                                                }))))));
    }

    private static void portal(CommandDispatcher<CommandSourceStack> dispatcher) {
        var node = root("playerNoNetherPortalTeleport", () -> GeneralCompatConfig.commandPlayerNoNetherPortalTeleport);
        node.then(Commands.literal("globalMode").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> tell(ctx.getSource(), "playerNoNetherPortalTeleport.globalMode_state", AmsManagementSettings.noPortalGlobal)))
                .then(Commands.argument("boolean", BoolArgumentType.bool()).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                    AmsManagementSettings.noPortalGlobal = BoolArgumentType.getBool(ctx, "boolean");
                    return tell(ctx.getSource(), "playerNoNetherPortalTeleport.globalMode_" + (AmsManagementSettings.noPortalGlobal ? "enable" : "disable"));
                }))));
        for (boolean add : new boolean[]{true, false})
            node.then(Commands.literal(add ? "add" : "remove").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                boolean changed = add ? AmsManagementSettings.NO_PORTAL.add(target.getUUID()) : AmsManagementSettings.NO_PORTAL.remove(target.getUUID());
                return tell(ctx.getSource(), "playerNoNetherPortalTeleport." + (changed ? add ? "add_success" : "remove_success" : add ? "add_fail" : "remove_fail"), target.getScoreboardName());
            }))));
        node.then(Commands.literal("clear").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            AmsManagementSettings.NO_PORTAL.clear();
            return tell(ctx.getSource(), "playerNoNetherPortalTeleport.clear_success");
        })));
        node.then(Commands.literal("list").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            tell(ctx.getSource(), "playerNoNetherPortalTeleport.list_title");
            AmsManagementSettings.NO_PORTAL.stream().sorted().forEach(id -> AmsNativeCommandEffects.reply(ctx.getSource(), () -> CarpetMessenger.send(ctx.getSource(), java.util.List.of(Component.literal(id.toString())))));
            return 1;
        })));
        node.then(Commands.literal("help").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> helpWithoutFinalBreak(ctx.getSource(), "playerNoNetherPortalTeleport.help", "globalMode", "globalModeState", "add", "remove", "clear", "list"))));
        dispatcher.register(node);
    }

    private static void leaders(CommandDispatcher<CommandSourceStack> dispatcher) {
        var node = root("leader", () -> GeneralCompatConfig.commandPlayerLeader);
        for (boolean add : new boolean[]{true, false})
            node.then(Commands.literal(add ? "add" : "remove").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                AmsNativeCommandEffects.receipt(leader(ctx.getSource(), target, add));
                return 1;
            }))));
        node.then(Commands.literal("removeAll").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            AmsNativeCommandEffects.receipt(removeLeaders(ctx.getSource()));
            return 1;
        })));
        node.then(Commands.literal("list").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            tell(ctx.getSource(), "leader.list_title");
            AmsManagementSettings.LEADERS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> AmsNativeCommandEffects.reply(ctx.getSource(), () -> CarpetMessenger.send(ctx.getSource(), java.util.List.of(Component.literal(e.getKey() + " " + e.getValue())))));
            return 1;
        })));
        node.then(Commands.literal("broadcastLeaderPos").then(Commands.argument("player", EntityArgument.player()).then(Commands.literal("interval")
                .then(Commands.argument("interval", IntegerArgumentType.integer()).suggests((ctx, b) -> SharedSuggestionProvider.suggest(java.util.List.of("20", "40", "80", "160", "320", "640", "-1024"), b)).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                    int ticks = IntegerArgumentType.getInteger(ctx, "interval");
                    if (!AmsManagementSettings.LEADERS.containsKey(target.getScoreboardName())) {
                        tell(ctx.getSource(), "leader.is_not_leader", target.getScoreboardName());
                        return 0;
                    }
                    BROADCASTS.put(target.getScoreboardName(), new Interval(target.getUUID(), ticks));
                    AmsNativeCommandEffects.receipt(broadcast(target));
                    return 1;
                }))))));
        node.then(Commands.literal("help").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> help(ctx.getSource(), "leader.help", "add", "remove", "removeAll", "broadcast_leader_pos", "list"))));
        dispatcher.register(node);
    }

    private static java.util.concurrent.CompletableFuture<Void> saveLeaders(MinecraftServer server) {
        return AmsManagementSettings.saveLeaders(server);
    }

    public static void onJoin(ServerPlayer player) {
        if (!AmsManagementSettings.enabled(GeneralCompatConfig.commandPlayerLeader)) return;
        if (AmsManagementSettings.LEADERS.containsValue(player.getUUID()))
            player.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
        else player.removeEffect(MobEffects.GLOWING);
    }

    public static void onRespawn(ServerPlayer player, boolean alive) {
        if (!alive && AmsManagementSettings.enabled(GeneralCompatConfig.commandPlayerLeader) && AmsManagementSettings.LEADERS.containsValue(player.getUUID()))
            player.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
    }

    public static void tick(MinecraftServer server, long tick) {
        if (!AmsManagementSettings.enabled(GeneralCompatConfig.commandPlayerLeader)) return;
        BROADCASTS.forEach((name, value) -> {
            if (value.ticks() < 0 || !AmsManagementSettings.LEADERS.containsValue(value.player())) {
                BROADCASTS.remove(name, value);
                return;
            }
            if (++value.elapsed >= value.ticks()) {
                value.elapsed = 0;
                ServerPlayer target = server.getPlayerList().getPlayer(value.player());
                if (target != null) broadcast(target);
            }
        });
    }

    private static java.util.concurrent.CompletableFuture<Void> broadcast(ServerPlayer target) {
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target, () -> {
            if (!target.isAlive() || !AmsManagementSettings.LEADERS.containsValue(target.getUUID()) || !AmsManagementSettings.LEADERS.containsKey(target.getScoreboardName()))
                return null;
            var pos = target.blockPosition();
            return Component.literal(target.getScoreboardName() + " [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "] " + target.level().dimension().identifier());
        }), location -> location == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : AmsNativeCommandEffects.global(target.carpetSpawnServer(), () -> {
            CarpetMessenger.print_server_message(target.carpetSpawnServer(), location);
            return (Void) null;
        }));
    }

    private record LeaderPlayer(String name, UUID id, boolean present) {
    }

    private static java.util.concurrent.CompletableFuture<Void> leader(CommandSourceStack source, ServerPlayer target, boolean add) {
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target, () -> new LeaderPlayer(target.getScoreboardName(), target.getUUID(), AmsManagementSettings.LEADERS.containsValue(target.getUUID()))), read -> {
            if (add == read.present())
                return leaderMessage(source, add ? "is_already_leader" : "is_not_leader", read.name());
            return AmsNativeCommandEffects.then(AmsNativeCommandEffects.owned(target, () -> {
                if (add) target.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
                else target.removeEffect(MobEffects.GLOWING);
                return (Void) null;
            }), ignored ->
                    AmsNativeCommandEffects.then(leaderMessage(source, add ? "add" : "remove", read.name()), message ->
                            AmsNativeCommandEffects.global(source.getServer(), () -> {
                                if (add) AmsManagementSettings.LEADERS.put(read.name(), read.id());
                                else AmsManagementSettings.LEADERS.remove(read.name(), read.id());
                                return saveLeaders(source.getServer());
                            }).thenCompose(value -> value)));
        });
    }

    private static java.util.concurrent.CompletableFuture<Void> leaderMessage(CommandSourceStack source, String key, Object... args) {
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.source(source, () -> AmsTranslations.message(source, "command.leader." + key, args)), message ->
                AmsNativeCommandEffects.global(source.getServer(), () -> {
                    CarpetMessenger.print_server_message(source.getServer(), message);
                    return (Void) null;
                }));
    }

    private static java.util.concurrent.CompletableFuture<Void> removeLeaders(CommandSourceStack source) {
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(source.getServer(), () -> List.copyOf(AmsManagementSettings.LEADERS.entrySet())), all -> removeLeaderNext(source, all.iterator()));
    }

    private static java.util.concurrent.CompletableFuture<Void> removeLeaderNext(CommandSourceStack source, java.util.Iterator<Map.Entry<String, UUID>> all) {
        if (!all.hasNext())
            return AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(source.getServer(), () -> {
                AmsManagementSettings.LEADERS.clear();
                return saveLeaders(source.getServer());
            }).thenCompose(value -> value), ignored ->
                    AmsNativeCommandEffects.source(source, () -> {
                        tell(source, "leader.removeAll");
                        return (Void) null;
                    }));
        var entry = all.next();
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(source.getServer(), () -> source.getServer().getPlayerList().getPlayer(entry.getValue())), target ->
                AmsNativeCommandEffects.then(target == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : AmsNativeCommandEffects.owned(target, () -> {
                    target.removeEffect(MobEffects.GLOWING);
                    return (Void) null;
                }), ignored ->
                        AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(source.getServer(), () -> {
                            AmsManagementSettings.LEADERS.remove(entry.getKey(), entry.getValue());
                            return (Void) null;
                        }), unused -> removeLeaderNext(source, all))));
    }

    private static void permissions(CommandDispatcher<CommandSourceStack> dispatcher) {
        var node = root("customCommandPermissionLevel", () -> GeneralCompatConfig.commandCustomCommandPermissionLevel);
        node.then(Commands.literal("set").then(Commands.argument("command", StringArgumentType.string())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(dispatcher.getRoot().getChildren().stream().map(com.mojang.brigadier.tree.CommandNode::getName).toList(), b))
                .then(Commands.argument("level", IntegerArgumentType.integer()).suggests((ctx, b) -> SharedSuggestionProvider.suggest(java.util.List.of("0", "1", "2", "3", "4"), b)).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
                    String name = StringArgumentType.getString(ctx, "command");
                    if (name.equals("customCommandPermissionLevel")) {
                        tell(ctx.getSource(), "customCommandPermissionLevel.cant_modify_self");
                        return 0;
                    }
                    int level = IntegerArgumentType.getInteger(ctx, "level");
                    Integer previous = AmsManagementSettings.PERMISSIONS.put(name, level);
                    savePermissions(ctx.getSource().getServer());
                    AmsNativeCommandEffects.effect(() -> AmsNativeCommandEffects.global(ctx.getSource().getServer(), () -> {
                        AmsCommandPermissionLevels.refresh(dispatcher, ctx.getSource().getServer());
                        return (Void) null;
                    }));
                    return previous == null ? tell(ctx.getSource(), "customCommandPermissionLevel.set", name, level) : tell(ctx.getSource(), "customCommandPermissionLevel.modify_set", name, previous, level);
                })))));
        node.then(Commands.literal("remove").then(Commands.argument("command", StringArgumentType.string()).executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            String name = StringArgumentType.getString(ctx, "command");
            boolean changed = AmsManagementSettings.PERMISSIONS.remove(name) != null;
            savePermissions(ctx.getSource().getServer());
            AmsNativeCommandEffects.effect(() -> AmsNativeCommandEffects.global(ctx.getSource().getServer(), () -> {
                AmsCommandPermissionLevels.refresh(dispatcher, ctx.getSource().getServer());
                return (Void) null;
            }));
            return tell(ctx.getSource(), "customCommandPermissionLevel." + (changed ? "remove" : "not_found"), name);
        }))));
        node.then(Commands.literal("removeAll").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            AmsManagementSettings.PERMISSIONS.clear();
            savePermissions(ctx.getSource().getServer());
            AmsNativeCommandEffects.effect(() -> AmsNativeCommandEffects.global(ctx.getSource().getServer(), () -> {
                AmsCommandPermissionLevels.refresh(dispatcher, ctx.getSource().getServer());
                return (Void) null;
            }));
            return tell(ctx.getSource(), "customCommandPermissionLevel.removeAll");
        })));
        node.then(Commands.literal("refresh").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            AmsNativeCommandEffects.effect(() -> AmsNativeCommandEffects.global(ctx.getSource().getServer(), () -> {
                AmsCommandPermissionLevels.refresh(dispatcher, ctx.getSource().getServer());
                return (Void) null;
            }));
            return tell(ctx.getSource(), "commandHelper.refresh_cmd_tree");
        })));
        node.then(Commands.literal("list").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> {
            tell(ctx.getSource(), "customCommandPermissionLevel.list_title");
            AmsManagementSettings.PERMISSIONS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> AmsNativeCommandEffects.reply(ctx.getSource(), () -> CarpetMessenger.send(ctx.getSource(), java.util.List.of(Component.literal(e.getKey() + ": " + e.getValue())))));
            return 1;
        })));
        node.then(Commands.literal("help").executes(ctx -> AmsNativeCommandEffects.command(ctx, ignored -> help(ctx.getSource(), "customCommandPermissionLevel.help", "set", "remove", "removeAll", "refresh", "list"))));
        dispatcher.register(node);
    }

    private static java.util.concurrent.CompletableFuture<Void> savePermissions(MinecraftServer server) {
        return AmsManagementSettings.savePermissions(server);
    }

    public static void reset() {
        BROADCASTS.clear();
    }
}
