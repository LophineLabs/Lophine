// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1 BedrockAction.
package fun.bm.lophine.carpet;

import com.google.gson.JsonObject;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.bot.ServerBot;

final class OrgHiddenBedrock extends OrgHiddenPlayerActions.Engine {
    enum Step { CONTINUE, COMPLETION, TICK_COMPLETION, FAIL }
    enum State { PLACE_THE_PISTON_FACING_UP, PLACE_AND_ACTIVATE_THE_LEVER, PISTON_BREAK_BEDROCK, CLEAN_PISTON, COMPLETE }
    enum Phase { EAT, WORK, COLLECT }
    static final class Context {
        final BlockPos bedrock; BlockPos lever; State state = State.PLACE_THE_PISTON_FACING_UP;
        Context(BlockPos bedrock) { this.bedrock = bedrock.immutable(); }
        void next() { state = State.values()[state.ordinal()+1]; }
        void fail() { state = State.COMPLETE; }
        public boolean equals(Object other) { return other instanceof Context context && bedrock.equals(context.bedrock); }
        public int hashCode() { return bedrock.hashCode(); }
    }
    private static final List<Direction> HORIZONTAL = Arrays.stream(Direction.values()).filter(direction -> direction.getAxis().isHorizontal()).toList();
    final OrgHiddenBedrockSelection selection;
    final boolean ai;
    int recycleTimer;
    private final LinkedHashSet<Context> contexts = new LinkedHashSet<>();
    private final Set<BlockPos> lava = new HashSet<>();
    private final Map<Long, Set<BlockPos>> columns = new HashMap<>();
    private final Map<BlockPos,Integer> invalid = new HashMap<>();
    private Iterator<BlockPos> scanning;
    private final OrgHiddenPathfinder path;
    private final Vec3 initial;
    private Context current;
    private Phase phase = Phase.WORK, previous = Phase.WORK;
    private BlockPos target;
    private boolean movingNearby, hasAction, collected;
    private long nonAction;
    private final List<Drop> drops = new ArrayList<>();
    private Drop recent;
    private final OrgHiddenMaterialStorage storage;
    private record Column(BlockPos horizontal, List<BlockPos> blocks) {}
    record Drop(ItemEntity entity, Vec3 position, BlockPos block) {}

    OrgHiddenBedrock(ServerPlayer player, OrgHiddenBedrockSelection selection, boolean ai, boolean recycle) {
        super(player); this.selection=selection;this.ai=ai;recycleTimer=recycle?2400:-1;
        scanning=selection.columns(); initial=player.position(); storage=new OrgHiddenMaterialStorage(inventory);
        path=new OrgHiddenPathfinder(()->player,()->Optional.ofNullable(phase==Phase.WORK?target:phase==Phase.COLLECT&&recent!=null?recent.block:null));
    }
    String name() { return "bedrock"; }
    List<Component> info() {
        String key="carpet-org-addition.command.playerAction.bedrock.info";var lines=new ArrayList<Component>();
        lines.add(OrgHiddenPlayerActions.localized(key,"%s is breaking the bedrock",player.getDisplayName()));
        JsonObject data=selection.write(ai,recycleTimer!=-1);
        if(data.get("region_type").getAsString().equals("cuboid"))lines.add(OrgHiddenPlayerActions.localized(key+".cuboid.range","Range: from %s to %s",
            OrgHiddenPlayerActions.coordinates(selection.from()),OrgHiddenPlayerActions.coordinates(selection.to())));
        else {
            var center=data.getAsJsonArray("center");BlockPos block=new BlockPos(center.get(0).getAsInt(),center.get(1).getAsInt(),center.get(2).getAsInt());
            lines.add(OrgHiddenPlayerActions.localized(key+".cylinder.center","Center: %s",OrgHiddenPlayerActions.coordinates(block)));
            lines.add(OrgHiddenPlayerActions.localized(key+".cylinder.radius","Radius: %s blocks",data.get("radius").getAsInt()));
            lines.add(OrgHiddenPlayerActions.localized(key+".cylinder.height","Height: %s blocks",data.get("height").getAsInt()));
        }
        if(ai)lines.add(OrgHiddenPlayerActions.localized(key+".ai.enable","AI enabled"));return List.copyOf(lines);
    }
    JsonObject data() { return selection.write(ai,recycleTimer!=-1); }
    AABB footprint() {
        double range = ai ? Math.max(50, player.entityInteractionRange()+2) : Math.min(player.blockInteractionRange(),10)+3;
        return new AABB(player.blockPosition()).inflate(range);
    }
    void stop() { path.onStop(); }
    CompletableFuture<Void> tick() {
        excavator.tick();
        if (!ai) return work();
        if (scanning!=null) return scan();
        if(target!=null&&!movingNearby){
            BlockPos requested=target;
            return column(requested).thenCompose(result->ownerFuture(()->{if(!result.blocks.contains(requested))target=null;return aiTick();}));
        }
        return aiTick();
    }
    private CompletableFuture<Void> aiTick() {
        path.tick();claimMovement();if(falling(3,player.blockPosition())){path.pause(3);claimMovement();}
        return refreshDrops().thenCompose(ignored->ownerFuture(()-> {
            if (shouldEat()) { if(phase!=Phase.EAT)previous=phase;setPhase(Phase.EAT); }
            else if(recycleTimer>0 && --recycleTimer==0)setPhase(Phase.COLLECT);
            if(recycleTimer < -1)throw new IllegalStateException("Invalid material recycling time "+recycleTimer);
            CompletableFuture<Boolean> danger=phase==Phase.EAT?CompletableFuture.completedFuture(false):deflect();
            return danger.thenCompose(deflected->ownerFuture(()-> {
                if(deflected)return done();
                return (phase==Phase.EAT?CompletableFuture.completedFuture(false):extinguish()).thenCompose(extinguished->ownerFuture(()-> {
                    if(extinguished)return done();
                    return switch(phase){case WORK->cycle();case EAT->eat();case COLLECT->recycle();};
                }));
            }));
        }));
    }
    private CompletableFuture<Void> scan() {
        // Initial traversal needs one matching block per column. Group actual chunk actors so
        // a large cylinder does not create one ticket/future for every individual X/Z cell.
        var batch=new LinkedHashMap<Long,List<BlockPos>>();int count=0;
        while(scanning.hasNext() && count++<4096){var column=scanning.next();batch.computeIfAbsent(chunk(column),ignored->new ArrayList<>()).add(column);}
        var requests=batch.entrySet().stream().map(entry->{
            var first=entry.getValue().getFirst();int x=first.getX()>>4,z=first.getZ()>>4;
            return CarpetRegionLease.<List<BlockPos>>runValue(world,x,z,x,z,lease->{
                var found=new ArrayList<BlockPos>();
                for(var horizontal:entry.getValue()){
                    for(int y=Math.max(selection.from().getY(),world.getMinY());y<=Math.min(selection.to().getY(),world.getMaxY());y++)
                        if(world.getBlockState(new BlockPos(horizontal.getX(),y,horizontal.getZ())).is(Blocks.BEDROCK)){found.add(horizontal);break;}
                }
                return List.copyOf(found);
            });
        }).toList();
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).thenCompose(ignored->owner(()-> {
            for(var request:requests)for(var column:request.getNow(List.of()))columns.computeIfAbsent(chunk(column),key->new HashSet<>()).add(column);
            if(!scanning.hasNext())scanning=null;return null;
        }));
    }
    private CompletableFuture<Column> column(BlockPos horizontal) {
        int chunkX=horizontal.getX()>>4,chunkZ=horizontal.getZ()>>4;
        return CarpetRegionLease.runValue(world,chunkX,chunkZ,chunkX,chunkZ,lease->{
            var found=new ArrayList<BlockPos>();
            for(int y=Math.max(selection.from().getY(),world.getMinY());y<=Math.min(selection.to().getY(),world.getMaxY());y++){
                BlockPos block=new BlockPos(horizontal.getX(),y,horizontal.getZ());
                if(world.getBlockState(block).is(Blocks.BEDROCK))found.add(block);
            }
            return new Column(horizontal,List.copyOf(found));
        });
    }
    private CompletableFuture<Void> cycle() {
        nonAction=hasAction?0:nonAction+1;
        if(nonAction>0 && nonAction%1200==0){
            long minute=nonAction/1200;
            if(minute<=2){
                var location=new org.bukkit.Location(world.getWorld(),initial.x,initial.y,initial.z,player.getYRot(),player.getXRot());
                return player.getBukkitEntity().teleportAsync(location,org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .thenCompose(teleported->owner(()->{if(teleported)world.broadcastEntityEvent(player,EntityEvent.TELEPORT);return null;}));
            }
            announce(Component.empty(),false);
            announce(OrgHiddenPlayerActions.localized("carpet-org-addition.command.playerAction.bedrock.no_action.first","%s has had no bedrock-breaking actions for %s minutes",player.getDisplayName(),minute)
                .withStyle(net.minecraft.ChatFormatting.GRAY,net.minecraft.ChatFormatting.ITALIC),false);
            if(minute==5){((ServerBot)player).kill(world);cancelled=true;return done();}
            announce(OrgHiddenPlayerActions.localized("carpet-org-addition.command.playerAction.bedrock.no_action.second","Wait %s more minutes. If the player does not resume breaking bedrock, they will be kicked automatically",5-minute)
                .withStyle(net.minecraft.ChatFormatting.GRAY,net.minecraft.ChatFormatting.ITALIC),true);
        }
        return work();
    }
    private void announce(Component text,boolean sound) {
        var server=world.getServer();Component immutable=text.copy();OrgCommandNativeEffects.global(server,()->{
            for(ServerPlayer recipient:List.copyOf(server.getPlayerList().getPlayers()))OrgMenuNativeEffects.run(recipient,()->{
                if(!recipient.isRemoved()){
                    recipient.sendSystemMessage(immutable);
                    if(sound)recipient.level().playSound(null,recipient.getX(),recipient.getY(),recipient.getZ(),SoundEvents.ANVIL_PLACE,SoundSource.PLAYERS,1,1);
                }
                return null;
            });
            return null;
        });
    }
    private CompletableFuture<Void> work() {
        hasAction=false;if(collected){collected=false;target=null;}
        return drain(new ArrayList<>(lava).iterator()).thenCompose(ignored->ownerFuture(()-> {
            invalid.replaceAll((block,count)->count-1);invalid.values().removeIf(count->count<=0);
            if(current!=null && contexts.contains(current) && interact(current.bedrock)){
                if(!movingNearby){movingNearby=true;target=current.bedrock;}
                return process(current,0).thenCompose(stop->ownerFuture(()->stop?done():remainingWork()));
            }
            current=null;return remainingWork();
        }));
    }
    private CompletableFuture<Void> remainingWork() {
        contexts.removeIf(context->{
            if(context.state==State.COMPLETE)return true;
            if(world.getBlockState(context.bedrock).is(Blocks.BEDROCK))return !interact(context.bedrock);
            return context.state!=State.CLEAN_PISTON;
        });
        AABB box=new AABB(player.blockPosition()).inflate(Math.min(player.blockInteractionRange(),10));
        var cells=new OrgHiddenPlant.Positions((int)box.minX,(int)box.minY,(int)box.minZ,(int)box.maxX,(int)box.maxY,(int)box.maxZ);
        cells.forEachRemaining(block->{if(interact(block)&&selection.contains(block)){
            var state=world.getBlockState(block);if(state.is(Blocks.BEDROCK))contexts.add(new Context(block));else if(state.is(Blocks.LAVA))lava.add(block);
        }});
        return contexts(new ArrayList<>(contexts).iterator());
    }
    private CompletableFuture<Void> contexts(Iterator<Context> iterator) {
        while(iterator.hasNext()&&!cancelled){
            Context context=iterator.next();if(context==current)continue;
            var operation=process(context,0);
            if(operation.isDone()&&!operation.isCompletedExceptionally()){if(operation.getNow(false))return done();continue;}
            return operation.thenCompose(stop->ownerFuture(()->stop?done():contexts(iterator)));
        }
        return ai?chooseTarget():done();
    }
    private CompletableFuture<Boolean> process(Context context,int depth) {
        if(cancelled)return CompletableFuture.completedFuture(true);
        if(depth>=10)throw new IllegalStateException("Bedrock context exceeded the source ten-step loop bound");
        return execute(context).thenCompose(step->ownerFuture(()-> {
            if(step==Step.COMPLETION)return CompletableFuture.completedFuture(false);
            if(step==Step.TICK_COMPLETION){current=context;return CompletableFuture.completedFuture(true);}
            return process(context,depth+1);
        }));
    }
    private CompletableFuture<Step> execute(Context context) {
        return switch(context.state){
            case PLACE_THE_PISTON_FACING_UP-> {
                if(!material()){setPhase(Phase.COLLECT);yield step(Step.TICK_COMPLETION);}
                yield placeUp(context.bedrock).thenCompose(value->owner(()->{if(value==Step.CONTINUE)context.next();return value;}));
            }
            case PLACE_AND_ACTIVATE_THE_LEVER->lever(context,0,null).thenCompose(value->owner(()->{
                if(value==Step.CONTINUE){context.next();return Step.COMPLETION;}return value;
            }));
            case PISTON_BREAK_BEDROCK->breakBedrock(context).thenCompose(value->owner(()->{
                if(value==Step.COMPLETION)context.next();else if(value==Step.FAIL){context.fail();return Step.COMPLETION;}return value;
            }));
            case CLEAN_PISTON->clean(context.bedrock.above()).thenCompose(cleaned->owner(()->{if(cleaned){context.next();return Step.CONTINUE;}return Step.TICK_COMPLETION;}));
            case COMPLETE->step(Step.COMPLETION);
        };
    }
    private boolean material() {
        int pistons=0,levers=0;
        for(int slot=0;slot<inventory.size();slot++){
            ItemStack stack=inventory.getItem(slot);if(stack.is(Items.PISTON))pistons+=stack.getCount();else if(stack.is(Items.LEVER))levers+=stack.getCount();
            if(pistons>=2&&levers>=1)return true;
        }
        if(GeneralCompatConfig.fakePlayerShulkerBoxItemHandling)for(int slot=0;slot<inventory.size();slot++){
            ItemStack box=inventory.getItem(slot);if(!OrgGameplayHelper.isShulkerBox(box)||box.getCount()!=1)continue;
            for(ItemStack stack:box.getOrDefault(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.EMPTY).itemCopies().toList()){
                if(stack.is(Items.PISTON))pistons+=stack.getCount();else if(stack.is(Items.LEVER))levers+=stack.getCount();if(pistons>=2&&levers>=1)return true;
            }
        }
        return false;
    }
    private CompletableFuture<Step> placeUp(BlockPos bedrock) {
        if(torch()&&falling(0,bedrock))return step(Step.COMPLETION);
        BlockPos above=bedrock.above();var state=world.getBlockState(above);boolean piston=false;
        if(replaceable(state)){}
        else if(state.is(Blocks.PISTON)&&state.getValue(PistonBaseBlock.FACING)==Direction.UP)piston=true;
        else return canMine(state,above)?breakObstruction(above):step(Step.COMPLETION);
        above=bedrock.above(2);state=world.getBlockState(above);
        if(piston&&state.is(Blocks.PISTON_HEAD)&&state.getValue(PistonHeadBlock.FACING)==Direction.UP)return step(Step.CONTINUE);
        if(state.isAir()||state.getPistonPushReaction()==PushReaction.POPPED){
            if(piston)return step(Step.CONTINUE);
            return piston(bedrock,Direction.UP).thenApply(result->result.consumesAction()?Step.CONTINUE:Step.COMPLETION);
        }
        return canMine(state,above)?breakObstruction(above):step(Step.COMPLETION);
    }
    private boolean canMine(BlockState state,BlockPos block) {
        if(state.isAir())return true;
        boolean piston=state.is(Blocks.PISTON);
        if(piston&&(state.getValue(PistonBaseBlock.FACING).getAxis()!=Direction.Axis.Y||!world.getBlockState(block.below()).is(Blocks.BEDROCK)
            ||state.getValue(PistonBaseBlock.FACING)==Direction.DOWN))return true;
        if(state.is(Blocks.LEVER))return state.getValue(LeverBlock.FACE)!=AttachFace.WALL;
        if(piston||state.is(Blocks.PISTON_HEAD))return false;
        return state.getDestroySpeed(world,block)!=-1&&excavator.canBreak(block);
    }
    private CompletableFuture<Step> lever(Context context,int index,Direction candidate) {
        while(index<HORIZONTAL.size()){
            Direction face=HORIZONTAL.get(index++);BlockPos offset=context.bedrock.relative(face);var state=world.getBlockState(offset);
            if(replaceable(state)){candidate=face;continue;}
            if(state.is(Blocks.LEVER)){
                if(state.getValue(LeverBlock.FACE)!=AttachFace.WALL)return breakObstruction(offset);
                BlockPos support=offset.relative(state.getValue(LeverBlock.FACING),-1);
                if(context.bedrock.equals(support)){
                    if(context.lever!=null)return breakObstruction(offset);
                    context.lever=offset;
                    if(!state.getValue(LeverBlock.POWERED)){
                        int next=index;Direction nextCandidate=candidate;
                        return clickLever(offset).thenCompose(ignored->ownerFuture(()->lever(context,next,nextCandidate)));
                    }
                }else if(!world.getBlockState(support).is(Blocks.BEDROCK)||!selection.contains(support))return breakObstruction(offset);
            }else if(selection.contains(offset)&&interact(offset)&&canMine(state,offset))return breakObstruction(offset);
        }
        if(context.lever!=null)return step(Step.CONTINUE);
        if(candidate==null)return step(Step.COMPLETION);
        BlockPos offset=context.bedrock.relative(candidate);var below=world.getBlockState(offset.below());
        if(below.is(Blocks.MOVING_PISTON)||below.is(Blocks.PISTON)&&below.getValue(PistonBaseBlock.FACING)==Direction.UP)return step(Step.COMPLETION);
        inventory.replenish(InteractionHand.OFF_HAND,stack->stack.is(Items.LEVER));player.carpetActionPack.look(candidate.getOpposite());
        var hit=new BlockHitResult(Vec3.atCenterOf(context.bedrock),candidate,context.bedrock,false);
        return click(InteractionHand.OFF_HAND,hit).thenCompose(result->ownerFuture(()->clickLever(offset)))
            .thenCompose(ignored->owner(()->{context.lever=offset;return Step.CONTINUE;}));
    }
    static boolean replaceable(BlockState state) {
        return state.isAir()||state.is(BlockTags.REPLACEABLE)&&(!state.is(Blocks.SNOW)||state.getValue(SnowLayerBlock.LAYERS)==1);
    }
    private CompletableFuture<Step> breakBedrock(Context context) {
        BlockPos up=context.bedrock.above();var state=world.getBlockState(up);
        if(!state.is(Blocks.PISTON)||!state.getValue(PistonBaseBlock.EXTENDED))return step(Step.FAIL);
        inventory.switchToAppropriateTool(world,up);
        if(excavator.remaining(up)!=1)return mine(up,false).thenApply(ignored->Step.TICK_COMPLETION);
        if(context.lever==null||!world.getBlockState(context.lever).is(Blocks.LEVER))return step(Step.COMPLETION);
        var ticket=OrgHiddenBedrockSignals.arm(world,up);
        try {
            var critical=OrgHiddenBedrockCycle.unpowerAndReplace(
                ()->world.getBlockState(context.lever).getValue(LeverBlock.POWERED)?clickLever(context.lever):done(),
                ()->ownerFuture(()->closeLevers(up)),
                ()->ownerFuture(()->{context.lever=null;return mine(up,false);}),
                ()->ownerFuture(()->piston(context.bedrock,Direction.DOWN,result->{
                    // Observe the true placement before later cancelled actor bookkeeping.
                    if(result.consumesAction())ticket.replaced();
                })));
            return critical.whenComplete((result,failure)->ticket.close()).thenApply(ignored->Step.COMPLETION);
        } catch(Throwable failure){ticket.close();return CompletableFuture.failedFuture(failure);}
    }

    private CompletableFuture<Void> closeLevers(BlockPos piston) {
        var positions=new ArrayList<BlockPos>();
        for(int dy=0;dy<=1;dy++)for(Direction a:HORIZONTAL){BlockPos offset=piston.above(dy).relative(a);positions.add(offset);for(Direction b:HORIZONTAL)positions.add(offset.relative(b));}
        positions.add(piston.above(2));for(Direction direction:HORIZONTAL)positions.add(piston.above(2).relative(direction));
        return close(positions.iterator()).thenCompose(ignored->ownerFuture(()-> {
            BlockPos above=piston.above(3);var state=world.getBlockState(above);
            CompletableFuture<Void> a=state.is(Blocks.LEVER)&&state.getValue(LeverBlock.POWERED)&&state.getValue(LeverBlock.FACE)==AttachFace.FLOOR?clickLever(above):done();
            return a.thenCompose(value->ownerFuture(()->{
                BlockPos below=piston.below(2);var lower=world.getBlockState(below);
                return lower.is(Blocks.LEVER)&&lower.getValue(LeverBlock.POWERED)&&lower.getValue(LeverBlock.FACE)==AttachFace.CEILING?clickLever(below):done();
            }));
        }));
    }
    private CompletableFuture<Void> close(Iterator<BlockPos> iterator) {
        while(iterator.hasNext()){
            BlockPos block=iterator.next();var state=world.getBlockState(block);
            if(state.is(Blocks.LEVER)&&state.getValue(LeverBlock.POWERED))return clickLever(block).thenCompose(ignored->ownerFuture(()->close(iterator)));
        }return done();
    }
    private CompletableFuture<Boolean> clean(BlockPos block) {
        var state=world.getBlockState(block);if(state.isAir())return CompletableFuture.completedFuture(true);
        if(state.is(Blocks.MOVING_PISTON))return CompletableFuture.completedFuture(false);
        return state.is(Blocks.PISTON)?mine(block,true):CompletableFuture.completedFuture(true);
    }
    private CompletableFuture<Step> breakObstruction(BlockPos block) {
        if(FallingBlock.isFree(world.getBlockState(block.above()))&&torch()&&falling(0,block))return step(Step.COMPLETION);
        var state=world.getBlockState(block);
        if(state.is(Blocks.TORCH)||state.is(Blocks.WALL_TORCH))for(int dy=1;dy<=2;dy++){
            BlockPos above=block.above(dy);if(world.getBlockState(above).getBlock() instanceof FallingBlock)
                return interact(above)?mine(above,true).thenApply(done->done?Step.COMPLETION:Step.TICK_COMPLETION):step(Step.COMPLETION);
        }
        if(state.getBlock() instanceof FallingBlock){
            boolean torch=torch();
            while(true){Direction direction=torch?Direction.DOWN:Direction.UP;BlockPos offset=block.relative(direction);
                if(world.getBlockState(offset).getBlock() instanceof FallingBlock&&interact(offset.relative(direction)))block=offset;else break;}
            if(!interact(block))return step(Step.COMPLETION);
            BlockPos finalBlock=block;
            return mine(block,true).thenCompose(broken->ownerFuture(()->{
                if(!broken||!torch)return step(Step.TICK_COMPLETION);
                inventory.replenish(InteractionHand.OFF_HAND,item->item.is(Items.TORCH));return place(finalBlock).thenApply(ignored->Step.COMPLETION);
            }));
        }
        return mine(block,true).thenApply(broken->broken?Step.COMPLETION:Step.TICK_COMPLETION);
    }
    private boolean torch(){return inventory.contains(stack->stack.is(Items.TORCH));}
    private boolean falling(int range,BlockPos block){
        var box=new AABB(block.getX()-range,block.getY(),block.getZ()-range,block.getX()+range+1,(double)world.getMaxY()+1,block.getZ()+range+1);
        return !CarpetPlayerTracer.nearby(player,box,entity->entity instanceof FallingBlockEntity).isEmpty();
    }
    private CompletableFuture<Boolean> mine(BlockPos block,boolean tool){hasAction=true;if(tool)inventory.switchToAppropriateTool(world,block);return excavator.mine(block,Direction.DOWN,false);}
    private CompletableFuture<InteractionResult> piston(BlockPos bedrock,Direction direction){return piston(bedrock,direction,result->{});}
    private CompletableFuture<InteractionResult> piston(BlockPos bedrock,Direction direction,java.util.function.Consumer<InteractionResult> actualResult){
        player.carpetActionPack.look(direction.getOpposite());inventory.replenish(InteractionHand.OFF_HAND,stack->stack.is(Items.PISTON));
        return click(InteractionHand.OFF_HAND,new BlockHitResult(Vec3.upFromBottomCenterOf(bedrock,1),direction,bedrock.above(),false))
            .thenApply(result->{actualResult.accept(result);return result;})
            .thenCompose(result->owner(()->{if(result.consumesAction())hasAction=true;return result;}));
    }
    private CompletableFuture<Void> place(BlockPos block){player.carpetActionPack.lookAt(Vec3.atCenterOf(block));return click(InteractionHand.OFF_HAND,
        new BlockHitResult(Vec3.upFromBottomCenterOf(block,1),Direction.DOWN,block,false)).thenApply(ignored->null);}
    private CompletableFuture<Void> clickLever(BlockPos block){return click(InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(block),Direction.UP,block,false))
        .thenCompose(result->owner(()->{if(result.consumesAction())hasAction=true;return null;}));}
    private CompletableFuture<Void> drain(Iterator<BlockPos> iterator){
        while(iterator.hasNext()){
            BlockPos block=iterator.next();
            if(interact(block)&&(inventory.replenish(InteractionHand.OFF_HAND,stack->drainMaterial(stack,block))||inventory.replenish(InteractionHand.OFF_HAND,stack->stack.is(Items.PISTON))))
                return place(block).thenCompose(ignored->ownerFuture(()->{lava.remove(block);return drain(iterator);}));
            lava.remove(block);
        }return done();
    }
    private boolean drainMaterial(ItemStack stack,BlockPos block){
        if(stack.is(Items.PISTON)||stack.is(Items.REDSTONE_BLOCK)||!(stack.getItem() instanceof BlockItem item)||item.getBlock() instanceof BaseEntityBlock)return false;
        return item.getBlock().defaultBlockState().isRedstoneConductor(world,block);
    }
    private boolean shouldEat(){return inventory.contains(stack->stack.has(DataComponents.FOOD))&&!player.getAbilities().invulnerable
        &&(player.getFoodData().getFoodLevel()<=10||player.getFoodData().needsFood()&&player.getMaxHealth()-player.getHealth()>2);}
    private CompletableFuture<Void> eat(){
        Phase previous=this.previous==Phase.EAT?Phase.WORK:this.previous;
        if(player.getAbilities().invulnerable||!player.canEat(false)){setPhase(previous);return done();}
        if(player.getUseItem().isEmpty()){
            if(inventory.replenish(stack->stack.has(DataComponents.FOOD)))return OrgHiddenNative.use(player,InteractionHand.MAIN_HAND).thenApply(ignored->null);
            setPhase(previous);
        }return done();
    }
    private CompletableFuture<Boolean> deflect(){
        Vec3 eyes=player.getEyePosition();double range=player.entityInteractionRange();
        AABB box=new AABB(eyes.x-range,eyes.y-range,eyes.z-range,eyes.x+range,eyes.y+range,eyes.z+range);
        var fireballs=CarpetPlayerTracer.nearby(player,box,entity->entity instanceof LargeFireball ball&&ball.getOwner()!=player)
            .stream().map(entity->(LargeFireball)entity).toList();
        for(LargeFireball fireball:fireballs){
            player.carpetActionPack.lookAt(fireball.getEyePosition());ownedAttack=CarpetPlayerActionPack.Action.once();
            player.carpetActionPack.start(CarpetPlayerActionPack.ActionType.ATTACK,ownedAttack);
        }
        return CompletableFuture.completedFuture(!fireballs.isEmpty());
    }
    private CompletableFuture<Boolean> extinguish(){
        var iterator=new OrgHiddenPlant.Positions(player.blockPosition().getX()-1,player.blockPosition().getY()-1,player.blockPosition().getZ()-1,
            player.blockPosition().getX()+1,player.blockPosition().getY()+1,player.blockPosition().getZ()+1);
        while(iterator.hasNext()){BlockPos block=iterator.next();if(world.getBlockState(block).is(Blocks.FIRE))return excavator.mine(block,Direction.DOWN,false).thenApply(broken->!broken);}
        return CompletableFuture.completedFuture(false);
    }
    private CompletableFuture<Void> chooseTarget(){
        if(hasAction){if(path.isFinished())target=null;return done();}
        movingNearby=false;
        if(target!=null&&interact(target)){
            BlockPos previous=target;
            return select().thenCompose(ignored->owner(()->{invalid.put(previous,200);return null;}));
        }
        for(Context context:contexts)if(!invalid.containsKey(context.bedrock)){target=context.bedrock;return done();}
        return select();
    }
    private CompletableFuture<Void> select(){
        if(!path.isInvalid()||columns.isEmpty())return done();
        BlockPos location=player.blockPosition();int chunkX=location.getX()>>4,chunkZ=location.getZ()>>4;
        var nearby=new ArrayList<BlockPos>();
        for(int x=chunkX-2;x<=chunkX+2;x++)for(int z=chunkZ-2;z<=chunkZ+2;z++){
            long key=ChunkPos.pack(x,z);Set<BlockPos> positions=columns.get(key);
            if(positions==null)continue;if(positions.isEmpty()){columns.remove(key,positions);continue;}nearby.addAll(positions);
        }
        var requests=nearby.stream().map(this::column).toList();
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).thenCompose(ignored->ownerFuture(()->{
            BlockPos nearest=null;
            for(var request:requests){
                Column column=request.getNow(null);
                if(column.blocks.isEmpty())columns.getOrDefault(chunk(column.horizontal),Set.of()).remove(column.horizontal);
                for(int index=column.blocks.size()-1;index>=0;index--){
                    BlockPos block=column.blocks.get(index);if(invalid.containsKey(block))continue;
                    if(nearest==null||manhattan(block,location)<manhattan(nearest,location))nearest=block;
                    break;
                }
            }
            if(columns.isEmpty())return done();
            if(nearest!=null){target=nearest;return done();}
            return randomTarget();
        }));
    }
    private CompletableFuture<Void> randomTarget(){
        var entries=new ArrayList<>(columns.entrySet());if(entries.isEmpty())return done();
        var selected=entries.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(entries.size()));
        if(selected.getValue().isEmpty()){columns.remove(selected.getKey(),selected.getValue());return done();}
        var positions=new ArrayList<>(selected.getValue());BlockPos horizontal=positions.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(positions.size()));
        return column(horizontal).thenCompose(column->owner(()->{
            for(int index=column.blocks.size()-1;index>=0;index--){BlockPos block=column.blocks.get(index);if(!invalid.containsKey(block)){target=block;return null;}}
            selected.getValue().remove(horizontal);return null;
        }));
    }
    private static int manhattan(BlockPos a,BlockPos b){return Math.abs(a.getX()-b.getX())+Math.abs(a.getY()-b.getY())+Math.abs(a.getZ()-b.getZ());}
    private CompletableFuture<Drop> snapshot(ItemEntity item,AABB box){
        return carpet.script.external.ScarpetRuntime.atEntityFuture(item,()->{
            if(item.isRemoved()||item.level()!=world||!ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup.getEntityStatus(item).isAccessible()
                ||!material(item.getItem())||box!=null&&!item.getBoundingBox().intersects(box))return null;
            return new Drop(item,item.position(),item.blockPosition().immutable());
        }).handle((value,failure)->failure==null?value:null);
    }
    private CompletableFuture<Void> refreshDrops(){
        var remembered=List.copyOf(drops);ItemEntity previous=recent==null?null:recent.entity;
        var queries=remembered.stream().map(drop->snapshot(drop.entity,null)).toList();
        return CompletableFuture.allOf(queries.toArray(CompletableFuture[]::new)).thenCompose(ignored->owner(()->{
            drops.clear();recent=null;
            for(var query:queries){Drop drop=query.getNow(null);if(drop!=null){drops.add(drop);if(drop.entity==previous)recent=drop;}}
            return null;
        }));
    }
    private CompletableFuture<Void> discoverDrops(){
        AABB box=selection.box().inflate(10);
        var items=new ArrayList<ItemEntity>();
        // Reference enumeration is over the real ConcurrentHashMap UUID registry. Every mutable
        // item field, bounds and material predicate is then captured on that item's actual owner.
        var lookup=((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getEntityLookup();
        for(Entity entity:lookup.getAllMapped())if(entity instanceof ItemEntity item)items.add(item);
        var queries=items.stream().map(item->snapshot(item,box)).toList();
        return CompletableFuture.allOf(queries.toArray(CompletableFuture[]::new)).thenCompose(ignored->owner(()->{
            for(var query:queries){Drop drop=query.getNow(null);if(drop!=null)drops.add(drop);}return null;
        }));
    }
    private CompletableFuture<Void> recycle(){
        if(path.isFinished())storage.collect(()->setPhase(Phase.WORK));
        boolean tooFar=recent!=null&&recent.position.distanceTo(player.position())>25;
        if((path.isInvalid()||path.isInaccessible()&&tooFar)&&!drops.isEmpty()){
            drops.remove(recent);path.pause(1);claimMovement();collected=true;recent=null;
            if(drops.isEmpty()){setPhase(Phase.WORK);return done();}
        }
        CompletableFuture<Void> found=drops.isEmpty()?discoverDrops():done();
        return found.thenCompose(ignored->owner(()->{
            if(drops.isEmpty()){setPhase(Phase.WORK);return null;}
            if(recent!=null)return null;
            Drop nearest=drops.getFirst();
            for(Drop drop:drops)if(nearest.position.distanceTo(player.position())>drop.position.distanceTo(player.position()))nearest=drop;
            recent=nearest;return null;
        }));
    }
    static boolean material(ItemStack stack){return stack.is(Items.PISTON)||stack.is(Items.LEVER);}
    private boolean interact(BlockPos block){return player.isWithinBlockInteractionRange(block,0);}
    private void setPhase(Phase phase){this.phase=phase;if(recycleTimer!=-1&&phase==Phase.WORK)recycleTimer=2400;}
    private static long chunk(BlockPos block){return ChunkPos.pack(block.getX()>>4,block.getZ()>>4);}
    private static CompletableFuture<Step> step(Step step){return CompletableFuture.completedFuture(step);}
    private static CompletableFuture<Void> done(){return CompletableFuture.completedFuture(null);}
}
