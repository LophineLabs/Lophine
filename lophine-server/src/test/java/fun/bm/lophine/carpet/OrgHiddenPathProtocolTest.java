package fun.bm.lophine.carpet;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OrgHiddenPathProtocolTest {
    @Test void rawPacketUsesOriginalBigEndianIntsAndDoubles() throws Exception {
        var from = new Vec3(1.25, -2.5, 3.75); var to = new Vec3(8.5, 9.25, -10.75);
        byte[] bytes = OrgHiddenPathProtocol.encode(123456, List.of(from, to));
        var input = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals(123456, input.readInt()); assertEquals(2, input.readInt());
        for (Vec3 expected : List.of(from, to)) { assertEquals(expected.x, input.readDouble()); assertEquals(expected.y, input.readDouble()); assertEquals(expected.z, input.readDouble()); }
        assertEquals(0, input.available()); assertEquals(56, bytes.length);
    }
    @Test void unsubscribeCarriesSourceAllEntitiesClearIdAndNoNodes() throws Exception {
        var input = new DataInputStream(new ByteArrayInputStream(OrgHiddenPathProtocol.encode(-1, List.of())));
        assertEquals(-1, input.readInt()); assertEquals(0, input.readInt()); assertEquals(0, input.available());
    }
    @Test void collinearCompressionRetainsTurnsAndBacktracking() {
        var a = new Vec3(0, 0, 0); var b = new Vec3(1, 1, 1); var c = new Vec3(2, 2, 2); var d = new Vec3(2, 2, 3);
        assertEquals(List.of(a, c, d), OrgHiddenPathProtocol.compress(List.of(a, b, c, d)));
        assertEquals(List.of(a, c, b), OrgHiddenPathProtocol.compress(List.of(a, c, b)));
        // The source adds first and last independently, including a one-node path.
        assertEquals(List.of(a, a), OrgHiddenPathProtocol.compress(List.of(a)));
    }
    @Test void blockIterationMatchesSourceXThenYThenZWithoutMutablePositionReuse() {
        var iterator = new OrgHiddenPlant.Positions(-1, 2, 5, 0, 3, 6); var actual = new ArrayList<BlockPos>();
        iterator.forEachRemaining(actual::add);
        assertEquals(List.of(new BlockPos(-1,2,5),new BlockPos(0,2,5),new BlockPos(-1,3,5),new BlockPos(0,3,5),
            new BlockPos(-1,2,6),new BlockPos(0,2,6),new BlockPos(-1,3,6),new BlockPos(0,3,6)),actual);
        assertThrows(java.util.NoSuchElementException.class, iterator::next);
    }
    @Test void storedActionValidationUsesOriginalSingleKeyFormatWithoutCreatingAPlayer() {
        var plant = com.google.gson.JsonParser.parseString("{\"plant\":{}}").getAsJsonObject();
        assertTrue(OrgHiddenPlayerActions.accepts(plant)); assertDoesNotThrow(() -> OrgHiddenPlayerActions.validate(plant));
        var go = com.google.gson.JsonParser.parseString("{\"GOTO\":{}}").getAsJsonObject();
        assertTrue(OrgHiddenPlayerActions.accepts(go)); assertDoesNotThrow(() -> OrgHiddenPlayerActions.validate(go));
        var publicAction = com.google.gson.JsonParser.parseString("{\"fishing\":{}}").getAsJsonObject();
        assertFalse(OrgHiddenPlayerActions.accepts(publicAction));
        assertThrows(IllegalArgumentException.class, () -> OrgHiddenPlayerActions.validate(publicAction));
    }
    @Test void malformedStoredDataFailsBeforeNativeBotCreation() {
        var malformed = com.google.gson.JsonParser.parseString("{\"plant\":1}").getAsJsonObject();
        assertThrows(IllegalStateException.class, () -> OrgHiddenPlayerActions.validate(malformed));
        var ambiguous = com.google.gson.JsonParser.parseString("{\"plant\":{},\"goto\":{}}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> OrgHiddenPlayerActions.validate(ambiguous));
    }
}
