// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetDamageContinuations;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Creative filtering and virtual kill bodies execute on their actual targets before source feedback. */
public final class CarpetKillCommand {
    private record Target(boolean eligible,Component name) {}
    private record Outcome(List<Target> targets,Throwable failure) { int count(){return (int)targets.stream().filter(Target::eligible).count();} }
    private CarpetKillCommand() {}
    public static int execute(CommandSourceStack source,Collection<? extends Entity> victims){
        var targets=List.copyOf(victims);var callerWorld=source.getLevel();
        boolean creativeImmune=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.creativeImmuneKill;
        var completion=CarpetAsyncCommandResults.defer(source);var actual=new CompletableFuture<Integer>();
        ScarpetNativeWork.record(actual);ScarpetNativeWork.trackNative(source.getServer(),actual);
        var delivered=new AtomicBoolean();
        var reportFailure=ScarpetRuntime.captureNativeFunction((Throwable failure)->feedback(source,()->{
            delivered.set(true);source.sendFailure(Component.literal("Kill command failed: "+failure.getMessage()));return 0;
        }));
        var observed=ScarpetNativeWork.observeNative(source.getEntity(),()->{
            var jobs=targets.stream().map(target->TisCommandContinuations.entity(source,target,()->kill(target,callerWorld,creativeImmune))).toList();
            var nativeBodies=CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new));ScarpetNativeWork.record(nativeBodies);
            var result=nativeBodies.handle((ignored,failure)->new Outcome(failure==null?jobs.stream().map(CompletableFuture::join).toList():List.of(),failure));
            var publication=TisCommandContinuations.then(result,outcome->feedback(source,()->{
                delivered.set(true);
                if(outcome.failure()!=null)source.sendFailure(Component.literal("Kill command failed: "+outcome.failure().getMessage()));
                else if(outcome.count()==0)source.sendFailure(Component.translatable("argument.entity.notfound.entity"));
                else if(outcome.count()==1){Component name=outcome.targets().stream().filter(Target::eligible).findFirst().orElseThrow().name();source.sendSuccess(()->Component.translatable("commands.kill.success.single",name),true);}
                else source.sendSuccess(()->Component.translatable("commands.kill.success.multiple",outcome.count()),true);
                return outcome.count();
            }));
            ScarpetNativeWork.record(publication);return publication;
        });
        ScarpetNativeWork.aliasDependency(actual,observed);ScarpetNativeWork.trackNative(source.getServer(),observed);
        ScarpetNativeWork.recoverGuestValue(observed).thenCompose(next->next).whenComplete((count,failure)->{
            if(failure==null){completion.complete(count>0,count);actual.complete(count);}
            else if(delivered.get()){completion.complete(false,0);actual.completeExceptionally(failure);}
            else reportFailure.apply(failure).whenComplete((ignored,error)->{completion.complete(false,0);actual.completeExceptionally(failure);});
        });return 1;
    }
    private static CompletableFuture<Target> kill(Entity target,ServerLevel callerWorld,boolean creativeImmune){
        return TisCommandContinuations.phase(target,()->{
            if(creativeImmune&&target instanceof ServerPlayer player&&(player.isCreative()||player.isSpectator()))return new Target(false,null);
            Component name=target.getDisplayName().copy();
            target.kill(callerWorld);
            if(target instanceof net.minecraft.world.entity.LivingEntity living){var damage=ScarpetDamageContinuations.pendingResult(living);if(damage!=null)ScarpetNativeWork.record(damage);}
            return new Target(true,name);
        });
    }
    private static <T> CompletableFuture<T> feedback(CommandSourceStack source,Supplier<T> body){
        Entity owner=source.getEntity();return owner==null?TisCommandContinuations.owned(source.getLevel(),net.minecraft.core.BlockPos.containing(source.getPosition()),body):TisCommandContinuations.owned(owner,body);
    }
}
