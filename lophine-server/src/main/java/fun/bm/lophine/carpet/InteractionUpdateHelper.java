package fun.bm.lophine.carpet;

import java.util.function.Supplier;

public final class InteractionUpdateHelper {
    private static final ThreadLocal<Integer> SUPPRESSED_DEPTH = ThreadLocal.withInitial(() -> 0);

    public static boolean shouldSkipUpdates() {
        return SUPPRESSED_DEPTH.get() > 0;
    }

    public static void runWithSuppressedUpdates(Runnable action) {
        push();
        try {
            action.run();
        } finally {
            pop();
        }
    }

    public static <T> T supplyWithSuppressedUpdates(Supplier<T> action) {
        push();
        try {
            return action.get();
        } finally {
            pop();
        }
    }

    private static void push() {
        SUPPRESSED_DEPTH.set(SUPPRESSED_DEPTH.get() + 1);
    }

    private static void pop() {
        int depth = SUPPRESSED_DEPTH.get();
        if (depth <= 1) {
            SUPPRESSED_DEPTH.remove();
        } else {
            SUPPRESSED_DEPTH.set(depth - 1);
        }
    }
}
