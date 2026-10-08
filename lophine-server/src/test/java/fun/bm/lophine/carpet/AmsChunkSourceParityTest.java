package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AmsChunkSourceParityTest {
    @BeforeAll
    static void boot() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var config = new io.papermc.paper.configuration.GlobalConfiguration();
        config.misc = config.new Misc();
        try (var configs = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)) {
            configs.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);
            Class.forName("net.minecraft.network.Connection");
        }
    }

    @TempDir
    Path directory;
    String oldPiston, oldNote, oldCommand;
    int oldTime, oldRange;
    boolean oldKeep, oldBell;
    Map<String, Boolean> prior;

    @SuppressWarnings("unchecked")
    static Map<String, Boolean> state() throws Exception {
        var field = AmsPlayerChunkLoading.class.getDeclaredField("PLAYER_LOADING");
        field.setAccessible(true);
        return (Map<String, Boolean>) field.get(null);
    }

    @BeforeEach
    void preserve() throws Exception {
        oldPiston = GeneralCompatConfig.pistonBlockChunkLoader;
        oldNote = GeneralCompatConfig.noteBlockChunkLoader;
        oldCommand = GeneralCompatConfig.commandPlayerChunkLoadController;
        oldTime = GeneralCompatConfig.blockChunkLoaderTimeController;
        oldRange = GeneralCompatConfig.blockChunkLoaderRangeController;
        oldKeep = GeneralCompatConfig.blockChunkLoaderKeepWorldTickUpdate;
        oldBell = GeneralCompatConfig.bellBlockChunkLoader;
        prior = Map.copyOf(state());
        state().clear();
        GeneralCompatConfig.pistonBlockChunkLoader = "false";
        GeneralCompatConfig.noteBlockChunkLoader = "false";
        GeneralCompatConfig.commandPlayerChunkLoadController = "true";
        GeneralCompatConfig.blockChunkLoaderKeepWorldTickUpdate = false;
        GeneralCompatConfig.blockChunkLoaderRangeController = 3;
        GeneralCompatConfig.bellBlockChunkLoader = false;
    }

    @AfterEach
    void restore() throws Exception {
        GeneralCompatConfig.pistonBlockChunkLoader = oldPiston;
        GeneralCompatConfig.noteBlockChunkLoader = oldNote;
        GeneralCompatConfig.commandPlayerChunkLoadController = oldCommand;
        GeneralCompatConfig.blockChunkLoaderTimeController = oldTime;
        GeneralCompatConfig.blockChunkLoaderRangeController = oldRange;
        GeneralCompatConfig.blockChunkLoaderKeepWorldTickUpdate = oldKeep;
        GeneralCompatConfig.bellBlockChunkLoader = oldBell;
        state().clear();
        state().putAll(prior);
    }

    @Test
    void originalAmsTicketTimeoutsStayFixedAtRegistrationAfterTheSettingChanges() {
        long note = TicketType.AMS_NOTE_BLOCK_LOADER.timeout(), piston = TicketType.AMS_PISTON_BLOCK_LOADER.timeout(), bell = TicketType.AMS_BELL_BLOCK_LOADER.timeout();
        GeneralCompatConfig.blockChunkLoaderTimeController = 1;
        assertEquals(note, TicketType.AMS_NOTE_BLOCK_LOADER.timeout());
        assertEquals(piston, TicketType.AMS_PISTON_BLOCK_LOADER.timeout());
        assertEquals(bell, TicketType.AMS_BELL_BLOCK_LOADER.timeout());
        GeneralCompatConfig.blockChunkLoaderTimeController = 17;
        assertEquals(note, TicketType.AMS_NOTE_BLOCK_LOADER.timeout());
        int previous = TicketType.PLUGIN_TYPE_TIMEOUT;
        try {
            TicketType.PLUGIN_TYPE_TIMEOUT = 37;
            assertEquals(37, TicketType.PLUGIN.timeout());
        } finally {
            TicketType.PLUGIN_TYPE_TIMEOUT = previous;
        }
    }

    @Test
    void originalPistonAlwaysReadsBothAboveAndBelowBeforeItsEnabledModeCondition() {
        GeneralCompatConfig.pistonBlockChunkLoader = "bone_block";
        var world = mock(ServerLevel.class);
        var cache = mock(ServerChunkCache.class);
        when(world.getChunkSource()).thenReturn(cache);
        when(world.getBlockState(BlockPos.ZERO.above())).thenReturn(Blocks.BONE_BLOCK.defaultBlockState());
        when(world.getBlockState(BlockPos.ZERO.below())).thenReturn(Blocks.BEDROCK.defaultBlockState());
        AmsBlockChunkLoaders.piston(world, BlockPos.ZERO, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        var order = inOrder(world, cache);
        order.verify(world).getBlockState(BlockPos.ZERO.above());
        order.verify(world).getBlockState(BlockPos.ZERO.below());
        order.verify(world).getChunkSource();
        order.verify(cache).addTicketWithRadius(TicketType.AMS_PISTON_BLOCK_LOADER, ChunkPos.containing(BlockPos.ZERO.east()), 3);
    }

    @Test
    void actualNativeAllPistonWaitsFirstTicketThenResetBeforeSecondTicket() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            GeneralCompatConfig.pistonBlockChunkLoader = "all";
            GeneralCompatConfig.blockChunkLoaderKeepWorldTickUpdate = true;
            var cache = mock(ServerChunkCache.class);
            when(f.world.getChunkSource()).thenReturn(cache);
            when(f.world.getBlockState(BlockPos.ZERO.above())).thenAnswer(call -> {
                f.order.add("above");
                return Blocks.BONE_BLOCK.defaultBlockState();
            });
            when(f.world.getBlockState(BlockPos.ZERO.below())).thenAnswer(call -> {
                f.order.add("below");
                return Blocks.BEDROCK.defaultBlockState();
            });
            var first = new CompletableFuture<Void>();
            var reset = new CompletableFuture<Void>();
            var second = new CompletableFuture<Void>();
            var tickets = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                int i = tickets.incrementAndGet();
                f.order.add("ticket" + i);
                ScarpetNativeWork.record(i == 1 ? first : second);
                return null;
            }).when(cache).addTicketWithRadius(eq(TicketType.AMS_PISTON_BLOCK_LOADER), any(ChunkPos.class), eq(3));
            var resets = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                int i = resets.incrementAndGet();
                f.order.add("reset" + i);
                if (i == 1) ScarpetNativeWork.record(reset);
                return null;
            }).when(f.world).resetEmptyTime();
            var actual = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> {
                AmsBlockChunkLoaders.piston(f.world, BlockPos.ZERO, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
                return 42;
            });
            f.drain();
            assertEquals(List.of("above", "below", "ticket1"), f.order);
            first.complete(null);
            f.drain();
            assertEquals(List.of("above", "below", "ticket1", "reset1"), f.order);
            assertFalse(actual.isDone());
            reset.complete(null);
            f.drain();
            assertEquals("ticket2", f.order.getLast());
            assertFalse(actual.isDone());
            second.complete(null);
            f.drain();
            assertEquals(42, actual.get(3, TimeUnit.SECONDS));
            assertEquals("reset2", f.order.getLast());
        }
    }

    @Test
    void actualNativeFirstTicketFailurePreventsResetAndSecondTicket() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            GeneralCompatConfig.pistonBlockChunkLoader = "all";
            GeneralCompatConfig.blockChunkLoaderKeepWorldTickUpdate = true;
            var cache = mock(ServerChunkCache.class);
            when(f.world.getChunkSource()).thenReturn(cache);
            when(f.world.getBlockState(BlockPos.ZERO.above())).thenReturn(Blocks.BONE_BLOCK.defaultBlockState());
            when(f.world.getBlockState(BlockPos.ZERO.below())).thenReturn(Blocks.BEDROCK.defaultBlockState());
            var first = new CompletableFuture<Void>();
            var failure = new IllegalStateException("actual first ticket");
            doAnswer(call -> {
                ScarpetNativeWork.record(first);
                return null;
            }).when(cache).addTicketWithRadius(eq(TicketType.AMS_PISTON_BLOCK_LOADER), any(ChunkPos.class), eq(3));
            var actual = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> {
                AmsBlockChunkLoaders.piston(f.world, BlockPos.ZERO, Blocks.PISTON.defaultBlockState());
                return 42;
            });
            f.drain();
            first.completeExceptionally(failure);
            f.drain();
            assertSame(failure, assertThrows(ExecutionException.class, () -> actual.get(3, TimeUnit.SECONDS)).getCause());
            verify(cache).addTicketWithRadius(eq(TicketType.AMS_PISTON_BLOCK_LOADER), any(ChunkPos.class), eq(3));
            verify(f.world, never()).resetEmptyTime();
            ScarpetNativeWork.whenIdle(f.server).handle((value, error) -> null).join();
        }
    }

    @Test
    void originalNoteBlockModeStillReadsAboveBeforeTicket() {
        GeneralCompatConfig.noteBlockChunkLoader = "note_block";
        var world = mock(ServerLevel.class);
        var cache = mock(ServerChunkCache.class);
        when(world.getChunkSource()).thenReturn(cache);
        when(world.getBlockState(BlockPos.ZERO.above())).thenReturn(Blocks.AIR.defaultBlockState());
        AmsBlockChunkLoaders.note(world, BlockPos.ZERO);
        var order = inOrder(world, cache);
        order.verify(world).getBlockState(BlockPos.ZERO.above());
        order.verify(world).getChunkSource();
        order.verify(cache).addTicketWithRadius(TicketType.AMS_NOTE_BLOCK_LOADER, ChunkPos.containing(BlockPos.ZERO), 3);
    }

    static void guest(CompletableFuture<?> actual) {
        try {
            var method = ScarpetNativeWork.class.getDeclaredMethod("recordGuest", CompletableFuture.class);
            method.setAccessible(true);
            method.invoke(null, actual);
        } catch (ReflectiveOperationException failed) {
            throw new RuntimeException(failed);
        }
    }

    @Test
    void guestFirstTicketFailurePreservesRawFailureAndContinuesResetAndSecondTicket() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory)) {
            GeneralCompatConfig.pistonBlockChunkLoader = "all";
            GeneralCompatConfig.blockChunkLoaderKeepWorldTickUpdate = true;
            var cache = mock(ServerChunkCache.class);
            when(f.world.getChunkSource()).thenReturn(cache);
            when(f.world.getBlockState(BlockPos.ZERO.above())).thenReturn(Blocks.BONE_BLOCK.defaultBlockState());
            when(f.world.getBlockState(BlockPos.ZERO.below())).thenReturn(Blocks.BEDROCK.defaultBlockState());
            var guest = new CompletableFuture<Void>();
            var tickets = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                if (tickets.incrementAndGet() == 1) guest(guest);
                return null;
            }).when(cache).addTicketWithRadius(eq(TicketType.AMS_PISTON_BLOCK_LOADER), any(ChunkPos.class), eq(3));
            var actual = ScarpetNativeWork.observeNative(f.sourcePlayer, () -> {
                AmsBlockChunkLoaders.piston(f.world, BlockPos.ZERO, Blocks.PISTON.defaultBlockState());
                return 42;
            });
            f.drain();
            assertEquals(1, tickets.get());
            guest.completeExceptionally(new IllegalArgumentException("guest first ticket"));
            f.drain();
            var failure = assertThrows(ExecutionException.class, () -> actual.get(3, TimeUnit.SECONDS)).getCause();
            assertTrue(ScarpetNativeWork.onlyGuestFailure(failure));
            assertEquals(2, tickets.get());
            verify(f.world, times(2)).resetEmptyTime();
            ScarpetNativeWork.whenIdle(f.server).handle((value, error) -> null).join();
        }
    }

    static com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack> root() {
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        AmsPlayerChunkLoading.register(dispatcher);
        return dispatcher.getRoot().getChild("playerChunkLoading");
    }

    static void player(AmsNativeManagementTest.Fixture f) {
        when(f.source.getTextName()).thenReturn("source");
        when(f.sourcePlayer.getName()).thenReturn(Component.literal("source"));
        var players = mock(net.minecraft.server.players.PlayerList.class);
        when(f.server.getPlayerList()).thenReturn(players);
        when(players.getPlayerByName("source")).thenReturn(f.sourcePlayer);
    }

    @Test
    void actualRegisteredPlayerChunkSetStoresOriginalNameAndWaitsTranslatedReplyBeforeInt() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            player(f);
            var ctx = f.context();
            when(ctx.getArgument("boolean", Boolean.class)).thenReturn(false);
            var reply = new CompletableFuture<Void>();
            var messages = new ArrayList<Component>();
            f.messenger.when(() -> CarpetMessenger.send(eq(f.source), anyList())).thenAnswer(call -> {
                messages.add(((List<Component>) call.getArgument(1)).getFirst());
                ScarpetNativeWork.record(reply);
                return null;
            });
            assertEquals(1, root().getChild("boolean").getCommand().run(ctx));
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(false, state().get("source"));
            assertEquals("command.playerChunkLoading.set", messages.getFirst().getString());
            assertTrue(messages.getFirst().getStyle().isBold());
            assertFalse(actual.isDone());
            reply.complete(null);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            verify(f.callback).onResult(true, 1);
            verify(f.source, never()).getPlayerOrException();
            assertFalse(AmsPlayerChunkLoading.loadsChunks(f.sourcePlayer));
        }
    }

    @Test
    void actualRegisteredPlayerChunkConsoleReturnsOriginalNoPlayerZeroAfterItsReply() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            player(f);
            when(f.source.getTextName()).thenReturn("console");
            var reply = new CompletableFuture<Void>();
            f.messenger.when(() -> CarpetMessenger.send(eq(f.source), anyList())).thenAnswer(call -> {
                assertEquals("command.playerChunkLoading.no_player_specified", ((List<Component>) call.getArgument(1)).getFirst().getString());
                ScarpetNativeWork.record(reply);
                return null;
            });
            root().getCommand().run(f.context());
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertTrue(state().isEmpty());
            assertFalse(actual.isDone());
            reply.complete(null);
            f.drain();
            assertEquals(0, actual.get(3, TimeUnit.SECONDS));
            verify(f.callback).onResult(true, 0);
            verify(f.source, never()).getPlayerOrException();
        }
    }

    @Test
    void actualRegisteredPlayerChunkQueryUsesOriginalDefaultTrue() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            player(f);
            var messages = new ArrayList<String>();
            f.messenger.when(() -> CarpetMessenger.send(eq(f.source), anyList())).thenAnswer(call -> {
                messages.add(((List<Component>) call.getArgument(1)).getFirst().getString());
                return null;
            });
            root().getCommand().run(f.context());
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            assertEquals(List.of("command.playerChunkLoading.chunk_loading_true"), messages);
            assertTrue(state().isEmpty());
        }
    }

    @Test
    void actualRegisteredPlayerChunkHelpKeepsOriginalTranslatedGrayReply() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            var messages = new ArrayList<Component>();
            f.messenger.when(() -> CarpetMessenger.send(eq(f.source), anyList())).thenAnswer(call -> {
                messages.add(((List<Component>) call.getArgument(1)).getFirst());
                return null;
            });
            root().getChild("help").getCommand().run(f.context());
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            assertEquals("command.playerChunkLoading.help", messages.getFirst().getString());
            assertFalse(messages.getFirst().getStyle().isBold());
            verify(f.server, never()).getPlayerList();
        }
    }

    @Test
    void actualRegisteredPlayerChunkReplyFailureKeepsEarlierMapChangeAndFailsNativeSourceInt() throws Exception {
        try (var f = new AmsNativeManagementTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            player(f);
            var ctx = f.context();
            when(ctx.getArgument("boolean", Boolean.class)).thenReturn(false);
            var reply = new CompletableFuture<Void>();
            f.messenger.when(() -> CarpetMessenger.send(eq(f.source), anyList())).thenAnswer(call -> {
                ScarpetNativeWork.record(reply);
                return null;
            });
            root().getChild("boolean").getCommand().run(ctx);
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(false, state().get("source"));
            assertFalse(actual.isDone());
            reply.completeExceptionally(new IllegalStateException("native source reply"));
            f.drain();
            assertEquals(0, actual.get(3, TimeUnit.SECONDS));
            assertEquals(false, state().get("source"));
            verify(f.callback).onResult(false, 0);
            ScarpetNativeWork.whenIdle(f.server).handle((value, error) -> null).join();
        }
    }
}
