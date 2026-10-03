// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Physical block drops retain their original world and their real stack until the final add result.
 */
public final class OrgBlockDropRouting {
    private static final Map<Entity, CompletableFuture<Boolean>> ADDS = Collections.synchronizedMap(new WeakHashMap<>());

    private OrgBlockDropRouting() {
    }

    /**
     * Internal native consumers need the uncancellable receipt, not an externally cancellable copy.
     */
    public static CompletableFuture<Boolean> pendingNativeResult(Entity entity) {
        return ADDS.get(entity);
    }

    public static CompletableFuture<Boolean> pendingResult(Entity entity) {
        var actual = pendingNativeResult(entity);
        return actual == null ? null : caller(actual);
    }

    /**
     * Called after the original add-event prefix on the original world actor.
     */
    public static CompletableFuture<Boolean> routeNative(ServerLevel originalWorld, ItemEntity item, ServerPlayer breaker,
                                                         Supplier<Boolean> nativeRemainder) {
        var actual = new CompletableFuture<Boolean>();
        ADDS.put(item, actual);
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(originalWorld.getServer(), actual);
        ItemStack stack = item.getItem();
        BlockPos origin = item.blockPosition().immutable();
        try {
            var inventory = OrgCommandNativeEffects.intent(breaker, () -> {
                var result = new AtomicReference<Boolean>();
                Boolean immediate = OrgItemShadowGroups.actor(breaker, () -> {
                    var stacks = new ArrayList<>(OrgItemShadowGroups.inventory(breaker, breaker.inventoryMenu.slots));
                    stacks.add(stack);
                    return stacks;
                }, () -> {
                    if (!OrgRulePlayerPreferences.blockDropsEnterInventory(breaker)) return false;
                    breaker.getInventory().add(stack);
                    breaker.getInventory().setChanged();
                    return true;
                }, null, owner -> !owner.isRemoved(), result::set);
                if (immediate != null) result.set(immediate);
                return result;
            });
            var remainder = TisCommandContinuations.then(inventory, ignored -> sharedWorld(originalWorld, origin, List.of(stack), () -> {
                if (stack.isEmpty()) {
                    item.discard();
                    return true;
                }
                return nativeRemainder.get();
            }));
            finish(actual, remainder);
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        return actual;
    }

    /**
     * Preserves a direct add's real boolean even when its synchronous compatibility entry returned false while queued.
     */
    public static CompletableFuture<Boolean> addNative(ServerLevel world, Entity entity, Supplier<Boolean> original) {
        var actual = new CompletableFuture<Boolean>();
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(world.getServer(), actual);
        try {
            var deferred = new AtomicReference<CompletableFuture<Boolean>>();
            var before = pendingNativeResult(entity);
            var observed = ScarpetNativeWork.observeNative(null, () -> {
                boolean immediate = original.get();
                var after = pendingNativeResult(entity);
                if (after != null && after != before) deferred.set(after);
                return immediate;
            });
            ScarpetNativeWork.trackNative(world.getServer(), observed);
            var completed = TisCommandContinuations.then(ScarpetNativeWork.recoverGuestValue(observed), immediate ->
                    deferred.get() == null ? CompletableFuture.completedFuture(immediate) : deferred.get());
            finish(actual, completed);
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        return actual;
    }

    /**
     * The UUID check admits a real tree; each add consumes its true result before the next add.
     */
    public static CompletableFuture<Boolean> addTreeNative(ServerLevel world, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason, Supplier<List<Entity>> admit) {
        var actual = new CompletableFuture<Boolean>();
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(world.getServer(), actual);
        try {
            var tree = TisCommandContinuations.phase(null, () -> {
                List<Entity> admitted = admit.get();
                if (admitted == null) return (List<AddSite>) null;
                var sites = new ArrayList<AddSite>(admitted.size());
                for (Entity entity : admitted) {
                    carpet.script.external.ScarpetRetiredActors.capture(entity);
                    sites.add(new AddSite(entity, entity.blockPosition().immutable()));
                }
                return List.copyOf(sites);
            });
            var added = TisCommandContinuations.then(tree, sites -> {
                if (sites == null) return CompletableFuture.completedFuture(false);
                CompletableFuture<Boolean> sequence = CompletableFuture.completedFuture(true);
                for (AddSite site : sites)
                    sequence = TisCommandContinuations.then(sequence, previous -> previous
                            ? full(world, site.position(), () -> world.carpetAddFreshEntityNativeAsync(site.entity(),
                            reason)).thenCompose(value -> value)
                            : CompletableFuture.completedFuture(false));
                return sequence;
            });
            finish(actual, added);
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        return actual;
    }

    private record AddSite(Entity entity, BlockPos position) {
    }

    /**
     * A player continuation re-enters the actual owner and respects pending inventory custody.
     */
    public static <T> CompletableFuture<T> playerNative(ServerPlayer player, Supplier<T> body) {
        return OrgMenuNativeEffects.run(player, body);
    }

    /**
     * Add callers retain the source world while entering that world's current actor at the captured site.
     */
    public static <T> CompletableFuture<T> worldNative(ServerLevel originalWorld, BlockPos origin, Supplier<T> body) {
        return full(originalWorld, origin.immutable(), body);
    }

    /**
     * Current creative state is read on the actual breaker; original world RNG and the exact stack remain on their owner.
     */
    public static CompletableFuture<Void> scatterNative(ServerLevel originalWorld, BlockPos origin, ItemStack stack,
                                                        ServerPlayer breaker, Supplier<Void> originalScatter) {
        var actual = new CompletableFuture<Void>();
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(originalWorld.getServer(), actual);
        try {
            var creative = breaker != null && fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.disableCreativeContainerDrops
                    ? OrgCommandNativeEffects.intent(breaker, breaker::isCreative)
                    : CompletableFuture.completedFuture(false);
            var scatter = TisCommandContinuations.then(creative, skip -> skip ? CompletableFuture.completedFuture(null)
                    : sharedWorld(originalWorld, origin.immutable(), List.of(stack), originalScatter));
            finish(actual, scatter);
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        return actual;
    }

    /**
     * A new borrow is acquired on each actual original-world body. No caller's old borrow crosses an actor boundary.
     */
    private static <T> CompletableFuture<T> sharedWorld(ServerLevel world, BlockPos origin, List<ItemStack> stacks, Supplier<T> body) {
        var phase = full(world, origin, () -> OrgItemShadowGroups.attempt(stacks, body));
        return TisCommandContinuations.then(phase, result -> result.completed()
                ? CompletableFuture.completedFuture(result.value()) : retry(world, origin, () -> sharedWorld(world, origin, stacks, body)));
    }

    private static <T> CompletableFuture<T> full(ServerLevel world, BlockPos origin, Supplier<T> body) {
        var captured = ScarpetRuntime.captureNativeContinuation(() -> TisCommandContinuations.phase(null, body));
        var held = CarpetRegionLease.<CompletableFuture<T>>runValue(world, (origin.getX() - 1) >> 4, (origin.getZ() - 1) >> 4,
                (origin.getX() + 1) >> 4, (origin.getZ() + 1) >> 4, lease -> captured.get());
        var actual = held.thenCompose(value -> value);
        ScarpetNativeWork.record(actual);
        return actual;
    }

    private static <T> CompletableFuture<T> retry(ServerLevel world, BlockPos origin, Supplier<CompletableFuture<T>> body) {
        var actual = new CompletableFuture<T>();
        ScarpetNativeWork.record(actual);
        var captured = ScarpetRuntime.captureNativeContinuation(body);
        try {
            org.bukkit.Bukkit.getRegionScheduler().runDelayed(org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
                    world.getWorld(), origin.getX() >> 4, origin.getZ() >> 4, task -> {
                        try {
                            finish(actual, captured.get());
                        } catch (Throwable failure) {
                            actual.completeExceptionally(failure);
                        }
                    }, 1L);
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        return actual;
    }

    private static <T> void finish(CompletableFuture<T> actual, CompletableFuture<T> body) {
        ScarpetNativeWork.aliasDependency(actual, body);
        body.whenComplete((value, failure) -> {
            if (failure == null) actual.complete(value);
            else actual.completeExceptionally(failure);
        });
    }

    private static <T> CompletableFuture<T> caller(CompletableFuture<T> actual) {
        var caller = actual.copy();
        ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }
}
