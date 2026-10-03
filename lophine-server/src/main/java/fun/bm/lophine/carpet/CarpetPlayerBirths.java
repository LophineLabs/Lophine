// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import net.minecraft.server.MinecraftServer;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Admission metadata for actual first-read / full-join jobs; no native work runs under a monitor.
 */
public final class CarpetPlayerBirths {
    private static final Map<MinecraftServer, Table> TABLES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<net.minecraft.server.level.ServerPlayer, Set<CompletableFuture<?>>> PLAYERS = Collections.synchronizedMap(new WeakHashMap<>());

    private CarpetPlayerBirths() {
    }

    private static Table table(MinecraftServer server) {
        synchronized (TABLES) {
            return TABLES.computeIfAbsent(server, ignored -> new Table());
        }
    }

    public static void track(MinecraftServer server, CompletableFuture<?> actual, Runnable abandonUnplaced) {
        Table table = table(server);
        Entry entry = new Entry(actual, abandonUnplaced);
        boolean draining;
        synchronized (table) {
            if (table.jobs.isEmpty()) table.idle = new CompletableFuture<>();
            table.jobs.add(entry);
            draining = table.draining;
        }
        actual.whenComplete((ignored, failure) -> {
            CompletableFuture<Void> idle = null;
            synchronized (table) {
                table.jobs.remove(entry);
                if (table.jobs.isEmpty()) idle = table.idle;
            }
            if (idle != null) idle.complete(null);
        });
        if (draining) abandonUnplaced.run();
    }

    /**
     * Unplaced logins terminate; an admitted read or native placement still drains its actual phases.
     */
    public static void beginDrain(MinecraftServer server) {
        Table table = table(server);
        ArrayList<Entry> old;
        synchronized (table) {
            table.draining = true;
            old = new ArrayList<>(table.jobs);
        }
        for (Entry entry : old) entry.abandonUnplaced.run();
    }

    public static CompletableFuture<Void> whenIdle(MinecraftServer server) {
        Table table = table(server);
        synchronized (table) {
            return table.idle.copy();
        }
    }

    /**
     * UUID reservation already admitted this particular new player; this is metadata only.
     */
    public static void admitPlayer(net.minecraft.server.level.ServerPlayer player, CompletableFuture<?> actual) {
        synchronized (PLAYERS) {
            PLAYERS.computeIfAbsent(player, ignored -> new HashSet<>()).add(actual);
        }
        carpet.script.external.ScarpetPlayerInventoryGate.admitBirth(player, actual);
        actual.whenComplete((ignored, failure) -> {
            synchronized (PLAYERS) {
                var pending = PLAYERS.get(player);
                if (pending != null) {
                    pending.remove(actual);
                    if (pending.isEmpty()) PLAYERS.remove(player);
                }
            }
        });
    }

    public static boolean playerPending(net.minecraft.server.level.ServerPlayer player) {
        synchronized (PLAYERS) {
            var pending = PLAYERS.get(player);
            return pending != null && pending.stream().anyMatch(actual -> !actual.isDone());
        }
    }

    public static CompletableFuture<Void> playerCompletion(net.minecraft.server.level.ServerPlayer player) {
        synchronized (PLAYERS) {
            var pending = PLAYERS.get(player);
            return pending == null ? CompletableFuture.completedFuture(null) : CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).handle((ignored, failure) -> null);
        }
    }

    private record Entry(CompletableFuture<?> actual, Runnable abandonUnplaced) {
    }

    private static final class Table {
        final Set<Entry> jobs = new HashSet<>();
        CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);
        boolean draining;
    }
}
