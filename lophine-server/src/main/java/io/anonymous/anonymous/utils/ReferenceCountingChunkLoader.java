package io.anonymous.anonymous.utils;

import ca.spottedleaf.concurrentutil.completable.CallbackCompletable;
import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 *  一个简单的基于folia的RegionizedTaskQueue引用计数的轻量ticket区块加载器
 *  用于在folia传送门搜索的高频率scheduleChunkLoad的调用下减少ticket操作从而减轻锁负载
 *  大部分内容物均取自folia的RegionizedTaskQueue(引用计数), ttl机制取自我的优化
 *  
 * @see io.papermc.paper.threadedregions.RegionizedTaskQueue
 * @see Entity#findOrCreatePortalAsync(ServerLevel, BlockPos, ServerLevel, Entity.PortalType, CallbackCompletable)
 */
public class ReferenceCountingChunkLoader {
    private static final long MAX_CHUNK_TTL_TICKS = 2L;

    private final ServerLevel world;
    private final ChunkStatus targetStatus;
    private final ConcurrentChainedLong2ReferenceHashTable<ReferenceCountData> referenceCounters = new ConcurrentChainedLong2ReferenceHashTable<>();

    public ReferenceCountingChunkLoader(ServerLevel world, ChunkStatus targetStatus) {
        this.world = world;
        this.targetStatus = targetStatus;
    }

    private void decrementReference(final @NonNull ReferenceCountData referenceCountData, final long coord) {
        if (!referenceCountData.decreaseReferenceCount()) {
            return;
        }

        final ReferenceCountData[] toRemoveTicket = new ReferenceCountData[1];

        this.referenceCounters.computeIfPresent(coord, (final long keyInMap, final ReferenceCountData valueInMap) -> {
            if (valueInMap.referenceCount.get() != 0L) {
                return valueInMap;
            }

            toRemoveTicket[0] = valueInMap;

            return null;
        });

        if (toRemoveTicket[0] != null) {
            this.removeTicket(coord, toRemoveTicket[0].id);
        }
    }

    private void removeTicket(final long coord, final long id) {
        this.world.moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
                ChunkTaskScheduler.CHUNK_LOAD, coord, ChunkTaskScheduler.getTicketLevel(this.targetStatus), Long.valueOf(id)
        );
    }

    private void addTicket(final long coord, final long id) {
        this.world.moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(
                ChunkTaskScheduler.CHUNK_LOAD, coord, ChunkTaskScheduler.getTicketLevel(this.targetStatus), Long.valueOf(id)
        );
    }

    private void processTicketUpdates(final long coord) {
        this.world.moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates(CoordinateUtils.getChunkX(coord), CoordinateUtils.getChunkZ(coord));
    }

    private void ensureTicketAdded(final long coord, final @NonNull ReferenceCountData referenceCountData) {
        if (!referenceCountData.addedTicket) {
            this.addTicket(coord, referenceCountData.id);
            this.processTicketUpdates(coord);
            referenceCountData.addedTicket = true;
        }
    }

    public final void loadChunksAsync(final @NonNull BlockPos pos, final int radiusBlocks,
                                      final Priority priority,
                                      final Consumer<List<ChunkAccess>> onLoad) {
        this.loadChunksAsync(
                (pos.getX() - radiusBlocks) >> 4,
                (pos.getX() + radiusBlocks) >> 4,
                (pos.getZ() - radiusBlocks) >> 4,
                (pos.getZ() + radiusBlocks) >> 4,
                priority, onLoad
        );
    }

    public final void loadChunksAsync(final int minChunkX, final int maxChunkX, final int minChunkZ, final int maxChunkZ,
                                      final Priority priority,
                                      final Consumer<List<ChunkAccess>> onLoad) {
        this.loadChunksAsync(minChunkX, maxChunkX, minChunkZ, maxChunkZ, priority, onLoad, null);
    }


    public final void loadChunksAsync(final int minChunkX, final int maxChunkX, final int minChunkZ, final int maxChunkZ,
                                      final Priority priority,
                                      final Consumer<List<ChunkAccess>> onLoad, final Consumer<ChunkAccess> onEachLoad) {
        final int requiredChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        final AtomicInteger loadedChunks = new AtomicInteger();

        final List<Pair<ReferenceCountData, ChunkAccess>> ret = new ArrayList<>(requiredChunks);

        final Consumer<ChunkAccess> consumer = (final ChunkAccess chunk) -> {

            if (chunk != null) {
                final long pos = chunk.getPos().longKey();

                synchronized (ret) {
                    ret.add(Pair.of(this.incrementReference(pos), chunk));
                }
            }

            if (onEachLoad != null) {
                onEachLoad.accept(chunk);
            }

            if (loadedChunks.incrementAndGet() == requiredChunks) {
                try {
                    if (onLoad != null) {
                        final List<ChunkAccess> processed = new ArrayList<>(ret.size());

                        for (Pair<ReferenceCountData, ChunkAccess> result : ret) {
                            processed.add(result.right());
                        }

                        onLoad.accept(processed);
                    }
                } finally {
                    for (Pair<ReferenceCountData, ChunkAccess> extraRefEntry : ret) {
                        this.decrementReference(extraRefEntry.left(), extraRefEntry.right().getPos().longKey());
                    }
                }
            }
        };

        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                this.loadAsync(cx, cz, consumer, priority);
            }
        }
    }

    public void loadAsync(int chunkX, int chunkZ, Consumer<ChunkAccess> callback, Priority priority) {
        final long coord = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        final ReferenceCountData increased = this.incrementReference(coord);

        final ChunkAccess cached = increased.cached;
        if (cached != null) {
            RegionizedServer.getInstance().taskQueue.queueChunkTask(this.world, chunkX, chunkZ, () -> {
                try {
                    callback.accept(cached);
                }finally {
                    this.decrementReference(increased, coord);
                }
            }, priority);

            return;
        }

        this.world.moonrise$getChunkTaskScheduler().scheduleChunkLoad(
                chunkX, chunkZ, this.targetStatus, true, priority,
                chunk -> {
                    try {
                        increased.cached = chunk;

                        callback.accept(chunk);
                    }finally {
                        this.decrementReference(increased, coord);
                    }
                }
        );
    }

    private ReferenceCountData incrementReference(final long coord) {
        ReferenceCountData referenceCountData = this.referenceCounters.get(coord);

        if (referenceCountData != null && referenceCountData.addCount()) {
            this.ensureTicketAdded(coord, referenceCountData);
            return referenceCountData;
        }

        referenceCountData = this.referenceCounters.compute(coord, (final long keyInMap, final ReferenceCountData valueInMap) -> {
            if (valueInMap == null) {
                return new ReferenceCountData();
            }

            valueInMap.referenceCount.getAndIncrement();

            return valueInMap;
        });

        this.ensureTicketAdded(coord, referenceCountData);

        return referenceCountData;
    }

    public void tickChunkReferenceTTL() {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> currentRegion
                = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion();
        if (currentRegion == null) {
            return;
        }

        final ReferenceCountData[] toRemoveTicket = new ReferenceCountData[1];

        for (ConcurrentChainedLong2ReferenceHashTable.TableEntry<ReferenceCountData> counterEntry : this.referenceCounters.entrySet()) {
            final long coord = counterEntry.getKey();
            final ReferenceCountData counterData = counterEntry.getValue();

            if (currentRegion == this.world.regioniser.getRegionAtUnsynchronised(CoordinateUtils.getChunkX(coord), CoordinateUtils.getChunkZ(coord))) {
                long curr = counterData.referenceTTL.get();
                if (curr == (curr = counterData.referenceTTL.compareAndExchange(curr, curr - 1))) {
                    if (counterData.referenceCount.get() != 0L) {
                        counterData.referenceTTL.set(MAX_CHUNK_TTL_TICKS);
                        continue;
                    }

                    if (curr <= 0) {
                        this.referenceCounters.computeIfPresent(coord, (final long keyInMap, final ReferenceCountData valueInMap) -> {
                            if (valueInMap.referenceCount.get() != 0L) {
                                valueInMap.referenceTTL.set(MAX_CHUNK_TTL_TICKS);
                                return valueInMap;
                            }

                            toRemoveTicket[0] = valueInMap;

                            return null;
                        });

                        if (toRemoveTicket[0] != null) {
                            this.removeTicket(coord, toRemoveTicket[0].id);
                            toRemoveTicket[0] = null;
                        }
                    }
                }
            }
        }
    }

    private static final class ReferenceCountData {

        private static final AtomicLong ID_GENERATOR = new AtomicLong();

        private final long id = ID_GENERATOR.getAndIncrement();

        public final AtomicLong referenceCount = new AtomicLong(1L);
        public final AtomicLong referenceTTL = new AtomicLong(MAX_CHUNK_TTL_TICKS);

        public volatile ChunkAccess cached;
        public volatile boolean addedTicket;

        public boolean addCount() {
            int failures = 0;
            for (long curr = this.referenceCount.get();;) {
                for (int i = 0; i < failures; ++i) {
                    Thread.onSpinWait();
                }

                if (curr == 0L) {
                    return false;
                }

                if (curr == (curr = this.referenceCount.compareAndExchange(curr, curr + 1L))) {
                    int ttlFailures = 0;
                    for (long currTTL = this.referenceTTL.get();;) {
                        for (int i = 0; i < ttlFailures; i++) {
                            Thread.onSpinWait();
                        }

                        if (currTTL <= 0) {
                            this.referenceCount.decrementAndGet();
                            return false;
                        }

                        if (currTTL == (currTTL = this.referenceTTL.compareAndExchange(currTTL, MAX_CHUNK_TTL_TICKS))) {
                            break;
                        }

                        ++ttlFailures;
                    }
                    return true;
                }

                ++failures;
            }
        }

        public boolean decreaseReferenceCount() {
            final long res = this.referenceCount.decrementAndGet();
            if (res >= 0L) {
                return res == 0L && this.referenceTTL.get() <= 0L;
            } else {
                throw new IllegalStateException("Negative reference count");
            }
        }
    }
}
