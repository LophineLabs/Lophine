// SPDX-License-Identifier: LGPL-3.0-or-later
// Adapted from Fabric Carpet revision f358000b175ddbcf1dd0bc59641c715fb0545664.
package fun.bm.lophine.carpet;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.leavesmc.leaves.bot.ServerBot;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class CarpetPlayerActionPack
{
    private final ServerPlayer player;

    private final Map<ActionType, Action> actions = new EnumMap<>(ActionType.class);

    public Action getAction(ActionType type) {
        return this.actions.get(type);
    }

    private BlockPos currentBlock;
    private int blockHitDelay;
    private boolean isHittingBlock;
    private float curBlockDamageMP;

    private boolean sneaking;
    private boolean sprinting;
    private float forward;
    private float strafing;
    private long movementGeneration;

    public long getMovementGeneration() { return movementGeneration; }

    /** Captured only after old actual work ends, while the producer remains paused. */
    public record RemovalSnapshot(Map<ActionType,Action> identities,long movementGeneration,net.minecraft.nbt.CompoundTag metadata){
        public RemovalSnapshot{identities=Map.copyOf(identities);metadata=metadata.copy();}
        @Override public net.minecraft.nbt.CompoundTag metadata(){return metadata.copy();}
    }
    public RemovalSnapshot captureForRemoval(){
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(player,"Old action capture requires its owner");
        return new RemovalSnapshot(actions,movementGeneration,save());
    }
    /** Stops only the captured controllers; this precise old cleanup is allowed under its producer pause. */
    public java.util.concurrent.CompletableFuture<Void> stopForRemoval(RemovalSnapshot expected){
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(player,"Old action stop requires its owner");
        var actual=carpet.script.external.ScarpetNativeWork.<Void>observeNative(player,()->{
            for(var entry:expected.identities.entrySet()){
                Action current=actions.get(entry.getKey());if(current!=entry.getValue())continue;
                // The authorization covers only this proven old action's stop and its actual
                // native continuations, never the later inventory snapshot or a new action.
                current.cancel();actions.remove(entry.getKey(),current);completedAttempts.remove(entry.getKey());
                try(var accepted=carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)){entry.getKey().stop(player,current);}
            }
            if(movementGeneration==expected.movementGeneration){stopMovement();player.zza=0;player.xxa=0;}
            if(actions.isEmpty()&&!pendingMount&&!completion.hasPending())targetArea.close();
            return null;
        });
        carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(player,actual);
        var result=new java.util.concurrent.CompletableFuture<Void>(){@Override public boolean cancel(boolean mayInterruptIfRunning){return false;}};
        carpet.script.external.ScarpetNativeWork.aliasDependency(result,actual);
        actual.whenComplete((ignored,failure)->{if(failure==null)result.complete(null);else result.completeExceptionally(failure);});return result;
    }

    private int itemUseCooldown;
    private final CarpetPlayerTargetArea targetArea = new CarpetPlayerTargetArea();
    private final CarpetActionCompletion completion = new CarpetActionCompletion();

    /** Includes accepted actions removed/replaced in the action map, through their owner commit. */
    public java.util.concurrent.CompletableFuture<Void> pendingCompletion() {
        return completion.pendingCompletion();
    }

    /** Pause new action passes while an owner snapshot waits for accepted native effects. */
    public <T> java.util.concurrent.CompletableFuture<T> whenIdle(java.util.function.Supplier<T> snapshot) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(player, "Carpet player snapshot must own its player");
        return completion.whenIdle(work -> ownedSnapshot(player,
            () -> carpet.script.external.ScarpetNativeWork.observeNative(player, work)), snapshot);
    }

    public <T> java.util.concurrent.CompletableFuture<T> whenIdleForRemoval(java.util.function.Supplier<T> snapshot) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(player, "Carpet player removal snapshot must own its player");
        return completion.whenIdleAfterTermination(work -> ownedSnapshot(player,
            () -> carpet.script.external.ScarpetNativeWork.observeNative(player,work)),snapshot);
    }

    public float getForward() { return forward; }
    public float getStrafing() { return strafing; }
    private boolean pendingMount;
    private boolean mountOnlyRideables;
    private final Map<ActionType, Boolean> completedAttempts = new EnumMap<>(ActionType.class);

    public void detachTargetArea() { targetArea.close(); }

    private boolean pendingTargets() {
        Action using = actions.get(ActionType.USE), attacking = actions.get(ActionType.ATTACK);
        return using != null && using.pending != null || attacking != null && attacking.pending != null;
    }

    private boolean prepareTargets() { return prepareTargets(true); }

    private boolean prepareTargets(boolean preservePending) {
        Action using = actions.get(ActionType.USE), attacking = actions.get(ActionType.ATTACK);
        boolean ray = using != null && (!using.done || using.pending != null || completedAttempts.containsKey(ActionType.USE))
            || attacking != null && (!attacking.done || attacking.pending != null || completedAttempts.containsKey(ActionType.ATTACK));
        // A started native plan owns its captured click footprint until its real result resolves.
        if (preservePending && completion.hasPending()) return false;
        if (!ray && !pendingMount) { targetArea.close(); return true; }
        var box = player.getBoundingBox();
        if (ray) {
            double reach = player.gameMode.isCreative() ? 5 : 4.5;
            box = box.expandTowards(player.getViewVector(1).scale(reach)).inflate(1);
            if (currentBlock != null && currentBlock.distSqr(player.blockPosition()) <= 64) {
                box = box.minmax(new net.minecraft.world.phys.AABB(currentBlock));
            }
        }
        if (pendingMount) box = box.minmax(player.getBoundingBox().inflate(3, 1, 3));
        return targetArea.ready(player, box);
    }

    public CarpetPlayerActionPack(ServerPlayer playerIn)
    {
        player = playerIn;
        stopAll();
    }
    public void copyFrom(CarpetPlayerActionPack other)
    {
        movementGeneration++;
        actions.clear();
        completedAttempts.clear();
        completedAttempts.putAll(other.completedAttempts);
        other.actions.forEach((type, action) -> actions.put(type, action.copy()));
        currentBlock = other.currentBlock;
        blockHitDelay = other.blockHitDelay;
        isHittingBlock = other.isHittingBlock;
        curBlockDamageMP = other.curBlockDamageMP;

        sneaking = other.sneaking;
        sprinting = other.sprinting;
        forward = other.forward;
        strafing = other.strafing;

        itemUseCooldown = other.itemUseCooldown;
    }

    public CarpetPlayerActionPack start(ActionType type, Action action)
    {
        Action previous = actions.remove(type);
        if (type == ActionType.USE || type == ActionType.ATTACK) completedAttempts.clear();
        else completedAttempts.remove(type);
        if (previous != null) { previous.cancel(); type.stop(player, previous); }
        if (action != null)
        {
            actions.put(type, action);
            type.start(player, action); // noop
        }
        if (type == ActionType.USE || type == ActionType.ATTACK) prepareTargets();
        return this;
    }

    public CarpetPlayerActionPack setSneaking(boolean doSneak)
    {
        movementGeneration++;
        sneaking = doSneak;
        player.setShiftKeyDown(doSneak);
        if (sprinting && sneaking)
            setSprinting(false);
        return this;
    }
    public CarpetPlayerActionPack setSprinting(boolean doSprint)
    {
        sprinting = doSprint;
        player.setSprinting(doSprint);
        if (sneaking && sprinting)
            setSneaking(false);
        return this;
    }

    public CarpetPlayerActionPack setForward(float value)
    {
        movementGeneration++;
        forward = value;
        return this;
    }
    public CarpetPlayerActionPack setStrafing(float value)
    {
        movementGeneration++;
        strafing = value;
        return this;
    }
    public CarpetPlayerActionPack look(Direction direction)
    {
        return switch (direction)
        {
            case NORTH -> look(180, 0);
            case SOUTH -> look(0, 0);
            case EAST  -> look(-90, 0);
            case WEST  -> look(90, 0);
            case UP    -> look(player.getYRot(), -90);
            case DOWN  -> look(player.getYRot(), 90);
        };
    }
    public CarpetPlayerActionPack look(Vec2 rotation)
    {
        return look(rotation.x, rotation.y);
    }

    public CarpetPlayerActionPack look(float yaw, float pitch)
    {
        player.setYRot(yaw % 360); //setYaw
        player.setXRot(Mth.clamp(pitch, -90, 90)); // setPitch
        // maybe player.moveTo(player.getX(), player.getY(), player.getZ(), yaw, Mth.clamp(pitch,-90.0F, 90.0F));
        return this;
    }

    public CarpetPlayerActionPack lookAt(Vec3 position)
    {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, position);
        return this;
    }

    public CarpetPlayerActionPack turn(float yaw, float pitch)
    {
        return look(player.getYRot() + yaw, player.getXRot() + pitch);
    }

    public CarpetPlayerActionPack turn(Vec2 rotation)
    {
        return turn(rotation.x, rotation.y);
    }

    public CarpetPlayerActionPack stopMovement()
    {
        movementGeneration++;
        setSneaking(false);
        setSprinting(false);
        forward = 0.0F;
        strafing = 0.0F;
        return this;
    }


    public CarpetPlayerActionPack stopAll()
    {
        if (player instanceof ServerBot bot && bot.getBotActions() != null) bot.getBotActions().clear();
        for (ActionType type : actions.keySet()) { actions.get(type).cancel(); type.stop(player, actions.get(type)); }
        actions.clear();
        pendingMount = false;
        completedAttempts.clear();
        if (!completion.hasPending()) targetArea.close();
        return stopMovement();
    }

    public CarpetPlayerActionPack mount(boolean onlyRideables)
    {
        pendingMount = true;
        mountOnlyRideables = onlyRideables;
        if (prepareTargets()) {
            try { mountNow(onlyRideables); }
            catch (CarpetPlayerTargetArea.Pending waiting) { pendingMount = true; }
        }
        return this;
    }

    private CarpetPlayerActionPack mountNow(boolean onlyRideables)
    {
        pendingMount = false;
        //test what happens
        List<Entity> entities;
        if (onlyRideables)
        {
            entities = CarpetPlayerTracer.nearby(player, player.getBoundingBox().inflate(3.0D, 1.0D, 3.0D),
                    e -> e instanceof Minecart || e instanceof Boat || e instanceof AbstractHorse);
        }
            else
        {
            entities = CarpetPlayerTracer.nearby(player, player.getBoundingBox().inflate(3.0D, 1.0D, 3.0D), e -> true);
        }
        if (entities.size()==0)
            return this;
        Entity closest = null;
        double distance = Double.POSITIVE_INFINITY;
        Entity currentVehicle = player.getVehicle();
        for (Entity e: entities)
        {
            if (e == player || (currentVehicle == e))
                continue;
            double dd = player.distanceToSqr(e);
            if (dd<distance)
            {
                distance = dd;
                closest = e;
            }
        }
        if (closest == null) return this;
        if (closest instanceof AbstractHorse && onlyRideables)
            ((AbstractHorse) closest).mobInteract(player, InteractionHand.MAIN_HAND);
        else
            player.startRiding(closest,true, true);
        return this;
    }
    public CarpetPlayerActionPack dismount()
    {
        pendingMount = false;
        player.stopRiding();
        return this;
    }

    public void onUpdate()
    {
        try (var carpetMicroPhase = fun.bm.lophine.carpet.TisMicroTiming.active() ? fun.bm.lophine.carpet.TisMicroTiming.phase(player.level(), "async_task", "action_pack/" + player.getScoreboardName()) : fun.bm.lophine.carpet.TisMicroTiming.noop()) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(player, "Carpet player actions must own their player");
        if (completion.paused() || carpet.script.external.ScarpetPlayerInventoryGate.paused(player)) {
            player.zza = 0; player.xxa = 0; player.setJumping(false);
            return;
        }
        Map<ActionType, Boolean> actionAttempts = new HashMap<>(completedAttempts);
        actions.entrySet().removeIf(entry -> entry.getValue().done
            && entry.getValue().pending == null && !completedAttempts.containsKey(entry.getKey()));
        boolean ready = prepareTargets();
        if (pendingMount && ready) {
            try { mountNow(mountOnlyRideables); }
            catch (CarpetPlayerTargetArea.Pending waiting) { pendingMount = true; }
        }
        for (Map.Entry<ActionType, Action> e : actions.entrySet())
        {
            ActionType type = e.getKey();
            Action action = e.getValue();
            if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player) || player.isRemoved()) { targetArea.close(); return; }
            if ((type == ActionType.ATTACK || type == ActionType.USE && action.needsTrace(this)) && !ready) continue;
            if ((type == ActionType.USE || type == ActionType.ATTACK) && pendingTargets()) continue;
            // skipping attack if use was successful
            if (!(actionAttempts.getOrDefault(ActionType.USE, false) && type == ActionType.ATTACK))
            {
                Boolean actionStatus;
                try { actionStatus = actionAttempts.containsKey(type) ? actionAttempts.get(type) : action.tick(this, type); }
                catch (CarpetPlayerTargetArea.Pending waiting) { action.next = Math.max(1, action.next); continue; }
                if (actionStatus != null)
                    actionAttempts.put(type, actionStatus);
            }
            // optionally retrying use after successful attack and unsuccessful use
            if (type == ActionType.ATTACK
                    && actionAttempts.getOrDefault(ActionType.ATTACK, false)
                    && !actionAttempts.getOrDefault(ActionType.USE, true) )
            {
                // according to MinecraftClient.handleInputEvents
                Action using = actions.get(ActionType.USE);
                if (using != null) // this is always true - we know use worked, but just in case
                {
                    // This attack grants one retry, even if that retry itself becomes deferred.
                    completedAttempts.remove(ActionType.ATTACK);
                    actionAttempts.remove(ActionType.ATTACK);
                    using.retry(this, ActionType.USE);
                }
            }
        }
        if (ready && !pendingTargets()) completedAttempts.clear();
        else { // preserve this attempt's USE/ATTACK coupling through native replay
            for (ActionType type : new ActionType[] { ActionType.USE, ActionType.ATTACK }) {
                if (actionAttempts.containsKey(type)) completedAttempts.put(type, actionAttempts.get(type));
            }
        }
        prepareTargets(); // release completed once scopes, retain active and deferred actions
        float vel = sneaking?0.3F:1.0F;
        // The != 0.0F checks are needed given else real players can't control minecarts, however it works with fakes and else they don't stop immediately
        if (forward != 0.0F || player instanceof ServerBot) {
            player.zza = forward * vel;
        }
        if (strafing != 0.0F || player instanceof ServerBot) {
            player.xxa = strafing * vel;
        }
    
        }
    }

    static HitResult getTarget(ServerPlayer player)
    {
        double reach = player.gameMode.isCreative() ? 5 : 4.5f;
        return CarpetPlayerTracer.rayTrace(player, 1, reach, false);
    }

    private void dropItemFromSlot(int slot, boolean dropAll)
    {
        Inventory inv = player.getInventory(); // getInventory;
        if (!inv.getItem(slot).isEmpty())
            player.drop(inv.removeItem(slot,
                    dropAll ? inv.getItem(slot).getCount() : 1
            ), false, Prediction.SERVER_ONLY); // scatter, keep owner
    }

    public void drop(int selectedSlot, boolean dropAll)
    {
        Inventory inv = player.getInventory(); // getInventory;
        if (selectedSlot == -2) // all
        {
            for (int i = inv.getContainerSize(); i >= 0; i--)
                dropItemFromSlot(i, dropAll);
        }
        else // one slot
        {
            if (selectedSlot == -1)
                selectedSlot = inv.getSelectedSlot();
            dropItemFromSlot(selectedSlot, dropAll);
        }
    }

    public void setSlot(int slot)
    {
        player.getInventory().setSelectedSlot(slot-1);
        player.connection.send(new ClientboundSetHeldSlotPacket(slot-1));
    }

    public net.minecraft.nbt.CompoundTag save() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putBoolean("sneaking", sneaking);
        tag.putBoolean("sprinting", sprinting);
        tag.putFloat("forward", forward);
        tag.putFloat("strafing", strafing);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (Map.Entry<ActionType, Action> entry : actions.entrySet()) {
            Action action = entry.getValue();
            if (action.done && !completedAttempts.containsKey(entry.getKey())) continue;
            net.minecraft.nbt.CompoundTag record = new net.minecraft.nbt.CompoundTag();
            record.putString("type", entry.getKey().name());
            record.putInt("limit", action.limit);
            record.putInt("interval", action.interval);
            record.putInt("offset", action.offset);
            record.putInt("count", action.count);
            record.putBoolean("done", action.done);
            record.putInt("next", action.next);
            record.putBoolean("continuous", action.isContinuous);
            record.putInt("perTick", action.perTick);
            record.putInt("useProgress", action.useProgress);
            list.add(record);
        }
        tag.put("actions", list);
        net.minecraft.nbt.CompoundTag attempts = new net.minecraft.nbt.CompoundTag();
        completedAttempts.forEach((type, value) -> attempts.putBoolean(type.name(), value));
        tag.put("completedAttempts", attempts);
        return tag;
    }

    public void load(net.minecraft.nbt.CompoundTag tag) {
        stopAll();
        setSneaking(tag.getBooleanOr("sneaking", false));
        setSprinting(tag.getBooleanOr("sprinting", false));
        forward = tag.getFloatOr("forward", 0.0F);
        strafing = tag.getFloatOr("strafing", 0.0F);
        for (net.minecraft.nbt.Tag entry : tag.getList("actions").orElseGet(net.minecraft.nbt.ListTag::new)) {
            if (!(entry instanceof net.minecraft.nbt.CompoundTag record)) continue;
            try {
                ActionType type = ActionType.valueOf(record.getStringOr("type", ""));
                Action action = new Action(record.getIntOr("limit", -1), Math.max(1, record.getIntOr("interval", 1)),
                    Math.max(0, record.getIntOr("offset", 0)), record.getBooleanOr("continuous", false));
                action.count = Math.max(0, record.getIntOr("count", 0));
                action.done = record.getBooleanOr("done", false);
                action.next = Math.max(1, record.getIntOr("next", action.interval));
                action.perTick = Math.clamp(record.getIntOr("perTick", 1), 1, 64);
                action.useProgress = Math.clamp(record.getIntOr("useProgress", 0), 0, action.perTick - 1);
                actions.put(type, action);
            } catch (IllegalArgumentException ignored) {
                // Ignore actions that no longer exist after an upstream version change.
            }
        }
        var attempts = tag.getCompound("completedAttempts").orElseGet(net.minecraft.nbt.CompoundTag::new);
        for (ActionType type : new ActionType[] { ActionType.USE, ActionType.ATTACK }) {
            if (actions.containsKey(type) && attempts.contains(type.name()))
                completedAttempts.put(type, attempts.getBooleanOr(type.name(), false));
        }
    }

    private static java.util.concurrent.CompletableFuture<InteractionResult> carpetInteraction(ServerPlayer player,java.util.function.Supplier<InteractionResult> original) {
        return TisCommandContinuations.phase(player,()->{
            var result=actual(original.get());carpet.script.external.ScarpetNativeWork.record(result);return result;
        }).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(result->result));
    }

    private static java.util.concurrent.CompletableFuture<InteractionResult> carpetInteractAt(Entity entity, ServerPlayer player, InteractionHand hand, Vec3 hit) {
        return carpetInteraction(player,()->entity.interact(player,hand,hit)).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(result->{
            if(!fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.fakePlayerInteractLikeClient
                ||!(entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand))
                return java.util.concurrent.CompletableFuture.completedFuture(result);
            return TisCommandContinuations.owned(entity,()->stand.isMarker())
                .thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(marker->marker?java.util.concurrent.CompletableFuture.completedFuture(result):owned(player,()->
                    java.util.concurrent.CompletableFuture.completedFuture(!player.getItemInHand(hand).is(net.minecraft.world.item.Items.NAME_TAG)&&!player.isSpectator()?InteractionResult.PASS:result))));
        }));
    }

    private static java.util.concurrent.CompletableFuture<InteractionResult> carpetInteractOn(ServerPlayer player, Entity entity, InteractionHand hand, Vec3 hit) {
        return carpetInteraction(player,()->player.interactOn(entity,hand,hit)).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(result->{
            if(!fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.fakePlayerInteractLikeClient)
                return java.util.concurrent.CompletableFuture.completedFuture(result);
            return owned(player,()->{
                if(player.isSecondaryUseActive())return java.util.concurrent.CompletableFuture.completedFuture(result);
                if(entity instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat)return java.util.concurrent.CompletableFuture.completedFuture(InteractionResult.SUCCESS);
                if(entity instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart cart)
                    return TisCommandContinuations.owned(entity,()->cart.isVehicle()).thenApply(vehicle->vehicle?result:InteractionResult.SUCCESS);
                return java.util.concurrent.CompletableFuture.completedFuture(result);
            });
        }));
    }

    private static java.util.concurrent.CompletableFuture<Boolean> completed(boolean value) {
        return java.util.concurrent.CompletableFuture.completedFuture(value);
    }

    private static <T> java.util.concurrent.CompletableFuture<T> owned(ServerPlayer player,
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<T>> operation) {
        return owned(player,operation,CarpetNativeActionContext.current()==player?player:null);
    }

    private static <T> java.util.concurrent.CompletableFuture<T> ownedSnapshot(ServerPlayer player,
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<T>> operation) {
        return owned(player,operation,null);
    }

    private static <T> java.util.concurrent.CompletableFuture<T> invokeOwned(ServerPlayer player,
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<T>> operation, ServerPlayer admittedJob,
            carpet.script.external.ScarpetNativeWork.Token jobToken) {
        try {
            var actual=CarpetNativeActionContext.inNative(jobToken,()->CarpetNativeActionContext.with(admittedJob,()-> {
                if(admittedJob!=null)try(var accepted=carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player);
                        var scope=carpet.script.external.ScarpetInteractionContinuations.open()) { return operation.get(); }
                else try(var permissions=carpet.script.external.ScarpetPlayerInventoryGate.inheritAccepted(java.util.Set.of());
                        var scope=carpet.script.external.ScarpetInteractionContinuations.open()) { return operation.get(); }
            }));
            return CarpetNativeActionContext.relay(admittedJob,jobToken,actual);
        } catch(Throwable failure) {
            return CarpetNativeActionContext.relay(admittedJob,jobToken,java.util.concurrent.CompletableFuture.failedFuture(failure));
        }
    }

    private static <T> java.util.concurrent.CompletableFuture<T> owned(ServerPlayer player,
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<T>> operation,ServerPlayer admittedJob) {
        var jobToken=admittedJob==null?null:carpet.script.external.ScarpetNativeWork.capture();
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)) {
            var actual=invokeOwned(player,operation,admittedJob,jobToken);
            if(jobToken!=null)carpet.script.external.ScarpetNativeWork.record(actual);
            return actual;
        }
        var future = new java.util.concurrent.CompletableFuture<T>();
        boolean accepted = player.getBukkitEntity().taskScheduler.schedule((ServerPlayer owner) -> {
            invokeOwned(player,operation,admittedJob,jobToken).whenComplete((value, failure) -> {
                    if (failure == null) future.complete(value); else future.completeExceptionally(failure);
            });
        }, retired -> future.completeExceptionally(new IllegalStateException("Player action owner retired")), 1L);
        if (!accepted) future.completeExceptionally(new IllegalStateException("Player action scheduler retired"));
        var actual=CarpetNativeActionContext.relay(admittedJob,jobToken,future);
        if(jobToken!=null)carpet.script.external.ScarpetNativeWork.record(actual);
        return actual;
    }

    private static java.util.concurrent.CompletableFuture<InteractionResult> actual(InteractionResult result) {
        return result instanceof InteractionResult.Deferred pending ? pending.plan().future()
            : java.util.concurrent.CompletableFuture.completedFuture(result);
    }

    private static java.util.concurrent.CompletableFuture<Boolean> used(ServerPlayer player) {
        player.carpetActionPack.itemUseCooldown = 3;
        return completed(true);
    }

    private static java.util.concurrent.CompletableFuture<Boolean> useHand(ServerPlayer player, Action action, ServerLevel world, HitResult hit, int index) {
        if (action.cancelled || index >= InteractionHand.values().length) return completed(false);
        player.carpetActionPack.targetArea.requireCaptured(player, world);
        InteractionHand hand = InteractionHand.values()[index];
        if (hit.getType() == HitResult.Type.BLOCK) {
            player.resetLastActionTime();
            BlockHitResult blockHit = (BlockHitResult)hit;
            BlockPos pos = blockHit.getBlockPos();
            if (pos.getY() < world.getMaxY() - (blockHit.getDirection() == Direction.UP ? 1 : 0) && world.mayInteract(player, pos)) {
                return actual(player.gameMode.useItemOn(player, world, player.getItemInHand(hand), hand, blockHit))
                    .thenCompose(result -> owned(player, () -> {
                        if (action.cancelled) return completed(false);
                        if (result instanceof InteractionResult.Success success) {
                            if (success.swingSource() == InteractionResult.SwingSource.SERVER_ONLY) player.swing(hand, SwingAnimation.DEFAULT, true);
                            return used(player);
                        }
                        return useAir(player, action, world, hit, index);
                    }));
            }
        } else if (hit.getType() == HitResult.Type.ENTITY) {
            player.resetLastActionTime();
            EntityHitResult entityHit = (EntityHitResult)hit;
            Entity entity = entityHit.getEntity();
            if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity)) throw new CarpetPlayerTargetArea.Pending();
            ItemStack handItem = player.getItemInHand(hand);
            boolean emptyFrame = handItem.isEmpty() && entity instanceof ItemFrame frame && frame.getItem().isEmpty();
            boolean feedingHorse = entity instanceof AbstractHorse horse && horse.isAlive() && horse.isFood(handItem) && !(horse instanceof SkeletonHorse);
            Vec3 relative = entityHit.getLocation().subtract(entity.getX(), entity.getY(), entity.getZ());
            return carpetInteractAt(entity, player, hand, relative).thenCompose(result -> owned(player, () -> {
                if (action.cancelled) return completed(false);
                if (result.consumesAction()) return used(player);
                if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity)) throw new CarpetPlayerTargetArea.Pending();
                return carpetInteractOn(player, entity, hand, relative).thenCompose(on -> owned(player, () -> {
                    if (action.cancelled) return completed(false);
                    if (on.consumesAction() && !emptyFrame || feedingHorse) return used(player);
                    return useAir(player, action, world, hit, index);
                }));
            }));
        }
        return useAir(player, action, world, hit, index);
    }

    private static java.util.concurrent.CompletableFuture<Boolean> useAir(ServerPlayer player, Action action, ServerLevel world, HitResult hit, int index) {
        if (action.cancelled) return completed(false);
        player.carpetActionPack.targetArea.requireCaptured(player, world);
        InteractionHand hand = InteractionHand.values()[index];
        return actual(player.gameMode.useItem(player, world, player.getItemInHand(hand), hand)).thenCompose(result -> owned(player, () -> {
            if (action.cancelled) return completed(false);
            return result.consumesAction() ? used(player) : useHand(player, action, world, hit, index + 1);
        }));
    }

    public enum ActionType
    {
        USE(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                throw new IllegalStateException("USE actions require their native result continuation");
            }

            @Override
            java.util.concurrent.CompletableFuture<Boolean> executeAsync(ServerPlayer player, Action action) {
                return owned(player, () -> {
                    CarpetPlayerActionPack ap = player.carpetActionPack;
                    if (ap.itemUseCooldown > 0) { ap.itemUseCooldown--; return completed(true); }
                    if (player.isUsingItem()) return completed(true);
                    // Previous deferred passes have finished here, so a fresh pass may retarget.
                    if (!ap.prepareTargets(false)) throw new CarpetPlayerTargetArea.Pending();
                    return useHand(player, action, player.level(), getTarget(player), 0);
                });
            }

            @Override
            void inactiveTick(ServerPlayer player, Action action)
            {
                CarpetPlayerActionPack ap = player.carpetActionPack;
                ap.itemUseCooldown = 0;
                player.releaseUsingItem();
            }
        },
        ATTACK(true) {
            @Override
            boolean execute(ServerPlayer player, Action action) {
                throw new IllegalStateException("ATTACK actions require their native completion observation");
            }

            @Override
            java.util.concurrent.CompletableFuture<Boolean> executeAsync(ServerPlayer player, Action action) {
                return owned(player, () -> {
                    if (action.cancelled) return completed(false);
                    if (!player.carpetActionPack.prepareTargets(false)) throw new CarpetPlayerTargetArea.Pending();
                    HitResult hit = getTarget(player);
                    if (hit instanceof EntityHitResult entityHit) {
                        if (action.isContinuous) {
                            player.resetAttackStrengthTicker(); player.resetLastActionTime();
                            return completed(true);
                        }
                        return carpet.script.external.ScarpetNativeWork.observeNative(player, () -> {
                            player.attack(entityHit.getEntity()); return true;
                        }).thenCompose(value -> owned(player, () -> {
                            if (!action.cancelled) {
                                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                                player.resetAttackStrengthTicker();
                                player.resetLastActionTime();
                            }
                            return completed(value);
                        }));
                    }
                    return carpet.script.external.ScarpetNativeWork.observeNative(player, () -> executeBlock(player, action, hit));
                });
            }

            private boolean executeBlock(ServerPlayer player, Action action, HitResult hit) {
                switch (hit.getType()) {
                    case BLOCK: {
                        CarpetPlayerActionPack ap = player.carpetActionPack;
                        if (ap.blockHitDelay > 0)
                        {
                            ap.blockHitDelay--;
                            return false;
                        }
                        BlockHitResult blockHit = (BlockHitResult) hit;
                        BlockPos pos = blockHit.getBlockPos();
                        Direction side = blockHit.getDirection();
                        if (player.blockActionRestricted(player.level(), pos, player.gameMode.getGameModeForPlayer())) return false;
                        if (ap.currentBlock != null && (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player.level(), ap.currentBlock) || player.level().getBlockState(ap.currentBlock).isAir()))
                        {
                            ap.currentBlock = null;
                            return false;
                        }
                        BlockState state = player.level().getBlockState(pos);
                        boolean blockBroken = false;
                        if (player.gameMode.getGameModeForPlayer().isCreative())
                        {
                            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                            ap.blockHitDelay = 5;
                            blockBroken = true;
                        }
                        else  if (ap.currentBlock == null || !ap.currentBlock.equals(pos))
                        {
                            if (ap.currentBlock != null)
                            {
                                player.gameMode.handleBlockBreakAction(ap.currentBlock, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                            }
                            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                            boolean notAir = !state.isAir();
                            if (notAir && ap.curBlockDamageMP == 0)
                            {
                                state.attack(player.level(), pos, player);
                            }
                            if (notAir && state.getDestroyProgress(player, player.level(), pos) >= 1)
                            {
                                ap.currentBlock = null;
                                //instamine??
                                blockBroken = true;
                            }
                            else
                            {
                                ap.currentBlock = pos;
                                ap.curBlockDamageMP = 0;
                            }
                        }
                        else
                        {
                            ap.curBlockDamageMP += state.getDestroyProgress(player, player.level(), pos);
                            if (ap.curBlockDamageMP >= 1)
                            {
                                player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                                ap.currentBlock = null;
                                ap.blockHitDelay = 5;
                                blockBroken = true;
                            }
                            player.level().destroyBlockProgress(-1, pos, (int) (ap.curBlockDamageMP * 10));

                        }
                        player.resetLastActionTime();
                        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                        return blockBroken;
                    }
                }
                return false;
            }

            @Override
            void inactiveTick(ServerPlayer player, Action action)
            {
                CarpetPlayerActionPack ap = player.carpetActionPack;
                if (ap.currentBlock == null) return;
                if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player.level(), ap.currentBlock)) {
                    ap.currentBlock = null;
                    return;
                }
                player.level().destroyBlockProgress(-1, ap.currentBlock, -1);
                player.gameMode.handleBlockBreakAction(ap.currentBlock, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.DOWN, player.level().getMaxY(), -1);
                ap.currentBlock = null;
            }
        },
        JUMP(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                if (action.limit == 1)
                {
                    if (player.onGround()) player.jumpFromGround();
                    else if (!player.onClimbable()) player.tryToStartFallFlying();
                }
                else
                {
                    player.setJumping(true);
                }
                return false;
            }

            @Override
            void inactiveTick(ServerPlayer player, Action action)
            {
                player.setJumping(false);
            }
        },
        DROP_ITEM(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                player.resetLastActionTime();
                player.drop(false); // dropSelectedItem
                return false;
            }
        },
        DROP_STACK(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                player.resetLastActionTime();
                player.drop(true); // dropSelectedItem
                return false;
            }
        },
        SWAP_HANDS(true)
        {
            @Override
            boolean execute(ServerPlayer player, Action action)
            {
                player.resetLastActionTime();
                ItemStack itemStack_1 = player.getItemInHand(InteractionHand.OFF_HAND);
                player.setItemInHand(InteractionHand.OFF_HAND, player.getItemInHand(InteractionHand.MAIN_HAND));
                player.setItemInHand(InteractionHand.MAIN_HAND, itemStack_1);
                return false;
            }
        };

        public final boolean preventSpectator;

        ActionType(boolean preventSpectator)
        {
            this.preventSpectator = preventSpectator;
        }

        void start(ServerPlayer player, Action action) {}
        abstract boolean execute(ServerPlayer player, Action action);
        java.util.concurrent.CompletableFuture<Boolean> executeAsync(ServerPlayer player, Action action) {
            return completed(execute(player, action));
        }
        void inactiveTick(ServerPlayer player, Action action) {}
        void stop(ServerPlayer player, Action action)
        {
            inactiveTick(player, action);
        }
    }

    public static class Action
    {
        public boolean isContinuous() { return this.isContinuous; }
        public boolean done = false;
        public final int limit;
        public final int interval;
        public final int offset;
        private int count;
        private int next;
        private final boolean isContinuous;
        private int perTick = 1;
        private java.util.concurrent.CompletableFuture<Boolean> pending;
        private boolean cancelled;
        private int useProgress;

        private boolean needsTrace(CarpetPlayerActionPack pack) {
            return pending != null || interval == 1 && !isContinuous || pack.itemUseCooldown == 0 && !pack.player.isUsingItem();
        }

        private Action copy() {
            Action result = new Action(limit, interval, offset, isContinuous);
            result.count = count; result.next = next; result.perTick = perTick;
            result.done = done; result.useProgress = useProgress;
            return result;
        }

        private void cancel() { cancelled = true; }

        private java.util.concurrent.CompletableFuture<Boolean> executeObserved(CarpetPlayerActionPack pack, ActionType type) {
            return owned(pack.player, () -> carpet.script.external.ScarpetNativeWork.observeNative(pack.player, () -> {
                var result = type.executeAsync(pack.player, this);
                carpet.script.external.ScarpetNativeWork.record(result);
                return result;
            }).thenCompose(result -> result));
        }

        private java.util.concurrent.CompletableFuture<Boolean> nativeBatch(CarpetPlayerActionPack pack, ActionType type) {
            return CarpetActionSequence.run(useProgress, perTick, false, step -> owned(pack.player, () -> {
                    if (cancelled) return completed(false);
                    return executeObserved(pack, type);
                }), (step, value) -> owned(pack.player, () -> {
                    return carpet.script.external.ScarpetNativeWork.observeNative(pack.player, () -> {
                        if (step < perTick - 1 && !cancelled) type.inactiveTick(pack.player, this);
                        return value;
                    }).thenCompose(resolved -> owned(pack.player, () -> {
                        useProgress = step + 1;
                        return completed(resolved);
                    }));
                }));
        }

        private void completeNative(CarpetPlayerActionPack pack, ActionType type) {
            useProgress = 0;
            count++;
            if (count == limit) done = true;
            next = interval;
        }

        private Boolean nativeAttempt(CarpetPlayerActionPack pack, ActionType type, boolean retry) {
            if (pack.completion.paused() || carpet.script.external.ScarpetPlayerInventoryGate.paused(pack.player)) return null;
            var immediate=new java.util.concurrent.atomic.AtomicReference<Boolean>();
            var observed=carpet.script.external.ScarpetNativeWork.observeNative(pack.player,()->
                CarpetNativeActionContext.with(pack.player,()-> {
                    Boolean value=nativeAttemptAccepted(pack,type,retry);immediate.set(value);return value;
                }));
            carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(pack.player,observed);
            return immediate.get();
        }

        private Boolean nativeAttemptAccepted(CarpetPlayerActionPack pack,ActionType type,boolean retry) {
            var accepted = pack.completion.begin();
            carpet.script.external.ScarpetNativeWork.record(accepted.future());
            carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(pack.player,accepted.future());
            var operation = owned(pack.player, () -> carpet.script.external.ScarpetNativeWork.observeNative(pack.player, () -> {
                if (!retry && useProgress == 0 && interval == 1 && !isContinuous && !cancelled) type.inactiveTick(pack.player, this);
                return false;
            })).thenCompose(ignored -> owned(pack.player, () -> retry ? executeObserved(pack, type) : nativeBatch(pack, type)))
                .thenCompose(value -> owned(pack.player, () -> carpet.script.external.ScarpetNativeWork.observeNative(pack.player, () -> {
                    if (!cancelled && count + 1 == limit) type.stop(pack.player, null);
                    return value;
                })));
            if (operation.isDone() && !operation.isCompletedExceptionally()) {
                Boolean value = operation.getNow(false);
                try { completeNative(pack, type); accepted.finish(); return value; }
                catch(Throwable failure){accepted.finish(failure);throw failure;}
            }
            pending = operation;
            operation.whenComplete((value, failure) -> owned(pack.player, () -> {
                if (pack.actions.get(type) != this || cancelled) return completed(false);
                pending = null;
                if (failure == null) {
                    completeNative(pack, type);
                    if (!retry) pack.completedAttempts.put(type, value);
                } else if (rootFailure(failure) instanceof CarpetPlayerTargetArea.Pending) {
                    next = 1; // preserve count and completed extra passes; retry only the unfinished pass
                } else {
                    next = Math.max(interval, 20);
                    net.minecraft.server.MinecraftServer.LOGGER.error("Carpet player " + type + " native continuation failed", failure);
                }
                return completed(false);
            }).whenComplete((committed, commitFailure) -> {
                accepted.finish(failure != null ? failure : commitFailure);
                // Replaced/cancelled actions retain their target footprint until the accepted
                // native tail and owner count/done commit have actually terminated.
                owned(pack.player, () -> { pack.prepareTargets(); return completed(false); });
            }));
            return null;
        }

        private static Throwable rootFailure(Throwable failure) {
            while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) failure = failure.getCause();
            return failure;
        }

        private Action(int limit, int interval, int offset, boolean continuous)
        {
            this.limit = limit;
            this.interval = interval;
            this.offset = offset;
            next = interval + offset;
            isContinuous = continuous;
        }

        public static Action once()
        {
            return new Action(1, 1, 0, false);
        }

        public static Action continuous()
        {
            return new Action(-1, 1, 0, true);
        }

        public static Action interval(int interval)
        {
            return new Action(-1, interval, 0, false);
        }

        public static Action interval(int interval, int offset)
        {
            return new Action(-1, interval, offset, false);
        }

        // TIS actions keep the same offset semantics as the original Carpet constructor.
        public static Action after(int delay)
        {
            return new Action(1, 1, delay, false);
        }

        public static Action perTick(int multiplier)
        {
            Action action = interval(1);
            action.perTick = Math.clamp(multiplier, 1, 64);
            return action;
        }

        Boolean tick(CarpetPlayerActionPack actionPack, ActionType type)
        {
            if ((type == ActionType.ATTACK || type == ActionType.USE && needsTrace(actionPack)) && !actionPack.prepareTargets()) return null;
            if (pending != null) return null;
            next--;
            Boolean cancel = null;
            if (next <= 0)
            {
                if (interval == 1 && !isContinuous && type != ActionType.USE && type != ActionType.ATTACK)
                {
                    // need to allow entity to tick, otherwise won't have effect (bow)
                    // actions are 20 tps, so need to clear status mid tick, allowing entities process it till next time
                    if (!type.preventSpectator || !actionPack.player.isSpectator())
                    {
                        type.inactiveTick(actionPack.player, this);
                    }
                }

                if (type == ActionType.USE || type == ActionType.ATTACK) {
                    if (actionPack.player.isSpectator()) { count++; next = interval; if (count == limit) { type.stop(actionPack.player, null); done = true; } return null; }
                    return nativeAttempt(actionPack, type, false);
                }
                if (!type.preventSpectator || !actionPack.player.isSpectator())
                {
                    for (int i = 1; i < perTick; ++i)
                    {
                        type.execute(actionPack.player, this);
                        type.inactiveTick(actionPack.player, this);
                    }
                    cancel = type.execute(actionPack.player, this);
                }
                count++;
                if (count == limit)
                {
                    type.stop(actionPack.player, null);
                    done = true;
                    return cancel;
                }
                next = interval;
            }
            else
            {
                if (!type.preventSpectator || !actionPack.player.isSpectator())
                {
                    type.inactiveTick(actionPack.player, this);
                }
            }
            return cancel;
        }

        void retry(CarpetPlayerActionPack actionPack, ActionType type)
        {
            //assuming action run but was unsuccesful that tick, but opportunity emerged to retry it, lets retry it.
            if (type == ActionType.USE || type == ActionType.ATTACK) { if (pending == null && !actionPack.player.isSpectator()) nativeAttempt(actionPack, type, true); return; }
            if (!type.preventSpectator || !actionPack.player.isSpectator())
            {
                type.execute(actionPack.player, this);
            }
            count++;
            if (count == limit)
            {
                type.stop(actionPack.player, null);
                done = true;
            }
        }
    }
}
