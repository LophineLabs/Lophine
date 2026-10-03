// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import org.leavesmc.leaves.bot.ServerBot;

/** Loaded world queries run in bounded native actor slices and publish immutable rows. */
final class OrgFinderService {
    enum Kind { BLOCK,ITEM,TRADE,BOOK }
    private static final carpet.script.external.WeakIdentityMap<ServerPlayer,Query> TASKS=new carpet.script.external.WeakIdentityMap<>();
    private static final int LIMIT=30_000;
    static final class QueryConflict extends IllegalStateException {QueryConflict(){super(OrgFinderText.localized("carpet-org-addition.operation.wait_last").getString());}}
    static Component commandFailure(Throwable failure){return failure instanceof OrgFinderBounds.TooBig?OrgFinderText.finder("toobig",1025):failure instanceof QueryConflict?OrgFinderText.localized("carpet-org-addition.operation.wait_last"):Component.literal(failure.getMessage());}
    private OrgFinderService() {}
    static CompletableFuture<Void> start(CommandSourceStack source,ServerPlayer player,OrgFinderBounds bounds,Kind kind,
            Predicate<BlockInWorld> block,Predicate<ItemStack> item,Holder<Enchantment> enchantment){
        return start(source,player,bounds,kind,block,item,enchantment,state->!state.isAir());
    }
    static CompletableFuture<Void> start(CommandSourceStack source,ServerPlayer player,OrgFinderBounds bounds,Kind kind,
            Predicate<BlockInWorld> block,Predicate<ItemStack> item,Holder<Enchantment> enchantment,Predicate<BlockState> palette){
        Component label=kind==Kind.BOOK?enchantment.value().description():Component.literal(kind==Kind.BLOCK?"blocks":"items");
        return start(source,player,bounds,kind,block,item,enchantment,palette,label,"");
    }
    static CompletableFuture<Void> start(CommandSourceStack source,ServerPlayer player,OrgFinderBounds bounds,Kind kind,
            Predicate<BlockInWorld> block,Predicate<ItemStack> item,Holder<Enchantment> enchantment,Predicate<BlockState> palette,Component label,String itemInput){
        TickThread.ensureTickThread(player,"Finder starts on its requester's owner");
        Task task=new Task(source,player,bounds,kind,block,item,enchantment,palette,label,itemInput);
        synchronized(TASKS){if(TASKS.get(player)!=null)throw new QueryConflict();TASKS.put(player,task);}
        carpet.script.external.ScarpetNativeWork.record(task.actual);
        carpet.script.external.ScarpetNativeWork.trackNative(source.getServer(),task.actual);
        try{task.next();}catch(Throwable failure){task.finish(failure);}
        return task.actual;
    }
    private interface Query { void cancel(); }
    static boolean stop(ServerPlayer player){Query task=TASKS.get(player);if(task==null)return false;task.cancel();return true;}
    static External startExternal(ServerPlayer player){
        TickThread.ensureTickThread(player,"Finder file query starts on its owner");
        External handle=new External(player);
        synchronized(TASKS){if(TASKS.get(player)!=null)throw new QueryConflict();TASKS.put(player,handle);}
        return handle;
    }
    static final class External implements Query,AutoCloseable {
        final ServerPlayer player;final net.minecraft.server.MinecraftServer server;volatile boolean cancelled;
        External(ServerPlayer player){this.player=player;this.server=player.level().getServer();}
        public void cancel(){cancelled=true;}
        boolean cancelled(){return cancelled||carpet.script.external.ScarpetNativeWork.isDraining(server);}
        public void close(){synchronized(TASKS){if(TASKS.get(player)==this)TASKS.remove(player,this);}}
    }
    /** Its owner callback receipt includes every real native child created while delivering the result. */
    static CompletableFuture<Void> output(ServerPlayer player,Runnable body){
        var captured=carpet.script.external.ScarpetRuntime.captureNativeContinuation(()->carpet.script.external.ScarpetNativeWork.<Void>observeNative(player,()->{body.run();return null;}));
        var actual=carpet.script.external.ScarpetExplosionActors.entity(player,captured).thenCompose(next->next);
        carpet.script.external.ScarpetNativeWork.record(actual);return actual;
    }
    static Component position(BlockPos position){
        String text=position.getX()+" "+position.getY()+" "+position.getZ();
        return Component.literal("["+text+"]").withStyle(style->style.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.CopyToClipboard(text)));
    }
    static boolean worldEater(ServerLevel world,BlockPos pos){
        BlockState state=world.getBlockState(pos);
        if(state.is(Blocks.BEDROCK)||state.isAir()||state.getBlock() instanceof LiquidBlock||state.getPistonPushReaction()==PushReaction.POPPED)return false;
        if(state.getBlock().getExplosionResistance()>17)return true;
        boolean wet=!state.getFluidState().isEmpty();
        if(wet&&(state.getBlock() instanceof BaseEntityBlock||state.getPistonPushReaction()==PushReaction.IMMOVEABLE))return true;
        if(!wet)return false;
        for(int offset=1;offset<=8;offset++){
            PushReaction reaction=world.getBlockState(pos.below(offset)).getPistonPushReaction();
            if(reaction==PushReaction.IMMOVEABLE)return true;
            if(reaction==PushReaction.POPPED)return false;
        }
        return true;
    }
    private record Row(BlockPos position,Component title,long count,int level,Component text) {}
    private static final class TooMany extends IllegalStateException {
        final Component message;TooMany(Component message){super(message.getString());this.message=message;}
    }
    private static final class Task implements Query {
        final CommandSourceStack source;final ServerPlayer player;final ServerLevel world;final BlockPos origin;
        final OrgFinderBounds bounds;final Kind kind;final Predicate<BlockInWorld> blocks;final Predicate<ItemStack> items;final Holder<Enchantment> enchantment;final Predicate<BlockState> palette;
        final Component label;final String itemInput;
        final Map<BlockPos,Block> matches=new HashMap<>();final List<Row> rows=new ArrayList<>();final Set<UUID> entities=new HashSet<>();
        final CompletableFuture<Void> actual=new CompletableFuture<>();
        final java.util.function.Supplier<CompletableFuture<Void>> slicePhase;
        final java.util.function.Consumer<Entity> nextOwned;
        final java.util.function.Function<Runnable,CompletableFuture<Void>> output;

        final long started=System.nanoTime();volatile boolean cancelled;boolean warned,chunkStarted,terminal,nested;int chunkX,chunkZ,x,y,z,merchants,tradeCount;long visited,totalItems;
        List<BlockPos> blockEntities=List.of();List<Entity> chunkEntities=List.of();int blockEntityCursor,entityCursor;
        public void cancel(){cancelled=true;}
        Task(CommandSourceStack source,ServerPlayer player,OrgFinderBounds bounds,Kind kind,Predicate<BlockInWorld> blocks,Predicate<ItemStack> items,Holder<Enchantment> enchantment,Predicate<BlockState> palette,Component label,String itemInput){
            this.palette=palette;this.source=source;this.player=player;this.world=player.level();this.origin=player.blockPosition().immutable();this.bounds=bounds;this.kind=kind;this.blocks=blocks;this.items=items;this.enchantment=enchantment;
            this.label=label.copy();this.itemInput=itemInput;
            chunkX=bounds.minX()>>4;chunkZ=bounds.minZ()>>4;
            slicePhase=carpet.script.external.ScarpetRuntime.captureNativeContinuation(()->carpet.script.external.ScarpetNativeWork.<Void>observeNative(null,()->{slice();return null;}));
            output=carpet.script.external.ScarpetRuntime.captureNativeFunction(body->OrgFinderService.output(player,body));
            nextOwned=carpet.script.external.ScarpetRuntime.captureNativeConsumer(owner->{
                try{
                    Component progress=kind==Kind.BLOCK?OrgFinderText.finder("block.progress",this.label,OrgFinderText.progress(visited,bounds.size())):null;
                    boolean notice=!warned&&System.nanoTime()-started>(kind==Kind.BLOCK?60:20)*1_000_000_000L;
                    if(notice)warned=true;
                    if(progress==null&&!notice){next();return;}
                    output.apply(()->{if(notice)player.sendSystemMessage(OrgFinderText.waiting());if(progress!=null)player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(progress));})
                        .whenComplete((ignored,failure)->{if(failure==null)next();else finish(failure);});
                }catch(Throwable failure){finish(failure);}
            });
        }
        void next(){
            if(cancelled||carpet.script.external.ScarpetNativeWork.isDraining(world.getServer())){finish(new CancellationException("Finder cancelled"));return;}
            if(bounds.empty()||chunkZ>(bounds.maxZ()>>4)){finish(null);return;}
            var actual=CarpetRegionLease.<CompletableFuture<Void>>runLoadedValue(world,chunkX-8,chunkZ-8,chunkX+8,chunkZ+8,lease->slicePhase.get()).thenCompose(next->next);
            actual.whenComplete((ignored,error)->{
                if(error!=null){finish(error);return;}
                boolean scheduled=player.getBukkitEntity().taskScheduler.schedule(nextOwned,retired->finish(new CancellationException("Finder requester left")),1L);
                if(!scheduled)finish(new CancellationException("Finder requester retired"));
            });
        }
        void advanceChunk(){chunkStarted=false;if(++chunkX>(bounds.maxX()>>4)){chunkX=bounds.minX()>>4;chunkZ++;}}
        void slice(){
            var chunk=world.getChunkIfLoaded(chunkX,chunkZ);if(chunk==null){advanceChunk();return;}
            if(kind!=Kind.BLOCK){
                if(!chunkStarted){
                    blockEntities=kind==Kind.ITEM?chunk.getBlockEntities().keySet().stream().filter(bounds::contains).map(BlockPos::immutable).toList():List.of();
                    AABB box=bounds.box().intersect(new AABB(chunkX*16D,bounds.minY(),chunkZ*16D,chunkX*16D+16,(double)bounds.maxY()+1,chunkZ*16D+16));
                    chunkEntities=List.copyOf(world.getEntitiesOfClass(Entity.class,box));blockEntityCursor=entityCursor=0;chunkStarted=true;
                }
                long deadline=System.nanoTime()+30_000_000L;
                while(blockEntityCursor<blockEntities.size()){
                    if(cancelled)throw new CancellationException("Finder cancelled");
                    BlockPos pos=blockEntities.get(blockEntityCursor);var entity=chunk.getBlockEntity(pos);
                    if(entity instanceof Container inventory&&!count(inventory,pos,entity instanceof BaseContainerBlockEntity named?named.getName():chunk.getBlockState(pos).getBlock().getName()))return;
                    blockEntityCursor++;if(System.nanoTime()>=deadline)return;
                }
                while(entityCursor<chunkEntities.size()){
                    if(cancelled)throw new CancellationException("Finder cancelled");Entity entity=chunkEntities.get(entityCursor);
                    // A captured identity may have migrated since this slice's owner topology was admitted.
                    // Skip the old slice without reading foreign live position or inventory.
                    if(TickThread.isTickThreadFor(entity)&&!entity.isRemoved()&&entity.level()==world&&bounds.contains(entity.blockPosition())&&!entities.contains(entity.getUUID())){
                        if(kind==Kind.ITEM&&!scanInventory(entity))return;
                        if(kind!=Kind.ITEM&&entity instanceof AbstractVillager merchant)scanTrade(merchant);
                        entities.add(entity.getUUID());
                    }
                    entityCursor++;if(System.nanoTime()>=deadline)return;
                }
                blockEntities=List.of();chunkEntities=List.of();advanceChunk();return;
            }
            if(!chunkStarted){x=Math.max(bounds.minX(),chunkX<<4);z=Math.max(bounds.minZ(),chunkZ<<4);y=bounds.minY();chunkStarted=true;}
            int maxX=Math.min(bounds.maxX(),(chunkX<<4)+15),maxZ=Math.min(bounds.maxZ(),(chunkZ<<4)+15);
            long deadline=System.nanoTime()+30_000_000L;
            while(z<=maxZ){
                if(cancelled)throw new CancellationException("Finder cancelled");
                if(x==Math.max(bounds.minX(),chunkX<<4)){
                    var section=chunk.getSection(world.getSectionIndex(y));
                    if(section.hasOnlyAir()||!section.maybeHas(palette)){
                        visited+=(long)(maxX-x+1)*Math.min(16-(y&15),bounds.maxY()-y+1);
                        y=(y|15)+1;if(y>bounds.maxY()){y=bounds.minY();z++;}
                        if(System.nanoTime()>=deadline)return;continue;
                    }
                }
                BlockPos position=new BlockPos(x,y,z);BlockState state=chunk.getBlockState(position);
                visited++;
                if(!state.isAir()&&blocks.test(new BlockInWorld(world,position,false))){matches.put(position,state.getBlock());if(matches.size()>LIMIT)throw new TooMany(OrgFinderText.finder("block.too_much",label));}
                if(++x>maxX){x=Math.max(bounds.minX(),chunkX<<4);if(++y>bounds.maxY()){y=bounds.minY();z++;}}
                if(System.nanoTime()>=deadline)return;
            }
            advanceChunk();
        }
        boolean scanInventory(Entity entity){
            return switch(entity){
                case ItemEntity item -> count(new SimpleContainer(item.getItem()),item.blockPosition(),OrgFinderText.localized("carpet-org-addition.item.drops"));
                case ServerBot bot -> count(bot.getInventory(),bot.blockPosition(),bot.getName());
                case net.minecraft.world.entity.vehicle.ContainerEntity container -> count(container,entity.blockPosition(),entity.getName());
                case ItemFrame frame -> frame.getItem().isEmpty()||count(new SimpleContainer(frame.getItem()),frame.blockPosition(),frame.getName());
                case Villager villager when fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.openVillagerInventory -> count(villager.getInventory(),villager.blockPosition(),villager.getName());
                case AbstractChestedHorse horse when horse.hasChest() -> count(horse.inventory,horse.blockPosition(),horse.getName());
                case Allay allay -> count(new SimpleContainer(allay.getMainHandItem()),allay.blockPosition(),allay.getName());
                default -> true;
            };
        }
        boolean count(Container inventory,BlockPos position,Component title){
            var stacks=new ArrayList<ItemStack>();for(int slot=0;slot<inventory.getContainerSize();slot++)stacks.add(inventory.getItem(slot));
            var attempt=OrgItemShadowGroups.attempt(stacks,()->OrgFinderStatistics.count(inventory,items));
            if(!attempt.completed())return false;
            var statistics=attempt.value();if(statistics.total()==0)return true;
            totalItems=Math.addExact(totalItems,statistics.total());nested|=!statistics.nested().isEmpty();
            rows.add(new Row(position.immutable(),title.copy(),statistics.total(),0,OrgFinderText.finder("item.each",position(position),title.copy(),OrgFinderText.count(statistics))));
            if(rows.size()>LIMIT)throw new TooMany(OrgFinderText.finder("item.too_much",label));
            return true;
        }
        void scanTrade(AbstractVillager merchant){
            var indices=new TreeMap<Integer,List<Integer>>(Comparator.reverseOrder());var offers=merchant.getOffers();
            for(int index=0;index<offers.size();index++){
                ItemStack result=offers.get(index).getResult();int level=kind==Kind.BOOK&&result.is(Items.ENCHANTED_BOOK)?EnchantmentHelper.getEnchantmentsForCrafting(result).getLevel(enchantment):0;
                if(kind==Kind.BOOK?level>0:items.test(result))indices.computeIfAbsent(level,ignored->new ArrayList<>()).add(index+1);
            }
            BlockPos position=merchant.blockPosition().immutable();Component name=merchant.getName().copy();
            if(!indices.isEmpty())merchants++;
            indices.forEach((level,slots)->{String slotText=slots.size()==1?Integer.toString(slots.getFirst()):slots.toString();Component text=kind==Kind.BOOK?OrgFinderText.finder("trade.enchanted_book.each",position(position),name,slotText,Enchantment.getFullname(enchantment,level)):OrgFinderText.finder("trade.item.each",position(position),name,slotText);tradeCount+=slots.size();rows.add(new Row(position,name,slots.size(),level,text));});
        }
        void finish(Throwable failure){
            synchronized(this){if(terminal)return;terminal=true;}
            if(failure!=null){
                Throwable cause=failure;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                Component message=cause instanceof CancellationException?OrgFinderText.finder("cancelled"):cause instanceof TooMany many?many.message.copy().withStyle(ChatFormatting.RED):Component.literal("Finder: "+cause.getMessage());
                output.apply(()->player.sendSystemMessage(message))
                    .whenComplete((ignored,error)->{release();actual.completeExceptionally(failure);});
                return;
            }
            List<Component> output;
            if(kind==Kind.BLOCK)output=OrgFinderStatistics.groups(matches,origin).stream().map(group->(Component)OrgFinderText.finder("block.each",position(group.center()),group.positions().size(),group.block().getName())).toList();
            else{rows.sort(kind==Kind.ITEM?Comparator.comparingLong(Row::count).reversed():Comparator.comparingInt(Row::level).reversed().thenComparingDouble(row->row.position.distSqr(origin)));output=rows.stream().map(Row::text).toList();}
            String key=switch(kind){case BLOCK->"block";case ITEM->"item";case TRADE->"trade.item";case BOOK->"trade.enchanted_book";};
            Component header=switch(kind){
                case BLOCK->OrgFinderText.finder(key+".head",matches.size(),label);
                case ITEM->OrgFinderText.finder(key+".head",rows.size(),OrgFinderText.total(itemInput,totalItems,nested),label);
                case TRADE,BOOK->OrgFinderText.finder(key+".head",merchants,label,Component.translatable("entity.minecraft.villager"),tradeCount);
            };
            Component empty=kind==Kind.BLOCK||kind==Kind.ITEM?OrgFinderText.finder(key+".cannot_find",label):OrgFinderText.finder(key+".cannot_find",label,Component.translatable("entity.minecraft.villager"));
            this.output.apply(()->{if(output.isEmpty())player.sendSystemMessage(empty);else{player.sendSystemMessage(Component.empty());player.sendSystemMessage(header);OrgPages.print(source,output);}})
                .whenComplete((ignored,error)->{release();if(error==null)actual.complete(null);else actual.completeExceptionally(error);});
        }
        void release(){synchronized(TASKS){if(TASKS.get(player)==this)TASKS.remove(player,this);}}
    }
}
