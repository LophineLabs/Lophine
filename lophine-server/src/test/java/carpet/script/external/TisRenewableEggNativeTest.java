package carpet.script.external;
import fun.bm.lophine.carpet.CarpetRegionLease;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.bukkit.craftbukkit.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class TisRenewableEggNativeTest {
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private record Task(BlockPos position,Entity entity,Runnable body){}
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);final ServerLevel world=mock(ServerLevel.class);
        final CraftServer craft=mock(CraftServer.class);final CraftWorld craftWorld=mock(CraftWorld.class);
        final BlockPos source=new BlockPos(15,60,15),target=new BlockPos(30,60,15);
        final BlockState state=Blocks.DRAGON_EGG.defaultBlockState();final RandomSource random=mock(RandomSource.class);
        final AreaEffectCloud cloud=mock(AreaEffectCloud.class);final List<AreaEffectCloud> clouds=new ArrayList<>();
        final ArrayDeque<Task> tasks=new ArrayDeque<>();final List<String> order=new ArrayList<>();final List<Integer> draws=new ArrayList<>();
        final org.mockito.MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final org.mockito.MockedStatic<CarpetRegionLease> leases=mockStatic(CarpetRegionLease.class);
        final org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit=mockStatic(org.bukkit.Bukkit.class);
        final boolean previous=GeneralCompatConfig.renewableDragonEgg;
        BlockPos owner;Entity running;CompletableFuture<Void> setChild,aliveChild;boolean setResult=false;int xDraw;
        Fixture()throws Exception{
            GeneralCompatConfig.renewableDragonEgg=true;Field f=MinecraftServer.class.getDeclaredField("server");f.setAccessible(true);f.set(server,craft);
            when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            when(world.getServer()).thenReturn(server);when(world.getWorld()).thenReturn(craftWorld);var scheduler=mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);when(craft.getRegionScheduler()).thenReturn(scheduler);
            doAnswer(call->{int x=call.getArgument(2);BlockPos pos=x==(source.getX()>>4)?source:target;tasks.add(new Task(pos,null,call.getArgument(4)));return null;}).when(scheduler).execute(any(),eq(craftWorld),anyInt(),anyInt(),any());
            ticks.when(()->TickThread.isTickThreadFor(eq(world),any(BlockPos.class))).thenAnswer(call->{BlockPos pos=call.getArgument(1);return owner!=null&&(owner.getX()>>4)==(pos.getX()>>4)&&(owner.getZ()>>4)==(pos.getZ()>>4);});
            ticks.when(()->TickThread.isTickThreadFor(any(Entity.class))).thenAnswer(call->running==call.getArgument(0));
            leases.when(()->CarpetRegionLease.runLoadedValue(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),any(Function.class))).thenAnswer(call->CompletableFuture.completedFuture(((Function)call.getArgument(5)).apply(null)));
            bind(cloud,"cloud");clouds.add(cloud);
            when(world.getEntitiesOfClass(eq(AreaEffectCloud.class),any(AABB.class),any())).thenAnswer(call->{assertEquals(source,owner);order.add("query");return clouds;});
            when(random.nextInt(anyInt())).thenAnswer(call->{assertEquals(source,owner);int bound=call.getArgument(0);draws.add(bound);return bound==16&&xDraw++==0?15:0;});
            when(world.getBlockState(target)).thenAnswer(call->{assertEquals(1,owner.getX()>>4);order.add("air");return Blocks.AIR.defaultBlockState();});
            when(world.setBlock(target,state,Block.UPDATE_CLIENTS)).thenAnswer(call->{assertEquals(1,owner.getX()>>4);order.add("set");if(setChild!=null)ScarpetNativeWork.record(setChild);return setResult;});
        }
        void bind(AreaEffectCloud value,String name)throws Exception{
            when(value.level()).thenReturn(world);when(value.blockPosition()).thenReturn(new BlockPos(17,60,15));when(value.position()).thenReturn(new net.minecraft.world.phys.Vec3(17,60,15));
            var api=mock(org.bukkit.craftbukkit.entity.CraftAreaEffectCloud.class);when(value.getBukkitEntity()).thenReturn(api);var scheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);Field f=org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");f.setAccessible(true);f.set(api,scheduler);
            when(scheduler.schedule(any(),any(),eq(1L))).thenAnswer(call->{Consumer<Entity> task=call.getArgument(0);tasks.add(new Task(value.blockPosition(),value,()->task.accept(value)));return true;});
            when(value.isAlive()).thenAnswer(call->{assertSame(value,running);order.add(name+":alive");if(aliveChild!=null&&value==cloud)ScarpetNativeWork.record(aliveChild);return true;});
            when(value.getParticle()).thenAnswer(call->{assertSame(value,running);order.add(name+":particle");return net.minecraft.core.particles.PowerParticleOption.create(ParticleTypes.DRAGON_BREATH,1F);});
            when(value.getRadius()).thenAnswer(call->{assertSame(value,running);order.add(name+":radius");return 3F;});
            doAnswer(call->{assertSame(value,running);assertEquals(.6F,(float)call.getArgument(0),.00001);order.add(name+":shrink");return null;}).when(value).setRadius(anyFloat());
        }
        CompletableFuture<Void> fire()throws Exception{
            owner=source;try{return ScarpetNativeWork.observeNative(null,()->{try{Method m=DragonEggBlock.class.getDeclaredMethod("randomTick",BlockState.class,ServerLevel.class,BlockPos.class,RandomSource.class);m.setAccessible(true);m.invoke(Blocks.DRAGON_EGG,state,world,source,random);}catch(Exception e){throw new RuntimeException(e);}return null;});}finally{owner=null;}
        }
        void drain(){while(!tasks.isEmpty()){Task task=tasks.remove();owner=task.position();running=task.entity();try{task.body().run();}finally{owner=null;running=null;}}}
        @Override public void close(){bukkit.close();leases.close();ticks.close();GeneralCompatConfig.renewableDragonEgg=previous;}
    }
    @Test void actualRandomTickKeepsForeignCloudOriginalRngAndIgnoresFalsePlacementResult()throws Exception{
        try(var f=new Fixture()){f.setChild=new CompletableFuture<>();var raw=f.fire();f.drain();assertEquals(List.of("query","cloud:alive","cloud:particle","air","set"),f.order);assertFalse(raw.isDone());verify(f.cloud,never()).setRadius(anyFloat());f.setChild.complete(null);f.drain();raw.join();assertEquals(List.of(64,1,16,16,8,8,16,16),f.draws);assertEquals("cloud:shrink",f.order.getLast());}
    }
    @Test void originalAliveQueryPredicatesAllFinishBeforeParticleFiltering()throws Exception{
        try(var f=new Fixture()){var second=mock(AreaEffectCloud.class);f.bind(second,"second");f.clouds.add(second);f.aliveChild=new CompletableFuture<>();var raw=f.fire();f.drain();assertEquals(List.of("query","cloud:alive"),f.order);assertEquals(List.of(64),f.draws);f.aliveChild.complete(null);f.drain();raw.join();assertTrue(f.order.indexOf("second:alive")<f.order.indexOf("cloud:particle"));assertEquals(2,f.draws.get(1));}
    }
    @Test void truePlacementNativeFailureBlocksActualCloudTail()throws Exception{
        try(var f=new Fixture()){f.setChild=new CompletableFuture<>();var raw=f.fire();f.drain();f.setChild.completeExceptionally(new IllegalStateException("native"));f.drain();assertFalse(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,raw::join)));verify(f.cloud,never()).getRadius();}
    }
    @Test void guestOnlyPlacementFailurePreservesRawParentAndOriginalCloudRadiusTail()throws Exception{
        try(var f=new Fixture()){f.setChild=new CompletableFuture<>();var raw=f.fire();f.drain();Throwable failure=new IllegalStateException("guest");Method mark=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);mark.setAccessible(true);mark.invoke(null,failure);f.setChild.completeExceptionally(failure);f.drain();assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,raw::join)));verify(f.cloud).setRadius(.6F);}
    }
    @Test void originalDisabledRuleConsumesNoRandomAndPerformsNoQuery()throws Exception{
        try(var f=new Fixture()){GeneralCompatConfig.renewableDragonEgg=false;f.fire().join();assertTrue(f.draws.isEmpty());assertTrue(f.order.isEmpty());}
    }
    @Test void privateActualProducerCannotBeCancelledAndStaysInGlobalDrain()throws Exception{
        try(var f=new Fixture()){f.setChild=new CompletableFuture<>();f.owner=f.source;var actual=ScarpetRenewableDragonEgg.tick(f.state,f.world,f.source,f.random);f.owner=null;assertFalse(actual.cancel(true));f.drain();var drain=ScarpetNativeWork.whenIdle(f.server);assertFalse(drain.isDone());f.setChild.complete(null);f.drain();actual.join();drain.join();verify(f.cloud).setRadius(.6F);}
    }
}
