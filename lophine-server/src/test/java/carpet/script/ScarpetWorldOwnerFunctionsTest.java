package carpet.script;

import carpet.script.api.WorldAccess;
import carpet.script.external.ScarpetRuntime;
import carpet.script.utils.WorldTools;
import carpet.script.value.BlockValue;
import carpet.script.value.Value;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetWorldOwnerFunctionsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final class Host extends ScriptHost {
        Host() {
            super(null, new ScriptServer() {
                @Override
                public Path resolveResource(String name) {
                    return Path.of(name);
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
    void realSetFunctionMutatesOnlyInsideItsBlockActorWithoutCallingTheRemovedMainThreadApi() {
        for (boolean succeeds : new boolean[]{false, true}) {
            var server = mock(MinecraftServer.class);
            var world = mock(ServerLevel.class);
            var source = mock(CommandSourceStack.class);
            when(source.getServer()).thenReturn(server);
            when(source.getLevel()).thenReturn(world);
            var position = new BlockPos(4096, 71, 0);
            when(world.getBlockState(position)).thenReturn(Blocks.AIR.defaultBlockState());
            var owning = new AtomicBoolean();
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
            when(world.setBlock(position, Blocks.STONE.defaultBlockState(), flags)).thenAnswer(call -> {
                assertTrue(owning.get(), "The actual world mutation must execute on its destination actor");
                return succeeds;
            });
            doThrow(new UnsupportedOperationException("Folia has no main tick thread"))
                    .when(server).executeBlocking(any(Runnable.class));
            var context = new CarpetContext(new Host(), source, BlockPos.ZERO);
            context.initialize();
            context.with("target", (c, type) -> new BlockValue(world, position));
            context.with("template", (c, type) -> new BlockValue(Blocks.STONE.defaultBlockState()));
            var expression = new Expression("set(target, template)");
            WorldAccess.apply(expression);
            try (var actors = mockStatic(ScarpetRuntime.class)) {
                actors.when(() -> ScarpetRuntime.atBlock(eq(world), eq(position), any(Supplier.class)))
                        .thenAnswer(call -> {
                            owning.set(true);
                            try {
                                return ((Supplier<?>) call.getArgument(2)).get();
                            } finally {
                                owning.set(false);
                            }
                        });
                Value result = expression.executeAndEvaluate(context, true, Expression.LoadOverride.DEFAULT, null).getLeft();
                assertEquals(succeeds, result.getBoolean());
                if (succeeds) {
                    assertInstanceOf(BlockValue.class, result);
                    assertEquals(position, ((BlockValue) result).getPos());
                }
                verify(world).setBlock(position, Blocks.STONE.defaultBlockState(), flags);
                verify(server, never()).executeBlocking(any(Runnable.class));
            }
        }
    }

    @Test
    void realReloadChunkFunctionRefreshesOnItsDestinationActorWithoutTheMainThreadApi() {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        var source = mock(CommandSourceStack.class);
        when(source.getServer()).thenReturn(server);
        when(source.getLevel()).thenReturn(world);
        var position = new BlockPos(8192, 71, 0);
        doThrow(new UnsupportedOperationException("Folia has no main tick thread"))
                .when(server).executeBlocking(any(Runnable.class));
        var context = new CarpetContext(new Host(), source, BlockPos.ZERO);
        context.initialize();
        context.with("target", (c, type) -> new BlockValue(world, position));
        var expression = new Expression("reload_chunk(target)");
        WorldAccess.apply(expression);
        var owning = new AtomicBoolean();
        try (var actors = mockStatic(ScarpetRuntime.class); var chunks = mockStatic(WorldTools.class)) {
            actors.when(() -> ScarpetRuntime.atBlock(eq(world), eq(position), any(Supplier.class)))
                    .thenAnswer(call -> {
                        owning.set(true);
                        try {
                            return ((Supplier<?>) call.getArgument(2)).get();
                        } finally {
                            owning.set(false);
                        }
                    });
            chunks.when(() -> WorldTools.forceChunkUpdate(position, world))
                    .thenAnswer(call -> {
                        assertTrue(owning.get());
                        return null;
                    });
            assertTrue(expression.executeAndEvaluate(context, true, Expression.LoadOverride.DEFAULT, null).getLeft().getBoolean());
            chunks.verify(() -> WorldTools.forceChunkUpdate(position, world));
            verify(server, never()).executeBlocking(any(Runnable.class));
        }
    }
}
