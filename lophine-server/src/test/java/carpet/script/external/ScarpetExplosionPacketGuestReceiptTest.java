package carpet.script.external;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetExplosionPacketGuestReceiptTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void actualPacketOwnerReceiptWaitsDynamicNativeChildThenGuestReadyStillRetainsFailedParent() throws Exception {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(server);
        var players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        var recipient = mock(ServerPlayer.class);
        var list = PlayerList.class.getField("realPlayers");
        list.setAccessible(true);
        list.set(players, List.of(recipient));
        when(recipient.level()).thenReturn(world);
        when(recipient.distanceToSqr(Vec3.ZERO)).thenReturn(1D);
        recipient.connection = mock(ServerGamePacketListenerImpl.class);
        var explosion = mock(ServerExplosion.class);
        when(explosion.level()).thenReturn(world);
        when(explosion.center()).thenReturn(Vec3.ZERO);
        when(explosion.radius()).thenReturn(4F);
        when(explosion.getDamageSource()).thenReturn(mock(DamageSource.class));
        when(explosion.carpetHitPlayerIds()).thenReturn(Map.of());
        var guest = new CompletableFuture<Void>();
        var nativeChild = new CompletableFuture<Void>();
        doAnswer(call -> {
            ScarpetNativeWork.record(guest);
            ScarpetNativeWork.record(nativeChild);
            return null;
        }).when(recipient.connection).send(any(net.minecraft.network.protocol.Packet.class));
        var global = mock(io.papermc.paper.threadedregions.RegionizedServer.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return null;
        }).when(global).addTask(any());
        var actual = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
        try (var globals = mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class); var ticks = mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)) {
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(global);
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(eq(world), any(net.minecraft.core.BlockPos.class))).thenReturn(true);
            ticks.when(() -> ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(any(net.minecraft.world.entity.Entity.class))).thenReturn(true);
            var original = ScarpetNativeWork.observeNative(null, () -> {
                actual.set(ScarpetExplosionPackets.send(explosion, 7, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, net.minecraft.util.random.WeightedList.of(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE));
                return null;
            });
            verify(global, never()).addTask(any());
            var failure = new IllegalStateException("actual packet guest");
            ScarpetNativeWork.markGuestFailure(failure);
            guest.completeExceptionally(failure);
            assertFalse(actual.get().isDone());
            nativeChild.complete(null);
            actual.get().join();
            assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class, original::join)));
        }
    }
}
