// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class TisManipulateChunks {
    private static final Map<Action, Batch> RUNNING = new ConcurrentHashMap<>();

    private enum Action {ERASE, RELIGHT, QUERY, SET, ADD}

    private TisManipulateChunks() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return literal("chunk")
                .then(range(literal("erase"), Action.ERASE))
                .then(range(literal("relight").then(literal("abort").executes(c -> abort(c.getSource()))), Action.RELIGHT))
                .then(literal("inhabitedTime").then(range(literal("query"), Action.QUERY))
                        .then(literal("set").then(range(argument("ticks", LongArgumentType.longArg(0)), Action.SET)))
                        .then(literal("add").then(range(argument("deltaTicks", LongArgumentType.longArg()), Action.ADD))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> range(ArgumentBuilder<CommandSourceStack, ?> root, Action action) {
        root.executes(c -> {
            TisManipulateCommand.feedback(c.getSource(), "This operation changes chunk state. Choose current,"
                    + " square/chebyshev/circle/euclidean <radius0..32>, or at <chunkX> <chunkZ>");
            return 0;
        });
        root.then(literal("current").executes(c -> operate(c, action, selection(c.getSource(), 0, false), false)));
        for (String name : List.of("square", "chebyshev", "circle", "euclidean")) {
            boolean circle = "circle".equals(name) || "euclidean".equals(name);
            root.then(literal(name).then(argument("radius", IntegerArgumentType.integer(0, 32)).executes(c ->
                    operate(c, action, selection(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), circle), false))));
        }
        root.then(literal("at").then(argument("chunkX", IntegerArgumentType.integer()).then(argument("chunkZ", IntegerArgumentType.integer())
                .executes(c -> operate(c, action, List.of(new ChunkPos(IntegerArgumentType.getInteger(c, "chunkX"),
                        IntegerArgumentType.getInteger(c, "chunkZ"))), true)))));
        return root;
    }

    private static List<ChunkPos> selection(CommandSourceStack source, int radius, boolean circle) {
        ChunkPos center = ChunkPos.containing(BlockPos.containing(source.getPosition()));
        List<ChunkPos> result = new ArrayList<>();
        for (int dz = -radius; dz <= radius; ++dz)
            for (int dx = -radius; dx <= radius; ++dx)
                if (!circle || dx * dx + dz * dz <= radius * radius)
                    result.add(new ChunkPos(center.x() + dx, center.z() + dz));
        return result;
    }

    private static int operate(CommandContext<CommandSourceStack> context, Action action, List<ChunkPos> chunks, boolean loadedOnly) {
        CommandSourceStack source = context.getSource();
        for (ChunkPos pos : chunks) {
            long x = (long) pos.x() * 16, z = (long) pos.z() * 16;
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE
                    || !net.minecraft.world.level.Level.isInSpawnableBounds(new BlockPos((int) x, 0, (int) z))) {
                TisManipulateCommand.feedback(source, "Chunk is outside world bounds");
                return 0;
            }
        }
        long value = action == Action.SET ? LongArgumentType.getLong(context, "ticks") : action == Action.ADD
                ? LongArgumentType.getLong(context, "deltaTicks") : 0L;
        Batch batch = new Batch(source, action, chunks, loadedOnly, value);
        if ((action == Action.ERASE || action == Action.RELIGHT) && RUNNING.putIfAbsent(action, batch) != null) {
            TisManipulateCommand.feedback(source, "This chunk operation is already running");
            return 0;
        }
        return TisCommandContinuations.complete(source, chunks.size(), batch::start, null, () -> {
        });
    }

    private static int abort(CommandSourceStack source) {
        Batch batch = RUNNING.get(Action.RELIGHT);
        if (batch == null) {
            TisManipulateCommand.feedback(source, "No chunk relight is running");
            return 0;
        }
        return TisCommandContinuations.complete(source, 1, () -> {
            batch.cancel();
            return batch.actual.copy().thenApply(ignored -> 1);
        }, "Aborted relight after accepted work completed", () -> {
        });
    }

    public static void reset() {
        for (Batch batch : List.copyOf(RUNNING.values())) batch.cancel();
        RUNNING.clear();
    }

    private static final class Batch {
        final CommandSourceStack source;
        final ServerLevel world;
        final Action action;
        final List<ChunkPos> chunks;
        final boolean loadedOnly;
        final long value;
        final long started = System.nanoTime();
        final LongAdder[] erased = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};
        final List<Long> inhabited = new ArrayList<>();
        final CompletableFuture<Integer> actual = new CompletableFuture<>();
        final List<CompletableFuture<?>> reports = new ArrayList<>();
        java.util.function.Function<ChunkPos, CompletableFuture<Long>> execute;
        java.util.function.Supplier<CompletableFuture<Void>> report;
        int next, active, completed;
        boolean cancelled, finished, pumping;
        Throwable failure;
        long lastProgress = System.nanoTime();

        Batch(CommandSourceStack source, Action action, List<ChunkPos> chunks, boolean loadedOnly, long value) {
            this.source = source;
            this.world = source.getLevel();
            this.action = action;
            this.chunks = List.copyOf(chunks);
            this.loadedOnly = loadedOnly;
            this.value = value;
        }

        CompletableFuture<Integer> start() {
            // These are captured inside this invocation's real Native observer, before the first queued lease or worker.
            carpet.script.external.ScarpetNativeWork.record(actual);
            var caller = carpet.script.external.ScarpetNativeWork.trackNative(source.getServer(), actual);
            execute = carpet.script.external.ScarpetRuntime.captureNativeFunction(this::executeActual);
            report = carpet.script.external.ScarpetRuntime.captureNativeContinuation(() -> TisCommandContinuations.feedback(source, () -> {
                report();
                return null;
            }));
            reports.add(TisCommandContinuations.feedback(source, () -> {
                TisManipulateCommand.feedback(source, "Starting " + action.name().toLowerCase(Locale.ROOT) + " for " + chunks.size() + " chunks");
                return null;
            }));
            pump();
            return caller;
        }

        synchronized void cancel() {
            cancelled = true;
            pump();
        }

        synchronized void pump() {
            if (pumping || execute == null) return;
            pumping = true;
            try {
                while (!finished && !cancelled && failure == null && active < 5 && next < chunks.size()) {
                    ChunkPos pos = chunks.get(next++);
                    ++active;
                    CompletableFuture<Long> accepted;
                    try {
                        accepted = execute.apply(pos);
                    } catch (Throwable error) {
                        accepted = CompletableFuture.failedFuture(error);
                    }
                    var committed = accepted.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((time, error) -> {
                        synchronized (this) {
                            --active;
                            if (error != null) failure = error;
                            else {
                                ++completed;
                                if (time != null) inhabited.add(time);
                            }
                            long now = System.nanoTime();
                            if (now - lastProgress > 3_000_000_000L) {
                                lastProgress = now;
                                double seconds = (now - started) / 1e9;
                                reports.add(TisCommandContinuations.feedback(source, () -> {
                                    TisManipulateCommand.feedback(source, String.format(Locale.ROOT, "%s %d/%d chunks, %.1f chunks/s",
                                            action.name().toLowerCase(Locale.ROOT), completed, chunks.size(), completed / seconds));
                                    return null;
                                }));
                            }
                            pump();
                        }
                    }));
                    carpet.script.external.ScarpetNativeWork.record(committed);
                }
                if (!finished && active == 0 && (cancelled || failure != null || next >= chunks.size())) finish();
            } finally {
                pumping = false;
            }
        }

        CompletableFuture<Long> executeActual(ChunkPos pos) {
            java.util.function.Supplier<CompletableFuture<Long>> nativeBody = () -> {
                LevelChunk chunk = world.getChunkIfLoaded(pos.x(), pos.z());
                if (chunk == null)
                    return CompletableFuture.failedFuture(new IllegalStateException("Chunk is not loaded: " + pos));
                if (action == Action.QUERY || action == Action.SET || action == Action.ADD)
                    return TisCommandContinuations.phase(null, () -> {
                        if (action == Action.SET) chunk.setInhabitedTime(value);
                        else if (action == Action.ADD)
                            chunk.setInhabitedTime(Math.max(0L, chunk.getInhabitedTime() + value));
                        if (action != Action.QUERY) chunk.markUnsaved();
                        return chunk.getInhabitedTime();
                    });
                CompletableFuture<int[]> matter = action == Action.ERASE ? chunk.carpetEraseContentsAsync() : CompletableFuture.completedFuture(new int[0]);
                return TisCommandContinuations.then(matter, removed -> {
                    for (int i = 0; i < removed.length; ++i) erased[i].add(removed[i]);
                    // Upstream erasure sends new matter before its separate empty-light phase.
                    CompletableFuture<Void> refresh = action == Action.ERASE ? viewers(pos) : CompletableFuture.completedFuture(null);
                    return TisCommandContinuations.then(refresh, ignored -> TisCommandContinuations.then(
                            TisCommandContinuations.owned(world, new BlockPos(pos.getMinBlockX(), 0, pos.getMinBlockZ()), () -> {
                                var lighting = action == Action.ERASE ? TisChunkRelighter.eraseLight(world, pos) : TisChunkRelighter.relight(world, List.of(pos));
                                carpet.script.external.ScarpetNativeWork.record(lighting);
                                return lighting;
                            }).thenCompose(next -> next), count -> {
                                if (count == 0)
                                    return CompletableFuture.failedFuture(new IllegalStateException("Chunk could not be relit: " + pos));
                                return TisCommandContinuations.then(viewers(pos), sent -> CompletableFuture.completedFuture(null));
                            }));
                });
            };
            return loadedOnly ? TisCommandContinuations.loadedArea(source, world, pos.x(), pos.z(), pos.x(), pos.z(), nativeBody)
                    : TisCommandContinuations.area(source, world, pos.x(), pos.z(), pos.x(), pos.z(), nativeBody);
        }

        private CompletableFuture<Void> viewers(ChunkPos pos) {
            return TisCommandContinuations.owned(world, new BlockPos(pos.getMinBlockX(), 0, pos.getMinBlockZ()), () -> {
                var sent = refreshViewers(source, world, pos);
                carpet.script.external.ScarpetNativeWork.record(sent);
                return sent;
            }).thenCompose(next -> next);
        }

        void finish() {
            finished = true;
            var previous = CompletableFuture.allOf(reports.toArray(CompletableFuture[]::new));
            var delivered = TisCommandContinuations.then(previous, ignored -> report.get());
            delivered.whenComplete((ignored, failed) -> {
                RUNNING.remove(action, this);
                Throwable error = failure == null ? failed : failure;
                if (error == null) actual.complete(completed);
                else actual.completeExceptionally(error);
            });
        }

        void report() {
            String status = failure != null ? "failed: " + failure.getMessage() : cancelled ? "aborted" : "done";
            TisManipulateCommand.feedback(source, String.format(Locale.ROOT, "%s %s: %d/%d chunks in %.2f seconds",
                    action.name().toLowerCase(Locale.ROOT), status, completed, chunks.size(), (System.nanoTime() - started) / 1e9));
            if (action == Action.ERASE) TisManipulateCommand.feedback(source, "Erased entities=" + erased[0].sum()
                    + ", block entities=" + erased[1].sum() + ", scheduled ticks=" + erased[2].sum()
                    + ", block events=" + erased[3].sum() + ", chunk sections=" + erased[4].sum());
            if (!inhabited.isEmpty()) {
                var stats = inhabited.stream().mapToLong(Long::longValue).summaryStatistics();
                TisManipulateCommand.feedback(source, "Inhabited time ticks: min=" + stats.getMin() + ", max=" + stats.getMax()
                        + ", average=" + (long) stats.getAverage());
            }
        }
    }

    private static CompletableFuture<Void> refreshViewers(CommandSourceStack source, ServerLevel world, ChunkPos pos) {
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        for (ServerPlayer player : List.copyOf(world.getChunkSource().chunkMap.getPlayers(pos, false))) {
            sends.add(TisCommandContinuations.entity(source, player, () -> {
                if (player.level() != world || player.hasDisconnected() || player.isRemoved() || player.moonrise$getChunkLoader() == null
                        || !player.moonrise$getChunkLoader().getSentChunksRaw().contains(pos.pack()))
                    return CompletableFuture.completedFuture(null);
                boolean modify = world.chunkPacketBlockController.shouldModify(player, null);
                return TisCommandContinuations.then(TisCommandContinuations.owned(world,
                        new BlockPos(pos.getMinBlockX(), 0, pos.getMinBlockZ()), () -> {
                            LevelChunk chunk = world.getChunkIfLoaded(pos.x(), pos.z());
                            return chunk == null ? null : new ClientboundLevelChunkWithLightPacket(chunk, world.getLightEngine(), null, null, modify);
                        }), packet -> TisCommandContinuations.owned(player, () -> {
                    if (packet == null || player.level() != world || player.hasDisconnected() || player.isRemoved() || player.moonrise$getChunkLoader() == null
                            || !player.moonrise$getChunkLoader().getSentChunksRaw().contains(pos.pack())) return null;
                    AmsNativeCommandEffects.packet(player, packet);
                    return null;
                }));
            }));
        }
        var actual = CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new));
        carpet.script.external.ScarpetNativeWork.record(actual);
        return actual;
    }
}
