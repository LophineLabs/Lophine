package fun.bm.lophine.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-position native event barrier for an admitted hidden BedrockAction critical sequence.
 */
public final class OrgHiddenBedrockSignals {
    private record Key(ServerLevel world, BlockPos position) {
    }

    private record Executing(ServerLevel world, BlockEventData event, boolean ready) {
    }

    private static final Map<Key, OrgHiddenBedrockEventProof<BlockEventData>> PROOFS = new HashMap<>();
    private static final ThreadLocal<Executing> EXECUTING = new ThreadLocal<>();

    private OrgHiddenBedrockSignals() {
    }

    public static final class Ticket implements AutoCloseable {
        private final Key key;
        private final OrgHiddenBedrockEventProof<BlockEventData>.Ticket ticket;

        private Ticket(Key key, OrgHiddenBedrockEventProof<BlockEventData>.Ticket ticket) {
            this.key = key;
            this.ticket = ticket;
        }

        void replaced() {
            synchronized (PROOFS) {
                ticket.replaced();
            }
        }

        public void close() {
            synchronized (PROOFS) {
                ticket.close();
                prune(key);
            }
        }
    }

    static Ticket arm(ServerLevel world, BlockPos position) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(world, position, "Bedrock sequence must be armed on its actual owner");
        synchronized (PROOFS) {
            var key = new Key(world, position.immutable());
            var proof = PROOFS.computeIfAbsent(key, ignored -> new OrgHiddenBedrockEventProof<>());
            return new Ticket(key, proof.arm());
        }
    }

    private static boolean matching(BlockEventData event) {
        return event.block() == Blocks.PISTON && (event.paramA() == 1 || event.paramA() == 2) && Direction.from3DDataValue(event.paramB() & 7) == Direction.UP;
    }

    public static void queued(ServerLevel world, BlockEventData event) {
        if (!matching(event)) return;
        synchronized (PROOFS) {
            PROOFS.computeIfAbsent(new Key(world, event.pos().immutable()), ignored -> new OrgHiddenBedrockEventProof<>()).queued(event);
        }
    }

    public static boolean defer(ServerLevel world, BlockEventData event) {
        if (!matching(event)) return false;
        synchronized (PROOFS) {
            var proof = PROOFS.get(new Key(world, event.pos()));
            return proof != null && proof.defer(event);
        }
    }

    public static void consumed(ServerLevel world, BlockEventData event) {
        if (!matching(event)) return;
        synchronized (PROOFS) {
            var key = new Key(world, event.pos());
            var proof = PROOFS.get(key);
            if (proof != null) {
                proof.consumed(event);
                prune(key);
            }
        }
    }

    private static void prune(Key key) {
        var proof = PROOFS.get(key);
        if (proof != null && proof.empty()) PROOFS.remove(key);
    }

    public static Scope executing(ServerLevel world, BlockEventData event) {
        boolean ready = false;
        if (matching(event)) synchronized (PROOFS) {
            var proof = PROOFS.get(new Key(world, event.pos()));
            ready = proof != null && proof.ready(event);
        }
        var scope = new Scope(EXECUTING.get());
        EXECUTING.set(new Executing(world, event, ready));
        return scope;
    }

    public static final class Scope implements AutoCloseable {
        private final Executing previous;

        private Scope(Executing previous) {
            this.previous = previous;
        }

        public void close() {
            if (previous == null) EXECUTING.remove();
            else EXECUTING.set(previous);
        }
    }

    public static boolean allows(Level level, BlockPos position, int type, int data, Direction actual) {
        var executing = EXECUTING.get();
        return executing != null && executing.ready && executing.world == level && executing.event.pos().equals(position)
                && executing.event.paramA() == type && executing.event.paramB() == data && actual == Direction.DOWN && matching(executing.event);
    }

    public static void clearAll() {
        synchronized (PROOFS) {
            PROOFS.clear();
        }
    }
}
