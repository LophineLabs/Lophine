package fun.bm.lophine.carpet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class CarpetLoggerStorageTest {
    @TempDir Path directory;
    @Test void preservesEmptyEntriesAndOptionsAcrossReplacement() throws Exception {
        Path file = directory.resolve("subscriptions.json");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var expected = Map.of(first, Map.of("movement", "non_zero:@e[distance=..5]", "counter", "red,blue"), second, Map.<String,String>of());
        CarpetLoggerStorage.writeFile(file, expected);
        assertEquals(expected, CarpetLoggerStorage.readFile(file));
        CarpetLoggerStorage.writeFile(file, Map.of(first, Map.of()));
        assertEquals(Map.of(first, Map.of()), CarpetLoggerStorage.readFile(file));
    }
    @Test void acceptsLegacyNullOptionAndRetainsMalformedFile() throws Exception {
        Path file = directory.resolve("subscriptions.json"); UUID id = UUID.randomUUID();
        Files.writeString(file, "{\"" + id + "\":{\"tps\":null}}");
        assertEquals(Map.of(id, Map.of("tps", "")), CarpetLoggerStorage.readFile(file));
        String broken = "{broken"; Files.writeString(file, broken);
        assertThrows(java.io.IOException.class, () -> CarpetLoggerStorage.readFile(file));
        assertEquals(broken, Files.readString(file));
    }
}
