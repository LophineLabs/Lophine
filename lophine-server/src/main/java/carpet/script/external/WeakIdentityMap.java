// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Entity.equals/hashCode use a reusable mutable network id; actor state must use object identity. */
public final class WeakIdentityMap<K, V> {
    private static final class Key<K> extends WeakReference<K> {
        private final int hash;
        Key(K referent, ReferenceQueue<K> queue) { super(referent, queue); hash = System.identityHashCode(referent); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key<?> key)) return false;
            Object referent = get(); return referent != null && referent == key.get();
        }
    }
    private final ReferenceQueue<K> queue = new ReferenceQueue<>();
    private final ConcurrentHashMap<Key<K>, V> values = new ConcurrentHashMap<>();
    private void expunge() { for (java.lang.ref.Reference<? extends K> key; (key = queue.poll()) != null;) values.remove(key); }
    public V get(K key) { expunge(); return values.get(new Key<>(key, null)); }
    public V put(K key, V value) { expunge(); return values.put(new Key<>(key, queue), value); }
    public V computeIfAbsent(K key, Function<? super K, ? extends V> create) { expunge(); return values.computeIfAbsent(new Key<>(key, queue), ignored -> create.apply(key)); }
    public V compute(K key, java.util.function.BiFunction<? super K, ? super V, ? extends V> update) { expunge(); return values.compute(new Key<>(key, queue), (ignored, previous) -> update.apply(key, previous)); }
    public boolean remove(K key, V value) { expunge(); return values.remove(new Key<>(key, null), value); }
}
