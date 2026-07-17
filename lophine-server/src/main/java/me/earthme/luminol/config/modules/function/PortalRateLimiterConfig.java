package me.earthme.luminol.config.modules.function;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ConfigClassInfo(name = "portal_rate_limit", category = EnumConfigCategory.FUNCTION)
public class PortalRateLimiterConfig implements IConfigModule {
    @ConfigInfo(name = "enable", comments = "Whether or not to limit the portal rate when entity goes into portals")
    @HotReloadUnsupported
    public static boolean enabled = false;

    @ConfigInfo(name = "maximum_portal_teleports_per_tick", comments = """
            Decides how much portal teleportation should be handled within a tick in a single tick region,when exceed,
            the portal teleportation will be pushed into the next tick
            
            Note: set to -1 to use custom expressions""")
    @HotReloadUnsupported
    public static int maxPortalTeleportsPerTick = 200;

    @ConfigInfo(name = "maximum_portal_teleports_per_tick_expression", comments = """
            If the fixed limit is not enough for use, you could define your own expression to dynamically limit the
            portal rate.
            
            Available variables(all is of current tickregion): e (ticking_entity_count)
                                 c (ticking_chunk_count)
                                 p (player_count)
            Example: 50 * (1 + sqrt(x/1000) + c/200 + p/5)
            """)
    @HotReloadUnsupported
    public static String maxPortalTeleportsExpression = "50 * (1 + sqrt(e/1000) + c/200 + p/5)";

    // use this to prevent reallocation
    private static final String VARIABLE_TICKING_ENTITY_CONT = "e";
    private static final String VARIABLE_TICKING_CHUNK_CONT = "c";
    private static final String VARIABLE_PLAYER_CONT = "p";

    @Nullable
    public static Expression getExpressionIfConfigured() {
        if (maxPortalTeleportsPerTick != -1) {
            return null;
        }

        return new ExpressionBuilder(maxPortalTeleportsExpression)
                .variables(
                        VARIABLE_PLAYER_CONT,
                        VARIABLE_TICKING_CHUNK_CONT,
                        VARIABLE_TICKING_ENTITY_CONT
                )
                .build();
    }

    public static int computeExpression(@NotNull Expression expression, int entityCount, int chunkCount, int playerCount) {
        expression.setVariable(VARIABLE_TICKING_ENTITY_CONT, entityCount);
        expression.setVariable(VARIABLE_TICKING_CHUNK_CONT, chunkCount);
        expression.setVariable(VARIABLE_PLAYER_CONT, playerCount);

        return (int) expression.evaluate();
    }
}
