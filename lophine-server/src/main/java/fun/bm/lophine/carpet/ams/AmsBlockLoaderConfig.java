// SPDX-License-Identifier: LGPL-3.0-or-later
// Configuration schema and matching semantics from Carpet AMS Addition 66b8c3b0.
package fun.bm.lophine.carpet.ams;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.brigadier.StringReader;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class AmsBlockLoaderConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(AmsBlockLoaderConfig.class);
    static final List<String> RANGES = List.of("1x1", "3x3", "5x5", "7x7", "9x9");

    private static volatile Map<Type, Config> configs = defaults();
    private static MinecraftServer loaded;
    private static boolean unreadable;

    private AmsBlockLoaderConfig() {}

    enum Type {
        NOTEBLOCK, PISTON, BELL;

        String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        String permission() {
            return switch (this) {
                case NOTEBLOCK -> GeneralCompatConfig.commandNoteblockChunkloader;
                case PISTON -> GeneralCompatConfig.commandPistonChunkloader;
                case BELL -> GeneralCompatConfig.commandBellChunkloader;
            };
        }

        TicketType<?> ticket() {
            return switch (this) {
                case NOTEBLOCK -> TicketType.AMS_CUSTOM_NOTE_LOADER;
                case PISTON -> TicketType.AMS_CUSTOM_PISTON_LOADER;
                case BELL -> TicketType.AMS_CUSTOM_BELL_LOADER;
            };
        }
    }

    static final class Config {
        boolean enabled;
        int time = 300;
        String range = "3x3";
        int nextId = 1;
        Map<String, Group> groups = new LinkedHashMap<>(Map.of("default", new Group()));

        int ticketRadius() {
            return 2 + RANGES.indexOf(range);
        }

        void validate() {
            if (time < 1 || time > 300 || !RANGES.contains(range) || groups == null)
                throw new IllegalArgumentException("Invalid chunk loader settings");
            nextId = 1;
            groups.forEach((name, group) -> {
                if (!name.matches("[a-zA-Z0-9_-]{1,32}") || group == null || group.conditions == null)
                    throw new IllegalArgumentException("Invalid loader group " + name);
                for (Condition condition : group.conditions) {
                    if (condition == null || condition.block == null || condition.block.isBlank()
                            || condition.dx < -32 || condition.dx > 32 || condition.dy < -32 || condition.dy > 32
                            || condition.dz < -32 || condition.dz > 32) throw new IllegalArgumentException("Invalid loader condition");
                    condition.id = nextId++;
                }
            });
        }
    }

    static final class Group {
        boolean enabled = true;
        boolean any;
        List<Condition> conditions = new ArrayList<>();

        CompletableFuture<Result> evaluate(ServerLevel world, BlockPos origin) {
            CompletableFuture<Result> result = CompletableFuture.completedFuture(any ? Result.FAIL : Result.MATCH);
            for (Condition condition : conditions) {
                result = AmsNativeCommandEffects.then(result, previous -> decisive(previous, any)
                        ? CompletableFuture.completedFuture(previous)
                        : AmsNativeCommandEffects.then(condition.evaluate(world, origin),
                                value -> CompletableFuture.completedFuture(combine(previous, value, any))));
            }
            return result;
        }
    }

    enum Result { MATCH, FAIL, UNKNOWN }

    static boolean decisive(Result result, boolean any) {
        return result == (any ? Result.MATCH : Result.FAIL);
    }

    static Result combine(Result first, Result second, boolean any) {
        if (decisive(first, any)) return first;
        if (decisive(second, any)) return second;
        return first == Result.UNKNOWN || second == Result.UNKNOWN ? Result.UNKNOWN : second;
    }

    static final class Condition {
        int id, dx, dy, dz;
        boolean inverted;
        String block;
        private transient volatile Parsed parsed;

        Condition(int dx, int dy, int dz, boolean inverted, String block) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.inverted = inverted;
            this.block = block;
        }

        CompletableFuture<Result> evaluate(ServerLevel world, BlockPos origin) {
            BlockPos target = origin.offset(dx, dy, dz);
            if (world.isOutsideBuildHeight(target) || world.getChunkIfLoadedImmediately(target.getX() >> 4, target.getZ() >> 4) == null)
                return CompletableFuture.completedFuture(Result.UNKNOWN);
            return AmsNativeCommandEffects.world(world, target, () -> {
                if (!TickThread.isTickThreadFor(world, target) || world.getBlockStateIfLoaded(target) == null)
                    return Result.UNKNOWN;
                var server = world.getServer();
                var current = parsed;
                if (current == null || current.commands() != server.getCommands()) {
                    BlockPredicateArgument.Result predicate = null;
                    try {
                        var access = CommandBuildContext.simple(server.registryAccess(), server.getWorldData().enabledFeatures());
                        var reader = new StringReader(block);
                        var candidate = BlockPredicateArgument.blockPredicate(access).parse(reader);
                        if (reader.canRead()) throw new IllegalArgumentException("Trailing block predicate input");
                        predicate = candidate;
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException | IllegalArgumentException failure) {
                        LOGGER.warn("Invalid AMS block loader predicate {}", block, failure);
                    }
                    parsed = current = new Parsed(server.getCommands(), predicate);
                }
                return current.predicate() == null ? Result.UNKNOWN
                        : current.predicate().test(new BlockInWorld(world, target, false)) != inverted ? Result.MATCH : Result.FAIL;
            });
        }

        private record Parsed(Commands commands, BlockPredicateArgument.Result predicate) {}
    }

    static Config get(Type type) {
        return configs.get(type);
    }

    private static Map<Type, Config> defaults() {
        var map = new EnumMap<Type, Config>(Type.class);
        for (Type type : Type.values()) map.put(type, new Config());
        return Map.copyOf(map);
    }

    static synchronized void load(MinecraftServer server) {
        if (loaded == server) return;
        loaded = server;
        unreadable = false;
        configs = defaults();
        var file = AmsManagementSettings.file(server, "block_chunkloader");
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            var next = new EnumMap<Type, Config>(configs);
            for (Type type : Type.values()) {
                if (!json.has(type.id())) continue;
                Config config = GSON.fromJson(json.get(type.id()), Config.class);
                config.validate();
                next.put(type, config);
            }
            configs = Map.copyOf(next);
        } catch (IOException | RuntimeException failure) {
            unreadable = true;
            LOGGER.error("Cannot read AMS block chunk loader settings from {}", file, failure);
        }
    }

    static synchronized void edit(MinecraftServer server, Type type, Consumer<Config> edit) {
        load(server);
        if (unreadable) throw new IllegalStateException("The existing block_chunkloader.json could not be read");
        Config replacement = GSON.fromJson(GSON.toJsonTree(get(type)), Config.class);
        edit.accept(replacement);
        replacement.validate();
        var updated = new EnumMap<Type, Config>(configs);
        updated.put(type, replacement);
        var payload = new LinkedHashMap<String, Config>();
        for (Type key : Type.values()) payload.put(key.id(), updated.get(key));
        var file = AmsManagementSettings.file(server, "block_chunkloader");
        try {
            Files.createDirectories(file.getParent());
            var temporary = Files.createTempFile(file.getParent(), "block_chunkloader-", ".tmp");
            try {
                Files.writeString(temporary, GSON.toJson(payload));
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException failure) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            configs = Map.copyOf(updated);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException("Could not store block loader settings", failure);
        }
    }

    static CompletableFuture<Boolean> matches(Config config, ServerLevel world, BlockPos origin) {
        var result = CompletableFuture.completedFuture(true);
        for (Group group : config.groups.values()) {
            if (group.enabled && !group.conditions.isEmpty()) {
                result = AmsNativeCommandEffects.then(result, matched -> matched
                        ? group.evaluate(world, origin).thenApply(value -> value == Result.MATCH) : CompletableFuture.completedFuture(false));
            }
        }
        return result;
    }

    static boolean trigger(Type type, ServerLevel world, BlockPos origin, BlockPos center) {
        if ("false".equals(type.permission())) return false;
        Config config = get(type);
        if (config.enabled) {
            var work = AmsNativeCommandEffects.then(matches(config, world, origin), matched -> {
                if (!matched) return CompletableFuture.completedFuture(null);
                return AmsNativeCommandEffects.world(world, origin, () -> {
                    if (get(type) == config && !"false".equals(type.permission()))
                        world.getChunkSource().addTicketWithRadius(type.ticket(), ChunkPos.containing(center), config.ticketRadius());
                    return (Void) null;
                });
            });
            AmsNativeCommandEffects.receipt(work);
        }
        return true;
    }

    public static long customTimeout(TicketType<?> type) {
        if (type == TicketType.AMS_CUSTOM_NOTE_LOADER) return get(Type.NOTEBLOCK).time;
        if (type == TicketType.AMS_CUSTOM_PISTON_LOADER) return get(Type.PISTON).time;
        if (type == TicketType.AMS_CUSTOM_BELL_LOADER) return get(Type.BELL).time;
        return 300;
    }
}
