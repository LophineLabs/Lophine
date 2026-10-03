package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetEntityIndex;
import carpet.script.external.ScarpetNativeWork;
import carpet.script.external.ScarpetRuntime;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Navigation state and every mutable entity sample remain on their actual actors.
 */
public final class OrgNavigation {
    private static final ConcurrentHashMap<UUID, Navigation> ACTIVE = new ConcurrentHashMap<>();

    private record Target(String dimension, Vec3 position, BlockPos blockPosition, Component name, boolean destroyed,
                          int entityId) {
        Target(String dimension, Vec3 position, BlockPos blockPosition, Component name, boolean destroyed) {
            this(dimension, position, blockPosition, name, destroyed, -1);
        }
    }

    private record Resolved(Entity reference, UUID identity, boolean player, boolean renewable, Target target,
                            Component displayName) {
    }

    private record NamedPoint(OrgWaypointStore.Waypoint waypoint, Component name) {
    }

    private static final class Navigation {
        final UUID observer, entity;
        final boolean continued, playerEntity;
        boolean named = true;
        final OrgWaypointStore.Waypoint waypoint;
        final MinecraftServer server;
        final AtomicReference<Target> target = new AtomicReference<>();
        final AtomicBoolean polling = new AtomicBoolean();
        volatile Entity reference;
        volatile boolean resolveEntity;
        final Supplier<CompletableFuture<Void>> poll;
        volatile ServerPlayer observerActor;
        boolean updated = true;
        boolean waypointWire;
        Target previousWire;
        String previousObserverDimension;

        Navigation(ServerPlayer observer, Resolved entity, boolean continued, OrgWaypointStore.Waypoint waypoint) {
            this.observer = observer.getUUID();
            this.observerActor = observer;
            this.server = observer.level().getServer();
            this.entity = entity == null ? null : entity.identity();
            this.playerEntity = entity != null && entity.player();
            this.reference = entity == null ? null : entity.reference();
            this.resolveEntity = entity != null && entity.renewable();
            this.continued = continued;
            this.waypoint = waypoint;
            target.set(entity == null ? new Target(waypoint.dimension(), Vec3.atCenterOf(waypoint.position()), waypoint.position(), Component.literal(waypoint.name()), false) : entity.target());
            previousWire = target.get();
            previousObserverDimension = target.get().dimension();
            // Recurring navigation is a separate periodic job. Keep pure flags without retaining
            // the original command callback, guest host, borrow or accepted player identity forever.
            this.poll = ScarpetRuntime.captureDetachedNativeContinuation(() -> refreshReported(this));
        }
    }

    private OrgNavigation() {
    }

    private static int command(CommandSourceStack source, Supplier<CompletableFuture<Boolean>> action) {
        var completion = CarpetAsyncCommandResults.defer(source);
        var actual = OrgMenuNativeEffects.admit(source.getServer(), action);
        var outcome = actual.handle((value, failure) -> new CommandOutcome(Boolean.TRUE.equals(value), failure));
        var reported = TisCommandContinuations.then(outcome, result -> {
            if (result.failure() == null) return CompletableFuture.completedFuture(result.success());
            Throwable cause = result.failure();
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
                cause = cause.getCause();
            Component error = cause instanceof CommandSyntaxException syntax && syntax.getRawMessage() instanceof Component component ? component : Component.literal(cause.getMessage() == null ? "Navigation failed" : cause.getMessage());
            return TisCommandContinuations.feedback(source, () -> {
                source.sendFailure(error);
                return false;
            });
        });
        var finished = reported.whenComplete(ScarpetRuntime.captureNativeConsumer((success, failure) -> completion.complete(failure == null && Boolean.TRUE.equals(success), failure == null && Boolean.TRUE.equals(success) ? 1 : 0)));
        ScarpetNativeWork.record(finished);
        return 1;
    }

    private record CommandOutcome(boolean success, Throwable failure) {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("navigate").requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.commandNavigate));
        for (String kind : java.util.List.of("entity", "player")) {
            root.then(Commands.literal(kind).then(Commands.argument(kind, kind.equals("player") ? EntityArgument.player() : EntityArgument.entity())
                    .executes(context -> entity(context.getSource(), EntityArgument.getEntity(context, kind), false))
                    .then(Commands.literal("continue").executes(context -> entity(context.getSource(), EntityArgument.getEntity(context, kind), true)))));
        }
        root.then(Commands.literal("uuid").then(Commands.argument("uuid", UuidArgument.uuid()).executes(context -> uuid(context.getSource(), UuidArgument.getUuid(context, "uuid")))));
        root.then(Commands.literal("waypoint").requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.commandLocations))
                .then(Commands.argument("waypoint", StringArgumentType.string()).suggests(OrgLocationsCommand.names()).executes(context -> waypointFile(context.getSource(), StringArgumentType.getString(context, "waypoint")))));
        root.then(Commands.literal("blockPos").then(Commands.argument("blockPos", BlockPosArgument.blockPos()).executes(context -> block(context.getSource(), BlockPosArgument.getBlockPos(context, "blockPos")))));
        root.then(Commands.literal("spawnpoint").executes(context -> spawnpoint(context.getSource())));
        root.then(Commands.literal("death").requires(source -> OrgServerPermissions.allowed(source, "navigate.death")).executes(context -> death(context.getSource(), context.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player()).executes(context -> death(context.getSource(), EntityArgument.getPlayer(context, "player")))));
        root.then(Commands.literal("stop").executes(context -> stop(context.getSource())));
        dispatcher.register(root);
    }

    private static Component message(ServerPlayer observer, Component destination) {
        return translated("start", "%s starts to neviagates -> %s", observer.getDisplayName(), destination);
    }

    private static net.minecraft.network.chat.MutableComponent translated(String suffix, String fallback, Object... arguments) {
        String key = "carpet-org-addition.command.navigate." + suffix;
        return Component.translatableWithFallback(key, OrgRuleTranslations.text(key, fallback), arguments);
    }

    private static Component simplePosition(BlockPos position) {
        return ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates", position.getX(), position.getY(), position.getZ()));
    }

    private static Component dimensionName(String dimension) {
        String path = dimension.substring(dimension.indexOf(':') + 1);
        if (!dimension.startsWith("minecraft:") || !java.util.Set.of("overworld", "the_nether", "the_end").contains(path))
            return Component.literal(dimension);
        String key = "carpet-org-addition.dimension." + path;
        return Component.translatableWithFallback(key, OrgRuleTranslations.text(key, path));
    }

    private static Component commandPosition(BlockPos position, String dimension) {
        ChatFormatting color = dimension.equals("minecraft:the_nether") ? ChatFormatting.RED : dimension.equals("minecraft:the_end") ? ChatFormatting.DARK_PURPLE : ChatFormatting.GREEN;
        var coordinates = simplePosition(position).copy().withStyle(style -> style
                .withClickEvent(new ClickEvent.CopyToClipboard(position.getX() + " " + position.getY() + " " + position.getZ()))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.copy.click"))));
        String key = "carpet-org-addition.button.highlight";
        var highlight = Component.literal(" [H]").withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand("/highlight " + position.getX() + " " + position.getY() + " " + position.getZ())).withHoverEvent(new HoverEvent.ShowText(Component.translatableWithFallback(key, OrgRuleTranslations.text(key, "Highlight")))));
        return Component.empty().append(coordinates).append(highlight).withStyle(color);
    }

    private static CompletableFuture<Boolean> feedback(CommandSourceStack source, Component message, boolean broadcast) {
        return broadcast ? TisCommandContinuations.then(OrgCommandNativeEffects.broadcast(source.getServer(), message.copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)), ignored -> CompletableFuture.completedFuture(true))
                : TisCommandContinuations.feedback(source, () -> {
            source.sendSuccess(() -> message, false);
            return true;
        });
    }

    private static CompletableFuture<Resolved> snapshot(Entity entity) {
        return TisCommandContinuations.owned(entity, () -> {
            boolean removed = entity.isRemoved();
            var reason = entity.getRemovalReason();
            boolean player = entity instanceof ServerPlayer;
            boolean destroyed = !player && (reason != null && reason.shouldDestroy() || entity instanceof net.minecraft.world.entity.LivingEntity living && living.isDeadOrDying());
            var point = new Target(entity.level().dimension().identifier().toString(), entity.getEyePosition(), entity.blockPosition().immutable(), entity.getName().copy(), destroyed, entity.getId());
            return new Resolved(entity, entity.getUUID(), player, removed && !destroyed, point, entity.getDisplayName().copy());
        });
    }

    private static CompletableFuture<Entity> find(MinecraftServer server, UUID identity) {
        return OrgCommandNativeEffects.global(server, () -> {
            for (var world : server.getAllLevels()) {
                Entity entity = ScarpetEntityIndex.reference(world, identity);
                if (entity != null) return entity;
            }
            // Native and legacy fake players can briefly have a roster entry between two index phases.
            return server.getPlayerList().getPlayer(identity);
        });
    }

    private static CompletableFuture<Boolean> assignEntity(CommandSourceStack source, ServerPlayer observer, Resolved resolved, boolean continued) {
        return TisCommandContinuations.then(TisCommandContinuations.owned(observer, () -> {
            var navigation = new Navigation(observer, resolved, continued, null);
            OrgNavigationProtocol.clear(observer);
            ACTIVE.put(navigation.observer, navigation);
            return message(observer, resolved.displayName());
        }), message -> feedback(source, message, resolved.player() && resolved.reference() != observer && !(resolved.reference() instanceof org.leavesmc.leaves.bot.ServerBot)));
    }

    private static int entity(CommandSourceStack source, Entity target, boolean continued) throws CommandSyntaxException {
        ServerPlayer observer = source.getPlayerOrException();
        return command(source, () -> TisCommandContinuations.then(snapshot(target), value -> assignEntity(source, observer, value, continued)));
    }

    private static int uuid(CommandSourceStack source, UUID identity) throws CommandSyntaxException {
        ServerPlayer observer = source.getPlayerOrException();
        return command(source, () -> TisCommandContinuations.then(find(source.getServer(), identity), entity -> entity == null ? CompletableFuture.failedFuture(EntityArgument.NO_ENTITIES_FOUND.create())
                : TisCommandContinuations.then(snapshot(entity), value -> value.renewable() ? CompletableFuture.failedFuture(EntityArgument.NO_ENTITIES_FOUND.create()) : assignEntity(source, observer, value, false))));
    }

    private static CompletableFuture<Boolean> assignWaypoint(CommandSourceStack source, ServerPlayer observer, OrgWaypointStore.Waypoint waypoint, boolean broadcast) {
        return assignPoint(source, observer, waypoint, Component.literal(waypoint.name()), Component.literal("[" + waypoint.name().split("\\.")[0] + "]"), true, broadcast, true);
    }

    private static CompletableFuture<Boolean> assignPoint(CommandSourceStack source, ServerPlayer observer, OrgWaypointStore.Waypoint waypoint, Component name, Component start, boolean named, boolean broadcast) {
        return assignPoint(source, observer, waypoint, name, start, named, broadcast, false);
    }

    private static CompletableFuture<Boolean> assignPoint(CommandSourceStack source, ServerPlayer observer, OrgWaypointStore.Waypoint waypoint, Component name, Component start, boolean named, boolean broadcast, boolean waypointWire) {
        return TisCommandContinuations.then(TisCommandContinuations.owned(observer, () -> {
            var navigation = new Navigation(observer, null, false, waypoint);
            navigation.named = named;
            navigation.waypointWire = waypointWire;
            Target target = navigation.target.get();
            navigation.target.set(new Target(target.dimension(), target.position(), target.blockPosition(), name, false));
            OrgNavigationProtocol.clear(observer);
            ACTIVE.put(navigation.observer, navigation);
            return message(observer, start);
        }), message -> feedback(source, message, broadcast));
    }

    private static int waypointFile(CommandSourceStack source, String name) throws CommandSyntaxException {
        ServerPlayer observer = source.getPlayerOrException();
        return command(source, () -> TisCommandContinuations.then(OrgCommandNativeEffects.file(source.getServer(), () -> {
            try {
                return OrgLocationsCommand.store(source.getServer()).load(name);
            } catch (java.io.IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }), waypoint -> assignWaypoint(source, observer, waypoint, false)));
    }

    private static int block(CommandSourceStack source, BlockPos requested) throws CommandSyntaxException {
        ServerPlayer observer = source.getPlayerOrException();
        return command(source, () -> TisCommandContinuations.then(TisCommandContinuations.owned(observer, () ->
                        new OrgWaypointStore.Waypoint(requested.toShortString(), requested.immutable(), observer.level().dimension().identifier().toString(), "", "", null)),
                waypoint -> assignPoint(source, observer, waypoint, Component.empty(), commandPosition(waypoint.position(), waypoint.dimension()), false, false)));
    }

    private static int spawnpoint(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer observer = source.getPlayerOrException();
        return command(source, () -> TisCommandContinuations.then(TisCommandContinuations.owned(observer, () -> {
            var respawn = observer.getRespawnConfig();
            if (respawn == null) return null;
            var data = respawn.respawnData();
            return new OrgWaypointStore.Waypoint("Spawnpoint", data.pos().immutable(), data.dimension().identifier().toString(), "", "", null);
        }), waypoint -> waypoint == null ? unable(source, observer, translated("name.spawnpoint", "Spawn Point")) : assignPoint(source, observer, waypoint, translated("name.spawnpoint", "Spawn Point"), translated("name.spawnpoint", "Spawn Point"), true, false)));
    }

    private static int death(CommandSourceStack source, ServerPlayer selected) throws CommandSyntaxException {
        ServerPlayer observer = source.getPlayerOrException();
        return command(source, () -> TisCommandContinuations.then(TisCommandContinuations.owned(selected, () -> {
            var previous = selected.getLastDeathLocation();
            if (previous.isEmpty()) return null;
            Component name = selected == observer ? translated("name.death", "Last Death") : translated("hud.of", "%2$s of %1$s", selected.getDisplayName(), translated("name.death", "Last Death"));
            var point = previous.get();
            return new NamedPoint(new OrgWaypointStore.Waypoint("Death", point.pos().immutable(), point.dimension().identifier().toString(), "", "", null), name);
        }), point -> point == null ? unable(source, selected, translated("name.death", "Last Death")) : assignPoint(source, observer, point.waypoint(), point.name(), point.name(), true, selected != observer)));
    }

    private static CompletableFuture<Boolean> unable(CommandSourceStack source, ServerPlayer target, Component destination) {
        return TisCommandContinuations.then(TisCommandContinuations.owned(target, () -> target.getDisplayName().copy()), name -> TisCommandContinuations.feedback(source, () -> {
            source.sendFailure(translated("unable_to_find", "Failed to find %2$s of %1$s", name, destination));
            return false;
        }));
    }

    private static int stop(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer observer = source.getPlayerOrException();
        return command(source, () -> TisCommandContinuations.owned(observer, () -> {
            ACTIVE.remove(observer.getUUID());
            OrgNavigationProtocol.clearManager(observer);
            observer.connection.send(new ClientboundSetActionBarTextPacket(translated("hud.stop", "Navigation ends")));
            return true;
        }));
    }

    private static CompletableFuture<Void> refresh(Navigation navigation) {
        if (ACTIVE.get(navigation.observer) != navigation) return CompletableFuture.completedFuture(null);
        CompletableFuture<Resolved> sampled = navigation.entity == null ? CompletableFuture.completedFuture(null)
                : navigation.resolveEntity || navigation.reference == null ? TisCommandContinuations.then(find(navigation.server, navigation.entity), renewed -> renewed == null ? CompletableFuture.completedFuture(null) : snapshot(renewed))
                : snapshot(navigation.reference);
        return TisCommandContinuations.then(sampled, resolved -> {
            if (resolved != null) {
                navigation.reference = resolved.reference();
                navigation.resolveEntity = resolved.renewable();
                if (!resolved.renewable()) navigation.target.set(resolved.target());
            } else if (navigation.entity != null && navigation.playerEntity) {
                Target last = navigation.target.get();
                if (last != null)
                    navigation.target.set(new Target(last.dimension(), last.position(), last.blockPosition(), last.name(), true));
            }
            ServerPlayer observer = navigation.observerActor;
            return TisCommandContinuations.owned(observer, () -> {
                if (!observer.isRemoved() && ACTIVE.get(navigation.observer) == navigation)
                    render(observer, navigation);
                return null;
            });
        });
    }

    private record UpdateOutcome(Throwable failure) {
    }

    private static CompletableFuture<Void> refreshReported(Navigation navigation) {
        CompletableFuture<Void> update;
        try {
            update = refresh(navigation);
        } catch (Throwable failure) {
            update = CompletableFuture.failedFuture(failure);
        }
        return TisCommandContinuations.then(update.handle((ignored, failure) -> new UpdateOutcome(failure)), outcome -> {
            if (outcome.failure() == null) return CompletableFuture.completedFuture(null);
            ACTIVE.remove(navigation.observer, navigation);
            return TisCommandContinuations.then(TisCommandContinuations.owned(navigation.observerActor, () -> {
                navigation.observerActor.connection.send(new ClientboundSetActionBarTextPacket(translated("error", "Navigator is encountering unexpected problems")));
                return null;
            }), ignored -> TisCommandContinuations.then(TisCommandContinuations.owned(navigation.observerActor, () -> {
                OrgNavigationProtocol.clearManager(navigation.observerActor);
                return null;
            }), cleared -> CompletableFuture.failedFuture(outcome.failure())));
        });
    }

    public static void tick(ServerPlayer observer) {
        Navigation navigation = ACTIVE.get(observer.getUUID());
        if (navigation == null) return;
        if (ScarpetNativeWork.isDraining(observer.level().getServer()) || !OrgUtilityCommands.permitted(observer.createCommandSourceStack(), GeneralCompatConfig.commandNavigate)) {
            ACTIVE.remove(navigation.observer, navigation);
            if (!ScarpetNativeWork.isDraining(observer.level().getServer()))
                OrgMenuNativeEffects.admit(navigation.server, () -> TisCommandContinuations.owned(observer, () -> {
                    OrgNavigationProtocol.clearManager(observer);
                    return null;
                }));
            return;
        }
        if (!navigation.polling.compareAndSet(false, true)) return;
        navigation.observerActor = observer;
        var actual = OrgMenuNativeEffects.admit(navigation.server, navigation.poll);
        actual.whenComplete((ignored, failure) -> {
            navigation.polling.set(false);
            if (failure != null) {
                ACTIVE.remove(navigation.observer, navigation);
                com.mojang.logging.LogUtils.getLogger().error("Carpet Org navigation update failed", failure);
            }
        });
    }

    public static void disconnected(ServerPlayer observer) {
        UUID identity = observer.getUUID();
        ACTIVE.computeIfPresent(identity, (ignored, navigation) -> navigation.observerActor == observer ? null : navigation);
    }

    /**
     * Source restoreFrom copies the navigator object without clearing or forcing an initial sync.
     */
    public static void copyFrom(ServerPlayer next, ServerPlayer previous) {
        Navigation old = ACTIVE.get(previous.getUUID());
        if (old == null || old.observerActor != previous) return;
        Target point = old.target.get();
        Resolved entity = old.entity == null ? null : new Resolved(old.reference, old.entity, old.playerEntity, old.resolveEntity, point, point.name());
        Navigation copy = new Navigation(next, entity, old.continued, old.waypoint);
        copy.target.set(point);
        copy.named = old.named;
        copy.waypointWire = old.waypointWire;
        copy.updated = false;
        ACTIVE.put(copy.observer, copy);
    }

    private static void render(ServerPlayer player, Navigation navigation) {
        Target target = navigation.target.get();
        if (target == null) return;
        String dimension = player.level().dimension().identifier().toString();
        BlockPos blockTarget = target.blockPosition;
        long roundedDistance = Math.round(Math.sqrt(player.blockPosition().distSqr(blockTarget)));
        boolean arrived = navigation.entity != null ? Math.sqrt(player.blockPosition().distSqr(blockTarget)) <= 8.0 : roundedDistance <= 8;
        if (!navigation.continued && dimension.equals(target.dimension) && arrived) {
            player.connection.send(new ClientboundSetActionBarTextPacket(translated("hud.reach", "You have reached the destination")));
            OrgNavigationProtocol.clearManager(player);
            ACTIVE.remove(player.getUUID(), navigation);
            return;
        }
        if (target.destroyed) {
            if (navigation.updated) syncWire(player, navigation, target, true);
            player.connection.send(new ClientboundSetActionBarTextPacket(translated("hud.target_death", "Target is killed or removed")));
            OrgNavigationProtocol.clearManager(player);
            ACTIVE.remove(player.getUUID(), navigation);
            return;
        }
        Vec3 destination = target.position;
        boolean matching = dimension.equals(target.dimension);
        boolean mapped = false;
        if (!matching && navigation.waypoint != null && navigation.waypoint.another() != null
                && ((dimension.equals("minecraft:overworld") && target.dimension.equals("minecraft:the_nether"))
                || (dimension.equals("minecraft:the_nether") && target.dimension.equals("minecraft:overworld")))) {
            destination = Vec3.atCenterOf(navigation.waypoint.another());
            matching = true;
            mapped = true;
        }
        Component text;
        if (matching) {
            double yaw = player.getYRot() + Math.toDegrees(Math.atan2(destination.x - player.getX(), destination.z - player.getZ()));
            yaw = yaw < 0 ? yaw + 360 : yaw;
            yaw = yaw > 180 ? yaw - 360 : yaw;
            int arrows = Math.abs(yaw) <= 3.0 ? 0 : Math.abs(yaw) <= 60.0 ? 1 : Math.abs(yaw) <= 100.0 ? 2 : 3;
            double pitch = player.getXRot() + Math.toDegrees(Math.atan2(destination.y - player.getEyeY(), Math.sqrt(Math.pow(player.getX() - destination.x, 2) + Math.pow(player.getZ() - destination.z, 2))));
            BlockPos displayed = navigation.entity == null ? BlockPos.containing(destination) : blockTarget;
            Component position = simplePosition(displayed);
            if (mapped) position = position.copy().withStyle(ChatFormatting.ITALIC);
            Component display = navigation.named ? translated("hud.in", "%s is in %s", target.name, position) : position;
            text = Component.literal(yaw > 0 ? "<".repeat(arrows) + " ".repeat(4 - arrows) : "    ").append(display)
                    .append(pitch >= 10 ? " ↑ " : pitch <= -10 ? " ↓ " : "   ")
                    .append(translated("hud.distance", "Distance: %s blocks away", (int) Math.round(Math.sqrt(player.blockPosition().distSqr(displayed)))))
                    .append(yaw < 0 ? " ".repeat(4 - arrows) + ">".repeat(arrows) : "    ");
        } else {
            Component display = Component.empty().append(dimensionName(target.dimension)).append(simplePosition(blockTarget));
            text = navigation.named ? translated("hud.in", "%s is in %s", target.name, display) : display;
        }
        if (navigation.updated) {
            syncWire(player, navigation, target, true);
            navigation.updated = false;
        }
        player.connection.send(new ClientboundSetActionBarTextPacket(text));
        syncWire(player, navigation, target, false);
    }

    private static void syncWire(ServerPlayer observer, Navigation navigation, Target target, boolean force) {
        String observerDimension = observer.level().dimension().identifier().toString();
        Target previous = navigation.previousWire;
        boolean changed = navigation.entity != null ? (previous == null || !previous.position().equals(target.position()) || !previous.dimension().equals(target.dimension())) : navigation.waypointWire && (navigation.previousObserverDimension == null || !navigation.previousObserverDimension.equals(observerDimension));
        if (force || changed) {
            Vec3 position = target.position();
            String dimension = target.dimension();
            if (navigation.entity == null && navigation.waypoint != null && navigation.waypoint.another() != null
                    && ((observerDimension.equals("minecraft:overworld") && dimension.equals("minecraft:the_nether")) || (observerDimension.equals("minecraft:the_nether") && dimension.equals("minecraft:overworld")))) {
                position = Vec3.atCenterOf(navigation.waypoint.another());
                dimension = observerDimension;
            }
            OrgNavigationProtocol.update(observer, position, dimension, target.entityId());
        }
        // Source prevWorld/prevPos updates after tick; the initial onUpdate does not update them.
        if (!force) {
            navigation.previousWire = target;
            navigation.previousObserverDimension = observerDimension;
        }
    }
}
