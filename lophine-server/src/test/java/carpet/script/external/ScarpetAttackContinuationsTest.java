package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetAttackContinuationsTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final ServerPlayer attacker = mock(ServerPlayer.class);
        final LivingEntity target = mock(LivingEntity.class);
        final AtomicBoolean owned = new AtomicBoolean(true);
        final ArrayDeque<Consumer<Entity>> tasks = new ArrayDeque<>();
        final MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final MockedStatic<MinecraftServer> servers = mockStatic(MinecraftServer.class);
        Fixture() throws Exception {
            servers.when(MinecraftServer::getServer).thenReturn(server);
            when(attacker.level()).thenReturn(world); when(world.getServer()).thenReturn(server);
            when(attacker.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
            when(target.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
            var bukkit = mock(CraftPlayer.class); when(attacker.getBukkitEntity()).thenReturn(bukkit);
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var field = CraftEntity.class.getField("taskScheduler"); field.setAccessible(true); field.set(bukkit, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> { tasks.add(call.getArgument(0)); return true; });
            ticks.when(() -> TickThread.isTickThreadFor(attacker)).thenAnswer(call -> owned.get());
            ticks.when(() -> TickThread.isTickThreadFor(target)).thenAnswer(call -> owned.get());
        }
        void tick() { var action = tasks.removeFirst(); owned.set(true); try { action.accept(attacker); } finally { owned.set(false); } }
        @Override public void close() { ScarpetRuntime.beginShutdown(server, () -> {}); servers.close(); ticks.close(); }
    }

    @Test void realNativeDamageStatisticsReadHealthOnlyAfterActualDamageAndOwnerResume() throws Exception {
        try (var f = new Fixture()) {
            var health = new AtomicReference<Float>(10f);
            when(f.target.getHealth()).thenAnswer(call -> { assertTrue(f.owned.get()); return health.get(); });
            var method = Player.class.getDeclaredMethod("damageStatsAndHearts", Entity.class, float.class); method.setAccessible(true);
            var outcome = new CompletableFuture<Boolean>();
            var actual = ScarpetAttackContinuations.afterDamage(f.attacker, f.target, outcome, hurt -> {
                assertTrue(hurt);
                try { method.invoke(f.attacker, f.target, 10f); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                return true;
            });
            verify(f.attacker, never()).awardStat(eq(Stats.DAMAGE_DEALT), anyInt());
            assertFalse(actual.isDone());
            f.owned.set(false); health.set(6f); outcome.complete(true);
            assertFalse(actual.isDone());
            verify(f.attacker, never()).awardStat(eq(Stats.DAMAGE_DEALT), anyInt());
            f.tick();
            assertTrue(actual.get(3, TimeUnit.SECONDS));
            verify(f.attacker).awardStat(Stats.DAMAGE_DEALT, 40);
        }
    }

    @Test void actualCancellationIsFalseAndAttackerCompletionWaitsForDynamicNativeChild() throws Exception {
        try (var f = new Fixture()) {
            var outcome = new CompletableFuture<Boolean>();
            var child = new CompletableFuture<Void>();
            var actual = ScarpetAttackContinuations.afterDamage(f.attacker, f.target, outcome, hurt -> {
                assertFalse(hurt); ScarpetNativeWork.record(child); return hurt;
            });
            f.owned.set(false); outcome.complete(false); f.tick();
            assertFalse(actual.isDone());
            child.complete(null);
            assertFalse(actual.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void acceptedOwnerAuthorityCrossesVmAndOwnerHopsAndIsRestoredForNextGuest() throws Exception {
        try (var f = new Fixture()) {
            var running = new CompletableFuture<Void>();
            ScarpetPlayerInventoryGate.trackAccepted(f.attacker, running);
            var snapshot = ScarpetPlayerInventoryGate.whenIdle(f.attacker, () -> 7);
            assertTrue(ScarpetPlayerInventoryGate.paused(f.attacker));
            CompletableFuture<Boolean> allowed;
            try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(f.attacker)) {
                allowed = ScarpetRuntime.of(f.server).submit(() -> !ScarpetPlayerInventoryGate.paused(f.attacker));
            }
            assertTrue(allowed.get(3, TimeUnit.SECONDS));
            assertTrue(ScarpetRuntime.of(f.server).submit(() -> ScarpetPlayerInventoryGate.paused(f.attacker)).get(3, TimeUnit.SECONDS));
            assertTrue(ScarpetPlayerInventoryGate.paused(f.attacker));
            f.owned.set(false); running.complete(null);
            assertFalse(snapshot.isDone()); assertEquals(1,f.tasks.size());
            f.tick();
            assertEquals(7, snapshot.get(3, TimeUnit.SECONDS));
            assertFalse(ScarpetPlayerInventoryGate.paused(f.attacker));
        }
    }
}
