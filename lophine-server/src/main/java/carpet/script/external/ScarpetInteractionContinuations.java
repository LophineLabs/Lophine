// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.OrgItemShadowGroups;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** Native result continuations. Each caller adds its real remaining work before the owner resumes. */
public final class ScarpetInteractionContinuations {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private static final Set<Plan> ACTIVE=ConcurrentHashMap.newKeySet();
    private ScarpetInteractionContinuations() {}

    public static Scope open() {
        Scope scope=new Scope(CURRENT.get()); CURRENT.set(scope); return scope;
    }
    public static final class Scope implements AutoCloseable {
        private final Scope parent;
        private final Set<Plan> plans=new LinkedHashSet<>();
        private boolean closed;
        private Scope(Scope parent) { this.parent=parent; }
        @Override public void close() {
            if(closed) return; closed=true;
            if(CURRENT.get()!=this) throw new IllegalStateException("Interaction scopes closed out of order");
            if(parent==null) { CURRENT.remove(); plans.forEach(Plan::start); }
            else { parent.plans.addAll(plans); CURRENT.set(parent); }
        }
    }

    public static InteractionResult defer(ServerPlayer owner,Supplier<Boolean> event,BooleanSupplier valid,
                                         Function<Boolean,InteractionResult> remaining,List<ItemStack> items) {
        TickThread.ensureTickThread(owner,"Scarpet interaction must capture its owner");
        return create(owner,ScarpetRuntime.captureDecisions(List.of(event)),valid,remaining,items);
    }
    public static InteractionResult managed(ServerPlayer owner,List<ItemStack> items,BooleanSupplier valid,
                                            Supplier<InteractionResult> action,Consumer<InteractionResult> delayedTail) {
        TickThread.ensureTickThread(owner,"Shared interaction must capture its owner");
        if (!ScarpetPlayerInventoryGate.paused(owner)) {
            var attempt=OrgItemShadowGroups.attempt(items,action);
            if(attempt.completed()) return attempt.value();
        }
        InteractionResult pending=create(owner,CompletableFuture.completedFuture(false),valid,ignored -> action.get(),items);
        ((InteractionResult.Deferred)pending).plan().map(result -> { delayedTail.accept(result); return result; });
        return pending;
    }
    /** An interaction returns only after its already accepted native phase and actual outer tail. */
    public static InteractionResult after(ServerPlayer owner,CompletableFuture<?> actual,Supplier<InteractionResult> tail) {
        TickThread.ensureTickThread(owner,"Native interaction completion requires its owner");
        var ready=actual.thenApply(ignored -> false);
        ScarpetNativeWork.aliasDependency(ready,actual);
        return create(owner,ready,() -> true,ignored -> tail.get(),List.of(),actual);
    }
    private static InteractionResult create(ServerPlayer owner,CompletableFuture<Boolean> decision,BooleanSupplier valid,
                                            Function<Boolean,InteractionResult> remaining,List<ItemStack> items) {
        return create(owner,decision,valid,remaining,items,null);
    }
    private static InteractionResult create(ServerPlayer owner,CompletableFuture<Boolean> decision,BooleanSupplier valid,
                                            Function<Boolean,InteractionResult> remaining,List<ItemStack> items,CompletableFuture<?> acceptedNative) {
        Scope scope=CURRENT.get();
        if(scope==null) throw new IllegalStateException("Native interaction has no result scope");
        final Plan[] created = new Plan[1];
        CompletableFuture<Plan> actual = ScarpetNativeWork.observeNative(owner, () -> {
            if(acceptedNative!=null) ScarpetNativeWork.record(acceptedNative);
            Plan plan = new Plan(owner,decision,valid,remaining,items,acceptedNative!=null);
            created[0] = plan;
            ScarpetNativeWork.record(plan.future);
            return plan;
        });
        Plan plan = created[0];
        plan.terminal = actual.thenCompose(ignored -> plan.future);
        ScarpetNativeWork.aliasDependency(plan.terminal, actual);
        if (!ScarpetPlayerInventoryGate.paused(owner)) {
            plan.admitted = true;
            ScarpetPlayerInventoryGate.trackAccepted(owner, plan.terminal);
        }
        ScarpetNativeWork.trackNative(plan.server,actual);
        ScarpetNativeWork.trackNative(plan.server,plan.terminal);
        ACTIVE.add(plan);
        plan.terminal.whenComplete((result,failure) -> ACTIVE.remove(plan));
        scope.plans.add(plan);
        return new InteractionResult.Deferred(plan);
    }

    public static InteractionResult map(InteractionResult.Deferred pending,Function<InteractionResult,InteractionResult> tail) {
        pending.plan().map(tail); return pending;
    }
    public static InteractionResult around(InteractionResult.Deferred pending,Function<Supplier<InteractionResult>,InteractionResult> scope) {
        pending.plan().around(scope); return pending;
    }

    public static final class Plan {
        private final ServerPlayer owner;
        private final MinecraftServer server;
        private final CompletableFuture<Boolean> decision;
        private final BooleanSupplier valid;
        private final List<ItemStack> items;
        private final CompletableFuture<InteractionResult> future=new CompletableFuture<>();
        private CompletableFuture<InteractionResult> terminal;
        private boolean admitted;
        private final AtomicBoolean started=new AtomicBoolean();
        private final List<Consumer<InteractionResult>> finish=new ArrayList<>();
        private final ScarpetNativeWork.Token nativeWork;
        private final Consumer<Runnable> nativeResume;
        private final boolean physicalContinuation;
        private Function<Boolean,InteractionResult> work;
        private Plan(ServerPlayer owner,CompletableFuture<Boolean> decision,BooleanSupplier valid,
                     Function<Boolean,InteractionResult> work,List<ItemStack> items,boolean physicalContinuation) {
            this.owner=owner; this.server=owner.level().getServer(); this.decision=decision;
            this.valid=valid; this.work=work; this.items=List.copyOf(items);
            this.nativeWork=ScarpetNativeWork.capture();this.physicalContinuation=physicalContinuation;
            // Restore accepted native flags before re-entering this plan's own privilege and token.
            this.nativeResume=ScarpetRuntime.captureNativeConsumer(action->{
                try(var accepted=ScarpetPlayerInventoryGate.acceptedScope(owner)){action.run();}
            });
        }
        public CompletableFuture<InteractionResult> future() {
            var view = terminal.copy(); ScarpetNativeWork.aliasDependency(view, terminal); return view;
        }
        private void building() { if(started.get()) throw new IllegalStateException("Interaction continuation already started"); }
        public void map(Function<InteractionResult,InteractionResult> tail) {
            building(); Function<Boolean,InteractionResult> previous=work;
            work=cancelled -> {
                InteractionResult result=previous.apply(cancelled);
                if(result instanceof InteractionResult.Deferred nested) { nested.plan().map(tail); return nested; }
                return tail.apply(result);
            };
        }
        public void around(Function<Supplier<InteractionResult>,InteractionResult> scope) {
            building(); Function<Boolean,InteractionResult> previous=work;
            work=cancelled -> {
                InteractionResult result=scope.apply(() -> previous.apply(cancelled));
                if(result instanceof InteractionResult.Deferred nested) nested.plan().around(scope);
                return result;
            };
        }
        /** Runs on the real owner even when a stale operation only needs packet resynchronization. */
        public void onComplete(Consumer<InteractionResult> action) { building(); finish.add(action); }
        private void start() {
            if(!started.compareAndSet(false,true)) return;
            decision.whenComplete((cancelled,failure) -> schedule(Boolean.TRUE.equals(cancelled),failure));
        }
        private void schedule(boolean cancelled,Throwable failure) {
            if(future.isDone()) return;
            boolean accepted=owner.getBukkitEntity().taskScheduler.schedule(owned -> resume(cancelled,failure),
                retired -> future.completeExceptionally(new IllegalStateException("Interaction owner retired")),1);
            if(!accepted) future.completeExceptionally(new IllegalStateException("Interaction scheduler retired"));
        }
        private void resume(boolean cancelled,Throwable failure) {
            if(future.isDone()) return;
            if (!admitted) {
                if (ScarpetPlayerInventoryGate.paused(owner)) {
                    ScarpetPlayerInventoryGate.whenOpen(owner).whenComplete((ignored, stopped) -> schedule(cancelled, stopped == null ? failure : stopped));
                    return;
                }
                admitted = true;
                ScarpetPlayerInventoryGate.trackAccepted(owner, terminal);
            }
            try {
                nativeResume.accept(()->ScarpetNativeWork.with(nativeWork,()->this.resumeObserved(cancelled,failure)));
            }
            catch(IllegalStateException stopped) { if(!future.isDone()) { future.completeExceptionally(stopped); throw stopped; } }
        }
        private void resumeObserved(boolean cancelled,Throwable failure) {
            if(future.isDone()) return;
            try(var scope=open()) {
                TickThread.ensureTickThread(owner,"Scarpet interaction continuation must own its player");
                var attempt=OrgItemShadowGroups.attempt(items,() -> {
                    InteractionResult result=failure==null && valid.getAsBoolean() ? work.apply(cancelled) : InteractionResult.FAIL;
                    if(result instanceof InteractionResult.Deferred nested) {
                        for(var tail:finish) nested.plan().onComplete(tail);
                        nested.plan().future().whenComplete((resolved,error) -> {
                            if(error==null) future.complete(resolved); else future.completeExceptionally(error);
                        });
                        return result;
                    }
                    for(var tail:finish) tail.accept(result);
                    owner.containerMenu.broadcastChanges();
                    if(failure!=null) owner.sendSystemMessage(Component.literal("Scarpet interaction callback failed: "+failure.getMessage()));
                    return result;
                });
                if(!attempt.completed()) { schedule(cancelled,failure); return; }
                if(!(attempt.value() instanceof InteractionResult.Deferred)) future.complete(attempt.value());
            } catch(Throwable problem) {
                owner.containerMenu.sendAllDataToRemote();
                future.completeExceptionally(problem);
                MinecraftServer.LOGGER.error("Scarpet native interaction continuation failed",problem);
            }
        }
    }

    public static void shutdown(MinecraftServer server) {
        for(Plan plan:List.copyOf(ACTIVE)) if(plan.server==server) {
            if(plan.admitted||plan.physicalContinuation)plan.start();
            else plan.future.completeExceptionally(new IllegalStateException("Server stopped before interaction admission"));
        }
    }
}
