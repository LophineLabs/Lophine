package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgCustodyDrainCadenceTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){OrgInventoryPersistenceTest.bootstrap();}
    @Test void frozenWorldTimeCannotPreventTheAcceptedUnknownInventoryCustodyRetryFromEnding()throws Exception{
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory, false, true)){
            fixture.unreadable.add(3);fixture.start();fixture.process(fixture.target);assertEquals(3,fixture.reads);assertTrue(fixture.target.inventory().getItem(0).isEmpty());
            when(fixture.viewer.player().level().getGameTime()).thenReturn(17L);when(fixture.target.player().level().getGameTime()).thenReturn(17L);carpet.script.external.ScarpetNativeWork.beginDrain(fixture.server);
            for(int pass=0;pass<10&&!OrgInventoryTransfers.whenAvailable(fixture.server,fixture.viewer.id()).isDone();pass++){
                fixture.owner.set(fixture.target.player());OrgInventoryTransfers.tick(fixture.target.player());fixture.drain(fixture.target);
                fixture.owner.set(fixture.viewer.player());OrgInventoryTransfers.tick(fixture.viewer.player());fixture.drain(fixture.viewer);
            }
            assertTrue(OrgInventoryTransfers.whenAvailable(fixture.server,fixture.viewer.id()).isDone());assertEquals(10,fixture.target.inventory().getItem(0).getCount());assertEquals(20,fixture.viewer.inventory().getItem(0).getCount());
        }
    }
}
