package fun.bm.lophine.carpet;
import carpet.script.external.*;
import fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig;
import java.lang.reflect.*;import java.nio.file.Path;import java.util.concurrent.*;import java.util.function.Consumer;
import net.minecraft.world.InteractionResult;import net.minecraft.world.InteractionHand;import net.minecraft.world.entity.*;import net.minecraft.world.entity.decoration.ArmorStand;import net.minecraft.world.entity.vehicle.boat.AbstractBoat;import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;import net.minecraft.world.item.*;import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
public class AmsFakeInteractNativeTest {
 @BeforeAll static void boot()throws Exception{AmsNativeManagementTest.bootstrap();}
 @TempDir Path directory;
 @AfterEach void reset(){FakePlayerCompatConfig.fakePlayerInteractLikeClient=false;}
 private void target(AmsNativeManagementTest.Fixture f,Entity entity)throws Exception{
  when(entity.level()).thenReturn(f.world);when(entity.blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);var craft=entity instanceof LivingEntity?mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class):mock(org.bukkit.craftbukkit.entity.CraftEntity.class);when(entity.getBukkitEntity()).thenReturn(craft);var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);var field=org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");field.setAccessible(true);field.set(craft,scheduler);when(scheduler.schedule(any(),any(),anyLong())).thenAnswer(call->{Consumer<Entity> action=call.getArgument(0);f.tasks.add(()->{Entity before=f.current;f.current=entity;try{action.accept(entity);}finally{f.current=before;}});return true;});
 }
 @SuppressWarnings("unchecked")private CompletableFuture<InteractionResult> invoke(AmsNativeManagementTest.Fixture f,Entity entity,boolean at){
  try{var method=CarpetPlayerActionPack.class.getDeclaredMethod(at?"carpetInteractAt":"carpetInteractOn",at?new Class<?>[]{Entity.class,net.minecraft.server.level.ServerPlayer.class,InteractionHand.class,Vec3.class}:new Class<?>[]{net.minecraft.server.level.ServerPlayer.class,Entity.class,InteractionHand.class,Vec3.class});method.setAccessible(true);return (CompletableFuture<InteractionResult>)method.invoke(null,at?new Object[]{entity,f.sourcePlayer,InteractionHand.MAIN_HAND,Vec3.ZERO}:new Object[]{f.sourcePlayer,entity,InteractionHand.MAIN_HAND,Vec3.ZERO});}catch(InvocationTargetException failure){throw new RuntimeException(failure.getCause());}catch(Exception failure){throw new RuntimeException(failure);}
 }
 private record Actual(CompletableFuture<Integer> raw,CompletableFuture<InteractionResult> result){}
 private Actual actual(AmsNativeManagementTest.Fixture f,Entity entity,boolean at){var result=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<InteractionResult>>();var raw=ScarpetNativeWork.observeNative(f.sourcePlayer,()->{var typed=CarpetNativeActionContext.with(f.sourcePlayer,()->invoke(f,entity,at));result.set(typed);ScarpetNativeWork.record(typed);return 17;});return new Actual(raw,result.get());}
 @Test void armorResultWaitsOriginalNativeCallThenActualMarkerOwnerThenActualPlayerItemAndSpectator()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   FakePlayerCompatConfig.fakePlayerInteractLikeClient=true;var stand=mock(ArmorStand.class);target(f,stand);var child=new CompletableFuture<Void>();when(stand.interact(f.sourcePlayer,InteractionHand.MAIN_HAND,Vec3.ZERO)).thenAnswer(call->{f.order.add("original");ScarpetNativeWork.record(child);return InteractionResult.SUCCESS;});when(stand.isMarker()).thenAnswer(call->{assertSame(stand,f.current);f.order.add("marker");return false;});var item=mock(ItemStack.class);when(f.sourcePlayer.getItemInHand(InteractionHand.MAIN_HAND)).thenAnswer(call->{assertSame(f.sourcePlayer,f.current);f.order.add("item");return item;});when(f.sourcePlayer.isSpectator()).thenAnswer(call->{assertSame(f.sourcePlayer,f.current);f.order.add("spectator");return false;});
   var actual=actual(f,stand,true);f.drain();assertEquals(java.util.List.of("original"),f.order);assertFalse(actual.raw().isDone());child.complete(null);f.drain();assertEquals(java.util.List.of("original","marker","item","spectator"),f.order);assertEquals(InteractionResult.PASS,actual.result().join());assertEquals(17,actual.raw().join());
  }
 }
 @Test void minecartChecksPlayerSecondaryUseBeforeActualForeignVehicleAndReturnsSourceSuccess()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   FakePlayerCompatConfig.fakePlayerInteractLikeClient=true;var cart=mock(AbstractMinecart.class);target(f,cart);when(f.sourcePlayer.interactOn(cart,InteractionHand.MAIN_HAND,Vec3.ZERO)).thenReturn(InteractionResult.PASS);when(f.sourcePlayer.isSecondaryUseActive()).thenAnswer(call->{assertSame(f.sourcePlayer,f.current);f.order.add("secondary");return false;});when(cart.isVehicle()).thenAnswer(call->{assertSame(cart,f.current);f.order.add("vehicle");return false;});var actual=actual(f,cart,false);f.drain();assertEquals(java.util.List.of("secondary","vehicle"),f.order);assertEquals(InteractionResult.SUCCESS,actual.result().join());assertEquals(17,actual.raw().join());
   clearInvocations(cart);when(f.sourcePlayer.isSecondaryUseActive()).thenReturn(true);var secondary=actual(f,cart,false);f.drain();assertEquals(InteractionResult.PASS,secondary.result().join());secondary.raw().join();verify(cart,never()).isVehicle();
  }
 }
 @Test void originalNativeInteractionFailureBlocksBoatResultOverrideAndPlayerPredicate()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   FakePlayerCompatConfig.fakePlayerInteractLikeClient=true;var boat=mock(AbstractBoat.class);target(f,boat);var child=new CompletableFuture<Void>();when(f.sourcePlayer.interactOn(boat,InteractionHand.MAIN_HAND,Vec3.ZERO)).thenAnswer(call->{ScarpetNativeWork.record(child);return InteractionResult.PASS;});var actual=actual(f,boat,false);var failure=new IllegalStateException("native interaction");child.completeExceptionally(failure);f.drain();assertSame(failure,assertThrows(CompletionException.class,actual.result()::join).getCause());assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,actual.raw()::join)));verify(f.sourcePlayer,never()).isSecondaryUseActive();ScarpetNativeWork.whenIdle(f.server).handle((value,error)->null).join();
  }
 }
 @Test void originalGuestFailureKeepsRawParentAndContinuesActualBoatNativeResultOverride()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   FakePlayerCompatConfig.fakePlayerInteractLikeClient=true;var boat=mock(AbstractBoat.class);target(f,boat);var child=new CompletableFuture<Void>();when(f.sourcePlayer.interactOn(boat,InteractionHand.MAIN_HAND,Vec3.ZERO)).thenAnswer(call->{ScarpetNativeWork.record(child);return InteractionResult.PASS;});var actual=actual(f,boat,false);var failure=new IllegalStateException("guest interaction");var mark=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);mark.setAccessible(true);mark.invoke(null,failure);child.completeExceptionally(failure);f.drain();assertEquals(InteractionResult.SUCCESS,actual.result().join());assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,actual.raw()::join)));ScarpetNativeWork.whenIdle(f.server).handle((value,error)->null).join();
  }
 }
 @Test void disabledRulePreservesActualOriginalResultWithoutAddedTargetOrPlayerPredicates()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   var stand=mock(ArmorStand.class);target(f,stand);when(stand.interact(f.sourcePlayer,InteractionHand.MAIN_HAND,Vec3.ZERO)).thenReturn(InteractionResult.CONSUME);var actual=actual(f,stand,true);f.drain();assertEquals(InteractionResult.CONSUME,actual.result().join());actual.raw().join();verify(stand,never()).isMarker();verify(f.sourcePlayer,never()).getItemInHand(any());
  }
 }
}
