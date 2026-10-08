package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgHiddenFeedbackReceiptTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    @Test
    void hiddenFailureWaitsRealGlobalAdmissionRecipientAndLateNativeChildren() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            var server = mock(io.papermc.paper.threadedregions.RegionizedServer.class);
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(server);
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(false);
            var globalQueue = new ArrayDeque<Runnable>();
            doAnswer(call -> {
                globalQueue.add(call.getArgument(0));
                return null;
            }).when(server).addTask(any());
            var bot = fixture.target.player();
            fixture.owner.set(bot);
            when(bot.blockPosition()).thenReturn(BlockPos.ZERO);
            when(fixture.viewer.player().blockPosition()).thenReturn(BlockPos.ZERO);
            OrgHiddenPlayerActions.pendingCompletion(bot);
            var holders = OrgHiddenPlayerActions.class.getDeclaredField("PLAYERS");
            holders.setAccessible(true);
            var holder = ((java.util.Map<?, ?>) holders.get(null)).get(bot.getUUID());
            var method = OrgHiddenPlayerActions.class.getDeclaredMethod("fail", net.minecraft.server.level.ServerPlayer.class, holder.getClass(), Throwable.class);
            method.setAccessible(true);
            var child = new CompletableFuture<Void>();
            var late = new CompletableFuture<Void>();
            var sent = new AtomicInteger();
            doAnswer(call -> {
                assertSame(fixture.viewer.player(), fixture.owner.get());
                sent.incrementAndGet();
                ScarpetNativeWork.record(child);
                child.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((ignored, failure) -> ScarpetNativeWork.record(late)));
                return null;
            }).when(fixture.viewer.player()).sendSystemMessage(any(Component.class));
            var parent = ScarpetNativeWork.observeNative(bot, () -> {
                try {
                    method.invoke(null, bot, holder, new IllegalStateException("hidden native failed"));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                return null;
            });
            assertFalse(parent.isDone());
            assertEquals(1, globalQueue.size());
            assertEquals(0, sent.get());
            globalQueue.remove().run();
            assertEquals(0, sent.get());
            fixture.drain(fixture.viewer);
            assertEquals(1, sent.get());
            assertFalse(parent.isDone());
            assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            child.complete(null);
            assertFalse(parent.isDone());
            late.complete(null);
            parent.join();
            ScarpetNativeWork.whenIdle(fixture.server).join();
            OrgHiddenPlayerActions.onRetired(bot);
        }
    }

    @Test
    void hiddenRecipientNativeFailureIsRetainedByTheActualFailureBroadcast() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);
            var bot = fixture.target.player();
            fixture.owner.set(bot);
            when(bot.blockPosition()).thenReturn(BlockPos.ZERO);
            when(fixture.viewer.player().blockPosition()).thenReturn(BlockPos.ZERO);
            OrgHiddenPlayerActions.pendingCompletion(bot);
            var holders = OrgHiddenPlayerActions.class.getDeclaredField("PLAYERS");
            holders.setAccessible(true);
            var holder = ((java.util.Map<?, ?>) holders.get(null)).get(bot.getUUID());
            var method = OrgHiddenPlayerActions.class.getDeclaredMethod("fail", net.minecraft.server.level.ServerPlayer.class, holder.getClass(), Throwable.class);
            method.setAccessible(true);
            var child = new CompletableFuture<Void>();
            doAnswer(call -> {
                ScarpetNativeWork.record(child);
                return null;
            }).when(fixture.viewer.player()).sendSystemMessage(any(Component.class));
            var parent = ScarpetNativeWork.observeNative(bot, () -> {
                try {
                    method.invoke(null, bot, holder, new IllegalStateException("hidden native failed"));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                return null;
            });
            fixture.drain(fixture.viewer);
            assertFalse(parent.isDone());
            child.completeExceptionally(new IllegalStateException("real recipient native failure"));
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, parent::join)));
            OrgHiddenPlayerActions.onRetired(bot);
        }
    }
}
