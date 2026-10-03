package carpet.script;

import carpet.script.exception.InternalExpressionException;
import carpet.script.external.Vanilla;
import carpet.script.value.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class EntityEventsGroup {
    private record EventKey(String host, String user) {
    }

    private final Map<Event, Map<EventKey, CarpetEventServer.Callback>> actions;
    private final Entity entity;

    public EntityEventsGroup(Entity e) {
        actions = new java.util.concurrent.ConcurrentHashMap<>();
        entity = e;
    }

    public boolean hasEvent(Event type) {
        Map<EventKey, CarpetEventServer.Callback> registered = actions.get(type);
        return registered != null && !registered.isEmpty();
    }

    public void onEvent(Event type, Object... args) {
        var completed = onEventFuture(type, args);
        carpet.script.external.ScarpetNativeWork.record(completed);
        completed.exceptionally(failure -> {
            CarpetScriptServer.LOG.error("Scarpet entity event failed", failure);
            return null;
        });
    }

    public java.util.concurrent.CompletableFuture<Void> onEventFuture(Event type, Object... args) {
        Map<EventKey, CarpetEventServer.Callback> actionSet = actions.get(type);
        if (actionSet == null || actionSet.isEmpty())
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        CommandSourceStack captured = entity instanceof ServerPlayer player ? carpet.script.external.ScarpetRuntime.entitySource(player) : entity.level().getServer().createCommandSourceStack();
        List<Value> capturedArguments = new java.util.ArrayList<>(type.makeArgs(entity, args));
        if (type == Event.ON_REMOVED) capturedArguments.set(0, EntityValue.snapshotForRetiredEvent(entity));
        List<Value> values = List.copyOf(capturedArguments);
        Map<EventKey, CarpetEventServer.Callback> calls = Map.copyOf(actionSet);
        return carpet.script.external.ScarpetRuntime.of(captured.getServer()).<Void>submit(() -> {
            try (var phase = fun.bm.lophine.carpet.TisMicroTiming.phase(captured.getLevel(), "scarpet", type.id); var damageScope = carpet.script.external.ScarpetRuntime.damageCallback(type == Event.ON_DAMAGE ? entity : null)) {
                CarpetScriptServer scriptServer = Vanilla.MinecraftServer_getScriptServer(captured.getServer());
                if (scriptServer.stopAll) return null;
                for (Map.Entry<EventKey, CarpetEventServer.Callback> action : calls.entrySet()) {
                    EventKey key = action.getKey();
                    ScriptHost host = scriptServer.getAppHostByName(key.host());
                    boolean online = key.user() == null || carpet.script.external.EntityActors.player(captured.getServer(), key.user()) != null;
                    if (host == null || !online || action.getValue().execute(captured, values) == CarpetEventServer.CallbackResult.FAIL)
                        actionSet.remove(key, action.getValue());
                }
                if (actionSet.isEmpty()) actions.remove(type, actionSet);
                return null;
            }
        });
    }

    public void addEvent(Event type, ScriptHost host, FunctionValue fun, List<Value> extraargs) {
        EventKey key = new EventKey(host.getName(), host.user);
        if (fun != null) {
            CarpetEventServer.Callback call = type.create(key, fun, extraargs, (CarpetScriptServer) host.scriptServer());
            if (call == null) {
                throw new InternalExpressionException("wrong number of arguments for callback, required " + type.argcount);
            }
            actions.computeIfAbsent(type, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(key, call);
        } else {
            actions.computeIfAbsent(type, k -> new java.util.concurrent.ConcurrentHashMap<>()).remove(key);
            if (actions.get(type).isEmpty()) {
                actions.remove(type);
            }
        }
    }


    public static class Event {
        public static final Map<String, Event> byName = new HashMap<>();
        public static final Event ON_DEATH = new Event("on_death", 1) {
            @Override
            public List<Value> makeArgs(Entity entity, Object... providedArgs) {
                return Arrays.asList(
                        new EntityValue(entity),
                        new StringValue((String) providedArgs[0])
                );
            }
        };
        public static final Event ON_REMOVED = new Event("on_removed", 0);
        public static final Event ON_TICK = new Event("on_tick", 0);
        public static final Event ON_DAMAGE = new Event("on_damaged", 3) {
            @Override
            public List<Value> makeArgs(Entity entity, Object... providedArgs) {
                float amount = (Float) providedArgs[0];
                DamageSource source = (DamageSource) providedArgs[1];
                return Arrays.asList(
                        new EntityValue(entity),
                        new NumericValue(amount),
                        new StringValue(source.getMsgId()),
                        source.getEntity() == null ? Value.NULL : new EntityValue(source.getEntity())
                );
            }
        };
        public static final Event ON_MOVE = new Event("on_move", 3) {
            @Override
            public List<Value> makeArgs(Entity entity, Object... providedArgs) {
                return Arrays.asList(
                        new EntityValue(entity),
                        ValueConversions.of((Vec3) providedArgs[0]),
                        ValueConversions.of((Vec3) providedArgs[1]),
                        ValueConversions.of((Vec3) providedArgs[2])
                );
            }
        };

        public final int argcount;
        public final String id;

        public Event(String identifier, int args) {
            id = identifier;
            argcount = args + 1; // entity is not extra
            byName.put(identifier, this);
        }

        public CarpetEventServer.Callback create(EventKey key, FunctionValue function, List<Value> extraArgs, CarpetScriptServer scriptServer) {
            if ((function.getArguments().size() - (extraArgs == null ? 0 : extraArgs.size())) != argcount) {
                return null;
            }
            return new CarpetEventServer.Callback(key.host(), key.user(), function, extraArgs, scriptServer);
        }

        public CarpetEventServer.CallbackResult call(CarpetEventServer.Callback tickCall, Entity entity, Object... args) {
            assert args.length == argcount - 1;
            return tickCall.execute((entity instanceof ServerPlayer player ? player.createCommandSourceStack() : entity.level().getServer().createCommandSourceStack()), makeArgs(entity, args));
        }

        protected List<Value> makeArgs(Entity entity, Object... args) {
            return Collections.singletonList(new EntityValue(entity));
        }
    }
}
