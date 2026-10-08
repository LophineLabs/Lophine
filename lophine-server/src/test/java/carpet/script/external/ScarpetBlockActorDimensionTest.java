package carpet.script.external;

import carpet.script.*;
import carpet.script.Module;
import carpet.script.argument.Vector3Argument;
import carpet.script.value.BlockValue;
import carpet.script.value.EntityValue;
import carpet.script.value.NumericValue;
import carpet.script.value.Value;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetBlockActorDimensionTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Host extends ScriptHost {
        Host() {
            super(null, new carpet.script.ScriptServer() {
                @Override
                public java.nio.file.Path resolveResource(String name) {
                    return java.nio.file.Path.of(name);
                }
            }, false, null, Expression.LoadOverride.DEFAULT);
        }

        @Override
        protected Module getModuleOrLibraryByName(String name) {
            return null;
        }

        @Override
        protected void runModuleCode(Context context, Module module) {
        }

        @Override
        protected ScriptHost duplicate() {
            return new Host();
        }
    }

    @Test
    void foreignBlockReadAndInventoryRunOnTheLocatorsDimensionWithoutChangingOuterSource() {
        ServerLevel outer = mock(ServerLevel.class), foreign = mock(ServerLevel.class);
        CommandSourceStack source = mock(CommandSourceStack.class), located = mock(CommandSourceStack.class);
        when(source.getLevel()).thenReturn(outer);
        when(source.withLevel(foreign)).thenReturn(located);
        when(located.getLevel()).thenReturn(foreign);
        BlockPos position = new BlockPos(4096, 70, -4096);
        var block = new BlockValue(foreign, position);
        when(foreign.getBlockState(position)).thenReturn(Blocks.STONE.defaultBlockState());
        var context = new CarpetContext(new Host(), source, new BlockPos(1, 2, 3));
        context.with("kept", (c, t) -> Value.TRUE);
        AtomicReference<ServerLevel> owner = new AtomicReference<>();
        try (var runtime = mockStatic(ScarpetRuntime.class)) {
            runtime.when(() -> ScarpetRuntime.atBlock(any(), any(), any(Supplier.class))).thenAnswer(call -> {
                ServerLevel world = call.getArgument(0);
                assertSame(foreign, world);
                owner.set(world);
                return ((Supplier<?>) call.getArgument(2)).get();
            });
            var read = ActorFunctions.block(0, true, (c, t, args) -> {
                assertSame(owner.get(), ((CarpetContext) c).level());
                assertEquals(context.origin(), ((CarpetContext) c).origin());
                assertSame(context.variables, c.variables);
                return carpet.script.value.BooleanValue.of(((BlockValue) args.getFirst()).getBlockState().is(Blocks.STONE));
            });
            assertTrue(read.apply(context, Context.NONE, List.of(block)).getBoolean());
            var inventory = ActorFunctions.inventory((c, t, args) -> {
                assertSame(foreign, ((CarpetContext) c).level());
                return new NumericValue(17);
            });
            assertEquals(17, inventory.apply(context, Context.NONE, List.of(block)).readInteger());
        }
        assertSame(outer, context.level());
        verify(outer, never()).getBlockState(any());
    }

    @Test
    void foreignSetTargetUsesItsOwnActorWhileSourceStateIsCapturedBeforeDispatch() {
        ServerLevel outer = mock(ServerLevel.class), destination = mock(ServerLevel.class);
        CommandSourceStack source = mock(CommandSourceStack.class), located = mock(CommandSourceStack.class);
        when(source.getLevel()).thenReturn(outer);
        when(source.withLevel(destination)).thenReturn(located);
        when(located.getLevel()).thenReturn(destination);
        var context = new CarpetContext(new Host(), source, BlockPos.ZERO);
        BlockPos position = new BlockPos(8192, 60, 0);
        var target = new BlockValue(destination, position);
        var state = new BlockValue(Blocks.STONE.defaultBlockState());
        try (var runtime = mockStatic(ScarpetRuntime.class)) {
            runtime.when(() -> ScarpetRuntime.atBlock(eq(destination), eq(position), any(Supplier.class)))
                    .thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
            var set = ActorFunctions.setBlock((c, t, args) -> {
                assertSame(destination, ((CarpetContext) c).level());
                BlockValue captured = (BlockValue) args.get(1);
                assertSame(destination, captured.getWorld());
                assertSame(Blocks.STONE.defaultBlockState(), captured.getBlockState());
                return Value.TRUE;
            });
            assertTrue(set.apply(context, Context.NONE, List.of(target, state)).getBoolean());
            runtime.verify(() -> ScarpetRuntime.atBlock(eq(outer), any(), any(Supplier.class)), never());
        }
        assertSame(outer, context.level());
    }

    @Test
    void nestedEntityLocatorsDoNotMixPositionsAfterNativeIdReuseOrChange() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var allocator = (sun.misc.Unsafe) field.get(null);
        ItemEntity first = (ItemEntity) allocator.allocateInstance(ItemEntity.class);
        ItemEntity second = (ItemEntity) allocator.allocateInstance(ItemEntity.class);
        first.setId(7);
        second.setId(7);
        assertEquals(first, second);
        Vec3 a = new Vec3(1, 2, 3), b = new Vec3(9, 8, 7);
        Vector3Argument.withCapturedEntityPosition(first, a, () -> {
            Vector3Argument.withCapturedEntityPosition(second, b, () -> {
                assertEquals(a, Vector3Argument.findIn(List.of(new EntityValue(first)), 0, false, true).vec);
                assertEquals(b, Vector3Argument.findIn(List.of(new EntityValue(second)), 0, false, true).vec);
                first.setId(13);
                assertEquals(a, Vector3Argument.findIn(List.of(new EntityValue(first)), 0, false, true).vec);
                return null;
            });
            assertEquals(a, Vector3Argument.findIn(List.of(new EntityValue(first)), 0, false, true).vec);
            return null;
        });
    }
}
