package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import carpet.script.CarpetEventServer;
import carpet.script.CarpetScriptServer;
import carpet.script.exception.InternalExpressionException;
import carpet.script.value.Value;
import com.mojang.brigadier.Command;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Owns the complete upstream interpreter; only script threads wait for actor results.
 */
public final class ScarpetRuntime {
    private static final Map<MinecraftServer, ScarpetRuntime> SERVERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<ScarpetRuntime> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ScarpetRuntime> SHUTDOWN_SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<GuestFrame> CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<Entity> DAMAGE_CALLBACK = new ThreadLocal<>();

    private record NativeDecision(Entity owner, Object key) {
    }

    private static final ThreadLocal<NativeDecision> NATIVE_DECISION = new ThreadLocal<>();

    public static boolean currentNativeDecision(Entity owner, Object key) {
        NativeDecision current = NATIVE_DECISION.get();
        return current != null && current.owner() == owner && java.util.Objects.equals(current.key(), key);
    }

    public static CompletableFuture<Void> nativeDecisionFuture(Entity owner, Object key) {
        Map<Object, CompletableFuture<Void>> pending = of(MinecraftServer.getServer()).pendingDecisions.get(owner);
        return pending == null ? null : pending.get(key);
    }

    public static final class DamageCallbackScope implements AutoCloseable {
        private final Entity previous;

        private DamageCallbackScope(Entity target) {
            previous = DAMAGE_CALLBACK.get();
            if (target != null) DAMAGE_CALLBACK.set(target);
        }

        @Override
        public void close() {
            if (previous == null) DAMAGE_CALLBACK.remove();
            else DAMAGE_CALLBACK.set(previous);
        }
    }

    public static DamageCallbackScope damageCallback(Entity target) {
        return new DamageCallbackScope(target);
    }

    public static boolean isDamageCallbackFor(Entity target) {
        return DAMAGE_CALLBACK.get() == target;
    }

    private static final ThreadLocal<carpet.script.ScriptHost> CLOSING_HOST = new ThreadLocal<>();

    public static final class ClosingHostScope implements AutoCloseable {
        private final carpet.script.ScriptHost previous;

        private ClosingHostScope(carpet.script.ScriptHost host) {
            previous = CLOSING_HOST.get();
            CLOSING_HOST.set(host);
        }

        @Override
        public void close() {
            if (previous == null) CLOSING_HOST.remove();
            else CLOSING_HOST.set(previous);
        }
    }

    public static ClosingHostScope closingHost(carpet.script.ScriptHost host) {
        return new ClosingHostScope(host);
    }

    public static boolean canRunClosingHost(carpet.script.ScriptHost host) {
        return CLOSING_HOST.get() == host;
    }

    private static boolean currentShutdown(carpet.script.ScriptHost host) {
        ScarpetRuntime runtime = CURRENT.get();
        GuestFrame inherited = CONTEXT.get();
        return runtime != null && runtime.shutdownCallbacks && (SHUTDOWN_SCOPE.get() == runtime || inherited != null && inherited.shutdown)
                && host.scriptServer() instanceof CarpetScriptServer scripts && scripts.server == runtime.server;
    }

    private static GuestFrame currentFrame() {
        GuestFrame frame = CONTEXT.get();
        if (frame != null) return frame;
        carpet.script.ScriptHost host = CLOSING_HOST.get();
        return host == null ? null : new GuestFrame(host, host.executionEpoch(), true, currentShutdown(host));
    }

    private boolean allowsClosingActors() {
        if (!this.shutdownCallbacks) return false;
        if (SHUTDOWN_SCOPE.get() == this && this.isInterpreterThread()) return true;
        GuestFrame frame = currentFrame();
        if (frame == null || !frame.shutdown || !(frame.host.scriptServer() instanceof CarpetScriptServer scripts) || scripts.server != this.server)
            return false;
        frame.validate();
        return true;
    }

    private record GuestFrame(carpet.script.ScriptHost host, long epoch, boolean closing, boolean shutdown) {
        void validate() {
            if (host.executionEpoch() != epoch || host.isTerminating() && !closing) {
                var failure = new InternalExpressionException("Scarpet app closed while its execution was suspended");
                ScarpetNativeWork.markGuestFailure(failure);
                throw failure;
            }
        }
    }

    public static final class ContextScope implements AutoCloseable {
        private final GuestFrame previous;

        private ContextScope(GuestFrame next) {
            this.previous = CONTEXT.get();
            if (next == null) CONTEXT.remove();
            else CONTEXT.set(next);
        }

        @Override
        public void close() {
            if (previous == null) CONTEXT.remove();
            else CONTEXT.set(previous);
        }
    }

    public static ContextScope enterContext(carpet.script.Context context) {
        GuestFrame previous = CONTEXT.get();
        if (previous != null) previous.validate();
        GuestFrame next = new GuestFrame(context.host, context.executionEpoch(), canRunClosingHost(context.host), currentShutdown(context.host));
        next.validate();
        return new ContextScope(next);
    }

    /**
     * __on_close has its own fresh frame; the caller's suspended epoch remains invalid.
     */
    public static ContextScope closingContext() {
        return new ContextScope(null);
    }

    public static ContextScope enterCapturedContext(carpet.script.Context context, long epoch) {
        GuestFrame frame = new GuestFrame(context.host, epoch, canRunClosingHost(context.host), currentShutdown(context.host));
        frame.validate();
        return new ContextScope(frame);
    }

    public static final class TaskScope implements AutoCloseable {
        private final ScarpetRuntime runtime;
        private final Thread thread = Thread.currentThread();
        private final ContextScope scope;

        private TaskScope(carpet.script.Context context, long epoch, ScarpetRuntime capturedRuntime) {
            runtime = capturedRuntime;
            if (runtime != null && runtime.closing) throw new carpet.script.exception.ExitStatement(Value.NULL);
            scope = enterCapturedContext(context, epoch);
            if (runtime != null) {
                runtime.guestThreads.add(thread);
                if (runtime.closing) {
                    runtime.guestThreads.remove(thread);
                    scope.close();
                    throw new carpet.script.exception.ExitStatement(Value.NULL);
                }
            }
        }

        @Override
        public void close() {
            scope.close();
            if (runtime != null) runtime.guestThreads.remove(thread);
        }
    }

    public static ScarpetRuntime taskRuntime(carpet.script.Context context) {
        return context instanceof carpet.script.CarpetContext carpetContext ? of(carpetContext.server()) : CURRENT.get();
    }

    public static TaskScope taskScope(carpet.script.Context context, long epoch, ScarpetRuntime runtime) {
        return new TaskScope(context, epoch, runtime);
    }

    private static final ThreadLocal<CompletableFuture<Boolean>> EVENT_CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<java.util.Set<Object>> REPLAYING = ThreadLocal.withInitial(java.util.HashSet::new);
    private final Set<Thread> guestThreads = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.locks.ReentrantLock interpreterLock = new java.util.concurrent.locks.ReentrantLock(true);
    private final CompletableFuture<CarpetScriptServer> scriptsReady = new CompletableFuture<>();
    private final java.util.concurrent.atomic.AtomicBoolean creatingScripts = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean shutdownCallbacks;
    private volatile boolean nativeShutdownReady;
    private final java.util.concurrent.atomic.AtomicBoolean shutdownStarted = new java.util.concurrent.atomic.AtomicBoolean();
    public static final ThreadLocal<Boolean> EVENT_DISABLED = ThreadLocal.withInitial(() -> false);
    public static final ThreadLocal<Boolean> FILL_SKIP_UPDATES = ThreadLocal.withInitial(() -> false);
    public static final ThreadLocal<Boolean> SKIP_GENERATION_CHECKS = ThreadLocal.withInitial(() -> false);
    public static final Map<String, Component> HEADERS = new ConcurrentHashMap<>();
    public static final Map<String, Component> FOOTERS = new ConcurrentHashMap<>();
    public static final Map<String, LongAdder> PROFILE_NANOS = new ConcurrentHashMap<>();
    public static final Map<String, LongAdder> PROFILE_CALLS = new ConcurrentHashMap<>();
    private static final WeakIdentityMap<Entity, Boolean> SELECTOR_PERMISSION_SNAPSHOTS = new WeakIdentityMap<>();

    private record SourceData(java.lang.ref.WeakReference<net.minecraft.commands.CommandSource> output,
                              ServerLevel world,
                              net.minecraft.world.phys.Vec3 position, net.minecraft.world.phys.Vec2 rotation,
                              PermissionSet permissions,
                              String textName, Component displayName, boolean bypassSelectors) {
        CommandSourceStack restore(Entity entity) {
            var names = new CommandSourceStack.NamesProvider() {
                @Override
                public String textName(Entity ignored) {
                    return SourceData.this.textName;
                }

                @Override
                public Component displayName(Entity ignored) {
                    return SourceData.this.displayName.copy();
                }
            };
            var actualOutput = output.get();
            return CommandSourceStack.carpetSnapshotSource(actualOutput == null ? net.minecraft.commands.CommandSource.NULL : actualOutput,
                    position, rotation, world, permissions, names, world.getServer(), entity, bypassSelectors);
        }
    }

    private static final WeakIdentityMap<Entity, SourceData> SOURCE_SNAPSHOTS = new WeakIdentityMap<>();

    private final MinecraftServer server;
    private final ExecutorService interpreter = Executors.newCachedThreadPool(Thread.ofVirtual().name("Scarpet interpreter-", 0).factory());
    private final Set<CompletableFuture<?>> pendingActors = ConcurrentHashMap.newKeySet();
    private final WeakIdentityMap<Entity, Map<Object, CompletableFuture<Void>>> pendingDecisions = new WeakIdentityMap<>();
    private final AtomicLong ticks = new AtomicLong();
    private final Map<Long, Set<CompletableFuture<Void>>> tickWaiters = new ConcurrentHashMap<>();
    private volatile CarpetScriptServer scripts;
    private volatile boolean closing;
    private final java.util.concurrent.atomic.AtomicBoolean initialized = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile PermissionSet runPermission = LevelBasedPermissionSet.GAMEMASTER;

    private ScarpetRuntime(MinecraftServer server) {
        this.server = server;
    }

    public static ScarpetRuntime of(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, ScarpetRuntime::new);
    }

    public static void initialize(MinecraftServer server) {
        ScarpetRuntime runtime = of(server);
        if (!runtime.initialized.compareAndSet(false, true)) return;
        runtime.submit(() -> {
            Carpet.ruleChanged(server.createCommandSourceStack(), "scriptsAppStore", fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.scriptsAppStore);
            Carpet.ruleChanged(server.createCommandSourceStack(), "commandScriptACE", fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandScriptACE);
            runtime.scriptServer().initializeForWorld();
            return null;
        });
    }

    public CarpetScriptServer scriptServer() {
        CarpetScriptServer result = this.scripts;
        if (result != null) return result;
        if (this.creatingScripts.compareAndSet(false, true)) {
            try {
                this.scripts = new CarpetScriptServer(this.server);
                this.scriptsReady.complete(this.scripts);
            } catch (Throwable failure) {
                this.scriptsReady.completeExceptionally(failure);
                throw failure;
            }
        }
        return await(this.scriptsReady);
    }

    public void setScriptServer(CarpetScriptServer scripts) {
        this.scripts = scripts;
        this.scriptsReady.complete(scripts);
    }

    public PermissionSet runPermission() {
        return this.runPermission;
    }

    public void updateRunPermission(CommandSourceStack source) {
        this.runPermission = source == null ? LevelBasedPermissionSet.GAMEMASTER : source.permissions();
    }

    public <T> CompletableFuture<T> submit(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        GuestFrame submittedFrame = currentFrame();
        boolean closingSubmission = this.closing && this.allowsClosingActors();
        if (this.closing && !closingSubmission) {
            var failure = new IllegalStateException("Scarpet server is closing");
            ScarpetNativeWork.markGuestFailure(failure);
            result.completeExceptionally(failure);
            return result;
        }
        boolean submittedDisabled = EVENT_DISABLED.get();
        Entity submittedDamage = DAMAGE_CALLBACK.get();
        NativeDecision submittedDecision = NATIVE_DECISION.get();
        var submittedWork = ScarpetNativeWork.capture();
        var submittedAttribution = ScarpetAttribution.capture();
        var submittedAccepted = ScarpetPlayerInventoryGate.captureAccepted();
        var submittedRules = fun.bm.lophine.carpet.OrgGameplayHelper.captureNativeRuleScopes();
        ScarpetNativeWork.recordGuest(result);
        try {
            this.interpreter.execute(() -> {
                Thread thread = Thread.currentThread();
                this.guestThreads.add(thread);
                try {
                    this.interpreterLock.lockInterruptibly();
                    try {
                        if (this.closing && !closingSubmission) {
                            var failure = new IllegalStateException("Scarpet server is closing");
                            ScarpetNativeWork.markGuestFailure(failure);
                            result.completeExceptionally(failure);
                            return;
                        }
                        ScarpetRuntime old = CURRENT.get();
                        boolean oldDisabled = EVENT_DISABLED.get();
                        Entity oldDamage = DAMAGE_CALLBACK.get();
                        NativeDecision oldDecision = NATIVE_DECISION.get();
                        if (submittedDecision == null) NATIVE_DECISION.remove();
                        else NATIVE_DECISION.set(submittedDecision);
                        EVENT_DISABLED.set(submittedDisabled);
                        if (submittedDamage == null) DAMAGE_CALLBACK.remove();
                        else DAMAGE_CALLBACK.set(submittedDamage);
                        CURRENT.set(this);
                        try (var guestFrame = new ContextScope(submittedFrame); var closingScope = submittedFrame != null && submittedFrame.closing ? closingHost(submittedFrame.host) : new ClosingHostScope(null); var accepted = ScarpetPlayerInventoryGate.inheritAccepted(submittedAccepted)) {
                            if (submittedFrame != null) submittedFrame.validate();
                            result.complete(ScarpetNativeWork.with(submittedWork, () -> {
                                try {
                                    return submittedRules.call(() -> ScarpetAttribution.with(submittedAttribution, action));
                                } catch (Throwable failure) {
                                    ScarpetNativeWork.markGuestFailure(failure);
                                    throw failure;
                                }
                            }));
                        } catch (Throwable failure) {
                            ScarpetNativeWork.markGuestFailure(failure);
                            result.completeExceptionally(failure);
                        } finally {
                            if (old == null) CURRENT.remove();
                            else CURRENT.set(old);
                            EVENT_DISABLED.set(oldDisabled);
                            if (oldDamage == null) DAMAGE_CALLBACK.remove();
                            else DAMAGE_CALLBACK.set(oldDamage);
                            if (oldDecision == null) NATIVE_DECISION.remove();
                            else NATIVE_DECISION.set(oldDecision);
                        }
                    } finally {
                        this.interpreterLock.unlock();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    ScarpetNativeWork.markGuestFailure(interrupted);
                    result.completeExceptionally(interrupted);
                } finally {
                    this.guestThreads.remove(thread);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            ScarpetNativeWork.markGuestFailure(stopped);
            result.completeExceptionally(stopped);
        }
        return result;
    }

    public boolean isInterpreterThread() {
        return CURRENT.get() == this;
    }

    public static CommandSourceStack entitySource(Entity entity) {
        return ScarpetRetiredActors.access(entity, () -> ownedEntitySource(entity));
    }

    private static CommandSourceStack ownedEntitySource(Entity entity) {
        CommandSourceStack captured = entity instanceof ServerPlayer player ? player.createCommandSourceStack() : entity.createCommandSourceStackForNameResolution((ServerLevel) entity.level());
        captureSourcePermission(captured);
        return captured;
    }

    /**
     * A remote event source is captured by that entity's owner, without any tick waiting.
     */
    public static CompletableFuture<CommandSourceStack> entitySourceFuture(Entity entity) {
        return ScarpetRetiredActors.accessFuture(entity, () -> ownedEntitySource(entity)).handle((source, failure) -> {
            if (failure == null) return source;
            Throwable cause = failure;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
                cause = cause.getCause();
            if (cause instanceof InternalExpressionException && ("Entity retired before scarpet operation".equals(cause.getMessage()) || "Entity scheduler retired".equals(cause.getMessage()))) {
                SourceData captured = SOURCE_SNAPSHOTS.get(entity);
                if (captured != null) return captured.restore(entity);
            }
            throw new java.util.concurrent.CompletionException(cause);
        });
    }

    public static CompletableFuture<Boolean> ownerEventDecision(CarpetEventServer.CallbackList callbacks, List<Value> arguments, Entity owner) {
        return ownerEventDecision(callbacks, arguments, owner, null);
    }

    public static CompletableFuture<Boolean> ownerEventDecision(CarpetEventServer.CallbackList callbacks, List<Value> arguments, Entity owner, Entity damageTarget) {
        List<Value> captured = ActorFunctions.snapshotArguments(arguments);
        return entitySourceFuture(owner).thenCompose(source -> of(source.getServer()).submit(() -> {
            try (var phase = fun.bm.lophine.carpet.TisMicroTiming.phase(source.getLevel(), "scarpet", "owner_event"); var damageScope = damageCallback(damageTarget)) {
                return callbacks.call(() -> captured, () -> source);
            }
        }));
    }

    private static void captureSourcePermission(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (entity != null && TickThread.isTickThreadFor(entity)) {
            SOURCE_SNAPSHOTS.put(entity, new SourceData(new java.lang.ref.WeakReference<>(source.source), source.getLevel(), source.getPosition(), source.getRotation(),
                    source.permissions(), source.getTextName(), source.getDisplayName().copy(), source.bypassSelectorPermissions));
            // Scarpet's entity_selector always raises the vanilla permission set to OWNER upstream.
            CommandSourceStack privileged = source.withMaximumPermission(LevelBasedPermissionSet.OWNER);
            boolean permitted = privileged.bypassSelectorPermissions || privileged.hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ENTITY_SELECTORS, "minecraft.command.selector");
            SELECTOR_PERMISSION_SNAPSHOTS.put(entity, permitted);
        }
    }

    /**
     * Immutable fallback only for a captured source whose entity scheduler has retired.
     */
    public static void checkCapturedSelectorPermissions(CommandSourceStack source, net.minecraft.commands.arguments.selector.EntitySelector selector) {
        if (!selector.usesSelector()) return;
        Entity entity = source.getEntity();
        Boolean permitted = entity == null ? null : SELECTOR_PERMISSION_SNAPSHOTS.get(entity);
        if (permitted == null)
            throw new InternalExpressionException("No captured selector permission for this retired source");
        if (!permitted)
            throw new InternalExpressionException("Entity selector permissions rejected for this captured source");
    }

    /**
     * The source actor captures event arguments; the VM evaluates cancellable callbacks.
     */
    public static CompletableFuture<Boolean> captureEvent(Supplier<Boolean> event) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        CompletableFuture<Boolean> previous = EVENT_CAPTURE.get();
        EVENT_CAPTURE.set(result);
        try {
            boolean immediate = event.get();
            if (!result.isDone() && EVENT_CAPTURE.get() == result) result.complete(immediate);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        } finally {
            if (previous == null) EVENT_CAPTURE.remove();
            else EVENT_CAPTURE.set(previous);
        }
        return result;
    }

    public static boolean enqueueEvent(CarpetEventServer.CallbackList callbacks, List<Value> args, CommandSourceStack source) {
        captureSourcePermission(source);
        CompletableFuture<Boolean> captured = EVENT_CAPTURE.get();
        if (captured != null) EVENT_CAPTURE.remove();
        of(source.getServer()).submit(() -> {
            try (var phase = fun.bm.lophine.carpet.TisMicroTiming.phase(source.getLevel(), "scarpet", "event")) {
                return callbacks.call(() -> args, () -> source);
            }
        }).whenComplete((cancelled, failure) -> {
            if (captured == null) {
                if (failure != null) CarpetScriptServer.LOG.error("Scarpet event failed", failure);
            } else if (failure == null) captured.complete(cancelled);
            else captured.completeExceptionally(failure);
        });
        return false;
    }

    public static Command<CommandSourceStack> command(Command<CommandSourceStack> command) {
        return context -> {
            CommandSourceStack source = context.getSource();
            captureSourcePermission(source);
            ScarpetRuntime runtime = of(source.getServer());
            if (runtime.isInterpreterThread()) return command.run(context);
            var completion = fun.bm.lophine.carpet.CarpetAsyncCommandResults.defer(source);
            runtime.submit(() -> {
                try {
                    return command.run(context);
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                    throw new InternalExpressionException(failure.getMessage());
                }
            }).whenComplete((result, failure) -> {
                if (failure != null) {
                    send(source, Component.literal("Scarpet: " + failure.getMessage()), true);
                    completion.complete(false, 0);
                } else completion.complete(true, result);
            });
            return 1;
        };
    }

    public static <T> T atEntity(Entity entity, Supplier<T> operation) {
        if (TickThread.isTickThreadFor(entity)) return operation.get();
        return await(atEntityFuture(entity, operation));
    }

    public static <T> CompletableFuture<T> atEntityFuture(Entity entity, Supplier<T> operation) {
        if (TickThread.isTickThreadFor(entity)) {
            CompletableFuture<T> completed = new CompletableFuture<>();
            complete(completed, operation);
            return completed;
        }
        ScarpetRuntime runtime = of(MinecraftServer.getServer());
        CompletableFuture<T> future = runtime.actorFuture();
        if (future.isDone()) return future;
        Supplier<T> inherited = inheritFlags(operation);
        if (ScarpetNativeRemovals.isPending(entity)) {
            ScarpetNativeRemovals.onOwnerFuture(entity, () -> future.isDone() ? null : inherited.get())
                    .whenComplete((value, failure) -> {
                        if (failure == null) future.complete(value);
                        else future.completeExceptionally(failure);
                    });
            return future;
        }
        boolean scheduled = entity.getBukkitEntity().taskScheduler.schedule(owned -> {
            if (owned != entity || entity.isRemoved())
                future.completeExceptionally(new InternalExpressionException("Entity retired before scarpet operation"));
            else complete(future, inherited);
        }, retired -> future.completeExceptionally(new InternalExpressionException("Entity retired before scarpet operation")), 1L);
        if (!scheduled) future.completeExceptionally(new InternalExpressionException("Entity scheduler retired"));
        return future;
    }

    public static <T> T atBlock(ServerLevel world, BlockPos position, Supplier<T> operation) {
        if (TickThread.isTickThreadFor(world, position)) return operation.get();
        return await(atBlockFuture(world, position, operation));
    }

    public static <T> CompletableFuture<T> atBlockFuture(ServerLevel world, BlockPos position, Supplier<T> operation) {
        if (TickThread.isTickThreadFor(world, position)) {
            CompletableFuture<T> completed = new CompletableFuture<>();
            complete(completed, operation);
            return completed;
        }
        CompletableFuture<T> future = of(world.getServer()).actorFuture();
        if (future.isDone()) return future;
        Supplier<T> inherited = inheritFlags(operation);
        world.getServer().server.getRegionScheduler().execute(MinecraftInternalPlugin.INSTANCE, world.getWorld(), position.getX() >> 4, position.getZ() >> 4,
                () -> complete(future, inherited));
        return future;
    }

    public static <T> T atGlobal(MinecraftServer server, Supplier<T> operation) {
        if (RegionizedServer.isGlobalTickThread()) return operation.get();
        return await(atGlobalFuture(server, operation));
    }

    public static <T> CompletableFuture<T> atGlobalFuture(MinecraftServer server, Supplier<T> operation) {
        if (RegionizedServer.isGlobalTickThread()) {
            CompletableFuture<T> completed = new CompletableFuture<>();
            complete(completed, operation);
            return completed;
        }
        CompletableFuture<T> future = of(server).actorFuture();
        if (future.isDone()) return future;
        Supplier<T> inherited = inheritFlags(operation);
        RegionizedServer.getInstance().addTask(() -> complete(future, inherited));
        return future;
    }

    public static CompletableFuture<Boolean> worldEventDecision(CarpetEventServer.CallbackList callbacks, List<Value> arguments, ServerLevel world) {
        List<Value> captured = ActorFunctions.snapshotArguments(arguments);
        return atGlobalFuture(world.getServer(), () -> world.getServer().createCommandSourceStack().withLevel(world)).thenCompose(source -> of(source.getServer()).submit(() -> {
            try (var phase = fun.bm.lophine.carpet.TisMicroTiming.phase(world, "scarpet", "world_event")) {
                return callbacks.call(() -> captured, () -> source);
            }
        }));
    }

    public static <T> T withArea(ServerLevel world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, Supplier<T> operation) {
        if (TickThread.isTickThreadFor(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ)) return operation.get();
        assertMayWait();
        ScarpetRuntime runtime = of(world.getServer());
        if (runtime.closing && !runtime.allowsClosingActors())
            throw new InternalExpressionException("Scarpet server is closing");
        Supplier<T> inherited = inheritFlags(operation);
        CompletableFuture<T> future = fun.bm.lophine.carpet.CarpetRegionLease.runValue(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ, lease -> inherited.get());
        runtime.pendingActors.add(future);
        ScarpetNativeWork.record(future);
        future.whenComplete((value, failure) -> runtime.pendingActors.remove(future));
        if (runtime.closing && !runtime.allowsClosingActors())
            future.completeExceptionally(new IllegalStateException("Scarpet server is closing"));
        return await(future);
    }

    private <T> CompletableFuture<T> actorFuture() {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (this.closing && !this.allowsClosingActors())
            future.completeExceptionally(new IllegalStateException("Scarpet server is closing"));
        else {
            this.pendingActors.add(future);
            ScarpetNativeWork.record(future);
            future.whenComplete((result, failure) -> this.pendingActors.remove(future));
            if (this.closing && !this.allowsClosingActors())
                future.completeExceptionally(new IllegalStateException("Scarpet server is closing"));
        }
        return future;
    }

    private static void assertMayWait() {
        if (TickThread.isTickThread())
            throw new InternalExpressionException("A tick actor cannot wait for a different actor; evaluate this script on its interpreter thread");
    }

    public static <T> Supplier<T> captureOwnerOperation(Supplier<T> operation) {
        return inheritFlags(operation);
    }

    /**
     * Already accepted native work retains its physical continuation after a guest callback closes its app.
     */
    public static <T> Supplier<T> captureNativeContinuation(Supplier<T> operation) {
        var runner = ScarpetRuntime.<T>captureNativeRunner();
        return () -> runner.apply(operation);
    }

    /**
     * A delayed external command retains native flags and has no identity from the callback it outlives.
     */
    public static <T> Supplier<T> captureDetachedNativeContinuation(Supplier<T> operation) {
        GuestFrame frame = currentFrame();
        if (frame != null) frame.validate();
        Supplier<T> captured = detachedNativeIdentity(() -> captureNativeContinuation(operation));
        return () -> detachedNativeIdentity(captured);
    }

    private static <T> T detachedNativeIdentity(Supplier<T> operation) {
        GuestFrame previousFrame = CONTEXT.get();
        carpet.script.ScriptHost previousClosing = CLOSING_HOST.get();
        Entity previousDamage = DAMAGE_CALLBACK.get();
        NativeDecision previousDecision = NATIVE_DECISION.get();
        CONTEXT.remove();
        CLOSING_HOST.remove();
        DAMAGE_CALLBACK.remove();
        NATIVE_DECISION.remove();
        try (var accepted = ScarpetPlayerInventoryGate.inheritAccepted(java.util.Set.of())) {
            return fun.bm.lophine.carpet.OrgItemShadowGroups.withoutNativeBorrowScopes(() -> fun.bm.lophine.carpet.OrgGameplayHelper.withoutPhysicalRouting(() -> ScarpetNativeWork.without(() -> ScarpetAttribution.with(null, operation))));
        } finally {
            if (previousFrame == null) CONTEXT.remove();
            else CONTEXT.set(previousFrame);
            if (previousClosing == null) CLOSING_HOST.remove();
            else CLOSING_HOST.set(previousClosing);
            if (previousDamage == null) DAMAGE_CALLBACK.remove();
            else DAMAGE_CALLBACK.set(previousDamage);
            if (previousDecision == null) NATIVE_DECISION.remove();
            else NATIVE_DECISION.set(previousDecision);
        }
    }

    public static <T> java.util.function.Consumer<T> captureNativeConsumer(java.util.function.Consumer<T> operation) {
        var runner = ScarpetRuntime.<Void>captureNativeRunner();
        return value -> runner.apply(() -> {
            operation.accept(value);
            return null;
        });
    }

    public static <T, U> java.util.function.BiConsumer<T, U> captureNativeConsumer(java.util.function.BiConsumer<T, U> operation) {
        var runner = ScarpetRuntime.<Void>captureNativeRunner();
        return (value, other) -> runner.apply(() -> {
            operation.accept(value, other);
            return null;
        });
    }

    public static <T, R> java.util.function.Function<T, R> captureNativeFunction(java.util.function.Function<T, R> operation) {
        var runner = ScarpetRuntime.<R>captureNativeRunner();
        return value -> runner.apply(() -> operation.apply(value));
    }

    private static <T> java.util.function.Function<Supplier<T>, T> captureNativeRunner() {
        GuestFrame frame = currentFrame();
        if (frame != null) frame.validate();
        GuestFrame previous = CONTEXT.get();
        carpet.script.ScriptHost previousClosing = CLOSING_HOST.get();
        CONTEXT.remove();
        CLOSING_HOST.remove();
        try {
            return inheritFlagsRunner();
        } finally {
            if (previous != null) CONTEXT.set(previous);
            if (previousClosing != null) CLOSING_HOST.set(previousClosing);
        }
    }


    public static boolean nativeEventsAllowed(MinecraftServer server) {
        ScarpetRuntime runtime = SERVERS.get(server);
        return runtime != null && (!runtime.closing || runtime.allowsClosingActors());
    }

    public static CompletableFuture<Void> entityRemovalDecision(Entity entity, Supplier<CompletableFuture<Void>> event) {
        NativeDecision previous = NATIVE_DECISION.get();
        NATIVE_DECISION.set(new NativeDecision(entity, ScarpetNativeRemovals.EVENT_KEY));
        try {
            return event.get();
        } finally {
            if (previous == null) NATIVE_DECISION.remove();
            else NATIVE_DECISION.set(previous);
        }
    }

    public static CompletableFuture<Void> nativeDeathDecision(LivingEntity entity, Supplier<CompletableFuture<Void>> event) {
        NativeDecision previous = NATIVE_DECISION.get();
        NATIVE_DECISION.set(new NativeDecision(entity, ScarpetNativeDeaths.EVENT_KEY));
        try {
            return event.get();
        } finally {
            if (previous == null) NATIVE_DECISION.remove();
            else NATIVE_DECISION.set(previous);
        }
    }

    private static <T> Supplier<T> inheritFlags(Supplier<T> operation) {
        var runner = ScarpetRuntime.<T>inheritFlagsRunner();
        return () -> runner.apply(operation);
    }

    private static <T> java.util.function.Function<Supplier<T>, T> inheritFlagsRunner() {
        boolean fill = FILL_SKIP_UPDATES.get(), generation = SKIP_GENERATION_CHECKS.get();
        ScarpetRuntime runtime = CURRENT.get();
        GuestFrame capturedFrame = currentFrame();
        Entity capturedDamage = DAMAGE_CALLBACK.get();
        NativeDecision capturedDecision = NATIVE_DECISION.get();
        var capturedWork = ScarpetNativeWork.capture();
        var capturedAttribution = ScarpetAttribution.capture();
        var capturedAccepted = ScarpetPlayerInventoryGate.captureAccepted();
        var capturedRules = fun.bm.lophine.carpet.OrgGameplayHelper.captureNativeRuleScopes();
        var capturedBorrows = fun.bm.lophine.carpet.OrgItemShadowGroups.captureNativeBorrowScopes();
        boolean eventsDisabled = EVENT_DISABLED.get() || runtime != null && runtime.scripts != null && !runtime.scripts.events.handleEvents.get();
        return operation -> {
            boolean oldFill = FILL_SKIP_UPDATES.get(), oldGeneration = SKIP_GENERATION_CHECKS.get(), oldEvents = EVENT_DISABLED.get();
            NativeDecision oldDecision = NATIVE_DECISION.get();
            if (capturedDecision == null) NATIVE_DECISION.remove();
            else NATIVE_DECISION.set(capturedDecision);
            Entity oldDamage = DAMAGE_CALLBACK.get();
            if (capturedDamage == null) DAMAGE_CALLBACK.remove();
            else DAMAGE_CALLBACK.set(capturedDamage);
            FILL_SKIP_UPDATES.set(fill);
            SKIP_GENERATION_CHECKS.set(generation);
            EVENT_DISABLED.set(eventsDisabled);
            try (var ownerFrame = new ContextScope(capturedFrame); var accepted = ScarpetPlayerInventoryGate.inheritAccepted(capturedAccepted)) {
                if (capturedFrame != null) capturedFrame.validate();
                return capturedBorrows.call(() -> capturedRules.call(() -> ScarpetNativeWork.with(capturedWork, () -> ScarpetAttribution.with(capturedAttribution, operation))));
            } finally {
                FILL_SKIP_UPDATES.set(oldFill);
                SKIP_GENERATION_CHECKS.set(oldGeneration);
                EVENT_DISABLED.set(oldEvents);
                if (oldDamage == null) DAMAGE_CALLBACK.remove();
                else DAMAGE_CALLBACK.set(oldDamage);
                if (oldDecision == null) NATIVE_DECISION.remove();
                else NATIVE_DECISION.set(oldDecision);
            }
        };
    }

    private static <T> void complete(CompletableFuture<T> future, Supplier<T> operation) {
        if (future.isDone()) return;
        try {
            future.complete(operation.get());
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
        }
    }

    public static <T> T await(CompletableFuture<T> future) {
        assertMayWait();
        ScarpetRuntime runtime = CURRENT.get();
        int held = runtime == null ? 0 : runtime.interpreterLock.getHoldCount();
        for (int i = 0; i < held; ++i) runtime.interpreterLock.unlock();
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new InternalExpressionException("Scarpet thread interrupted");
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new InternalExpressionException(failure.getCause().toString());
        } finally {
            for (int i = 0; i < held; ++i) runtime.interpreterLock.lock();
            GuestFrame frame = CONTEXT.get();
            if (frame != null) frame.validate();
        }
    }

    public static void globalTick(MinecraftServer server) {
        ScarpetRuntime runtime = of(server);
        if (!runtime.closing) initialize(server);
        long tick = runtime.ticks.incrementAndGet();
        runtime.tickWaiters.forEach((due, waiters) -> {
            if (due <= tick && runtime.tickWaiters.remove(due, waiters))
                waiters.forEach(future -> future.complete(null));
        });
        if (runtime.closing) return;
        runtime.submit(() -> {
            runtime.scriptServer().tick();
            if (server.tickRateManager().runsNormally()) {
                carpet.script.CarpetEventServer.Event.TICK.onTick(server);
                carpet.script.CarpetEventServer.Event.NETHER_TICK.onTick(server);
                carpet.script.CarpetEventServer.Event.ENDER_TICK.onTick(server);
            }
            return null;
        }).exceptionally(failure -> {
            CarpetScriptServer.LOG.error("Scarpet tick failed", failure);
            return null;
        });
    }

    public static void awaitNextTick(MinecraftServer server) {
        assertMayWait();
        ScarpetRuntime runtime = of(server);
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (runtime.closing) throw new InternalExpressionException("Scarpet server is closing");
        long due = runtime.ticks.get() + 1L;
        runtime.tickWaiters.compute(due, (key, waiters) -> {
            if (waiters == null) waiters = ConcurrentHashMap.newKeySet();
            waiters.add(future);
            return waiters;
        });
        // Cancellation belongs to this caller, not every script waiting for the same tick.
        future.whenComplete((ignored, failure) -> runtime.tickWaiters.computeIfPresent(due, (key, waiters) -> {
            waiters.remove(future);
            return waiters.isEmpty() ? null : waiters;
        }));
        // The tick or shutdown may have passed before this waiter was inserted.
        if (runtime.closing) future.completeExceptionally(new IllegalStateException("Scarpet server is closing"));
        else if (runtime.ticks.get() >= due) future.complete(null);
        await(future);
    }

    public static void send(CommandSourceStack source, Component message, boolean failure) {
        Runnable action = () -> {
            if (failure) source.sendFailure(message);
            else source.sendSuccess(() -> message, false);
        };
        Entity entity = source.getEntity();
        if (entity == null) RegionizedServer.getInstance().addTask(action);
        else entity.getBukkitEntity().taskScheduler.schedule(owned -> action.run(), null, 1L);
    }

    public static void onJoin(ServerPlayer player) {
        ScarpetNativeWork.record(onJoinFuture(player));
    }

    public static CompletableFuture<Void> onJoinFuture(ServerPlayer player) {
        CommandSourceStack captured = player.createCommandSourceStack();
        captureSourcePermission(captured);
        var actual = of(captured.getServer()).submit(() -> {
            of(captured.getServer()).scriptServer().onPlayerJoin(player, captured);
            return (Void) null;
        });
        ScarpetNativeWork.record(actual);
        return actual;
    }

    public static void onLeave(ServerPlayer player, Component reason) {
        onLeaveFuture(player, reason).exceptionally(failure -> {
            CarpetScriptServer.LOG.error("Scarpet leave event failed", failure);
            return null;
        });
    }

    public static CompletableFuture<Void> onLeaveFuture(ServerPlayer player, Component reason) {
        CommandSourceStack captured = player.createCommandSourceStack();
        captureSourcePermission(captured);
        CompletableFuture<Void> completed;
        try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
            completed = ScarpetNativeWork.observeNative(player, () -> {
                if (CarpetEventServer.Event.PLAYER_DISCONNECTS.isNeeded() && nativeEventsAllowed(captured.getServer())) {
                    List<Value> args = List.of(carpet.script.value.EntityValue.snapshotForRetiredEvent(player), carpet.script.value.StringValue.of(reason.getString()));
                    ScarpetNativeWork.record(of(captured.getServer()).submit(() -> {
                        CarpetEventServer.Event.PLAYER_DISCONNECTS.handler.call(() -> args, () -> captured);
                        return null;
                    }));
                }
                return null;
            });
        }
        ScarpetPlayerInventoryGate.trackAccepted(player, completed);
        HEADERS.remove(player.getScoreboardName());
        FOOTERS.remove(player.getScoreboardName());
        return completed;
    }

    public static void runReplaying(Object key, Runnable operation) {
        java.util.Set<Object> active = REPLAYING.get();
        boolean added = active.add(key);
        try {
            operation.run();
        } finally {
            if (added) active.remove(key);
            if (active.isEmpty()) REPLAYING.remove();
        }
    }

    public static boolean isReplaying(Object event) {
        return REPLAYING.get().contains(event);
    }

    /**
     * Lets the source actor return, then rechecks captured state before replaying its native operation.
     */
    public static boolean defer(Entity owner, Object eventKey, Supplier<Boolean> event,
                                Supplier<Boolean> stillValid, Runnable continuation, Runnable resynchronize) {
        return deferDecisions(owner, eventKey, () -> captureEvent(event), stillValid, continuation, resynchronize);
    }

    public static CompletableFuture<Boolean> captureDecisions(List<Supplier<Boolean>> events) {
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(false);
        for (Supplier<Boolean> event : events)
            result = result.thenCombine(captureEvent(event), (previous, current) -> previous || current);
        return result;
    }

    public static boolean deferDecisions(Entity owner, Object eventKey, Supplier<CompletableFuture<Boolean>> events,
                                         Supplier<Boolean> stillValid, Runnable continuation, Runnable resynchronize) {
        if (EVENT_DISABLED.get() || isReplaying(eventKey)) return false;
        if (owner instanceof ServerPlayer player) return ScarpetPlayerInventoryGate.observeAccepted(player,
                () -> deferAcceptedDecisions(owner, eventKey, events, stillValid, continuation, resynchronize));
        return deferAcceptedDecisions(owner, eventKey, events, stillValid, continuation, resynchronize);
    }

    private static boolean deferAcceptedDecisions(Entity owner, Object eventKey, Supplier<CompletableFuture<Boolean>> events,
                                                  Supplier<Boolean> stillValid, Runnable continuation, Runnable resynchronize) {
        ScarpetRuntime runtime = of(owner.level().getServer());
        Map<Object, CompletableFuture<Void>> pending = runtime.pendingDecisions.computeIfAbsent(owner, key -> new ConcurrentHashMap<>());
        CompletableFuture<Void> tail = new CompletableFuture<>();
        CompletableFuture<Void> existing = pending.putIfAbsent(eventKey, tail);
        if (existing != null) {
            ScarpetNativeWork.record(existing);
            return true;
        }
        var token = ScarpetNativeWork.capture();
        ScarpetNativeWork.record(tail);
        runtime.pendingActors.add(tail);
        tail.whenComplete((ignored, failure) -> {
            runtime.pendingActors.remove(tail);
            pending.remove(eventKey, tail);
            if (pending.isEmpty()) runtime.pendingDecisions.remove(owner, pending);
        });
        CompletableFuture<Boolean> decision;
        NativeDecision previousDecision = NATIVE_DECISION.get();
        NATIVE_DECISION.set(new NativeDecision(owner, eventKey));
        try {
            decision = events.get();
        } catch (Throwable failure) {
            tail.completeExceptionally(failure);
            throw failure;
        } finally {
            if (previousDecision == null) NATIVE_DECISION.remove();
            else NATIVE_DECISION.set(previousDecision);
        }
        decision.whenComplete((cancelled, failure) -> {
            boolean scheduled = owner.getBukkitEntity().taskScheduler.schedule(entity -> {
                if (tail.isDone()) return;
                try {
                    ScarpetNativeWork.with(token, () -> {
                        if (entity != owner || owner.isRemoved())
                            throw new InternalExpressionException("Entity retired before scarpet continuation");
                        if (failure != null || Boolean.TRUE.equals(cancelled) || !stillValid.get()) {
                            resynchronize.run();
                            if (failure != null) CarpetScriptServer.LOG.error("Scarpet deferred event failed", failure);
                        } else if (owner instanceof ServerPlayer player) {
                            try (var accepted = ScarpetPlayerInventoryGate.acceptedScope(player)) {
                                runReplaying(eventKey, continuation);
                            }
                        } else runReplaying(eventKey, continuation);
                    });
                    if (failure == null) tail.complete(null);
                    else tail.completeExceptionally(failure);
                } catch (Throwable problem) {
                    tail.completeExceptionally(problem);
                }
            }, retired -> tail.completeExceptionally(new InternalExpressionException("Entity retired before scarpet continuation")), 1L);
            if (!scheduled) tail.completeExceptionally(new InternalExpressionException("Entity scheduler retired"));
        });
        return true;
    }

    /**
     * A screen callback shares the same decision future as a system event.
     */
    public static boolean enqueueCallback(MinecraftServer server, Supplier<Boolean> callback) {
        CompletableFuture<Boolean> captured = EVENT_CAPTURE.get();
        if (captured != null) EVENT_CAPTURE.remove();
        of(server).submit(() -> {
            try (var phase = fun.bm.lophine.carpet.TisMicroTiming.phase(null, "scarpet", "callback")) {
                return callback.get();
            }
        }).whenComplete((cancelled, failure) -> {
            if (captured == null) {
                if (failure != null) CarpetScriptServer.LOG.error("Scarpet callback failed", failure);
            } else if (failure == null) captured.complete(cancelled);
            else captured.completeExceptionally(failure);
        });
        return false;
    }

    /**
     * Shutdown is a continuation, so scheduler threads never wait on guest code.
     */
    public static boolean beginShutdown(MinecraftServer server, Runnable nativeShutdown) {
        ScarpetRuntime runtime = SERVERS.get(server);
        if (runtime == null) return false;
        if (!runtime.shutdownStarted.compareAndSet(false, true)) return !runtime.nativeShutdownReady;
        runtime.closing = true;
        IllegalStateException stopped = new IllegalStateException("Scarpet server is closing");
        runtime.pendingActors.forEach(future -> future.completeExceptionally(stopped));
        runtime.tickWaiters.values().forEach(waiters -> waiters.forEach(future -> future.completeExceptionally(stopped)));
        runtime.guestThreads.forEach(Thread::interrupt);
        CompletableFuture<Void> closed = new CompletableFuture<>();
        runtime.interpreter.execute(() -> {
            // Clear the interrupted guest operation before invoking the source shutdown callbacks.
            Thread.interrupted();
            Thread thread = Thread.currentThread();
            runtime.guestThreads.add(thread);
            runtime.interpreterLock.lock();
            CURRENT.set(runtime);
            SHUTDOWN_SCOPE.set(runtime);
            runtime.shutdownCallbacks = true;
            try {
                if (runtime.scripts != null) runtime.scripts.onClose();
                closed.complete(null);
            } catch (Throwable failure) {
                closed.completeExceptionally(failure);
            } finally {
                runtime.shutdownCallbacks = false;
                SHUTDOWN_SCOPE.remove();
                CURRENT.remove();
                runtime.interpreterLock.unlock();
                runtime.guestThreads.remove(thread);
                runtime.interpreter.shutdown();
            }
        });
        Thread.ofPlatform().name("Scarpet shutdown continuation").start(() -> {
            try {
                closed.get(30L, TimeUnit.SECONDS);
            } catch (Exception failure) {
                CarpetScriptServer.LOG.error("Scarpet shutdown callback did not finish", failure);
                runtime.guestThreads.forEach(Thread::interrupt);
                runtime.interpreter.shutdownNow();
            } finally {
                runtime.pendingActors.forEach(future -> future.completeExceptionally(stopped));
                HEADERS.clear();
                FOOTERS.clear();
                runtime.nativeShutdownReady = true;
                nativeShutdown.run();
            }
        });
        return true;
    }

    public static void close(MinecraftServer server) {
        beginShutdown(server, () -> {
        });
    }
}
