package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgWorldFormatTest {
    @TempDir Path world;
    @BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }
    private Path legacy() { return world.resolve("carpetorgaddition"); }
    private Path modern() { return world.resolve("config/carpet-org-addition"); }
    private void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent()); Files.writeString(file, text);
    }
    private MinecraftServer server() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        return server;
    }
    private void oldRules() throws Exception { write(legacy().resolve("config.json"), "{\"rules\":{\"commandMail\":\"true\"}}"); }

    @Test void realFirstAccessAtomicallyMovesEntireDirectoryAndRenamesRules() throws Exception {
        oldRules();
        write(legacy().resolve("data_version.json"), "{\"data_version\":0}");
        write(legacy().resolve("unrecognized/deep/preserved.bin"), "preserve every old file");
        String rules = Files.readString(legacy().resolve("config.json"));
        assertEquals(modern().toAbsolutePath(), OrgWorldFormat.directory(world));
        assertFalse(Files.exists(legacy()));
        assertEquals(rules, Files.readString(modern().resolve("rules.json")));
        assertFalse(Files.exists(modern().resolve("config.json")));
        assertEquals(1, JsonParser.parseString(Files.readString(modern().resolve("data_version.json"))).getAsJsonObject().get("data_version").getAsInt());
        assertEquals("preserve every old file", Files.readString(modern().resolve("unrecognized/deep/preserved.bin")));
        assertFalse(Files.exists(modern().resolve("data_version.json.bak")));
        try (var files = Files.list(modern())) { assertFalse(files.anyMatch(file -> file.toString().endsWith(".tmp"))); }
    }

    @Test void existingEmptyModernDirectoryPreventsEveryLegacyMoveOrMetadataWrite() throws Exception {
        oldRules(); Files.createDirectories(modern());
        assertEquals(modern().toAbsolutePath(), OrgWorldFormat.directory(world));
        assertTrue(Files.isRegularFile(legacy().resolve("config.json")));
        assertFalse(Files.exists(modern().resolve("rules.json")));
        assertFalse(Files.exists(modern().resolve("data_version.json")));
    }

    @Test void existingModernFileAlsoPreventsOverwrite() throws Exception {
        oldRules(); write(modern(), "existing target"); OrgWorldFormat.directory(world);
        assertEquals("existing target", Files.readString(modern()));
        assertTrue(Files.isRegularFile(legacy().resolve("config.json")));
    }

    @Test void legacyFileIsNeverMistakenForADirectory() throws Exception {
        write(legacy(), "old path is a file"); OrgWorldFormat.directory(world);
        assertEquals("old path is a file", Files.readString(legacy()));
        assertFalse(Files.exists(modern()));
    }

    @Test void realMoveFailureConsumesLatchUntilServerClose() throws Exception {
        oldRules(); write(world.resolve("config"), "parent obstruction");
        MinecraftServer server = server(); OrgWorldFormat.directory(server);
        assertTrue(Files.isRegularFile(legacy().resolve("config.json")));
        Files.delete(world.resolve("config"));
        new OrgWaypointStore(world).names();
        assertTrue(Files.isDirectory(legacy())); assertFalse(Files.exists(modern()));
        OrgServerPermissions.close(server);
        OrgWorldFormat.directory(server());
        assertFalse(Files.exists(legacy())); assertTrue(Files.isRegularFile(modern().resolve("rules.json")));
    }

    @Test void realRenameCollisionKeepsBothRuleFilesAfterWholeDirectoryMove() throws Exception {
        oldRules(); write(legacy().resolve("rules.json"), "existing rules must survive");
        OrgWorldFormat.directory(world);
        assertFalse(Files.exists(legacy()));
        assertEquals("existing rules must survive", Files.readString(modern().resolve("rules.json")));
        assertTrue(Files.isRegularFile(modern().resolve("config.json")));
        assertEquals(1, JsonParser.parseString(Files.readString(modern().resolve("data_version.json"))).getAsJsonObject().get("data_version").getAsInt());
        Files.delete(modern().resolve("rules.json")); OrgWorldFormat.directory(world);
        assertTrue(Files.isRegularFile(modern().resolve("config.json"))); assertFalse(Files.exists(modern().resolve("rules.json")));
    }

    @Test void missingConfigFailureRetainsOtherMovedDataAndVersion() throws Exception {
        write(legacy().resolve("waypoint/unrelated.json"), "existing data"); OrgWorldFormat.directory(world);
        assertFalse(Files.exists(legacy()));
        assertEquals("existing data", Files.readString(modern().resolve("waypoint/unrelated.json")));
        assertTrue(Files.isRegularFile(modern().resolve("data_version.json")));
        assertFalse(Files.exists(modern().resolve("rules.json")));
    }

    @Test void actualWaypointStoreLoadsLegacyDirectoryBeforeItsFirstRead() throws Exception {
        oldRules(); write(legacy().resolve("waypoint/home.json"), "{\"x\":2,\"y\":63,\"z\":5,\"dimension\":\"minecraft:the_nether\"}");
        var store = new OrgWaypointStore(world);
        assertEquals(List.of("home"), store.names());
        assertEquals(new BlockPos(2, 63, 5), store.load("home").position()); assertFalse(Files.exists(legacy()));
    }

    @Test void actualProfileStoreLoadsLegacyDirectoryBeforeItsFirstRead() throws Exception {
        oldRules(); String profile = "{\"pos\":{\"x\":1,\"y\":64,\"z\":3},\"direction\":{\"yaw\":2,\"pitch\":3},\"dimension\":\"minecraft:overworld\",\"gamemode\":\"survival\"}";
        write(legacy().resolve("player_data/Alex.json"), profile);
        var store = new OrgPlayerProfileStore(world);
        assertEquals(List.of("Alex"), store.names()); var expected=JsonParser.parseString(profile).getAsJsonObject();expected.addProperty("data_version",5);assertEquals(expected, store.load("Alex"));assertEquals(profile,Files.readString(modern().resolve("player_data/Alex.json"))); assertFalse(Files.exists(legacy()));
    }

    @Test void actualFirstPermissionPredicateReadsMovedDenialBeforeReturning() throws Exception {
        oldRules(); write(legacy().resolve("permission.json"), "{\"data_version\":3,\"permission\":{\"finder.block\":\"false\"}}");
        MinecraftServer server = server(); var source = mock(net.minecraft.commands.CommandSourceStack.class);
        when(source.getServer()).thenReturn(server); when(source.permissions()).thenReturn(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
        try { assertFalse(OrgServerPermissions.allowed(source, "finder.block")); assertFalse(Files.exists(legacy())); }
        finally { OrgServerPermissions.close(server); }
    }

    @Test void actualSpectatorReadUsesMovedOriginAndExistingModernDirectoryHidesLegacy() throws Exception {
        oldRules(); UUID player = UUID.randomUUID();
        String origin = "{\"dimension\":\"minecraft:overworld\",\"x\":2,\"y\":64,\"z\":3,\"yaw\":4,\"pitch\":5}";
        write(legacy().resolve("spectator/" + player + ".json"), origin);
        var loaded = OrgSpectatorOrigins.read(server(), player);
        assertEquals(modern().resolve("spectator/" + player + ".json").toAbsolutePath(), loaded.file()); assertEquals(2, loaded.x());
        assertEquals(origin, Files.readString(loaded.file()));
        UUID other = UUID.randomUUID(); write(legacy().resolve("spectator/" + other + ".json"), origin);
        assertNull(OrgSpectatorOrigins.read(server(), other)); assertTrue(Files.isRegularFile(legacy().resolve("spectator/" + other + ".json")));
    }

    @Test void actualMailInitializationLoadsMovedParcelWithoutChangingItsBytes() throws Exception {
        oldRules(); Path file = legacy().resolve("express/7.nbt"); Files.createDirectories(file.getParent());
        CompoundTag parcel = new CompoundTag(); parcel.putInt("data_version", 3); parcel.putString("sender", "sender"); parcel.putString("recipient", "recipient");
        ListTag items = new ListTag(); CompoundTag item = new CompoundTag(); item.putString("id", "minecraft:diamond"); item.putInt("count", 1); items.add(item); parcel.put("items", items);
        NbtIo.write(parcel, file); byte[] bytes = Files.readAllBytes(file);
        assertEquals(List.of(7), OrgMailService.get(server()).numbers("recipient", OrgMailService.Operation.COLLECT).get(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(legacy())); assertArrayEquals(bytes, Files.readAllBytes(modern().resolve("express/7.nbt")));
    }

    @Test void concurrentDifferentStoresWaitForTheSameWholeDirectoryMigration() throws Exception {
        oldRules(); write(legacy().resolve("waypoint/home.json"), "{\"x\":1,\"y\":2,\"z\":3}"); write(legacy().resolve("player_data/Alex.json"), "{}");
        var waypoint = java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { return new OrgWaypointStore(world).names(); } catch (Exception failure) { throw new AssertionError(failure); } });
        var profiles = java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { return new OrgPlayerProfileStore(world.resolve(".")).names(); } catch (Exception failure) { throw new AssertionError(failure); } });
        assertEquals(List.of("home"), waypoint.get(5, TimeUnit.SECONDS)); assertEquals(List.of("Alex"), profiles.get(5, TimeUnit.SECONDS));
        assertTrue(Files.isRegularFile(modern().resolve("rules.json"))); assertFalse(Files.exists(legacy()));
    }
}
