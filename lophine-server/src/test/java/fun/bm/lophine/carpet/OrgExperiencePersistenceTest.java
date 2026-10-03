package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the actual coordinator against storage that writes successfully but refuses selected readbacks. */
class OrgExperiencePersistenceTest {
    @TempDir Path directory;
    private static final NamespacedKey DEBIT = new NamespacedKey("lophine", "carpet_org_xp_debit");
    private static final NamespacedKey CREDIT = new NamespacedKey("lophine", "carpet_org_xp_credit");
    private static final NamespacedKey HELD = new NamespacedKey("lophine", "carpet_org_xp_credit_escrow");

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void anUnreadableDebitNeverRestoresPaidXpOrAdvancesOnALiveMarkerAlone() throws Exception {
        Fixture fixture = new Fixture(directory);
        Actor source = fixture.actor(UUID.randomUUID(), 10), target = fixture.actor(UUID.randomUUID(), 5);
        fixture.unreadable.addAll(List.of(1, 2));
        assertThrows(InvocationTargetException.class, () -> fixture.begin(source, target, 17));
        assertEquals(BigInteger.valueOf(143), OrgExperienceAmounts.read(source.player));
        assertNotNull(source.data.get(DEBIT));
        OrgExperienceAmounts.write(source.player, BigInteger.valueOf(148)); // Legitimate later XP gain.
        fixture.process(source);
        assertTrue(fixture.ledger().contains("\"created\""));
        assertEquals(BigInteger.valueOf(148), OrgExperienceAmounts.read(source.player));
        fixture.process(source);
        assertTrue(fixture.ledger().contains("\"debited\""));
        assertEquals(BigInteger.valueOf(148), OrgExperienceAmounts.read(source.player));
    }

    @Test void unreadableHeldCustodyAndReleaseCannotExposeIncomingXpToGameplay() throws Exception {
        Fixture fixture = new Fixture(directory);
        Actor source = fixture.actor(UUID.randomUUID(), 10), target = fixture.actor(UUID.randomUUID(), 5);
        fixture.begin(source, target, 17);
        fixture.unreadable.addAll(List.of(2, 4));
        fixture.process(target); // Deposit save succeeded, readback failed.
        assertEquals(BigInteger.valueOf(55), OrgExperienceAmounts.read(target.player));
        assertNotNull(target.data.get(HELD));
        fixture.process(target); // Held custody verified; release wrote 72 XP but readback failed.
        assertEquals(BigInteger.valueOf(55), OrgExperienceAmounts.read(target.player));
        assertNotNull(target.data.get(HELD));
        assertTrue(target.data.get(CREDIT).endsWith(":held"));

        CompoundTag durableRelease = fixture.saved.get(target.id);
        assertEquals(6, durableRelease.getIntOr("XpLevel", -1));
        assertFalse(durableRelease.getCompoundOrEmpty("BukkitValues").contains(HELD.toString()));
        Actor restarted = fixture.actor(target.id, durableRelease);
        fixture.restartCoordinator();
        fixture.process(restarted);
        assertEquals(BigInteger.valueOf(72), OrgExperienceAmounts.read(restarted.player));
        assertEquals("[]", fixture.ledger().trim());
    }

    @Test void aReleaseThatNeverReachedDiskResumesFromTheHeldReceiptExactlyOnce() throws Exception {
        Fixture fixture = new Fixture(directory);
        Actor source = fixture.actor(UUID.randomUUID(), 10), target = fixture.actor(UUID.randomUUID(), 5);
        fixture.begin(source, target, 17);
        fixture.unreadable.addAll(List.of(2, 4));
        fixture.discardedWrites.add(4);
        fixture.process(target); fixture.process(target);
        CompoundTag durableHeld = fixture.saved.get(target.id);
        assertTrue(durableHeld.getCompoundOrEmpty("BukkitValues").contains(HELD.toString()));
        Actor restarted = fixture.actor(target.id, durableHeld);
        fixture.restartCoordinator(); fixture.process(restarted);
        assertEquals(BigInteger.valueOf(72), OrgExperienceAmounts.read(restarted.player));
        assertNull(restarted.data.get(HELD));
        assertEquals("[]", fixture.ledger().trim());
    }

    @Test void legitimateXpEarnedDuringHeldCustodyIsPreservedOnRelease() throws Exception {
        Fixture fixture = new Fixture(directory);
        Actor source = fixture.actor(UUID.randomUUID(), 10), target = fixture.actor(UUID.randomUUID(), 5);
        fixture.begin(source, target, 17); fixture.unreadable.add(2);
        fixture.process(target);
        OrgExperienceAmounts.write(target.player, BigInteger.valueOf(65));
        fixture.process(target);
        assertEquals(BigInteger.valueOf(82), OrgExperienceAmounts.read(target.player));
        assertEquals("[]", fixture.ledger().trim());
    }

    private record Actor(UUID id, ServerPlayer player, Map<NamespacedKey, String> data) {}

    private static final class Fixture {
        final Path directory;
        final MinecraftServer server = mock(MinecraftServer.class);
        final PlayerDataStorage storage = mock(PlayerDataStorage.class);
        final Map<UUID, CompoundTag> saved = new HashMap<>();
        final Map<UUID, Actor> actors = new HashMap<>();
        final Set<Integer> unreadable = new HashSet<>(), discardedWrites = new HashSet<>();
        int writes, reads;
        Object coordinator;
        final Method begin, process;
        final Constructor<?> constructor;

        Fixture(Path directory) throws Exception {
            this.directory = directory;
            PlayerList players = mock(PlayerList.class);
            var storageField = PlayerList.class.getField("playerIo"); storageField.setAccessible(true); storageField.set(players, storage);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getPlayers()).thenReturn(List.of()); // Explicitly run actors serially, as a one-worker scheduler does.
            when(server.getWorldPath(LevelResource.ROOT)).thenReturn(directory);
            doAnswer(call -> {
                writes++;
                ServerPlayer player = (ServerPlayer) call.getArgument(0);
                if (!discardedWrites.contains(writes)) saved.put(player.getUUID(), snapshot(actors.get(player.getUUID())));
                return null;
            }).when(storage).save(any(Player.class));
            when(storage.load(any(NameAndId.class))).thenAnswer(call -> {
                reads++; NameAndId identity = call.getArgument(0);
                return unreadable.contains(reads) ? Optional.empty() : Optional.ofNullable(saved.get(identity.id())).map(CompoundTag::copy);
            });
            Class<?> type = Class.forName(OrgExperienceTransfers.class.getName() + "$Coordinator");
            constructor = type.getDeclaredConstructor(MinecraftServer.class); constructor.setAccessible(true);
            begin = type.getDeclaredMethod("begin", ServerPlayer.class, UUID.class, BigInteger.class); begin.setAccessible(true);
            process = type.getDeclaredMethod("process", ServerPlayer.class); process.setAccessible(true);
            restartCoordinator();
        }

        void restartCoordinator() throws Exception { coordinator = constructor.newInstance(server); }
        void begin(Actor source, Actor target, int amount) throws Exception { begin.invoke(coordinator, source.player, target.id, BigInteger.valueOf(amount)); }
        void process(Actor actor) throws Exception { process.invoke(coordinator, actor.player); }
        String ledger() throws Exception { return Files.readString(directory.resolve("carpet-org-experience-transfers.json")); }

        Actor actor(UUID id, int level) {
            ServerPlayer player = mock(ServerPlayer.class); CraftPlayer bukkit = mock(CraftPlayer.class);
            org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer pdc = mock(org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer.class);
            Map<NamespacedKey, String> data = new HashMap<>();
            when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> data.get(call.getArgument(0)));
            doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
            doAnswer(call -> { data.remove(call.getArgument(0)); return null; }).when(pdc).remove(any(NamespacedKey.class));
            when(bukkit.getPersistentDataContainer()).thenReturn(pdc); when(player.getBukkitEntity()).thenReturn(bukkit);
            when(player.getUUID()).thenReturn(id); when(player.nameAndId()).thenReturn(new NameAndId(id, "xp_test"));
            player.connection = mock(ServerGamePacketListenerImpl.class);
            ServerLevel world = mock(ServerLevel.class); when(player.level()).thenReturn(world); when(world.getServer()).thenReturn(server);
            when(player.getXpNeededForNextLevel()).thenAnswer(call -> player.experienceLevel >= 30 ? 9 * player.experienceLevel - 158 : player.experienceLevel >= 15 ? 5 * player.experienceLevel - 38 : 2 * player.experienceLevel + 7);
            doAnswer(call -> { player.experienceLevel = call.getArgument(0); player.experienceProgress = 0; return null; }).when(player).setExperienceLevels(anyInt());
            doAnswer(call -> { int points = call.getArgument(0); player.experienceProgress = (float) points / player.getXpNeededForNextLevel(); return null; }).when(player).setExperiencePoints(anyInt());
            player.experienceLevel = level; player.experienceProgress = 0; player.totalExperience = OrgExperienceAmounts.forLevel(level).intValueExact();
            Actor result = new Actor(id, player, data); actors.put(id, result); return result;
        }

        Actor actor(UUID id, CompoundTag loaded) {
            Actor actor = actor(id, loaded.getIntOr("XpLevel", 0));
            actor.player.experienceProgress = loaded.getFloatOr("XpP", 0); actor.player.totalExperience = loaded.getIntOr("XpTotal", 0);
            for (var entry : loaded.getCompoundOrEmpty("BukkitValues").entrySet()) loaded.getCompoundOrEmpty("BukkitValues").getString(entry.getKey()).ifPresent(value -> actor.data.put(NamespacedKey.fromString(entry.getKey()), value));
            return actor;
        }

        CompoundTag snapshot(Actor actor) {
            CompoundTag tag = new CompoundTag(), values = new CompoundTag();
            tag.putInt("XpLevel", actor.player.experienceLevel); tag.putFloat("XpP", actor.player.experienceProgress); tag.putInt("XpTotal", actor.player.totalExperience);
            actor.data.forEach((key, value) -> values.putString(key.toString(), value)); tag.put("BukkitValues", values); return tag;
        }
    }
}
