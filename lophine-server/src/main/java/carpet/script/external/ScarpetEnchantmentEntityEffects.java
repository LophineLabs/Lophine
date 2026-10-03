// SPDX-License-Identifier: MIT
package carpet.script.external;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.effects.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.phys.Vec3;

/** Actual entity and original world phases for each registered native post-attack effect. */
public final class ScarpetEnchantmentEntityEffects {
    private ScarpetEnchantmentEntityEffects() { }
    public static CompletableFuture<Void> apply(EnchantmentEntityEffect effect, ScarpetAttackEnchantments.SourceAdmission admission, int level,
        EnchantedItemInUse item, Entity target, Vec3 originalPosition) {
        if (effect instanceof AllOf.EntityEffects all) {
            CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
            for (var child : all.effects()) sequence = sequence.thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> apply(child, admission, level, item, target, originalPosition)));
            return sequence;
        }
        if (effect instanceof ChangeItemDamage) return item.owner() == null
            ? original(admission, originalPosition, 0, () -> { effect.apply(admission.world(), level, item, target, originalPosition); return null; })
            : mutate(item.owner(), () -> { effect.apply(admission.world(), level, item, target, originalPosition); return null; });
        if (effect instanceof Ignite fire && item.owner() != null) {
            return ScarpetLootConditions.actor(item.owner(), item.owner()::getBukkitEntity).thenCompose(ScarpetRuntime.captureNativeFunction(owner ->
                mutate(target, () -> {
                    var event = new org.bukkit.event.entity.EntityCombustByEntityEvent(owner, target.getBukkitEntity(), fire.duration().calculate(level));
                    org.bukkit.Bukkit.getPluginManager().callEvent(event); return event;
                }).thenCompose(ScarpetRuntime.captureNativeFunction(event -> event.isCancelled() ? CompletableFuture.completedFuture(null) : mutate(target, () -> {
                    target.igniteForSeconds(event.getDuration(), false); return (Void)null;
                })))));
        }
        if (effect instanceof ReplaceBlock replace) {
            BlockPos position = BlockPos.containing(originalPosition).offset(replace.offset());
            return replace(replace.predicate(), replace.blockState().value(), replace.triggerGameEvent(), admission, target, position);
        }
        if (effect instanceof ReplaceDisk disk) {
            BlockPos center = BlockPos.containing(originalPosition).offset(disk.offset()); int radius = (int)disk.radius().calculate(level), height = (int)disk.height().calculate(level);
            CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
            for (BlockPos mutable : BlockPos.betweenClosed(center.offset(-radius, 0, -radius), center.offset(radius, Math.min(height - 1, 0), radius))) {
                BlockPos position = mutable.immutable();
                if (position.distToCenterSqr(originalPosition.x, position.getY() + 0.5, originalPosition.z) < net.minecraft.util.Mth.square(radius))
                    sequence = sequence.thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> replace(disk.predicate(), disk.blockState().value(), disk.triggerGameEvent(), admission, target, position)));
            }
            return sequence;
        }
        if (effect instanceof SetBlockProperties properties) {
            BlockPos position = BlockPos.containing(originalPosition).offset(properties.offset());
            return ScarpetLootConditions.actor(target, () -> (ServerLevel)target.level()).thenCompose(ScarpetRuntime.captureNativeFunction(targetWorld ->
                world(targetWorld, position, 16, () -> {
                    var original = targetWorld.getBlockState(position); var changed = properties.properties().apply(original);
                    return original != changed && targetWorld.setBlockAndUpdate(position, changed);
                }).thenCompose(ScarpetRuntime.captureNativeFunction(changed -> !changed || properties.triggerGameEvent().isEmpty() ? CompletableFuture.completedFuture(null)
                    : original(admission, Vec3.atCenterOf(position), 16, () -> { admission.world().gameEvent(target, properties.triggerGameEvent().get(), position); return null; })))));
        }
        if (effect instanceof ExplodeEffect explosion) {
            Vec3 position = originalPosition.add(explosion.offset()); int radius = (int)Math.ceil(Math.max(explosion.radius().calculate(level), 0F) * 2D) + 2;
            // Native ServerLevel.explode registers its actual core/packet/removal continuations into this direct observer.
            return original(admission, position, radius, () -> { effect.apply(admission.world(), level, item, target, originalPosition); return null; });
        }
        if (effect instanceof SummonEntityEffect summon) return summon(summon, admission, level, item, target, originalPosition);
        if (effect instanceof PlaySoundEffect sound) return sound(sound, admission, level, target, originalPosition);
        if (effect instanceof SpawnParticlesEffect particles) return particles(particles, admission, target, originalPosition);
        if (effect instanceof RunFunction function) return function(function, admission, target, originalPosition);
        // Native entity-only effects: mob effect, damage entity, fire/combust, impulse and exhaustion. Their true dynamic damage/effect children are awaited.
        return mutate(target, () -> { effect.apply(admission.world(), level, item, target, originalPosition); return null; });
    }
    private static <T> CompletableFuture<T> mutate(Entity actualOwner, Supplier<T> body) {
        return ScarpetExplosionActors.admitTarget(actualOwner, () -> ScarpetNativeWork.recoverGuestValue(ScarpetNativeWork.observeNative(actualOwner, body)));
    }
    private static CompletableFuture<Void> replace(Optional<net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate> predicate, BlockStateProvider provider,
        Optional<net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent>> event, ScarpetAttackEnchantments.SourceAdmission admission, Entity randomOwner, BlockPos position) {
        return (predicate.isEmpty() ? CompletableFuture.completedFuture(true) : ScarpetEnchantmentBlockStates.predicate(predicate.get(), admission.world(), position))
            .thenCompose(ScarpetRuntime.captureNativeFunction(allowed -> !allowed ? CompletableFuture.completedFuture(null) :
                ScarpetEnchantmentBlockStates.state(provider, admission.world(), randomOwner, position, false)
                    .thenCompose(ScarpetRuntime.captureNativeFunction(state -> original(admission, Vec3.atCenterOf(position), 16, () ->
                        org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(admission.world(), position, state, Block.UPDATE_ALL, randomOwner, true))
                        .thenCompose(ScarpetRuntime.captureNativeFunction(changed -> !changed || event.isEmpty() ? CompletableFuture.completedFuture(null)
                            : original(admission, Vec3.atCenterOf(position), 16, () -> {
                                admission.world().gameEvent(randomOwner, event.get(), position); return (Void)null;
                            })))))));
    }
    static <T> CompletableFuture<T> original(ScarpetAttackEnchantments.SourceAdmission admission, Vec3 position, int radius, Supplier<T> body) {
        return world(admission.world(), BlockPos.containing(position), radius, body);
    }
    static <T> CompletableFuture<T> world(ServerLevel world, BlockPos position, int radius, Supplier<T> body) {
        var accepted = ScarpetRuntime.captureNativeContinuation(() -> {
            var observed = ScarpetNativeWork.observeNative(null, body); ScarpetNativeWork.trackNative(world.getServer(), observed);
            return ScarpetNativeWork.recoverGuestValue(observed);
        });
        var actual = fun.bm.lophine.carpet.CarpetRegionLease.<CompletableFuture<T>>runValue(world, (position.getX() - radius) >> 4,
            (position.getZ() - radius) >> 4, (position.getX() + radius) >> 4, (position.getZ() + radius) >> 4, lease -> accepted.get())
            .thenCompose(ScarpetRuntime.captureNativeFunction(value -> value));
        ScarpetNativeWork.record(actual); return actual;
    }
    private static CompletableFuture<Void> sound(PlaySoundEffect sound, ScarpetAttackEnchantments.SourceAdmission admission, int level, Entity target, Vec3 position) {
        return ScarpetLootConditions.actor(target, () -> {
            if (target.isSilent()) return null;
            var random = target.getRandom(); int index = net.minecraft.util.Mth.clamp(level - 1, 0, sound.soundEvents().size() - 1);
            return new Sound(sound.soundEvents().get(index), target.getSoundSource(), sound.volume().sample(random), sound.pitch().sample(random));
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> value == null ? CompletableFuture.completedFuture(null) : original(admission, position, 0, () -> {
            admission.world().playSound(null, position.x, position.y, position.z, value.event(), value.source(), value.volume(), value.pitch()); return null;
        })));
    }
    private record Sound(net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> event, net.minecraft.sounds.SoundSource source, float volume, float pitch) { }
    private static CompletableFuture<Void> particles(SpawnParticlesEffect particles, ScarpetAttackEnchantments.SourceAdmission admission, Entity target, Vec3 position) {
        return ScarpetLootConditions.actor(target, () -> {
            var random = target.getRandom(); var motion = target.getKnownMovement(); float width = target.getBbWidth(), height = target.getBbHeight();
            return new Particles(particles.horizontalPosition().getCoordinate(position.x, position.x, width, random), particles.verticalPosition().getCoordinate(position.y, position.y + height / 2F, height, random),
                particles.horizontalPosition().getCoordinate(position.z, position.z, width, random), particles.horizontalVelocity().getVelocity(motion.x, random),
                particles.verticalVelocity().getVelocity(motion.y, random), particles.horizontalVelocity().getVelocity(motion.z, random), particles.speed().sample(random));
        }).thenCompose(ScarpetRuntime.captureNativeFunction(value -> original(admission, position, 0, () -> {
            admission.world().sendParticlesSource(target, particles.particle(), false, false, value.x(), value.y(), value.z(), 0, value.dx(), value.dy(), value.dz(), value.speed()); return null;
        })));
    }
    private record Particles(double x, double y, double z, double dx, double dy, double dz, double speed) { }
    private static CompletableFuture<Void> function(RunFunction function, ScarpetAttackEnchantments.SourceAdmission admission, Entity target, Vec3 position) {
        return original(admission, position, 0, () -> {
            var server = admission.world().getServer(); var manager = server.getFunctions(); var found = manager.get(function.function());
            if (found.isEmpty()) {
                org.slf4j.LoggerFactory.getLogger(RunFunction.class).error("Enchantment run_function effect failed for non-existent function {}", function.function());
                return null;
            }
            var source = server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput()
                .withEntity(target).withLevel(admission.world()).withPosition(position);
            return new FunctionCall(manager, found.get(), source);
        }).thenCompose(ScarpetRuntime.captureNativeFunction(call -> call == null ? CompletableFuture.completedFuture(null) :
            ScarpetLootConditions.actor(target, target::getRotationVector).thenCompose(ScarpetRuntime.captureNativeFunction(rotation -> original(admission, position, 0, () -> {
                call.manager().execute(call.function(), call.source().withRotation(rotation)); return (Void)null;
            })))));
    }
    private record FunctionCall(net.minecraft.server.ServerFunctionManager manager,
        net.minecraft.commands.functions.CommandFunction<net.minecraft.commands.CommandSourceStack> function, net.minecraft.commands.CommandSourceStack source) { }
    private static CompletableFuture<Void> summon(SummonEntityEffect summon, ScarpetAttackEnchantments.SourceAdmission admission, int level, EnchantedItemInUse item, Entity target, Vec3 position) {
        if (!net.minecraft.world.level.Level.isInSpawnableBounds(BlockPos.containing(position))) return CompletableFuture.completedFuture(null);
        var parent = ScarpetNativeWork.capture();
        return ScarpetLootActors.jobNative(admission.world(), () -> summonAccepted(summon, admission, item, target, position, parent == null ? ScarpetNativeWork.capture() : parent));
    }
    private static CompletableFuture<Void> summonAccepted(SummonEntityEffect summon, ScarpetAttackEnchantments.SourceAdmission admission,
        EnchantedItemInUse item, Entity target, Vec3 position, ScarpetNativeWork.Token parent) {
        return ScarpetAttackEnchantments.caller(admission, () -> summon.entityTypes().getRandomElement(admission.contextRandom()))
            .thenCompose(ScarpetRuntime.captureNativeFunction(selected -> selected.isEmpty() ? CompletableFuture.<Entity>completedFuture(null)
                : original(admission, position, 16, () -> {
                    var spawned = selected.get().value().create(admission.world(), null, BlockPos.containing(position), net.minecraft.world.entity.EntitySpawnReason.TRIGGERED, false, false);
                    if (spawned != null) { fun.bm.lophine.carpet.CarpetPlayerSpawnContinuations.holdUntil(spawned, ScarpetNativeWork.completionOf(parent)); ScarpetRetiredActors.capture(spawned); }
                    return spawned;
                }))).thenCompose(ScarpetRuntime.captureNativeFunction(spawned -> {
            if (spawned == null) return CompletableFuture.completedFuture(null);
            return original(admission, position, 16, () -> {
                if (spawned instanceof net.minecraft.world.entity.LightningBolt bolt && item.owner() instanceof net.minecraft.server.level.ServerPlayer player) bolt.setCause(player);
                if (spawned instanceof net.minecraft.world.entity.LightningBolt) admission.world().strikeLightning(spawned, item.itemStack().is(net.minecraft.world.item.Items.TRIDENT)
                    ? org.bukkit.event.weather.LightningStrikeEvent.Cause.TRIDENT : org.bukkit.event.weather.LightningStrikeEvent.Cause.ENCHANTMENT);
                else admission.world().addFreshEntityWithPassengers(spawned, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.ENCHANTMENT);
                return null;
            }).thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> summon.joinTeam() ? ScarpetLootConditions.actor(target, target::getTeam)
                : CompletableFuture.<net.minecraft.world.scores.PlayerTeam>completedFuture(null)))
                .thenCompose(ScarpetRuntime.captureNativeFunction(team -> {
                    var joined = team == null ? CompletableFuture.<Void>completedFuture(null) : ScarpetLootConditions.actor(spawned, spawned::getScoreboardName)
                        .thenCompose(ScarpetRuntime.captureNativeFunction(name -> original(admission, position, 16, () -> {
                            admission.world().getScoreboard().addPlayerToTeam(name, team); return (Void)null;
                        })));
                    return joined.thenCompose(ScarpetRuntime.captureNativeFunction(ignored -> mutate(spawned, () -> {
                        spawned.snapTo(position.x, position.y, position.z, spawned.getYRot(), spawned.getXRot()); return (Void)null;
                    })));
                }));
        }));
    }
}
