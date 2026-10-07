package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.leavesmc.leaves.entity.bot.CraftBot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.ServerBot;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetExplosionRetirementTest {
    @BeforeAll static void version() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    static class Fixture implements AutoCloseable {
        final ServerBot bot = mock(ServerBot.class);
        final ServerLevel world = mock(ServerLevel.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final CraftServer craft = mock(CraftServer.class);
        final CraftBot player = mock(CraftBot.class);
        final io.papermc.paper.threadedregions.EntityScheduler scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
        final io.papermc.paper.threadedregions.scheduler.RegionScheduler regions = mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);
        final AtomicBoolean removed = new AtomicBoolean();
        final AtomicBoolean ownedRegion = new AtomicBoolean(true);
        final ArrayList<Runnable> queued = new ArrayList<>();
        final org.mockito.MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class);
        final org.mockito.MockedStatic<MinecraftServer> servers = mockStatic(MinecraftServer.class);
        Fixture() throws Exception {
            servers.when(MinecraftServer::getServer).thenReturn(server);
            when(bot.carpetSpawnServer()).thenReturn(server); when(bot.getUUID()).thenReturn(java.util.UUID.randomUUID());
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            when(craft.getLogger()).thenReturn(java.util.logging.Logger.getLogger("retirement-test"));
            MinecraftServer.class.getField("server").set(server, craft);
            var schedulerField = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
            schedulerField.setAccessible(true); schedulerField.set(player, scheduler);
            when(bot.level()).thenReturn(world); when(bot.blockPosition()).thenReturn(BlockPos.ZERO);
            when(bot.isRemoved()).thenAnswer(call -> removed.get()); when(bot.getBukkitEntity()).thenReturn(player);
            when(world.getServer()).thenReturn(server); when(world.getWorld()).thenReturn(mock(CraftWorld.class));
            when(craft.getRegionScheduler()).thenReturn(regions);
            ticks.when(() -> TickThread.isTickThreadFor(bot)).thenAnswer(call -> !removed.get());
            ticks.when(() -> TickThread.ensureTickThread(eq(bot), anyString())).thenAnswer(call -> {
                if (removed.get()) throw new IllegalStateException("No live actor owns this retired player");
                return null;
            });
            ticks.when(() -> TickThread.isTickThreadFor(world, BlockPos.ZERO)).thenAnswer(call -> ownedRegion.get());
            doAnswer(call -> { queued.add(call.getArgument(4)); return null; }).when(regions).execute(any(), any(), anyInt(), anyInt(), any());
            when(scheduler.schedule(any(), any(), anyLong())).thenReturn(false);
        }
        void die() { removed.set(true); ScarpetRetiredActors.capture(bot); }
        public void close() { servers.close(); bukkit.close(); ticks.close(); }
    }

    @Test void realAfterDeathTailCompletesOnTheCurrentLastOwnerWithoutAddingATick() throws Exception {
        try (var f = new Fixture()) {
            var order = new ArrayList<String>();
            var receipt = ScarpetNativeDeaths.afterDeath(f.bot, () -> { order.add("death"); f.die(); }, () -> { order.add("damage tail"); return true; });
            assertTrue(receipt.isDone(), "The explosion must not acquire a new tick solely because its victim retired");
            assertTrue(receipt.join()); assertEquals(java.util.List.of("death", "damage tail"), order);
            assertTrue(f.queued.isEmpty());
        }
    }

    @Test void aForeignLastOwnerStillRequiresItsActualRegionCallback() throws Exception {
        try (var f = new Fixture()) {
            f.ownedRegion.set(false); f.die();
            var receipt = ScarpetExplosionActors.entity(f.bot, () -> 73);
            assertFalse(receipt.isDone()); assertEquals(1, f.queued.size());
            f.ownedRegion.set(true); f.queued.removeFirst().run();
            assertEquals(73, receipt.join());
        }
    }

    @Test void aLeftoverRetiredBotWithoutAnyCapturedOwnerDoesNotPoisonTheExplosion() throws Exception {
        try (var f = new Fixture()) {
            when(f.scheduler.isRetiredOffThread()).thenReturn(true);
            var whole = ScarpetNativeWork.observeNative(null, () -> ScarpetExplosionActors.admitExplosionTarget(f.bot,
                    () -> { fail("A retired actor must never receive a new hit"); return null; }));
            assertTrue(whole.isDone()); whole.join().join();
            assertTrue(f.queued.isEmpty()); verify(f.scheduler, never()).schedule(any(), any(), anyLong());
        }
    }

    @Test void retirementBetweenQueryAndSchedulerAdmissionDoesNotAbortOtherExplosionWork() throws Exception {
        try (var f = new Fixture()) {
            f.removed.set(true); // foreign actor, with no final-owner snapshot
            var retired = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<net.minecraft.world.entity.Entity>>();
            when(f.scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> { retired.set(call.getArgument(1)); return true; });
            var whole = ScarpetNativeWork.observeNative(null, () -> ScarpetExplosionActors.admitExplosionTarget(f.bot,
                    () -> { fail("Retirement rejected this admission"); return null; }));
            assertFalse(whole.isDone()); retired.get().accept(f.bot);
            assertTrue(whole.isDone()); whole.join().join();
        }
    }

    @Test void admittedDamageFailureStillFailsTheRealExplosionReceipt() throws Exception {
        try (var f = new Fixture()) {
            var child = new CompletableFuture<Void>();
            var whole = ScarpetNativeWork.observeNative(null, () -> ScarpetExplosionActors.admitExplosionTarget(f.bot, () -> child));
            assertFalse(whole.isDone());
            when(f.scheduler.isRetiredOffThread()).thenReturn(true);
            var failure = new IllegalStateException("actual physical damage failed"); child.completeExceptionally(failure);
            assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, whole::join).getCause());
        }
    }

    @Test void actualNativePostImpactKeepsTheDamageOutcomeWithoutPushingAnOfflineBody() throws Exception {
        try (var f = new Fixture()) {
            f.die();
            var explosion = mock(net.minecraft.world.level.ServerExplosion.class, CALLS_REAL_METHODS);
            var affected = new ArrayList<net.minecraft.world.entity.Entity>();
            var field = net.minecraft.world.level.ServerExplosion.class.getDeclaredField("carpetAffectedEntities");
            field.setAccessible(true); field.set(explosion, affected);
            Class<?> impactClass = java.util.Arrays.stream(net.minecraft.world.level.ServerExplosion.class.getDeclaredClasses())
                    .filter(c -> c.getSimpleName().equals("CarpetImpact")).findFirst().orElseThrow();
            var constructor = impactClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            Object impact = constructor.newInstance(f.bot, .2D, net.minecraft.world.phys.Vec3.ZERO, true, 1F, 1F);
            var post = net.minecraft.world.level.ServerExplosion.class.getDeclaredMethod("carpetPostImpact", impactClass);
            post.setAccessible(true); post.invoke(explosion, impact);
            assertEquals(java.util.List.of(f.bot), affected);
            verify(f.bot, never()).pushFromExplosion(any()); verify(f.bot, never()).onExplosionHit(any());
        }
    }

    @Test void fatalPlayerDamageCanRegisterItsRealOuterTailOnItsProvenFinalRegion() throws Exception {
        try (var f = new Fixture()) {
            f.die();
            var damage = new CompletableFuture<Boolean>(); var children = new CompletableFuture<Void>();
            var tail = ScarpetAttackContinuations.afterDamageNativeAsync(f.bot, damage, hurt -> {
                assertTrue(hurt); return children;
            });
            f.ticks.verify(() -> TickThread.ensureTickThread(f.bot, "Deferred typed attack must be captured by its attacker"), never());
            f.ticks.verify(() -> TickThread.ensureTickThread(f.bot, "Native player work must be admitted by its owner"), never());
            assertFalse(tail.isDone()); damage.complete(true); assertFalse(tail.isDone());
            children.complete(null); tail.join(); assertTrue(f.queued.isEmpty());
        }
    }

    @Test void aForeignFinalRegionDoesNotRelaxTheOuterTailOwnershipCheck() throws Exception {
        try (var f = new Fixture()) {
            f.die(); f.ownedRegion.set(false);
            assertThrows(IllegalStateException.class, () -> ScarpetAttackContinuations.afterDamageNativeAsync(f.bot,
                    CompletableFuture.completedFuture(true), hurt -> CompletableFuture.completedFuture(null)));
            f.ticks.verify(() -> TickThread.ensureTickThread(f.bot, "Deferred typed attack must be captured by its attacker"));
            assertTrue(f.queued.isEmpty());
        }
    }

    @Test void safeAfkAfterFatalLogoutRetainsItsActualCheckWithoutReopeningALivePlayerGate() throws Exception {
        try (var f = new Fixture(); var damage = mockStatic(ScarpetDamageContinuations.class);
             var afk = mockStatic(fun.bm.lophine.carpet.OrgSafeAfk.class, CALLS_REAL_METHODS)) {
            var pending = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>>();
            var body = new CompletableFuture<Boolean>(); var checkChild = new CompletableFuture<Void>();
            var source = mock(net.minecraft.world.damagesource.DamageSource.class);
            damage.when(() -> ScarpetDamageContinuations.pendingBodyResult(f.bot)).thenAnswer(call -> pending.get());
            afk.when(() -> fun.bm.lophine.carpet.OrgSafeAfk.afterDamage(f.bot, source, 8F, false)).thenAnswer(call -> {
                assertTrue(f.ownedRegion.get()); ScarpetNativeWork.record(checkChild); return null;
            });
            var whole = ScarpetNativeWork.observeNative(f.bot, () -> fun.bm.lophine.carpet.OrgSafeAfk.withDamageOuter(
                    f.bot, source, 8F, () -> { pending.set(body); ScarpetNativeWork.record(body); f.die(); return false; }));
            assertFalse(whole.isDone());
            f.ticks.verify(() -> TickThread.ensureTickThread(f.bot, "Native player work must be admitted by its owner"), never());
            body.complete(true); assertFalse(whole.isDone());
            checkChild.complete(null); assertFalse(whole.join()); assertTrue(f.queued.isEmpty());
        }
    }
}
