// SPDX-License-Identifier: MIT
package carpet.script.external;

import net.minecraft.world.entity.projectile.Projectile;

import java.util.concurrent.CompletableFuture;

/**
 * Retained real native hit receipts let enclosing projectile bodies await their original tail.
 */
public final class ScarpetProjectileContinuations {
    private static final WeakIdentityMap<Projectile, CompletableFuture<Void>> HITS = new WeakIdentityMap<>();

    private ScarpetProjectileContinuations() {
    }

    public static CompletableFuture<Void> pendingHit(Projectile projectile) {
        return HITS.get(projectile);
    }

    public static void publishHit(Projectile projectile, CompletableFuture<Void> actual) {
        HITS.put(projectile, actual);
    }
}
