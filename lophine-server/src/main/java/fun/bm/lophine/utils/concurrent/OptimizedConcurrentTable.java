package fun.bm.lophine.utils.concurrent;

import java.util.*;
import java.util.function.Predicate;

public class OptimizedConcurrentTable<X, Y, Z> extends ConcurrentTable<X, Y, Z> {
    private final Map<X, Map<Y, Set<Z>>> xyIndex = new HashMap<>();
    private final Map<Y, Map<Z, Set<X>>> yzIndex = new HashMap<>();
    private final Map<Z, Map<X, Set<Y>>> zxIndex = new HashMap<>();

    public OptimizedConcurrentTable() {
        super();
    }

    public OptimizedConcurrentTable(boolean flagX, boolean flagY, boolean flagZ) {
        super(flagX, flagY, flagZ);
    }

    @Override
    public void put(X x, Y y, Z z) {
        requireNonNullEntry(x, y, z);
        lock.writeLock().lock();
        try {
            enforceConstraints(x, y, z);
            addEntry(x, y, z);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(X x, Y y, Z z) {
        lock.writeLock().lock();
        try {
            removeEntry(x, y, z);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeAll(Predicate<TableEntry<X, Y, Z>> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        lock.writeLock().lock();
        try {
            data.removeIf(entry -> {
                if (!predicate.test(entry)) {
                    return false;
                }
                removeFromIndex(xyIndex, entry.x(), entry.y(), entry.z());
                removeFromIndex(yzIndex, entry.y(), entry.z(), entry.x());
                removeFromIndex(zxIndex, entry.z(), entry.x(), entry.y());
                return true;
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean putIfAbsent(X x, Y y, Z z) {
        requireNonNullEntry(x, y, z);
        lock.writeLock().lock();
        try {
            if (data.contains(new TableEntry<>(x, y, z))) {
                return false;
            }
            enforceConstraints(x, y, z);
            addEntry(x, y, z);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<Z> getZ(X x, Y y) {
        if (x == null || y == null) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            return copyOf(findZ(x, y));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Y> getY(X x, Z z) {
        if (x == null || z == null) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            return copyOf(findY(x, z));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<X> getX(Y y, Z z) {
        if (y == null || z == null) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            return copyOf(findX(y, z));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<X, Y> getXY(Z z) {
        if (z == null) {
            return new HashMap<>();
        }
        lock.readLock().lock();
        try {
            return pairs(zxIndex.get(z));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<Y, Z> getYZ(X x) {
        if (x == null) {
            return new HashMap<>();
        }
        lock.readLock().lock();
        try {
            return pairs(xyIndex.get(x));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<X, Z> getXZ(Y y) {
        if (y == null) {
            return new HashMap<>();
        }
        lock.readLock().lock();
        try {
            Map<Z, Set<X>> byZ = yzIndex.get(y);
            Map<X, Z> result = new HashMap<>();
            if (byZ == null) {
                return result;
            }
            for (Map.Entry<Z, Set<X>> entry : byZ.entrySet()) {
                Set<X> values = entry.getValue();
                if (values == null || values.isEmpty()) {
                    continue;
                }
                for (X value : values) {
                    result.put(value, entry.getKey());
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<X> getAllX() {
        lock.readLock().lock();
        try {
            return List.copyOf(xyIndex.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Y> getAllY() {
        lock.readLock().lock();
        try {
            return List.copyOf(yzIndex.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Z> getAllZ() {
        lock.readLock().lock();
        try {
            return List.copyOf(zxIndex.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void clearXY(Z z) {
        lock.writeLock().lock();
        try {
            Map<X, Set<Y>> byX = zxIndex.remove(z);
            if (byX == null) {
                return;
            }
            for (Map.Entry<X, Set<Y>> entry : byX.entrySet()) {
                X x = entry.getKey();
                for (Y y : entry.getValue()) {
                    data.remove(new TableEntry<>(x, y, z));
                    removeFromIndex(xyIndex, x, y, z);
                    removeFromIndex(yzIndex, y, z, x);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clearYZ(X x) {
        lock.writeLock().lock();
        try {
            Map<Y, Set<Z>> byY = xyIndex.remove(x);
            if (byY == null) {
                return;
            }
            for (Map.Entry<Y, Set<Z>> entry : byY.entrySet()) {
                Y y = entry.getKey();
                for (Z z : entry.getValue()) {
                    data.remove(new TableEntry<>(x, y, z));
                    removeFromIndex(yzIndex, y, z, x);
                    removeFromIndex(zxIndex, z, x, y);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clearXZ(Y y) {
        lock.writeLock().lock();
        try {
            Map<Z, Set<X>> byZ = yzIndex.remove(y);
            if (byZ == null) {
                return;
            }
            for (Map.Entry<Z, Set<X>> entry : byZ.entrySet()) {
                Z z = entry.getKey();
                for (X x : entry.getValue()) {
                    data.remove(new TableEntry<>(x, y, z));
                    removeFromIndex(xyIndex, x, y, z);
                    removeFromIndex(zxIndex, z, x, y);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clearAll() {
        lock.writeLock().lock();
        try {
            data.clear();
            xyIndex.clear();
            yzIndex.clear();
            zxIndex.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void enforceConstraints(X x, Y y, Z z) {
        if (flagX) {
            dropOthers(findX(y, z), x, value -> removeEntry(value, y, z));
        }
        if (flagY) {
            dropOthers(findY(x, z), y, value -> removeEntry(x, value, z));
        }
        if (flagZ) {
            dropOthers(findZ(x, y), z, value -> removeEntry(x, y, value));
        }
    }

    private <T> void dropOthers(Set<T> existing, T keep, java.util.function.Consumer<T> drop) {
        if (existing.isEmpty() || (existing.size() == 1 && existing.contains(keep))) {
            return;
        }
        for (T value : List.copyOf(existing)) {
            if (!Objects.equals(value, keep)) {
                drop.accept(value);
            }
        }
    }

    private void addEntry(X x, Y y, Z z) {
        if (data.add(new TableEntry<>(x, y, z))) {
            xyIndex.computeIfAbsent(x, key -> new HashMap<>())
                    .computeIfAbsent(y, key -> new HashSet<>())
                    .add(z);
            yzIndex.computeIfAbsent(y, key -> new HashMap<>())
                    .computeIfAbsent(z, key -> new HashSet<>())
                    .add(x);
            zxIndex.computeIfAbsent(z, key -> new HashMap<>())
                    .computeIfAbsent(x, key -> new HashSet<>())
                    .add(y);
        }
    }

    private void removeEntry(X x, Y y, Z z) {
        if (data.remove(new TableEntry<>(x, y, z))) {
            removeFromIndex(xyIndex, x, y, z);
            removeFromIndex(yzIndex, y, z, x);
            removeFromIndex(zxIndex, z, x, y);
        }
    }

    private <K, V, T> void removeFromIndex(Map<K, Map<V, Set<T>>> index, K key1, V key2, T value) {
        Map<V, Set<T>> secondLevel = index.get(key1);
        if (secondLevel == null) {
            return;
        }
        Set<T> values = secondLevel.get(key2);
        if (values != null) {
            values.remove(value);
            if (values.isEmpty()) {
                secondLevel.remove(key2);
            }
        }
        if (secondLevel.isEmpty()) {
            index.remove(key1);
        }
    }

    private Set<X> findX(Y y, Z z) {
        return slice(yzIndex.get(y), z);
    }

    private Set<Y> findY(X x, Z z) {
        return slice(zxIndex.get(z), x);
    }

    private Set<Z> findZ(X x, Y y) {
        return slice(xyIndex.get(x), y);
    }

    private <K, V> Set<V> slice(Map<K, Set<V>> index, K key) {
        if (index == null || key == null) {
            return Set.of();
        }
        Set<V> result = index.get(key);
        return result == null ? Set.of() : result;
    }

    private <T> List<T> copyOf(Set<T> values) {
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private <K, V> Map<K, V> pairs(Map<K, Set<V>> indexMap) {
        Map<K, V> result = new HashMap<>();
        if (indexMap == null) {
            return result;
        }
        for (Map.Entry<K, Set<V>> entry : indexMap.entrySet()) {
            Set<V> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            for (V value : values) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private void requireNonNullEntry(X x, Y y, Z z) {
        if (x == null || y == null || z == null) {
            throw new IllegalArgumentException("x, y and z must not be null");
        }
    }
}
