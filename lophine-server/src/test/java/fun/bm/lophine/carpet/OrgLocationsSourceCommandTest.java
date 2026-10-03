package fun.bm.lophine.carpet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgLocationsSourceCommandTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap()throws Exception{OrgInventoryPersistenceTest.bootstrap();try(var bukkit=mockStatic(org.bukkit.Bukkit.class)){var server=mock(org.bukkit.Server.class);when(server.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(server);Class.forName("org.leavesmc.leaves.plugin.MinecraftInternalPlugin");}}
    @Test void consoleCanCommentAndRemoveThroughActualFileAndCallbackResults()throws Exception{
        String prior=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandLocations;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandLocations="true";
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            var world=fixture.viewer.player().level();var craft=mock(org.bukkit.craftbukkit.CraftServer.class);var region=mock(io.papermc.paper.threadedregions.scheduler.FoliaRegionScheduler.class);when(craft.getRegionScheduler()).thenReturn(region);var field=net.minecraft.server.MinecraftServer.class.getField("server");field.setAccessible(true);field.set(fixture.server,craft);var regions=new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();doAnswer(call->{assertEquals(0,call.<Integer>getArgument(2));assertEquals(0,call.<Integer>getArgument(3));regions.add(call.getArgument(4));return null;}).when(region).execute(any(),any(),anyInt(),anyInt(),any(Runnable.class));var source=mock(CommandSourceStack.class);when(source.getServer()).thenReturn(fixture.server);when(source.getLevel()).thenReturn(world);when(source.getPosition()).thenReturn(Vec3.ZERO);when(source.getPlayerOrException()).thenThrow(net.minecraft.commands.arguments.EntityArgument.NO_PLAYERS_FOUND.create());var callback=new AtomicReference<String>();when(source.callback()).thenReturn((success,value)->callback.set(success+":"+value));var child=new CompletableFuture<Void>();doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(source).sendSuccess(any(),eq(false));var store=OrgLocationsCommand.store(fixture.server);store.save(new OrgWaypointStore.Waypoint("home",BlockPos.ZERO,"minecraft:overworld","Alice","old",null),false);var dispatcher=new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();OrgLocationsCommand.register(dispatcher);
            CompletableFuture<Integer> comment;try(var scope=CarpetAsyncCommandResults.open()){assertEquals(1,dispatcher.execute("locations supplement home comment \"new note\"",source));comment=scope.resultFuture(source);}assertFalse(comment.isDone());assertNull(callback.get());drainUntil(regions,()->!mockingDetails(source).getInvocations().stream().filter(call->call.getMethod().getName().equals("sendSuccess")).toList().isEmpty());assertFalse(comment.isDone());assertNull(callback.get());child.complete(null);drainUntil(regions,comment::isDone);assertEquals(1,comment.join());assertEquals("new note",store.load("home").comment());assertEquals("true:1",callback.get());verify(source,never()).getPlayerOrException();
            CompletableFuture<Integer> remove;try(var scope=CarpetAsyncCommandResults.open()){assertEquals(1,dispatcher.execute("locations remove home",source));remove=scope.resultFuture(source);}drainUntil(regions,remove::isDone);assertEquals(1,remove.join());assertEquals(java.util.List.of(),store.names());assertEquals("true:1",callback.get());verify(source,never()).getPlayerOrException();
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandLocations=prior;}
    }
    private static void drainUntil(java.util.concurrent.ConcurrentLinkedQueue<Runnable> regions,java.util.function.BooleanSupplier done)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(!done.getAsBoolean()&&System.nanoTime()<deadline){Runnable task;while((task=regions.poll())!=null)task.run();Thread.sleep(1);}assertTrue(done.getAsBoolean());}
    @Test void sourceBlankCommentBecomesEmptyAndExistingDimensionAndCreatorRemain()throws Exception{
        var store=new OrgWaypointStore(directory);store.save(new OrgWaypointStore.Waypoint("home",new BlockPos(2,64,3),"minecraft:the_nether","Alice","old",new BlockPos(16,64,24)),false);store.update("home",waypoint->waypoint.comment("  "));var result=store.load("home");assertEquals("",result.comment());assertEquals("Alice",result.creator());assertEquals("minecraft:the_nether",result.dimension());assertEquals(new BlockPos(16,64,24),result.another());
    }
}
