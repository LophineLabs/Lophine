package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgServerPermissionsNativeTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private final class Fixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors=new OrgInventoryPersistenceTest.Fixture(directory);
        final CommandSourceStack source=mock(CommandSourceStack.class);
        final net.minecraft.commands.Commands commands=mock(net.minecraft.commands.Commands.class);
        final AtomicReference<String> callback=new AtomicReference<>();
        final AtomicInteger sent=new AtomicInteger();
        final CompletableFuture<Void> packet=new CompletableFuture<>(),late=new CompletableFuture<>();
        final org.mockito.MockedStatic<io.papermc.paper.threadedregions.RegionizedServer> globals=mockStatic(io.papermc.paper.threadedregions.RegionizedServer.class);
        final com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher=new com.mojang.brigadier.CommandDispatcher<>();
        final Path file=directory.resolve("config/carpet-org-addition/permission.json");
        Fixture()throws Exception{
            globals.when(io.papermc.paper.threadedregions.RegionizedServer::isGlobalTickThread).thenReturn(true);
            when(actors.server.getCommands()).thenReturn(commands);when(source.getServer()).thenReturn(actors.server);when(source.getEntity()).thenReturn(actors.viewer.player());when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);when(source.callback()).thenReturn((success,value)->callback.set(success+":"+value));
            for(var actor:actors.actors.values())when(actor.player().blockPosition()).thenReturn(BlockPos.ZERO);
            when(commands.carpetReloadCommands(any())).thenAnswer(call->{assertSame(call.getArgument(0),actors.owner.get());var actual=ScarpetNativeWork.observeNative(call.getArgument(0),()->{ScarpetNativeWork.record(packet);if(sent.incrementAndGet()==1){var append=ScarpetRuntime.captureNativeContinuation(()->{ScarpetNativeWork.record(late);return null;});packet.thenRun(append::get);}return (Void)null;});return ScarpetNativeWork.recoverGuestValue(actual);});
            OrgRulePlayerPreferences.register(dispatcher);
        }
        CompletableFuture<Integer> command()throws Exception{try(var scope=CarpetAsyncCommandResults.open()){assertEquals(1,dispatcher.execute("orange permission finder.block false",source));return scope.resultFuture(source);}}
        void cycle(){actors.drain(actors.viewer);actors.drain(actors.target);actors.owner.set(null);}
        void until(java.util.function.BooleanSupplier done)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(!done.getAsBoolean()&&System.nanoTime()<deadline){cycle();Thread.sleep(1);}assertTrue(done.getAsBoolean());}
        @Override public void close(){packet.complete(null);late.complete(null);for(int i=0;i<20;i++)cycle();OrgServerPermissions.close(actors.server);globals.close();actors.close();}
    }
    @Test void firstWorldPredicateUsesActualPersistedDenyAndDefaultsAndBukkitDeny()throws Exception{
        Path file=directory.resolve("config/carpet-org-addition/permission.json");java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,"{\"data_version\":3,\"permission\":{\"finder.block\":\"false\",\"navigate.death\":\"4\",\"finder.item\":\"invalid\"}}");var server=mock(net.minecraft.server.MinecraftServer.class);when(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).thenReturn(directory);var source=mock(CommandSourceStack.class);when(source.getServer()).thenReturn(server);when(source.permissions()).thenReturn(PermissionSet.NO_PERMISSIONS);try{assertFalse(OrgServerPermissions.allowed(source,"finder.block"));assertFalse(OrgServerPermissions.allowed(source,"navigate.death"));assertTrue(OrgServerPermissions.allowed(source,"finder.item"));assertFalse(OrgServerPermissions.allowed(source,"mail.intercept"));when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);assertTrue(OrgServerPermissions.allowed(source,"navigate.death"));assertFalse(OrgServerPermissions.allowed(source,"finder.block"));var sender=mock(org.bukkit.command.CommandSender.class);when(source.getBukkitSender()).thenReturn(sender);when(sender.isPermissionSet("finder.item")).thenReturn(true);when(sender.hasPermission("finder.item")).thenReturn(false);assertFalse(OrgServerPermissions.allowed(source,"finder.item"));}finally{OrgServerPermissions.close(server);}
    }
    @Test void actualPermissionTreesAndLateNativePacketChildrenPrecedeFileAndOrdinalCallback()throws Exception{
        try(var f=new Fixture()){var result=f.command();assertFalse(result.isDone());assertFalse(OrgServerPermissions.allowed(f.source,"finder.block"));assertFalse(java.nio.file.Files.exists(f.file));f.until(()->f.sent.get()==2);assertFalse(result.isDone());f.packet.complete(null);f.cycle();assertFalse(result.isDone());assertFalse(java.nio.file.Files.exists(f.file));f.late.complete(null);f.until(result::isDone);assertEquals(5,result.join());assertEquals("true:5",f.callback.get());var data=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f.file)).getAsJsonObject();assertEquals(3,data.get("data_version").getAsInt());assertEquals("false",data.getAsJsonObject("permission").get("finder.block").getAsString());assertEquals("2",data.getAsJsonObject("permission").get("mail.intercept").getAsString());}
    }
    @Test void actualNativeTreeFailureBlocksFileTailAndRetainsParentFailure()throws Exception{
        try(var f=new Fixture()){var result=new AtomicReference<CompletableFuture<Integer>>();var parent=ScarpetNativeWork.observeNative(f.actors.viewer.player(),()->{try{result.set(f.command());return null;}catch(Exception failure){throw new AssertionError(failure);}});f.until(()->f.sent.get()==2);f.packet.completeExceptionally(new IllegalStateException("actual command tree native failure"));f.late.complete(null);f.until(()->result.get().isDone());assertEquals(0,result.get().join());assertEquals("false:0",f.callback.get());assertFalse(java.nio.file.Files.exists(f.file));assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,parent::join)));assertFalse(OrgServerPermissions.allowed(f.source,"finder.block"));}
    }
    @Test void actualGuestTreeFailureStillPersistsRealValueAndKeepsRawParentFailed()throws Exception{
        try(var f=new Fixture()){var result=new AtomicReference<CompletableFuture<Integer>>();var parent=ScarpetNativeWork.observeNative(f.actors.viewer.player(),()->{try{result.set(f.command());return null;}catch(Exception failure){throw new AssertionError(failure);}});f.until(()->f.sent.get()==2);var failure=new IllegalStateException("guest packet callback closed");var mark=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);mark.setAccessible(true);mark.invoke(null,failure);f.packet.completeExceptionally(failure);f.late.complete(null);f.until(()->result.get().isDone());assertEquals(5,result.get().join());assertTrue(java.nio.file.Files.isRegularFile(f.file));assertEquals("true:5",f.callback.get());assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,parent::join)));}
    }
    @Test void sourceLevelAliasesAndOrdinalsRemainExact(){assertEquals("true",OrgServerPermissions.canonical("0"));assertEquals("2",OrgServerPermissions.canonical("ops"));for(int level=1;level<=4;level++)assertEquals(level,OrgServerPermissions.ordinal(OrgServerPermissions.canonical(""+level)));assertEquals(0,OrgServerPermissions.ordinal("true"));assertEquals(5,OrgServerPermissions.ordinal("false"));assertThrows(IllegalArgumentException.class,()->OrgServerPermissions.canonical("5"));}
}
