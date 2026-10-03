// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetNativeRemovals;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRetiredActors;
import carpet.script.external.ScarpetRuntime;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/** The original countdown owns its actual spawn, explosion and final physical removal. */
final class OrgCreeperCommandTask {
    private final MinecraftServer server;
    private final ServerPlayer player;
    private final Creeper creeper;
    private final CompletableFuture<Boolean> finished = new CompletableFuture<>();
    private int countdown = 30;

    private OrgCreeperCommandTask(MinecraftServer server, ServerPlayer player, Creeper creeper) {
        this.server = server;
        this.player = player;
        this.creeper = creeper;
    }

    static CompletableFuture<Boolean> start(MinecraftServer server, ServerPlayer player) {
        return OrgMenuNativeEffects.admit(server, () -> {
            var created = new AtomicReference<Creeper>();
            var initial = spawn(player, created);
            var initialized = TisCommandContinuations.then(initial.handle((value, failure) -> new Initialization(value, failure)), state -> {
                if (state.failure() == null) return CompletableFuture.completedFuture(state.creeper());
                var cleanup = created.get() == null ? CompletableFuture.<Void>completedFuture(null) : remove(server, created.get());
                return TisCommandContinuations.then(cleanup.handle((value, failure) -> failure), cleanupFailure -> {
                    if (cleanupFailure != null && cleanupFailure != state.failure()) state.failure().addSuppressed(cleanupFailure);
                    return CompletableFuture.failedFuture(state.failure());
                });
            });
            return TisCommandContinuations.then(initialized, creeper -> {
            if (creeper == null) return CompletableFuture.completedFuture(false);
            var task = new OrgCreeperCommandTask(server, player, creeper);
            ScarpetNativeWork.record(task.finished);
            task.nextTick();
            return task.finished;
            });
        });
    }
    private record Initialization(Creeper creeper, Throwable failure) { }

    private record Pose(ServerLevel world, BlockPos block, Vec3 position) { }
    private static CompletableFuture<Pose> pose(ServerPlayer player) {
        return OrgMenuNativeEffects.run(player, () -> new Pose(player.level(), player.blockPosition().immutable(), player.position()));
    }
    private record SpawnAttempt(boolean retry, Creeper creeper) { }

    private static CompletableFuture<Creeper> spawn(ServerPlayer player, AtomicReference<Creeper> created) {
        return TisCommandContinuations.then(pose(player), pose -> {
            Supplier<CompletableFuture<SpawnAttempt>> captured = ScarpetRuntime.captureNativeContinuation(() ->
                OrgMenuNativeEffects.run(player, () -> {
                    if (player.level() != pose.world() || !player.blockPosition().equals(pose.block())
                        || !TickThread.isTickThreadFor(pose.world(), (pose.block().getX()-3)>>4, (pose.block().getZ()-3)>>4,
                            (pose.block().getX()+3)>>4, (pose.block().getZ()+3)>>4)) return new SpawnAttempt(true, null);
                    ArrayList<BlockPos> positions = new ArrayList<>();
                    for (BlockPos pos : BlockPos.betweenClosed(pose.block().offset(-3,-1,-3), pose.block().offset(3,1,3))) {
                        if (pose.world().getBlockState(pos).isAir()
                            && pose.world().getBlockState(pos.below()).isRedstoneConductor(pose.world(), pos.below())
                            && pose.world().getBlockState(pos.above()).isAir()) positions.add(pos.immutable());
                    }
                    BlockPos chosen = positions.isEmpty() ? pose.block() : positions.get(player.getRandom().nextInt(positions.size()));
                    Creeper creeper = new Creeper(EntityTypes.CREEPER, pose.world());
                    creeper.snapTo(Vec3.atBottomCenterOf(chosen), 0F, 0F);
                    created.set(creeper);
                    ScarpetRetiredActors.capture(creeper);
                    if (!pose.world().addFreshEntity(creeper, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM)) {
                        creeper.discard();
                        return new SpawnAttempt(false, null);
                    }
                    ScarpetRetiredActors.capture(creeper);
                    return new SpawnAttempt(false, creeper);
                }));
            var held = CarpetRegionLease.<CompletableFuture<SpawnAttempt>>runValue(pose.world(),
                (pose.block().getX()-3)>>4, (pose.block().getZ()-3)>>4, (pose.block().getX()+3)>>4, (pose.block().getZ()+3)>>4,
                lease -> captured.get());
            var actual = held.thenCompose(value -> value); ScarpetNativeWork.record(actual);
            return TisCommandContinuations.then(actual, attempt -> attempt.retry() ? spawn(player, created) : CompletableFuture.completedFuture(attempt.creeper()));
        });
    }

    private void nextTick() {
        var delayed = new CompletableFuture<Void>(); ScarpetNativeWork.record(delayed);
        var step = TisCommandContinuations.then(delayed, ignored -> pose(player));
        var tick = TisCommandContinuations.then(step, pose ->
            TisCommandContinuations.then(TisCommandContinuations.owned(creeper, () -> tick(pose)), value -> value));
        var physical = TisCommandContinuations.then(tick, result -> switch(result) {
            case STOP -> CompletableFuture.completedFuture(true);
            case FROZEN -> CompletableFuture.completedFuture(false);
            case CHECK -> TisCommandContinuations.then(pose(player), current -> TisCommandContinuations.owned(creeper,
                () -> creeper.position().distanceTo(current.position()) > 7D));
        });
        physical.whenComplete(ScarpetRuntime.captureNativeConsumer((stop, failure) -> {
            if (failure != null || Boolean.TRUE.equals(stop)) finish(failure);
            else nextTick();
        }));
        try {
            boolean admitted = player.getBukkitEntity().taskScheduler.schedule(ignored -> delayed.complete(null),
                ignored -> delayed.completeExceptionally(new IllegalStateException("Creeper command target retired")), 1L);
            if (!admitted) delayed.completeExceptionally(new IllegalStateException("Creeper command target retired"));
        } catch (Throwable failure) { delayed.completeExceptionally(failure); }
    }

    private enum TickResult { STOP, FROZEN, CHECK }
    private CompletableFuture<TickResult> tick(Pose pose) {
        if (ScarpetNativeWork.isDraining(server) || creeper.isRemoved()) return CompletableFuture.completedFuture(TickResult.STOP);
        if (!server.tickRateManager().runsNormally()) return CompletableFuture.completedFuture(TickResult.FROZEN);
        if (countdown == 30) {
            creeper.playSound(SoundEvents.CREEPER_PRIMED, 1F, .5F);
            creeper.gameEvent(GameEvent.PRIME_FUSE);
        }
        --countdown;
        if (countdown != 0) return CompletableFuture.completedFuture(countdown < 0 ? TickResult.STOP : TickResult.CHECK);
        var explosion = pose.world().explode0Async(creeper, null, null, creeper.getX(), pose.position().y, pose.position().z,
            3F, false, Level.ExplosionInteraction.NONE, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER,
            Level.DEFAULT_EXPLOSION_BLOCK_PARTICLES, SoundEvents.GENERIC_EXPLODE);
        ScarpetNativeWork.record(explosion);
        return TisCommandContinuations.then(explosion, ignored -> CompletableFuture.completedFuture(TickResult.CHECK));
    }

    private void finish(Throwable previousFailure) {
        CompletableFuture<Void> removed;
        try { removed = remove(server, creeper); }
        catch (Throwable failure) { removed = CompletableFuture.failedFuture(failure); }
        removed.whenComplete(ScarpetRuntime.captureNativeConsumer((ignored, failure) -> {
            if (previousFailure != null) {
                if (failure != null && failure != previousFailure) previousFailure.addSuppressed(failure);
                finished.completeExceptionally(previousFailure);
            } else if (failure != null) finished.completeExceptionally(failure);
            else finished.complete(true);
        }));
    }

    private static CompletableFuture<Void> remove(MinecraftServer server, Creeper creeper) {
        var captured = ScarpetRuntime.captureNativeContinuation(() -> {
            var observed = ScarpetNativeWork.observeNative(creeper, () -> {
                creeper.discard();
                var removal = ScarpetNativeRemovals.completion(creeper);
                ScarpetNativeWork.record(removal);
                return removal;
            });
            ScarpetNativeWork.trackNative(server, observed);
            return ScarpetNativeWork.recoverGuestValue(observed).thenCompose(value -> value);
        });
        var removed = ScarpetNativeRemovals.onOwnerFuture(creeper, captured).thenCompose(value -> value);
        ScarpetNativeWork.record(removed);
        return removed;
    }
}
