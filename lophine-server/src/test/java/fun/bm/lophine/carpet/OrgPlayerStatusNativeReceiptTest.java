package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/** Actual source clock, inventory/read/send actor bodies and private cancellation-safe Native receipts. */
public class OrgPlayerStatusNativeReceiptTest {
    @TempDir Path directory;
    boolean previous;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    @BeforeEach void enable(){previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.autoSyncPlayerStatus;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.autoSyncPlayerStatus=true;}
    @AfterEach void restore(){fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.autoSyncPlayerStatus=previous;}
    private static final class Fixture implements AutoCloseable{
        final OrgInventoryPersistenceTest.Fixture actors;
        final net.minecraft.server.level.ServerPlayer player;
        final net.minecraft.server.level.ServerLevel world;
        final CompletableFuture<Void> infoChild=new CompletableFuture<>(),readChild=new CompletableFuture<>(),sendChild=new CompletableFuture<>();
        final AtomicInteger infoCalls=new AtomicInteger(),reads=new AtomicInteger(),sent=new AtomicInteger();
        final AtomicBoolean fullOwner=new AtomicBoolean(),firstRead=new AtomicBoolean(true);
        final ArrayList<Function<Object,Object>> pending=new ArrayList<>();
        final MockedStatic<CarpetRegionLease> leases;
        boolean delayInfo,delayRead,delaySend,checkFlags;
        long gameTime=30;
        double interactionRange=20;
        Fixture(Path directory)throws Exception{
            actors=new OrgInventoryPersistenceTest.Fixture(directory);player=actors.target.player();world=player.level();when(player.blockPosition()).thenReturn(new BlockPos(15,64,15));when(player.position()).thenReturn(new Vec3(15.5,64,15.5));when(player.blockInteractionRange()).thenAnswer(call->interactionRange);when(world.getGameTime()).thenAnswer(call->gameTime);
            var listener=mock(ServerGamePacketListenerImpl.class);var field=net.minecraft.server.level.ServerPlayer.class.getField("connection");field.setAccessible(true);field.set(player,listener);
            var players=actors.server.getPlayerList();doAnswer(call->{assertSame(player,actors.owner.get());flags();infoCalls.incrementAndGet();if(delayInfo)ScarpetNativeWork.record(infoChild);return null;}).when(players).sendAllPlayerInfo(player);
            when(world.getBlockState(any(BlockPos.class))).thenAnswer(call->{assertTrue(fullOwner.get());flags();reads.incrementAndGet();if(delayRead&&firstRead.getAndSet(false))ScarpetNativeWork.record(readChild);return Blocks.AIR.defaultBlockState();});
            doAnswer(call->{assertSame(player,actors.owner.get());flags();sent.incrementAndGet();if(delaySend)ScarpetNativeWork.record(sendChild);return null;}).when(listener).send(any(ClientboundBlockUpdatePacket.class));
            leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();leases.when(()->CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any(Function.class))).thenAnswer(call->{
                int radius=(int)Math.min(interactionRange+1,8);assertEquals((15-radius)>>4,call.<Integer>getArgument(1));assertEquals((15-radius)>>4,call.<Integer>getArgument(2));assertEquals((15+radius)>>4,call.<Integer>getArgument(3));assertEquals((15+radius)>>4,call.<Integer>getArgument(4));var actual=new CompletableFuture<Object>();pending.add(lease->{try{actual.complete(call.<Function<Object,Object>>getArgument(5).apply(null));}catch(Throwable failure){actual.completeExceptionally(failure);}return null;});return actual;
            });actors.owner.set(player);
        }
        void flags(){if(checkFlags){assertTrue(OrgGameplayHelper.toolNoBreakActive());assertTrue(fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.CHANNELING_TRIDENT.orElse(false));assertSame(player,OrgGameplayHelper.blockBreaker());}}
        void tick(){actors.owner.set(player);OrgPlayerStatusSync.tick(player);actors.owner.set(null);}
        void read(){actors.owner.set(null);fullOwner.set(true);try{var jobs=java.util.List.copyOf(pending);pending.clear();jobs.forEach(job->job.apply(null));}finally{fullOwner.set(false);}}
        void player(){actors.drain(actors.target);actors.owner.set(null);}
        @Override public void close(){infoChild.complete(null);readChild.complete(null);sendChild.complete(null);for(int i=0;i<8;i++){read();player();}OrgPlayerStatusSync.removed(player);leases.close();actors.close();}
    }
    @Test void originalClockCappedSphereAndTrueInventoryFullReadPacketChildrenRetainSourceFlagsAndPrivateReceipt()throws Exception{
        try(var f=new Fixture(directory)){
            f.delayInfo=f.delayRead=f.delaySend=f.checkFlags=true;f.gameTime=29;f.tick();assertEquals(0,f.infoCalls.get());f.gameTime=30;
            var parent=ScarpetNativeWork.observeNative(f.player,()->new OrgGameplayHelper.NativeRuleScopes(true,f.player,true,true).call(()->{f.tick();return 37;}));var caller=OrgPlayerStatusSync.completion(f.player);var idle=ScarpetNativeWork.whenIdle(f.actors.server);assertTrue(caller.cancel(false));assertFalse(idle.isDone());assertEquals(1,f.infoCalls.get());assertEquals(0,f.pending.size());
            f.infoChild.complete(null);assertEquals(1,f.pending.size());f.read();assertEquals(OrgPlayerStatusSync.positions(new BlockPos(15,64,15),8).size(),f.reads.get());assertEquals(0,f.sent.get());assertFalse(parent.isDone());assertFalse(idle.isDone());
            f.readChild.complete(null);f.player();assertEquals(f.reads.get(),f.sent.get());assertFalse(idle.isDone());f.sendChild.complete(null);assertEquals(37,parent.join());idle.join();assertTrue(caller.isCancelled());assertNull(OrgGameplayHelper.blockBreaker());assertFalse(OrgGameplayHelper.toolNoBreakActive());f.tick();assertEquals(1,f.infoCalls.get());
        }
    }
    @Test void resetDuringAdmittedWorldReadWaitsRealChildrenAndSkipsOnlyFutureDelivery()throws Exception{
        try(var f=new Fixture(directory)){
            f.delayRead=true;f.tick();var actual=OrgPlayerStatusSync.completion(f.player);f.read();assertFalse(actual.isDone());OrgPlayerStatusSync.reset(f.actors.server);var idle=ScarpetNativeWork.whenIdle(f.actors.server);assertFalse(idle.isDone());assertFalse(actual.isDone());assertTrue(f.reads.get()>0);f.readChild.complete(null);f.player();assertFalse(actual.join());assertEquals(0,f.sent.get());idle.join();
        }
    }
    @Test void removalWhileFullLeaseWaitingDoesNotCancelOrFinishActualWaitingPhysicalJob()throws Exception{
        try(var f=new Fixture(directory)){
            f.tick();var actual=OrgPlayerStatusSync.completion(f.player);OrgPlayerStatusSync.removed(f.player);var idle=ScarpetNativeWork.whenIdle(f.actors.server);assertFalse(actual.isDone());assertFalse(idle.isDone());f.read();f.player();assertFalse(actual.join());assertEquals(0,f.reads.get());assertEquals(0,f.sent.get());idle.join();
        }
    }
    @Test void alreadySentPacketNativeFailureIsGenuineAndResetCannotReplaceItsAdmittedReceiptWithFalse()throws Exception{
        try(var f=new Fixture(directory)){
            f.delaySend=true;f.interactionRange=0;f.tick();var actual=OrgPlayerStatusSync.completion(f.player);f.read();f.player();assertEquals(7,f.sent.get());OrgPlayerStatusSync.reset(f.actors.server);assertFalse(actual.isDone());var failure=new IllegalStateException("physical packet child failed");f.sendChild.completeExceptionally(failure);Throwable error=assertThrows(CompletionException.class,actual::join);while(error.getCause()!=null)error=error.getCause();assertSame(failure,error);assertFalse(ScarpetNativeWork.onlyGuestFailure(error));
        }
    }
    @Test void resetOneServerDoesNotDisableAnotherServerSourceThirtyTickSync()throws Exception{
        try(var first=new Fixture(directory.resolve("first"))){OrgPlayerStatusSync.reset(first.actors.server);first.tick();assertEquals(0,first.infoCalls.get());}
        try(var second=new Fixture(directory.resolve("second"))){second.tick();var actual=OrgPlayerStatusSync.completion(second.player);second.read();second.player();assertTrue(actual.join());assertEquals(1,second.infoCalls.get());assertEquals(second.reads.get(),second.sent.get());}
    }
}
