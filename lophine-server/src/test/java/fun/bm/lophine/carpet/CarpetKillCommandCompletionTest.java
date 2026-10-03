package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CarpetKillCommandCompletionTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private static CommandSourceStack source(OrgInventoryPersistenceTest.Fixture fixture,ServerLevel callerWorld,List<String> results){
        when(fixture.viewer.player().blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);when(fixture.target.player().blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        var source=mock(CommandSourceStack.class);when(source.getServer()).thenReturn(fixture.server);when(source.getEntity()).thenReturn(fixture.viewer.player());when(source.getLevel()).thenReturn(callerWorld);
        when(source.callback()).thenReturn((success,count)->results.add(success+":"+count));return source;
    }
    @Test void theRealNativeKillEntryFiltersOnlyOnEachOwnerAndWaitsActualDeathAndCallbackChildren()throws Exception{
        boolean previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeImmuneKill;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeImmuneKill=true;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var scope=CarpetAsyncCommandResults.open()){
            var viewer=fixture.viewer.player();var target=fixture.target.player();var callerWorld=mock(ServerLevel.class);var results=new ArrayList<String>();var source=source(fixture,callerWorld,results);
            fixture.owner.set(viewer);when(viewer.isCreative()).thenAnswer(call->{assertSame(viewer,fixture.owner.get());return true;});when(target.isCreative()).thenAnswer(call->{assertSame(target,fixture.owner.get());return false;});when(target.isSpectator()).thenAnswer(call->{assertSame(target,fixture.owner.get());return false;});when(target.getDisplayName()).thenReturn(Component.literal("Target"));
            var death=new CompletableFuture<Void>();var callback=new CompletableFuture<Void>();
            doAnswer(call->{assertSame(target,fixture.owner.get());assertSame(callerWorld,call.getArgument(0));ScarpetNativeWork.record(death);return null;}).when(target).kill(callerWorld);
            when(source.callback()).thenReturn((success,count)->{assertSame(viewer,fixture.owner.get());results.add(success+":"+count);ScarpetNativeWork.record(callback);});
            var method=net.minecraft.server.commands.KillCommand.class.getDeclaredMethod("kill",CommandSourceStack.class,Collection.class);method.setAccessible(true);
            assertEquals(1,method.invoke(null,source,List.of(viewer,target)));var result=scope.resultFuture(source);assertNotNull(result);var cancelled=result.copy();assertTrue(cancelled.cancel(false));var idle=ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(result.isDone());assertFalse(idle.isDone());fixture.drain(fixture.target);assertFalse(result.isDone());assertTrue(results.isEmpty());verify(viewer,never()).kill(any());
            death.complete(null);fixture.drain(fixture.viewer);assertEquals(List.of("true:1"),results);assertFalse(result.isDone());assertFalse(idle.isDone());
            callback.complete(null);assertEquals(1,result.join());assertTrue(idle.isDone());assertTrue(cancelled.isCancelled());
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeImmuneKill=previous;}
    }
    @Test void allImmuneTargetsReportTheOriginalNoEntitiesFailureAndZeroAfterTheirOwnerDecision()throws Exception{
        boolean previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeImmuneKill;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeImmuneKill=true;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var scope=CarpetAsyncCommandResults.open()){
            var viewer=fixture.viewer.player();var target=fixture.target.player();fixture.owner.set(viewer);var world=mock(ServerLevel.class);var results=new ArrayList<String>();var source=source(fixture,world,results);
            when(target.isCreative()).thenAnswer(call->{assertSame(target,fixture.owner.get());return true;});CarpetKillCommand.execute(source,List.of(target));var actual=scope.resultFuture(source);assertFalse(actual.isDone());fixture.drain(fixture.target);fixture.drain(fixture.viewer);
            assertEquals(0,actual.join());assertEquals(List.of("false:0"),results);verify(target,never()).kill(any());verify(source).sendFailure(argThat(message->message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text&&text.getKey().equals("argument.entity.notfound.entity")));
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeImmuneKill=previous;}
    }
    @Test void oneNativeFailureWaitsAlreadyAcceptedTargetsAndRemainsARealFailure()throws Exception{
        boolean previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeImmuneKill;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeImmuneKill=false;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var scope=CarpetAsyncCommandResults.open()){
            var viewer=fixture.viewer.player();var target=fixture.target.player();fixture.owner.set(viewer);var world=mock(ServerLevel.class);var results=new ArrayList<String>();var source=source(fixture,world,results);when(viewer.getDisplayName()).thenReturn(Component.literal("Source"));when(target.getDisplayName()).thenReturn(Component.literal("Target"));
            var child=new CompletableFuture<Void>();var failure=new IllegalStateException("real native kill");doThrow(failure).when(viewer).kill(world);doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(target).kill(world);
            var root=ScarpetNativeWork.observeNative(null,()->{CarpetKillCommand.execute(source,List.of(viewer,target));return true;});var result=scope.resultFuture(source);var idle=ScarpetNativeWork.whenIdle(fixture.server);assertFalse(root.isDone());assertFalse(result.isDone());fixture.drain(fixture.target);assertFalse(result.isDone());assertFalse(idle.isDone());child.complete(null);fixture.drain(fixture.viewer);
            assertEquals(0,result.join());assertEquals(List.of("false:0"),results);assertTrue(root.isCompletedExceptionally());assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,root::join)));assertTrue(idle.isDone());
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeImmuneKill=previous;}
    }
}
