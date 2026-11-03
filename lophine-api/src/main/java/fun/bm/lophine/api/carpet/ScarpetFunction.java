package fun.bm.lophine.api.carpet;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * A function invoked on a Scarpet interpreter thread, without Folia region or
 * entity ownership. Arguments are immutable data snapshots. To access the world,
 * schedule on the appropriate Bukkit entity/region scheduler and return a stage;
 * do not block the callback or assume future continuations own a region.
 * Unsupported native values (entities, blocks, functions and lazy sequences) are
 * rejected before invoking plugin code. Unregistration fails pending results but
 * cannot interrupt work the plugin has already started.
 */
@FunctionalInterface
public interface ScarpetFunction {
    /**
     * Invokes the registered function.
     *
     * @param arguments immutable arguments
     * @return a non-null stage resolving to a non-null value, or an exceptional result
     */
    CompletionStage<ScarpetValue> apply(List<ScarpetValue> arguments);
}
