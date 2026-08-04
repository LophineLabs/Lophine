package fun.bm.lophine.carpet;

import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.objecthunter.exp4j.ExpressionBuilder;

import java.math.BigDecimal;

public final class CarpetCalculatorHelper {

    public static boolean handleChat(ServerPlayer player, String rawMessage) {
        if (!GeneralCompatConfig.simpleInGameCalculator || !rawMessage.startsWith("=")) {
            return false;
        }

        String expression = rawMessage.substring(1).trim();
        if (expression.isEmpty()) {
            return false;
        }

        Component response;
        try {
            double result = new ExpressionBuilder(expression).build().evaluate();
            response = Component.literal(expression + " = " + formatNumber(result)).withStyle(ChatFormatting.YELLOW);
        } catch (RuntimeException exception) {
            response = Component.literal("Calculator error: " + exception.getMessage()).withStyle(ChatFormatting.RED);
        }

        Component scheduledResponse = response;
        player.getBukkitEntity().taskScheduler.schedule((ServerPlayer scheduledPlayer) -> scheduledPlayer.sendSystemMessage(scheduledResponse), null, 1L);
        return true;
    }

    private static String formatNumber(double value) {
        if (Double.isFinite(value) && Math.rint(value) == value && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
