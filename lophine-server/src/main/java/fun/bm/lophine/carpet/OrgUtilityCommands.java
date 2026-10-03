package fun.bm.lophine.carpet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Org's utility commands, with all entity mutations routed to owning regions.
 */
public final class OrgUtilityCommands {
    private static final NamespacedKey SPECTATOR_ORIGIN = new NamespacedKey("lophine", "carpet_org_spectator_origin");
    private static final SimpleCommandExceptionType FAKE_ONLY = new SimpleCommandExceptionType(Component.literal("The selected player must be a fake player"));
    private static final SimpleCommandExceptionType WAIT_LAST = new SimpleCommandExceptionType(Component.literal("Wait for the previous creeper task to finish"));
    private static final Object CREEPER_CONSOLE = new Object();
    private static final ConcurrentHashMap<Object, UUID> CREEPER_TASKS = new ConcurrentHashMap<>();

    private OrgUtilityCommands() {
    }

    public static boolean committingSuicide() {
        return OrgSuicideCommand.synchronous();
    }

    public static boolean permitted(CommandSourceStack source, String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "0" -> true;
            case "1" -> Commands.LEVEL_MODERATORS.check(source.permissions());
            case "ops", "2" -> Commands.LEVEL_GAMEMASTERS.check(source.permissions());
            case "3" -> Commands.LEVEL_ADMINS.check(source.permissions());
            case "4" -> Commands.LEVEL_OWNERS.check(source.permissions());
            default -> false;
        };
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("killMe")
                .requires(source -> permitted(source, GeneralCompatConfig.commandKillMe))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    return OrgSuicideCommand.execute(context.getSource(), player);
                }));
        dispatcher.register(Commands.literal("ruleSearch")
                .requires(source -> permitted(source, GeneralCompatConfig.commandRuleSearch))
                .then(Commands.argument("rule", StringArgumentType.greedyString()).executes(context -> {
                    String input = StringArgumentType.getString(context, "rule");
                    String query = input.length() >= 2 && input.startsWith("\"") && input.endsWith("\"") ? input.substring(1, input.length() - 1) : input;
                    Component title = Component.literal(String.format(java.util.Locale.ROOT,
                                    OrgRuleTranslations.text("carpet.settings.command.mod_settings_matching", "%s settings matching \"%s\" "), "Carpet", query))
                            .withStyle(net.minecraft.ChatFormatting.BOLD);
                    context.getSource().sendSuccess(() -> title, false);
                    if (query.isEmpty()) return 0;
                    int matches = 0;
                    for (String rule : CarpetRuleRegistry.names()) {
                        String name = OrgRuleTranslations.name(rule);
                        String description = OrgRuleTranslations.description(rule);
                        if (!name.contains(query) && !description.contains(query)) continue;
                        Object value = CarpetRuleRegistry.get(rule).value();
                        Component row = Component.literal(name + " [" + rule + "] = " + value).withStyle(style -> style
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent.SuggestCommand("/carpet " + rule + " "))
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(description))));
                        if (!name.contains(query)) row = row.copy().withStyle(net.minecraft.ChatFormatting.ITALIC);
                        final Component displayed = row;
                        context.getSource().sendSuccess(() -> displayed, false);
                        matches++;
                    }
                    return matches;
                })));
        dispatcher.register(Commands.literal("spectator")
                .requires(source -> permitted(source, GeneralCompatConfig.commandSpectator))
                .executes(context -> toggleSpectator(context.getSource(), context.getSource().getPlayerOrException(), false))
                .then(Commands.argument("player", EntityArgument.player()).executes(context ->
                        toggleSpectator(context.getSource(), EntityArgument.getPlayer(context, "player"), true)))
                .then(Commands.literal("teleport")
                        .then(Commands.literal("dimension").then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(context -> teleportDimension(context.getSource(), DimensionArgument.getDimension(context, "dimension"), null))
                                .then(Commands.argument("location", Vec3Argument.vec3()).executes(context ->
                                        teleportDimension(context.getSource(), DimensionArgument.getDimension(context, "dimension"), Vec3Argument.getVec3(context, "location"))))))
                        .then(Commands.literal("location").then(Commands.argument("location", Vec3Argument.vec3()).executes(context ->
                                teleportDimension(context.getSource(), null, Vec3Argument.getVec3(context, "location")))))
                        .then(Commands.literal("entity").then(Commands.argument("entity", EntityArgument.entity()).executes(context ->
                                teleportEntity(context.getSource(), EntityArgument.getEntity(context, "entity")))))));
        dispatcher.register(Commands.literal("creeper")
                .requires(source -> permitted(source, GeneralCompatConfig.commandCreeper))
                .then(Commands.argument("player", EntityArgument.player()).executes(context ->
                        startCreeper(context.getSource(), EntityArgument.getPlayer(context, "player")))));
    }

    private static int toggleSpectator(CommandSourceStack source, ServerPlayer player, boolean fake) throws CommandSyntaxException {
        if (fake && !(player instanceof org.leavesmc.leaves.bot.ServerBot)) throw FAKE_ONLY.create();
        return OrgCommandNativeEffects.command(source, 1, () ->
                TisCommandContinuations.then(OrgMenuNativeEffects.run(player, () -> {
                    ServerPlayer target = player;
                    if (target.isSpectator()) {
                        if (fake) {
                            target.setGameMode(GameType.SURVIVAL);
                            return CompletableFuture.completedFuture(true);
                        }
                        return restoreOrigin(target);
                    } else if (fake) {
                        return OrgCommandNativeEffects.teleport(target, target.getBukkitEntity().getLocation().add(0.0, 0.2, 0.0), () -> target.setGameMode(GameType.SPECTATOR));
                    } else {
                        JsonObject origin = new JsonObject();
                        origin.addProperty("world", target.level().getWorld().getUID().toString());
                        origin.addProperty("x", target.getX());
                        origin.addProperty("y", target.getY());
                        origin.addProperty("z", target.getZ());
                        origin.addProperty("yaw", target.getYRot());
                        origin.addProperty("pitch", target.getXRot());
                        target.getBukkitEntity().getPersistentDataContainer().set(SPECTATOR_ORIGIN, PersistentDataType.STRING, origin.toString());
                        target.setGameMode(GameType.SPECTATOR);
                        return CompletableFuture.completedFuture(true);
                    }
                }).thenCompose(value -> value), success -> !success ? CompletableFuture.failedFuture(new IllegalStateException("Spectator change failed"))
                        : OrgMenuNativeEffects.run(player, () -> {
                    GameType mode = player.gameMode.getGameModeForPlayer();
                    player.sendOverlayMessage(Component.translatable("commands.gamemode.success.self", mode.getLongDisplayName()));
                    return mode == GameType.SURVIVAL ? 1 : 0;
                })));
    }

    private static CompletableFuture<Boolean> restoreOrigin(ServerPlayer player) {
        String serialized = player.getBukkitEntity().getPersistentDataContainer().get(SPECTATOR_ORIGIN, PersistentDataType.STRING);
        if (serialized == null) {
            var server = player.level().getServer();
            return TisCommandContinuations.then(OrgCommandNativeEffects.file(server, () -> OrgSpectatorOrigins.read(server, player.getUUID())), origin -> {
                if (origin == null) return OrgMenuNativeEffects.run(player, () -> {
                    player.setGameMode(GameType.SURVIVAL);
                    return true;
                });
                return TisCommandContinuations.then(OrgMenuNativeEffects.run(player, () -> {
                    var world = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                            net.minecraft.resources.Identifier.parse(origin.dimension())));
                    if (world == null) throw new IllegalStateException("The saved spectator world is unavailable");
                    return new Location(world.getWorld(), origin.x(), origin.y(), origin.z(), origin.yaw(), origin.pitch());
                }), destination -> TisCommandContinuations.then(OrgCommandNativeEffects.teleport(player, destination, () -> player.setGameMode(GameType.SURVIVAL)), success ->
                        !success ? CompletableFuture.completedFuture(false) : OrgCommandNativeEffects.file(server, () -> {
                            OrgSpectatorOrigins.remove(origin);
                            return true;
                        })));
            });
        }
        try {
            JsonObject origin = JsonParser.parseString(serialized).getAsJsonObject();
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(UUID.fromString(origin.get("world").getAsString()));
            if (world == null) {
                player.sendSystemMessage(Component.literal("The saved spectator world is unavailable"));
                return CompletableFuture.completedFuture(false);
            }
            Location destination = new Location(world, origin.get("x").getAsDouble(), origin.get("y").getAsDouble(), origin.get("z").getAsDouble(),
                    origin.get("yaw").getAsFloat(), origin.get("pitch").getAsFloat());
            return OrgCommandNativeEffects.teleport(player, destination, () -> {
                player.setGameMode(GameType.SURVIVAL);
                player.getBukkitEntity().getPersistentDataContainer().remove(SPECTATOR_ORIGIN);
            });
        } catch (RuntimeException exception) {
            player.sendSystemMessage(Component.literal("The saved spectator position cannot be read"));
            return CompletableFuture.completedFuture(false);
        }
    }

    private static boolean canTeleport(ServerPlayer player) {
        if (player.isSpectator() || player.isCreative()) return true;
        player.sendSystemMessage(Component.literal("Spectator or creative mode is required"));
        return false;
    }

    private static int teleportDimension(CommandSourceStack source, net.minecraft.server.level.ServerLevel requested, Vec3 position) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return OrgCommandNativeEffects.command(source, 1, () ->
                TisCommandContinuations.then(OrgMenuNativeEffects.run(player, () -> {
                    ServerPlayer target = player;
                    if (!canTeleport(target)) return null;
                    var destination = requested == null ? target.level() : requested;
                    double scale = position == null && target.level().dimension() == Level.OVERWORLD && destination.dimension() == Level.NETHER ? 0.125
                            : position == null && target.level().dimension() == Level.NETHER && destination.dimension() == Level.OVERWORLD ? 8.0 : 1.0;
                    Location location = new Location(destination.getWorld(), position == null ? target.getX() * scale : position.x,
                            position == null ? target.getY() : position.y, position == null ? target.getZ() * scale : position.z, target.getYRot(), target.getXRot());
                    Component feedback = position == null
                            ? Component.literal(String.format(java.util.Locale.ROOT, OrgRuleTranslations.text("carpet-org-addition.command.spectator.teleport.success", "%s is teleported to %s"),
                            target.getDisplayName().getString(), destination.dimension().identifier().toString()))
                            : Component.translatable("commands.teleport.success.location.single", target.getDisplayName(), coordinates(position.x), coordinates(position.y), coordinates(position.z));
                    return new TeleportPlan(location, feedback);
                }), plan -> completeTeleport(source, player, plan)));
    }

    private static int teleportEntity(CommandSourceStack source, Entity entity) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return OrgCommandNativeEffects.command(source, 1, () ->
                TisCommandContinuations.then(TisCommandContinuations.owned(entity, () -> {
                    if (entity.isRemoved()) throw new IllegalStateException("Teleport destination entity retired");
                    return new EntityDestination(entity.getBukkitEntity().getLocation(), entity.getDisplayName().copy());
                }), destination -> TisCommandContinuations.then(OrgMenuNativeEffects.run(player, () -> !canTeleport(player) ? null
                                : new TeleportPlan(destination.location(), Component.translatable("commands.teleport.success.entity.single", player.getDisplayName(), destination.name()))),
                        plan -> completeTeleport(source, player, plan))));
    }

    private record EntityDestination(Location location, Component name) {
    }

    private record TeleportPlan(Location location, Component feedback) {
    }

    private static String coordinates(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static CompletableFuture<Integer> completeTeleport(CommandSourceStack source, ServerPlayer player, TeleportPlan plan) {
        if (plan == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Spectator or creative mode is required"));
        return TisCommandContinuations.then(OrgCommandNativeEffects.teleport(player, plan.location(), () -> {
        }), success -> {
            if (!success) return CompletableFuture.failedFuture(new IllegalStateException("Spectator teleport failed"));
            return TisCommandContinuations.feedback(source, () -> {
                source.sendSuccess(plan::feedback, false);
                return 1;
            });
        });
    }

    private static int startCreeper(CommandSourceStack source, ServerPlayer player) throws CommandSyntaxException {
        Object identity = source.getPlayer() == null ? CREEPER_CONSOLE : source.getPlayer();
        UUID token = UUID.randomUUID();
        if (CREEPER_TASKS.putIfAbsent(identity, token) != null) throw WAIT_LAST.create();
        return OrgMenuNativeEffects.command(source, () -> OrgCreeperCommandTask.start(source.getServer(), player)
                .whenComplete((value, failure) -> CREEPER_TASKS.remove(identity, token)), "Creeper task failed");
    }
}
