package me.earthme.luminol.utils.thread;

import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;

import java.lang.invoke.VarHandle;

// -1 -> write locked
// -2 -> unreachable
// >= 0 read locked or not locked (0)

// this is a simple version of rw lock which removed the owner thread check
// only used for reference counting

// we only use this for preventing a player joining an unloading level or halt the level scheduler during some unfinished place logics
// such as async teleportation and some player join logics

// in this lock, the write area is designed for a single threaded env(the shutdown logic itself is single threaded)
// then the read area could be happened on multiple threads

// if the write lock is held, then it means that the scheduler is begun to shut down
// we could just do a simple check (try acquiring read) and if it's failed then it means the level is being unloaded so we could correct the player's level safely
// additionally, the blocking write lock is to ensure those async place tasks
public class SimpleReferenceRWLock {
    private int count;
    private boolean readReferencingBlocked = false;

    private static final VarHandle COUNT_HANDLE = ConcurrentUtil.getVarHandle(SimpleReferenceRWLock.class, "count", int.class);
    private static final VarHandle READ_REFERENCE_BLOCKED = ConcurrentUtil.getVarHandle(SimpleReferenceRWLock.class, "readReferencingBlocked", boolean.class);

    public void acquireUnreachable() {
        final int curr = (int) COUNT_HANDLE.getVolatile(this);

        // we require the unreachable state should be upgraded from write held state
        if (curr != -1) {
            throw new IllegalStateException("Not write held!");
        }

        if (!COUNT_HANDLE.compareAndSet(this, curr, -2))
            throw new IllegalStateException("race cond!");
    }

    public void blockReadingReferencing() {
        READ_REFERENCE_BLOCKED.setVolatile(this, true);
    }

    public boolean isReadReferencingBlocked() {
        return (boolean) READ_REFERENCE_BLOCKED.getVolatile(this);
    }

    public void releaseWrite() {
        long failures = 1L;

        for (; ; ) {
            final int curr = (int) COUNT_HANDLE.getVolatile(this);

            // unreachable acquired
            if (curr == -2) {
                throw new IllegalStateException("Unreachable set!");
            }

            // write acquired (we do not allow multiple write competitions)
            if (curr != -1) {
                throw new IllegalStateException("Not write held!");
            }

            if (COUNT_HANDLE.compareAndSet(this, curr, 0)) {
                break;
            }

            // max sleep for 2us
            Thread.yield();
            failures = ConcurrentUtil.linearLongBackoff(failures, 100L, 2000);
        }
    }

    public boolean acquireWrite(long deadline) {
        long failures = 1L;

        for (; ; ) {
            // deadline reached
            if (deadline <= System.nanoTime()) {
                return false;
            }

            final int curr = (int) COUNT_HANDLE.getVolatile(this);

            // unreachable acquired
            if (curr == -2) {
                return false;
            }

            // have remaining read operations
            if (curr > 0) {
                // max sleep for 2us
                Thread.yield();
                failures = ConcurrentUtil.linearLongBackoff(failures, 100L, 2000);
                continue;
            }

            // write acquired (we do not allow multiple write competitions)
            if (curr == -1) {
                return false;
            }

            if (COUNT_HANDLE.compareAndSet(this, curr, -1)) {
                break;
            }

            // max sleep for 2us
            Thread.yield();
            failures = ConcurrentUtil.linearLongBackoff(failures, 100L, 2000);
        }

        // gained
        return true;
    }

    public boolean acquireWrite() {
        long failures = 1L;

        for (; ; ) {
            final int curr = (int) COUNT_HANDLE.getVolatile(this);

            // unreachable acquired
            if (curr == -2) {
                return false;
            }

            // write acquired (we do not allow multiple write competitions)
            if (curr == -1) {
                return false;
            }

            // have remaining read operations
            if (curr > 0) {
                // max sleep for 2us
                Thread.yield();
                failures = ConcurrentUtil.linearLongBackoff(failures, 100L, 2000);
                continue;
            }

            if (COUNT_HANDLE.compareAndSet(this, curr, -1)) {
                break;
            }

            // max sleep for 2us
            Thread.yield();
            failures = ConcurrentUtil.linearLongBackoff(failures, 100L, 2000);
        }

        return true;
    }

    public boolean isReadLocked() {
        return (int) COUNT_HANDLE.getVolatile(this) > 0;
    }

    public void releaseRead() {
        long failures = 1L;

        for (; ; ) {
            final int curr = (int) COUNT_HANDLE.getVolatile(this);

            // unreachable acquired
            if (curr == -2) {
                throw new IllegalStateException("Unreachable set!");
            }

            // write acquired
            if (curr == -1) {
                throw new IllegalStateException("Unexpected state! Expected is read locked but got write locked!");
            }

            if (curr == 0) {
                throw new IllegalStateException("Not read held!");
            }

            if (COUNT_HANDLE.compareAndSet(this, curr, curr - 1)) {
                break;
            }

            // max sleep for 2us
            Thread.yield();
            failures = ConcurrentUtil.linearLongBackoff(failures, 100L, 2000);
        }
    }

    public boolean acquireRead() {
        long failures = 1L;

        for (; ; ) {
            final int curr = (int) COUNT_HANDLE.getVolatile(this);

            // read referencing blocked
            if ((boolean) READ_REFERENCE_BLOCKED.getVolatile(this)) {
                return false;
            }

            // unreachable acquired
            if (curr == -2) {
                return false;
            }

            // write acquired
            if (curr == -1) {
                return false;
            }

            if (COUNT_HANDLE.compareAndSet(this, curr, curr + 1)) {
                break;
            }

            // max sleep for 2us
            Thread.yield();
            failures = ConcurrentUtil.linearLongBackoff(failures, 100L, 2000);
        }

        return true;
    }

    public boolean isUnreachable() {
        return (int) COUNT_HANDLE.getVolatile(this) == -2;
    }
}
