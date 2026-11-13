// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet.org;

import fun.bm.lophine.carpet.CarpetFakePlayerPersistence;
import net.minecraft.server.MinecraftServer;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manager snapshots finish on live region actors before Folia halts them.
 */
public final class OrgPlayerManagerLifecycle {
    // Region work admitted during a drain can keep admitting more, so the coordinator waits for a
    // bounded period like the Scarpet shutdown phase instead of holding the server open forever.
    private static final long DRAIN_TIMEOUT_MILLIS = 30_000L;
    private static final long DRAIN_REPORT_MILLIS = 5_000L;
    private static final Set<MinecraftServer> STARTED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<MinecraftServer, Stop> STOPS = new ConcurrentHashMap<>();

    private static final class Stop {
        final AtomicBoolean started = new AtomicBoolean();
        volatile boolean ready;
    }

    private OrgPlayerManagerLifecycle() {
    }

    public static void tick(MinecraftServer server) {
        if (!STOPS.containsKey(server) && STARTED.add(server)) {
            OrgServerPermissions.initialize(server);
            OrgPlayerManager.start(server);
            OrgFinderPlayerData.start(server);
            CarpetFakePlayerPersistence.respawn(server, server.overworld());
        }
    }

    public static void worldLoaded(MinecraftServer server, net.minecraft.server.level.ServerLevel level) {
        if (STARTED.contains(server) && !STOPS.containsKey(server)) CarpetFakePlayerPersistence.respawn(server, level);
    }

    public static boolean beforeStop(MinecraftServer server, Runnable nativeStop) {
        Stop stop = STOPS.computeIfAbsent(server, ignored -> new Stop());
        if (!stop.started.compareAndSet(false, true)) return !stop.ready;
        var captured = OrgPlayerManager.beforeShutdown(server).handle((profiles, failure) -> {
            if (failure != null)
                MinecraftServer.LOGGER.error("Cannot finish Org resident profiles before shutdown", failure);
            return null;
        });
        var drained = captured.thenCompose(ignored -> carpet.script.external.ScarpetNativeWork.whenIdle(server));
        Runnable finish = () -> {
            try {
                awaitDrain(server, captured, drained);
            } catch (Throwable failure) {
                MinecraftServer.LOGGER.error("Cannot finish Org resident profiles before shutdown", failure);
            } finally {
                OrgHiddenBedrockSignals.clearAll();
                OrgPlayerManager.close(server);
                OrgServerPermissions.close(server);
                OrgFinderPlayerData.close(server);
                STARTED.remove(server);
                stop.ready = true;
            }
        };
        if (drained.isDone()) {
            finish.run();
            return false;
        }
        Thread.ofPlatform().name("Org player manager shutdown").start(() -> {
            finish.run();
            nativeStop.run();
        });
        return true;
    }

    /**
     * Returns once the drain ends or once its budget is spent. The native shutdown that follows
     * cancels whatever was abandoned, so a stuck continuation halts the server late, never forever.
     */
    private static void awaitDrain(MinecraftServer server, CompletableFuture<?> captured, CompletableFuture<Void> drained) {
        long slice = TimeUnit.MILLISECONDS.toNanos(DRAIN_REPORT_MILLIS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_TIMEOUT_MILLIS);
        while (!drained.isDone()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                MinecraftServer.LOGGER.error("Carpet shutdown work did not finish within {} ms ({}); halting anyway",
                        DRAIN_TIMEOUT_MILLIS, pendingState(server, captured));
                return;
            }
            try {
                drained.get(Math.min(remaining, slice), TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException stillWaiting) {
                MinecraftServer.LOGGER.info("Waiting for Carpet shutdown work to finish ({}; halts anyway after {} ms)",
                        pendingState(server, captured), DRAIN_TIMEOUT_MILLIS);
            } catch (InterruptedException interrupted) {
                // The cleanup and the native halt that follow still have to run, so this dedicated
                // shutdown thread keeps the original join semantics of not restoring the interrupt.
                MinecraftServer.LOGGER.error("Carpet shutdown wait was interrupted ({}); halting anyway",
                        pendingState(server, captured));
                return;
            } catch (ExecutionException failure) {
                MinecraftServer.LOGGER.error("Cannot finish Org resident profiles before shutdown",
                        failure.getCause() == null ? failure : failure.getCause());
                return;
            } catch (CancellationException failure) {
                MinecraftServer.LOGGER.error("Cannot finish Org resident profiles before shutdown", failure);
                return;
            }
        }
    }

    private static String pendingState(MinecraftServer server, CompletableFuture<?> captured) {
        return "phase=" + (captured.isDone() ? "scarpet native drain" : "Org resident profile capture")
                + ", " + carpet.script.external.ScarpetNativeWork.describePending(server);
    }
}
