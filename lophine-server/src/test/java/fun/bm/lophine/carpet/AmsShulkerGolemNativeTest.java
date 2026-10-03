package fun.bm.lophine.carpet;
import carpet.script.external.*;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.lang.reflect.*;import java.nio.file.Path;import java.util.*;import java.util.concurrent.*;
import net.minecraft.core.BlockPos;import net.minecraft.core.particles.*;import net.minecraft.world.entity.*;import net.minecraft.world.entity.monster.Shulker;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.BlockState;import net.minecraft.world.level.block.state.pattern.BlockPattern;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
public class AmsShulkerGolemNativeTest {
 @BeforeAll static void boot()throws Exception{AmsNativeManagementTest.bootstrap();}
 @TempDir Path directory;
 @AfterEach void reset(){GeneralCompatConfig.shulkerGolem=false;}
 private static final BlockPos HEAD=new BlockPos(3,65,-7),BODY=HEAD.below();
 private void world(AmsNativeManagementTest.Fixture f){GeneralCompatConfig.shulkerGolem=true;when(f.world.getBlockState(HEAD)).thenReturn(Blocks.CARVED_PUMPKIN.defaultBlockState());when(f.world.getBlockState(BODY)).thenReturn(Blocks.SHULKER_BOX.defaultBlockState());when(f.world.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.VANILLA_SET);when(f.world.getDifficulty()).thenReturn(net.minecraft.world.Difficulty.NORMAL);}
 private void source(AmsNativeManagementTest.Fixture f)throws Exception{var method=CarvedPumpkinBlock.class.getDeclaredMethod("trySpawnShulkerGolem",net.minecraft.world.level.Level.class,BlockPos.class);method.setAccessible(true);method.invoke(Blocks.CARVED_PUMPKIN,f.world,HEAD);}
 @Test void actualPumpkinHookUsesOriginalSummonedShulkerAndBodyHeadDestroyThenAddThenParticlesEachTrueChild()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var types=mockStatic(TisLifetimeTracker.class);var created=mockConstruction(Shulker.class,(entity,ctx)->{assertSame(f.world,ctx.arguments().get(1));doAnswer(call->{f.order.add("snap");return null;}).when(entity).snapTo(3.5D,64D,-6.5D,0F,0F);})){
   world(f);var body=new CompletableFuture<Void>();var head=new CompletableFuture<Void>();var add=new CompletableFuture<Void>();var particles=new CompletableFuture<Void>();types.when(()->TisLifetimeTracker.inferSpawn(any(Entity.class),eq(EntitySpawnReason.MOB_SUMMONED))).thenAnswer(call->{f.order.add("create");return null;});
   when(f.world.destroyBlock(BODY,false)).thenAnswer(call->{f.order.add("body");ScarpetNativeWork.record(body);return false;});when(f.world.destroyBlock(HEAD,false)).thenAnswer(call->{f.order.add("head");ScarpetNativeWork.record(head);return false;});when(f.world.addFreshEntity(any(Shulker.class))).thenAnswer(call->{assertSame(created.constructed().getFirst(),call.getArgument(0));f.order.add("add");ScarpetNativeWork.record(add);return false;});
   when(f.world.sendParticles(any(PowerParticleOption.class),eq(3.5D),eq(64D),eq(-6.5D),eq(1688),eq(.8D),eq(.8D),eq(.8D),eq(.0168D))).thenAnswer(call->{PowerParticleOption particle=call.getArgument(0);assertSame(ParticleTypes.DRAGON_BREATH,particle.getType());assertEquals(0F,particle.getPower());f.order.add("particles");ScarpetNativeWork.record(particles);return 0;});
   var actual=ScarpetNativeWork.observeNative(f.sourcePlayer,()->{try{source(f);}catch(Exception error){throw new RuntimeException(error);}return 13;});assertEquals(List.of("create","snap","body"),f.order);assertFalse(actual.isDone());body.complete(null);assertEquals(List.of("create","snap","body","head"),f.order);head.complete(null);assertEquals("add",f.order.getLast());add.complete(null);assertEquals("particles",f.order.getLast());assertFalse(actual.isDone());particles.complete(null);assertEquals(13,actual.join());verify(f.world,never()).setBlock(any(),any(),anyInt());verify(f.world,never()).getEntitiesOfClass(any(),any());ScarpetNativeWork.whenIdle(f.server).join();
  }
 }
 @Test void genuineBodyDestroyFailureBlocksHeadDestroyAddAndParticles()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var types=mockStatic(TisLifetimeTracker.class);var created=mockConstruction(Shulker.class)){
   world(f);var body=new CompletableFuture<Void>();when(f.world.destroyBlock(BODY,false)).thenAnswer(call->{ScarpetNativeWork.record(body);return true;});var raw=ScarpetNativeWork.observeNative(f.sourcePlayer,()->{AmsNativeShulkerGolem.spawn(f.world,HEAD);return 13;});assertFalse(raw.isDone());var failure=new IllegalStateException("body destroy");body.completeExceptionally(failure);assertSame(failure,assertThrows(CompletionException.class,raw::join).getCause());verify(f.world,never()).destroyBlock(HEAD,false);verify(f.world,never()).addFreshEntity(any(Shulker.class));assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));ScarpetNativeWork.whenIdle(f.server).handle((value,error)->null).join();
  }
 }
 @Test void guestBodyFailureKeepsRawParentAndRunsOriginalRemainingNativeWorldStages()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var types=mockStatic(TisLifetimeTracker.class);var created=mockConstruction(Shulker.class)){
   world(f);var body=new CompletableFuture<Void>();when(f.world.destroyBlock(BODY,false)).thenAnswer(call->{ScarpetNativeWork.record(body);return true;});var raw=ScarpetNativeWork.observeNative(f.sourcePlayer,()->{AmsNativeShulkerGolem.spawn(f.world,HEAD);return 13;});var failure=new IllegalStateException("guest body");var mark=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);mark.setAccessible(true);mark.invoke(null,failure);body.completeExceptionally(failure);assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,raw::join)));verify(f.world).destroyBlock(HEAD,false);verify(f.world).addFreshEntity(created.constructed().getFirst());verify(f.world).sendParticles(any(PowerParticleOption.class),eq(3.5D),eq(64D),eq(-6.5D),eq(1688),eq(.8D),eq(.8D),eq(.8D),eq(.0168D));ScarpetNativeWork.whenIdle(f.server).handle((value,error)->null).join();
  }
 }
 @Test void originalFalseRuleAndNonShulkerBodyMakeNoEntityAndNoWorldMutation()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var created=mockConstruction(Shulker.class)){
   source(f);assertTrue(created.constructed().isEmpty());verify(f.world,never()).getBlockState(any());world(f);when(f.world.getBlockState(BODY)).thenReturn(Blocks.STONE.defaultBlockState());AmsNativeShulkerGolem.spawn(f.world,HEAD).join();assertTrue(created.constructed().isEmpty());verify(f.world,never()).destroyBlock(any(),anyBoolean());
  }
 }
 @Test void originalNullCreateIsTrueNativeFailureBeforeAnyDestroy()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   world(f);when(f.world.getDifficulty()).thenReturn(net.minecraft.world.Difficulty.PEACEFUL);var actual=AmsNativeShulkerGolem.spawn(f.world,HEAD);assertFalse(actual.cancel(false));assertInstanceOf(NullPointerException.class,assertThrows(CompletionException.class,actual::join).getCause());verify(f.world,never()).destroyBlock(any(),anyBoolean());ScarpetNativeWork.whenIdle(f.server).handle((value,error)->null).join();
  }
 }
 @Test void originalDispenserCanSpawnAdmissionHasNoAddedShulkerExtension()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   world(f);var block=mock(CarvedPumpkinBlock.class,CALLS_REAL_METHODS);for(String name:List.of("snowGolemBase","ironGolemBase","copperGolemBase")){Field field=CarvedPumpkinBlock.class.getDeclaredField(name);field.setAccessible(true);field.set(block,mock(BlockPattern.class));}assertFalse(block.canSpawnGolem(f.world,HEAD));verify(f.world,never()).getBlockState(any());
  }
 }
}
