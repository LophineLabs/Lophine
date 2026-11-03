package fun.bm.lophine.bot.action.gui;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.minecraft.world.item.Item;
import org.apache.commons.lang3.tuple.Pair;
import org.leavesmc.leaves.bot.agent.actions.AbstractBotAction;
import org.leavesmc.leaves.command.WrappedArgument;
import org.leavesmc.leaves.command.arguments.EnumArgumentType;

import java.util.*;

public class GuiRootNode extends GuiNode {
    private final boolean visibleInGui;
    private final Map<Integer, List<Pair<String, WrappedArgument<?>>>> arguments;
    private final List<List<WrappedArgument.GuiSuggestion>> suggestionLayers = new ArrayList<>();
    private Command<CommandSourceStack> executor;

    public GuiRootNode(String name, String displayName, String description, Item item, boolean confirmable, boolean visibleInGui) {
        super(name, displayName, description, item, confirmable);
        this.visibleInGui = visibleInGui;
        this.arguments = new HashMap<>();
    }

    public static GuiRootNode fromAction(AbstractBotAction<?> action) {
        List<Pair<String, WrappedArgument<?>>> primaryArgs = action.getArguments().getOrDefault(0, List.of());
        boolean confirmable = action.isGuiConfirmable();
        if (!primaryArgs.isEmpty() && !primaryArgs.getFirst().getRight().isOptional()) {
            confirmable = false;
        }

        GuiRootNode root = new GuiRootNode(
                action.getName(),
                action.getGuiTitle(),
                action.getGuiDescription(),
                action.getGuiItem(),
                confirmable,
                action.isGuiVisible()
        );
        root.arguments.putAll(action.getArguments());
        if (root.visibleInGui) {
            for (Pair<String, WrappedArgument<?>> pair : primaryArgs) {
                List<WrappedArgument.GuiSuggestion> suggestions = resolveGuiSuggestions(pair.getRight());
                if (suggestions.isEmpty()) {
                    break;
                }
                root.suggestionLayers.add(suggestions);
            }
        }
        return root;
    }

    public boolean isVisibleInGui() {
        return this.visibleInGui;
    }

    @Override
    public GuiRootNode getActionRoot() {
        return this;
    }

    public void setExecutor(Command<CommandSourceStack> executor) {
        this.executor = executor;
    }

    List<WrappedArgument.GuiSuggestion> layerAt(int depth) {
        if (depth < 0 || depth >= this.suggestionLayers.size()) {
            return List.of();
        }
        return this.suggestionLayers.get(depth);
    }

    @Override
    protected List<WrappedArgument.GuiSuggestion> nextSuggestionLayer() {
        return this.layerAt(0);
    }

    @Override
    protected ArgumentBuilder<CommandSourceStack, ?> compile() {
        ArgumentBuilder<CommandSourceStack, ?> builder = compileBase().requires(this::requires);
        if (this.executor == null) {
            return builder;
        }

        Command<CommandSourceStack> command = this.executor;
        for (Map.Entry<Integer, List<Pair<String, WrappedArgument<?>>>> entry : this.arguments.entrySet()) {
            List<Pair<String, WrappedArgument<?>>> value = entry.getValue();
            ArgumentBuilder<CommandSourceStack, ?> branchArgumentBuilder = null;

            for (Pair<String, WrappedArgument<?>> pair : value.reversed()) {
                WrappedArgument<?> argument = pair.getRight();
                if (branchArgumentBuilder == null) {
                    branchArgumentBuilder = argument.compile().executes(command);
                } else {
                    branchArgumentBuilder = argument.compile().then(branchArgumentBuilder);
                    if (argument.isOptional()) {
                        branchArgumentBuilder = branchArgumentBuilder.executes(command);
                    }
                }
            }

            if (value.isEmpty() || value.getFirst().getRight().isOptional()) {
                builder = builder.executes(command);
            }
            if (branchArgumentBuilder != null) {
                builder = builder.then(branchArgumentBuilder);
            }
        }

        if (this.arguments.isEmpty()) {
            builder = builder.executes(command);
        }
        return builder;
    }

    private static List<WrappedArgument.GuiSuggestion> resolveGuiSuggestions(WrappedArgument<?> argument) {
        List<WrappedArgument.GuiSuggestion> configured = argument.getGuiSuggestions();
        if (!configured.isEmpty()) {
            return configured;
        }
        if (argument.getType() instanceof EnumArgumentType<?> enumType) {
            List<WrappedArgument.GuiSuggestion> suggestions = new ArrayList<>();
            Item item = argument.getGuiItem();
            for (Enum<?> constant : enumType.enumClass().getEnumConstants()) {
                String value = constant.name().toLowerCase(Locale.ROOT);
                suggestions.add(new WrappedArgument.GuiSuggestion(value, value, item));
            }
            return suggestions;
        }
        return List.of();
    }
}
