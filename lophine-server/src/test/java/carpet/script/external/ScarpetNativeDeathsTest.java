package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.CarpetEventServer;
import carpet.script.EntityEventsGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Executes the real patched Native death, effect, removal and health methods. World scheduling is a fixture.
 */
public class ScarpetNativeDeathsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final Map<LivingEntity, Fixture> STATES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final carpet.script.ScriptServer FILES = new carpet.script.ScriptServer() {
        @Override
        public java.nio.file.Path resolveResource(String name) {
            return java.nio.file.Path.of(name);
        }
    };

    private static final class Host extends carpet.script.ScriptHost {
        Host() {
            super(null, FILES, false, null, carpet.script.Expression.LoadOverride.DEFAULT);
        }

        @Override
        protected carpet.script.Module getModuleOrLibraryByName(String name) {
            return null;
        }

        @Override
        protected void runModuleCode(carpet.script.Context context, carpet.script.Module module) {
        }

        @Override
        protected carpet.script.ScriptHost duplicate() {
            return new Host();
        }
    }

    private static final class ReadyContext extends carpet.script.Context {
        ReadyContext(Host host) {
            super(host);
            initialize();
        }
    }

    public static abstract class TestLiving extends LivingEntity {
        protected TestLiving(ServerLevel level) {
            super(null, level);
        }

        @Override
        protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
            Fixture state = STATES.get(this);
            state.order.add("loot:" + getMainHandItem().getCount());
            if (state.nativeFailure != null) throw state.nativeFailure;
            ScarpetNativeWork.record(state.drops);
        }

        @Override
        protected void createWitherRose(LivingEntity killer) {
            STATES.get(this).order.add("rose");
        }
    }

    public static class TestPlayer extends ServerPlayer {
        public TestPlayer(MinecraftServer server, ServerLevel world, com.mojang.authlib.GameProfile profile) {
            super(server, world, profile, net.minecraft.server.level.ClientInformation.createDefault());
        }

        @Override
        public boolean shouldDropLoot(ServerLevel world) {
            return false;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final TestLiving target = mock(TestLiving.class, CALLS_REAL_METHODS);
        final EntityEventsGroup events = mock(EntityEventsGroup.class);
        final DamageSource source = mock(DamageSource.class);
        final CompletableFuture<Void> decision = new CompletableFuture<>(), drops = new CompletableFuture<>();
        final List<String> order = new ArrayList<>();
        final Queue<Runnable> ownerTasks = new ConcurrentLinkedQueue<>();
        Entity activeOwner = target;
        RuntimeException nativeFailure;
        final MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final MockedStatic<MinecraftServer> servers = mockStatic(MinecraftServer.class);
        final MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class);
        final MockedStatic<org.bukkit.craftbukkit.event.CraftEventFactory> craft = mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class);
        final MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        final ScarpetRuntime runtime;

        Fixture() throws Exception {
            servers.when(MinecraftServer::getServer).thenReturn(server);
            when(world.getServer()).thenReturn(server);
            var fixtureCraft = mock(org.bukkit.craftbukkit.CraftServer.class);
            when(fixtureCraft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(fixtureCraft);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            doReturn(world).when(target).level();
            doReturn(BlockPos.ZERO).when(target).blockPosition();
            doReturn(net.minecraft.world.phys.Vec3.ZERO).when(target).position();
            doReturn(false).when(target).isRemoved();
            doReturn(false).when(target).isSleeping();
            doReturn(false).when(target).hasCustomName();
            doReturn(null).when(target).getKillCredit();
            doNothing().when(target).stopUsingItem();
            doNothing().when(target).gameEvent(any(net.minecraft.core.Holder.class));
            target.combatTracker = mock(net.minecraft.world.damagesource.CombatTracker.class);
            doAnswer(call -> {
                order.add("pose");
                return null;
            }).when(target).setPose(any());
            ItemStack hand = mock(ItemStack.class);
            when(hand.getCount()).thenReturn(1);
            doReturn(hand).when(target).getMainHandItem();
            when(source.getMsgId()).thenReturn("test");
            Field eventField = Entity.class.getDeclaredField("carpetEvents");
            eventField.setAccessible(true);
            eventField.set(target, events);
            Field tasks = LivingEntity.class.getField("postDeathEventTasks");
            tasks.set(target, new ArrayList<Runnable>());
            when(events.hasEvent(EntityEventsGroup.Event.ON_DEATH)).thenReturn(true);
            when(events.onEventFuture(EntityEventsGroup.Event.ON_DEATH, "test")).thenAnswer(call -> {
                order.add("event");
                return decision;
            });
            var deathEvent = mock(org.bukkit.event.entity.EntityDeathEvent.class);
            craft.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.callEntityDeathEvent(eq(world), eq(target), eq(source), anyList(), eq(true))).thenReturn(deathEvent);
            leases.when(() -> fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>, Object> action = call.getArgument(5);
                return CompletableFuture.completedFuture(action.apply(null));
            });
            attach(target, mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class));
            STATES.put(target, this);
            runtime = ScarpetRuntime.of(server);
        }

        void attach(Entity entity, org.bukkit.craftbukkit.entity.CraftEntity wrapper) throws Exception {
            doReturn(wrapper).when(entity).getBukkitEntity();
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            Field field = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
            field.setAccessible(true);
            field.set(wrapper, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                java.util.function.Consumer<Entity> action = call.getArgument(0);
                ownerTasks.add(() -> {
                    Entity previous = activeOwner;
                    activeOwner = entity;
                    try {
                        action.accept(entity);
                    } finally {
                        activeOwner = previous;
                    }
                });
                return true;
            });
        }

        void drainUntil(java.util.function.BooleanSupplier ready) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!ready.getAsBoolean()) {
                Runnable next = ownerTasks.poll();
                if (next != null) next.run();
                else Thread.sleep(1);
                if (System.nanoTime() > deadline) {
                    fail("Native owner completion did not reach its expected phase: " + order);
                }
            }
            Runnable next;
            while ((next = ownerTasks.poll()) != null) next.run();
        }

        ServerPlayer player() throws Exception {
            ServerPlayer player = mock(TestPlayer.class, CALLS_REAL_METHODS);
            doReturn(world).when(player).level();
            doReturn(BlockPos.ZERO).when(player).blockPosition();
            doReturn(false).when(player).isRemoved();
            doReturn(false).when(player).isDeadOrDying();
            player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            Field health = ServerPlayer.class.getDeclaredField("carpetDamageHealthScopes");
            health.setAccessible(true);
            health.set(player, new IdentityHashMap<>());
            return player;
        }

        @Override
        public void close() {
            ScarpetRuntime.beginShutdown(server, () -> {
            });
            STATES.remove(target);
            leases.close();
            craft.close();
            bukkit.close();
            servers.close();
            ticks.close();
        }
    }

    @Test
    void realLivingDeathWaitsForTheGuestThenLootChildrenThenEnclosingAndHurtTails() throws Exception {
        try (Fixture f = new Fixture()) {
            var actual = ScarpetNativeDeaths.afterDeath(f.target, () -> {
                f.target.die(f.source);
                assertTrue(ScarpetNativeDeaths.thenOwner(f.target, () -> f.order.add("enclosing")));
            }, () -> {
                f.order.add("hurt/result");
                return true;
            });
            assertEquals(List.of("event"), f.order);
            assertTrue(ScarpetNativeDeaths.isPending(f.target));
            assertFalse(actual.isDone());
            assertTrue(ScarpetNativeRemovals.tickPending(f.target));
            assertFalse(ScarpetNativeRemovals.isPending(f.target));
            ItemStack changed = mock(ItemStack.class);
            when(changed.getCount()).thenReturn(37);
            doReturn(changed).when(f.target).getMainHandItem();
            f.decision.complete(null);
            assertEquals(List.of("event", "loot:37"), f.order);
            assertFalse(actual.isDone());
            f.drops.complete(null);
            assertTrue(actual.get(3, TimeUnit.SECONDS));
            assertEquals(List.of("event", "loot:37", "rose", "pose", "enclosing", "hurt/result"), f.order);
            assertFalse(ScarpetNativeDeaths.isPending(f.target));
        }
    }

    @Test
    void aFailedGuestStillCompletesTheAcceptedRealDeath() throws Exception {
        try (Fixture f = new Fixture()) {
            var actual = ScarpetNativeDeaths.afterDeath(f.target, () -> f.target.die(f.source), () -> true);
            f.decision.completeExceptionally(new IllegalStateException("guest closed"));
            f.drops.complete(null);
            assertTrue(actual.get(3, TimeUnit.SECONDS));
            assertTrue(f.order.contains("loot:1"));
            assertFalse(ScarpetNativeDeaths.isPending(f.target));
        }
    }

    @Test
    void aRealInterpreterFailureStillWaitsNativeLootAndRunsTheOriginalDeathAndHurtBodies() throws Exception {
        try (Fixture f = new Fixture()) {
            var guest = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
            var release = new CountDownLatch(1);
            var problem = new IllegalStateException("actual death callback failed");
            doAnswer(call -> {
                f.order.add("event");
                var work = f.runtime.<Void>submit(() -> {
                    try {
                        if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("callback release timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    throw problem;
                });
                guest.set(work);
                return work;
            }).when(f.events).onEventFuture(EntityEventsGroup.Event.ON_DEATH, "test");
            var actual = ScarpetNativeDeaths.afterDeath(f.target, () -> f.target.die(f.source), () -> {
                f.order.add("hurt/result");
                return true;
            });
            assertEquals(List.of("event"), f.order);
            release.countDown();
            assertSame(problem, assertThrows(ExecutionException.class, () -> guest.get().get(3, TimeUnit.SECONDS)).getCause());
            f.drainUntil(() -> f.order.contains("loot:1"));
            assertFalse(actual.isDone());
            assertFalse(f.order.contains("pose"));
            assertFalse(f.order.contains("hurt/result"));
            f.drops.complete(null);
            f.drainUntil(actual::isDone);
            assertTrue(actual.get(3, TimeUnit.SECONDS));
            assertEquals(List.of("event", "loot:1", "rose", "pose", "hurt/result"), f.order);
        }
    }

    @Test
    void aTrueNativeDeathFailureCannotBeRecoveredAsAGuestFailureOrRunTheHurtTail() throws Exception {
        try (Fixture f = new Fixture()) {
            var failure = new IllegalStateException("actual loot body failed");
            f.nativeFailure = failure;
            var actual = ScarpetNativeDeaths.afterDeath(f.target, () -> f.target.die(f.source), () -> {
                f.order.add("hurt/result");
                return true;
            });
            f.decision.complete(null);
            assertSame(failure, assertThrows(ExecutionException.class, () -> actual.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
            assertEquals(List.of("event", "loot:1"), f.order);
        }
    }

    @Test
    void realGuestFailureInsideAnEffectDoesNotSkipItsNativeChildrenOrTheNextOriginalEffect() throws Exception {
        try (Fixture f = new Fixture()) {
            MobEffectInstance first = mock(MobEffectInstance.class), second = mock(MobEffectInstance.class);
            doReturn(List.of(first, second)).when(f.target).getActiveEffects();
            Field active = LivingEntity.class.getDeclaredField("activeEffects");
            active.setAccessible(true);
            active.set(f.target, new HashMap<>());
            var guest = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
            var problem = new IllegalStateException("actual effect callback failed");
            doAnswer(call -> {
                f.order.add("first");
                guest.set(f.runtime.<Void>submit(() -> {
                    throw problem;
                }));
                ScarpetNativeWork.record(f.drops);
                return null;
            }).when(first).onMobRemoved(f.world, f.target, Entity.RemovalReason.KILLED);
            doAnswer(call -> {
                f.order.add("second");
                return null;
            }).when(second).onMobRemoved(f.world, f.target, Entity.RemovalReason.KILLED);
            doAnswer(call -> {
                f.order.add("clear");
                return true;
            }).when(f.target).removeAllEffects(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.DEATH);
            var actual = f.target.carpetTriggerOnDeathMobEffectsAsync(f.world, Entity.RemovalReason.KILLED);
            assertSame(problem, assertThrows(ExecutionException.class, () -> guest.get().get(3, TimeUnit.SECONDS)).getCause());
            assertEquals(List.of("first"), f.order);
            assertFalse(actual.isDone());
            f.drops.complete(null);
            f.drainUntil(actual::isDone);
            actual.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("first", "second", "clear"), f.order);
        }
    }

    @Test
    void closingTheActualCallbackHostCannotRejectTheAlreadyAcceptedPhysicalDeath() throws Exception {
        try (Fixture f = new Fixture()) {
            var actual = ScarpetNativeDeaths.afterDeath(f.target, () -> f.target.die(f.source), () -> true);
            Host host = new Host();
            carpet.script.Context context = new ReadyContext(host);
            try (var guest = ScarpetRuntime.enterContext(context)) {
                host.onClose();
                f.decision.complete(null);
            }
            f.drops.complete(null);
            assertTrue(actual.get(3, TimeUnit.SECONDS));
            assertTrue(f.order.contains("loot:1"));
        }
    }

    @Test
    void actualRemovedEffectsFinishTheFirstExplosionBeforeTheNextEffectAndClear() throws Exception {
        try (Fixture f = new Fixture()) {
            MobEffectInstance first = mock(MobEffectInstance.class), second = mock(MobEffectInstance.class);
            doReturn(List.of(first, second)).when(f.target).getActiveEffects();
            Field active = LivingEntity.class.getDeclaredField("activeEffects");
            active.setAccessible(true);
            active.set(f.target, new HashMap<>());
            doAnswer(call -> {
                f.order.add("first");
                ScarpetNativeWork.record(f.drops);
                return null;
            }).when(first).onMobRemoved(f.world, f.target, Entity.RemovalReason.KILLED);
            doAnswer(call -> {
                f.order.add("second");
                return null;
            }).when(second).onMobRemoved(f.world, f.target, Entity.RemovalReason.KILLED);
            doAnswer(call -> {
                f.order.add("clear");
                return true;
            }).when(f.target).removeAllEffects(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.DEATH);
            var actual = f.target.carpetTriggerOnDeathMobEffectsAsync(f.world, Entity.RemovalReason.KILLED);
            assertEquals(List.of("first"), f.order);
            assertFalse(actual.isDone());
            f.drops.complete(null);
            actual.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("first", "second", "clear"), f.order);
        }
    }

    @Test
    void actualLivingRemovalWaitsForEffectsThenRemovedEventBeforePhysicalAndSubclassCleanup() throws Exception {
        try (Fixture f = new Fixture()) {
            MobEffectInstance first = mock(MobEffectInstance.class);
            doReturn(List.of(first)).when(f.target).getActiveEffects();
            Field active = LivingEntity.class.getDeclaredField("activeEffects");
            active.setAccessible(true);
            active.set(f.target, new HashMap<>());
            doAnswer(call -> {
                f.order.add("effect");
                ScarpetNativeWork.record(f.drops);
                return null;
            }).when(first).onMobRemoved(f.world, f.target, Entity.RemovalReason.KILLED);
            doReturn(true).when(f.target).removeAllEffects(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.DEATH);
            when(f.events.hasEvent(EntityEventsGroup.Event.ON_REMOVED)).thenReturn(true);
            when(f.events.onEventFuture(EntityEventsGroup.Event.ON_REMOVED)).thenAnswer(call -> {
                f.order.add("removed event");
                return f.decision;
            });
            var brain = mock(Brain.class);
            Field b = LivingEntity.class.getDeclaredField("brain");
            b.setAccessible(true);
            b.set(f.target, brain);
            doAnswer(call -> {
                f.order.add("brain");
                return null;
            }).when(brain).clearMemories();
            doAnswer(call -> {
                f.order.add("physical");
                return null;
            }).when(f.target).setRemoved(any(), any());
            // Removal's last-owner scheduler is asynchronous; use its true dispatch callback in this fixture.
            var serverCraft = mock(org.bukkit.craftbukkit.CraftServer.class);
            var scheduler = mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);
            when(serverCraft.getRegionScheduler()).thenReturn(scheduler);
            Field sf = MinecraftServer.class.getField("server");
            sf.setAccessible(true);
            sf.set(f.server, serverCraft);
            when(f.world.getWorld()).thenReturn(mock(org.bukkit.craftbukkit.CraftWorld.class));
            doAnswer(call -> {
                ((Runnable) call.getArgument(4)).run();
                return null;
            }).when(scheduler).execute(any(), any(), anyInt(), anyInt(), any());
            var observed = ScarpetNativeWork.observeNative(f.target, () -> {
                f.target.remove(Entity.RemovalReason.KILLED, null);
                assertTrue(ScarpetNativeRemovals.thenOwner(f.target, () -> f.order.add("outer")));
                return null;
            });
            assertEquals(List.of("effect"), f.order);
            f.drops.complete(null);
            assertEquals(List.of("effect", "removed event"), f.order);
            assertFalse(observed.isDone());
            f.decision.complete(null);
            observed.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("effect", "removed event", "physical", "brain", "outer"), f.order);
        }
    }

    @Test
    void realPlayerHealthPacketIsReleasedBeforeTheActualBodyResult() throws Exception {
        try (Fixture f = new Fixture()) {
            ServerPlayer player = f.player();
            CompletableFuture<Boolean> body = new CompletableFuture<>();
            var packet = new ClientboundSetHealthPacket(1, 20, 5);
            player.queuedHealthUpdatePacket = packet;
            assertFalse(player.carpetContinueQueuedHealthUpdate(() -> {
                ScarpetDamageContinuations.publishBodyResult(player, body);
                return false;
            }));
            var actual = ScarpetDamageContinuations.pendingBodyResult(player);
            assertNotSame(body, actual);
            assertTrue(player.queueHealthUpdatePacket);
            doAnswer(call -> {
                assertFalse(player.queueHealthUpdatePacket);
                f.order.add("health packet");
                return null;
            }).when(player.connection).send(packet);
            actual.thenAccept(value -> {
                assertTrue(value);
                f.order.add("actual result");
            });
            body.complete(true);
            assertTrue(actual.get(3, TimeUnit.SECONDS));
            assertEquals(List.of("health packet", "actual result"), f.order);
            assertFalse(player.queueHealthUpdatePacket);
            assertNull(player.queuedHealthUpdatePacket);
        }
    }

    @Test
    void twoRealPlayerHealthScopesFlushOnceAfterBothNativeBodies() throws Exception {
        try (Fixture f = new Fixture()) {
            ServerPlayer player = f.player();
            CompletableFuture<Boolean> first = new CompletableFuture<>(), second = new CompletableFuture<>();
            var packet = new ClientboundSetHealthPacket(2, 20, 5);
            player.queuedHealthUpdatePacket = packet;
            player.carpetContinueQueuedHealthUpdate(() -> {
                ScarpetDamageContinuations.publishBodyResult(player, first);
                return false;
            });
            var actualFirst = ScarpetDamageContinuations.pendingBodyResult(player);
            player.carpetContinueQueuedHealthUpdate(() -> {
                ScarpetDamageContinuations.publishBodyResult(player, second);
                return false;
            });
            var actualSecond = ScarpetDamageContinuations.pendingBodyResult(player);
            second.complete(false);
            assertFalse(actualSecond.get(3, TimeUnit.SECONDS));
            assertTrue(player.queueHealthUpdatePacket);
            verify(player.connection, never()).send(any());
            first.complete(true);
            assertTrue(actualFirst.get(3, TimeUnit.SECONDS));
            assertFalse(player.queueHealthUpdatePacket);
            verify(player.connection, times(1)).send(packet);
        }
    }

    @Test
    void actualDeferredDamageReleasesBothHealthScopesBeforeItsPublishedBoolean() throws Exception {
        try (Fixture f = new Fixture()) {
            ServerPlayer player = f.player();
            Field event = Entity.class.getDeclaredField("carpetEvents");
            event.setAccessible(true);
            event.set(player, f.events);
            when(f.events.hasEvent(EntityEventsGroup.Event.ON_DAMAGE)).thenReturn(true);
            when(f.events.onEventFuture(eq(EntityEventsGroup.Event.ON_DAMAGE), any(), any())).thenReturn(f.decision);
            CompletableFuture<Boolean> actualBody = new CompletableFuture<>();
            var packet = new ClientboundSetHealthPacket(3, 20, 5);
            player.queuedHealthUpdatePacket = packet;
            assertFalse(player.carpetContinueQueuedHealthUpdate(() -> {
                assertTrue(ScarpetDamageContinuations.defer(player, f.world, f.source, 5, 0, 20, true, cancelled -> {
                    f.order.add("native body");
                    ScarpetDamageContinuations.publishBodyResult(player, actualBody);
                    return false;
                }));
                return false;
            }));
            var outcome = ScarpetDamageContinuations.pendingResult(player);
            assertNotNull(outcome);
            assertTrue(player.queueHealthUpdatePacket);
            doAnswer(call -> {
                assertFalse(player.queueHealthUpdatePacket);
                f.order.add("health packet");
                return null;
            }).when(player.connection).send(packet);
            outcome.thenAccept(value -> {
                assertTrue(value);
                f.order.add("outcome");
            });
            f.decision.complete(null);
            assertEquals(List.of("native body"), f.order);
            assertFalse(outcome.isDone());
            assertTrue(player.queueHealthUpdatePacket);
            actualBody.complete(true);
            assertTrue(outcome.get(3, TimeUnit.SECONDS));
            assertEquals(List.of("native body", "health packet", "outcome"), f.order);
            assertFalse(player.queueHealthUpdatePacket);
            assertNull(ScarpetDamageContinuations.pendingResult(player));
        }
    }

    @Test
    void nativeFakeDeathActuallyFiresBothOriginalEventsBeforeTheBotDeathBody() throws Exception {
        nativeFakeDeath(false, false, false);
    }

    @Test
    void aRealGuestFailureCannotSkipTheNativeFakeSuperDeathResetAndTrueDisconnect() throws Exception {
        nativeFakeDeath(true, false, false);
    }

    @Test
    void cancelledPaperDeathRevivesTheNativeFakeWithoutResettingOrDisconnectingIt() throws Exception {
        nativeFakeDeath(false, true, false);
    }

    @Test
    void aGuestFailureCannotTurnACancelledPaperDeathIntoAFakeDisconnect() throws Exception {
        nativeFakeDeath(true, true, false);
    }

    @Test
    void cancelledPaperDeathPreservesHealthAlreadyRestoredByThePlugin() throws Exception {
        nativeFakeDeath(false, true, true);
    }

    private void nativeFakeDeath(boolean failGuest, boolean cancelDeath, boolean pluginRevives) throws Exception {
        try (Fixture f = new Fixture(); var adventure = mockStatic(io.papermc.paper.adventure.PaperAdventure.class)) {
            adventure.when(() -> io.papermc.paper.adventure.PaperAdventure.asAdventure(any(net.minecraft.network.chat.Component.class))).thenReturn(net.kyori.adventure.text.Component.text("fake"));
            adventure.when(() -> io.papermc.paper.adventure.PaperAdventure.asVanilla(any(net.kyori.adventure.text.Component.class))).thenReturn(net.minecraft.network.chat.Component.empty());
            var bot = mock(org.leavesmc.leaves.bot.ServerBot.class, CALLS_REAL_METHODS);
            bot.carpetNativePlayer = true;
            // Mockito skips Entity/Player construction; the real Paper death body sends an entity-ID packet.
            bot.setId(73);
            bot.setUUID(new UUID(0, 73));
            bot.gameProfile = new com.mojang.authlib.GameProfile(bot.getUUID(), "NativeDeathFixture");
            doReturn(EntityTypes.PLAYER).when(bot).getType();
            doReturn(net.minecraft.world.phys.Vec3.ZERO).when(bot).position();
            doReturn(new net.minecraft.world.entity.ai.attributes.AttributeMap(net.minecraft.world.entity.player.Player.createAttributes().build())).when(bot).getAttributes();
            doReturn(new net.minecraft.world.entity.player.Abilities()).when(bot).getAbilities();
            doReturn(net.minecraft.util.RandomSource.create(19)).when(bot).getRandom();
            Field nativeServer = ServerPlayer.class.getDeclaredField("server");
            nativeServer.setAccessible(true);
            nativeServer.set(bot, f.server);
            doReturn(f.world).when(bot).level();
            doReturn(BlockPos.ZERO).when(bot).blockPosition();
            doReturn(false).when(bot).isRemoved();
            doReturn(null).when(bot).getVehicle();
            doReturn(List.of()).when(bot).getPassengers();
            doReturn(true).when(bot).isSpectator();
            doReturn(mock(net.minecraft.world.entity.player.Inventory.class)).when(bot).getInventory();
            doReturn(f.server).when(bot).carpetSpawnServer();
            bot.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            doNothing().when(bot).gameEvent(any(net.minecraft.core.Holder.class));
            doNothing().when(bot).removeEntitiesOnShoulder();
            doNothing().when(bot).setCamera(any());
            doNothing().when(bot).awardStat(any(net.minecraft.stats.Stat.class));
            doNothing().when(bot).resetStat(any(net.minecraft.stats.Stat.class));
            doNothing().when(bot).clearFire();
            doNothing().when(bot).setTicksFrozen(anyInt());
            doNothing().when(bot).setSharedFlagOnFire(anyBoolean());
            doNothing().when(bot).setLastDeathLocation(any());
            f.attach(bot, mock(org.leavesmc.leaves.entity.bot.CraftBot.class));
            var combat = mock(net.minecraft.world.damagesource.CombatTracker.class);
            when(combat.getDeathMessage()).thenReturn(net.minecraft.network.chat.Component.literal("fake"));
            doReturn(combat).when(bot).getCombatTracker();
            var health = new java.util.concurrent.atomic.AtomicReference<>(0F);
            doAnswer(call -> health.get()).when(bot).getHealth();
            doAnswer(call -> {
                float value = call.getArgument(0);
                health.set(value);
                f.order.add("health:" + value);
                return null;
            }).when(bot).setHealth(anyFloat());
            var food = new net.minecraft.world.food.FoodData();
            Field foodField = net.minecraft.world.entity.player.Player.class.getDeclaredField("foodData");
            foodField.setAccessible(true);
            foodField.set(bot, food);
            Field event = Entity.class.getDeclaredField("carpetEvents");
            event.setAccessible(true);
            event.set(bot, f.events);
            var rules = spy(new GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));
            doReturn(false).when(rules).get(GameRules.SHOW_DEATH_MESSAGES);
            doReturn(true).when(rules).get(GameRules.KEEP_INVENTORY);
            doReturn(false).when(rules).get(GameRules.FORGIVE_DEAD_PLAYERS);
            when(f.world.getGameRules()).thenReturn(rules);
            var craftServer = mock(org.bukkit.craftbukkit.CraftServer.class);
            var plugins = mock(org.bukkit.plugin.PluginManager.class);
            when(craftServer.getPluginManager()).thenReturn(plugins);
            when(f.world.getCraftServer()).thenReturn(craftServer);
            when(f.world.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
            when(craftServer.getScoreboardManager()).thenReturn(mock(org.bukkit.craftbukkit.scoreboard.CraftScoreboardManager.class));
            Field server = MinecraftServer.class.getField("server");
            server.setAccessible(true);
            server.set(f.server, craftServer);
            var deathEvent = mock(org.bukkit.event.entity.PlayerDeathEvent.class);
            when(deathEvent.isCancelled()).thenReturn(cancelDeath);
            when(deathEvent.getReviveHealth()).thenReturn(1D);
            when(deathEvent.getKeepInventory()).thenReturn(true);
            f.craft.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerDeathEvent(eq(bot), eq(f.source), anyList(), any(net.kyori.adventure.text.Component.class), eq(false), eq(true))).thenAnswer(call -> {
                f.order.add("player death body");
                if (pluginRevives) bot.setHealth(7F);
                return deathEvent;
            });
            var bots = mock(org.leavesmc.leaves.bot.BotList.class);
            when(f.server.getBotList()).thenReturn(bots);
            var removed = new CompletableFuture<Boolean>();
            when(bots.carpetRemoveBotAsync(eq(bot), eq(org.leavesmc.leaves.event.bot.BotRemoveEvent.RemoveReason.DEATH), isNull(), eq(true), eq(false))).thenAnswer(call -> {
                f.order.add("typed disconnect");
                return removed;
            });
            var handler = mock(CarpetEventServer.CallbackList.class);
            Field calls = CarpetEventServer.CallbackList.class.getDeclaredField("callList");
            calls.setAccessible(true);
            calls.set(handler, List.of(mock(CarpetEventServer.Callback.class)));
            var playerDecision = new CompletableFuture<Boolean>();
            Field capture = ScarpetRuntime.class.getDeclaredField("EVENT_CAPTURE");
            capture.setAccessible(true);
            doAnswer(call -> {
                assertTrue(ScarpetRuntime.currentNativeDecision(bot, ScarpetNativeDeaths.EVENT_KEY));
                f.order.add("player_dies");
                var slot = (ThreadLocal<CompletableFuture<Boolean>>) capture.get(null);
                var result = slot.get();
                slot.remove();
                playerDecision.whenComplete((value, failure) -> result.complete(value));
                return false;
            }).when(handler).call(any(), any());
            Field hf = CarpetEventServer.Event.class.getField("handler");
            hf.setAccessible(true);
            Object original = hf.get(CarpetEventServer.Event.PLAYER_DIES);
            hf.set(CarpetEventServer.Event.PLAYER_DIES, handler);
            var guest = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
            var release = new CountDownLatch(1);
            var problem = new IllegalStateException("real native fake callback failed");
            if (failGuest) doAnswer(call -> {
                f.order.add("event");
                var work = f.runtime.<Void>submit(() -> {
                    try {
                        if (!release.await(3, TimeUnit.SECONDS))
                            throw new AssertionError("fake callback release timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    throw problem;
                });
                guest.set(work);
                return work;
            }).when(f.events).onEventFuture(EntityEventsGroup.Event.ON_DEATH, "test");
            try {
                var actual = ScarpetNativeDeaths.afterDeath(bot, () -> bot.die(f.source), () -> true);
                assertEquals(List.of("event"), f.order);
                if (failGuest) {
                    release.countDown();
                    assertSame(problem, assertThrows(ExecutionException.class, () -> guest.get().get(3, TimeUnit.SECONDS)).getCause());
                    f.drainUntil(() -> f.order.contains("player_dies"));
                } else f.decision.complete(null);
                assertEquals(List.of("event", "player_dies"), f.order);
                assertFalse(actual.isDone());
                playerDecision.complete(false);
                if (actual.isCompletedExceptionally()) actual.join();
                Field restored = org.leavesmc.leaves.bot.ServerBot.class.getDeclaredField("carpetRestoreAfterDeath");
                restored.setAccessible(true);
                if (cancelDeath) {
                    assertEquals(List.of("event", "player_dies", "player death body", pluginRevives ? "health:7.0" : "health:1.0"), f.order);
                    assertEquals(pluginRevives ? 7F : 1F, bot.getHealth());
                    assertSame(food, bot.getFoodData());
                    assertFalse(restored.getBoolean(bot));
                    verify(bots, never()).carpetRemoveBotAsync(any(), any(), any(), anyBoolean(), anyBoolean());
                } else {
                    assertEquals(List.of("event", "player_dies", "player death body", "health:20.0", "typed disconnect"), f.order);
                    assertFalse(actual.isDone());
                    assertEquals(20F, bot.getHealth());
                    assertNotSame(food, bot.getFoodData());
                    assertTrue(restored.getBoolean(bot));
                    removed.complete(true);
                }
                assertTrue(actual.get(3, TimeUnit.SECONDS));
                assertFalse(ScarpetNativeDeaths.isPending(bot));
                assertTrue(ScarpetNativeWork.whenIdle(f.server).isDone());
                verify(plugins, never()).callEvent(any(org.leavesmc.leaves.event.bot.BotDeathEvent.class));
            } finally {
                hf.set(CarpetEventServer.Event.PLAYER_DIES, original);
            }
        }
    }

    @Test
    void theRealPaperPlayerDeathProducesTheProfileHeadOnlyWhenInventoryDrops() throws Exception {
        boolean previous = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.headHunter;
        fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.headHunter = true;
        try (Fixture f = new Fixture(); var adventure = mockStatic(io.papermc.paper.adventure.PaperAdventure.class)) {
            adventure.when(() -> io.papermc.paper.adventure.PaperAdventure.asAdventure(any(net.minecraft.network.chat.Component.class))).thenReturn(net.kyori.adventure.text.Component.empty());
            var rules = mock(GameRules.class);
            when(rules.get(GameRules.SHOW_DEATH_MESSAGES)).thenReturn(false);
            when(f.world.getGameRules()).thenReturn(rules);
            for (boolean keep : new boolean[]{false, true}) {
                when(rules.get(GameRules.KEEP_INVENTORY)).thenReturn(keep);
                ServerPlayer player = f.player();
                doReturn(false).when(player).isSpectator();
                doReturn(0F).when(player).getHealth();
                doNothing().when(player).setHealth(anyFloat());
                var inventory = mock(net.minecraft.world.entity.player.Inventory.class);
                when(inventory.getContents()).thenReturn(List.of());
                doReturn(inventory).when(player).getInventory();
                var combat = mock(net.minecraft.world.damagesource.CombatTracker.class);
                when(combat.getDeathMessage()).thenReturn(net.minecraft.network.chat.Component.empty());
                doReturn(combat).when(player).getCombatTracker();
                doReturn(new com.mojang.authlib.GameProfile(new UUID(0, 37), "HeadFixture")).when(player).getGameProfile();
                var death = mock(org.bukkit.event.entity.PlayerDeathEvent.class);
                when(death.isCancelled()).thenReturn(true);
                var sizes = new ArrayList<Integer>();
                f.craft.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerDeathEvent(eq(player), eq(f.source), anyList(), any(net.kyori.adventure.text.Component.class), eq(false), eq(keep)))
                        .thenAnswer(call -> {
                            sizes.add(((List<?>) call.getArgument(2)).size());
                            return death;
                        });
                try (var stacks = mockConstruction(ItemStack.class)) {
                    player.die(f.source);
                    assertEquals(List.of(keep ? 0 : 1), sizes);
                    if (!keep) {
                        assertEquals(1, stacks.constructed().size());
                        verify(stacks.constructed().getFirst()).set(eq(net.minecraft.core.component.DataComponents.PROFILE), any(net.minecraft.world.item.component.ResolvableProfile.class));
                    } else assertTrue(stacks.constructed().isEmpty());
                }
            }
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.headHunter = previous;
        }
    }

    @Test
    void closingTheCallbackHostDoesNotRejectTheAlreadyAcceptedActualDamageRemainder() throws Exception {
        try (Fixture f = new Fixture()) {
            ServerPlayer player = f.player();
            Field event = Entity.class.getDeclaredField("carpetEvents");
            event.setAccessible(true);
            event.set(player, f.events);
            when(f.events.hasEvent(EntityEventsGroup.Event.ON_DAMAGE)).thenReturn(true);
            when(f.events.onEventFuture(eq(EntityEventsGroup.Event.ON_DAMAGE), any(), any())).thenReturn(f.decision);
            Host host = new Host();
            var context = new ReadyContext(host);
            CompletableFuture<Boolean> actual;
            try (var guest = ScarpetRuntime.enterContext(context)) {
                assertTrue(ScarpetDamageContinuations.defer(player, f.world, f.source, 5, 0, 20, true, cancelled -> {
                    f.order.add("actual damage");
                    return true;
                }));
                actual = ScarpetDamageContinuations.pendingResult(player);
                assertNotNull(actual);
                assertFalse(actual.isDone());
                host.onClose();
                f.decision.complete(null);
            }
            assertTrue(actual.get(3, TimeUnit.SECONDS));
            assertEquals(List.of("actual damage"), f.order);
        }
    }

    @Test
    void independentRepeatedDeathHasItsOwnHeadAndWaitsThePriorNativeTail() throws Exception {
        try (Fixture f = new Fixture()) {
            var secondGuest = new CompletableFuture<Void>();
            var firstTail = new CompletableFuture<Void>();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                int n = calls.incrementAndGet();
                f.order.add("event:" + n);
                return n == 1 ? f.decision : secondGuest;
            }).when(f.events).onEventFuture(EntityEventsGroup.Event.ON_DEATH, "test");
            assertTrue(ScarpetNativeDeaths.defer(f.target, f.source, () -> {
                f.order.add("native:1");
                ScarpetNativeWork.record(f.drops);
            }));
            assertTrue(ScarpetNativeDeaths.thenOwner(f.target, () -> {
                f.order.add("tail:1");
                ScarpetNativeWork.record(firstTail);
            }));
            var first = ScarpetNativeDeaths.completion(f.target);
            assertTrue(ScarpetNativeDeaths.defer(f.target, f.source, () -> f.order.add("native:2")));
            assertTrue(ScarpetNativeDeaths.thenOwner(f.target, () -> f.order.add("tail:2")));
            var second = ScarpetNativeDeaths.completion(f.target);
            assertEquals(List.of("event:1"), f.order);
            first.cancel(false);
            f.decision.complete(null);
            assertEquals(List.of("event:1", "native:1"), f.order);
            f.drops.complete(null);
            assertEquals(List.of("event:1", "native:1", "tail:1"), f.order);
            assertFalse(second.isDone());
            firstTail.complete(null);
            assertEquals(List.of("event:1", "native:1", "tail:1", "event:2"), f.order);
            assertFalse(second.isDone());
            secondGuest.complete(null);
            second.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("event:1", "native:1", "tail:1", "event:2", "native:2", "tail:2"), f.order);
            assertFalse(ScarpetNativeDeaths.isPending(f.target));
        }
    }

    @Test
    void acceptedDamageUsesOriginalWorldAfterCallbackMovesOrRemovesItsActualTarget() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.events.hasEvent(EntityEventsGroup.Event.ON_DAMAGE)).thenReturn(true);
            when(f.events.onEventFuture(eq(EntityEventsGroup.Event.ON_DAMAGE), any(), any())).thenReturn(f.decision);
            var original = f.world;
            var moved = mock(ServerLevel.class);
            when(moved.getServer()).thenReturn(f.server);
            var realChild = new CompletableFuture<Void>();
            assertTrue(ScarpetDamageContinuations.defer(f.target, original, f.source, 5, 0, 20, true, cancelled -> {
                assertSame(moved, f.target.level());
                assertTrue(f.target.isRemoved());
                assertSame(original, f.world);
                f.order.add("original world body");
                ScarpetNativeWork.record(realChild);
                return true;
            }));
            var result = ScarpetDamageContinuations.pendingResult(f.target);
            assertNotNull(result);
            doReturn(moved).when(f.target).level();
            doReturn(true).when(f.target).isRemoved();
            f.decision.complete(null);
            assertEquals(List.of("original world body"), f.order);
            assertFalse(result.isDone());
            realChild.complete(null);
            assertTrue(result.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void realForeignPlayerKillCreditAndStatsFollowPreLootEventAwardAndKilledOrder() throws Exception {
        try (Fixture f = new Fixture()) {
            var killer = f.player();
            var otherWorld = mock(ServerLevel.class);
            when(otherWorld.getServer()).thenReturn(f.server);
            when(otherWorld.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(19));
            var nativeRegistries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            when(otherWorld.registryAccess()).thenReturn(nativeRegistries);
            when(f.server.reloadableRegistries()).thenReturn(new net.minecraft.server.ReloadableServerRegistries.Holder(nativeRegistries));
            doReturn(otherWorld).when(killer).level();
            doReturn(f.server).when(killer).carpetSpawnServer();
            doReturn(0F).when(killer).getLuck();
            doReturn(BlockPos.ZERO).when(killer).blockPosition();
            doReturn(net.minecraft.world.phys.Vec3.ZERO).when(killer).position();
            var sourceWrapper = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
            f.attach(killer, sourceWrapper);
            when(sourceWrapper.getHandleRaw()).thenReturn(killer);
            when(sourceWrapper.getUniqueId()).thenReturn(UUID.randomUUID());
            var scoreboard = mock(net.minecraft.server.ServerScoreboard.class);
            when(otherWorld.getScoreboard()).thenReturn(scoreboard);
            var bukkitBoard = mock(org.bukkit.craftbukkit.scoreboard.CraftScoreboard.class);
            when(sourceWrapper.getScoreboard()).thenReturn(bukkitBoard);
            when(bukkitBoard.getHandle()).thenReturn(scoreboard);
            var craft = mock(org.bukkit.craftbukkit.CraftServer.class);
            when(otherWorld.getCraftServer()).thenReturn(craft);
            var manager = mock(org.bukkit.craftbukkit.scoreboard.CraftScoreboardManager.class);
            when(craft.getScoreboardManager()).thenReturn(manager);
            var stats = mock(net.minecraft.stats.ServerStatsCounter.class);
            Field statField = ServerPlayer.class.getDeclaredField("stats");
            statField.setAccessible(true);
            statField.set(killer, stats);
            doReturn(mock(net.minecraft.server.PlayerAdvancements.class)).when(killer).getAdvancements();
            doReturn("killer").when(killer).getScoreboardName();
            doReturn("victim").when(f.target).getScoreboardName();
            doReturn(EntityTypes.COW).when(f.target).getType();
            var credit = LivingEntity.class.getDeclaredField("lastHurtByPlayer");
            credit.setAccessible(true);
            credit.set(f.target, EntityReference.of(killer));
            f.ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> call.getArgument(0) == f.activeOwner);
            var pre = new CompletableFuture<Void>();
            var statChild = new CompletableFuture<Void>();
            doAnswer(call -> {
                assertSame(killer, f.activeOwner);
                assertSame(f.world, call.getArgument(0));
                f.order.add("pre");
                ScarpetNativeWork.record(pre);
                return true;
            })
                    .when(killer).killedEntityPreEvent(eq(f.world), eq(f.target), eq(f.source));
            doAnswer(call -> {
                assertSame(killer, f.activeOwner);
                f.order.add(call.getArgument(1) == net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.MOB_KILLS) ? "award stat" : "killed stat");
                ScarpetNativeWork.record(statChild);
                return null;
            })
                    .when(stats).increment(eq(killer), any(), eq(1));
            when(f.source.getEntity()).thenReturn(killer);
            var event = mock(org.bukkit.event.entity.EntityDeathEvent.class);
            f.craft.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.callEntityDeathEvent(eq(f.world), eq(f.target), eq(f.source), anyList(), eq(true)))
                    .thenAnswer(call -> {
                        assertSame(f.target, f.activeOwner);
                        f.order.add("Bukkit");
                        return event;
                    });
            var actual = ScarpetNativeDeaths.afterDeath(f.target, () -> f.target.die(f.source), () -> {
                f.order.add("hurt tail");
                return true;
            });
            f.decision.complete(null);
            f.drainUntil(() -> f.order.contains("pre"));
            assertFalse(f.order.contains("loot:1"));
            pre.complete(null);
            f.drainUntil(() -> f.order.contains("loot:1"));
            assertFalse(f.order.contains("Bukkit"));
            f.drops.complete(null);
            f.drainUntil(() -> f.order.contains("award stat"));
            assertFalse(f.order.contains("killed stat"));
            assertFalse(f.order.contains("rose"));
            assertFalse(actual.isDone());
            statChild.complete(null);
            f.drainUntil(actual::isDone);
            assertTrue(actual.get(3, TimeUnit.SECONDS));
            assertEquals(List.of("event", "pre", "loot:1", "Bukkit", "award stat", "killed stat", "rose", "pose", "hurt tail"), f.order);
        }
    }

    @Test
    void realEntityReferenceChecksCachedRetirementOnlyOnItsActualOwner() throws Exception {
        try (Fixture f = new Fixture()) {
            var cached = mock(net.minecraft.world.entity.player.Player.class);
            var replacement = mock(net.minecraft.world.entity.player.Player.class);
            var wrapper = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
            f.attach(cached, wrapper);
            when(wrapper.getHandleRaw()).thenReturn(cached);
            UUID id = UUID.randomUUID();
            when(wrapper.getUniqueId()).thenReturn(id);
            var replacementWrapper = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
            f.attach(replacement, replacementWrapper);
            doReturn(f.world).when(cached).level();
            doReturn(BlockPos.ZERO).when(cached).blockPosition();
            doReturn(f.world).when(replacement).level();
            doReturn(BlockPos.ZERO).when(replacement).blockPosition();
            when(cached.isRemoved()).thenAnswer(call -> {
                assertSame(cached, f.activeOwner);
                return true;
            });
            when(replacement.isRemoved()).thenAnswer(call -> {
                assertSame(replacement, f.activeOwner);
                return false;
            });
            when(f.world.getPlayerInAnyDimension(id)).thenAnswer(call -> {
                assertSame(f.target, f.activeOwner);
                return replacement;
            });
            f.ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> call.getArgument(0) == f.activeOwner);
            var actual = ScarpetNativeDeathActors.resolve(f.target, EntityReference.of(cached), net.minecraft.world.entity.player.Player.class);
            assertFalse(actual.isDone());
            f.drainUntil(actual::isDone);
            assertSame(replacement, actual.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualLootLuckIsProducedByItsForeignPlayerOwnerThenReadOnlyInTheVictimScope() throws Exception {
        try (Fixture f = new Fixture()) {
            var player = mock(net.minecraft.world.entity.player.Player.class);
            var wrapper = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
            f.attach(player, wrapper);
            when(wrapper.getHandleRaw()).thenReturn(player);
            when(wrapper.getUniqueId()).thenReturn(UUID.randomUUID());
            doReturn(f.world).when(player).level();
            doReturn(BlockPos.ZERO).when(player).blockPosition();
            var credit = LivingEntity.class.getDeclaredField("lastHurtByPlayer");
            credit.setAccessible(true);
            credit.set(f.target, EntityReference.of(player));
            when(player.getLuck()).thenAnswer(call -> {
                assertSame(player, f.activeOwner);
                return 17.25F;
            });
            f.ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> call.getArgument(0) == f.activeOwner);
            var actual = ScarpetNativeDeathActors.loot(f.target, () -> {
                assertSame(f.target, f.activeOwner);
                var loot = ScarpetNativeDeathActors.currentLoot(f.target);
                assertSame(player, loot.player());
                return loot.luck();
            });
            assertFalse(actual.isDone());
            f.drainUntil(actual::isDone);
            assertEquals(17.25F, actual.get(3, TimeUnit.SECONDS));
            assertNull(ScarpetNativeDeathActors.currentLoot(f.target));
        }
    }

}
