package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import net.minecraft.server.level.ServerLevel;

import java.util.function.Supplier;

/**
 * The synchronous API never substitutes a provisional value for accepted asynchronous native work.
 */
public final class CarpetSynchronousExplosionScope {
    private static final ThreadLocal<ServerLevel> CURRENT = new ThreadLocal<>();

    private CarpetSynchronousExplosionScope() {
    }

    public static boolean active(ServerLevel world) {
        return CURRENT.get() == world;
    }

    public static <T> T run(ServerLevel world, net.minecraft.world.entity.Entity owner, Supplier<T> operation) {
        var actual = observe(world, owner, operation);
        if (!actual.isDone())
            throw new Unavailable("A Bukkit callback introduced deferred native work; accepted work remains registered and will drain", true);
        return ScarpetNativeWork.recoverGuestValue(actual).getNow(null);
    }

    /**
     * The asynchronous native API preserves its true count and every unexpected committed callback child.
     */
    public static <T> java.util.concurrent.CompletableFuture<T> observeCore(ServerLevel world, net.minecraft.world.entity.Entity owner, Supplier<T> operation) {
        return ScarpetNativeWork.recoverGuestValue(observe(world, owner, operation));
    }

    private static <T> java.util.concurrent.CompletableFuture<T> observe(ServerLevel world, net.minecraft.world.entity.Entity owner, Supplier<T> operation) {
        var actual = ScarpetNativeWork.observeNative(owner, () -> {
            ServerLevel previous = CURRENT.get();
            CURRENT.set(world);
            try {
                return operation.get();
            } finally {
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
            }
        });
        ScarpetNativeWork.trackNative(world.getServer(), actual);
        return actual;
    }

    public static Unavailable unavailable(String reason) {
        return new Unavailable(reason, CURRENT.get() != null);
    }

    public static Unavailable beforeNative(String reason) {
        return new Unavailable(reason, false);
    }

    public static final class Unavailable extends IllegalStateException {
        private final boolean nativeStarted;

        private Unavailable(String reason, boolean nativeStarted) {
            super(reason + ". Use fun.bm.lophine.carpet.CarpetBukkitExplosions.createExplosionAsync for the complete asynchronous result.");
            this.nativeStarted = nativeStarted;
        }

        /**
         * True means accepted work may already have effects; a caller must not blindly retry it.
         */
        public boolean nativeStarted() {
            return nativeStarted;
        }
    }
}
