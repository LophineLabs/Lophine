package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket;
import net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TisTagQueryNativeTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var config = new io.papermc.paper.configuration.GlobalConfiguration();
        config.misc = config.new Misc();
        try (var configs = mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)) {
            configs.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);
            Class.forName("net.minecraft.network.Connection");
        }
    }

    private boolean previous;
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final ServerLevel world = mock(ServerLevel.class);
    private final ServerPlayer player = mock(ServerPlayer.class);
    private final Entity target = mock(Entity.class);
    private final Connection connection = mock(Connection.class);
    private final PermissionSet permissions = mock(PermissionSet.class);
    private final ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class, CALLS_REAL_METHODS);
    private final AtomicReference<ClientboundTagQueryPacket> packet = new AtomicReference<>();
    private final AtomicReference<io.netty.channel.ChannelFutureListener> receipt = new AtomicReference<>();
    private final AtomicReference<io.netty.channel.ChannelFutureListener> closed = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        previous = GeneralCompatConfig.debugNbtQueryNoPermission;
        GeneralCompatConfig.debugNbtQueryNoPermission = true;
        listener.player = player;
        field(listener, ServerCommonPacketListenerImpl.class, "server", server);
        field(listener, ServerCommonPacketListenerImpl.class, "connection", connection);
        when(world.getServer()).thenReturn(server);
        when(player.level()).thenReturn(world);
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        when(player.permissions()).thenReturn(permissions);
        when(target.level()).thenReturn(world);
        when(target.blockPosition()).thenReturn(BlockPos.ZERO);
        when(target.problemPath()).thenReturn(() -> "target");
        when(target.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(world.getEntity(42)).thenReturn(target);
        when(connection.isConnected()).thenReturn(true);
        connection.channel = mock(io.netty.channel.Channel.class);
        var closeFuture = mock(io.netty.channel.ChannelFuture.class);
        when(connection.channel.closeFuture()).thenReturn(closeFuture);
        when(closeFuture.addListener(any(io.netty.channel.ChannelFutureListener.class))).thenAnswer(call -> {
            closed.set(call.getArgument(0));
            return closeFuture;
        });
        doAnswer(call -> {
            ValueOutput output = call.getArgument(0);
            output.putString("actual", "original");
            return null;
        }).when(target).saveWithoutId(any(ValueOutput.class));
        doAnswer(call -> {
            packet.set(call.getArgument(0));
            receipt.set(call.getArgument(1));
            return null;
        }).when(listener).send(any(ClientboundTagQueryPacket.class), any(io.netty.channel.ChannelFutureListener.class));
    }

    @AfterEach
    void restore() {
        GeneralCompatConfig.debugNbtQueryNoPermission = previous;
    }

    private static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        f.set(object, value);
    }

    private org.mockito.MockedStatic<TickThread> owner() {
        var ticks = mockStatic(TickThread.class);
        ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
        ticks.when(() -> TickThread.isTickThreadFor(any(ServerLevel.class), any(BlockPos.class))).thenReturn(true);
        return ticks;
    }

    private CompletableFuture<Void> entityQuery() {
        return ScarpetNativeWork.observeNative(player, () -> {
            listener.handleEntityTagQuery(new ServerboundEntityTagQueryPacket(17, 42));
            return null;
        });
    }

    private void sent(boolean success) throws Exception {
        var network = mock(io.netty.channel.ChannelFuture.class);
        when(network.isSuccess()).thenReturn(success);
        when(network.cause()).thenReturn(success ? null : new IllegalStateException("network"));
        receipt.get().operationComplete(network);
    }

    @Test
    void realEntityHandlerReadsPermissionSerializesOriginalTargetAndWaitsNetworkReceipt() throws Exception {
        try (var ticks = owner(); var packets = mockStatic(PacketUtils.class)) {
            var actual = entityQuery();
            verify(permissions).hasPermission(Permissions.COMMANDS_GAMEMASTER);
            verify(target).saveWithoutId(any(ValueOutput.class));
            assertEquals(17, packet.get().getTransactionId());
            assertEquals("original", packet.get().getTag().getStringOr("actual", ""));
            assertFalse(actual.isDone());
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            sent(true);
            actual.join();
            ScarpetNativeWork.whenIdle(server).join();
        }
    }

    @Test
    void realSaveNativeChildrenFinishBeforeSenderAndReporterClosesAfterOriginalSendCall() throws Exception {
        var child = new CompletableFuture<Void>();
        doAnswer(call -> {
            ((ValueOutput) call.getArgument(0)).putString("actual", "original");
            ScarpetNativeWork.record(child);
            return null;
        }).when(target).saveWithoutId(any(ValueOutput.class));
        try (var ticks = owner(); var packets = mockStatic(PacketUtils.class); var reporters = mockConstruction(net.minecraft.util.ProblemReporter.ScopedCollector.class)) {
            var actual = entityQuery();
            assertNull(packet.get());
            verify(reporters.constructed().getFirst(), never()).close();
            child.complete(null);
            assertNotNull(packet.get());
            verify(reporters.constructed().getFirst()).close();
            assertFalse(actual.isDone());
            sent(true);
            actual.join();
        }
    }

    @Test
    void guestOnlySerializationFailureKeepsRawParentAndOriginalNativeTagAfterTrueChild() throws Exception {
        var guest = new CompletableFuture<Void>();
        var child = new CompletableFuture<Void>();
        doAnswer(call -> {
            ((ValueOutput) call.getArgument(0)).putString("actual", "original");
            ScarpetNativeWork.record(guest);
            ScarpetNativeWork.record(child);
            return null;
        }).when(target).saveWithoutId(any(ValueOutput.class));
        try (var ticks = owner(); var packets = mockStatic(PacketUtils.class)) {
            var raw = entityQuery();
            Throwable failure = new IllegalStateException("guest");
            Method mark = ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure", Throwable.class);
            mark.setAccessible(true);
            mark.invoke(null, failure);
            guest.completeExceptionally(failure);
            assertNull(packet.get());
            child.complete(null);
            assertEquals("original", packet.get().getTag().getStringOr("actual", ""));
            sent(true);
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, raw::join)));
        }
    }

    @Test
    void trueSerializationFailureBlocksSendButClosesActualReporter() throws Exception {
        var child = new CompletableFuture<Void>();
        doAnswer(call -> {
            ScarpetNativeWork.record(child);
            return null;
        }).when(target).saveWithoutId(any(ValueOutput.class));
        try (var ticks = owner(); var packets = mockStatic(PacketUtils.class); var reporters = mockConstruction(net.minecraft.util.ProblemReporter.ScopedCollector.class)) {
            var raw = entityQuery();
            child.completeExceptionally(new IllegalStateException("native"));
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, raw::join)));
            assertNull(packet.get());
            verify(reporters.constructed().getFirst()).close();
            ScarpetNativeWork.whenIdle(server).handle((value, failure) -> null).join();
        }
    }

    @Test
    void realBlockHandlerRetainsOriginalWorldAndTagIdentityAfterPlayerChangesWorld() throws Exception {
        BlockEntity block = mock(BlockEntity.class);
        CompoundTag tag = new CompoundTag();
        tag.putString("block", "original");
        ServerLevel changed = mock(ServerLevel.class);
        when(changed.getServer()).thenReturn(server);
        when(world.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(world.getBlockEntity(BlockPos.ZERO)).thenReturn(block);
        when(block.saveWithoutMetadata(RegistryAccess.EMPTY)).thenAnswer(call -> {
            when(player.level()).thenReturn(changed);
            return tag;
        });
        try (var ticks = owner(); var packets = mockStatic(PacketUtils.class)) {
            var raw = ScarpetNativeWork.observeNative(player, () -> {
                listener.handleBlockEntityTagQuery(new ServerboundBlockEntityTagQueryPacket(23, BlockPos.ZERO));
                return null;
            });
            assertSame(tag, packet.get().getTag());
            assertEquals(23, packet.get().getTransactionId());
            verify(changed, never()).getBlockEntity(any());
            sent(true);
            raw.join();
        }
    }

    @Test
    void disabledPermissionAndAbsentEntityKeepOriginalNoReplyBehavior() throws Exception {
        try (var ticks = owner(); var packets = mockStatic(PacketUtils.class)) {
            GeneralCompatConfig.debugNbtQueryNoPermission = false;
            entityQuery().join();
            verify(permissions).hasPermission(Permissions.COMMANDS_GAMEMASTER);
            verify(world, never()).getEntity(anyInt());
            assertNull(packet.get());
            GeneralCompatConfig.debugNbtQueryNoPermission = true;
            when(world.getEntity(42)).thenReturn(null);
            entityQuery().join();
            assertNull(packet.get());
        }
    }

    @Test
    void realNetworkFailureCompletesNativeFailureAndDoesNotLeakGlobalWork() throws Exception {
        try (var ticks = owner(); var packets = mockStatic(PacketUtils.class)) {
            var raw = entityQuery();
            sent(false);
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, raw::join)));
            ScarpetNativeWork.whenIdle(server).handle((value, failure) -> null).join();
        }
    }

    @Test
    void realChannelCloseBeforeSendReceiptDoesNotLeakActualNativeQuery() throws Exception {
        try (var ticks = owner(); var packets = mockStatic(PacketUtils.class)) {
            var raw = entityQuery();
            assertFalse(raw.isDone());
            closed.get().operationComplete(mock(io.netty.channel.ChannelFuture.class));
            assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, raw::join)));
            ScarpetNativeWork.whenIdle(server).handle((value, failure) -> null).join();
        }
    }

    @Test
    void realForeignSchedulersSerializeTargetThenSendOnOriginalPlayerOwner() throws Exception {
        var running = new AtomicReference<Entity>();
        var queue = new ArrayDeque<Runnable>();
        for (Entity actor : List.of(target, player)) {
            org.bukkit.craftbukkit.entity.CraftEntity craft = actor == player ? mock(org.bukkit.craftbukkit.entity.CraftPlayer.class) : mock(org.bukkit.craftbukkit.entity.CraftEntity.class);
            when(actor.getBukkitEntity()).thenReturn(craft);
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            field(craft, org.bukkit.craftbukkit.entity.CraftEntity.class, "taskScheduler", scheduler);
            when(scheduler.schedule(any(), any(), eq(1L))).thenAnswer(call -> {
                Consumer<Entity> task = call.getArgument(0);
                queue.add(() -> {
                    running.set(actor);
                    task.accept(actor);
                    running.set(null);
                });
                return true;
            });
        }
        doAnswer(call -> {
            assertSame(target, running.get());
            ((ValueOutput) call.getArgument(0)).putString("actual", "original");
            return null;
        }).when(target).saveWithoutId(any(ValueOutput.class));
        doAnswer(call -> {
            assertSame(player, running.get());
            packet.set(call.getArgument(0));
            receipt.set(call.getArgument(1));
            return null;
        }).when(listener).send(any(ClientboundTagQueryPacket.class), any(io.netty.channel.ChannelFutureListener.class));
        try (var ticks = mockStatic(TickThread.class); var packets = mockStatic(PacketUtils.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> running.get() == call.getArgument(0));
            var raw = entityQuery();
            assertNull(packet.get());
            while (!queue.isEmpty()) queue.remove().run();
            assertNotNull(packet.get());
            assertFalse(raw.isDone());
            sent(true);
            raw.join();
        }
    }
}
