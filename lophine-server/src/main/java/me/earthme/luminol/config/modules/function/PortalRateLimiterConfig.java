package me.earthme.luminol.config.modules.function;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.DoNotLoad;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumLoadType;
import me.earthme.luminol.config.WorldValues;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ConfigClassInfo(name = "portal_rate_limit", category = EnumConfigCategory.FUNCTION)
public class PortalRateLimiterConfig {
    @ConfigInfo(name = "enable", isWorldConfig = true)
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static boolean enabled = false;

    @ConfigInfo(name = "maximum_portal_teleports_per_tick", isWorldConfig = true)
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static int maxPortalTeleportsPerTick = 200;

    @ConfigInfo(name = "maximum_portal_teleports_per_tick_expression", isWorldConfig = true)
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static String maxPortalTeleportsExpression = "50 * (1 + sqrt(e/1000) + c/200 + p/5)";

    @ConfigInfo(name = "destination_backpressure_enabled", isWorldConfig = true)
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static boolean destinationBackpressureEnabled = true;

    @ConfigInfo(name = "destination_tokens_per_tick", isWorldConfig = true)
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static int portalIngressTokensPerTick = 16;

    @ConfigInfo(name = "destination_burst_capacity", isWorldConfig = true)
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static int portalIngressBurstCapacity = 64;

    public static boolean enabled() {
        return WorldValues.get(enabled);
    }

    public static int maxPortalTeleportsPerTick() {
        return WorldValues.get(maxPortalTeleportsPerTick);
    }

    public static String maxPortalTeleportsExpression() {
        return WorldValues.get(maxPortalTeleportsExpression);
    }

    public static boolean destinationBackpressureEnabled() {
        return WorldValues.get(destinationBackpressureEnabled);
    }

    public static int portalIngressTokensPerTick() {
        return WorldValues.get(portalIngressTokensPerTick);
    }

    public static int portalIngressBurstCapacity() {
        return WorldValues.get(portalIngressBurstCapacity);
    }

    // use this to prevent reallocation
    private static final String VARIABLE_TICKING_ENTITY_CONT = "e";
    private static final String VARIABLE_TICKING_CHUNK_CONT = "c";
    private static final String VARIABLE_PLAYER_CONT = "p";

    @Nullable
    public static Expression getExpressionIfConfigured() {
        if (maxPortalTeleportsPerTick() != -1) {
            return null;
        }

        return new ExpressionBuilder(maxPortalTeleportsExpression())
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
