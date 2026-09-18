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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.rei.ingredient.EntryIngredient;

import java.util.List;
import java.util.Optional;

public abstract class CookingDisplay extends Display {
    private static final StreamCodec<RegistryFriendlyByteBuf, CookingDisplay> CODEC = StreamCodec.composite(
            EntryIngredient.CODEC.apply(ByteBufCodecs.list()),
            CookingDisplay::getInputEntries,
            EntryIngredient.CODEC.apply(ByteBufCodecs.list()),
            CookingDisplay::getOutputEntries,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC),
            CookingDisplay::getOptionalLocation,
            ByteBufCodecs.FLOAT,
            CookingDisplay::getXp,
            ByteBufCodecs.DOUBLE,
            CookingDisplay::getCookTime,
            CookingDisplay::of
    );
    protected float xp;
    protected double cookTime;

    private CookingDisplay(@NotNull List<EntryIngredient> inputs, @NotNull List<EntryIngredient> outputs, @NotNull Identifier id, float xp, double cookTime) {
        super(inputs, outputs, id);
        this.xp = xp;
        this.cookTime = cookTime;
    }

    public CookingDisplay(RecipeHolder<? extends AbstractCookingRecipe> recipe) {
        this(
                List.of(EntryIngredient.ofIngredient(recipe.value().input())),
                List.of(EntryIngredient.of(recipe.value().assemble(new SingleRecipeInput(ItemStack.EMPTY)))), // Leaves - Paper 26.1: assemble no longer takes RegistryAccess
                recipe.id().identifier(),
                recipe.value().experience(),
                recipe.value().cookingTime()
        );
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static CookingDisplay of(@NotNull List<EntryIngredient> inputs, @NotNull List<EntryIngredient> outputs, @NotNull Optional<Identifier> id, float xp, double cookTime) {
        throw new UnsupportedOperationException();
    }

    public float getXp() {
        return xp;
    }

    public double getCookTime() {
        return cookTime;
    }

    public StreamCodec<RegistryFriendlyByteBuf, CookingDisplay> streamCodec() {
        return CODEC;
    }
}
