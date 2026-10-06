package carpet.script.external;

import carpet.script.CarpetScriptServer;
import carpet.script.exception.InternalExpressionException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetTickWaitersTest {
    @SuppressWarnings("unchecked")
    private static int waiting(ScarpetRuntime runtime) throws Exception {
        var field = ScarpetRuntime.class.getDeclaredField("tickWaiters");
        field.setAccessible(true);
        var waiters = (Map<Long, Set<CompletableFuture<Void>>>) field.get(runtime);
        return waiters.values().stream().mapToInt(Set::size).sum();
    }

    private static void awaitWaiters(ScarpetRuntime runtime, int count) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (waiting(runtime) != count && System.nanoTime() < limit) Thread.sleep(1);
        assertEquals(count, waiting(runtime));
    }

    private static ScarpetRuntime ready(MinecraftServer server) throws Exception {
        var runtime = ScarpetRuntime.of(server);
        runtime.setScriptServer(mock(CarpetScriptServer.class));
        when(server.tickRateManager()).thenReturn(mock(ServerTickRateManager.class));
        var initialized = ScarpetRuntime.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        ((AtomicBoolean) initialized.get(runtime)).set(true);
        return runtime;
    }

    private static Thread waiter(MinecraftServer server, CompletableFuture<Void> finished) {
        return Thread.ofPlatform().start(() -> {
            try {
                ScarpetRuntime.awaitNextTick(server);
                finished.complete(null);
            } catch (Throwable failure) {
                finished.completeExceptionally(failure);
            }
        });
    }

    @Test void interruptingOneWaiterLeavesOtherScriptsWaitingForTheirTick() throws Exception {
        var server = mock(MinecraftServer.class);
        var runtime = ready(server);
        var firstResult = new CompletableFuture<Void>();
        var secondResult = new CompletableFuture<Void>();
        Thread first = waiter(server, firstResult), second = waiter(server, secondResult);
        try {
            awaitWaiters(runtime, 2);
            first.interrupt();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> firstResult.get(3, TimeUnit.SECONDS));
            awaitWaiters(runtime, 1);
            assertFalse(secondResult.isDone());
            ScarpetRuntime.globalTick(server);
            secondResult.get(3, TimeUnit.SECONDS);
            awaitWaiters(runtime, 0);
        } finally {
            first.interrupt();
            second.interrupt();
            first.join(3_000);
            second.join(3_000);
            ScarpetRuntime.beginShutdown(server, () -> {});
        }
    }

    @Test void shutdownRefusesANewWaiterAndDrainsExistingWaiters() throws Exception {
        var server = mock(MinecraftServer.class);
        var runtime = ready(server);
        var result = new CompletableFuture<Void>();
        Thread existing = waiter(server, result);
        var stopped = new CountDownLatch(1);
        try {
            awaitWaiters(runtime, 1);
            assertTrue(ScarpetRuntime.beginShutdown(server, stopped::countDown));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertThrows(InternalExpressionException.class, () -> ScarpetRuntime.awaitNextTick(server));
            awaitWaiters(runtime, 0);
            assertTrue(stopped.await(3, TimeUnit.SECONDS));
        } finally {
            existing.interrupt();
            existing.join(3_000);
            ScarpetRuntime.beginShutdown(server, () -> {});
        }
    }

    @Test void shutdownAlsoRejectsAnActorAdmittedAfterItsDrainSnapshot() throws Exception {
        var server = mock(MinecraftServer.class);
        var runtime = ready(server);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        Set<CompletableFuture<?>> delegate = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var pending = ScarpetRuntime.class.getDeclaredField("pendingActors");
        pending.setAccessible(true);
        pending.set(runtime, new java.util.AbstractSet<CompletableFuture<?>>() {
            @Override public java.util.Iterator<CompletableFuture<?>> iterator() { return delegate.iterator(); }
            @Override public int size() { return delegate.size(); }
            @Override public boolean add(CompletableFuture<?> future) {
                entered.countDown();
                try {
                    if (!resume.await(3, TimeUnit.SECONDS)) throw new AssertionError("Actor admission did not resume");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return delegate.add(future);
            }
        });
        var actorFuture = ScarpetRuntime.class.getDeclaredMethod("actorFuture");
        actorFuture.setAccessible(true);
        var admitted = new CompletableFuture<CompletableFuture<?>>();
        Thread admission = Thread.ofPlatform().start(() -> {
            try {
                admitted.complete((CompletableFuture<?>) actorFuture.invoke(runtime));
            } catch (Throwable failure) {
                admitted.completeExceptionally(failure);
            }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(ScarpetRuntime.beginShutdown(server, () -> {}));
            resume.countDown();
            assertTrue(admitted.get(3, TimeUnit.SECONDS).isCompletedExceptionally());
            assertTrue(delegate.isEmpty());
        } finally {
            resume.countDown();
            admission.interrupt();
            admission.join(3_000);
            ScarpetRuntime.beginShutdown(server, () -> {});
        }
    }
}
