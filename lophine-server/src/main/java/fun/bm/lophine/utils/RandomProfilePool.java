package fun.bm.lophine.utils;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.authlib.GameProfile;
import fun.bm.lophine.config.modules.function.ReplayAPIConfig;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public class RandomProfilePool {
    private static int lastUsedId = 0;
    private static final Object lock = new Object();
    private static Cache<Integer, GameProfile> cache;
    private static final Object lockCache = new Object();

    public static void init() {
        if (ReplayAPIConfig.enableCache) {
            cache = CacheBuilder.newBuilder()
                    .maximumSize(ReplayAPIConfig.cachePhotographerSize)
                    .expireAfterWrite(Duration.ofSeconds(ReplayAPIConfig.cachePhotographerTime))
                    .build();
        }
    }

    public static GameProfile getRandomProfile(String id) {
        if (ReplayAPIConfig.enableCache) {
            synchronized (lockCache) {
                for (Map.Entry<Integer, GameProfile> entry : cache.asMap().entrySet()) {
                    int key = entry.getKey();
                    cache.invalidate(key);
                    GameProfile gp = entry.getValue();
                    return new GameProfile(gp.id(), id, gp.properties());
                }
            }
        }
        return new GameProfile(UUID.randomUUID(), id);
    }

    public static void putProfile(GameProfile profile) {
        if (ReplayAPIConfig.enableCache) {
            synchronized (lockCache) {
                cache.put(getNextId(), profile);
            }
        }
    }

    private static int getNextId() {
        synchronized (lock) {
            int newId = lastUsedId + 1;
            while (cache.getIfPresent(newId) != null) {
                newId++;
            }
            lastUsedId = newId;
            return newId;
        }
    }
}
