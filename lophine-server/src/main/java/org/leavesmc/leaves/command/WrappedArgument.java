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

package org.leavesmc.leaves.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class WrappedArgument<T> {
    private final String name;
    private final ArgumentType<T> type;
    private AsyncSuggestionProvider asyncSuggestionProvider = null;
    private SuggestionApplier suggestionApplier = null;
    private boolean optional = false;
    private Item guiItem = null;
    private final List<GuiSuggestion> guiSuggestions = new ArrayList<>();

    public WrappedArgument(String name, ArgumentType<T> type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return this.name;
    }

    public ArgumentType<T> getType() {
        return this.type;
    }

    public WrappedArgument<T> suggestsAsync(AsyncSuggestionProvider provider) {
        this.asyncSuggestionProvider = provider;
        return this;
    }

    public WrappedArgument<T> suggests(SuggestionApplier provider) {
        this.suggestionApplier = provider;
        return this;
    }

    public WrappedArgument<T> guiItem(Item item) {
        this.guiItem = item;
        return this;
    }

    public WrappedArgument<T> guiSuggest(String value, String description) {
        this.guiSuggestions.add(new GuiSuggestion(value, description, this.guiItem));
        return this;
    }

    public WrappedArgument<T> guiSuggest(String description, Iterable<String> values) {
        for (String value : values) {
            guiSuggest(value, description);
        }
        return this;
    }

    public Item getGuiItem() {
        return this.guiItem;
    }

    public List<GuiSuggestion> getGuiSuggestions() {
        return Collections.unmodifiableList(this.guiSuggestions);
    }

    public WrappedArgument<T> setOptional(boolean optional) {
        this.optional = optional;
        return this;
    }

    public boolean isOptional() {
        return optional;
    }

    public RequiredArgumentBuilder<CommandSourceStack, T> compile() {
        RequiredArgumentBuilder<CommandSourceStack, T> builder = Commands.argument(name, type);
        if (asyncSuggestionProvider != null) {
            builder.suggests((context, b) ->
                    asyncSuggestionProvider.getSuggestions(new CommandContext(context), b)
            );
        } else if (suggestionApplier != null) {
            builder.suggests((context, b) -> {
                suggestionApplier.applySuggestions(new CommandContext(context), b);
                return CompletableFuture.completedFuture(b.build());
            });
        } else if (!guiSuggestions.isEmpty()) {
            builder.suggests((context, b) -> {
                for (GuiSuggestion suggestion : guiSuggestions) {
                    if (suggestion.description() != null && !suggestion.description().isEmpty()) {
                        b.suggest(suggestion.value(), Component.literal(suggestion.description()));
                    } else {
                        b.suggest(suggestion.value());
                    }
                }
                return CompletableFuture.completedFuture(b.build());
            });
        }
        return builder;
    }

    public record GuiSuggestion(String value, String description, Item item) {
    }

    @FunctionalInterface
    public interface SuggestionApplier {
        void applySuggestions(final CommandContext context, final SuggestionsBuilder builder) throws CommandSyntaxException;
    }

    @FunctionalInterface
    public interface AsyncSuggestionProvider {
        CompletableFuture<Suggestions> getSuggestions(final CommandContext context, final SuggestionsBuilder builder) throws CommandSyntaxException;
    }
}
