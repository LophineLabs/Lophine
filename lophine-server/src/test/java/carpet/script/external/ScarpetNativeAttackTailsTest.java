package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The true continuation runs on each actual entity owner; no foreign entity is used as a view or copy.
 */
public class ScarpetNativeAttackTailsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel from = mock(ServerLevel.class), to = mock(ServerLevel.class);
        final ServerPlayer attacker = mock(ServerPlayer.class);
        final LivingEntity target = mock(LivingEntity.class);
        final Queue<Runnable> actions = new ConcurrentLinkedQueue<>();
        Entity owner = attacker;
        final org.mockito.MockedStatic<TickThread> ticks = mockStatic(TickThread.class);

        Fixture() throws Exception {
            when(from.getServer()).thenReturn(server);
            when(to.getServer()).thenReturn(server);
            when(attacker.carpetSpawnServer()).thenReturn(server);
            when(attacker.level()).thenReturn(from);
            when(attacker.blockPosition()).thenReturn(BlockPos.ZERO);
            when(target.level()).thenReturn(to);
            when(target.blockPosition()).thenReturn(BlockPos.ZERO);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> call.getArgument(0) == owner);
            attach(attacker, mock(org.bukkit.craftbukkit.entity.CraftPlayer.class));
            attach(target, mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class));
        }

        void attach(Entity entity, org.bukkit.craftbukkit.entity.CraftEntity wrapper) throws Exception {
            when(entity.getBukkitEntity()).thenReturn(wrapper);
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            Field field = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
            field.setAccessible(true);
            field.set(wrapper, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                java.util.function.Consumer<Entity> body = call.getArgument(0);
                actions.add(() -> {
                    Entity previous = owner;
                    owner = entity;
                    try {
                        body.accept(entity);
                    } finally {
                        owner = previous;
                    }
                });
                return true;
            });
        }

        void drain() {
            Runnable action;
            while ((action = actions.poll()) != null) action.run();
        }

        @Override
        public void close() {
            ticks.close();
        }
    }

    @Test
    void changedWorldTargetBodyAndItsChildrenFinishBeforeActualAttackerTail() throws Exception {
        try (Fixture f = new Fixture()) {
            var hurt = new CompletableFuture<Boolean>();
            var child = new CompletableFuture<Void>();
            var order = new ArrayList<String>();
            var result = ScarpetAttackContinuations.afterDamageAsync(f.attacker, hurt, accepted -> {
                assertSame(f.attacker, f.owner);
                assertTrue(accepted);
                order.add("attacker prefix");
                return ScarpetNativeDeathActors.entity(f.target, () -> {
                            assertSame(f.target, f.owner);
                            assertSame(f.to, f.target.level());
                            order.add("actual target");
                            ScarpetNativeWork.record(child);
                            return 19;
                        })
                        .thenCompose(ScarpetRuntime.captureNativeFunction(value -> ScarpetNativeDeathActors.entity(f.attacker, () -> {
                            assertSame(f.attacker, f.owner);
                            order.add("attacker tail");
                            return value;
                        })));
            });
            hurt.complete(true);
            assertEquals(List.of("attacker prefix"), order);
            f.drain();
            assertEquals(List.of("attacker prefix", "actual target"), order);
            assertFalse(result.isDone());
            child.complete(null);
            f.drain();
            assertEquals(19, result.get(3, TimeUnit.SECONDS));
            assertEquals(List.of("attacker prefix", "actual target", "attacker tail"), order);
        }
    }

    @Test
    void callerCancellationLeavesPrivateOwnerQueueAndGlobalDrainAlive() throws Exception {
        try (Fixture f = new Fixture()) {
            var hurt = new CompletableFuture<Boolean>();
            var child = new CompletableFuture<Integer>();
            var caller = ScarpetAttackContinuations.afterDamageAsync(f.attacker, hurt, accepted -> child);
            caller.cancel(false);
            var drain = ScarpetNativeWork.whenIdle(f.server);
            assertFalse(drain.isDone());
            f.owner = f.target;
            hurt.complete(true);
            assertFalse(drain.isDone());
            f.drain();
            assertFalse(drain.isDone());
            child.complete(23);
            f.drain();
            drain.get(3, TimeUnit.SECONDS);
            assertTrue(caller.isCancelled());
        }
    }

    @Test
    void actualTypedCallbackFailureWaitsItsAcceptedNativeChildAndRetainsTheGenuineCause() throws Exception {
        try (Fixture f = new Fixture()) {
            var child = new CompletableFuture<Void>();
            var problem = new IllegalStateException("actual native attack tail");
            var actual = ScarpetAttackContinuations.afterDamageNativeAsync(f.attacker, CompletableFuture.completedFuture(true), accepted -> {
                ScarpetNativeWork.record(child);
                throw problem;
            });
            assertFalse(actual.cancel(false));
            assertFalse(actual.isDone());
            child.complete(null);
            assertSame(problem, assertThrows(ExecutionException.class, () -> actual.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(ScarpetNativeWork.onlyGuestFailure(problem));
        }
    }
}
