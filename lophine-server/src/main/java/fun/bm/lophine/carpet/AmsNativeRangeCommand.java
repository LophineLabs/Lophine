// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

import carpet.script.external.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** Original AMS wraps only setBaseValue; target validation and both source messages keep their real order and one. */
public final class AmsNativeRangeCommand {
 private AmsNativeRangeCommand(){}
 public static int controlledSet(CommandSourceStack source,Entity target,String rule,Runnable validate,Component attribute,double value){
  return OrgCommandNativeEffects.command(source,1,()->
   ScarpetNativeDeathActors.entity(target,()->{validate.run();return (Void)null;})
    .thenCompose(ScarpetRuntime.captureNativeFunction(ignored->AmsNativeCommandEffects.source(source,()->{
     source.sendSuccess(()->AmsTranslations.message(source,"rule."+rule+".disable_command").withStyle(ChatFormatting.RED),false);return (Void)null;
    })))
    .thenCompose(ScarpetRuntime.captureNativeFunction(ignored->ScarpetNativeDeathActors.entity(target,()->target.getDisplayName().copy())))
    .thenCompose(ScarpetRuntime.captureNativeFunction(name->AmsNativeCommandEffects.source(source,()->{
     source.sendSuccess(()->Component.translatable("commands.attribute.base_value.set.success",attribute,name,value),false);return 1;
    }))));
 }
}
