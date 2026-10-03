package fun.bm.lophine.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Carries the original instance through the normal chunk, light, POI and physics update path.
 */
public final class CarriedBlockEntityPlacement {
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    private CarriedBlockEntityPlacement() {
    }

    public static boolean place(Level level, BlockPos pos, BlockState state, @Nullable BlockEntity entity, int flags) {
        if (entity == null || !state.hasBlockEntity() || !entity.isValidBlockState(state)) {
            return level.setBlock(pos, state, flags);
        }
        try (Scope ignored = begin(level, pos, entity)) {
            return level.setBlock(pos, state, flags);
        }
    }

    public static Scope begin(Level level, BlockPos pos, BlockEntity entity) {
        Pending parent = PENDING.get();
        PENDING.set(new Pending(level, pos.immutable(), entity));
        return () -> {
            if (parent == null) PENDING.remove();
            else PENDING.set(parent);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static @Nullable BlockEntity take(Level level, BlockPos pos, BlockState state) {
        Pending pending = PENDING.get();
        if (pending == null || pending.consumed || pending.level != level || !pending.pos.equals(pos)
                || !pending.entity.isValidBlockState(state)) return null;
        pending.consumed = true;
        BlockEntity entity = pending.entity;
        entity.carpetSetPosition(pos);
        entity.setLevel(level);
        entity.setBlockState(state);
        entity.clearRemoved();
        return entity;
    }

    private static final class Pending {
        final Level level;
        final BlockPos pos;
        final BlockEntity entity;
        boolean consumed;

        Pending(Level level, BlockPos pos, BlockEntity entity) {
            this.level = level;
            this.pos = pos;
            this.entity = entity;
        }
    }
}
