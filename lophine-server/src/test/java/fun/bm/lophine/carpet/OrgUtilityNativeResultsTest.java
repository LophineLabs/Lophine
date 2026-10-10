package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgUtilityNativeResultsTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    private static CommandSourceStack source(OrgInventoryPersistenceTest.Fixture fixture, AtomicReference<String> result) throws Exception {
        var player = fixture.viewer.player();
        var level = player.level();
        var source = mock(CommandSourceStack.class);
        when(source.getEntity()).thenReturn(player);
        when(source.getPlayer()).thenReturn(player);
        when(source.getPlayerOrException()).thenReturn(player);
        when(source.getServer()).thenReturn(fixture.server);
        when(source.getLevel()).thenReturn(level);
        when(source.getPosition()).thenReturn(Vec3.ZERO);
        when(source.callback()).thenReturn((success, value) -> {
            assertSame(player, fixture.owner.get());
            result.set(success + ":" + value);
        });
        for (var actor : fixture.actors.values()) when(actor.player().blockPosition()).thenReturn(BlockPos.ZERO);
        return source;
    }

    private static int call(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = OrgUtilityCommands.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return (Integer) method.invoke(null, values);
    }

    @Test
    void enteringSpectatorReturnsActualSourceZeroAndWaitsModeAndHudNativeChildren() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var result = new AtomicReference<String>();
            var source = source(fixture, result);
            var bot = fixture.target.player();
            var mode = new AtomicReference<>(GameType.SURVIVAL);
            bot.gameMode = mock(ServerPlayerGameMode.class);
            when(bot.gameMode.getGameModeForPlayer()).thenAnswer(call -> mode.get());
            when(bot.isSpectator()).thenAnswer(call -> mode.get() == GameType.SPECTATOR);
            when(bot.getBukkitEntity().getLocation()).thenReturn(new Location(null, 4, 64, 9));
            var trip = new CompletableFuture<Boolean>();
            when(bot.getBukkitEntity().teleportAsync(any(Location.class), any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class))).thenReturn(trip);
            var modeChild = new CompletableFuture<Void>();
            var hudChild = new CompletableFuture<Void>();
            var hud = new AtomicReference<Component>();
            doAnswer(call -> {
                assertSame(bot, fixture.owner.get());
                mode.set(call.getArgument(0));
                ScarpetNativeWork.record(modeChild);
                return true;
            }).when(bot).setGameMode(any());
            doAnswer(call -> {
                assertSame(bot, fixture.owner.get());
                hud.set(call.getArgument(0));
                ScarpetNativeWork.record(hudChild);
                return null;
            }).when(bot).sendOverlayMessage(any());
            CompletableFuture<Integer> actual;
            try (var scope = CarpetAsyncCommandResults.open()) {
                assertEquals(1, call("toggleSpectator", new Class<?>[]{CommandSourceStack.class, ServerPlayer.class, boolean.class}, source, bot, true));
                actual = scope.resultFuture(source);
            }
            fixture.drain(fixture.target);
            assertNull(result.get());
            trip.complete(true);
            fixture.drain(fixture.target);
            assertEquals(GameType.SPECTATOR, mode.get());
            assertNull(hud.get());
            assertFalse(actual.isDone());
            modeChild.complete(null);
            fixture.drain(fixture.target);
            assertNotNull(hud.get());
            assertTrue(hud.get().getString().contains("Spectator"));
            assertFalse(actual.isDone());
            hudChild.complete(null);
            fixture.drain(fixture.viewer);
            assertEquals(0, actual.join());
            assertEquals("true:0", result.get());
        }
    }

    @Test
    void actualDeniedFakeTeleportKeepsModeAndHudAndReturnsFailedZero() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var result = new AtomicReference<String>();
            var source = source(fixture, result);
            var bot = fixture.target.player();
            when(bot.getBukkitEntity().getLocation()).thenReturn(new Location(null, 4, 64, 9));
            var trip = new CompletableFuture<Boolean>();
            when(bot.getBukkitEntity().teleportAsync(any(Location.class), any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class))).thenReturn(trip);
            CompletableFuture<Integer> actual;
            try (var scope = CarpetAsyncCommandResults.open()) {
                call("toggleSpectator", new Class<?>[]{CommandSourceStack.class, ServerPlayer.class, boolean.class}, source, bot, true);
                actual = scope.resultFuture(source);
            }
            fixture.drain(fixture.target);
            assertFalse(actual.isDone());
            trip.complete(false);
            fixture.drain(fixture.target);
            fixture.drain(fixture.viewer);
            assertEquals(0, actual.join());
            assertEquals("false:0", result.get());
            verify(bot, never()).setGameMode(any());
            verify(bot, never()).sendOverlayMessage(any());
        }
    }

    @Test
    void actualTeleportAndFeedbackChildrenPrecedeTheCommandInteger() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var result = new AtomicReference<String>();
            var source = source(fixture, result);
            var player = fixture.viewer.player();
            when(player.isCreative()).thenReturn(true);
            when(player.getDisplayName()).thenReturn(Component.literal("Alice"));
            when(player.level().dimension()).thenReturn(Level.OVERWORLD);
            var trip = new CompletableFuture<Boolean>();
            when(player.getBukkitEntity().teleportAsync(any(Location.class), any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class))).thenReturn(trip);
            var feedbackChild = new CompletableFuture<Void>();
            var sent = new AtomicInteger();
            doAnswer(call -> {
                assertSame(player, fixture.owner.get());
                sent.incrementAndGet();
                ScarpetNativeWork.record(feedbackChild);
                return null;
            }).when(source).sendSuccess(any(), eq(false));
            CompletableFuture<Integer> actual;
            try (var scope = CarpetAsyncCommandResults.open()) {
                call("teleportDimension", new Class<?>[]{CommandSourceStack.class, net.minecraft.server.level.ServerLevel.class, Vec3.class}, source, null, new Vec3(7.2, 63, -12.4));
                actual = scope.resultFuture(source);
            }
            fixture.drain(fixture.viewer);
            assertFalse(actual.isDone());
            assertEquals(0, sent.get());
            trip.complete(true);
            fixture.drain(fixture.viewer);
            assertEquals(1, sent.get());
            assertFalse(actual.isDone());
            feedbackChild.complete(null);
            fixture.drain(fixture.viewer);
            assertEquals(1, actual.join());
            assertEquals("true:1", result.get());
        }
    }

    @Test
    void ruleSearchPrintsOfficialBoldTitleEvenForAnEmptyFilter() throws Exception {
        String prior = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandRuleSearch;
        fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandRuleSearch = "true";
        try {
            var source = mock(CommandSourceStack.class);
            var sent = new AtomicReference<Component>();
            doAnswer(call -> {
                sent.set(call.<java.util.function.Supplier<Component>>getArgument(0).get());
                return null;
            }).when(source).sendSuccess(any(), eq(false));
            var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
            OrgUtilityCommands.register(dispatcher);
            assertEquals(0, dispatcher.execute("ruleSearch \"\"", source));
            assertTrue(sent.get().getStyle().isBold());
            assertTrue(sent.get().getString().contains("Carpet"));
        } finally {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandRuleSearch = prior;
        }
    }

    @Test
    void nativeModeFailureStopsTheHudAndKeepsTheRawParentFailure() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true)) {
            var result = new AtomicReference<String>();
            var source = source(fixture, result);
            var bot = fixture.target.player();
            when(bot.isSpectator()).thenReturn(true);
            var child = new CompletableFuture<Void>();
            doAnswer(call -> {
                ScarpetNativeWork.record(child);
                return true;
            }).when(bot).setGameMode(GameType.SURVIVAL);
            var actual = new AtomicReference<CompletableFuture<Integer>>();
            var parent = ScarpetNativeWork.observeNative(null, () -> {
                try (var scope = CarpetAsyncCommandResults.open()) {
                    try {
                        call("toggleSpectator", new Class<?>[]{CommandSourceStack.class, ServerPlayer.class, boolean.class}, source, bot, true);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    actual.set(scope.resultFuture(source));
                }
                return null;
            });
            fixture.drain(fixture.target);
            assertFalse(parent.isDone());
            child.completeExceptionally(new IllegalStateException("mode packet native failure"));
            fixture.drain(fixture.viewer);
            assertEquals(0, actual.get().join());
            assertEquals("false:0", result.get());
            verify(bot, never()).sendOverlayMessage(any());
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(java.util.concurrent.CompletionException.class, parent::join)));
        }
    }

    @Test
    void anImportedOfficialOriginIsRetainedUntilTheRealTeleportSucceeds() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory)) {
            var result = new AtomicReference<String>();
            source(fixture, result);
            var player = fixture.viewer.player();
            var world = player.level();
            when(fixture.server.getLevel(Level.OVERWORLD)).thenReturn(world);
            var file = directory.resolve("config/carpet-org-addition/spectator/" + player.getUUID() + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"data_version\":1,\"pos\":{\"x\":4,\"y\":66,\"z\":-9},\"direction\":{\"yaw\":13,\"pitch\":-5},\"dimension\":\"minecraft:overworld\"}");
            var trip = new CompletableFuture<Boolean>();
            var began = new java.util.concurrent.CountDownLatch(1);
            when(player.getBukkitEntity().teleportAsync(any(Location.class), any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class))).thenAnswer(call -> {
                assertSame(player, fixture.owner.get());
                began.countDown();
                return trip;
            });
            Method method = OrgUtilityCommands.class.getDeclaredMethod("restoreOrigin", ServerPlayer.class);
            method.setAccessible(true);
            fixture.owner.set(player);
            @SuppressWarnings("unchecked") var actual = (CompletableFuture<Boolean>) method.invoke(null, player);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (began.getCount() != 0 && System.nanoTime() < deadline) {
                fixture.drain(fixture.viewer);
                began.await(2, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            assertEquals(0, began.getCount());
            assertTrue(Files.exists(file));
            trip.complete(false);
            assertFalse(actual.join());
            assertTrue(Files.exists(file));
            verify(player, never()).setGameMode(any());
        }
    }

    @Test
    void officialVersionZeroAndOneOriginsAreReadAndChangedFilesAreRetained() throws Exception {
        var server = mock(net.minecraft.server.MinecraftServer.class);
        when(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).thenReturn(directory);
        var id = java.util.UUID.randomUUID();
        var file = directory.resolve("carpetorgaddition/spectator/" + id + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"x\":3,\"y\":65,\"z\":-8,\"yaw\":12,\"pitch\":-4,\"dimension\":\"minecraft:the_nether\"}");
        var old = OrgSpectatorOrigins.read(server, id);
        assertEquals(3, old.x());
        assertEquals("minecraft:the_nether", old.dimension());
        var moved = old.file();
        assertFalse(Files.exists(file));
        Files.writeString(moved, "changed");
        assertThrows(IllegalStateException.class, () -> OrgSpectatorOrigins.remove(old));
        assertEquals("changed", Files.readString(moved));
        var secondId = java.util.UUID.randomUUID();
        var modern = directory.resolve("config/carpet-org-addition/spectator/" + secondId + ".json");
        Files.createDirectories(modern.getParent());
        Files.writeString(modern, "{\"data_version\":1,\"pos\":{\"x\":4,\"y\":66,\"z\":-9},\"direction\":{\"yaw\":13,\"pitch\":-5},\"dimension\":\"minecraft:overworld\"}");
        var origin = OrgSpectatorOrigins.read(server, secondId);
        assertEquals(modern, origin.file());
        assertEquals(-5, origin.pitch());
        OrgSpectatorOrigins.remove(origin);
        assertFalse(Files.exists(modern));
        assertEquals("changed", Files.readString(moved));
    }
}
