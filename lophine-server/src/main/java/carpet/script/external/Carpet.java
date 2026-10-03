package carpet.script.external;

import carpet.script.CarpetEventServer;
import carpet.script.CarpetExpression;
import carpet.script.CarpetScriptHost;
import carpet.script.CarpetScriptServer;
import carpet.script.exception.InternalExpressionException;
import carpet.script.exception.LoadException;
import carpet.script.external.version.api.Version;
import carpet.script.external.version.api.VersionParsingException;
import carpet.script.external.version.api.metadata.version.VersionPredicate;
import carpet.script.value.MapValue;
import carpet.script.value.StringValue;
import fun.bm.lophine.carpet.CarpetRuleRegistry;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.protocol.CarpetServerProtocol;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Platform bridge for the unabridged upstream Scarpet module.
 */
public final class Carpet {
    private static final List<Consumer<CarpetExpression>> EXTENSIONS = new CopyOnWriteArrayList<>();
    private static CarpetEventServer.Event ruleChanges;
    private static volatile String configuredAppStore;

    public static Map<String, Component> getScarpetHeaders() {
        return ScarpetRuntime.HEADERS;
    }

    public static Map<String, Component> getScarpetFooters() {
        return ScarpetRuntime.FOOTERS;
    }

    public static void updateScarpetHUDs(MinecraftServer server, List<ServerPlayer> players) {
        for (ServerPlayer player : players)
            player.getBukkitEntity().taskScheduler.schedule(owned ->
                    fun.bm.lophine.protocol.CarpetLoggerProtocol.sendScarpetHud((ServerPlayer) owned), null, 1L);
    }

    public static Component Messenger_compose(Object... messages) {
        return ScarpetMessenger.c(messages);
    }

    public static void Messenger_message(CommandSourceStack source, Object... messages) {
        if (source != null) ScarpetRuntime.send(source, ScarpetMessenger.c(messages), false);
    }

    public static ThreadLocal<Boolean> getImpendingFillSkipUpdates() {
        return ScarpetRuntime.FILL_SKIP_UPDATES;
    }

    public static Runnable startProfilerSection(String name) {
        long start = System.nanoTime();
        return () -> {
            ScarpetRuntime.PROFILE_NANOS.computeIfAbsent(name, key -> new LongAdder()).add(System.nanoTime() - start);
            ScarpetRuntime.PROFILE_CALLS.computeIfAbsent(name, key -> new LongAdder()).increment();
        };
    }

    public static void MinecraftServer_addScriptServer(MinecraftServer server, CarpetScriptServer scriptServer) {
        ScarpetRuntime.of(server).setScriptServer(scriptServer);
    }

    public static boolean isValidCarpetPlayer(ServerPlayer player) {
        return CarpetServerProtocol.isValidCarpetPlayer(player);
    }

    public static String getPlayerStatus(ServerPlayer player) {
        return CarpetServerProtocol.getPlayerStatus(player);
    }

    public static MapValue getAllCarpetRules() {
        MapValue values = new MapValue(Collections.emptyList());
        for (String name : CarpetRuleRegistry.names())
            values.put(new StringValue(name), new StringValue(CarpetRuleRegistry.get(name).value().toString()));
        return values;
    }

    public static String getCarpetVersion() {
        return "26.3";
    }

    @Nullable
    public static String isModdedPlayer(Player player) {
        return player instanceof ServerBot bot ? bot.carpetShadow ? "shadow" : "fake" : null;
    }

    public static void registerExtensionAPI(Consumer<CarpetExpression> extension) {
        EXTENSIONS.add(extension);
    }

    public static void handleExtensionsAPI(CarpetExpression expression) {
        EXTENSIONS.forEach(extension -> extension.accept(expression));
    }

    public static boolean getFillUpdates() {
        return GeneralCompatConfig.fillUpdates;
    }

    public static boolean isDebugEnabled() {
        return GeneralCompatConfig.superSecretSetting;
    }

    public static @Nullable Path fetchGlobalPath(MinecraftServer server) {
        return null;
    }

    public static void assertRequirementMet(CarpetScriptHost host, String mod, String condition) {
        try {
            VersionPredicate predicate = VersionPredicate.parse(condition);
            String version = Vanilla.platformVersions().get(mod);
            if (version != null && predicate.test(Version.parse(version))) return;
        } catch (VersionParsingException failure) {
            throw new InternalExpressionException("Failed to parse version conditions for '" + mod + "' in 'requires': " + failure.getMessage());
        }
        throw new LoadException(String.format("%s requires a version of mod '%s' matching '%s', which is missing!", host.getVisualName(), mod, condition));
    }

    public static void initCarpetEvents() {
        ruleChanges = new CarpetEventServer.Event("carpet_rule_changes", 2, true) {
            @Override
            public void handleAny(Object... args) {
                String name = (String) args[0];
                String value = (String) args[1];
                CommandSourceStack source = (CommandSourceStack) args[2];
                handler.call(() -> List.of(new StringValue(name), new StringValue(value)), () -> source);
            }
        };
    }

    public static void ruleChanged(CommandSourceStack source, String name, Object value) {
        applyRuleSideEffects(source, name, value);
        if (ruleChanges != null && ruleChanges.isNeeded()) ruleChanges.handleAny(name, value.toString(), source);
    }

    public static void applyRuleSideEffects(CommandSourceStack source, String name, Object value) {
        if (name.equals("commandScriptACE")) ScarpetRuntime.of(source.getServer()).updateRunPermission(source);
        if (name.equals("scriptsAppStore")) {
            String path = value.toString();
            if (!java.util.Objects.equals(configuredAppStore, path)) {
                configuredAppStore = path;
                carpet.script.utils.AppStoreManager.setScarpetRepoLink(path.equals("none") ? null : "https://api.github.com/repos/" + path + "/");
            }
        }
    }

    public static void validateRuleChange(CommandSourceStack source, String name, Object value) {
        if (!name.equals("commandScriptACE") || source == null) return;
        net.minecraft.server.permissions.PermissionCheck required = switch (value.toString()) {
            case "0", "false" -> net.minecraft.commands.Commands.LEVEL_ALL;
            case "1" -> net.minecraft.commands.Commands.LEVEL_MODERATORS;
            case "2", "true", "ops" -> net.minecraft.commands.Commands.LEVEL_GAMEMASTERS;
            case "3" -> net.minecraft.commands.Commands.LEVEL_ADMINS;
            case "4" -> net.minecraft.commands.Commands.LEVEL_OWNERS;
            default -> throw new IllegalArgumentException("Invalid Scarpet ACE level");
        };
        if (!required.check(source.permissions()))
            throw new IllegalArgumentException("You must have the permission level you are giving Scarpet");
    }
}
