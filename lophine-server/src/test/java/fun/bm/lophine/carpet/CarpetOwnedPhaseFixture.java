package fun.bm.lophine.carpet;

import net.minecraft.server.level.ServerLevel;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.function.Function;

/**
 * Retains existing scheduler answers when a native actor switches to the owned-phase lease entry.
 */
public final class CarpetOwnedPhaseFixture {
    private CarpetOwnedPhaseFixture() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static MockedStatic<CarpetRegionLease> open() {
        return Mockito.mockStatic(CarpetRegionLease.class, call -> {
            String method = call.getMethod().getName();
            if (method.equals("runOwnedPhaseValue") || method.equals("runOwnedLoadedPhaseValue")) {
                ServerLevel world = call.getArgument(0);
                int minX = call.getArgument(1), minZ = call.getArgument(2);
                int maxX = call.getArgument(3), maxZ = call.getArgument(4);
                Function body = call.getArgument(5);
                return method.equals("runOwnedLoadedPhaseValue")
                        ? CarpetRegionLease.runLoadedValue(world, minX, minZ, maxX, maxZ, body)
                        : CarpetRegionLease.runValue(world, minX, minZ, maxX, maxZ, body);
            }
            return Mockito.RETURNS_DEFAULTS.answer(call);
        });
    }
}
