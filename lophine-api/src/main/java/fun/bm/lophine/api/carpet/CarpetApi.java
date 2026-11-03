package fun.bm.lophine.api.carpet;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Access to this server's Carpet integration. Methods may be called from any thread.
 * Rule operations are queued on the Folia global region; never block a tick thread
 * waiting for their futures. Completions confer no entity or region ownership.
 */
public interface CarpetApi {
    /**
     * Returns the current server's Carpet API.
     *
     * @return the current server's Carpet API
     */
    static CarpetApi get() {
        return Bukkit.getServer().getCarpet();
    }

    /**
     * Returns immutable snapshots, including hidden rules marked unavailable.
     *
     * @return immutable snapshots, including hidden rules marked unavailable
     */
    CompletableFuture<List<CarpetRule>> rules();

    /**
     * Looks up a rule by its case-sensitive name.
     *
     * @param name case-sensitive Carpet rule name
     * @return a snapshot, or empty for an unknown rule
     */
    CompletableFuture<Optional<CarpetRule>> rule(String name);

    /**
     * Validates and changes a rule using the command's native configuration and
     * notification pipeline. A restart-only rule requires {@code persist=true}.
     * An exceptional completion after a mutation does not roll it back.
     * Cancellation of the returned future does not undo accepted work.
     *
     * @param owner   enabled plugin requesting the mutation
     * @param name    case-sensitive rule name
     * @param value   command-form input, including comma-separated lists
     * @param persist whether to save the value as the startup default
     * @return applied/unchanged/restart status after native notifications complete;
     *         invalid, unavailable or protocol-blocked requests fail exceptionally
     */
    CompletableFuture<CarpetRuleUpdate> setRule(Plugin owner, String name, String value, boolean persist);

    /**
     * Registers a function in newly constructed Scarpet expressions. Existing
     * expressions must be reloaded to acquire it. The exposed name is {@code plugin_}, then the plugin
     * name lowercased with non-identifier characters replaced by underscores,
     * followed by {@code __} and {@code name}. Collisions are rejected.
     * Registration is removed automatically before plugin disable callbacks.
     *
     * @param owner    enabled plugin owning the callback
     * @param name     a lowercase identifier matching {@code [a-z][a-z0-9_]*}
     * @param function callback; must return promptly and schedule world work on its owner
     * @return a handle that immediately revokes calls, including in old expressions
     */
    ScarpetRegistration registerScarpetFunction(Plugin owner, String name, ScarpetFunction function);

    /**
     * Revokes this plugin's functions without waiting for plugin code. Accepted
     * invocations may already be executing; their outstanding result futures fail.
     *
     * @param owner plugin whose functions should be removed
     */
    void unregisterScarpetFunctions(Plugin owner);
}
