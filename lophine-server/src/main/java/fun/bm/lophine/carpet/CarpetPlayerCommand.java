// SPDX-License-Identifier: LGPL-3.0-or-later
// Adapted from Fabric Carpet revision f358000b175ddbcf1dd0bc59641c715fb0545664.
package fun.bm.lophine.carpet;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fun.bm.lophine.carpet.CarpetPlayerActionPack.Action;
import fun.bm.lophine.carpet.CarpetPlayerActionPack.ActionType;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.leavesmc.leaves.bot.ServerBot;

import java.util.*;
import java.util.function.Consumer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

public class CarpetPlayerCommand {
    // TODO: allow any order like execute
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext) {
        LiteralArgumentBuilder<CommandSourceStack> command = literal("player")
                .requires((player) -> (CarpetCommandPermissions.canUse(player, fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.commandPlayer) || OrgPlayerInventoryMenus.canUsePlayerExtensions(player)))
                .then(argument("player", StringArgumentType.word())
                        .suggests((c, b) -> suggest(getPlayerSuggestions(c.getSource()), b))
                        .then(literal("stop").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(CarpetPlayerActionPack::stopAll)))
                        .then(makeActionCommand("use", ActionType.USE))
                        .then(makeActionCommand("jump", ActionType.JUMP))
                        .then(makeActionCommand("attack", ActionType.ATTACK))
                        .then(makeActionCommand("drop", ActionType.DROP_ITEM))
                        .then(makeDropCommand("drop", false))
                        .then(makeActionCommand("dropStack", ActionType.DROP_STACK))
                        .then(makeDropCommand("dropStack", true))
                        .then(makeActionCommand("swapHands", ActionType.SWAP_HANDS))
                        .then(literal("hotbar").requires(CarpetPlayerCommand::canUseNative)
                                .then(argument("slot", IntegerArgumentType.integer(1, 9))
                                        .executes(c -> manipulate(c, ap -> ap.setSlot(IntegerArgumentType.getInteger(c, "slot"))))))
                        .then(literal("kill").requires(CarpetPlayerCommand::canUseNative).executes(CarpetPlayerCommand::kill))
                        .then(literal("shadow").requires(CarpetPlayerCommand::canUseNative).executes(CarpetPlayerCommand::shadow))
                        .then(literal("mount").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.mount(true)))
                                .then(literal("anything").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.mount(false)))))
                        .then(literal("dismount").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(CarpetPlayerActionPack::dismount)))
                        .then(literal("sneak").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.setSneaking(true))))
                        .then(literal("unsneak").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.setSneaking(false))))
                        .then(literal("sprint").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.setSprinting(true))))
                        .then(literal("unsprint").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.setSprinting(false))))
                        .then(literal("look").requires(CarpetPlayerCommand::canUseNative)
                                .then(literal("north").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.look(Direction.NORTH))))
                                .then(literal("south").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.look(Direction.SOUTH))))
                                .then(literal("east").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.look(Direction.EAST))))
                                .then(literal("west").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.look(Direction.WEST))))
                                .then(literal("up").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.look(Direction.UP))))
                                .then(literal("down").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.look(Direction.DOWN))))
                                .then(literal("at").requires(CarpetPlayerCommand::canUseNative).then(argument("position", Vec3Argument.vec3())
                                        .executes(c -> manipulate(c, ap -> ap.lookAt(Vec3Argument.getVec3(c, "position"))))))
                                .then(argument("direction", RotationArgument.rotation())
                                        .executes(c -> manipulate(c, ap -> ap.look(RotationArgument.getRotation(c, "direction").getRotation(c.getSource())))))
                        ).then(literal("turn").requires(CarpetPlayerCommand::canUseNative)
                                .then(literal("left").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.turn(-90, 0))))
                                .then(literal("right").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.turn(90, 0))))
                                .then(literal("back").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.turn(180, 0))))
                                .then(argument("rotation", RotationArgument.rotation())
                                        .executes(c -> manipulate(c, ap -> ap.turn(RotationArgument.getRotation(c, "rotation").getRotation(c.getSource())))))
                        ).then(literal("move").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(CarpetPlayerActionPack::stopMovement))
                                .then(literal("forward").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.setForward(1))))
                                .then(literal("backward").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.setForward(-1))))
                                .then(literal("left").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.setStrafing(1))))
                                .then(literal("right").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.setStrafing(-1))))
                        ).then(literal("spawn").requires(CarpetPlayerCommand::canUseNative).executes(CarpetPlayerCommand::spawn)
                                .then(literal("in").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                        .then(argument("gamemode", GameModeArgument.gameMode())
                                                .executes(CarpetPlayerCommand::spawn)))
                                .then(literal("at").requires(CarpetPlayerCommand::canUseNative).then(argument("position", Vec3Argument.vec3()).executes(CarpetPlayerCommand::spawn)
                                        .then(literal("facing").requires(CarpetPlayerCommand::canUseNative).then(argument("direction", RotationArgument.rotation()).executes(CarpetPlayerCommand::spawn)
                                                .then(literal("in").requires(CarpetPlayerCommand::canUseNative).then(argument("dimension", DimensionArgument.dimension()).executes(CarpetPlayerCommand::spawn)
                                                        .then(literal("in").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                                                .then(argument("gamemode", GameModeArgument.gameMode())
                                                                        .executes(CarpetPlayerCommand::spawn)
                                                                )))
                                                )))
                                ))
                        )
                );
        dispatcher.register(command);
    }

    private static boolean canUseNative(CommandSourceStack source) {
        return CarpetCommandPermissions.canUse(source, fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.commandPlayer);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeActionCommand(String actionName, ActionType type) {
        return literal(actionName).requires(CarpetPlayerCommand::canUseNative)
                .executes(manipulation(ap -> ap.start(type, Action.once())))
                .then(literal("once").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.start(type, Action.once()))))
                .then(literal("continuous").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.start(type, Action.continuous()))))
                .then(literal("interval").requires(CarpetPlayerCommand::canUseNative).then(argument("ticks", IntegerArgumentType.integer(1))
                        .executes(c -> manipulate(c, ap -> ap.start(type, Action.interval(IntegerArgumentType.getInteger(c, "ticks")))))))
                .then(literal("after").requires(CarpetPlayerCommand::canUseNative).then(argument("delay", IntegerArgumentType.integer(1))
                        .executes(c -> manipulate(c, ap -> ap.start(type, Action.after(IntegerArgumentType.getInteger(c, "delay")))))))
                .then(literal("perTick").requires(CarpetPlayerCommand::canUseNative).then(argument("perTick", IntegerArgumentType.integer(1, 64))
                        .executes(c -> {
                            if (!CarpetCommandPermissions.canUse(c.getSource(), fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandPlayerActionPerTick)) {
                                feedback(c.getSource(), "Per-tick player actions are disabled or require higher permission", true);
                                return 0;
                            }
                            return manipulate(c, ap -> ap.start(type, Action.perTick(IntegerArgumentType.getInteger(c, "perTick"))));
                        })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> makeDropCommand(String actionName, boolean dropAll) {
        return literal(actionName).requires(CarpetPlayerCommand::canUseNative)
                .then(literal("all").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.drop(-2, dropAll))))
                .then(literal("mainhand").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.drop(-1, dropAll))))
                .then(literal("offhand").requires(CarpetPlayerCommand::canUseNative).executes(manipulation(ap -> ap.drop(40, dropAll))))
                .then(argument("slot", IntegerArgumentType.integer(0, 40)).
                        executes(c -> manipulate(c, ap -> ap.drop(IntegerArgumentType.getInteger(c, "slot"), dropAll))));
    }

    private static Collection<String> getPlayerSuggestions(CommandSourceStack source) {
        Set<String> players = new LinkedHashSet<>(List.of("Steve", "Alex"));
        players.addAll(source.getOnlinePlayerNames());
        for (ServerBot bot : source.getServer().getBotList().bots) players.add(bot.getGameProfile().name());
        return players;
    }

    private static final Set<String> SPAWNING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static ServerPlayer getPlayer(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        ServerPlayer real = context.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (real != null) return real;
        for (ServerBot bot : context.getSource().getServer().getBotList().bots) {
            if (bot.getGameProfile().name().equalsIgnoreCase(name)) return bot;
        }
        return null;
    }

    private static boolean canManipulate(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            feedback(source, "Can only manipulate existing players", true);
            return false;
        }
        ServerPlayer sender = source.getPlayer();
        if (sender != null && sender != player && !(player instanceof ServerBot)
                && !source.getServer().getPlayerList().isOp(sender.nameAndId())) {
            feedback(source, "Non OP players cannot control other real players", true);
            return false;
        }
        return true;
    }

    private static int manipulate(CommandContext<CommandSourceStack> context, Consumer<CarpetPlayerActionPack> action) {
        ServerPlayer player = getPlayer(context);
        if (!canManipulate(context.getSource(), player)) return 0;
        player.getBukkitEntity().taskScheduler.scheduleOrExecute(entity -> action.accept(((ServerPlayer) entity).carpetActionPack));
        return 1;
    }

    private static Command<CommandSourceStack> manipulation(Consumer<CarpetPlayerActionPack> action) {
        return context -> manipulate(context, action);
    }

    private static int kill(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = getPlayer(context);
        if (!canManipulate(context.getSource(), player)) return 0;
        if (!(player instanceof ServerBot bot)) {
            feedback(context.getSource(), "Only fake players can be killed", true);
            return 0;
        }
        var result = CarpetAsyncCommandResults.defer(context.getSource());
        bot.getBukkitEntity().taskScheduler.scheduleOrExecute(entity -> {
            ServerBot owned = (ServerBot) entity;
            owned.carpetActionPack.stopAll();
            context.getSource().getServer().getBotList().carpetRemoveBotAsync(owned,
                            org.leavesmc.leaves.event.bot.BotRemoveEvent.RemoveReason.COMMAND,
                            context.getSource().getBukkitSender(), owned.carpetNativePlayer, false)
                    .whenComplete((removed, failure) -> {
                        if (failure != null)
                            feedback(context.getSource(), "Cannot remove fake player: " + failure.getMessage(), true);
                        result.complete(failure == null && Boolean.TRUE.equals(removed), failure == null && Boolean.TRUE.equals(removed) ? 1 : 0);
                    });
        });
        return 1;
    }

    @FunctionalInterface
    private interface ArgumentGetter<T> {
        T get() throws CommandSyntaxException;
    }

    private static <T> T argumentOr(ArgumentGetter<T> getter, T fallback) throws CommandSyntaxException {
        try {
            return getter.get();
        } catch (IllegalArgumentException absent) {
            return fallback;
        }
    }

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String name = TisFakePlayerRules.spawnName(AmsFakePlayers.spawnName(StringArgumentType.getString(context, "player")));
        if (name.isEmpty() || name.length() > (source.getServer().getPort() >= 0 ? SharedConstants.MAX_PLAYER_NAME_LENGTH : 40)) {
            feedback(source, "Player name is too long or empty", true);
            return 0;
        }
        Vec3 position = argumentOr(() -> Vec3Argument.getVec3(context, "position"), source.getPosition());
        Vec2 rotation = argumentOr(() -> RotationArgument.getRotation(context, "direction").getRotation(source), source.getRotation());
        ServerLevel level = argumentOr(() -> DimensionArgument.getDimension(context, "dimension"), source.getLevel());
        if (!Level.isInSpawnableBounds(BlockPos.containing(position))) {
            feedback(source, "Player cannot be placed outside the world", true);
            return 0;
        }
        GameType explicitMode = argumentOr(() -> GameModeArgument.getGameMode(context, "gamemode"), null);
        if (!SPAWNING.add(name.toLowerCase(Locale.ROOT))) {
            feedback(source, "Player is already logging on", true);
            return 0;
        }
        ServerPlayer sender = source.getPlayer();
        Consumer<ServerPlayer> begin = ownedSender -> {
            if (!TisFakePlayerRules.canSpawnRemotely(source, ownedSender, level, position)) {
                SPAWNING.remove(name.toLowerCase(Locale.ROOT));
                feedback(source, "Remote player spawning is not allowed", true);
                return;
            }
            AmsFakePlayers.SpawnMode spawnMode = AmsFakePlayers.spawnMode(explicitMode, ownedSender);
            resolveAndSpawn(source, name, level, position, rotation, spawnMode.mode(), spawnMode.flying());
        };
        if (sender != null) {
            if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(sender)) begin.accept(sender);
            else sender.getBukkitEntity().taskScheduler.schedule(entity -> begin.accept((ServerPlayer) entity),
                    retired -> SPAWNING.remove(name.toLowerCase(Locale.ROOT)), 1L);
        } else begin.accept(null);
        return 1;
    }

    private static void resolveAndSpawn(CommandSourceStack source, String name, ServerLevel level, Vec3 position, Vec2 rotation, GameType mode, boolean flying) {
        net.minecraft.network.chat.Component summoner = source.getPlayer() == null ? null : source.getDisplayName().copy();
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        carpet.script.external.ScarpetNativeWork.record(completion);
        carpet.script.external.ScarpetNativeWork.trackNative(source.getServer(), completion);
        CarpetPlayerBirths.track(source.getServer(), completion, () -> {
        });
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    UUID uuid = fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.fakePlayerUseOfflinePlayerUUID
                            ? UUIDUtil.createOfflinePlayerUUID(name) : OldUsersConverter.convertMobOwnerIfNecessary(source.getServer(), name);
                    if (uuid == null && fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.allowSpawningOfflinePlayers)
                        uuid = UUIDUtil.createOfflinePlayerUUID(name);
                    if (uuid == null)
                        throw new IllegalArgumentException("Player does not exist; allowSpawningOfflinePlayers is disabled");
                    return uuid;
                }).thenCompose(uuid -> net.minecraft.world.item.component.ResolvableProfile.createUnresolved(uuid)
                        .resolveProfile(source.getServer().services().profileResolver())
                        .exceptionally(error -> new GameProfile(uuid, name)))
                .thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction((GameProfile profile) -> OrgPlayerFileLease.withLease(source.getServer(), profile.id(), "Carpet fake-player login", carpet.script.external.ScarpetRuntime.captureNativeFunction((OrgPlayerFileLease.Lease lease) -> {
                    if (carpet.script.external.ScarpetNativeWork.isDraining(source.getServer()))
                        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Server is stopping"));
                    return OrgPlayerInventoryMenus.recoverOfflineBeforeRead(source.getServer(), profile.id()).thenCompose(recovered -> java.util.concurrent.CompletableFuture.supplyAsync(() -> new ProfileData(profile.name().isEmpty() ? new GameProfile(profile.id(), name) : profile,
                                    source.getServer().getPlayerList().loadPlayerData(new NameAndId(profile)).orElse(null))))
                            .thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction((ProfileData data) -> CarpetRegionLease.<java.util.concurrent.CompletableFuture<Void>>runValue(level,
                                    BlockPos.containing(position).getX() >> 4, BlockPos.containing(position).getZ() >> 4,
                                    (BlockPos.containing(position).getX() >> 4), (BlockPos.containing(position).getZ() >> 4), region -> {
                                        if (carpet.script.external.ScarpetNativeWork.isDraining(source.getServer()))
                                            throw new IllegalStateException("Server is stopping");
                                        PlayerList list = source.getServer().getPlayerList();
                                        NameAndId identity = new NameAndId(data.profile());
                                        if (list.getPlayerByName(name) != null || source.getServer().getBotList().bots.stream().anyMatch(bot -> bot.getGameProfile().name().equalsIgnoreCase(name)))
                                            throw new IllegalArgumentException("Player is already logged on");
                                        if (list.getBans().isBanned(identity))
                                            throw new IllegalArgumentException("Player is banned on this server");
                                        if ((list.isUsingWhitelist() || fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.onlyOpCanSpawnRealPlayerInWhitelist)
                                                && list.getWhiteList().isWhiteListed(identity) && !Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source))
                                            throw new IllegalArgumentException("Whitelisted players can only be spawned by operators");
                                        ServerBot created = create(source, data.profile(), level, position, rotation, mode, flying, data.saved(), false, null, null, null, completion);
                                        return source.getServer().getBotList().carpetPlacementCompletion(created)
                                                .thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(owned -> OrgFakePlayerActions.owned(owned, () -> {
                                                    OrgPlayerSummoner.spawned(owned, summoner, false);
                                                    return null;
                                                })));
                                    }).thenCompose(java.util.function.Function.identity())));
                })))).whenComplete((ignored, failure) -> {
                    SPAWNING.remove(name.toLowerCase(Locale.ROOT));
                    if (failure == null) completion.complete(null);
                    else {
                        completion.completeExceptionally(failure);
                        feedback(source, "Cannot spawn player: " + failure.getMessage(), true);
                    }
                });
    }

    private record ProfileData(GameProfile profile, net.minecraft.nbt.CompoundTag saved) {
    }

    /**
     * The Org manager checks its own permission and acquires the destination region before entering this native creation path.
     */
    public static ServerBot createOrgManagedPlayer(CommandSourceStack source, GameProfile profile, ServerLevel level, Vec3 position, Vec2 rotation,
                                                   GameType mode, boolean flying, net.minecraft.nbt.CompoundTag saved, net.minecraft.nbt.CompoundTag actions) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(level, BlockPos.containing(position), "Create managed fake player off owner");
        return createOrgManagedPlayer(source, profile, level, position, rotation, mode, flying, saved, actions, null);
    }

    public static ServerBot createOrgManagedPlayer(CommandSourceStack source, GameProfile profile, ServerLevel level, Vec3 position, Vec2 rotation,
                                                   GameType mode, boolean flying, net.minecraft.nbt.CompoundTag saved, net.minecraft.nbt.CompoundTag actions,
                                                   java.util.concurrent.CompletableFuture<?> fullBirth) {
        return createOrgManagedPlayer(source, profile, level, position, rotation, mode, flying, saved, actions, fullBirth, false);
    }

    public static ServerBot createOrgManagedPlayer(CommandSourceStack source, GameProfile profile, ServerLevel level, Vec3 position, Vec2 rotation,
                                                   GameType mode, boolean flying, net.minecraft.nbt.CompoundTag saved, net.minecraft.nbt.CompoundTag actions,
                                                   java.util.concurrent.CompletableFuture<?> fullBirth, boolean silence) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(level, BlockPos.containing(position), "Create managed fake player off owner");
        return create(source, profile, level, position, rotation, mode, flying, saved, false, actions, null, null, fullBirth, silence);
    }

    private static ServerBot create(CommandSourceStack source, GameProfile profile, ServerLevel level, Vec3 position, Vec2 rotation,
                                    GameType mode, boolean flying, net.minecraft.nbt.CompoundTag saved, boolean shadow, net.minecraft.nbt.CompoundTag actions,
                                    net.minecraft.server.level.ClientInformation information, net.minecraft.network.chat.RemoteChatSession chatSession) {
        return create(source, profile, level, position, rotation, mode, flying, saved, shadow, actions, information, chatSession, null);
    }

    private static ServerBot create(CommandSourceStack source, GameProfile profile, ServerLevel level, Vec3 position, Vec2 rotation,
                                    GameType mode, boolean flying, net.minecraft.nbt.CompoundTag saved, boolean shadow, net.minecraft.nbt.CompoundTag actions,
                                    net.minecraft.server.level.ClientInformation information, net.minecraft.network.chat.RemoteChatSession chatSession,
                                    java.util.concurrent.CompletableFuture<?> fullBirth) {
        return create(source, profile, level, position, rotation, mode, flying, saved, shadow, actions, information, chatSession, fullBirth, false);
    }

    private static ServerBot create(CommandSourceStack source, GameProfile profile, ServerLevel level, Vec3 position, Vec2 rotation,
                                    GameType mode, boolean flying, net.minecraft.nbt.CompoundTag saved, boolean shadow, net.minecraft.nbt.CompoundTag actions,
                                    net.minecraft.server.level.ClientInformation information, net.minecraft.network.chat.RemoteChatSession chatSession,
                                    java.util.concurrent.CompletableFuture<?> fullBirth, boolean silence) {
        GameProfile botProfile = org.leavesmc.leaves.bot.BotList.createBotProfile(profile.id(), profile.name(), null);
        botProfile.properties().putAll(profile.properties());
        ServerBot bot = new ServerBot(source.getServer(), level, botProfile, true);
        bot.carpetNativePlayer = true;
        bot.carpetShadow = shadow;
        bot.getEntityData().set(net.minecraft.world.entity.player.Player.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
        String[] skin = null;
        for (com.mojang.authlib.properties.Property texture : profile.properties().get("textures")) {
            if (texture.signature() != null) skin = new String[]{texture.value(), texture.signature()};
            break;
        }
        org.bukkit.Location location = new org.bukkit.Location(level.getWorld(), position.x, position.y, position.z, rotation.y, rotation.x);
        bot.createState = org.leavesmc.leaves.bot.BotCreateState.builder(profile.name(), location).name(profile.name())
                .skin(skin).createReason(org.leavesmc.leaves.event.bot.BotCreateEvent.CreateReason.COMMAND).creator(source.getBukkitSender()).build();
        net.minecraft.world.level.storage.ValueInput input = null;
        if (saved != null) {
            saved = saved.copy();
            net.minecraft.nbt.CompoundTag creation = new net.minecraft.nbt.CompoundTag();
            creation.putString("rawName", profile.name());
            creation.putString("name", profile.name());
            creation.putString("skinName", profile.name());
            if (skin != null) {
                net.minecraft.nbt.ListTag texture = new net.minecraft.nbt.ListTag();
                texture.add(net.minecraft.nbt.StringTag.valueOf(skin[0]));
                texture.add(net.minecraft.nbt.StringTag.valueOf(skin[1]));
                creation.put("skin", texture);
            }
            saved.put("createStatus", creation);
            saved.putBoolean("carpetNativePlayer", true);
            input = net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, bot.registryAccess(), saved);
            bot.load(input);
            bot.carpetSetProfile(botProfile);
            bot.carpetShadow = shadow;
        }
        if (information != null) bot.updateOptionsNoEvents(information);
        if (chatSession != null) bot.setChatSession(chatSession);
        net.minecraft.nbt.CompoundTag restoredActions = actions == null ? null : actions.copy();
        bot.carpetPlacementInitializer = () -> {
            if (fullBirth != null) {
                carpet.script.external.ScarpetNativeWork.linkDependency(fullBirth,
                        carpet.script.external.ScarpetNativeWork.completionOf(carpet.script.external.ScarpetNativeWork.capture()));
                CarpetPlayerBirths.admitPlayer(bot, fullBirth);
            }
            bot.stopRiding();
            bot.carpetNativePlayer = true;
            bot.carpetShadow = shadow;
            if (!shadow) {
                bot.setHealth(20.0F);
                bot.deathTime = 0;
            }
            bot.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT).setBaseValue(0.6D);
            bot.setGameMode(mode, org.bukkit.event.player.PlayerGameModeChangeEvent.Cause.COMMAND, null);
            bot.getAbilities().flying = flying;
            if (restoredActions != null) bot.carpetActionPack.load(restoredActions);
        };
        OrgNativePlayerMessages.place(source.getServer(), bot, level, location, input, silence);
        return bot;
    }

    private static int shadow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(context);
        if (!canManipulate(source, player)) return 0;
        if (player instanceof ServerBot || source.getServer().isSingleplayerOwner(player.nameAndId())) {
            feedback(source, "Cannot shadow this player", true);
            return 0;
        }
        player.getBukkitEntity().taskScheduler.scheduleOrExecute(entity -> {
            ServerPlayer owned = (ServerPlayer) entity;
            GameProfile profile = owned.getGameProfile();
            if (!SPAWNING.add(profile.name().toLowerCase(Locale.ROOT))) {
                feedback(source, "Player is already logging on", true);
                return;
            }
            ServerLevel level = owned.level();
            Vec3 position = owned.position();
            Vec2 rotation = new Vec2(owned.getXRot(), owned.getYRot());
            GameType mode = owned.gameMode.getGameModeForPlayer();
            boolean flying = owned.getAbilities().flying;
            net.minecraft.server.level.ClientInformation information = owned.clientInformation();
            net.minecraft.network.chat.RemoteChatSession chatSession = owned.getChatSession();
            net.minecraft.world.level.storage.TagValueOutput output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, owned.registryAccess());
            owned.saveWithoutId(output);
            net.minecraft.nbt.CompoundTag saved = output.buildResult();
            net.minecraft.nbt.CompoundTag actions = owned.carpetActionPack.save();
            owned.connection.disconnect(net.minecraft.network.chat.Component.translatable("multiplayer.disconnect.duplicate_login"),
                    io.papermc.paper.connection.DisconnectionReason.UNKNOWN);
            waitForShadow(source, profile, level, position, rotation, mode, flying, saved, actions, information, chatSession, 40);
        });
        return 1;
    }

    private static void waitForShadow(CommandSourceStack source, GameProfile profile, ServerLevel level, Vec3 position, Vec2 rotation,
                                      GameType mode, boolean flying, net.minecraft.nbt.CompoundTag saved, net.minecraft.nbt.CompoundTag actions,
                                      net.minecraft.server.level.ClientInformation information, net.minecraft.network.chat.RemoteChatSession chatSession, int remaining) {
        org.bukkit.Bukkit.getRegionScheduler().runDelayed(org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE, level.getWorld(),
                BlockPos.containing(position).getX() >> 4, BlockPos.containing(position).getZ() >> 4, task -> {
                    if (source.getServer().getPlayerList().getPlayerByName(profile.name()) != null) {
                        if (remaining > 0)
                            waitForShadow(source, profile, level, position, rotation, mode, flying, saved, actions, information, chatSession, remaining - 1);
                        else {
                            SPAWNING.remove(profile.name().toLowerCase(Locale.ROOT));
                            feedback(source, "Shadow creation was blocked because the player did not disconnect", true);
                        }
                    } else {
                        try {
                            create(source, profile, level, position, rotation, mode, flying, saved, true, actions, information, chatSession);
                        } finally {
                            SPAWNING.remove(profile.name().toLowerCase(Locale.ROOT));
                        }
                    }
                }, 1L);
    }

    private static void feedback(CommandSourceStack source, String message, boolean failure) {
        Runnable send = () -> {
            if (failure) source.sendFailure(net.minecraft.network.chat.Component.literal(message));
            else source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(message), false);
        };
        ServerPlayer recipient = source.getPlayer();
        if (recipient != null) recipient.getBukkitEntity().taskScheduler.scheduleOrExecute(entity -> send.run());
        else send.run();
    }
}
