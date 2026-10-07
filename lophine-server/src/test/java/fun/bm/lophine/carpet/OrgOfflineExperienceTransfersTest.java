package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetPlayerInventoryGate;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgOfflineExperienceTransfersTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private final class Fixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors=new OrgInventoryPersistenceTest.Fixture(directory, false, true);
        final UUID source=UUID.randomUUID();final Path file=directory.resolve("playerdata").resolve(source+".dat");
        final Object coordinator;final org.mockito.MockedStatic<OrgHiddenPlayerActions> hidden;
        final String finder=GeneralCompatConfig.commandFinder, xpPermission=GeneralCompatConfig.commandXpTransfer;
        Fixture()throws Exception{
            org.mockito.MockedStatic<OrgHiddenPlayerActions> created=null;
            try{
                GeneralCompatConfig.commandFinder="true";GeneralCompatConfig.commandXpTransfer="true";when(actors.server.getWorldPath(LevelResource.PLAYER_DATA_DIR)).thenReturn(directory.resolve("playerdata"));
                var sourceStack=mock(CommandSourceStack.class);when(sourceStack.getServer()).thenReturn(actors.server);when(sourceStack.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);when(actors.viewer.player().createCommandSourceStack()).thenReturn(sourceStack);
                var player=actors.viewer.player();player.connection=mock(ServerGamePacketListenerImpl.class);player.experienceLevel=5;player.totalExperience=55;
                when(player.getXpNeededForNextLevel()).thenAnswer(call->player.experienceLevel>=30?9*player.experienceLevel-158:player.experienceLevel>=15?5*player.experienceLevel-38:2*player.experienceLevel+7);
                doAnswer(call->{player.experienceLevel=call.getArgument(0);player.experienceProgress=0;return null;}).when(player).setExperienceLevels(anyInt());doAnswer(call->{player.experienceProgress=(float)call.<Integer>getArgument(0)/player.getXpNeededForNextLevel();return null;}).when(player).setExperiencePoints(anyInt());
                doAnswer(call->{var tag=OrgInventoryTransfers.inventoryTag(player);tag.putInt("XpLevel",player.experienceLevel);tag.putInt("XpTotal",player.totalExperience);tag.putFloat("XpP",player.experienceProgress);var values=new CompoundTag();actors.viewer.pdc().forEach((key,value)->{if(value instanceof String text)values.putString(key.toString(),text);else if(value instanceof byte[] bytes)values.putByteArray(key.toString(),bytes.clone());});tag.put("BukkitValues",values);actors.saved.put(player.getUUID(),tag);return null;}).when(actors.storage).save(any(Player.class));
                var data=NbtUtils.addCurrentDataVersion(new CompoundTag());data.put("UUID",UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE,source).getOrThrow());data.putInt("XpLevel",4);data.putFloat("XpP",0.2F);data.putInt("XpTotal",43);data.putString("PluginState","kept");Files.createDirectories(file.getParent());NbtIo.writeCompressed(data,file);
                var factory=OrgExperienceTransfers.class.getDeclaredMethod("coordinator",net.minecraft.server.MinecraftServer.class);factory.setAccessible(true);coordinator=factory.invoke(null,actors.server);
                created=mockStatic(OrgHiddenPlayerActions.class);created.when(OrgHiddenPlayerActions::enabled).thenReturn(true);hidden=created;
            }catch(Exception|Error failure){if(created!=null)created.close();actors.close();GeneralCompatConfig.commandFinder=finder;GeneralCompatConfig.commandXpTransfer=xpPermission;throw failure;}
        }
        CompletableFuture<Boolean> take(){actors.owner.set(actors.viewer.player());return OrgExperienceTransfers.takeOffline(actors.viewer.player(),source);}
        void until(BooleanSupplier done)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!done.getAsBoolean()&&System.nanoTime()<deadline){actors.owner.set(actors.viewer.player());OrgExperienceTransfers.tick(actors.viewer.player());if(!done.getAsBoolean())actors.process(actors.viewer);Thread.sleep(5);}assertTrue(done.getAsBoolean(),"Real file/player XP actor did not terminate");}
        void untilFile(BooleanSupplier done)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!done.getAsBoolean()&&System.nanoTime()<deadline){actors.process(actors.viewer);Thread.sleep(5);}assertTrue(done.getAsBoolean(),"Real source file actor did not terminate");}
        CompoundTag data()throws Exception{return NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());}
        void reader(OrgExperienceTransfers.OfflineReader reader)throws Exception{var field=coordinator.getClass().getDeclaredField("offlineReader");field.setAccessible(true);field.set(coordinator,reader);}
        Path realPlayerFile(UUID player){return directory.resolve("playerdata").resolve(player+".dat");}
        void realPlayerFiles()throws Exception{
            var playerList=actors.server.getPlayerList();
            when(actors.storage.getPlayerDir()).thenReturn(directory.resolve("playerdata").toFile());
            doAnswer(call->{var player=(net.minecraft.server.level.ServerPlayer)call.getArgument(0);var tag=NbtUtils.addCurrentDataVersion(OrgInventoryTransfers.inventoryTag(player));tag.put("UUID",UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE,player.getUUID()).getOrThrow());tag.putInt("XpLevel",player.experienceLevel);tag.putInt("XpTotal",player.totalExperience);tag.putFloat("XpP",player.experienceProgress);var values=new CompoundTag();actors.actors.get(player.getUUID()).pdc().forEach((key,value)->{if(value instanceof String text)values.putString(key.toString(),text);else if(value instanceof byte[] bytes)values.putByteArray(key.toString(),bytes.clone());});tag.put("BukkitValues",values);actors.saved.put(player.getUUID(),tag);Files.createDirectories(realPlayerFile(player.getUUID()).getParent());NbtIo.writeCompressed(tag,realPlayerFile(player.getUUID()));return null;}).when(actors.storage).save(any(Player.class));
            doAnswer(call->{actors.storage.save(call.getArgument(0));return null;}).when(playerList).carpetSaveFakePlayer(any(org.leavesmc.leaves.bot.ServerBot.class));
        }
        CompletableFuture<Void> firstRead(UUID player,Path path){return OrgPlayerFileLease.withLease(actors.server,player,"actual Native first NBT read",lease->OrgPlayerInventoryMenus.recoverOfflineBeforeRead(actors.server,player,path));}
        @Override public void close(){hidden.close();actors.close();GeneralCompatConfig.commandFinder=finder;GeneralCompatConfig.commandXpTransfer=xpPermission;}
    }
    @Test void clearsRealOfflineExperienceAndCreditsTheActualViewerWithUnrelatedDataKept()throws Exception{
        try(var fixture=new Fixture()){
            var result=fixture.take();fixture.until(result::isDone);assertTrue(result.join());assertEquals(BigInteger.valueOf(98),OrgExperienceAmounts.read(fixture.actors.viewer.player()));assertEquals(0,fixture.data().getIntOr("XpLevel",-1));assertEquals(0,fixture.data().getIntOr("XpTotal",-1));assertEquals("kept",fixture.data().getStringOr("PluginState",""));assertFalse(OrgPlayerFileLease.busy(fixture.actors.server,fixture.source));assertTrue(OrgExperienceTransfers.whenAvailable(fixture.actors.server,fixture.source).isDone());
        }
    }
    @Test void anUnknownSourceClearReadbackKeepsItsActualUuidLoanAndNeverCreditsOrRestoresTheOldFile()throws Exception{
        try(var fixture=new Fixture()){
            var fail=new AtomicBoolean(true);var unknown=new AtomicBoolean();fixture.reader(path->{CompoundTag data=NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap());if(data.getIntOr("XpLevel",-1)==0&&fail.getAndSet(false)){unknown.set(true);throw new IOException("Successful source clear has an unreadable result");}return data;});
            var result=fixture.take();fixture.until(unknown::get);assertFalse(result.isDone());assertTrue(OrgPlayerFileLease.busy(fixture.actors.server,fixture.source));assertEquals(0,fixture.data().getIntOr("XpLevel",-1));assertEquals(BigInteger.valueOf(55),OrgExperienceAmounts.read(fixture.actors.viewer.player()));
            var admitted=new AtomicBoolean();var login=OrgPlayerFileLease.withLease(fixture.actors.server,fixture.source,"later source login",lease->{admitted.set(true);return CompletableFuture.completedFuture(true);});assertFalse(admitted.get());fixture.until(result::isDone);assertTrue(result.join());assertTrue(login.join());assertEquals(BigInteger.valueOf(98),OrgExperienceAmounts.read(fixture.actors.viewer.player()));assertEquals(0,fixture.data().getIntOr("XpTotal",-1));
        }
    }
    @Test void unknownDestinationCreditIsHeldAndLaterLegitimateExperienceIsPreserved()throws Exception{
        try(var fixture=new Fixture()){
            fixture.actors.unreadable.add(2);var result=fixture.take();fixture.until(()->fixture.actors.reads>=2);assertFalse(result.isDone());assertEquals(BigInteger.valueOf(55),OrgExperienceAmounts.read(fixture.actors.viewer.player()));var held=new org.bukkit.NamespacedKey("lophine","carpet_org_xp_credit_escrow");assertNotNull(fixture.actors.viewer.pdc().get(held));fixture.actors.owner.set(fixture.actors.viewer.player());OrgExperienceAmounts.write(fixture.actors.viewer.player(),BigInteger.valueOf(60));fixture.until(result::isDone);assertTrue(result.join());assertEquals(BigInteger.valueOf(103),OrgExperienceAmounts.read(fixture.actors.viewer.player()));assertNull(fixture.actors.viewer.pdc().get(held));assertEquals(0,fixture.data().getIntOr("XpTotal",-1));
        }
    }
    @Test void playerCreditWaitsForAnAcceptedOldNativeTailAndDoesNotResetItsExperienceGain()throws Exception{
        try(var fixture=new Fixture()){
            var old=new CompletableFuture<Void>();fixture.actors.owner.set(fixture.actors.viewer.player());ScarpetPlayerInventoryGate.trackAccepted(fixture.actors.viewer.player(),old);var result=fixture.take();fixture.until(()->!OrgPlayerFileLease.busy(fixture.actors.server,fixture.source));assertFalse(result.isDone());assertEquals(BigInteger.valueOf(55),OrgExperienceAmounts.read(fixture.actors.viewer.player()));OrgExperienceAmounts.write(fixture.actors.viewer.player(),BigInteger.valueOf(62));old.complete(null);fixture.until(result::isDone);assertTrue(result.join());assertEquals(BigInteger.valueOf(105),OrgExperienceAmounts.read(fixture.actors.viewer.player()));
        }
    }
    @Test void aColdCreatedRowRecognizesItsPaidMarkerAndPreservesLaterLegitimateSourceExperience()throws Exception{
        try(var fixture=new Fixture()){
            when(fixture.actors.server.getPlayerList().getPlayer(fixture.actors.viewer.id())).thenReturn(null);fixture.take();fixture.untilFile(()->!OrgPlayerFileLease.busy(fixture.actors.server,fixture.source));
            Path ledger=directory.resolve("carpet-org-experience-transfers.json");String saved=Files.readString(ledger);assertTrue(saved.contains("\"debited\""));Files.writeString(ledger,saved.replace("\"phase\": \"debited\"","\"phase\": \"created\""));
            CompoundTag source=fixture.data();source.putInt("XpLevel",2);source.putInt("XpTotal",16);source.putFloat("XpP",0);NbtIo.writeCompressed(source,fixture.file); // A valid later native save retains the server debit marker.
            Class<?> type=fixture.coordinator.getClass();var constructor=type.getDeclaredConstructor(net.minecraft.server.MinecraftServer.class);constructor.setAccessible(true);Object recovered=constructor.newInstance(fixture.actors.server);var field=OrgExperienceTransfers.class.getDeclaredField("COORDINATORS");field.setAccessible(true);synchronized(OrgExperienceTransfers.class){((java.util.Map<net.minecraft.server.MinecraftServer,Object>)field.get(null)).put(fixture.actors.server,recovered);}
            fixture.untilFile(()->!OrgPlayerFileLease.busy(fixture.actors.server,fixture.source));assertEquals(2,fixture.data().getIntOr("XpLevel",-1));assertEquals(16,fixture.data().getIntOr("XpTotal",-1));
            when(fixture.actors.server.getPlayerList().getPlayer(fixture.actors.viewer.id())).thenReturn(fixture.actors.viewer.player());fixture.until(()->OrgExperienceTransfers.whenAvailable(fixture.actors.server,fixture.actors.viewer.id()).isDone());assertEquals(BigInteger.valueOf(98),OrgExperienceAmounts.read(fixture.actors.viewer.player()));assertEquals(16,fixture.data().getIntOr("XpTotal",-1));
        }
    }
    @Test void nativeIdleAlreadyIncludesTheActualDestinationDispatchAndCreditBeforeTheirOwnerQueuesRun()throws Exception{
        try(var fixture=new Fixture()){
            var result=fixture.take();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while((!Files.isRegularFile(fixture.file)||fixture.data().getIntOr("XpTotal",-1)!=0||OrgPlayerFileLease.busy(fixture.actors.server,fixture.source))&&System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(0,fixture.data().getIntOr("XpTotal",-1));assertFalse(OrgPlayerFileLease.busy(fixture.actors.server,fixture.source));
            assertFalse(result.isDone());assertEquals(BigInteger.valueOf(55),OrgExperienceAmounts.read(fixture.actors.viewer.player()));
            var idle=carpet.script.external.ScarpetNativeWork.whenIdle(fixture.actors.server);assertFalse(idle.isDone(),"File completion cannot leave a zero-job gap before destination dispatch runs");
            var queue=fixture.actors.scheduled.get(fixture.actors.viewer.id());assertNotNull(queue);var dispatch=queue.poll();assertNotNull(dispatch);
            fixture.actors.owner.set(fixture.actors.viewer.player());fixture.actors.clocks.computeIfAbsent(fixture.actors.viewer.id(),ignored->new java.util.concurrent.atomic.AtomicLong()).set(dispatch.due());dispatch.work().accept(fixture.actors.viewer.player());
            assertFalse(idle.isDone());assertFalse(carpet.script.external.ScarpetNativeWork.whenIdle(fixture.actors.server).isDone(),"Credit must already be registered before its queued owner starts");
            assertEquals(BigInteger.valueOf(55),OrgExperienceAmounts.read(fixture.actors.viewer.player()));fixture.until(result::isDone);assertTrue(result.join());assertEquals(BigInteger.valueOf(98),OrgExperienceAmounts.read(fixture.actors.viewer.player()));idle.join();
        }
    }
    @Test void foreignOwnerAdmissionIsRegisteredBeforeItsFirstQueueAndTheApiRechecksXpPermission()throws Exception{
        try(var fixture=new Fixture()){
            fixture.actors.owner.set(null);var result=OrgExperienceTransfers.takeOffline(fixture.actors.viewer.player(),fixture.source);
            assertFalse(result.isDone());assertFalse(carpet.script.external.ScarpetNativeWork.whenIdle(fixture.actors.server).isDone());
            fixture.until(result::isDone);assertTrue(result.join());
        }
        try(var fixture=new Fixture()){
            GeneralCompatConfig.commandXpTransfer="false";var result=fixture.take();assertFalse(result.join());assertEquals(43,fixture.data().getIntOr("XpTotal",-1));assertEquals(BigInteger.valueOf(55),OrgExperienceAmounts.read(fixture.actors.viewer.player()));assertFalse(OrgPlayerFileLease.busy(fixture.actors.server,fixture.source));
        }
    }
    @Test void confirmedOfflineSourceReadsDoNotWaitForThePassiveOfflineRecipientsPaidCustody()throws Exception{
        try(var fixture=new Fixture()){
            when(fixture.actors.server.getPlayerList().getPlayer(fixture.actors.viewer.id())).thenReturn(null);
            var result=fixture.take();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while((fixture.data().getIntOr("XpTotal",-1)!=0||OrgPlayerFileLease.busy(fixture.actors.server,fixture.source))&&System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(0,fixture.data().getIntOr("XpTotal",-1));assertFalse(OrgPlayerFileLease.busy(fixture.actors.server,fixture.source));
            assertFalse(result.isDone());assertTrue(OrgExperienceTransfers.whenAvailable(fixture.actors.server,fixture.source).isDone());assertFalse(OrgExperienceTransfers.whenAvailable(fixture.actors.server,fixture.actors.viewer.id()).isDone());
            var read=OrgOfflinePlayerSnapshots.read(fixture.actors.server,fixture.source);fixture.untilFile(read::isDone);assertEquals(0,read.join().totalExperience());assertEquals(0,read.join().experienceLevel());
            assertTrue(carpet.script.external.ScarpetNativeWork.whenIdle(fixture.actors.server).isDone(),"Confirmed paid custody awaiting offline recipient is passive, and the source is free for a real readonly file actor");
            when(fixture.actors.server.getPlayerList().getPlayer(fixture.actors.viewer.id())).thenReturn(fixture.actors.viewer.player());fixture.until(result::isDone);assertTrue(result.join());assertEquals(BigInteger.valueOf(98),OrgExperienceAmounts.read(fixture.actors.viewer.player()));
        }
    }
    @Test void aColdCandidateCreditIsConfirmedBeforeNativeNbtReadAndIsNeverPaidAgain()throws Exception{
        try(var fixture=new Fixture()){
            fixture.realPlayerFiles();fixture.actors.unreadable.add(2);var result=fixture.take();fixture.until(()->fixture.actors.reads>=2);assertFalse(result.isDone());assertEquals(BigInteger.valueOf(55),OrgExperienceAmounts.read(fixture.actors.viewer.player()));
            Path file=fixture.realPlayerFile(fixture.actors.viewer.id());var tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());assertEquals(98,tag.getIntOr("XpTotal",-1));assertNotNull(tag.getCompoundOrEmpty("BukkitValues").getString("lophine:carpet_org_xp_credit").orElse(null));
            // A subsequent valid native save can retain the paid id after XP was legally spent.
            tag.putInt("XpLevel",6);tag.putFloat("XpP",8F/19F);tag.putInt("XpTotal",80);tag.putString("OtherPlugin","latest");NbtIo.writeCompressed(tag,file);
            when(fixture.actors.server.getPlayerList().getPlayer(fixture.actors.viewer.id())).thenReturn(null);var recovered=fixture.firstRead(fixture.actors.viewer.id(),file);fixture.untilFile(recovered::isDone);recovered.join();assertTrue(result.join());
            tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());assertEquals(80,tag.getIntOr("XpTotal",-1));assertEquals("latest",tag.getStringOr("OtherPlugin",""));assertFalse(tag.getCompoundOrEmpty("BukkitValues").contains("lophine:carpet_org_xp_credit_escrow"));assertTrue(OrgExperienceTransfers.whenAvailable(fixture.actors.server,fixture.actors.viewer.id()).isDone());
            assertEquals("[]",Files.readString(directory.resolve("carpet-org-experience-transfers.json")).trim());assertEquals(0,fixture.data().getIntOr("XpTotal",-1));
        }
    }
    @Test void aColdHeldCreditPreservesCurrentXpAndRetriesUnknownPublicationBeforeReleasingTheFirstReadLoan()throws Exception{
        try(var fixture=new Fixture()){
            fixture.realPlayerFiles();fixture.actors.unreadable.add(2);fixture.take();fixture.until(()->fixture.actors.reads>=2);Path file=fixture.realPlayerFile(fixture.actors.viewer.id());var tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());
            tag.putInt("XpLevel",5);tag.putFloat("XpP",6F/17F);tag.putInt("XpTotal",61);var values=tag.getCompoundOrEmpty("BukkitValues").copy();values.remove("lophine:carpet_org_xp_credit");values.remove("lophine:carpet_org_xp_credit_escrow");tag.put("BukkitValues",values);tag.putString("OtherPlugin","kept");NbtIo.writeCompressed(tag,file);
            when(fixture.actors.server.getPlayerList().getPlayer(fixture.actors.viewer.id())).thenReturn(null);var unknown=new AtomicBoolean();var fail=new AtomicBoolean(true);
            fixture.reader(path->{var current=NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap());if(path.equals(file)&&current.getCompoundOrEmpty("BukkitValues").contains("lophine:carpet_org_xp_credit_escrow")&&fail.getAndSet(false)){unknown.set(true);throw new IOException("Actual held file publication has an unreadable result");}return current;});
            var recovery=fixture.firstRead(fixture.actors.viewer.id(),file);fixture.untilFile(unknown::get);assertFalse(recovery.isDone());assertTrue(OrgPlayerFileLease.busy(fixture.actors.server,fixture.actors.viewer.id()));assertFalse(carpet.script.external.ScarpetNativeWork.whenIdle(fixture.actors.server).isDone());
            fixture.untilFile(recovery::isDone);recovery.join();tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());assertEquals(61,tag.getIntOr("XpTotal",-1));assertEquals("kept",tag.getStringOr("OtherPlugin",""));values=tag.getCompoundOrEmpty("BukkitValues");assertTrue(values.getStringOr("lophine:carpet_org_xp_credit","").endsWith(":held"));assertTrue(values.contains("lophine:carpet_org_xp_credit_escrow"));assertFalse(OrgPlayerFileLease.busy(fixture.actors.server,fixture.actors.viewer.id()));
            // Simulate only Native load's XP/PDC fields from the exact admitted tag into this existing real actor fixture.
            var player=fixture.actors.viewer.player();player.experienceLevel=tag.getIntOr("XpLevel",0);player.experienceProgress=tag.getFloatOr("XpP",0);player.totalExperience=tag.getIntOr("XpTotal",0);fixture.actors.viewer.pdc().put(new org.bukkit.NamespacedKey("lophine","carpet_org_xp_credit"),values.getStringOr("lophine:carpet_org_xp_credit",""));fixture.actors.viewer.pdc().put(new org.bukkit.NamespacedKey("lophine","carpet_org_xp_credit_escrow"),values.getStringOr("lophine:carpet_org_xp_credit_escrow",""));
            when(fixture.actors.server.getPlayerList().getPlayer(fixture.actors.viewer.id())).thenReturn(player);fixture.until(()->OrgExperienceTransfers.whenAvailable(fixture.actors.server,fixture.actors.viewer.id()).isDone());assertEquals(BigInteger.valueOf(104),OrgExperienceAmounts.read(player));
        }
    }
    @Test void aNativeCarpetBotUsesItsActualNativePlayerDataFileInsteadOfTheLegacyBotCopy()throws Exception{
        try(var fixture=new Fixture()){
            fixture.realPlayerFiles();var player=(org.leavesmc.leaves.bot.ServerBot)fixture.actors.actor(org.leavesmc.leaves.bot.ServerBot.class).player();player.carpetNativePlayer=true;player.experienceLevel=5;player.totalExperience=55;player.connection=mock(ServerGamePacketListenerImpl.class);
            var source=mock(CommandSourceStack.class);when(source.getServer()).thenReturn(fixture.actors.server);when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);when(player.createCommandSourceStack()).thenReturn(source);when(player.getXpNeededForNextLevel()).thenAnswer(call->player.experienceLevel>=30?9*player.experienceLevel-158:player.experienceLevel>=15?5*player.experienceLevel-38:2*player.experienceLevel+7);
            doAnswer(call->{player.experienceLevel=call.getArgument(0);player.experienceProgress=0;return null;}).when(player).setExperienceLevels(anyInt());doAnswer(call->{player.experienceProgress=(float)call.<Integer>getArgument(0)/player.getXpNeededForNextLevel();return null;}).when(player).setExperiencePoints(anyInt());
            fixture.actors.owner.set(player);var result=OrgExperienceTransfers.takeOffline(player,fixture.source);var actor=fixture.actors.actors.get(player.getUUID());long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!result.isDone()&&System.nanoTime()<deadline){fixture.actors.owner.set(player);OrgExperienceTransfers.tick(player);fixture.actors.process(actor);Thread.sleep(5);}assertTrue(result.isDone());assertTrue(result.join());assertEquals(BigInteger.valueOf(98),OrgExperienceAmounts.read(player));
            assertEquals(98,NbtIo.readCompressed(fixture.realPlayerFile(player.getUUID()),NbtAccounter.unlimitedHeap()).getIntOr("XpTotal",-1));verify(fixture.actors.server.getPlayerList(),atLeastOnce()).carpetSaveFakePlayer(player);assertFalse(Files.exists(directory.resolve("fakeplayerdata").resolve(player.getUUID()+".dat")));
        }
    }
    @Test void frozenWorldTimeDuringDrainCannotStopTheAcceptedUnknownXpCreditRetry()throws Exception{
        try(var fixture=new Fixture()){
            fixture.actors.unreadable.add(2);var result=fixture.take();fixture.until(()->fixture.actors.reads>=2);assertFalse(result.isDone());assertEquals(BigInteger.valueOf(55),OrgExperienceAmounts.read(fixture.actors.viewer.player()));
            when(fixture.actors.viewer.player().level().getGameTime()).thenReturn(17L);carpet.script.external.ScarpetNativeWork.beginDrain(fixture.actors.server);fixture.until(result::isDone);assertTrue(result.join());assertEquals(BigInteger.valueOf(98),OrgExperienceAmounts.read(fixture.actors.viewer.player()));
        }
    }
}
