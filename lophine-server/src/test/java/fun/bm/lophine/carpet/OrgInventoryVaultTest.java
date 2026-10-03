package fun.bm.lophine.carpet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import carpet.script.external.ScarpetPlayerInventoryGate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrgInventoryVaultTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { OrgInventoryPersistenceTest.bootstrap(); }
    private static final class FilesActor {
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        FilesActor(OrgInventoryPersistenceTest.Fixture fixture) throws Exception {
            var field = fixture.coordinator.getClass().getDeclaredField("fileActors"); field.setAccessible(true);
            field.set(fixture.coordinator, (java.util.concurrent.Executor) work::add);
        }
        void run() { while (!work.isEmpty()) work.remove().run(); }
    }
    private OrgInventoryTransfers.Vault vault(Path file, CompoundTag before, CompoundTag after) {
        return new OrgInventoryTransfers.Vault(UUID.nameUUIDFromBytes(file.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), directory.relativize(file).toString(), false, before, after);
    }
    private static CompoundTag stock(int value) { var tag=new CompoundTag();tag.putInt("items",value);return tag; }
    private static void submit(OrgInventoryPersistenceTest.Fixture fixture, OrgInventoryTransfers.Vault vault, java.util.function.Consumer<Boolean> settled) {
        fixture.owner.set(fixture.viewer.player());
        var before=OrgInventoryTransfers.viewerState(fixture.viewer.player());var after=OrgInventoryTransfers.copies(before);after.set(0,ItemStack.EMPTY);
        assertTrue(OrgInventoryTransfers.submitVault(fixture.viewer.player(),vault,List.of(ItemStack.EMPTY),List.of(new ItemStack(Items.DIAMOND,10)),before,after,List.of(),settled));
        fixture.owner.set(null);
    }
    @Test void reservationWaitsForAnAcceptedHandReplacementAndRejectsItsStaleInput() throws Exception {
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)) {
            var files=new FilesActor(fixture);Path file=directory.resolve("express/1.nbt");java.nio.file.Files.createDirectories(file.getParent());NbtIo.write(stock(0),file);
            var tail=new CompletableFuture<Void>();fixture.owner.set(fixture.viewer.player());ScarpetPlayerInventoryGate.trackAccepted(fixture.viewer.player(),tail);
            var cancelled=new AtomicBoolean();submit(fixture,vault(file,stock(0),stock(10)),success->cancelled.set(!success));fixture.process(fixture.viewer);
            assertTrue(fixture.viewer.inventory().getItem(0).is(Items.DIAMOND));assertEquals(10,fixture.viewer.inventory().getItem(0).getCount());
            fixture.owner.set(fixture.viewer.player());fixture.viewer.inventory().setItem(0,new ItemStack(Items.GOLD_INGOT,3));tail.complete(null);fixture.owner.set(null);
            fixture.process(fixture.viewer);files.run();assertTrue(cancelled.get());assertEquals(0,NbtIo.read(file).getIntOr("items",-1));
            assertTrue(fixture.viewer.inventory().getItem(0).is(Items.GOLD_INGOT));assertEquals(3,fixture.viewer.inventory().getItem(0).getCount());
        }
    }
    @Test void anUnknownFileReadbackCannotRefundAlreadyWrittenParcelStock() throws Exception {
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)) {
            var files=new FilesActor(fixture);Path file=directory.resolve("express/1.nbt");java.nio.file.Files.createDirectories(file.getParent());NbtIo.write(stock(0),file);
            var complete=new AtomicBoolean();submit(fixture,vault(file,stock(0),stock(10)),complete::set);fixture.process(fixture.viewer);
            var unknown=new AtomicBoolean(true);
            try(var io=mockStatic(NbtIo.class,CALLS_REAL_METHODS)) {
                io.when(()->NbtIo.read(file)).thenAnswer(call->{CompoundTag actual=(CompoundTag)call.callRealMethod();if(actual.getIntOr("items",0)==10&&unknown.getAndSet(false))throw new IOException("readback temporarily unavailable");return actual;});
                files.run();assertFalse(complete.get());assertTrue(fixture.viewer.inventory().getItem(0).isEmpty());assertEquals(10,NbtIo.read(file).getIntOr("items",-1));
                fixture.viewer.inventory().setItem(0,new ItemStack(Items.GOLD_INGOT,3));fixture.process(fixture.viewer);files.run();fixture.process(fixture.viewer);
                assertTrue(complete.get());assertEquals(10,NbtIo.read(file).getIntOr("items",-1));assertTrue(fixture.viewer.inventory().getItem(0).is(Items.GOLD_INGOT));assertEquals(3,fixture.viewer.inventory().getItem(0).getCount());
                assertTrue(fixture.viewer.inventory().getNonEquipmentItems().stream().noneMatch(stack->stack.is(Items.DIAMOND)));
            }
        }
    }
    @Test void aVerifiedFileActorCanPublishWhileItsOfflineSourceStillHasEscrowCustody() throws Exception {
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)) {
            var files=new FilesActor(fixture);Path file=directory.resolve("express/1.nbt");java.nio.file.Files.createDirectories(file.getParent());NbtIo.write(stock(0),file);
            var vault=vault(file,stock(0),stock(10));submit(fixture,vault,success->{});fixture.process(fixture.viewer);
            when(fixture.server.getPlayerList().getPlayer(fixture.viewer.id())).thenReturn(null);files.run();
            assertTrue(OrgInventoryTransfers.whenAvailable(fixture.server,vault.participant()).isDone());assertFalse(OrgInventoryTransfers.whenAvailable(fixture.server,fixture.viewer.id()).isDone());
            var published=OrgInventoryTransfers.updateVaultMetadata(fixture.server,vault.participant(),vault.relativePath(),false,tag->{tag.putBoolean("published",true);return tag;});files.run();published.join();
            assertEquals(10,NbtIo.read(file).getIntOr("items",-1));assertTrue(NbtIo.read(file).getBooleanOr("published",false));
            when(fixture.server.getPlayerList().getPlayer(fixture.viewer.id())).thenReturn(fixture.viewer.player());fixture.process(fixture.viewer);
            assertTrue(OrgInventoryTransfers.whenAvailable(fixture.server,fixture.viewer.id()).isDone());
        }
    }
    @Test void metadataOnlyWorkCannotChangeAssetsAndReleasesItsFileLeaseOnRejection() throws Exception {
        try(var fixture=new OrgInventoryPersistenceTest.Fixture(directory)) {
            var files=new FilesActor(fixture);Path file=directory.resolve("express/1.nbt");java.nio.file.Files.createDirectories(file.getParent());NbtIo.write(stock(9),file);
            var vault=vault(file,stock(9),stock(9));var rejected=OrgInventoryTransfers.updateVaultMetadata(fixture.server,vault.participant(),vault.relativePath(),false,tag->{tag.putInt("items",18);return tag;});files.run();
            assertTrue(rejected.isCompletedExceptionally());assertEquals(9,NbtIo.read(file).getIntOr("items",-1));assertTrue(OrgInventoryTransfers.whenAvailable(fixture.server,vault.participant()).isDone());
        }
    }
}
