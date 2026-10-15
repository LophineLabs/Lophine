package fun.bm.lophine.carpet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.ServerBot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CarpetFakePlayerPersistenceTest {
    @TempDir
    Path world;

    @Test
    void anOnlineCarpetPlayerStaysOnTheRosterUntilItIsKilled() throws Exception {
        UUID id = UUID.randomUUID();
        ServerBot online = bot(id, "Steve", true);
        CarpetFakePlayerPersistence.recordOnline(online);
        CompoundTag saved = NbtIo.readCompressed(roster(), NbtAccounter.unlimitedHeap());
        assertTrue(saved.contains("steve"));

        ServerBot killed = bot(id, "Steve", false);
        CarpetFakePlayerPersistence.recordOnline(killed);
        CarpetFakePlayerPersistence.forget(killed);
        CompoundTag empty = NbtIo.readCompressed(roster(), NbtAccounter.unlimitedHeap());
        assertFalse(empty.contains("steve"));
    }

    private ServerBot bot(UUID id, String name, boolean resume) {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        var level = mock(net.minecraft.server.level.ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        ServerBot bot = mock(ServerBot.class);
        when(bot.level()).thenReturn(level);
        when(bot.getUUID()).thenReturn(id);
        when(bot.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(id, name));
        bot.carpetNativePlayer = true;
        bot.resume = resume;
        return bot;
    }

    private Path roster() {
        return world.resolve("carpet/fake_players.dat");
    }
}
