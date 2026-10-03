package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;

class CarpetLoadedChunkHoldTest {
    private static void field(Object value,String name,Object replacement)throws Exception{var field=value.getClass().getSuperclass();java.lang.reflect.Field target;try{target=value.getClass().getField(name);}catch(NoSuchFieldException missing){target=field.getField(name);}target.setAccessible(true);target.set(value,replacement);}
    @SuppressWarnings("unchecked")
    @Test void missingAndUnloadingChunksNeverReceiveAFullLoadTicket()throws Exception{
        ServerLevel world=mock(ServerLevel.class);ChunkTaskScheduler scheduler=mock(ChunkTaskScheduler.class);ChunkHolderManager manager=mock(ChunkHolderManager.class);TicketType<Long> type=mock(TicketType.class);
        field(scheduler,"chunkHolderManager",manager);field(manager,"ticketLockArea",new ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock(4));field(scheduler,"schedulingLockArea",new ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock(4));when(world.moonrise$getChunkTaskScheduler()).thenReturn(scheduler);
        try(var ticks=mockStatic(TickThread.class)){
            assertFalse(CarpetLoadedChunkHold.tryAdd(world,0,0,type,1L));verify(manager,never()).addTicketAtLevel(any(),anyInt(),anyInt(),anyInt(),any());
            NewChunkHolder holder=mock(NewChunkHolder.class);when(manager.getChunkHolder(0,0)).thenReturn(holder);when(holder.carpetFullStatusSnapshot()).thenReturn(FullChunkStatus.FULL);
            assertFalse(CarpetLoadedChunkHold.tryAdd(world,0,0,type,1L));verify(manager,never()).addTicketAtLevel(any(),anyInt(),anyInt(),anyInt(),any());
            when(holder.getCurrentChunk()).thenReturn(mock(LevelChunk.class));assertTrue(CarpetLoadedChunkHold.tryAdd(world,0,0,type,1L));verify(manager).addTicketAtLevel(type,0,0,33,1L);
            CarpetLoadedChunkHold.remove(world,0,0,type,1L);verify(manager).removeTicketAtLevel(type,0,0,33,1L);
        }
    }
    @Test void regionWorkersNeverWaitForNativeTicketLocks(){try(var ticks=mockStatic(TickThread.class)){ticks.when(TickThread::isTickThread).thenReturn(true);assertThrows(IllegalStateException.class,()->CarpetLoadedChunkHold.tryAdd(mock(ServerLevel.class),0,0,null,1L));}}
}
