package fun.bm.lophine.carpet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OrgProfileVersionMigrationTest {
    @TempDir
    Path world;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    JsonObject input(int version) {
        JsonObject p = JsonParser.parseString("{\"pos\":{\"x\":1,\"y\":64,\"z\":2},\"direction\":{\"yaw\":3,\"pitch\":4},\"dimension\":\"minecraft:overworld\",\"gamemode\":\"survival\",\"annotation\":\"retain\",\"group\":[\"g\"],\"autologin\":true,\"_lophine_uuid\":\"preserve\"}").getAsJsonObject();
        if (version >= 0) p.addProperty("data_version", version);
        return p;
    }

    Path write(String name, JsonObject p) throws Exception {
        Path f = world.resolve("config/carpet-org-addition/player_data/" + name + ".json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, p.toString());
        return f;
    }

    @Test
    void officialVersionZeroScriptAndStartupMigrateOnReadWithoutRewritingOldFile() throws Exception {
        JsonObject p = input(-1);
        p.add("script_action", JsonParser.parseString("{\"fill\":{\"allItem\":false,\"item\":\"diamond\",\"ignored\":true},\"clean\":{\"allItem\":true},\"inventory_crafting\":{\"0\":\"diamond\"},\"sorting\":{},\"planting\":{}}").getAsJsonObject());
        p.add("hand_action", JsonParser.parseString("{\"use\":{\"interval\":7,\"continuous\":false}}").getAsJsonObject());
        p.add("startup", JsonParser.parseString("[{\"action\":\"use\",\"delay\":8,\"condition\":\"keep\"}]").getAsJsonArray());
        Path file = write("old", p);
        String before = Files.readString(file);
        var store = new OrgPlayerProfileStore(world);
        JsonObject result = store.load("old");
        assertEquals(before, Files.readString(file));
        assertEquals(5, result.get("data_version").getAsInt());
        assertFalse(result.has("hand_action"));
        assertFalse(result.has("startup"));
        assertEquals(7, result.getAsJsonObject("simple_action").getAsJsonObject("use").get("interval").getAsInt());
        var startup = result.getAsJsonArray("startup_action").get(0).getAsJsonObject();
        assertEquals("simple", startup.getAsJsonObject("function").get("type").getAsString());
        assertEquals("use", startup.getAsJsonObject("function").get("value").getAsString());
        assertEquals("keep", startup.get("condition").getAsString());
        var action = result.getAsJsonObject("script_action");
        assertEquals("minecraft:diamond", action.getAsJsonObject("fill_the_container").get("item").getAsString());
        assertTrue(action.getAsJsonObject("fill_the_container").get("dropOther").getAsBoolean());
        assertFalse(action.getAsJsonObject("fill_the_container").has("ignored"));
        assertEquals("*", action.getAsJsonObject("empty_the_container").get("item").getAsString());
        assertTrue(action.has("inventory_craft"));
        assertTrue(action.has("categorize"));
        assertTrue(action.has("plant"));
        assertEquals("preserve", result.get("_lophine_uuid").getAsString());
        store.modify("old", same -> same);
        assertEquals(5, JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("data_version").getAsInt());
    }

    @Test
    void everyOfficialPreFiveVersionRenamesAndPreservesProfileMetadata() throws Exception {
        for (int version = 0; version <= 5; version++) {
            JsonObject p = input(version);
            p.add("script_action", JsonParser.parseString(version < 5 ? "{\"rename\":{\"item\":\"diamond\",\"new_name\":\"new\",\"extra\":9}}" : "{\"rename\":{\"item\":\"diamond\",\"name\":\"new\",\"extra\":9}}").getAsJsonObject());
            write("v" + version, p);
            var result = new OrgPlayerProfileStore(world).load("v" + version);
            assertEquals(5, result.get("data_version").getAsInt());
            assertEquals("new", result.getAsJsonObject("script_action").getAsJsonObject("rename").get("name").getAsString());
            assertEquals(9, result.getAsJsonObject("script_action").getAsJsonObject("rename").get("extra").getAsInt());
            assertEquals("retain", result.get("annotation").getAsString());
            assertTrue(result.get("autologin").getAsBoolean());
            assertEquals("g", result.getAsJsonArray("group").get(0).getAsString());
        }
    }

    @Test
    void invalidVersionOrOldActionDoesNotOverwriteTheRealFile() throws Exception {
        var store = new OrgPlayerProfileStore(world);
        JsonObject future = input(6);
        Path file = write("future", future);
        assertThrows(java.io.IOException.class, () -> store.load("future"));
        assertEquals(future.toString(), Files.readString(file));
        JsonObject invalid = input(0);
        invalid.add("script_action", JsonParser.parseString("{\"clean\":{\"item\":\"illegal:id:format\"}}").getAsJsonObject());
        Path bad = write("bad", invalid);
        assertThrows(java.io.IOException.class, () -> store.modify("bad", same -> same));
        assertEquals(invalid.toString(), Files.readString(bad));
    }
}
