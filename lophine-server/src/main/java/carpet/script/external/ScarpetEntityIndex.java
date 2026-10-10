// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * References published by the actual Moonrise add/remove lifecycle; predicates still run on entity owners.
 */
public final class ScarpetEntityIndex {
    private static final Map<ServerLevel, Map<UUID, Entity>> WORLDS = new ConcurrentHashMap<>();

    private ScarpetEntityIndex() {
    }

    public static void added(net.minecraft.world.level.Level level, Entity entity) {
        if (level instanceof ServerLevel world)
            WORLDS.computeIfAbsent(world, key -> new ConcurrentHashMap<>()).put(entity.getUUID(), entity);
    }

    public static void removed(net.minecraft.world.level.Level world, Entity entity) {
        Map<UUID, Entity> entities = WORLDS.get(world);
        if (entities != null) entities.remove(entity.getUUID(), entity);
    }

    public static List<Entity> entities(ServerLevel world) {
        return List.copyOf(WORLDS.getOrDefault(world, Map.of()).values());
    }

    /**
     * Only a published identity reference; every mutable getter must still run on its actual actor.
     */
    public static Entity reference(ServerLevel world, UUID identity) {
        return WORLDS.getOrDefault(world, Map.of()).get(identity);
    }

    public static void close(ServerLevel world) {
        WORLDS.remove(world);
    }
}
