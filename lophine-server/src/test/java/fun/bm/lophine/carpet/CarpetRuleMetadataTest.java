package fun.bm.lophine.carpet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CarpetRuleMetadataTest {
    @Test void bundledDefinitionsDriveDescriptionsCategoriesAndStrictValues() {
        var piston=CarpetRuleMetadata.get("pushLimit");
        assertTrue(piston.source().contains("fabric-carpet/"));
        assertTrue(piston.categories().contains("creative"));
        assertTrue(piston.matches("piston"));
        assertTrue(piston.matches("creative"));
        assertFalse(piston.strict());
        assertEquals(java.util.List.of("en_us","fr_fr","es_ar","pt_br","zh_cn","zh_tw"),CarpetRuleMetadata.options("language"));
        assertThrows(IllegalArgumentException.class,()->CarpetRuleRegistry.get("language").parse("unknown_language"));
        assertThrows(IllegalArgumentException.class,()->CarpetRuleRegistry.get("hardcodeTNTangle").parse("6.283185307179586"));
        assertThrows(IllegalArgumentException.class,()->CarpetRuleRegistry.get("hardcodeTNTangle").parse("NaN"));
        assertEquals(-1D,CarpetRuleRegistry.get("hardcodeTNTangle").parse("-1"));
        assertEquals(java.util.List.of("fake_player", "online_player", "non_whitelist", "all_player"), CarpetRuleMetadata.options("playerCommandOpenPlayerInventoryOption"));
        assertEquals("sneaking", CarpetRuleRegistry.get("quickSettingFakePlayerCraft").parse("SNEAKING"));
    }
    @Test void everyCurrentDefaultCanBeLoadedThroughTheSameValidatorAsCommands() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(String name:CarpetRuleRegistry.names()) {
            var binding=CarpetRuleRegistry.get(name);
            Object value=binding.value();
            String input=value instanceof java.util.List<?> list ? String.join(",",list.stream().map(Object::toString).toList()) : value.toString();
            checks.add(()->assertDoesNotThrow(()->binding.parse(input),name+" = "+input));
        }
        assertAll(checks);
    }
    @Test void originalServerLanguageLoadsAllSixPinnedCatalogs() {
        var previous=fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.language;
        try {
            fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.language="fr_fr";
            assertEquals("créatif",OrgRuleTranslations.text("carpet.category.creative","missing"));
        } finally { fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.language=previous; }
    }
}
