package net.caffeinemc.mods.lithium.common.tracking.entity;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.caffeinemc.mods.lithium.common.util.tuples.WorldSectionBox;

/**
 * Region-owned, identity-based inventory of movement timestamps. In particular, this includes sections
 * allocated in a tracker's halo, which need not have a loaded LevelChunk. Only the owning tick thread
 * (or the regioniser's merge/split callback) may mutate this registry.
 */
public final class MovementTrackerRegionRegistry {
    private final ReferenceOpenHashSet<SectionedEntityMovementTracker<?>> trackers = new ReferenceOpenHashSet<>();
    private final ReferenceOpenHashSet<MoonriseEntityMovementTrackerSection> sections = new ReferenceOpenHashSet<>();
    // Equal trackers from merged regions can both still be referenced by hoppers. Keep every live
    // identity, but return one representative for future registrations. No world-wide lock is needed.
    private final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<SectionedEntityMovementTracker<?>,
            java.util.ArrayList<SectionedEntityMovementTracker<?>>> canonicalTrackers = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();

    @SuppressWarnings("unchecked")
    <T extends SectionedEntityMovementTracker<?>> T getCanonical(T candidate) {
        var bucket = this.canonicalTrackers.get(candidate);
        return bucket == null ? candidate : (T) bucket.getFirst();
    }

    private void indexTracker(SectionedEntityMovementTracker<?> tracker) {
        this.canonicalTrackers.computeIfAbsent(tracker, ignored -> new java.util.ArrayList<>(1)).add(tracker);
    }

    void addTracker(SectionedEntityMovementTracker<?> tracker) {
        if (this.trackers.add(tracker)) {
            this.indexTracker(tracker);
        }
        tracker.regionRegistry = this;
    }

    void removeTracker(SectionedEntityMovementTracker<?> tracker) {
        if (this.trackers.remove(tracker)) {
            var bucket = this.canonicalTrackers.remove(tracker);
            // Removal must use identity: an equal tracker can still be live after a region merge.
            bucket.removeIf(entry -> entry == tracker);
            if (!bucket.isEmpty()) {
                this.canonicalTrackers.put(bucket.getFirst(), bucket);
            }
        }
        tracker.regionRegistry = null;
    }

    void addSection(MoonriseEntityMovementTrackerSection section) {
        this.sections.add(section);
        section.regionRegistry = this;
    }

    void removeSection(MoonriseEntityMovementTrackerSection section) {
        this.sections.remove(section);
        section.regionRegistry = null;
    }

    public void mergeInto(MovementTrackerRegionRegistry into, long redstoneTimeOffset) {
        // Never walk tracker.sortedSections here: a source tracker can refer to a target-owned section,
        // and multiple canonical trackers (and multiple hoppers) can refer to the very same section.
        for (SectionedEntityMovementTracker<?> tracker : this.trackers) {
            tracker.offsetMovementTime(redstoneTimeOffset);
            into.addTracker(tracker);
        }
        for (MoonriseEntityMovementTrackerSection section : this.sections) {
            section.offsetMovementTime(redstoneTimeOffset);
            into.addSection(section);
        }
        this.trackers.clear();
        this.canonicalTrackers.clear();
        this.sections.clear();
    }

    public void split(int chunkToRegionShift, Long2ReferenceOpenHashMap<RegionizedWorldData> regionToData) {
        this.split(chunkToRegionShift, key -> {
            RegionizedWorldData data = regionToData.get(key);
            return data == null ? null : data.entityMovementTrackerRegistry;
        });
    }

    void split(int chunkToRegionShift, java.util.function.LongFunction<MovementTrackerRegionRegistry> regionToRegistry) {
        // All children inherit the same redstone time, so splitting changes ownership, not timestamps.
        for (SectionedEntityMovementTracker<?> tracker : this.trackers) {
            WorldSectionBox box = tracker.trackedWorldSections;
            MovementTrackerRegionRegistry into = null;
            // Do not rely on the lower corner: it can be an unowned halo chunk. The first mapped
            // chunk gives a stable owner independently of identity-set iteration order. Current
            // callers track the small hopper query halo; independent Folia regions cannot be adjacent,
            // so that halo cannot cover two live split children. Arbitrarily large boxes would need
            // caller-provided ownership instead.
            for (int x = box.chunkX1(); x < box.chunkX2() && into == null; ++x) {
                for (int z = box.chunkZ1(); z < box.chunkZ2() && into == null; ++z) {
                    into = regionToRegistry.apply(CoordinateUtils.getChunkKey(x >> chunkToRegionShift, z >> chunkToRegionShift));
                }
            }
            if (into != null) {
                into.addTracker(tracker);
            } else {
                // No live region intersects this tracker (e.g. a removed hopper awaiting cleanup).
                // Retain the old registry for eventual unregister; it will no longer be ticked.
                tracker.regionRegistry = this;
            }
        }
        for (MoonriseEntityMovementTrackerSection section : this.sections) {
            MovementTrackerRegionRegistry into = regionToRegistry.apply(CoordinateUtils.getChunkKey(
                    section.chunkX >> chunkToRegionShift, section.chunkZ >> chunkToRegionShift));
            MovementTrackerRegionRegistry owner = into == null ? section.getListenerRegistryAfterSplit(this) : into;
            if (owner != null && owner != this) {
                owner.addSection(section);
            }
        }
        this.trackers.removeIf(tracker -> tracker.regionRegistry != this);
        this.canonicalTrackers.clear();
        for (SectionedEntityMovementTracker<?> tracker : this.trackers) {
            this.indexTracker(tracker);
        }
        this.sections.removeIf(section -> section.regionRegistry != this);
    }
}
