package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import carpet.script.external.ScarpetPlayerInventoryGate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.phys.AABB;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgBeaconNativeEffectsTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private static void guest(Throwable failure)throws Exception{var method=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);method.setAccessible(true);method.invoke(null,failure);}
    private static final BlockPos ORIGIN=new BlockPos(7,20,9);
    private static final AABB BOUNDS=new AABB(-20,-128,-20,40,448,40);
    private static void prepare(OrgInventoryPersistenceTest.Fixture fixture){when(fixture.viewer.player().blockPosition()).thenReturn(ORIGIN);when(fixture.target.player().blockPosition()).thenReturn(ORIGIN);}
    @Test void actualRecipientReceivesOriginalSourceAndDynamicPrimaryTailBeforeSecondaryDespiteGuestFailure()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            prepare(fixture);
            var player=fixture.target.player();var source=player.level();when(player.getBoundingBox()).thenReturn(new AABB(8,21,10,9,23,11));
            var order=new ArrayList<String>();var guestChild=new CompletableFuture<Void>();var first=new CompletableFuture<Void>();var late=new CompletableFuture<Void>();var secondary=new CompletableFuture<Void>();var nativeReceipt=new AtomicReference<CompletableFuture<Void>>();var append=new AtomicReference<Runnable>();
            var parent=ScarpetNativeWork.observeNative(null,()->{nativeReceipt.set(OrgBeaconEffects.applyNative(source,ORIGIN,BOUNDS,true,(recipient,originalWorld,origin,primary)->{
                assertSame(player,recipient);assertSame(player,fixture.owner.get());assertSame(source,originalWorld);assertEquals(ORIGIN,origin);
                order.add(primary?"primary":"secondary");if(primary){ScarpetNativeWork.record(guestChild);ScarpetNativeWork.record(first);var continuation=ScarpetRuntime.captureNativeContinuation(()->{ScarpetNativeWork.record(late);return null;});append.set(()->{continuation.get();first.complete(null);});}else ScarpetNativeWork.record(secondary);
            }));return null;});
            assertFalse(nativeReceipt.get().isDone());fixture.drain(fixture.viewer);fixture.drain(fixture.target);assertEquals(List.of("primary"),order);var guest=new IllegalArgumentException("actual beacon Guest callback");guest(guest);guestChild.completeExceptionally(guest);append.get().run();assertEquals(List.of("primary"),order);assertFalse(nativeReceipt.get().isDone());
            late.complete(null);assertEquals(List.of("primary","secondary"),order);assertFalse(nativeReceipt.get().isDone());assertFalse(parent.isDone());assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());secondary.complete(null);nativeReceipt.get().join();assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,parent::join)));ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }
    @Test void aRealPrimaryNativeFailureBlocksTheSecondaryAndKeepsNativeFailureClassification()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            prepare(fixture);
            var player=fixture.target.player();var source=player.level();when(player.getBoundingBox()).thenReturn(new AABB(8,21,10,9,23,11));var child=new CompletableFuture<Void>();var order=new ArrayList<String>();
            var actual=OrgBeaconEffects.applyNative(source,ORIGIN,BOUNDS,true,(recipient,originalWorld,origin,primary)->{order.add(primary?"primary":"secondary");ScarpetNativeWork.record(child);});fixture.drain(fixture.viewer);fixture.drain(fixture.target);assertEquals(List.of("primary"),order);child.completeExceptionally(new IllegalStateException("actual effect Native failure"));assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,actual::join)));assertEquals(List.of("primary"),order);
        }
    }
    @Test void theWholeAcceptedBeaconTailKeepsAnInventorySnapshotBehindSecondaryEffect()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            prepare(fixture);
            var player=fixture.target.player();var source=player.level();when(player.getBoundingBox()).thenReturn(new AABB(8,21,10,9,23,11));var primary=new CompletableFuture<Void>();var order=new ArrayList<String>();
            var actual=OrgBeaconEffects.applyNative(source,ORIGIN,BOUNDS,true,(recipient,originalWorld,origin,isPrimary)->{order.add(isPrimary?"primary":"secondary");if(isPrimary)ScarpetNativeWork.record(primary);});fixture.drain(fixture.viewer);fixture.drain(fixture.target);var snapshot=ScarpetPlayerInventoryGate.whenIdle(player,()->List.copyOf(order));assertFalse(snapshot.isDone());assertTrue(ScarpetPlayerInventoryGate.paused(player));primary.complete(null);assertEquals(List.of("primary","secondary"),order);actual.join();assertFalse(snapshot.isDone());fixture.drain(fixture.target);assertEquals(List.of("primary","secondary"),snapshot.join());
        }
    }
    @Test void actualBeaconBoundsMatchNegativeRangeCollapseAndWholeWorldHeight()throws Exception{
        int old=GeneralCompatConfig.beaconRangeExpand;boolean oldHeight=GeneralCompatConfig.beaconWorldHeight;
        try{
            var world=mock(net.minecraft.server.level.ServerLevel.class);when(world.getHeight()).thenReturn(384);when(world.getMinY()).thenReturn(-64);when(world.getMaxY()).thenReturn(320);var method=BeaconBlockEntity.class.getDeclaredMethod("carpetEffectBounds",Level.class,BlockPos.class,double.class);method.setAccessible(true);
            GeneralCompatConfig.beaconRangeExpand=-100;GeneralCompatConfig.beaconWorldHeight=true;AABB collapsed=(AABB)method.invoke(null,world,ORIGIN,50D);assertEquals(7,collapsed.minX);assertEquals(8,collapsed.maxX);assertEquals(9,collapsed.minZ);assertEquals(10,collapsed.maxZ);assertEquals(-128,collapsed.minY);assertEquals(384,collapsed.maxY);
            GeneralCompatConfig.beaconRangeExpand=1024;GeneralCompatConfig.beaconWorldHeight=false;AABB expanded=(AABB)method.invoke(null,world,ORIGIN,50D);assertEquals(-1067,expanded.minX);assertEquals(1082,expanded.maxX);assertEquals(-30,expanded.minY);assertEquals(455,expanded.maxY);
            GeneralCompatConfig.beaconRangeExpand=1025;AABB ignored=(AABB)method.invoke(null,world,ORIGIN,50D);assertEquals(-43,ignored.minX);assertEquals(58,ignored.maxX);
        }finally{GeneralCompatConfig.beaconRangeExpand=old;GeneralCompatConfig.beaconWorldHeight=oldHeight;}
    }
}
