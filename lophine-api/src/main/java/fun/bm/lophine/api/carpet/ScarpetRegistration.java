package fun.bm.lophine.api.carpet;

/** A thread-safe plugin-owned registration. Closing it is idempotent and non-blocking. */
public interface ScarpetRegistration extends AutoCloseable {
    /**
     * Returns the name callable from Scarpet.
     *
     * @return the name callable from Scarpet
     */
    String functionName();

    /**
     * Returns whether this registration still accepts invocations.
     *
     * @return whether this registration still accepts invocations
     */
    boolean isRegistered();

    /** Revokes new calls and fails pending results; already executing plugin work may finish. */
    @Override
    void close();
}
