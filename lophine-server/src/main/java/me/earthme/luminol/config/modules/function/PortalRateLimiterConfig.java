package me.earthme.luminol.config.modules.function;

import me.earthme.luminol.api.config.WorldValue;
import me.earthme.luminol.api.config.WorldValues;
import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.annotation.DoNotLoad;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;
import me.earthme.luminol.api.config.enums.EnumLoadType;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ConfigClassInfo(name = "portal_rate_limit", category = EnumConfigCategory.FUNCTION)
public class PortalRateLimiterConfig {
    @ConfigInfo(name = "enable")
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static final WorldValue<Boolean> enabled = WorldValues.key(false);

    @ConfigInfo(name = "maximum_portal_teleports_per_tick")
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static final WorldValue<Integer> maxPortalTeleportsPerTick = WorldValues.key(200);

    @ConfigInfo(name = "maximum_portal_teleports_per_tick_expression")
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static final WorldValue<String> maxPortalTeleportsExpression = WorldValues.key("50 * (1 + sqrt(e/1000) + c/200 + p/5)");

    @ConfigInfo(name = "destination_backpressure_enabled")
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static final WorldValue<Boolean> destinationBackpressureEnabled = WorldValues.key(true);

    @ConfigInfo(name = "destination_tokens_per_tick")
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static final WorldValue<Integer> portalIngressTokensPerTick = WorldValues.key(16);

    @ConfigInfo(name = "destination_burst_capacity")
    @DoNotLoad(when = EnumLoadType.RELOAD)
    public static final WorldValue<Integer> portalIngressBurstCapacity = WorldValues.key(64);

    // use this to prevent reallocation
    private static final String VARIABLE_TICKING_ENTITY_CONT = "e";
    private static final String VARIABLE_TICKING_CHUNK_CONT = "c";
    private static final String VARIABLE_PLAYER_CONT = "p";

    @Nullable
    public static Expression getExpressionIfConfigured() {
        if (maxPortalTeleportsPerTick.get() != -1) {
            return null;
        }

        return new ExpressionBuilder(maxPortalTeleportsExpression.get())
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
