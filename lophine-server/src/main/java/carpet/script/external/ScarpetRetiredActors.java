// SPDX-License-Identifier: MIT
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.exception.InternalExpressionException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.function.Supplier;

/**
 * A retired non-player is still owned by the region containing its location (Native TickThread contract).
 */
public final class ScarpetRetiredActors {
    private record Location(ServerLevel world, BlockPos position, boolean removed) {
    }

    public record LastOwner(ServerLevel world, BlockPos position) {
    }

    public static LastOwner lastOwner(Entity entity) {
        Location location = LOCATIONS.get(entity);
        return location == null ? null : new LastOwner(location.world(), location.position());
    }

    public static boolean matchesLastOwner(Entity entity, LastOwner owner) {
        Location current = LOCATIONS.get(entity);
        return current != null && current.world() == owner.world() && current.position().equals(owner.position());
    }

    private record Result<T>(boolean valid, T value) {
    }

    private static final WeakIdentityMap<Entity, Location> LOCATIONS = new WeakIdentityMap<>();

    private ScarpetRetiredActors() {
    }

    /**
     * Called on the actual owner before and after Native removal, or while capturing a retirement event.
     */
    public static void capture(Entity entity) {
        if (entity.level() instanceof ServerLevel world)
            LOCATIONS.put(entity, new Location(world, entity.blockPosition().immutable(), entity.isRemoved()));
    }

    public static boolean knownRetired(Entity entity) {
        Location location = LOCATIONS.get(entity);
        return location != null && location.removed();
    }

    public static <T> java.util.concurrent.CompletableFuture<T> accessFuture(Entity entity, Supplier<T> operation) {
        return ScarpetRuntime.atEntityFuture(entity, () -> {
            try {
                return operation.get();
            } finally {
                capture(entity);
            }
        }).handle((value, failure) -> {
            if (failure == null) return java.util.concurrent.CompletableFuture.completedFuture(value);
            Throwable cause = failure;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
                cause = cause.getCause();
            if (!(cause instanceof InternalExpressionException) || !("Entity retired before scarpet operation".equals(cause.getMessage()) || "Entity scheduler retired".equals(cause.getMessage())) || LOCATIONS.get(entity) == null)
                return java.util.concurrent.CompletableFuture.<T>failedFuture(cause);
            return retiredFuture(entity, operation, 0);
        }).thenCompose(next -> next);
    }

    private static <T> java.util.concurrent.CompletableFuture<T> retiredFuture(Entity entity, Supplier<T> operation, int attempt) {
        Location origin = LOCATIONS.get(entity);
        if (origin == null || attempt == 8)
            return java.util.concurrent.CompletableFuture.failedFuture(new InternalExpressionException("Retired entity kept moving between regions"));
        return ScarpetRuntime.atBlockFuture(origin.world(), origin.position(), () -> {
            if (LOCATIONS.get(entity) != origin) return new Result<T>(false, null);
            try {
                return new Result<>(true, operation.get());
            } finally {
                capture(entity);
            }
        }).thenCompose(result -> result.valid() ? java.util.concurrent.CompletableFuture.completedFuture(result.value()) : retiredFuture(entity, operation, attempt + 1));
    }

    public static <T> T access(Entity entity, Supplier<T> operation) {
        try {
            return ScarpetRuntime.atEntity(entity, () -> {
                try {
                    return operation.get();
                } finally {
                    capture(entity);
                }
            });
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
                cause = cause.getCause();
            if (!(cause instanceof InternalExpressionException) || !("Entity retired before scarpet operation".equals(cause.getMessage()) || "Entity scheduler retired".equals(cause.getMessage())))
                throw failure;
            if (LOCATIONS.get(entity) == null) throw failure;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            Location origin = LOCATIONS.get(entity);
            if (origin == null) throw new InternalExpressionException("No captured region for this retired entity");
            Result<T> completed = ScarpetRuntime.atBlock(origin.world(), origin.position(), () -> {
                if (LOCATIONS.get(entity) != origin) return new Result<T>(false, null);
                // Removed non-players have no scheduler, but the Native ownership check explicitly permits their chunk owner.
                // Offline players have no live connection owner; their original object remains confined to its final region.
                if (!TickThread.isTickThreadFor(origin.world(), origin.position())) return new Result<T>(false, null);
                try {
                    return new Result<>(true, operation.get());
                } finally {
                    capture(entity);
                }
            });
            if (completed.valid()) return completed.value();
        }
        throw new InternalExpressionException("Retired entity kept moving between regions");
    }
}
