package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.value.EntityValue;
import carpet.script.value.NBTSerializableValue;
import carpet.script.value.NumericValue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.storage.ValueOutput;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetRetiredActorsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final Entity retired = mock(Entity.class), target = mock(Entity.class);
        final AtomicBoolean regionOwner = new AtomicBoolean();
        final AtomicReference<Entity> vehicle = new AtomicReference<>();
        final MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final MockedStatic<MinecraftServer> servers = mockStatic(MinecraftServer.class);
        final MockedStatic<org.bukkit.Bukkit> bukkitServer = mockStatic(org.bukkit.Bukkit.class);
        final MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        int regionDispatches, retiredAttempts;

        Fixture() throws Exception {
            servers.when(MinecraftServer::getServer).thenReturn(server);
            CraftServer craft = mock(CraftServer.class);
            when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkitServer.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            var field = MinecraftServer.class.getField("server");
            field.setAccessible(true);
            field.set(server, craft);
            var scheduler = mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);
            when(craft.getRegionScheduler()).thenReturn(scheduler);
            when(world.getServer()).thenReturn(server);
            when(world.getWorld()).thenReturn(mock(CraftWorld.class));
            var registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            when(world.registryAccess()).thenReturn(registry);
            entity(retired, true, registry);
            entity(target, false, registry);
            when(retired.getVehicle()).thenAnswer(call -> vehicle.get());
            doAnswer(call -> {
                assertTrue(regionOwner.get());
                vehicle.set(call.getArgument(0));
                return true;
            }).when(retired).startRiding(any(Entity.class), eq(true), eq(true));
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> regionOwner.get());
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenAnswer(call -> regionOwner.get());
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(call -> regionOwner.get());
            doAnswer(call -> {
                regionDispatches++;
                own(() -> call.<Runnable>getArgument(4).run());
                return null;
            }).when(scheduler).execute(any(), any(org.bukkit.World.class), anyInt(), anyInt(), any(Runnable.class));
            leases.when(() -> fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease, Object> body = call.getArgument(5);
                var result = new AtomicReference<Object>();
                own(() -> result.set(body.apply(null)));
                return CompletableFuture.completedFuture(result.get());
            });
            own(() -> ScarpetRetiredActors.capture(retired));
        }

        private void entity(Entity entity, boolean removed, RegistryAccess registry) throws Exception {
            when(entity.level()).thenReturn(world);
            when(entity.blockPosition()).thenReturn(BlockPos.ZERO);
            when(entity.getUUID()).thenReturn(UUID.randomUUID());
            when(entity.isRemoved()).thenReturn(removed);
            when(entity.getPassengers()).thenReturn(List.of());
            doReturn(EntityTypes.PIG).when(entity).getType();
            when(entity.registryAccess()).thenReturn(registry);
            when(entity.problemPath()).thenReturn(mock(ProblemReporter.PathElement.class));
            doAnswer(call -> {
                assertTrue(regionOwner.get());
                call.<ValueOutput>getArgument(0).putInt("Age", entity.tickCount);
                return null;
            }).when(entity).saveWithoutId(any(ValueOutput.class));
            doAnswer(call -> {
                assertTrue(regionOwner.get());
                call.<ValueOutput>getArgument(0).putInt("Age", entity.tickCount);
                return true;
            }).when(entity).save(any(ValueOutput.class));
            CraftEntity bukkit = mock(CraftEntity.class);
            when(entity.getBukkitEntity()).thenReturn(bukkit);
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var field = CraftEntity.class.getField("taskScheduler");
            field.setAccessible(true);
            field.set(bukkit, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                if (removed) {
                    retiredAttempts++;
                    return false;
                }
                own(() -> call.<java.util.function.Consumer<Entity>>getArgument(0).accept(entity));
                return true;
            });
        }

        void own(Runnable action) {
            boolean previous = regionOwner.getAndSet(true);
            try {
                action.run();
            } finally {
                regionOwner.set(previous);
            }
        }

        @Override
        public void close() {
            ScarpetRuntime.beginShutdown(server, () -> {
            });
            leases.close();
            bukkitServer.close();
            servers.close();
            ticks.close();
        }
    }

    @Test
    void aRetiredSchedulerFallsBackToTheWorldOwnerAndRunsActualMutatorAndNbt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<EntityValue> value = new AtomicReference<>();
            fixture.own(() -> value.set(EntityValue.snapshotForRetiredEvent(fixture.retired)));
            value.get().set("age", NumericValue.of(37));
            assertEquals(37, fixture.retired.tickCount);
            assertEquals(37L, value.get().get("age", null).readInteger());
            var nbt = (NBTSerializableValue) value.get().get("nbt", null);
            assertEquals(37, nbt.getCompoundTag().getIntOr("Age", -1));
            var saved = (CompoundTag) value.get().toTag(true, fixture.world.registryAccess());
            assertEquals(37, saved.getCompoundOrEmpty("Data").getIntOr("Age", -1));
            assertTrue(fixture.retiredAttempts >= 4);
            assertTrue(fixture.regionDispatches >= 4);
        }
    }

    @Test
    void retiredRelationshipUsesTheCommonWorldOwnerAndMutatesTheOriginalObject() throws Exception {
        try (Fixture fixture = new Fixture()) {
            EntityValue value = EntityValue.snapshotForRetiredEvent(fixture.retired);
            value.set("mount", new EntityValue(fixture.target));
            assertSame(fixture.target, fixture.vehicle.get());
            verify(fixture.retired).startRiding(fixture.target, true, true);
            assertTrue(fixture.retiredAttempts > 0);
            assertTrue(fixture.regionDispatches > 0);
        }
    }
}
