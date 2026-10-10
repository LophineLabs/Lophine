// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Complete actual AMS command bodies, their file receipts and every actual recipient child.
 */
public final class AmsNativeCommandEffects {
    private static final ThreadLocal<Sequence> SEQUENCE = new ThreadLocal<>();

    private static final class Sequence {
        CompletableFuture<?> last = CompletableFuture.completedFuture(null);
    }

    private AmsNativeCommandEffects() {
    }

    private static <T> T value(Supplier<T> body) {
        T actual = body.get();
        if (actual instanceof CompletableFuture<?> work) ScarpetNativeWork.record(work);
        return actual;
    }

    private static <T> CompletableFuture<T> protectedValue(MinecraftServer server, Supplier<CompletableFuture<T>> body) {
        var actual = new CompletableFuture<T>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        ScarpetNativeWork.record(actual);
        if (server != null) ScarpetNativeWork.trackNative(server, actual);
        try {
            var work = body.get();
            ScarpetNativeWork.aliasDependency(actual, work);
            work.whenComplete((result, failure) -> {
                if (failure == null) actual.complete(result);
                else actual.completeExceptionally(failure);
            });
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        return actual;
    }

    public static <T> CompletableFuture<T> nativeReceipt(MinecraftServer server, Supplier<CompletableFuture<T>> body) {
        return protectedValue(server, body);
    }

    public static <T> CompletableFuture<T> world(net.minecraft.server.level.ServerLevel world, net.minecraft.core.BlockPos position, Supplier<T> body) {
        return protectedValue(world.getServer(), () -> TisCommandContinuations.owned(world, position, () -> value(body)));
    }

    public static int command(CommandContext<CommandSourceStack> context, Command<CommandSourceStack> nativeBody) {
        var source = context.getSource();
        return OrgCommandNativeEffects.command(source, 1, () -> source(source, () -> {
            Sequence previous = SEQUENCE.get(), sequence = new Sequence();
            SEQUENCE.set(sequence);
            try {
                return nativeBody.run(context);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            } finally {
                ScarpetNativeWork.record(sequence.last);
                if (previous == null) SEQUENCE.remove();
                else SEQUENCE.set(previous);
            }
        }));
    }

    /**
     * A source save's actual receipt fences subsequent messages and refresh/packet effects.
     */
    public static void receipt(CompletableFuture<?> actual) {
        Sequence sequence = SEQUENCE.get();
        if (sequence != null) sequence.last = then(sequence.last, ignored -> actual);
        ScarpetNativeWork.record(actual);
    }

    public static void effect(Supplier<CompletableFuture<?>> body) {
        Sequence sequence = SEQUENCE.get();
        if (sequence == null) {
            ScarpetNativeWork.record(body.get());
            return;
        }
        sequence.last = then(sequence.last, ignored -> body.get());
    }

    public static void reply(CommandSourceStack source, Runnable body) {
        Sequence sequence = SEQUENCE.get();
        if (sequence == null) {
            ScarpetNativeWork.record(source(source, () -> {
                body.run();
                return (Void) null;
            }));
            return;
        }
        sequence.last = then(sequence.last, ignored -> source(source, () -> {
            body.run();
            return (Void) null;
        }));
    }

    public static <T> CompletableFuture<T> source(CommandSourceStack source, Supplier<T> body) {
        if (source.getEntity() == null && source.getLevel() == null) return global(source.getServer(), body);
        return protectedValue(source.getServer(), () -> TisCommandContinuations.feedback(source, () -> value(body)));
    }

    public static <T> CompletableFuture<T> global(MinecraftServer server, Supplier<T> body) {
        return protectedValue(server, () -> OrgCommandNativeEffects.global(server, () -> value(body)));
    }

    public static <T> CompletableFuture<T> owned(ServerPlayer player, Supplier<T> body) {
        return protectedValue(player.carpetSpawnServer(), () -> TisCommandContinuations.owned(player, () -> value(body)));
    }

    /**
     * Called on the actual player owner; physical write failure remains a native child.
     */
    public static CompletableFuture<Void> packet(ServerPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        var actual = new CompletableFuture<Void>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(player.carpetSpawnServer(), actual);
        try {
            var listener = player.connection;
            var connection = listener.connection;
            // Leaves' synthetic transport intentionally has no Netty channel, and
            // its no-op listener never acknowledges a physical client packet.
            if (connection instanceof org.leavesmc.leaves.bot.ServerBotPacketListenerImpl.BotConnection) {
                actual.complete(null);
                return actual;
            }
            if (connection == null || connection.channel == null || !connection.isConnected()) {
                actual.completeExceptionally(new java.nio.channels.ClosedChannelException());
                return actual;
            }
            var closing = connection.channel.closeFuture();
            io.netty.channel.ChannelFutureListener closed = ignored -> actual.completeExceptionally(new java.nio.channels.ClosedChannelException());
            closing.addListener(closed);
            actual.whenComplete((result, failure) -> closing.removeListener(closed));
            if (actual.isDone()) return actual;
            if (!connection.isConnected()) {
                actual.completeExceptionally(new java.nio.channels.ClosedChannelException());
                return actual;
            }
            listener.send(packet, completed -> {
                if (completed.isSuccess()) actual.complete(null);
                else
                    actual.completeExceptionally(completed.cause() == null ? new IllegalStateException("Actual AMS packet failed") : completed.cause());
            });
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
            throw failure;
        }
        return actual;
    }

    public static <T, R> CompletableFuture<R> then(CompletableFuture<T> before, Function<T, CompletableFuture<R>> after) {
        return TisCommandContinuations.then(before, after);
    }

    public static CompletableFuture<Void> broadcast(MinecraftServer server, Consumer<ServerPlayer> body) {
        return then(global(server, () -> List.copyOf(server.getPlayerList().getPlayers())), all -> sequence(server, all.iterator(), player -> owned(player, () -> {
            body.accept(player);
            return (Void) null;
        })));
    }

    /**
     * Already owned phases can complete immediately. Keep source order without a
     * recursively growing tick-thread stack, and retain every real suspended child.
     */
    public static <T> CompletableFuture<Void> sequence(MinecraftServer server, Iterator<T> values, Function<T, CompletableFuture<?>> operation) {
        return protectedValue(server, () -> {
            var done = new CompletableFuture<Void>();
            ScarpetNativeWork.record(done);
            var captured = carpet.script.external.ScarpetRuntime.captureNativeFunction(operation);
            class Pump {
                final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
                CompletableFuture<?> current;

                void run() {
                    if (running.getAndIncrement() != 0) return;
                    do {
                        try {
                            while (!done.isDone()) {
                                if (current == null) {
                                    if (!values.hasNext()) {
                                        done.complete(null);
                                        break;
                                    }
                                    current = java.util.Objects.requireNonNull(captured.apply(values.next()));
                                    ScarpetNativeWork.record(current);
                                    if (!current.isDone())
                                        current.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((value, failure) -> run()));
                                }
                                if (!current.isDone()) break;
                                current.join(); // Only inspect an already completed phase; never wait an owner.
                                current = null;
                            }
                        } catch (Throwable failure) {
                            done.completeExceptionally(failure);
                        }
                    } while (running.decrementAndGet() != 0);
                }
            }
            new Pump().run();
            return done;
        });
    }

    public static CompletableFuture<Void> pause(MinecraftServer server, long millis) {
        var actual = new CompletableFuture<Void>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(server, actual);
        CompletableFuture.delayedExecutor(millis, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> actual.complete(null));
        return actual;
    }
}
