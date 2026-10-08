package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.Mannequin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgMannequinSourceWorldTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    @Test
    void actualCommandConstructsInCommandWorldAndSpawnsOnPlayerDestinationAfterAllChildren() throws Exception {
        check(true, false);
    }

    @Test
    void cancelledNativeSpawnReportsFalseAfterActualDestinationChildren() throws Exception {
        check(false, false);
    }

    @Test
    void failedConstructionNativeChildPreventsDestinationPublication() throws Exception {
        check(true, true);
    }

    void check(boolean added, boolean fail) throws Exception {
        String prior = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.playerCommandSummonMannequin;
        fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.playerCommandSummonMannequin = "true";
        try (var actors = new OrgInventoryPersistenceTest.Fixture(directory); var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
            var player = actors.viewer.player();
            var commandWorld = mock(ServerLevel.class);
            var destination = player.level();
            var source = mock(CommandSourceStack.class);
            when(source.getServer()).thenReturn(actors.server);
            when(source.getLevel()).thenReturn(commandWorld);
            when(source.getEntity()).thenReturn(player);
            when(source.getPlayerOrException()).thenReturn(player);
            when(source.permissions()).thenReturn(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
            when(source.callback()).thenReturn(CommandResultCallback.EMPTY);
            when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
            var point = new net.minecraft.world.phys.Vec3(33, 64, -49);
            when(player.position()).thenReturn(point);
            when(player.getYRot()).thenReturn(15F);
            when(player.getXRot()).thenReturn(30F);
            var calls = new ArrayList<ServerLevel>();
            var prepared = new CompletableFuture<Void>();
            var physical = new CompletableFuture<Void>();
            var spawned = new AtomicInteger();
            var callback = new AtomicReference<String>();
            when(source.callback()).thenReturn((success, value) -> callback.set(success + ":" + value));
            leases.when(() -> CarpetRegionLease.runValue(any(ServerLevel.class), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class))).thenAnswer(call -> {
                ServerLevel world = call.getArgument(0);
                calls.add(world);
                assertEquals(2, call.<Integer>getArgument(1));
                assertEquals(-4, call.<Integer>getArgument(2));
                Function work = call.getArgument(5);
                return CompletableFuture.completedFuture(work.apply(mock(CarpetRegionLease.Lease.class)));
            });
            try (var constructed = mockConstruction(Mannequin.class, (entity, context) -> {
                assertSame(commandWorld, context.arguments().get(1));
                doAnswer(call -> {
                    ScarpetNativeWork.record(prepared);
                    return null;
                }).when(entity).setProfile(any());
            })) {
                when(destination.addFreshEntity(any(Mannequin.class))).thenAnswer(call -> {
                    spawned.incrementAndGet();
                    ScarpetNativeWork.record(physical);
                    return added;
                });
                var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
                OrgPlayerExtraCommands.register(dispatcher);
                CompletableFuture<Integer> result;
                try (var scope = CarpetAsyncCommandResults.open()) {
                    dispatcher.execute("player test mannequin", source);
                    result = scope.resultFuture(source);
                }
                actors.drain(actors.viewer);
                assertEquals(List.of(commandWorld), calls);
                assertEquals(0, spawned.get());
                assertFalse(result.isDone());
                assertNull(callback.get());
                if (fail) prepared.completeExceptionally(new IllegalStateException("source construction child"));
                else prepared.complete(null);
                actors.drain(actors.viewer);
                if (fail) {
                    assertEquals(0, spawned.get());
                    assertEquals(0, result.get(3, TimeUnit.SECONDS));
                    assertEquals("false:0", callback.get());
                } else {
                    assertEquals(List.of(commandWorld, destination), calls);
                    assertEquals(1, spawned.get());
                    assertFalse(result.isDone());
                    assertNull(callback.get());
                    physical.complete(null);
                    actors.drain(actors.viewer);
                    assertEquals(added ? 1 : 0, result.get(3, TimeUnit.SECONDS));
                    assertEquals(added ? "true:1" : "false:0", callback.get());
                    var entity = constructed.constructed().getFirst();
                    verify(entity).setLevel(destination);
                    verify(entity).snapTo(point, 15F, 30F);
                    verify(commandWorld, never()).addFreshEntity(any());
                    verify(entity, never()).teleportTo(any(ServerLevel.class), anyDouble(), anyDouble(), anyDouble(), anySet(), anyFloat(), anyFloat(), anyBoolean());
                }
            }
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.playerCommandSummonMannequin = prior;
        }
    }
}
