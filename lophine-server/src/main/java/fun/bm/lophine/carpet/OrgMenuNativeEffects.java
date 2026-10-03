// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import carpet.script.external.ScarpetRuntime;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** True menu effects, including old-container item returns and dynamically appended native children. */
final class OrgMenuNativeEffects {
    private OrgMenuNativeEffects() {}
    /** The whole menu intent owns a private receipt before its first identity lookup or owner queue. */
    static <T> CompletableFuture<T> admit(net.minecraft.server.MinecraftServer server,Supplier<CompletableFuture<T>> operation){
        var actual=new CompletableFuture<T>();ScarpetNativeWork.record(actual);ScarpetNativeWork.trackNative(server,actual);
        try{
            var body=operation.get();ScarpetNativeWork.aliasDependency(actual,body);
            body.whenComplete((value,failure)->{if(failure==null)actual.complete(value);else actual.completeExceptionally(failure);});
        }catch(Throwable failure){actual.completeExceptionally(failure);}
        var caller=actual.copy();ScarpetNativeWork.aliasDependency(caller,actual);return caller;
    }
    static <T> CompletableFuture<T> run(ServerPlayer player,Supplier<T> operation){
        var actual=new CompletableFuture<T>();ScarpetNativeWork.record(actual);ScarpetNativeWork.trackNative(player.level().getServer(),actual);
        Supplier<CompletableFuture<T>> body=ScarpetRuntime.captureNativeContinuation(()->{
            ScarpetPlayerInventoryGate.trackAccepted(player,actual);
            try(var accepted=ScarpetPlayerInventoryGate.acceptedScope(player)){
                var observed=ScarpetNativeWork.observeNative(player,operation);ScarpetNativeWork.aliasDependency(actual,observed);
                ScarpetNativeWork.trackNative(player.level().getServer(),observed);
                return ScarpetNativeWork.recoverGuestValue(observed);
            }
        });
        try{start(player,body).whenComplete((value,failure)->{if(failure==null)actual.complete(value);else actual.completeExceptionally(failure);});}
        catch(Throwable failure){actual.completeExceptionally(failure);}
        var caller=actual.copy();ScarpetNativeWork.aliasDependency(caller,actual);return caller;
    }
    private static <T> CompletableFuture<T> start(ServerPlayer player,Supplier<CompletableFuture<T>> body){
        var admitted = ScarpetRuntime.captureNativeContinuation(() -> ScarpetPlayerInventoryGate.paused(player)
            ? TisCommandContinuations.then(ScarpetPlayerInventoryGate.whenOpen(player), ignored -> start(player,body)) : body.get());
        return OrgFakePlayerActions.owned(player,admitted).thenCompose(value->value);
    }
    /** Admission/metadata lookup is registered before its first external/owner queue. */
    static int command(CommandSourceStack source,Supplier<CompletableFuture<Boolean>> operation,String message){
        var completion=CarpetAsyncCommandResults.defer(source);
        var actual=new CompletableFuture<Boolean>();ScarpetNativeWork.record(actual);ScarpetNativeWork.trackNative(source.getServer(),actual);
        try{operation.get().whenComplete((value,failure)->{if(failure==null)actual.complete(value);else actual.completeExceptionally(failure);});}
        catch(Throwable failure){actual.completeExceptionally(failure);}
        actual.whenComplete(ScarpetRuntime.captureNativeConsumer((value,failure)->{
            boolean success=failure==null&&Boolean.TRUE.equals(value);
            if(success){completion.complete(true,1);return;}
            var feedback=admit(source.getServer(),()->{
                Supplier<Boolean> report=()->{source.sendFailure(net.minecraft.network.chat.Component.literal(message+(failure==null?"":": "+failure.getMessage())));return false;};
                if(source.getEntity()==null&&source.getLevel()==null){
                    var observed=ScarpetNativeWork.observeNative(null,report);ScarpetNativeWork.trackNative(source.getServer(),observed);return ScarpetNativeWork.recoverGuestValue(observed);
                }
                return TisCommandContinuations.feedback(source,report);
            });
            feedback.whenComplete(ScarpetRuntime.captureNativeConsumer((reported,feedbackFailure)->completion.complete(false,0)));
        }));return 1;
    }
    /** Stable inventory snapshots requested by a waiting native callback cannot await that same parent. */
    static int snapshotCommand(CommandSourceStack source,ServerPlayer viewer,Supplier<CompletableFuture<Boolean>> operation,String message){
        if(!CarpetAsyncCommandResults.hasNativeCause())return command(source,operation,message);
        var deferred=new CompletableFuture<Boolean>();
        // This is an external post-callback job. Never record it in the original parent
        // or capture the original guest/accepted identity into the deferred operation.
        ScarpetNativeWork.trackNative(source.getServer(),deferred);
        var output=source.withCallback(net.minecraft.commands.CommandResultCallback.EMPTY);
        boolean queued=OrgDeferredPlayerCommands.deferIfCausal(viewer,()->command(output,()->{
            try{CompletableFuture<Boolean> opened=operation.get();opened.whenComplete((value,failure)->{if(failure==null)deferred.complete(value);else deferred.completeExceptionally(failure);});return opened;}
            catch(Throwable failure){deferred.completeExceptionally(failure);throw failure;}
        },message),failure->deferred.completeExceptionally(failure));
        if(!queued||deferred.isCompletedExceptionally()){if(!queued)deferred.completeExceptionally(new IllegalStateException("Causal menu snapshot queue was not admitted"));return 0;}
        // The source's original callback receives queue admission exactly once; the
        // global native barrier separately owns the actual delayed open/cancel result.
        return 1;
    }
}
