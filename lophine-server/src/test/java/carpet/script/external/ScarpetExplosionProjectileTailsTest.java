package carpet.script.external;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.EntityHitResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetExplosionProjectileTailsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void realFireballMethodWaitsTypedFalseDamageBeforeOriginalUnconditionalPostEnchant() throws Exception {
        var projectile = mock(LargeFireball.class, CALLS_REAL_METHODS);
        var world = mock(ServerLevel.class);
        var target = mock(LivingEntity.class);
        var owner = mock(LivingEntity.class);
        var sources = mock(DamageSources.class);
        var damage = mock(DamageSource.class);
        doReturn(world).when(projectile).level();
        doReturn(owner).when(projectile).getOwner();
        doReturn(sources).when(projectile).damageSources();
        when(sources.fireball(projectile, owner)).thenReturn(damage);
        var hurt = new CompletableFuture<Boolean>();
        var order = new ArrayList<String>();
        try (var actors = mockStatic(ScarpetExplosionActors.class, CALLS_REAL_METHODS); var enchants = mockStatic(EnchantmentHelper.class)) {
            actors.when(() -> ScarpetExplosionActors.hurt(target, world, damage, 6F)).thenReturn(hurt);
            actors.when(() -> ScarpetExplosionActors.entity(eq(target), any())).thenAnswer(call -> CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(1)).get()));
            enchants.when(() -> EnchantmentHelper.doPostAttackEffects(world, target, damage)).thenAnswer(call -> {
                order.add("actual enchant tail");
                return null;
            });
            var method = LargeFireball.class.getDeclaredMethod("onHitEntity", EntityHitResult.class);
            method.setAccessible(true);
            var actual = ScarpetNativeWork.<Void>observeNative(projectile, () -> {
                try {
                    method.invoke(projectile, new EntityHitResult(target));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                return null;
            });
            assertFalse(actual.isDone());
            assertTrue(order.isEmpty());
            hurt.complete(false);
            assertTrue(actual.isDone());
            assertEquals(List.of("actual enchant tail"), order);
        }
    }

    @Test
    void realSkullUsesTypedTrueHurtThenActualOwnerHealThenFreshSourceAndTargetEffect() throws Exception {
        var projectile = mock(WitherSkull.class, CALLS_REAL_METHODS);
        var world = mock(ServerLevel.class);
        var target = mock(LivingEntity.class);
        var owner = mock(LivingEntity.class);
        var newOwner = mock(LivingEntity.class);
        var sources = mock(DamageSources.class);
        var damage = mock(DamageSource.class);
        doReturn(world).when(projectile).level();
        doReturn(owner).when(projectile).getOwner();
        doReturn(owner).when(projectile).getEffectSource();
        doReturn(sources).when(projectile).damageSources();
        when(sources.witherSkull(projectile, owner)).thenReturn(damage);
        when(target.isAlive()).thenReturn(false);
        when(world.getDifficulty()).thenReturn(net.minecraft.world.Difficulty.NORMAL);
        var hurt = new CompletableFuture<Boolean>();
        var order = new ArrayList<String>();
        doAnswer(call -> {
            order.add("owner heal");
            doReturn(newOwner).when(projectile).getEffectSource();
            return null;
        }).when(owner).heal(eq(5F), eq(org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.WITHER));
        doAnswer(call -> {
            order.add("actual target wither");
            assertSame(newOwner, call.getArgument(1));
            assertEquals(200, ((net.minecraft.world.effect.MobEffectInstance) call.getArgument(0)).getDuration());
            return true;
        }).when(target).addEffect(any(), any(Entity.class), eq(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ATTACK));
        try (var actors = mockStatic(ScarpetExplosionActors.class, CALLS_REAL_METHODS)) {
            actors.when(() -> ScarpetExplosionActors.hurt(target, world, damage, 8F)).thenReturn(hurt);
            actors.when(() -> ScarpetExplosionActors.entity(any(Entity.class), any())).thenAnswer(call -> CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(1)).get()));
            var method = WitherSkull.class.getDeclaredMethod("onHitEntity", EntityHitResult.class);
            method.setAccessible(true);
            var actual = ScarpetNativeWork.<Void>observeNative(projectile, () -> {
                try {
                    method.invoke(projectile, new EntityHitResult(target));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                return null;
            });
            assertFalse(actual.isDone());
            assertTrue(order.isEmpty());
            hurt.complete(true);
            assertTrue(actual.isDone());
            assertEquals(List.of("owner heal", "actual target wither"), order);
        }
    }
}
