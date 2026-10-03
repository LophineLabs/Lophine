// SPDX-License-Identifier: LGPL-3.0-or-later
package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import fun.bm.lophine.carpet.CarpetRegionLease;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Complete native death tick; every real producer finishes its children before the next source phase.
 */
public final class ScarpetDragonDeathContinuations {
    private static final WeakIdentityMap<EnderDragon, CompletableFuture<Void>> TICKS = new WeakIdentityMap<>();

    private record Start(ServerLevel world, EnderDragonFight fight) {
    }

    private record Sound(int distance, List<ServerPlayer> players) {
    }

    private record Range(double radius, boolean global) {
    }

    private ScarpetDragonDeathContinuations() {
    }

    public static void tick(EnderDragon dragon) {
        var pending = TICKS.get(dragon);
        if (pending != null) {
            ScarpetNativeWork.record(pending);
            return;
        }
        var previousHurt = ScarpetRenewableDragonHead.pendingResult(dragon);
        ServerLevel admitted = (ServerLevel) dragon.level();
        var actual = new CompletableFuture<Void>() {
            @Override
            public boolean cancel(boolean interrupt) {
                return false;
            }
        };
        TICKS.put(dragon, actual);
        ScarpetNativeWork.record(actual);
        ScarpetNativeWork.trackNative(admitted.getServer(), actual);
        var before = previousHurt == null ? CompletableFuture.completedFuture(null) : previousHurt.thenApply(ignored -> null);
        var body = ScarpetLootActors.jobNative(admitted, () -> before.thenCompose(ScarpetRuntime.captureNativeFunction(ignored ->
                        ScarpetNativeDeathActors.entity(dragon, () -> new Start((ServerLevel) dragon.level(), dragon.carpetDeathFight()))))
                .thenCompose(ScarpetRuntime.captureNativeFunction(start -> update(dragon, start.fight())
                        .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetNativeDeathActors.entity(dragon, () -> dragon.carpetDeathAdvance(start.world()))))
                        .thenCompose(ScarpetRuntime.captureNativeFunction(time -> previousKilled(start.fight())
                                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetNativeDeathActors.entity(dragon, dragon::carpetDeathExperience)))
                                .thenCompose(ScarpetRuntime.captureNativeFunction(xp -> death(dragon, start, time, xp))))))));
        ScarpetNativeWork.aliasDependency(actual, body);
        body.whenComplete((value, failure) -> {
            if (failure == null) actual.complete(null);
            else actual.completeExceptionally(failure);
            TICKS.remove(dragon, actual);
        });
    }

    public static CompletableFuture<Void> pending(EnderDragon dragon) {
        return TICKS.get(dragon);
    }

    private static <T> CompletableFuture<T> world(ServerLevel original, BlockPos position, int radius, Supplier<T> operation) {
        Supplier<CompletableFuture<T>> phase = ScarpetRuntime.captureNativeContinuation(() -> {
            var observed = ScarpetNativeWork.observeNative(null, () -> {
                T value = operation.get();
                if (value instanceof CompletableFuture<?> future) ScarpetNativeWork.record(future);
                return value;
            });
            ScarpetNativeWork.trackNative(original.getServer(), observed);
            return ScarpetNativeWork.recoverGuestValue(observed);
        });
        int minX = (position.getX() - radius) >> 4, minZ = (position.getZ() - radius) >> 4;
        int maxX = (position.getX() + radius) >> 4, maxZ = (position.getZ() + radius) >> 4;
        CompletableFuture<T> result;
        if (radius == 0)
            result = ScarpetExplosionActors.world(original, position, phase).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        else if (TickThread.isTickThreadFor(original, minX, minZ, maxX, maxZ)) result = phase.get();
        else
            result = CarpetRegionLease.<CompletableFuture<T>>runValue(original, minX, minZ, maxX, maxZ, lease -> phase.get())
                    .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        ScarpetNativeWork.record(result);
        return result;
    }

    private static <T> CompletableFuture<T> fight(EnderDragonFight fight, Supplier<T> operation) {
        return world(fight.level, fight.origin, 0, operation);
    }

    private static CompletableFuture<Void> update(EnderDragon dragon, EnderDragonFight fight) {
        if (fight == null) return CompletableFuture.completedFuture(null);
        return ScarpetNativeDeathActors.entity(dragon, dragon::getUUID)
                .thenCompose(ScarpetRuntime.captureNativeFunction(uuid -> fight(fight, () -> fight.carpetDeathMatches(uuid))))
                .thenCompose(ScarpetRuntime.captureNativeFunction(matches -> {
                    if (!matches) return CompletableFuture.completedFuture(null);
                    return ScarpetNativeDeathActors.entity(dragon, () -> dragon.getHealth() / dragon.getMaxHealth())
                            .thenCompose(ScarpetRuntime.captureNativeFunction(progress -> fight(fight, () -> {
                                fight.carpetDeathUpdateProgress(progress);
                                return (Void) null;
                            })))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetNativeDeathActors.entity(dragon, dragon::hasCustomName)))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(custom -> custom ? ScarpetNativeDeathActors.entity(dragon, dragon::getDisplayName) : CompletableFuture.completedFuture(null)))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(name -> fight(fight, () -> {
                                fight.carpetDeathUpdateName(name);
                                return (Void) null;
                            })));
                }));
    }

    private static CompletableFuture<Boolean> previousKilled(EnderDragonFight fight) {
        return fight == null ? CompletableFuture.completedFuture(false) : fight(fight, fight::hasPreviouslyKilledDragon);
    }

    private static CompletableFuture<Void> death(EnderDragon dragon, Start start, int time, int xp) {
        CompletableFuture<Void> eight = time > 150 && time % 5 == 0 ? award(dragon, start.world(), Mth.floor(xp * .08F)) : CompletableFuture.completedFuture(null);
        return eight.thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> sounds(dragon, start.world(), time)))
                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetNativeDeathActors.target(dragon, () -> {
                    dragon.carpetDeathMove();
                    return (Void) null;
                })))
                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetNativeDeathActors.entity(dragon, dragon::getSubEntities)))
                .thenCompose(ScarpetRuntime.captureNativeFunction(parts -> parts(parts, 0)))
                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> {
                    if (time < 200) return CompletableFuture.completedFuture(null);
                    return award(dragon, start.world(), Mth.floor(xp * .2F))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(done -> killed(dragon, start.fight())))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(done -> ScarpetNativeDeathActors.entity(dragon, dragon::carpetDeathDropsHead)))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(drop -> drop ? ScarpetNativeDeathActors.target(dragon, () -> {
                                dragon.carpetDeathDropHead(start.world());
                                return (Void) null;
                            }) : CompletableFuture.completedFuture(null)))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(done -> ScarpetNativeDeathActors.entity(dragon, () -> {
                                dragon.carpetDeathRemove();
                                return (Void) null;
                            })))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(done -> ScarpetNativeDeathActors.target(dragon, () -> {
                                dragon.carpetDeathGameEvent();
                                return (Void) null;
                            })));
                }));
    }

    private static CompletableFuture<Player> resolve(EnderDragon dragon, ServerLevel original, EntityReference<Player> reference) {
        if (reference == null) return CompletableFuture.completedFuture(null);
        return ScarpetNativeDeathActors.entity(dragon, () -> reference.carpetCandidate(original, Player.class))
                .thenCompose(ScarpetRuntime.captureNativeFunction(candidate -> {
                    if (candidate == null) return CompletableFuture.completedFuture(null);
                    return ScarpetNativeDeathActors.entity(candidate, candidate::isRemoved)
                            .thenCompose(ScarpetRuntime.captureNativeFunction(removed -> {
                                if (!removed) return CompletableFuture.completedFuture(candidate);
                                return ScarpetNativeDeathActors.entity(dragon, () -> reference.carpetLookup(original, Player.class))
                                        .thenCompose(ScarpetRuntime.captureNativeFunction(resolved -> resolved == null || resolved == candidate ? CompletableFuture.completedFuture(null)
                                                : ScarpetNativeDeathActors.entity(resolved, () -> resolved.isRemoved() ? null : resolved)));
                            }));
                }));
    }

    private static CompletableFuture<Void> award(EnderDragon dragon, ServerLevel original, int amount) {
        return ScarpetNativeDeathActors.entity(dragon, dragon::position)
                .thenCompose(ScarpetRuntime.captureNativeFunction(position -> ScarpetNativeDeathActors.entity(dragon, dragon::carpetDeathPlayerReference)
                        .thenCompose(ScarpetRuntime.captureNativeFunction(reference -> resolve(dragon, original, reference)))
                        .thenCompose(ScarpetRuntime.captureNativeFunction(player -> awards(dragon, original, position, player, amount)))));
    }

    private static CompletableFuture<Void> awards(EnderDragon dragon, ServerLevel original, Vec3 position, Player player, int amount) {
        var result = new CompletableFuture<Void>();
        class Award {
            int remaining = amount;

            void resume() {
                try {
                    while (remaining > 0) {
                        var next = world(original, BlockPos.containing(position), 2, () -> dragon.carpetDeathAwardUnit(original, position, player, remaining));
                        if (next.isDone()) {
                            remaining = next.join();
                            continue;
                        }
                        next.whenComplete(ScarpetRuntime.captureNativeConsumer((value, failure) -> {
                            if (failure != null) result.completeExceptionally(failure);
                            else {
                                remaining = value;
                                resume();
                            }
                        }));
                        return;
                    }
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            }
        }
        ScarpetNativeWork.record(result);
        new Award().resume();
        return result;
    }

    private static CompletableFuture<Void> parts(EnderDragonPart[] parts, int index) {
        if (index == parts.length) return CompletableFuture.completedFuture(null);
        EnderDragonPart part = parts[index];
        return ScarpetNativeDeathActors.entity(part, () -> {
                    part.setOldPosAndRot();
                    return (Void) null;
                })
                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> ScarpetNativeDeathActors.entity(part, () -> {
                    part.setPos(part.position().add(new Vec3(0, .1F, 0)));
                    return (Void) null;
                })))
                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> parts(parts, index + 1)));
    }

    private static CompletableFuture<Void> killed(EnderDragon dragon, EnderDragonFight fight) {
        if (fight == null) return CompletableFuture.completedFuture(null);
        return ScarpetNativeDeathActors.entity(dragon, dragon::getUUID)
                .thenCompose(ScarpetRuntime.captureNativeFunction(uuid -> fight(fight, () -> fight.carpetDeathMatches(uuid))))
                .thenCompose(ScarpetRuntime.captureNativeFunction(matches -> {
                    if (!matches) return CompletableFuture.completedFuture(null);
                    return fight(fight, () -> {
                        fight.carpetDeathZeroProgress();
                        return (Void) null;
                    })
                            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> fight(fight, () -> {
                                fight.carpetDeathHide();
                                return (Void) null;
                            })))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> fight(fight, fight::carpetDeathPortalPosition)))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(portal -> world(fight.level, portal, 32, () -> portal(fight, portal))))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> fight(fight, fight::carpetDeathGatewayPosition)))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(gateway -> world(fight.level, gateway == null ? fight.origin : gateway, gateway == null ? 0 : 32, () -> gateway(fight, gateway))))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> fight(fight, fight::carpetDeathPodiumPosition)))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(podium -> world(fight.level, podium, 32, () -> egg(fight, podium))))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> fight(fight, () -> {
                                fight.carpetDeathFinishKilled();
                                return (Void) null;
                            })));
                }));
    }

    private static CompletableFuture<Void> portal(EnderDragonFight fight, BlockPos portal) {
        return world(fight.level, portal, 0, fight::carpetDeathPreparePortal)
                .thenCompose(ScarpetRuntime.captureNativeFunction(feature -> world(fight.level, portal, 0, () -> fight.carpetDeathPlacePortal(feature))))
                .thenCompose(ScarpetRuntime.captureNativeFunction(placed -> !placed ? CompletableFuture.completedFuture(null) : world(fight.level, portal, 0, () -> {
                    fight.carpetDeathLightPortal();
                    return (Void) null;
                })));
    }

    private static CompletableFuture<Void> gateway(EnderDragonFight fight, BlockPos footprint) {
        return world(fight.level, footprint == null ? fight.origin : footprint, 0, fight::carpetDeathTakeGateway)
                .thenCompose(ScarpetRuntime.captureNativeFunction(position -> {
                    if (position == null) return CompletableFuture.completedFuture(null);
                    return world(fight.level, position, 0, () -> {
                        fight.carpetDeathGatewayEvent(position);
                        return (Void) null;
                    })
                            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> world(fight.level, position, 0, () -> {
                                fight.carpetDeathGatewayPlace(position);
                                return (Void) null;
                            })))
                            .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> fight(fight, () -> {
                                fight.carpetDeathDirty();
                                return (Void) null;
                            })));
                }));
    }

    private static CompletableFuture<Void> egg(EnderDragonFight fight, BlockPos podium) {
        return world(fight.level, podium, 0, fight::carpetDeathPrepareEgg)
                .thenCompose(ScarpetRuntime.captureNativeFunction(event -> world(fight.level, podium, 0, event::callEvent)
                        .thenCompose(ScarpetRuntime.captureNativeFunction(accepted -> !accepted ? CompletableFuture.completedFuture(null) : world(fight.level, podium, 0, () -> {
                            ((org.bukkit.craftbukkit.block.CraftBlockState) event.getNewState()).place(net.minecraft.world.level.block.Block.UPDATE_ALL);
                            return (Void) null;
                        })))));
    }

    private static CompletableFuture<Void> sounds(EnderDragon dragon, ServerLevel original, int time) {
        if (time != 1) return CompletableFuture.completedFuture(null);
        return ScarpetNativeDeathActors.entity(dragon, dragon::isSilent).thenCompose(ScarpetRuntime.captureNativeFunction(silent -> {
            if (silent) return CompletableFuture.completedFuture(null);
            return ScarpetNativeDeathActors.entity(dragon, dragon::blockPosition)
                    .thenCompose(ScarpetRuntime.captureNativeFunction(position -> world(original, position, 0, () -> new Sound(original.getCraftServer().getViewDistance() * 16, List.copyOf(original.getPlayersForGlobalSoundGamerule())))))
                    .thenCompose(ScarpetRuntime.captureNativeFunction(sound -> sound(dragon, original, sound, 0)));
        }));
    }

    private static CompletableFuture<Void> sound(EnderDragon dragon, ServerLevel original, Sound sound, int index) {
        if (index == sound.players().size()) return CompletableFuture.completedFuture(null);
        ServerPlayer player = sound.players().get(index);
        return ScarpetNativeDeathActors.entity(dragon, dragon::getX)
                .thenCompose(ScarpetRuntime.captureNativeFunction(x -> ScarpetNativeDeathActors.entity(player, player::getX)
                        .thenCompose(ScarpetRuntime.captureNativeFunction(px -> ScarpetNativeDeathActors.entity(dragon, dragon::getZ)
                                .thenCompose(ScarpetRuntime.captureNativeFunction(z -> ScarpetNativeDeathActors.entity(player, player::getZ)
                                        .thenCompose(ScarpetRuntime.captureNativeFunction(pz -> soundRange(dragon, original, sound, player, x - px, z - pz)))))))))
                .thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> sound(dragon, original, sound, index + 1)));
    }

    private static CompletableFuture<Void> soundRange(EnderDragon dragon, ServerLevel original, Sound sound, ServerPlayer player, double dx, double dz) {
        double squared = Mth.square(dx) + Mth.square(dz);
        return ScarpetNativeDeathActors.entity(dragon, dragon::blockPosition)
                .thenCompose(ScarpetRuntime.captureNativeFunction(position -> world(original, position, 0, () -> new Range(original.getGlobalSoundRangeSquared(config -> config.dragonDeathSoundRadius), original.getGameRules().get(GameRules.GLOBAL_SOUND_EVENTS)))))
                .thenCompose(ScarpetRuntime.captureNativeFunction(range -> {
                    if (!range.global() && squared > range.radius()) return CompletableFuture.completedFuture(null);
                    CompletableFuture<Vec3> location;
                    if (squared > Mth.square(sound.distance())) {
                        double length = Math.sqrt(squared);
                        location = ScarpetNativeDeathActors.entity(player, player::getX)
                                .thenCompose(ScarpetRuntime.captureNativeFunction(nowX -> ScarpetNativeDeathActors.entity(player, player::getZ)
                                        .thenCompose(ScarpetRuntime.captureNativeFunction(nowZ -> ScarpetNativeDeathActors.entity(dragon, dragon::getY)
                                                .thenApply(ScarpetRuntime.captureNativeFunction(y -> new Vec3(nowX + dx / length * sound.distance(), y, nowZ + dz / length * sound.distance())))))));
                    } else
                        location = ScarpetNativeDeathActors.entity(dragon, () -> new Vec3(dragon.getX(), dragon.getY(), dragon.getZ()));
                    return location.thenCompose(ScarpetRuntime.captureNativeFunction(pos -> ScarpetNativeDeathActors.entity(player, () -> {
                        send(player, new BlockPos((int) pos.x, (int) pos.y, (int) pos.z));
                        return (Void) null;
                    })));
                }));
    }

    private static void send(ServerPlayer player, BlockPos position) {
        var receipt = new CompletableFuture<Void>();
        ScarpetNativeWork.record(receipt);
        try {
            var closing = player.connection.connection.channel.closeFuture();
            io.netty.channel.ChannelFutureListener closed = network -> receipt.completeExceptionally(new java.nio.channels.ClosedChannelException());
            closing.addListener(closed);
            receipt.whenComplete((value, failure) -> closing.removeListener(closed));
            player.connection.send(new ClientboundLevelEventPacket(LevelEvent.SOUND_DRAGON_DEATH, position, 0, true), network -> {
                if (network.isSuccess()) receipt.complete(null);
                else
                    receipt.completeExceptionally(network.cause() == null ? new IllegalStateException("Native dragon death sound send failed") : network.cause());
            });
        } catch (Throwable failure) {
            receipt.completeExceptionally(failure);
            throw failure;
        }
    }
}
