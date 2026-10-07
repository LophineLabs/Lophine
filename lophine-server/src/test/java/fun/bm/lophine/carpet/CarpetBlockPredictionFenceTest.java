package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetInteractionContinuations;
import carpet.script.external.ScarpetNativeWork;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class CarpetBlockPredictionFenceTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test void actualNativeAckCannotPassAnUnfinishedLowerSequenceAndRetriesAfterFailureSettlement() throws Exception {
        var connection = mock(ServerGamePacketListenerImpl.class, CALLS_REAL_METHODS);
        var pending = new CompletableFuture<Void>();
        var sent = new ArrayList<Integer>();
        doAnswer(call -> { sent.add(call.<ClientboundBlockChangedAckPacket>getArgument(0).sequence()); return null; })
                .when(connection).send(any(ClientboundBlockChangedAckPacket.class));
        var flush = ServerGamePacketListenerImpl.class.getDeclaredMethod("carpetFlushBlockAcks"); flush.setAccessible(true);
        var last = ServerGamePacketListenerImpl.class.getDeclaredField("carpetLastBlockAck"); last.setAccessible(true); last.setInt(connection, -1);
        CarpetBlockPredictionFence.retain(connection, 7, pending);
        connection.ackBlockChangesUpTo(10);
        flush.invoke(connection); flush.invoke(connection);
        assertEquals(java.util.List.of(6), sent, "Do not acknowledge prediction 7 or duplicate partial acknowledgements");
        pending.completeExceptionally(new IllegalStateException("Placement failed after client prediction"));
        flush.invoke(connection);
        assertEquals(java.util.List.of(6, 10), sent);
    }

    @Test void reorderedCompletionAndRepeatedSequenceStillWaitEveryActualBody() {
        var connection = mock(ServerGamePacketListenerImpl.class);
        var older = new CompletableFuture<Void>(); var later = new CompletableFuture<Void>(); var replay = new CompletableFuture<Void>();
        CarpetBlockPredictionFence.retain(connection, 0, older);
        CarpetBlockPredictionFence.retain(connection, 2, later);
        CarpetBlockPredictionFence.retain(connection, 0, replay);
        later.complete(null); older.complete(null);
        assertEquals(-1, CarpetBlockPredictionFence.readyThrough(connection, 2));
        replay.complete(null);
        assertEquals(2, CarpetBlockPredictionFence.readyThrough(connection, 2));
    }

    @Test void aReadyOwnerInteractionFinishesInTheSameCallAndExceptionalResyncCannotStrandItsGate() {
        var player = mock(ServerPlayer.class); var world = mock(ServerLevel.class); var server = mock(MinecraftServer.class);
        when(player.level()).thenReturn(world); when(world.getServer()).thenReturn(server);
        player.containerMenu = mock(AbstractContainerMenu.class);
        var failure = new IllegalStateException("Actual placement failed"); var repairFailure = new IllegalStateException("Disconnected client resync");
        doThrow(repairFailure).when(player.containerMenu).sendAllDataToRemote();
        var order = new ArrayList<String>();
        InteractionResult.Deferred pending;
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(player)).thenReturn(true);
            try (var scope = ScarpetInteractionContinuations.open()) {
                pending = assertInstanceOf(InteractionResult.Deferred.class, ScarpetInteractionContinuations.after(player,
                        CompletableFuture.completedFuture(null), () -> { order.add("body"); throw failure; }));
                pending.plan().onFailure(problem -> { assertSame(failure, problem); order.add("block repair"); });
                assertFalse(pending.plan().future().isDone());
            }
            assertEquals(java.util.List.of("body", "block repair"), order);
            assertTrue(pending.plan().future().isCompletedExceptionally());
            assertSame(failure, assertThrows(CompletionException.class, () -> pending.plan().future().join()).getCause());
            assertArrayEquals(new Throwable[]{repairFailure}, failure.getSuppressed());
            assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
        }
    }
    @Test void blockRepairWaitsForTheBlockOwnerAndSameLivePlayerOwnerDelivery() {
        var connection = mock(ServerGamePacketListenerImpl.class);
        var player = mock(ServerPlayer.class); var world = mock(ServerLevel.class); var server = mock(MinecraftServer.class);
        connection.player = player; player.connection = connection;
        when(player.level()).thenReturn(world); when(world.getServer()).thenReturn(server);
        var block = new net.minecraft.core.BlockPos(16, 90, 4);
        var packet = new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(block,
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        var blockRead = new CompletableFuture<java.util.List<net.minecraft.network.protocol.Packet<?>>>();
        var playerDelivery = new CompletableFuture<Void>();
        var body = new java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<Void>>();
        try (var blocks = mockStatic(carpet.script.external.ScarpetExplosionActors.class);
             var actors = mockStatic(carpet.script.external.ScarpetNativeDeathActors.class)) {
            blocks.when(() -> carpet.script.external.ScarpetExplosionActors.world(eq(world), eq(block), any())).thenReturn(blockRead);
            actors.when(() -> carpet.script.external.ScarpetNativeDeathActors.entity(eq(player), any())).thenAnswer(call -> {
                body.set(call.getArgument(1)); return playerDelivery;
            });
            var actual = ScarpetNativeWork.observeNative(player, () -> {
                CarpetBlockPredictionFence.resyncBlocks(connection, world, block); return null;
            });
            assertFalse(actual.isDone()); verify(connection, never()).send(any(net.minecraft.network.protocol.Packet.class));
            blockRead.complete(java.util.List.of(packet)); assertFalse(actual.isDone());
            body.get().get(); verify(connection).send(packet); assertFalse(actual.isDone());
            playerDelivery.complete(null); actual.join(); assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
        }
    }

    @Test void originalWorldRepairCannotSendToAReplacementPlayerSession() {
        var connection = mock(ServerGamePacketListenerImpl.class);
        var original = mock(ServerPlayer.class); var replacement = mock(ServerPlayer.class);
        var world = mock(ServerLevel.class); var server = mock(MinecraftServer.class);
        connection.player = original; original.connection = connection;
        when(original.level()).thenReturn(world); when(world.getServer()).thenReturn(server);
        var block = net.minecraft.core.BlockPos.ZERO;
        var blockRead = new CompletableFuture<java.util.List<net.minecraft.network.protocol.Packet<?>>>();
        try (var blocks = mockStatic(carpet.script.external.ScarpetExplosionActors.class);
             var actors = mockStatic(carpet.script.external.ScarpetNativeDeathActors.class)) {
            blocks.when(() -> carpet.script.external.ScarpetExplosionActors.world(eq(world), eq(block), any())).thenReturn(blockRead);
            actors.when(() -> carpet.script.external.ScarpetNativeDeathActors.entity(eq(original), any())).thenAnswer(call -> {
                return CompletableFuture.completedFuture(call.<java.util.function.Supplier<Void>>getArgument(1).get());
            });
            var actual = ScarpetNativeWork.observeNative(original, () -> {
                CarpetBlockPredictionFence.resyncBlocks(connection, world, block); return null;
            });
            connection.player = replacement;
            blockRead.complete(java.util.List.of(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(block,
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())));
            actual.join(); verify(connection, never()).send(any(net.minecraft.network.protocol.Packet.class));
        }
    }

    @Test void aDeliveredInteractionCannotResumeAnEntityThatWasPhysicallyRemoved() throws Exception {
        var player = mock(ServerPlayer.class); var world = mock(ServerLevel.class); var server = mock(MinecraftServer.class);
        when(player.level()).thenReturn(world); when(world.getServer()).thenReturn(server);
        var craft = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
        var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
        when(player.getBukkitEntity()).thenReturn(craft);
        var field = org.bukkit.craftbukkit.entity.CraftEntity.class.getDeclaredField("taskScheduler");
        field.setAccessible(true); field.set(craft, scheduler);
        var callback = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<net.minecraft.world.entity.Entity>>();
        when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> { callback.set(call.getArgument(0)); return true; });
        var predecessor = new CompletableFuture<Void>(); var called = new java.util.concurrent.atomic.AtomicBoolean();
        InteractionResult.Deferred pending;
        try (var ticks = mockStatic(TickThread.class)) {
            try (var scope = ScarpetInteractionContinuations.open()) {
                pending = assertInstanceOf(InteractionResult.Deferred.class,
                        ScarpetInteractionContinuations.after(player, predecessor, () -> { called.set(true); return InteractionResult.SUCCESS; }));
            }
            predecessor.complete(null);
            when(player.isRemoved()).thenReturn(true);
            callback.get().accept(player);
            assertFalse(called.get()); assertTrue(pending.plan().future().isCompletedExceptionally());
            assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
        }
    }

}
