package fun.bm.lophine.carpet;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.execution.*;
import net.minecraft.commands.execution.tasks.ExecuteCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarpetCommandContextTest {
    @Test
    void nativeQueueWaitsForTheActualResultBeforeItsNextCommandAndTracerClose() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.callback()).thenReturn(CommandResultCallback.EMPTY);
        var completion = new AtomicReference<CarpetAsyncCommandResults.Completion>();
        var order = new ArrayList<String>();
        var resumes = new ArrayDeque<Runnable>();
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
        dispatcher.register(net.minecraft.commands.Commands.literal("async_first").executes(ctx -> {
            order.add("first dispatched");
            completion.set(CarpetAsyncCommandResults.defer(ctx.getSource()));
            return 1;
        }));
        var context = new ExecutionContext<CommandSourceStack>(10, 10, mock(net.minecraft.util.profiling.ProfilerFiller.class));
        context.carpetConfigureContinuation(resumes::add, context::runCommandQueue);
        var tracer = mock(TraceCallbacks.class);
        context.tracer(tracer);
        Frame frame = new Frame(0, CommandResultCallback.EMPTY, context.frameControlForDepth(0));
        var command = dispatcher.parse("async_first", source).getContext().build("async_first");
        context.queueNext(new CommandQueueEntry<>(frame, new ExecuteCommand<>("async_first", ChainModifiers.DEFAULT, command).bind(source)));
        context.queueNext(new CommandQueueEntry<>(frame, (queue, f) -> order.add("second executed")));
        context.runCommandQueue();
        context.close();
        assertEquals(List.of("first dispatched"), order);
        assertFalse(context.carpetCompletion().isDone());
        verify(tracer, never()).onReturn(anyInt(), anyString(), anyInt());
        verify(tracer, never()).close();
        completion.get().complete(true, 47);
        verify(tracer).onReturn(0, "async_first", 47);
        assertEquals(1, resumes.size());
        assertEquals(List.of("first dispatched"), order);
        resumes.removeFirst().run();
        assertEquals(List.of("first dispatched", "second executed"), order);
        assertTrue(context.carpetCompletion().isDone());
        verify(tracer, times(1)).close();
        context.close();
        verify(tracer, times(1)).close();
    }

    @Test
    void nativeReturnFrameDiscardsTheRemainderAfterTheDeferredCommandReturns() {
        var context = new ExecutionContext<CommandSourceStack>(10, 10, mock(net.minecraft.util.profiling.ProfilerFiller.class));
        var resumes = new ArrayDeque<Runnable>();
        context.carpetConfigureContinuation(resumes::add, context::runCommandQueue);
        var returned = new ArrayList<Integer>();
        Frame frame = new Frame(0, (success, result) -> returned.add(result), context.frameControlForDepth(0));
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.callback()).thenReturn((success, result) -> {
            frame.returnSuccess(result);
            frame.discard();
        });
        var completion = new AtomicReference<CarpetAsyncCommandResults.Completion>();
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
        dispatcher.register(net.minecraft.commands.Commands.literal("return_async").executes(ctx -> {
            completion.set(CarpetAsyncCommandResults.defer(ctx.getSource()));
            return 1;
        }));
        var command = dispatcher.parse("return_async", source).getContext().build("return_async");
        context.queueNext(new CommandQueueEntry<>(frame, new ExecuteCommand<>("return_async", ChainModifiers.DEFAULT, command).bind(source)));
        context.queueNext(new CommandQueueEntry<>(frame, (queue, f) -> fail("return must discard this native queue entry")));
        context.runCommandQueue();
        context.close();
        assertTrue(returned.isEmpty());
        completion.get().complete(true, 83);
        assertEquals(List.of(83), returned);
        resumes.removeFirst().run();
        assertTrue(context.carpetCompletion().isDone());
    }

    @Test
    void aSuccessfulForkThatReturnsZeroStillHasOneSuccessfulExecution() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.callback()).thenReturn(CommandResultCallback.EMPTY);
        try (var scope = CarpetAsyncCommandResults.open()) {
            var completion = CarpetAsyncCommandResults.defer(source);
            var trace = scope.traceFuture(source, true);
            completion.complete(true, 0);
            assertEquals(1, trace.join());
        }
    }

    @Test
    void ordinaryNativeCommandChildrenPauseTheRealQueueAndCannotBeCutShortByACancelledContextView() {
        var source = mock(CommandSourceStack.class);
        when(source.callback()).thenReturn(CommandResultCallback.EMPTY);
        var child = new java.util.concurrent.CompletableFuture<Void>();
        var order = new ArrayList<String>();
        var resumes = new ArrayDeque<Runnable>();
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
        dispatcher.register(net.minecraft.commands.Commands.literal("native_children").executes(ctx -> {
            assertFalse(CarpetAsyncCommandResults.hasNativeCause());
            carpet.script.external.ScarpetNativeWork.record(child);
            order.add("native body");
            return 7;
        }));
        var context = new ExecutionContext<CommandSourceStack>(10, 10, mock(net.minecraft.util.profiling.ProfilerFiller.class));
        context.carpetConfigureContinuation(resumes::add, context::runCommandQueue);
        var tracer = mock(TraceCallbacks.class);
        context.tracer(tracer);
        var frame = new Frame(0, CommandResultCallback.EMPTY, context.frameControlForDepth(0));
        var parsed = dispatcher.parse("native_children", source).getContext().build("native_children");
        context.queueNext(new CommandQueueEntry<>(frame, new ExecuteCommand<>("native_children", ChainModifiers.DEFAULT, parsed).bind(source)));
        context.queueNext(new CommandQueueEntry<>(frame, (queue, f) -> order.add("next native command")));
        context.runCommandQueue();
        context.close();
        var caller = context.carpetCompletion();
        assertTrue(caller.cancel(false));
        assertFalse(context.carpetCompletion().isDone());
        verify(tracer, never()).onReturn(anyInt(), anyString(), anyInt());
        verify(tracer, never()).close();
        assertEquals(List.of("native body"), order);
        child.complete(null);
        verify(tracer).onReturn(0, "native_children", 7);
        assertEquals(1, resumes.size());
        resumes.removeFirst().run();
        assertEquals(List.of("native body", "next native command"), order);
        assertTrue(context.carpetCompletion().isDone());
        assertTrue(caller.isCancelled());
        verify(tracer).close();
    }

    @Test
    void aSyntaxFailureCompletesItsDeferredCallbackAndWaitsAcceptedChildrenBeforeTheNextEntry() {
        var source = mock(CommandSourceStack.class);
        when(source.callback()).thenReturn(CommandResultCallback.EMPTY);
        var child = new java.util.concurrent.CompletableFuture<Void>();
        var next = new java.util.concurrent.atomic.AtomicBoolean();
        var resumes = new ArrayDeque<Runnable>();
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
        dispatcher.register(net.minecraft.commands.Commands.literal("bad_async").executes(ctx -> {
            CarpetAsyncCommandResults.defer(ctx.getSource());
            carpet.script.external.ScarpetNativeWork.record(child);
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(net.minecraft.network.chat.Component.literal("actual syntax failure")).create();
        }));
        var context = new ExecutionContext<CommandSourceStack>(10, 10, mock(net.minecraft.util.profiling.ProfilerFiller.class));
        context.carpetConfigureContinuation(resumes::add, context::runCommandQueue);
        var frame = new Frame(0, CommandResultCallback.EMPTY, context.frameControlForDepth(0));
        var parsed = dispatcher.parse("bad_async", source).getContext().build("bad_async");
        context.queueNext(new CommandQueueEntry<>(frame, new ExecuteCommand<>("bad_async", ChainModifiers.DEFAULT, parsed).bind(source)));
        context.queueNext(new CommandQueueEntry<>(frame, (queue, f) -> next.set(true)));
        context.runCommandQueue();
        context.close();
        assertFalse(next.get());
        assertFalse(context.carpetCompletion().isDone());
        verify(source).handleError(any(com.mojang.brigadier.exceptions.CommandSyntaxException.class), eq(false), isNull());
        child.complete(null);
        assertEquals(1, resumes.size());
        resumes.removeFirst().run();
        assertTrue(next.get());
        context.carpetCompletion().join();
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

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path directory;

    @Test
    void theActualConfiguredOwnerResumerRetainsScriptFlagsAndWholeNativeLifetimeAfterHostClose() throws Exception {
        OrgInventoryPersistenceTest.bootstrap();
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var player = fixture.viewer.player();
            var world = player.level();
            fixture.owner.set(player);
            var source = mock(CommandSourceStack.class);
            when(source.getEntity()).thenReturn(player);
            when(source.getServer()).thenReturn(fixture.server);
            when(source.getLevel()).thenReturn(world);
            when(source.getPosition()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
            when(source.callback()).thenReturn(CommandResultCallback.EMPTY);
            var first = new java.util.concurrent.CompletableFuture<Void>();
            var second = new java.util.concurrent.CompletableFuture<Void>();
            var seen = new java.util.concurrent.atomic.AtomicBoolean();
            var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
            dispatcher.register(net.minecraft.commands.Commands.literal("hold_first").executes(ctx -> {
                carpet.script.external.ScarpetNativeWork.record(first);
                return 1;
            }));
            dispatcher.register(net.minecraft.commands.Commands.literal("check_flags").executes(ctx -> {
                assertTrue(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
                assertTrue(carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get());
                assertTrue(carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.get());
                seen.set(true);
                carpet.script.external.ScarpetNativeWork.record(second);
                return 2;
            }));
            var context = new ExecutionContext<CommandSourceStack>(10, 10, mock(net.minecraft.util.profiling.ProfilerFiller.class));
            var frame = new Frame(0, CommandResultCallback.EMPTY, context.frameControlForDepth(0));
            for (String command : List.of("hold_first", "check_flags")) {
                var parsed = dispatcher.parse(command, source).getContext().build(command);
                context.queueNext(new CommandQueueEntry<>(frame, new ExecuteCommand<>(command, ChainModifiers.DEFAULT, parsed).bind(source)));
            }
            Host host = new Host();
            var guest = new ReadyContext(host);
            var actual = carpet.script.external.ScarpetNativeWork.observeNative(null, () -> {
                try (var entered = carpet.script.external.ScarpetRuntime.enterContext(guest)) {
                    boolean fill = carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get(), events = carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get(), generation = carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.get();
                    try {
                        carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
                        carpet.script.external.ScarpetRuntime.EVENT_DISABLED.set(true);
                        carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.set(true);
                        CarpetCommandContextContinuations.configure(source, context, context::runCommandQueue);
                        context.runCommandQueue();
                        context.close();
                    } finally {
                        carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(fill);
                        carpet.script.external.ScarpetRuntime.EVENT_DISABLED.set(events);
                        carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.set(generation);
                    }
                }
                return true;
            });
            host.onClose();
            var caller = context.carpetCompletion();
            assertTrue(caller.cancel(false));
            var idle = carpet.script.external.ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            assertFalse(actual.isDone());
            first.complete(null);
            fixture.drain(fixture.viewer);
            assertTrue(seen.get());
            assertFalse(context.carpetCompletion().isDone());
            assertFalse(idle.isDone());
            assertFalse(actual.isDone());
            second.complete(null);
            fixture.drain(fixture.viewer);
            context.carpetCompletion().join();
            assertTrue(actual.join());
            assertTrue(idle.isDone());
            assertTrue(caller.isCancelled());
            assertFalse(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
            assertFalse(carpet.script.external.ScarpetRuntime.EVENT_DISABLED.get());
            assertFalse(carpet.script.external.ScarpetRuntime.SKIP_GENERATION_CHECKS.get());
        }
    }
}
