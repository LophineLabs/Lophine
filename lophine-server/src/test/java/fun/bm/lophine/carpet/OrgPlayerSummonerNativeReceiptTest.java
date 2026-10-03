package fun.bm.lophine.carpet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.ServerBot;

class OrgPlayerSummonerNativeReceiptTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    @Test void birthAnnouncementUsesPinnedTranslationAndKeepsActualRecipientPacketChildrenInTheBirth()throws Exception{
        boolean old=GeneralCompatConfig.displayPlayerSummoner;String language=GeneralCompatConfig.language;GeneralCompatConfig.displayPlayerSummoner=true;GeneralCompatConfig.language="zh_cn";
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true)){
            when(fixture.viewer.player().blockPosition()).thenReturn(new BlockPos(17,80,19));
            var bot=(ServerBot)fixture.target.player();fixture.owner.set(bot);when(bot.blockPosition()).thenReturn(new BlockPos(17,80,19));var source=bot.level();when(source.dimension()).thenReturn(Level.OVERWORLD);
            var recipient=fixture.viewer.player();var child=new CompletableFuture<Void>();var sent=new AtomicReference<Component>();
            doAnswer(call->{assertSame(recipient,fixture.owner.get());sent.set(call.getArgument(0));ScarpetNativeWork.record(child);return null;}).when(recipient).sendSystemMessage(any(Component.class));
            var parent=ScarpetNativeWork.observeNative(bot,()->{OrgPlayerSummoner.spawned(bot,Component.literal("Alice"),false);return null;});assertFalse(parent.isDone());assertNull(sent.get());assertFalse(ScarpetNativeWork.whenIdle(fixture.server).isDone());fixture.drain(fixture.viewer);
            assertEquals(String.format(java.util.Locale.ROOT,OrgRuleTranslations.text("carpet-org-addition.rule.message.displayPlayerSummoner","Summoner: %s"),"Alice"),sent.get().getString());assertFalse(parent.isDone());child.complete(null);parent.join();ScarpetNativeWork.whenIdle(fixture.server).join();
        }finally{GeneralCompatConfig.displayPlayerSummoner=old;GeneralCompatConfig.language=language;}
    }
    @Test void actualRecipientNativeFailureRemainsPartOfTheAnnouncementParent()throws Exception{
        boolean old=GeneralCompatConfig.displayPlayerSummoner;GeneralCompatConfig.displayPlayerSummoner=true;
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true)){
            when(fixture.viewer.player().blockPosition()).thenReturn(new BlockPos(17,80,19));
            var child=new CompletableFuture<Void>();doAnswer(call->{ScarpetNativeWork.record(child);return null;}).when(fixture.viewer.player()).sendSystemMessage(any(Component.class));var parent=ScarpetNativeWork.observeNative(null,()->{OrgPlayerSummoner.batch(fixture.server,Component.literal("Alice"),2);return null;});fixture.drain(fixture.viewer);assertFalse(parent.isDone());child.completeExceptionally(new IllegalStateException("actual recipient packet failure"));assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,parent::join)));
        }finally{GeneralCompatConfig.displayPlayerSummoner=old;}
    }
}
