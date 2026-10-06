package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.decoration.Mannequin;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** True production command entry bodies, actual owner queues and native teleport/effect futures. */
public class OrgPlayerActionCommandCompletionTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private static CommandSourceStack source(OrgInventoryPersistenceTest.Fixture fixture){
        when(fixture.viewer.player().blockPosition()).thenReturn(net.minecraft.core.BlockPos.ZERO);when(fixture.viewer.player().position()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);var source=mock(CommandSourceStack.class);when(source.getServer()).thenReturn(fixture.server);when(source.getEntity()).thenReturn(fixture.viewer.player());when(source.getPlayer()).thenReturn(fixture.viewer.player());when(source.callback()).thenReturn(CommandResultCallback.EMPTY);return source;
    }
    private static int target(Class<?> facade,CommandContext<CommandSourceStack> context,Consumer<ServerPlayer> body){
        try{var method=facade.getDeclaredMethod("target",CommandContext.class,Consumer.class);method.setAccessible(true);return (int)method.invoke(null,context,body);}
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;throw new AssertionError(failure.getCause());}
        catch(Exception failure){throw new AssertionError(failure);}
    }
    private static int invoke(Class<?> facade,CommandSourceStack source,Consumer<ServerPlayer> body){
        var dispatcher=new CommandDispatcher<CommandSourceStack>();dispatcher.register(Commands.literal("entry").then(Commands.argument("player",EntityArgument.player()).executes(context->target(facade,context,body))));
        try{return dispatcher.execute("entry fake",source);}catch(Exception failure){throw new AssertionError(failure);}
    }
    @Test void ordinaryAndHiddenActionEntryResultsWaitActualAssignmentChildrenAndTheirRealSourceCallback()throws Exception{
        for(Class<?> facade:java.util.List.of(OrgFakePlayerActionCommands.class,OrgHiddenActionCommands.class))try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory.resolve(facade.getSimpleName()));var scope=CarpetAsyncCommandResults.open()){
            var fake=fixture.actor(org.leavesmc.leaves.bot.ServerBot.class);when(fixture.server.getPlayerList().getPlayerByName("fake")).thenReturn(fake.player());var source=source(fixture);fixture.owner.set(null);var child=new CompletableFuture<Void>();var writes=new AtomicInteger();
            var parent=ScarpetNativeWork.observeNative(fixture.viewer.player(),()->invoke(facade,source,player->{assertSame(fake.player(),fixture.owner.get());writes.incrementAndGet();OrgFakePlayerActions.set(player,OrgFakePlayerActions.Action.simple("fishing",java.util.List.of()));ScarpetNativeWork.record(child);}));
            var commandResult=scope.resultFuture(source);assertNotNull(commandResult);assertFalse(commandResult.isDone());assertFalse(parent.isDone());assertEquals(0,writes.get());var idle=ScarpetNativeWork.whenIdle(fixture.server);assertFalse(idle.isDone());
            fixture.drain(fake);assertEquals(1,writes.get());assertFalse(commandResult.isDone());child.complete(null);assertFalse(commandResult.isDone());fixture.drain(fixture.viewer);assertEquals(1,commandResult.get(3,TimeUnit.SECONDS));assertEquals(1,parent.get(3,TimeUnit.SECONDS));idle.get(3,TimeUnit.SECONDS);
            fixture.owner.set(fake.player());assertEquals("fishing",OrgFakePlayerActions.get(fake.player()).kind());
        }
    }
    @Test void genuinelyFailedOrRetiredHiddenActionCannotReportAnAdmittedQueueAsSuccessfulCompletion()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
            var fake=fixture.actor(org.leavesmc.leaves.bot.ServerBot.class);when(fixture.server.getPlayerList().getPlayerByName("fake")).thenReturn(fake.player());var source=source(fixture);fixture.owner.set(null);
            var failure=new IllegalStateException("actual hidden owner failed");invoke(OrgHiddenActionCommands.class,source,player->{throw failure;});var result=scope.resultFuture(source);assertFalse(result.isDone());fixture.drain(fake);fixture.drain(fixture.viewer);assertEquals(0,result.get(3,TimeUnit.SECONDS));verify(source).sendFailure(any(Component.class));
        }
    }
    private static void poses(OrgInventoryPersistenceTest.Fixture fixture,OrgInventoryPersistenceTest.Actor fake){
        when(fixture.viewer.player().getX()).thenReturn(31D);when(fixture.viewer.player().getY()).thenReturn(67D);when(fixture.viewer.player().getZ()).thenReturn(-47D);when(fixture.viewer.player().getYRot()).thenReturn(30F);when(fixture.viewer.player().getXRot()).thenReturn(15F);when(fixture.viewer.player().getDisplayName()).thenReturn(Component.literal("source"));when(fake.player().getDisplayName()).thenReturn(Component.literal("fake"));when(fake.player().level().getWorld()).thenReturn(mock(org.bukkit.craftbukkit.CraftWorld.class));
    }
    @Test void actualPlayerTeleportKeepsItsPrivateGlobalAndFakeOwnerGateThroughTeleportArrivalEffectsAndFeedback()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            var fake=fixture.actor(org.leavesmc.leaves.bot.ServerBot.class);poses(fixture,fake);fixture.owner.set(null);var teleport=new CompletableFuture<Boolean>();var effects=new CompletableFuture<Void>();var launches=new AtomicInteger();var announcements=new AtomicInteger();
            when(fake.player().getBukkitEntity().teleportAsync(any(Location.class),eq(PlayerTeleportEvent.TeleportCause.COMMAND))).thenAnswer(call->{assertSame(fake.player(),fixture.owner.get());Location destination=call.getArgument(0);assertSame(fake.player().level().getWorld(),destination.getWorld());assertEquals(31,destination.getX());assertEquals(-47,destination.getZ());launches.incrementAndGet();return teleport;});
            var fakeLevel=fake.player().level();doAnswer(call->{assertSame(fake.player(),fixture.owner.get());ScarpetNativeWork.record(effects);return null;}).when(fakeLevel).broadcastEntityEvent(fake.player(),EntityEvent.TELEPORT);
            doAnswer(call->{assertSame(fixture.viewer.player(),fixture.owner.get());announcements.incrementAndGet();return null;}).when(fixture.viewer.player()).sendSystemMessage(any(Component.class));
            var caller=OrgPlayerExtraCommands.teleportAsync(fixture.viewer.player(),fake.player());var idle=ScarpetNativeWork.whenIdle(fixture.server);assertFalse(idle.isDone());assertTrue(caller.cancel(false));assertFalse(idle.isDone());fixture.drain(fixture.viewer);assertEquals(0,launches.get());fixture.drain(fake);assertEquals(1,launches.get());assertFalse(idle.isDone());
            fixture.owner.set(fake.player());var snapshot=ScarpetPlayerInventoryGate.whenIdle(fake.player(),()->"stable after effects");assertFalse(snapshot.isDone());teleport.complete(true);fixture.drain(fake);assertFalse(snapshot.isDone());assertFalse(idle.isDone());assertEquals(0,announcements.get());effects.complete(null);fixture.drain(fixture.viewer);fixture.drain(fake);assertEquals(1,announcements.get());assertEquals("stable after effects",snapshot.get(3,TimeUnit.SECONDS));idle.get(3,TimeUnit.SECONDS);assertTrue(caller.isCancelled());verify(fake.player()).resetFallDistance();
        }
    }
    @Test void actualTeleportCancellationSkipsArrivalEffectsAndReportsFalseOnlyAfterOwnerDecision()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            var fake=fixture.actor(org.leavesmc.leaves.bot.ServerBot.class);poses(fixture,fake);fixture.owner.set(null);var teleport=new CompletableFuture<Boolean>();when(fake.player().getBukkitEntity().teleportAsync(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class))).thenReturn(teleport);
            var actual=OrgPlayerExtraCommands.teleportAsync(fixture.viewer.player(),fake.player());fixture.drain(fixture.viewer);fixture.drain(fake);assertFalse(actual.isDone());teleport.complete(false);fixture.drain(fake);assertFalse(actual.get(3,TimeUnit.SECONDS));verify(fake.player().level(),never()).broadcastEntityEvent(fake.player(),EntityEvent.TELEPORT);verify(fake.player(),never()).resetFallDistance();
        }
    }
    @Test void aRealNativeTeleportExceptionIsNotAFalseSuccessAndWaitsAnyAlreadyAdmittedPhysicalChild()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)){
            var fake=fixture.actor(org.leavesmc.leaves.bot.ServerBot.class);poses(fixture,fake);fixture.owner.set(null);var child=new CompletableFuture<Void>();var failure=new IllegalStateException("actual teleport launch failed");
            when(fake.player().getBukkitEntity().teleportAsync(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class))).thenAnswer(call->{ScarpetNativeWork.record(child);throw failure;});
            var actual=OrgPlayerExtraCommands.teleportAsync(fixture.viewer.player(),fake.player());fixture.drain(fixture.viewer);fixture.drain(fake);assertFalse(actual.isDone());child.complete(null);assertSame(failure,assertThrows(CompletionException.class,actual::join).getCause());assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
        }
    }
    @Test void actualMannequinOwnerSpawnResultIncludesNativeSpawnChildrenAndSurvivesCallerCancellationBeforeOwner()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var models=mockConstruction(Mannequin.class)){
            var player=fixture.viewer.player();fixture.owner.set(null);var child=new CompletableFuture<Void>();var added=new AtomicReference<Mannequin>();when(player.position()).thenReturn(new net.minecraft.world.phys.Vec3(3,67,5));
            when(player.level().addFreshEntity(any(Mannequin.class))).thenAnswer(call->{assertSame(player,fixture.owner.get());added.set(call.getArgument(0));ScarpetNativeWork.record(child);return true;});
            var caller=OrgPlayerExtraCommands.mannequinAsync(player,"profile");var idle=ScarpetNativeWork.whenIdle(fixture.server);assertFalse(idle.isDone());assertTrue(caller.cancel(false));fixture.drain(fixture.viewer);assertNotNull(added.get());assertFalse(idle.isDone());child.complete(null);idle.get(3,TimeUnit.SECONDS);assertTrue(caller.isCancelled());verify(added.get()).snapTo(eq(new net.minecraft.world.phys.Vec3(3,67,5)),anyFloat(),anyFloat());
        }
    }
    @Test void realMenuCommandFailureFeedbackRunsOnItsSourceOwnerAndCommandCompletionWaitsItsNativeChildren()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
            var source=source(fixture);var operation=new CompletableFuture<Boolean>();var feedbackChild=new CompletableFuture<Void>();var feedbackCalls=new AtomicInteger();fixture.owner.set(null);
            doAnswer(call->{assertSame(fixture.viewer.player(),fixture.owner.get());feedbackCalls.incrementAndGet();ScarpetNativeWork.record(feedbackChild);return null;}).when(source).sendFailure(any(Component.class));
            var parent=ScarpetNativeWork.observeNative(fixture.viewer.player(),()->OrgMenuNativeEffects.command(source,()->operation,"actual false result"));var result=scope.resultFuture(source);assertFalse(result.isDone());
            operation.complete(false);assertEquals(0,feedbackCalls.get());fixture.drain(fixture.viewer);assertEquals(1,feedbackCalls.get());assertFalse(result.isDone());assertFalse(parent.isDone());
            feedbackChild.complete(null);fixture.drain(fixture.viewer);assertEquals(0,result.get(3,TimeUnit.SECONDS));assertEquals(1,parent.get(3,TimeUnit.SECONDS));
        }
    }
    private static final carpet.script.ScriptServer FILES=new carpet.script.ScriptServer(){@Override public Path resolveResource(String name){return Path.of(name);}};
    private static final class Host extends carpet.script.ScriptHost {
        Host(){super(null,FILES,false,null,carpet.script.Expression.LoadOverride.DEFAULT);}
        @Override protected carpet.script.Module getModuleOrLibraryByName(String name){return null;}
        @Override protected void runModuleCode(carpet.script.Context context,carpet.script.Module module){}
        @Override protected carpet.script.ScriptHost duplicate(){return new Host();}
    }
    private static final class ReadyContext extends carpet.script.Context {ReadyContext(Host host){super(host);initialize();}}
    @Test void actualRegisteredPlayerTeleportCommandSurvivesClosedInitiatingGuestAndReportsItsFinalNativeResult()throws Exception{
        String prior=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.playerCommandTeleportFakePlayer;fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.playerCommandTeleportFakePlayer="true";
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory);var scope=CarpetAsyncCommandResults.open()){
            var fake=fixture.actor(org.leavesmc.leaves.bot.ServerBot.class);poses(fixture,fake);when(fixture.server.getPlayerList().getPlayerByName("fake")).thenReturn(fake.player());var source=source(fixture);when(source.getPlayerOrException()).thenReturn(fixture.viewer.player());fixture.owner.set(null);var teleport=new CompletableFuture<Boolean>();when(fake.player().getBukkitEntity().teleportAsync(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class))).thenReturn(teleport);
            var dispatcher=new CommandDispatcher<CommandSourceStack>();OrgPlayerExtraCommands.register(dispatcher);var host=new Host();var context=new ReadyContext(host);CompletableFuture<Integer> parent;
            try(var frame=carpet.script.external.ScarpetRuntime.enterContext(context)){
                parent=ScarpetNativeWork.observeNative(fixture.viewer.player(),()->{try{return dispatcher.execute("player fake teleport",source);}catch(Exception failure){throw new AssertionError(failure);}});
            }
            var result=scope.resultFuture(source);assertNotNull(result);host.onClose();fixture.drain(fixture.viewer);fixture.drain(fake);assertFalse(result.isDone());assertFalse(parent.isDone());teleport.complete(true);fixture.drain(fake);fixture.drain(fixture.viewer);assertEquals(1,result.get(3,TimeUnit.SECONDS));assertEquals(1,parent.get(3,TimeUnit.SECONDS));verify(fake.player()).resetFallDistance();
        }finally{fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.playerCommandTeleportFakePlayer=prior;}
    }

}
