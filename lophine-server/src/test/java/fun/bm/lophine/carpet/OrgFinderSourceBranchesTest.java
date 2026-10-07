package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetNativeWork;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.Lifecycle;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.Stream;
import net.minecraft.commands.*;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.*;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.trading.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Executable search branches, source messages and the true output/native-child lifetimes. */
class OrgFinderSourceBranchesTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){
        OrgInventoryPersistenceTest.bootstrap();OrgFinderStatisticsTest.bootstrap();
        for(var item:List.of(Items.STONE,Items.ENCHANTED_BOOK,Items.DIRT))try{item.builtInRegistryHolder().components();}catch(NullPointerException unbound){item.builtInRegistryHolder().bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE,64).build());}
    }
    final class Fixture implements AutoCloseable {
        final OrgInventoryPersistenceTest.Fixture actors=new OrgInventoryPersistenceTest.Fixture(directory,true);
        final ServerPlayer player=actors.viewer.player();final ServerLevel world=player.level();
        final CommandSourceStack source=mock(CommandSourceStack.class);final List<Component> messages=new CopyOnWriteArrayList<>(),rows=new CopyOnWriteArrayList<>();
        final List<ClientboundSetActionBarTextPacket> overlays=new CopyOnWriteArrayList<>();
        final org.mockito.MockedStatic<CarpetRegionLease> leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        final org.mockito.MockedStatic<io.papermc.paper.configuration.GlobalConfiguration> configuration=mockStatic(io.papermc.paper.configuration.GlobalConfiguration.class);
        final net.minecraft.server.Services services=mock(net.minecraft.server.Services.class,RETURNS_DEEP_STUBS);
        final String oldPermission=GeneralCompatConfig.commandFinder;
        Fixture()throws Exception{
            var settings=new io.papermc.paper.configuration.GlobalConfiguration();settings.unsupportedSettings=settings.new UnsupportedSettings();configuration.when(io.papermc.paper.configuration.GlobalConfiguration::get).thenReturn(settings);
            GeneralCompatConfig.commandFinder="true";actors.owner.set(player);when(player.blockPosition()).thenReturn(BlockPos.ZERO);player.connection=mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
            when(source.getServer()).thenReturn(actors.server);when(source.getPlayer()).thenReturn(player);when(source.getPlayerOrException()).thenReturn(player);when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);when(source.getLevel()).thenReturn(world);when(source.getPosition()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);when(player.createCommandSourceStack()).thenReturn(source);
            doAnswer(call->{messages.add(call.getArgument(0));return null;}).when(player).sendSystemMessage(any(Component.class));
            doAnswer(call->{rows.add(call.<Supplier<Component>>getArgument(0).get());return null;}).when(source).sendSuccess(any(),eq(false));
            doAnswer(call->{overlays.add(call.getArgument(0));return null;}).when(player.connection).send(any(ClientboundSetActionBarTextPacket.class));
            when(actors.server.services()).thenReturn(services);when(services.nameToIdCache().get(any(UUID.class))).thenReturn(Optional.empty());
            when(world.getMinY()).thenReturn(0);when(world.getMaxY()).thenReturn(0);
            actors.ticks.when(()->ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(any(Entity.class))).thenReturn(true);
            leases.when(()->CarpetRegionLease.runLoadedValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any(Function.class))).thenAnswer(call->CompletableFuture.completedFuture(((Function<?,?>)call.getArgument(5)).apply(null)));
        }
        CommandDispatcher<CommandSourceStack> commands(){
            var enchantments=new MappedRegistry<Enchantment>(Registries.ENCHANTMENT,Lifecycle.stable());Registry.register(enchantments,"mending",mock(Enchantment.class));enchantments.freeze();
            var lookup=HolderLookup.Provider.create(Stream.concat(BuiltInRegistries.REGISTRY.stream().map(registry->(HolderLookup.RegistryLookup<?>)registry),Stream.of(enchantments)));
            var dispatcher=new CommandDispatcher<CommandSourceStack>();OrgFinderCommands.register(dispatcher,CommandBuildContext.simple(lookup,FeatureFlags.DEFAULT_FLAGS));return dispatcher;
        }
        void drive(BooleanSupplier done)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!done.getAsBoolean()&&System.nanoTime()<until){actors.drain(actors.viewer);Thread.sleep(3);}assertTrue(done.getAsBoolean(),"Actual Finder/owner operation must terminate");}
        LevelChunk chunk(List<? extends Entity> entities){var chunk=mock(LevelChunk.class);when(chunk.getBlockEntities()).thenReturn(Map.of());when(world.getChunkIfLoaded(0,0)).thenReturn(chunk);doReturn(entities).when(world).getEntitiesOfClass(eq(Entity.class),any(AABB.class));return chunk;}
        @Override public void close(){configuration.close();leases.close();actors.close();GeneralCompatConfig.commandFinder=oldPermission;}
    }
    private static TranslatableContentsView view(Component value){var contents=(net.minecraft.network.chat.contents.TranslatableContents)value.getContents();return new TranslatableContentsView(contents.getKey(),contents.getArgs());}
    private record TranslatableContentsView(String key,Object[] args){}
    private Component message(Fixture f,String suffix){return f.messages.stream().filter(value->value.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text&&text.getKey().equals(OrgFinderText.KEY+suffix)).findFirst().orElseThrow();}
    @Test void registeredBlockCommandRunsNativeSearchProgressAndCountsBlocksRatherThanGroups()throws Exception{
        try(var f=new Fixture()){
            var chunk=f.chunk(List.of());var section=mock(LevelChunkSection.class);when(section.maybeHas(any())).thenReturn(true);when(chunk.getSection(0)).thenReturn(section);
            when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.defaultBlockState());when(f.world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.defaultBlockState());when(f.world.hasChunkAt(any(BlockPos.class))).thenReturn(true);
            int returned=f.commands().execute("finder block stone from 0 0 0 to 1 0 0",f.source);assertEquals(1,returned);var idle=ScarpetNativeWork.whenIdle(f.actors.server);f.drive(idle::isDone);idle.join();
            assertFalse(f.overlays.isEmpty(),f.messages.toString());assertEquals("carpet-org-addition.command.finder.block.progress",view(f.overlays.getFirst().text()).key());
            assertEquals(2,view(message(f,"block.head")).args()[0]);assertEquals(1,f.rows.size());assertEquals("carpet-org-addition.command.finder.block.each",view(f.rows.getFirst()).key());assertEquals(2,view(f.rows.getFirst()).args()[1]);
        }
    }
    @Test void actualItemSearchCountsNestedDropsAndContainerRowsWithSourceHover()throws Exception{
        try(var f=new Fixture()){
            var drop=mock(ItemEntity.class);when(drop.getUUID()).thenReturn(UUID.randomUUID());when(drop.level()).thenReturn(f.world);when(drop.blockPosition()).thenReturn(BlockPos.ZERO);when(drop.getItem()).thenReturn(new ItemStack(Items.STONE,70));f.chunk(List.of(drop));
            var actual=OrgFinderService.start(f.source,f.player,new OrgFinderBounds(0,0,0,0,0,0),OrgFinderService.Kind.ITEM,null,stack->stack.is(Items.STONE),null,state->true,OrgFinderText.itemLabel("stone",stack->stack.is(Items.STONE)),"stone");f.drive(actual::isDone);actual.join();
            var head=view(message(f,"item.head"));assertEquals(1,head.args()[0]);assertEquals("70",((Component)head.args()[1]).getString());
            Component total=(Component)head.args()[1];assertEquals("carpet-org-addition.item.count",view(((HoverEvent.ShowText)total.getStyle().getHoverEvent()).value()).key());
            var row=view(f.rows.getFirst());assertEquals("carpet-org-addition.command.finder.item.each",row.key());assertEquals("carpet-org-addition.item.drops",view((Component)row.args()[1]).key());
        }
    }
    @Test void actualTradeGroupsOfferIndicesButCountsEachMerchantOnce()throws Exception{
        try(var f=new Fixture()){
            var merchant=mock(AbstractVillager.class);when(merchant.getUUID()).thenReturn(UUID.randomUUID());when(merchant.level()).thenReturn(f.world);when(merchant.blockPosition()).thenReturn(BlockPos.ZERO);when(merchant.getName()).thenReturn(Component.literal("merchant"));var offers=new MerchantOffers();for(int i=0;i<3;i++)offers.add(new MerchantOffer(new ItemCost(Items.EMERALD,1),new ItemStack(Items.STONE),10,1,0F));when(merchant.getOffers()).thenReturn(offers);f.chunk(List.of(merchant));
            var actual=OrgFinderService.start(f.source,f.player,new OrgFinderBounds(0,0,0,0,0,0),OrgFinderService.Kind.TRADE,null,stack->stack.is(Items.STONE),null,state->true,Component.literal("stone"),"stone");f.drive(actual::isDone);actual.join();
            var head=view(message(f,"trade.item.head"));assertEquals(1,head.args()[0]);assertEquals(3,head.args()[3]);assertEquals("[1, 2, 3]",view(f.rows.getFirst()).args()[2]);
        }
    }
    @Test void actualBookQueryGroupsLevelsAndSortsHighestLevelFirst()throws Exception{
        try(var f=new Fixture()){
            var enchantment=new Enchantment(Component.literal("Mending"),Enchantment.definition(HolderSet.direct(List.of()),1,3,Enchantment.constantCost(1),Enchantment.constantCost(1),1),HolderSet.direct(List.of()),DataComponentMap.EMPTY);Holder<Enchantment> holder=Holder.direct(enchantment);
            var merchant=mock(AbstractVillager.class);when(merchant.getUUID()).thenReturn(UUID.randomUUID());when(merchant.level()).thenReturn(f.world);when(merchant.blockPosition()).thenReturn(BlockPos.ZERO);when(merchant.getName()).thenReturn(Component.literal("merchant"));var offers=new MerchantOffers();
            for(int level:List.of(1,3,3)){var book=new ItemStack(Items.ENCHANTED_BOOK);var mutable=new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);mutable.set(holder,level);book.set(DataComponents.STORED_ENCHANTMENTS,mutable.toImmutable());offers.add(new MerchantOffer(new ItemCost(Items.EMERALD,1),book,10,1,0F));}when(merchant.getOffers()).thenReturn(offers);f.chunk(List.of(merchant));
            var actual=OrgFinderService.start(f.source,f.player,new OrgFinderBounds(0,0,0,0,0,0),OrgFinderService.Kind.BOOK,null,null,holder,state->true,enchantment.description(),"");f.drive(actual::isDone);actual.join();
            assertEquals(1,view(message(f,"trade.enchanted_book.head")).args()[0]);assertEquals(3,view(message(f,"trade.enchanted_book.head")).args()[3]);assertEquals("[2, 3]",view(f.rows.getFirst()).args()[2]);assertEquals("1",view(f.rows.getLast()).args()[2]);
        }
    }
    @Test void actualEmptyAndStopBranchesUseSourceTranslatedMessagesAndFeedbackChildren()throws Exception{
        try(var f=new Fixture()){
            f.chunk(List.of());var child=new CompletableFuture<Void>();doAnswer(call->{f.messages.add(call.getArgument(0));ScarpetNativeWork.record(child);return null;}).when(f.player).sendSystemMessage(any(Component.class));
            var root=ScarpetNativeWork.observeNative(null,()->OrgFinderService.start(f.source,f.player,new OrgFinderBounds(0,0,0,0,0,0),OrgFinderService.Kind.ITEM,null,stack->false,null));var idle=ScarpetNativeWork.whenIdle(f.actors.server);f.actors.drain(f.actors.viewer);assertFalse(root.isDone());assertFalse(idle.isDone());assertTrue(OrgFinderService.stop(f.player));child.complete(null);f.drive(root::isDone);root.join();message(f,"item.cannot_find");assertFalse(OrgFinderService.stop(f.player));
            assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,()->f.commands().execute("finder stop",f.source));
        }
    }
    private CompoundTag file(Fixture f,UUID uuid,List<ItemStackWithSlot> inventory,List<ItemStackWithSlot> ender)throws Exception{
        var data=NbtUtils.addCurrentDataVersion(new CompoundTag());data.put("UUID",UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE,uuid).getOrThrow());var ops=f.actors.lookup.createSerializationContext(NbtOps.INSTANCE);data.put("Inventory",ItemStackWithSlot.CODEC.listOf().encodeStart(ops,inventory).getOrThrow());data.put("EnderItems",ItemStackWithSlot.CODEC.listOf().encodeStart(ops,ender).getOrThrow());var dir=directory.resolve("playerdata");Files.createDirectories(dir);when(f.actors.storage.getPlayerDir()).thenReturn(dir.toFile());when(f.actors.server.getWorldPath(LevelResource.PLAYER_DATA_DIR)).thenReturn(dir);NbtIo.writeCompressed(data,dir.resolve(uuid+".dat"));return data;
    }
    @Test void registeredOfflineItemQueryCountsInventoryAndEnderRowsAndRetainsSourceUnknownButtons()throws Exception{
        try(var f=new Fixture()){
            UUID uuid=UUID.randomUUID();file(f,uuid,List.of(new ItemStackWithSlot(0,new ItemStack(Items.STONE,3))),List.of(new ItemStackWithSlot(0,new ItemStack(Items.STONE,4))));
            assertEquals(1,f.commands().execute("finder item stone from offline_player",f.source));var idle=ScarpetNativeWork.whenIdle(f.actors.server);f.drive(idle::isDone);idle.join();
            var head=view(message(f,"item.offline_player.head"));assertEquals("2",((Component)head.args()[0]).getString());assertEquals("7",((Component)head.args()[1]).getString());assertEquals(2,f.rows.size());assertTrue(f.rows.getFirst().getString().contains("[Unknown]"));assertTrue(f.rows.getFirst().getString().contains("🔍"));
        }
    }
    @Test void registeredOfflineXpShowsSummedLevelAndExactPointsHoverRatherThanRawPoints()throws Exception{
        try(var f=new Fixture();var hidden=mockStatic(OrgHiddenPlayerActions.class)){
            hidden.when(OrgHiddenPlayerActions::enabled).thenReturn(true);UUID uuid=UUID.randomUUID();var data=file(f,uuid,List.of(),List.of());data.putInt("XpLevel",10);data.putInt("XpTotal",160);NbtIo.writeCompressed(data,directory.resolve("playerdata").resolve(uuid+".dat"));
            f.commands().execute("finder xp from offline_player",f.source);var idle=ScarpetNativeWork.whenIdle(f.actors.server);f.drive(idle::isDone);idle.join();var amount=(Component)view(message(f,"xp.offline_player.head")).args()[1];assertEquals("10",amount.getString());assertEquals("160",((HoverEvent.ShowText)amount.getStyle().getHoverEvent()).value().getString());assertFalse(f.rows.getFirst().getString().contains("TAKE"));
        }
    }
    @Test void cachedQueryNameActualClickHasNoFinderPermissionGateAndWaitsNativeFeedback()throws Exception{
        try(var f=new Fixture()){
            GeneralCompatConfig.commandFinder="false";UUID uuid=UUID.randomUUID();when(f.services.nameToIdCache().get(uuid)).thenReturn(Optional.of(new net.minecraft.server.players.NameAndId(uuid,"source_name")));var child=new CompletableFuture<Void>();doAnswer(call->{f.messages.add(call.getArgument(0));ScarpetNativeWork.record(child);return null;}).when(f.player).sendSystemMessage(any(Component.class));
            var data=new CompoundTag();data.putString("uuid",uuid.toString());data.putInt("data_version",1);var root=ScarpetNativeWork.observeNative(null,()->{OrgFinderFiles.customClick(f.player,net.minecraft.resources.Identifier.fromNamespaceAndPath("carpet-org-addition","query_player_name"),Optional.of(data));return true;});var idle=ScarpetNativeWork.whenIdle(f.actors.server);assertFalse(root.isDone());assertFalse(idle.isDone());assertEquals("carpet-org-addition.operation.query_player_name.success",view(f.messages.getFirst()).key());child.complete(null);root.join();idle.join();
        }
    }
    @Test void staticPredicateLabelsAndGroupHoversMatchSourceFormatting(){
        assertEquals("carpet-org-addition.item.any_item",view(OrgFinderText.itemLabel("*[]",stack->false)).key());assertEquals("3",OrgFinderText.total("stone",3,false).getString());assertEquals("carpet-org-addition.item.remainder",view(((HoverEvent.ShowText)OrgFinderText.total("stone",3,false).getStyle().getHoverEvent()).value()).key());assertEquals("100%",OrgFinderText.progress(4,4).getString());assertEquals("10",OrgFinderText.experienceLevel(BigInteger.valueOf(160)).getString());
        var world=mock(ServerLevel.class);when(world.getMinY()).thenReturn(-64);when(world.getMaxY()).thenReturn(319);assertEquals(-100,OrgFinderBounds.ofEntities(world,new BlockPos(0,-100,0),new BlockPos(0,400,0)).minY());assertEquals(400,OrgFinderBounds.ofEntities(world,new BlockPos(0,-100,0),new BlockPos(0,400,0)).maxY());
    }
    private ClickEvent.Custom click(Component component){if(component.getStyle().getClickEvent() instanceof ClickEvent.Custom event)return event;for(Component sibling:component.getSiblings()){try{return click(sibling);}catch(NoSuchElementException ignored){}}throw new NoSuchElementException();}
    @Test void actualPageClickRendersSourceFooterBlankLineAndWaitsDynamicallyAppendedNativeChildren()throws Exception{
        int old=GeneralCompatConfig.maxLinesPerPage;GeneralCompatConfig.maxLinesPerPage=1;
        try(var f=new Fixture()){
            OrgPages.print(f.source,List.of(Component.literal("first"),Component.literal("second")));assertEquals("first",f.messages.getFirst().getString());var button=click(f.messages.getLast());f.messages.clear();
            var child=new CompletableFuture<Void>();var late=new CompletableFuture<Void>();doAnswer(call->{f.messages.add(call.getArgument(0));ScarpetNativeWork.record(child);child.whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((Void ignored,Throwable failure)->ScarpetNativeWork.record(late)));return null;}).when(f.player).sendSystemMessage(any(Component.class));var root=ScarpetNativeWork.observeNative(null,()->{OrgPages.customClick(f.player,button.id(),button.payload());return true;});var idle=ScarpetNativeWork.whenIdle(f.actors.server);assertFalse(root.isDone());assertFalse(idle.isDone());assertEquals("",f.messages.getFirst().getString());assertEquals("second",f.messages.get(1).getString());
            child.complete(null);assertFalse(root.isDone());late.complete(null);root.join();idle.join();
        }finally{GeneralCompatConfig.maxLinesPerPage=old;}
    }
    @Test void actualPageMissingInvalidAndExpiredClicksUseSourceErrors()throws Exception{
        int old=GeneralCompatConfig.maxLinesPerPage;GeneralCompatConfig.maxLinesPerPage=1;
        try(var f=new Fixture()){
            OrgPages.print(f.source,List.of(Component.literal("first"),Component.literal("second")));var event=click(f.messages.getLast());var data=((CompoundTag)event.payload().orElseThrow()).copy();data.putInt("page_number",3);OrgPages.customClick(f.player,event.id(),Optional.of(data));assertEquals("carpet-org-addition.operation.page.invalid_index",view(f.messages.getLast()).key());
            data.putInt("id",Integer.MAX_VALUE);OrgPages.customClick(f.player,event.id(),Optional.of(data));assertEquals("carpet-org-addition.operation.page.non_existent",view(f.messages.getLast()).key());
            data.putInt("data_version",2);int before=f.messages.size();OrgPages.customClick(f.player,event.id(),Optional.of(data));assertEquals("carpet-org-addition.custom_click_action.expired",view(f.messages.getLast()).key());OrgPages.customClick(f.player,event.id(),Optional.of(data));assertEquals(before+1,f.messages.size());
        }finally{GeneralCompatConfig.maxLinesPerPage=old;}
    }
    @Test void actualWorldEaterPredicateUsesAllPinnedWaterResistanceAndBelowEightBranches()throws Exception{
        try(var f=new Fixture()){var world=f.world;BlockPos pos=BlockPos.ZERO;
        for(Block block:List.of(Blocks.BEDROCK,Blocks.AIR,Blocks.WATER,Blocks.DANDELION)){when(world.getBlockState(pos)).thenReturn(block.defaultBlockState());assertFalse(OrgFinderService.worldEater(world,pos),block.toString());}
        when(world.getBlockState(pos)).thenReturn(Blocks.OBSIDIAN.defaultBlockState());assertTrue(OrgFinderService.worldEater(world,pos));when(world.getBlockState(pos)).thenReturn(Blocks.STONE.defaultBlockState());assertFalse(OrgFinderService.worldEater(world,pos));
        when(world.getBlockState(pos)).thenReturn(Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true));assertTrue(OrgFinderService.worldEater(world,pos));
        var wet=Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true);when(world.getBlockState(pos)).thenReturn(wet);
        for(int offset=1;offset<=8;offset++)when(world.getBlockState(pos.below(offset))).thenReturn(Blocks.STONE.defaultBlockState());assertTrue(OrgFinderService.worldEater(world,pos));verify(world).getBlockState(pos.below(8));
        when(world.getBlockState(pos.below(3))).thenReturn(Blocks.DANDELION.defaultBlockState());assertFalse(OrgFinderService.worldEater(world,pos));when(world.getBlockState(pos.below(2))).thenReturn(Blocks.BEDROCK.defaultBlockState());assertTrue(OrgFinderService.worldEater(world,pos));}
    }
    @Test void registeredWorldEaterSearchUsesSourceLabelAndOwnedProgress()throws Exception{
        try(var f=new Fixture();var hidden=mockStatic(OrgHiddenPlayerActions.class)){
            hidden.when(OrgHiddenPlayerActions::enabled).thenReturn(true);var chunk=f.chunk(List.of());var section=mock(LevelChunkSection.class);when(section.maybeHas(any())).thenReturn(true);when(chunk.getSection(0)).thenReturn(section);when(chunk.getBlockState(any(BlockPos.class))).thenReturn(Blocks.OBSIDIAN.defaultBlockState());when(f.world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.OBSIDIAN.defaultBlockState());
            f.commands().execute("finder worldEater 0 0 0 0 0 0",f.source);var idle=ScarpetNativeWork.whenIdle(f.actors.server);f.drive(idle::isDone);idle.join();assertEquals("carpet-org-addition.command.finder.world_eater.head",view((Component)view(message(f,"block.head")).args()[1]).key());assertFalse(f.overlays.isEmpty());
        }
    }
    @Test void uncachedActualQueryNameTracksResolverSuccessCacheAndFailureWithOnePendingRequest()throws Exception{
        try(var f=new Fixture()){
            UUID uuid=UUID.randomUUID();var pending=new CompletableFuture<com.mojang.authlib.GameProfile>();var unresolved=spy(net.minecraft.world.item.component.ResolvableProfile.createUnresolved(uuid));try(var profiles=mockStatic(net.minecraft.world.item.component.ResolvableProfile.class)){profiles.when(()->net.minecraft.world.item.component.ResolvableProfile.createUnresolved(uuid)).thenReturn(unresolved);doReturn(pending).when(unresolved).resolveProfile(any());
            var data=new CompoundTag();data.putString("uuid",uuid.toString());var id=net.minecraft.resources.Identifier.fromNamespaceAndPath("carpet-org-addition","query_player_name");
            var root=ScarpetNativeWork.observeNative(null,()->{OrgFinderFiles.customClick(f.player,id,Optional.of(data));return true;});assertFalse(root.isDone());var idle=ScarpetNativeWork.whenIdle(f.actors.server);assertFalse(idle.isDone());assertEquals("carpet-org-addition.operation.query_player_name.start",view(f.messages.getFirst()).key());
            OrgFinderFiles.customClick(f.player,id,Optional.of(data));assertEquals("carpet-org-addition.operation.wait_last",view(f.messages.getLast()).key());verify(unresolved,times(1)).resolveProfile(any());
            pending.complete(new com.mojang.authlib.GameProfile(uuid,"resolved_source"));f.drive(root::isDone);root.join();idle.join();assertEquals("carpet-org-addition.operation.query_player_name.success",view(f.messages.getLast()).key());verify(f.services.nameToIdCache()).add(new net.minecraft.server.players.NameAndId(uuid,"resolved_source"));
            var failed=new CompletableFuture<com.mojang.authlib.GameProfile>();doReturn(failed).when(unresolved).resolveProfile(any());var failureRoot=ScarpetNativeWork.observeNative(null,()->{OrgFinderFiles.customClick(f.player,id,Optional.of(data));return true;});failed.completeExceptionally(new java.io.IOException("Mojang source failure"));f.drive(failureRoot::isDone);assertThrows(CompletionException.class,failureRoot::join);assertEquals("carpet-org-addition.operation.query_player_name.fail",view(f.messages.getLast()).key());}
        }
    }
}
