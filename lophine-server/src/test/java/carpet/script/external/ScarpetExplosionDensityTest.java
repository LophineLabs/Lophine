package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import org.bukkit.craftbukkit.*;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native collision sampler evaluates contextual PowderSnow with the original entity on its foreign owner. */
public class ScarpetExplosionDensityTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        try { Items.LEATHER_BOOTS.builtInRegistryHolder().components(); }
        catch(NullPointerException missing) { Items.LEATHER_BOOTS.builtInRegistryHolder().bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE,1).build()); }
    }
    @Test void oldWorldCollisionUsesActualForeignOwnerEquipmentAndDoesNotCacheTheContextualShape() throws Exception {
        var server=mock(MinecraftServer.class); var world=mock(ServerLevel.class); var targetWorld=mock(ServerLevel.class);
        var target=mock(LivingEntity.class); var explosion=mock(ServerExplosion.class);
        var owner=new AtomicReference<ServerLevel>(); var boots=new AtomicReference<>(new ItemStack(Items.LEATHER_BOOTS));
        var shapeCache=new AtomicInteger();
        try(var bukkitServer=mockStatic(org.bukkit.Bukkit.class);var ticks=mockStatic(TickThread.class); var leases=fun.bm.lophine.carpet.CarpetOwnedPhaseFixture.open()) {
            CraftServer craft=mock(CraftServer.class);when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());bukkitServer.when(org.bukkit.Bukkit::getServer).thenReturn(craft); var sf=MinecraftServer.class.getField("server"); sf.setAccessible(true); sf.set(server,craft);
            when(world.getServer()).thenReturn(server); when(world.getWorld()).thenReturn(mock(CraftWorld.class));
            var region=mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);when(craft.getRegionScheduler()).thenReturn(region);
            Consumer<Runnable> atWorld=action->{var previous=owner.getAndSet(world);try{action.run();}finally{owner.set(previous);}};
            Consumer<Runnable> atTarget=action->{var previous=owner.getAndSet(targetWorld);try{action.run();}finally{owner.set(previous);}};
            doAnswer(call->{atWorld.accept(call.getArgument(4));return null;}).when(region).execute(any(),any(org.bukkit.World.class),anyInt(),anyInt(),any(Runnable.class));
            var bukkit=mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class);when(target.getBukkitEntity()).thenReturn(bukkit);
            var entityScheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var schedulerField=CraftEntity.class.getField("taskScheduler");schedulerField.setAccessible(true);schedulerField.set(bukkit,entityScheduler);
            when(entityScheduler.schedule(any(),any(),anyLong())).thenAnswer(call->{atTarget.accept(()->call.<Consumer<Entity>>getArgument(0).accept(target));return true;});
            ticks.when(()->TickThread.isTickThreadFor(target)).thenAnswer(call->owner.get()==targetWorld);
            ticks.when(()->TickThread.isTickThreadFor(eq(world),any(BlockPos.class))).thenAnswer(call->owner.get()==world);
            leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
                var result=new AtomicReference<Object>(); Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>,Object> action=call.getArgument(5);
                atWorld.accept(()->result.set(action.apply(null)));return CompletableFuture.completedFuture(result.get());
            });
            when(target.level()).thenReturn(targetWorld);when(target.blockPosition()).thenReturn(new BlockPos(0,1,0));
            when(target.getBoundingBox()).thenAnswer(call->{assertSame(targetWorld,owner.get());return new AABB(.2,1.01,.2,.8,1.8,.8);});
            when(target.getY()).thenAnswer(call->{assertSame(targetWorld,owner.get());return 1.01;});
            when(target.getMainHandItem()).thenAnswer(call->{assertSame(targetWorld,owner.get());return ItemStack.EMPTY;});
            when(target.getItemBySlot(EquipmentSlot.FEET)).thenAnswer(call->{assertSame(targetWorld,owner.get());return boots.get();});
            when(explosion.carpetCachedDensity(any())).thenReturn(null);when(explosion.level()).thenReturn(world);when(explosion.center()).thenReturn(new Vec3(.5,-2,.5));
            when(explosion.carpetCollisionEntry(any())).thenAnswer(call->{assertSame(world,owner.get());BlockPos pos=call.getArgument(0);
                return new ScarpetExplosionDensity.Entry(pos.equals(BlockPos.ZERO)?Blocks.POWDER_SNOW.defaultBlockState():Blocks.VOID_AIR.defaultBlockState(),null);
            });
            doAnswer(call->{assertSame(world,owner.get());Map<Long,?> shapes=call.getArgument(2);shapeCache.addAndGet(shapes.containsKey(BlockPos.ZERO.asLong())?1:0);return null;}).when(explosion).carpetStoreDensity(any(),anyFloat(),anyMap());
            float protectedExposure=ScarpetExplosionDensity.compute(explosion,target).get(3,TimeUnit.SECONDS);
            assertTrue(protectedExposure<1F,"leather boots use PowderSnow's real collision surface");
            boots.set(ItemStack.EMPTY);
            float barefootExposure=ScarpetExplosionDensity.compute(explosion,target).get(3,TimeUnit.SECONDS);
            assertEquals(1F,barefootExposure);assertEquals(0,shapeCache.get(),"entity-delegated PowderSnow shape must not enter the Paper constant shape cache");
        }
    }
}
