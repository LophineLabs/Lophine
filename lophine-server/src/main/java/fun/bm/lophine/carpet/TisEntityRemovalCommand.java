// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeEntityRemoval;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

/**
 * Reports the original removeentity count after the real entity queue and source feedback children.
 */
public final class TisEntityRemovalCommand {
    private TisEntityRemovalCommand() {
    }

    public static int execute(CommandSourceStack source, Collection<? extends Entity> targets) {
        var completion = CarpetAsyncCommandResults.defer(source);
        var observed = ScarpetNativeWork.observeNative(source.getEntity(), () -> {
            var removed = ScarpetNativeEntityRemoval.removeAll(targets);
            var delivered = TisCommandContinuations.then(removed, count -> TisCommandContinuations.feedback(source, () -> {
                source.sendSuccess(() -> TisTranslations.message(source, "command.removeentity.success", count), true);
                return count;
            }));
            var committed = delivered.whenComplete(ScarpetRuntime.captureNativeConsumer((count, failure) ->
                    completion.complete(failure == null, failure == null ? count : 0)));
            ScarpetNativeWork.record(committed);
            return null;
        });
        ScarpetNativeWork.trackNative(source.getServer(), observed);
        return targets.size();
    }
}
