package fun.bm.lophine.carpet;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AmsTranslationsTest {
    @Test void keepsStyledNestedComponentsAndIndexedArguments() {
        Component argument = Component.literal("target").withStyle(style -> style.withColor(ChatFormatting.RED)
            .withClickEvent(new ClickEvent.RunCommand("/tp target"))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("details"))));
        var result = AmsTranslations.formatPattern("[%1$s] %1$s %% %2$d", argument, 7);
        assertEquals("[target] target % 7", result.getString());
        assertEquals(2L, result.getSiblings().stream().filter(piece -> piece.getStyle().equals(argument.getStyle())).count());
        assertNotSame(argument, result.getSiblings().get(1));
    }

    @Test void preservesParentAndSiblingStyleDuringTranslation() {
        var original = Component.translatableWithFallback("carpetamsaddition.missing-test-key", "fallback", Component.literal("arg"))
            .withStyle(ChatFormatting.BOLD).append(Component.literal(" sibling").withStyle(ChatFormatting.BLUE));
        var translated = AmsTranslations.translateText(original, "en_us");
        assertEquals(original.getStyle(), translated.getStyle());
        assertEquals(original.getSiblings().getFirst().getStyle(), translated.getSiblings().getFirst().getStyle());
        assertEquals(original.getString(), translated.getString());
    }
}
