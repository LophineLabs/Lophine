// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class CarpetRuleObservers {
    private CarpetRuleObservers(){}
    public static CompletableFuture<Void> enderChestEnabled(MinecraftServer server){
        var message=Component.translatableWithFallback("carpetamsaddition.observer.largeEnderChest.switch_tip",
            "<Carpet AMS Addition> The detection of the Large Ender Chest (largeEnderChest) rule has been enabled, and it will take effect after re-entering the game. To prevent items from being lost, it is recommended to set this rule to be enabled by default").withStyle(ChatFormatting.GREEN);
        return AmsNativeCommandEffects.then(AmsNativeCommandEffects.global(server,()->{
            server.sendSystemMessage(AmsTranslations.translateText(message,AmsTranslations.serverLanguage()));return (Void)null;
        }),ignored->AmsNativeCommandEffects.broadcast(server,player->{
            if(!player.isRemoved())player.sendSystemMessage(AmsTranslations.translate(message,player));
        }));
    }
    static void restartRequired(CommandSourceStack source,String name){
        CarpetMessenger.send(source,java.util.List.of(AmsTranslations.message(source,"observer.need_restart_server_or_client.is_server_message",name).withStyle(ChatFormatting.YELLOW)));
    }
}
