package fun.bm.lophine.carpet.api;

import carpet.script.CarpetExpression;
import carpet.script.exception.InternalExpressionException;
import carpet.script.external.ScarpetRuntime;
import fun.bm.lophine.api.carpet.*;
import fun.bm.lophine.carpet.CarpetRuleChanges;
import fun.bm.lophine.carpet.CarpetRuleMetadata;
import fun.bm.lophine.carpet.CarpetRuleRegistry;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import me.earthme.luminol.api.config.annotation.DoNotLoad;
import me.earthme.luminol.api.config.enums.EnumLoadType;
import net.minecraft.server.MinecraftServer;
import org.bukkit.plugin.Plugin;

/** The global scheduler owns rule state; the extension registry owns callback admission. */
public final class LophineCarpetApi implements CarpetApi {
    private static final ConcurrentHashMap<MinecraftServer, LophineCarpetApi> SERVERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<ChangeSource> SOURCE = new ThreadLocal<>();
    private record ChangeSource(CarpetRuleChangeEvent.Cause cause, String pluginName) { }

    private final MinecraftServer server;
    private final ScarpetExtensions extensions = new ScarpetExtensions();
    private final CarpetApiTasks tasks;

    private LophineCarpetApi(MinecraftServer server) {
        this(server, task -> RegionizedServer.getInstance().addTask(task));
    }

    LophineCarpetApi(MinecraftServer server, java.util.concurrent.Executor executor) {
        this.server = server;
        this.tasks = new CarpetApiTasks(executor);
    }

    public static LophineCarpetApi get(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, LophineCarpetApi::new);
    }

    public static void pluginEnabling(Plugin plugin) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) get(server);
        SERVERS.values().forEach(api -> api.extensions.enable(plugin));
    }

    public static void pluginDisabling(Plugin plugin) {
        // Materialize the gate even if the plugin first asks for the API in a disable listener.
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) get(server);
        SERVERS.values().forEach(api -> api.extensions.unregister(plugin, true));
    }

    public static void shutdown(MinecraftServer server) {
        LophineCarpetApi api = get(server);
        api.extensions.close();
        api.tasks.close();
    }

    public static <T> T withSource(CarpetRuleChangeEvent.Cause cause, String pluginName, Supplier<T> action) {
        ChangeSource previous = SOURCE.get();
        SOURCE.set(new ChangeSource(cause, pluginName));
        try { return action.get(); }
        finally {
            if (previous == null) SOURCE.remove();
            else SOURCE.set(previous);
        }
    }

    public static String text(Object value) {
        return value instanceof List<?> list ? String.join(",", list.stream().map(Object::toString).toList()) : value.toString();
    }

    public static CarpetRule snapshot(String name) {
        return snapshot(name, text(CarpetRuleRegistry.get(name).value()));
    }

    private static CarpetRule snapshot(String name, String value) {
        var binding = CarpetRuleRegistry.get(name);
        var metadata = CarpetRuleMetadata.get(name);
        Object defaultValue = CarpetRuleRegistry.config().getDefaultConfig(binding.path());
        var loading = binding.field().getAnnotation(DoNotLoad.class);
        return new CarpetRule(name, binding.path(), metadata.project(), binding.field().getType().getSimpleName(), value,
                defaultValue == null ? null : text(defaultValue), metadata.description(), metadata.categories(), metadata.options(),
                (loading == null || loading.when() != EnumLoadType.ALWAYS)
                        && (!CarpetRuleMetadata.isHidden(name) || fun.bm.lophine.carpet.org.OrgHiddenPlayerActions.enabled()),
                loading != null && loading.when() == EnumLoadType.RELOAD);
    }

    /** Called only for actual live-value changes; snapshots are built on the global owner. */
    public static void changed(String name, Object previous, Object current) {
        if (Objects.equals(previous, current)) return;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || !server.isReady() || server.hasStopped()) return;
        String oldValue = text(previous), newValue = text(current);
        ChangeSource source = SOURCE.get();
        if (source == null) source = new ChangeSource(CarpetRuleChangeEvent.Cause.CONFIGURATION, null);
        ChangeSource captured = source;
        Runnable dispatch = () -> server.server.getPluginManager().callEvent(new CarpetRuleChangeEvent(
                snapshot(name, oldValue), snapshot(name, newValue), captured.cause(), captured.pluginName()));
        if (RegionizedServer.isGlobalTickThread()) dispatch.run();
        else get(server).tasks.submit(() -> { dispatch.run(); return null; }).exceptionally(failure -> {
            carpet.script.CarpetScriptServer.LOG.error("Carpet rule event dispatch failed", failure);
            return null;
        });
    }

    @Override
    public CompletableFuture<List<CarpetRule>> rules() {
        return this.tasks.submit(() -> CarpetRuleRegistry.names().stream().map(LophineCarpetApi::snapshot).toList());
    }

    @Override
    public CompletableFuture<Optional<CarpetRule>> rule(String name) {
        Objects.requireNonNull(name, "name");
        return this.tasks.submit(() -> CarpetRuleRegistry.names().contains(name) ? Optional.of(snapshot(name)) : Optional.empty());
    }

    @Override
    public CompletableFuture<CarpetRuleUpdate> setRule(Plugin owner, String name, String value, boolean persist) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        try { this.extensions.checkOwner(owner); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        return this.tasks.submitAsync(() -> {
            this.extensions.checkOwner(owner);
            return CarpetRuleChanges.changeFromApi(this.server, owner.getName(), name, value, persist);
        });
    }

    @Override
    public ScarpetRegistration registerScarpetFunction(Plugin owner, String name, ScarpetFunction function) {
        return this.extensions.register(owner, Objects.requireNonNull(name, "name"), function);
    }

    @Override
    public void unregisterScarpetFunctions(Plugin owner) {
        this.extensions.unregister(Objects.requireNonNull(owner, "owner"), false);
    }

    public static void attach(MinecraftServer server, CarpetExpression expression) {
        for (var entry : get(server).extensions.snapshot()) {
            if (expression.getExpr().getFunctionNames().contains(entry.functionName())) {
                throw new InternalExpressionException("Scarpet extension name conflict: " + entry.functionName());
            }
            expression.getExpr().addImpureFunction(entry.functionName(), arguments -> {
                if (!ScarpetRuntime.of(server).isInterpreterThread()) {
                    throw new InternalExpressionException("Plugin Scarpet functions require the interpreter thread");
                }
                var result = entry.invoke(new ScarpetValues().arguments(arguments));
                try { return new ScarpetValues().nativeValue(ScarpetRuntime.await(result)); }
                catch (RuntimeException failure) {
                    throw new InternalExpressionException("Scarpet extension " + entry.functionName() + " failed: " + failure);
                }
            });
        }
    }
}
