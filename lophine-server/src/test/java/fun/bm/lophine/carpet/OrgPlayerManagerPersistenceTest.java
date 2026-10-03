package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgPlayerManagerPersistenceTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private static JsonObject profile(){JsonObject profile=new JsonObject();JsonObject pos=new JsonObject();pos.addProperty("x",1);pos.addProperty("y",70);pos.addProperty("z",2);profile.add("pos",pos);JsonObject direction=new JsonObject();direction.addProperty("yaw",0);direction.addProperty("pitch",0);profile.add("direction",direction);profile.addProperty("dimension","minecraft:overworld");profile.addProperty("gamemode","survival");profile.addProperty("annotation","saved");profile.addProperty("counter",0);JsonArray group=new JsonArray();group.add("builders");profile.add("group",group);profile.add("startup_action",new JsonArray());return profile;}
    @Test void concurrentProfileModificationsReloadTheLatestFileAndPreserveItsOtherMetadata()throws Exception{
        OrgPlayerProfileStore store=new OrgPlayerProfileStore(directory);store.save("Ada",profile(),false);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->{for(int i=0;i<15;i++)store.modify("Ada",value->{value.addProperty("counter",value.get("counter").getAsInt()+1);return value;});return null;});
            var second=executor.submit(()->{for(int i=0;i<15;i++)store.modify("Ada",value->{value.addProperty("counter",value.get("counter").getAsInt()+1);return value;});return null;});first.get();second.get();
        }
        JsonObject saved=store.load("Ada");assertEquals(30,saved.get("counter").getAsInt());assertEquals("saved",saved.get("annotation").getAsString());assertEquals("builders",saved.getAsJsonArray("group").get(0).getAsString());assertEquals(List.of("Ada"),store.names());
    }
    @Test void invalidUpdatesLeaveTheLastVerifiedProfileReadableAndPathsStayInsidePlayerData()throws Exception{
        OrgPlayerProfileStore store=new OrgPlayerProfileStore(directory);store.save("Ada",profile(),false);
        assertThrows(IllegalArgumentException.class,()->store.modify("Ada",value->{value.getAsJsonObject("pos").addProperty("x",Double.NaN);return value;}));assertEquals(1,store.load("Ada").getAsJsonObject("pos").get("x").getAsInt());
        assertThrows(IllegalArgumentException.class,()->store.save("../escape",profile(),false));assertFalse(Files.exists(directory.resolve("escape.json")));
        Files.writeString(directory.resolve("config/carpet-org-addition/player_data/Ada.json"),"{ broken");assertThrows(java.io.IOException.class,()->store.modify("Ada",value->profile()));assertEquals("{ broken",Files.readString(directory.resolve("config/carpet-org-addition/player_data/Ada.json")));
    }
    @Test void spawnReadsTheLatestRealPlayerFileInsteadOfAnOldReusableInventoryImage()throws Exception{
        MinecraftServer server=mock(MinecraftServer.class);when(server.getWorldPath(LevelResource.ROOT)).thenReturn(directory);
        var constructor=OrgPlayerManager.class.getDeclaredConstructor(MinecraftServer.class,CommandBuildContext.class);constructor.setAccessible(true);OrgPlayerManager manager=constructor.newInstance(server,null);
        UUID id=UUID.randomUUID();Path data=directory.resolve("playerdata").resolve(id+".dat");Files.createDirectories(data.getParent());
        CompoundTag old=new CompoundTag();old.putInt("XpTotal",20);old.putInt("item_count",20);JsonObject metadata=profile();metadata.addProperty("_lophine_state_file",directory.relativize(data).toString());metadata.addProperty("_lophine_native_state",OrgPlayerManager.encode(old));
        CompoundTag latest=new CompoundTag();latest.putInt("XpTotal",7);latest.putInt("item_count",9);NbtIo.writeCompressed(latest,data);
        CompoundTag restored=manager.readLatestState(metadata,new GameProfile(id,"Ada"));assertEquals(7,restored.getIntOr("XpTotal",-1));assertEquals(9,restored.getIntOr("item_count",-1));
        metadata.addProperty("_lophine_state_file","../foreign.dat");assertThrows(IllegalArgumentException.class,()->manager.readLatestState(metadata,new GameProfile(id,"Ada")));
    }
    @Test void aFakePlayerOutsideTheHumanRosterIsFoundThroughTheRealNameIndex() {
        MinecraftServer server=mock(MinecraftServer.class);var players=mock(net.minecraft.server.players.PlayerList.class);var source=mock(net.minecraft.commands.CommandSourceStack.class);
        var fake=mock(org.leavesmc.leaves.bot.ServerBot.class);when(source.getServer()).thenReturn(server);when(server.getPlayerList()).thenReturn(players);when(players.getPlayers()).thenReturn(List.of());when(players.getPlayerByName("fake")).thenReturn(fake);
        assertSame(fake,OrgFakePlayerActionCommands.find(source,"fake"));
    }
    @Test void aLegacyFakeTargetOutsideTheRosterCompletesItsSerialOwnerInventoryTransaction()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true)){
            assertFalse(fixture.server.getPlayerList().getPlayers().contains(fixture.target.player()));fixture.start();fixture.process(fixture.target);fixture.process(fixture.viewer);
            assertTrue(fixture.viewer.inventory().getItem(0).is(Items.EMERALD));assertEquals(20,fixture.viewer.inventory().getItem(0).getCount());assertTrue(fixture.target.inventory().getItem(0).is(Items.DIAMOND));assertEquals(10,fixture.target.inventory().getItem(0).getCount());
        }
    }
    @Test void persistedNativeItemPredicatesRetainTheirComponentMatchingSemantics()throws Exception{
        var lookup=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);CommandBuildContext access=CommandBuildContext.simple(lookup,FeatureFlags.DEFAULT_FLAGS);
        var filter=OrgFakePlayerActionCodec.filter("minecraft:emerald[minecraft:custom_data~{category:'material'}]",access);
        JsonObject encoded=OrgFakePlayerActionCodec.write(OrgFakePlayerActions.Action.simple("craft_inventory",List.of(filter,OrgFakePlayerActions.EMPTY,OrgFakePlayerActions.EMPTY,OrgFakePlayerActions.EMPTY)));
        var restored=OrgFakePlayerActionCodec.read(encoded,access);ItemStack accepted=new ItemStack(Items.EMERALD);CompoundTag data=new CompoundTag();data.putString("category","material");accepted.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        assertTrue(restored.filters().getFirst().test(accepted));assertFalse(restored.filters().getFirst().test(new ItemStack(Items.EMERALD)));assertTrue(restored.filters().get(1).test(ItemStack.EMPTY));assertEquals(encoded,OrgFakePlayerActionCodec.write(restored));
    }
}
