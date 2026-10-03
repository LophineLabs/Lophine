package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

/** Org's player-specific rule switches, saved with the player's existing data. */
public final class OrgRulePlayerPreferences {
    private static final Set<String> RULES = Set.of("blockDropsDirectlyEnterInventory", "itemPickupRangeExpand");
    private static final SimpleCommandExceptionType INVALID = new SimpleCommandExceptionType(Component.literal("Unknown player-controlled Carpet rule"));
    private static final SimpleCommandExceptionType OTHER = new SimpleCommandExceptionType(Component.literal("You can only change this rule for yourself or a fake player"));

    private OrgRulePlayerPreferences() {}

    private static NamespacedKey key(String rule) {
        return new NamespacedKey("lophine", "carpet_org_" + rule.toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isEnabled(ServerPlayer player, String rule) {
        return player.getBukkitEntity().getPersistentDataContainer().getOrDefault(key(rule), PersistentDataType.BYTE, (byte) 0) != 0;
    }

    public static boolean blockDropsEnterInventory(ServerPlayer player) {
        return "true".equals(GeneralCompatConfig.blockDropsDirectlyEnterInventory)
            || ("custom".equals(GeneralCompatConfig.blockDropsDirectlyEnterInventory)
                && isEnabled(player, "blockDropsDirectlyEnterInventory"));
    }

    public static int itemPickupRange(ServerPlayer player) {
        return GeneralCompatConfig.itemPickupRangeExpandPlayerControl && !isEnabled(player, "itemPickupRangeExpand")
            ? 0 : GeneralCompatConfig.itemPickupRangeExpand;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        OrgServerPermissions.register(dispatcher);
        dispatcher.register(Commands.literal("orange")
            .then(Commands.literal("ruleself")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("rule", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(RULES, builder))
                        .executes(context -> apply(context.getSource(), EntityArgument.getPlayer(context, "player"),
                            StringArgumentType.getString(context, "rule"), null))
                        .then(Commands.argument("value", BoolArgumentType.bool())
                            .executes(context -> apply(context.getSource(), EntityArgument.getPlayer(context, "player"),
                                StringArgumentType.getString(context, "rule"), BoolArgumentType.getBool(context, "value"))))))));
    }

    private static int apply(CommandSourceStack source, ServerPlayer player, String rule, Boolean value) throws CommandSyntaxException {
        if (!RULES.contains(rule)) throw INVALID.create();
        if (source.getEntity() != player && !(player instanceof org.leavesmc.leaves.bot.ServerBot)) {
            throw OTHER.create();
        }
        return OrgCommandNativeEffects.command(source, 1, () -> TisCommandContinuations.then(OrgMenuNativeEffects.run(player, () -> {
            if (value != null) {
                player.getBukkitEntity().getPersistentDataContainer().set(key(rule), PersistentDataType.BYTE, (byte) (value ? 1 : 0));
            }
            return Component.literal(player.getScoreboardName() + " " + rule + " = " + isEnabled(player, rule));
        }), message -> TisCommandContinuations.feedback(source, () -> {
            source.sendSuccess(() -> message, false);
            return 1;
        })));
    }
}
