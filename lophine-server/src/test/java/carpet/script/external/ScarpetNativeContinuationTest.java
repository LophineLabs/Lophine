package carpet.script.external;

import carpet.script.Context;
import carpet.script.Expression;
import carpet.script.ScriptHost;
import carpet.script.ScriptServer;
import carpet.script.exception.InternalExpressionException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Accepted native tails survive a closed initiating app; a subsequent guest mutation still fails its epoch.
 */
public class ScarpetNativeContinuationTest {
    private static final ScriptServer FILES = new ScriptServer() {
        @Override
        public Path resolveResource(String name) {
            return Path.of(name);
        }
    };

    private static final class Host extends ScriptHost {
        Host() {
            super(null, FILES, false, null, Expression.LoadOverride.DEFAULT);
        }

        @Override
        protected carpet.script.Module getModuleOrLibraryByName(String name) {
            return null;
        }

        @Override
        protected void runModuleCode(Context context, carpet.script.Module module) {
        }

        @Override
        protected ScriptHost duplicate() {
            return new Host();
        }
    }

    private static final class ReadyContext extends Context {
        ReadyContext(Host host) {
            super(host);
            initialize();
        }
    }

    @Test
    void committedNativeTailAndGuestEpochHaveDifferentActualLifetimes() {
        Host host = new Host();
        Context context = new ReadyContext(host);
        AtomicBoolean physicallyFinished = new AtomicBoolean(), guestCommitted = new AtomicBoolean();
        Supplier<Boolean> nativeTail, guestMutation;
        try (var frame = ScarpetRuntime.enterContext(context)) {
            nativeTail = ScarpetRuntime.captureNativeContinuation(() -> {
                physicallyFinished.set(true);
                return true;
            });
            guestMutation = ScarpetRuntime.captureOwnerOperation(() -> {
                guestCommitted.set(true);
                return true;
            });
        }
        host.onClose();
        assertTrue(nativeTail.get());
        assertTrue(physicallyFinished.get());
        assertThrows(InternalExpressionException.class, guestMutation::get);
        assertFalse(guestCommitted.get());
        assertThrows(InternalExpressionException.class, () -> {
            try (var frame = ScarpetRuntime.enterCapturedContext(context, context.executionEpoch())) {
                ScarpetRuntime.captureNativeContinuation(() -> true);
            }
        });
    }

    @Test
    void committedConsumerCarriesFlagsAndRealChildrenAfterTheInitiatingHostCloses() {
        Host host = new Host();
        Context context = new ReadyContext(host);
        var child = new java.util.concurrent.CompletableFuture<Void>();
        var callback = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Integer>>();
        var value = new java.util.concurrent.atomic.AtomicInteger();
        var actual = ScarpetNativeWork.observeNative(null, () -> {
            ScarpetNativeWork.record(child);
            try (var frame = ScarpetRuntime.enterContext(context)) {
                boolean oldFill = ScarpetRuntime.FILL_SKIP_UPDATES.get(), oldGeneration = ScarpetRuntime.SKIP_GENERATION_CHECKS.get(), oldEvents = ScarpetRuntime.EVENT_DISABLED.get();
                try {
                    ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
                    ScarpetRuntime.SKIP_GENERATION_CHECKS.set(true);
                    ScarpetRuntime.EVENT_DISABLED.set(true);
                    callback.set(ScarpetDimensionContinuations.captureConsumer((Integer input) -> {
                        assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get());
                        assertTrue(ScarpetRuntime.SKIP_GENERATION_CHECKS.get());
                        assertTrue(ScarpetRuntime.EVENT_DISABLED.get());
                        value.set(input);
                        child.complete(null);
                    }));
                } finally {
                    ScarpetRuntime.FILL_SKIP_UPDATES.set(oldFill);
                    ScarpetRuntime.SKIP_GENERATION_CHECKS.set(oldGeneration);
                    ScarpetRuntime.EVENT_DISABLED.set(oldEvents);
                }
            }
            return true;
        });
        host.onClose();
        assertFalse(actual.isDone());
        callback.get().accept(43);
        assertTrue(actual.join());
        assertEquals(43, value.get());
        assertFalse(ScarpetRuntime.FILL_SKIP_UPDATES.get());
        assertFalse(ScarpetRuntime.SKIP_GENERATION_CHECKS.get());
        assertFalse(ScarpetRuntime.EVENT_DISABLED.get());
    }
}
