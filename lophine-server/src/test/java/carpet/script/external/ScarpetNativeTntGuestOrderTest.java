package carpet.script.external;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.gamerules.*;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class ScarpetNativeTntGuestOrderTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
 @Test void actualNativeTntFuseWaitsExplosionPacketsBeforeDiscardThenOnRemovedCompletes() throws Exception {
  boolean previous=fun.bm.lophine.config.modules.fixes.VanillaLikeExperienceConfig.enabled;fun.bm.lophine.config.modules.fixes.VanillaLikeExperienceConfig.enabled=true;
  var server=mock(MinecraftServer.class);var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(server);var rules=mock(GameRules.class);when(world.getGameRules()).thenReturn(rules);when(rules.get(GameRules.TNT_EXPLODES)).thenReturn(true);
  var paper=mock(io.papermc.paper.configuration.WorldConfiguration.class);var fixes=io.papermc.paper.configuration.WorldConfiguration.class.getField("fixes");Object fix=mock(fixes.getType());fixes.set(paper,fix);fixes.getType().getField("tntEntityHeightNerf").set(fix,io.papermc.paper.configuration.type.number.IntOr.Disabled.DISABLED);when(world.paperConfig()).thenReturn(paper);
  var tnt=mock(PrimedTnt.class,call->call.getMethod().getName().equals("getAirDrag")?1F:Set.of("applyGravity","move","applyEffectsFromBlocks","setFuse","setDeltaMovement","setRequiresPrecisePosition").contains(call.getMethod().getName())?null:CALLS_REAL_METHODS.answer(call));
  doReturn(world).when(tnt).level();doReturn(Vec3.ZERO).when(tnt).getDeltaMovement();doReturn(1).when(tnt).getFuse();doReturn(0D).when(tnt).getX();doReturn(0D).when(tnt).getZ();doReturn(0D).when(tnt).getY(anyDouble());
  var explosive=mock(org.bukkit.craftbukkit.entity.CraftTNTPrimed.class);doReturn(explosive).when(tnt).getBukkitEntity();var prime=mock(org.bukkit.event.entity.ExplosionPrimeEvent.class);when(prime.getRadius()).thenReturn(4F);var damage=mock(DamageSource.class);
  var guest=new CompletableFuture<Void>();var packets=new CompletableFuture<Void>();var removed=new CompletableFuture<Void>();var order=new ArrayList<String>();doAnswer(call->{order.add("actual discard/ON_REMOVED head");ScarpetNativeWork.record(removed);return null;}).when(tnt).discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.EXPLODE);
  doAnswer(call->{order.add("actual EXP");ScarpetNativeWork.record(guest);ScarpetNativeWork.record(packets);return null;}).when(world).explode(eq(tnt),eq(damage),nullable(ExplosionDamageCalculator.class),anyDouble(),anyDouble(),anyDouble(),eq(4F),eq(false),eq(Level.ExplosionInteraction.TNT));
  try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);var actors=mockStatic(ScarpetExplosionActors.class);var factory=mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class);var explosions=mockStatic(Explosion.class)){
   actors.when(()->ScarpetExplosionActors.entity(eq(tnt),any())).thenAnswer(call->CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(1)).get()));factory.when(()->org.bukkit.craftbukkit.event.CraftEventFactory.callExplosionPrimeEvent(explosive)).thenReturn(prime);explosions.when(()->Explosion.getDefaultDamageSource(world,tnt)).thenReturn(damage);
   var original=ScarpetNativeWork.observeNative(tnt,()->{tnt.tick();return null;});if(original.isCompletedExceptionally())original.join();assertEquals(List.of("actual EXP"),order);var failure=new IllegalStateException("actual guest callback failure");ScarpetNativeWork.markGuestFailure(failure);guest.completeExceptionally(failure);assertEquals(List.of("actual EXP"),order);packets.complete(null);assertEquals(List.of("actual EXP","actual discard/ON_REMOVED head"),order);assertFalse(original.isDone());assertTrue(ScarpetExplosionContinuations.pending(tnt));removed.complete(null);assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,original::join)));assertFalse(ScarpetExplosionContinuations.pending(tnt));
  }finally{fun.bm.lophine.config.modules.fixes.VanillaLikeExperienceConfig.enabled=previous;}
 }
}
