// SPDX-License-Identifier: LGPL-3.0-or-later
// Server adaptation of Carpet TIS Addition revision 62c3cb6fff26cd9c4e12aa616b403c184a4a6ca0.
package fun.bm.lophine.carpet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import fun.bm.lophine.protocol.CarpetLoggerProtocol;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.phys.Vec3;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class TisLifetimeTracker {
    private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Map<String, Filter> FILTERS = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile Session latest;

    private TisLifetimeTracker() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var tracking = literal("tracking").executes(c -> report(c.getSource(), null, null, false))
            .then(literal("start").executes(c -> start(c.getSource(), false)))
            .then(literal("stop").executes(c -> stop(c.getSource())))
            .then(literal("restart").executes(c -> start(c.getSource(), true)))
            .then(literal("realtime").executes(c -> report(c.getSource(), null, null, true)));
        var typeResult = argument("entity_type", StringArgumentType.string())
            .suggests((c, b) -> SharedSuggestionProvider.suggest(availableTypes(), b))
            .executes(c -> report(c.getSource(), StringArgumentType.getString(c, "entity_type"), null, false))
            .then(literal("realtime").executes(c -> report(c.getSource(), StringArgumentType.getString(c, "entity_type"), null, true)));
        typeResult.then(argument("detail", StringArgumentType.string())
            .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("spawning", "removal", "lifetime"), b))
            .executes(c -> report(c.getSource(), StringArgumentType.getString(c, "entity_type"), StringArgumentType.getString(c, "detail"), false))
            .then(literal("realtime").executes(c -> report(c.getSource(), StringArgumentType.getString(c, "entity_type"),
                StringArgumentType.getString(c, "detail"), true))));
        dispatcher.register(literal("lifetime")
            .requires(source -> CarpetCommandPermissions.canUse(source, GeneralCompatConfig.commandLifeTime))
            .executes(c -> help(c.getSource())).then(literal("help").executes(c -> help(c.getSource())))
            .then(tracking).then(typeResult)
            .then(literal("filter").executes(c -> displayFilters(c.getSource()))
                .then(filterTree(literal("global"), true))
                .then(filterTree(argument("entity_type", StringArgumentType.string()).suggests((c, b) ->
                    SharedSuggestionProvider.suggest(allTypes(), b)), false)))
            .then(literal("recorder").executes(c -> TisLifetimeRecorder.status(c.getSource()))
                .then(literal("status").executes(c -> TisLifetimeRecorder.status(c.getSource())))
                .then(literal("reload").requires(TisLifetimeRecorder::hasPermission).executes(c -> TisLifetimeRecorder.reload(c.getSource())))
                .then(literal("enable").requires(TisLifetimeRecorder::hasPermission).executes(c -> TisLifetimeRecorder.enabled(c.getSource(), true)))
                .then(literal("disable").requires(TisLifetimeRecorder::hasPermission).executes(c -> TisLifetimeRecorder.enabled(c.getSource(), false)))));
    }

    public static void registerLogger() {
        CarpetLoggerProtocol.registerLogger("lifetime", "", List.of(), false);
    }

    public static List<String> loggerOptions() { return availableTypes(); }

    public static boolean isValidLoggerOption(String option) {
        return option != null && BuiltInRegistries.ENTITY_TYPE.stream().anyMatch(type -> {
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            return option.equals(key.getPath()) || option.equals(key.toString());
        });
    }

    public static int activeTrackId() { Session session = ACTIVE.get(); return session == null ? -1 : session.id; }

    private static synchronized int start(CommandSourceStack source, boolean restart) {
        Session previous = ACTIVE.get();
        if (previous != null && !restart) { tell(source, "Lifetime tracking is already started"); return 0; }
        if (previous != null) print(source, previous, null, null, false);
        ACTIVE.set(null);
        Session fresh = new Session(IDS.incrementAndGet(), CarpetServerClock.gameTime(), System.currentTimeMillis());
        TisLifetimeRecorder.start(source, fresh.id);
        latest = fresh;
        ACTIVE.set(fresh);
        tell(source, restart ? "Lifetime tracking restarted" : "Lifetime tracking started");
        return 1;
    }

    private static synchronized int stop(CommandSourceStack source) {
        Session session = ACTIVE.getAndSet(null);
        if (session == null) { tell(source, "Lifetime tracking is not started"); return 0; }
        print(source, session, null, null, false);
        TisLifetimeRecorder.stop(source);
        tell(source, "Lifetime tracking stopped");
        return 1;
    }

    public static synchronized void reset() {
        ACTIVE.set(null);
        latest = null;
        FILTERS.clear();
        TisLifetimeRecorder.stop(null);
    }

    private static int help(CommandSourceStack source) {
        tell(source, "/lifetime tracking start|stop|restart|realtime; <entity_type> [spawning|removal|lifetime] [realtime];"
            + " filter global|<type> set <selector>...|clear; recorder status|reload|enable|disable");
        return 1;
    }

    private static List<String> allTypes() {
        return BuiltInRegistries.ENTITY_TYPE.stream().map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath()).toList();
    }

    private static List<String> availableTypes() {
        Session session = ACTIVE.get();
        return session == null ? List.of() : session.statistics.keySet().stream().map(key -> key.type).distinct().toList();
    }

    private static String normalizeType(CommandSourceStack source, String name) {
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (name.equals(key.getPath()) || name.equals(key.toString())) return key.getPath();
        }
        tell(source, "Unknown entity type: " + name);
        return null;
    }

    private static int report(CommandSourceStack source, String typeName, String detail, boolean realtime) {
        Session session = ACTIVE.get();
        if (session == null) { tell(source, "Lifetime tracking is not started"); return 1; }
        String type = typeName == null ? null : normalizeType(source, typeName);
        if (typeName != null && type == null) return 0;
        if (detail != null && !List.of("spawning", "removal", "lifetime").contains(detail.toLowerCase(Locale.ROOT))) {
            tell(source, "Unknown lifetime detail mode: " + detail);
            return 0;
        }
        print(source, session, type, detail == null ? null : detail.toLowerCase(Locale.ROOT), realtime);
        return 1;
    }

    private static void print(CommandSourceStack source, Session session, String type, String detail, boolean realtime) {
        long ticks = Math.max(1, realtime ? (System.currentTimeMillis() - session.startMillis) / 50
            : CarpetServerClock.gameTime() - session.startTick);
        tell(source, "Lifetime tracker: " + ticks + " " + (realtime ? "real-time" : "in-game") + " ticks");
        List<Map.Entry<Key, Stats>> entries = session.statistics.entrySet().stream().filter(entry -> type == null || entry.getKey().type.equals(type))
            .sorted(Comparator.comparing((Map.Entry<Key, Stats> entry) -> entry.getKey().dimension)
                .thenComparing(Comparator.comparingLong((Map.Entry<Key, Stats> entry) -> entry.getValue().snapshot().spawned).reversed())).toList();
        if (entries.isEmpty()) { tell(source, "No lifetime tracking result"); return; }
        for (var entry : entries) {
            Summary summary = entry.getValue().snapshot();
            String prefix = entry.getKey().dimension + " - " + entry.getKey().type;
            tell(source, prefix + ": S/R " + summary.spawned + "/" + summary.removed + ", L " + lifetime(summary.lifetime)
                + "; S/h " + rate(summary.spawned, ticks) + ", R/h " + rate(summary.removed, ticks));
            if (summary.spawnExtra != 0 || summary.removeExtra != 0) tell(source, "  Item/XP amount S/R " + summary.spawnExtra
                + "/" + summary.removeExtra + "; S/h " + rate(summary.spawnExtra, ticks) + ", R/h " + rate(summary.removeExtra, ticks));
            if (type == null) continue;
            if (detail == null || "spawning".equals(detail)) reasons(source, summary.spawnReasons, summary.spawned, ticks, false);
            if (detail == null || "removal".equals(detail)) reasons(source, summary.removeReasons, summary.removed, ticks, true);
            if (detail == null || "lifetime".equals(detail)) positions(source, summary.lifetime);
        }
    }

    private static String rate(long count, long ticks) { return String.format(Locale.ROOT, "%.1f", count * 72000.0 / ticks); }
    private static String lifetime(Lifetime time) {
        return time.count == 0 ? "N/A" : String.format(Locale.ROOT, "%d/%d/%.2f gt", time.minimum.time, time.maximum.time,
            time.sum / (double) time.count);
    }

    private static void reasons(CommandSourceStack source, Map<Reason, ReasonSummary> reasons, long total, long ticks, boolean removal) {
        tell(source, removal ? "  Removal reasons:" : "  Spawning reasons:");
        reasons.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<Reason, ReasonSummary> entry) -> entry.getValue().count).reversed())
            .forEach(entry -> {
                ReasonSummary data = entry.getValue();
                tell(source, "  - " + entry.getKey().id + ("{}".equals(entry.getKey().data) ? "" : " " + entry.getKey().data) + ": "
                    + data.count + ", " + rate(data.count, ticks) + "/h, " + String.format(Locale.ROOT, "%.1f%%", 100.0 * data.count / Math.max(1, total))
                    + (data.extra == 0 ? "" : "; Item/XP amount " + data.extra) + (removal ? "; L " + lifetime(data.lifetime) : ""));
                positions(source, removal ? data.lifetime : null);
            });
    }

    private static void positions(CommandSourceStack source, Lifetime time) {
        if (time == null || time.count == 0) return;
        component(source, Component.literal("  Minimum lifetime: " + time.minimum.time + " gt ").append(positionButton("[S]", time.minimum.dimension, time.minimum.spawn))
            .append(" ").append(positionButton("[R]", time.minimum.dimension, time.minimum.removal)));
        component(source, Component.literal("  Maximum lifetime: " + time.maximum.time + " gt ").append(positionButton("[S]", time.maximum.dimension, time.maximum.spawn))
            .append(" ").append(positionButton("[R]", time.maximum.dimension, time.maximum.removal)));
        tell(source, String.format(Locale.ROOT, "  Average lifetime: %.4f gt", time.sum / (double) time.count));
    }

    private static Component positionButton(String text, String dimension, Vec3 pos) {
        String command = String.format(Locale.ROOT, "/execute in %s run tp @s %.8f %.8f %.8f", dimension, pos.x, pos.y, pos.z);
        return Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.GRAY)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(dimension + " " + pos)))
            .withClickEvent(new ClickEvent.SuggestCommand(command)));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> filterTree(ArgumentBuilder<CommandSourceStack, ?> root, boolean global) {
        ArgumentBuilder<CommandSourceStack, ?> next = null;
        for (int i = 16; i >= 1; --i) {
            String name = i == 1 ? "filter" : "filter" + i;
            var node = argument(name, EntityArgument.entities()).executes(c -> setFilter(c, global));
            if (next != null) node.then(next);
            next = node;
        }
        return root.executes(c -> displayFilter(c.getSource(), filterKey(c, global)))
            .then(literal("clear").executes(c -> {
                String key = filterKey(c, global);
                if (key == null) return 0;
                FILTERS.remove(key);
                tell(c.getSource(), "Lifetime filter cleared: " + key);
                return 1;
            })).then(literal("set").then(next));
    }

    private static String filterKey(CommandContext<CommandSourceStack> context, boolean global) {
        return global ? "global" : normalizeType(context.getSource(), StringArgumentType.getString(context, "entity_type"));
    }

    private static int setFilter(CommandContext<CommandSourceStack> context, boolean global) {
        String key = filterKey(context, global);
        if (key == null) return 0;
        List<EntitySelector> selectors = new ArrayList<>();
        for (int i = 1; i <= 16; ++i) {
            try {
                EntitySelector selector = context.getArgument(i == 1 ? "filter" : "filter" + i, EntitySelector.class);
                if (!selector.carpetSupportsLifetimeFilter()) { tell(context.getSource(), "Lifetime filters require entity selectors, not player-name/player-only selectors"); return 0; }
                selectors.add(selector);
            } catch (IllegalArgumentException absent) { break; }
        }
        CommandSourceStack source = context.getSource();
        UUID id = source.getEntity() == null ? new UUID(0L, 0L) : source.getEntity().getUUID();
        String descriptor = context.getNodes().stream().filter(node -> node.getNode().getName().startsWith("filter") && !"filter".equals(node.getNode().getName())
            || "filter".equals(node.getNode().getName()) && node.getNode() instanceof com.mojang.brigadier.tree.ArgumentCommandNode)
            .map(node -> context.getInput().substring(node.getRange().getStart(), node.getRange().getEnd())).collect(java.util.stream.Collectors.joining(" "));
        FILTERS.put(key, new Filter(List.copyOf(selectors), source.getPosition(), source.getLevel(), id, source.enabledFeatures(), descriptor));
        return displayFilter(source, key);
    }

    private static int displayFilter(CommandSourceStack source, String key) {
        if (key == null) return 0;
        Filter filter = FILTERS.get(key);
        tell(source, "Lifetime filter " + key + ": " + (filter == null ? "none" : filter.descriptor + " @ "
            + filter.level.dimension().identifier() + " " + filter.origin));
        return 1;
    }

    private static int displayFilters(CommandSourceStack source) {
        tell(source, "Lifetime filters: " + FILTERS.size());
        FILTERS.keySet().stream().sorted().forEach(key -> displayFilter(source, key));
        return 1;
    }

    private record Filter(List<EntitySelector> selectors, Vec3 origin, ServerLevel level, UUID id, FeatureFlagSet features, String descriptor) {
        boolean test(Entity target) { return selectors.stream().anyMatch(selector -> selector.carpetMatchesMovement(target, origin, level, id, features)); }
    }

    private static boolean filtered(Entity target) {
        Filter global = FILTERS.get("global");
        Filter type = FILTERS.get(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath());
        return (global == null || global.test(target)) && (type == null || type.test(target));
    }

    public static List<Component> hud(String option, ServerPlayer player) {
        Session session = latest;
        if (session == null || !isValidLoggerOption(option)) return List.of();
        if (option.startsWith("minecraft:")) option = option.substring("minecraft:".length());
        var stats = session.statistics.get(new Key(player.level().dimension().identifier().toString(), option));
        Summary summary = stats == null ? new Stats().snapshot() : stats.snapshot();
        return List.of(Component.literal(option + ": " + summary.spawned + "/" + summary.removed + "  " + lifetime(summary.lifetime))
            .withStyle(ChatFormatting.GRAY));
    }

    public static Target newTarget(Entity entity) {
        Session session = ACTIVE.get();
        return session != null && entity.level() instanceof ServerLevel
            && (entity instanceof Mob || entity instanceof ItemEntity || entity instanceof ExperienceOrb
                || entity instanceof AbstractMinecart || entity instanceof AbstractBoat) ? new Target(entity, session) : null;
    }

    public static JsonObject related(Entity entity) {
        return related(entity, "entityType");
    }
    public static JsonObject related(Entity entity, String key) {
        JsonObject data = new JsonObject();
        data.addProperty(key, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        return data;
    }

    public static void markStatusEffectSpawn(Entity entity, net.minecraft.world.effect.MobEffect effect) {
        JsonObject data = new JsonObject();
        data.addProperty("effect", BuiltInRegistries.MOB_EFFECT.getKey(effect).toString());
        markSpawn(entity, "status_effect", data);
    }

    public static void markMobDrop(ItemEntity item, Entity dropper) {
        if (item.carpetLifetimeTarget != null && item.carpetLifetimeTarget.pendingReason == null) {
            markSpawn(item, "mob_drop", related(dropper, "dropperType"));
        }
    }

    public static void inferSpawn(Entity entity, net.minecraft.world.entity.EntitySpawnReason reason) {
        String name = switch (reason) {
            case NATURAL, PATROL -> "natural";
            case SPAWNER -> "spawner";
            case BREEDING -> "breeding";
            case MOB_SUMMONED -> "summon";
            case JOCKEY -> "jockey";
            case REINFORCEMENT -> "zombie_reinforce";
            case BUCKET, SPAWN_ITEM_USE -> "item";
            case COMMAND -> "command";
            case DISPENSER -> "dispensed";
            default -> null;
        };
        if (name != null) markSpawn(entity, name);
    }

    // Paper gives some event spawns their explicit producer reason at insertion.
    // Keep existing producer data (conversion/status effect/dropper) when present.
    public static void bridgeSpawnReason(Entity entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
        if (reason == null || entity.carpetLifetimeTarget == null || entity.carpetLifetimeTarget.pendingReason != null) return;
        String name = switch (reason.name()) {
            case "NATURAL", "VILLAGE_INVASION", "PATROL" -> "natural";
            case "SPAWNER" -> "spawner";
            case "BREEDING" -> "breeding";
            case "COMMAND" -> "command";
            case "BUILD_IRONGOLEM", "BUILD_SNOWMAN", "BUILD_WITHER", "VILLAGE_DEFENSE", "SPELL" -> "summon";
            case "NETHER_PORTAL" -> "portal_pigman";
            case "RAID" -> "raid";
            case "REINFORCEMENTS" -> "zombie_reinforce";
            case "MOUNT" -> "jockey_mount";
            case "JOCKEY" -> "jockey";
            default -> null;
        };
        if (name != null) markSpawn(entity, name);
    }

    public static void markJockeyMount(Entity vehicle) {
        if (vehicle.carpetLifetimeTarget != null && "jockey".equals(vehicle.carpetLifetimeTarget.pendingReason)
            && !vehicle.carpetLifetimeTarget.spawned) markSpawn(vehicle, "jockey_mount");
    }

    public static void markSpawn(Entity entity, String reason, JsonObject data) {
        if (entity.carpetLifetimeTarget != null) entity.carpetLifetimeTarget.pendingSpawn(reason, data);
    }

    public static void markSpawn(Entity entity, String reason) { markSpawn(entity, reason, new JsonObject()); }
    public static void markSpawnWithPassengers(Entity entity, String reason) {
        entity.getSelfAndPassengers().forEach(target -> markSpawn(target, reason));
    }
    public static void beforeTeleport(Entity entity, ServerLevel destination) {
        if (entity.level() == destination) return;
        JsonObject removal = new JsonObject();
        removal.addProperty("toDimension", destination.dimension().identifier().toString());
        removed(entity, "trans_dimension", removal);
    }
    public static void afterTeleport(Entity original, Entity copy, ServerLevel destination) {
        if (original.level() == destination) {
            // Folia recreates entities even for cross-region travel inside one world.
            // Preserve the original tracking identity rather than treating that copy as a new spawn.
            copy.carpetLifetimeTarget = original.carpetLifetimeTarget == null ? null : original.carpetLifetimeTarget.copyTo(copy);
            return;
        }
        JsonObject spawn = new JsonObject();
        spawn.addProperty("fromDimension", original.level().dimension().identifier().toString());
        markSpawn(copy, "trans_dimension", spawn);
    }
    public static void removed(Entity entity, String reason) {
        if (entity.carpetLifetimeTarget != null) entity.carpetLifetimeTarget.remove(reason, new JsonObject(), false, null);
    }
    public static void removed(Entity entity, String reason, JsonObject data) {
        if (entity.carpetLifetimeTarget != null) entity.carpetLifetimeTarget.remove(reason, data, false, null);
    }
    public static void removedAmount(Entity entity, String reason, long amount) {
        if (entity.carpetLifetimeTarget != null) entity.carpetLifetimeTarget.remove(reason, new JsonObject(), false, amount);
    }
    public static void removedAmount(Entity entity, String reason, JsonObject data, long amount) {
        if (entity.carpetLifetimeTarget != null) entity.carpetLifetimeTarget.remove(reason, data, false, amount);
    }
    public static JsonObject noDamage() {
        JsonObject data = new JsonObject();
        data.add("damageSource", com.google.gson.JsonNull.INSTANCE);
        return data;
    }
    public static void markExperienceSpawn(ExperienceOrb orb, org.bukkit.entity.ExperienceOrb.SpawnReason reason, Entity source) {
        if (reason == null) return;
        switch (reason.name()) {
            case "BLOCK_BREAK" -> markSpawn(orb, "block_drop");
            case "EXP_BOTTLE" -> markSpawn(orb, "item");
            case "ENTITY_DEATH", "BREED" -> { if (source != null) markSpawn(orb, "mob_drop", related(source, "dropperType")); }
            default -> { }
        }
    }
    public static void removedFromCap(Entity entity, String reason) {
        if (entity.carpetLifetimeTarget != null) entity.carpetLifetimeTarget.remove(reason, new JsonObject(), true, null);
    }
    public static void returnedToCap(Entity entity, String reason) {
        if (entity.carpetLifetimeTarget != null) entity.carpetLifetimeTarget.spawn(reason, new JsonObject(), true);
    }

    public static final class Target {
        final Entity entity;
        final Session session;
        private boolean spawned;
        private boolean removed;
        private long born;
        private Vec3 spawnPosition;
        private String dimension;
        private String pendingReason;
        private JsonObject pendingData;
        private String deathDamage;

        private Target(Entity entity, Session session) { this.entity = entity; this.session = session; }
        private Target copyTo(Entity replacement) {
            Target copy = new Target(replacement, session);
            copy.spawned = spawned; copy.removed = removed; copy.born = born;
            copy.spawnPosition = spawnPosition; copy.dimension = dimension;
            copy.pendingReason = pendingReason; copy.pendingData = pendingData;
            copy.deathDamage = deathDamage;
            return copy;
        }
        boolean current() { return ACTIVE.get() == session; }
        long clock() { return ((ServerLevel) entity.level()).getCurrentWorldData().getRedstoneGameTime(); }

        public void shiftClock(long offset) { if (spawned) born += offset; }
        public void damage(DamageSource source) { deathDamage = source.getMsgId(); }
        public void pendingSpawn(String reason, JsonObject data) { if (current()) { pendingReason = reason; pendingData = data.deepCopy(); } }
        public void addedToWorld() {
            if (pendingReason != null) spawn(pendingReason, pendingData, false);
        }

        public void spawn(String reason, JsonObject data, boolean backToCap) {
            if (!current()) return;
            if (spawned && removed && backToCap && GeneralCompatConfig.lifeTimeTrackerConsidersMobcap) { spawned = false; removed = false; }
            if (spawned || !filtered(entity)) return;
            if (GeneralCompatConfig.lifeTimeTrackerConsidersMobcap && entity instanceof Mob mob
                && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) return;
            spawned = true;
            spawnPosition = entity.position();
            born = clock();
            dimension = entity.level().dimension().identifier().toString();
            Reason key = new Reason(reason, GSON.toJson(data));
            session.stats(dimension, entity.getType()).spawn(key, extra(entity));
            record("spawning", reason, data, 0L, spawnPosition);
        }

        public void remove(String reason, JsonObject data, boolean fromCap, Long amount) {
            if (!current() || !spawned || removed) return;
            if (fromCap && (!GeneralCompatConfig.lifeTimeTrackerConsidersMobcap || !(entity instanceof Mob))) return;
            removed = true;
            long lifetime = clock() - born;
            Vec3 position = entity.position();
            Point point = new Point(lifetime, dimension, spawnPosition, position);
            session.stats(dimension, entity.getType()).remove(new Reason(reason, GSON.toJson(data)), amount == null ? extra(entity) : amount, point);
            record("removal", reason, data, lifetime, position);
        }

        public void fallbackRemoval(org.bukkit.event.entity.EntityRemoveEvent.Cause cause) {
            if (cause != null) {
                String explicit = switch (cause) {
                    case EXPLODE -> "exploded";
                    case MERGE -> "merge";
                    case OUT_OF_WORLD -> "void";
                    case PLAYER_QUIT -> "player_logout";
                    default -> null;
                };
                if (explicit != null) { remove(explicit, new JsonObject(), false, null); return; }
            }
            if (entity instanceof net.minecraft.world.entity.LivingEntity living && !living.isDeadOrDying()) deathDamage = null;
            if (deathDamage == null) remove("other", new JsonObject(), false, null);
            else {
                JsonObject data = new JsonObject();
                data.addProperty("damageSource", deathDamage);
                remove("death", data, false, null);
            }
        }

        private void record(String type, String reason, JsonObject data, long lifetime, Vec3 eventPosition) {
            if (!TisLifetimeRecorder.isRecording()) return;
            JsonObject record = new JsonObject();
            record.addProperty("serverTick", ((ServerLevel) entity.level()).getServer().getTickCount());
            record.addProperty("gameTime", CarpetServerClock.gameTime());
            record.addProperty("eventType", type);
            record.addProperty("eventId", reason);
            record.add("eventPosition", vector(eventPosition));
            record.add("eventData", data.deepCopy());
            record.addProperty("entityType", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            record.addProperty("entityId", entity.getId());
            record.addProperty("entityUuid", entity.getStringUUID());
            record.add("entityPosition", vector(entity.position()));
            record.addProperty("entityDimension", entity.level().dimension().identifier().toString());
            record.addProperty("entityLifetime", lifetime);
            TisLifetimeRecorder.add(GSON.toJson(record));
        }
    }

    private static JsonArray vector(Vec3 position) {
        JsonArray array = new JsonArray();
        array.add(position.x); array.add(position.y); array.add(position.z);
        return array;
    }

    private static long extra(Entity entity) {
        if (entity instanceof ItemEntity item) return item.getItem().getCount();
        if (entity instanceof ExperienceOrb orb) return orb.getValue() * (long) orb.count;
        return 0L;
    }

    private record Key(String dimension, String type) { }
    record Reason(String id, String data) { }
    record Point(long time, String dimension, Vec3 spawn, Vec3 removal) { }
    record Lifetime(long count, long sum, Point minimum, Point maximum) { }
    record ReasonSummary(long count, long extra, Lifetime lifetime) { }
    record Summary(long spawned, long removed, long spawnExtra, long removeExtra, Lifetime lifetime,
                           Map<Reason, ReasonSummary> spawnReasons, Map<Reason, ReasonSummary> removeReasons) { }

    private static final class Session {
        final int id;
        final long startTick;
        final long startMillis;
        final Map<Key, Stats> statistics = new ConcurrentHashMap<>();
        Session(int id, long startTick, long startMillis) { this.id = id; this.startTick = startTick; this.startMillis = startMillis; }
        Stats stats(String dimension, EntityType<?> type) {
            return statistics.computeIfAbsent(new Key(dimension, BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath()), ignored -> new Stats());
        }
    }

    private static final class MutableLifetime {
        long count;
        long sum;
        Point minimum;
        Point maximum;
        void add(Point point) {
            ++count; sum += point.time;
            if (minimum == null || point.time < minimum.time) minimum = point;
            if (maximum == null || point.time > maximum.time) maximum = point;
        }
        Lifetime snapshot() { return new Lifetime(count, sum, minimum, maximum); }
    }
    private static final class MutableReason {
        long count;
        long extra;
        final MutableLifetime lifetime = new MutableLifetime();
        ReasonSummary snapshot() { return new ReasonSummary(count, extra, lifetime.snapshot()); }
    }
    static final class Stats {
        long spawned;
        long removed;
        long spawnExtra;
        long removeExtra;
        final MutableLifetime lifetime = new MutableLifetime();
        final Map<Reason, MutableReason> spawnReasons = new HashMap<>();
        final Map<Reason, MutableReason> removeReasons = new HashMap<>();
        synchronized void spawn(Reason key, long extra) {
            ++spawned; spawnExtra += extra;
            MutableReason reason = spawnReasons.computeIfAbsent(key, ignored -> new MutableReason());
            ++reason.count; reason.extra += extra;
        }
        synchronized void remove(Reason key, long extra, Point point) {
            ++removed; removeExtra += extra; lifetime.add(point);
            MutableReason reason = removeReasons.computeIfAbsent(key, ignored -> new MutableReason());
            ++reason.count; reason.extra += extra; reason.lifetime.add(point);
        }
        synchronized Summary snapshot() {
            Map<Reason, ReasonSummary> spawns = new HashMap<>(), removals = new HashMap<>();
            spawnReasons.forEach((key, value) -> spawns.put(key, value.snapshot()));
            removeReasons.forEach((key, value) -> removals.put(key, value.snapshot()));
            return new Summary(spawned, removed, spawnExtra, removeExtra, lifetime.snapshot(), Map.copyOf(spawns), Map.copyOf(removals));
        }
    }

    private static void tell(CommandSourceStack source, String message) { TisRaycastCommand.feedback(source, message); }
    private static void component(CommandSourceStack source, Component message) {
        ServerPlayer player = source.getPlayer();
        if (player == null) source.sendSuccess(() -> message, false);
        else player.getBukkitEntity().taskScheduler.schedule(entity -> source.sendSuccess(() -> message, false), null, 1L);
    }
}
