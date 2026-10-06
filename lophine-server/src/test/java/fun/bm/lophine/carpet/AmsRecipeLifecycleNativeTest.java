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
 /** Real resource command construction and Paper/Leaves registrars; only vanilla command population is isolated. */
 static final class RegistrarFixture implements AutoCloseable {
  final net.minecraft.commands.Commands previous=mock(net.minecraft.commands.Commands.class);
  final MinecraftServer server=mock(MinecraftServer.class);
  final ReloadableServerRegistries.LoadResult registries;
  final net.minecraft.commands.CommandBuildContext previousContext;
  final Map<java.lang.reflect.Field,Object> savedRegistrar=new HashMap<>();
  final Map<String,org.leavesmc.leaves.command.RootNode> registered;
  final Map<String,org.leavesmc.leaves.command.RootNode> savedRoots;
  final org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit=mockStatic(org.bukkit.Bukkit.class);
  final org.mockito.MockedStatic<MinecraftServer> servers=mockStatic(MinecraftServer.class);
  @SuppressWarnings("unchecked") RegistrarFixture()throws Exception {
   var paper=io.papermc.paper.command.brigadier.PaperCommands.INSTANCE;
   for(String name:List.of("dispatcher","buildContext","invalid","currentContext")){var field=paper.getClass().getDeclaredField(name);field.setAccessible(true);savedRegistrar.put(field,field.get(paper));}
   var roots=org.leavesmc.leaves.command.RootNode.class.getDeclaredField("REGISTERED");roots.setAccessible(true);registered=(Map<String,org.leavesmc.leaves.command.RootNode>)roots.get(null);savedRoots=Map.copyOf(registered);registered.clear();
   try {
   var recipes=new net.minecraft.core.MappedRegistry<Recipe<?>>(Registries.RECIPE,com.mojang.serialization.Lifecycle.stable());
   var advancements=new net.minecraft.core.MappedRegistry<net.minecraft.advancements.Advancement>(Registries.ADVANCEMENT,com.mojang.serialization.Lifecycle.stable());
   var reloadable=new RegistryAccess.ImmutableRegistryAccess(List.of(recipes.freeze(),advancements.freeze())).freeze();
   var layers=RegistryLayer.createRegistryAccess().replaceFrom(RegistryLayer.RELOADABLE,reloadable);registries=new ReloadableServerRegistries.LoadResult(layers,layers.compositeAccess());
   previousContext=net.minecraft.commands.CommandBuildContext.simple(registries.lookupWithUpdatedTags(),net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
   when(previous.getDispatcher()).thenReturn(new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>());when(server.getCommands()).thenReturn(previous);servers.when(MinecraftServer::getServer).thenReturn(server);
   var api=mock(org.bukkit.Server.class);var plugins=mock(org.bukkit.plugin.PluginManager.class);when(api.getPluginManager()).thenReturn(plugins);bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(api);bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(List.of());
   paper.setDispatcher(previous,previousContext);new org.leavesmc.leaves.command.RootNode("carpet_reload_lifecycle_test","carpet.reload.lifecycle.test"){}.register();
   assertThrows(IllegalStateException.class,paper::getDispatcher);
   } catch(Exception|Error failure) {
    try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;
   }
  }
  org.mockito.MockedConstruction<net.minecraft.commands.Commands> commandConstruction(){
   return mockConstruction(net.minecraft.commands.Commands.class,(commands,context)->when(commands.getDispatcher()).thenReturn(new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>()));
  }
  void verifyCommands(ReloadableServerResources resources){
   var root=resources.getCommands().getDispatcher().getRoot();
   // Folia disables Paper's mspt command; these are the actual internal registrations.
   for(String name:List.of("carpet_reload_lifecycle_test","version","plugins","bukkit:version"))
    assertNotNull(root.getChild(name),()->"Missing "+name+" from "+root.getChildren().stream().map(com.mojang.brigadier.tree.CommandNode::getName).toList());
  }
  @Override public void close()throws Exception {
   try{registered.clear();registered.putAll(savedRoots);for(var entry:savedRegistrar.entrySet())entry.getKey().set(io.papermc.paper.command.brigadier.PaperCommands.INSTANCE,entry.getValue());}finally{servers.close();bukkit.close();}
  }
 }
 @Test void realResourceConstructorReopensInternalRegistrationAfterLeavesRebindInvalidatesIt()throws Exception {
  try(var fixture=new RegistrarFixture();var commands=fixture.commandConstruction()){
   var constructor=ReloadableServerResources.class.getDeclaredConstructors()[0];constructor.setAccessible(true);
   var resources=(ReloadableServerResources)constructor.newInstance(fixture.registries,net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS,net.minecraft.commands.Commands.CommandSelection.ALL,List.of(),net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS,List.of());
   fixture.verifyCommands(resources);assertEquals(1,commands.constructed().size());
  }
 }
 @Test void actualDetachedFactoryRestoresThePreviousDispatcherAndClosesItsRegistrarWindow()throws Exception {
  try(var fixture=new RegistrarFixture();var commands=fixture.commandConstruction()){
   // Invoke the actual compiled Supplier body so unrelated datapack/component IO
   // cannot mask the dispatcher lifecycle contract being exercised here.
   var factory=Arrays.stream(ReloadableServerResources.class.getDeclaredMethods()).filter(method->method.isSynthetic()&&method.getReturnType()==ReloadableServerResources.class&&Arrays.asList(method.getParameterTypes()).contains(boolean.class)&&Arrays.asList(method.getParameterTypes()).contains(ReloadableServerRegistries.LoadResult.class)).findFirst().orElseThrow();
   factory.setAccessible(true);Object[] arguments=new Object[factory.getParameterCount()];var types=factory.getParameterTypes();
   for(int index=0;index<types.length;index++){
    if(types[index]==boolean.class)arguments[index]=true;
    else if(types[index]==ReloadableServerRegistries.LoadResult.class)arguments[index]=fixture.registries;
    else if(types[index]==net.minecraft.world.flag.FeatureFlagSet.class)arguments[index]=net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
    else if(types[index]==net.minecraft.commands.Commands.CommandSelection.class)arguments[index]=net.minecraft.commands.Commands.CommandSelection.ALL;
    else if(types[index]==net.minecraft.server.permissions.PermissionSet.class)arguments[index]=net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS;
    else if(types[index]==List.class)arguments[index]=List.of();
    else throw new AssertionError("Unexpected detached factory capture "+types[index]);
   }
   var resources=(ReloadableServerResources)factory.invoke(null,arguments);fixture.verifyCommands(resources);assertEquals(1,commands.constructed().size());
   var paper=io.papermc.paper.command.brigadier.PaperCommands.INSTANCE;assertSame(fixture.previousContext,paper.getBuildContext());
   var mirror=(io.papermc.paper.command.brigadier.ApiMirrorRootNode)paper.getDispatcherInternal().getRoot();assertSame(fixture.previous.getDispatcher(),mirror.getDispatcher());assertThrows(IllegalStateException.class,paper::getDispatcher);
  }
 }
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
