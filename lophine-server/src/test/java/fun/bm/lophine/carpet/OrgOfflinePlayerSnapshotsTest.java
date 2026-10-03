package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgOfflinePlayerSnapshotsTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private record Fixture(MinecraftServer server,UUID player,RegistryAccess.Frozen lookup) {
        CompoundTag data(){
            var data=NbtUtils.addCurrentDataVersion(new CompoundTag());data.put("UUID",UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE,player).getOrThrow());
            data.putInt("XpLevel",4);data.putInt("XpTotal",43);data.putFloat("XpP",0.25F);data.putString("CustomPluginState","preserved");return data;
        }
    }
    private Fixture fixture(){
        MinecraftServer server=mock(MinecraftServer.class);PlayerList players=mock(PlayerList.class);when(server.getPlayerList()).thenReturn(players);
        when(server.getWorldPath(any(LevelResource.class))).thenAnswer(call->call.getArgument(0)==LevelResource.PLAYER_DATA_DIR?directory.resolve("playerdata"):directory);
        var lookup=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);when(server.registryAccess()).thenReturn(lookup);return new Fixture(server,UUID.randomUUID(),lookup);
    }
    private static void inventory(Fixture fixture,CompoundTag data){
        var ops=fixture.lookup.createSerializationContext(NbtOps.INSTANCE);
        data.put("Inventory",ItemStackWithSlot.CODEC.listOf().encodeStart(ops,List.of(new ItemStackWithSlot(35,new ItemStack(Items.DIAMOND,7)))).getOrThrow());
        var equipment=new EntityEquipment();equipment.set(EquipmentSlot.OFFHAND,new ItemStack(Items.EMERALD,3));equipment.set(EquipmentSlot.BODY,new ItemStack(Items.DIAMOND,2));equipment.set(EquipmentSlot.SADDLE,new ItemStack(Items.GOLD_INGOT,1));
        data.put("equipment",EntityEquipment.CODEC.encodeStart(ops,equipment).getOrThrow());
        data.put("EnderItems",ItemStackWithSlot.CODEC.listOf().encodeStart(ops,List.of(new ItemStackWithSlot(26,new ItemStack(Items.GOLD_INGOT,11)))).getOrThrow());
    }
    @Test void readsNativeEquipmentEnderAndExperienceFromTheActualUuidFileWithoutAMutablePlayer()throws Exception{
        var fixture=fixture();var data=fixture.data();inventory(fixture,data);Path file=directory.resolve("playerdata").resolve(fixture.player+".dat");Files.createDirectories(file.getParent());NbtIo.writeCompressed(data,file);
        var saved=OrgOfflinePlayerSnapshots.read(fixture.server,fixture.player).get(5,TimeUnit.SECONDS);
        assertEquals(43,saved.inventory().size());assertEquals(7,saved.inventory().get(35).getCount());assertEquals(3,saved.inventory().get(40).getCount());assertEquals(2,saved.inventory().get(41).getCount());assertEquals(1,saved.inventory().get(42).getCount());assertEquals(11,saved.enderItems().get(26).getCount());assertEquals(4,saved.experienceLevel());assertEquals(43,saved.totalExperience());assertEquals(0.25F,saved.experienceProgress());assertEquals(data,saved.fileData());
        saved.inventory().get(35).shrink(3);saved.nativeData().putString("CustomPluginState","changed");assertEquals(7,saved.inventory().get(35).getCount());assertEquals("preserved",saved.nativeData().getStringOr("CustomPluginState",""));assertEquals(data,NbtIo.readCompressed(file,net.minecraft.nbt.NbtAccounter.unlimitedHeap()));
    }
    @Test void aColdZeroCountNativeSlotReadsTheLatestDurableCanonicalQuantityWithoutRegisteringAnAlias()throws Exception{
        var fixture=fixture();var ops=fixture.lookup.createSerializationContext(NbtOps.INSTANCE);ItemStack original=new ItemStack(Items.EMERALD,20);UUID id=OrgItemShadowGroups.share(original);var descriptor=OrgShadowInventoryCodec.descriptor(original,ops);descriptor.putInt("kind",0);descriptor.putInt("slot",2);
        String draft="config/carpet-org-addition/express/7.nbt";Path file=directory.resolve(draft);Files.createDirectories(file.getParent());var draftData=new CompoundTag();draftData.putBoolean("_lophine_draft",true);NbtIo.write(draftData,file);OrgItemShadowGroups.pin(fixture.server,draft,List.of(original));original.shrink(3);OrgItemShadowGroups.unpin(fixture.server,draft);
        // Emulate a cold process: only private native descriptors and the durable journal
        // survive. This old Java object is no longer a spendable registered alias.
        ItemStack remaining=original.copy();original.carpetOrgShadowId=null;original.carpetOrgShadowAnchor=null;original.components.carpetOrgShadowId=null;original.components.carpetOrgShadowAnchor=null;original.setCount(remaining.getCount());
        var groupsField=OrgItemShadowGroups.class.getDeclaredField("GROUPS");groupsField.setAccessible(true);Map<?,?> groups=(Map<?,?>)groupsField.get(null);groups.remove(id);
        var data=fixture.data();var identities=new ListTag();identities.add(descriptor);data.put("CarpetOrgEscrowShadows",identities);data.put("Inventory",new ListTag());
        var saved=OrgOfflinePlayerSnapshots.parse(fixture.server,fixture.player,data).get(5,TimeUnit.SECONDS);assertEquals(17,saved.inventory().get(2).getCount());assertTrue(saved.inventory().get(2).is(Items.EMERALD));assertNull(saved.inventory().get(2).carpetOrgShadowId);assertFalse(groups.containsKey(id));saved.inventory().get(2).shrink(4);assertEquals(17,original.getCount());assertEquals(20,descriptor.getIntOr("count",-1));
    }
    @Test void activeShadowCustodyIsAwaitedAndNeverReportedAsItsFrozenBeforeCount()throws Exception{
        var fixture=fixture();var ops=fixture.lookup.createSerializationContext(NbtOps.INSTANCE);ItemStack original=new ItemStack(Items.EMERALD,20);UUID id=OrgItemShadowGroups.share(original);var descriptor=OrgShadowInventoryCodec.descriptor(original,ops);descriptor.putInt("kind",0);descriptor.putInt("slot",0);var identities=new ListTag();identities.add(descriptor);var data=fixture.data();data.put("CarpetOrgEscrowShadows",identities);
        var preview=new OrgItemShadowGroups.Preview();preview.copies(List.of(original)).getFirst().shrink(3);var changes=preview.changes();UUID transaction=UUID.randomUUID();assertTrue(OrgItemShadowGroups.prepare(transaction,changes,false));
        try{
            var saved=OrgOfflinePlayerSnapshots.parse(fixture.server,fixture.player,data);assertFalse(saved.isDone());OrgItemShadowGroups.finish(transaction,changes,true);var snapshot=saved.get(5,TimeUnit.SECONDS);assertEquals(17,snapshot.inventory().getFirst().getCount());assertNull(snapshot.inventory().getFirst().carpetOrgShadowId);
        }finally{OrgItemShadowGroups.forget(transaction,changes);}
    }
    @Test void anOnlineUnknownNameUuidCannotEnterTheOfflineSnapshotPath(){
        var fixture=fixture();when(fixture.server.getPlayerList().getPlayer(fixture.player)).thenReturn(mock(net.minecraft.server.level.ServerPlayer.class));
        var result=OrgOfflinePlayerSnapshots.parse(fixture.server,fixture.player,fixture.data());assertThrows(java.util.concurrent.CompletionException.class,result::join);
        verify(fixture.server.getPlayerList()).getPlayer(fixture.player);
    }
}
