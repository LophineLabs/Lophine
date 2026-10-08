package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

public class ScarpetLootInternalReceiptsTest {
    @BeforeAll
    static void bootstrap() {
        ScarpetLootTablesTest.bootstrap();
    }

    @Test
    void nativeInternalVoidReceiptCannotBeCanceledBeforeRequiredDeathTail() {
        run(false);
    }

    @Test
    void nativeInternalListReceiptCannotBeCanceledBeforeRequiredDeathTail() {
        run(true);
    }

    private void run(boolean list) {
        var server = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
        var world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(server);
        when(world.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
        var config = mock(WorldConfiguration.class);
        config.fixes = config.new Fixes();
        when(world.paperConfig()).thenReturn(config);
        var global = mock(GlobalConfiguration.class);
        global.misc = global.new Misc();
        var child = new CompletableFuture<Void>();
        var condition = mock(LootItemCondition.class);
        when(condition.test(any())).thenAnswer(call -> {
            ScarpetNativeWork.record(child);
            return true;
        });
        var table = LootTable.lootTable().withPool(LootPool.lootPool().add(LootItem.lootTableItem(Items.STONE)).when(Holder.direct(condition))).build();
        var params = new LootParams.Builder(world).withParameter(LootContextParams.ORIGIN, Vec3.ZERO).create(LootContextParamSets.CHEST);
        var tail = new java.util.concurrent.atomic.AtomicInteger();
        try (var ticks = mockStatic(TickThread.class); var configs = mockStatic(GlobalConfiguration.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(any(ServerLevel.class), any(BlockPos.class))).thenReturn(true);
            configs.when(GlobalConfiguration::get).thenReturn(global);
            CompletableFuture<?> actual = list ? table.carpetGetRandomItemsNativeAsync(params) : table.carpetGetRandomItemsNativeAsync(params, 3, stack -> {
            });
            var required = actual.thenRun(tail::incrementAndGet);
            assertFalse(actual.cancel(false));
            assertFalse(required.isDone());
            assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
            child.complete(null);
            required.join();
            assertEquals(1, tail.get());
            if (list) assertEquals(1, ((java.util.List<?>) actual.join()).size());
        }
    }
}