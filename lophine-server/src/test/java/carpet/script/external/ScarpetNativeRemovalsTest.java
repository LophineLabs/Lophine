package carpet.script.external;

import carpet.script.*;
import carpet.script.value.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.inventory.InventoryMenu;
import org.bukkit.craftbukkit.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tests the real patched Native remove methods; only world scheduling and the Bukkit event envelope are fixtures. */
public class ScarpetNativeRemovalsTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    private static final ScriptServer FILES = new ScriptServer() { @Override public Path resolveResource(String name) { return Path.of(name); } };
    private static final class Host extends ScriptHost {
        Host() { super(null, FILES, false, null, Expression.LoadOverride.DEFAULT); }
        @Override protected carpet.script.Module getModuleOrLibraryByName(String name) { return null; }
        @Override protected void runModuleCode(Context context, carpet.script.Module module) { }
        @Override protected ScriptHost duplicate() { return new Host(); }
    }
    private static final class ReadyContext extends Context { ReadyContext(Host host) { super(host); initialize(); } }
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final Player entity = mock(Player.class, CALLS_REAL_METHODS);
        final EntityEventsGroup events = mock(EntityEventsGroup.class);
        final AtomicBoolean owner = new AtomicBoolean(), removed = new AtomicBoolean();
        final List<String> sequence = new ArrayList<>();
        final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        final MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final MockedStatic<MinecraftServer> servers = mockStatic(MinecraftServer.class);
        final MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class);
        final MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        final ScarpetRuntime runtime;
        Fixture() throws Exception {
            CraftServer craft = mock(CraftServer.class); when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft); servers.when(MinecraftServer::getServer).thenReturn(server);
            assertNotNull(org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE);
            var serverField = MinecraftServer.class.getField("server"); serverField.setAccessible(true); serverField.set(server, craft);
            var scheduler = mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class); when(craft.getRegionScheduler()).thenReturn(scheduler);
            var craftWorld=mock(CraftWorld.class);
            when(world.getServer()).thenReturn(server); when(world.getWorld()).thenReturn(craftWorld);
            doReturn(world).when(entity).level(); doReturn(BlockPos.ZERO).when(entity).blockPosition();
            doAnswer(call -> removed.get()).when(entity).isRemoved();
            doReturn(false).when(entity).hasContainerOpen();
            var eventField = Entity.class.getDeclaredField("carpetEvents"); eventField.setAccessible(true); eventField.set(entity, events);
            when(events.hasEvent(EntityEventsGroup.Event.ON_REMOVED)).thenReturn(true);
            doAnswer(call -> { assertTrue(owner.get()); sequence.add("physical"); removed.set(true); return null; }).when(entity).setRemoved(any(Entity.RemovalReason.class), any());
            var brain = mock(Brain.class); var brainField = LivingEntity.class.getDeclaredField("brain"); brainField.setAccessible(true); brainField.set(entity, brain);
            doAnswer(call -> { sequence.add("brain"); assertTrue(removed.get()); return null; }).when(brain).clearMemories();
            entity.combatTracker = mock(CombatTracker.class);
            doAnswer(call -> { sequence.add("combat"); return null; }).when(entity.combatTracker).recheckStatus();
            InventoryMenu menu = mock(InventoryMenu.class); var menuField = Player.class.getField("inventoryMenu"); menuField.setAccessible(true); menuField.set(entity, menu);
            doAnswer(call -> { sequence.add("inventory"); assertTrue(removed.get()); return null; }).when(menu).removed(entity);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> owner.get());
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenAnswer(call -> owner.get());
            doAnswer(call -> { tasks.add(call.getArgument(4)); return null; }).when(scheduler).execute(any(), any(org.bukkit.World.class), anyInt(), anyInt(), any(Runnable.class));
            leases.when(() -> fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>, Object> action = call.getArgument(5);
                return CompletableFuture.completedFuture(action.apply(null));
            });
            runtime = ScarpetRuntime.of(server);
        }
        void own(Runnable action) { owner.set(true); try { action.run(); } finally { owner.set(false); } }
        void step() throws Exception { Runnable task = tasks.poll(3, TimeUnit.SECONDS); assertNotNull(task); own(task); }
        @Override public void close() { ScarpetRuntime.beginShutdown(server, () -> {}); leases.close(); bukkit.close(); servers.close(); ticks.close(); }
    }
    @Test void actualPlayerRemovalWaitsForItsGuestMutationAndAllEnclosingCleanup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.events.onEventFuture(EntityEventsGroup.Event.ON_REMOVED)).thenAnswer(call -> fixture.runtime.<Void>submit(() -> {
                Expression expression = new Expression("compat_removed_callback() -> compat_mutate(); compat_removed_callback()");
                expression.addContextFunction("compat_mutate", 0, (context, type, args) -> NumericValue.of(ScarpetRuntime.atBlock(fixture.world, BlockPos.ZERO, () -> {
                    assertFalse(fixture.entity.isRemoved());
                    EntityValue.snapshotForRetiredEvent(fixture.entity).set("age", NumericValue.of(37));
                    fixture.sequence.add("callback"); return 37L;
                })));
                assertEquals(37L, expression.executeAndEvaluate(new ReadyContext(new Host()), false, Expression.LoadOverride.DEFAULT, null).getLeft().readInteger());
                return null;
            }));
            var observed = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
            fixture.own(() -> observed.set(ScarpetNativeWork.observeNative(fixture.entity, () -> {
                fixture.sequence.add("saved"); fixture.entity.remove(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
                assertTrue(ScarpetNativeRemovals.thenOwner(fixture.entity, () -> fixture.sequence.add("retire/maps")));
                assertTrue(ScarpetNativeRemovals.thenOwner(fixture.entity, () -> fixture.sequence.add("disconnect/notify")));
                return null;
            })));
            assertEquals(List.of("saved"), fixture.sequence); assertFalse(fixture.removed.get());
            var idle = ScarpetNativeRemovals.whenIdle(fixture.server); assertFalse(idle.isDone());
            fixture.step(); // guest native function mutates the original player on its owner
            assertFalse(observed.get().isDone()); assertFalse(fixture.removed.get());
            fixture.step(); // raw physical remove, Living/Player cleanup, then outer callers
            observed.get().get(3, TimeUnit.SECONDS); idle.get(3, TimeUnit.SECONDS);
            assertEquals(37, fixture.entity.tickCount);
            assertEquals(List.of("saved", "callback", "physical", "brain", "combat", "inventory", "retire/maps", "disconnect/notify"), fixture.sequence);
            assertFalse(ScarpetNativeRemovals.isPending(fixture.entity));
        }
    }
    @Test void aClosedGuestDoesNotPreventTheOriginalNativeRemovalAndRetirementTail() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.events.onEventFuture(EntityEventsGroup.Event.ON_REMOVED)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Guest stopped")));
            fixture.own(() -> { fixture.entity.remove(Entity.RemovalReason.UNLOADED_WITH_PLAYER); assertTrue(ScarpetNativeRemovals.thenOwner(fixture.entity, () -> fixture.sequence.add("retire/maps"))); });
            CountDownLatch stopped = new CountDownLatch(1); ScarpetRuntime.beginShutdown(fixture.server, stopped::countDown); assertTrue(stopped.await(3, TimeUnit.SECONDS));
            var idle = ScarpetNativeRemovals.whenIdle(fixture.server); fixture.step(); idle.get(3, TimeUnit.SECONDS);
            assertTrue(fixture.removed.get()); assertEquals(List.of("physical", "brain", "combat", "inventory", "retire/maps"), fixture.sequence);
        }
    }

    @Test void cancellingACallerViewCannotCancelPhysicalRemovalOrReleaseTheShutdownBarrier() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var callback = new CompletableFuture<Void>();
            when(fixture.events.onEventFuture(EntityEventsGroup.Event.ON_REMOVED)).thenReturn(callback);
            fixture.own(() -> {
                fixture.entity.remove(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
                assertTrue(ScarpetNativeRemovals.thenOwner(fixture.entity, () -> fixture.sequence.add("retire/maps")));
            });
            var view = ScarpetNativeRemovals.completion(fixture.entity);
            assertTrue(view.cancel(false));
            assertTrue(ScarpetNativeRemovals.isPending(fixture.entity));
            var idle = ScarpetNativeRemovals.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            callback.complete(null);
            fixture.step();
            idle.get(3, TimeUnit.SECONDS);
            assertTrue(fixture.removed.get());
            assertEquals(List.of("physical", "brain", "combat", "inventory", "retire/maps"), fixture.sequence);
        }
    }

    @Test void rejectedPhysicalRemovalSettlesOnlyAfterItsAlreadyAcceptedNativeChildren() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var callback = new CompletableFuture<Void>();
            var child = new CompletableFuture<Void>();
            var failure = new RejectedExecutionException("Owner scheduler closed after removal admission");
            when(fixture.events.onEventFuture(EntityEventsGroup.Event.ON_REMOVED)).thenAnswer(call -> {
                ScarpetNativeWork.record(child);
                return callback;
            });
            try {
                fixture.own(() -> fixture.entity.remove(Entity.RemovalReason.UNLOADED_WITH_PLAYER));
                var actual = ScarpetNativeRemovals.completion(fixture.entity);
                var idle = ScarpetNativeRemovals.whenIdle(fixture.server);
                var regions = fixture.server.server.getRegionScheduler();
                doThrow(failure).when(regions).execute(any(), any(org.bukkit.World.class), anyInt(), anyInt(), any(Runnable.class));
                callback.complete(null);
                assertFalse(actual.isDone()); assertFalse(idle.isDone());
                assertFalse(fixture.removed.get()); assertTrue(fixture.sequence.isEmpty());
                child.complete(null);
                assertTrue(actual.isCompletedExceptionally(), "Rejected owner dispatch must publish the actual failure");
                assertSame(failure, assertThrows(CompletionException.class, actual::join).getCause());
                assertTrue(idle.isDone()); assertFalse(ScarpetNativeRemovals.isPending(fixture.entity));
                assertFalse(fixture.removed.get()); assertTrue(fixture.sequence.isEmpty());
            } finally {
                child.complete(null); callback.complete(null);
                // A broken dispatcher must not leave the test's server shutdown thread waiting forever.
                var field = ScarpetNativeRemovals.class.getDeclaredField("PENDING"); field.setAccessible(true);
                Object plan = ((Map<?, ?>) field.get(null)).get(fixture.entity);
                if (plan != null) {
                    var body = plan.getClass().getDeclaredField("nativeBody"); body.setAccessible(true);
                    ((CompletableFuture<?>) body.get(plan)).completeExceptionally(failure);
                }
            }
        }
    }

    @Test void rejectedOwnerValueDispatchReturnsItsActualFailureInsteadOfThrowingAndStrandingTheReceipt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.own(() -> ScarpetRetiredActors.capture(fixture.entity));
            var failure = new RejectedExecutionException("Owner value scheduler closed");
            var regions = fixture.server.server.getRegionScheduler();
                doThrow(failure).when(regions).execute(any(), any(org.bukkit.World.class), anyInt(), anyInt(), any(Runnable.class));
            var called = new AtomicBoolean();
            var actual = assertDoesNotThrow(() -> ScarpetNativeRemovals.onOwnerFuture(fixture.entity, () -> {
                called.set(true); return 17;
            }));
            assertTrue(actual.isCompletedExceptionally());
            assertSame(failure, assertThrows(CompletionException.class, actual::join).getCause());
            assertFalse(called.get()); assertTrue(ScarpetNativeRemovals.whenIdle(fixture.server).isDone());
        }
    }
}
