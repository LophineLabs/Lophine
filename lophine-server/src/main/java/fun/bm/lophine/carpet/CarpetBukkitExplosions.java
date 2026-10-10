package fun.bm.lophine.carpet;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;

import java.util.concurrent.CompletableFuture;

/**
 * Server plugin bridge: its Boolean follows the actual core and every owner packet receipt.
 */
public final class CarpetBukkitExplosions {
    private CarpetBukkitExplosions() {
    }

    public static CompletableFuture<Boolean> createExplosionAsync(org.bukkit.World world, double x, double y, double z, float power, boolean fire, boolean breakBlocks, org.bukkit.entity.Entity source) {
        return createExplosionAsync(world, x, y, z, power, fire, breakBlocks, source, null);
    }

    public static CompletableFuture<Boolean> createExplosionAsync(org.bukkit.World world, double x, double y, double z, float power, boolean fire, boolean breakBlocks, org.bukkit.entity.Entity source, Boolean excludeSourceFromDamage) {
        if (!(world instanceof CraftWorld craft))
            return CompletableFuture.failedFuture(new IllegalArgumentException("World is not a native server world"));
        if (source != null && !(source instanceof CraftEntity))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Source is not a native server entity"));
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(power) || power < 0F)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Explosion coordinates and nonnegative power must be finite"));
        Level.ExplosionInteraction mode = !breakBlocks ? Level.ExplosionInteraction.NONE : source == null ? Level.ExplosionInteraction.STANDARD :
                source instanceof org.bukkit.entity.TNTPrimed || source instanceof org.bukkit.entity.minecart.ExplosiveMinecart ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.MOB;
        var actual = craft.getHandle().explode0Async(source == null ? null : ((CraftEntity) source).getHandleRaw(), null, null, x, y, z, power, fire, mode,
                ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, Level.DEFAULT_EXPLOSION_BLOCK_PARTICLES, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE,
                excludeSourceFromDamage == null ? null : explosion -> explosion.excludeSourceFromDamage = excludeSourceFromDamage);
        var result = actual.thenApply(explosion -> !explosion.wasCanceled);
        carpet.script.external.ScarpetNativeWork.aliasDependency(result, actual);
        return result;
    }
}
