package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real closed Netty transport, native disconnect entry and real Folia scheduler retirement.
 */
public class CarpetNormalDisconnectNativeTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        AmsNativeManagementTest.bootstrap();
    }

    private static void field(Object object, Class<?> declaring, String name, Object value) throws Exception {
        Field field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final PlayerList players = mock(PlayerList.class);
        final org.bukkit.craftbukkit.CraftServer craftServer = mock(org.bukkit.craftbukkit.CraftServer.class);
        final org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
        final io.papermc.paper.threadedregions.EntityScheduler scheduler = new io.papermc.paper.threadedregions.EntityScheduler(craftPlayer);
        final ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class);
        final Connection connection;
        final EmbeddedChannel channel;
        final UUID id = UUID.randomUUID();
        final com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(id, "LogoutPlayer");
        final List<ServerPlayer> online = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<ServerPlayer> real = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<UUID, ServerPlayer> byId = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, ServerPlayer> byName = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicBoolean removed = new AtomicBoolean(), owner = new AtomicBoolean(true);
        java.util.function.BooleanSupplier regionHasConnection = () -> true;
        final AtomicInteger quit = new AtomicInteger(), close = new AtomicInteger();
        final org.bukkit.plugin.PluginManager plugins = mock(org.bukkit.plugin.PluginManager.class);
        final org.mockito.MockedStatic<TickThread> ticks = mockStatic(TickThread.class);
        final org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class);
        final org.mockito.MockedStatic<io.papermc.paper.configuration.GlobalConfiguration> configuration = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class);
        final org.mockito.MockedStatic<MinecraftServer> serverSingleton = mockStatic(MinecraftServer.class);
        final org.mockito.MockedStatic<org.leavesmc.leaves.protocol.core.LeavesProtocolManager> protocols = mockStatic(org.leavesmc.leaves.protocol.core.LeavesProtocolManager.class);
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager tickets = mock(ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.class);
        CompletableFuture<Void> quitChild;

        Fixture() throws Exception {
            var global = new io.papermc.paper.configuration.GlobalConfiguration();
            global.messages = global.new Messages();
            global.misc = global.new Misc();
            global.packetLimiter = global.new PacketLimiter();
            global.unsupportedSettings = global.new UnsupportedSettings();
            configuration.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(global);
            serverSingleton.when(MinecraftServer::getServer).thenReturn(server);
            connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel();
            var boss = mock(net.minecraft.server.bossevents.CustomBossEvents.class);
            var notifications = mock(net.minecraft.server.notifications.NotificationManager.class);
            var scoreboard = mock(org.bukkit.craftbukkit.scoreboard.CraftScoreboardManager.class);
            field(server, MinecraftServer.class, "server", craftServer);
            when(server.getPlayerList()).thenReturn(players);
            when(server.getCustomBossEvents()).thenReturn(boss);
            when(server.notificationManager()).thenReturn(notifications);
            when(world.getServer()).thenReturn(server);
            when(craftServer.getPluginManager()).thenReturn(plugins);
            when(craftServer.getScoreboardManager()).thenReturn(scoreboard);
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(plugins);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> owner.get()
                    && (call.getArgument(0) != player || !removed.get() || regionHasConnection.getAsBoolean()));
            ticks.when(TickThread::isTickThread).thenAnswer(call -> owner.get());
            ticks.when(() -> TickThread.isTickThreadFor(any(ServerLevel.class), any(BlockPos.class))).thenAnswer(call -> owner.get());
            ticks.when(() -> TickThread.ensureTickThread(any(Entity.class), anyString())).thenAnswer(call -> {
                assertTrue(TickThread.isTickThreadFor((Entity) call.getArgument(0)));
                return null;
            });
            when(player.level()).thenAnswer(call -> {
                assertTrue(owner.get());
                return world;
            });
            when(player.carpetSpawnServer()).thenReturn(server);
            when(player.getUUID()).thenReturn(id);
            when(player.getGameProfile()).thenReturn(profile);
            when(player.getScoreboardName()).thenReturn(profile.name());
            when(player.getPlainTextName()).thenReturn(profile.name());
            when(player.getDisplayName()).thenReturn(io.papermc.paper.adventure.PaperAdventure.asVanilla(net.kyori.adventure.text.Component.text(profile.name())));
            when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            when(player.chunkPosition()).thenReturn(new ChunkPos(0, 0));
            when(player.isRemoved()).thenAnswer(call -> removed.get());
            when(player.getBukkitEntity()).thenReturn(craftPlayer);
            when(craftPlayer.getHandleRaw()).thenReturn(player);
            when(player.getEnderPearls()).thenReturn(Set.of());
            var source = new net.minecraft.commands.CommandSourceStack(net.minecraft.commands.CommandSource.NULL, net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO,
                    world, net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER, net.minecraft.network.chat.Component.literal(profile.name()), server);
            source = source.withEntity(player);
            field(source, net.minecraft.commands.CommandSourceStack.class, "namesProvider",
                    net.minecraft.commands.CommandSourceStack.NamesProvider.constant(net.minecraft.network.chat.Component.literal(profile.name())));
            when(player.createCommandSourceStack()).thenReturn(source);
            var advancements = mock(net.minecraft.server.PlayerAdvancements.class);
            when(player.getAdvancements()).thenAnswer(call -> {
                assertTrue(TickThread.isTickThreadFor(player));
                return advancements;
            });
            var filter = mock(net.minecraft.server.network.TextFilter.class);
            when(player.getTextFilter()).thenReturn(filter);
            var inventory = mock(net.minecraft.world.inventory.InventoryMenu.class);
            field(player, net.minecraft.world.entity.player.Player.class, "inventoryMenu", inventory);
            player.containerMenu = inventory;
            when(inventory.getCarried()).thenReturn(net.minecraft.world.item.ItemStack.EMPTY);
            var chunks = mock(ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.class);
            field(chunks, ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.class, "chunkHolderManager", tickets);
            when(world.moonrise$getChunkTaskScheduler()).thenReturn(chunks);
            field(craftPlayer, org.bukkit.craftbukkit.entity.CraftEntity.class, "taskScheduler", scheduler);
            field(craftPlayer, org.bukkit.craftbukkit.entity.CraftPlayer.class, "packetProcessor", new net.minecraft.network.PacketProcessor(null));
            field(listener, ServerCommonPacketListenerImpl.class, "server", server);
            field(listener, ServerCommonPacketListenerImpl.class, "connection", connection);
            field(listener, ServerCommonPacketListenerImpl.class, "cserver", craftServer);
            field(listener, ServerGamePacketListenerImpl.class, "player", player);
            field(listener, ServerGamePacketListenerImpl.class, "chatMessageChain", new net.minecraft.util.FutureChain(Runnable::run));
            field(listener, ServerGamePacketListenerImpl.class, "disconnectTicketId", 1L);
            player.connection = listener;
            connection.channel = channel;
            connection.address = new InetSocketAddress("127.0.0.1", 25500);
            connection.preparing = false;
            field(connection, Connection.class, "packetListener", listener);
            when(listener.getOwner()).thenReturn(profile);
            doCallRealMethod().when(player).disconnect();
            doCallRealMethod().when(player).hasDisconnected();
            doCallRealMethod().when(player).retireScheduler();
            doCallRealMethod().when(listener).onDisconnect(any(net.minecraft.network.DisconnectionDetails.class));
            doCallRealMethod().when(listener).switchToConfig();
            doCallRealMethod().when(listener).isAcceptingMessages();
            doCallRealMethod().when(listener).shouldHandleMessage(any());
            doCallRealMethod().when(players).carpetRemoveAsync(same(player), any(), any());
            field(players, PlayerList.class, "server", server);
            field(players, PlayerList.class, "cserver", craftServer);
            field(players, PlayerList.class, "players", online);
            field(players, PlayerList.class, "realPlayers", real);
            field(players, PlayerList.class, "playedPlayers", new java.util.concurrent.CopyOnWriteArrayList<String>());
            field(players, PlayerList.class, "playersByUUID", byId);
            field(players, PlayerList.class, "playersByName", byName);
            online.add(player);
            real.add(player);
            byId.put(id, player);
            byName.put("logoutplayer", player);
            doAnswer(call -> {
                assertTrue(owner.get());
                removed.set(true);
                return null;
            }).when(world).removePlayerImmediately(player, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
            doAnswer(call -> {
                assertTrue(owner.get());
                if (call.getArgument(0) instanceof org.bukkit.event.player.PlayerQuitEvent) {
                    quit.incrementAndGet();
                    if (quitChild != null) ScarpetNativeWork.record(quitChild);
                } else if (call.getArgument(0) instanceof com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent)
                    close.incrementAndGet();
                return null;
            }).when(plugins).callEvent(any());
        }

        void disconnect() {
            channel.close().syncUninterruptibly();
            connection.handleDisconnection();
        }

        io.papermc.paper.threadedregions.RegionizedWorldData worldData() throws Exception {
            var data = mock(io.papermc.paper.threadedregions.RegionizedWorldData.class);
            var connections = new ca.spottedleaf.moonrise.common.list.ReferenceList<Connection>(new Connection[0]);
            connections.add(connection);
            field(data, io.papermc.paper.threadedregions.RegionizedWorldData.class, "connections", connections);
            field(data, io.papermc.paper.threadedregions.RegionizedWorldData.class, "world", world);
            regionHasConnection = () -> connections.contains(connection);
            when(world.getCurrentWorldData()).thenReturn(data);
            doCallRealMethod().when(data).removeConnection(player);
            doCallRealMethod().when(data).tickConnections();
            doCallRealMethod().when(data).hasConnection(any());
            return data;
        }

        void configurationPipeline(AtomicInteger starts, io.papermc.paper.threadedregions.RegionizedWorldData data) {
            channel.pipeline().addLast(net.minecraft.network.HandlerNames.OUTBOUND_CONFIG, new net.minecraft.network.UnconfiguredPipelineHandler.Outbound());
            doAnswer(call -> {
                if (call.getArgument(0) instanceof net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket) {
                    assertTrue(removed.get());
                    assertTrue(online.isEmpty());
                    assertTrue(real.isEmpty());
                    assertTrue(scheduler.isRetired());
                    assertFalse(data.hasConnection(connection));
                    assertTrue(listener.waitingForSwitchToConfig);
                    starts.incrementAndGet();
                }
                return null;
            }).when(listener).send(any(net.minecraft.network.protocol.Packet.class));
        }

        void assertConfigurationProtocol() throws Exception {
            channel.runPendingTasks();
            var encoder = channel.pipeline().get(net.minecraft.network.PacketEncoder.class);
            assertNotNull(encoder);
            Field info = net.minecraft.network.PacketEncoder.class.getDeclaredField("protocolInfo");
            info.setAccessible(true);
            assertEquals(net.minecraft.network.ConnectionProtocol.CONFIGURATION, ((net.minecraft.network.ProtocolInfo<?>) info.get(encoder)).id());
        }

        void assertRemoved() {
            assertTrue(player.hasDisconnected());
            assertTrue(removed.get());
            assertTrue(scheduler.isRetired());
            assertTrue(online.isEmpty());
            assertTrue(real.isEmpty());
            assertFalse(byId.containsKey(id));
            assertFalse(byName.containsKey("logoutplayer"));
            assertEquals(new ChunkPos(0, 0), listener.disconnectPos);
            verify(player.getTextFilter()).leave();
            assertEquals(1, quit.get());
        }

        public void close() {
            channel.finishAndReleaseAll();
            protocols.close();
            serverSingleton.close();
            configuration.close();
            bukkit.close();
            ticks.close();
        }
    }

    @Test
    void aNormalRealClosedConnectionRunsNativeLogoutAndRetiresThePlayerWithinItsCurrentOwner() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.disconnect();
            fixture.assertRemoved();
            assertEquals(1, fixture.close.get());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            fixture.connection.handleDisconnection();
            assertEquals(1, fixture.quit.get());
            assertEquals(1, fixture.close.get());
        }
    }

    @Test
    void failedOldAcceptedWorkCannotCancelMandatoryLogoutAndOffOwnerCompletionReturnsToTheRealScheduler() throws Exception {
        try (var fixture = new Fixture()) {
            var old = new CompletableFuture<Void>();
            ScarpetPlayerInventoryGate.trackAccepted(fixture.player, old);
            fixture.disconnect();
            assertFalse(fixture.removed.get());
            assertFalse(fixture.online.isEmpty());
            assertTrue(ScarpetPlayerInventoryGate.paused(fixture.player));
            fixture.owner.set(false);
            old.completeExceptionally(new java.nio.channels.ClosedChannelException());
            assertFalse(fixture.removed.get());
            fixture.owner.set(true);
            fixture.scheduler.executeTick();
            fixture.assertRemoved();
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        }
    }

    @Test
    void aRealQuitCallbackChildRetainsTheNativeReceiptWithoutKeepingTheDisconnectedPlayerOnline() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.quitChild = new CompletableFuture<>();
            fixture.disconnect();
            fixture.assertRemoved();
            assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            assertTrue(ScarpetPlayerInventoryGate.paused(fixture.player));
            fixture.quitChild.completeExceptionally(new java.nio.channels.ClosedChannelException());
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
        }
    }

    @Test
    void rejectedPausedIntentSettlesItsNativeObservationInsteadOfLeavingAnInvisibleDisconnectBlocker() throws Exception {
        try (var fixture = new Fixture()) {
            var rejected = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var failure = new IllegalStateException("Actual entity scheduler rejected dispatch");
            when(rejected.schedule(any(), any(), anyLong())).thenThrow(failure);
            field(fixture.craftPlayer, org.bukkit.craftbukkit.entity.CraftEntity.class, "taskScheduler", rejected);
            var hold = new CompletableFuture<Void>();
            ScarpetPlayerInventoryGate.whenIdle(fixture.player, () -> hold);
            var receipt = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Boolean>>();
            var observed = ScarpetNativeWork.observeNative(fixture.player, () -> {
                receipt.set(ScarpetPlayerInventoryGate.enqueuePaused(fixture.player, () -> {
                    fail("Rejected intent cannot execute its native body");
                    return CompletableFuture.completedFuture(true);
                }));
                return true;
            });
            ScarpetNativeWork.trackNative(fixture.server, observed);
            assertFalse(receipt.get().isDone());
            fixture.owner.set(false);
            hold.complete(null);
            fixture.owner.set(true);
            assertTrue(receipt.get().isDone());
            assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, receipt.get()::join).getCause());
            assertTrue(observed.isDone());
            assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, observed::join).getCause());
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        }
    }

    @Test
    void aDelayedOldNativeQuitCannotRemoveTheReplacementIdentityFromEitherPlayerIndex() throws Exception {
        try (var fixture = new Fixture()) {
            var old = new CompletableFuture<Void>();
            ScarpetPlayerInventoryGate.trackAccepted(fixture.player, old);
            fixture.disconnect();
            var replacement = mock(ServerPlayer.class);
            var craftReplacement = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
            when(replacement.getBukkitEntity()).thenReturn(craftReplacement);
            fixture.online.add(replacement);
            fixture.real.add(replacement);
            fixture.byName.put("logoutplayer", replacement);
            fixture.byId.put(fixture.id, replacement);
            old.complete(null);
            assertTrue(fixture.removed.get());
            assertTrue(fixture.scheduler.isRetired());
            assertEquals(List.of(replacement), fixture.online);
            assertEquals(List.of(replacement), fixture.real);
            assertSame(replacement, fixture.byId.get(fixture.id));
            assertSame(replacement, fixture.byName.get("logoutplayer"));
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        }
    }

    @Test
    void realHotbarPacketsResumeInAdmissionOrderAndWaitThePriorNativeCallbackChildDespiteLifoGateCompletion() throws Exception {
        try (var fixture = new Fixture()) {
            var held = new CompletableFuture<Void>();
            var child = new CompletableFuture<Void>();
            try {
                var inventory = mock(net.minecraft.world.entity.player.Inventory.class);
                when(fixture.player.getInventory()).thenReturn(inventory);
                var slot = new AtomicInteger();
                when(inventory.getSelectedSlot()).thenAnswer(call -> slot.get());
                doAnswer(call -> {
                    assertTrue(fixture.owner.get());
                    slot.set(call.getArgument(0));
                    return null;
                }).when(inventory).setSelectedSlot(anyInt());
                when(fixture.listener.getCraftPlayer()).thenReturn(fixture.craftPlayer);
                doCallRealMethod().when(fixture.listener).handleSetCarriedItem(any(net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket.class));
                var order = new ArrayList<Integer>();
                doAnswer(call -> {
                    var event = (org.bukkit.event.player.PlayerItemHeldEvent) call.getArgument(0);
                    assertTrue(fixture.owner.get());
                    order.add(event.getNewSlot());
                    if (event.getNewSlot() == 1) ScarpetNativeWork.record(child);
                    return null;
                }).when(fixture.plugins).callEvent(isA(org.bukkit.event.player.PlayerItemHeldEvent.class));
                ScarpetPlayerInventoryGate.whenIdle(fixture.player, () -> held);
                for (int selected : List.of(1, 2, 3))
                    fixture.listener.handleSetCarriedItem(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(selected));
                assertTrue(order.isEmpty());
                held.complete(null);
                assertEquals(List.of(1), order);
                assertEquals(1, slot.get());
                assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());
                fixture.owner.set(false);
                child.complete(null);
                assertEquals(List.of(1), order);
                fixture.owner.set(true);
                fixture.scheduler.executeTick();
                assertEquals(List.of(1, 2, 3), order);
                assertEquals(3, slot.get());
                assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            } finally {
                fixture.owner.set(true);
                held.complete(null);
                child.complete(null);
                if (!fixture.scheduler.isRetired()) fixture.scheduler.executeTick();
            }
        }
    }

    @Test
    void retiringTheRealSchedulerFailsWaitingIntentsWithoutCancellingTheAdmittedNativeBody() throws Exception {
        try (var fixture = new Fixture()) {
            var child = new CompletableFuture<Void>();
            var later = new AtomicInteger();
            try {
                var active = ScarpetPlayerInventoryGate.enqueuePaused(fixture.player, () -> {
                    ScarpetNativeWork.record(child);
                    return CompletableFuture.completedFuture(7);
                });
                var waiting = ScarpetPlayerInventoryGate.enqueuePaused(fixture.player, () -> {
                    later.incrementAndGet();
                    return CompletableFuture.completedFuture(9);
                });
                assertFalse(active.isDone());
                assertFalse(waiting.isDone());
                assertFalse(active.cancel(false));
                fixture.player.retireScheduler();
                assertTrue(fixture.scheduler.isRetired());
                assertTrue(waiting.isCompletedExceptionally());
                assertEquals(0, later.get());
                assertFalse(active.isDone());
                assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());
                child.complete(null);
                assertEquals(7, active.join());
                assertEquals(0, later.get());
                assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            } finally {
                child.complete(null);
            }
        }
    }

    @Test
    void retirementSettlesAnIntentStillWaitingForTheInventoryGateWithoutMakingTheSnapshotAwaitThatIntent() throws Exception {
        try (var fixture = new Fixture()) {
            var held = new CompletableFuture<Void>();
            var calls = new AtomicInteger();
            try {
                var snapshot = ScarpetPlayerInventoryGate.whenIdle(fixture.player, () -> held);
                var waiting = ScarpetPlayerInventoryGate.enqueuePaused(fixture.player, () -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(3);
                });
                assertFalse(snapshot.isDone());
                assertFalse(waiting.isDone());
                fixture.player.retireScheduler();
                assertTrue(waiting.isCompletedExceptionally());
                assertEquals(0, calls.get());
                assertFalse(snapshot.isDone());
                held.complete(null);
                assertTrue(snapshot.isDone());
                assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
                assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            } finally {
                held.complete(null);
            }
        }
    }

    @Test
    void consecutiveQueuedIntentsAndTheirCompletionCallbacksKeepTheirOwnCapturedNativeFlags() throws Exception {
        boolean previous = carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get();
        try (var fixture = new Fixture()) {
            var held = new CompletableFuture<Void>();
            var child = new CompletableFuture<Void>();
            try {
                ScarpetPlayerInventoryGate.whenIdle(fixture.player, () -> held);
                carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(true);
                var first = ScarpetPlayerInventoryGate.enqueuePaused(fixture.player, () -> {
                    assertTrue(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
                    ScarpetNativeWork.record(child);
                    return CompletableFuture.completedFuture(1);
                });
                var firstCompleted = first.thenAccept(value -> assertTrue(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get()));
                carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(false);
                var second = ScarpetPlayerInventoryGate.enqueuePaused(fixture.player, () -> {
                    assertFalse(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
                    return CompletableFuture.completedFuture(2);
                });
                var secondCompleted = second.thenAccept(value -> assertFalse(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get()));
                held.complete(null);
                assertFalse(first.isDone());
                assertFalse(second.isDone());
                child.complete(null);
                assertEquals(1, first.join());
                assertEquals(2, second.join());
                firstCompleted.join();
                secondCompleted.join();
                assertFalse(carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.get());
                assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            } finally {
                held.complete(null);
                child.complete(null);
            }
        } finally {
            carpet.script.external.ScarpetRuntime.FILL_SKIP_UPDATES.set(previous);
        }
    }

    @Test
    void aLargeReadyOwnedQueueCompletesInFifoOrderWithoutAnExtraSchedulerTickOrRecursiveStack() throws Exception {
        try (var fixture = new Fixture()) {
            var held = new CompletableFuture<Void>();
            try {
                ScarpetPlayerInventoryGate.whenIdle(fixture.player, () -> held);
                var order = new ArrayList<Integer>();
                var receipts = new ArrayList<CompletableFuture<Integer>>();
                for (int index = 0; index < 512; index++) {
                    int number = index;
                    receipts.add(ScarpetPlayerInventoryGate.enqueuePaused(fixture.player, () -> {
                        order.add(number);
                        return CompletableFuture.completedFuture(number);
                    }));
                }
                assertTrue(order.isEmpty());
                held.complete(null);
                assertEquals(java.util.stream.IntStream.range(0, 512).boxed().toList(), order);
                for (int index = 0; index < 512; index++) assertEquals(index, receipts.get(index).join());
                assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            } finally {
                held.complete(null);
            }
        }
    }

    @Test
    void anAdmittedIntentCanRemoveItselfWithoutAwaitingItsOwnReceiptWhileIndependentOldNativeWorkStillFinishes() throws Exception {
        try (var fixture = new Fixture()) {
            var independent = new CompletableFuture<Void>();
            var cleanups = new AtomicInteger();
            try {
                ScarpetPlayerInventoryGate.trackAccepted(fixture.player, independent);
                var actual = ScarpetPlayerInventoryGate.enqueuePaused(fixture.player, () -> ScarpetPlayerInventoryGate.whenIdleForRemoval(fixture.player, () -> {
                    cleanups.incrementAndGet();
                    fixture.player.retireScheduler();
                    return 17;
                }));
                assertFalse(actual.isDone());
                assertEquals(0, cleanups.get());
                independent.completeExceptionally(new java.nio.channels.ClosedChannelException());
                assertEquals(17, actual.join());
                assertEquals(1, cleanups.get());
                assertTrue(fixture.scheduler.isRetired());
                assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
                assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            } finally {
                independent.complete(null);
            }
        }
    }

    @Test
    void realRegionConnectionTicksKeepTheOwnerUntilDeferredNativeLogoutHasItsDisconnectTicket() throws Exception {
        try (var fixture = new Fixture(); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            var global = mock(io.papermc.paper.threadedregions.RegionizedServer.class);
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(global);
            var data = fixture.worldData();
            var old = new CompletableFuture<Void>();
            try {
                ScarpetPlayerInventoryGate.trackAccepted(fixture.player, old);
                fixture.channel.close().syncUninterruptibly();
                data.tickConnections();
                assertTrue(data.hasConnection(fixture.connection));
                assertNull(fixture.listener.disconnectPos);
                assertFalse(fixture.removed.get());
                verify(global, never()).removeConnection(fixture.connection);
                old.complete(null);
                fixture.assertRemoved();
                assertTrue(data.hasConnection(fixture.connection));
                assertTrue(TickThread.isTickThreadFor(fixture.player));
                data.tickConnections();
                assertFalse(data.hasConnection(fixture.connection));
                assertFalse(TickThread.isTickThreadFor(fixture.player));
                verify(global).removeConnection(fixture.connection);
                assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            } finally {
                old.complete(null);
            }
        }
    }

    @Test
    void aClosedConfigurationSwitchConnectionNeedsNoLogoutTicketOrRepeatedGlobalCleanupTask() throws Exception {
        try (var fixture = new Fixture(); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            var global = mock(io.papermc.paper.threadedregions.RegionizedServer.class);
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(global);
            var data = fixture.worldData();
            fixture.listener.waitingForSwitchToConfig = true;
            fixture.removed.set(true);
            fixture.channel.close().syncUninterruptibly();
            data.tickConnections();
            assertFalse(data.hasConnection(fixture.connection));
            assertNull(fixture.listener.disconnectPos);
            assertEquals(0, fixture.quit.get());
            verify(global).removeConnection(fixture.connection);
            verifyNoInteractions(fixture.tickets);
            fixture.bukkit.verify(org.bukkit.Bukkit::getGlobalRegionScheduler, never());
        }
    }

    @Test
    void aClosedConnectionWhoseGamePlayerIsAlreadyGoneCanBeRemovedWithoutADanglingTicketCleanup() throws Exception {
        try (var fixture = new Fixture(); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            var global = mock(io.papermc.paper.threadedregions.RegionizedServer.class);
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(global);
            var data = fixture.worldData();
            field(fixture.connection, Connection.class, "packetListener", mock(net.minecraft.network.protocol.handshake.ServerHandshakePacketListener.class));
            fixture.channel.close().syncUninterruptibly();
            data.tickConnections();
            assertNull(fixture.connection.getPlayer());
            assertFalse(data.hasConnection(fixture.connection));
            verify(global).removeConnection(fixture.connection);
            verifyNoInteractions(fixture.tickets);
            fixture.bukkit.verify(org.bukkit.Bukkit::getGlobalRegionScheduler, never());
        }
    }

    @Test
    void realConfigurationSwitchRetainsTheOriginalConnectionOwnerUntilOldWorkAndPhysicalRemovalFinish() throws Exception {
        try (var fixture = new Fixture(); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            var data = fixture.worldData();
            var starts = new AtomicInteger();
            fixture.configurationPipeline(starts, data);
            var old = new CompletableFuture<Void>();
            try {
                ScarpetPlayerInventoryGate.trackAccepted(fixture.player, old);
                fixture.listener.switchToConfig();
                fixture.listener.switchToConfig();
                assertTrue(data.hasConnection(fixture.connection));
                assertFalse(fixture.listener.waitingForSwitchToConfig);
                assertFalse(fixture.removed.get());
                assertFalse(fixture.listener.isAcceptingMessages());
                assertFalse(fixture.listener.shouldHandleMessage(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN)));
                assertFalse(fixture.listener.shouldHandleMessage(net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket.INSTANCE));
                assertEquals(0, starts.get());
                assertNotNull(fixture.channel.pipeline().get(net.minecraft.network.HandlerNames.OUTBOUND_CONFIG));
                fixture.owner.set(false);
                old.complete(null);
                assertFalse(fixture.removed.get());
                assertTrue(data.hasConnection(fixture.connection));
                fixture.owner.set(true);
                fixture.scheduler.executeTick();
                assertTrue(fixture.removed.get());
                assertTrue(fixture.online.isEmpty());
                assertTrue(fixture.scheduler.isRetired());
                assertTrue(fixture.listener.waitingForSwitchToConfig);
                assertFalse(data.hasConnection(fixture.connection));
                assertFalse(fixture.listener.isAcceptingMessages());
                assertTrue(fixture.listener.shouldHandleMessage(net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket.INSTANCE));
                assertEquals(1, fixture.quit.get());
                assertEquals(1, starts.get());
                assertNull(fixture.listener.disconnectPos);
                fixture.assertConfigurationProtocol();
                assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
                verifyNoInteractions(fixture.tickets);
            } finally {
                fixture.owner.set(true);
                old.complete(null);
            }
        }
    }

    @Test
    void realConfigurationProtocolCanChangeAtThePhysicalOwnerTailWhileTheActualQuitChildStillRuns() throws Exception {
        try (var fixture = new Fixture(); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            var data = fixture.worldData();
            var starts = new AtomicInteger();
            fixture.configurationPipeline(starts, data);
            fixture.quitChild = new CompletableFuture<>();
            try {
                fixture.listener.switchToConfig();
                assertTrue(fixture.removed.get());
                assertTrue(fixture.scheduler.isRetired());
                assertTrue(fixture.listener.waitingForSwitchToConfig);
                assertFalse(data.hasConnection(fixture.connection));
                assertEquals(1, starts.get());
                fixture.assertConfigurationProtocol();
                assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());
                assertTrue(ScarpetPlayerInventoryGate.paused(fixture.player));
                fixture.owner.set(false);
                fixture.quitChild.complete(null);
                fixture.owner.set(true);
                assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
                assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
                assertEquals(1, fixture.quit.get());
                verifyNoInteractions(fixture.tickets);
            } finally {
                fixture.owner.set(true);
                fixture.quitChild.complete(null);
            }
        }
    }

    @Test
    void aRealNetworkCloseDuringPendingConfigurationUsesTheSameRemovalAndOrdinaryDisconnectTicket() throws Exception {
        try (var fixture = new Fixture(); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            var global = mock(io.papermc.paper.threadedregions.RegionizedServer.class);
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(global);
            var data = fixture.worldData();
            var starts = new AtomicInteger();
            fixture.configurationPipeline(starts, data);
            var old = new CompletableFuture<Void>();
            try {
                ScarpetPlayerInventoryGate.trackAccepted(fixture.player, old);
                fixture.listener.switchToConfig();
                fixture.channel.close().syncUninterruptibly();
                data.tickConnections();
                assertTrue(data.hasConnection(fixture.connection));
                assertFalse(fixture.removed.get());
                assertEquals(0, fixture.quit.get());
                old.complete(null);
                fixture.assertRemoved();
                assertFalse(fixture.listener.waitingForSwitchToConfig);
                assertFalse(fixture.listener.hackSwitchingConfig);
                assertEquals(0, starts.get());
                assertNull(fixture.channel.pipeline().get(net.minecraft.network.PacketEncoder.class));
                assertTrue(data.hasConnection(fixture.connection));
                data.tickConnections();
                assertFalse(data.hasConnection(fixture.connection));
                verify(global).removeConnection(fixture.connection);
                assertEquals(1, fixture.quit.get());
                assertEquals(1, fixture.close.get());
                assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            } finally {
                fixture.owner.set(true);
                old.complete(null);
            }
        }
    }

    @Test
    void nativeLogoutPrefixFailureSettlesTheAlreadyPublishedReceiptWithItsOriginalCause() throws Exception {
        try (var fixture = new Fixture()) {
            var failure = new IllegalStateException("Native disconnect prefix failure");
            doThrow(failure).when(fixture.player).disconnect();
            var observed = ScarpetNativeWork.observeNative(fixture.player, () -> {
                fixture.disconnect();
                return true;
            });
            assertTrue(observed.isDone());
            assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, observed::join).getCause());
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
            assertFalse(ScarpetPlayerInventoryGate.paused(fixture.player));
            assertEquals(0, fixture.quit.get());
            assertFalse(fixture.removed.get());
        }
    }

    @Test
    void physicalConfigurationHandoffNeverReopensGamePacketAdmissionBeforePublishingTheNewOwner() throws Exception {
        try (var fixture = new Fixture(); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            var data = fixture.worldData();
            var starts = new AtomicInteger();
            fixture.configurationPipeline(starts, data);
            var handoffs = new AtomicInteger();
            when(fixture.world.getCurrentWorldData()).thenAnswer(call -> {
                if (fixture.removed.get()) {
                    handoffs.incrementAndGet();
                    assertFalse(fixture.listener.isAcceptingMessages(), "Removed player must never reopen game packet admission during owner handoff");
                }
                return data;
            });
            fixture.listener.switchToConfig();
            assertTrue(handoffs.get() > 0, "Exercise the actual physical owner transfer, not only its final state");
            assertTrue(fixture.listener.waitingForSwitchToConfig);
            assertEquals(1, starts.get());
            fixture.assertConfigurationProtocol();
            assertTrue(ScarpetNativeWork.whenIdle(fixture.server).isDone());
        }
    }

}
