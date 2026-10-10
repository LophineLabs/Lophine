package carpet.script.external;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SulfurCubeArchetype;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetSulfurExplosionTailTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void actualFuseWaitsExplosionBeforeDeathEffectsAndDiscard() throws Exception {
        fuse(false);
    }

    @Test
    void actualPrimeCancelResetsFuseAndNeverMarksDeadOrRunsSuccessTail() throws Exception {
        fuse(true);
    }

    private void fuse(boolean cancel) throws Exception {
        var cube = mock(SulfurCube.class, CALLS_REAL_METHODS);
        var world = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        doReturn(world).when(cube).level();
        when(world.getServer()).thenReturn(server);
        doReturn(false).when(cube).isLeashed();
        doReturn(0D).when(cube).getX();
        doReturn(0D).when(cube).getY(anyDouble());
        doReturn(0D).when(cube).getZ();
        doReturn(0).when(cube).getPortalCooldown();
        var data = SulfurCube.class.getDeclaredField("explosionData");
        data.setAccessible(true);
        data.set(cube, Optional.of(new SulfurCubeArchetype.ExplosionData(4, false, 10)));
        var fuse = SulfurCube.class.getDeclaredField("fuse");
        fuse.setAccessible(true);
        fuse.setInt(cube, 0);
        var sync = Entity.class.getDeclaredField("entityData");
        sync.setAccessible(true);
        sync.set(cube, mock(net.minecraft.network.syncher.SynchedEntityData.class));
        doNothing().when(cube).setFuse(anyInt());
        var game = mock(GameRules.class);
        when(world.getGameRules()).thenReturn(game);
        when(game.get(GameRules.TNT_EXPLODES)).thenReturn(true);
        when(game.get(GameRules.MOB_GRIEFING)).thenReturn(true);
        var damage = mock(DamageSource.class);
        var event = mock(org.bukkit.event.entity.ExplosionPrimeEvent.class);
        when(event.isCancelled()).thenReturn(cancel);
        when(event.getRadius()).thenReturn(4F);
        var actualExplosion = new CompletableFuture<Void>();
        var effectTail = new CompletableFuture<Void>();
        var order = new ArrayList<String>();
        var effects = LivingEntity.class.getDeclaredMethod("triggerOnDeathMobEffects", ServerLevel.class, Entity.RemovalReason.class);
        effects.setAccessible(true);
        effects.invoke(doAnswer(call -> {
            order.add("actual death effects");
            ScarpetNativeWork.record(effectTail);
            return null;
        }).when(cube), world, Entity.RemovalReason.KILLED);
        doAnswer(call -> {
            order.add("actual discard");
            return null;
        }).when(cube).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.EXPLODE);
        doAnswer(call -> {
            order.add("actual explosion");
            ScarpetNativeWork.record(actualExplosion);
            return null;
        }).when(world).explode(eq(cube), eq(damage), isNull(), anyDouble(), anyDouble(), anyDouble(), eq(4F), eq(false), eq(Level.ExplosionInteraction.TNT));
        try (var events = mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class); var defaultSource = mockStatic(Explosion.class, CALLS_REAL_METHODS); var actors = mockStatic(ScarpetExplosionActors.class)) {
            events.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.callExplosionPrimeEvent(cube, 4F, false)).thenReturn(event);
            defaultSource.when(() -> Explosion.getDefaultDamageSource(world, cube)).thenReturn(damage);
            actors.when(() -> ScarpetExplosionActors.entity(eq(cube), any())).thenAnswer(call -> CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(1)).get()));
            var tickFuse = SulfurCube.class.getDeclaredMethod("tickFuse");
            tickFuse.setAccessible(true);
            var actual = ScarpetNativeWork.<Void>observeNative(cube, () -> {
                try {
                    tickFuse.invoke(cube);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                return null;
            });
            var dead = LivingEntity.class.getDeclaredField("dead");
            dead.setAccessible(true);
            if (cancel) {
                assertTrue(actual.isDone());
                assertFalse(dead.getBoolean(cube));
                assertTrue(order.isEmpty());
                verify(cube).setFuse(net.minecraft.world.entity.item.PrimedTnt.NO_FUSE);
            } else {
                assertTrue(dead.getBoolean(cube));
                assertEquals(List.of("actual explosion"), order);
                assertFalse(actual.isDone());
                actualExplosion.complete(null);
                assertFalse(actual.isDone());
                assertEquals(List.of("actual explosion", "actual death effects"), order);
                effectTail.complete(null);
                assertTrue(actual.isDone());
                assertEquals(List.of("actual explosion", "actual death effects", "actual discard"), order);
            }
            assertFalse(actual.isCompletedExceptionally());
        }
    }
}
