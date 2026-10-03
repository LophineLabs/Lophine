// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetDamageContinuations;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import carpet.script.external.ScarpetRuntime;
import carpet.script.external.WeakIdentityMap;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** A kill command's death-message identity survives its actual deferred native damage/death tail. */
public final class OrgSuicideCommand {
    private static final ScopedValue<Request> REQUEST=ScopedValue.newInstance();
    private static final WeakIdentityMap<DamageSource,WeakReference<ServerPlayer>> SOURCES=new WeakIdentityMap<>();
    private static final class Request {
        final ServerPlayer player;final Set<DamageSource> sources=Collections.newSetFromMap(new IdentityHashMap<>());
        Request(ServerPlayer player){this.player=player;}
        void finish(){for(DamageSource source:sources){var marker=SOURCES.get(source);if(marker!=null&&marker.get()==player)SOURCES.remove(source,marker);}sources.clear();}
    }
    private OrgSuicideCommand() {}
    public static boolean synchronous(){return REQUEST.isBound();}
    /** Called by the existing LivingEntity.kill body, preserving its ordinary eligibility and all overrides. */
    public static DamageSource source(LivingEntity player,DamageSource original){
        if(!REQUEST.isBound()||REQUEST.get().player!=player)return original;
        DamageSource unique=new DamageSource(original.typeHolder(),original.getDirectEntity(),original.getEntity(),original.getSourcePosition(),original.getDamageContext());
        var request=REQUEST.get();request.sources.add(unique);SOURCES.put(unique,new WeakReference<>(request.player));return unique;
    }
    /** Checks the precise actual killing blow, rather than a thread's expired or unrelated command scope. */
    public static boolean committing(LivingEntity player,DamageSource source){var marker=SOURCES.get(source);return marker!=null&&marker.get()==player;}
    public static int execute(CommandSourceStack source,ServerPlayer player){
        var completion=CarpetAsyncCommandResults.defer(source);ScarpetNativeWork.trackNative(source.getServer(),completion.future());
        kill(player).whenComplete((ignored,failure)->{
            try{if(failure!=null)source.sendFailure(net.minecraft.network.chat.Component.literal("The suicide command could not finish its actual native death: "+failure.getMessage()));}
            finally{completion.complete(failure==null,failure==null?1:0);}
        });return 1;
    }
    static CompletableFuture<Void> kill(ServerPlayer player){
        var actual=new CompletableFuture<Void>();var request=new Request(player);
        ScarpetNativeWork.record(actual);ScarpetNativeWork.trackNative(player.level().getServer(),actual);
        Supplier<CompletableFuture<Void>> continuation=ScarpetRuntime.captureNativeContinuation(()->{
            ScarpetPlayerInventoryGate.trackAccepted(player,actual);
            try(var accepted=ScarpetPlayerInventoryGate.acceptedScope(player)){
                return ScarpetNativeWork.<CompletableFuture<Void>>observeNative(player,()->{
                    var before=ScarpetDamageContinuations.pendingResult(player);
                    ScopedValue.where(REQUEST,request).run(()->player.kill(player.level()));
                    var pending=ScarpetDamageContinuations.pendingResult(player);
                    return pending!=null&&pending!=before?pending.thenApply(ignored->null):CompletableFuture.completedFuture(null);
                }).thenCompose(value->value);
            }
        });
        try{start(player,continuation).whenComplete((ignored,failure)->{request.finish();if(failure==null)actual.complete(null);else actual.completeExceptionally(failure);});}
        catch(Throwable failure){request.finish();actual.completeExceptionally(failure);}
        return actual.copy();
    }
    private static CompletableFuture<Void> start(ServerPlayer player,Supplier<CompletableFuture<Void>> continuation){
        return OrgFakePlayerActions.owned(player,()->{
            if(ScarpetPlayerInventoryGate.paused(player))return ScarpetPlayerInventoryGate.whenOpen(player).thenCompose(ignored->start(player,continuation));
            return continuation.get();
        }).thenCompose(value->value);
    }
}
