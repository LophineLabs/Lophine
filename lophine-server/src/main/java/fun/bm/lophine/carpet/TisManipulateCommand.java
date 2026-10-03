// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.TickPriority;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class TisManipulateCommand {
    private TisManipulateCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(literal("manipulate")
            .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandManipulate))
            .then(TisManipulateBlocks.tree())
            .then(entityTree(context))
            .then(serverTree())
            .then(containerTree(context))
            .then(TisManipulateChunks.tree()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> entityTree(CommandBuildContext context) {
        return literal("entity").then(argument("target", EntityArgument.entities())
            .then(literal("rename")
                .then(literal("clear").executes(c -> entities(c.getSource(), EntityArgument.getEntities(c, "target"),
                    "clear custom names", entity -> entity.setCustomName(null))))
                .then(argument("name", ComponentArgument.textComponent(context)).executes(c -> {
                    Component name = ComponentArgument.getResolvedComponent(c, "name");
                    return entities(c.getSource(), EntityArgument.getEntities(c, "target"), "rename", entity -> entity.setCustomName(name));
                })))
            .then(literal("persistent").executes(c -> entities(c.getSource(), EntityArgument.getEntities(c, "target").stream().filter(entity -> entity instanceof Mob).toList(),
                    "query persistence", entity -> { if (entity instanceof Mob mob) feedback(c.getSource(), entity.getName().getString()
                        + ": persistent=" + mob.isPersistenceRequired()); }))
                .then(argument("state", BoolArgumentType.bool()).executes(c -> {
                    boolean state = BoolArgumentType.getBool(c, "state");
                    return entities(c.getSource(), EntityArgument.getEntities(c, "target").stream().filter(entity -> entity instanceof Mob).toList(), "set persistence", entity -> {
                        if (entity instanceof Mob mob) mob.persistenceRequired = state;
                    });
                })))
            .then(literal("mount").then(argument("vehicle", EntityArgument.entity()).executes(c -> {
                Entity vehicle = EntityArgument.getEntity(c, "vehicle");
                var targets = EntityArgument.getEntities(c, "target");
                return TisCommandContinuations.complete(c.getSource(), targets.size(), () -> {
                    List<CompletableFuture<Integer>> actuals = new ArrayList<>();
                    for (Entity passenger : targets) if (!passenger.equals(vehicle)) actuals.add(mount(c.getSource(), passenger, vehicle, 8));
                    return TisCommandContinuations.total(actuals);
                }, "mount", () -> { });
            })))
            .then(literal("dismount").executes(c -> dismount(c.getSource(), EntityArgument.getEntities(c, "target"))))
            .then(literal("velocity").executes(c -> entities(c.getSource(), EntityArgument.getEntities(c, "target"),
                    "query velocity", entity -> feedback(c.getSource(), entity.getName().getString() + ": " + entity.getDeltaMovement())))
                .then(literal("add").then(argument("x", DoubleArgumentType.doubleArg()).then(argument("y", DoubleArgumentType.doubleArg())
                    .then(argument("z", DoubleArgumentType.doubleArg()).executes(c -> {
                        Vec3 value = new Vec3(DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "y"), DoubleArgumentType.getDouble(c, "z"));
                        return entities(c.getSource(), EntityArgument.getEntities(c, "target"), "add velocity", entity -> entity.push(value.x, value.y, value.z));
                    })))))
                .then(literal("set").then(argument("x", StringArgumentType.word()).then(argument("y", StringArgumentType.word())
                    .then(argument("z", StringArgumentType.word()).executes(c -> {
                        Vec3 value = new Vec3(number(StringArgumentType.getString(c, "x")), number(StringArgumentType.getString(c, "y")),
                            number(StringArgumentType.getString(c, "z")));
                        return entities(c.getSource(), EntityArgument.getEntities(c, "target"), "set velocity", entity -> entity.setDeltaMovement(value));
                    })))))));
    }

    private static double number(String text) throws CommandSyntaxException {
        String value = switch (text.toLowerCase(Locale.ROOT)) {
            case "nan" -> "NaN";
            case "inf", "infinity" -> "Infinity";
            case "-inf", "-infinity" -> "-Infinity";
            default -> text;
        };
        try { return Double.parseDouble(value); }
        catch (NumberFormatException bad) { throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerInvalidDouble().create(text); }
    }

    private static int entities(CommandSourceStack source, Collection<? extends Entity> targets, String action, Consumer<Entity> task) {
        return TisCommandContinuations.complete(source, targets.size(), () -> {
            List<CompletableFuture<Integer>> actuals = new ArrayList<>();
            for (Entity entity : targets) actuals.add(TisCommandContinuations.entity(source, entity, () ->
                TisCommandContinuations.phase(entity, () -> {
                    if (entity.isRemoved()) throw new IllegalStateException("Target retired before " + action);
                    task.accept(entity); return 1;
                })));
            return TisCommandContinuations.total(actuals);
        }, action, () -> { });
    }

    private static int dismount(CommandSourceStack source, Collection<? extends Entity> targets) {
        return TisCommandContinuations.complete(source, targets.size(), () -> {
            List<CompletableFuture<Integer>> actuals = new ArrayList<>();
            for (Entity rider : targets) actuals.add(TisCommandContinuations.entity(source, rider, () -> {
                if (!rider.isPassenger()) return CompletableFuture.completedFuture(0);
                return TisCommandContinuations.then(carpet.script.external.ScarpetNativeRelationships.stopRiding(rider, false),
                    ignored -> CompletableFuture.completedFuture(1));
            }));
            return TisCommandContinuations.total(actuals);
        }, "dismount", () -> { });
    }

    private record MountPosition(Entity entity, ServerLevel world, Vec3 position, Entity vehicle, List<Entity> passengers) { }

    private static CompletableFuture<Integer> mount(CommandSourceStack source, Entity passenger, Entity vehicle, int attempts) {
        if (attempts == 0) return CompletableFuture.failedFuture(new IllegalStateException("Mount participants kept changing owner"));
        return TisCommandContinuations.entity(source, passenger, () -> {
            var queue = new java.util.ArrayDeque<Entity>(); queue.add(passenger); queue.add(vehicle);
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Entity, Boolean>());
            return TisCommandContinuations.then(mountFootprint(queue, seen, new ArrayList<>()), footprint -> {
                ServerLevel world = footprint.getFirst().world();
                // Native startRiding rejects a passenger and destination in different dimensions.
                var destination = footprint.stream().filter(value -> value.entity() == vehicle).findFirst().orElseThrow();
                if (destination.world() != world) return CompletableFuture.completedFuture(0);
                for (var participant : footprint) if (participant.world() != world)
                    return CompletableFuture.failedFuture(new IllegalStateException("Native mount relationship spans dimensions"));
                int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (var participant : footprint) {
                    int x = net.minecraft.util.Mth.floor(participant.position().x) >> 4, z = net.minecraft.util.Mth.floor(participant.position().z) >> 4;
                    minX = Math.min(minX, x); minZ = Math.min(minZ, z); maxX = Math.max(maxX, x); maxZ = Math.max(maxZ, z);
                }
                final int firstX = minX, firstZ = minZ, lastX = maxX, lastZ = maxZ;
                return admitMountPlayers(footprint, 0, () -> TisCommandContinuations.loadedArea(source, world,
                    firstX, firstZ, lastX, lastZ, () -> TisCommandContinuations.phase(passenger, () -> {
                        for (var participant : footprint) {
                            Entity actual = participant.entity();
                            if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(actual)
                                || actual.level() != participant.world() || actual.getVehicle() != participant.vehicle()
                                || !samePassengers(actual.getPassengers(), participant.passengers())) return -1;
                        }
                        if (passenger.isRemoved() || vehicle.isRemoved()) return 0;
                        return passenger.startRiding(vehicle, true, true) ? 1 : 0;
                    }))).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(result -> result < 0
                        ? mount(source, passenger, vehicle, attempts - 1) : CompletableFuture.completedFuture(result)));
            });
        });
    }

    /** Snapshot every Native relationship read by startRiding, not just the two named entities. */
    private static CompletableFuture<List<MountPosition>> mountFootprint(java.util.ArrayDeque<Entity> queue, Set<Entity> seen,
                                                                        List<MountPosition> positions) {
        Entity next;
        do { next = queue.poll(); } while (next != null && !seen.add(next));
        if (next == null) return CompletableFuture.completedFuture(List.copyOf(positions));
        Entity actor = next;
        return TisCommandContinuations.then(TisCommandContinuations.owned(actor, () ->
            new MountPosition(actor, (ServerLevel) actor.level(), actor.position(), actor.getVehicle(), List.copyOf(actor.getPassengers()))), value -> {
            positions.add(value); if (value.vehicle() != null) queue.add(value.vehicle()); queue.addAll(value.passengers());
            return mountFootprint(queue, seen, positions);
        });
    }

    private static boolean samePassengers(List<Entity> actual, List<Entity> captured) {
        if (actual.size() != captured.size()) return false;
        for (int i = 0; i < actual.size(); ++i) if (actual.get(i) != captured.get(i)) return false;
        return true;
    }

    private static CompletableFuture<Integer> admitMountPlayers(List<MountPosition> footprint, int index,
                                                                java.util.function.Supplier<CompletableFuture<Integer>> work) {
        if (index == footprint.size()) return work.get();
        Entity participant = footprint.get(index).entity();
        if (participant instanceof net.minecraft.server.level.ServerPlayer)
            return carpet.script.external.ScarpetExplosionActors.admitTarget(participant, () -> admitMountPlayers(footprint, index + 1, work));
        return admitMountPlayers(footprint, index + 1, work);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> serverTree() {
        return literal("server").then(literal("entity_id_counter")
            .executes(c -> counter(c.getSource(), ServerLevel.carpetEntityIdCounter()))
            .then(literal("show").executes(c -> counter(c.getSource(), ServerLevel.carpetEntityIdCounter())))
            .then(literal("query").then(argument("entity", EntityArgument.entity()).executes(c -> entities(c.getSource(),
                List.of(EntityArgument.getEntity(c, "entity")), "query entity ID", entity -> counter(c.getSource(), entity.getId())))))
            .then(literal("set").then(argument("value", IntegerArgumentType.integer()).executes(c -> {
                int value = IntegerArgumentType.getInteger(c, "value");
                ServerLevel.carpetSetEntityIdCounter(value);
                return counter(c.getSource(), value);
            })))
            .then(literal("add").then(argument("delta", IntegerArgumentType.integer()).executes(c -> counter(c.getSource(),
                ServerLevel.carpetAddEntityIdCounter(IntegerArgumentType.getInteger(c, "delta")))))));
    }

    private static int counter(CommandSourceStack source, int value) {
        double negative = value >= 0 ? 100.0 * value / Integer.MAX_VALUE : 100.0;
        long distance = value > 0 ? (1L << 32) - value : -(long) value;
        double zero = 100.0 * ((1L << 32) - distance) / (1L << 32);
        feedback(source, String.format(Locale.ROOT, "Entity ID counter: %d; negative overflow %.4f%%; zero overflow %.4f%%", value, negative, zero));
        return 0;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> containerTree(CommandBuildContext context) {
        var entity = literal("entity").executes(c -> containerHelp(c.getSource(), "entity"))
            .then(literal("shuffle").executes(c -> reorder(c.getSource(), false, true)))
            .then(literal("revert").executes(c -> reorder(c.getSource(), false, false)));
        var blockEntity = literal("tileentity").executes(c -> containerHelp(c.getSource(), "tileentity"))
            .then(literal("shuffle").executes(c -> reorder(c.getSource(), true, true)))
            .then(literal("revert").executes(c -> reorder(c.getSource(), true, false)))
            .then(literal("statistic").executes(c -> blockEntityStats(c.getSource())))
            .then(literal("query").then(argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
                return owned(c.getSource(), pos, level -> {
                    var found = level.getBlockEntity(pos);
                    if (found == null) { feedback(c.getSource(), "No block entity at " + pos.toShortString()); return 0; }
                    int index = -1;
                    var tickers = level.getCurrentWorldData().getBlockEntityTickers();
                    for (int i = 0; i < tickers.size(); ++i) if (tickers.get(i).getTileEntity() == found) { index = i; break; }
                    feedback(c.getSource(), "Block entity " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(found.getType())
                        + " @ " + pos.toShortString() + "; owning region tick order " + (index < 0 ? "N/A" : index));
                    return 1;
                });
            })));
        var tileTick = literal("tiletick").executes(c -> containerHelp(c.getSource(), "tiletick"))
            .then(literal("remove").then(argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
                // The upstream modern clearArea includes the two corner positions.
                return TisManipulateBlocks.clearTicks(c.getSource(), BoundingBox.fromCorners(pos, pos.offset(1, 1, 1)));
            })))
            .then(literal("add").then(argument("pos", BlockPosArgument.blockPos()).then(argument("block", BlockStateArgument.block(context))
                .then(argument("delay", IntegerArgumentType.integer()).executes(c -> addTick(c, TickPriority.NORMAL))
                    .then(argument("priority", IntegerArgumentType.integer(-3, 3)).executes(c ->
                        addTick(c, TickPriority.byValue(IntegerArgumentType.getInteger(c, "priority")))))))));
        var blockEvent = literal("blockevent").executes(c -> containerHelp(c.getSource(), "blockevent"))
            .then(literal("remove").then(argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
                return owned(c.getSource(), pos, level -> {
                    int[] count = {0};
                    level.getCurrentWorldData().removeIfBlockEvents(event -> {
                        boolean remove = event.pos().equals(pos);
                        if (remove) ++count[0];
                        return remove;
                    });
                    feedback(c.getSource(), "Removed " + count[0] + " block events");
                    return count[0];
                });
            })))
            .then(literal("add").then(argument("pos", BlockPosArgument.blockPos()).then(argument("block", BlockStateArgument.block(context))
                .then(argument("type", IntegerArgumentType.integer()).then(argument("data", IntegerArgumentType.integer()).executes(c -> {
                    BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
                    Block block = BlockStateArgument.getBlock(c, "block").getState().getBlock();
                    int type = IntegerArgumentType.getInteger(c, "type"), data = IntegerArgumentType.getInteger(c, "data");
                    return owned(c.getSource(), pos, level -> { level.blockEvent(pos, block, type, data); return 1; });
                }))))));
        return literal("container").then(entity).then(blockEntity).then(tileTick).then(blockEvent);
    }

    private static int addTick(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, TickPriority priority) {
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        Block block = BlockStateArgument.getBlock(c, "block").getState().getBlock();
        int delay = IntegerArgumentType.getInteger(c, "delay");
        return owned(c.getSource(), pos, level -> { level.scheduleTick(pos, block, delay, priority); return 1; });
    }

    private static int containerHelp(CommandSourceStack source, String name) {
        feedback(source, "Container " + name + ": entity/tileentity shuffle|revert, tileentity statistic|query <pos>,"
            + " tiletick/blockevent remove <pos>|add <pos> <block> ...");
        return 1;
    }

    private static int reorder(CommandSourceStack source, boolean blockEntities, boolean shuffle) {
        return worldRegions(source, level -> {
            var data = level.getCurrentWorldData();
            int count = blockEntities ? data.carpetReorderBlockEntities(shuffle) : data.carpetReorderEntities(shuffle);
            if (count < 0) feedback(source, "Cannot reorder a container during its native iteration");
            return Math.max(0, count);
        }, (blockEntities ? "Block entities" : "Entities") + (shuffle ? " shuffled" : " reversed"));
    }

    private static int blockEntityStats(CommandSourceStack source) {
        Map<String, Integer> totals = new ConcurrentHashMap<>();
        return worldRegions(source, level -> {
            int count = 0;
            for (var ticker : level.getCurrentWorldData().getBlockEntityTickers()) {
                var blockEntity = ticker.getTileEntity();
                if (blockEntity == null || blockEntity.isRemoved()) continue;
                totals.merge(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString(), 1, Integer::sum);
                ++count;
            }
            return count;
        }, "Ticking block entity statistics", () -> totals.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10)
            .forEach(entry -> feedback(source, " - " + entry.getKey() + ": " + entry.getValue())));
    }

    private static int worldRegions(CommandSourceStack source, ToIntFunction<ServerLevel> action, String message) {
        return worldRegions(source, action, message, () -> { });
    }

    private static int worldRegions(CommandSourceStack source, ToIntFunction<ServerLevel> action, String message, Runnable after) {
        ServerLevel level = source.getLevel();
        List<ChunkPos> anchors = new ArrayList<>();
        level.regioniser.computeForAllRegions(region -> { ChunkPos pos = region.getCenterChunk(); if (pos != null) anchors.add(pos); });
        Set<Long> regions = ConcurrentHashMap.newKeySet();
        return TisCommandContinuations.complete(source, 1, () -> {
            List<CompletableFuture<Integer>> actuals = new ArrayList<>();
            for (ChunkPos pos : anchors) actuals.add(TisCommandContinuations.world(source, level, new BlockPos(pos.x() << 4, 0, pos.z() << 4), () -> {
                long id = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion().id;
                return regions.add(id) ? action.applyAsInt(level) : 0;
            }));
            return TisCommandContinuations.total(actuals);
        }, message, after);
    }

    static int owned(CommandSourceStack source, BlockPos pos, ToIntFunction<ServerLevel> action) {
        ServerLevel level = source.getLevel();
        if (!net.minecraft.world.level.Level.isInSpawnableBounds(pos)) { feedback(source, "Position is outside world bounds"); return 0; }
        return TisCommandContinuations.complete(source, 1, () -> TisCommandContinuations.world(source, level, pos,
            () -> action.applyAsInt(level)), null, () -> { });
    }

    static void feedback(CommandSourceStack source, String message) { TisRaycastCommand.feedback(source, message); }
}
