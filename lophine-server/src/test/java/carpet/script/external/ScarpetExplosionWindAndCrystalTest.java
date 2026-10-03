package carpet.script.external;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class ScarpetExplosionWindAndCrystalTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void actualWindBodyRemembersOwnerThenTrueHurtThenEnchantThenExplosion()throws Exception{wind(true);}
 @Test void actualWindFalseHurtSkipsEnchantButStillActuallyExplodes()throws Exception{wind(false);}
 private void wind(boolean result)throws Exception{
  var projectile=mock(AbstractWindCharge.class,CALLS_REAL_METHODS);var world=mock(ServerLevel.class);var target=mock(LivingEntity.class);var owner=mock(LivingEntity.class);var sources=mock(DamageSources.class);var damage=mock(DamageSource.class);
  doReturn(world).when(projectile).level();doReturn(owner).when(projectile).getOwner();doReturn(sources).when(projectile).damageSources();doReturn(Vec3.ZERO).when(projectile).position();when(sources.windCharge(projectile,owner)).thenReturn(damage);
  var hurt=new CompletableFuture<Boolean>();var explosion=new CompletableFuture<Void>();var order=new ArrayList<String>();
  doAnswer(call->{order.add("remember actual owner");return null;}).when(owner).setLastHurtMob(target);
  doAnswer(call->{order.add("actual explosion");ScarpetNativeWork.record(explosion);return null;}).when(projectile).explode(Vec3.ZERO);
  try(var actors=mockStatic(ScarpetExplosionActors.class,CALLS_REAL_METHODS);var enchants=mockStatic(EnchantmentHelper.class)){
   actors.when(()->ScarpetExplosionActors.hurt(target,world,damage,1F)).thenAnswer(call->{order.add("actual target hurt");return hurt;});
   actors.when(()->ScarpetExplosionActors.entity(any(Entity.class),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
   enchants.when(()->EnchantmentHelper.doPostAttackEffects(world,target,damage)).thenAnswer(call->{order.add("actual enchant");return null;});
   var method=AbstractWindCharge.class.getDeclaredMethod("onHitEntity",EntityHitResult.class);method.setAccessible(true);
   var actual=ScarpetNativeWork.<Void>observeNative(projectile,()->{try{method.invoke(projectile,new EntityHitResult(target));}catch(Exception failure){throw new RuntimeException(failure);}return null;});
   assertEquals(List.of("remember actual owner","actual target hurt"),order);assertFalse(actual.isDone());hurt.complete(result);
   assertEquals(result?List.of("remember actual owner","actual target hurt","actual enchant","actual explosion"):List.of("remember actual owner","actual target hurt","actual explosion"),order);
   assertFalse(actual.isDone());explosion.complete(null);assertTrue(actual.isDone());assertFalse(actual.isCompletedExceptionally());
  }
 }
 @Test void actualCrystalWaitsRemovalTailThenExplosionThenDragonNotificationAndSuppressesRepeatPrime()throws Exception{
  var crystal=mock(EndCrystal.class,CALLS_REAL_METHODS);var world=mock(ServerLevel.class);var server=mock(MinecraftServer.class);var source=mock(DamageSource.class);var fight=mock(EnderDragonFight.class);
  doReturn(world).when(crystal).level();doReturn(0D).when(crystal).getX();doReturn(0D).when(crystal).getY();doReturn(0D).when(crystal).getZ();when(world.getServer()).thenReturn(server);when(world.getDragonFight()).thenReturn(fight);doReturn(false).when(crystal).isInvulnerableToBase(source);
  var removed=new CompletableFuture<Void>();var exploded=new CompletableFuture<Void>();var order=new ArrayList<String>();
  doAnswer(call->{order.add("actual remove");ScarpetNativeWork.record(removed);return null;}).when(crystal).remove(eq(Entity.RemovalReason.KILLED),eq(org.bukkit.event.entity.EntityRemoveEvent.Cause.EXPLODE));
  doAnswer(call->{order.add("actual explosion");ScarpetNativeWork.record(exploded);return null;}).when(world).explode(eq(crystal),nullable(DamageSource.class),isNull(),anyDouble(),anyDouble(),anyDouble(),eq(6F),eq(false),eq(net.minecraft.world.level.Level.ExplosionInteraction.BLOCK));
  doAnswer(call->{order.add("actual fight notification");return null;}).when(fight).onCrystalDestroyed(crystal,source);
  var event=mock(org.bukkit.event.entity.ExplosionPrimeEvent.class);when(event.getRadius()).thenReturn(6F);
  try(var events=mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class);var actors=mockStatic(ScarpetExplosionActors.class);var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
   events.when(()->org.bukkit.craftbukkit.event.CraftEventFactory.callExplosionPrimeEvent(crystal,6F,false)).thenReturn(event);
   actors.when(()->ScarpetExplosionActors.entity(eq(crystal),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
   var actual=ScarpetNativeWork.observeNative(crystal,()->crystal.hurtServer(world,source,1F));assertFalse(actual.isDone());assertEquals(List.of("actual remove"),order);
   assertTrue(crystal.hurtServer(world,source,1F));events.verify(()->org.bukkit.craftbukkit.event.CraftEventFactory.callExplosionPrimeEvent(crystal,6F,false),times(1));
   removed.complete(null);assertEquals(List.of("actual remove","actual explosion"),order);assertFalse(actual.isDone());exploded.complete(null);
   assertTrue(actual.join());assertEquals(List.of("actual remove","actual explosion","actual fight notification"),order);assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
  }
 }
}
