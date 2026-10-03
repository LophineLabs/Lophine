// SPDX-License-Identifier: LGPL-3.0-only
package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.chunk.LevelChunk;

/** Holds an existing FULL chunk under native ticket -> scheduling lock order; never starts a load. */
public final class CarpetLoadedChunkHold {
    private CarpetLoadedChunkHold() {}
    public static <T> boolean tryAdd(ServerLevel world,int x,int z,TicketType<T> type,T identifier){
        if(TickThread.isTickThread())throw new IllegalStateException("Conditional loaded tickets must be acquired off region/global workers");
        var scheduler=world.moonrise$getChunkTaskScheduler();var manager=scheduler.chunkHolderManager;
        var ticket=manager.ticketLockArea.lock(x,z);
        try{
            var scheduling=scheduler.schedulingLockArea.lock(x,z);
            try{
                var holder=manager.getChunkHolder(x,z);
                if(holder==null||!(holder.getCurrentChunk() instanceof LevelChunk)||!holder.carpetFullStatusSnapshot().isOrAfter(FullChunkStatus.FULL))return false;
                // The same locks exclude holder removal/unload between the check and admission.
                manager.addTicketAtLevel(type,x,z,33,identifier);return true;
            }finally{scheduler.schedulingLockArea.unlock(scheduling);}
        }finally{manager.ticketLockArea.unlock(ticket);}
    }
    public static <T> void remove(ServerLevel world,int x,int z,TicketType<T> type,T identifier){
        if(TickThread.isTickThread())throw new IllegalStateException("Conditional loaded tickets must be released off region/global workers");
        world.moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(type,x,z,33,identifier);
    }
}
