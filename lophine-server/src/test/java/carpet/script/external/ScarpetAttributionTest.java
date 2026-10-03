package carpet.script.external;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.*;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScarpetAttributionTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        for(Item item:new Item[]{Items.STONE,Items.DIAMOND}) try {item.builtInRegistryHolder().components();}
        catch(NullPointerException unbound) {item.builtInRegistryHolder().bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE,64).build());}
    }
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server=mock(MinecraftServer.class);
        final ServerLevel explosionWorld=mock(ServerLevel.class), sourceWorld=mock(ServerLevel.class);
        final LivingEntity source=mock(LivingEntity.class);
        final AtomicReference<ServerLevel> owner=new AtomicReference<>();
        final AtomicReference<ItemStack> hand=new AtomicReference<>(new ItemStack(Items.STONE));
        final AtomicBoolean wet=new AtomicBoolean();
        final ArrayDeque<Consumer<Entity>> sourceTasks=new ArrayDeque<>();
        final MockedStatic<TickThread> ticks=mockStatic(TickThread.class);
        final MockedStatic<MinecraftServer> servers=mockStatic(MinecraftServer.class);
        final MockedStatic<org.bukkit.Bukkit> bukkitServer=mockStatic(org.bukkit.Bukkit.class);
        final MockedStatic<fun.bm.lophine.carpet.CarpetRegionLease> leases=mockStatic(fun.bm.lophine.carpet.CarpetRegionLease.class);
        final boolean previousTool=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tooledTNT;
        int mainHandReads;
        Fixture() throws Exception {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tooledTNT=true;
            servers.when(MinecraftServer::getServer).thenReturn(server);
            CraftServer craft=mock(CraftServer.class);when(craft.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            bukkitServer.when(org.bukkit.Bukkit::getServer).thenReturn(craft);
            var field=MinecraftServer.class.getField("server");field.setAccessible(true);field.set(server,craft);
            var region=mock(io.papermc.paper.threadedregions.scheduler.RegionScheduler.class);when(craft.getRegionScheduler()).thenReturn(region);
            for(ServerLevel world:List.of(explosionWorld,sourceWorld)) {when(world.getServer()).thenReturn(server);when(world.getWorld()).thenReturn(mock(CraftWorld.class));}
            when(source.level()).thenReturn(sourceWorld);when(source.blockPosition()).thenReturn(BlockPos.ZERO);when(source.position()).thenReturn(Vec3.ZERO);
            when(source.getDisplayName()).thenReturn(Component.literal("tool owner"));when(source.getUUID()).thenReturn(UUID.randomUUID());
            when(source.getMainHandItem()).thenAnswer(call->{assertSame(sourceWorld,owner.get());mainHandReads++;return hand.get();});
            when(source.isInWater()).thenAnswer(call->{assertSame(sourceWorld,owner.get());return wet.get();});
            var bukkit=mock(org.bukkit.craftbukkit.entity.CraftLivingEntity.class);when(source.getBukkitEntity()).thenReturn(bukkit);
            var entityScheduler=mock(io.papermc.paper.threadedregions.EntityScheduler.class);
            var schedulerField=CraftEntity.class.getField("taskScheduler");schedulerField.setAccessible(true);schedulerField.set(bukkit,entityScheduler);
            when(entityScheduler.schedule(any(),any(),anyLong())).thenAnswer(call->{sourceTasks.add(call.getArgument(0));return true;});
            ticks.when(()->TickThread.isTickThreadFor(source)).thenAnswer(call->owner.get()==sourceWorld);
            ticks.when(()->TickThread.isTickThreadFor(any(ServerLevel.class),any(BlockPos.class))).thenAnswer(call->owner.get()==call.getArgument(0));
            doAnswer(call->{at(explosionWorld,()->call.<Runnable>getArgument(4).run());return null;}).when(region).execute(any(),any(org.bukkit.World.class),anyInt(),anyInt(),any(Runnable.class));
            leases.when(()->fun.bm.lophine.carpet.CarpetRegionLease.runValue(eq(explosionWorld),anyInt(),anyInt(),anyInt(),anyInt(),any())).thenAnswer(call->{
                var lease=mock(fun.bm.lophine.carpet.CarpetRegionLease.Lease.class);when(lease.ownsAll()).thenAnswer(ignored->owner.get()==explosionWorld);
                Function<fun.bm.lophine.carpet.CarpetRegionLease.Lease<?>,Object> action=call.getArgument(5);
                var result=new AtomicReference<Object>();at(explosionWorld,()->result.set(action.apply(lease)));return CompletableFuture.completedFuture(result.get());
            });
        }
        void at(ServerLevel world,Runnable action) {ServerLevel previous=owner.getAndSet(world);try {action.run();}finally {owner.set(previous);}}
        void tickSource() {var action=sourceTasks.removeFirst();at(sourceWorld,()->action.accept(source));}
        @Override public void close() {fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.tooledTNT=previousTool;ScarpetRuntime.beginShutdown(server,()->{});leases.close();bukkitServer.close();servers.close();ticks.close();}
    }
    @Test void ordinaryWorldExplosionWaitsForCurrentForeignOwnerToolAfterItsAreaIsReady() throws Exception {
        try(Fixture fixture=new Fixture()) {
            var explosion=mock(Explosion.class);when(explosion.getIndirectSourceEntity()).thenReturn(fixture.source);
            var ran=new AtomicBoolean();var finished=new AtomicReference<CompletableFuture<Void>>();
            fixture.at(fixture.explosionWorld,()->finished.set(ScarpetNativeWork.observeNative(null,()->{
                assertTrue(ScarpetWorldExplosions.defer(fixture.explosionWorld,fixture.source,Vec3.ZERO,4,false,Explosion.BlockInteraction.DESTROY,()->{
                    assertSame(fixture.explosionWorld,fixture.owner.get());
                    assertTrue(ScarpetAttribution.explosionTool(explosion).is(Items.DIAMOND));
                    assertEquals(Boolean.TRUE,ScarpetAttribution.inWater(fixture.source));ran.set(true);
                }));return null;
            })));
            assertFalse(finished.get().isDone());assertFalse(ran.get());
            fixture.tickSource(); // resolves source identity, then the full operation area's owner requests a fresh capture
            assertEquals(0,fixture.mainHandReads);
            fixture.hand.set(new ItemStack(Items.DIAMOND));fixture.wet.set(true);
            fixture.tickSource();
            finished.get().get(3,TimeUnit.SECONDS);
            assertTrue(ran.get());assertEquals(1,fixture.mainHandReads);
            assertNull(ScarpetAttribution.inWater(fixture.source));
        }
    }
}
