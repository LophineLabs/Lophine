package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgOfflineInventorySessionTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private final class Fixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors=new OrgInventoryPersistenceTest.Fixture(directory);
        final UUID target=UUID.randomUUID();final Path file=directory.resolve("playerdata").resolve(target+".dat");
        final String permission=GeneralCompatConfig.playerCommandOpenPlayerInventory,option=GeneralCompatConfig.playerCommandOpenPlayerInventoryOption;
        Fixture()throws Exception{
            GeneralCompatConfig.playerCommandOpenPlayerInventory="true";GeneralCompatConfig.playerCommandOpenPlayerInventoryOption="all_player";
            when(actors.server.getWorldPath(LevelResource.PLAYER_DATA_DIR)).thenReturn(directory.resolve("playerdata"));
            var source=mock(CommandSourceStack.class);when(source.getServer()).thenReturn(actors.server);when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);when(actors.viewer.player().createCommandSourceStack()).thenReturn(source);
            when(actors.viewer.player().openMenu(any(MenuProvider.class))).thenAnswer(call->{
                MenuProvider provider=call.getArgument(0);actors.viewer.player().containerMenu.removed(actors.viewer.player());AbstractContainerMenu menu=provider.createMenu(51,actors.viewer.inventory(),actors.viewer.player());actors.viewer.player().containerMenu=menu;return java.util.OptionalInt.of(51);
            });
            doAnswer(call->{AbstractContainerMenu old=actors.viewer.player().containerMenu;actors.viewer.player().containerMenu=actors.viewer.player().inventoryMenu;old.removed(actors.viewer.player());return null;}).when(actors.viewer.player()).closeContainer();
            var data=NbtUtils.addCurrentDataVersion(new CompoundTag());data.put("UUID",UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE,target).getOrThrow());data.putString("PluginState","kept");data.putInt("XpLevel",3);data.putInt("XpTotal",34);data.putFloat("XpP",0.2F);data.put("Inventory",ItemStackWithSlot.CODEC.listOf().encodeStart(actors.lookup.createSerializationContext(NbtOps.INSTANCE),List.of(new ItemStackWithSlot(0,new ItemStack(Items.EMERALD,20)))).getOrThrow());Files.createDirectories(file.getParent());NbtIo.writeCompressed(data,file);
        }
        void driveUntil(java.util.function.BooleanSupplier done)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!done.getAsBoolean()&&System.nanoTime()<deadline){actors.process(actors.viewer);Thread.sleep(5);}assertTrue(done.getAsBoolean(),"Actual owner/file actor tasks did not terminate");}
        AbstractContainerMenu open()throws Exception{actors.owner.set(actors.viewer.player());var opened=OrgPlayerInventoryMenus.openOffline(actors.viewer.player(),target,false);driveUntil(opened::isDone);assertTrue(opened.join());assertTrue(OrgPlayerFileLease.busy(actors.server,target));return actors.viewer.player().containerMenu;}
        void swap(AbstractContainerMenu menu)throws Exception{actors.owner.set(actors.viewer.player());menu.clicked(0,0,ContainerInput.SWAP,actors.viewer.player());driveUntil(()->actors.viewer.inventory().getItem(0).is(Items.EMERALD)&&OrgInventoryTransfers.whenAvailable(actors.server,actors.viewer.id()).isDone());}
        void closeMenu()throws Exception{actors.owner.set(actors.viewer.player());actors.viewer.player().closeContainer();driveUntil(()->!OrgPlayerFileLease.busy(actors.server,target));}
        CompoundTag saved()throws Exception{return NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());}
        @Override public void close(){GeneralCompatConfig.playerCommandOpenPlayerInventory=permission;GeneralCompatConfig.playerCommandOpenPlayerInventoryOption=option;actors.close();}
    }
    @Test void nativeSwapUsesRealFileCustodyPreservesPlayerFieldsAndUnlocksLoginOnlyAfterCloseReadback()throws Exception{
        try(var fixture=new Fixture()){
            AbstractContainerMenu menu=fixture.open();fixture.swap(menu);assertEquals(20,fixture.actors.viewer.inventory().getItem(0).getCount());assertTrue(fixture.saved().getBooleanOr(OrgOfflineInventorySessions.CUSTODY,false));assertEquals("kept",fixture.saved().getStringOr("PluginState",""));assertEquals(34,fixture.saved().getIntOr("XpTotal",-1));
            var admitted=new AtomicBoolean();var login=OrgPlayerFileLease.withLease(fixture.actors.server,fixture.target,"next real login",lease->{admitted.set(true);return CompletableFuture.completedFuture(true);});assertFalse(admitted.get());fixture.closeMenu();assertTrue(login.join());assertTrue(admitted.get());assertFalse(fixture.saved().contains(OrgOfflineInventorySessions.CUSTODY));var snapshot=OrgOfflinePlayerSnapshots.parse(fixture.actors.server,fixture.target,fixture.saved()).get(5,TimeUnit.SECONDS);assertTrue(snapshot.inventory().getFirst().is(Items.DIAMOND));assertEquals(10,snapshot.inventory().getFirst().getCount());assertEquals("kept",snapshot.nativeData().getStringOr("PluginState",""));
        }
    }
    @Test void fullPointerMoveStaysAliasedAcrossTheOpenOfflineMenuAndCloseCopiesTheLatestQuantity()throws Exception{
        try(var fixture=new Fixture()){
            ItemStack alias=fixture.actors.viewer.inventory().getItem(0);OrgItemShadowGroups.share(alias);fixture.actors.viewer.inventory().setItem(1,alias);AbstractContainerMenu menu=fixture.open();fixture.swap(menu);fixture.actors.owner.set(fixture.actors.viewer.player());alias.shrink(3);menu.broadcastChanges();assertEquals(7,menu.getSlot(0).getItem().getCount());assertEquals(alias.carpetOrgShadowId,menu.getSlot(0).getItem().carpetOrgShadowId);
            fixture.closeMenu();var snapshot=OrgOfflinePlayerSnapshots.parse(fixture.actors.server,fixture.target,fixture.saved()).get(5,TimeUnit.SECONDS);assertEquals(7,snapshot.inventory().getFirst().getCount());assertNull(snapshot.inventory().getFirst().carpetOrgShadowId);assertEquals(7,alias.getCount());assertFalse(fixture.saved().contains("CarpetOrgEscrowShadows"));
        }
    }
    @Test void passiveGuiIsNotAGlobalNativeJobAndDrainClosesItBeforeReleasingItsUuidFileLease()throws Exception{
        try(var fixture=new Fixture()){
            fixture.open();assertTrue(carpet.script.external.ScarpetNativeWork.whenIdle(fixture.actors.server).isDone());carpet.script.external.ScarpetNativeWork.beginDrain(fixture.actors.server);OrgPlayerInventoryMenus.beginDrain(fixture.actors.server);
            var nativeIdle=carpet.script.external.ScarpetNativeWork.whenIdle(fixture.actors.server);assertFalse(nativeIdle.isDone(),"Queued actual owner close must already be in the native barrier");
            fixture.driveUntil(()->!OrgPlayerFileLease.busy(fixture.actors.server,fixture.target)&&nativeIdle.isDone());nativeIdle.join();assertFalse(fixture.saved().contains(OrgOfflineInventorySessions.CUSTODY));var rejected=OrgPlayerInventoryMenus.openOffline(fixture.actors.viewer.player(),fixture.target,false);fixture.driveUntil(rejected::isDone);assertFalse(rejected.join());
        }
    }
    @Test void policyMatchesAllFourSourceOptionsAndRechecksProtectedTargets(){
        for(String option:List.of("fake_player","online_player","non_whitelist","all_player"))assertTrue(OrgPlayerInventoryMenus.optionAllowed(option,true,true,true,true));
        assertFalse(OrgPlayerInventoryMenus.optionAllowed("fake_player",false,true,true,false));assertFalse(OrgPlayerInventoryMenus.optionAllowed("online_player",false,false,true,false));assertTrue(OrgPlayerInventoryMenus.optionAllowed("online_player",false,true,false,false));assertFalse(OrgPlayerInventoryMenus.optionAllowed("non_whitelist",false,true,true,false));assertTrue(OrgPlayerInventoryMenus.optionAllowed("non_whitelist",false,false,false,false));assertFalse(OrgPlayerInventoryMenus.optionAllowed("non_whitelist",true,true,false,true));assertTrue(OrgPlayerInventoryMenus.optionAllowed("non_whitelist",false,false,true,true));assertTrue(OrgPlayerInventoryMenus.optionAllowed("all_player",false,false,false,true));
    }
    @Test void nativeFirstReadRecoveryFinalizesAColdCustodyFileToLatestCanonicalWithoutAnotherUuidLease()throws Exception{
        try(var fixture=new Fixture()){
            ItemStack alias=fixture.actors.viewer.inventory().getItem(0);UUID id=OrgItemShadowGroups.share(alias);var raw=fixture.saved();var descriptor=OrgShadowInventoryCodec.descriptor(alias,fixture.actors.lookup.createSerializationContext(NbtOps.INSTANCE));descriptor.putInt("kind",0);descriptor.putInt("slot",0);var shadows=new net.minecraft.nbt.ListTag();shadows.add(descriptor);raw.put("CarpetOrgEscrowShadows",shadows);raw.putBoolean(OrgOfflineInventorySessions.CUSTODY,true);NbtIo.writeCompressed(raw,fixture.file);String relative=directory.toAbsolutePath().normalize().relativize(fixture.file.toAbsolutePath().normalize()).toString();OrgItemShadowGroups.pin(fixture.actors.server,relative,List.of(alias));alias.shrink(3);
            var recovery=OrgPlayerFileLease.withLease(fixture.actors.server,fixture.target,"actual first NBT read",lease->OrgPlayerInventoryMenus.recoverOfflineBeforeRead(fixture.actors.server,fixture.target));fixture.driveUntil(recovery::isDone);recovery.join();assertFalse(OrgPlayerFileLease.busy(fixture.actors.server,fixture.target));assertFalse(fixture.saved().contains(OrgOfflineInventorySessions.CUSTODY));assertFalse(fixture.saved().contains("CarpetOrgEscrowShadows"));var snapshot=OrgOfflinePlayerSnapshots.parse(fixture.actors.server,fixture.target,fixture.saved()).get(5,TimeUnit.SECONDS);assertEquals(7,snapshot.inventory().getFirst().getCount());assertEquals(7,alias.getCount());
        }
    }
    @Test void openingRegistersItsPreparationBeforeTheUuidFileActorIsAdmittedAndDrainCannotPassIt()throws Exception{
        try(var fixture=new Fixture()){
            var release=new CompletableFuture<Void>();var held=OrgPlayerFileLease.withLease(fixture.actors.server,fixture.target,"older real login/file actor",lease->release);fixture.actors.owner.set(fixture.actors.viewer.player());var opening=OrgPlayerInventoryMenus.openOffline(fixture.actors.viewer.player(),fixture.target,false);
            var idle=carpet.script.external.ScarpetNativeWork.whenIdle(fixture.actors.server);assertFalse(opening.isDone());assertFalse(idle.isDone(),"Opening job must be registered before ACTIVE publication and actual first file read");
            carpet.script.external.ScarpetNativeWork.beginDrain(fixture.actors.server);OrgPlayerInventoryMenus.beginDrain(fixture.actors.server);assertFalse(idle.isDone());release.complete(null);fixture.driveUntil(()->opening.isDone()&&idle.isDone()&&!OrgPlayerFileLease.busy(fixture.actors.server,fixture.target));assertFalse(opening.join());idle.join();assertFalse(fixture.saved().contains(OrgOfflineInventorySessions.CUSTODY));
        }
    }
}
