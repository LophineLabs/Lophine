/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package org.leavesmc.leaves.util;

import fun.bm.lophine.carpet.config.modules.WoolHopperCounterConfig;
import fun.bm.lophine.utils.ServerI18nUtil;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static java.util.Map.entry;

// Powered by fabric-carpet(https://github.com/gnembon/fabric-carpet)

public class HopperCounter {

    private static volatile boolean enabled = false;
    private static final Map<DyeColor, HopperCounter> COUNTERS;

    static {
        EnumMap<DyeColor, HopperCounter> counterMap = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values()) {
            counterMap.put(color, new HopperCounter(color));
        }
        COUNTERS = Collections.unmodifiableMap(counterMap);
    }

    public final DyeColor color;
    private final TextComponent coloredName;
    private final Object2LongMap<Item> counter = new Object2LongLinkedOpenHashMap<>();
    private long startTick;
    private long startMillis;

    private HopperCounter(DyeColor color) {
        this.startTick = -1;
        this.color = color;
        this.coloredName = Component.text(color.getName(), TextColor.color(color.getTextColor()));
    }

    public synchronized void add(MinecraftServer server, ItemStack stack) {
        if (startTick < 0) {
            startTick = fun.bm.lophine.carpet.CarpetServerClock.gameTime();
            startMillis = System.currentTimeMillis();
        }
        Item item = stack.getItem();
        counter.put(item, counter.getLong(item) + stack.getCount());
    }

    public synchronized void reset(MinecraftServer server) {
        counter.clear();
        startTick = fun.bm.lophine.carpet.CarpetServerClock.gameTime();
        startMillis = System.currentTimeMillis();
    }

    public static void resetAll(MinecraftServer server, boolean fresh) {
        for (HopperCounter counter : COUNTERS.values()) {
            synchronized (counter) {
                counter.reset(server);
                if (fresh) counter.startTick = -1;
            }
        }
    }

    public synchronized List<Component> format(MinecraftServer server, boolean realTime) {
        long ticks = Math.max(realTime ? (System.currentTimeMillis() - startMillis) / 50 : fun.bm.lophine.carpet.CarpetServerClock.gameTime() - startTick, -1);
        String colorName = ServerI18nUtil.getLocalizedText("color.minecraft." + coloredName.content().toLowerCase());
        String minText = ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.min");
        String realTimeText = realTime ? ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.real_time") : "";

        if (startTick < 0 || ticks == -1) {
            return Collections.singletonList(Component.text(ServerI18nUtil.getFormatedLocalizedText("lophine.hopper_counter.format.not_started", colorName)));
        }

        long total = getTotalItems();
        if (total <= 0) {
            return Collections.singletonList(Component.text(ServerI18nUtil.getFormatedLocalizedText("lophine.hopper_counter.format.no_items",
                    colorName, String.format("%.2f", ticks / (20.0 * 60.0)), minText, realTimeText)));
        }

        List<Component> items = new ArrayList<>();
        String parenOpen = ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.paren_open");
        String parenClose = ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.paren_close");
        String comma = ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.comma");
        items.add(Component.text()
                .append(Component.text(ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.items_for")), coloredName)
                .append(Component.text(parenOpen), Component.text(String.format("%.2f", ticks * 1.0 / (20 * 60)), Style.style(TextDecoration.BOLD)))
                .append(Component.text(minText), Component.text(realTimeText), Component.text(parenClose), Component.text(comma))
                .append(Component.text(ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.total")), Component.text(total, Style.style(TextDecoration.BOLD)), Component.text(comma))
                .append(Component.text(ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.paren_open")), Component.text(String.format("%.1f", total * 1.0 * (20 * 60 * 60) / ticks), Style.style(TextDecoration.BOLD)))
                .append(Component.text(ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.per_hour_suffix")))
                .build());

        items.addAll(counter.object2LongEntrySet().stream().sorted((e, f) -> Long.compare(f.getLongValue(), e.getLongValue())).map(entry -> {
            Item item = entry.getKey();
            Component name = Component.translatable(item.getDescriptionId());
            TextColor textColor = guessColor(server, item);

            if (textColor != null) {
                name = name.style(name.style().merge(Style.style(textColor)));
            } else {
                name = name.style(name.style().merge(Style.style(TextDecoration.ITALIC)));
            }

            String colon = ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.colon");
            long count = entry.getLongValue();
            return Component.text()
                    .append(Component.text("- ", NamedTextColor.GRAY))
                    .append(name)
                    .append(Component.text(colon, NamedTextColor.GRAY), Component.text(" "))
                    .append(Component.text(count, Style.style(TextDecoration.BOLD)), Component.text(comma, NamedTextColor.GRAY), Component.text(" "))
                    .append(Component.text(String.format("%.1f", count * (20.0 * 60.0 * 60.0) / ticks), Style.style(TextDecoration.BOLD)))
                    .append(Component.text(ServerI18nUtil.getLocalizedText("lophine.hopper_counter.format.per_hour_suffix")))
                    .build();
        }).toList());
        return items;
    }

    private static final Map<Item, Block> DEFAULTS = Map.<Item, Block>ofEntries(
            entry(Items.DANDELION, Blocks.WOOL.yellow()),
            entry(Items.POPPY, Blocks.WOOL.red()),
            entry(Items.BLUE_ORCHID, Blocks.WOOL.lightBlue()),
            entry(Items.ALLIUM, Blocks.WOOL.magenta()),
            entry(Items.AZURE_BLUET, Blocks.SNOW_BLOCK),
            entry(Items.RED_TULIP, Blocks.WOOL.red()),
            entry(Items.ORANGE_TULIP, Blocks.WOOL.orange()),
            entry(Items.WHITE_TULIP, Blocks.SNOW_BLOCK),
            entry(Items.PINK_TULIP, Blocks.WOOL.pink()),
            entry(Items.OXEYE_DAISY, Blocks.SNOW_BLOCK),
            entry(Items.CORNFLOWER, Blocks.WOOL.blue()),
            entry(Items.WITHER_ROSE, Blocks.WOOL.black()),
            entry(Items.LILY_OF_THE_VALLEY, Blocks.WOOL.white()),
            entry(Items.BROWN_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK),
            entry(Items.RED_MUSHROOM, Blocks.RED_MUSHROOM_BLOCK),
            entry(Items.STICK, Blocks.OAK_PLANKS),
            entry(Items.GOLD_INGOT, Blocks.GOLD_BLOCK),
            entry(Items.IRON_INGOT, Blocks.IRON_BLOCK),
            entry(Items.DIAMOND, Blocks.DIAMOND_BLOCK),
            entry(Items.NETHERITE_INGOT, Blocks.NETHERITE_BLOCK),
            entry(Items.SUNFLOWER, Blocks.WOOL.yellow()),
            entry(Items.LILAC, Blocks.WOOL.magenta()),
            entry(Items.ROSE_BUSH, Blocks.WOOL.red()),
            entry(Items.PEONY, Blocks.WOOL.pink()),
            entry(Items.CARROT, Blocks.WOOL.orange()),
            entry(Items.APPLE, Blocks.WOOL.red()),
            entry(Items.WHEAT, Blocks.HAY_BLOCK),
            entry(Items.PORKCHOP, Blocks.WOOL.pink()),
            entry(Items.RABBIT, Blocks.WOOL.pink()),
            entry(Items.CHICKEN, Blocks.GLAZED_TERRACOTTA.white()),
            entry(Items.BEEF, Blocks.NETHERRACK),
            entry(Items.ENCHANTED_GOLDEN_APPLE, Blocks.GOLD_BLOCK),
            entry(Items.COD, Blocks.GLAZED_TERRACOTTA.white()),
            entry(Items.SALMON, Blocks.ACACIA_PLANKS),
            entry(Items.ROTTEN_FLESH, Blocks.WOOL.brown()),
            entry(Items.PUFFERFISH, Blocks.GLAZED_TERRACOTTA.yellow()),
            entry(Items.TROPICAL_FISH, Blocks.WOOL.orange()),
            entry(Items.POTATO, Blocks.GLAZED_TERRACOTTA.white()),
            entry(Items.MUTTON, Blocks.WOOL.red()),
            entry(Items.BEETROOT, Blocks.NETHERRACK),
            entry(Items.MELON_SLICE, Blocks.MELON),
            entry(Items.POISONOUS_POTATO, Blocks.SLIME_BLOCK),
            entry(Items.SPIDER_EYE, Blocks.NETHERRACK),
            entry(Items.GUNPOWDER, Blocks.WOOL.gray()),
            entry(Items.TURTLE_SCUTE, Blocks.WOOL.lime()),
            entry(Items.ARMADILLO_SCUTE, Blocks.ANCIENT_DEBRIS),
            entry(Items.FEATHER, Blocks.WOOL.white()),
            entry(Items.FLINT, Blocks.WOOL.black()),
            entry(Items.LEATHER, Blocks.SPRUCE_PLANKS),
            entry(Items.GLOWSTONE_DUST, Blocks.GLOWSTONE),
            entry(Items.PAPER, Blocks.WOOL.white()),
            entry(Items.BRICK, Blocks.BRICKS),
            entry(Items.INK_SAC, Blocks.WOOL.black()),
            entry(Items.SNOWBALL, Blocks.SNOW_BLOCK),
            entry(Items.WATER_BUCKET, Blocks.WATER),
            entry(Items.LAVA_BUCKET, Blocks.LAVA),
            entry(Items.MILK_BUCKET, Blocks.WOOL.white()),
            entry(Items.CLAY_BALL, Blocks.CLAY),
            entry(Items.COCOA_BEANS, Blocks.COCOA),
            entry(Items.BONE, Blocks.BONE_BLOCK),
            entry(Items.COD_BUCKET, Blocks.GLAZED_TERRACOTTA.brown()),
            entry(Items.PUFFERFISH_BUCKET, Blocks.GLAZED_TERRACOTTA.yellow()),
            entry(Items.SALMON_BUCKET, Blocks.GLAZED_TERRACOTTA.pink()),
            entry(Items.TROPICAL_FISH_BUCKET, Blocks.GLAZED_TERRACOTTA.orange()),
            entry(Items.SUGAR, Blocks.WOOL.white()),
            entry(Items.BLAZE_POWDER, Blocks.GOLD_BLOCK),
            entry(Items.ENDER_PEARL, Blocks.WARPED_PLANKS),
            entry(Items.NETHER_STAR, Blocks.DIAMOND_BLOCK),
            entry(Items.PRISMARINE_CRYSTALS, Blocks.SEA_LANTERN),
            entry(Items.PRISMARINE_SHARD, Blocks.PRISMARINE),
            entry(Items.RABBIT_HIDE, Blocks.OAK_PLANKS),
            entry(Items.CHORUS_FRUIT, Blocks.PURPUR_BLOCK),
            entry(Items.SHULKER_SHELL, Blocks.SHULKER_BOX),
            entry(Items.NAUTILUS_SHELL, Blocks.BONE_BLOCK),
            entry(Items.HEART_OF_THE_SEA, Blocks.CONDUIT),
            entry(Items.HONEYCOMB, Blocks.HONEYCOMB_BLOCK),
            entry(Items.NAME_TAG, Blocks.BONE_BLOCK),
            entry(Items.TOTEM_OF_UNDYING, Blocks.GLAZED_TERRACOTTA.yellow()),
            entry(Items.TRIDENT, Blocks.PRISMARINE),
            entry(Items.GHAST_TEAR, Blocks.WOOL.white()),
            entry(Items.PHANTOM_MEMBRANE, Blocks.BONE_BLOCK),
            entry(Items.EGG, Blocks.BONE_BLOCK),
            entry(Items.COPPER_INGOT, Blocks.COPPER_BLOCK.waxed().pick(WeatheringCopper.WeatherState.UNAFFECTED)),
            entry(Items.AMETHYST_SHARD, Blocks.AMETHYST_BLOCK)
    );

    @SuppressWarnings("deprecation")
    @Nullable
    public static TextColor guessColor(@NotNull MinecraftServer server, Item item) {
        RegistryAccess registryAccess = server.registryAccess();
        TextColor direct = fromItem(item, registryAccess);
        if (direct != null) {
            return direct;
        }

        Identifier id = registryAccess.lookupOrThrow(Registries.ITEM).getKey(item);
        if (id == null) {
            return null;
        }


        for (Recipe<?> recipe : getRecipesForOutput(server.getRecipeManager(), id, server.overworld())) {
            for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
                Optional<Holder<Item>> match = ingredient.items().filter(stack -> fromItem(stack.value(), registryAccess) != null).findFirst();
                if (match.isPresent()) {
                    return fromItem(match.get().value(), registryAccess);
                }
            }
        }
        return null;
    }

    @NotNull
    public static List<Recipe<?>> getRecipesForOutput(@NotNull RecipeManager recipeManager, Identifier id, Level level) {
        List<Recipe<?>> results = new ArrayList<>();
        ContextMap context = SlotDisplayContext.fromLevel(level);
        recipeManager.getRecipes().forEach(recipe -> {
            for (RecipeDisplay recipeDisplay : recipe.value().display()) {
                recipeDisplay.result().resolveForStacks(context).forEach(stack -> {
                    if (BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem()).unwrapKey().map(ResourceKey::identifier).orElseThrow(IllegalStateException::new).equals(id)) {
                        results.add(recipe.value());
                    }
                });
            }
        });
        return results;
    }

    @Nullable
    public static TextColor fromItem(Item item, RegistryAccess registryAccess) {
        if (DEFAULTS.containsKey(item)) {
            return TextColor.color(appropriateColor(DEFAULTS.get(item).defaultMapColor().col));
        }
        if (item instanceof DyeItem) {
            // Leaves - Paper 26.1: DyeItem#getDyeColor() removed, color now lives in DataComponents.DYE
            DyeColor dyeColor = item.components().get(DataComponents.DYE);
            if (dyeColor != null) {
                return TextColor.color(appropriateColor(dyeColor.getMapColor().col));
            }
        }

        Block block = null;
        final Registry<Item> itemRegistry = registryAccess.lookupOrThrow(Registries.ITEM);
        final Registry<Block> blockRegistry = registryAccess.lookupOrThrow(Registries.BLOCK);
        Identifier id = itemRegistry.getKey(item);
        if (item instanceof BlockItem blockItem) {
            block = blockItem.getBlock();
        } else if (blockRegistry.getOptional(id).isPresent()) {
            block = blockRegistry.getValue(id);
        }

        if (block != null) {
            if (block instanceof AbstractBannerBlock) {
                return TextColor.color(appropriateColor(((AbstractBannerBlock) block).getColor().getMapColor().col));
            } else if (block instanceof BeaconBeamBlock) {
                return TextColor.color(appropriateColor(((BeaconBeamBlock) block).getColor().getMapColor().col));
            }
            return TextColor.color(appropriateColor(block.defaultMapColor().col));
        }
        return null;
    }

    public static int appropriateColor(int color) {
        if (color == 0) {
            return MapColor.SNOW.col;
        }
        int r = (color >> 16 & 255);
        int g = (color >> 8 & 255);
        int b = (color & 255);
        if (r < 70) {
            r = 70;
        }
        if (g < 70) {
            g = 70;
        }
        if (b < 70) {
            b = 70;
        }
        return (r << 16) + (g << 8) + b;
    }

    public synchronized long getTotalItems() {
        return counter.isEmpty() ? 0 : counter.values().longStream().sum();
    }

    public static HopperCounter getCounter(DyeColor color) {
        return COUNTERS.get(color);
    }

    public static void setEnabled(boolean is) {
        enabled = is;
    }

    public static boolean isEnabled() {
        return WoolHopperCounterConfig.hopperCounters && enabled;
    }
}
