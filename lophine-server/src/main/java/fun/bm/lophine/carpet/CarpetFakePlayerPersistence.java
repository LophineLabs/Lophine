// SPDX-License-Identifier: LGPL-3.0-or-later
// Adapted from Fabric Carpet revision f358000b175ddbcf1dd0bc59641c715fb0545664, carpet.patches.EntityPlayerMPFake.
package fun.bm.lophine.carpet;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.bot.ServerBot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Carpet keeps the online fake-player roster in the world, then respawns that roster after restart.
 * A killed player is removed from the roster and is not written back on shutdown.
 */
public final class CarpetFakePlayerPersistence {
    private static final String FILE = "carpet/fake_players.dat";

    public static void recordOnline(ServerBot bot) {
        if (!bot.carpetNativePlayer || bot.carpetShadow) return;
        MinecraftServer server = bot.level().getServer();
        try {
            CompoundTag roster = load(server);
            CompoundTag entry = new CompoundTag();
            entry.store("uuid", UUIDUtil.CODEC, bot.getUUID());
            entry.putString("name", bot.getGameProfile().name());
            roster.put(bot.getGameProfile().name().toLowerCase(Locale.ROOT), entry);
            save(server, roster);
        } catch (IOException failure) {
            MinecraftServer.LOGGER.warn("Cannot record Carpet fake player {}", bot.getGameProfile().name(), failure);
        }
    }

    public static void forget(ServerBot bot) {
        if (!bot.carpetNativePlayer) return;
        MinecraftServer server = bot.level().getServer();
        try {
            CompoundTag roster = load(server);
            if (roster.remove(bot.getGameProfile().name().toLowerCase(Locale.ROOT)) != null) save(server, roster);
        } catch (IOException failure) {
            MinecraftServer.LOGGER.warn("Cannot forget Carpet fake player {}", bot.getGameProfile().name(), failure);
        }
    }

    public static void respawn(MinecraftServer server, ServerLevel level) {
        if (level == null || carpet.script.external.ScarpetNativeWork.isDraining(server)) return;
        List<Entry> entries;
        try {
            entries = entries(load(server));
        } catch (IOException failure) {
            MinecraftServer.LOGGER.error("Cannot load Carpet fake players", failure);
            return;
        }
        for (Entry entry : entries) {
            if (server.getPlayerList().getPlayer(entry.id()) != null || server.getBotList().getBotByName(entry.name()) != null)
                continue;
            try {
                respawn(server, entry);
            } catch (RuntimeException failure) {
                MinecraftServer.LOGGER.error("Cannot respawn Carpet fake player {}", entry.name(), failure);
            }
        }
    }

    private static void respawn(MinecraftServer server, Entry entry) {
        Optional<CompoundTag> saved = server.getPlayerList().loadPlayerData(new NameAndId(entry.id(), entry.name()));
        if (saved.isEmpty()) return;
        CompoundTag tag = saved.get();
        ServerLevel level = world(server, tag);
        if (level == null) return;
        Vec3 position = tag.read("Pos", Vec3.CODEC).orElse(null);
        Vec2 rotation = tag.read("Rotation", Vec2.CODEC).orElse(Vec2.ZERO);
        if (position == null) return;
        GameType mode = tag.read("playerGameType", GameType.LEGACY_ID_CODEC).orElse(server.getDefaultGameType());
        boolean flying = tag.getCompound("abilities").map(abilities -> abilities.getBooleanOr("flying", false)).orElse(false);
        GameProfile profile = new GameProfile(entry.id(), entry.name());
        CompoundTag actions = tag.getCompound("carpetPlayerActions").orElse(null);
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        carpet.script.external.ScarpetNativeWork.record(completion);
        carpet.script.external.ScarpetNativeWork.trackNative(server, completion);
        CarpetPlayerBirths.track(server, completion, () -> {
        });
        ServerBot created = CarpetPlayerCommand.respawn(server.createCommandSourceStack(), profile, level, position, rotation, mode, flying, tag, actions, completion);
        server.getBotList().carpetPlacementCompletion(created).whenComplete((owned, failure) -> {
            if (failure == null) completion.complete(null);
            else completion.completeExceptionally(failure);
        });
    }

    private static ServerLevel world(MinecraftServer server, CompoundTag tag) {
        Optional<Long> most = tag.getLong("WorldUUIDMost");
        Optional<Long> least = tag.getLong("WorldUUIDLeast");
        if (most.isPresent() && least.isPresent()) {
            org.bukkit.World world = org.bukkit.Bukkit.getServer().getWorld(new UUID(most.get(), least.get()));
            if (world != null) return ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
        }
        return tag.read("Dimension", net.minecraft.world.level.Level.RESOURCE_KEY_CODEC)
                .map(server::getLevel).orElse(server.overworld());
    }

    private static List<Entry> entries(CompoundTag roster) {
        List<Entry> entries = new ArrayList<>();
        for (Tag value : roster.values()) {
            if (!(value instanceof CompoundTag entry)) continue;
            Optional<UUID> id = entry.read("uuid", UUIDUtil.CODEC);
            String name = entry.getStringOr("name", "");
            if (id.isPresent() && !name.isEmpty()) entries.add(new Entry(id.get(), name));
        }
        return entries;
    }

    private static CompoundTag load(MinecraftServer server) throws IOException {
        Path file = file(server);
        if (!Files.isRegularFile(file)) return new CompoundTag();
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }

    private static void save(MinecraftServer server, CompoundTag roster) throws IOException {
        Path file = file(server);
        Files.createDirectories(file.getParent());
        Path staging = Files.createTempFile(file.getParent(), "carpet-fake-players-", ".dat.tmp");
        try {
            NbtIo.writeCompressed(roster, staging);
            try {
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
    }

    private record Entry(UUID id, String name) {
    }
}
