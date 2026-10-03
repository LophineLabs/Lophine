// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;

public final class TisMicroTiming {
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final ThreadLocal<Phase> PHASE = ThreadLocal.withInitial(() -> new Phase("unknown", null));
    private static final Set<BlockPos> SCRIPT_POSITIONS = ConcurrentHashMap.newKeySet();
    private static final CopyOnWriteArrayList<Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Scope EMPTY = new Scope(null, null, null, false);

    private TisMicroTiming() { }

    public static void registerLogger() {
        CarpetLoggerProtocol.registerLogger("microTiming", "merged", List.of("merged", "all", "unique"), true);
    }

    public static boolean active() {
        return GeneralCompatConfig.microTiming && (CarpetLoggerProtocol.hasSubscribers("microTiming") || !SCRIPT_POSITIONS.isEmpty());
    }

    public static Scope noop() { return EMPTY; }

    public static void trackScriptPosition(BlockPos pos, boolean tracked) {
        if (tracked) SCRIPT_POSITIONS.add(pos.immutable()); else SCRIPT_POSITIONS.remove(pos);
    }
    public static void addListener(Consumer<Event> listener) { LISTENERS.addIfAbsent(listener); }
    public static void removeListener(Consumer<Event> listener) { LISTENERS.remove(listener); }
    public static void ruleChanged() { EPOCH.incrementAndGet(); TisMicroTimingMarkers.ruleChanged(); }

    public static void enabledChanged(net.minecraft.commands.CommandSourceStack source) {
        ruleChanged();
        if (source != null && GeneralCompatConfig.microTiming && !GeneralCompatConfig.instantBlockUpdaterReintroduced) {
            TisRaycastCommand.feedback(source, "Micro timing records queued neighbor updates. /carpet instantBlockUpdaterReintroduced selects instant neighbor updates.");
        }
    }

    public static void targetChanged(net.minecraft.commands.CommandSourceStack source) {
        if (source != null && !"marker_only".equalsIgnoreCase(GeneralCompatConfig.microTimingTarget.toString())) {
            TisRaycastCommand.feedback(source, "microTimingTarget values other than marker_only are deprecated upstream; dye markers select positions and colors.");
        }
    }
    public static void reset() { EPOCH.incrementAndGet(); SCRIPT_POSITIONS.clear(); LISTENERS.clear(); TisMicroTimingMarkers.clear(); PHASE.remove(); }

    private static Frame frame(ServerLevel world) {
        var data = TickRegionScheduler.getCurrentRegionizedWorldData();
        if (data == null || data.world != world) return null;
        Frame frame = data.carpetMicroTimingFrame;
        if (frame == null) data.carpetMicroTimingFrame = frame = new Frame(world, Integer.toHexString(System.identityHashCode(data)));
        if (frame.epoch != EPOCH.get()) frame.clear();
        return frame;
    }

    public static Scope phase(Level world, String stage, String detail) {
        if (!active()) return EMPTY;
        Phase previous = PHASE.get();
        PHASE.set(new Phase(stage, detail));
        return new Scope(null, null, previous, false);
    }

    public static Scope packet(Object listener, Object packet) {
        if (!active()) return EMPTY;
        if (listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl handler) {
            return phase(handler.player.level(), "player_action", handler.player.getScoreboardName() + "/" + packet.getClass().getSimpleName());
        }
        return phase(null, "network", packet.getClass().getSimpleName());
    }

    public static void stage(String stage) { if (active()) PHASE.set(new Phase(stage, null)); }
    public static void detail(String detail) { if (active()) PHASE.set(new Phase(PHASE.get().stage, detail)); }

    public static Scope entityPhase(Entity entity) {
        if (!active()) return EMPTY;
        return phase(entity.level(), "entity", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + "#" + entity.getId());
    }

    public static void timeBoundary(ServerLevel world) {
        if (!active()) return;
        Frame frame = frame(world);
        if (frame != null && "world_timer".equalsIgnoreCase(GeneralCompatConfig.microTimingTickDivision.toString())) frame.flush();
    }
    public static void endRegionTick(ServerLevel world) {
        if (!active()) return;
        Frame frame = frame(world);
        if (frame != null && "player_action".equalsIgnoreCase(GeneralCompatConfig.microTimingTickDivision.toString())) frame.flush();
    }

    public static void endCurrentRegionTick() {
        var data = TickRegionScheduler.getCurrentRegionizedWorldData();
        if (data != null) endRegionTick(data.world);
    }

    public static void regionTransition(Frame frame) { if (frame != null) frame.flush(); }

    public static boolean pistonResult(Level world, BlockPos pos, boolean success, List<BlockPos> push, List<BlockPos> destroy) {
        if (active()) event(world, pos, world.getBlockState(pos).getBlock(), "piston_compute_push_structure",
            "success=" + success + "; push=" + push + "; destroy=" + destroy, false);
        return success;
    }

    public static Scope begin(Level level, BlockPos pos, Object source, String event, String data, boolean update, boolean important) {
        if (!active() || !(level instanceof ServerLevel world)) return EMPTY;
        Frame frame = frame(world);
        if (frame == null) return EMPTY;
        var color = TisMicroTimingMarkers.color(world, pos, update);
        dispatch(world, pos, source, event, data, "action_start");
        if (color == null) {
            if (!SCRIPT_POSITIONS.contains(pos)) return EMPTY;
            Node scriptNode = new Node(frame.current, capture(world, pos, source, event, data, DyeColor.LIGHT_GRAY), important, true);
            return new Scope(frame, scriptNode, null, false);
        }
        Node node = new Node(frame.current, capture(world, pos, source, event, data, color), important, true);
        frame.add(node);
        frame.current = node;
        return new Scope(frame, node, null, true);
    }

    public static void event(Level level, BlockPos pos, Object source, String event, String data, boolean update) {
        if (!active() || !(level instanceof ServerLevel world)) return;
        Frame frame = frame(world);
        if (frame == null) return;
        dispatch(world, pos, source, event, data, "event");
        DyeColor color = TisMicroTimingMarkers.color(world, pos, update);
        if (color != null) frame.add(new Node(frame.current, capture(world, pos, source, event, data, color), true, false));
    }

    public static Scope setBlock(Level level, BlockPos pos, BlockState state, int flags) {
        if (!active() || !(level instanceof ServerLevel world)
            || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(world, pos)) return EMPTY;
        BlockState old = world.getBlockStateIfLoaded(pos);
        if (old == null || old == state) return EMPTY;
        String event = old.getBlock() == state.getBlock() ? "block_state_change" : "block_replace";
        return begin(world, pos, old.getBlock(), event, old + " -> " + state + "; flags=" + flags, false, true);
    }

    public static Scope executeTick(ServerLevel world, ScheduledTick<?> tick, int index, boolean block) {
        if (!active()) return EMPTY;
        return begin(world, tick.pos(), tick.type(), "execute_tile_tick", (block ? "block" : "fluid") + " #" + index
            + "; priority=" + tick.priority() + "; subTickOrder=" + tick.subTickOrder() + "; trigger=" + tick.triggerTick(), false, true);
    }

    public static void scheduleTick(ServerLevel world, ScheduledTick<?> tick, boolean accepted) {
        if (!active()) return;
        event(world, tick.pos(), tick.type(), "schedule_tile_tick", "delay=" + (tick.triggerTick() - world.getRedstoneGameTime())
            + "; priority=" + tick.priority() + "; subTickOrder=" + tick.subTickOrder() + "; success=" + accepted, false);
    }

    private static String sourceId(Object source) {
        if (source instanceof Block block) return BuiltInRegistries.BLOCK.getKey(block).toString();
        if (source instanceof Fluid fluid) return BuiltInRegistries.FLUID.getKey(fluid).toString();
        return String.valueOf(source);
    }

    private static Event capture(ServerLevel world, BlockPos pos, Object source, String event, String data, DyeColor color) {
        String stack = StackWalker.getInstance().walk(stream -> stream.filter(frame -> !frame.getClassName().equals(TisMicroTiming.class.getName()))
            .limit(32).map(Object::toString).collect(java.util.stream.Collectors.joining("\n")));
        return new Event(world.dimension().identifier().toString(), pos.immutable(), sourceId(source), event, data, color,
            PHASE.get(), TisMicroTimingMarkers.name(world, pos), stack);
    }

    private static void dispatch(ServerLevel world, BlockPos pos, Object source, String event, String data, String eventType) {
        if (SCRIPT_POSITIONS.contains(pos)) {
            Event snapshot = new Event(world.dimension().identifier().toString(), pos.immutable(), sourceId(source), event,
                data + "; eventType=" + eventType, DyeColor.LIGHT_GRAY, PHASE.get(), null, "");
            for (var listener : LISTENERS) listener.accept(snapshot);
        }
    }

    public record Phase(String stage, String detail) {
        @Override public String toString() { return stage + (detail == null ? "" : "[" + detail + "]"); }
    }
    public record Event(String dimension, BlockPos pos, String source, String event, String data, DyeColor color,
                        Phase phase, String name, String stack) { }

    public static final class Scope implements AutoCloseable {
        private final Frame frame;
        private final Node node;
        private final Phase previousPhase;
        private final boolean logged;
        private boolean closed;
        private Scope(Frame frame, Node node, Phase previousPhase, boolean logged) {
            this.frame = frame; this.node = node; this.previousPhase = previousPhase; this.logged = logged;
        }
        public boolean result(boolean result) { result(Boolean.toString(result)); return result; }
        public void result(String result) { if (node != null) node.result = result; }
        @Override public void close() {
            if (this == EMPTY || closed) return;
            closed = true;
            if (node != null && frame.epoch == EPOCH.get()) {
                node.closed = true;
                if (logged) frame.current = node.parent;
                var world = frame.world;
                dispatch(world, node.entry.pos, node.entry.source, node.entry.event,
                    node.result == null ? node.entry.data : node.entry.data + "; result=" + node.result, "action_end");
            }
            if (previousPhase != null) PHASE.set(previousPhase);
        }
    }

    static final class Node {
        final Node parent;
        final Event entry;
        final boolean important;
        final boolean procedure;
        final List<Node> children = new ArrayList<>();
        boolean closed;
        String result;
        boolean visible;
        int visibleChildren;
        Node(Node parent, Event entry, boolean important, boolean procedure) {
            this.parent = parent; this.entry = entry; this.important = important; this.procedure = procedure;
        }
    }
    record Line(Event event, int depth, boolean procedure, boolean end, String result) {
        String equalityKey() { return event.dimension + "/" + event.pos + "/" + event.color + "/" + event.phase
            + "/" + event.source + "/" + event.event + "/" + event.data + "/" + result + "/" + end; }
    }

    public static final class Frame {
        final ServerLevel world;
        final String region;
        long epoch = EPOCH.get();
        final List<Node> roots = new ArrayList<>();
        Node current;
        public Frame(ServerLevel world, String region) { this.world = world; this.region = region; }
        void add(Node node) { if (node.parent == null) roots.add(node); else node.parent.children.add(node); }
        void clear() { roots.clear(); current = null; epoch = EPOCH.get(); }
        void flush() {
            if (roots.isEmpty()) return;
            if (!GeneralCompatConfig.microTiming || epoch != EPOCH.get()) { clear(); return; }
            List<Line> lines = flatten(roots);
            roots.clear(); current = null;
            if (lines.isEmpty()) return;
            long time = CarpetServerClock.gameTime();
            String dimension = world.dimension().identifier().toString();
            CarpetLoggerProtocol.log("microTiming", option -> render(lines, option, time, dimension, region));
        }
    }

    // Both passes use explicit stacks; deeply nested redstone must not overflow the logger stack.
    static List<Line> flatten(List<Node> roots) {
        var post = new ArrayDeque<Node>();
        var order = new ArrayList<Node>();
        for (Node root : roots) post.push(root);
        while (!post.isEmpty()) {
            Node node = post.pop(); order.add(node);
            for (Node child : node.children) post.push(child);
        }
        for (int i = order.size() - 1; i >= 0; --i) {
            Node node = order.get(i);
            node.visibleChildren = (int) node.children.stream().filter(child -> child.visible).count();
            node.visible = node.important || node.visibleChildren > 0;
        }
        record Visit(Node node, int depth, boolean end) { }
        var stack = new ArrayDeque<Visit>();
        var lines = new ArrayList<Line>();
        for (int i = roots.size() - 1; i >= 0; --i) stack.push(new Visit(roots.get(i), 0, false));
        while (!stack.isEmpty()) {
            Visit visit = stack.pop(); Node node = visit.node;
            if (!node.visible) continue;
            if (visit.end) { lines.add(new Line(node.entry, visit.depth, true, true, node.result)); continue; }
            boolean separateEnd = node.procedure && node.closed && node.visibleChildren > 1;
            lines.add(new Line(node.entry, visit.depth, node.procedure, false, separateEnd ? null : node.result));
            if (separateEnd) stack.push(new Visit(node, visit.depth, true));
            for (int i = node.children.size() - 1; i >= 0; --i) stack.push(new Visit(node.children.get(i), visit.depth + 1, false));
        }
        return List.copyOf(lines);
    }

    static List<Component> render(List<Line> lines, String option, long time, String dimension, String region) {
        String mode = option == null ? "merged" : option.toLowerCase(Locale.ROOT);
        var messages = new ArrayList<Component>();
        messages.add(Component.literal("[GameTime " + time + " @ " + dimension + "; region " + region + "] ------------").withStyle(ChatFormatting.GRAY));
        Set<String> seen = new HashSet<>();
        String previousKey = null;
        int repeated = 0;
        net.minecraft.network.chat.MutableComponent previous = null;
        for (Line line : lines) {
            String key = line.equalityKey();
            boolean show = line.procedure || "all".equals(mode)
                || "unique".equals(mode) && seen.add(key) || !"unique".equals(mode) && !key.equals(previousKey);
            if (!show) { ++repeated; continue; }
            if (previous != null && "merged".equals(mode) && repeated > 0) previous.append(" +" + repeated + "x");
            repeated = 0;
            previous = lineComponent(line);
            messages.add(previous);
            previousKey = key;
        }
        if (previous != null && "merged".equals(mode) && repeated > 0) previous.append(" +" + repeated + "x");
        return messages;
    }

    private static net.minecraft.network.chat.MutableComponent lineComponent(Line line) {
        Event event = line.event;
        String coords = event.pos.getX() + " " + event.pos.getY() + " " + event.pos.getZ();
        var tag = Component.literal("# ").withStyle(style -> style.withColor(event.color.getTextColor() & 0xFFFFFF)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(event.dimension + " " + coords + "\n" + event.color + "; indentation=" + line.depth)))
            .withClickEvent(new ClickEvent.SuggestCommand("/execute in " + event.dimension + " run tp @s " + coords)));
        var source = Component.literal("[" + (event.name == null ? event.source : event.name) + "] ")
            .withStyle(style -> style.withColor(ChatFormatting.GRAY).withHoverEvent(new HoverEvent.ShowText(Component.literal(event.source))));
        var body = Component.literal((line.end ? "end " : "") + event.event + (line.end ? "" : ": " + event.data)
            + (line.result == null ? "" : "; result=" + line.result)).withStyle(ChatFormatting.WHITE);
        var result = Component.literal("  ".repeat(Math.min(line.depth, 10))).append(tag).append(source).append(body);
        if (!line.end) result.append(Component.literal(" @ " + event.phase).withStyle(ChatFormatting.YELLOW));
        if (!event.stack.isEmpty()) result.append(Component.literal("  $").withStyle(style -> style.withColor(ChatFormatting.GRAY)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(event.stack)))));
        return result;
    }
}
