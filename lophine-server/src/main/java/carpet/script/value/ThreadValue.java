package carpet.script.value;

import carpet.script.Context;
import carpet.script.Expression;
import carpet.script.Token;
import carpet.script.exception.ExitStatement;
import carpet.script.exception.ExpressionException;
import carpet.script.exception.InternalExpressionException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.Tag;

public class ThreadValue extends LazyListValue
{
    private final CompletableFuture<Value> taskFuture;
    private final long id;
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();
    private final Deque<Value> coState = new ArrayDeque<>();
    private final AtomicReference<Value> coLock = new AtomicReference<>(Value.EOL);
    private CompletableFuture<Void> stateSignal = new CompletableFuture<>();
    private CompletableFuture<Void> lockSignal = new CompletableFuture<>();
    private void signalState() { CompletableFuture<Void> previous = stateSignal; stateSignal = new CompletableFuture<>(); previous.complete(null); }
    private void signalLock() { CompletableFuture<Void> previous = lockSignal; lockSignal = new CompletableFuture<>(); previous.complete(null); }
    public CompletableFuture<Value> completionFuture() { return taskFuture; }
    public final boolean isCoroutine;

    public ThreadValue(Value pool, FunctionValue function, Expression expr, Token token, Context ctx, List<Value> args)
    {
        this.id = SEQUENCE.getAndIncrement();
        this.isCoroutine = ctx.host.canSynchronouslyExecute();
        this.taskFuture = getCompletableFutureFromFunction(pool, function, expr, token, ctx, args);

        Thread.yield();
    }

    public CompletableFuture<Value> getCompletableFutureFromFunction(Value pool, FunctionValue function, Expression expr, Token token, Context ctx, List<Value> args)
    {
        ExecutorService executor = ctx.host.getExecutor(pool);
        ThreadValue callingThread = isCoroutine ? this : null;
        if (executor == null)
        {
            // app is shutting down - no more threads can be spawned.
            return CompletableFuture.completedFuture(Value.NULL);
        }
        else
        {
            long epoch = ctx.host.executionEpoch();
            var runtime = carpet.script.external.ScarpetRuntime.taskRuntime(ctx);
            return CompletableFuture.supplyAsync(() -> {
                try (var taskScope = carpet.script.external.ScarpetRuntime.taskScope(ctx, epoch, runtime))
                {
                    return function.execute(ctx, Context.NONE, expr, token, args, callingThread).evalValue(ctx);
                }
                catch (ExitStatement exit)
                {
                    // app stopped
                    return exit.retval;
                }
                catch (ExpressionException exc)
                {
                    ctx.host.handleExpressionException("Thread failed\n", exc);
                    return Value.NULL;
                }
            }, executor);
        }
    }

    @Override
    public String getString()
    {
        return taskFuture.getNow(Value.NULL).getString();
    }

    public Value getValue()
    {
        return taskFuture.getNow(Value.NULL);
    }

    @Override
    public boolean getBoolean()
    {
        return taskFuture.getNow(Value.NULL).getBoolean();
    }

    public Value join()
    {
        return carpet.script.external.ScarpetRuntime.await(taskFuture);
    }

    public boolean isFinished()
    {
        return taskFuture.isDone();
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof ThreadValue tv && tv.id == this.id;
    }

    @Override
    public int compareTo(Value o)
    {
        if (!(o instanceof ThreadValue tv))
        {
            throw new InternalExpressionException("Cannot compare tasks to other types");
        }
        return (int) (this.id - tv.id);
    }

    @Override
    public int hashCode()
    {
        return Long.hashCode(id);
    }

    @Override
    public Tag toTag(boolean force, RegistryAccess regs)
    {
        if (!force)
        {
            throw new NBTSerializableValue.IncompatibleTypeException(this);
        }
        return getValue().toTag(true, regs);
    }

    @Override
    public String getTypeString()
    {
        return "task";
    }

    @Override
    public void fatality()
    {
        // we signal that won't be interested in the co-thread anymore
        // but threads run client code, so we can't just kill them
    }

    @Override
    public void reset()
    {
        //throw new InternalExpressionException("Illegal operation on a task");
    }


    @Override
    public Iterator<Value> iterator()
    {
        if (!isCoroutine)
        {
            throw new InternalExpressionException("Cannot iterate over this task");
        }
        return this;
    }

    @Override
    public boolean hasNext()
    {
        synchronized (coState) { return !(coState.isEmpty() && taskFuture.isDone()); }
    }

    @Override
    public Value next()
    {
        for (;;) {
            CompletableFuture<Void> wait;
            synchronized (coState) {
                if (!coState.isEmpty()) { Value value = coState.pop(); signalState(); return value; }
                if (taskFuture.isDone()) return Value.EOL;
                wait = stateSignal;
            }
            carpet.script.external.ScarpetRuntime.await(CompletableFuture.anyOf(wait, taskFuture));
        }
    }

    public void send(Value value)
    {
        synchronized (coLock) { coLock.set(value); signalLock(); }
    }

    public Value ping(Value value, boolean lock)
    {
        for (;;) {
            CompletableFuture<Void> wait;
            synchronized (coState) {
                if (!lock || coState.isEmpty()) { coState.add(value); signalState(); break; }
                wait = stateSignal;
            }
            carpet.script.external.ScarpetRuntime.await(wait);
        }
        if (!lock) return Value.NULL;
        for (;;) {
            CompletableFuture<Void> wait;
            synchronized (coLock) {
                Value answer = coLock.get();
                if (answer != Value.EOL) { coLock.set(Value.EOL); signalLock(); return answer; }
                wait = lockSignal;
            }
            carpet.script.external.ScarpetRuntime.await(wait);
        }
    }
}
