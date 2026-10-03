package carpet.script.external;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.effect.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class ScarpetCreeperExplosionTailTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @Test void actualNativeCreeperWaitsExplosionThenCloudSpawnThenDeathEffectsThenDiscard()throws Exception{creeper(false);}
 @Test void actualNativeCreeperPrimeCancelNeverCreatesCloudDeathOrDiscard()throws Exception{creeper(true);}
 private static Object nested(Object owner,String name)throws Exception{var field=owner.getClass().getField(name);var child=mock(field.getType());field.set(owner,child);return child;}
 private void creeper(boolean cancel)throws Exception{
  var creep=mock(Creeper.class,CALLS_REAL_METHODS);var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);doReturn(world).when(creep).level();when(world.getServer()).thenReturn(server);doReturn(false).when(creep).isPowered();doReturn(0D).when(creep).getX();doReturn(0D).when(creep).getY();doReturn(0D).when(creep).getZ();doReturn(List.of(new MobEffectInstance(MobEffects.WIND_CHARGED,100))).when(creep).getActiveEffects();
  var config=mock(io.papermc.paper.configuration.WorldConfiguration.class);var entities=nested(config,"entities");nested(entities,"behavior");when(world.paperConfig()).thenReturn(config);
  var radius=Creeper.class.getDeclaredField("explosionRadius");radius.setAccessible(true);radius.setInt(creep,3);var sync=Entity.class.getDeclaredField("entityData");sync.setAccessible(true);sync.set(creep,mock(net.minecraft.network.syncher.SynchedEntityData.class));
  var event=mock(org.bukkit.event.entity.ExplosionPrimeEvent.class);when(event.isCancelled()).thenReturn(cancel);when(event.getRadius()).thenReturn(3F);var boom=new CompletableFuture<Void>();var cloud=new CompletableFuture<Void>();var death=new CompletableFuture<Void>();var order=new ArrayList<String>();
  doAnswer(call->{order.add("actual explosion");ScarpetNativeWork.record(boom);return null;}).when(world).explode(eq(creep),anyDouble(),anyDouble(),anyDouble(),eq(3F),eq(false),eq(Level.ExplosionInteraction.MOB));
  doAnswer(call->{order.add("actual cloud spawn");ScarpetNativeWork.record(cloud);return true;}).when(world).addFreshEntity(any(AreaEffectCloud.class),eq(org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.EXPLOSION));
  var effects=LivingEntity.class.getDeclaredMethod("triggerOnDeathMobEffects",ServerLevel.class,Entity.RemovalReason.class);effects.setAccessible(true);effects.invoke(doAnswer(call->{order.add("actual death effects");ScarpetNativeWork.record(death);return null;}).when(creep),world,Entity.RemovalReason.KILLED);
  doAnswer(call->{order.add("actual discard");return null;}).when(creep).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.EXPLODE);
  try(var events=mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class);var actors=mockStatic(ScarpetExplosionActors.class);var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);var constructed=mockConstruction(AreaEffectCloud.class)){
   events.when(()->org.bukkit.craftbukkit.event.CraftEventFactory.callExplosionPrimeEvent(creep,3F,false)).thenReturn(event);actors.when(()->ScarpetExplosionActors.entity(eq(creep),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));
   var actual=ScarpetNativeWork.<Void>observeNative(creep,()->{creep.explodeCreeper();return null;});
   if(cancel){assertTrue(actual.isDone());assertTrue(order.isEmpty());assertTrue(constructed.constructed().isEmpty());}
   else{
    assertEquals(List.of("actual explosion"),order);assertFalse(actual.isDone());creep.explodeCreeper();events.verify(()->org.bukkit.craftbukkit.event.CraftEventFactory.callExplosionPrimeEvent(creep,3F,false),times(1));
    boom.complete(null);assertEquals(List.of("actual explosion","actual cloud spawn"),order);assertFalse(actual.isDone());assertEquals(1,constructed.constructed().size());
    cloud.complete(null);assertEquals(List.of("actual explosion","actual cloud spawn","actual death effects"),order);assertFalse(actual.isDone());assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
    death.complete(null);assertEquals(List.of("actual explosion","actual cloud spawn","actual death effects","actual discard"),order);assertTrue(actual.isDone());
   }
   assertFalse(actual.isCompletedExceptionally());assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
  }
 }
}
