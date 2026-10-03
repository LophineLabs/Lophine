// SPDX-License-Identifier: MIT
package carpet.script.external;

import carpet.script.exception.InternalExpressionException;
import carpet.script.value.EntityValue;
import carpet.script.value.ListValue;
import carpet.script.value.Value;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** VM fan-in of immutable owner results; no actor waits for another actor. */
public final class EntityActors {
    private record Match(Entity entity, UUID uuid, double distance) {}
    private record Location(ServerLevel world,BlockPos position) {}
    private EntityActors() {}

    private static List<Entity> references(MinecraftServer server,ServerLevel onlyWorld,boolean players) {
        return ScarpetRuntime.atGlobal(server,() -> {
            if(players) return new ArrayList<Entity>(server.getPlayerList().getPlayers());
            List<Entity> result=new ArrayList<>();
            if(onlyWorld!=null) result.addAll(ScarpetEntityIndex.entities(onlyWorld));
            else for(ServerLevel level:server.getAllLevels()) result.addAll(ScarpetEntityIndex.entities(level));
            return result;
        });
    }
    private static List<Match> match(Collection<? extends Entity> candidates,Predicate<Entity> predicate,Vec3 position) {
        List<CompletableFuture<Match>> jobs=new ArrayList<>(candidates.size());
        for(Entity entity:candidates) jobs.add(ScarpetRuntime.atEntityFuture(entity,() -> {
            if(entity.isRemoved() || !predicate.test(entity)) return null;
            return new Match(entity,entity.getUUID(),position==null ? 0 : entity.distanceToSqr(position));
        }).handle((value,failure) -> {
            if (failure == null) return value;
            Throwable cause = failure;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof InternalExpressionException && ("Entity retired before scarpet operation".equals(cause.getMessage()) || "Entity scheduler retired".equals(cause.getMessage()))) return null;
            throw new java.util.concurrent.CompletionException(cause);
        }));
        ScarpetRuntime.await(CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)));
        LinkedHashMap<UUID,Match> unique=new LinkedHashMap<>();
        for(var job:jobs) { Match value=job.getNow(null); if(value!=null) unique.putIfAbsent(value.uuid(),value); }
        return new ArrayList<>(unique.values());
    }
    public static List<ServerPlayer> players(MinecraftServer server,ServerLevel world,Predicate<ServerPlayer> predicate) {
        return match(references(server,null,true),e -> e instanceof ServerPlayer player && (world==null || player.level()==world) && predicate.test(player),null)
            .stream().map(value -> (ServerPlayer)value.entity()).toList();
    }
    public static net.minecraft.world.entity.player.Player nearestPlayer(ServerLevel world,Vec3 position) {
        return match(references(world.getServer(),null,true),e -> e instanceof ServerPlayer && e.level()==world && net.minecraft.world.entity.EntitySelector.ENTITY_STILL_ALIVE.test(e),position)
            .stream().min(Comparator.comparingDouble(Match::distance)).map(value -> (net.minecraft.world.entity.player.Player)value.entity()).orElse(null);
    }
    public static ServerPlayer player(MinecraftServer server,String name) {
        return players(server,null,player -> player.getScoreboardName().equalsIgnoreCase(name)).stream().map(entity -> (ServerPlayer)entity).findFirst().orElse(null);
    }
    public static Entity byId(ServerLevel world,Integer id,UUID uuid) {
        List<Match> found=match(references(world.getServer(),world,false),e -> e.level()==world && (id!=null ? e.getId()==id : uuid.equals(e.getUUID())),null);
        return found.isEmpty() ? null : found.getFirst().entity();
    }
    public static List<Entity> entities(ServerLevel world,EntityTypeTest<Entity,?> type,Predicate<? super Entity> predicate,AABB area) {
        return match(references(world.getServer(),world,false),e -> e.level()==world && type.tryCast(e)!=null && (area==null || area.intersects(e.getBoundingBox())) && predicate.test(e),null)
            .stream().map(Match::entity).toList();
    }
    public static List<Entity> select(CommandSourceStack source,EntitySelector selector) {
        try {
            if(source.getEntity()!=null) ScarpetRuntime.atEntity(source.getEntity(),() -> { selector.carpetCheckSelectorPermissions(source); return null; });
            else ScarpetRuntime.atGlobal(source.getServer(),() -> { selector.carpetCheckSelectorPermissions(source); return null; });
        } catch(RuntimeException failure) {
            Throwable cause=failure;
            while(cause instanceof java.util.concurrent.CompletionException && cause.getCause()!=null) cause=cause.getCause();
            if(cause instanceof InternalExpressionException && ("Entity retired before scarpet operation".equals(cause.getMessage()) || "Entity scheduler retired".equals(cause.getMessage())))
                ScarpetRuntime.checkCapturedSelectorPermissions(source,selector);
            else throw new InternalExpressionException("Entity selector permissions rejected: "+failure.getMessage());
        }
        Vec3 position=selector.carpetSelectorAnchor(source.getPosition());
        var features=source.enabledFeatures();
        Entity caller=source.getEntity(); UUID callerId=caller==null ? null : caller.getUUID();
        List<Entity> candidates=selector.isSelfSelector() ? caller==null ? List.of() : List.of(caller)
            : references(source.getServer(),selector.isWorldLimited() && selector.carpetEntityUuid()==null && selector.carpetPlayerName()==null ? source.getLevel() : null,!selector.includesEntities());
        List<Match> selected=match(candidates,e -> selector.carpetMatchesScarpet(e,source.getPosition(),source.getLevel(),callerId,features),position);
        switch(selector.carpetSelectorOrderKind()) {
            case 1 -> selected.sort(Comparator.comparingDouble(Match::distance));
            case 2 -> selected.sort(Comparator.comparingDouble(Match::distance).reversed());
            case 3 -> java.util.Collections.shuffle(selected);
            default -> { }
        }
        return selected.subList(0,Math.min(selector.getMaxResults(),selected.size())).stream().map(Match::entity).toList();
    }
    private static EntityValue fixed(Entity entity) { return new EntityValue(entity) { @Override public Entity getEntity() { return entity; } }; }
    private static Value detachReferences(Value value,List<Entity> related) {
        if(value instanceof EntityValue ev) { Entity entity=ev.getEntity(); related.add(entity); return fixed(entity); }
        if(value instanceof ListValue list) {
            List<Value> values=new ArrayList<>(); for(Value element:list.getItems()) values.add(detachReferences(element,related));
            return ListValue.wrap(values);
        }
        return value;
    }
    /** Execute passenger mutations only while all related entities belong to one acquired region. */
    public static boolean relationship(Entity source,String action,Value value,BiConsumer<Entity,Value> operation) {
        if(!java.util.Set.of("mount","mount_passengers","dismount","drop_passengers").contains(action)) return false;
        List<Entity> related=new ArrayList<>(); related.add(source);
        Value captured=detachReferences(value,related);
        related.addAll(ScarpetRetiredActors.access(source,() -> {
            List<Entity> result=new ArrayList<>(source.getPassengers()); Entity vehicle=source.getVehicle(); if(vehicle!=null) result.add(vehicle); return result;
        }));
        for(int attempt=0;attempt<8;attempt++) {
            List<Location> locations=new ArrayList<>();
            for(Entity entity:related) locations.add(ScarpetRetiredActors.access(entity,() -> new Location((ServerLevel)entity.level(),entity.blockPosition().immutable())));
            ServerLevel world=locations.getFirst().world();
            if(locations.stream().anyMatch(location -> location.world()!=world)) throw new InternalExpressionException("Passenger operations require entities in the same dimension");
            int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(Location location:locations) { int x=location.position().getX()>>4,z=location.position().getZ()>>4; minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxZ=Math.max(maxZ,z); }
            boolean completed=ScarpetRuntime.withArea(world,minX,minZ,maxX,maxZ,() -> {
                for(Entity entity:related) if(entity.level()!=world
                    || !TickThread.isTickThreadFor(entity) && !(ScarpetRetiredActors.knownRetired(entity) && TickThread.isTickThreadFor(world,entity.blockPosition()))
                    || entity.isRemoved() && !ScarpetRetiredActors.knownRetired(entity)) return false;
                try { operation.accept(source,captured); return true; }
                finally { for(Entity entity:related) ScarpetRetiredActors.capture(entity); }
            });
            if(completed) return true;
        }
        throw new InternalExpressionException("Entities kept changing regions during passenger operation");
    }
}
