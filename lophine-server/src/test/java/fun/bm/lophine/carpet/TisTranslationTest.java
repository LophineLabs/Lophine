package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TisTranslationTest {
    @Test
    void originalTranslationModeSelectsTheServerLanguageAndNoneUsesEnglish() {
        String ultra = GeneralCompatConfig.ultraSecretSetting, language = GeneralCompatConfig.language;
        try {
            GeneralCompatConfig.language = "zh_cn";
            GeneralCompatConfig.ultraSecretSetting = "false";
            assertEquals("en_us", TisTranslations.targetLanguage("en_us"));
            GeneralCompatConfig.ultraSecretSetting = "translation";
            assertEquals("zh_cn", TisTranslations.targetLanguage("en_us"));
            GeneralCompatConfig.language = "none";
            assertEquals("en_us", TisTranslations.targetLanguage("zh_cn"));
        } finally { GeneralCompatConfig.ultraSecretSetting = ultra; GeneralCompatConfig.language = language; }
    }

    @Test
    void translatesArgumentsSiblingsHoverAndRootDotKeysAndKeepsStyles() {
        var hover = TisTranslations.text("command.refresh.inventory.done");
        var argument = TisTranslations.text("tracker.raid.name").withStyle(ChatFormatting.RED);
        var root = TisTranslations.text("tracker.tracker_name_full", argument)
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)))
            .append(TisTranslations.text("command.raid.status"));
        var translated = TisTranslations.translateText(root, "zh_cn");
        assertFalse(translated.getString().contains("carpettisaddition"));
        assertNotEquals(TisTranslations.translateText(root, "en_us").getString(), translated.getString());
        var translatedHover = (HoverEvent.ShowText) translated.getStyle().getHoverEvent();
        assertEquals(TisTranslations.translateText(hover, "zh_cn").getString(), translatedHover.value().getString());
        assertEquals(0xFF5555, translated.getSiblings().getFirst().getStyle().getColor().getValue());
        assertEquals("Raid TrackerStatus", TisTranslations.translateText(root, "missing_language").getString());
        assertEquals("carpettisaddition.command.raid.status", TisTranslations.text("command.raid.status").getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents ? contents.getKey() : "");
    }

    @Test
    void auditActionDoesNotReplaceTheCurrentDebugMode() {
        assertEquals("endermelon", TisDebugSettings.validateUltra("mixin_audit", "endermelon", null));
        assertEquals("some_custom_debug_token", TisDebugSettings.validateUltra("some_custom_debug_token", "false", null));
    }
}
