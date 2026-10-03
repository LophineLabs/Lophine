package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.ServerBot;

/** Production dispatcher/file/owner bodies; only external birth/removal admissions are controlled. */
class OrgManagerNativeCommandTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private static final class Fixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors;
        final OrgPlayerManager manager;
        final ServerBot bot;
        final CommandSourceStack source=mock(CommandSourceStack.class);
        final com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher=new com.mojang.brigadier.CommandDispatcher<>();
        final AtomicReference<String> callback=new AtomicReference<>();
        final Map<net.minecraft.server.MinecraftServer,OrgPlayerManager> managers;
        final String prior=GeneralCompatConfig.commandPlayerManager;
        @SuppressWarnings("unchecked") Fixture(Path directory)throws Exception{
            actors=new OrgInventoryPersistenceTest.Fixture(directory,true);bot=(ServerBot)actors.target.player();
            for(var actor:actors.actors.values()){var player=actor.player();when(player.blockPosition()).thenReturn(BlockPos.ZERO);when(player.getDisplayName()).thenReturn(Component.literal(actor==actors.target?"Fake":"Alice"));var pack=net.minecraft.server.level.ServerPlayer.class.getField("carpetActionPack");pack.setAccessible(true);pack.set(player,new CarpetPlayerActionPack(player));var slots=net.minecraft.world.inventory.AbstractContainerMenu.class.getDeclaredField("slots");slots.setAccessible(true);slots.set(player.containerMenu,net.minecraft.core.NonNullList.create());}
            when(bot.getScoreboardName()).thenReturn("Fake");when(bot.getMaxHealth()).thenReturn(20F);when(actors.server.getPlayerList().getPlayerByName("Fake")).thenReturn(bot);
            when(source.getServer()).thenReturn(actors.server);when(source.getEntity()).thenReturn(actors.viewer.player());when(source.getPlayer()).thenReturn(actors.viewer.player());when(source.callback()).thenReturn((success,value)->callback.set(success+":"+value));when(actors.server.createCommandSourceStack()).thenReturn(source);
            var create=OrgPlayerManager.class.getDeclaredConstructor(net.minecraft.server.MinecraftServer.class,net.minecraft.commands.CommandBuildContext.class);create.setAccessible(true);manager=spy(create.newInstance(actors.server,null));var index=OrgPlayerManager.class.getDeclaredField("MANAGERS");index.setAccessible(true);managers=(Map<net.minecraft.server.MinecraftServer,OrgPlayerManager>)index.get(null);managers.put(actors.server,manager);GeneralCompatConfig.commandPlayerManager="true";OrgPlayerManagerCommands.register(dispatcher,null);
        }
        CompletableFuture<Integer> command(String command)throws Exception{try(var scope=CarpetAsyncCommandResults.open()){assertEquals(1,dispatcher.execute(command,source));return scope.resultFuture(source);}}
        void cycle(){actors.drain(actors.target);actors.drain(actors.viewer);actors.owner.set(null);}
        void until(java.util.function.BooleanSupplier done)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(!done.getAsBoolean()&&System.nanoTime()<deadline){cycle();Thread.sleep(1);}assertTrue(done.getAsBoolean());}
        @Override public void close(){managers.remove(actors.server,manager);GeneralCompatConfig.commandPlayerManager=prior;actors.close();}
    }
    @Test void actualSafeOwnerAndLateFeedbackChildrenPrecedeTypedThresholdCallback()throws Exception{
        try(var f=new Fixture(directory)){var first=new CompletableFuture<Void>();var late=new CompletableFuture<Void>();doAnswer(call->{assertSame(f.actors.viewer.player(),f.actors.owner.get());ScarpetNativeWork.record(first);var append=ScarpetRuntime.captureNativeContinuation(()->{ScarpetNativeWork.record(late);return null;});first.thenRun(append::get);return null;}).when(f.source).sendSuccess(any(),eq(false));var result=f.command("playerManager safeafk set Fake 7");assertFalse(result.isDone());assertEquals(-1F,OrgPlayerManager.safeThreshold(f.bot));assertNull(f.callback.get());f.cycle();assertEquals(7F,OrgPlayerManager.safeThreshold(f.bot));assertFalse(result.isDone());first.complete(null);f.cycle();assertFalse(result.isDone());late.complete(null);f.until(result::isDone);assertEquals(7,result.join());assertEquals("true:7",f.callback.get());}
    }
    @Test void actualSafeThresholdDenialIsFailureAndCannotMutateRuntimeOrFile()throws Exception{
        try(var f=new Fixture(directory)){var result=f.command("playerManager safeafk set Fake 20 true");f.until(result::isDone);assertEquals(0,result.join());assertEquals("false:0",f.callback.get());assertEquals(-1F,OrgPlayerManager.safeThreshold(f.bot));assertFalse(java.nio.file.Files.exists(directory.resolve("config/carpet-org-addition/safeafk.properties")));}
    }
    @Test void actualPermanentSafeFileReadbackAndOwnerApplyPrecedeResult()throws Exception{
        try(var f=new Fixture(directory)){var result=f.command("playerManager safeafk set Fake 6 true");assertFalse(result.isDone());f.until(result::isDone);assertEquals(6,result.join());var data=new java.util.Properties();try(var reader=java.nio.file.Files.newBufferedReader(directory.resolve("config/carpet-org-addition/safeafk.properties"))){data.load(reader);}assertEquals("6.0",data.getProperty("Fake"));assertEquals(6F,OrgPlayerManager.safeThreshold(f.bot));assertEquals("true:6",f.callback.get());}
    }
    @Test void dispatcherNativeFileFailureKeepsRawFailureAndReportsActualCallbackFalse()throws Exception{
        try(var f=new Fixture(directory)){var parent=ScarpetNativeWork.observeNative(f.actors.viewer.player(),()->{try{return f.dispatcher.execute("playerManager remove absent",f.source);}catch(Exception failure){throw new AssertionError(failure);}});f.until(parent::isDone);assertThrows(CompletionException.class,parent::join);assertEquals("false:0",f.callback.get());}
    }
    @Test void managedSpawnCallbackWaitsActualDenialInsteadOfReportingQueuedTrue()throws Exception{
        try(var f=new Fixture(directory)){var profile=new com.google.gson.JsonObject();var pos=new com.google.gson.JsonObject();pos.addProperty("x",0);pos.addProperty("y",64);pos.addProperty("z",0);profile.add("pos",pos);var direction=new com.google.gson.JsonObject();direction.addProperty("yaw",0);direction.addProperty("pitch",0);profile.add("direction",direction);profile.addProperty("dimension","minecraft:overworld");profile.addProperty("gamemode","survival");f.manager.store.save("Saved",profile,false);var physical=new CompletableFuture<Boolean>();doReturn(physical).when(f.manager).spawn(eq(f.source),eq("Saved"),any(),eq(false));var result=f.command("playerManager spawn Saved");f.until(()->mockingDetails(f.manager).getInvocations().stream().anyMatch(call->call.getMethod().getName().equals("spawn")));assertFalse(result.isDone());assertNull(f.callback.get());physical.complete(false);f.until(result::isDone);assertEquals(0,result.join());assertEquals("false:0",f.callback.get());}
    }
    @Test void actualScheduledReceiptReturnsDelayAndCancellationReturnsRealCount()throws Exception{
        try(var f=new Fixture(directory)){var result=f.command("playerManager schedule logout Fake 3 s");f.until(result::isDone);assertEquals(60,result.join());assertEquals("true:60",f.callback.get());var cancelled=f.command("playerManager schedule cancel Fake");f.until(cancelled::isDone);assertEquals(1,cancelled.join());assertEquals(java.util.List.of(),f.manager.schedules());var absent=f.command("playerManager schedule cancel Fake");f.until(absent::isDone);assertEquals(0,absent.join());assertEquals("false:0",f.callback.get());}
    }
    @Test void mandatoryRemovalUsesRealOwnerCapturedFlagsAndPreservesCancelledCaller()throws Exception{
        try(var f=new Fixture(directory)){var physical=new CompletableFuture<Boolean>();when(f.actors.server.getBotList().carpetRemoveBotAsync(eq(f.bot),any(),any(),eq(true),eq(false))).thenAnswer(call->{assertSame(f.bot,f.actors.owner.get());assertTrue(OrgGameplayHelper.insideOrgAction(),"actual removal lost OrgAction");assertSame(f.bot,OrgGameplayHelper.blockBreaker(),"actual removal lost breaker");return physical;});var receipt=new AtomicReference<CompletableFuture<Boolean>>();var parent=ScarpetNativeWork.observeNative(f.actors.viewer.player(),()->new OrgGameplayHelper.NativeRuleScopes(true,f.bot,false,true,true).call(()->{receipt.set(f.manager.killAsync(f.source,"Fake"));return 41;}));assertTrue(receipt.get().cancel(false));f.until(()->mockingDetails(f.actors.server.getBotList()).getInvocations().stream().anyMatch(call->call.getMethod().getName().equals("carpetRemoveBotAsync")));if(parent.isDone())parent.join();assertFalse(parent.isDone(),"copied cancellation cannot end parent");assertFalse(ScarpetNativeWork.whenIdle(f.actors.server).isDone(),"physical removal must hold global idle");physical.complete(false);f.until(parent::isDone);assertEquals(41,parent.join());assertTrue(receipt.get().isCancelled());assertFalse(OrgGameplayHelper.insideOrgAction(),"OrgAction must be restored");}
    }
    @Test void startupKillIsAnActualExternalNativeJobAndCannotAwaitItsOwnBirth()throws Exception{
        try(var f=new Fixture(directory)){var physical=new CompletableFuture<Boolean>();when(f.actors.server.getBotList().carpetRemoveBotAsync(eq(f.bot),any(),any(),eq(true),eq(false))).thenReturn(physical);var function=new com.google.gson.JsonObject();function.addProperty("type","simple");function.addProperty("value","kill");var startup=OrgPlayerManager.class.getDeclaredMethod("startupDelayed",ServerBot.class,long.class,com.google.gson.JsonObject.class);startup.setAccessible(true);var birth=ScarpetNativeWork.observeNative(f.bot,()->{try{startup.invoke(f.manager,f.bot,2L,function);}catch(Exception failure){throw new AssertionError(failure);}return true;});assertTrue(birth.join());assertFalse(ScarpetNativeWork.whenIdle(f.actors.server).isDone());f.until(()->mockingDetails(f.actors.server.getBotList()).getInvocations().stream().anyMatch(call->call.getMethod().getName().equals("carpetRemoveBotAsync")));assertFalse(ScarpetNativeWork.whenIdle(f.actors.server).isDone());physical.complete(true);f.until(()->ScarpetNativeWork.whenIdle(f.actors.server).isDone());ScarpetNativeWork.whenIdle(f.actors.server).join();}
    }
    @Test void batchSourceNameWorldAndModeAreSnapshottedOnActualSourceOwner()throws Exception{
        try(var f=new Fixture(directory)){String before=GeneralCompatConfig.fakePlayerNamePrefix,after=GeneralCompatConfig.fakePlayerNameSuffix;GeneralCompatConfig.fakePlayerNamePrefix="p";GeneralCompatConfig.fakePlayerNameSuffix="s";try{when(f.source.getPosition()).thenReturn(new net.minecraft.world.phys.Vec3(5,64,7));var mode=mock(net.minecraft.server.level.ServerPlayerGameMode.class);f.actors.viewer.player().gameMode=mode;when(mode.getGameModeForPlayer()).thenAnswer(call->{assertSame(f.actors.viewer.player(),f.actors.owner.get());return net.minecraft.world.level.GameType.ADVENTURE;});var abilities=new net.minecraft.world.entity.player.Abilities();abilities.flying=true;when(f.actors.viewer.player().getAbilities()).thenReturn(abilities);when(f.actors.viewer.player().getXRot()).thenReturn(10F);when(f.actors.viewer.player().getYRot()).thenReturn(20F);var physical=new CompletableFuture<Boolean>();doAnswer(call->{String name=call.getArgument(1);assertEquals("pfarm_1s",name);var profile=call.<com.google.gson.JsonObject>getArgument(2);assertEquals("adventure",profile.get("gamemode").getAsString());assertTrue(profile.get("flying").getAsBoolean());assertEquals(20F,profile.getAsJsonObject("direction").get("yaw").getAsFloat());assertEquals(10F,profile.getAsJsonObject("direction").get("pitch").getAsFloat());return physical;}).when(f.manager).spawn(eq(f.source),eq("pfarm_1s"),any(),eq(true));var world=f.actors.viewer.player().level();when(world.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);var result=f.manager.batchAsync(f.source,"farm",1,1,"spawn",null);assertFalse(result.isDone());f.cycle();assertFalse(result.isDone());physical.complete(false);f.until(result::isDone);assertEquals(0,result.join());}finally{GeneralCompatConfig.fakePlayerNamePrefix=before;GeneralCompatConfig.fakePlayerNameSuffix=after;}}
    }
}
