package fun.bm.lophine.carpet;

import com.mojang.brigadier.CommandDispatcher;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Intentionally shares one stack object between both hands, as upstream item shadowing does. */
public final class OrgItemShadowingCommand {
    private OrgItemShadowingCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("itemshadowing")
            .requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.commandItemShadowing))
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                var source = context.getSource();
                return OrgCommandNativeEffects.command(source, 1, () -> TisCommandContinuations.then(OrgCommandNativeEffects.intent(player, () -> {
                    var result = new java.util.concurrent.atomic.AtomicReference<Component>();
                    OrgItemShadowGroups.actor(player, () -> java.util.List.of(player.getMainHandItem(), player.getOffhandItem()), () -> {
                    ServerPlayer target = player;
                    ItemStack main = target.getMainHandItem();
                    ItemStack off = target.getOffhandItem();
                    if (main.isEmpty() == off.isEmpty()) {
                        throw new IllegalArgumentException(OrgRuleTranslations.text("carpet-org-addition.command.itemshadowing.fail", "Exactly one hand must hold an item"));
                    }
                    ItemStack shared = main.isEmpty() ? off : main;
                    OrgItemShadowGroups.share(shared);
                    target.setItemInHand(main.isEmpty() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, shared);
                    target.getInventory().setChanged();
                    Component broadcast = Component.literal(String.format(OrgRuleTranslations.text(
                        "carpet-org-addition.command.itemshadowing.broadcast", "%s created an item shadow of %s"), target.getDisplayName().getString(), shared.getDisplayName().getString()));
                    com.mojang.logging.LogUtils.getLogger().info("{} created [{}] item shadow at {} {} contents={}", target.getScoreboardName(),
                        shared.getItemName().getString(), target.level().dimension().identifier(), target.blockPosition().toShortString(),
                        shared.getOrDefault(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY).nonEmptyItemCopyStream().toList());
                    result.set(broadcast);
                    return broadcast;
                    }, null, owner -> !owner.isRemoved(), result::set);
                    return result;
                }), result -> {
                    Component message = result.get();
                    if (message == null) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Item-shadow owner retired before its mutation"));
                    return TisCommandContinuations.then(OrgCommandNativeEffects.broadcast(source.getServer(), message), ignored -> java.util.concurrent.CompletableFuture.completedFuture(1));
                }));
            }));
    }
}
