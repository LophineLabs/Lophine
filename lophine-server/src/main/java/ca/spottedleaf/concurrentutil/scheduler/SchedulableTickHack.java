package ca.spottedleaf.concurrentutil.scheduler;

import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

public class SchedulableTickHack {
    public static boolean setState(@NonNull SchedulableTick tick, Object state) {
        return tick.setState(state);
    }

    @Contract(pure = true)
    public static <T> T getState(@NonNull SchedulableTick tick) {
        return (T) tick.state;
    }

    @Contract(pure = true)
    public static long getScheduledStart(@NonNull SchedulableTick tick) {
        return tick.getScheduledStart();
    }

    public static void setScheduledStart(@NonNull SchedulableTick tick, long newValue) {
        tick.setScheduledStart(newValue);
    }
}
