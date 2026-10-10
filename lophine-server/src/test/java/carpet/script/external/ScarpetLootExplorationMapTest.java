package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetLootExplorationMapTest {
    @BeforeAll
    static void bootstrap() {
        ScarpetLootTablesTest.bootstrap();
    }

    final MinecraftServer server = mock(MinecraftServer.class);
    final ServerLevel world = mock(ServerLevel.class);
    final LootContext context = mock(LootContext.class);
    final WorldConfiguration config = mock(WorldConfiguration.class);
    final HolderSet<Structure> wanted = HolderSet.direct(Holder.direct(mock(Structure.class)));
    final Vec3 origin = new Vec3(3, 70, 11);
    final BlockPos original = BlockPos.containing(origin);

    @BeforeEach
    void setup() {
        when(world.getServer()).thenReturn(server);
        when(context.getLevel()).thenReturn(world);
        when(context.getOptional(LootContextParams.ORIGIN)).thenReturn(origin);
        config.environment = config.new Environment();
        config.environment.treasureMaps = config.environment.new TreasureMaps();
        when(world.paperConfig()).thenReturn(config);
    }

    private org.mockito.MockedStatic<TickThread> owners() {
        var ticks = mockStatic(TickThread.class);
        ticks.when(() -> TickThread.isTickThreadFor(any(ServerLevel.class), any(BlockPos.class))).thenReturn(true);
        return ticks;
    }

    @Test
    void actualSourceClaimWaitsTrueChildrenBeforeNativeLocateAndMapTail() {
        var manager = mock(StructureManager.class);
        when(world.structureManager()).thenReturn(manager);
        var start = mock(StructureStart.class);
        when(manager.getStructureAt(original, wanted)).thenReturn(start);
        when(start.isValid()).thenReturn(true);
        when(start.tryReference()).thenReturn(true);
        var child = new CompletableFuture<Void>();
        var order = new ArrayList<String>();
        doAnswer(call -> {
            order.add("native claim");
            ScarpetNativeWork.record(child);
            return null;
        }).when(manager).addReference(start);
        var found = new BlockPos(140, 70, -60);
        when(world.findNearestMapStructure(wanted, original, 50, true)).thenAnswer(call -> {
            order.add("native locate");
            return found;
        });
        var function = ExplorationMapFunction.makeExplorationMap(wanted).build();
        var item = new ItemStack(Items.STONE);
        try (var ticks = owners(); var refs = mockStatic(ScarpetLocationPredicates.class); var map = mockStatic(MapItem.class); var decorations = mockStatic(MapItemSavedData.class)) {
            refs.when(() -> ScarpetLocationPredicates.withStructureReferences(eq(world), eq(original), eq(wanted), any(Supplier.class))).thenAnswer(call -> {
                Supplier<Void> body = call.getArgument(3);
                return ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(null, body));
            });
            map.when(() -> MapItem.renderBiomePreviewMap(world, item)).thenAnswer(call -> {
                order.add("native immutable biome preview");
                return null;
            });
            var actual = ScarpetLootFunctions.apply(function, item, context);
            assertEquals(List.of("native claim"), order);
            assertFalse(actual.isDone());
            child.complete(null);
            assertSame(item, actual.join());
            assertEquals(List.of("native claim", "native locate", "native immutable biome preview"), order);
            verify(start, never()).canBeReferenced();
            verify(manager).addReference(start);
            map.verify(() -> MapItem.applyNewSavedData(world, item, found.getX(), found.getZ(), (byte) 2, true, true));
        }
    }

    @Test
    void sourceDisabledMapReturnsOriginalItemWithoutStructureLookup() {
        config.environment.treasureMaps.enabled = false;
        var function = ExplorationMapFunction.makeExplorationMap(wanted).build();
        var item = new ItemStack(Items.STONE);
        try (var ticks = owners(); var refs = mockStatic(ScarpetLocationPredicates.class)) {
            assertSame(item, ScarpetLootFunctions.apply(function, item, context).join());
            refs.verifyNoInteractions();
            verify(world, never()).findNearestMapStructure(any(HolderSet.class), any(), anyInt(), anyBoolean());
        }
    }

    @Test
    void sourceNoSkipBranchKeepsOriginalWorldAndPositionWithoutClaim() {
        var function = ExplorationMapFunction.makeExplorationMap(wanted).setSkipKnownStructures(false).build();
        var item = new ItemStack(Items.STONE);
        try (var ticks = owners(); var refs = mockStatic(ScarpetLocationPredicates.class)) {
            assertSame(item, ScarpetLootFunctions.apply(function, item, context).join());
            refs.verifyNoInteractions();
            verify(world).findNearestMapStructure(wanted, original, 50, false);
        }
    }
}