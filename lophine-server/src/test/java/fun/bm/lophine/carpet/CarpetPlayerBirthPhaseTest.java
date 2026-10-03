package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarpetPlayerBirthPhaseTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    private final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);final ServerLevel world=mock(ServerLevel.class);final ServerPlayer player=mock(ServerPlayer.class);
        final org.mockito.MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final org.mockito.MockedStatic<ScarpetExplosionActors> actors=mockStatic(ScarpetExplosionActors.class);
        final org.mockito.MockedStatic<CarpetRegionLease> leases=mockStatic(CarpetRegionLease.class);
        Fixture(){
            when(player.level()).thenReturn(world);when(world.getServer()).thenReturn(server);when(player.carpetSpawnServer()).thenReturn(server);when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            ticks.when(()->TickThread.isTickThreadFor(player)).thenReturn(true);
            actors.when(()->ScarpetExplosionActors.entity(eq(player),any(Supplier.class))).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
            leases.when(()->CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any(Function.class))).thenAnswer(call->CompletableFuture.completedFuture(((Function<?,?>)call.getArgument(5)).apply(null)));
        }
        public void close(){leases.close();actors.close();ticks.close();}
    }
    @Test void cancelingTheCallerDoesNotEndTheActualBirthPhaseOrItsNativeChildren(){
        try(var f=new Fixture()){
            var birth=new CompletableFuture<Void>();var child=new CompletableFuture<Void>();CarpetPlayerBirths.admitPlayer(f.player,birth);
            try{
                assertTrue(ScarpetPlayerInventoryGate.paused(f.player));
                var caller=CarpetPlayerBirthPhase.run(f.player,()->{assertFalse(ScarpetPlayerInventoryGate.paused(f.player));ScarpetNativeWork.record(child);});
                assertTrue(ScarpetPlayerInventoryGate.paused(f.player));var idle=ScarpetNativeWork.whenIdle(f.server);
                assertTrue(caller.cancel(false));assertFalse(idle.isDone());child.complete(null);
                assertTrue(idle.isDone());assertTrue(caller.isCancelled());assertTrue(CarpetPlayerBirths.playerPending(f.player));
            }finally{birth.complete(null);}
            assertFalse(ScarpetPlayerInventoryGate.paused(f.player));
        }
    }
    @Test void aTrueBodyFailureWaitsItsAlreadyAcceptedChildrenAndRemainsANativeFailure(){
        try(var f=new Fixture()){
            var child=new CompletableFuture<Void>();var problem=new IllegalStateException("actual native initializer");
            var caller=CarpetPlayerBirthPhase.run(f.player,()->{ScarpetNativeWork.record(child);throw problem;});
            var idle=ScarpetNativeWork.whenIdle(f.server);assertFalse(caller.isDone());assertFalse(idle.isDone());child.complete(null);
            Throwable failure=assertThrows(CompletionException.class,caller::join).getCause();assertSame(problem,failure);assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));assertTrue(idle.isDone());
        }
    }
}
