// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The original enchant loop follows each actual target and its native children in input order.
 */
public final class TisEnchantCommand {
    private TisEnchantCommand() {
    }

    @FunctionalInterface
    public interface TargetBody {
        boolean apply(Entity target) throws CommandSyntaxException;
    }

    @FunctionalInterface
    public interface FeedbackBody {
        Component apply(List<Entity> changed) throws CommandSyntaxException;
    }

    public static int execute(CommandSourceStack source, Collection<? extends Entity> targets, TargetBody targetBody, FeedbackBody feedbackBody) {
        List<? extends Entity> selected = List.copyOf(targets);
        var completion = CarpetAsyncCommandResults.defer(source);
        var observed = ScarpetNativeWork.observeNative(source.getEntity(), () -> {
            CompletableFuture<List<Entity>> chain = CompletableFuture.completedFuture(new ArrayList<>());
            for (Entity target : selected) {
                chain = TisCommandContinuations.then(chain, changed -> TisCommandContinuations.entity(source, target,
                        () -> TisCommandContinuations.phase(target, () -> {
                            try {
                                if (targetBody.apply(target)) changed.add(target);
                                return changed;
                            } catch (CommandSyntaxException failure) {
                                throw new CompletionException(failure);
                            }
                        })));
            }
            var message = TisCommandContinuations.then(chain, changed -> {
                java.util.function.Supplier<Component> body = () -> {
                    try {
                        return feedbackBody.apply(changed);
                    } catch (CommandSyntaxException failure) {
                        throw new CompletionException(failure);
                    }
                };
                var prepared = changed.size() == 1 ? TisCommandContinuations.owned(changed.getFirst(), body)
                        : TisCommandContinuations.feedback(source, body);
                return TisCommandContinuations.then(prepared, component -> TisCommandContinuations.feedback(source, () -> {
                    source.sendSuccess(() -> component, true);
                    return changed.size();
                }));
            });
            ScarpetNativeWork.record(message);
            var outcome = message.handle((count, failure) -> new Outcome(count == null ? 0 : count, unwrap(failure)));
            var delivered = TisCommandContinuations.then(outcome, value -> {
                if (value.failure() instanceof CommandSyntaxException syntax) {
                    return TisCommandContinuations.feedback(source, () -> {
                        source.sendFailure((Component) syntax.getRawMessage());
                        return value;
                    });
                }
                return CompletableFuture.completedFuture(value);
            });
            var committed = delivered.whenComplete(ScarpetRuntime.captureNativeConsumer((value, failure) -> {
                if (failure != null) completion.complete(false, 0);
                else completion.complete(value.failure() == null, value.failure() == null ? value.count() : 0);
            }));
            ScarpetNativeWork.record(committed);
            return null;
        });
        ScarpetNativeWork.trackNative(source.getServer(), observed);
        return selected.size();
    }

    private record Outcome(int count, Throwable failure) {
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
