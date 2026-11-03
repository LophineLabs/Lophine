package fun.bm.lophine.carpet.api;

import fun.bm.lophine.api.carpet.ScarpetFunction;
import fun.bm.lophine.api.carpet.ScarpetRegistration;
import fun.bm.lophine.api.carpet.ScarpetValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;

/** Registry locks protect admission only; no plugin callback is invoked while holding them. */
final class ScarpetExtensions {
    private final Map<String, Entry> entries = new HashMap<>();
    private final Set<Plugin> disabling = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private boolean closed;

    synchronized void checkOwner(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        if (this.closed || this.disabling.contains(owner) || !owner.isEnabled()) {
            throw new IllegalStateException("Plugin is disabled or Carpet API is closing");
        }
    }

    synchronized Entry register(Plugin owner, String name, ScarpetFunction function) {
        checkOwner(owner);
        Objects.requireNonNull(function, "function");
        if (!name.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid Scarpet function identifier: " + name);
        String fullName = "plugin_" + owner.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_") + "__" + name;
        if (this.entries.containsKey(fullName)) throw new IllegalArgumentException("Scarpet function already registered: " + fullName);
        Entry entry = new Entry(owner, fullName, function);
        this.entries.put(fullName, entry);
        return entry;
    }

    synchronized List<Entry> snapshot() {
        return List.copyOf(this.entries.values());
    }

    void unregister(Plugin owner, boolean disable) {
        List<Entry> removed;
        synchronized (this) {
            if (disable) this.disabling.add(owner);
            removed = this.entries.values().stream().filter(entry -> entry.ownedBy(owner)).toList();
            removed.forEach(entry -> this.entries.remove(entry.functionName()));
        }
        removed.forEach(Entry::revoke);
    }

    synchronized void enable(Plugin owner) {
        this.disabling.remove(owner);
    }

    void close() {
        List<Entry> removed;
        synchronized (this) {
            this.closed = true;
            removed = new ArrayList<>(this.entries.values());
            this.entries.clear();
            this.disabling.clear();
        }
        removed.forEach(Entry::revoke);
    }

    final class Entry implements ScarpetRegistration {
        private Plugin owner;
        private ScarpetFunction function;
        private final String name;
        private final Set<CompletableFuture<ScarpetValue>> pending = new HashSet<>();

        private Entry(Plugin owner, String name, ScarpetFunction function) {
            this.owner = owner;
            this.name = name;
            this.function = function;
        }

        synchronized boolean ownedBy(Plugin plugin) { return this.owner == plugin; }

        CompletableFuture<ScarpetValue> invoke(List<ScarpetValue> arguments) {
            ScarpetFunction callback;
            var result = new CompletableFuture<ScarpetValue>();
            synchronized (this) {
                if (this.function == null || !this.owner.isEnabled()) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Scarpet extension is unregistered: " + this.name));
                }
                callback = this.function;
                this.pending.add(result);
            }
            result.whenComplete((value, failure) -> {
                synchronized (this) { this.pending.remove(result); }
            });
            try {
                Objects.requireNonNull(callback.apply(List.copyOf(arguments)), "Extension returned null stage")
                    .whenComplete((value, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                        else if (value == null) result.completeExceptionally(new NullPointerException("Extension returned null value"));
                        else result.complete(value);
                    });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
            return result;
        }

        @Override
        public String functionName() { return this.name; }

        @Override
        public synchronized boolean isRegistered() { return this.function != null && this.owner.isEnabled(); }

        @Override
        public void close() {
            synchronized (ScarpetExtensions.this) { ScarpetExtensions.this.entries.remove(this.name, this); }
            revoke();
        }

        private void revoke() {
            List<CompletableFuture<ScarpetValue>> rejected;
            synchronized (this) {
                this.function = null;
                this.owner = null;
                rejected = List.copyOf(this.pending);
                this.pending.clear();
            }
            rejected.forEach(future -> future.completeExceptionally(new IllegalStateException("Scarpet extension is unregistered: " + this.name)));
        }
    }
}
