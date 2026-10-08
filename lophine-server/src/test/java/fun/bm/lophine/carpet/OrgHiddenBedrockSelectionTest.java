package fun.bm.lophine.carpet;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OrgHiddenBedrockSelectionTest {
    @Test
    void sourceRoundedCylinderBoundaryAndEmptyHeightArePreserved() {
        var shape = OrgHiddenBedrockSelection.cylinder(BlockPos.ZERO, 3, 2);
        assertTrue(shape.contains(new BlockPos(3, 1, 1)));
        assertFalse(shape.contains(new BlockPos(3, 0, 2)));
        assertFalse(shape.contains(new BlockPos(0, 2, 0)));
        assertFalse(OrgHiddenBedrockSelection.cylinder(BlockPos.ZERO, 3, 0).columns().hasNext());
    }

    @Test
    void sourceSingleKeyStoredBedrockRoundTripsItsActualSelectionAndFlags() {
        var shape = OrgHiddenBedrockSelection.cuboid(new BlockPos(2, 5, 9), new BlockPos(-3, -2, 1));
        var data = shape.write(true, true);
        var restored = OrgHiddenBedrockSelection.read(data);
        assertEquals(shape, restored);
        var object = new com.google.gson.JsonObject();
        object.add("bedrock", data);
        assertTrue(OrgHiddenPlayerActions.accepts(object));
        assertDoesNotThrow(() -> OrgHiddenPlayerActions.validate(object));
        assertTrue(data.get("ai").getAsBoolean());
        assertTrue(data.get("timed_material_recycling").getAsBoolean());
    }

    @Test
    void malformedSelectionFailsBeforeBotCreationAndUnknownRegionMatchesSourceStop() {
        var bad = JsonParser.parseString("{\"bedrock\":{\"region_type\":\"cylinder\",\"center\":[0,0],\"radius\":1,\"height\":2}}").getAsJsonObject();
        assertThrows(IndexOutOfBoundsException.class, () -> OrgHiddenPlayerActions.validate(bad));
        var unknown = JsonParser.parseString("{\"region_type\":\"future_region\"}").getAsJsonObject();
        assertNull(OrgHiddenBedrockSelection.read(unknown));
    }
}
