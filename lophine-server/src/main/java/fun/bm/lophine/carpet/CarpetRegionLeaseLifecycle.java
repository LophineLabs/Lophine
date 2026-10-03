package fun.bm.lophine.carpet;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual lease ticket lifetime: acquisition, actor exit and every returned asynchronous tail. */
final class CarpetRegionLeaseLifecycle {
    private final Runnable release;
    private final Set<CompletionStage<?>> observed = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean acquired, started, actorFinished, closed, released;
    private int tails;

    CarpetRegionLeaseLifecycle(Runnable release) { this.release = release; }

    synchronized boolean isClosed() { return closed; }
    synchronized boolean hasStarted() { return started; }

    synchronized boolean appendTicket(Runnable metadata) {
        if (closed) return false;
        metadata.run();
        return true;
    }

    synchronized boolean beginActor() {
        if (closed || started) return false;
        started = true;
        return true;
    }

    void acquired() {
        Runnable cleanup;
        synchronized (this) { acquired = true; cleanup = takeRelease(); }
        if (cleanup != null) cleanup.run();
    }

    void actorFinished() {
        Runnable cleanup;
        synchronized (this) { actorFinished = true; closed = true; cleanup = takeRelease(); }
        if (cleanup != null) cleanup.run();
    }

    boolean cancelWaiting() {
        Runnable cleanup;
        synchronized (this) {
            if (started || closed) return false;
            closed = true;
            cleanup = takeRelease();
        }
        if (cleanup != null) cleanup.run();
        return true;
    }

    void close() {
        Runnable cleanup;
        synchronized (this) { closed = true; cleanup = takeRelease(); }
        if (cleanup != null) cleanup.run();
    }

    void follow(Object value) {
        if (!(value instanceof CompletionStage<?> stage)) return;
        synchronized (this) {
            if (!observed.add(stage)) return;
            if (released) throw new IllegalStateException("An asynchronous tail was added after lease release");
            tails++;
        }
        AtomicBoolean ended = new AtomicBoolean();
        try {
            stage.whenComplete((nested, failure) -> {
                if (!ended.compareAndSet(false, true)) return;
                try { if (failure == null) follow(nested); }
                finally { tailFinished(); }
            });
        } catch (Throwable failure) {
            if (ended.compareAndSet(false, true)) tailFinished();
            throw failure;
        }
    }

    private void tailFinished() {
        Runnable cleanup;
        synchronized (this) {
            if (--tails < 0) throw new IllegalStateException("Lease tail completed twice");
            cleanup = takeRelease();
        }
        if (cleanup != null) cleanup.run();
    }

    /** Called only under this metadata monitor; actual ticket work starts after it is released. */
    private Runnable takeRelease() {
        if (!closed || !acquired || started && !actorFinished || tails != 0 || released) return null;
        released = true;
        observed.clear();
        return release;
    }
}
