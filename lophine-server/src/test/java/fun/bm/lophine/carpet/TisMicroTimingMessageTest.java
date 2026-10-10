package fun.bm.lophine.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TisMicroTimingMessageTest {
    private static TisMicroTiming.Event event(String name) {
        return new TisMicroTiming.Event("minecraft:overworld", BlockPos.ZERO, "minecraft:stone", name, "data", DyeColor.RED,
                new TisMicroTiming.Phase("tile_tick", "block #1"), null, "");
    }

    @Test
    void emitsOnlyUsefulProceduresAndPreservesNestedOrderAndClosingResults() {
        var root = new TisMicroTiming.Node(null, event("emit_block_update"), false, true);
        root.closed = true;
        root.result = "true";
        var child = new TisMicroTiming.Node(root, event("block_state_change"), true, false);
        root.children.add(child);
        var dead = new TisMicroTiming.Node(null, event("unused_emission"), false, true);
        assertEquals(2, TisMicroTiming.flatten(List.of(root, dead)).size());
        assertEquals("true", TisMicroTiming.flatten(List.of(root)).getFirst().result());
        root.children.add(new TisMicroTiming.Node(root, event("schedule_tile_tick"), true, false));
        var lines = TisMicroTiming.flatten(List.of(root));
        assertEquals(List.of("emit_block_update", "block_state_change", "schedule_tile_tick", "emit_block_update"), lines.stream().map(line -> line.event().event()).toList());
        assertTrue(lines.getLast().end());
        assertEquals(1, lines.get(1).depth());
        assertEquals("true", lines.getLast().result());
    }

    @Test
    void preservesProceduresInAllModesAndGroupsOnlyRepeatedAtoms() {
        var duplicate = event("schedule_tile_tick");
        var procedure = new TisMicroTiming.Line(event("execute_tile_tick"), 0, true, false, null);
        var atom = new TisMicroTiming.Line(duplicate, 1, false, false, null);
        var lines = List.of(procedure, atom, atom, procedure, atom);
        assertEquals(6, TisMicroTiming.render(lines, "all", 1, "minecraft:overworld", "1").size());
        var merged = TisMicroTiming.render(lines, "merged", 1, "minecraft:overworld", "1");
        assertEquals(5, merged.size());
        assertTrue(merged.get(2).getString().endsWith(" +1x"));
        assertEquals(4, TisMicroTiming.render(lines, "unique", 1, "minecraft:overworld", "1").size());
    }

    @Test
    void handlesDeepRedstoneNestingWithoutRecursion() {
        var root = new TisMicroTiming.Node(null, event("root"), true, true);
        var cursor = root;
        for (int i = 0; i < 20000; ++i) {
            var child = new TisMicroTiming.Node(cursor, event("child"), true, true);
            cursor.children.add(child);
            cursor = child;
        }
        var lines = TisMicroTiming.flatten(List.of(root));
        assertEquals(20001, lines.size());
        assertEquals(20000, lines.getLast().depth());
    }
}
