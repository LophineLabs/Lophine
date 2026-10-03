// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import net.minecraft.server.MinecraftServer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * UUID admission metadata only; callbacks schedule their native actor without blocking a worker.
 */
public final class OrgPlayerFileLease {
    private static final Map<MinecraftServer, Table> TABLES = Collections.synchronizedMap(new WeakHashMap<>());

    private OrgPlayerFileLease() {
    }

    private static Table table(MinecraftServer server) {
        synchronized (TABLES) {
            return TABLES.computeIfAbsent(server, ignored -> new Table());
        }
    }

    public static boolean busy(MinecraftServer server, UUID player) {
        return table(server).busy(player);
    }

    public static CompletableFuture<Void> whenAvailable(MinecraftServer server, UUID player) {
        return table(server).available(player);
    }

    /**
     * Cancellation of the returned caller cannot end the actual login/file operation's lease.
     * A write with unknown durable outcome must keep its actual future pending until readback
     * resolves; exceptional terminal results mean that no unverified file custody remains.
     */
    public static <T> CompletableFuture<T> withLease(MinecraftServer server, UUID player, String purpose, Function<Lease, CompletableFuture<T>> operation) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(operation);
        var result = new CompletableFuture<T>();
        table(server).acquire(player, purpose).whenComplete((lease, admissionFailure) -> {
            if (admissionFailure != null) {
                result.completeExceptionally(admissionFailure);
                return;
            }
            CompletableFuture<T> actual;
            try {
                actual = Objects.requireNonNull(operation.apply(lease), "A player file actor must return its actual completion");
            } catch (Throwable failure) {
                lease.close();
                result.completeExceptionally(failure);
                return;
            }
            actual.whenComplete((value, failure) -> {
                // The supplied future is the real owned load/join/save/remove completion.
                // Public cancellation affects result only and never completes this future.
                lease.close();
                if (failure == null) result.complete(value);
                else result.completeExceptionally(failure);
            });
        });
        return result;
    }

    public static final class Lease {
        private final Table table;
        private final UUID player;
        private final String purpose;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Table table, UUID player, String purpose) {
            this.table = table;
            this.player = player;
            this.purpose = purpose;
        }

        public UUID player() {
            return player;
        }

        public String purpose() {
            return purpose;
        }

        public boolean active() {
            return !closed.get();
        }

        // Only withLease owns release. Native users cannot close an unfinished actual job.
        private void close() {
            if (closed.compareAndSet(false, true)) table.release(this);
        }
    }

    private record Request(String purpose, CompletableFuture<Lease> admission) {
    }

    private static final class Entry {
        Lease current;
        final ArrayDeque<Request> waiting = new ArrayDeque<>();
        final CompletableFuture<Void> available = new CompletableFuture<>();
    }

    private static final class Table {
        final Map<UUID, Entry> entries = new java.util.HashMap<>();

        synchronized boolean busy(UUID player) {
            return entries.containsKey(player);
        }

        synchronized CompletableFuture<Void> available(UUID player) {
            Entry entry = entries.get(player);
            return entry == null ? CompletableFuture.completedFuture(null) : entry.available.copy();
        }

        CompletableFuture<Lease> acquire(UUID player, String purpose) {
            var request = new Request(Objects.requireNonNull(purpose), new CompletableFuture<Lease>());
            Lease lease = null;
            synchronized (this) {
                Entry entry = entries.computeIfAbsent(player, ignored -> new Entry());
                if (entry.current == null) {
                    lease = new Lease(this, player, purpose);
                    entry.current = lease;
                } else entry.waiting.add(request);
            }
            if (lease != null) request.admission.complete(lease);
            return request.admission;
        }

        void release(Lease previous) {
            Request next = null;
            Lease lease = null;
            CompletableFuture<Void> available = null;
            synchronized (this) {
                Entry entry = entries.get(previous.player);
                if (entry == null || entry.current != previous)
                    throw new IllegalStateException("Player file lease identity changed");
                next = entry.waiting.poll();
                if (next == null) {
                    entries.remove(previous.player);
                    available = entry.available;
                } else {
                    lease = new Lease(this, previous.player, next.purpose);
                    entry.current = lease;
                }
            }
            // Never invoke a native callback or another owner while the table monitor is held.
            if (next != null) next.admission.complete(lease);
            else available.complete(null);
        }
    }
}
