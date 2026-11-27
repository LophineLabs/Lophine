// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition 69264014, copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet.org;

import fun.bm.lophine.carpet.CarpetServerClock;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class OrgLibrarianSearch {
    private OrgLibrarianSearch() {
    }

    enum PriceLevel {
        MIN, LOW, MEDIUM, HIGH;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        int maxPrice(int level, boolean doublePrice) {
            int factor = doublePrice ? 2 : 1;
            int min = (2 + level * 3) * factor;
            int max = (6 + level * 13) * factor;
            int total = max - min + 1;
            return Math.clamp(switch (this) {
                case MIN -> min;
                case LOW -> min + total / 3 - 1;
                case MEDIUM -> min + 2 * total / 3 - 1;
                case HIGH -> max;
            }, 1, 64);
        }
    }

    record Match(Holder<Enchantment> enchantment, int level, int price) {
    }

    static long elapsedSeconds(long startTick) {
        return startTick <= 0 ? 0 : Math.max(0, CarpetServerClock.gameTime() - startTick) / 20L;
    }

    static List<Match> matches(Villager villager, OrgFakePlayerActions.Librarian spec, Holder<Enchantment> selected) {
        var matches = new ArrayList<Match>();
        for (var offer : villager.getOffers()) {
            var enchantments = offer.getResult().get(DataComponents.STORED_ENCHANTMENTS);
            if (enchantments == null) continue;
            int price = offer.getBaseCostA().getCount();
            for (var entry : enchantments.entrySet()) {
                var enchantment = entry.getKey();
                if (selected != null && !selected.equals(enchantment)) continue;
                int maxLevel = enchantment.value().getMaxLevel();
                int minLevel = spec.minLevel() < 0 ? maxLevel : spec.minLevel();
                int maxPrice = spec.priceLevel() == null ? spec.maxPrice()
                        : spec.priceLevel().maxPrice(maxLevel, enchantment.is(EnchantmentTags.DOUBLE_TRADE_PRICE));
                if (entry.getIntValue() >= minLevel && (maxPrice == -1 || price <= maxPrice)) {
                    matches.add(new Match(enchantment, entry.getIntValue(), price));
                }
            }
        }
        return matches;
    }
}
