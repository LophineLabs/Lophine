package fun.bm.lophine.api.carpet;

import java.util.Objects;

/**
 * Result of a rule mutation. A saved restart-only value does not change the live
 * value in {@link #rule()} and does not emit a live rule-change event.
 *
 * @param rule           live snapshot following the operation
 * @param requestedValue validated input to the operation
 * @param status         outcome
 * @param persisted      whether the request saved a startup override
 */
public record CarpetRuleUpdate(CarpetRule rule, String requestedValue, Status status, boolean persisted) {
    /**
     * Creates a non-null rule update result.
     *
     * @param rule           live snapshot
     * @param requestedValue validated input
     * @param status         update status
     * @param persisted      whether saved
     */
    public CarpetRuleUpdate {
        Objects.requireNonNull(rule);
        Objects.requireNonNull(requestedValue);
        Objects.requireNonNull(status);
    }

    /** Runtime application status. */
    public enum Status {
        /** The live value changed. */
        UPDATED,
        /** The live value already matched. A requested save still occurs. */
        UNCHANGED,
        /** Saved successfully, but the live value requires a restart to change. */
        SAVED_FOR_RESTART
    }
}
