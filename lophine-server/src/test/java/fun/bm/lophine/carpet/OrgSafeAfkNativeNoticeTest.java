package fun.bm.lophine.carpet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.ServerBot;

class OrgSafeAfkNativeNoticeTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private static DamageSource prepare(OrgInventoryPersistenceTest.Fixture fixture){
        var bot=fixture.target.player();fixture.owner.set(bot);when(bot.blockPosition()).thenReturn(BlockPos.ZERO);when(bot.getDisplayName()).thenReturn(Component.literal("Bot"));when(bot.getHealth()).thenReturn(4F);when(bot.getItemInHand(any())).thenReturn(ItemStack.EMPTY);when(bot.getFoodData()).thenReturn(mock(net.minecraft.world.food.FoodData.class));when(fixture.viewer.player().blockPosition()).thenReturn(BlockPos.ZERO);var source=mock(DamageSource.class);when(source.getMsgId()).thenReturn("fall");return source;
    }
    @Test void actualBroadcastAndLateRecipientChildrenPrecedeFoodAndTheActualRemoval()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var manager=mockStatic(OrgPlayerManager.class);var globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)){
            var source=prepare(fixture);var bot=(ServerBot)fixture.target.player();manager.when(()->OrgPlayerManager.safeThreshold(bot)).thenReturn(5F);var global=mock(io.papermc.paper.threadedregions.RegionizedServer.class);globals.when(io.papermc.paper.threadedregions.RegionizedServer::getInstance).thenReturn(global);globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(false);var queue=new ArrayDeque<Runnable>();doAnswer(call->{queue.add(call.getArgument(0));return null;}).when(global).addTask(any());var child=new CompletableFuture<Void>();var late=new CompletableFuture<Void>();doAnswer(call->{assertSame(fixture.viewer.player(),fixture.owner.get());ScarpetNativeWork.record(child);child.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((ignored,failure)->ScarpetNativeWork.record(late)));return null;}).when(fixture.viewer.player()).sendSystemMessage(any(Component.class));var removal=new CompletableFuture<Boolean>();var removed=new AtomicInteger();when(fixture.server.getBotList().carpetRemoveBotAsync(eq(bot),any(),isNull(),eq(true),eq(false))).thenAnswer(call->{assertSame(bot,fixture.owner.get());removed.incrementAndGet();return removal;});var parent=ScarpetNativeWork.observeNative(bot,()->{OrgSafeAfk.afterDamage(bot,source,8F,false);return null;});assertFalse(parent.isDone());assertEquals(1,queue.size());verify(bot.getFoodData(),never()).setFoodLevel(anyInt());queue.remove().run();fixture.drain(fixture.viewer);assertEquals(0,removed.get());child.complete(null);fixture.drain(fixture.target);assertEquals(0,removed.get());late.complete(null);fixture.drain(fixture.target);verify(bot.getFoodData()).setFoodLevel(20);assertEquals(1,removed.get());assertFalse(parent.isDone());removal.complete(true);parent.join();ScarpetNativeWork.whenIdle(fixture.server).join();
        }
    }
    @Test void actualRecipientNativeFailureBlocksFoodAndRemoval()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var manager=mockStatic(OrgPlayerManager.class);var globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)){
            var source=prepare(fixture);var bot=(ServerBot)fixture.target.player();manager.when(()->OrgPlayerManager.safeThreshold(bot)).thenReturn(5F);globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);var child=new CompletableFuture<Void>();doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(fixture.viewer.player()).sendSystemMessage(any(Component.class));var parent=ScarpetNativeWork.observeNative(bot,()->{OrgSafeAfk.afterDamage(bot,source,8F,false);return null;});fixture.drain(fixture.viewer);child.completeExceptionally(new IllegalStateException("recipient native packet failed"));fixture.drain(fixture.target);assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,parent::join)));verify(bot.getFoodData(),never()).setFoodLevel(anyInt());verify(fixture.server.getBotList(),never()).carpetRemoveBotAsync(eq(bot),any(),any(),anyBoolean(),anyBoolean());
        }
    }
    @Test void recipientGuestOnlyFailureAllowsNativeRemovalButKeepsTheRawParentFailure()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var manager=mockStatic(OrgPlayerManager.class);var globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)){
            var source=prepare(fixture);var bot=(ServerBot)fixture.target.player();manager.when(()->OrgPlayerManager.safeThreshold(bot)).thenReturn(5F);globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);var child=new CompletableFuture<Void>();doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(fixture.viewer.player()).sendSystemMessage(any(Component.class));var removal=new CompletableFuture<Boolean>();when(fixture.server.getBotList().carpetRemoveBotAsync(eq(bot),any(),isNull(),eq(true),eq(false))).thenReturn(removal);var parent=ScarpetNativeWork.observeNative(bot,()->{OrgSafeAfk.afterDamage(bot,source,8F,false);return null;});fixture.drain(fixture.viewer);var failure=new IllegalStateException("guest packet observer closed");var mark=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);mark.setAccessible(true);mark.invoke(null,failure);child.completeExceptionally(failure);fixture.drain(fixture.target);verify(bot.getFoodData()).setFoodLevel(20);assertFalse(parent.isDone());removal.complete(true);assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,parent::join)));
        }
    }
    @Test void actualRemovalDenialIsANativeFailureAndDoesNotBecomeQueuedSuccess()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var manager=mockStatic(OrgPlayerManager.class);var globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class)){
            var source=prepare(fixture);var bot=(ServerBot)fixture.target.player();manager.when(()->OrgPlayerManager.safeThreshold(bot)).thenReturn(5F);globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);var removal=new CompletableFuture<Boolean>();when(fixture.server.getBotList().carpetRemoveBotAsync(eq(bot),any(),isNull(),eq(true),eq(false))).thenReturn(removal);var parent=ScarpetNativeWork.observeNative(bot,()->{OrgSafeAfk.afterDamage(bot,source,8F,false);return null;});fixture.drain(fixture.viewer);fixture.drain(fixture.target);assertFalse(parent.isDone());removal.complete(false);assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,parent::join)));
        }
    }
}
