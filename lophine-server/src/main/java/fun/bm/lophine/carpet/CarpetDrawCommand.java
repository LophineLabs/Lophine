// SPDX-License-Identifier: LGPL-3.0-only
// Original Carpet geometry; placement is dispatched to each chunk owner.
package fun.bm.lophine.carpet;

import com.google.common.collect.Lists;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

public class CarpetDrawCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext context) {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("draw").
                requires((player) -> CarpetCommandPermissions.canUse(player, fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandDraw)).
                then(literal("sphere").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(drawShape(c -> CarpetDrawCommand.drawSphere(c, false), context))))).
                then(literal("ball").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(drawShape(c -> CarpetDrawCommand.drawSphere(c, true), context))))).
                then(literal("diamond").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(drawShape(c -> CarpetDrawCommand.drawDiamond(c, true), context))))).
                then(literal("pyramid").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(argument("height", IntegerArgumentType.integer(1)).
                                                then(argument("pointing", StringArgumentType.word()).suggests((c, b) -> suggest(new String[]{"up", "down"}, b)).
                                                        then(argument("orientation", StringArgumentType.word()).suggests((c, b) -> suggest(new String[]{"y", "x", "z"}, b)).
                                                                then(drawShape(c -> CarpetDrawCommand.drawPyramid(c, "square", true), context)))))))).
                then(literal("cone").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(argument("height", IntegerArgumentType.integer(1)).
                                                then(argument("pointing", StringArgumentType.word()).suggests((c, b) -> suggest(new String[]{"up", "down"}, b)).
                                                        then(argument("orientation", StringArgumentType.word()).suggests((c, b) -> suggest(new String[]{"y", "x", "z"}, b))
                                                                .then(drawShape(c -> CarpetDrawCommand.drawPyramid(c, "circle", true), context)))))))).
                then(literal("cylinder").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(argument("height", IntegerArgumentType.integer(1)).
                                                then(argument("orientation", StringArgumentType.word()).suggests((c, b) -> suggest(new String[]{"y", "x", "z"}, b))
                                                        .then(drawShape(c -> CarpetDrawCommand.drawPrism(c, "circle"), context))))))).
                then(literal("cuboid").
                        then(argument("center", BlockPosArgument.blockPos()).
                                then(argument("radius", IntegerArgumentType.integer(1)).
                                        then(argument("height", IntegerArgumentType.integer(1)).
                                                then(argument("orientation", StringArgumentType.word()).suggests((c, b) -> suggest(new String[]{"y", "x", "z"}, b))
                                                        .then(drawShape(c -> CarpetDrawCommand.drawPrism(c, "square"), context)))))));
        dispatcher.register(command);
    }

    private record Plan(CarpetAsyncCommandResults.Completion completion,
                        java.util.concurrent.atomic.AtomicBoolean dispatched) {
    }

    private static final ThreadLocal<Plan> PLANNING = new ThreadLocal<>();

    private static int enqueue(CommandContext<CommandSourceStack> context, Command<CommandSourceStack> draw) {
        var plan = new Plan(CarpetAsyncCommandResults.defer(context.getSource()), new java.util.concurrent.atomic.AtomicBoolean());
        Thread.ofVirtual().name("Carpet-draw-plan").start(() -> {
            PLANNING.set(plan);
            try {
                int result = draw.run(context);
                if (!plan.dispatched().get()) plan.completion().complete(result != 0, result);
            } catch (Throwable failure) {
                CarpetMessenger.m(context.getSource(), "r Drawing failed: " + failure.getMessage());
                plan.completion().complete(false, 0);
            } finally {
                PLANNING.remove();
            }
        });
        return 1;
    }

    private static void apply(CommandSourceStack source, BlockInput block, Predicate<BlockInWorld> replacement, List<BlockPos> positions) {
        var world = source.getLevel();
        Map<Long, List<BlockPos>> chunks = new java.util.LinkedHashMap<>();
        for (var pos : positions)
            if (world.isInWorldBounds(pos))
                chunks.computeIfAbsent(net.minecraft.world.level.ChunkPos.pack(pos), ignored -> new java.util.ArrayList<>()).add(pos);
        Plan plan = PLANNING.get();
        if (plan == null) throw new IllegalStateException("Drawing must execute in its planning invocation");
        plan.dispatched().set(true);
        new Batch(source, block, replacement, new java.util.ArrayDeque<>(chunks.entrySet()), plan.completion()).pump();
    }

    private static final class Batch {
        final CommandSourceStack source;
        final BlockInput block;
        final Predicate<BlockInWorld> replacement;
        final java.util.ArrayDeque<Map.Entry<Long, List<BlockPos>>> chunks;
        final boolean updates = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fillUpdates;
        final CarpetAsyncCommandResults.Completion completion;
        boolean finished;
        int active, affected;
        Throwable failure;

        Batch(CommandSourceStack source, BlockInput block, Predicate<BlockInWorld> replacement, java.util.ArrayDeque<Map.Entry<Long, List<BlockPos>>> chunks, CarpetAsyncCommandResults.Completion completion) {
            this.source = source;
            this.block = block;
            this.replacement = replacement;
            this.chunks = chunks;
            this.completion = completion;
        }

        synchronized void pump() {
            while (active < 5 && !chunks.isEmpty()) {
                var entry = chunks.removeFirst();
                active++;
                int x = net.minecraft.world.level.ChunkPos.getX(entry.getKey()), z = net.minecraft.world.level.ChunkPos.getZ(entry.getKey());
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(source.getLevel(), x, z, () -> {
                    int placed = 0;
                    Throwable failed = null;
                    var world = source.getLevel();
                    var changed = new java.util.ArrayList<BlockPos>();
                    try {
                        world.getChunk(x, z);
                        if (!updates) InteractionUpdateHelper.beginSuppressedUpdates();
                        try {
                            for (var pos : entry.getValue()) {
                                if (replacement != null && !replacement.test(new BlockInWorld(world, pos, true)))
                                    continue;
                                var entity = world.getBlockEntity(pos);
                                if (entity instanceof Container container) container.clearContent();
                                if (block.place(world, pos, 2)) {
                                    changed.add(pos);
                                    placed++;
                                }
                            }
                        } finally {
                            if (!updates) InteractionUpdateHelper.endSuppressedUpdates();
                        }
                        if (updates)
                            for (var pos : changed) world.updateNeighborsAt(pos, world.getBlockState(pos).getBlock());
                    } catch (Throwable problem) {
                        failed = problem;
                    }
                    synchronized (this) {
                        affected += placed;
                        active--;
                        if (failed != null && failure == null) {
                            failure = failed;
                            chunks.clear();
                        }
                        pump();
                    }
                });
            }
            if (active == 0 && chunks.isEmpty() && !finished) {
                finished = true;
                CarpetMessenger.m(source, failure == null ? "gi Filled " + affected + " blocks" : "r Drawing stopped after " + affected + " blocks: " + failure.getMessage());
                completion.complete(failure == null && affected > 0, failure == null ? affected : 0);
            }
        }
    }

    @FunctionalInterface
    private interface ArgumentExtractor<T> {
        T apply(final CommandContext<CommandSourceStack> ctx, final String argName) throws CommandSyntaxException;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, BlockInput>
    drawShape(Command<CommandSourceStack> drawer, CommandBuildContext commandBuildContext) {
        return argument("block", BlockStateArgument.block(commandBuildContext)).
                executes(drawer)
                .then(literal("replace")
                        .then(argument("filter", BlockPredicateArgument.blockPredicate(commandBuildContext))
                                .executes(c -> enqueue(c, drawer))));
    }

    private static class ErrorHandled extends RuntimeException {
    }

    private static <T> T getArg(CommandContext<CommandSourceStack> ctx, ArgumentExtractor<T> extract, String hwat) throws CommandSyntaxException {
        return getArg(ctx, extract, hwat, false);
    }

    private static <T> T getArg(CommandContext<CommandSourceStack> ctx, ArgumentExtractor<T> extract, String hwat, boolean optional) throws CommandSyntaxException {
        T arg = null;
        try {
            arg = extract.apply(ctx, hwat);
        } catch (IllegalArgumentException e) {
            if (optional) return null;
            CarpetMessenger.m(ctx.getSource(), "rb Missing " + hwat);
            throw new ErrorHandled();
        }
        return arg;
    }

    private static double lengthSq(double x, double y, double z) {
        return (x * x) + (y * y) + (z * z);
    }

    private static int setBlock(
            ServerLevel world, BlockPos.MutableBlockPos mbpos, int x, int y, int z,
            BlockInput block, Predicate<BlockInWorld> replacement,
            List<BlockPos> list
    ) {
        mbpos.set(x, y, z);
        list.add(mbpos.immutable());
        return 1;
    }

    private static int drawSphere(CommandContext<CommandSourceStack> ctx, boolean solid) throws CommandSyntaxException {
        BlockPos pos;
        int radius;
        BlockInput block;
        Predicate<BlockInWorld> replacement;
        try {
            pos = getArg(ctx, BlockPosArgument::getSpawnablePos, "center");
            radius = getArg(ctx, IntegerArgumentType::getInteger, "radius");
            block = getArg(ctx, BlockStateArgument::getBlock, "block");
            replacement = getArg(ctx, BlockPredicateArgument::getBlockPredicate, "filter", true);
        } catch (ErrorHandled ignored) {
            return 0;
        }

        int affected = 0;
        ServerLevel world = ctx.getSource().getLevel();

        double radiusX = radius + 0.5;
        double radiusY = radius + 0.5;
        double radiusZ = radius + 0.5;

        final double invRadiusX = 1 / radiusX;
        final double invRadiusY = 1 / radiusY;
        final double invRadiusZ = 1 / radiusZ;

        final int ceilRadiusX = (int) Math.ceil(radiusX);
        final int ceilRadiusY = (int) Math.ceil(radiusY);
        final int ceilRadiusZ = (int) Math.ceil(radiusZ);

        BlockPos.MutableBlockPos mbpos = pos.mutable();
        List<BlockPos> list = Lists.newArrayList();

        double nextXn = 0;

        forX:
        for (int x = 0; x <= ceilRadiusX; ++x) {
            final double xn = nextXn;
            nextXn = (x + 1) * invRadiusX;
            double nextYn = 0;
            forY:
            for (int y = 0; y <= ceilRadiusY; ++y) {
                final double yn = nextYn;
                nextYn = (y + 1) * invRadiusY;
                double nextZn = 0;
                forZ:
                for (int z = 0; z <= ceilRadiusZ; ++z) {
                    final double zn = nextZn;
                    nextZn = (z + 1) * invRadiusZ;

                    double distanceSq = lengthSq(xn, yn, zn);
                    if (distanceSq > 1) {
                        if (z == 0) {
                            if (y == 0) {
                                break forX;
                            }
                            break forY;
                        }
                        break forZ;
                    }

                    if (!solid && lengthSq(nextXn, yn, zn) <= 1 && lengthSq(xn, nextYn, zn) <= 1 && lengthSq(xn, yn, nextZn) <= 1) {
                        continue;
                    }

                    for (int xmod = -1; xmod < 2; xmod += 2) {
                        for (int ymod = -1; ymod < 2; ymod += 2) {
                            for (int zmod = -1; zmod < 2; zmod += 2) {
                                affected += setBlock(world, mbpos,
                                        pos.getX() + xmod * x, pos.getY() + ymod * y, pos.getZ() + zmod * z,
                                        block, replacement, list
                                );
                            }
                        }
                    }
                }
            }
        }

        apply(ctx.getSource(), block, replacement, list);
        return affected;
    }

    private static int drawDiamond(CommandContext<CommandSourceStack> ctx, boolean solid) throws CommandSyntaxException {
        BlockPos pos;
        int radius;
        BlockInput block;
        Predicate<BlockInWorld> replacement;
        try {
            pos = getArg(ctx, BlockPosArgument::getSpawnablePos, "center");
            radius = getArg(ctx, IntegerArgumentType::getInteger, "radius");
            block = getArg(ctx, BlockStateArgument::getBlock, "block");
            replacement = getArg(ctx, BlockPredicateArgument::getBlockPredicate, "filter", true);
        } catch (ErrorHandled ignored) {
            return 0;
        }

        CommandSourceStack source = ctx.getSource();

        int affected = 0;

        BlockPos.MutableBlockPos mbpos = pos.mutable();
        List<BlockPos> list = Lists.newArrayList();

        ServerLevel world = source.getLevel();


        for (int r = 0; r < radius; ++r) {
            int y = r - radius + 1;
            for (int x = -r; x <= r; ++x) {
                int z = r - Math.abs(x);

                affected += setBlock(world, mbpos, pos.getX() + x, pos.getY() - y, pos.getZ() + z, block, replacement, list);
                affected += setBlock(world, mbpos, pos.getX() + x, pos.getY() - y, pos.getZ() - z, block, replacement, list);
                affected += setBlock(world, mbpos, pos.getX() + x, pos.getY() + y, pos.getZ() + z, block, replacement, list);
                affected += setBlock(world, mbpos, pos.getX() + x, pos.getY() + y, pos.getZ() - z, block, replacement, list);
            }
        }


        apply(source, block, replacement, list);

        return affected;
    }

    private static int fillFlat(
            ServerLevel world, BlockPos pos, int offset, double dr, boolean rectangle, String orientation,
            BlockInput block, Predicate<BlockInWorld> replacement,
            List<BlockPos> list, BlockPos.MutableBlockPos mbpos
    ) {
        int successes = 0;
        int r = Mth.floor(dr);
        double drsq = dr * dr;
        if (orientation.equalsIgnoreCase("x")) {
            for (int a = -r; a <= r; ++a)
                for (int b = -r; b <= r; ++b)
                    if (rectangle || a * a + b * b <= drsq) {
                        successes += setBlock(
                                world, mbpos, pos.getX() + offset, pos.getY() + a, pos.getZ() + b,
                                block, replacement, list
                        );
                    }
            return successes;
        }
        if (orientation.equalsIgnoreCase("y")) {
            for (int a = -r; a <= r; ++a)
                for (int b = -r; b <= r; ++b)
                    if (rectangle || a * a + b * b <= drsq) {
                        successes += setBlock(
                                world, mbpos, pos.getX() + a, pos.getY() + offset, pos.getZ() + b,
                                block, replacement, list
                        );
                    }
            return successes;
        }
        if (orientation.equalsIgnoreCase("z")) {
            for (int a = -r; a <= r; ++a)
                for (int b = -r; b <= r; ++b)
                    if (rectangle || a * a + b * b <= drsq) {
                        successes += setBlock(
                                world, mbpos, pos.getX() + b, pos.getY() + a, pos.getZ() + offset,
                                block, replacement, list
                        );
                    }
            return successes;
        }
        return 0;
    }

    private static int drawPyramid(CommandContext<CommandSourceStack> ctx, String base, boolean solid) throws CommandSyntaxException {
        BlockPos pos;
        double radius;
        int height;
        boolean pointup;
        String orientation;
        BlockInput block;
        Predicate<BlockInWorld> replacement;
        try {
            pos = getArg(ctx, BlockPosArgument::getSpawnablePos, "center");
            radius = getArg(ctx, IntegerArgumentType::getInteger, "radius") + 0.5D;
            height = getArg(ctx, IntegerArgumentType::getInteger, "height");
            pointup = getArg(ctx, StringArgumentType::getString, "pointing").equalsIgnoreCase("up");
            orientation = getArg(ctx, StringArgumentType::getString, "orientation");
            block = getArg(ctx, BlockStateArgument::getBlock, "block");
            replacement = getArg(ctx, BlockPredicateArgument::getBlockPredicate, "filter", true);
        } catch (ErrorHandled ignored) {
            return 0;
        }

        CommandSourceStack source = ctx.getSource();

        int affected = 0;
        BlockPos.MutableBlockPos mbpos = pos.mutable();

        List<BlockPos> list = Lists.newArrayList();

        ServerLevel world = source.getLevel();


        boolean isSquare = base.equalsIgnoreCase("square");

        for (int i = 0; i < height; ++i) {
            double r = pointup ? radius - radius * i / height - 1 : radius * i / height;
            affected += fillFlat(world, pos, i, r, isSquare, orientation, block, replacement, list, mbpos);
        }


        apply(source, block, replacement, list);

        return affected;
    }

    private static int drawPrism(CommandContext<CommandSourceStack> ctx, String base) {
        BlockPos pos;
        double radius;
        int height;
        String orientation;
        BlockInput block;
        Predicate<BlockInWorld> replacement;
        try {
            pos = getArg(ctx, BlockPosArgument::getSpawnablePos, "center");
            radius = getArg(ctx, IntegerArgumentType::getInteger, "radius") + 0.5D;
            height = getArg(ctx, IntegerArgumentType::getInteger, "height");
            orientation = getArg(ctx, StringArgumentType::getString, "orientation");
            block = getArg(ctx, BlockStateArgument::getBlock, "block");
            replacement = getArg(ctx, BlockPredicateArgument::getBlockPredicate, "filter", true);
        } catch (ErrorHandled | CommandSyntaxException ignored) {
            return 0;
        }

        CommandSourceStack source = ctx.getSource();

        int affected = 0;
        BlockPos.MutableBlockPos mbpos = pos.mutable();

        List<BlockPos> list = Lists.newArrayList();

        ServerLevel world = source.getLevel();


        boolean isSquare = base.equalsIgnoreCase("square");

        for (int i = 0; i < height; ++i) {
            affected += fillFlat(world, pos, i, radius, isSquare, orientation, block, replacement, list, mbpos);
        }


        apply(source, block, replacement, list);

        return affected;
    }
}