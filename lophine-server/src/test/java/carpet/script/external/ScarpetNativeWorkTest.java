package carpet.script.external;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ScarpetNativeWorkTest {
    @Test
    void replayMayAppendAChildAfterItsParentFutureCompletesInsideTheStillOpenScope() {
        var parent = new CompletableFuture<Void>();
        var child = new CompletableFuture<Void>();
        var captured = new AtomicReference<ScarpetNativeWork.Token>();
        var result = ScarpetNativeWork.observeNative(null, () -> {
            captured.set(ScarpetNativeWork.capture());
            ScarpetNativeWork.record(parent);
            return 7;
        });
        assertNull(ScarpetNativeWork.capture());
        assertFalse(result.isDone());
        ScarpetNativeWork.with(captured.get(), () -> {
            parent.complete(null);
            assertFalse(result.isDone());
            ScarpetNativeWork.record(child);
            assertSame(captured.get(), ScarpetNativeWork.capture());
        });
        assertNull(ScarpetNativeWork.capture());
        assertFalse(result.isDone());
        child.complete(null);
        assertEquals(7, result.getNow(-1));
    }

    @Test
    void failureWaitsForAllAlreadyAcceptedNativeWorkBeforeAllowingRetry() {
        var failed = new CompletableFuture<Void>();
        var stillRunning = new CompletableFuture<Void>();
        var problem = new IllegalStateException("actual native tail failed");
        var result = ScarpetNativeWork.observeNative(null, () -> {
            ScarpetNativeWork.record(failed);
            ScarpetNativeWork.record(stillRunning);
            return true;
        });
        failed.completeExceptionally(new CompletionException(problem));
        assertFalse(result.isDone());
        stillRunning.complete(null);
        var error = assertThrows(CompletionException.class, () -> result.getNow(false));
        assertSame(problem, error.getCause());
    }

    @Test
    void nestedObserverAutomaticallyBelongsToTheOuterOwnerAndRestoresItsToken() {
        var delayed = new CompletableFuture<Void>();
        var nested = new AtomicReference<CompletableFuture<Boolean>>();
        var outer = ScarpetNativeWork.observeNative(null, () -> {
            var token = ScarpetNativeWork.capture();
            nested.set(ScarpetNativeWork.observeNative(null, () -> {
                assertNotSame(token, ScarpetNativeWork.capture());
                ScarpetNativeWork.record(delayed);
                return true;
            }));
            assertSame(token, ScarpetNativeWork.capture());
            return "owner tail";
        });
        assertFalse(nested.get().isDone());
        assertFalse(outer.isDone());
        delayed.complete(null);
        assertTrue(nested.get().getNow(false));
        assertEquals("owner tail", outer.getNow(null));
        assertNull(ScarpetNativeWork.capture());
    }

    @Test
    void synchronousThrowStillWaitsForAlreadyCapturedWorkAndDoesNotLeakItsScope() {
        var delayed = new CompletableFuture<Void>();
        var problem = new IllegalArgumentException("native body");
        var result = ScarpetNativeWork.observeNative(null, () -> {
            ScarpetNativeWork.record(delayed);
            throw problem;
        });
        assertNull(ScarpetNativeWork.capture());
        assertFalse(result.isDone());
        delayed.complete(null);
        assertSame(problem, assertThrows(CompletionException.class, () -> result.getNow(null)).getCause());
        assertEquals(42, ScarpetNativeWork.with(null, () -> 42));
    }

    @Test
    void simultaneousNativeReplaysCanCompleteParentsAndAppendChildrenWithoutAnEarlyZero() throws Exception {
        int owners = 8;
        var parents = new ArrayList<CompletableFuture<Void>>();
        var children = new ArrayList<CompletableFuture<Void>>();
        for (int index = 0; index < owners; index++) {
            parents.add(new CompletableFuture<>());
            children.add(new CompletableFuture<>());
        }
        var captured = new AtomicReference<ScarpetNativeWork.Token>();
        var result = ScarpetNativeWork.observeNative(null, () -> {
            captured.set(ScarpetNativeWork.capture());
            parents.forEach(ScarpetNativeWork::record);
            return true;
        });
        var entered = new CountDownLatch(owners);
        var append = new CountDownLatch(1);
        var appended = new CountDownLatch(owners);
        var exit = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < owners; index++) {
            final int selected = index;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    ScarpetNativeWork.with(captured.get(), () -> {
                        entered.countDown();
                        await(append);
                        parents.get(selected).complete(null);
                        ScarpetNativeWork.record(children.get(selected));
                        appended.countDown();
                        await(exit);
                    });
                    if (ScarpetNativeWork.capture() != null)
                        throw new AssertionError("Observer leaked across a native owner task");
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }));
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            append.countDown();
            assertTrue(appended.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone());
        } finally {
            append.countDown();
            exit.countDown();
        }
        for (var thread : threads) thread.join(5_000L);
        assertNull(failure.get());
        assertFalse(result.isDone());
        for (int index = 0; index < owners - 1; index++) {
            children.get(index).complete(null);
            assertFalse(result.isDone());
        }
        children.getLast().complete(null);
        assertTrue(result.getNow(false));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Native replay proof timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void actualInterpreterFailureRetainsItsPublicCauseAndCannotFinishBeforeANativeSibling() throws Exception {
        var runtime = ScarpetRuntime.of(org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class));
        var problem = new IllegalStateException("script failure");
        var sibling = new CompletableFuture<Void>();
        var guest = new AtomicReference<CompletableFuture<Object>>();
        var actual = ScarpetNativeWork.observeNative(null, () -> {
            guest.set(runtime.submit(() -> {
                throw problem;
            }));
            ScarpetNativeWork.record(sibling);
            return true;
        });
        assertSame(problem, assertThrows(java.util.concurrent.ExecutionException.class, () -> guest.get().get(3, TimeUnit.SECONDS)).getCause());
        assertFalse(actual.isDone());
        sibling.complete(null);
        Throwable failure = assertThrows(CompletionException.class, actual::join).getCause();
        assertTrue(ScarpetNativeWork.onlyGuestFailure(failure));
        assertSame(problem, failure.getCause());
    }

    @Test
    void aFailedNativeChildIsNeverHiddenByAnEarlierGuestFailure() throws Exception {
        var runtime = ScarpetRuntime.of(org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class));
        var nativeFailure = new IllegalStateException("true native child");
        var nativeChild = new CompletableFuture<Void>();
        var guest = new AtomicReference<CompletableFuture<Object>>();
        var actual = ScarpetNativeWork.observeNative(null, () -> {
            guest.set(runtime.submit(() -> {
                throw new IllegalArgumentException("first guest");
            }));
            ScarpetNativeWork.record(nativeChild);
            return true;
        });
        assertThrows(java.util.concurrent.ExecutionException.class, () -> guest.get().get(3, TimeUnit.SECONDS));
        nativeChild.completeExceptionally(nativeFailure);
        Throwable failure = assertThrows(CompletionException.class, actual::join).getCause();
        assertSame(nativeFailure, failure);
        assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
    }

    @Test
    void propagatingAnActualNativeFailureThroughTheInterpreterDoesNotRelabelIt() throws Exception {
        var runtime = ScarpetRuntime.of(org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class));
        var problem = new IllegalStateException("native actor failed");
        var actual = ScarpetNativeWork.observeNative(null, () -> runtime.submit(() -> {
            var nativeWork = ScarpetNativeWork.observeNative(null, () -> {
                throw problem;
            });
            return nativeWork.join();
        }));
        Throwable failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> actual.get(3, TimeUnit.SECONDS)).getCause();
        assertSame(problem, failure);
        assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
    }

    @Test
    void aNativeBodyThatThrowsAnExistingGuestCauseStillFailsItsActualPhase() throws Exception {
        var runtime = ScarpetRuntime.of(org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class));
        var problem = new IllegalStateException("guest failure propagated by a native body");
        var guest = runtime.submit(() -> {
            throw problem;
        });
        assertThrows(java.util.concurrent.ExecutionException.class, () -> guest.get(3, TimeUnit.SECONDS));
        var nativeBody = ScarpetNativeWork.observeNative(null, () -> {
            throw problem;
        });
        Throwable failure = assertThrows(CompletionException.class, nativeBody::join).getCause();
        assertSame(problem, failure);
        assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
    }

    @Test
    void recoveringTheRealNativeValueWaitsEveryChildAndKeepsItsOriginalParentFailure() throws Exception {
        var runtime = ScarpetRuntime.of(org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class));
        var child = new CompletableFuture<Void>();
        var recovered = new AtomicReference<CompletableFuture<Integer>>();
        var outer = ScarpetNativeWork.observeNative(null, () -> {
            var observed = ScarpetNativeWork.observeNative(null, () -> {
                runtime.submit(() -> {
                    throw new IllegalStateException("guest");
                });
                ScarpetNativeWork.record(child);
                return 37;
            });
            recovered.set(ScarpetNativeWork.recoverGuestValue(observed));
            return null;
        });
        assertFalse(recovered.get().isDone());
        assertFalse(outer.isDone());
        child.complete(null);
        assertEquals(37, recovered.get().get(3, TimeUnit.SECONDS));
        assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(java.util.concurrent.ExecutionException.class, () -> outer.get(3, TimeUnit.SECONDS)).getCause()));
    }

    @Test
    void guestRecoveryCannotInventAValueForATransformedOrFlattenedFuture() throws Exception {
        var runtime = ScarpetRuntime.of(org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class));
        var observed = ScarpetNativeWork.observeNative(null, () -> {
            runtime.submit(() -> {
                throw new IllegalStateException("guest");
            });
            return CompletableFuture.completedFuture(37);
        });
        var flattened = observed.thenCompose(value -> value);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> ScarpetNativeWork.recoverGuestValue(flattened).get(3, TimeUnit.SECONDS));
        assertEquals(37, ScarpetNativeWork.recoverGuestValue(observed).thenCompose(value -> value).get(3, TimeUnit.SECONDS));
        assertSame(ScarpetNativeWork.knownDependencyOf(observed), ScarpetNativeWork.knownDependencyOf(ScarpetNativeWork.recoverGuestValue(observed)));
    }

    @Test
    void guestValueRecoveryNeverHidesTheFailureOfAnActualNativeChild() throws Exception {
        var runtime = ScarpetRuntime.of(org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class));
        var child = new CompletableFuture<Void>();
        var nativeFailure = new IllegalStateException("real native child");
        var observed = ScarpetNativeWork.observeNative(null, () -> {
            runtime.submit(() -> {
                throw new IllegalStateException("guest");
            });
            ScarpetNativeWork.record(child);
            return 37;
        });
        var recovered = ScarpetNativeWork.recoverGuestValue(observed);
        child.completeExceptionally(nativeFailure);
        assertSame(nativeFailure, assertThrows(java.util.concurrent.ExecutionException.class, () -> recovered.get(3, TimeUnit.SECONDS)).getCause());
    }

    @Test
    void canceledGuestReceiptCannotFinishBeforeItsAlreadyRunningBodyAndIsClassifiedPrecisely() throws Exception {
        var runtime = ScarpetRuntime.of(org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class));
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var guest = new AtomicReference<CompletableFuture<Integer>>();
        var actual = ScarpetNativeWork.observeNative(null, () -> {
            guest.set(runtime.submit(() -> {
                entered.countDown();
                await(finish);
                return 37;
            }));
            return true;
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(guest.get().cancel(false));
            assertFalse(actual.isDone());
        } finally {
            finish.countDown();
        }
        assertTrue(guest.get().isCancelled());
        assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(java.util.concurrent.ExecutionException.class, () -> actual.get(3, TimeUnit.SECONDS)).getCause()));
    }
}
