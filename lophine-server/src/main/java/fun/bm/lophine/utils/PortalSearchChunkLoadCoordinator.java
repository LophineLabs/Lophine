package fun.bm.lophine.utils;

import ca.spottedleaf.concurrentutil.util.Priority;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Coalesces identical in-flight chunk-area loads used by Nether portal searches.
 * Ticket ownership remains in Moonrise; this class only shares one load
 * completion among portal searches requesting the same chunk bounds.
 */
public final class PortalSearchChunkLoadCoordinator {
    private final ServerLevel world;
    private final ConcurrentHashMap<LoadKey, PendingLoad> inFlight = new ConcurrentHashMap<>();

    public PortalSearchChunkLoadCoordinator(final @NotNull ServerLevel world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    public void loadChunksAsync(
            final @NotNull BlockPos pos,
            final int radiusBlocks,
            final @NotNull Priority priority,
            final @NotNull Consumer<List<ChunkAccess>> onLoad
    ) {
        final LoadKey key = new LoadKey(
                (pos.getX() - radiusBlocks) >> 4,
                (pos.getX() + radiusBlocks) >> 4,
                (pos.getZ() - radiusBlocks) >> 4,
                (pos.getZ() + radiusBlocks) >> 4
        );

        for (;;) {
            final PendingLoad existing = this.inFlight.get(key);
            if (existing != null) {
                if (existing.add(onLoad)) {
                    return;
                }
                this.inFlight.remove(key, existing);
                continue;
            }

            final PendingLoad created = new PendingLoad();
            created.add(onLoad);
            if (this.inFlight.putIfAbsent(key, created) != null) {
                continue;
            }

            try {
                this.world.moonrise$loadChunksAsync(
                        key.minChunkX,
                        key.maxChunkX,
                        key.minChunkZ,
                        key.maxChunkZ,
                        ChunkStatus.EMPTY,
                        priority,
                        chunks -> this.complete(key, created, chunks)
                );
            } catch (final Throwable throwable) {
                created.close();
                this.inFlight.remove(key, created);
                if (throwable instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (throwable instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(throwable);
            }
            return;
        }
    }

    private void complete(final LoadKey key, final PendingLoad pending, final List<ChunkAccess> chunks) {
        final List<Consumer<List<ChunkAccess>>> callbacks = pending.close();
        this.inFlight.remove(key, pending);

        Throwable firstFailure = null;
        for (final Consumer<List<ChunkAccess>> callback : callbacks) {
            try {
                callback.accept(chunks);
            } catch (final Throwable throwable) {
                if (firstFailure == null) {
                    firstFailure = throwable;
                } else {
                    firstFailure.addSuppressed(throwable);
                }
            }
        }

        if (firstFailure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (firstFailure instanceof Error error) {
            throw error;
        }
        if (firstFailure != null) {
            throw new RuntimeException(firstFailure);
        }
    }

    public int inFlightRequestCount() {
        return this.inFlight.size();
    }

    private record LoadKey(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
    }

    private static final class PendingLoad {
        private final List<Consumer<List<ChunkAccess>>> callbacks = new ArrayList<>();
        private boolean closed;

        public synchronized boolean add(final Consumer<List<ChunkAccess>> callback) {
            if (this.closed) {
                return false;
            }
            this.callbacks.add(callback);
            return true;
        }

        public synchronized List<Consumer<List<ChunkAccess>>> close() {
            if (this.closed) {
                return List.of();
            }
            this.closed = true;
            return List.copyOf(this.callbacks);
        }
    }
}
