package fun.bm.lophine.carpet;

import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetAsyncCommandResultsTest {
    @Test
    void fastCompletionSuppressesAcceptedResultAndDeliversActualCountOnlyOnce() {
        var results = new ArrayList<String>();
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.callback()).thenReturn((success, result) -> results.add(success + ":" + result));
        try (var scope = CarpetAsyncCommandResults.open()) {
            var completion = CarpetAsyncCommandResults.defer(source);
            completion.complete(true, 42);
            assertTrue(CarpetAsyncCommandResults.consumeImmediate(source, true, 1));
            completion.complete(false, 0);
            assertEquals(42, completion.future().join());
            assertEquals(java.util.List.of("true:42"), results);
        }
        assertFalse(CarpetAsyncCommandResults.consumeImmediate(source, true, 3));
    }

    @Test
    void nestedInvocationsDoNotLeakToOtherCommandsOrReuseOfTheSource() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.callback()).thenReturn(net.minecraft.commands.CommandResultCallback.EMPTY);
        try (var outer = CarpetAsyncCommandResults.open()) {
            CarpetAsyncCommandResults.defer(source);
            try (var inner = CarpetAsyncCommandResults.open()) {
                assertFalse(CarpetAsyncCommandResults.consumeImmediate(source, true, 1));
                var completed = CarpetAsyncCommandResults.defer(source);
                completed.complete(true, 9);
                assertEquals(9, completed.future().join());
            }
            assertTrue(CarpetAsyncCommandResults.consumeImmediate(source, true, 1));
            assertEquals(2, outer.pendingFutures().size());
            assertEquals(9, outer.pendingFutures().getFirst().join());
        }
        try (var next = CarpetAsyncCommandResults.open()) {
            assertFalse(CarpetAsyncCommandResults.consumeImmediate(source, true, 1));
        }
    }

    @Test
    void nativeExecutionReportsTheCompletedCountThroughItsRealResultConsumer() {
        var results = new ArrayList<String>();
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.callback()).thenReturn((success, result) -> results.add(success + ":" + result));
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
        dispatcher.register(net.minecraft.commands.Commands.literal("compat_async")
                .executes(ctx -> {
                    CarpetAsyncCommandResults.defer(ctx.getSource()).complete(true, 47);
                    return 1;
                }));
        var command = dispatcher.parse("compat_async", source).getContext().build("compat_async");
        var context = mock(net.minecraft.commands.execution.ExecutionContext.class);
        when(context.profiler()).thenReturn(mock(net.minecraft.util.profiling.ProfilerFiller.class));
        var entry = new net.minecraft.commands.execution.tasks.ExecuteCommand<CommandSourceStack>("compat_async", net.minecraft.commands.execution.ChainModifiers.DEFAULT, command);
        entry.execute(source, context, mock(net.minecraft.commands.execution.Frame.class));
        assertEquals(java.util.List.of("true:47"), results);
        assertFalse(CarpetAsyncCommandResults.consumeImmediate(source, true, 1));
    }

    private static final class Host extends carpet.script.ScriptHost {
        Host() {
            super(null, new carpet.script.ScriptServer() {
                @Override
                public java.nio.file.Path resolveResource(String name) {
                    return java.nio.file.Path.of(name);
                }
            }, false, null, carpet.script.Expression.LoadOverride.DEFAULT);
        }

        @Override
        protected carpet.script.Module getModuleOrLibraryByName(String name) {
            return null;
        }

        @Override
        protected void runModuleCode(carpet.script.Context context, carpet.script.Module module) {
        }

        @Override
        protected carpet.script.ScriptHost duplicate() {
            return new Host();
        }
    }

    private static final class ReadyContext extends carpet.script.Context {
        ReadyContext(Host host) {
            super(host);
            initialize();
        }
    }

    @Test
    void anAcceptedOwnerCallbackKeepsItsFlagsAfterHostCloseAndWaitsRealChildrenDespiteCallerCancellation() {
        var server = mock(net.minecraft.server.MinecraftServer.class);
        var player = mock(net.minecraft.server.level.ServerPlayer.class);
        var source = mock(CommandSourceStack.class);
        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(player);
        var child = new java.util.concurrent.CompletableFuture<Void>();
        var observed = new ArrayList<String>();
        when(source.callback()).thenReturn((success, value) -> {
            assertTrue(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
            assertTrue(carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get());
            carpet.script.external.ScarpetNativeWork.record(child);
            observed.add(success + ":" + value);
        });
        var queued = new java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<?>>();
        var actor = new java.util.concurrent.CompletableFuture<Object>();
        var completion = new java.util.concurrent.atomic.AtomicReference<CarpetAsyncCommandResults.Completion>();
        Host host = new Host();
        var context = new ReadyContext(host);
        try (var actors = mockStatic(carpet.script.external.ScarpetExplosionActors.class)) {
            actors.when(() -> carpet.script.external.ScarpetExplosionActors.entity(eq(player), any(java.util.function.Supplier.class))).thenAnswer(call -> {
                queued.set(carpet.script.external.ScarpetRuntime.captureNativeContinuation(call.getArgument(1)));
                return actor;
            });
            var outer = carpet.script.external.ScarpetNativeWork.observeNative(null, () -> {
                try (var frame = carpet.script.external.ScarpetRuntime.enterContext(context)) {
                    boolean oldFill = carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get(), oldEvents = carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get();
                    try {
                        carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
                        carpet.script.external.ScarpetRuntime.EVENT_DISABLED.set(true);
                        completion.set(CarpetAsyncCommandResults.defer(source));
                    } finally {
                        carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(oldFill);
                        carpet.script.external.ScarpetRuntime.EVENT_DISABLED.set(oldEvents);
                    }
                }
                return true;
            });
            host.onClose();
            var caller = completion.get().future();
            assertTrue(caller.cancel(false));
            var idle = carpet.script.external.ScarpetNativeWork.whenIdle(server);
            assertFalse(idle.isDone());
            assertFalse(outer.isDone());
            completion.get().complete(true, 64);
            actor.complete(queued.get().get());
            assertEquals(java.util.List.of("true:64"), observed);
            assertFalse(completion.get().future().isDone());
            assertFalse(outer.isDone());
            assertFalse(idle.isDone());
            child.complete(null);
            assertEquals(64, completion.get().future().join());
            assertTrue(caller.isCancelled());
            assertTrue(outer.join());
            assertTrue(idle.isDone());
            assertFalse(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
            assertFalse(carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get());
        }
    }

    @Test
    void callbackFailureWaitsAlreadyAcceptedChildrenAndKeepsItsNativeCause() {
        var source = mock(CommandSourceStack.class);
        var child = new java.util.concurrent.CompletableFuture<Void>();
        var problem = new IllegalStateException("real result callback");
        when(source.callback()).thenReturn((success, value) -> {
            carpet.script.external.ScarpetNativeWork.record(child);
            throw problem;
        });
        try (var scope = CarpetAsyncCommandResults.open()) {
            var completion = CarpetAsyncCommandResults.defer(source);
            completion.complete(true, 5);
            var scopeDone = scope.completionFuture();
            assertFalse(scopeDone.isDone());
            assertFalse(completion.future().isDone());
            child.complete(null);
            assertSame(problem, assertThrows(java.util.concurrent.CompletionException.class, () -> completion.future().join()).getCause());
            assertFalse(carpet.script.external.ScarpetNativeWork.onlyGuestFailure(problem));
            assertTrue(scopeDone.isCompletedExceptionally());
        }
    }

    @Test
    void aCancelledPublicScopeEntryCannotEndTheActualNestedInvocation() {
        var source = mock(CommandSourceStack.class);
        when(source.callback()).thenReturn(net.minecraft.commands.CommandResultCallback.EMPTY);
        try (var outer = CarpetAsyncCommandResults.open()) {
            CarpetAsyncCommandResults.Completion completion;
            try (var inner = CarpetAsyncCommandResults.open()) {
                completion = CarpetAsyncCommandResults.defer(source);
                assertTrue(inner.pendingFutures().getFirst().cancel(false));
                assertFalse(inner.completionFuture().isDone());
            }
            var finished = outer.completionFuture();
            assertFalse(finished.isDone());
            completion.complete(true, 23);
            assertEquals(23, outer.pendingFutures().getFirst().join());
            assertTrue(finished.isDone());
        }
    }
}
