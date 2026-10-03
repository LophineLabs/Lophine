// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

public final class TisMicroTimingMarkers {
    private static final Map<Key, Marker> MARKERS = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerPosition> POSITIONS = new ConcurrentHashMap<>();
    private static final Map<Block, DyeColor> WOOL = Arrays.stream(DyeColor.values()).collect(Collectors.toUnmodifiableMap(Blocks.WOOL::pick, color -> color));
    private static volatile BiConsumer<Marker, Boolean> shapeSender = TisMicroTimingMarkers::sendShape;
    private static long lastSync;

    private TisMicroTimingMarkers() { }

    public record Marker(ServerLevel world, BlockPos pos, DyeColor color, Component name, boolean extended, boolean movable) { }
    private record Key(ServerLevel world, BlockPos pos) { }
    private record PlayerPosition(ServerLevel world, Vec3 position) { }

    public static void setShapeSender(BiConsumer<Marker, Boolean> sender) { shapeSender = sender; }
    public static List<Marker> snapshot() { return List.copyOf(MARKERS.values()); }
    public static String name(ServerLevel world, BlockPos pos) {
        Marker marker = MARKERS.get(new Key(world, pos));
        return marker == null || marker.name == null ? null : marker.name.getString();
    }

    public static synchronized int clear() {
        var previous = List.copyOf(MARKERS.values());
        MARKERS.clear();
        previous.forEach(marker -> publish(marker, false));
        return previous.size();
    }
    public static void ruleChanged() { MARKERS.values().forEach(marker -> publish(marker, GeneralCompatConfig.microTiming)); }
    public static void playerLeft(UUID player) { POSITIONS.remove(player); }

    public static String validateDyeRule(String value, String current, net.minecraft.commands.CommandSourceStack source) {
        if ("clear".equals(value)) {
            int count = clear();
            if (source != null) TisRaycastCommand.feedback(source, "Cleared " + count + " micro timing markers");
            return current;
        }
        return "true".equals(value) || "false".equals(value) ? value : null;
    }

    public static void globalTick(MinecraftServer server) {
        if (!GeneralCompatConfig.microTiming) return;
        if ("in_range".equalsIgnoreCase(GeneralCompatConfig.microTimingTarget.toString())) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.getBukkitEntity().taskScheduler.schedule(entity -> {
                    ServerPlayer current = (ServerPlayer) entity;
                    POSITIONS.put(current.getUUID(), new PlayerPosition(current.level(), current.position()));
                }, retired -> POSITIONS.remove(player.getUUID()), 1L);
            }
        }
        long time = CarpetServerClock.gameTime();
        if (time - lastSync >= 100) {
            lastSync = time;
            MARKERS.values().forEach(marker -> publish(marker, true));
        }
    }

    private static void publish(Marker marker, boolean display) {
        BiConsumer<Marker, Boolean> sender = shapeSender;
        if (sender != null) sender.accept(marker, display);
    }

    private static List<CompoundTag> shapes(Marker marker, boolean display) {
        int duration = display ? 12000 : 0;
        int rgba = ((marker.color.getTextColor() & 0xFFFFFF) << 8) | 0xAF;
        var result = new java.util.ArrayList<CompoundTag>();
        result.add(CarpetShapeSender.box(marker.world, Vec3.atLowerCornerOf(marker.pos), Vec3.atLowerCornerOf(marker.pos.offset(1, 1, 1)),
            rgba, 0, marker.extended ? 7.0F : 2.5F, duration));
        if (marker.name != null) result.add(CarpetShapeSender.text(marker.world, Vec3.atCenterOf(marker.pos),
            Component.literal("# ").withStyle(style -> style.withColor(marker.color.getTextColor() & 0xFFFFFF)).append(marker.name.copy()), 0xFFFFFFFF, duration));
        return List.copyOf(result);
    }

    private static void sendShape(Marker marker, boolean display) {
        List<CompoundTag> shapes = shapes(marker, display);
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
            for (ServerPlayer player : marker.world.getServer().getPlayerList().getPlayers()) {
                if (CarpetLoggerProtocol.subscriptions(player.getScoreboardName()).containsKey("microTiming")) {
                    player.getBukkitEntity().taskScheduler.schedule(current -> CarpetShapeSender.send((ServerPlayer) current, shapes), null, 1L);
                }
            }
        });
    }

    public static void subscribed(ServerPlayer player) {
        if (!GeneralCompatConfig.microTiming) return;
        var shapes = snapshot().stream().flatMap(marker -> shapes(marker, true).stream()).toList();
        CarpetShapeSender.send(player, shapes);
    }

    public static void unsubscribed(ServerPlayer player) {
        var shapes = snapshot().stream().flatMap(marker -> shapes(marker, false).stream()).toList();
        CarpetShapeSender.send(player, shapes);
    }

    public static synchronized boolean rightClick(Player raw, BlockPos pos) {
        if (!(raw instanceof ServerPlayer player) || !TisMicroTiming.active()
            || !"true".equals(GeneralCompatConfig.microTimingDyeMarker)
            || !CarpetLoggerProtocol.subscriptions(player.getScoreboardName()).containsKey("microTiming")) return false;
        var stack = player.getMainHandItem();
        if (stack.getItem() instanceof DyeItem) {
            DyeColor color = stack.get(DataComponents.DYE);
            if (color == null) return true;
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            Key key = new Key(player.level(), pos.immutable());
            Marker previous = MARKERS.get(key);
            Marker next = null;
            if (previous == null || previous.color != color) next = new Marker(player.level(), pos.immutable(), color, name == null ? null : name.copy(), false, false);
            else if (!previous.extended) next = new Marker(previous.world, previous.pos, color, previous.name, true, previous.movable);
            if (previous != null) publish(previous, false);
            if (next == null) {
                MARKERS.remove(key);
                player.sendSystemMessage(Component.literal("Micro timing marker removed at " + pos.toShortString()));
            } else {
                MARKERS.put(key, next);
                publish(next, true);
                player.sendSystemMessage(Component.literal("Micro timing " + (next.extended ? "END_ROD" : "REGULAR") + " marker: " + color.getName()
                    + " at " + pos.toShortString() + (name == null ? "" : "; " + name.getString())));
            }
            return true;
        }
        if (stack.is(Items.SLIME_BALL)) {
            Key key = new Key(player.level(), pos);
            Marker previous = MARKERS.get(key);
            if (previous == null) return false;
            Marker next = new Marker(previous.world, previous.pos, previous.color, previous.name, previous.extended, !previous.movable);
            MARKERS.put(key, next);
            player.sendSystemMessage(Component.literal("Micro timing marker mobility: " + next.movable + " at " + pos.toShortString()));
            return true;
        }
        return false;
    }

    public static synchronized void move(Level raw, BlockPos source, Direction direction) {
        if (!(raw instanceof ServerLevel world) || !TisMicroTiming.active() || !"true".equals(GeneralCompatConfig.microTimingDyeMarker)) return;
        Key key = new Key(world, source);
        Marker marker = MARKERS.get(key);
        if (marker == null || !marker.movable || !MARKERS.remove(key, marker)) return;
        Marker next = new Marker(world, source.relative(direction).immutable(), marker.color, marker.name, marker.extended, marker.movable);
        Marker replaced = MARKERS.put(new Key(world, next.pos), next);
        publish(marker, false);
        if (replaced != null) publish(replaced, false);
        publish(next, true);
    }

    public static DyeColor color(ServerLevel world, BlockPos pos, boolean update) {
        String mode = GeneralCompatConfig.microTimingTarget.toString().toLowerCase(java.util.Locale.ROOT);
        DyeColor result = null;
        boolean fallback = false;
        if (!"marker_only".equals(mode)) {
            if (!update) result = attachedWool(world, pos);
            if (result == null) result = endRod(world, pos);
            if (!update && result == null) {
                fallback = "all".equals(mode) || "in_range".equals(mode) && POSITIONS.values().stream()
                    .anyMatch(player -> player.world == world && player.position.distanceToSqr(Vec3.atCenterOf(pos)) <= 32.0 * 32.0);
                if (fallback) result = DyeColor.LIGHT_GRAY;
            }
        }
        Marker marker = MARKERS.get(new Key(world, pos));
        if ((result == null || fallback) && marker != null && (!update || marker.extended)) result = marker.color;
        return result;
    }

    private static BlockState state(ServerLevel world, BlockPos pos) {
        return ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(world, pos) && world.isPositionEntityTicking(pos)
            ? world.getBlockStateIfLoaded(pos) : null;
    }
    private static DyeColor wool(ServerLevel world, BlockPos pos) {
        BlockState state = state(world, pos);
        return state == null ? null : WOOL.get(state.getBlock());
    }
    private static DyeColor endRod(ServerLevel world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos rod = pos.relative(direction);
            BlockState state = state(world, rod);
            if (state != null && state.is(Blocks.END_ROD) && state.getValue(BlockStateProperties.FACING).getOpposite() == direction) {
                DyeColor color = wool(world, rod.relative(direction));
                if (color != null) return color;
            }
        }
        return null;
    }
    private static DyeColor attachedWool(ServerLevel world, BlockPos pos) {
        BlockState state = state(world, pos);
        if (state == null) return null;
        Block block = state.getBlock();
        BlockPos woolPos;
        if (block instanceof ObserverBlock || block instanceof EndRodBlock || block instanceof PistonBaseBlock || block instanceof MovingPistonBlock) {
            woolPos = pos.relative(state.getValue(BlockStateProperties.FACING).getOpposite());
        } else if (block instanceof ButtonBlock || block instanceof LeverBlock) {
            AttachFace face = state.getValue(BlockStateProperties.ATTACH_FACE);
            Direction facing = face == AttachFace.FLOOR ? Direction.UP : face == AttachFace.CEILING ? Direction.DOWN : state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            woolPos = pos.relative(facing.getOpposite());
        } else if (block instanceof RedstoneWallTorchBlock || block instanceof TripWireHookBlock) {
            woolPos = pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        } else if (block instanceof BaseRailBlock || block instanceof DiodeBlock || block instanceof RedstoneTorchBlock
            || block instanceof RedstoneWireBlock || block instanceof BasePressurePlateBlock) {
            woolPos = pos.below();
        } else return null;
        return wool(world, woolPos);
    }
}
