// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.CarpetContext;
import carpet.script.argument.Vector3Argument;
import carpet.script.exception.InternalExpressionException;
import carpet.script.value.EntityValue;
import carpet.script.value.NumericValue;
import carpet.script.value.Value;
import carpet.script.value.ValueConversions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * VM entry: actual Native explosion, damage continuations and audience packets finish before returning.
 */
public final class ScarpetExplosions {
    private static final WeightedList<ExplosionParticleInfo> PARTICLES = WeightedList.<ExplosionParticleInfo>builder()
            .add(new ExplosionParticleInfo(ParticleTypes.POOF, 0.5F, 1.0F)).add(new ExplosionParticleInfo(ParticleTypes.SMOKE, 1.0F, 1.0F)).build();

    private ScarpetExplosions() {
    }

    public static Value create(CarpetContext context, List<Value> args) {
        if (args.isEmpty())
            throw new InternalExpressionException("'create_explosion' requires at least a position to explode");
        List<Value> captured = ActorFunctions.snapshotArguments(args);
        Vector3Argument location = Vector3Argument.findIn(captured, 0, false, true);
        Vec3 center = location.vec;
        float power = captured.size() > location.offset ? NumericValue.asNumber(captured.get(location.offset), "explosion power").getFloat() : 4;
        if (power < 0) throw new InternalExpressionException("Explosion power cannot be negative");
        if (!Float.isFinite(power)) throw new InternalExpressionException("Explosion power must be finite");
        Explosion.BlockInteraction interaction = Explosion.BlockInteraction.DESTROY;
        if (captured.size() > location.offset + 1) {
            String name = captured.get(location.offset + 1).getString();
            try {
                interaction = Explosion.BlockInteraction.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new InternalExpressionException("Illegal explosions block behaviour: " + name);
            }
        }
        boolean fire = captured.size() > location.offset + 2 && captured.get(location.offset + 2).getBoolean();
        Entity source = entity(captured, location.offset + 3, "Fourth");
        Entity attacking = entity(captured, location.offset + 4, "Fifth");
        if (attacking != null && !(attacking instanceof LivingEntity))
            throw new InternalExpressionException("Attacking entity needs to be a living thing, " + ValueConversions.of(context.registry(net.minecraft.core.registries.Registries.ENTITY_TYPE).getKey(attacking.getType())).getString() + " ain't it.");
        LivingEntity attacker = (LivingEntity) attacking;
        Explosion.BlockInteraction mode = interaction;
        int radius = (int) Math.ceil(power * 2.0D) + 2;
        BlockPos point = BlockPos.containing(center);
        var observed = ScarpetNativeWork.<CompletableFuture<Void>>observeNative(source, () -> {
            java.util.function.Supplier<ServerExplosion> construct = () -> new ServerExplosion(context.level(), source, null, null, center, power, fire, mode) {
                @Override
                public LivingEntity getIndirectSourceEntity() {
                    return attacker;
                }
            };
            CompletableFuture<ServerExplosion> constructed = source == null ? ScarpetExplosionActors.world(context.level(), point, construct)
                    : ScarpetExplosionActors.entity(source, construct);
            CompletableFuture<Void> nativeCompletion = constructed.thenCompose(explosion -> explosion.carpetStartDecisionAsync().thenCompose(cancelled -> {
                if (Boolean.TRUE.equals(cancelled)) return explosion.carpetExplodeAsync().thenApply(count -> null);
                // The HEAD event precedes ticket acquisition. Once accepted, retain the whole core and actual packet receipts.
                return ScarpetExplosionActors.blocks(explosion, List.of(point.offset(-radius, 0, -radius), point.offset(radius, 0, radius)), () ->
                        explosion.carpetExplodeAsync().thenCompose(count -> ScarpetExplosionPackets.send(explosion, count, ParticleTypes.EXPLOSION,
                                ParticleTypes.EXPLOSION_EMITTER, PARTICLES, SoundEvents.GENERIC_EXPLODE))).thenCompose(completed -> completed);
            }));
            ScarpetNativeWork.record(nativeCompletion);
            return nativeCompletion;
        });
        CompletableFuture<Void> actual = observed.thenCompose(completed -> completed);
        ScarpetNativeWork.aliasDependency(actual, observed);
        ScarpetRuntime.await(ScarpetNativeWork.trackNative(context.server(), actual));
        return Value.TRUE;
    }

    private static Entity entity(List<Value> args, int offset, String number) {
        if (args.size() <= offset || args.get(offset).isNull()) return null;
        Value value = args.get(offset);
        if (value instanceof EntityValue entity) return entity.getEntity();
        throw new InternalExpressionException(number + " parameter of the explosion has to be " + (number.equals("Fifth") ? "a living entity" : "an entity") + ", not " + value.getTypeString());
    }
}
