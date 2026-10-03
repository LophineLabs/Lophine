// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Arrays;
import java.util.Map;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

public class CarpetSpawnCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext) {
        LiteralArgumentBuilder<CommandSourceStack> literalargumentbuilder = literal("spawn").
                requires((player) -> CarpetCommandPermissions.canUse(player, fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandSpawn));

        literalargumentbuilder.
                then(literal("list").
                        then(argument("pos", BlockPosArgument.blockPos()).
                                executes((c) -> listSpawns(c.getSource(), BlockPosArgument.getSpawnablePos(c, "pos"))))).
                then(literal("tracking").
                        executes((c) -> printTrackingReport(c.getSource())).
                        then(literal("start").
                                executes((c) -> startTracking(c.getSource(), null)).
                                then(argument("from", BlockPosArgument.blockPos()).
                                        then(argument("to", BlockPosArgument.blockPos()).
                                                executes((c) -> startTracking(
                                                        c.getSource(),
                                                        BoundingBox.fromCorners(
                                                                BlockPosArgument.getSpawnablePos(c, "from"),
                                                                BlockPosArgument.getSpawnablePos(c, "to"))))))).
                        then(literal("stop").
                                executes((c) -> stopTracking(c.getSource()))).
                        then(argument("type", word()).
                                suggests((c, b) -> suggest(Arrays.stream(MobCategory.values()).map(MobCategory::getName), b)).
                                executes((c) -> recentSpawnsForType(c.getSource(), getString(c, "type"))))).
                then(literal("test").
                        executes((c) -> runTest(c.getSource(), 72000, null)).
                        then(argument("ticks", integer(10)).
                                executes((c) -> runTest(
                                        c.getSource(),
                                        getInteger(c, "ticks"),
                                        null)).
                                then(argument("counter", word()).
                                        suggests((c, b) -> suggest(Arrays.stream(DyeColor.values()).map(DyeColor::toString), b)).
                                        executes((c) -> runTest(
                                                c.getSource(),
                                                getInteger(c, "ticks"),
                                                getString(c, "counter")))))).
                then(literal("mocking").
                        then(argument("to do or not to do?", BoolArgumentType.bool()).
                                executes((c) -> toggleMocking(c.getSource(), BoolArgumentType.getBool(c, "to do or not to do?"))))).
                then(literal("rates").
                        executes((c) -> generalMobcaps(c.getSource())).
                        then(literal("reset").
                                executes((c) -> resetSpawnRates(c.getSource()))).
                        then(argument("type", word()).
                                suggests((c, b) -> suggest(Arrays.stream(MobCategory.values()).map(MobCategory::getName), b)).
                                then(argument("rounds", integer(0)).
                                        suggests((c, b) -> suggest(new String[]{"1"}, b)).
                                        executes((c) -> setSpawnRates(
                                                c.getSource(),
                                                getString(c, "type"),
                                                getInteger(c, "rounds")))))).
                then(literal("mobcaps").
                        executes((c) -> generalMobcaps(c.getSource())).
                        then(literal("set").
                                then(argument("cap (hostile)", integer(1, 1400)).
                                        executes((c) -> setMobcaps(c.getSource(), getInteger(c, "cap (hostile)"))))).
                        then(argument("dimension", DimensionArgument.dimension()).
                                executes((c) -> mobcapsForDimension(c.getSource(), DimensionArgument.getDimension(c, "dimension"))))).
                then(literal("entities").
                        executes((c) -> generalMobcaps(c.getSource())).
                        then(argument("type", string()).
                                suggests((c, b) -> suggest(Arrays.stream(MobCategory.values()).map(MobCategory::getName), b)).
                                executes((c) -> listEntitiesOfType(c.getSource(), getString(c, "type"), false)).
                                then(literal("all").executes((c) -> listEntitiesOfType(c.getSource(), getString(c, "type"), true)))));

        dispatcher.register(literalargumentbuilder);
    }

    private static MobCategory getCategory(String name) throws CommandSyntaxException {
        for (var category : MobCategory.values()) if (category.getName().equalsIgnoreCase(name)) return category;
        throw new SimpleCommandExceptionType(net.minecraft.network.chat.Component.literal("Wrong mob type: " + name)).create();
    }

    private static int reportCommand(CommandSourceStack source, java.util.function.Supplier<java.util.concurrent.CompletableFuture<java.util.List<net.minecraft.network.chat.Component>>> report) {
        return OrgCommandNativeEffects.command(source, 1, () -> TisCommandContinuations.then(report.get(), lines ->
                TisCommandContinuations.then(CarpetMessenger.sendAsync(source, lines), ignored -> java.util.concurrent.CompletableFuture.completedFuture(1))));
    }

    private static int globalReport(CommandSourceStack source, java.util.function.Supplier<java.util.List<net.minecraft.network.chat.Component>> report) {
        return reportCommand(source, () -> OrgCommandNativeEffects.global(source.getServer(), report));
    }

    private static int globalChange(CommandSourceStack source, java.util.function.Supplier<net.minecraft.network.chat.Component> change) {
        return OrgCommandNativeEffects.command(source, 1, () -> TisCommandContinuations.then(OrgCommandNativeEffects.global(source.getServer(), change), message ->
                TisCommandContinuations.then(CarpetMessenger.sendAsync(source, java.util.List.of(message)), ignored -> java.util.concurrent.CompletableFuture.completedFuture(1))));
    }

    public static java.util.concurrent.CompletableFuture<java.util.List<net.minecraft.network.chat.Component>> reportAsync(ServerLevel level, BlockPos pos) {
        return OrgCommandNativeEffects.area(level, (pos.getX() - 16) >> 4, (pos.getZ() - 16) >> 4,
                (pos.getX() + 16) >> 4, (pos.getZ() + 16) >> 4, () -> CarpetSpawnProbe.report(pos, level));
    }

    public static int listSpawns(CommandSourceStack source, BlockPos pos) {
        return reportCommand(source, () -> reportAsync(source.getLevel(), pos.immutable()));
    }

    private static int printTrackingReport(CommandSourceStack source) {
        return globalReport(source, CarpetSpawnReporter::report);
    }

    private static int startTracking(CommandSourceStack source, BoundingBox filter) {
        var completion = CarpetAsyncCommandResults.defer(source);
        var actual = OrgMenuNativeEffects.admit(source.getServer(), () -> TisCommandContinuations.then(
                OrgCommandNativeEffects.global(source.getServer(), () -> CarpetSpawnReporter.start(filter)), started ->
                        TisCommandContinuations.then(CarpetMessenger.sendAsync(source, java.util.List.of(CarpetMessenger.c(started
                                ? "gi Spawning tracking started." : "r You are already tracking spawning."))), ignored -> java.util.concurrent.CompletableFuture.completedFuture(started))));
        var delivered = actual.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((started, failure) ->
                completion.complete(failure == null && Boolean.TRUE.equals(started), failure == null && Boolean.TRUE.equals(started) ? 1 : 0)));
        carpet.script.external.ScarpetNativeWork.record(delivered);
        return 1;
    }

    private static int stopTracking(CommandSourceStack source) {
        return globalReport(source, () -> {
            var report = new java.util.ArrayList<>(CarpetSpawnReporter.stop());
            report.add(CarpetMessenger.c("gi Spawning tracking stopped."));
            return java.util.List.copyOf(report);
        });
    }

    private static int recentSpawnsForType(CommandSourceStack source, String category) throws CommandSyntaxException {
        MobCategory type = getCategory(category);
        ServerLevel level = source.getLevel();
        return globalReport(source, () -> CarpetSpawnReporter.recent(level, type));
    }

    private static int runTest(CommandSourceStack source, int ticks, String color) {
        return globalChange(source, () -> {
            CarpetSpawnReporter.restart(null);
            if (color == null) org.leavesmc.leaves.util.HopperCounter.resetAll(source.getServer(), false);
            else {
                var counter = org.leavesmc.leaves.util.HopperCounter.getCounter(DyeColor.byName(color, null));
                if (counter != null) counter.reset(source.getServer());
            }
            source.getServer().tickRateManager().requestGameToSprint(ticks);
            return CarpetMessenger.c("gi Started spawn test for " + ticks + " ticks");
        });
    }

    private static int toggleMocking(CommandSourceStack source, boolean value) {
        return globalChange(source, () -> {
            CarpetSpawnReporter.mockSpawns = value;
            return CarpetMessenger.c(value ? "gi Mob spawns will now be mocked." : "gi Normal mob spawning.");
        });
    }

    public static java.util.List<net.minecraft.network.chat.Component> mobcaps(ServerLevel level) {
        var snapshot = CarpetMobcaps.dimension(level.dimension().identifier().toString());
        var lines = new java.util.ArrayList<net.minecraft.network.chat.Component>();
        lines.add(net.minecraft.network.chat.Component.literal("Mobcaps for " + level.dimension().identifier() + ":"));
        if (snapshot == null) {
            lines.add(net.minecraft.network.chat.Component.literal(" --UNAVAILABLE--"));
            return lines;
        }
        for (var category : MobCategory.values()) {
            if (category == MobCategory.MISC && fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.mobcapsDisplayIgnoreMisc)
                continue;
            lines.add(net.minecraft.network.chat.Component.literal(" " + category.getName() + ": " + snapshot.counts().getOrDefault(category, 0) + " / " + snapshot.limits().getOrDefault(category, 0) + " (" + CarpetSpawnReporter.rounds(category) + " rounds/tick)"));
        }
        return lines;
    }

    private static int generalMobcaps(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        return globalReport(source, () -> mobcaps(level));
    }

    private static int mobcapsForDimension(CommandSourceStack source, ServerLevel level) {
        return globalReport(source, () -> mobcaps(level));
    }

    private static int resetSpawnRates(CommandSourceStack source) {
        return globalChange(source, () -> {
            CarpetSpawnReporter.resetRates();
            return CarpetMessenger.c("gi Spawn rates brought to 1 round per tick for all groups.");
        });
    }

    private static int setSpawnRates(CommandSourceStack source, String category, int rounds) throws CommandSyntaxException {
        MobCategory type = getCategory(category);
        return globalChange(source, () -> {
            CarpetSpawnReporter.setRounds(type, rounds);
            return CarpetMessenger.c("gi " + category + " mobs will now spawn " + rounds + " times per tick");
        });
    }

    private static int setMobcaps(CommandSourceStack source, int hostile) {
        return globalChange(source, () -> {
            CarpetSpawnReporter.setHostileCap(hostile);
            return CarpetMessenger.c("gi Mobcaps for hostile mobs changed to " + hostile + ", other groups will follow");
        });
    }

    public static java.util.concurrent.CompletableFuture<java.util.List<net.minecraft.network.chat.Component>> entityReportAsync(ServerLevel level, MobCategory category, boolean all) {
        return OrgMenuNativeEffects.admit(level.getServer(), () -> TisCommandContinuations.then(
                OrgCommandNativeEffects.global(level.getServer(), () -> carpet.script.external.ScarpetEntityIndex.entities(level)), candidates -> {
                    var jobs = new java.util.ArrayList<java.util.concurrent.CompletableFuture<java.util.Map.Entry<java.util.UUID, net.minecraft.network.chat.Component>>>();
                    for (var entity : candidates)
                        jobs.add(TisCommandContinuations.owned(entity, () -> {
                            if (entity.isRemoved() || entity.level() != level || entity.getType().getCategory() != category)
                                return null;
                            boolean persistent = entity instanceof net.minecraft.world.entity.Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence());
                            if (!all && persistent) return null;
                            var line = CarpetMessenger.c(persistent ? "g  - " : "w  - ", CarpetMessenger.tp(persistent ? "gb" : "wb", entity.blockPosition().immutable()), "w : " + entity.getType().getDescription().getString());
                            return java.util.Map.entry(entity.getUUID(), line);
                        }));
                    var body = java.util.concurrent.CompletableFuture.allOf(jobs.toArray(java.util.concurrent.CompletableFuture[]::new)).thenApply(ignored -> {
                        var lines = new java.util.ArrayList<net.minecraft.network.chat.Component>();
                        lines.add(net.minecraft.network.chat.Component.literal("Loaded entities for " + category.getName() + " category:"));
                        lines.addAll(jobs.stream().map(job -> job.getNow(null)).filter(java.util.Objects::nonNull)
                                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList());
                        if (lines.size() == 1) lines.add(net.minecraft.network.chat.Component.literal(" - Empty."));
                        return java.util.List.copyOf(lines);
                    });
                    carpet.script.external.ScarpetNativeWork.record(body);
                    return body;
                }));
    }

    public static int listEntitiesOfType(CommandSourceStack source, String name, boolean all) throws CommandSyntaxException {
        MobCategory category = getCategory(name);
        ServerLevel level = source.getLevel();
        return reportCommand(source, () -> entityReportAsync(level, category, all));
    }
}
