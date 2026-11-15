package fun.bm.lophine.carpet.config.modules;

import fun.bm.lophine.carpet.ams.AmsRecipeLifecycle;
import net.minecraft.server.MinecraftServer;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@AllFeatures
class RecipeRuleReloadTest {
    private Field baseline;
    private Object previousBaseline;
    private List<Boolean> previousRules;

    @BeforeEach
    void saveState() throws ReflectiveOperationException {
        baseline = GeneralCompatConfig.class.getDeclaredField("appliedRecipeRules");
        baseline.setAccessible(true);
        previousBaseline = baseline.get(null);
        previousRules = GeneralCompatConfig.recipeRuleValues();
        baseline.set(null, null);
    }

    @AfterEach
    void restoreState() throws IllegalAccessException {
        setRules(previousRules);
        baseline.set(null, previousBaseline);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstLoadAndUnchangedRulesDoNotReloadButLaterChangesDo(boolean enabled) throws IllegalAccessException {
        setRules(List.of(enabled, enabled, enabled, enabled, enabled, enabled, enabled));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isRunning()).thenReturn(true);
        try (MockedStatic<MinecraftServer> servers = mockStatic(MinecraftServer.class);
             MockedStatic<AmsRecipeLifecycle> recipes = mockStatic(AmsRecipeLifecycle.class)) {
            servers.when(MinecraftServer::getServer).thenReturn(server);
            recipes.when(() -> AmsRecipeLifecycle.changed(server)).thenReturn(CompletableFuture.completedFuture(null));

            GeneralCompatConfig.refreshRecipeRules();
            assertEquals(GeneralCompatConfig.recipeRuleValues(), baseline.get(null));
            GeneralCompatConfig.refreshRecipeRules();
            recipes.verifyNoInteractions();

            GeneralCompatConfig.craftableElytra = !enabled;
            GeneralCompatConfig.refreshRecipeRules();
            GeneralCompatConfig.refreshRecipeRules();
            recipes.verify(() -> AmsRecipeLifecycle.changed(server), times(1));

            GeneralCompatConfig.craftableElytra = enabled;
            GeneralCompatConfig.refreshRecipeRules();
            recipes.verify(() -> AmsRecipeLifecycle.changed(server), times(2));
        }
    }

    private static void setRules(List<Boolean> values) {
        GeneralCompatConfig.betterCraftableBoneBlock = values.get(0);
        GeneralCompatConfig.betterCraftableDispenser = values.get(1);
        GeneralCompatConfig.craftableEnchantedGoldenApples = values.get(2);
        GeneralCompatConfig.craftableElytra = values.get(3);
        GeneralCompatConfig.betterCraftablePolishedBlackStoneButton = values.get(4);
        GeneralCompatConfig.rottenFleshBurnedIntoLeather = values.get(5);
        GeneralCompatConfig.craftableCarvedPumpkin = values.get(6);
    }
}
