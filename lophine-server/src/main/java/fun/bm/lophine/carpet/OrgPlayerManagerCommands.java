// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.server.level.ServerPlayer;
import org.leavesmc.leaves.bot.ServerBot;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Full public manager command tree backed by durable profiles and nonblocking actor scheduling.
 */
public final class OrgPlayerManagerCommands {
    private OrgPlayerManagerCommands() {
    }

    @FunctionalInterface
    private interface Work {
        void run(OrgPlayerManager manager) throws Exception;
    }

    private static int io(CommandContext<CommandSourceStack> context, Work work) {
        return ioResult(context, 1, work);
    }

    private static int ioResult(CommandContext<CommandSourceStack> context, int result, Work work) {
        return actual(context, manager -> OrgCommandNativeEffects.file(context.getSource().getServer(), () -> TisCommandContinuations.phase(null, () -> {
            try {
                work.run(manager);
                return result;
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        })).thenCompose(java.util.function.Function.identity()));
    }

    @FunctionalInterface
    private interface AsyncWork {
        CompletableFuture<Integer> run(OrgPlayerManager manager) throws Exception;
    }

    private static int actual(CommandContext<CommandSourceStack> context, AsyncWork work) {
        return OrgCommandNativeEffects.command(context.getSource(), 1, () -> {
            try {
                return work.run(manager(context));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
    }

    private static int snapshot(CommandContext<CommandSourceStack> context, String name, AsyncWork work) {
        ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (player != null && CarpetAsyncCommandResults.hasNativeCause())
            return OrgMenuNativeEffects.snapshotCommand(context.getSource(), player, () -> {
                try {
                    return work.run(manager(context)).thenApply(count -> count != 0);
                } catch (Exception failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }, "The deferred manager snapshot could not finish");
        return actual(context, work);
    }

    private static boolean pass(CommandSourceStack source, String permission) {
        return OrgServerPermissions.allowed(source, permission);
    }

    private static int save(CommandContext<CommandSourceStack> context, String comment, boolean replace) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String name = replace ? name(context) : player(context);
        return snapshot(context, name, manager -> manager.saveAsync(context.getSource(), name, comment, replace));
    }

    private static int spawn(CommandContext<CommandSourceStack> context) {
        return OrgMenuNativeEffects.command(context.getSource(), () -> TisCommandContinuations.then(OrgCommandNativeEffects.file(context.getSource().getServer(), () -> {
            try {
                return manager(context).store.load(name(context));
            } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }), profile -> manager(context).spawn(context.getSource(), name(context), profile, false)), "The managed fake-player spawn did not complete");
    }

    private static int kill(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String name = player(context);
        return OrgMenuNativeEffects.snapshotCommand(context.getSource(), EntityArgument.getPlayer(context, "player"), () -> manager(context).killAsync(context.getSource(), name), "The managed fake-player removal did not complete");
    }

    private static OrgPlayerManager manager(CommandContext<CommandSourceStack> context) {
        return OrgPlayerManager.get(context.getSource().getServer());
    }

    private static String name(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "name");
    }

    private static String player(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        if (!(player instanceof ServerBot))
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(net.minecraft.network.chat.Component.literal("The selected player must be a fake player")).create();
        return player.getScoreboardName();
    }

    private static SuggestionProvider<CommandSourceStack> profiles() {
        return (context, builder) -> CompletableFuture.supplyAsync(() -> {
            try {
                return manager(context).store.names();
            } catch (IOException failure) {
                return List.<String>of();
            }
        }).thenCompose(names -> SharedSuggestionProvider.suggest(names.stream().map(StringArgumentType::escapeIfRequired), builder));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext access) {
        var root = Commands.literal("playerManager").requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.commandPlayerManager));
        root.then(Commands.literal("save").then(Commands.argument("player", EntityArgument.player()).executes(context -> save(context, "", false)).then(Commands.argument("comment", StringArgumentType.string()).executes(context -> save(context, StringArgumentType.getString(context, "comment"), false)))));
        root.then(Commands.literal("spawn").then(Commands.argument("name", StringArgumentType.string()).suggests(profiles()).executes(OrgPlayerManagerCommands::spawn)));
        root.then(Commands.literal("kill").then(Commands.argument("player", EntityArgument.player()).executes(OrgPlayerManagerCommands::kill)));
        var modify = Commands.argument("name", StringArgumentType.string()).suggests(profiles());
        modify.then(Commands.literal("comment").then(Commands.argument("comment", StringArgumentType.string()).executes(context -> io(context, manager -> manager.store.modify(name(context), profile -> {
            profile.addProperty("annotation", StringArgumentType.getString(context, "comment"));
            return profile;
        })))));
        modify.then(Commands.literal("resave").executes(context -> save(context, "", true)));
        modify.then(Commands.literal("autologin").requires(source -> pass(source, "playerManager.autologin")).then(Commands.argument("autologin", BoolArgumentType.bool()).executes(OrgPlayerManagerCommands::autologin)));
        modify.then(startup());
        root.then(Commands.literal("modify").then(modify));
        root.then(Commands.literal("autologin").requires(source -> pass(source, "playerManager.autologin")).then(Commands.argument("name", StringArgumentType.string()).suggests(profiles()).then(Commands.argument("autologin", BoolArgumentType.bool()).executes(OrgPlayerManagerCommands::autologin))));
        var group = Commands.literal("group");
        for (String operation : List.of("add", "remove"))
            group.then(Commands.literal(operation).then(Commands.argument("name", StringArgumentType.string()).suggests(profiles()).then(Commands.argument("group", StringArgumentType.string()).executes(context -> io(context, manager -> manager.store.modify(name(context), profile -> {
                String selected = StringArgumentType.getString(context, "group");
                var previous = profile.has("group") ? profile.getAsJsonArray("group") : new JsonArray();
                JsonArray result = new JsonArray();
                boolean found = false;
                for (var element : previous) {
                    if (element.getAsString().equals(selected)) {
                        found = true;
                        if (operation.equals("remove")) continue;
                    }
                    result.add(element.deepCopy());
                }
                if (operation.equals("add") && (selected.isEmpty() || found) || operation.equals("remove") && !found)
                    throw new IllegalArgumentException("Cannot " + operation + " the selected group");
                if (operation.equals("add")) result.add(selected);
                profile.add("group", result);
                return profile;
            }))))));
        var listGroup = Commands.literal("list");
        listGroup.then(Commands.literal("group").then(Commands.argument("group", StringArgumentType.string()).executes(context -> list(context, StringArgumentType.getString(context, "group"), null, false))));
        listGroup.then(Commands.literal("ungrouped").executes(context -> list(context, null, null, true)));
        listGroup.then(Commands.literal("all").executes(context -> list(context, null, null, false)));
        group.then(listGroup);
        for (String operation : List.of("spawn", "kill"))
            group.then(Commands.literal(operation).then(Commands.argument("group", StringArgumentType.string()).executes(context -> group(context, operation))));
        root.then(group);
        root.then(Commands.literal("list").executes(context -> list(context, null, null, false, true)).then(Commands.argument("filter", StringArgumentType.string()).executes(context -> list(context, null, StringArgumentType.getString(context, "filter"), false))));
        root.then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.string()).suggests(profiles()).executes(context -> io(context, manager -> {
            if (!manager.store.remove(name(context))) throw new IOException("No such saved profile");
            OrgPlayerManager.message(context.getSource(), "Removed " + name(context));
        }))));
        root.then(Commands.literal("reload").executes(context -> io(context, manager -> {
            for (String name : manager.store.names()) manager.store.load(name);
            OrgPlayerManager.message(context.getSource(), "Reloaded saved fake-player profiles");
        })));
        root.then(schedule());
        root.then(safeAfk());
        root.then(batch());
        root.then(Commands.literal("respawn").executes(context -> actual(context, manager -> manager.respawnAsync(context.getSource(), null))).then(Commands.argument("time", StringArgumentType.string()).suggests((context, builder) -> CompletableFuture.supplyAsync(() -> {
            try {
                return manager(context).residentTimes();
            } catch (IOException failure) {
                return List.<String>of();
            }
        }).thenCompose(times -> SharedSuggestionProvider.suggest(times, builder))).executes(context -> actual(context, manager -> manager.respawnAsync(context.getSource(), StringArgumentType.getString(context, "time"))))));
        dispatcher.register(root);
    }

    private static int autologin(CommandContext<CommandSourceStack> context) {
        return io(context, manager -> manager.store.modify(name(context), profile -> {
            profile.addProperty("autologin", BoolArgumentType.getBool(context, "autologin"));
            return profile;
        }));
    }

    private static boolean inGroup(JsonObject profile, String selected) {
        if (!profile.has("group")) return false;
        for (var group : profile.getAsJsonArray("group")) if (group.getAsString().equals(selected)) return true;
        return false;
    }

    private static int list(CommandContext<CommandSourceStack> context, String group, String filter, boolean ungrouped) {
        return list(context, group, filter, ungrouped, false);
    }

    private static int list(CommandContext<CommandSourceStack> context, String group, String filter, boolean ungrouped, boolean overview) {
        return actual(context, manager -> TisCommandContinuations.then(OrgCommandNativeEffects.file(context.getSource().getServer(), () -> {
            try {
                var profiles = new java.util.LinkedHashMap<String, JsonObject>();
                for (String name : manager.store.names()) profiles.put(name, manager.store.load(name));
                return OrgPlayerProfileMessages.list(profiles, group, filter, ungrouped, overview);
            } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }), output -> TisCommandContinuations.feedback(context.getSource(), () -> {
            for (var header : output.headers()) context.getSource().sendSuccess(() -> header, false);
            if (output.pages()) OrgPages.print(context.getSource(), output.rows());
            else for (var row : output.rows()) context.getSource().sendSuccess(() -> row, false);
            return output.count();
        })));
    }

    private static int group(CommandContext<CommandSourceStack> context, String operation) {
        return actual(context, manager -> TisCommandContinuations.then(OrgCommandNativeEffects.file(context.getSource().getServer(), () -> {
            try {
                var profiles = new java.util.LinkedHashMap<String, JsonObject>();
                String selected = StringArgumentType.getString(context, "group");
                for (String name : manager.store.names()) {
                    JsonObject profile = manager.store.load(name);
                    if (inGroup(profile, selected)) profiles.put(name, profile);
                }
                if (profiles.isEmpty()) throw new IllegalArgumentException("The selected group does not exist");
                return profiles;
            } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }), profiles -> {
            CompletableFuture<Integer> count = CompletableFuture.completedFuture(0);
            for (var entry : profiles.entrySet()) {
                String name = entry.getKey();
                if (operation.equals("kill") && !(context.getSource().getServer().getPlayerList().getPlayerByName(name) instanceof ServerBot))
                    continue;
                count = TisCommandContinuations.then(count, before -> (operation.equals("spawn") ? manager.spawn(context.getSource(), name, entry.getValue(), true) : manager.killSilentAsync(context.getSource(), name)).thenApply(success -> before + (success ? 1 : 0)));
            }
            return TisCommandContinuations.then(count, total -> TisCommandContinuations.then(OrgCommandNativeEffects.broadcast(context.getSource().getServer(), OrgPlayerManager.translated(operation.equals("spawn") ? "summon.joined" : "summon.left", "%s fake players " + (operation.equals("spawn") ? "joined" : "left"), total).copy().withStyle(net.minecraft.ChatFormatting.YELLOW)), ignored -> CompletableFuture.completedFuture(total)));
        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> startup() {
        var root = Commands.literal("startup");
        for (String kind : List.of("use", "attack", "kill")) {
            var node = Commands.literal(kind).executes(context -> startup(context, "simple", kind, 1));
            node.then(Commands.argument("delay", TimeArgument.time(1)).executes(context -> startup(context, "simple", kind, IntegerArgumentType.getInteger(context, "delay"))));
            node.then(Commands.literal("clear").executes(context -> startup(context, "simple", kind, -1)));
            root.then(node);
        }
        root.then(Commands.literal("run").then(Commands.argument("command", StringArgumentType.string()).executes(context -> startup(context, "command", StringArgumentType.getString(context, "command"), 1)).then(Commands.argument("delay", TimeArgument.time(1)).executes(context -> startup(context, "command", StringArgumentType.getString(context, "command"), IntegerArgumentType.getInteger(context, "delay")))).then(Commands.literal("clear").executes(context -> startup(context, "command", StringArgumentType.getString(context, "command"), -1)))));
        return root;
    }

    private static int startup(CommandContext<CommandSourceStack> context, String type, String value, int delay) {
        return ioResult(context, delay, manager -> {
            if (type.equals("command") && !manager.allowStartupCommand(context.getSource()))
                throw new IOException("Dedicated-server startup commands require allow_mp_player_startup_cmd and owner permission");
            manager.store.modify(name(context), profile -> {
                JsonArray next = new JsonArray();
                if (profile.has("startup_action")) for (var element : profile.getAsJsonArray("startup_action")) {
                    JsonObject previous = element.getAsJsonObject(), function = previous.getAsJsonObject("function");
                    if (function.get("type").getAsString().equals(type) && function.get("value").getAsString().equals(value))
                        continue;
                    next.add(previous.deepCopy());
                }
                if (delay >= 0) {
                    JsonObject function = new JsonObject();
                    function.addProperty("type", type);
                    function.addProperty("value", value);
                    JsonObject entry = new JsonObject();
                    entry.addProperty("delay", Math.max(1, delay));
                    entry.add("function", function);
                    next.add(entry);
                }
                profile.add("startup_action", next);
                return profile;
            });
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> schedule() {
        var root = Commands.literal("schedule");
        var login = Commands.argument("name", StringArgumentType.string()).suggests(profiles());
        var loginDelay = Commands.argument("delayed", IntegerArgumentType.integer(1));
        var logout = Commands.argument("player", EntityArgument.player());
        var logoutDelay = Commands.argument("delayed", IntegerArgumentType.integer(1));
        for (var entry : java.util.Map.of("t", 1L, "s", 20L, "min", 1200L, "h", 72000L).entrySet()) {
            loginDelay.then(Commands.literal(entry.getKey()).executes(context -> actual(context, manager -> manager.scheduleAsync(context.getSource(), name(context), "login", IntegerArgumentType.getInteger(context, "delayed") * entry.getValue(), 0))));
            logoutDelay.then(Commands.literal(entry.getKey()).executes(context -> {
                String name = player(context);
                return actual(context, manager -> manager.scheduleAsync(context.getSource(), name, "logout", IntegerArgumentType.getInteger(context, "delayed") * entry.getValue(), 0));
            }));
        }
        login.then(loginDelay);
        logout.then(logoutDelay);
        root.then(Commands.literal("login").then(login));
        root.then(Commands.literal("logout").then(logout));
        root.then(Commands.literal("relogin").requires(source -> pass(source, "playerManager.schedule.relogin")).then(Commands.argument("name", StringArgumentType.string()).suggests(profiles()).then(Commands.argument("interval", IntegerArgumentType.integer(1)).executes(context -> snapshot(context, name(context), manager -> manager.reloginAsync(context.getSource(), name(context), IntegerArgumentType.getInteger(context, "interval"))))).then(Commands.literal("stop").executes(context -> actual(context, manager -> manager.cancelAsync(context.getSource(), name(context), "relogin"))))));
        root.then(Commands.literal("cancel").then(Commands.argument("name", StringArgumentType.string()).executes(context -> actual(context, manager -> manager.cancelAsync(context.getSource(), name(context), null)))));
        root.then(Commands.literal("list").executes(context -> actual(context, manager -> manager.scheduleListAsync(context.getSource()))));
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> safeAfk() {
        var root = Commands.literal("safeafk");
        root.then(Commands.literal("set").then(Commands.argument("player", EntityArgument.player()).executes(context -> safe(context, 5, false, false)).then(Commands.argument("threshold", FloatArgumentType.floatArg()).executes(context -> safe(context, FloatArgumentType.getFloat(context, "threshold"), false, false)).then(Commands.argument("save", BoolArgumentType.bool()).executes(context -> safe(context, FloatArgumentType.getFloat(context, "threshold"), BoolArgumentType.getBool(context, "save"), false))))));
        root.then(Commands.literal("cancel").then(Commands.argument("player", EntityArgument.player()).executes(context -> safe(context, -1, false, true)).then(Commands.argument("save", BoolArgumentType.bool()).executes(context -> safe(context, -1, BoolArgumentType.getBool(context, "save"), true)))));
        root.then(Commands.literal("query").then(Commands.argument("player", EntityArgument.player()).executes(context -> {
            String name = player(context);
            return actual(context, manager -> manager.safeQueryAsync(context.getSource(), name));
        })));
        root.then(Commands.literal("list").executes(context -> actual(context, manager -> manager.safeListAsync(context.getSource()))));
        return root;
    }

    private static int safe(CommandContext<CommandSourceStack> context, float threshold, boolean save, boolean cancelled) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String name = player(context);
        return actual(context, manager -> manager.safeAsync(context.getSource(), name, threshold, save).thenApply(result -> cancelled ? 1 : result));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> batch() {
        var root = Commands.literal("batch");
        var end = Commands.argument("end", IntegerArgumentType.integer(1));
        for (String action : List.of("spawn", "trial", "kill", "drop")) {
            var node = Commands.literal(action).executes(context -> actual(context, manager -> manager.batchAsync(context.getSource(), StringArgumentType.getString(context, "prefix"), IntegerArgumentType.getInteger(context, "start"), IntegerArgumentType.getInteger(context, "end"), action, null)));
            if (action.equals("spawn") || action.equals("trial"))
                node.then(Commands.argument("at", Vec3Argument.vec3()).executes(context -> actual(context, manager -> manager.batchAsync(context.getSource(), StringArgumentType.getString(context, "prefix"), IntegerArgumentType.getInteger(context, "start"), IntegerArgumentType.getInteger(context, "end"), action, Vec3Argument.getVec3(context, "at")))));
            end.then(node);
        }
        return root.then(Commands.argument("prefix", StringArgumentType.word()).then(Commands.argument("start", IntegerArgumentType.integer(1)).then(end)));
    }
}
