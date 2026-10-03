package carpet.script.exception;

import carpet.script.external.Carpet;
import carpet.script.value.FunctionValue;
import net.minecraft.commands.CommandSourceStack;

import java.util.List;

public class CarpetExpressionException extends StacklessRuntimeException implements ResolvedException {
    public final List<FunctionValue> stack;

    public CarpetExpressionException(String message, List<FunctionValue> stack) {
        super(message);
        this.stack = stack;
    }

    public void printStack(CommandSourceStack source) {
        if (stack != null && !stack.isEmpty()) {
            for (FunctionValue fun : stack) {
                Carpet.Messenger_message(source, "e  ... in " + fun.fullName(), "e /" + (fun.getToken().lineno + 1) + ":" + (fun.getToken().linepos + 1));
            }
        }
    }
}
