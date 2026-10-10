package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.leavesmc.leaves.bot.ServerBot;

/**
 * Org's native player extension actions, dispatched on the acting owner.
 */
public final class OrgPlayerExtraCommands {
    private OrgPlayerExtraCommands() {
    }

    public static boolean permitted(CommandSourceStack source) {
        return OrgUtilityCommands.permitted(source, GeneralCompatConfig.playerCommandTeleportFakePlayer)
                || OrgUtilityCommands.permitted(source, GeneralCompatConfig.playerCommandSummonMannequin);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var target = Commands.argument("player", StringArgumentType.word());
        target.then(Commands.literal("teleport")
                .requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.playerCommandTeleportFakePlayer))
                .executes(context -> {
                    ServerPlayer summoner = context.getSource().getPlayerOrException();
                    String name = StringArgumentType.getString(context, "player");
                    ServerPlayer selected = context.getSource().getServer().getPlayerList().getPlayerByName(name);
                    if (!(selected instanceof ServerBot)) {
                        context.getSource().sendFailure(Component.literal("The selected player must be an online fake player"));
                        return 0;
                    }
                    return OrgMenuNativeEffects.command(context.getSource(), () -> teleportAsync(summoner, selected), "The fake-player teleport was cancelled or could not finish");
                }));
        target.then(Commands.literal("mannequin")
                .requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.playerCommandSummonMannequin))
                .executes(context -> {
                    ServerPlayer summoner = context.getSource().getPlayerOrException();
                    String name = StringArgumentType.getString(context, "player");
                    return OrgMenuNativeEffects.command(context.getSource(), () -> mannequinAsync(context.getSource(), summoner, name), "The mannequin summon was cancelled or could not finish");
                }));
        dispatcher.register(Commands.literal("player").then(target));
    }

    private record Destination(double x, double y, double z, float yaw, float pitch, Component name) {
    }

    private record TeleportAttempt(java.util.concurrent.CompletableFuture<Boolean> teleported,
                                   java.util.function.Function<Boolean, java.util.concurrent.CompletableFuture<Boolean>> finish) {
    }

    static java.util.concurrent.CompletableFuture<Boolean> teleportAsync(ServerPlayer summoner, ServerPlayer selected) {
        var parent = carpet.script.external.ScarpetNativeWork.capture();
        return begin(summoner.level().getServer(), lifetime -> OrgMenuNativeEffects.run(summoner, () -> {
            if (summoner.isRemoved() || summoner.isDeadOrDying())
                throw new IllegalStateException("The teleport source is no longer available");
            return new Destination(summoner.getX(), summoner.getY(), summoner.getZ(), summoner.getYRot(), summoner.getXRot(), summoner.getDisplayName().copy());
        }).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(destination -> OrgMenuNativeEffects.run(selected, () -> {
            if (selected.isRemoved() || selected.isDeadOrDying())
                return new TeleportAttempt(java.util.concurrent.CompletableFuture.completedFuture(false), ignored -> java.util.concurrent.CompletableFuture.completedFuture(false));
            carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(selected, lifetime);
            // Capture the continuation only after this real fake owner was admitted. The original parent is still held by lifetime.
            // Its accepted owner identity prevents its own complete operation from blocking the arrival continuation.
            var finish = CarpetNativeActionContext.inNative(parent, () -> carpet.script.external.ScarpetRuntime.<Boolean, java.util.concurrent.CompletableFuture<Boolean>>captureNativeFunction(success ->
                    success ? finishTeleport(summoner, selected, destination) : java.util.concurrent.CompletableFuture.completedFuture(false)));
            // Source intentionally retains the fake player's existing dimension.
            var location = new Location(selected.level().getWorld(), destination.x(), destination.y(), destination.z(), destination.yaw(), destination.pitch());
            var teleported = selected.getBukkitEntity().teleportAsync(location, PlayerTeleportEvent.TeleportCause.COMMAND);
            carpet.script.external.ScarpetNativeWork.record(teleported);
            return new TeleportAttempt(teleported, finish);
        }).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(attempt -> attempt.teleported().thenCompose(attempt.finish()))))));
    }

    private static java.util.concurrent.CompletableFuture<Boolean> finishTeleport(ServerPlayer summoner, ServerPlayer selected, Destination destination) {
        return OrgMenuNativeEffects.run(selected, () -> {
            selected.level().broadcastEntityEvent(selected, EntityEvent.TELEPORT);
            selected.level().playSound(null, selected.getX(), selected.getY(), selected.getZ(), net.minecraft.sounds.SoundEvents.PLAYER_TELEPORT, selected.getSoundSource(), 1F, 1F);
            selected.resetFallDistance();
            return selected.getDisplayName().copy();
        }).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(fakeName -> OrgMenuNativeEffects.run(summoner, () -> {
            summoner.sendSystemMessage(Component.translatable("commands.teleport.success.entity.single", fakeName, destination.name()));
            return true;
        })));
    }

    static java.util.concurrent.CompletableFuture<Boolean> mannequinAsync(ServerPlayer summoner, String name) {
        return begin(summoner.level().getServer(), lifetime -> OrgMenuNativeEffects.run(summoner, () -> {
            if (summoner.isRemoved() || summoner.isDeadOrDying()) return false;
            Mannequin mannequin = new Mannequin(EntityTypes.MANNEQUIN, summoner.level());
            mannequin.setProfile(ResolvableProfile.createUnresolved(name));
            mannequin.snapTo(summoner.position(), summoner.getYRot(), summoner.getXRot());
            return summoner.level().addFreshEntity(mannequin);
        }));
    }

    private record MannequinDestination(net.minecraft.server.level.ServerLevel world,
                                        net.minecraft.world.phys.Vec3 position, float yaw, float pitch) {
    }

    static java.util.concurrent.CompletableFuture<Boolean> mannequinAsync(CommandSourceStack source, ServerPlayer summoner, String name) {
        final var commandWorld = source.getLevel();
        return begin(source.getServer(), lifetime -> TisCommandContinuations.then(OrgMenuNativeEffects.run(summoner, () -> {
            if (summoner.isRemoved() || summoner.isDeadOrDying())
                throw new IllegalStateException("The mannequin source is no longer available");
            return new MannequinDestination(summoner.level(), summoner.position(), summoner.getYRot(), summoner.getXRot());
        }), destination -> {
            var position = net.minecraft.core.BlockPos.containing(destination.position());
            return TisCommandContinuations.then(TisCommandContinuations.world(source, commandWorld, position, () -> {
                Mannequin mannequin = new Mannequin(EntityTypes.MANNEQUIN, commandWorld);
                mannequin.setProfile(ResolvableProfile.createUnresolved(name));
                return mannequin;
            }), mannequin -> TisCommandContinuations.world(source, destination.world(), position, () -> {
                // snapshot9 creates in commandWorld, then teleports the unregistered object to
                // the real source player's world. Folia forbids Entity.teleportTo; initialize
                // this unpublished entity once on that destination actor, then publish it there.
                mannequin.setLevel(destination.world());
                mannequin.snapTo(destination.position(), destination.yaw(), destination.pitch());
                return destination.world().addFreshEntity(mannequin);
            }));
        }));
    }

    private static <T> java.util.concurrent.CompletableFuture<T> begin(net.minecraft.server.MinecraftServer server, java.util.function.Function<java.util.concurrent.CompletableFuture<T>, java.util.concurrent.CompletableFuture<T>> operation) {
        var actual = new java.util.concurrent.CompletableFuture<T>();
        carpet.script.external.ScarpetNativeWork.record(actual);
        carpet.script.external.ScarpetNativeWork.trackNative(server, actual);
        try {
            var body = operation.apply(actual);
            carpet.script.external.ScarpetNativeWork.aliasDependency(actual, body);
            body.whenComplete((value, failure) -> {
                if (failure == null) actual.complete(value);
                else actual.completeExceptionally(failure);
            });
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        var caller = actual.copy();
        carpet.script.external.ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }
}
