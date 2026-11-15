package fun.bm.lophine.carpet;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@AllFeatures
class RuntimeEnchantmentTagsTest {
    private static TagKey<Item> key(String name) {
        return TagKey.create(Registries.ITEM, Identifier.parse(name));
    }

    @SuppressWarnings("unchecked")
    private static HolderSet.Named<Item> tag(TagKey<Item> key, List<Holder<Item>> members) {
        HolderSet.Named<Item> tag = mock(HolderSet.Named.class);
        when(tag.key()).thenReturn(key);
        when(tag.stream()).thenAnswer(unused -> members.stream());
        return tag;
    }

    @SuppressWarnings("unchecked")
    private static Registry<Item> registry() {
        Registry<Item> registry = mock(Registry.class);
        when(registry.key()).thenAnswer(unused -> Registries.ITEM);
        return registry;
    }

    @Test
    void snapshotsOnlyRuntimeEnchantmentTagsWithTheirMembers() {
        Registry<Item> registry = registry();
        ResourceManager resources = mock(ResourceManager.class);
        Holder<Item> item = Holder.direct(Items.STONE);
        TagKey<Item> runtime = key("minecraft:enchant_supported/runtime");
        TagKey<Item> fromPack = key("minecraft:enchant_supported/pack");
        when(registry.listTags()).thenAnswer(unused -> Stream.of(
                tag(runtime, List.of(item)), tag(fromPack, List.of(item)),
                tag(key("minecraft:other"), List.of(item)),
                tag(key("plugin:enchant_supported/other"), List.of(item))));
        when(resources.getResource(any())).thenReturn(Optional.empty());
        when(resources.getResource(Identifier.parse("minecraft:tags/item/enchant_supported/pack.json")))
                .thenReturn(Optional.of(mock(Resource.class)));

        Map<TagKey<Item>, List<Holder<Item>>> saved = RuntimeEnchantmentTags.snapshot(registry, resources);

        assertEquals(Map.of(runtime, List.of(item)), saved);
        assertFalse(saved.containsKey(fromPack), "Disabling a datapack must be able to remove its tags");
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoresMissingMembersWithoutOverwritingNewOrExplicitlyEmptyTags() {
        Registry<Item> registry = registry();
        Holder<Item> oldItem = Holder.direct(Items.STONE);
        Holder<Item> newItem = Holder.direct(Items.DIAMOND_SWORD);
        TagKey<Item> missing = key("minecraft:enchant_supported/missing");
        TagKey<Item> replaced = key("minecraft:enchant_supported/replaced");
        TagKey<Item> empty = key("minecraft:enchant_supported/empty");
        TagKey<Item> unrelated = key("minecraft:unrelated");
        when(registry.listTags()).thenAnswer(unused -> Stream.of(
                tag(replaced, List.of(newItem)), tag(empty, List.of()), tag(unrelated, List.of(newItem))));
        Registry.PendingTags<Item> pending = mock(Registry.PendingTags.class);
        when(registry.prepareTagReload(any())).thenReturn(pending);

        RuntimeEnchantmentTags.restore(registry, Map.of(
                missing, List.of(oldItem), replaced, List.of(oldItem), empty, List.of(oldItem)));

        ArgumentCaptor<TagLoader.LoadResult<Item>> result = ArgumentCaptor.forClass(TagLoader.LoadResult.class);
        verify(registry).prepareTagReload(result.capture());
        assertEquals(Map.of(missing, List.of(oldItem), replaced, List.of(newItem),
                empty, List.of(), unrelated, List.of(newItem)), result.getValue().tags());
        verify(pending).apply();
    }

    @Test
    void doesNotRebindWhenEverySavedTagIsPresent() {
        Registry<Item> registry = registry();
        TagKey<Item> existing = key("minecraft:enchant_supported/existing");
        when(registry.listTags()).thenAnswer(unused -> Stream.of(tag(existing, List.of())));

        RuntimeEnchantmentTags.restore(registry, Map.of(existing, List.of()));

        verify(registry, never()).prepareTagReload(any());
    }

    @Test
    void doesNotTouchRegistryForEmptySnapshot() {
        Registry<Item> registry = registry();

        RuntimeEnchantmentTags.restore(registry, Map.of());

        verify(registry, never()).listTags();
        verify(registry, never()).prepareTagReload(any());
    }

    @Test
    void restoresActualRegistryMembershipAfterTagsAreReplaced() {
        Registry<Item> registry = new MappedRegistry<>(Registries.ITEM, Lifecycle.stable());
        Holder<Item> item = Registry.registerForHolder(registry, Identifier.parse("test:stone"), Items.STONE);
        registry.freeze();
        TagKey<Item> runtime = key("minecraft:enchant_supported/runtime");
        registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM, Map.of(runtime, List.of(item)))).apply();
        ResourceManager resources = mock(ResourceManager.class);
        when(resources.getResource(any())).thenReturn(Optional.empty());
        var saved = RuntimeEnchantmentTags.snapshot(registry, resources);

        registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM, Map.of())).apply();
        assertTrue(registry.get(runtime).isEmpty());
        assertFalse(item.is(runtime));

        RuntimeEnchantmentTags.restore(registry, saved);

        assertEquals(List.of(item), registry.get(runtime).orElseThrow().stream().toList());
        assertTrue(item.is(runtime));
    }
}
