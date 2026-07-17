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
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public abstract class ArgumentNode<T> extends CommandNode {
    protected final ArgumentType<T> argumentType;

    protected ArgumentNode(String name, ArgumentType<T> argumentType) {
        super(name);
        this.argumentType = argumentType;
    }

    @SuppressWarnings({"unused", "RedundantThrows"})
    protected CompletableFuture<Suggestions> getSuggestions(final CommandContext context, final SuggestionsBuilder builder) throws CommandSyntaxException {
        return Suggestions.empty();
    }

    protected boolean overrideSuggestions() {
        return isMethodOverridden("getSuggestions", ArgumentNode.class);
    }

    @Override
    protected ArgumentBuilder<CommandSourceStack, ?> compileBase() {
        RequiredArgumentBuilder<CommandSourceStack, T> argumentBuilder = Commands.argument(name, argumentType);

        if (overrideSuggestions()) {
            argumentBuilder.suggests(
                    (context, builder) -> getSuggestions(new CommandContext(context), builder)
            );
        }

        return argumentBuilder;
    }

    public static class ArgumentSuggestions {
        @Contract(pure = true)
        public static WrappedArgument.@NotNull SuggestionApplier strings(String... values) {
            return (context, builder) -> {
                for (String s : values) {
                    builder.suggest(s);
                }
            };
        }

        @Contract(pure = true)
        public static WrappedArgument.@NotNull SuggestionApplier strings(List<String> values) {
            return (context, builder) -> {
                for (String s : values) {
                    builder.suggest(s);
                }
            };
        }
    }
}
