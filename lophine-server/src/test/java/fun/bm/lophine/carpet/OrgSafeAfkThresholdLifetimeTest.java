package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetDamageContinuations;
import carpet.script.external.ScarpetNativeWork;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesmc.leaves.bot.ServerBot;

class OrgSafeAfkThresholdLifetimeTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    private static carpet.script.external.WeakIdentityMap<ServerPlayer,Float> thresholds(OrgPlayerManager manager)throws Exception{
        var field=OrgPlayerManager.class.getDeclaredField("thresholds");field.setAccessible(true);return (carpet.script.external.WeakIdentityMap<ServerPlayer,Float>)field.get(manager);
    }
    private static Map<MinecraftServer,OrgPlayerManager> managers()throws Exception{
        var field=OrgPlayerManager.class.getDeclaredField("MANAGERS");field.setAccessible(true);return (Map<MinecraftServer,OrgPlayerManager>)field.get(null);
    }
    private static OrgPlayerManager install(MinecraftServer server)throws Exception{
        var constructor=OrgPlayerManager.class.getDeclaredConstructor(MinecraftServer.class,CommandBuildContext.class);constructor.setAccessible(true);var manager=constructor.newInstance(server,null);managers().put(server,manager);return manager;
    }
    @Test void theLatestTransientThresholdSurvivesRetirementUntilTheActualDeathReturnCheck()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true);var damage=mockStatic(ScarpetDamageContinuations.class);var afk=mockStatic(OrgSafeAfk.class,CALLS_REAL_METHODS)){
            var player=fixture.target.player();fixture.owner.set(player);var manager=install(fixture.server);
            try{
                var values=thresholds(manager);values.put(player,5F);var source=mock(DamageSource.class);var published=new AtomicReference<CompletableFuture<Boolean>>();var body=new CompletableFuture<Boolean>();var checks=new AtomicInteger();
                damage.when(()->ScarpetDamageContinuations.pendingBodyResult(player)).thenAnswer(call->published.get());
                afk.when(()->OrgSafeAfk.afterDamage(player,source,12F,false)).thenAnswer(call->{assertEquals(7F,OrgPlayerManager.safeThreshold(player));checks.incrementAndGet();return null;});
                var actual=ScarpetNativeWork.observeNative(player,()->OrgSafeAfk.withDamage(player,source,12F,()->{
                    published.set(body);ScarpetNativeWork.record(body);
                    // A real death callback can update the transient per-object threshold before removal.
                    values.put(player,7F);OrgPlayerManager.retired(player);return false;
                }));
                assertEquals(7F,OrgPlayerManager.safeThreshold(player));assertEquals(0,checks.get());assertFalse(actual.isDone());
                fixture.owner.set(null);body.complete(true);assertEquals(0,checks.get());fixture.drain(fixture.target);assertFalse(actual.join());assertEquals(1,checks.get());
            }finally{managers().remove(fixture.server);}
        }
    }
    @Test void aNewFakeObjectWithTheSameNameDoesNotInheritTheRetiredObjectsTransientThreshold()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory,true)){
            var manager=install(fixture.server);
            try{
                var old=fixture.target.player();when(old.getScoreboardName()).thenReturn("same_name");thresholds(manager).put(old,9F);OrgPlayerManager.retired(old);
                var replacement=fixture.actor(ServerBot.class).player();when(replacement.getScoreboardName()).thenReturn("same_name");
                assertEquals(9F,OrgPlayerManager.safeThreshold(old));assertEquals(-1F,OrgPlayerManager.safeThreshold(replacement));
            }finally{managers().remove(fixture.server);}
        }
    }
}
