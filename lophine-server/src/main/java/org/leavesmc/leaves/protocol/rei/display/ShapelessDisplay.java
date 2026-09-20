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

package org.leavesmc.leaves.protocol.rei.display;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.rei.ingredient.EntryIngredient;

import java.util.List;
import java.util.Optional;

public class ShapelessDisplay extends CraftingDisplay {
    private static final StreamCodec<RegistryFriendlyByteBuf, CraftingDisplay> CODEC = StreamCodec.composite(
            EntryIngredient.CODEC.apply(ByteBufCodecs.list()),
            CraftingDisplay::getInputEntries,
            EntryIngredient.CODEC.apply(ByteBufCodecs.list()),
            CraftingDisplay::getOutputEntries,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC),
            CraftingDisplay::getOptionalLocation,
            ShapelessDisplay::of
    );

    private static final Identifier SERIALIZER_ID = Identifier.tryBuild("minecraft", "default/crafting/shapeless");

    public ShapelessDisplay(@NotNull List<EntryIngredient> inputs,
                            @NotNull List<EntryIngredient> outputs,
                            @NotNull Identifier location) {
        super(inputs, outputs, location);
    }

    public ShapelessDisplay(@NotNull RecipeHolder<ShapelessRecipe> recipeHolder) {
        this(
                recipeHolder.value().placementInfo().ingredients().stream().map(EntryIngredient::ofIngredient).toList(),
                List.of(EntryIngredient.of(recipeHolder.value().assemble(CraftingInput.EMPTY))), // Leaves - Paper 26.1: assemble no longer takes RegistryAccess
                recipeHolder.id().identifier()
        );
    }

    public ShapelessDisplay(@NotNull ShapelessCraftingRecipeDisplay recipeDisplay, Identifier id) {
        this(
                ofSlotDisplays(recipeDisplay.ingredients()),
                List.of(ofSlotDisplay(recipeDisplay.result())),
                id
        );
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static CraftingDisplay of(List<EntryIngredient> inputs, List<EntryIngredient> outputs, Optional<Identifier> location) {
        throw new NotImplementedException();
    }

    @Override
    public int getWidth() {
        return getInputEntries().size() > 4 ? 3 : 2;
    }

    @Override
    public int getHeight() {
        return getInputEntries().size() > 4 ? 3 : 2;
    }

    @Override
    public Identifier getSerializerId() {
        return SERIALIZER_ID;
    }

    public StreamCodec<RegistryFriendlyByteBuf, CraftingDisplay> streamCodec() {
        return CODEC;
    }
}
