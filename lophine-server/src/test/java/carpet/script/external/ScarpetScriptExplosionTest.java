package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.*;
import carpet.script.value.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real guest expression -> native core with explicit foreign attacker -> true packet completion.
 */
public class ScarpetScriptExplosionTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final ScriptServer FILES = new ScriptServer() {
        @Override
        public Path resolveResource(String name) {
            return Path.of(name);
        }
    };

    private static final class Host extends ScriptHost {
        Host() {
            super(null, FILES, false, null, Expression.LoadOverride.DEFAULT);
        }

        @Override
        protected carpet.script.Module getModuleOrLibraryByName(String name) {
            return null;
        }

        @Override
        protected void runModuleCode(Context context, carpet.script.Module module) {
        }

        @Override
        protected ScriptHost duplicate() {
            return new Host();
        }
    }

    private static final class ReadyContext extends CarpetContext {
        ReadyContext(Host host, net.minecraft.commands.CommandSourceStack source) {
            super(host, source, BlockPos.ZERO);
            initialize();
        }
    }

    @Test
    void actualCreateExplosionAcceptsAForeignAttackerAndWaitsForPacketReceiptsWithItsLeaseHeld() throws Exception {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var foreign = mock(ServerLevel.class);
        var attacker = mock(LivingEntity.class);
        var tasks = new LinkedBlockingQueue<Runnable>();
        var owner = new AtomicReference<ServerLevel>();
        var activeLeases = new AtomicInteger();
        var packetReceipts = new CompletableFuture<Void>();
        var atPackets = new AtomicBoolean();
        var host = new Host();
        boolean oldNoBlocks = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage;
        try (var ticks = mockStatic(TickThread.class); var bukkit = mockStatic(org.bukkit.Bukkit.class); var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open(); var packets = mockStatic(ScarpetExplosionPackets.class)) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage = true;
            CraftServer craft = mock(CraftServer.class);
            when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            var sf = MinecraftServer.class.getField("server");
            sf.setAccessible(true);
            sf.set(server, craft);
            // Initialize on the fixture thread before the VM creates its first actor request.
            assertNotNull(org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE);
            when(world.getServer()).thenReturn(server);
            when(world.getWorld()).thenReturn(mock(CraftWorld.class));
            var region = mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);
            when(craft.getRegionScheduler()).thenReturn(region);
            Consumer<Runnable> atWorld = action -> {
                var previous = owner.getAndSet(world);
                try {
                    action.run();
                } finally {
                    owner.set(previous);
                }
            };
            Consumer<Runnable> atForeign = action -> {
                var previous = owner.getAndSet(foreign);
                try {
                    action.run();
                } finally {
                    owner.set(previous);
                }
            };
            doAnswer(call -> {
                tasks.add(() -> atWorld.accept(call.getArgument(4)));
                return null;
            }).when(region).execute(any(), any(org.bukkit.World.class), anyInt(), anyInt(), any(Runnable.class));
            when(attacker.level()).thenReturn(foreign);
            when(attacker.blockPosition()).thenReturn(BlockPos.ZERO);
            when(attacker.position()).thenReturn(Vec3.ZERO);
            when(attacker.getDisplayName()).thenReturn(Component.literal("foreign actual attacker"));
            when(attacker.getUUID()).thenReturn(UUID.randomUUID());
            when(attacker.getMainHandItem()).thenAnswer(call -> {
                assertSame(foreign, owner.get());
                return ItemStack.EMPTY;
            });
            when(attacker.isInWater()).thenAnswer(call -> {
                assertSame(foreign, owner.get());
                return false;
            });
            var ce = mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class);
            when(attacker.getBukkitEntity()).thenReturn(ce);
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var qf = CraftEntity.class.getField("taskScheduler");
            qf.setAccessible(true);
            qf.set(ce, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                Consumer<Entity> action = call.getArgument(0);
                tasks.add(() -> atForeign.accept(() -> action.accept(attacker)));
                return true;
            });
            ticks.when(() -> TickThread.isTickThreadFor(attacker)).thenAnswer(call -> owner.get() == foreign);
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenAnswer(call -> owner.get() == world);
            leases.when(() -> fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                activeLeases.incrementAndGet();
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>, Object> action = call.getArgument(5);
                var value = new AtomicReference<Object>();
                atWorld.accept(() -> value.set(action.apply(null)));
                Object actual = value.get();
                if (actual instanceof CompletableFuture<?> phase)
                    phase.whenComplete((unused, failure) -> activeLeases.decrementAndGet());
                else activeLeases.decrementAndGet();
                return CompletableFuture.completedFuture(actual);
            });
            DamageSources damageSources = mock(DamageSources.class);
            when(world.damageSources()).thenReturn(damageSources);
            when(damageSources.explosion(any(net.minecraft.world.level.Explosion.class))).thenAnswer(call -> {
                var e = call.<ServerExplosion>getArgument(0);
                assertSame(attacker, e.getIndirectSourceEntity());
                var source = mock(DamageSource.class);
                when(source.getEntity()).thenReturn(attacker);
                return source;
            });
            packets.when(() -> ScarpetExplosionPackets.send(any(), anyInt(), any(), any(), any(), any())).thenAnswer(call -> {
                ServerExplosion e = call.getArgument(0);
                assertSame(attacker, e.getIndirectSourceEntity());
                assertTrue(activeLeases.get() > 0);
                atPackets.set(true);
                return packetReceipts;
            });
            var css = mock(net.minecraft.commands.CommandSourceStack.class);
            when(css.getServer()).thenReturn(server);
            when(css.getLevel()).thenReturn(world);
            List<Value> arguments = List.of(ListValue.of(NumericValue.ZERO, NumericValue.ZERO, NumericValue.ZERO), NumericValue.ZERO, StringValue.of("keep"), Value.FALSE, Value.NULL, EntityValue.of(attacker));
            ScarpetRuntime runtime = ScarpetRuntime.of(server);
            var result = runtime.submit(() -> {
                // A completed actor future can attach its next phase on the VM thread. Static Mockito mocks
                // belong to their creating thread, so this lease admission must also model that real path.
                try (var vmLeases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
                    vmLeases.when(() -> fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                        Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>, Object> action = call.getArgument(5);
                        var admitted = new CompletableFuture<Object>();
                        tasks.add(() -> atWorld.accept(() -> {
                            activeLeases.incrementAndGet();
                            try {
                                Object actual = action.apply(null);
                                if (actual instanceof CompletableFuture<?> phase)
                                    phase.whenComplete((unused, failure) -> activeLeases.decrementAndGet());
                                else activeLeases.decrementAndGet();
                                admitted.complete(actual);
                            } catch (Throwable failure) {
                                activeLeases.decrementAndGet();
                                admitted.completeExceptionally(failure);
                            }
                        }));
                        return admitted;
                    });
                    Expression expression = new Expression("compat_actual_explosion()");
                    expression.addContextFunction("compat_actual_explosion", 0, (context, type, args) -> ScarpetExplosions.create((CarpetContext) context, arguments));
                    return expression.executeAndEvaluate(new ReadyContext(host, css), true, Expression.LoadOverride.DEFAULT, null).getLeft();
                }
            });
            int steps = 0;
            while (!atPackets.get()) {
                Runnable task = tasks.poll(3, TimeUnit.SECONDS);
                assertNotNull(task);
                assertTrue(++steps < 50);
                task.run();
            }
            assertFalse(result.isDone());
            assertTrue(activeLeases.get() > 0);
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            packetReceipts.complete(null);
            assertTrue(result.get(3, TimeUnit.SECONDS).getBoolean());
            assertEquals(0, activeLeases.get());
            ScarpetNativeWork.whenIdle(server).get(3, TimeUnit.SECONDS);
        } finally {
            host.onClose();
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.explosionNoBlockDamage = oldNoBlocks;
            ScarpetRuntime.beginShutdown(server, () -> {
            });
        }
    }
}
