package carpet.script.external;

import carpet.script.value.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ScarpetTraceTest {
    private static ScarpetTrace.Snapshot source(ServerLevel world, Entity root) {
        Entity source = mock(Entity.class);
        return new ScarpetTrace.Snapshot(source, world, Vec3.ZERO, new Vec3(1, 0, 0), new AABB(-0.5, -1, -0.5, 0.5, 1, 0.5), root, false, null);
    }

    private static Entity target(double x, Entity root) {
        Entity target = mock(Entity.class);
        when(target.getBoundingBox()).thenReturn(new AABB(x, -1, -1, x + 1, 1, 1));
        when(target.getRootVehicle()).thenReturn(root);
        return target;
    }

    @Test
    void actualRaySelectsClosestEntityBeforeABlockAndPreservesExactPosition() {
        ServerLevel world = mock(ServerLevel.class);
        Entity root = mock(Entity.class), other = mock(Entity.class);
        Entity near = target(2, other), far = target(5, other);
        when(world.clip(any())).thenReturn(new BlockHitResult(new Vec3(4, 0, 0), Direction.WEST, new BlockPos(4, 0, 0), false));
        when(world.getEntities(any(Entity.class), any(AABB.class), any())).thenReturn(List.of(far, near));
        Value result = ScarpetTrace.evaluate(source(world, root), ListValue.of(NumericValue.of(8), StringValue.of("entities"), StringValue.of("blocks"), StringValue.of("exact")));
        assertEquals("[2, 0, 0]", result.getString());
    }

    @Test
    void actualRayIgnoresASharedRootVehicleUntilAnInsideBoxHit() {
        ServerLevel world = mock(ServerLevel.class);
        Entity root = mock(Entity.class), passenger = target(1, root);
        when(world.clip(any())).thenReturn(new BlockHitResult(new Vec3(4, 0, 0), Direction.WEST, new BlockPos(4, 0, 0), false));
        when(world.getEntities(any(Entity.class), any(AABB.class), any())).thenReturn(List.of(passenger));
        Value block = ScarpetTrace.evaluate(source(world, root), null);
        assertInstanceOf(BlockValue.class, block);
        assertEquals(new BlockPos(4, 0, 0), ((BlockValue) block).getPos());
        Entity inside = target(-0.5, root);
        when(world.getEntities(any(Entity.class), any(AABB.class), any())).thenReturn(List.of(inside));
        Value selected = ScarpetTrace.evaluate(source(world, root), null);
        assertSame(inside, ((EntityValue) selected).getEntity());
    }

    @Test
    void entitiesOnlyDoesNotClipBlocksAndUnknownOptionsRetainTheSourceError() {
        ServerLevel world = mock(ServerLevel.class);
        Entity root = mock(Entity.class);
        when(world.getEntities(any(Entity.class), any(AABB.class), any())).thenReturn(List.of());
        assertSame(Value.NULL, ScarpetTrace.evaluate(source(world, root), ListValue.of(NumericValue.of(128), StringValue.of("entities"))));
        verify(world, never()).clip(any());
        var error = assertThrows(carpet.script.exception.InternalExpressionException.class,
                () -> ScarpetTrace.evaluate(source(world, root), ListValue.of(NumericValue.of(3), StringValue.of("unknown"))));
        assertEquals("Incorrect tracing: unknown", error.getMessage());
    }
}
