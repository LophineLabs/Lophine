package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.advancements.predicates.*;
import net.minecraft.advancements.predicates.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.*;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Calls actual Native EntityPredicate/Location/Nbt and saveAsPassenger bodies on explicit distinct owner fixtures. */
public class ScarpetEntityPredicatesTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    private record Task(Entity entity,Consumer<Entity> callback){}
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel caller=mock(ServerLevel.class),foreign=mock(ServerLevel.class);
        final Entity root,child=mock(Entity.class,CALLS_REAL_METHODS);
        final Queue<Task> tasks=new ArrayDeque<>();final List<String> order=new ArrayList<>();
        final Set<ServerLevel> leased=Collections.newSetFromMap(new IdentityHashMap<>());
        final MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final MockedStatic<MinecraftServer> servers=mockStatic(MinecraftServer.class);
        final MockedStatic<org.bukkit.Bukkit> bukkit=mockStatic(org.bukkit.Bukkit.class);
        final MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases=mockStatic(fun.bm.lophine.carpet.CarpetRegionLease.class);
        final ScarpetRuntime runtime;Entity owner;
        Fixture()throws Exception {this(Entity.class);}
        Fixture(Class<? extends Entity> type)throws Exception {
            root=mock(type,CALLS_REAL_METHODS);
            servers.when(MinecraftServer::getServer).thenReturn(server);when(caller.getServer()).thenReturn(server);when(foreign.getServer()).thenReturn(server);
            var craft=mock(CraftServer.class);when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            for(Entity entity:List.of(root,child)) {
                doReturn(entity==root?foreign:caller).when(entity).level();doReturn(new BlockPos(5,4,6)).when(entity).blockPosition();doReturn(new Vec3(5,4,6)).when(entity).position();
                doReturn(RegistryAccess.EMPTY).when(entity).registryAccess();doReturn(entity==root?"minecraft:cow":"minecraft:pig").when(entity).getEncodeId(false);
                entity.persist=true;entity.passengers=ImmutableList.of();
                CraftEntity wrapper=entity instanceof net.minecraft.server.level.ServerPlayer?mock(org.bukkit.craftbukkit.entity.CraftPlayer.class):mock(CraftEntity.class);doReturn(wrapper).when(entity).getBukkitEntity();
                var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);Field f=CraftEntity.class.getField("taskScheduler");f.setAccessible(true);f.set(wrapper,scheduler);
                when(scheduler.schedule(any(),any(),anyLong())).thenAnswer(call->{tasks.add(new Task(entity,call.getArgument(0)));return true;});
            }
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call->{Entity entity=call.getArgument(0);return owner==entity||leased.contains(entity.level());});
            ticks.when(()->TickThread.isTickThreadFor(any(ServerLevel.class),any(BlockPos.class))).thenAnswer(call->leased.contains(call.getArgument(0)));
            leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(any(ServerLevel.class),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
                ServerLevel world=call.getArgument(0);leased.add(world);Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>,Object> action=call.getArgument(5);Object value=action.apply(null);
                if(value instanceof CompletableFuture<?> future)return future.handle((ignored,failure)->{leased.remove(world);if(failure!=null)throw new CompletionException(failure);return value;});
                leased.remove(world);return CompletableFuture.completedFuture(value);
            });
            leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runLoadedValue(any(ServerLevel.class),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
                ServerLevel world=call.getArgument(0);leased.add(world);Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>,Object> action=call.getArgument(5);Object value=action.apply(null);
                if(value instanceof CompletableFuture<?> future)return future.handle((ignored,failure)->{leased.remove(world);if(failure!=null)throw new CompletionException(failure);return value;});
                leased.remove(world);return CompletableFuture.completedFuture(value);
            });
            runtime=ScarpetRuntime.of(server);
        }
        CompletableFuture<Boolean> begin(EntityPredicate predicate){owner=root;try{return predicate.carpetMatchesAsync(caller,Vec3.ZERO,root);}finally{owner=null;}}
        void drain(){Task task;while((task=tasks.poll())!=null){owner=task.entity();try{task.callback().accept(owner);}finally{owner=null;}}}
        @Override public void close(){ScarpetRuntime.beginShutdown(server,()->{});leases.close();bukkit.close();servers.close();ticks.close();}
    }
    @Test void actualOrderedPartsShortCircuitBeforeForeignPassengerAndRetainConstructorSnapshot()throws Exception {
        try(Fixture f=new Fixture()) {
            Map<com.mojang.serialization.Codec<? extends EntitySubPredicate>,EntitySubPredicate> parts=new LinkedHashMap<>();
            parts.put(EntityNbtPredicate.CODEC,(entity,world,origin)->{fail("last NBT part must not execute");return false;});
            parts.put(VehiclePredicate.CODEC,new VehiclePredicate(new EntityPredicate(Map.of())));
            parts.put(EntityTypePredicate.CODEC,(entity,world,origin)->{assertSame(f.root,entity);assertTrue(TickThread.isTickThreadFor(entity));assertSame(f.caller,world);f.order.add("type first");return false;});
            var predicate=new EntityPredicate(parts);parts.clear();var result=f.begin(predicate);f.drain();assertFalse(result.get(3,TimeUnit.SECONDS));assertEquals(List.of("type first"),f.order);
        }
    }
    @Test void actualNestedPassengerPredicatesReadEachOriginalEntityOnItsActualOwner()throws Exception {
        try(Fixture f=new Fixture()) {
            f.root.passengers=ImmutableList.of(f.child);
            EntitySubPredicate actual=(entity,world,origin)->{assertSame(f.child,entity);assertTrue(TickThread.isTickThreadFor(entity));assertSame(f.caller,world);f.order.add("actual child");return true;};
            var nested=new EntityPredicate(Map.of(EntityFlagsPredicate.CODEC,actual));var predicate=new EntityPredicate(Map.of(PassengerPredicate.CODEC,new PassengerPredicate(nested)));
            var result=f.begin(predicate);assertFalse(result.isDone());f.drain();assertTrue(result.get(3,TimeUnit.SECONDS));assertEquals(List.of("actual child"),f.order);
        }
    }
    @Test void asynchronousActualPredicateOwnersRetainOriginalParentAndFlagsWithRealChildOwner()throws Exception {
        try(Fixture f=new Fixture()) {
            f.root.passengers=ImmutableList.of(f.child);var captured=new java.util.concurrent.atomic.AtomicReference<ScarpetNativeWork.Token>();
            var child=new CompletableFuture<Void>();
            EntitySubPredicate actual=(entity,world,origin)->{assertSame(f.child,entity);assertTrue(TickThread.isTickThreadFor(entity));assertSame(f.child,ScarpetNativeWork.capture().owner());assertFalse(ScarpetNativeWork.completionOf(captured.get()).isDone());assertTrue(ScarpetRuntime.FILL_SKIP_UPDATES.get());ScarpetNativeWork.record(child);return true;};
            var nested=new EntityPredicate(Map.of(EntityFlagsPredicate.CODEC,actual));var predicate=new EntityPredicate(Map.of(PassengerPredicate.CODEC,new PassengerPredicate(nested)));
            ScarpetRuntime.FILL_SKIP_UPDATES.set(true);CompletableFuture<CompletableFuture<Boolean>> observed;
            try{observed=ScarpetNativeWork.observeNative(f.root,()->{captured.set(ScarpetNativeWork.capture());return f.begin(predicate);});}finally{ScarpetRuntime.FILL_SKIP_UPDATES.set(false);}
            assertFalse(observed.isDone());f.drain();assertFalse(observed.isDone());child.complete(null);f.drain();assertTrue(observed.thenCompose(value->value).get(3,TimeUnit.SECONDS));assertFalse(ScarpetRuntime.FILL_SKIP_UPDATES.get());
        }
    }
    @Test void actualNativeLocationLightUsesOriginalCallerWorldAndItsLoadedOwnedReadPoint()throws Exception {
        try(Fixture f=new Fixture()) {
            when(f.caller.isLoaded(any())).thenReturn(true);
            when(f.caller.getMaxLocalRawBrightness(any())).thenAnswer(call->{assertTrue(f.leased.contains(f.caller));assertFalse(f.leased.contains(f.foreign));assertEquals(new BlockPos(5,4,6),call.getArgument(0));f.order.add("original world light");return 9;});
            var location=LocationPredicate.Builder.location().setLight(LightPredicate.Builder.light().setComposite(MinMaxBounds.Ints.exactly(9))).build();
            var predicate=new EntityPredicate(Map.of(EntityLocationPredicate.CODEC,new EntityLocationPredicate(location)));var result=f.begin(predicate);f.drain();assertTrue(result.get(3,TimeUnit.SECONDS));
            assertEquals(List.of("original world light"),f.order);verify(f.foreign,never()).getMaxLocalRawBrightness(any());
        }
    }
    @Test void actualUnloadedLocationCannotBecomeLoadedBecauseOfOwnershipTicketsAndKeepsSkyException()throws Exception {
        try(Fixture f=new Fixture()) {
            when(f.caller.isLoaded(any())).thenReturn(false);when(f.caller.canSeeSky(any())).thenReturn(true);
            var location=LocationPredicate.Builder.location().setLight(LightPredicate.Builder.light()).build();var predicate=new EntityPredicate(Map.of(EntityLocationPredicate.CODEC,new EntityLocationPredicate(location)));
            var result=f.begin(predicate);f.drain();assertFalse(result.get(3,TimeUnit.SECONDS));
            var sky=LocationPredicate.Builder.location().setCanSeeSky(true).build();var skyPredicate=new EntityPredicate(Map.of(EntityLocationPredicate.CODEC,new EntityLocationPredicate(sky)));
            var skyResult=f.begin(skyPredicate);f.drain();assertTrue(skyResult.get(3,TimeUnit.SECONDS));
            f.leases.verify(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(any(ServerLevel.class),anyInt(),anyInt(),anyInt(),anyInt(),any()),never());
            verify(f.caller,never()).getMaxLocalRawBrightness(any());
        }
    }
    @Test void actualNativePassengerSaveRunsOnChildOwnerAndParentEmitsTheSameOriginalChildTag()throws Exception {
        try(Fixture f=new Fixture()) {
            f.root.passengers=ImmutableList.of(f.child);
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(f.child));f.order.add("actual child eligibility");return "minecraft:pig";}).when(f.child).getEncodeId(false);
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(f.child));f.order.add("actual child save body");ValueOutput output=call.getArgument(0);output.putInt("marker",37);return null;}).when(f.child).saveWithoutId(any(),eq(true),eq(false),eq(false));
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(f.root));assertFalse(TickThread.isTickThreadFor(f.child));f.order.add("actual parent save body");ValueOutput output=call.getArgument(0);output.putInt("marker",1);
                var children=output.childrenList("Passengers");for(Entity child:f.root.getPassengers())assertTrue(child.saveAsPassenger(children.addChild(),true,false,false));return null;
            }).when(f.root).saveWithoutId(any(),eq(true),eq(false),eq(false));
            CompoundTag child=new CompoundTag();child.putString("id","minecraft:pig");child.putInt("marker",37);ListTag passengers=new ListTag();passengers.add(child);CompoundTag expected=new CompoundTag();expected.putInt("marker",1);expected.put("Passengers",passengers);
            var predicate=new EntityPredicate(Map.of(EntityNbtPredicate.CODEC,new EntityNbtPredicate(new NbtPredicate(expected))));var result=f.begin(predicate);assertFalse(result.isDone());f.drain();assertTrue(result.get(3,TimeUnit.SECONDS));
            assertEquals(List.of("actual child eligibility","actual child save body","actual parent save body"),f.order);verify(f.child,times(1)).getEncodeId(false);
        }
    }
    @Test void theActualPlayerPrefixNestedLookingPredicateLineOfSightAndInputKeepTheirSourceOrder()throws Exception {
        playerLooking(true);
    }
    @Test void aFalseActualNestedLookingPredicateDoesNotExecuteLineOfSightOrInput()throws Exception {
        playerLooking(false);
    }
    private void playerLooking(boolean nestedPass)throws Exception {
        try(Fixture f=new Fixture(net.minecraft.server.level.ServerPlayer.class);var rays=mockStatic(net.minecraft.world.entity.projectile.ProjectileUtil.class)) {
            var player=(net.minecraft.server.level.ServerPlayer)f.root;
            doReturn(f.foreign).when(f.child).level();doReturn(new net.minecraft.world.food.FoodData()).when(player).getFoodData();doReturn(net.minecraft.world.level.GameType.SURVIVAL).when(player).gameMode();
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(player));f.order.add("native prefix stats");return mock(net.minecraft.stats.ServerStatsCounter.class);}).when(player).getStats();
            doReturn(mock(net.minecraft.stats.ServerRecipeBook.class)).when(player).getRecipeBook();
            doReturn(new Vec3(5,5,6)).when(player).getEyePosition();doReturn(new Vec3(1,0,0)).when(player).getViewVector(1F);
            rays.when(()->net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(eq(f.foreign),eq(player),any(),any(),any(),any(),eq(0F))).thenAnswer(call->{assertTrue(f.leased.contains(f.foreign));f.order.add("native ray envelope");return new net.minecraft.world.phys.EntityHitResult(f.child);});
            EntitySubPredicate actual=(entity,world,origin)->{assertSame(f.child,entity);assertTrue(TickThread.isTickThreadFor(entity));assertSame(f.foreign,world);f.order.add("actual looking predicate");return nestedPass;};
            var nested=new EntityPredicate(Map.of(EntityFlagsPredicate.CODEC,actual));
            doAnswer(call->{assertTrue(TickThread.isTickThreadFor(player));assertTrue(TickThread.isTickThreadFor(f.child));f.order.add("native line of sight envelope");return true;}).when(player).hasLineOfSight(f.child);
            var input=mock(InputPredicate.class);when(input.matches(any())).thenAnswer(call->{assertTrue(TickThread.isTickThreadFor(player));f.order.add("input last");return true;});
            var playerPredicate=new PlayerPredicate(MinMaxBounds.Ints.ANY,FoodPredicate.ANY,GameTypePredicate.ANY,List.of(),it.unimi.dsi.fastutil.objects.Object2BooleanMaps.emptyMap(),Map.of(),Optional.of(nested),Optional.of(input));
            var predicate=new EntityPredicate(Map.of(PlayerPredicate.CODEC,playerPredicate));var result=f.begin(predicate);f.drain();assertEquals(nestedPass,result.get(3,TimeUnit.SECONDS));
            assertEquals(nestedPass?List.of("native prefix stats","native ray envelope","actual looking predicate","native line of sight envelope","input last"):List.of("native prefix stats","native ray envelope","actual looking predicate"),f.order);
        }
    }
}
