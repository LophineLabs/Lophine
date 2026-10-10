package carpet.script.external;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetRespawnBlockExplosionTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void actualBedBlockDelegatesItsTrueExplosionObserverToTypedInteraction() throws Exception {
        block(false);
    }

    @Test
    void actualAnchorBlockDelegatesItsTrueExplosionObserverToTypedInteraction() throws Exception {
        block(true);
    }

    private void block(boolean anchor) throws Exception {
        var world = mock(ServerLevel.class);
        var server = mock(MinecraftServer.class);
        when(world.getServer()).thenReturn(server);
        var player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(world);
        var sources = mock(DamageSources.class);
        var damage = mock(DamageSource.class);
        when(world.damageSources()).thenReturn(sources);
        when(sources.badRespawnPointExplosion(any())).thenReturn(damage);
        when(damage.causingBlockSnapshot(any())).thenReturn(damage);
        var craft = mock(org.bukkit.craftbukkit.block.CraftBlock.class);
        when(craft.getState()).thenReturn(mock(org.bukkit.block.BlockState.class));
        when(world.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
        when(world.getFluidState(any())).thenReturn(net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState());
        var nativeTail = new CompletableFuture<Void>();
        var captured = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<?>>();
        doAnswer(call -> {
            ScarpetNativeWork.record(nativeTail);
            return null;
        }).when(world).explode(isNull(), eq(damage), nullable(ExplosionDamageCalculator.class), any(Vec3.class), eq(5F), eq(true), eq(Level.ExplosionInteraction.BLOCK));
        try (var blocks = mockStatic(org.bukkit.craftbukkit.block.CraftBlock.class); var anchorRules = mockStatic(RespawnAnchorBlock.class, CALLS_REAL_METHODS); var interactions = mockStatic(ScarpetInteractionContinuations.class); var actors = mockStatic(ScarpetExplosionActors.class)) {
            blocks.when(() -> org.bukkit.craftbukkit.block.CraftBlock.at(world, BlockPos.ZERO)).thenReturn(craft);
            anchorRules.when(() -> RespawnAnchorBlock.canSetSpawn(world, BlockPos.ZERO)).thenReturn(false);
            actors.when(() -> ScarpetExplosionActors.entity(eq(player), any())).thenAnswer(call -> CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(1)).get()));
            interactions.when(() -> ScarpetInteractionContinuations.after(eq(player), any(), any())).thenAnswer(call -> {
                captured.set(call.getArgument(1));
                return InteractionResult.PASS;
            });
            var type = anchor ? RespawnAnchorBlock.class : BedBlock.class;
            var method = anchor ? type.getDeclaredMethod("useWithoutItem", BlockState.class, Level.class, BlockPos.class, net.minecraft.world.entity.player.Player.class, BlockHitResult.class) : type.getDeclaredMethod("destroyOnUse", BlockState.class, Level.class, BlockPos.class, net.minecraft.world.entity.player.Player.class);
            method.setAccessible(true);
            var object = anchor ? Blocks.RESPAWN_ANCHOR : Blocks.BED.red();
            var state = anchor ? object.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 1) : object.defaultBlockState();
            var result = anchor ? method.invoke(object, state, world, BlockPos.ZERO, player, null) : method.invoke(object, state, world, BlockPos.ZERO, player);
            assertSame(InteractionResult.PASS, result);
            assertNotNull(captured.get());
            assertFalse(captured.get().isDone());
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            nativeTail.complete(null);
            assertTrue(captured.get().isDone());
            assertFalse(captured.get().isCompletedExceptionally());
        }
    }
}
