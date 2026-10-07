package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.CarpetBlockPredictionFence;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real event receipt, owner replay and block correction sends share one complete native lifetime. */
public class ScarpetDeferredDecisionNativeTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap(); }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel world=mock(ServerLevel.class);
        final ServerPlayer player=mock(ServerPlayer.class);
        final ServerGamePacketListenerImpl connection=mock(ServerGamePacketListenerImpl.class);
        final org.bukkit.craftbukkit.entity.CraftPlayer api=mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
        final io.papermc.paper.threadedregions.EntityScheduler scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();final List<String> order=new ArrayList<>();
        final org.mockito.MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final org.mockito.MockedStatic<MinecraftServer> servers=mockStatic(MinecraftServer.class);
        final boolean previousFill=ScarpetRuntime.FILL_SKIP_UPDATES.get();
        boolean owned=true;

        Fixture() throws Exception {
            when(world.getServer()).thenReturn(server);when(player.level()).thenAnswer(call->{assertTrue(owned);return world;});
            when(player.getBukkitEntity()).thenReturn(api);player.connection=connection;connection.player=player;
            when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            player.containerMenu=mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
            Field field=org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");field.setAccessible(true);field.set(api,scheduler);
            servers.when(MinecraftServer::getServer).thenReturn(server);
            ticks.when(()->TickThread.isTickThreadFor(player)).thenAnswer(call->owned);
            ticks.when(()->TickThread.isTickThreadFor(eq(world),any(BlockPos.class))).thenAnswer(call->owned);
            when(scheduler.schedule(any(),any(),anyLong())).thenAnswer(call->{
                Consumer<net.minecraft.world.entity.Entity> action=call.getArgument(0);
                tasks.add(()->action.accept(player));return true;
            });
            when(world.getBlockStateIfLoaded(any())).thenAnswer(call->{assertTrue(owned);return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();});
        }

        CompletableFuture<Boolean> start(Object key,CompletableFuture<Boolean> decision,Runnable replay,Runnable repair) {
            var actual=ScarpetNativeWork.observeNative(player,()->ScarpetRuntime.deferDecisions(player,key,()->{
                assertTrue(ScarpetRuntime.currentNativeDecision(player,key));return decision;
            },()->true,replay,repair));
            ScarpetNativeWork.trackNative(server,actual);return actual;
        }

        CompletableFuture<Void> receipt(Object key) {
            var result=ScarpetRuntime.nativeDecisionFuture(player,key);assertNotNull(result);return result;
        }

        void run() {
            boolean previous=owned;owned=true;
            try { assertFalse(tasks.isEmpty());tasks.remove().run(); }
            finally {owned=previous;}
        }

        public void close() { ScarpetRuntime.FILL_SKIP_UPDATES.set(previousFill);servers.close();ticks.close(); }
    }

    @Test void readyDecisionReplaysOnItsActualOwnerImmediatelyWithoutScheduling() throws Exception {
        try(var f=new Fixture()) {
            var key=new Object();
            var actual=f.start(key,CompletableFuture.completedFuture(false),()->{
                assertTrue(f.owned);assertSame(f.player,ScarpetNativeWork.capture().owner());assertTrue(ScarpetRuntime.isReplaying(key));f.order.add("replay");
            },()->fail("Allowed event must replay"));
            assertTrue(actual.isDone());assertTrue(actual.join());assertEquals(List.of("replay"),f.order);
            assertNull(ScarpetRuntime.nativeDecisionFuture(f.player,key));assertTrue(f.tasks.isEmpty());
            assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());verify(f.scheduler,never()).schedule(any(),any(),anyLong());
        }
    }

    @Test void replayChildrenHoldItsReceiptPredictionAckAndInventorySnapshotAndRetainOriginalNativeFlags() throws Exception {
        try(var f=new Fixture()) {
            var key=new Object();var decision=new CompletableFuture<Boolean>();var child=new CompletableFuture<Void>();
            ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
            var actual=f.start(key,decision,()->{
                assertTrue(f.owned);assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get());assertTrue(ScarpetRuntime.isReplaying(key));
                f.order.add("replay");ScarpetNativeWork.record(child);
            },()->fail("Allowed event must replay"));
            ScarpetRuntime.FILL_SKIP_UPDATES.set(false);
            var tail=f.receipt(key);CarpetBlockPredictionFence.retain(f.connection,4,tail);
            f.owned=false;decision.complete(false);assertEquals(1,f.tasks.size());f.run();
            assertEquals(List.of("replay"),f.order);assertFalse(actual.isDone());assertFalse(tail.isDone());
            assertEquals(3,CarpetBlockPredictionFence.readyThrough(f.connection,6));assertFalse(ScarpetNativeWork.whenIdle(f.server).isDone());
            f.owned=true;var snapshot=ScarpetPlayerInventoryGate.whenIdle(f.player,()->{f.order.add("snapshot");return 1;});
            assertFalse(snapshot.isDone());assertTrue(ScarpetPlayerInventoryGate.paused(f.player));
            f.owned=false;child.complete(null);assertTrue(actual.join());tail.join();assertEquals(6,CarpetBlockPredictionFence.readyThrough(f.connection,6));
            assertFalse(snapshot.isDone());f.run();assertEquals(1,snapshot.join().intValue());
            assertEquals(List.of("replay","snapshot"),f.order);assertFalse(ScarpetRuntime.FILL_SKIP_UPDATES.get());
            assertFalse(ScarpetPlayerInventoryGate.paused(f.player));assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
        }
    }

    @Test void failedEventWaitsRealBlockCorrectionSendChildrenBeforeSettlingItsOriginalFailure() throws Exception {
        try(var f=new Fixture()) {
            var key=new Object();var decision=new CompletableFuture<Boolean>();var sent=new CompletableFuture<Void>();
            var failure=new IllegalStateException("Actual script decision failure");
            doAnswer(call->{assertTrue(f.owned);f.order.add("correction packet");ScarpetNativeWork.record(sent);return null;}).when(f.connection).send(any(Packet.class));
            var actual=f.start(key,decision,()->fail("Failed event must not replay"),()->CarpetBlockPredictionFence.resyncBlocks(f.connection,f.world,BlockPos.ZERO));
            var tail=f.receipt(key);CarpetBlockPredictionFence.retain(f.connection,8,tail);
            f.owned=false;decision.completeExceptionally(failure);f.run();
            assertEquals(List.of("correction packet"),f.order);assertFalse(tail.isDone());assertFalse(actual.isDone());
            assertEquals(7,CarpetBlockPredictionFence.readyThrough(f.connection,9));assertFalse(ScarpetNativeWork.whenIdle(f.server).isDone());
            sent.complete(null);
            assertSame(failure,assertThrows(CompletionException.class,tail::join).getCause());
            assertSame(failure,assertThrows(CompletionException.class,actual::join).getCause());
            assertEquals(9,CarpetBlockPredictionFence.readyThrough(f.connection,9));assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
        }
    }

    @Test void failedCorrectionChildAndTheOriginalEventFailureAreBothRetainedAfterAllNativeChildrenEnd() throws Exception {
        try(var f=new Fixture()) {
            var key=new Object();var decision=new CompletableFuture<Boolean>();var failedChild=new CompletableFuture<Void>();var laterChild=new CompletableFuture<Void>();
            var eventFailure=new IllegalArgumentException("Script failure");var nativeFailure=new IllegalStateException("Correction send failed");
            var actual=f.start(key,decision,()->fail("Failed event must not replay"),()->{ScarpetNativeWork.record(failedChild);ScarpetNativeWork.record(laterChild);});
            var tail=f.receipt(key);f.owned=false;decision.completeExceptionally(eventFailure);f.run();
            failedChild.completeExceptionally(nativeFailure);assertFalse(tail.isDone());assertFalse(actual.isDone());
            laterChild.complete(null);assertSame(nativeFailure,assertThrows(CompletionException.class,tail::join).getCause());
            assertSame(nativeFailure,assertThrows(CompletionException.class,actual::join).getCause());
            assertTrue(List.of(nativeFailure.getSuppressed()).contains(eventFailure));assertFalse(ScarpetNativeWork.onlyGuestFailure(nativeFailure));
        }
    }

    @Test void replayWhichThrowsAfterCreatingAChildStillWaitsThatChildAndExposesTheNativeFailure() throws Exception {
        try(var f=new Fixture()) {
            var key=new Object();var decision=new CompletableFuture<Boolean>();var child=new CompletableFuture<Void>();var failure=new IllegalStateException("Replay body failed");
            var actual=f.start(key,decision,()->{ScarpetNativeWork.record(child);throw failure;},()->fail("Allowed event must replay"));
            var tail=f.receipt(key);f.owned=false;decision.complete(false);f.run();assertFalse(tail.isDone());assertFalse(actual.isDone());
            child.complete(null);assertSame(failure,assertThrows(CompletionException.class,tail::join).getCause());
            assertSame(failure,assertThrows(CompletionException.class,actual::join).getCause());assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
        }
    }

    @Test void nativeRemovalReachedByTheReplayCannotWaitOnItsOwnAcceptedDecisionReceipt() throws Exception {
        try(var f=new Fixture()) {
            var key=new Object();var decision=new CompletableFuture<Boolean>();var cleanupChild=new CompletableFuture<Void>();
            var actual=f.start(key,decision,()->{
                var removal=ScarpetPlayerInventoryGate.whenIdleForRemoval(f.player,()->{
                    assertTrue(f.owned);f.order.add("removal");return cleanupChild;
                });
                ScarpetNativeWork.record(removal);
            },()->fail("Allowed event must replay"));
            var tail=f.receipt(key);f.owned=false;decision.complete(false);f.run();assertFalse(tail.isDone());
            assertEquals(List.of("removal"),f.order,"Mandatory cleanup must be admitted, rather than waiting on this replay");
            assertTrue(f.tasks.isEmpty(),"Owned cleanup must not add an artificial entity tick");
            assertFalse(actual.isDone());assertTrue(ScarpetPlayerInventoryGate.paused(f.player));assertFalse(ScarpetNativeWork.whenIdle(f.server).isDone());
            cleanupChild.complete(null);tail.join();assertTrue(actual.join());
            assertFalse(ScarpetPlayerInventoryGate.paused(f.player));assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
        }
    }

    @Test void lookupSchedulingFalseAndRetirementFailuresAllTerminateTheOriginalDecisionReceipt() throws Exception {
        for(int fault=0;fault<4;fault++)try(var f=new Fixture()) {
            var key=new Object();var decision=new CompletableFuture<Boolean>();var problem=new IllegalStateException("Scheduler fault "+fault);
            var actual=f.start(key,decision,()->fail("Rejected owner must not replay"),()->fail("Rejected owner cannot resync"));
            var tail=f.receipt(key);f.owned=false;
            if(fault==0)doThrow(problem).when(f.player).getBukkitEntity();
            else if(fault==1)doThrow(problem).when(f.scheduler).schedule(any(),any(),anyLong());
            else if(fault==2)doReturn(false).when(f.scheduler).schedule(any(),any(),anyLong());
            else doAnswer(call->{call.<Consumer<net.minecraft.world.entity.Entity>>getArgument(1).accept(f.player);return true;}).when(f.scheduler).schedule(any(),any(),anyLong());
            decision.complete(false);assertTrue(tail.isCompletedExceptionally());assertTrue(actual.isCompletedExceptionally());
            var cause=assertThrows(CompletionException.class,tail::join).getCause();
            if(fault<2)assertSame(problem,cause);else assertInstanceOf(carpet.script.exception.InternalExpressionException.class,cause);
            assertNull(ScarpetRuntime.nativeDecisionFuture(f.player,key));assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
        }
    }

    @Test void shutdownCancelsWaitingDecisionsButKeepsAnAdmittedNativeBodyAndItsSnapshotGateUntilItsRealChildEnds() throws Exception {
        try(var f=new Fixture()) {
            var key=new Object();var decision=new CompletableFuture<Boolean>();var child=new CompletableFuture<Void>();
            var actual=f.start(key,decision,()->ScarpetNativeWork.record(child),()->fail("Allowed event must replay"));
            var tail=f.receipt(key);f.owned=false;decision.complete(false);f.run();
            f.owned=true;var snapshot=ScarpetPlayerInventoryGate.whenIdle(f.player,()->1);assertTrue(ScarpetPlayerInventoryGate.paused(f.player));
            var closed=new CompletableFuture<Void>();assertTrue(ScarpetRuntime.beginShutdown(f.server,()->closed.complete(null)));
            closed.get(3,TimeUnit.SECONDS);assertFalse(tail.isDone());assertFalse(actual.isDone());assertFalse(snapshot.isDone());
            assertFalse(ScarpetNativeWork.whenIdle(f.server).isDone());
            f.owned=false;child.complete(null);tail.join();assertTrue(actual.join());f.run();assertEquals(1,snapshot.join().intValue());
            assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
        }
        try(var f=new Fixture()) {
            var key=new Object();var decision=new CompletableFuture<Boolean>();
            var actual=f.start(key,decision,()->fail("Unadmitted decision was cancelled at shutdown"),()->fail("Unadmitted decision was cancelled at shutdown"));
            var tail=f.receipt(key);var closed=new CompletableFuture<Void>();ScarpetRuntime.beginShutdown(f.server,()->closed.complete(null));
            closed.get(3,TimeUnit.SECONDS);assertTrue(tail.isCompletedExceptionally());assertTrue(actual.isCompletedExceptionally());
            decision.complete(false);assertTrue(f.tasks.isEmpty());assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
        }
    }
}
