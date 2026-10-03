/* SPDX-License-Identifier: LGPL-3.0-or-later
 * Adapted from Carpet TIS Addition 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
 */
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.leavesmc.leaves.util.UpdateSuppressionException;

import java.util.Locale;

public final class TisSimulationRules {
    private TisSimulationRules() {
    }

    public static void suppress(final Level level, final BlockPos pos) {
        String mode = GeneralCompatConfig.updateSuppressionSimulator.toLowerCase(Locale.ROOT);
        if (mode.equals("false")) return;
        Throwable failure = switch (mode) {
            case "true", "stackoverflowerror" -> new StackOverflowError("TISCM UpdateSuppressionSimulator");
            case "outofmemoryerror" -> new OutOfMemoryError("TISCM UpdateSuppressionSimulator");
            case "classcastexception" -> new ClassCastException("TISCM UpdateSuppressionSimulator");
            case "illegalargumentexception" -> new IllegalArgumentException("TISCM UpdateSuppressionSimulator");
            case "illegalstateexception" -> new IllegalStateException("TISCM UpdateSuppressionSimulator");
            default -> throw new IllegalArgumentException("Unsupported update suppression simulator mode: " + mode);
        };
        throwFailure(level, pos, failure);
    }

    public static void sound(final Level level, final BlockPos pos) {
        throwFailure(level, pos, new IllegalArgumentException("TISCM SoundSuppressionSimulator"));
    }

    private static void throwFailure(final Level level, final BlockPos pos, final Throwable failure) {
        if (GeneralCompatConfig.mergedUpdateSuppressionCrashEnabled()) {
            throw new UpdateSuppressionException(pos, level, null, null, failure);
        }
        if (failure instanceof Error error) throw error;
        throw (RuntimeException) failure;
    }
}
