package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgMailCommandReceiptsTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    private static CommandSourceStack source(OrgInventoryPersistenceTest.Fixture f) {
        when(f.viewer.player().blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        var source = mock(CommandSourceStack.class);
        when(source.getServer()).thenReturn(f.server);
        when(source.getEntity()).thenReturn(f.viewer.player());
        when(source.getPlayer()).thenReturn(f.viewer.player());
        when(source.callback()).thenReturn(CommandResultCallback.EMPTY);
        doAnswer(call -> {
            f.viewer.player().sendSystemMessage(((Supplier<Component>) call.getArgument(0)).get());
            return null;
        }).when(source).sendSuccess(any(), eq(false));
        return source;
    }

    private static int report(CommandSourceStack source, CompletableFuture<?> operation) {
        try {
            var method = OrgMailCommands.class.getDeclaredMethod("report", CommandSourceStack.class, Supplier.class);
            method.setAccessible(true);
            return (Integer) method.invoke(null, source, (Supplier<CompletableFuture<?>>) () -> operation);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void realReportWaitsServiceResultAndThenItsActualOwnerCallbackChildren() throws Exception {
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            var source = source(f);
            var service = new CompletableFuture<Boolean>();
            var child = new CompletableFuture<Void>();
            when(source.callback()).thenReturn((success, value) -> {
                assertSame(f.viewer.player(), f.owner.get());
                assertTrue(success);
                assertEquals(1, value);
                ScarpetNativeWork.record(child);
            });
            f.owner.set(null);
            var parent = ScarpetNativeWork.observeNative(f.viewer.player(), () -> report(source, service));
            var result = scope.resultFuture(source);
            assertFalse(result.isDone());
            assertFalse(parent.isDone());
            service.complete(true);
            assertFalse(result.isDone());
            f.drain(f.viewer);
            assertFalse(result.isDone());
            assertFalse(parent.isDone());
            child.complete(null);
            assertEquals(1, result.get(3, TimeUnit.SECONDS));
            assertEquals(1, parent.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualFalseWaitsCancellationFeedbackChildrenAndReportsZero() throws Exception {
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            var source = source(f);
            var service = new CompletableFuture<Boolean>();
            var child = new CompletableFuture<Void>();
            var messages = new java.util.ArrayList<String>();
            doAnswer(call -> {
                assertSame(f.viewer.player(), f.owner.get());
                messages.add(((Component) call.getArgument(0)).getString());
                ScarpetNativeWork.record(child);
                return null;
            }).when(f.viewer.player()).sendSystemMessage(any(Component.class));
            f.owner.set(null);
            var parent = ScarpetNativeWork.observeNative(f.viewer.player(), () -> report(source, service));
            var result = scope.resultFuture(source);
            service.complete(false);
            assertEquals(List.of(), messages);
            f.drain(f.viewer);
            assertEquals(List.of("Mail operation cancelled; retry after the inventory transaction finishes"), messages);
            assertFalse(result.isDone());
            assertFalse(parent.isDone());
            child.complete(null);
            f.drain(f.viewer);
            assertEquals(0, result.get(3, TimeUnit.SECONDS));
            assertEquals(1, parent.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void nativeServiceFailureIsRetainedAndFeedbackCompletesBeforeZeroCallback() throws Exception {
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            var source = source(f);
            var service = new CompletableFuture<Boolean>();
            var failure = new IllegalStateException("actual storage failed");
            f.owner.set(null);
            var parent = ScarpetNativeWork.observeNative(f.viewer.player(), () -> report(source, service));
            var result = scope.resultFuture(source);
            service.completeExceptionally(new CompletionException(failure));
            assertFalse(result.isDone());
            f.drain(f.viewer);
            assertEquals(0, result.get(3, TimeUnit.SECONDS));
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, parent::join)));
            verify(f.viewer.player()).sendSystemMessage(argThat(c -> c.getString().equals("Mail: actual storage failed")));
        }
    }

    @Test
    void registeredListWaitsActualLinesAndTheirNativeSendTails() throws Exception {
        try (var f = new OrgInventoryPersistenceTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open(); var services = mockStatic(OrgMailService.class); var permissions = mockStatic(OrgUtilityCommands.class)) {
            var source = source(f);
            when(source.getPlayerOrException()).thenReturn(f.viewer.player());
            var service = mock(OrgMailService.class);
            services.when(() -> OrgMailService.get(f.server)).thenReturn(service);
            permissions.when(() -> OrgUtilityCommands.permitted(eq(source), anyString())).thenReturn(true);
            var lines = new CompletableFuture<List<Component>>();
            when(service.list(f.viewer.player())).thenReturn(lines);
            var child = new CompletableFuture<Void>();
            var messages = new java.util.ArrayList<String>();
            doAnswer(call -> {
                assertSame(f.viewer.player(), f.owner.get());
                messages.add(((Component) call.getArgument(0)).getString());
                ScarpetNativeWork.record(child);
                return null;
            }).when(f.viewer.player()).sendSystemMessage(any(Component.class));
            var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
            OrgMailCommands.register(dispatcher);
            f.owner.set(null);
            var parent = ScarpetNativeWork.observeNative(f.viewer.player(), () -> {
                try {
                    return dispatcher.execute("mail list", source);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            var result = scope.resultFuture(source);
            assertNotNull(result);
            lines.complete(List.of(Component.literal("parcel 4"), Component.literal("parcel 8")));
            f.drain(f.viewer);
            assertEquals(List.of("", "There are currently 2 pieces of mail awaiting processing:", "parcel 4", "parcel 8"), messages);
            assertFalse(result.isDone());
            assertFalse(parent.isDone());
            child.complete(null);
            f.drain(f.viewer);
            assertEquals(2, result.get(3, TimeUnit.SECONDS));
            assertEquals(1, parent.get(3, TimeUnit.SECONDS));
        }
    }
}
