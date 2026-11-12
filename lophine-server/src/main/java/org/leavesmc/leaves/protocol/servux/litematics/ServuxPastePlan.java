// SPDX-License-Identifier: LGPL-3.0-only
package org.leavesmc.leaves.protocol.servux.litematics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.ticks.ScheduledTick;
import org.leavesmc.leaves.protocol.servux.litematics.placement.SchematicPlacement;
import org.leavesmc.leaves.protocol.servux.litematics.placement.SubRegionPlacement;
import org.leavesmc.leaves.protocol.servux.litematics.utils.PositionUtils;
import org.leavesmc.leaves.protocol.servux.litematics.utils.ReplaceBehavior;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Immutable source data and compact section intersections; only a few sections are expanded at once.
 */
final class ServuxPastePlan {
    static final int PHASES = 5;
    private static final int MAX_SECTIONS = 65_536;
    final SchematicPlacement placement;
    final List<Section> sections;
    final boolean heightClipped;

    private ServuxPastePlan(SchematicPlacement placement, List<Section> sections, boolean heightClipped) {
        this.placement = placement;
        this.sections = List.copyOf(sections);
        this.heightClipped = heightClipped;
    }

    int passes() {
        return this.sections.size() == 1 ? 1 : PHASES;
    }

    Prepared preparePass(Section section, int pass, ReplaceBehavior replace, BooleanSupplier cancelled) {
        if (this.sections.size() != 1) return prepare(section, pass, replace, cancelled);
        List<Change> changes = new ArrayList<>();
        for (int phase = 0; phase < PHASES - 1; phase++)
            changes.addAll(prepare(section, phase, replace, cancelled).changes());
        Prepared auxiliary = prepare(section, PHASES - 1, replace, cancelled);
        return new Prepared(section, -1, List.copyOf(changes), auxiliary.ticks(), auxiliary.entities());
    }

    static ServuxPastePlan create(SchematicPlacement placement, int minY, int maxY, BooleanSupplier cancelled) {
        Map<Long, Section> sections = new HashMap<>();
        boolean heightClipped = false;
        var boxes = placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED);
        for (String name : new TreeSet<>(boxes.keySet())) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            var box = boxes.get(name);
            if (box.pos1() == null || box.pos2() == null) continue;
            BlockPos min = PositionUtils.getMinCorner(box.pos1(), box.pos2());
            BlockPos max = PositionUtils.getMaxCorner(box.pos1(), box.pos2());
            heightClipped |= min.getY() < minY || max.getY() >= maxY;
            int loY = Math.max(min.getY(), minY), hiY = Math.min(max.getY(), maxY - 1);
            if (loY > hiY) continue;
            var relative = Objects.requireNonNull(placement.getRelativeSubRegionPlacement(name));
            var region = placement.getSchematic().getSubRegion(name);
            Part part = new Part(name, placement, relative, region,
                    new BlockPos(min.getX(), loY, min.getZ()), new BlockPos(max.getX(), hiY, max.getZ()));
            for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++)
                for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++)
                    for (int y = loY >> 4; y <= hiY >> 4; y++) {
                        if (cancelled.getAsBoolean()) throw new CancellationException();
                        section(sections, x, y, z).parts.add(part);
                    }
            addTicks(sections, part, region.pendingBlockTicks(), true, minY, maxY);
            addTicks(sections, part, region.pendingFluidTicks(), false, minY, maxY);
            if (!placement.ignoreEntities() && !relative.ignoreEntities()) {
                for (var entity : region.entities()) {
                    var pos = PositionUtils.getTransformedPosition(entity.posVec(), placement.getMirror(), placement.getRotation());
                    pos = PositionUtils.getTransformedPosition(pos, relative.mirror(), relative.rotation());
                    if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) continue;
                    BlockPos target = BlockPos.containing(pos.add(part.offset.getX(), part.offset.getY(), part.offset.getZ()));
                    if (target.getY() >= minY && target.getY() < maxY
                            && (target.getX() >> 4) >= (min.getX() >> 4) && (target.getX() >> 4) <= (max.getX() >> 4)
                            && (target.getZ() >> 4) >= (min.getZ() >> 4) && (target.getZ() >> 4) <= (max.getZ() >> 4))
                        section(sections, target.getX() >> 4, target.getY() >> 4, target.getZ() >> 4)
                                .entities.add(new SavedEntity(entity, relative));
                }
            }
        }
        List<Section> ordered = new ArrayList<>(sections.values());
        ordered.sort(Comparator.comparingInt((Section s) -> s.x).thenComparingInt(s -> s.z).thenComparingInt(s -> s.y));
        Map<Long, Map<String, Integer>> chunkOrders = new HashMap<>();
        for (Section section : ordered) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            var order = chunkOrders.computeIfAbsent(ChunkPos.pack(section.x, section.z), ignored -> {
                Map<String, Integer> ranks = new HashMap<>();
                for (String name : placement.getRegionsTouchingChunk(section.x, section.z))
                    ranks.put(name, ranks.size());
                return ranks;
            });
            section.parts.sort(Comparator.comparingInt(part -> order.get(part.name)));
        }
        return new ServuxPastePlan(placement, ordered, heightClipped);
    }

    private static Section section(Map<Long, Section> sections, int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);
        Section result = sections.computeIfAbsent(key, ignored -> new Section(x, y, z));
        if (sections.size() > MAX_SECTIONS) throw new IllegalArgumentException("Too many schematic sections");
        return result;
    }

    private static <T> void addTicks(Map<Long, Section> sections, Part part, Map<BlockPos, ScheduledTick<T>> ticks,
                                     boolean block, int minY, int maxY) {
        if (ticks == null) return;
        ticks.forEach((pos, tick) -> {
            BlockPos target = part.toWorld(pos);
            if (target.getY() >= minY && target.getY() < maxY && part.contains(target))
                section(sections, target.getX() >> 4, target.getY() >> 4, target.getZ() >> 4)
                        .ticks.add(new SavedTick(target, tick, block));
        });
    }

    Prepared prepare(Section section, int phase, ReplaceBehavior replace, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        if (phase == PHASES - 1)
            return new Prepared(section, phase, List.of(), List.copyOf(section.ticks), List.copyOf(section.entities));
        Classification known = section.classification;
        if (known != null && known.replace() == replace && (known.mask() & (1 << phase)) == 0)
            return new Prepared(section, phase, List.of(), List.of(), List.of());
        boolean indexed = known != null && known.replace() == replace && known.blocks() != null;
        byte[] phases = indexed ? known.blocks() : new byte[4096];
        ChangeBuilder changes = new ChangeBuilder(replace == ReplaceBehavior.ALL && phase == 0);
        int mask = indexed ? known.mask() : 0;
        for (int y = 0; y < 16; y++) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int local = (y << 8) | (z << 4) | x;
                    if (indexed && (phases[local] & (1 << phase)) == 0) continue;
                    BlockPos target = new BlockPos((section.x << 4) + x, (section.y << 4) + y, (section.z << 4) + z);
                    BlockState state = null;
                    CompoundTag nbt = null;
                    for (Part part : section.parts) {
                        if (!part.contains(target) || part.region.blockContainers() == null) continue;
                        BlockPos source = part.toSource(target);
                        var container = part.region.blockContainers();
                        var size = container.getSize();
                        if (source.getX() < 0 || source.getY() < 0 || source.getZ() < 0
                                || source.getX() >= size.getX() || source.getY() >= size.getY() || source.getZ() >= size.getZ())
                            continue;
                        BlockState next = container.get(source.getX(), source.getY(), source.getZ());
                        if (next.is(Blocks.STRUCTURE_VOID)) continue;
                        if (replace == ReplaceBehavior.WITH_NON_AIR && next.isAir()) continue;
                        state = part.transform(next);
                        nbt = part.region.tileEntities().get(source);
                        if (replace == ReplaceBehavior.NONE && !state.isAir()) break;
                    }
                    if (state == null) continue;
                    if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
                        BlockState dry = state.setValue(BlockStateProperties.WATERLOGGED, false);
                        if (!indexed) {
                            phases[local] = (byte) ((1 << phase(dry)) | (1 << 3));
                            mask |= phases[local];
                        }
                        if (phase(dry) == phase) changes.add(target, dry, nbt, ChangeKind.DRY_BASE);
                        if (phase == 3) changes.add(target, state, null, ChangeKind.ADD_WATER);
                    } else {
                        int kind = phase(state);
                        if (!indexed) {
                            phases[local] = (byte) (1 << kind);
                            mask |= phases[local];
                        }
                        if (kind == phase) changes.add(target, state, nbt, ChangeKind.BLOCK);
                    }
                }
        }
        section.classification = new Classification(replace, mask, phase < 31 - Integer.numberOfLeadingZeros(mask) ? phases : null);
        return new Prepared(section, phase, changes.finish(), List.of(), List.of());
    }

    static int phase(BlockState state) {
        if (!state.getFluidState().isEmpty()) return 3;
        Block block = state.getBlock();
        if (block instanceof VegetationBlock || block instanceof GrowingPlantBlock
                || block instanceof CactusBlock || block instanceof SugarCaneBlock
                || block instanceof BambooStalkBlock || block instanceof BambooSaplingBlock) return 2;
        if (state.isSignalSource() || state.hasProperty(BlockStateProperties.POWERED)
                || block instanceof DiodeBlock || block instanceof RedstoneWireBlock
                || block instanceof PistonBaseBlock || block instanceof PistonHeadBlock
                || block instanceof ObserverBlock || block instanceof DispenserBlock
                || block instanceof HopperBlock || block instanceof RedstoneLampBlock
                || block instanceof NoteBlock || block instanceof SculkSensorBlock) return 1;
        return 0;
    }

    static final class Section {
        final int x, y, z;
        final List<Part> parts = new ArrayList<>();
        final List<SavedTick> ticks = new ArrayList<>();
        final List<SavedEntity> entities = new ArrayList<>();
        volatile Classification classification;

        Section(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        long key() {
            return SectionPos.asLong(x, y, z);
        }

        ChunkPos chunk() {
            return new ChunkPos(x, z);
        }
    }

    record Prepared(Section section, int phase, List<Change> changes, List<SavedTick> ticks,
                    List<SavedEntity> entities) {
        boolean empty() {
            return changes.isEmpty() && ticks.isEmpty() && entities.isEmpty();
        }

        int size() {
            return changes.size() + ticks.size() + entities.size();
        }
    }

    enum ChangeKind {BLOCK, DRY_BASE, ADD_WATER, AIR_RUN}

    private record Classification(ReplaceBehavior replace, int mask, byte[] blocks) {
    }

    record Change(BlockPos pos, BlockState state, CompoundTag nbt, ChangeKind kind, int length) {
        Change(BlockPos pos, BlockState state, CompoundTag nbt, ChangeKind kind) {
            this(pos, state, nbt, kind, 1);
        }

        BlockPos positionAt(int offset) {
            if (offset == 0) return pos;
            int local = (((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15)) + offset;
            return new BlockPos((pos.getX() & ~15) + (local & 15), (pos.getY() & ~15) + (local >> 8),
                    (pos.getZ() & ~15) + ((local >> 4) & 15));
        }
    }

    record SavedTick(BlockPos pos, ScheduledTick<?> tick, boolean block) {
    }

    record SavedEntity(LitematicaSchematic.EntityInfo info, SubRegionPlacement placement) {
    }

    private static final class ChangeBuilder {
        private final boolean compactAir;
        private final List<Change> entries = new ArrayList<>();
        private BlockPos airStart;
        private int airLength;

        ChangeBuilder(boolean compactAir) {
            this.compactAir = compactAir;
        }

        void add(BlockPos pos, BlockState state, CompoundTag nbt, ChangeKind kind) {
            if (compactAir && state == Blocks.AIR.defaultBlockState() && nbt == null && kind == ChangeKind.BLOCK) {
                int local = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
                int start = airStart == null ? local : ((airStart.getY() & 15) << 8) | ((airStart.getZ() & 15) << 4) | (airStart.getX() & 15);
                if (airStart != null && local != start + airLength) flushAir();
                if (airStart == null) airStart = pos;
                airLength++;
            } else {
                flushAir();
                entries.add(new Change(pos, state, nbt, kind));
            }
        }

        private void flushAir() {
            if (airStart == null) return;
            entries.add(new Change(airStart, Blocks.AIR.defaultBlockState(), null,
                    airLength == 1 ? ChangeKind.BLOCK : ChangeKind.AIR_RUN, airLength));
            airStart = null;
            airLength = 0;
        }

        List<Change> finish() {
            flushAir();
            return List.copyOf(entries);
        }
    }

    private static final class Part {
        final String name;
        final SchematicPlacement placement;
        final SubRegionPlacement relative;
        final LitematicaSchematic.SubRegion region;
        final BlockPos min, max, offset, containerOffset;
        final Mirror subMirror;
        final Rotation rotation;

        Part(String name, SchematicPlacement placement, SubRegionPlacement relative, LitematicaSchematic.SubRegion region, BlockPos min, BlockPos max) {
            this.name = name;
            this.placement = placement;
            this.relative = relative;
            this.region = region;
            this.min = min;
            this.max = max;
            this.offset = PositionUtils.getTransformedBlockPos(relative.pos(), placement.getMirror(), placement.getRotation()).offset(placement.getOrigin());
            var end = PositionUtils.getRelativeEndPositionFromAreaSize(region.size()).offset(relative.pos());
            this.containerOffset = PositionUtils.getMinCorner(relative.pos(), end).subtract(relative.pos());
            Mirror mirror = relative.mirror();
            if (mirror != Mirror.NONE && (placement.getRotation() == Rotation.CLOCKWISE_90 || placement.getRotation() == Rotation.COUNTERCLOCKWISE_90))
                mirror = mirror == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
            this.subMirror = mirror;
            this.rotation = placement.getRotation().getRotated(relative.rotation());
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY()
                    && pos.getY() <= max.getY() && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }

        BlockPos toSource(BlockPos target) {
            BlockPos pos = PositionUtils.getReverseTransformedBlockPos(target.subtract(offset), relative.mirror(), relative.rotation());
            return PositionUtils.getReverseTransformedBlockPos(pos, placement.getMirror(), placement.getRotation()).subtract(containerOffset);
        }

        BlockPos toWorld(BlockPos source) {
            return PositionUtils.getTransformedPlacementPosition(source.offset(containerOffset), placement, relative).offset(offset);
        }

        BlockState transform(BlockState state) {
            return state.mirror(placement.getMirror()).mirror(subMirror).rotate(rotation);
        }
    }
}
