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

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

public abstract class CommandNode {
    private static final Map<Class<? extends CommandNode>, String> class2NameMap = new HashMap<>();

    protected final String name;
    protected final List<CommandNode> children = new ArrayList<>();

    protected CommandNode(String name) {
        this.name = name;
        class2NameMap.put(getClass(), name);
    }

    @SafeVarargs
    protected final void children(Supplier<? extends CommandNode>... childrenClasses) {
        this.children.addAll(Stream.of(childrenClasses).map(Supplier::get).toList());
    }

    protected final void children(CommandNode... childrenClasses) {
        this.children.addAll(List.of(childrenClasses));
    }

    protected abstract ArgumentBuilder<CommandSourceStack, ?> compileBase();

    protected boolean execute(CommandContext context) throws CommandSyntaxException {
        return true;
    }

    public boolean requires(CommandSourceStack source) {
        return true;
    }

    public String getName() {
        return this.name;
    }

    protected ArgumentBuilder<CommandSourceStack, ?> compile() {
        ArgumentBuilder<CommandSourceStack, ?> builder = compileBase().requires(this::requires);

        for (CommandNode child : children) {
            builder = builder.then(child.compile());
        }

        if (canExecute()) {
            builder = builder.executes(mojangCtx -> {
                CommandContext ctx = new CommandContext(mojangCtx);
                return execute(ctx) ? 1 : 0;
            });
        }

        return builder;
    }

    protected boolean canExecute() {
        return isMethodOverridden("execute", CommandNode.class);
    }

    protected boolean isMethodOverridden(String methodName, @NotNull Class<?> baseClass) {
        for (Method method : getClass().getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method.getDeclaringClass() != baseClass;
            }
        }
        return false;
    }

    public static String getNameForNode(Class<? extends CommandNode> nodeClass) {
        return class2NameMap.get(nodeClass);
    }
}