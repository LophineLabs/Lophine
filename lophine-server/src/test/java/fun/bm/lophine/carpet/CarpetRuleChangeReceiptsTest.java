package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import me.earthme.luminol.config.ConfigsInstance;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CarpetRuleChangeReceiptsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CarpetRuleRegistry.names();
    }

    static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final CommandResultCallback callback = mock(CommandResultCallback.class);
        final ConfigsInstance config = mock(ConfigsInstance.class);
        final ScarpetRuntime engine = mock(ScarpetRuntime.class);
        final Commands commands = mock(Commands.class);
        final Queue<Runnable> tasks = new ArrayDeque<>();
        final List<String> order = new ArrayList<>();
        boolean global;
        final CompletableFuture<Void> configured = new CompletableFuture<>(), guest = new CompletableFuture<>(), published = new CompletableFuture<>(), tree = new CompletableFuture<>(), feedback = new CompletableFuture<>(), resultChild = new CompletableFuture<>();
        final org.mockito.MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final org.mockito.MockedStatic<io.papermc.paper.threadedregions.RegionizedServer> globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class);
        final org.mockito.MockedStatic<CarpetRuleRegistry> registry = mockStatic(CarpetRuleRegistry.class, CALLS_REAL_METHODS);
        final org.mockito.MockedStatic<ScarpetRuntime> runtimes = mockStatic(ScarpetRuntime.class, CALLS_REAL_METHODS);
        final org.mockito.MockedStatic<CarpetProtocalDataBase> protocol = mockStatic(CarpetProtocalDataBase.class);
        final String before = GeneralCompatConfig.commandLog;
        final boolean beforeAlways = GeneralCompatConfig.carpetAlwaysSetDefault;

        Fixture() throws Exception {
            when(source.getServer()).thenReturn(server);
            when(source.getLevel()).thenReturn(world);
            when(source.getEntity()).thenReturn(player);
            when(source.getPosition()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
            when(source.callback()).thenReturn(callback);
            when(player.carpetSpawnServer()).thenReturn(server);
            when(player.level()).thenReturn(world);
            when(world.getServer()).thenReturn(server);
            when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
            when(player.position()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            var players = mock(net.minecraft.server.players.PlayerList.class);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getPlayers()).thenReturn(List.of(player));
            when(server.getCommands()).thenReturn(commands);
            ticks.when(() -> TickThread.isTickThreadFor(player)).thenAnswer(call -> !global);
            var bukkit = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
            when(player.getBukkitEntity()).thenReturn(bukkit);
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var schedulerField = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
            schedulerField.setAccessible(true);
            schedulerField.set(bukkit, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                java.util.function.Consumer<net.minecraft.world.entity.Entity> action = call.getArgument(0);
                tasks.add(() -> {
                    boolean previous = global;
                    global = false;
                    try {
                        action.accept(player);
                    } finally {
                        global = previous;
                    }
                });
                return true;
            });
            var executor = mock(io.papermc.paper.threadedregions.RegionizedServer.class);
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(executor);
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenAnswer(call -> global);
            doAnswer(call -> {
                Runnable action = call.getArgument(0);
                tasks.add(() -> {
                    boolean previous = global;
                    global = true;
                    try {
                        action.run();
                    } finally {
                        global = previous;
                    }
                });
                return null;
            }).when(executor).addTask(any(Runnable.class));
            registry.when(CarpetRuleRegistry::config).thenReturn(config);
            runtimes.when(() -> ScarpetRuntime.of(server)).thenReturn(engine);
            when(config.applySingleConfig(anyString(), any(), anyBoolean())).thenAnswer(call -> {
                assertTrue(global);
                order.add("config");
                GeneralCompatConfig.commandLog = (String) call.getArgument(1);
                ScarpetNativeWork.record(configured);
                return ConfigsInstance.SingleConfigResult.UPDATED;
            });
            when(engine.submit(any(Supplier.class))).thenAnswer(call -> {
                order.add("guest");
                var guestRecord = ScarpetNativeWork.class.getDeclaredMethod("recordGuest", CompletableFuture.class);
                guestRecord.setAccessible(true);
                guestRecord.invoke(null, guest);
                return guest;
            });
            protocol.when(CarpetProtocalDataBase::apply).thenAnswer(call -> {
                assertTrue(global);
                order.add("protocol");
                ScarpetNativeWork.record(published);
                return null;
            });
            when(commands.carpetReloadCommands(player)).thenAnswer(call -> {
                assertFalse(global);
                order.add("tree");
                return tree;
            });
            doAnswer(call -> {
                assertFalse(global);
                order.add("feedback");
                ScarpetNativeWork.record(feedback);
                return null;
            }).when(source).sendSuccess(any(), eq(false));
            doAnswer(call -> {
                assertFalse(global);
                order.add("callback");
                ScarpetNativeWork.record(resultChild);
                return null;
            }).when(callback).onResult(anyBoolean(), anyInt());
        }

        void drain() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }

        void finish() {
            configured.complete(null);
            guest.complete(null);
            drain();
            published.complete(null);
            drain();
            tree.complete(null);
            drain();
            feedback.complete(null);
            drain();
            resultChild.complete(null);
            drain();
        }

        public void close() {
            protocol.close();
            runtimes.close();
            registry.close();
            globals.close();
            ticks.close();
            GeneralCompatConfig.commandLog = before;
            GeneralCompatConfig.carpetAlwaysSetDefault = beforeAlways;
        }
    }

    @Test
    void actualRuleCommandWaitsEveryObserverGuestTreeFeedbackAndCallbackChild() throws Exception {
        try (var f = new Fixture(); var scope = CarpetAsyncCommandResults.open()) {
            assertEquals(1, CarpetRuleChanges.change(f.source, "commandLog", "true", true, false));
            var result = scope.resultFuture(f.source);
            f.drain();
            assertEquals(List.of("config"), f.order);
            assertFalse(result.isDone());
            f.configured.complete(null);
            f.drain();
            assertEquals(List.of("config", "guest"), f.order);
            f.guest.complete(null);
            f.drain();
            assertEquals("protocol", f.order.getLast());
            assertFalse(result.isDone());
            f.published.complete(null);
            f.drain();
            assertEquals("tree", f.order.getLast());
            f.tree.complete(null);
            f.drain();
            assertEquals("feedback", f.order.getLast());
            assertFalse(result.isDone());
            f.feedback.complete(null);
            f.drain();
            assertEquals("callback", f.order.getLast());
            assertFalse(result.isDone());
            f.resultChild.complete(null);
            assertEquals(1, result.get(3, TimeUnit.SECONDS));
            verify(f.callback).onResult(true, 1);
        }
    }

    @Test
    void actualFailedConfigurationObserverBlocksNotificationsAndSuccess() throws Exception {
        try (var f = new Fixture(); var scope = CarpetAsyncCommandResults.open()) {
            CarpetRuleChanges.change(f.source, "commandLog", "true", false, false);
            var result = scope.resultFuture(f.source);
            f.drain();
            f.configured.completeExceptionally(new IllegalStateException("actual observer failed"));
            f.drain();
            f.resultChild.complete(null);
            assertEquals(0, result.get(3, TimeUnit.SECONDS));
            assertEquals(List.of("config", "callback"), f.order);
            verify(f.source).sendFailure(argThat(message -> message.getString().equals("actual observer failed")));
            verify(f.source, never()).sendSuccess(any(), anyBoolean());
            verify(f.engine, never()).submit(any(Supplier.class));
            verify(f.callback).onResult(false, 0);
        }
    }

    @Test
    void unchangedSettingDoesNotReinvokeTheGuestOrPublishAnotherRuleChange() throws Exception {
        try (var f = new Fixture(); var scope = CarpetAsyncCommandResults.open()) {
            doReturn(ConfigsInstance.SingleConfigResult.UNCHANGED).when(f.config).applySingleConfig(anyString(), any(), anyBoolean());
            CarpetRuleChanges.change(f.source, "commandLog", "ops", false, false);
            var result = scope.resultFuture(f.source);
            f.drain();
            assertEquals(List.of("feedback"), f.order);
            f.feedback.complete(null);
            f.drain();
            f.resultChild.complete(null);
            assertEquals(1, result.get(3, TimeUnit.SECONDS));
            verify(f.engine, never()).submit(any(Supplier.class));
            f.protocol.verifyNoInteractions();
            verifyNoInteractions(f.commands);
        }
    }

    @Test
    void cancellingTheCallerViewKeepsTheAcceptedRuleAndItsCallbackAlive() throws Exception {
        try (var f = new Fixture(); var scope = CarpetAsyncCommandResults.open()) {
            CarpetRuleChanges.change(f.source, "commandLog", "true", false, false);
            var caller = scope.resultFuture(f.source);
            assertTrue(caller.cancel(false));
            f.drain();
            f.finish();
            assertEquals(1, scope.resultFuture(f.source).get(3, TimeUnit.SECONDS));
            verify(f.callback, times(1)).onResult(true, 1);
        }
    }

    @Test
    void actualEnderChestEnableBroadcastRetainsConsoleAndEveryPlayerChild() throws Exception {
        try (var f = new Fixture()) {
            var console = new CompletableFuture<Void>();
            var player = new CompletableFuture<Void>();
            doAnswer(call -> {
                assertTrue(f.global);
                f.order.add("console");
                ScarpetNativeWork.record(console);
                return null;
            }).when(f.server).sendSystemMessage(any(Component.class));
            doAnswer(call -> {
                assertFalse(f.global);
                f.order.add("player");
                ScarpetNativeWork.record(player);
                return null;
            }).when(f.player).sendSystemMessage(any(Component.class));
            var actual = CarpetRuleObservers.enderChestEnabled(f.server);
            f.drain();
            assertEquals(List.of("console"), f.order);
            assertFalse(actual.isDone());
            console.complete(null);
            f.drain();
            assertEquals(List.of("console", "player"), f.order);
            assertFalse(actual.isDone());
            player.complete(null);
            actual.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void savedRestartSettingWaitsTheRealRestartTipWithoutChangingTheRuntimeRule() throws Exception {
        try (var f = new Fixture(); var scope = CarpetAsyncCommandResults.open()) {
            boolean before = GeneralCompatConfig.largeShulkerBox;
            var tip = new CompletableFuture<Void>();
            doReturn(ConfigsInstance.SingleConfigResult.SAVED_FOR_RESTART).when(f.config).applySingleConfig(anyString(), any(), anyBoolean());
            doAnswer(call -> {
                f.order.add("restart");
                ScarpetNativeWork.record(tip);
                return null;
            }).when(f.player).sendSystemMessage(any(Component.class));
            CarpetRuleChanges.change(f.source, "largeShulkerBox", "true", true, false);
            var actual = scope.resultFuture(f.source);
            f.drain();
            assertEquals(List.of("restart"), f.order);
            assertFalse(actual.isDone());
            assertEquals(before, GeneralCompatConfig.largeShulkerBox);
            tip.complete(null);
            f.drain();
            assertEquals("feedback", f.order.getLast());
            f.feedback.complete(null);
            f.drain();
            f.resultChild.complete(null);
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            verify(f.engine, never()).submit(any(Supplier.class));
            f.protocol.verifyNoInteractions();
        }
    }

    @Test
    void failedGuestRuleCallbackKeepsItsRawFailureAndFinishesNativePublicationBeforeFailureFeedback() throws Exception {
        try (var f = new Fixture(); var scope = CarpetAsyncCommandResults.open()) {
            var parent = ScarpetNativeWork.observeNative(f.player, () -> CarpetRuleChanges.change(f.source, "commandLog", "true", false, false));
            var actual = scope.resultFuture(f.source);
            f.drain();
            f.configured.complete(null);
            f.drain();
            f.guest.completeExceptionally(new IllegalArgumentException("actual guest rule callback"));
            f.drain();
            assertEquals("protocol", f.order.getLast());
            assertFalse(actual.isDone());
            f.published.complete(null);
            f.drain();
            assertEquals("tree", f.order.getLast());
            f.tree.complete(null);
            f.drain();
            f.resultChild.complete(null);
            assertEquals(0, actual.get(3, TimeUnit.SECONDS));
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(ExecutionException.class, () -> parent.get(3, TimeUnit.SECONDS))));
            verify(f.callback).onResult(false, 0);
            verify(f.source).sendFailure(argThat(message -> message.getString().equals("actual guest rule callback")));
        }
    }

    @Test
    void alwaysDefaultNormalChangeWaitsOrdinaryFeedbackThenDefaultNoticeAndTheRealSaveChild() throws Exception {
        try (var f = new Fixture(); var scope = CarpetAsyncCommandResults.open()) {
            GeneralCompatConfig.carpetAlwaysSetDefault = true;
            var file = mock(com.electronwill.nightconfig.core.file.CommentedFileConfig.class);
            when(f.config.getFileInstance()).thenReturn(file);
            var save = new CompletableFuture<Void>();
            doAnswer(call -> {
                assertTrue(f.global);
                f.order.add("save");
                ScarpetNativeWork.record(save);
                return null;
            }).when(f.config).saveConfigs();
            CarpetRuleChanges.change(f.source, "commandLog", "true", false, false);
            var actual = scope.resultFuture(f.source);
            f.drain();
            f.finish();
            assertEquals("save", f.order.getLast());
            assertFalse(actual.isDone());
            verify(f.source, times(2)).sendSuccess(any(), eq(false));
            verify(file).set(CarpetRuleRegistry.get("commandLog").path(), "true");
            save.complete(null);
            f.drain();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void enablingAlwaysDefaultStoresTheRuleItselfAfterItsTailReadsTheNewFlag() throws Exception {
        checkAlwaysFlag(true);
    }

    @Test
    void disablingAlwaysDefaultDoesNotStoreTheNewFalseBecauseTheTailReadsTheNewFlag() throws Exception {
        checkAlwaysFlag(false);
    }

    void checkAlwaysFlag(boolean requested) throws Exception {
        try (var f = new Fixture(); var scope = CarpetAsyncCommandResults.open()) {
            GeneralCompatConfig.carpetAlwaysSetDefault = !requested;
            var file = mock(com.electronwill.nightconfig.core.file.CommentedFileConfig.class);
            when(f.config.getFileInstance()).thenReturn(file);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                assertTrue(f.global);
                GeneralCompatConfig.carpetAlwaysSetDefault = (Boolean) call.getArgument(1);
                ScarpetNativeWork.record(f.configured);
                return calls.incrementAndGet() == 1 ? ConfigsInstance.SingleConfigResult.UPDATED : ConfigsInstance.SingleConfigResult.UNCHANGED;
            }).when(f.config).applySingleConfig(anyString(), any(), eq(false));
            CarpetRuleChanges.change(f.source, "carpetAlwaysSetDefault", Boolean.toString(requested), false, false);
            var actual = scope.resultFuture(f.source);
            f.drain();
            f.finish();
            assertEquals(1, actual.get(3, TimeUnit.SECONDS));
            assertEquals(requested, GeneralCompatConfig.carpetAlwaysSetDefault);
            if (requested) {
                assertEquals(2, calls.get());
                verify(file).set(CarpetRuleRegistry.get("carpetAlwaysSetDefault").path(), true);
                verify(f.config).saveConfigs();
            } else {
                assertEquals(1, calls.get());
                verifyNoInteractions(file);
                verify(f.config, never()).saveConfigs();
            }
        }
    }
}
