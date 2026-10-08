package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.event.bot.BotJoinEvent;
import org.leavesmc.leaves.event.bot.BotRemoveEvent;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgSilentBotMessagesTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    final class Fixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors = new OrgInventoryPersistenceTest.Fixture(directory);
        final OrgInventoryPersistenceTest.Actor actor = actors.actor(ServerBot.class);
        final ServerBot bot = (ServerBot) actor.player();
        final BotList list = mock(BotList.class, CALLS_REAL_METHODS);
        final org.bukkit.plugin.PluginManager plugins = mock(org.bukkit.plugin.PluginManager.class);
        final org.mockito.MockedStatic<org.bukkit.craftbukkit.CraftRegistry> registries = mockStatic(org.bukkit.craftbukkit.CraftRegistry.class);
        final CompletableFuture<Void> eventChild = new CompletableFuture<>(), wire = new CompletableFuture<>();
        final AtomicInteger sends = new AtomicInteger(), events = new AtomicInteger();

        Fixture() throws Exception {
            registries.when(org.bukkit.craftbukkit.CraftRegistry::getMinecraftRegistry).thenReturn(actors.lookup);
            var bukkit = mock(org.bukkit.craftbukkit.CraftServer.class);
            when(bukkit.getPluginManager()).thenReturn(plugins);
            var serverField = net.minecraft.server.MinecraftServer.class.getField("server");
            serverField.setAccessible(true);
            serverField.set(actors.server, bukkit);
            var ownField = BotList.class.getDeclaredField("server");
            ownField.setAccessible(true);
            ownField.set(list, actors.server);
            when(actors.server.getBotList()).thenReturn(list);
            when(bot.getDisplayName()).thenReturn(Component.literal("fake"));
            when(bot.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
            when(bot.getScoreboardName()).thenReturn("fake");
            when(bot.carpetSpawnServer()).thenReturn(actors.server);
            var profile = new com.mojang.authlib.GameProfile(actor.id(), "fake");
            when(bot.getGameProfile()).thenReturn(profile);
            doAnswer(call -> {
                events.incrementAndGet();
                ScarpetNativeWork.record(eventChild);
                var event = call.getArgument(0);
                if (event instanceof BotJoinEvent join)
                    join.joinMessage(net.kyori.adventure.text.Component.text("plugin join"));
                if (event instanceof BotRemoveEvent remove)
                    remove.removeMessage(net.kyori.adventure.text.Component.text("plugin quit"));
                return null;
            }).when(plugins).callEvent(any());
            var players = actors.server.getPlayerList();
            doAnswer(call -> {
                assertSame(bot, actors.owner.get());
                sends.incrementAndGet();
                ScarpetNativeWork.record(wire);
                return null;
            }).when(players).broadcastSystemMessage(any(Component.class), eq(false));
        }

        void until(java.util.function.BooleanSupplier ready) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
                actors.drain(actor);
                actors.drain(actors.viewer);
                actors.owner.set(null);
                Thread.sleep(1);
            }
            assertTrue(ready.getAsBoolean());
        }

        void join(boolean silence) throws Exception {
            var method = BotList.class.getDeclaredMethod("carpetPublishJoinMessage", ServerBot.class, boolean.class);
            method.setAccessible(true);
            method.invoke(list, bot, silence);
        }

        void leave(BotRemoveEvent event) throws Exception {
            var method = BotList.class.getDeclaredMethod("carpetPublishLeaveMessage", ServerBot.class, BotRemoveEvent.class);
            method.setAccessible(true);
            method.invoke(list, bot, event);
        }

        public void close() {
            eventChild.complete(null);
            wire.complete(null);
            for (int i = 0; i < 20; i++) {
                actors.drain(actor);
                actors.drain(actors.viewer);
            }
            registries.close();
            actors.close();
        }
    }

    @Test
    void actualPlacementAdmissionCarriesSilentJoinUntilAsyncNativeJoinBody() throws Exception {
        for (boolean silence : new boolean[]{false, true})
            try (var f = new Fixture()) {
                var consumed = new AtomicReference<Boolean>();
                doAnswer(call -> {
                    assertSame(f.bot, call.getArgument(0));
                    return f.bot;
                }).when(f.list).placeNewBot(eq(f.bot), any(), any(), isNull());
                OrgNativePlayerMessages.place(f.actors.server, f.bot, f.bot.level(), new org.bukkit.Location(null, 1, 64, 2), null, silence);
                var parent = OrgMenuNativeEffects.run(f.bot, () -> {
                    boolean value = OrgNativePlayerMessages.consumeJoin(f.bot);
                    consumed.set(value);
                    try {
                        f.join(value);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    return true;
                });
                f.until(() -> f.events.get() == 1 || parent.isDone());
                if (parent.isDone()) parent.join();
                assertEquals(silence, consumed.get());
                assertEquals(silence ? 0 : 1, f.sends.get());
                assertFalse(parent.isDone());
                f.eventChild.complete(null);
                if (!silence) {
                    assertFalse(parent.isDone());
                    f.wire.complete(null);
                }
                f.until(parent::isDone);
                assertTrue(parent.join());
                assertFalse(OrgNativePlayerMessages.consumeJoin(f.bot));
            }
    }

    @Test
    void actualJoinPublicationFailureKeepsNativeParentFailed() throws Exception {
        try (var f = new Fixture()) {
            var parent = OrgMenuNativeEffects.run(f.bot, () -> {
                try {
                    f.join(false);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return true;
            });
            f.until(() -> f.sends.get() == 1 || parent.isDone());
            if (parent.isDone()) parent.join();
            f.eventChild.complete(null);
            f.wire.completeExceptionally(new IllegalStateException("actual join publication"));
            f.until(parent::isDone);
            assertThrows(CompletionException.class, parent::join);
        }
    }

    @Test
    void nativeSilentQuitRetainsItsEventChildrenAndCallerCancellationCannotEraseTheFlag() throws Exception {
        try (var f = new Fixture()) {
            var physical = new CompletableFuture<Boolean>();
            var event = new BotRemoveEvent(f.bot.getBukkitEntity(), BotRemoveEvent.RemoveReason.COMMAND, null, net.kyori.adventure.text.Component.text("quit"), true);
            var caller = OrgNativePlayerMessages.remove(f.bot, true, () -> physical);
            assertTrue(caller.cancel(false));
            var parent = OrgMenuNativeEffects.run(f.bot, () -> {
                OrgNativePlayerMessages.prepared(f.bot, event);
                try {
                    f.plugins.callEvent(event);
                    f.leave(event);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return true;
            });
            f.until(() -> f.events.get() == 1);
            assertEquals(0, f.sends.get());
            assertFalse(parent.isDone());
            assertFalse(physical.isDone());
            f.eventChild.complete(null);
            f.until(parent::isDone);
            assertTrue(parent.join());
            physical.complete(true);
            assertTrue(caller.isCancelled());
            var ordinary = new BotRemoveEvent(f.bot.getBukkitEntity(), BotRemoveEvent.RemoveReason.COMMAND, null, net.kyori.adventure.text.Component.text("next"), true);
            OrgNativePlayerMessages.prepared(f.bot, ordinary);
            assertFalse(OrgNativePlayerMessages.consumeLeave(ordinary));
        }
    }

    @Test
    void ordinaryQuitPublishesActualPluginMessageAndWaitsNativePacketChildren() throws Exception {
        try (var f = new Fixture()) {
            var event = new BotRemoveEvent(f.bot.getBukkitEntity(), BotRemoveEvent.RemoveReason.COMMAND, null, net.kyori.adventure.text.Component.text("quit"), true);
            OrgNativePlayerMessages.prepared(f.bot, event);
            var parent = OrgMenuNativeEffects.run(f.bot, () -> {
                try {
                    f.plugins.callEvent(event);
                    f.leave(event);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return true;
            });
            f.until(() -> f.sends.get() == 1);
            assertFalse(parent.isDone());
            f.eventChild.complete(null);
            assertFalse(parent.isDone());
            f.wire.complete(null);
            f.until(parent::isDone);
            assertTrue(parent.join());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void actualRemovalFinishKeepsTabOnlyForTheRegisteredReloginUuid() throws Exception {
        try (var f = new Fixture()) {
            var constructor = OrgPlayerManager.class.getDeclaredConstructor(net.minecraft.server.MinecraftServer.class, net.minecraft.commands.CommandBuildContext.class);
            constructor.setAccessible(true);
            var manager = constructor.newInstance(f.actors.server, null);
            var managersField = OrgPlayerManager.class.getDeclaredField("MANAGERS");
            managersField.setAccessible(true);
            var managers = (java.util.Map<net.minecraft.server.MinecraftServer, OrgPlayerManager>) managersField.get(null);
            managers.put(f.actors.server, manager);
            try {
                var jobsField = OrgPlayerManager.class.getDeclaredField("jobs");
                jobsField.setAccessible(true);
                var jobs = (java.util.Map<String, Object>) jobsField.get(manager);
                var jobType = Class.forName("fun.bm.lophine.carpet.OrgPlayerManager$Job");
                var jobConstructor = jobType.getDeclaredConstructors()[0];
                jobConstructor.setAccessible(true);
                var profile = new com.google.gson.JsonObject();
                profile.addProperty("_lophine_uuid", f.bot.getUUID().toString());
                var job = jobConstructor.newInstance("fake", "relogin", mock(net.minecraft.commands.CommandSourceStack.class), 100L, 30, profile);
                jobs.put("relogin:fake", job);
                var data = mock(io.papermc.paper.threadedregions.RegionizedWorldData.class);
                var connectionList = io.papermc.paper.threadedregions.RegionizedWorldData.class.getField("connections");
                connectionList.setAccessible(true);
                connectionList.set(data, mock(connectionList.getType()));
                when(f.bot.level().getCurrentWorldData()).thenReturn(data);
                when(f.bot.level().players()).thenReturn(java.util.List.of());
                f.bot.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
                var botsField = BotList.class.getField("bots");
                botsField.setAccessible(true);
                botsField.set(f.list, new java.util.concurrent.CopyOnWriteArrayList<ServerBot>(java.util.List.of(f.bot)));
                var registrationsField = BotList.class.getDeclaredField("carpetRegistrations");
                registrationsField.setAccessible(true);
                registrationsField.set(f.list, new CarpetBotRegistrations<ServerBot>());
                var finish = BotList.class.getDeclaredMethod("carpetFinishBotRemoval", ServerBot.class, BotRemoveEvent.class);
                finish.setAccessible(true);
                var event = new BotRemoveEvent(f.bot.getBukkitEntity(), BotRemoveEvent.RemoveReason.COMMAND, null, net.kyori.adventure.text.Component.text("quit"), true);
                var physical = new CompletableFuture<Boolean>();
                OrgNativePlayerMessages.remove(f.bot, true, () -> physical);
                OrgNativePlayerMessages.prepared(f.bot, event);
                assertTrue(OrgNativePlayerMessages.keepTab(event));
                f.actors.owner.set(f.bot);
                assertTrue((Boolean) finish.invoke(f.list, f.bot, event));
                verify(f.bot, never()).removeTab();
                assertEquals(0, f.sends.get());
                physical.complete(true);
                jobs.clear();
                var ordinary = new BotRemoveEvent(f.bot.getBukkitEntity(), BotRemoveEvent.RemoveReason.COMMAND, null, null, true);
                OrgNativePlayerMessages.prepared(f.bot, ordinary);
                assertFalse(OrgNativePlayerMessages.keepTab(ordinary));
                assertTrue((Boolean) finish.invoke(f.list, f.bot, ordinary));
                verify(f.bot).removeTab();
            } finally {
                managers.remove(f.actors.server, manager);
            }
        }
    }

    @Test
    void actualDelayedLoginJobUsesTheOriginalVisibleJoinMode() throws Exception {
        try (var f = new Fixture()) {
            var constructor = OrgPlayerManager.class.getDeclaredConstructor(net.minecraft.server.MinecraftServer.class, net.minecraft.commands.CommandBuildContext.class);
            constructor.setAccessible(true);
            var manager = spy(constructor.newInstance(f.actors.server, null));
            var source = mock(net.minecraft.commands.CommandSourceStack.class);
            var profile = new com.google.gson.JsonObject();
            var jobType = Class.forName("fun.bm.lophine.carpet.OrgPlayerManager$Job");
            var jobConstructor = jobType.getDeclaredConstructors()[0];
            jobConstructor.setAccessible(true);
            var job = jobConstructor.newInstance("fake", "login", source, 0L, 0, profile);
            doReturn(CompletableFuture.completedFuture(true)).when(manager).spawn(source, "fake", profile, false);
            var execute = OrgPlayerManager.class.getDeclaredMethod("executeJob", jobType, long.class);
            execute.setAccessible(true);
            execute.invoke(manager, job, 1L);
            verify(manager).spawn(source, "fake", profile, false);
            verify(manager, never()).spawn(source, "fake", profile, true);
        }
    }
}
