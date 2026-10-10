package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Executes the actual Commands pool, async/sync event bodies and physical packet receipt.
 */
class OrgPermissionPhysicalReloadTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() throws Exception {
        OrgInventoryPersistenceTest.bootstrap();
        CarpetCommandTreePacketReceiptTest.bootstrap();
    }

    @Test
    void worldFileAndCallbackWaitRealPoolEventsPacketAndLateNativeChild() throws Exception {
        check(false);
    }

    @Test
    void actualPhysicalCommandPacketFailurePreventsPermissionFileAndSuccessCallback() throws Exception {
        check(true);
    }

    void check(boolean fail) throws Exception {
        int tab = org.spigotmc.SpigotConfig.tabComplete;
        boolean namespaced = org.spigotmc.SpigotConfig.sendNamespaced;
        var bukkitField = org.bukkit.Bukkit.class.getDeclaredField("server");
        bukkitField.setAccessible(true);
        Object old = bukkitField.get(null);
        var pluginManager = mock(org.bukkit.plugin.PluginManager.class);
        var bukkit = mock(org.bukkit.Server.class);
        when(bukkit.getPluginManager()).thenReturn(pluginManager);
        bukkitField.set(null, bukkit);
        org.spigotmc.SpigotConfig.tabComplete = 0;
        org.spigotmc.SpigotConfig.sendNamespaced = true;
        try (var actors = new OrgInventoryPersistenceTest.Fixture(directory); var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);
            when(actors.server.getPlayerList().getPlayers()).thenReturn(java.util.List.of(actors.viewer.player()));
            var player = actors.viewer.player();
            when(player.carpetSpawnServer()).thenReturn(actors.server);
            when(player.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
            when(player.getBukkitEntity().getServer()).thenReturn(bukkit);
            var playerSource = new CommandSourceStack(mock(CommandSource.class), net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO, player.level(), PermissionSet.ALL_PERMISSIONS, actors.server, player);
            when(player.createCommandSourceStack()).thenReturn(playerSource);
            var commands = mock(Commands.class, CALLS_REAL_METHODS);
            var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
            dispatcher.register(Commands.literal("physical"));
            var dispatcherField = Commands.class.getDeclaredField("dispatcher");
            dispatcherField.setAccessible(true);
            dispatcherField.set(commands, dispatcher);
            when(actors.server.getCommands()).thenReturn(commands);
            var listener = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            var wire = mock(net.minecraft.network.Connection.class);
            var channel = mock(io.netty.channel.Channel.class);
            var closing = mock(io.netty.channel.ChannelFuture.class);
            when(wire.isConnected()).thenReturn(true);
            when(channel.isOpen()).thenReturn(true);
            when(channel.closeFuture()).thenReturn(closing);
            var wireField = net.minecraft.network.Connection.class.getField("channel");
            wireField.setAccessible(true);
            wireField.set(wire, channel);
            var connectionField = net.minecraft.server.network.ServerCommonPacketListenerImpl.class.getField("connection");
            connectionField.setAccessible(true);
            connectionField.set(listener, wire);
            var playerField = net.minecraft.server.level.ServerPlayer.class.getField("connection");
            playerField.setAccessible(true);
            playerField.set(player, listener);
            var asyncEntered = new CompletableFuture<Void>();
            var asyncReleased = new CompletableFuture<Void>();
            var syncChild = new CompletableFuture<Void>();
            var physicalChild = new CompletableFuture<Void>();
            var completion = new AtomicReference<io.netty.channel.ChannelFutureListener>();
            var order = new CopyOnWriteArrayList<String>();
            doAnswer(call -> {
                Object event = call.getArgument(0);
                if (event instanceof com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent<?> e) {
                    if (e.hasFiredAsync()) {
                        assertSame(player, actors.owner.get());
                        order.add("sync");
                        ScarpetNativeWork.record(syncChild);
                    } else {
                        order.add("pool:" + Thread.currentThread().getName());
                        asyncEntered.complete(null);
                        asyncReleased.get(3, TimeUnit.SECONDS);
                    }
                } else if (event instanceof org.bukkit.event.player.PlayerCommandSendEvent) {
                    assertSame(player, actors.owner.get());
                    order.add("labels");
                }
                return null;
            }).when(pluginManager).callEvent(any());
            doAnswer(call -> {
                assertSame(player, actors.owner.get());
                order.add("packet");
                completion.set(call.getArgument(1));
                ScarpetNativeWork.record(physicalChild);
                return null;
            }).when(listener).send(any(net.minecraft.network.protocol.Packet.class), any(io.netty.channel.ChannelFutureListener.class));
            var source = mock(CommandSourceStack.class);
            var callback = new AtomicReference<String>();
            when(source.getServer()).thenReturn(actors.server);
            when(source.getEntity()).thenReturn(player);
            when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);
            when(source.callback()).thenReturn((success, value) -> callback.set(success + ":" + value));
            doAnswer(call -> {
                order.add("failure:" + call.<net.minecraft.network.chat.Component>getArgument(0).getString());
                return null;
            }).when(source).sendFailure(any());
            var root = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
            OrgServerPermissions.register(root);
            CompletableFuture<Integer> result;
            try (var scope = CarpetAsyncCommandResults.open()) {
                root.execute("orange permission finder.block false", source);
                result = scope.resultFuture(source);
            }
            Path file = directory.resolve("config/carpet-org-addition/permission.json");
            java.util.function.BooleanSupplier queued = () -> asyncEntered.isDone();
            drain(actors, queued);
            assertTrue(order.getFirst().startsWith("pool:Paper Async Command Builder Thread Pool"));
            assertFalse(result.isDone());
            assertNull(callback.get());
            assertFalse(java.nio.file.Files.exists(file));
            asyncReleased.complete(null);
            try {
                drain(actors, () -> order.contains("sync"));
            } catch (Throwable failure) {
                throw new AssertionError("sync stage: " + order + " result=" + result + " callback=" + callback.get(), failure);
            }
            assertFalse(order.contains("packet"));
            assertFalse(result.isDone());
            assertFalse(java.nio.file.Files.exists(file));
            syncChild.complete(null);
            try {
                drain(actors, () -> completion.get() != null);
            } catch (Throwable failure) {
                throw new AssertionError("packet stage: " + order + " result=" + result + " callback=" + callback.get(), failure);
            }
            assertTrue(order.contains("labels"));
            assertFalse(result.isDone());
            assertFalse(java.nio.file.Files.exists(file));
            var write = mock(io.netty.channel.ChannelFuture.class);
            when(write.isSuccess()).thenReturn(!fail);
            when(write.cause()).thenReturn(new IllegalStateException("physical permission tree write failed"));
            completion.get().operationComplete(write);
            assertFalse(result.isDone());
            assertFalse(java.nio.file.Files.exists(file));
            physicalChild.complete(null);
            drain(actors, result::isDone);
            assertEquals(fail ? 0 : 5, result.join());
            assertEquals(fail ? "false:0" : "true:5", callback.get());
            assertEquals(!fail, java.nio.file.Files.exists(file));
            verify(commands, never()).sendCommands(any());
            OrgServerPermissions.close(actors.server);
        } finally {
            org.spigotmc.SpigotConfig.tabComplete = tab;
            org.spigotmc.SpigotConfig.sendNamespaced = namespaced;
            bukkitField.set(null, old);
        }
    }

    static void drain(OrgInventoryPersistenceTest.Fixture actors, java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            actors.drain(actors.viewer);
            actors.drain(actors.target);
            actors.owner.set(null);
            Thread.sleep(1);
        }
        assertTrue(done.getAsBoolean());
    }
}
