package fun.bm.lophine.carpet;
import carpet.script.external.*;
import java.util.concurrent.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class CarpetSynchronousExplosionGuestValueTest {
 @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
 static void guest(Throwable failure)throws Exception{var method=ScarpetNativeWork.class.getDeclaredMethod("markGuestFailure",Throwable.class);method.setAccessible(true);method.invoke(null,failure);}
 @Test void alreadyEndedGuestOnlyReceiptReturnsRealSynchronousBooleanAndParentStillFails() throws Exception{
  var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(mock(MinecraftServer.class));var failure=new IllegalStateException("real guest");guest(failure);var value=new java.util.concurrent.atomic.AtomicReference<Boolean>();
  var original=ScarpetNativeWork.observeNative(null,()->{value.set(CarpetSynchronousExplosionScope.run(world,null,()->{ScarpetNativeWork.record(CompletableFuture.failedFuture(failure));return false;}));return null;});assertFalse(value.get());assertTrue(ScarpetNativeWork.onlyGuestFailure(assertThrows(CompletionException.class,original::join)));
 }
 @Test void pendingGuestStillRefusesSyncContractWithoutProvisionalValue(){var world=mock(ServerLevel.class);when(world.getServer()).thenReturn(mock(MinecraftServer.class));var guest=new CompletableFuture<Void>();var refused=assertThrows(CarpetSynchronousExplosionScope.Unavailable.class,()->CarpetSynchronousExplosionScope.run(world,null,()->{ScarpetNativeWork.record(guest);return true;}));assertTrue(refused.nativeStarted());guest.complete(null);}
}
