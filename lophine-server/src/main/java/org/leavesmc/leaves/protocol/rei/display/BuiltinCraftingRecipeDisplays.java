/*
 * This file is licensed under the MIT License, part of Roughly Enough Items.
 * Copyright (c) 2018, 2019, 2020, 2021, 2022, 2023 shedaniel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.leavesmc.leaves.protocol.rei.display;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.crafting.BannerDuplicateRecipe;
import net.minecraft.world.item.crafting.BookCloningRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShieldDecorationRecipe;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.leavesmc.leaves.protocol.rei.ingredient.EntryIngredient;

import java.util.*;

/**
 * Builds the three server-side recipe fillers registered by REI's 26.2 default plugin.
 */
public final class BuiltinCraftingRecipeDisplays {
    private static final String[] BOOK_TITLES = {
            "Adventurer's Dreams", "Adventurer's Diary", "The Lost Journal", "The Lost Diary", "The Lost Book",
            "The Lost Tome", "The Lost Codex", "The Last Journal", "The Last Diary", "The Last Book",
            "The Last Tome", "Secrets of the World", "Secrets of the Universe", "Secrets of the Cosmos",
            "Myths of the World", "Myths of the Universe", "Myths of the Cosmos", "Old Tales of the World",
            "Old Tales of the Universe", "Old Tales of the Cosmos", "The World of the Legends", "The Universe of the Heroes",
            "The Cosmos of the Gods", "Diary of a Villager", "Diary of a Farmer", "Diary of a Fisherman",
            "Dungeon Journal", "Dungeon Diary", "Dungeon Book", "Dungeon Tome", "Dunk Memes",
            "Top 10 Memes of 2019", "Top 10 Memes of 2020", "Plastic Memories", "Kizumonogatari"
    };
    private static final String[] BOOK_AUTHORS = {
            "shedaniel", "Steve", "Alex", "Notch", "Herobrine", "God", "Santa Claus", "The Easter Bunny", "The Tooth Fairy"
    };

    private BuiltinCraftingRecipeDisplays() {
    }

    public static Collection<Display> ofBookCloning(RecipeHolder<BookCloningRecipe> recipe) {
        List<Display> displays = new ArrayList<>();
        for (int copies = 1; copies <= 8; copies++) {
            List<ItemStack> writtenBookOptions = new ArrayList<>();
            List<EntryIngredient> inputs = new ArrayList<>();
            List<ItemStack> outputOptions = new ArrayList<>();

            for (int option = 0; option < 10; option++) {
                ItemStack writtenBook = randomWrittenBook();
                writtenBookOptions.add(writtenBook);
                ItemStack cloned = writtenBook.copy();
                cloned.update(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent.EMPTY, WrittenBookContent::craftCopy);
                cloned.setCount(copies);
                outputOptions.add(cloned);
            }

            inputs.add(EntryIngredient.of(writtenBookOptions.toArray(ItemStack[]::new)));
            for (int i = 0; i < copies; i++) {
                inputs.add(EntryIngredient.of(Items.WRITABLE_BOOK));
            }
            displays.add(new CustomDisplay(inputs, List.of(EntryIngredient.of(outputOptions.toArray(ItemStack[]::new))), recipe.id().identifier()));
        }
        return displays;
    }

    public static Collection<Display> ofBannerDuplicate(RecipeHolder<BannerDuplicateRecipe> recipe) {
        Map<DyeColor, List<ItemStack>> bannerOptions = new HashMap<>();
        Map<DyeColor, ItemStack> cleanBanners = new HashMap<>();
        for (Pair<DyeColor, ItemStack> pair : randomizeBanners()) {
            ItemStack clean = cleanBanners.computeIfAbsent(pair.getFirst(), color -> bannerOf(color));
            bannerOptions.computeIfAbsent(pair.getFirst(), ignored -> new ArrayList<>()).add(pair.getSecond());
            cleanBanners.putIfAbsent(pair.getFirst(), clean);
        }

        List<Display> displays = new ArrayList<>();
        for (Map.Entry<DyeColor, List<ItemStack>> entry : bannerOptions.entrySet()) {
            EntryIngredient patternedBanners = EntryIngredient.of(entry.getValue().toArray(ItemStack[]::new));
            EntryIngredient cleanBanner = EntryIngredient.of(cleanBanners.get(entry.getKey()));
            displays.add(new CustomDisplay(List.of(patternedBanners, cleanBanner), List.of(patternedBanners), recipe.id().identifier()));
        }
        return displays;
    }

    public static Collection<Display> ofShieldDecoration(RecipeHolder<ShieldDecorationRecipe> recipe) {
        List<ItemStack> bannerOptions = new ArrayList<>();
        List<ItemStack> shieldOptions = new ArrayList<>();
        for (Pair<DyeColor, ItemStack> pair : randomizeBanners()) {
            ItemStack banner = pair.getSecond();
            ItemStack shield = new ItemStack(Items.SHIELD);
            shield.set(DataComponents.BANNER_PATTERNS, banner.get(DataComponents.BANNER_PATTERNS));
            shield.set(DataComponents.BASE_COLOR, pair.getFirst());
            bannerOptions.add(banner);
            shieldOptions.add(shield);
        }
        return List.of(new CustomDisplay(
                List.of(EntryIngredient.of(bannerOptions.toArray(ItemStack[]::new)), EntryIngredient.of(Items.SHIELD)),
                List.of(EntryIngredient.of(shieldOptions.toArray(ItemStack[]::new))),
                recipe.id().identifier()));
    }

    private static ItemStack randomWrittenBook() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                new Filterable<>(BOOK_TITLES[new Random().nextInt(BOOK_TITLES.length)], Optional.empty()),
                BOOK_AUTHORS[new Random().nextInt(BOOK_AUTHORS.length)],
                0,
                List.of(),
                true));
        return book;
    }

    private static ItemStack bannerOf(DyeColor color) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(color.getName() + "_banner"))
                .map(ItemStack::new)
                .orElse(ItemStack.EMPTY);
    }

    private static List<Pair<DyeColor, ItemStack>> randomizeBanners() {
        List<Pair<DyeColor, ItemStack>> banners = new ArrayList<>();
        Random random = new Random();
        for (DyeColor color : DyeColor.values()) {
            ItemStack cleanBanner = bannerOf(color);
            if (cleanBanner.isEmpty()) {
                continue;
            }
            banners.add(Pair.of(color, cleanBanner));

            Optional<Registry<BannerPattern>> registry = MinecraftServer.getServer().registryAccess().lookup(Registries.BANNER_PATTERN);
            if (registry.isEmpty()) {
                return Collections.emptyList();
            }
            Holder<BannerPattern>[] patterns = registry.get().listElements().toArray(Holder[]::new);
            if (patterns.length < 2) {
                continue;
            }
            for (int variant = 0; variant < 2; variant++) {
                List<BannerPatternLayers.Layer> layers = new ArrayList<>();
                for (int layer = 0; layer < 2; layer++) {
                    Holder<BannerPattern> pattern = patterns[random.nextInt(patterns.length - 1) + 1];
                    layers.add(new BannerPatternLayers.Layer(pattern, DyeColor.values()[random.nextInt(DyeColor.values().length)]));
                }
                ItemStack patternedBanner = cleanBanner.copy();
                patternedBanner.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers(layers));
                banners.add(Pair.of(color, patternedBanner));
            }
        }
        return banners;
    }
}
