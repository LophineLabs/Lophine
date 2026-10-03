// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One short metadata transaction reserves and publishes both fake-player identity keys.
 */
public final class CarpetBotRegistrations<T> {
    private final Map<UUID, T> uuids = new ConcurrentHashMap<>();
    private final Map<String, T> names = new ConcurrentHashMap<>();
    private final Set<UUID> pendingUuids = new HashSet<>();
    private final Set<String> pendingNames = new HashSet<>();

    public Map<UUID, T> byUuid() {
        return java.util.Collections.unmodifiableMap(uuids);
    }

    public Map<String, T> byName() {
        return java.util.Collections.unmodifiableMap(names);
    }

    public synchronized Reservation reserve(UUID uuid, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (uuids.containsKey(uuid) || names.containsKey(key) || pendingUuids.contains(uuid) || pendingNames.contains(key))
            throw new IllegalStateException("Fake player identity is already logged in or logging in");
        pendingUuids.add(uuid);
        pendingNames.add(key);
        return new Reservation(uuid, key);
    }

    public synchronized void remove(UUID uuid, String name, T value) {
        if (uuids.get(uuid) == value) uuids.remove(uuid);
        String key = name.toLowerCase(Locale.ROOT);
        if (names.get(key) == value) names.remove(key);
    }

    public final class Reservation implements AutoCloseable {
        private final UUID uuid;
        private final String name;
        private boolean closed;

        private Reservation(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public void publish(T value) {
            synchronized (CarpetBotRegistrations.this) {
                if (closed) throw new IllegalStateException("Fake player reservation is closed");
                uuids.put(uuid, value);
                names.put(name, value);
                close();
            }
        }

        @Override
        public void close() {
            synchronized (CarpetBotRegistrations.this) {
                if (closed) return;
                closed = true;
                pendingUuids.remove(uuid);
                pendingNames.remove(name);
            }
        }
    }
}
