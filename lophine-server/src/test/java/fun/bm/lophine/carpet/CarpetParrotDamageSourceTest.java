package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetDamageContinuations;
import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.*;

class CarpetParrotDamageSourceTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    private static final class Fixture implements AutoCloseable {
        final ServerPlayer player = mock(ServerPlayer.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final DamageSource source = mock(DamageSource.class);
        final RandomSource random = mock(RandomSource.class);
        final List<String> order = new ArrayList<>();
        CompletableFuture<Entity> left = new CompletableFuture<>(), right = new CompletableFuture<>();
        CompletableFuture<Void> leftChild;
        final org.mockito.MockedStatic<ca.spottedleaf.moonrise.common.util.TickThread> ticks = mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);
        final org.mockito.MockedStatic<OrgFakePlayerActions> actors = mockStatic(OrgFakePlayerActions.class);
        final boolean previous = GeneralCompatConfig.persistentParrots;
        Fixture() throws Exception {
            GeneralCompatConfig.persistentParrots = true;
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)).thenReturn(true);
            when(player.level()).thenReturn(world); when(world.getServer()).thenReturn(server); when(player.carpetSpawnServer()).thenReturn(server);
            when(player.getRandom()).thenReturn(random); when(random.nextFloat()).thenReturn(0.9F);
            var field = Entity.class.getDeclaredField("random"); field.setAccessible(true); field.set(player, random);
            when(player.isInvulnerableTo(world, source)).thenAnswer(call -> { order.add("damage guard"); return true; });
            when(player.carpetReleaseShoulderNativeAsync(true)).thenAnswer(call -> {
                order.add("left"); if (leftChild != null) ScarpetNativeWork.record(leftChild); return left;
            });
            when(player.carpetReleaseShoulderNativeAsync(false)).thenAnswer(call -> { order.add("right"); return right; });
            actors.when(() -> OrgFakePlayerActions.owned(eq(player), any())).thenAnswer(call -> {
                try { return CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(1)).get()); }
                catch (Throwable failure) { return CompletableFuture.failedFuture(failure); }
            });
        }
        boolean nativeDamage(float amount) {
            try {
                var method = ServerPlayer.class.getDeclaredMethod("carpetHurtServerNative", ServerLevel.class, DamageSource.class, float.class);
                method.setAccessible(true); return (Boolean) method.invoke(player, world, source, amount);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        public void close() { actors.close(); ticks.close(); GeneralCompatConfig.persistentParrots = previous; }
    }
    @Test void realDamageHeadUsesSourceDoubleThresholdAndWaitsBothShouldersBeforeDamage() throws Exception {
        try (var f = new Fixture()) {
            var parent = ScarpetNativeWork.observeNative(f.player, () -> f.nativeDamage(13.5F));
            if (parent.isCompletedExceptionally()) parent.join();
            var result = ScarpetDamageContinuations.pendingBodyResult(f.player);
            assertNotNull(result); assertFalse(result.cancel(false)); assertEquals(List.of("left"), f.order);
            verify(f.random, times(1)).nextFloat(); assertFalse(parent.isDone());
            f.left.complete(mock(Entity.class)); assertEquals(List.of("left", "right"), f.order);
            verify(f.random, times(2)).nextFloat(); assertFalse(result.isDone()); assertFalse(parent.isDone());
            f.right.complete(mock(Entity.class)); assertFalse(result.join()); assertFalse(parent.join());
            assertEquals(List.of("left", "right", "damage guard"), f.order);
            ScarpetNativeWork.whenIdle(f.server).join();
        }
    }
    @Test void realLeftNativeFailureSuppressesRightDrawAndDamageTail() throws Exception {
        try (var f = new Fixture()) {
            var parent = ScarpetNativeWork.observeNative(f.player, () -> f.nativeDamage(13.5F));
            f.left.completeExceptionally(new IllegalStateException("native shoulder failure"));
            assertThrows(CompletionException.class, parent::join); assertEquals(List.of("left"), f.order);
            verify(f.random, times(1)).nextFloat(); verify(f.player, never()).isInvulnerableTo(f.world, f.source);
        }
    }
    @Test void actualOwnerPhaseWaitsDynamicGuestChildThenResumesNativeTailAndKeepsRawFailure() throws Exception {
        try (var f = new Fixture()) {
            f.left = CompletableFuture.completedFuture(mock(Entity.class)); f.right = CompletableFuture.completedFuture(mock(Entity.class));
            f.leftChild = new CompletableFuture<>();
            var parent = ScarpetNativeWork.observeNative(f.player, () -> f.nativeDamage(13.5F));
            if (parent.isCompletedExceptionally()) parent.join();
            var result = ScarpetDamageContinuations.pendingBodyResult(f.player);
            assertEquals(List.of("left"), f.order); assertFalse(parent.isDone());
            var failure = new IllegalArgumentException("guest shoulder child"); var marker = ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure", Throwable.class); marker.setAccessible(true); marker.invoke(null, failure);
            f.leftChild.completeExceptionally(failure);
            assertEquals(List.of("left", "right", "damage guard"), f.order); assertFalse(result.join());
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, parent::join)));
        }
    }
    @Test void realSneakingDamageHeadLeavesBothRandomDrawsAndShouldersUntouched() throws Exception {
        try (var f = new Fixture()) {
            when(f.player.isShiftKeyDown()).thenReturn(true);
            assertFalse(f.nativeDamage(13.5F)); assertEquals(List.of("damage guard"), f.order);
            verify(f.random, never()).nextFloat(); verify(f.player, never()).carpetReleaseShoulderNativeAsync(anyBoolean());
        }
    }
}
