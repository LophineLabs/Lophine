package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AmsPitcherPacketParityTest {
    @BeforeAll
    static void boot() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var config = new io.papermc.paper.configuration.GlobalConfiguration();
        config.misc = config.new Misc();
        try (var configurations = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)) {
            configurations.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);
            Class.forName("net.minecraft.network.Connection");
        }
    }

    @TempDir
    Path directory;

    @AfterEach
    void reset() {
        GeneralCompatConfig.easyGetPitcherPod = 0;
    }

    @Test
    void actualPitcherBodyDrawsFromItsFreshRandomAndCreatesDistinctOriginalOneItemStacks() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var draws = mockConstruction(java.util.Random.class, (random, context) -> when(random.nextInt(4)).thenReturn(2)); var stacks = mockConstruction(ItemStack.class, (stack, context) -> assertEquals(List.of(Items.PITCHER_POD), context.arguments())); var blocks = mockStatic(Block.class, CALLS_REAL_METHODS)) {
            GeneralCompatConfig.easyGetPitcherPod = 5;
            var state = mock(BlockState.class);
            when(state.is(Blocks.PITCHER_CROP)).thenReturn(true);
            var body = mock(Block.class);
            doCallRealMethod().when(body).playerWillDestroy(f.world, BlockPos.ZERO, state, f.sourcePlayer);
            var dropped = new ArrayList<ItemStack>();
            blocks.when(() -> Block.popResource(eq(f.world), eq(BlockPos.ZERO), any(ItemStack.class))).thenAnswer(call -> {
                dropped.add(call.getArgument(2));
                return null;
            });
            assertSame(state, body.playerWillDestroy(f.world, BlockPos.ZERO, state, f.sourcePlayer));
            assertEquals(1, draws.constructed().size());
            verify(draws.constructed().getFirst()).nextInt(4);
            verify(f.world, never()).getRandom();
            assertEquals(4, dropped.size());
            assertEquals(stacks.constructed(), dropped);
            assertEquals(4, new HashSet<>(dropped).size());
            verify(body).spawnDestroyByEntityParticles(f.world, f.sourcePlayer, BlockPos.ZERO, state);
        }
    }

    @Test
    void actualDefaultAndCreativePitcherBranchesMakeNoNewDropRandom() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var draws = mockConstruction(java.util.Random.class)) {
            var state = mock(BlockState.class);
            when(state.is(Blocks.PITCHER_CROP)).thenReturn(true);
            var body = mock(Block.class);
            doCallRealMethod().when(body).playerWillDestroy(f.world, BlockPos.ZERO, state, f.sourcePlayer);
            assertSame(state, body.playerWillDestroy(f.world, BlockPos.ZERO, state, f.sourcePlayer));
            GeneralCompatConfig.easyGetPitcherPod = 5;
            when(f.sourcePlayer.isCreative()).thenReturn(true);
            assertSame(state, body.playerWillDestroy(f.world, BlockPos.ZERO, state, f.sourcePlayer));
            assertTrue(draws.constructed().isEmpty());
            verify(f.world, never()).getRandom();
        }
    }

    @Test
    void disconnectedPacketFailsTheActualSourceIntBeforeNextReplyAndCallback() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            when(f.sourcePlayer.connection.connection.isConnected()).thenReturn(false);
            var message = new ArrayList<String>();
            AmsNativeCommandEffects.command(f.context(), ctx -> {
                AmsNativeCommandEffects.receipt(AmsNativeCommandEffects.packet(f.sourcePlayer, mock(Packet.class)));
                AmsNativeCommandEffects.reply(f.source, () -> message.add("later"));
                return 17;
            });
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(0, actual.get(3, TimeUnit.SECONDS));
            assertTrue(message.isEmpty());
            verify(f.callback).onResult(false, 0);
            verify(f.sourcePlayer.connection, never()).send(any(Packet.class), any(io.netty.channel.ChannelFutureListener.class));
            ScarpetNativeWork.whenIdle(f.server).handle((value, failure) -> null).join();
        }
    }

    @Test
    void actualChannelCloseBeforeWriteReplyEndsNativePacketAndRemovesCloseListener() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            var closed = new AtomicReference<io.netty.channel.ChannelFutureListener>();
            var close = f.sourcePlayer.connection.connection.channel.closeFuture();
            when(close.addListener(any(io.netty.channel.ChannelFutureListener.class))).thenAnswer(call -> {
                closed.set(call.getArgument(0));
                return close;
            });
            var actual = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> {
                AmsNativeCommandEffects.packet(f.sourcePlayer, mock(Packet.class));
                return 7;
            });
            assertFalse(actual.isDone());
            closed.get().operationComplete(mock(io.netty.channel.ChannelFuture.class));
            Throwable failure = assertThrows(CompletionException.class, actual::join).getCause();
            assertInstanceOf(java.nio.channels.ClosedChannelException.class, failure);
            assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
            verify(close).removeListener(closed.get());
            ScarpetNativeWork.whenIdle(f.server).handle((value, error) -> null).join();
        }
    }

    @Test
    void successfulActualWriteRemovesCloseListenerAndLaterCloseCannotUndoSourceValue() throws Exception {
        write(false);
    }

    @Test
    void genuineActualWriteFailureRetainsOriginalCauseAndRemovesCloseListener() throws Exception {
        write(true);
    }

    private void write(boolean fail) throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            var sent = new AtomicReference<io.netty.channel.ChannelFutureListener>();
            var closed = new AtomicReference<io.netty.channel.ChannelFutureListener>();
            var close = f.sourcePlayer.connection.connection.channel.closeFuture();
            when(close.addListener(any(io.netty.channel.ChannelFutureListener.class))).thenAnswer(call -> {
                closed.set(call.getArgument(0));
                return close;
            });
            doAnswer(call -> {
                sent.set(call.getArgument(1));
                return null;
            }).when(f.sourcePlayer.connection).send(any(Packet.class), any(io.netty.channel.ChannelFutureListener.class));
            var actual = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> {
                var packet = AmsNativeCommandEffects.packet(f.sourcePlayer, mock(Packet.class));
                assertFalse(packet.cancel(false));
                return 17;
            });
            assertFalse(actual.isDone());
            var done = mock(io.netty.channel.ChannelFuture.class);
            var failure = new IllegalStateException("original write failure");
            when(done.isSuccess()).thenReturn(!fail);
            when(done.cause()).thenReturn(failure);
            sent.get().operationComplete(done);
            verify(close).removeListener(closed.get());
            closed.get().operationComplete(mock(io.netty.channel.ChannelFuture.class));
            if (fail) {
                assertSame(failure, assertThrows(CompletionException.class, actual::join).getCause());
                assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
            } else assertEquals(17, actual.join());
            ScarpetNativeWork.whenIdle(f.server).handle((value, error) -> null).join();
        }
    }
}
