package carpet.script;

import carpet.script.CarpetEventServer.Callback;
import carpet.script.CarpetEventServer.CallbackList;
import carpet.script.CarpetEventServer.CallbackResult;
import carpet.script.value.FunctionValue;
import carpet.script.value.Value;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetCallbackDispatchTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @SuppressWarnings("unchecked")
    private static List<Callback> handlers(CallbackList callbacks) throws Exception {
        var field = CallbackList.class.getDeclaredField("callList");
        field.setAccessible(true);
        return (List<Callback>) field.get(callbacks);
    }

    private static Callback callback(String name) {
        FunctionValue function = mock(FunctionValue.class);
        when(function.getString()).thenReturn(name);
        return spy(new Callback("app", null, function, List.of(), null));
    }

    @Test
    void removesHandlersDeferredBySignalWhenItsLastDispatchReturns() throws Exception {
        CallbackList callbacks = new CallbackList(0, false, false);
        Callback first = callback("first"), removed = callback("removed");
        handlers(callbacks).addAll(List.of(first, removed));
        doAnswer(call -> {
            callbacks.removeEventCall("app", null, "removed");
            return CallbackResult.SUCCESS;
        }).when(first).signal(null, null, List.of());
        doReturn(CallbackResult.SUCCESS).when(removed).signal(null, null, List.of());

        assertEquals(2, callbacks.signal(null, null, List.of()));
        assertEquals(List.of(first), callbacks.inspectCurrentCalls());
        assertEquals(1, callbacks.signal(null, null, List.of()));
        verify(removed, times(1)).signal(null, null, List.of());
    }

    @Test
    void registrationsDuringSignalStartAtTheNextDispatch() throws Exception {
        CallbackList callbacks = new CallbackList(0, false, false);
        Callback first = callback("first"), added = callback("added");
        handlers(callbacks).add(first);
        doAnswer(call -> {
            if (!handlers(callbacks).contains(added)) handlers(callbacks).add(added);
            return CallbackResult.SUCCESS;
        }).when(first).signal(null, null, List.of());
        doReturn(CallbackResult.SUCCESS).when(added).signal(null, null, List.of());

        assertEquals(1, callbacks.signal(null, null, List.of()));
        verify(added, never()).signal(null, null, List.of());
        assertEquals(2, callbacks.signal(null, null, List.of()));
    }

    @Test
    void reloadStopsTheSuspendedDispatchBeforeAnyReplacementHandlerRuns() throws Exception {
        CallbackList callbacks = new CallbackList(0, false, false);
        Callback first = callback("first"), stale = callback("stale"), replacement = callback("replacement");
        handlers(callbacks).addAll(List.of(first, stale));
        doAnswer(call -> {
            callbacks.clearEverything();
            handlers(callbacks).add(replacement);
            return CallbackResult.SUCCESS;
        }).when(first).signal(null, null, List.of());
        doReturn(CallbackResult.SUCCESS).when(replacement).signal(null, null, List.of());

        assertEquals(1, callbacks.signal(null, null, List.of()));
        verify(stale, never()).signal(null, null, List.of());
        verify(replacement, never()).signal(null, null, List.of());
        assertEquals(1, callbacks.signal(null, null, List.of()));
        verify(replacement).signal(null, null, List.of());
    }

    @Test
    void throwingSignalStillFlushesItsDeferredRemovals() throws Exception {
        CallbackList callbacks = new CallbackList(0, false, false);
        Callback first = callback("first"), removed = callback("removed");
        handlers(callbacks).addAll(List.of(first, removed));
        doAnswer(call -> {
            callbacks.removeEventCall("app", null, "removed");
            throw new IllegalStateException("guest callback failed");
        }).when(first).signal(null, null, List.of());

        assertThrows(IllegalStateException.class, () -> callbacks.signal(null, null, List.of()));
        assertEquals(List.of(first), callbacks.inspectCurrentCalls());
    }

    @Test
    void actualTasksRegisterOneSharedCustomEventAndScheduleEachCallbackExactlyOnce() throws Exception {
        var server = mock(MinecraftServer.class);
        var scripts = mock(CarpetScriptServer.class);
        var serverField = CarpetScriptServer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(scripts, server);
        var ticks = mock(ServerTickRateManager.class);
        when(server.tickRateManager()).thenReturn(ticks);
        when(ticks.runsNormally()).thenReturn(true);
        var events = new CarpetEventServer(scripts);
        scripts.events = events;
        var source = mock(CommandSourceStack.class);
        when(source.getServer()).thenReturn(server);
        CarpetScriptHost host = CarpetScriptHost.create(scripts, null, false, source, ignored -> true, false, null, Expression.LoadOverride.DEFAULT);
        when(scripts.getAppHostByName(isNull())).thenReturn(host);
        var runtime = carpet.script.external.ScarpetRuntime.of(server);
        runtime.setScriptServer(scripts);
        var entered = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var observed = new AtomicIntegerArray(1_024);
        try {
            var registered = runtime.submit(() -> {
                var context = new CarpetContext(host, source, BlockPos.ZERO);
                context.initialize();
                var expression = new Expression("compat_scheduled(value) -> compat_observe(value); compat_register_worker(offset) -> (compat_register_start(); loop(128, (schedule(100000, 'compat_scheduled', offset + _); handler = (call('compat_event_handler_' + (offset + _), payload) -> payload); handle_event('compat_parallel_event', handler)))); jobs = map(range(8), task('compat_register_worker', _ * 128)); map(jobs, task_join(_))");
                carpet.script.api.Auxiliary.apply(expression);
                carpet.script.api.Threading.apply(expression);
                expression.addContextFunction("compat_register_start", 0, (guest, type, args) -> {
                    entered.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("Registration tasks did not resume");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return Value.NULL;
                });
                expression.addContextFunction("compat_observe", 1, (guest, type, args) -> {
                    observed.incrementAndGet((int) args.getFirst().readInteger());
                    // Due calls must leave the accepted queue before any reentrant tick.
                    events.tick();
                    return Value.NULL;
                });
                return expression.executeAndEvaluate(context, true, Expression.LoadOverride.DEFAULT, null).getLeft();
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            runtime.submit(() -> {
                start.countDown();
                for (int tick = 0; tick < 200; tick++) {
                    events.tick();
                    Thread.yield();
                }
                return null;
            }).get(10, TimeUnit.SECONDS);
            registered.get(10, TimeUnit.SECONDS);
            assertEquals(1, events.customEvents.size());
            assertEquals(1_024, events.customEvents.get("compat_parallel_event").handler.inspectCurrentCalls().size());
            assertEquals(1_024, events.scheduledCalls.size());
            events.scheduledCalls.forEach(call -> call.dueTime = 1);
            runtime.submit(() -> {
                events.tick();
                events.tick();
                return null;
            }).get(10, TimeUnit.SECONDS);
            assertTrue(events.scheduledCalls.isEmpty());
            for (int index = 0; index < observed.length(); index++) assertEquals(1, observed.get(index));
        } finally {
            start.countDown();
            host.getExecutor(Value.NULL).shutdownNow();
            carpet.script.external.ScarpetRuntime.beginShutdown(server, () -> {
            });
        }
    }
}
