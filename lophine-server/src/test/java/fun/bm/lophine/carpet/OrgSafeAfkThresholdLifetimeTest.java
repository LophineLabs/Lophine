package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetDamageContinuations;
import carpet.script.external.ScarpetNativeWork;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.ServerBot;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgSafeAfkThresholdLifetimeTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    private static carpet.script.external.WeakIdentityMap<ServerPlayer, Float> thresholds(OrgPlayerManager manager) throws Exception {
        var field = OrgPlayerManager.class.getDeclaredField("thresholds");
        field.setAccessible(true);
        return (carpet.script.external.WeakIdentityMap<ServerPlayer, Float>) field.get(manager);
    }

    private static Map<MinecraftServer, OrgPlayerManager> managers() throws Exception {
        var field = OrgPlayerManager.class.getDeclaredField("MANAGERS");
        field.setAccessible(true);
        return (Map<MinecraftServer, OrgPlayerManager>) field.get(null);
    }

    private static OrgPlayerManager install(MinecraftServer server) throws Exception {
        var constructor = OrgPlayerManager.class.getDeclaredConstructor(MinecraftServer.class, CommandBuildContext.class);
        constructor.setAccessible(true);
        var manager = constructor.newInstance(server, null);
        managers().put(server, manager);
        return manager;
    }

    @Test
    void theLatestTransientThresholdSurvivesRetirementUntilTheActualDeathReturnCheck() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var damage = mockStatic(ScarpetDamageContinuations.class); var afk = mockStatic(OrgSafeAfk.class, CALLS_REAL_METHODS)) {
            var player = fixture.target.player();
            fixture.owner.set(player);
            var manager = install(fixture.server);
            try {
                var values = thresholds(manager);
                values.put(player, 5F);
                var source = mock(DamageSource.class);
                var published = new AtomicReference<CompletableFuture<Boolean>>();
                var body = new CompletableFuture<Boolean>();
                var checks = new AtomicInteger();
                damage.when(() -> ScarpetDamageContinuations.pendingBodyResult(player)).thenAnswer(call -> published.get());
                afk.when(() -> OrgSafeAfk.afterDamage(player, source, 12F, false)).thenAnswer(call -> {
                    assertEquals(7F, OrgPlayerManager.safeThreshold(player));
                    checks.incrementAndGet();
                    return null;
                });
                var actual = ScarpetNativeWork.observeNative(player, () -> OrgSafeAfk.withDamage(player, source, 12F, () -> {
                    published.set(body);
                    ScarpetNativeWork.record(body);
                    // A real death callback can update the transient per-object threshold before removal.
                    values.put(player, 7F);
                    OrgPlayerManager.retired(player);
                    return false;
                }));
                assertEquals(7F, OrgPlayerManager.safeThreshold(player));
                assertEquals(0, checks.get());
                assertFalse(actual.isDone());
                fixture.owner.set(null);
                body.complete(true);
                assertEquals(0, checks.get());
                fixture.drain(fixture.target);
                assertFalse(actual.join());
                assertEquals(1, checks.get());
            } finally {
                managers().remove(fixture.server);
            }
        }
    }

    @Test
    void aNewFakeObjectWithTheSameNameDoesNotInheritTheRetiredObjectsTransientThreshold() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var manager = install(fixture.server);
            try {
                var old = fixture.target.player();
                when(old.getScoreboardName()).thenReturn("same_name");
                thresholds(manager).put(old, 9F);
                OrgPlayerManager.retired(old);
                var replacement = fixture.actor(ServerBot.class).player();
                when(replacement.getScoreboardName()).thenReturn("same_name");
                assertEquals(9F, OrgPlayerManager.safeThreshold(old));
                assertEquals(-1F, OrgPlayerManager.safeThreshold(replacement));
            } finally {
                managers().remove(fixture.server);
            }
        }
    }

    @Test
    void aBukkitOwnerLookupFailureTerminatesTheActualSafeAfkWaiterAndServerDrain() throws Exception {
        failedDispatch(DispatchFailure.LOOKUP);
    }

    @Test
    void aSchedulerExceptionTerminatesTheActualSafeAfkWaiterAndServerDrain() throws Exception {
        failedDispatch(DispatchFailure.SCHEDULE);
    }

    @Test
    void aRejectedSchedulerStillFailsTheActualSafeAfkWaiter() throws Exception {
        failedDispatch(DispatchFailure.REJECTED);
    }

    @Test
    void schedulerRetirementStillFailsTheActualSafeAfkWaiter() throws Exception {
        failedDispatch(DispatchFailure.RETIRED);
    }

    private enum DispatchFailure {LOOKUP, SCHEDULE, REJECTED, RETIRED}

    @Test
    void aPhysicallyRetiredDamageOwnerRunsItsEnabledCheckOnTheCapturedRegion() throws Exception {
        retiredDamage(RetirementTiming.BEFORE, false, false);
    }

    @Test
    void aPhysicallyRetiredLowHealthOwnerStillReportsFailureAndWaitsForReportChildren() throws Exception {
        retiredDamage(RetirementTiming.BEFORE, true, false);
    }

    @Test
    void physicalRetirementDuringSchedulerRejectionRunsTheReturnCheckOnce() throws Exception {
        retiredDamage(RetirementTiming.REJECTED, false, false);
    }

    @Test
    void physicalRetirementDuringSchedulerRetirementRunsTheReturnCheckOnce() throws Exception {
        retiredDamage(RetirementTiming.CALLBACK, false, false);
    }

    @Test
    void aFailedDamageRemainderKeepsItsCauseAfterPhysicalRetirement() throws Exception {
        retiredDamage(RetirementTiming.BEFORE, false, true);
    }

    private enum RetirementTiming {BEFORE, REJECTED, CALLBACK}

    private void retiredDamage(RetirementTiming timing, boolean lowHealth, boolean bodyFails) throws Exception {
        String previousTotem = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.betterTotemOfUndying;
        fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.betterTotemOfUndying = "vanilla";
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var damage = mockStatic(ScarpetDamageContinuations.class);
             var afk = mockStatic(OrgSafeAfk.class, CALLS_REAL_METHODS); var bukkit = mockStatic(org.bukkit.Bukkit.class);
             var regions = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class);
             var commands = mockStatic(OrgCommandNativeEffects.class, CALLS_REAL_METHODS)) {
            var player = fixture.target.player();
            var world = player.level();
            var manager = install(fixture.server);
            try {
                thresholds(manager).put(player, 5F);
                var removed = new java.util.concurrent.atomic.AtomicBoolean();
                var regionOwner = new java.util.concurrent.atomic.AtomicBoolean();
                when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
                when(player.isRemoved()).thenAnswer(call -> removed.get());
                when(player.getHealth()).thenAnswer(call -> {
                    assertTrue(regionOwner.get(), "SafeAFK must read retired health on the final region");
                    return lowHealth ? 0F : 20F;
                });
                when(player.getItemInHand(any())).thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
                var source = mock(DamageSource.class);
                when(source.getMsgId()).thenReturn("fall");
                var craft = mock(org.bukkit.craftbukkit.CraftServer.class);
                when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
                bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
                var serverField = MinecraftServer.class.getField("server");
                serverField.setAccessible(true);
                serverField.set(fixture.server, craft);
                var scheduler = mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);
                when(craft.getRegionScheduler()).thenReturn(scheduler);
                when(world.getWorld()).thenReturn(mock(org.bukkit.craftbukkit.CraftWorld.class));
                var queued = new java.util.ArrayDeque<Runnable>();
                doAnswer(call -> {
                    assertEquals(0, call.<Integer>getArgument(2));
                    assertEquals(0, call.<Integer>getArgument(3));
                    queued.add(call.getArgument(4));
                    return null;
                })
                        .when(scheduler).execute(any(), any(org.bukkit.World.class), anyInt(), anyInt(), any(Runnable.class));
                fixture.ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), any(net.minecraft.core.BlockPos.class))).thenAnswer(call -> regionOwner.get());
                regions.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);
                var report = new CompletableFuture<Void>();
                var reported = new AtomicReference<net.minecraft.network.chat.Component>();
                commands.when(() -> OrgCommandNativeEffects.broadcast(eq(fixture.server), any(net.minecraft.network.chat.Component.class))).thenAnswer(call -> {
                    reported.set(call.getArgument(1));
                    ScarpetNativeWork.record(report);
                    return report;
                });
                Runnable physicalRetirement = () -> {
                    fixture.owner.set(player);
                    removed.set(true);
                    carpet.script.external.ScarpetRetiredActors.capture(player);
                    fixture.owner.set(null);
                    assertTrue(carpet.script.external.ScarpetRetiredActors.knownRetired(player));
                };
                var entityScheduler = player.getBukkitEntity().taskScheduler;
                doAnswer(call -> {
                    if (removed.get()) return false;
                    physicalRetirement.run();
                    if (timing == RetirementTiming.CALLBACK)
                        call.<java.util.function.Consumer<net.minecraft.world.entity.Entity>>getArgument(1).accept(player);
                    // False after the callback also exercises the duplicate-retirement race.
                    return false;
                }).when(entityScheduler).schedule(any(), any(), anyLong());
                var published = new AtomicReference<CompletableFuture<Boolean>>();
                var body = new CompletableFuture<Boolean>();
                damage.when(() -> ScarpetDamageContinuations.pendingBodyResult(player)).thenAnswer(call -> published.get());
                fixture.owner.set(player);
                var actual = ScarpetNativeWork.observeNative(player, () -> OrgSafeAfk.withDamage(player, source, 12F, () -> {
                    published.set(body);
                    ScarpetNativeWork.record(body);
                    return false;
                }));
                var drain = ScarpetNativeWork.whenIdle(fixture.server);
                assertFalse(actual.isDone());
                assertFalse(drain.isDone());
                if (timing == RetirementTiming.BEFORE) physicalRetirement.run();
                else fixture.owner.set(null);
                var cause = new IllegalStateException("native damage failed");
                if (bodyFails) {
                    body.completeExceptionally(cause);
                    var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> actual.get(3, java.util.concurrent.TimeUnit.SECONDS));
                    assertSame(cause, failure.getCause());
                    assertTrue(queued.isEmpty());
                    afk.verify(() -> OrgSafeAfk.afterDamage(player, source, 12F, false), never());
                } else {
                    body.complete(true);
                    assertFalse(actual.isDone());
                    assertFalse(drain.isDone());
                    assertEquals(1, queued.size());
                    regionOwner.set(true);
                    try {
                        queued.remove().run();
                    } finally {
                        regionOwner.set(false);
                    }
                    afk.verify(() -> OrgSafeAfk.afterDamage(player, source, 12F, false), times(1));
                    if (lowHealth) {
                        assertNotNull(reported.get());
                        assertFalse(actual.isDone());
                        assertFalse(drain.isDone());
                        report.complete(null);
                    } else assertNull(reported.get());
                    assertFalse(actual.get(3, java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue(queued.isEmpty());
                }
                drain.get(3, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                managers().remove(fixture.server);
            }
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.betterTotemOfUndying = previousTotem;
        }
    }

    private void failedDispatch(DispatchFailure mode) throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var damage = mockStatic(ScarpetDamageContinuations.class); var afk = mockStatic(OrgSafeAfk.class, CALLS_REAL_METHODS)) {
            var player = fixture.target.player();
            fixture.owner.set(player);
            var source = mock(DamageSource.class);
            var published = new AtomicReference<CompletableFuture<Boolean>>();
            var body = new CompletableFuture<Boolean>();
            damage.when(() -> ScarpetDamageContinuations.pendingBodyResult(player)).thenAnswer(call -> published.get());
            var actual = ScarpetNativeWork.observeNative(player, () -> OrgSafeAfk.withDamage(player, source, 12F, () -> {
                published.set(body);
                ScarpetNativeWork.record(body);
                return false;
            }));
            var drain = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(actual.isDone());
            assertFalse(drain.isDone());
            var bukkit = player.getBukkitEntity();
            var scheduler = bukkit.taskScheduler;
            var failure = new IllegalStateException("SafeAFK dispatch failed");
            switch (mode) {
                case LOOKUP -> doThrow(failure).when(player).getBukkitEntity();
                case SCHEDULE -> doThrow(failure).when(scheduler).schedule(any(), any(), anyLong());
                case REJECTED -> doReturn(false).when(scheduler).schedule(any(), any(), anyLong());
                case RETIRED -> doAnswer(call -> {
                    call.<java.util.function.Consumer<net.minecraft.world.entity.Entity>>getArgument(1).accept(player);
                    return true;
                }).when(scheduler).schedule(any(), any(), anyLong());
            }
            fixture.owner.set(null);
            body.complete(true);
            var thrown = assertThrows(java.util.concurrent.ExecutionException.class, () -> actual.get(3, java.util.concurrent.TimeUnit.SECONDS));
            if (mode == DispatchFailure.LOOKUP || mode == DispatchFailure.SCHEDULE)
                assertSame(failure, thrown.getCause());
            else assertEquals("SafeAFK actual damage owner retired", thrown.getCause().getMessage());
            drain.get(3, java.util.concurrent.TimeUnit.SECONDS);
            afk.verify(() -> OrgSafeAfk.afterDamage(player, source, 12F, false), never());
            // A failed return dispatch must also leave inventory admission usable.
            doReturn(bukkit).when(player).getBukkitEntity();
            doAnswer(call -> {
                call.<java.util.function.Consumer<net.minecraft.world.entity.Entity>>getArgument(0).accept(player);
                return true;
            }).when(scheduler).schedule(any(), any(), anyLong());
            fixture.owner.set(player);
            assertEquals("ready", carpet.script.external.ScarpetPlayerInventoryGate.whenIdle(player, () -> "ready").get(3, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
