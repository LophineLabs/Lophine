package fun.bm.lophine.carpet;
import carpet.script.external.ScarpetNativeWork;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.network.*;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.advancements.triggers.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class AmsRecipeLifecycleNativeTest {
 @BeforeAll static void bootstrap()throws Exception{
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();var config=new io.papermc.paper.configuration.GlobalConfiguration();config.misc=config.new Misc();
  try(var configs=mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class)){configs.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(config);Class.forName("net.minecraft.network.Connection");}
 }
 static RecipeHolder<?> recipe(String namespace,String name){return new RecipeHolder<>(ResourceKey.create(Registries.RECIPE,Identifier.fromNamespaceAndPath(namespace,name)),mock(ShapedRecipe.class));}
 static ServerPlayer player(MinecraftServer server){var player=mock(ServerPlayer.class);var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(server);when(player.level()).thenReturn(world);when(player.carpetSpawnServer()).thenReturn(server);when(player.blockPosition()).thenReturn(BlockPos.ZERO);when(player.getAdvancements()).thenReturn(mock(PlayerAdvancements.class));return player;}
 @Test void actualSelectedPackReloadAndItsChildFinishBeforeAMSRecipeQueryAndGrants()throws Exception{
  var server=mock(MinecraftServer.class);when(server.isRunning()).thenReturn(true);var packs=mock(net.minecraft.server.packs.repository.PackRepository.class);when(server.getPackRepository()).thenReturn(packs);when(packs.getSelectedIds()).thenReturn(List.of("vanilla","custom"));
  var reload=new CompletableFuture<Void>();var reloadChild=new CompletableFuture<Void>();when(server.reloadResources(List.of("vanilla","custom"))).thenAnswer(call->{ScarpetNativeWork.record(reloadChild);return reload;});
  var first=player(server);var second=player(server);var firstBook=mock(ServerRecipeBook.class);var secondBook=mock(ServerRecipeBook.class);when(first.getRecipeBook()).thenReturn(firstBook);when(second.getRecipeBook()).thenReturn(secondBook);
  var ams=recipe("carpetamsaddition","test");var other=recipe("minecraft","test");var manager=mock(RecipeManager.class);manager.recipes=mock(RecipeMap.class);when(manager.recipes.values()).thenReturn(List.of(other,ams));when(server.getRecipeManager()).thenReturn(manager);var list=mock(PlayerList.class);when(server.getPlayerList()).thenReturn(list);when(list.getPlayers()).thenReturn(List.of(first,second));
  var granted=new CompletableFuture<Integer>();when(firstBook.carpetAddRecipeNative(ams,first)).thenReturn(granted);when(secondBook.carpetAddRecipeNative(ams,second)).thenReturn(CompletableFuture.completedFuture(1));
  try(var global=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class);var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class)){
   global.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(any(net.minecraft.world.entity.Entity.class))).thenReturn(true);
   var actual=AmsRecipeLifecycle.changed(server);assertFalse(actual.isDone());verify(server,never()).getRecipeManager();reload.complete(null);assertFalse(actual.isDone());verifyNoInteractions(firstBook,secondBook);reloadChild.complete(null);assertFalse(actual.isDone());verify(firstBook).carpetAddRecipeNative(ams,first);verify(secondBook,never()).carpetAddRecipeNative(any(),any());granted.complete(1);actual.get(3,TimeUnit.SECONDS);verify(secondBook).carpetAddRecipeNative(ams,second);verify(firstBook,never()).carpetAddRecipeNative(other,first);
  }
 }
 @Test void failedRealResourceReloadDoesNotGrantFromTheOldManager(){
  var server=mock(MinecraftServer.class);when(server.isRunning()).thenReturn(true);var packs=mock(net.minecraft.server.packs.repository.PackRepository.class);when(server.getPackRepository()).thenReturn(packs);when(packs.getSelectedIds()).thenReturn(List.of("vanilla"));when(server.reloadResources(List.of("vanilla"))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reload native failed")));
  try(var global=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)){
   global.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);var actual=AmsRecipeLifecycle.changed(server);assertThrows(CompletionException.class,actual::join);verify(server,never()).getRecipeManager();
  }
 }
 @Test void actualRecipeBookEventThenKnownMutationThenPhysicalWriteAndChild()throws Exception{book(false,false);}
 @Test void actualRecipeBookPhysicalFailureRemainsNativeFailure()throws Exception{book(true,false);}
 @Test void cancelledRecipeDiscoverEventStopsMutationCriterionAndPacket()throws Exception{book(false,true);}
 void book(boolean failure,boolean cancelled)throws Exception{
  var server=mock(MinecraftServer.class);var player=player(server);var recipe=recipe("carpetamsaddition","test");var display=mock(RecipeDisplayEntry.class);
  var book=new ServerRecipeBook((key,consumer)->consumer.accept(display));when(player.getRecipeBook()).thenReturn(book);
  var listener=mock(ServerGamePacketListenerImpl.class);player.connection=listener;var wire=mock(net.minecraft.network.Connection.class);wire.channel=mock(io.netty.channel.Channel.class);when(wire.channel.closeFuture()).thenReturn(mock(io.netty.channel.ChannelFuture.class));when(wire.isConnected()).thenReturn(true);var field=ServerCommonPacketListenerImpl.class.getField("connection");field.setAccessible(true);field.set(listener,wire);
  var event=mock(org.bukkit.event.player.PlayerRecipeDiscoverEvent.class);when(event.isCancelled()).thenReturn(cancelled);var eventChild=new CompletableFuture<Void>();var packetChild=new CompletableFuture<Void>();var write=new AtomicReference<io.netty.channel.ChannelFutureListener>();
  doAnswer(call->{write.set(call.getArgument(1));ScarpetNativeWork.record(packetChild);return null;}).when(listener).send(any(Packet.class),any(io.netty.channel.ChannelFutureListener.class));
  try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);var factory=mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)){
   ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)).thenReturn(true);factory.when(()->org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerRecipeListUpdateEvent(player,recipe)).thenAnswer(call->{ScarpetNativeWork.record(eventChild);return event;});
   var actual=book.carpetAddRecipeNative(recipe,player);assertFalse(actual.isDone());assertFalse(book.contains(recipe.id()));verifyNoInteractions(listener);eventChild.complete(null);
   if(cancelled){assertEquals(0,actual.get(3,TimeUnit.SECONDS));assertFalse(book.contains(recipe.id()));verifyNoInteractions(listener);return;}
   assertTrue(book.contains(recipe.id()));assertFalse(actual.isDone());assertNotNull(write.get());var sent=mock(io.netty.channel.ChannelFuture.class);when(sent.isSuccess()).thenReturn(!failure);when(sent.cause()).thenReturn(new IllegalStateException("recipe wire failed"));write.get().operationComplete(sent);assertFalse(actual.isDone());packetChild.complete(null);
   if(failure)assertThrows(ExecutionException.class,()->actual.get(3,TimeUnit.SECONDS));else assertEquals(1,actual.get(3,TimeUnit.SECONDS));
  }
 }
 @Test void actualJoinWaitsWelcomeLeaderRecipeThenScarpetBody()throws Exception{join(false);}
 @Test void failedWelcomeStopsLaterJoinCallbacks()throws Exception{join(true);}
 void join(boolean fail)throws Exception{
  var server=mock(MinecraftServer.class);var player=player(server);var welcome=new CompletableFuture<Void>();var leaderChild=new CompletableFuture<Void>();var recipes=new CompletableFuture<Void>();var script=new CompletableFuture<Void>();var order=new ArrayList<String>();
  try(var ticks=mockStatic(ca.spottedleaf.moonrise.common.util.TickThread.class);var greetings=mockStatic(AmsWelcomeMessage.class);var leaders=mockStatic(AmsManagementCommands.class);var awards=mockStatic(AmsRecipeLifecycle.class);var vm=mockStatic(carpet.script.external.ScarpetRuntime.class,CALLS_REAL_METHODS)){
   ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)).thenReturn(true);greetings.when(()->AmsWelcomeMessage.sendAsync(player)).thenAnswer(call->{order.add("welcome");return welcome;});leaders.when(()->AmsManagementCommands.onJoin(player)).thenAnswer(call->{order.add("leader");ScarpetNativeWork.record(leaderChild);return null;});awards.when(()->AmsRecipeLifecycle.loggedIn(server,player)).thenAnswer(call->{order.add("recipes");return recipes;});vm.when(()->carpet.script.external.ScarpetRuntime.onJoinFuture(player)).thenAnswer(call->{order.add("script");return script;});
   var actual=AmsPlayerJoin.join(player,true);assertFalse(actual.isDone());assertEquals(List.of("welcome"),order);
   if(fail){welcome.completeExceptionally(new IllegalStateException("welcome native failed"));assertThrows(CompletionException.class,actual::join);assertEquals(List.of("welcome"),order);return;}
   welcome.complete(null);assertEquals(List.of("welcome","leader"),order);assertFalse(actual.isDone());leaderChild.complete(null);assertEquals(List.of("welcome","leader","recipes"),order);recipes.complete(null);assertEquals(List.of("welcome","leader","recipes","script"),order);assertFalse(actual.isDone());script.complete(null);actual.get(3,TimeUnit.SECONDS);
  }
 }
}