package fun.bm.lophine.carpet;

import carpet.script.external.*;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;
import net.minecraft.commands.*;import net.minecraft.core.BlockPos;import net.minecraft.network.chat.Component;import net.minecraft.world.InteractionResult;import net.minecraft.world.entity.player.*;import net.minecraft.world.food.FoodData;import net.minecraft.world.level.*;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;

public class AmsUseIoParityTest {
 @BeforeAll static void boot(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
 @TempDir Path directory;
 @AfterEach void reset(){GeneralCompatConfig.noCakeEating=false;GeneralCompatConfig.sneakToEatCake=false;GeneralCompatConfig.creativeShulkerBoxDropsDisabled=false;}
 static void waitFor(AmsNativeManagementTest.Fixture f,CompletableFuture<?> actual)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!actual.isDone()&&System.nanoTime()<end){f.drain();Thread.sleep(2);}actual.get(1,TimeUnit.SECONDS);}
 @Test void noCakeEatingIsOriginalReturnTailAfterActualFoodEventsAndWorldBite()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var events=mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)){
   GeneralCompatConfig.noCakeEating=true;var player=f.sourcePlayer;when(player.canEat(false)).thenReturn(true);var food=mock(FoodData.class);when(player.getFoodData()).thenReturn(food);when(food.getFoodLevel()).thenReturn(10);var event=mock(org.bukkit.event.entity.FoodLevelChangeEvent.class);when(event.getFoodLevel()).thenReturn(12);
   events.when(()->org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(eq(player),eq(BlockPos.ZERO),any(BlockState.class))).thenAnswer(call->{f.order.add("blockEvent");return true;});events.when(()->org.bukkit.craftbukkit.event.CraftEventFactory.callFoodLevelChangeEvent(player,12)).thenAnswer(call->{f.order.add("foodEvent");return event;});doAnswer(call->{f.order.add("food");return null;}).when(food).eat(2,.1F);
   var nativeBite=new CompletableFuture<Void>();when(f.world.setBlockAndUpdate(eq(BlockPos.ZERO),any(BlockState.class))).thenAnswer(call->{f.order.add("bite");ScarpetNativeWork.record(nativeBite);return true;});
   var method=CakeBlock.class.getDeclaredMethod("useWithoutItem",BlockState.class,Level.class,BlockPos.class,Player.class,net.minecraft.world.phys.BlockHitResult.class);method.setAccessible(true);var state=Blocks.CAKE.defaultBlockState();
   var actual=ScarpetNativeWork.observeNative(player,()->{try{return (InteractionResult)method.invoke(Blocks.CAKE,state,f.world,BlockPos.ZERO,player,new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.ZERO,net.minecraft.core.Direction.UP,BlockPos.ZERO,false));}catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}});
   assertEquals(List.of("blockEvent","foodEvent","food","bite"),f.order);assertFalse(actual.isDone());nativeBite.complete(null);assertEquals(InteractionResult.FAIL,actual.join());verify(player).awardStat(net.minecraft.stats.Stats.EAT_CAKE_SLICE);verify(food).eat(2,.1F);
  }
 }
 @Test void sneakToEatCakeStillWrapsActualEatAdmissionAndPreventsItsBodyWhenNotSneaking()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   GeneralCompatConfig.sneakToEatCake=true;when(f.sourcePlayer.isShiftKeyDown()).thenReturn(false);var method=CakeBlock.class.getDeclaredMethod("useWithoutItem",BlockState.class,Level.class,BlockPos.class,Player.class,net.minecraft.world.phys.BlockHitResult.class);method.setAccessible(true);
   assertEquals(InteractionResult.FAIL,method.invoke(Blocks.CAKE,Blocks.CAKE.defaultBlockState(),f.world,BlockPos.ZERO,f.sourcePlayer,null));verify(f.sourcePlayer,never()).canEat(anyBoolean());verify(f.world,never()).setBlockAndUpdate(any(),any());
  }
 }
 @Test void creativeShulkerOriginalHeadCallsActualNoDropDestroyAndSkipsLootAndSuperBody()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   GeneralCompatConfig.creativeShulkerBoxDropsDisabled=true;when(f.sourcePlayer.isCreative()).thenReturn(true);var nativeDestroy=new CompletableFuture<Void>();when(f.world.destroyBlock(BlockPos.ZERO,false)).thenAnswer(call->{f.order.add("destroy:false");ScarpetNativeWork.record(nativeDestroy);return false;});var state=Blocks.SHULKER_BOX.defaultBlockState();
   var actual=ScarpetNativeWork.observeNative(f.sourcePlayer,()->Blocks.SHULKER_BOX.playerWillDestroy(f.world,BlockPos.ZERO,state,f.sourcePlayer));assertEquals(List.of("destroy:false"),f.order);assertFalse(actual.isDone());verify(f.world,never()).getBlockEntity(any());nativeDestroy.complete(null);assertSame(state,actual.join());
  }
 }
 @Test void realForceModeRegisteredLeafWaitsOriginalSourceMessageThenActualAtomicFileReceipt()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
   AmsUpdateSuppressor.load(f.server);var ctx=f.context();when(ctx.getArgument("mode",Boolean.class)).thenReturn(true);var dispatcher=new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();AmsUpdateSuppressor.register(dispatcher);var command=dispatcher.getRoot().getChild("amsUpdateSuppressionCrashFixForceMode").getChild("mode").getCommand();
   var reply=new CompletableFuture<Void>();doAnswer(call->{f.order.add(((Supplier<Component>)call.getArgument(0)).get().getString());ScarpetNativeWork.record(reply);return null;}).when(f.source).sendSuccess(any(),eq(false));assertEquals(1,command.run(ctx));var actual=scope.resultFuture(f.source);f.drain();Path file=directory.resolve("carpetamsaddition/amsUpdateSuppressionCrashFixForceMode.json");assertFalse(Files.exists(file));assertFalse(actual.isDone());reply.complete(null);waitFor(f,actual);assertEquals(1,actual.join());assertTrue(com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("amsUpdateSuppressionCrashFixForceMode").getAsBoolean());verify(f.callback).onResult(true,1);
  }
 }
 @Test void originalUnreadableForceFileRemainsAndRealFailureCannotCancelOrPublishFalseStorageSuccess()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory)){
   Path file=directory.resolve("carpetamsaddition/amsUpdateSuppressionCrashFixForceMode.json");Files.createDirectories(file.getParent());Files.writeString(file,"original malformed force data");AmsUpdateSuppressor.load(f.server);var actual=AmsUpdateSuppressor.saveForceMode(f.server);assertFalse(actual.cancel(false));assertThrows(CompletionException.class,actual::join);assertEquals("original malformed force data",Files.readString(file));
  }
 }
 @Test void realAnvilLeafPreservesOriginalOperatorBroadcastBeforeOriginalForceConfigSave()throws Exception{
  try(var f=new AmsNativeManagementTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
   AmsUpdateSuppressor.load(f.server);var ctx=f.context();when(ctx.getArgument("boolean",Boolean.class)).thenReturn(true);var dispatcher=new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();var access=CommandBuildContext.simple(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY),net.minecraft.world.flag.FeatureFlags.VANILLA_SET);AmsManagementCommands.register(dispatcher,access);var command=dispatcher.getRoot().getChild("anvilInteractionDisabled").getChild("boolean").getCommand();
   var reply=new CompletableFuture<Void>();doAnswer(call->{ScarpetNativeWork.record(reply);return null;}).when(f.source).sendSuccess(any(),eq(true));assertEquals(1,command.run(ctx));var actual=scope.resultFuture(f.source);f.drain();Path file=directory.resolve("carpetamsaddition/amsUpdateSuppressionCrashFixForceMode.json");assertTrue(AmsManagementSettings.anvilDisabled);assertFalse(Files.exists(file));assertFalse(actual.isDone());reply.complete(null);waitFor(f,actual);assertEquals(1,actual.join());assertTrue(Files.exists(file));verify(f.source).sendSuccess(any(),eq(true));
  }
 }
 @Test void trueForceFileFailureReturnsOriginalFailureZeroAfterItsOriginalEarlierMessage()throws Exception{
  Files.createFile(directory.resolve("blocked"));try(var f=new AmsNativeManagementTest.Fixture(directory.resolve("blocked"));var scope=CarpetAsyncCommandResults.open()){
   AmsUpdateSuppressor.load(f.server);var ctx=f.context();when(ctx.getArgument("mode",Boolean.class)).thenReturn(true);var dispatcher=new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();AmsUpdateSuppressor.register(dispatcher);var command=dispatcher.getRoot().getChild("amsUpdateSuppressionCrashFixForceMode").getChild("mode").getCommand();
   doAnswer(call->{f.order.add(((Supplier<Component>)call.getArgument(0)).get().getString());return null;}).when(f.source).sendSuccess(any(),eq(false));command.run(ctx);var actual=scope.resultFuture(f.source);waitFor(f,actual);assertEquals(0,actual.join());assertEquals("command.amsUpdateSuppressionCrashFixForceMode.force_mode",f.order.getFirst());verify(f.callback).onResult(false,0);assertTrue(Files.isRegularFile(directory.resolve("blocked")));
  }
 }
}
