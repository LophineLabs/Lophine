package fun.bm.lophine.carpet;

import com.mojang.serialization.DynamicOps;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Server-private identity descriptors sit beside vanilla item NBT, outside client components.
 */
final class OrgShadowInventoryCodec {
    private OrgShadowInventoryCodec() {
    }

    static CompoundTag descriptor(ItemStack value, DynamicOps<Tag> ops) {
        return descriptor(value, ops, true);
    }

    static CompoundTag descriptor(ItemStack value, DynamicOps<Tag> ops, boolean receipts) {
        ItemStack stack = OrgItemShadowGroups.managed(value) ? OrgItemShadowGroups.snapshot(value) : value;
        if (stack.carpetOrgShadowId == null)
            throw new IllegalArgumentException("An item shadow descriptor needs explicit identity");
        CompoundTag tag = new CompoundTag();
        tag.putString("id", stack.carpetOrgShadowId.toString());
        tag.putLong("revision", stack.carpetOrgShadowRevision);
        tag.putString("item", BuiltInRegistries.ITEM.getKey(stack.carpetOrgOriginalHolder().value()).toString());
        tag.putInt("count", stack.carpetOrgOriginalCount());
        tag.putInt("pop", stack.getPopTime());
        tag.put("patch", DataComponentPatch.CODEC.encodeStart(ops, stack.components.asPatch()).getOrThrow());
        ListTag completed = new ListTag();
        if (receipts)
            OrgItemShadowGroups.completedReceipts(stack).stream().sorted().forEach(id -> completed.add(net.minecraft.nbt.StringTag.valueOf(id.toString())));
        if (!completed.isEmpty()) tag.put("completed", completed);
        return tag;
    }

    static ItemStack descriptor(CompoundTag tag, DynamicOps<Tag> ops) {
        return descriptor(tag, ops, true);
    }

    static ItemStack descriptor(CompoundTag tag, DynamicOps<Tag> ops, boolean receipts) {
        UUID id = UUID.fromString(tag.getStringOr("id", ""));
        long revision = tag.getLongOr("revision", -1L);
        if (revision < 0) throw new IllegalArgumentException("An item shadow revision cannot be negative");
        ItemStack stack = plainDescriptor(tag, ops);
        var completed = new HashSet<UUID>();
        for (Tag receipt : receipts ? tag.getListOrEmpty("completed") : new ListTag()) {
            UUID transaction = UUID.fromString(receipt.asString().orElseThrow());
            if (!completed.add(transaction))
                throw new IllegalArgumentException("Duplicate item shadow completion receipt");
        }
        return OrgItemShadowGroups.restore(id, revision, stack, completed);
    }

    static ItemStack plainDescriptor(CompoundTag tag, DynamicOps<Tag> ops) {
        var item = BuiltInRegistries.ITEM.get(Identifier.parse(tag.getStringOr("item", ""))).orElseThrow();
        ItemStack stack = new ItemStack(item, tag.getIntOr("count", 0), DataComponentPatch.CODEC.parse(ops, Objects.requireNonNull(tag.get("patch"), "patch")).getOrThrow());
        stack.setPopTime(tag.getIntOr("pop", 0));
        return stack;
    }

    static void stacks(CompoundTag tag, String key, List<ItemStack> items, DynamicOps<Tag> ops) {
        tag.put(key, ItemStack.OPTIONAL_CODEC.listOf().encodeStart(ops, items).getOrThrow());
        ListTag shadows = new ListTag();
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            if (stack.carpetOrgShadowId != null) {
                CompoundTag identity = descriptor(stack, ops, false);
                identity.putInt("slot", slot);
                shadows.add(identity);
            }
        }
        if (!shadows.isEmpty()) tag.put(key + "_shadows", shadows);
    }

    static List<ItemStack> stacks(CompoundTag tag, String key, DynamicOps<Tag> ops) {
        List<ItemStack> items = new ArrayList<>(ItemStack.OPTIONAL_CODEC.listOf().parse(ops, Objects.requireNonNull(tag.get(key), key)).getOrThrow());
        var slots = new HashSet<Integer>();
        var identities = new java.util.HashMap<UUID, ItemStack>();
        for (Tag value : tag.getListOrEmpty(key + "_shadows")) {
            if (!(value instanceof CompoundTag identity))
                throw new IllegalArgumentException("Malformed item shadow descriptor");
            int slot = identity.getIntOr("slot", -1);
            if (slot < 0 || slot >= items.size() || !slots.add(slot))
                throw new IllegalArgumentException("Malformed item shadow slot");
            ItemStack restored = descriptor(identity, ops, false);
            ItemStack previous = identities.putIfAbsent(restored.carpetOrgShadowId, restored);
            if (previous != null) {
                if (previous.carpetOrgShadowRevision != restored.carpetOrgShadowRevision || previous.carpetOrgOriginalCount() != restored.carpetOrgOriginalCount()
                        || !previous.carpetOrgOriginalHolder().equals(restored.carpetOrgOriginalHolder()) || !previous.components.equals(restored.components))
                    throw new IllegalArgumentException("Conflicting item shadow descriptors");
                restored = previous;
            }
            items.set(slot, restored);
        }
        return items;
    }
}
