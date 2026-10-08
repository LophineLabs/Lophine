package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.FloatRangePredicate;
import net.minecraft.world.level.storage.loot.IntLimit;
import net.minecraft.world.level.storage.loot.IntRangePredicate;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.providers.number.DispatcherProvider;
import net.minecraft.world.level.storage.loot.providers.number.floats.ContextFloatProvider;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Native record bodies are the algorithm oracle; foreign ownership, true children and seeded RNG are independent proof.
 */
public class ScarpetLootNumbersTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final String INT = "net.minecraft.world.level.storage.loot.providers.number.ints.", FLOAT = "net.minecraft.world.level.storage.loot.providers.number.floats.";

    private static Holder<ContextIntProvider> i(int value) {
        return Holder.direct(new net.minecraft.world.level.storage.loot.providers.number.ints.ConstantValue(value));
    }

    private static Holder<ContextFloatProvider> f(float value) {
        return Holder.direct(new net.minecraft.world.level.storage.loot.providers.number.floats.ConstantValue(value));
    }

    private static LootContext context(long seed) {
        var context = mock(LootContext.class);
        when(context.getRandom()).thenReturn(RandomSource.create(seed));
        return context;
    }

    private static ContextIntProvider integer(String type, Class<?>[] signature, Object... parameters) throws Exception {
        return (ContextIntProvider) Class.forName(INT + type).getConstructor(signature).newInstance(parameters);
    }

    private static ContextFloatProvider floating(String type, Class<?>[] signature, Object... parameters) throws Exception {
        return (ContextFloatProvider) Class.forName(FLOAT + type).getConstructor(signature).newInstance(parameters);
    }

    @Test
    void actualIntegerArithmeticRecordOracleCoversOverflowUnsafeNestingRoundingAndEmptyAggregates() throws Exception {
        var context = context(3);
        int[] edges = {Integer.MIN_VALUE, -19, -1, 0, 1, 3, 13, Integer.MAX_VALUE};
        for (int first : edges) {
            for (String type : List.of("Absolute", "Negate")) {
                var provider = integer(type, new Class<?>[]{Holder.class}, i(first));
                assertEquals(provider.getInt(context), ScarpetLootNumbers.integer(provider, context).join(), type + " " + first);
            }
            for (int second : edges)
                for (String type : List.of("Difference", "Quotient", "FloorQuotient", "Modulus", "FloorModulus", "Power")) {
                    var provider = integer(type, new Class<?>[]{Holder.class, Holder.class}, i(first), i(second));
                    assertEquals(provider.getInt(context), ScarpetLootNumbers.integer(provider, context).join(), type + " " + first + " " + second);
                }
            for (String type : List.of("Average", "Maximum", "Minimum", "Product", "Sum")) {
                var values = HolderSet.direct(List.of(i(first), i(19), i(-3)));
                var provider = integer(type, new Class<?>[]{HolderSet.class}, values);
                assertEquals(provider.getInt(context), ScarpetLootNumbers.integer(provider, context).join(), type + " " + first);
                var empty = integer(type, new Class<?>[]{HolderSet.class}, HolderSet.direct(List.<Holder<ContextIntProvider>>of()));
                assertEquals(empty.getInt(context), ScarpetLootNumbers.integer(empty, context).join(), type + " empty");
            }
        }
        var bad = new net.minecraft.world.level.storage.loot.providers.number.ints.Quotient(i(5), i(0));
        var nested = new net.minecraft.world.level.storage.loot.providers.number.ints.Sum(HolderSet.direct(List.of(Holder.direct(bad), i(9))));
        assertEquals(0, nested.getInt(context));
        assertEquals(0, ScarpetLootNumbers.integer(nested, context).join(), "An unsafe child failure defaults only the whole public expression, never child zero then plus nine");
        var convert = new net.minecraft.world.level.storage.loot.providers.number.ints.FromFloat(f(Float.POSITIVE_INFINITY));
        assertEquals(convert.getInt(context), ScarpetLootNumbers.integer(convert, context).join());
    }

    @Test
    void actualFloatingArithmeticOraclePreservesNonFiniteInnerValuesAndModulusRightFirstZeroShortCircuit() throws Exception {
        var context = context(8);
        float[] edges = {Float.NEGATIVE_INFINITY, -19.25F, -1F, -0F, 0F, 0.25F, 3F, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN};
        for (float value : edges) {
            for (String type : List.of("Absolute", "Negate", "Ceiling", "Cosine", "Floor", "Round", "Sine", "SquareRoot", "Truncate")) {
                var provider = floating(type, new Class<?>[]{Holder.class}, f(value));
                assertEquals(Float.floatToRawIntBits(provider.getFloat(context)), Float.floatToRawIntBits(ScarpetLootNumbers.floating(provider, context).join()), type + " " + value);
            }
            for (float right : edges)
                for (String type : List.of("Difference", "Quotient", "Modulus", "Power")) {
                    var provider = floating(type, new Class<?>[]{Holder.class, Holder.class}, f(value), f(right));
                    assertEquals(Float.floatToRawIntBits(provider.getFloat(context)), Float.floatToRawIntBits(ScarpetLootNumbers.floating(provider, context).join()), type + " " + value + " " + right);
                }
            for (String type : List.of("Average", "Maximum", "Minimum", "Product", "Sum", "Length")) {
                var provider = floating(type, new Class<?>[]{HolderSet.class}, HolderSet.direct(List.of(f(value), f(2.5F), f(-3F))));
                assertEquals(Float.floatToRawIntBits(provider.getFloat(context)), Float.floatToRawIntBits(ScarpetLootNumbers.floating(provider, context).join()), type + " " + value);
                var empty = floating(type, new Class<?>[]{HolderSet.class}, HolderSet.direct(List.<Holder<ContextFloatProvider>>of()));
                assertEquals(empty.getFloat(context), ScarpetLootNumbers.floating(empty, context).join(), type + " empty");
            }
        }
        var unknown = mock(ContextFloatProvider.class);
        var shorted = new net.minecraft.world.level.storage.loot.providers.number.floats.Modulus(Holder.direct(unknown), f(0));
        assertEquals(0F, ScarpetLootNumbers.floating(shorted, context).join());
        verifyNoInteractions(unknown);
        var innerInfinite = new net.minecraft.world.level.storage.loot.providers.number.floats.Quotient(f(1), f(0));
        var outer = new net.minecraft.world.level.storage.loot.providers.number.floats.Quotient(f(4), Holder.direct(innerInfinite));
        assertEquals(outer.getFloat(context), ScarpetLootNumbers.floating(outer, context).join(), "Unsafe infinity must survive to an outer division yielding finite zero");
        var fromInt = new net.minecraft.world.level.storage.loot.providers.number.floats.FromInt(i(Integer.MAX_VALUE));
        assertEquals(fromInt.getFloat(context), ScarpetLootNumbers.floating(fromInt, context).join());
        var enchant = new net.minecraft.world.level.storage.loot.providers.number.floats.EnchantmentLevelProvider(net.minecraft.world.item.enchantment.LevelBasedValue.perLevel(4, 3));
        when(context.getOptional(LootContextParams.ENCHANTMENT_LEVEL)).thenReturn(7);
        assertEquals(enchant.getFloat(context), ScarpetLootNumbers.floating(enchant, context).join());
    }

    @Test
    void seededNativeUniformBinomialWeightedAndNestedRandomTreesConsumeExactlyTheSameRandomStream() {
        var uniform = new net.minecraft.world.level.storage.loot.providers.number.ints.UniformGenerator(i(-4), i(7));
        var probability = new net.minecraft.world.level.storage.loot.providers.number.floats.UniformGenerator(f(0.15F), f(0.8F));
        var binomial = new net.minecraft.world.level.storage.loot.providers.number.ints.BinomialDistributionGenerator(Holder.direct(new net.minecraft.world.level.storage.loot.providers.number.ints.UniformGenerator(i(2), i(8))), Holder.direct(probability));
        var weighted = new net.minecraft.world.level.storage.loot.providers.number.ints.WeightedListValue(WeightedList.<Holder<ContextIntProvider>>builder().add(Holder.direct(uniform), 3).add(Holder.direct(binomial), 7).build());
        var sum = new net.minecraft.world.level.storage.loot.providers.number.ints.Sum(HolderSet.direct(List.of(Holder.direct(weighted), Holder.direct(binomial), Holder.direct(uniform))));
        var floatWeighted = new net.minecraft.world.level.storage.loot.providers.number.floats.WeightedListValue(WeightedList.<Holder<ContextFloatProvider>>builder().add(Holder.direct(probability), 3).add(Holder.direct(new net.minecraft.world.level.storage.loot.providers.number.floats.FromInt(Holder.direct(uniform))), 7).build());
        for (long seed = 0; seed < 24; seed++) {
            var vanilla = context(seed);
            var actual = context(seed);
            assertEquals(sum.getInt(vanilla), ScarpetLootNumbers.integer(sum, actual).join());
            assertEquals(vanilla.getRandom().nextLong(), actual.getRandom().nextLong(), "integer remaining rng seed " + seed);
            vanilla = context(seed);
            actual = context(seed);
            assertEquals(floatWeighted.getFloat(vanilla), ScarpetLootNumbers.floating(floatWeighted, actual).join());
            assertEquals(vanilla.getRandom().nextLong(), actual.getRandom().nextLong(), "float remaining rng seed " + seed);
        }
    }

    @Test
    void actualConditionalAndDispatcherWaitTheirSelectedRealConditionChildrenAndNeverTouchUnselectedBranches() {
        var context = context(4);
        var condition = mock(LootItemCondition.class);
        var after = mock(LootItemCondition.class);
        var child = new CompletableFuture<Void>();
        when(condition.test(context)).thenAnswer(call -> {
            ScarpetNativeWork.record(child);
            return true;
        });
        var bad = mock(ContextIntProvider.class);
        var provider = new net.minecraft.world.level.storage.loot.providers.number.ints.ConditionalValue(Holder.direct(condition), i(7), Holder.direct(bad));
        var selected = ScarpetLootNumbers.integer(provider, context);
        assertFalse(selected.isDone());
        verifyNoInteractions(bad);
        child.complete(null);
        assertEquals(7, selected.join());
        verifyNoInteractions(bad);
        var dispatcher = new net.minecraft.world.level.storage.loot.providers.number.ints.NumberDispatcher(List.of(new DispatcherProvider.Case<>(Holder.direct(condition), i(4)), new DispatcherProvider.Case<>(Holder.direct(after), Holder.direct(bad))), Holder.direct(bad));
        assertEquals(4, ScarpetLootNumbers.integer(dispatcher, context).join());
        verifyNoInteractions(after, bad);
        var floatProvider = new net.minecraft.world.level.storage.loot.providers.number.floats.ConditionalValue(Holder.direct(condition), f(4.25F), f(8));
        assertEquals(4.25F, ScarpetLootNumbers.floating(floatProvider, context).join());
        var floatDispatcher = new net.minecraft.world.level.storage.loot.providers.number.floats.NumberDispatcher(List.of(new DispatcherProvider.Case<>(Holder.direct(condition), f(2.5F)), new DispatcherProvider.Case<>(Holder.direct(after), f(3))), f(7));
        assertEquals(2.5F, ScarpetLootNumbers.floating(floatDispatcher, context).join());
        verifyNoInteractions(after);
    }

    @Test
    void dynamicRangeBoundsShortCircuitAndIntLimitEvaluatesBothActualBoundsInNativeOrder() throws Exception {
        var context = context(0);
        var max = mock(ContextIntProvider.class);
        var min = i(8);
        var constructor = IntRangePredicate.Line.class.getDeclaredConstructor(Optional.class, Optional.class);
        constructor.setAccessible(true);
        var line = (IntRangePredicate.Line) constructor.newInstance(Optional.of(min), Optional.of(Holder.direct(max)));
        assertFalse(ScarpetLootNumbers.ranges(line, context, 7).join());
        verifyNoInteractions(max);
        for (int value : new int[]{-4, 0, 5, 11, 20}) {
            var range = IntRangePredicate.range(0, 11);
            assertEquals(range.test(context, value), ScarpetLootNumbers.ranges(range, context, value).join());
            var limit = IntLimit.range(0, 11);
            assertEquals(limit.clamp(context, value), ScarpetLootNumbers.clamp(limit, context, value).join());
        }
        var floatMax = mock(ContextFloatProvider.class);
        var floatConstructor = FloatRangePredicate.Line.class.getDeclaredConstructor(Optional.class, Optional.class);
        floatConstructor.setAccessible(true);
        var floatLine = (FloatRangePredicate.Line) floatConstructor.newInstance(Optional.of(f(8)), Optional.of(Holder.direct(floatMax)));
        assertFalse(ScarpetLootNumbers.ranges(floatLine, context, 7F).join());
        verifyNoInteractions(floatMax);
        assertEquals(FloatRangePredicate.exact(2.5F).test(context, 2.5F), ScarpetLootNumbers.ranges(FloatRangePredicate.exact(2.5F), context, 2.5F).join());
        var order = new ArrayList<String>();
        var lowCondition = mock(LootItemCondition.class);
        var highCondition = mock(LootItemCondition.class);
        when(lowCondition.test(context)).thenAnswer(call -> {
            order.add("min");
            return true;
        });
        when(highCondition.test(context)).thenAnswer(call -> {
            order.add("max");
            return true;
        });
        var low = new net.minecraft.world.level.storage.loot.providers.number.ints.ConditionalValue(Holder.direct(lowCondition), i(6), i(0));
        var high = new net.minecraft.world.level.storage.loot.providers.number.ints.ConditionalValue(Holder.direct(highCondition), i(4), i(0));
        var limitConstructor = IntLimit.class.getDeclaredConstructor(Optional.class, Optional.class);
        limitConstructor.setAccessible(true);
        var backwards = (IntLimit) limitConstructor.newInstance(Optional.of(Holder.direct(low)), Optional.of(Holder.direct(high)));
        assertEquals(4, ScarpetLootNumbers.clamp(backwards, context, 2).join());
        assertEquals(List.of("min", "max"), order);
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel original = mock(ServerLevel.class), foreign = mock(ServerLevel.class);
        final Entity entity = mock(Entity.class);
        final Queue<Runnable> entityQueue = new ArrayDeque<>(), worldQueue = new ArrayDeque<>();
        final AtomicInteger reads = new AtomicInteger();
        final List<String> order = new ArrayList<>();
        final LootContext context = context(3);
        final org.mockito.MockedStatic<TickThread> ticks;
        final org.mockito.MockedStatic<ScarpetExplosionActors> worlds;
        Entity owner;

        Fixture() throws Exception {
            when(original.getServer()).thenReturn(server);
            when(foreign.getServer()).thenReturn(server);
            when(entity.level()).thenReturn(foreign);
            when(entity.blockPosition()).thenReturn(BlockPos.ZERO);
            when(entity.position()).thenReturn(Vec3.ZERO);
            when(context.getLevel()).thenReturn(original);
            when(context.getOptional(LootContextParams.ORIGIN)).thenReturn(new Vec3(19, 65, 35));
            when(context.getOptional(LootContextParams.THIS_ENTITY)).thenReturn(entity);
            var bukkit = mock(org.bukkit.craftbukkit.entity.CraftEntity.class);
            when(entity.getBukkitEntity()).thenReturn(bukkit);
            var scheduler = mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var field = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
            field.setAccessible(true);
            field.set(bukkit, scheduler);
            when(scheduler.schedule(any(), any(), anyLong())).thenAnswer(call -> {
                Consumer<Entity> task = call.getArgument(0);
                entityQueue.add(() -> {
                    owner = entity;
                    try {
                        task.accept(entity);
                    } finally {
                        owner = null;
                    }
                });
                return true;
            });
            ticks = mockStatic(TickThread.class);
            worlds = mockStatic(ScarpetExplosionActors.class, CALLS_REAL_METHODS);
            ticks.when(() -> TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call -> owner == call.getArgument(0));
            when(entity.getScoreboardName()).thenAnswer(call -> {
                assertSame(entity, owner);
                reads.incrementAndGet();
                order.add("foreign name");
                return "actual foreign";
            });
            worlds.when(() -> ScarpetExplosionActors.world(any(ServerLevel.class), any(BlockPos.class), any())).thenAnswer(call -> {
                assertSame(original, call.getArgument(0));
                assertEquals(new BlockPos(19, 65, 35), call.getArgument(1));
                Supplier<Object> operation = call.getArgument(2);
                var captured = ScarpetRuntime.captureNativeContinuation(operation);
                var future = new CompletableFuture<Object>();
                ScarpetNativeWork.record(future);
                worldQueue.add(() -> {
                    order.add("original world");
                    try {
                        future.complete(captured.get());
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                });
                return future;
            });
        }

        void entities() {
            Runnable task;
            while ((task = entityQueue.poll()) != null) task.run();
        }

        void world() {
            Runnable task;
            while ((task = worldQueue.poll()) != null) task.run();
        }

        @Override
        public void close() {
            worlds.close();
            ticks.close();
        }
    }

    @Test
    void actualScoreboardProviderReadsForeignEntityOwnerThenOriginalScoreboardAndKeepsCancelledGlobalActualUntilChildrenEnd() throws Exception {
        try (var fixture = new Fixture()) {
            var scoreboard = mock(net.minecraft.server.ServerScoreboard.class);
            when(fixture.original.getScoreboard()).thenReturn(scoreboard);
            var objective = mock(net.minecraft.world.scores.Objective.class);
            when(scoreboard.getObjective("score")).thenReturn(objective);
            var info = mock(net.minecraft.world.scores.ReadOnlyScoreInfo.class);
            when(info.value()).thenReturn(37);
            when(scoreboard.getPlayerScoreInfo(any(net.minecraft.world.scores.ScoreHolder.class), eq(objective))).thenAnswer(call -> {
                assertEquals("actual foreign", call.<net.minecraft.world.scores.ScoreHolder>getArgument(0).getScoreboardName());
                assertNotSame(fixture.entity, call.getArgument(0));
                return info;
            });
            var child = new CompletableFuture<Void>();
            doAnswer(call -> {
                assertSame(fixture.entity, fixture.owner);
                fixture.order.add("foreign name");
                ScarpetNativeWork.record(child);
                return "actual foreign";
            }).when(fixture.entity).getScoreboardName();
            var provider = new net.minecraft.world.level.storage.loot.providers.number.ints.ScoreboardValue(new net.minecraft.world.level.storage.loot.providers.score.ContextScoreboardNameProvider(LootContext.EntityTarget.THIS), "score", i(9));
            var caller = ScarpetLootNumbers.integer(provider, fixture.context);
            var idle = ScarpetNativeWork.whenIdle(fixture.server);
            assertFalse(idle.isDone());
            assertTrue(caller.cancel(false));
            fixture.entities();
            assertTrue(fixture.worldQueue.isEmpty());
            assertFalse(idle.isDone());
            child.complete(null);
            fixture.world();
            idle.join();
            assertTrue(caller.isCancelled());
            assertEquals(List.of("foreign name", "original world"), fixture.order);
            verify(fixture.foreign, never()).getScoreboard();
            fixture.order.clear();
            var actual = ScarpetLootNumbers.integer(provider, fixture.context);
            fixture.entities();
            fixture.world();
            assertEquals(37, actual.join());
        }
    }

    @Test
    void arithmeticFailureOfAnActualAsynchronousChildIsNotAZeroFallbackAndCannotBeHidden() throws Exception {
        try (var fixture = new Fixture()) {
            var child = new CompletableFuture<Void>();
            doAnswer(call -> {
                ScarpetNativeWork.record(child);
                return "actual foreign";
            }).when(fixture.entity).getScoreboardName();
            var provider = new net.minecraft.world.level.storage.loot.providers.number.ints.ScoreboardValue(new net.minecraft.world.level.storage.loot.providers.score.ContextScoreboardNameProvider(LootContext.EntityTarget.THIS), "score", i(9));
            var actual = ScarpetLootNumbers.integer(provider, fixture.context);
            fixture.entities();
            var failure = new ArithmeticException("actual native child failed");
            child.completeExceptionally(failure);
            assertSame(failure, assertThrows(CompletionException.class, actual::join).getCause());
            assertTrue(fixture.worldQueue.isEmpty());
            assertFalse(ScarpetNativeWork.onlyGuestFailure(failure));
        }
    }

    @Test
    void actualStorageValueUsesOriginalCommandStorageAndOnlyEvaluatesNativeFallbackWhenSelected() throws Exception {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(server);
        var context = context(12);
        when(context.getLevel()).thenReturn(world);
        var storage = mock(net.minecraft.world.level.storage.CommandStorage.class);
        when(server.getCommandStorage()).thenReturn(storage);
        var id = net.minecraft.resources.Identifier.fromNamespaceAndPath("test", "numbers");
        var data = new net.minecraft.nbt.CompoundTag();
        data.putInt("value", 37);
        when(storage.get(id)).thenReturn(data);
        var path = net.minecraft.commands.arguments.NbtPathArgument.nbtPath().parse(new com.mojang.brigadier.StringReader("value"));
        var access = new net.minecraft.world.level.storage.loot.providers.number.StoredNumberAccess(id, path);
        var condition = mock(LootItemCondition.class);
        when(condition.test(context)).thenReturn(true);
        var fallback = new net.minecraft.world.level.storage.loot.providers.number.ints.ConditionalValue(Holder.direct(condition), i(8), i(2));
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            var provider = new net.minecraft.world.level.storage.loot.providers.number.ints.StorageValue(access, Holder.direct(fallback));
            assertEquals(37, ScarpetLootNumbers.integer(provider, context).join());
            verifyNoInteractions(condition);
            var floatProvider = new net.minecraft.world.level.storage.loot.providers.number.floats.StorageValue(access, f(8));
            assertEquals(37F, ScarpetLootNumbers.floating(floatProvider, context).join());
            when(storage.get(id)).thenReturn(new net.minecraft.nbt.CompoundTag());
            assertEquals(8, ScarpetLootNumbers.integer(provider, context).join());
            verify(condition).test(context);
        }
    }

    @Test
    void actualPositionalEnvironmentReadsTheOriginalLoadedFootprintAndMissingOriginPreservesNativeDefault() throws Exception {
        var server = mock(MinecraftServer.class);
        var world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(server);
        var context = context(7);
        when(context.getLevel()).thenReturn(world);
        var origin = new Vec3(31, 68, 47);
        when(context.getOptional(LootContextParams.ORIGIN)).thenReturn(origin);
        var attribute = net.minecraft.world.attribute.EnvironmentAttribute.builder(net.minecraft.world.attribute.AttributeTypes.FLOAT).defaultValue(2.5F).build();
        var reader = mock(net.minecraft.world.attribute.EnvironmentAttributeSystem.class);
        when(world.environmentAttributes()).thenReturn(reader);
        var child = new CompletableFuture<Void>();
        when(reader.getValue(context, attribute)).thenAnswer(call -> {
            ScarpetNativeWork.record(child);
            return 9.75F;
        });
        var floatProvider = new net.minecraft.world.level.storage.loot.providers.number.floats.EnvironmentAttributeValue(attribute);
        var integerAttribute = net.minecraft.world.attribute.EnvironmentAttribute.builder(net.minecraft.world.attribute.AttributeTypes.INTEGER).defaultValue(2).build();
        when(reader.getValue(context, integerAttribute)).thenReturn(9);
        var intProvider = new net.minecraft.world.level.storage.loot.providers.number.ints.EnvironmentAttributeValue(integerAttribute);
        try (var leases = fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open(); var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(eq(world), any(BlockPos.class))).thenReturn(true);
            leases.when(() -> fun.bm.lophine.carpet.CarpetRegionLease.runLoadedValue(eq(world), eq(0), eq(1), eq(2), eq(3), any())).thenAnswer(call -> {
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>, Object> body = call.getArgument(5);
                var value = body.apply(null);
                return CompletableFuture.completedFuture(value);
            });
            var actual = ScarpetLootNumbers.floating(floatProvider, context);
            assertFalse(actual.isDone());
            var idle = ScarpetNativeWork.whenIdle(server);
            assertFalse(idle.isDone());
            child.complete(null);
            assertEquals(9.75F, actual.join());
            idle.join();
            assertEquals(9, ScarpetLootNumbers.integer(intProvider, context).join());
            leases.verify(() -> fun.bm.lophine.carpet.CarpetRegionLease.runValue(any(), anyInt(), anyInt(), anyInt(), anyInt(), any()), never());
            when(context.getOptional(LootContextParams.ORIGIN)).thenReturn(null);
            when(reader.getValue(context, attribute)).thenReturn(attribute.defaultValue());
            assertEquals(floatProvider.getFloat(context), ScarpetLootNumbers.floating(floatProvider, context).join());
        }
    }

    @Test
    void unboundedNativeRangeProviderOverloadsNeverEvaluateTheirInputAndPointChecksInputBeforeItsBound() throws Exception {
        var context = context(1);
        var intInput = mock(ContextIntProvider.class);
        var floatInput = mock(ContextFloatProvider.class);
        var intConstructor = IntRangePredicate.Line.class.getDeclaredConstructor(Optional.class, Optional.class);
        intConstructor.setAccessible(true);
        var noInts = (IntRangePredicate.Line) intConstructor.newInstance(Optional.empty(), Optional.empty());
        var floatConstructor = FloatRangePredicate.Line.class.getDeclaredConstructor(Optional.class, Optional.class);
        floatConstructor.setAccessible(true);
        var noFloats = (FloatRangePredicate.Line) floatConstructor.newInstance(Optional.empty(), Optional.empty());
        assertTrue(ScarpetLootNumbers.ranges(noInts, context, intInput).join());
        assertTrue(ScarpetLootNumbers.ranges(noFloats, context, floatInput).join());
        verifyNoInteractions(intInput, floatInput);
        var order = new ArrayList<String>();
        var inputCondition = mock(LootItemCondition.class);
        var boundCondition = mock(LootItemCondition.class);
        when(inputCondition.test(context)).thenAnswer(call -> {
            order.add("input");
            return true;
        });
        when(boundCondition.test(context)).thenAnswer(call -> {
            order.add("point bound");
            return true;
        });
        var input = new net.minecraft.world.level.storage.loot.providers.number.ints.ConditionalValue(Holder.direct(inputCondition), i(4), i(0));
        var bound = new net.minecraft.world.level.storage.loot.providers.number.ints.ConditionalValue(Holder.direct(boundCondition), i(4), i(0));
        assertTrue(ScarpetLootNumbers.ranges(new IntRangePredicate.Point(Holder.direct(bound)), context, input).join());
        assertEquals(List.of("input", "point bound"), order);
    }

    @Test
    void aRealVmGuestFailureAfterNativeRandomSamplingKeepsTheActualNumericValueAndItsOriginalFailedParentReceipt() throws Exception {
        var server = mock(MinecraftServer.class);
        var runtime = ScarpetRuntime.of(server);
        var context = context(23);
        var random = context.getRandom();
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var guest = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Integer>>();
        var numeric = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Integer>>();
        doAnswer(call -> {
            guest.set(runtime.submit(() -> {
                entered.countDown();
                try {
                    if (!finish.await(3, TimeUnit.SECONDS)) throw new AssertionError("Guest release timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                throw new IllegalStateException("actual guest numeric callback failed");
            }));
            return random;
        }).when(context).getRandom();
        var provider = new net.minecraft.world.level.storage.loot.providers.number.ints.UniformGenerator(i(4), i(4));
        var parent = ScarpetNativeWork.observeNative(null, () -> {
            numeric.set(ScarpetLootNumbers.integer(provider, context));
            return "physical parent";
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(parent.isDone());
            assertFalse(numeric.get().isDone());
        } finally {
            finish.countDown();
        }
        assertEquals(4, numeric.get().get(3, TimeUnit.SECONDS));
        var original = assertThrows(ExecutionException.class, () -> parent.get(3, TimeUnit.SECONDS));
        assertTrue(ScarpetNativeWork.onlyGuestFailure(original.getCause()));
        assertThrows(CompletionException.class, () -> guest.get().join());
    }

}
