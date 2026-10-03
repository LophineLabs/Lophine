// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetPlayerInventoryGate;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Owns UUID file admission from the first native read through all actual join tails.
 */
public final class CarpetPlayerLoginLease {
    private final MinecraftServer server;
    private final UUID player;
    private final CompletableFuture<Void> nativeLifetime = new CompletableFuture<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private boolean started, admitted, closed, terminal;
    private int phases;
    private Throwable failure;

    public CarpetPlayerLoginLease(MinecraftServer server, UUID player) {
        this.server = Objects.requireNonNull(server);
        this.player = Objects.requireNonNull(player);
    }

    /**
     * The supplied startup future is private native work, never a caller's cancellable view.
     */
    public void start(Supplier<CompletableFuture<Void>> startup) {
        synchronized (this) {
            if (started) throw new IllegalStateException("Login was already started");
            started = true;
        }
        var captured = ScarpetRuntime.captureNativeContinuation(startup);
        ScarpetNativeWork.record(completion);
        ScarpetNativeWork.trackNative(server, completion);
        CarpetPlayerBirths.track(server, completion, this::close);
        var leased = OrgPlayerFileLease.withLease(server, player, "native login", lease -> {
            boolean abandoned;
            synchronized (this) {
                abandoned = closed || ScarpetNativeWork.isDraining(server);
                if (abandoned) closed = terminal = true;
                else {
                    admitted = true;
                    phases++;
                }
            }
            if (abandoned) {
                finishIfReady();
                return nativeLifetime;
            }
            CompletableFuture<Void> actual;
            try {
                actual = Objects.requireNonNull(captured.get());
            } catch (Throwable problem) {
                actual = CompletableFuture.failedFuture(problem);
            }
            actual.whenComplete((ignored, problem) -> phaseFinished(problem, problem != null));
            return nativeLifetime;
        });
        leased.whenComplete((ignored, problem) -> {
            if (problem == null) completion.complete(null);
            else completion.completeExceptionally(problem);
        });
    }

    public synchronized boolean closed() {
        return closed;
    }

    public CompletableFuture<Void> completion() {
        var caller = completion.copy();
        ScarpetNativeWork.aliasDependency(caller, completion);
        return caller;
    }

    /**
     * Native placement still returns its immediate player; the file remains leased for its children.
     */
    public <T> T spawn(ServerPlayer owner, Supplier<T> operation) {
        synchronized (this) {
            if (!admitted || closed || terminal) throw new CancellationException("Login is no longer admitted");
            phases++;
        }
        var immediate = new AtomicReference<T>();
        var thrown = new AtomicReference<Throwable>();
        var actual = ScarpetNativeWork.observeNative(owner, () -> {
            try {
                var lifetime = ScarpetNativeWork.completionOf(ScarpetNativeWork.capture());
                ScarpetNativeWork.linkDependency(completion, lifetime);
                ScarpetNativeWork.aliasDependency(completion, lifetime);
                CarpetPlayerBirths.admitPlayer(owner, completion);
                ScarpetPlayerInventoryGate.admitBirth(owner, ScarpetNativeWork.completionOf(ScarpetNativeWork.capture()));
                try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(owner)) {
                    T value = operation.get();
                    immediate.set(value);
                    return value;
                }
            } catch (RuntimeException | Error problem) {
                thrown.set(problem);
                throw problem;
            }
        });
        ScarpetNativeWork.aliasDependency(completion, actual);
        actual.whenComplete((ignored, problem) -> phaseFinished(problem, true));
        if (thrown.get() instanceof RuntimeException problem) throw problem;
        if (thrown.get() instanceof Error problem) throw problem;
        return immediate.get();
    }

    /**
     * Disconnect is terminal only after an already admitted read or placement has finished.
     */
    public void close() {
        synchronized (this) {
            closed = terminal = true;
        }
        finishIfReady();
    }

    private void phaseFinished(Throwable problem, boolean end) {
        synchronized (this) {
            if (problem != null && failure == null) failure = problem;
            if (end) terminal = true;
            if (--phases < 0) throw new IllegalStateException("Login phase completed twice");
        }
        finishIfReady();
    }

    private void finishIfReady() {
        Throwable problem;
        synchronized (this) {
            if (!terminal || phases != 0) return;
            problem = failure;
        }
        if (problem == null) nativeLifetime.complete(null);
        else nativeLifetime.completeExceptionally(problem);
    }
}
