package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetDragonDeathContinuations;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRenewableDragonHead;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CarpetDragonAiReceiptGuardTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void actualAcceptedHurtBlocksAiBeforeItsFirstNativeMovementUntilRealChildrenFinish() {
        var world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(mock(MinecraftServer.class));
        var dragon = mock(EnderDragon.class, CALLS_REAL_METHODS);
        doReturn(world).when(dragon).level();
        doReturn(BlockPos.ZERO).when(dragon).blockPosition();
        var child = new CompletableFuture<Void>();
        doAnswer(call -> {
            ScarpetNativeWork.record(child);
            return null;
        }).when(dragon).carpetHurtPrefix(any(), any(), anyFloat());
        var moved = new IllegalStateException("actual first movement");
        doThrow(moved).when(dragon).isFlapping();
        try (var ticks = mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)) {
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(dragon)).thenReturn(true);
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
            var source = mock(net.minecraft.world.damagesource.DamageSource.class);
            var actual = ScarpetRenewableDragonHead.hurt(dragon, world, mock(EnderDragonPart.class), source, 3F);
            assertFalse(actual.isDone());
            dragon.aiStep();
            verify(dragon, never()).isFlapping();
            child.complete(null);
            assertFalse(actual.join());
            assertSame(moved, assertThrows(IllegalStateException.class, dragon::aiStep));
        }
    }

    @Test
    void actualAiEnrolsAcceptedPendingHurtInItsNativeParentRatherThanClaimingIdle() {
        var world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(mock(MinecraftServer.class));
        var dragon = mock(EnderDragon.class, CALLS_REAL_METHODS);
        doReturn(world).when(dragon).level();
        doReturn(BlockPos.ZERO).when(dragon).blockPosition();
        var child = new CompletableFuture<Void>();
        doAnswer(call -> {
            ScarpetNativeWork.record(child);
            return null;
        }).when(dragon).carpetHurtPrefix(any(), any(), anyFloat());
        try (var ticks = mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)) {
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(dragon)).thenReturn(true);
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
            var accepted = ScarpetRenewableDragonHead.hurt(dragon, world, mock(EnderDragonPart.class), mock(net.minecraft.world.damagesource.DamageSource.class), 3F);
            var tick = ScarpetNativeWork.observeNative(dragon, () -> {
                dragon.aiStep();
                return 7;
            });
            assertFalse(tick.isDone());
            child.complete(null);
            assertFalse(accepted.join());
            assertEquals(7, tick.join());
        }
    }

    @Test
    void actualDeathTickKeepsAiStillUntilItsRealAdvanceChildAndSourceTailFinish() {
        var world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(mock(MinecraftServer.class));
        var dragon = mock(EnderDragon.class, CALLS_REAL_METHODS);
        doReturn(world).when(dragon).level();
        doReturn(BlockPos.ZERO).when(dragon).blockPosition();
        doReturn(null).when(dragon).carpetDeathFight();
        doReturn(0).when(dragon).carpetDeathExperience();
        doReturn(new EnderDragonPart[0]).when(dragon).getSubEntities();
        doNothing().when(dragon).carpetDeathMove();
        var child = new CompletableFuture<Void>();
        doAnswer(call -> {
            ScarpetNativeWork.record(child);
            return 2;
        }).when(dragon).carpetDeathAdvance(world);
        var movement = new IllegalStateException("actual native movement");
        doThrow(movement).when(dragon).isFlapping();
        try (var ticks = mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class); var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
            leases.when(() -> CarpetRegionLease.runValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any(java.util.function.Function.class))).thenAnswer(call -> CompletableFuture.completedFuture(((java.util.function.Function<?, ?>) call.getArgument(5)).apply(null)));
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(dragon)).thenReturn(true);
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
            ScarpetDragonDeathContinuations.tick(dragon);
            var accepted = ScarpetDragonDeathContinuations.pending(dragon);
            assertNotNull(accepted);
            assertFalse(accepted.isDone());
            dragon.aiStep();
            verify(dragon, never()).isFlapping();
            child.complete(null);
            accepted.join();
            assertNull(ScarpetDragonDeathContinuations.pending(dragon));
            assertSame(movement, assertThrows(IllegalStateException.class, dragon::aiStep));
        }
    }

}