package carpet.script;

import carpet.script.value.Value;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// WIP
public abstract class ScriptServer {
    public final Map<Value, Value> systemGlobals = new ConcurrentHashMap<>();

    public abstract Path resolveResource(String suffix);
}
