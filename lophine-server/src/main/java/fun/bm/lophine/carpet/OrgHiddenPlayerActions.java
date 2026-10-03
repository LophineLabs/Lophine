// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.external.ScarpetNativeWork;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.bot.ServerBot;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Hidden Org actions retain original JSON and real native effects across Folia actor continuations.
 */
public final class OrgHiddenPlayerActions {
    private static final ConcurrentHashMap<UUID, Holder> PLAYERS = new ConcurrentHashMap<>();

    private OrgHiddenPlayerActions() {
    }

    private static final class Settings {
        static final boolean HIDDEN = readHidden();

        private static boolean readHidden() {
            Path file = Path.of("config", "carpetorgaddition", "carpet-org-addition.json");
            if (!Files.isRegularFile(file)) return false;
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
                return object.has("enableHiddenFunction") && object.get("enableHiddenFunction").getAsBoolean();
            } catch (Exception failure) {
                MinecraftServer.LOGGER.error("Cannot load Carpet Org hidden function settings", failure);
                return false;
            }
        }
    }

    public static boolean enabled() {
        return Settings.HIDDEN;
    }

    public static boolean debug() {
        return net.minecraft.SharedConstants.IS_RUNNING_IN_IDE
                && ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch(argument -> argument.contains("jdwp"));
    }

    private static Holder holder(ServerPlayer player) {
        own(player);
        return PLAYERS.compute(player.getUUID(), (id, existing) -> existing == null || existing.player != player ? new Holder(player) : existing);
    }

    private static void own(ServerPlayer player) {
        TickThread.ensureTickThread(player, "Org hidden player action must own its player");
        if (!(player instanceof ServerBot))
            throw new IllegalArgumentException("Hidden action requires an online fake player");
    }

    private static final class Holder {
        final ServerPlayer player;
        final CarpetActionCompletion completion = new CarpetActionCompletion();
        Engine action;
        RuntimeException pendingDebug;

        Holder(ServerPlayer player) {
            this.player = player;
        }
    }

    public static void setPlant(ServerPlayer player) {
        assign(player, new OrgHiddenPlant(player));
    }

    public static void setGotoBlock(ServerPlayer player, BlockPos target) {
        assign(player, new Goto(player, target.immutable(), null));
    }

    public static void setGotoEntity(ServerPlayer player, Entity target) {
        own(player);
        if (!enabled()) throw new IllegalStateException("Org hidden functions are disabled");
        var actual = TisCommandContinuations.then(carpet.script.external.ScarpetRuntime.atEntityFuture(target, () -> target.getDisplayName().copy()),
                displayName -> OrgHiddenNative.owner(player, () -> {
                    assign(player, new Goto(player, null, target, displayName));
                    return null;
                }));
        ScarpetNativeWork.record(actual);
    }

    public static void setBedrockCuboid(ServerPlayer player, BlockPos from, BlockPos to, boolean ai, boolean recycle) {
        assign(player, new OrgHiddenBedrock(player, OrgHiddenBedrockSelection.cuboid(from, to), ai, recycle));
    }

    public static void setBedrockCylinder(ServerPlayer player, BlockPos center, int radius, int height, boolean ai, boolean recycle) {
        assign(player, new OrgHiddenBedrock(player, OrgHiddenBedrockSelection.cylinder(center, radius, height), ai, recycle));
    }

    private static void assign(ServerPlayer player, Engine action) {
        if (!enabled()) throw new IllegalStateException("Org hidden functions are disabled");
        assignLoaded(player, action);
    }

    private static void assignLoaded(ServerPlayer player, Engine action) {
        own(player);
        // Public set clears the old hidden action through its reverse hook; assignment follows it.
        OrgFakePlayerActions.set(player, OrgFakePlayerActions.Action.simple("stop", List.of()));
        stopOwner(player);
        holder(player).action = action;
    }

    public static void raise(ServerPlayer player, String message) {
        if (!debug()) throw new IllegalStateException("Org debug actions require the development debug environment");
        holder(player).pendingDebug = new DebugTriggerException(message);
    }

    private static final class DebugTriggerException extends RuntimeException {
        DebugTriggerException(String message) {
            super(message);
        }
    }

    public static void tick(ServerPlayer player) {
        if (!(player instanceof ServerBot)) return;
        own(player);
        Holder holder = PLAYERS.get(player.getUUID());
        if (holder == null || holder.player != player || holder.completion.paused()) return;
        if (holder.pendingDebug != null) {
            RuntimeException failure = holder.pendingDebug;
            holder.pendingDebug = null;
            fail(player, holder, failure);
            return;
        }
        Engine action = holder.action;
        if (action == null || action.pending != null || action.cancelled) return;
        if (carpet.script.external.ScarpetPlayerInventoryGate.paused(player)) return;
        if (!enabled()) {
            fail(player, holder, new IllegalStateException("Org hidden action is disabled"));
            return;
        }
        if (action.world != player.level()) {
            action.area.close();
            action.world = player.level();
        }
        action.jobArea = action.footprint();
        if (!action.area.ready(player, action.jobArea)) return;
        var accepted = holder.completion.begin();
        var nativeDone = new CompletableFuture<Void>();
        var pendingMarker = new CompletableFuture<Void>();
        action.pending = pendingMarker;
        var full = ScarpetNativeWork.<Void>observeNative(player, () -> {
            var root = ScarpetNativeWork.capture();
            action.jobToken = root;
            ScarpetNativeWork.record(nativeDone);
            // The final owner flag commit depends on this root but cannot be recorded inside it:
            // its final cleanup runs after all dynamic native children have really terminated.
            ScarpetNativeWork.aliasDependency(accepted.future(), nativeDone);
            carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(player, accepted.future());
            var operation = OrgGameplayHelper.withOrgAction(() -> action.ownerFuture(action::tick));
            ScarpetNativeWork.record(operation);
            operation.whenComplete((ignored, failure) -> CarpetNativeActionContext.inNative(root,
                    () -> OrgHiddenNative.owner(player, () -> {
                        if (failure != null && holder.action == action && !worldChanged(failure))
                            fail(player, holder, failure);
                        return null;
                    }).whenComplete((committed, commitFailure) -> {
                        if (failure != null) nativeDone.completeExceptionally(failure);
                        else if (commitFailure != null) nativeDone.completeExceptionally(commitFailure);
                        else nativeDone.complete(null);
                    })));
            return null;
        });
        carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(player, full);
        full.whenComplete((ignored, nativeFailure) -> OrgHiddenNative.snapshotOwner(player, () -> {
            action.pending = null;
            action.jobArea = null;
            if (action.cancelled || holder.action != action) action.area.close();
            return null;
        }).whenComplete((committed, commitFailure) -> {
            Throwable failure = nativeFailure != null ? nativeFailure : commitFailure;
            accepted.finish(failure);
            if (failure == null) pendingMarker.complete(null);
            else {
                action.area.close();
                pendingMarker.completeExceptionally(failure);
            }
        }));
    }

    private static boolean worldChanged(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof WorldChanged) return true;
        return false;
    }

    private static final class WorldChanged extends RuntimeException {
    }

    private static void fail(ServerPlayer player, Holder holder, Throwable failure) {
        MinecraftServer.LOGGER.error("Org fake player {} script action failed", player.getScoreboardName(), failure);
        stopOwner(player);
        OrgFakePlayerActions.set(player, OrgFakePlayerActions.Action.simple("stop", List.of()));
        var message = Component.literal("Org player action failed for " + player.getScoreboardName() + ": " + failure.getMessage());
        var server = player.level().getServer();
        OrgCommandNativeEffects.global(server, () -> {
            OrgCommandNativeEffects.broadcast(server, message);
            return null;
        });
    }

    public static void stopOwner(ServerPlayer player) {
        own(player);
        Holder holder = PLAYERS.get(player.getUUID());
        if (holder == null || holder.player != player) return;
        Engine action = holder.action;
        holder.action = null;
        if (action != null) {
            action.cancelled = true;
            action.stop();
            if (action.pending == null) action.area.close();
            else action.pending.whenComplete((ignored, failure) -> action.area.close());
        }
    }

    /**
     * Retirement performs metadata cleanup; accepted tails keep their own range until completion.
     */
    public static void onRetired(ServerPlayer player) {
        Holder holder = PLAYERS.get(player.getUUID());
        if (holder == null || holder.player != player || !PLAYERS.remove(player.getUUID(), holder)) return;
        Engine action = holder.action;
        if (action != null) {
            action.cancelled = true;
            if (action.pending == null) action.area.close();
            else action.pending.whenComplete((ignored, failure) -> action.area.close());
        }
    }

    public static CompletableFuture<Void> pendingCompletion(ServerPlayer player) {
        return holder(player).completion.pendingCompletion();
    }

    public static <T> CompletableFuture<T> whenIdle(ServerPlayer player, Supplier<T> snapshot) {
        return holder(player).completion.whenIdle(work -> OrgHiddenNative.snapshotOwnerFuture(player,
                () -> player.carpetActionPack.whenIdle(work)), snapshot);
    }

    public static <T> CompletableFuture<T> whenIdleForRemoval(ServerPlayer player, Supplier<T> snapshot) {
        return holder(player).completion.whenIdleAfterTermination(work -> OrgHiddenNative.snapshotOwnerFuture(player,
                () -> player.carpetActionPack.whenIdleForRemoval(work)), snapshot);
    }

    public static JsonObject get(ServerPlayer player) {
        Engine action = holder(player).action;
        var result = new JsonObject();
        result.addProperty("name", action == null ? "stop" : action.name());
        result.add("data", action == null ? new JsonObject() : action.data());
        return result;
    }

    public static List<Component> info(ServerPlayer player) {
        Engine action = holder(player).action;
        return action == null ? List.of() : action.info();
    }

    static net.minecraft.network.chat.MutableComponent localized(String key, String fallback, Object... values) {
        return Component.translatableWithFallback(key, OrgRuleTranslations.text(key, fallback), values);
    }

    static Component coordinates(BlockPos position) {
        return Component.literal("[" + position.toShortString() + "]").withStyle(net.minecraft.ChatFormatting.GREEN)
                .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.CopyToClipboard(position.getX() + " " + position.getY() + " " + position.getZ())));
    }

    public static JsonObject save(ServerPlayer player) {
        Engine action = holder(player).action;
        var result = new JsonObject();
        // Upstream GotoAction deliberately persists as STOP.
        result.add(action == null || action instanceof Goto ? "stop" : action.name(), action == null || action instanceof Goto ? new JsonObject() : action.data());
        return result;
    }

    public static boolean load(ServerPlayer player, JsonObject json) {
        own(player);
        String name;
        JsonObject data;
        if (json.has("name")) {
            name = json.get("name").getAsString();
            data = json.has("data") ? json.getAsJsonObject("data") : new JsonObject();
        } else {
            if (json.size() != 1) return false;
            var entry = json.entrySet().iterator().next();
            name = entry.getKey();
            data = entry.getValue().getAsJsonObject();
        }
        name = name.toLowerCase(java.util.Locale.ROOT);
        if (name.equals("goto")) {
            stopOwner(player);
            return true;
        }
        if (name.equals("plant")) {
            assignLoaded(player, new OrgHiddenPlant(player));
            return true;
        }
        if (name.equals("bedrock")) {
            var selection = OrgHiddenBedrockSelection.read(data);
            if (selection == null) {
                OrgFakePlayerActions.set(player, OrgFakePlayerActions.Action.simple("stop", List.of()));
                return true;
            }
            boolean ai = data.has("ai") && data.get("ai").getAsBoolean();
            boolean recycle = data.has("timed_material_recycling") && data.get("timed_material_recycling").getAsBoolean();
            assignLoaded(player, new OrgHiddenBedrock(player, selection, ai, recycle));
            return true;
        }
        return false;
    }

    /**
     * Pure parse guard used before creating a bot from stored action data.
     */
    public static boolean accepts(JsonObject json) {
        String name = serializedName(json);
        return name.equals("plant") || name.equals("goto") || name.equals("bedrock");
    }

    public static void validate(JsonObject json) {
        String name = serializedName(json);
        if (!name.equals("plant") && !name.equals("goto") && !name.equals("bedrock"))
            throw new IllegalArgumentException("Unsupported hidden action: " + name);
        JsonObject data = json.has("name") ? (json.has("data") ? json.getAsJsonObject("data") : new JsonObject()) : json.entrySet().iterator().next().getValue().getAsJsonObject();
        if (name.equals("bedrock")) {
            OrgHiddenBedrockSelection.read(data);
            if (data.has("ai")) data.get("ai").getAsBoolean();
            if (data.has("timed_material_recycling")) data.get("timed_material_recycling").getAsBoolean();
        }
    }

    private static String serializedName(JsonObject json) {
        if (json.has("name")) return json.get("name").getAsString().toLowerCase(java.util.Locale.ROOT);
        if (json.size() != 1)
            throw new IllegalArgumentException("Player action must contain exactly one script action");
        return json.entrySet().iterator().next().getKey().toLowerCase(java.util.Locale.ROOT);
    }

    public static CompoundTag sanitizeActionPackSnapshot(ServerPlayer player, CompoundTag original) {
        own(player);
        CompoundTag copy = original.copy();
        Holder holder = PLAYERS.get(player.getUUID());
        Engine action = holder == null ? null : holder.action;
        if (action != null && action.movementGeneration >= 0 && action.movementGeneration == player.carpetActionPack.getMovementGeneration()) {
            copy.putFloat("forward", 0);
            copy.putFloat("strafing", 0);
            copy.putBoolean("sneaking", false);
        }
        if (action != null && action.ownedAttack != null && !action.ownedAttack.done
                && player.carpetActionPack.getAction(CarpetPlayerActionPack.ActionType.ATTACK) == action.ownedAttack) {
            var actions = copy.getList("actions").orElseGet(net.minecraft.nbt.ListTag::new);
            actions.removeIf(tag -> tag instanceof CompoundTag record && record.getStringOr("type", "").equals("ATTACK"));
            copy.put("actions", actions);
            copy.getCompound("completedAttempts").ifPresent(attempts -> attempts.remove("ATTACK"));
        }
        return copy;
    }

    static void path(ServerPlayer player, List<Vec3> nodes) {
        OrgHiddenPathProtocol.path(player, nodes);
    }

    abstract static class Engine {
        final ServerPlayer player;
        ServerLevel world;
        final OrgHiddenInventory inventory;
        final OrgHiddenExcavator excavator;
        final CarpetPlayerTargetArea area = new CarpetPlayerTargetArea();
        CompletableFuture<Void> pending;
        AABB jobArea;
        ScarpetNativeWork.Token jobToken;
        volatile boolean cancelled;
        long movementGeneration = -1;
        CarpetPlayerActionPack.Action ownedAttack;

        Engine(ServerPlayer player) {
            own(player);
            this.player = player;
            this.world = player.level();
            inventory = new OrgHiddenInventory(player);
            excavator = new OrgHiddenExcavator(player);
        }

        abstract String name();

        abstract AABB footprint();

        abstract CompletableFuture<Void> tick();

        JsonObject data() {
            return new JsonObject();
        }

        List<Component> info() {
            return List.of(localized("carpet-org-addition.command.playerAction." + name() + ".info", "%s is plant", player.getDisplayName()));
        }

        void stop() {
        }

        void claimMovement() {
            movementGeneration = player.carpetActionPack.getMovementGeneration();
        }

        <T> CompletableFuture<T> owner(Supplier<T> work) {
            return CarpetNativeActionContext.inNative(jobToken, () -> OrgHiddenNative.ownerFuture(player, () -> {
                if (player.level() != world) throw new WorldChanged();
                if (cancelled) throw new java.util.concurrent.CancellationException("Hidden action stopped");
                AABB footprint = footprint();
                if (jobArea != null) footprint = footprint.minmax(jobArea);
                if (!area.ready(player, footprint)) {
                    return OrgHiddenNative.laterOwner(player, () -> owner(work)).thenCompose(result -> result);
                }
                try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)) {
                    return CompletableFuture.completedFuture(work.get());
                }
            }));
        }

        <T> CompletableFuture<T> ownerFuture(Supplier<CompletableFuture<T>> work) {
            return owner(work).thenCompose(result -> result);
        }

        CompletableFuture<InteractionResult> click(InteractionHand hand, BlockHitResult hit) {
            return ownerFuture(() -> OrgHiddenNative.click(player, hand, hit));
        }
    }

    private record Target(ServerLevel world, BlockPos block, Vec3 position, boolean destroyed) {
    }

    private static final class Goto extends Engine {
        private BlockPos target;
        private final UUID entityId;
        private Entity entity;
        private Target snapshot;
        private long lastUpdate;
        private final OrgHiddenPathfinder pathfinder;
        private final Component displayName;
        private boolean initialized;

        Goto(ServerPlayer player, BlockPos target, Entity entity) {
            this(player, target, entity, null);
        }

        Goto(ServerPlayer player, BlockPos target, Entity entity, Component displayName) {
            super(player);
            this.target = target;
            this.entity = entity;
            this.entityId = entity == null ? null : entity.getUUID();
            this.displayName = entity == null ? coordinates(target) : displayName == null ? Component.literal(entityId.toString()) : displayName.copy();
            this.pathfinder = new OrgHiddenPathfinder(() -> player, () -> java.util.Optional.ofNullable(this.target));
            lastUpdate = world.getGameTime() - 60;
        }

        String name() {
            return "goto";
        }

        List<Component> info() {
            return List.of(localized("carpet-org-addition.command.playerAction.goto.info." + (entityId == null ? "block" : "entity"),
                    entityId == null ? "%s is moving to %s" : "%s is following %s", player.getDisplayName(), displayName));
        }

        JsonObject data() {
            var json = new JsonObject();
            json.addProperty("target_type", entityId == null ? "block" : "entity");
            if (entityId != null) json.addProperty("entity", entityId.toString());
            else if (target != null) {
                var position = new com.google.gson.JsonArray();
                position.add(target.getX());
                position.add(target.getY());
                position.add(target.getZ());
                json.add("target", position);
            }
            return json;
        }

        AABB footprint() {
            return new AABB(player.blockPosition()).inflate(50);
        }

        CompletableFuture<Void> tick() {
            long now = world.getGameTime();
            boolean force = entityId != null && pathfinder.isFinished();
            boolean scheduled = now - lastUpdate >= 60;
            if (entityId != null && (snapshot == null || force || scheduled)) {
                // Finished-path distance checks must not postpone the source's regular 60-tick cache update.
                if (snapshot == null || scheduled) lastUpdate = now;
                Entity reference = entity;
                CompletableFuture<Target> query = reference == null ? CompletableFuture.completedFuture(null)
                        : carpet.script.external.ScarpetRuntime.atEntityFuture(reference, () -> {
                    boolean dead = reference.isRemoved() && (!(reference instanceof LivingEntity living) || living.isDeadOrDying()
                            || reference.getRemovalReason() != null && reference.getRemovalReason().shouldDestroy());
                    return new Target((ServerLevel) reference.level(), reference.blockPosition().immutable(), reference.position(), dead);
                }).handle((captured, failure) -> failure == null ? captured : null);
                return query.thenCompose(captured -> {
                            if (captured != null && !captured.destroyed) return CompletableFuture.completedFuture(captured);
                            return carpet.script.external.ScarpetRuntime.atGlobalFuture(world.getServer(), () -> {
                                Entity refreshed = world.getServer().getPlayerList().getPlayer(entityId);
                                if (refreshed == null) for (ServerLevel level : world.getServer().getAllLevels()) {
                                    refreshed = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level).moonrise$getEntityLookup().get(entityId);
                                    if (refreshed != null) break;
                                }
                                return refreshed;
                            }).thenCompose(refreshed -> {
                                if (refreshed == null) return CompletableFuture.completedFuture(captured);
                                entity = refreshed;
                                return carpet.script.external.ScarpetRuntime.atEntityFuture(refreshed, () ->
                                                new Target((ServerLevel) refreshed.level(), refreshed.blockPosition().immutable(), refreshed.position(), refreshed.isRemoved()))
                                        .handle((value, failure) -> failure == null ? value : null);
                            });
                        })
                        .thenCompose(captured -> owner(() -> {
                            if (captured == null) {
                                target = null;
                            } else {
                                boolean changed = target == null || !force || scheduled || Vec3.atBottomCenterOf(target).distanceTo(captured.position) > 3;
                                snapshot = captured;
                                if (captured.destroyed || captured.world != world) target = null;
                                else if (changed) target = captured.block;
                            }
                            if (entity != null) pathfinder.tick();
                            else pathfinder.stop();
                            claimMovement();
                            return null;
                        }));
            }
            if (entityId == null && initialized && pathfinder.isFinished())
                return CompletableFuture.completedFuture(null);
            initialized = true;
            pathfinder.tick();
            claimMovement();
            return CompletableFuture.completedFuture(null);
        }

        void stop() {
            pathfinder.onStop();
        }
    }
}
