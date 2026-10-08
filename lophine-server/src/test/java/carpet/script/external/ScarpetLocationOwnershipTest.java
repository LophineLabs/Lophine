package carpet.script.external;

import com.mojang.serialization.Lifecycle;
import fun.bm.lophine.carpet.CarpetRegionLease;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.advancements.predicates.LightPredicate;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetLocationOwnershipTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel world = mock(ServerLevel.class);
        final MockedStatic<CarpetRegionLease> leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open();
        final List<List<Integer>> footprints = new ArrayList<>();
        final Deque<List<Integer>> owned = new ArrayDeque<>();
        final MappedRegistry<Structure> structures = new MappedRegistry<>(Registries.STRUCTURE, Lifecycle.stable());
        final Structure structure = mock(Structure.class);
        final ChunkAccess center = mock(ChunkAccess.class), reference = mock(ChunkAccess.class);
        final StructureStart start = mock(StructureStart.class);
        final StructurePiece piece = mock(StructurePiece.class);
        final RegistryAccess.Frozen registries = mock(RegistryAccess.Frozen.class);
        CompletableFuture<Void> nativeChild;
        boolean disappear;

        Fixture() {
            when(world.getServer()).thenReturn(server);
            when(world.isLoaded(any())).thenReturn(true);
            Registry.register(structures, "test:structure", structure);
            structures.freeze();
            when(registries.lookupOrThrow(Registries.STRUCTURE)).thenReturn(structures);
            when(world.registryAccess()).thenReturn(registries);
            when(center.getAllReferences()).thenReturn(Map.of(structure, new LongOpenHashSet(new long[]{ChunkPos.pack(37, -23)})));
            when(reference.getStartForStructure(structure)).thenReturn(start);
            when(start.isValid()).thenReturn(true);
            when(start.getPieces()).thenReturn(List.of(piece));
            when(start.getBoundingBox()).thenReturn(new BoundingBox(-1, -1, -1, 16, 16, 16));
            when(piece.getBoundingBox()).thenReturn(new BoundingBox(-1, -1, -1, 16, 16, 16));
            when(world.structureManager()).thenReturn(new StructureManager(world, new WorldOptions(0L, true, false), null));
            when(world.getChunk(anyInt(), anyInt(), any(ChunkStatus.class))).thenAnswer(call -> {
                int x = call.getArgument(0), z = call.getArgument(1);
                ChunkStatus status = call.getArgument(2);
                var bounds = owned.peek();
                assertNotNull(bounds);
                assertTrue(x >= bounds.get(0) && x <= bounds.get(2) && z >= bounds.get(1) && z <= bounds.get(3), "Native reference is outside the actual owned footprint");
                if (status == ChunkStatus.STRUCTURE_REFERENCES) {
                    assertEquals(0, x);
                    assertEquals(0, z);
                    return center;
                }
                assertSame(ChunkStatus.STRUCTURE_STARTS, status);
                assertEquals(37, x);
                assertEquals(-23, z);
                if (nativeChild != null) ScarpetNativeWork.record(nativeChild);
                return reference;
            });
            leases.when(() -> CarpetRegionLease.runLoadedValue(eq(world), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class))).thenAnswer(call -> {
                var bounds = List.of((Integer) call.getArgument(1), (Integer) call.getArgument(2), (Integer) call.getArgument(3), (Integer) call.getArgument(4));
                footprints.add(bounds);
                owned.push(bounds);
                if (disappear) when(world.isLoaded(any())).thenReturn(false);
                try {
                    return CompletableFuture.completedFuture(((Function<?, ?>) call.getArgument(5)).apply(null));
                } finally {
                    owned.pop();
                }
            });
        }

        Holder<Structure> holder() {
            return structures.get(Identifier.parse("test:structure")).orElseThrow();
        }

        public void close() {
            leases.close();
        }
    }

    @Test
    void theOriginalNativeStructureAlgorithmOwnsFarReferenceChunksWithoutForcingFullRectangleLoads() {
        try (var f = new Fixture()) {
            var predicate = LocationPredicate.Builder.inStructure(f.holder()).build();
            assertTrue(ScarpetLocationPredicates.matches(predicate, f.world, new Vec3(5, 4, 6)).join());
            assertEquals(List.of(List.of(-1, -1, 1, 1), List.of(-1, -23, 37, 1)), f.footprints);
            f.leases.verify(() -> CarpetRegionLease.runValue(any(), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class)), never());
            verify(f.reference).getStartForStructure(f.structure);
            verify(f.start).getPieces();
        }
    }

    @Test
    void theNativeBiomePrefixShortCircuitsBeforeAnyStructureReferencesOrTheirLoad() {
        try (var f = new Fixture()) {
            var wanted = Holder.direct(mock(Biome.class));
            var other = Holder.direct(mock(Biome.class));
            when(f.world.getBiome(any())).thenReturn(other);
            var predicate = LocationPredicate.Builder.inStructure(f.holder()).setBiomes(HolderSet.direct(wanted)).build();
            assertFalse(ScarpetLocationPredicates.matches(predicate, f.world, new Vec3(5, 4, 6)).join());
            assertEquals(1, f.footprints.size());
            verify(f.world, never()).getChunk(anyInt(), anyInt(), any(ChunkStatus.class));
        }
    }

    @Test
    void aChunkUnloadedDuringAdmissionStaysFalseAndCallerCancellationDoesNotFinishRealNativeReadChildren() {
        try (var f = new Fixture()) {
            f.disappear = true;
            var light = LocationPredicate.Builder.location().setLight(LightPredicate.Builder.light()).build();
            assertFalse(ScarpetLocationPredicates.matches(light, f.world, new Vec3(5, 4, 6)).join());
            verify(f.world, never()).getMaxLocalRawBrightness(any());
        }
        try (var f = new Fixture()) {
            f.nativeChild = new CompletableFuture<>();
            var predicate = LocationPredicate.Builder.inStructure(f.holder()).build();
            var caller = ScarpetLocationPredicates.matches(predicate, f.world, new Vec3(5, 4, 6));
            var idle = ScarpetNativeWork.whenIdle(f.server);
            assertFalse(caller.isDone());
            assertFalse(idle.isDone());
            assertTrue(caller.cancel(false));
            assertFalse(idle.isDone());
            f.nativeChild.complete(null);
            assertTrue(idle.isDone());
            assertTrue(caller.isCancelled());
        }
    }

    @Test
    void aNativeStructureClaimMayLoadItsOriginalLowerStatusOriginAndStillOwnsEveryActualReference() {
        try (var f = new Fixture()) {
            when(f.world.isLoaded(any())).thenReturn(false);
            var scalar = ScarpetLocationPredicates.withStructureReferences(f.world, new BlockPos(5, 4, 6), HolderSet.direct(f.holder()), () -> f.world.structureManager().getStructureAt(new BlockPos(5, 4, 6), HolderSet.direct(f.holder())).isValid() ? 37 : 0);
            // getStructureAt uses native boundingBox, unlike the piece predicate used by the prior tests.
            // This fixture returns its actual found StructureStart with a current native box.
            assertEquals(37, scalar.join());
            assertEquals(List.of(-1, -23, 37, 1), f.footprints.getLast());
            f.leases.verify(() -> CarpetRegionLease.runValue(any(), anyInt(), anyInt(), anyInt(), anyInt(), any(Function.class)), never());
        }
    }
}
