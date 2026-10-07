// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The original hopper passes retain each real value before the next pass and cooldown tail.
 */
public final class CarpetHopperCounters {
    private static final Map<HopperBlockEntity, CompletableFuture<Boolean>> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    private CarpetHopperCounters() {
    }

    public interface Operations {
        Integer begin();

        boolean eject();

        boolean pull();

        boolean empty();

        boolean full();

        void cooldown();

        boolean counter();

        void drained();

        void finish();
    }

    public static boolean pending(HopperBlockEntity hopper) {
        var actual = PENDING.get(hopper);
        return actual != null && !actual.isDone();
    }

    public static CompletableFuture<Boolean> move(ServerLevel world, BlockPos position, HopperBlockEntity hopper,
                                                  BooleanSupplier prepare, Operations operations, Consumer<Boolean> after) {
        BlockPos origin = position.immutable();
        var actual = new CompletableFuture<Boolean>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        synchronized (PENDING) {
            if (pending(hopper)) throw new IllegalStateException("A native hopper pass is already pending");
            PENDING.put(hopper, actual);
        }
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(world.getServer(), actual);
        actual.whenComplete((value, failure) -> PENDING.remove(hopper, actual));
        var flow = new Flow(world, origin, hopper, operations);
        try {
            var prepared = flow.phase(prepare::getAsBoolean);
            var finished = TisCommandContinuations.then(prepared, ready -> !ready ? CompletableFuture.completedFuture(false)
                    : TisCommandContinuations.then(flow.initial(), value -> flow.phase(() -> {
                after.accept(value);
                return value;
            })));
            ScarpetNativeWork.aliasDependency(actual, finished);
            finished.whenComplete((value, failure) -> {
                if (failure == null) actual.complete(value);
                else actual.completeExceptionally(failure);
            });
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        return actual;
    }

    private static final class Flow {
        final ServerLevel world;
        final BlockPos position;
        final HopperBlockEntity hopper;
        final Operations operations;

        Flow(ServerLevel world, BlockPos position, HopperBlockEntity hopper, Operations operations) {
            this.world = world;
            this.position = position;
            this.hopper = hopper;
            this.operations = operations;
        }

        <T> CompletableFuture<T> phase(Supplier<T> operation) {
            return TisCommandContinuations.owned(world, position, () -> {
                ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(world, position, "Native hopper pass must run on its original owner");
                // A deferred native child does not reserve this block. Piston carrying,
                // unloads and replacements can retire or move the original instance.
                // Consult the original actor's table before reading that instance.
                if (world.getBlockEntity(position) != hopper || hopper.isRemoved()
                        || hopper.getLevel() != world || !position.equals(hopper.getBlockPos()))
                    throw new IllegalStateException("Hopper moved or retired before native pass");
                return operation.get();
            });
        }

        CompletableFuture<Void> effect(Runnable operation) {
            return phase(() -> {
                operation.run();
                return null;
            });
        }

        CompletableFuture<Boolean> initial() {
            return TisCommandContinuations.then(phase(operations::begin), fullState -> {
                if (fullState == null) return CompletableFuture.completedFuture(false);
                var ejected = fullState != 0 ? phase(operations::eject) : CompletableFuture.completedFuture(false);
                var changed = TisCommandContinuations.then(ejected, pushed -> pushed || fullState != 2
                        ? TisCommandContinuations.then(phase(operations::pull), pulled -> CompletableFuture.completedFuture(pushed | pulled))
                        : CompletableFuture.completedFuture(false));
                return TisCommandContinuations.then(changed, moved -> {
                    if (!moved) return CompletableFuture.completedFuture(false);
                    var cooldown = effect(operations::cooldown);
                    var drained = TisCommandContinuations.then(cooldown, ignored -> TisCommandContinuations.then(phase(operations::counter), counter -> counter ? drain() : CompletableFuture.completedFuture(null)));
                    return TisCommandContinuations.then(drained, ignored -> TisCommandContinuations.then(effect(operations::finish), finished -> CompletableFuture.completedFuture(true)));
                });
            });
        }

        CompletableFuture<Boolean> pass() {
            var ejected = TisCommandContinuations.then(phase(operations::empty), empty -> empty ? CompletableFuture.completedFuture(false) : phase(operations::eject));
            return TisCommandContinuations.then(ejected, pushed -> TisCommandContinuations.then(phase(operations::full), full -> full ? CompletableFuture.completedFuture(pushed)
                    : TisCommandContinuations.then(phase(operations::pull), pulled -> CompletableFuture.completedFuture(pushed | pulled))));
        }

        CompletableFuture<Void> drain() {
            var result = new CompletableFuture<Void>();
            ScarpetNativeWork.record(result);
            class Pump {
                final AtomicInteger running = new AtomicInteger();
                CompletableFuture<Boolean> current;
                int count;

                void run() {
                    if (running.getAndIncrement() != 0) return;
                    do {
                        try {
                            while (!result.isDone()) {
                                if (current == null) {
                                    if (count == Short.MAX_VALUE) {
                                        com.mojang.logging.LogUtils.getLogger().warn("Hopper at {} exceeded hopperCountersUnlimitedSpeed operation limit {}", position, Short.MAX_VALUE);
                                        result.complete(null);
                                        break;
                                    }
                                    count++;
                                    current = pass();
                                    if (!current.isDone())
                                        current.whenComplete(ScarpetRuntime.captureNativeConsumer((changed, failure) -> run()));
                                }
                                if (!current.isDone()) break;
                                boolean changed = current.join();
                                current = null;
                                if (!changed) result.complete(null);
                            }
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    } while (running.decrementAndGet() != 0);
                }
            }
            new Pump().run();
            return TisCommandContinuations.then(result, ignored -> effect(operations::drained));
        }
    }
}
