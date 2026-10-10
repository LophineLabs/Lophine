package fun.bm.lophine.carpet;

import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

class OrgMailMultiplePersistenceTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrap() {
        OrgInventoryPersistenceTest.bootstrap();
    }

    private OrgMailPersistenceTest.Fixture fixture() throws Exception {
        var test = new OrgMailPersistenceTest();
        test.directory = directory;
        return test.new Fixture();
    }

    private static OrgMailDraftMenu open(OrgMailPersistenceTest.Fixture fixture) throws Exception {
        var menu = new AtomicReference<OrgMailDraftMenu>();
        var player = fixture.actors.viewer.player();
        when(player.openMenu(any(MenuProvider.class))).thenAnswer(call -> {
            MenuProvider provider = call.getArgument(0);
            OrgMailDraftMenu created = (OrgMailDraftMenu) provider.createMenu(9, fixture.actors.viewer.inventory(), player);
            menu.set(created);
            player.containerMenu = created;
            return java.util.OptionalInt.of(9);
        });
        var opened = fixture.mail.multiple(player, new NameAndId(fixture.actors.target.id(), "recipient"));
        fixture.pump();
        assertTrue(opened.join());
        return menu.get();
    }

    @Test
    void actualVanillaTwentySevenSlotSwapIsDurableAndClosingPublishesTheParcel() throws Exception {
        try (var fixture = fixture()) {
            AbstractContainerMenu previous = fixture.actors.viewer.player().containerMenu;
            OrgMailDraftMenu menu = open(fixture);
            fixture.actors.owner.set(menu.player);
            menu.clicked(0, 0, ContainerInput.SWAP, menu.player);
            fixture.pump();
            assertTrue(fixture.actors.viewer.inventory().getItem(0).isEmpty());
            assertEquals(10, menu.top.getItem(0).getCount());
            var draft = NbtIo.read(fixture.parcel(1));
            assertTrue(draft.getBooleanOr("_lophine_draft", false));
            assertEquals(10, OrgMailService.count(OrgMailService.items(fixture.actors.server, draft, fixture.actors.lookup.createSerializationContext(NbtOps.INSTANCE))));
            fixture.actors.owner.set(menu.player);
            menu.removed(menu.player);
            menu.player.containerMenu = previous;
            fixture.pump();
            var sent = NbtIo.read(fixture.parcel(1));
            assertFalse(sent.getBooleanOr("_lophine_draft", false));
            assertFalse(sent.contains("draft_slots"));
            assertEquals(10, OrgMailService.count(OrgMailService.items(fixture.actors.server, sent, fixture.actors.lookup.createSerializationContext(NbtOps.INSTANCE))));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void pointerSwapRetainsAliasesAndCloseCopiesTheNewestCanonicalCountWithoutZeroingTheSource() throws Exception {
        try (var fixture = fixture()) {
            var original = fixture.actors.viewer.inventory().getItem(0);
            UUID group = OrgItemShadowGroups.share(original);
            fixture.actors.viewer.inventory().setItem(1, original);
            AbstractContainerMenu previous = fixture.actors.viewer.player().containerMenu;
            OrgMailDraftMenu menu = open(fixture);
            fixture.actors.owner.set(menu.player);
            menu.clicked(0, 0, ContainerInput.SWAP, menu.player);
            fixture.pump();
            assertEquals(group, menu.top.getItem(0).carpetOrgShadowId);
            assertEquals(10, original.getCount());
            var old = OrgShadowInventoryCodec.descriptor(original, fixture.actors.lookup.createSerializationContext(NbtOps.INSTANCE));
            original.shrink(3);
            assertEquals(7, menu.top.getItem(0).getCount());
            fixture.actors.owner.set(menu.player);
            menu.removed(menu.player);
            menu.player.containerMenu = previous;
            fixture.pump();
            assertEquals(7, original.getCount());
            assertEquals(7, OrgMailService.count(OrgMailService.items(fixture.actors.server, NbtIo.read(fixture.parcel(1)), fixture.actors.lookup.createSerializationContext(NbtOps.INSTANCE))));
            var index = OrgItemShadowGroups.class.getDeclaredField("GROUPS");
            index.setAccessible(true);
            ((Map<UUID, ?>) index.get(null)).remove(group);
            ItemStack stale = OrgShadowInventoryCodec.descriptor(old, fixture.actors.lookup.createSerializationContext(NbtOps.INSTANCE));
            ItemStack restored = OrgItemShadowGroups.restoreCustody(fixture.actors.server, stale);
            assertNotNull(restored);
            assertEquals(7, restored.getCount());
            assertNull(restored.carpetOrgShadowId);
        }
    }

    @Test
    void draftPublicationUsesTheRealFileActorWithoutRequiringAnOnlineSender() throws Exception {
        try (var fixture = fixture()) {
            OrgMailDraftMenu menu = open(fixture);
            fixture.actors.owner.set(menu.player);
            menu.clicked(0, 0, ContainerInput.SWAP, menu.player);
            fixture.pump();
            when(fixture.actors.server.getPlayerList().getPlayer(fixture.actors.viewer.id())).thenReturn(null);
            when(fixture.actors.server.getPlayerList().getPlayerByName("sender")).thenReturn(null);
            var published = fixture.mail.publishDraft(1);
            fixture.fileWork();
            assertTrue(published.join());
            assertFalse(NbtIo.read(fixture.parcel(1)).getBooleanOr("_lophine_draft", false));
            assertEquals(10, OrgMailService.count(OrgMailService.items(fixture.actors.server, NbtIo.read(fixture.parcel(1)), fixture.actors.lookup.createSerializationContext(NbtOps.INSTANCE))));
        }
    }
}
