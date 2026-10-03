package carpet.script.api;

import carpet.script.CarpetContext;
import carpet.script.Expression;
import carpet.script.exception.ExpressionException;
import carpet.script.exception.InternalExpressionException;
import carpet.script.value.ThreadValue;
import carpet.script.value.Value;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletionException;

public class Threading
{
    public static void apply(Expression expression)
    {
        //"overridden" native call to cancel if on main thread
        expression.addContextFunction("task_join", 1, (c, t, lv) -> {
            if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThread())
            {
                throw new InternalExpressionException("'task_join' cannot be called from main thread to avoid deadlocks");
            }
            Value v = lv.get(0);
            if (!(v instanceof final ThreadValue tv))
            {
                throw new InternalExpressionException("'task_join' could only be used with a task value");
            }
            return tv.join();
        });

        // has to be lazy due to deferred execution of the expression
        expression.addLazyFunctionWithDelegation("task_dock", 1, false, true, (c, t, expr, tok, lv) -> {
            CarpetContext cc = (CarpetContext) c;
            MinecraftServer server = cc.server();
            if (server.isSameThread() || carpet.script.external.ScarpetRuntime.of(server).isInterpreterThread())
            {
                return lv.get(0); // pass through for on thread tasks
            }
            long epoch = c.host.executionEpoch();
            Value value = carpet.script.external.ScarpetRuntime.await(carpet.script.external.ScarpetRuntime.of(server).submit(() -> {
                try (var captured = carpet.script.external.ScarpetRuntime.enterCapturedContext(c, epoch)) { return lv.get(0).evalValue(c, t); }
            }));
            return (ct, tt) -> value;
            // pass through placeholder
            // implmenetation should dock the task on the main thread.
        });
    }
}
