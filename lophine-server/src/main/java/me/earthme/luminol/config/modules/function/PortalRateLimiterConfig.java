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
    @ConfigInfo(name = "enable")
    @HotReloadUnsupported
    public static boolean enabled = false;

    @ConfigInfo(name = "maximum_portal_teleports_per_tick")
    @HotReloadUnsupported
    public static int maxPortalTeleportsPerTick = 200;

    @ConfigInfo(name = "maximum_portal_teleports_per_tick_expression")
    @HotReloadUnsupported
    public static String maxPortalTeleportsExpression = "50 * (1 + sqrt(e/1000) + c/200 + p/5)";

    @ConfigInfo(name = "destination_backpressure_enabled")
    @HotReloadUnsupported
    public static boolean destinationBackpressureEnabled = true;

    @ConfigInfo(name = "destination_tokens_per_tick")
    @HotReloadUnsupported
    public static int portalIngressTokensPerTick = 16;

    @ConfigInfo(name = "destination_burst_capacity")
    @HotReloadUnsupported
    public static int portalIngressBurstCapacity = 64;

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
