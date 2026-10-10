package carpet.script;

import carpet.script.value.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the actual upstream parser, UDF runtime, optimizer, collections and module imports.
 */
public class ScarpetEngineTest {
    private static final ScriptServer FILES = new ScriptServer() {
        @Override
        public Path resolveResource(String path) {
            return Path.of(path);
        }
    };

    private static final class Host extends ScriptHost {
        private final Map<String, Module> libraries;

        private Host(Map<String, Module> libraries) {
            super(null, FILES, false, null, Expression.LoadOverride.DEFAULT);
            this.libraries = libraries;
        }

        @Override
        protected Module getModuleOrLibraryByName(String name) {
            return this.libraries.get(name);
        }

        @Override
        protected void runModuleCode(Context context, Module module) {
            Expression expression = new Expression(module.code());
            expression.asAModule(module);
            expression.executeAndEvaluate(context, true, Expression.LoadOverride.DEFAULT, null);
        }

        @Override
        protected ScriptHost duplicate() {
            return new Host(this.libraries);
        }
    }

    private static Value eval(Host host, String source, boolean optimize) {
        Context context = new Context(host);
        context.initialize();
        return new Expression(source).executeAndEvaluate(context, optimize, Expression.LoadOverride.DEFAULT, null).getLeft();
    }

    @Test
    void evaluatesRecursiveFunctionsAndPreservesLazyBranchesWithBothOptimizerModes() {
        for (boolean optimized : new boolean[]{false, true}) {
            Host host = new Host(Map.of());
            assertEquals(3_628_800L, eval(host, "compat_factorial(n) -> if(n <= 1, 1, n * compat_factorial(n - 1)); compat_factorial(10)", optimized).readInteger());
            assertEquals(42L, eval(host, "if(false, throw('unreachable'), 42)", optimized).readInteger());
            assertEquals(512L, eval(host, "2 ^ 3 ^ 2", optimized).readInteger());
        }
    }

    @Test
    void evaluatesCollectionsAndIterationInTheRealRuntime() {
        for (boolean optimized : new boolean[]{false, true}) {
            Host host = new Host(Map.of());
            assertEquals("[0, 1, 4, 9, 16, 25]", eval(host, "map(range(6), _ * _)", optimized).getString());
            assertEquals(15L, eval(host, "reduce(range(6), _a + _, 0)", optimized).readInteger());
            assertEquals(9L, eval(host, "data = {'one' -> 1, 'two' -> 9}; get(data, 'two')", optimized).readInteger());
        }
    }

    @Test
    void importsAnActualModuleAndResolvesItsFunction() {
        Module library = new Module("mathlib", "square(n) -> n * n", true);
        Host host = new Host(Map.of("mathlib", library));
        assertEquals(49L, eval(host, "import('mathlib', 'square'); square(7)", true).readInteger());
    }

    @Test
    void executesARealQueuedCancellationCallbackWhileItsCallerAwaitsTheDecision() throws Exception {
        net.minecraft.server.MinecraftServer server = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
        var runtime = carpet.script.external.ScarpetRuntime.of(server);
        try {
            Value result = runtime.submit(() -> {
                Host host = new Host(Map.of());
                eval(host, "global_callback_answer = 0; compat_callback() -> (global_callback_answer = 42; 'cancel')", true);
                var decision = carpet.script.external.ScarpetRuntime.captureEvent(() ->
                        carpet.script.external.ScarpetRuntime.enqueueCallback(server, () -> eval(host, "compat_callback()", true).getString().equals("cancel")));
                assertTrue(carpet.script.external.ScarpetRuntime.await(decision));
                return eval(host, "global_callback_answer", true);
            }).get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(42L, result.readInteger());
        } finally {
            carpet.script.external.ScarpetRuntime.beginShutdown(server, () -> {
            });
        }
    }

    @Test
    void detachesNestedActorArgumentsWhileARealCallbackMutatesGuestState() throws Exception {
        net.minecraft.server.MinecraftServer server = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
        var runtime = carpet.script.external.ScarpetRuntime.of(server);
        try {
            Value original = runtime.submit(() -> {
                Host host = new Host(Map.of());
                Value data = eval(host, "global_callback_data = [{'nested' -> [1, 2]}]; global_callback_data", true);
                var captured = carpet.script.external.ActorFunctions.snapshotArguments(java.util.List.of(data));
                var decision = carpet.script.external.ScarpetRuntime.captureEvent(() ->
                        carpet.script.external.ScarpetRuntime.enqueueCallback(server, () -> {
                            eval(host, "put(get(get(global_callback_data, 0), 'nested'), 0, 99)", true);
                            return true;
                        }));
                assertTrue(carpet.script.external.ScarpetRuntime.await(decision));
                assertEquals("[{nested: [1, 2]}]", captured.getFirst().getString());
                return eval(host, "get(get(get(global_callback_data, 0), 'nested'), 0)", true);
            }).get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(99L, original.readInteger());
        } finally {
            carpet.script.external.ScarpetRuntime.beginShutdown(server, () -> {
            });
        }
    }

    @Test
    void refusesACommitAfterARealCallbackClosesItsSuspendedHost() throws Exception {
        net.minecraft.server.MinecraftServer server = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
        var runtime = carpet.script.external.ScarpetRuntime.of(server);
        java.util.concurrent.atomic.AtomicBoolean committed = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            runtime.submit(() -> {
                Host host = new Host(Map.of());
                Context context = new Context(host);
                context.initialize();
                Expression expression = new Expression("compat_suspend()");
                expression.addContextFunction("compat_suspend", 0, (guestContext, type, arguments) -> {
                    var closed = runtime.submit(() -> {
                        host.onClose();
                        return Value.TRUE;
                    });
                    carpet.script.external.ScarpetRuntime.await(closed);
                    committed.set(true);
                    return Value.TRUE;
                });
                assertThrows(carpet.script.exception.ExpressionException.class,
                        () -> expression.executeAndEvaluate(context, true, Expression.LoadOverride.DEFAULT, null));
                return null;
            }).get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(committed.get());
        } finally {
            carpet.script.external.ScarpetRuntime.beginShutdown(server, () -> {
            });
        }
    }

    private static CarpetContext taskContext(Host host, net.minecraft.server.MinecraftServer server) {
        var source = org.mockito.Mockito.mock(net.minecraft.commands.CommandSourceStack.class);
        org.mockito.Mockito.when(source.getServer()).thenReturn(server);
        CarpetContext context = new CarpetContext(host, source, net.minecraft.core.BlockPos.ZERO);
        context.initialize();
        return context;
    }

    @Test
    void joinsARealTaskThatDocksBackIntoTheWaitingInterpreter() throws Exception {
        var server = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
        var runtime = carpet.script.external.ScarpetRuntime.of(server);
        Host host = new Host(Map.of());
        try {
            Value result = runtime.submit(() -> {
                CarpetContext context = taskContext(host, server);
                Expression expression = new Expression("compat_docked() -> task_dock(42); task_join(task('compat_docked'))");
                carpet.script.api.Threading.apply(expression);
                return expression.executeAndEvaluate(context, true, Expression.LoadOverride.DEFAULT, null).getLeft();
            }).get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(42L, result.readInteger());
        } finally {
            host.onClose();
            carpet.script.external.ScarpetRuntime.beginShutdown(server, () -> {
            });
        }
    }

    @Test
    void performsRealCoroutineYieldAndReplyWhileTheInterpreterWaits() throws Exception {
        var server = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
        var runtime = carpet.script.external.ScarpetRuntime.of(server);
        Host host = new Host(Map.of());
        try {
            Value result = runtime.submit(() -> {
                CarpetContext context = taskContext(host, server);
                Expression expression = new Expression("compat_ping() -> yield(task_dock(7), true); pending = task('compat_ping'); first = task_await(pending); task_send(pending, 42); [first, task_join(pending)]");
                carpet.script.api.Threading.apply(expression);
                return expression.executeAndEvaluate(context, true, Expression.LoadOverride.DEFAULT, null).getLeft();
            }).get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("[7, 42]", result.getString());
        } finally {
            host.onClose();
            carpet.script.external.ScarpetRuntime.beginShutdown(server, () -> {
            });
        }
    }

    @Test
    void serverShutdownInterruptsAnActualBusyTaskOutsideTheInterpreterExecutor() throws Exception {
        var server = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
        var runtime = carpet.script.external.ScarpetRuntime.of(server);
        Host host = new Host(Map.of());
        var started = new java.util.concurrent.CountDownLatch(1);
        try {
            var task = (carpet.script.value.ThreadValue) runtime.submit(() -> {
                CarpetContext context = taskContext(host, server);
                Expression expression = new Expression("compat_busy() -> (compat_started(); while(true, 1)); task('compat_busy')");
                expression.addContextFunction("compat_started", 0, (guest, type, arguments) -> {
                    started.countDown();
                    return Value.NULL;
                });
                carpet.script.api.Threading.apply(expression);
                return expression.executeAndEvaluate(context, true, Expression.LoadOverride.DEFAULT, null).getLeft();
            }).get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            carpet.script.external.ScarpetRuntime.beginShutdown(server, () -> {
            });
            assertEquals(Value.NULL, task.completionFuture().get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(task.isFinished());
        } finally {
            host.onClose();
            carpet.script.external.ScarpetRuntime.beginShutdown(server, () -> {
            });
        }
    }

    @Test
    void anAlreadyClosedHostCannotCreateANewEpochToCommitFreshCode() {
        Host host = new Host(Map.of());
        host.onClose();
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        Context context = new Context(host);
        context.initialize();
        Expression expression = new Expression("compat_late_commit()");
        expression.addContextFunction("compat_late_commit", 0, (guest, type, arguments) -> {
            committed.set(true);
            return Value.TRUE;
        });
        assertThrows(carpet.script.exception.InternalExpressionException.class,
                () -> expression.executeAndEvaluate(context, true, Expression.LoadOverride.DEFAULT, null));
        assertFalse(committed.get());
    }

    @Test
    void closingHostExecutesItsFreshCloseCallbackButCannotReuseASuspendedContext() {
        Host host = new Host(Map.of());
        Context suspended = new Context(host);
        suspended.initialize();
        host.onClose();
        try (var detached = carpet.script.external.ScarpetRuntime.closingContext(); var closing = carpet.script.external.ScarpetRuntime.closingHost(host)) {
            assertEquals(7L, eval(host, "compat_close_callback() -> 7; compat_close_callback()", true).readInteger());
            assertThrows(carpet.script.exception.InternalExpressionException.class,
                    () -> new Expression("42").executeAndEvaluate(suspended, true, Expression.LoadOverride.DEFAULT, null));
        }
        assertThrows(carpet.script.exception.InternalExpressionException.class, () -> eval(host, "42", true));
    }

    @Test
    void seededRandomCacheSupportsConcurrentTasksAndPreservesResetSequences() throws Exception {
        Host host = new Host(Map.of());
        long commonSeed = Long.MIN_VALUE + 53;
        host.resetRandom(commonSeed);
        var expected = host.getRandom(commonSeed);
        try (var tasks = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                int offset = worker * 1_024;
                jobs.add(tasks.submit(() -> {
                    start.await();
                    for (int index = 0; index < 1_024; index++) {
                        long seed = Long.MIN_VALUE + 10_000 + offset + index;
                        host.resetRandom(seed);
                        var random = host.getRandom(seed);
                        assertSame(expected, host.getRandom(commonSeed));
                        assertSame(random, host.getRandom(seed));
                        assertEquals(new java.util.Random(seed).nextLong(), random.nextLong());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertTrue(host.resetRandom(commonSeed));
        assertNotSame(expected, host.getRandom(commonSeed));
        assertEquals(new java.util.Random(commonSeed).nextLong(), host.getRandom(commonSeed).nextLong());
    }

    @Test
    void noiseCachesPreserveSeededSamplesUnderParallelInsertionAndEviction() throws Exception {
        try (var tasks = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                int offset = worker * 256;
                jobs.add(tasks.submit(() -> {
                    start.await();
                    for (int index = 0; index < 256; index++) {
                        long seed = Long.MIN_VALUE + offset + index;
                        var perlin = carpet.script.utils.PerlinNoiseSampler.getPerlin(seed);
                        var simplex = carpet.script.utils.SimplexNoiseSampler.getSimplex(seed);
                        assertEquals(new carpet.script.utils.PerlinNoiseSampler(new java.util.Random(seed)).sample3d(1.25, 2.5, -3.75), perlin.sample3d(1.25, 2.5, -3.75));
                        assertEquals(new carpet.script.utils.SimplexNoiseSampler(new java.util.Random(seed)).sample3d(1.25, 2.5, -3.75), simplex.sample3d(1.25, 2.5, -3.75));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void realParallelTasksRetainIndependentGlobalAssignmentsAndFunctionDefinitions() throws Exception {
        for (boolean optimized : new boolean[]{false, true}) {
            var server = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
            var runtime = carpet.script.external.ScarpetRuntime.of(server);
            Host host = new Host(Map.of());
            var entered = new java.util.concurrent.CountDownLatch(8);
            try {
                runtime.submit(() -> {
                    CarpetContext context = taskContext(host, server);
                    Expression expression = new Expression("compat_parallel_writer(offset) -> (compat_parallel_start(); loop(128, (var('global_parallel_' + (offset + _)) = offset + _; call('compat_parallel_function_' + (offset + _)) -> 42))); jobs = map(range(8), task('compat_parallel_writer', _ * 128)); map(jobs, task_join(_))");
                    expression.addContextFunction("compat_parallel_start", 0, (guest, type, args) -> {
                        entered.countDown();
                        try {
                            if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("Parallel script tasks did not enter together");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                        return Value.NULL;
                    });
                    carpet.script.api.Threading.apply(expression);
                    expression.executeAndEvaluate(context, optimized, Expression.LoadOverride.DEFAULT, null);
                    for (int index = 0; index < 1_024; index++) {
                        assertEquals(index, host.getGlobalVariable("global_parallel_" + index).evalValue(context).readInteger());
                        assertNotNull(host.getFunction("compat_parallel_function_" + index));
                    }
                    assertEquals(42L, eval(host, "compat_parallel_function_1023()", optimized).readInteger());
                    return null;
                }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                host.onClose();
                carpet.script.external.ScarpetRuntime.beginShutdown(server, () -> {
                });
            }
        }
    }
}
