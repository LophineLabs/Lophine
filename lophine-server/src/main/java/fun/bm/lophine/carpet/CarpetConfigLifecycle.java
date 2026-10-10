// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import carpet.script.external.Carpet;
import carpet.script.external.ScarpetRuntime;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applies file reloads through the same VM side effects as accepted native rule commands.
 */
public final class CarpetConfigLifecycle {
    private CarpetConfigLifecycle() {
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : CarpetRuleRegistry.names()) values.put(name, CarpetRuleRegistry.get(name).value());
        return Map.copyOf(values);
    }

    public static void reloaded(Map<String, Object> previous) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || !server.isReady()) return;
        var source = server.createCommandSourceStack();
        Map<String, Object> values = snapshot();
        boolean commandsChanged = values.entrySet().stream().anyMatch(entry -> !Objects.equals(previous.get(entry.getKey()), entry.getValue())
                && (entry.getKey().startsWith("command") || entry.getKey().startsWith("playerCommand") || entry.getKey().startsWith("tick")
                || entry.getKey().endsWith("Permission") || entry.getKey().equals("carpetCommandPermissionLevel") || entry.getKey().equals("perfPermissionLevel")));
        if (commandsChanged) for (var player : server.getPlayerList().getPlayers()) {
            player.getBukkitEntity().taskScheduler.schedule(actor -> server.getCommands().sendCommands((net.minecraft.server.level.ServerPlayer) actor), null, 1L);
        }
        ScarpetRuntime.of(server).submit(() -> {
            // Source validators run on a file load even when a rule's final value is unchanged.
            Carpet.applyRuleSideEffects(source, "commandScriptACE", values.get("commandScriptACE"));
            Carpet.applyRuleSideEffects(source, "scriptsAppStore", values.get("scriptsAppStore"));
            values.forEach((name, value) -> {
                if (!Objects.equals(previous.get(name), value)) Carpet.ruleChanged(source, name, value);
            });
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null)
                carpet.script.CarpetScriptServer.LOG.error("Scarpet configuration reload callback failed", failure);
        });
    }
}
