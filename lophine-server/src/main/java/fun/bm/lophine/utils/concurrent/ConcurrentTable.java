package fun.bm.lophine.utils.concurrent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Predicate;

public class ConcurrentTable<X, Y, Z> implements AbstractConcurrentTable<X, Y, Z> {
    protected final Set<TableEntry<X, Y, Z>> data = ConcurrentHashMap.newKeySet();
    protected final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    protected final boolean flagX;
    protected final boolean flagY;
    protected final boolean flagZ;

    public ConcurrentTable() {
        this(false, false, false);
    }

    public ConcurrentTable(boolean flagX, boolean flagY, boolean flagZ) {
        this.flagX = flagX;
        this.flagY = flagY;
        this.flagZ = flagZ;
    }

    @Override
    public void put(X x, Y y, Z z) {
        if (x == null || y == null || z == null) {
            throw new IllegalArgumentException("x, y and z must not be null");
        }
        lock.writeLock().lock();
        try {
            if (flagX) {
                List<X> values = getX(y, z);
                for (X value : values) {
                    if (!Objects.equals(value, x)) {
                        remove(value, y, z);
                    }
                }
            }
            if (flagY) {
                List<Y> values = getY(x, z);
                for (Y value : values) {
                    if (!Objects.equals(value, y)) {
                        remove(x, value, z);
                    }
                }
            }
            if (flagZ) {
                List<Z> values = getZ(x, y);
                for (Z value : values) {
                    if (!Objects.equals(value, z)) {
                        remove(x, y, value);
                    }
                }
            }
            data.add(new TableEntry<>(x, y, z));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(X x, Y y, Z z) {
        lock.writeLock().lock();
        try {
            data.removeIf(entry -> Objects.equals(entry.x(), x)
                    && Objects.equals(entry.y(), y)
                    && Objects.equals(entry.z(), z));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<Z> getZ(X x, Y y) {
        lock.readLock().lock();
        try {
            return filterAndCollect(
                    entry -> Objects.equals(entry.x(), x) && Objects.equals(entry.y(), y),
                    TableEntry::z
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Y> getY(X x, Z z) {
        lock.readLock().lock();
        try {
            return filterAndCollect(
                    entry -> Objects.equals(entry.x(), x) && Objects.equals(entry.z(), z),
                    TableEntry::y
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<X> getX(Y y, Z z) {
        lock.readLock().lock();
        try {
            return filterAndCollect(
                    entry -> Objects.equals(entry.y(), y) && Objects.equals(entry.z(), z),
                    TableEntry::x
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<X, Y> getXY(Z z) {
        lock.readLock().lock();
        try {
            return filterAndMap(
                    entry -> Objects.equals(entry.z(), z),
                    TableEntry::x,
                    TableEntry::y
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<Y, Z> getYZ(X x) {
        lock.readLock().lock();
        try {
            return filterAndMap(
                    entry -> Objects.equals(entry.x(), x),
                    TableEntry::y,
                    TableEntry::z
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<X, Z> getXZ(Y y) {
        lock.readLock().lock();
        try {
            return filterAndMap(
                    entry -> Objects.equals(entry.y(), y),
                    TableEntry::x,
                    TableEntry::z
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<X> getAllX() {
        lock.readLock().lock();
        try {
            return collectAll(TableEntry::x);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Y> getAllY() {
        lock.readLock().lock();
        try {
            return collectAll(TableEntry::y);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Z> getAllZ() {
        lock.readLock().lock();
        try {
            return collectAll(TableEntry::z);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void clearXY(Z z) {
        lock.writeLock().lock();
        try {
            data.removeIf(entry -> Objects.equals(entry.z(), z));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clearYZ(X x) {
        lock.writeLock().lock();
        try {
            data.removeIf(entry -> Objects.equals(entry.x(), x));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clearXZ(Y y) {
        lock.writeLock().lock();
        try {
            data.removeIf(entry -> Objects.equals(entry.y(), y));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clearAll() {
        lock.writeLock().lock();
        try {
            data.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private <T> List<T> filterAndCollect(Predicate<TableEntry<X, Y, Z>> filter,
                                         Function<TableEntry<X, Y, Z>, T> mapper) {
        Set<T> result = new LinkedHashSet<>();
        for (TableEntry<X, Y, Z> entry : data) {
            if (filter.test(entry)) {
                result.add(mapper.apply(entry));
            }
        }
        return List.copyOf(result);
    }

    private <K, V> Map<K, V> filterAndMap(Predicate<TableEntry<X, Y, Z>> filter,
                                          Function<TableEntry<X, Y, Z>, K> keyMapper,
                                          Function<TableEntry<X, Y, Z>, V> valueMapper) {
        Map<K, V> map = new HashMap<>();
        for (TableEntry<X, Y, Z> entry : data) {
            if (filter.test(entry)) {
                map.put(keyMapper.apply(entry), valueMapper.apply(entry));
            }
        }
        return map;
    }

    private <T> List<T> collectAll(Function<TableEntry<X, Y, Z>, T> mapper) {
        Set<T> result = new LinkedHashSet<>();
        for (TableEntry<X, Y, Z> entry : data) {
            result.add(mapper.apply(entry));
        }
        return List.copyOf(result);
    }
}
