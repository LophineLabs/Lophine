package me.earthme.luminol.utils.thread;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import ca.spottedleaf.concurrentutil.scheduler.SchedulableTick;
import ca.spottedleaf.concurrentutil.scheduler.SchedulableTickHack;
import ca.spottedleaf.concurrentutil.scheduler.Scheduler;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class LevelAwareRegionScheduler extends Scheduler {
    private final Scheduler parent;
    private final AtomicBoolean halted = new AtomicBoolean(false);

    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final MultiThreadedQueue<Runnable> haltedCallbacks = new MultiThreadedQueue<>();
    private final Set<WrappedTask> managedTasks = new CopyOnWriteArraySet<>();

    private final AtomicBoolean fullyExited = new AtomicBoolean(false);

    public LevelAwareRegionScheduler(Scheduler parent) {
        this.parent = parent;
    }

    private void allTaskExitedCallback() {
        try {
            Runnable callback;
            while ((callback = this.haltedCallbacks.pollOrBlockAdds()) != null) {
                callback.run();
            }
        } finally {
            this.fullyExited.set(true);
        }
    }

    public boolean isFullyExited() {
        return this.fullyExited.get();
    }

    public boolean addHaltCallback(Runnable task) {
        return this.haltedCallbacks.offer(task);
    }

    public boolean isHaltSignalled() {
        return this.halted.get();
    }

    public boolean isAllHalted() {
        return this.haltedCallbacks.isAddBlocked();
    }

    // the parent is already shut down and no tasks will be executed, so just directly retire all callbacks
    public void onParentHalted() {
        this.halted.set(true);

        this.allTaskExitedCallback();
    }

    private final class WrappedTask extends SchedulableTick {
        private final SchedulableTick handle;
        private final AtomicBoolean thisHalted = new AtomicBoolean(false);

        private WrappedTask(SchedulableTick handle) {
            this.handle = handle;

            // resync deadline
            SchedulableTickHack.setScheduledStart(this, SchedulableTickHack.getScheduledStart(this.handle));
        }

        public void register() {
            LevelAwareRegionScheduler.this.activeCount.getAndIncrement();
            LevelAwareRegionScheduler.this.managedTasks.add(this);
        }

        private void handleParentHalted() {
            if (this.thisHalted.compareAndSet(false, true)) {
                final int remaining = LevelAwareRegionScheduler.this.activeCount.decrementAndGet();

                LevelAwareRegionScheduler.this.managedTasks.remove(this);

                if (remaining == 0) {
                    LevelAwareRegionScheduler.this.allTaskExitedCallback();
                }
            }
        }

        @Override
        public boolean runTick() {
            if (LevelAwareRegionScheduler.this.halted.get()) {
                this.handleParentHalted();

                return false;
            }

            final boolean notCancelled = this.handle.runTick();

            if (!notCancelled && this.thisHalted.compareAndSet(false, true)) {
                LevelAwareRegionScheduler.this.activeCount.decrementAndGet();
                LevelAwareRegionScheduler.this.managedTasks.remove(this);
            }

            // resync deadline
            SchedulableTickHack.setScheduledStart(this, SchedulableTickHack.getScheduledStart(this.handle));
            return notCancelled;
        }

        @Override
        public boolean hasTasks() {
            return this.handle.hasTasks() || LevelAwareRegionScheduler.this.halted.get();
        }

        @Override
        public boolean runTasks(BooleanSupplier canContinue) {
            if (LevelAwareRegionScheduler.this.halted.get()) {
                this.handleParentHalted();

                return false;
            }

            final boolean notCancelled = this.handle.runTasks(canContinue);

            if (!notCancelled && this.thisHalted.compareAndSet(false, true)) {
                LevelAwareRegionScheduler.this.activeCount.decrementAndGet();
                LevelAwareRegionScheduler.this.managedTasks.remove(this);
            }

            return notCancelled;
        }
    }

    @Override
    public Thread[] getAliveThreads() {
        return this.parent.getAliveThreads();
    }

    @Override
    public Thread[] getCoreThreads() {
        return this.parent.getCoreThreads();
    }

    @Override
    public void halt() {
        if (!this.halted.compareAndSet(false, true)) {
            return;
        }

        // the parent schedule has its ability to handle notify when task is already canceled
        // so just do it directly
        for (WrappedTask task : this.managedTasks) {
            this.parent.notifyTasks(task);
        }

        if (this.activeCount.get() == 0) {
            this.allTaskExitedCallback();
        }
    }

    @Override
    public boolean join(long msToWait) {
        return false; // TODO
    }

    @Override
    public boolean joinInterruptable(long msToWait) {
        return false; // TODO
    }

    @Override
    public void schedule(SchedulableTick tick) {
        if (this.halted.get()) {
            return;
        }

        final WrappedTask toSchedule = new WrappedTask(tick);

        if (!SchedulableTickHack.setState(tick, toSchedule)) {
            throw new IllegalStateException("Already scheduled!");
        }

        toSchedule.register();

        this.parent.schedule(toSchedule);
    }

    @Override
    public void notifyTasks(SchedulableTick tick) {
        if (this.halted.get()) {
            return;
        }

        final WrappedTask wrapped = SchedulableTickHack.getState(tick);

        if (wrapped != null) {
            this.parent.notifyTasks(wrapped);
        }
    }

    @Override
    public boolean cancel(SchedulableTick tick) {
        final WrappedTask wrapped = SchedulableTickHack.getState(tick);

        if (wrapped != null) {
            final boolean cancelled = this.parent.cancel(wrapped);

            if (cancelled && wrapped.thisHalted.compareAndSet(false, true)) {
                final int remaining = this.activeCount.decrementAndGet();
                this.managedTasks.remove(wrapped);

                if (remaining == 0 && this.halted.get()) {
                    this.allTaskExitedCallback();
                }
            }

            return cancelled;
        }

        return false;
    }
}
