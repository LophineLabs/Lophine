// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Native Starlight area worker, with an actual receipt after engine release and ticket cleanup. */
public final class TisChunkRelighter {
    private TisChunkRelighter() { }
    public static CompletableFuture<Integer> relight(ServerLevel world, Collection<ChunkPos> requested) {
        return work(world, requested, false);
    }
    public static CompletableFuture<Integer> eraseLight(ServerLevel world, ChunkPos position) {
        return work(world, java.util.List.of(position), true);
    }
    private static CompletableFuture<Integer> work(ServerLevel world, Collection<ChunkPos> requested, boolean erase) {
        var actual = new CompletableFuture<Integer>(); ScarpetNativeWork.record(actual);
        var caller = ScarpetNativeWork.trackNative(world.getServer(), actual);
        var completed = new CompletableFuture<Integer>();
        var published = new CompletableFuture<Void>();
        var admissionFailure = new AtomicReference<Throwable>();
        completed.whenComplete((count, failure) -> published.whenComplete((ignored, publicationFailure) -> {
            Throwable failed = failure == null ? admissionFailure.get() : failure;
            if (failed == null) actual.complete(count); else actual.completeExceptionally(failed);
        }));
        var tickets = new LinkedHashMap<ChunkPos, Long>();
        var admission = new AtomicInteger();
        try {
            var chunks = new LinkedHashSet<>(requested);
            var scheduler = world.moonrise$getChunkTaskScheduler();
            var light = world.getChunkSource().getLightEngine();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (var iterator = chunks.iterator(); iterator.hasNext();) {
                ChunkPos pos = iterator.next();
                ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(world, pos, "Relight capture must own the chunk");
                minX = Math.min(minX, pos.x()); minZ = Math.min(minZ, pos.z()); maxX = Math.max(maxX, pos.x()); maxZ = Math.max(maxZ, pos.z());
                long id = ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.getNextChunkRelightId();
                scheduler.chunkHolderManager.addTicketAtLevel(ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.CHUNK_RELIGHT,
                    pos, ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.LIGHT_TICKET_LEVEL, id);
                tickets.put(pos, id);
                var chunk = (net.minecraft.world.level.chunk.ChunkAccess) world.getChunkSource().getChunkForLighting(pos.x(), pos.z());
                if (chunk == null || !chunk.isLightCorrect() || !chunk.getPersistedStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.LIGHT)) iterator.remove();
            }
            if (chunks.isEmpty()) {
                release(world, tickets); completed.complete(0);
            } else {
                var captured = ScarpetRuntime.captureNativeContinuation(() -> {
                    if (!admission.compareAndSet(0, 1)) return null;
                    var observed = ScarpetNativeWork.observeNative(null, () -> {
                        Throwable problem = null;
                        var count = new AtomicReference<Integer>();
                        try {
                            if (erase) {
                                for (ChunkPos pos : chunks) {
                                    var chunk = (net.minecraft.world.level.chunk.ChunkAccess) world.getChunkSource().getChunkForLighting(pos.x(), pos.z());
                                    if (chunk == null) throw new IllegalStateException("Erased light chunk lost its hold");
                                    var data = (ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk) chunk;
                                    data.starlight$setBlockNibbles(filled(data.starlight$getBlockNibbles().length, false));
                                    if (world.dimensionType().hasSkyLight()) data.starlight$setSkyNibbles(filled(data.starlight$getSkyNibbles().length, true));
                                    boolean[] empty = new boolean[chunk.getSections().length];
                                    for (int i = 0; i < empty.length; ++i) empty[i] = chunk.getSections()[i].hasOnlyAir();
                                    data.starlight$setBlockEmptinessMap(empty.clone()); data.starlight$setSkyEmptinessMap(empty);
                                }
                                count.set(chunks.size());
                            } else light.starlight$getLightEngine().relightChunks(chunks, null, count::set);
                        }
                        catch (Throwable failure) { problem = failure; }
                        try { release(world, tickets); }
                        catch (Throwable failure) { if (problem == null) problem = failure; else problem.addSuppressed(failure); }
                        if (problem instanceof Error error) throw error;
                        if (problem != null) throw new java.util.concurrent.CompletionException(problem);
                        if (count.get() == null) throw new IllegalStateException("Native relighter did not publish its actual outcome");
                        return count.get();
                    });
                    ScarpetNativeWork.trackNative(world.getServer(), observed); ScarpetNativeWork.aliasDependency(actual, observed);
                    ScarpetNativeWork.recoverGuestValue(observed).whenComplete((count, failure) -> {
                        if (failure == null) completed.complete(count); else completed.completeExceptionally(failure);
                    });
                    return null;
                });
                var task = scheduler.radiusAwareScheduler.createTask(minX - 1, minZ - 1, maxX + 1, maxZ + 1, () -> {
                    try { captured.get(); }
                    catch (Throwable failure) { completed.completeExceptionally(failure); }
                });
                if (!task.queue()) throw new java.util.concurrent.RejectedExecutionException("Native relight worker rejected");
            }
        } catch (Throwable failure) {
            admissionFailure.set(failure);
            if (admission.compareAndSet(0, 2)) {
                try { release(world, tickets); }
                catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                completed.completeExceptionally(failure);
            }
        } finally { published.complete(null); }
        return caller;
    }
    private static ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray[] filled(int size, boolean sky) {
        var arrays = new ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray[size];
        for (int i = 0; i < size; ++i) {
            var nibble = new ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray();
            if (sky) nibble.setFull(); else nibble.setZero();
            nibble.updateVisible(); arrays[i] = nibble;
        }
        return arrays;
    }
    private static void release(ServerLevel world, Map<ChunkPos, Long> tickets) {
        Throwable failed = null;
        for (var ticket : tickets.entrySet()) try {
            world.moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
                ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.CHUNK_RELIGHT, ticket.getKey(),
                ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.LIGHT_TICKET_LEVEL, ticket.getValue());
        } catch (Throwable failure) { if (failed == null) failed = failure; else failed.addSuppressed(failure); }
        tickets.clear();
        if (failed instanceof Error error) throw error;
        if (failed != null) throw new java.util.concurrent.CompletionException(failed);
    }
}
