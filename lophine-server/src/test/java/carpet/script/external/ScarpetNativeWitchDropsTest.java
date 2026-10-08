package carpet.script.external;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetNativeWitchDropsTest {
    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    int oldRedstone, oldGlowstone;

    @BeforeEach
    void preserve() {
        oldRedstone = GeneralCompatConfig.witchRedstoneDustDropController;
        oldGlowstone = GeneralCompatConfig.witchGlowstoneDustDropController;
        GeneralCompatConfig.witchRedstoneDustDropController = -1;
        GeneralCompatConfig.witchGlowstoneDustDropController = -1;
    }

    @AfterEach
    void restore() {
        GeneralCompatConfig.witchRedstoneDustDropController = oldRedstone;
        GeneralCompatConfig.witchGlowstoneDustDropController = oldGlowstone;
    }

    static void original(LivingEntity entity, ServerLevel level, DamageSource damage) {
        try {
            var method = LivingEntity.class.getDeclaredMethod("dropFromLootTable", ServerLevel.class, DamageSource.class, boolean.class);
            method.setAccessible(true);
            method.invoke(entity, level, damage, false);
        } catch (InvocationTargetException failed) {
            throw new CompletionException(failed.getCause());
        } catch (ReflectiveOperationException failed) {
            throw new RuntimeException(failed);
        }
    }

    static org.mockito.MockedConstruction<ItemStack> stacks() {
        return mockConstruction(ItemStack.class, (stack, context) -> {
            assertEquals(2, context.arguments().size());
            when(stack.getItem()).thenReturn((Item) context.arguments().get(0));
            when(stack.getCount()).thenReturn((int) context.arguments().get(1));
        });
    }

    @Test
    void originalZeroCountDropsStillConstructBothStacksAndUseTheActualEntityWorld() throws Exception {
        try (var f = new ScarpetNativeDeathCustomTest.Fixture(); var stacks = stacks()) {
            var witch = f.entity(Witch.class);
            doReturn(Optional.empty()).when(witch).getLootTable();
            GeneralCompatConfig.witchRedstoneDustDropController = 0;
            GeneralCompatConfig.witchGlowstoneDustDropController = 0;
            original(witch, f.foreign, f.damage);
            assertEquals(List.of(Items.REDSTONE, Items.GLOWSTONE_DUST), f.drops.stream().map(ItemStack::getItem).toList());
            assertEquals(List.of(0, 0), f.drops.stream().map(ItemStack::getCount).toList());
            verify(witch, never()).spawnAtLocation(eq(f.foreign), any(ItemStack.class));
        }
    }

    @Test
    void actualNativeZeroRedstoneWaitsItsTrueChildBeforeGlowstoneAndFinalValue() throws Exception {
        try (var f = new ScarpetNativeDeathCustomTest.Fixture(); var stacks = stacks()) {
            var witch = f.entity(Witch.class);
            doReturn(Optional.empty()).when(witch).getLootTable();
            GeneralCompatConfig.witchRedstoneDustDropController = 0;
            GeneralCompatConfig.witchGlowstoneDustDropController = 0;
            var redstone = f.child();
            var glowstone = f.child();
            var actual = f.nativeBody(witch, () -> original(witch, f.foreign, f.damage));
            assertEquals(List.of(Items.REDSTONE), f.drops.stream().map(ItemStack::getItem).toList());
            assertFalse(actual.isDone());
            redstone.complete(null);
            assertEquals(List.of(Items.REDSTONE, Items.GLOWSTONE_DUST), f.drops.stream().map(ItemStack::getItem).toList());
            assertFalse(actual.isDone());
            glowstone.complete(null);
            assertEquals(42, actual.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualNativeRedstoneFailureBlocksGlowstoneAndRetainsTheOriginalFailure() throws Exception {
        try (var f = new ScarpetNativeDeathCustomTest.Fixture(); var stacks = stacks()) {
            var witch = f.entity(Witch.class);
            doReturn(Optional.empty()).when(witch).getLootTable();
            GeneralCompatConfig.witchRedstoneDustDropController = 2;
            GeneralCompatConfig.witchGlowstoneDustDropController = 2;
            var redstone = f.child();
            var problem = new IllegalStateException("original redstone drop");
            var actual = f.nativeBody(witch, () -> original(witch, f.world, f.damage));
            redstone.completeExceptionally(problem);
            assertSame(problem, assertThrows(ExecutionException.class, () -> actual.get(3, TimeUnit.SECONDS)).getCause());
            assertEquals(List.of(Items.REDSTONE), f.drops.stream().map(ItemStack::getItem).toList());
            assertFalse(ScarpetNativeWork.onlyGuestFailure(problem));
        }
    }

    @Test
    void guestRedstoneFailureRetainsRawFailureAndContinuesTheAcceptedGlowstoneTail() throws Exception {
        try (var f = new ScarpetNativeDeathCustomTest.Fixture(); var stacks = stacks()) {
            var witch = f.entity(Witch.class);
            doReturn(Optional.empty()).when(witch).getLootTable();
            GeneralCompatConfig.witchRedstoneDustDropController = 2;
            GeneralCompatConfig.witchGlowstoneDustDropController = 2;
            var guest = new CompletableFuture<Void>();
            doAnswer(call -> {
                f.drops.add(call.getArgument(1));
                if (f.drops.size() == 1) ScarpetNativeWork.recordGuest(guest);
                return null;
            }).when(witch).spawnAtLocation(eq(f.world), any(ItemStack.class));
            var actual = f.nativeBody(witch, () -> original(witch, f.world, f.damage));
            assertEquals(1, f.drops.size());
            guest.completeExceptionally(new IllegalArgumentException("guest drop"));
            var failure = assertThrows(ExecutionException.class, () -> actual.get(3, TimeUnit.SECONDS)).getCause();
            assertTrue(ScarpetNativeWork.onlyGuestFailure(failure));
            assertEquals(List.of(Items.REDSTONE, Items.GLOWSTONE_DUST), f.drops.stream().map(ItemStack::getItem).toList());
        }
    }

    @Test
    void disabledOriginalTailCreatesNoExtraStack() throws Exception {
        try (var f = new ScarpetNativeDeathCustomTest.Fixture(); var stacks = stacks()) {
            var witch = f.entity(Witch.class);
            doReturn(Optional.empty()).when(witch).getLootTable();
            var actual = f.nativeBody(witch, () -> original(witch, f.world, f.damage));
            assertEquals(42, actual.get(3, TimeUnit.SECONDS));
            assertTrue(stacks.constructed().isEmpty());
            assertTrue(f.drops.isEmpty());
        }
    }
}
