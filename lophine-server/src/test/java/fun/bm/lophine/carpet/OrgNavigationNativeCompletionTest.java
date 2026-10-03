package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetEntityIndex;
import carpet.script.external.ScarpetNativeWork;
import com.mojang.brigadier.CommandDispatcher;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class OrgNavigationNativeCompletionTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }
    private static CommandSourceStack source(OrgInventoryPersistenceTest.Fixture fixture) throws Exception {
        var result = mock(CommandSourceStack.class); when(result.getServer()).thenReturn(fixture.server);
        when(result.getEntity()).thenReturn(fixture.viewer.player()); when(result.getPlayerOrException()).thenReturn(fixture.viewer.player());
        var observerWorld = fixture.viewer.player().level(); var targetWorld = fixture.target.player().level();
        when(result.getLevel()).thenReturn(observerWorld); when(result.callback()).thenReturn(CommandResultCallback.EMPTY);
        when(fixture.viewer.player().createCommandSourceStack()).thenReturn(result);
        for (var actor : java.util.List.of(fixture.viewer, fixture.target)) {
            var player = actor.player(); when(player.blockPosition()).thenReturn(new BlockPos(actor == fixture.viewer ? -23 : 37, 73, 37));
            when(player.getEyePosition()).thenAnswer(call -> { assertSame(player, fixture.owner.get()); return new Vec3(actor == fixture.viewer ? -23 : 37, 74.6, 37); });
            when(player.getName()).thenAnswer(call -> { assertSame(player, fixture.owner.get()); return Component.literal(actor == fixture.viewer ? "observer" : "target"); });
            when(player.getDisplayName()).thenAnswer(call -> { assertSame(player, fixture.owner.get()); return Component.literal(actor == fixture.viewer ? "observer" : "target"); });
            when(player.level().dimension()).thenReturn(Level.OVERWORLD); player.connection = mock(ServerGamePacketListenerImpl.class);
        }
        var worlds = java.util.List.of(observerWorld, targetWorld);
        when(fixture.server.getAllLevels()).thenReturn(worlds);
        return result;
    }
    private static int invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = OrgNavigation.class.getDeclaredMethod(name, parameters); method.setAccessible(true); return (int) method.invoke(null, arguments);
    }

    @Test void registeredStopCommandWaitsItsRealOwnerPacketAndDynamicChildrenDespiteCallerCancellation() throws Exception {
        String old = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate;
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory); var scope = CarpetAsyncCommandResults.open()) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate = "true";
            var source = source(fixture); var child = new CompletableFuture<Void>(); var callbacks = new AtomicInteger();
            when(source.callback()).thenReturn((success, count) -> { assertTrue(success); assertEquals(1, count); callbacks.incrementAndGet(); });
            doAnswer(call -> { assertSame(fixture.viewer.player(), fixture.owner.get()); ScarpetNativeWork.record(child); return null; }).when(fixture.viewer.player().connection).send(any(Packet.class));
            var dispatcher = new CommandDispatcher<CommandSourceStack>(); OrgNavigation.register(dispatcher);
            var parent = ScarpetNativeWork.observeNative(null, () -> {
                try { return dispatcher.execute("navigate stop", source); } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            var view = scope.resultFuture(source); var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertTrue(view.cancel(false)); assertFalse(parent.isDone()); assertFalse(idle.isDone()); assertEquals(0, callbacks.get());
            fixture.drain(fixture.viewer); assertFalse(parent.isDone()); assertEquals(0, callbacks.get());
            child.complete(null); parent.join(); idle.join(); assertEquals(1, callbacks.get());
        } finally { fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate = old; }
    }

    @Test void uuidUsesPublishedIdentityThenRealTargetAndObserverActorsBeforeFeedbackCompletion() throws Exception {
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var scope = CarpetAsyncCommandResults.open();
             var global = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)) {
            global.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);
            var source = source(fixture); var target = fixture.target.player(); var sampled = new CompletableFuture<Void>(); var feedback = new CompletableFuture<Void>();
            doAnswer(call -> { assertSame(target, fixture.owner.get()); ScarpetNativeWork.record(sampled); return Component.literal("target"); }).when(target).getName();
            doAnswer(call -> { assertSame(fixture.viewer.player(), fixture.owner.get()); ScarpetNativeWork.record(feedback); return null; }).when(source).sendSuccess(any(), eq(false));
            fixture.owner.set(target); ScarpetEntityIndex.added(target.level(), target); fixture.owner.set(null);
            try {
                var parent = ScarpetNativeWork.observeNative(null, () -> {
                    try { return invoke("uuid", new Class<?>[]{CommandSourceStack.class, java.util.UUID.class}, source, target.getUUID()); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                });
                var result = scope.resultFuture(source); fixture.drain(fixture.target); assertFalse(result.isDone()); assertFalse(parent.isDone());
                sampled.complete(null); fixture.drain(fixture.viewer); assertFalse(result.isDone()); feedback.complete(null);
                assertEquals(1, result.join()); parent.join(); verify(target.level(), never()).getEntity(any(java.util.UUID.class));
            } finally { fixture.owner.set(fixture.viewer.player()); OrgNavigation.disconnected(fixture.viewer.player()); ScarpetEntityIndex.close(target.level()); }
        }
    }

    @Test void recurringSamplingHasAnIndependentReceiptAndCannotReuseTheCompletedCommandParent() throws Exception {
        String old = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate;
        try (var fixture = new OrgInventoryPersistenceTest.Fixture(directory, true); var scope = CarpetAsyncCommandResults.open()) {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate = "true";
            var source = source(fixture); var target = fixture.target.player();
            var original = ScarpetNativeWork.observeNative(null, () -> {
                try { return invoke("entity", new Class<?>[]{CommandSourceStack.class, Entity.class, boolean.class}, source, target, true); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            fixture.drain(fixture.target); fixture.drain(fixture.viewer); assertEquals(1, scope.resultFuture(source).join()); original.join();
            var sampleChild = new CompletableFuture<Void>(); var packetChild = new CompletableFuture<Void>(); var packets = new AtomicInteger();
            doAnswer(call -> { assertSame(target, fixture.owner.get()); ScarpetNativeWork.record(sampleChild); return Component.literal("changed"); }).when(target).getName();
            doAnswer(call -> { assertSame(fixture.viewer.player(), fixture.owner.get()); packets.incrementAndGet(); ScarpetNativeWork.record(packetChild); return null; }).when(fixture.viewer.player().connection).send(any(Packet.class));
            fixture.owner.set(fixture.viewer.player()); OrgNavigation.tick(fixture.viewer.player()); var idle = ScarpetNativeWork.whenIdle(fixture.server);
            fixture.drain(fixture.target); assertTrue(original.isDone()); assertEquals(0, packets.get()); assertFalse(idle.isDone());
            sampleChild.complete(null); fixture.drain(fixture.viewer); assertEquals(2, packets.get()); assertFalse(idle.isDone());
            packetChild.complete(null); idle.join(); OrgNavigation.disconnected(fixture.viewer.player());
        } finally { fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandNavigate = old; }
    }
}
