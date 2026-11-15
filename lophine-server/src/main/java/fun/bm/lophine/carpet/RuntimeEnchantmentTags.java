package fun.bm.lophine.carpet;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Preserves code-bound Aiyatsbus item tags across a resource reload. */
public final class RuntimeEnchantmentTags {
    private RuntimeEnchantmentTags() {
    }

    public static Map<TagKey<Item>, List<Holder<Item>>> snapshot(Registry<Item> registry, ResourceManager resources) {
        Map<TagKey<Item>, List<Holder<Item>>> tags = new HashMap<>();
        registry.listTags().forEach(tag -> {
            Identifier id = tag.key().location();
            if (!id.getNamespace().equals("minecraft") || !id.getPath().startsWith("enchant_supported/")) return;
            Identifier resource = Identifier.fromNamespaceAndPath(id.getNamespace(), "tags/item/" + id.getPath() + ".json");
            // Datapack-owned tags must still be removable by disabling or editing their pack.
            if (resources.getResource(resource).isEmpty()) tags.put(tag.key(), List.copyOf(tag.stream().toList()));
        });
        return Map.copyOf(tags);
    }

    public static void restore(Registry<Item> registry, Map<TagKey<Item>, List<Holder<Item>>> saved) {
        if (saved.isEmpty()) return;
        Map<TagKey<Item>, List<Holder<Item>>> tags = new HashMap<>();
        registry.listTags().forEach(tag -> tags.put(tag.key(), tag.stream().toList()));
        boolean changed = false;
        for (var entry : saved.entrySet()) {
            // The freshly loaded tag (including an explicitly empty one) takes precedence.
            if (!tags.containsKey(entry.getKey())) {
                tags.put(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (changed) registry.prepareTagReload(new TagLoader.LoadResult<>(registry.key(), tags)).apply();
    }
}
