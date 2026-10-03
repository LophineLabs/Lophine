// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.event.bot.BotRemoveEvent;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;

/** Runs only after the actual native damage remainder, including deferred event continuations. */
public final class OrgSafeAfk {
    private static final ScopedValue<Frame> DAMAGE = ScopedValue.newInstance();
    private static final class Frame {
        final ServerPlayer player;
        volatile boolean totem, actual;
        final java.util.concurrent.atomic.AtomicBoolean checked=new java.util.concurrent.atomic.AtomicBoolean();
        Frame(ServerPlayer player) { this.player=player; }
    }
    private OrgSafeAfk() {}
    public static <T> T withDamage(ServerPlayer player,DamageSource source,float amount,Supplier<T> original) {
        if(!(player instanceof ServerBot))return original.get();
        if(DAMAGE.isBound()&&DAMAGE.get().player==player){DAMAGE.get().actual=true;return original.get();}
        Frame frame=new Frame(player);frame.actual=true;
        return scoped(player,source,amount,frame,original,false);
    }
    /** Outer SP eligibility returns are checked only when no asynchronous damage is pending. */
    public static <T> T withDamageOuter(ServerPlayer player,DamageSource source,float amount,Supplier<T> original) {
        if(!(player instanceof ServerBot))return original.get();
        if(DAMAGE.isBound()&&DAMAGE.get().player==player)return original.get();
        Frame frame=new Frame(player);
        return scoped(player,source,amount,frame,original,true);
    }
    private static <T> T scoped(ServerPlayer player,DamageSource source,float amount,Frame frame,Supplier<T> original,boolean outer){
        var before=carpet.script.external.ScarpetDamageContinuations.pendingBodyResult(player);
        // Capture the wrapper's parent scope before original enters its independent
        // death/body observer. Recording back into the awaited child would self-wait.
        var parent=carpet.script.external.ScarpetNativeWork.capture();
        var continuation=carpet.script.external.ScarpetRuntime.captureNativeContinuation(()->{
            try(var accepted=carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)){
                return carpet.script.external.ScarpetNativeWork.<Void>observeNative(player,()->{afterDamage(player,source,amount,frame.totem);return null;});
            }
        });
        return ScopedValue.where(DAMAGE,frame).call(()->{
            T result=original.get();var actual=carpet.script.external.ScarpetDamageContinuations.pendingBodyResult(player);
            if(actual!=null&&actual!=before&&!actual.isDone()){
                if(frame.checked.compareAndSet(false,true)){
                    var checked=new java.util.concurrent.CompletableFuture<Void>();
                    carpet.script.external.ScarpetNativeWork.with(parent,()->carpet.script.external.ScarpetNativeWork.record(checked));
                    carpet.script.external.ScarpetNativeWork.trackNative(player.level().getServer(),checked);
                    carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(player,checked);
                    actual.whenComplete((value,failure)->{
                        if(failure!=null){checked.completeExceptionally(failure);return;}
                        if(ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player)){
                            try{continuation.get().whenComplete((ignored,error)->{if(error==null)checked.complete(null);else checked.completeExceptionally(error);});}
                            catch(Throwable error){checked.completeExceptionally(error);}return;
                        }
                        boolean scheduled=player.getBukkitEntity().taskScheduler.schedule(owner->{
                            if(owner!=player){checked.completeExceptionally(new IllegalStateException("SafeAFK damage owner identity changed"));return;}
                            try{continuation.get().whenComplete((ignored,error)->{if(error==null)checked.complete(null);else checked.completeExceptionally(error);});}
                            catch(Throwable error){checked.completeExceptionally(error);}
                        },retired->checked.completeExceptionally(new IllegalStateException("SafeAFK actual damage owner retired")),1L);
                        if(!scheduled)checked.completeExceptionally(new IllegalStateException("SafeAFK actual damage owner retired"));
                    });
                }
            }else if((!outer||frame.actual||carpet.script.external.ScarpetDamageContinuations.pendingCompletion(player)==null)&&frame.checked.compareAndSet(false,true))afterDamage(player,source,amount,frame.totem);
            return result;
        });
    }
    public static void markTotemUsed(ServerPlayer player) { if(DAMAGE.isBound()&&DAMAGE.get().player==player)DAMAGE.get().totem=true; }
    public static void afterDamage(ServerPlayer player,DamageSource source,float amount,boolean totemUsed) {
        if(!(player instanceof ServerBot bot))return;
        float threshold=OrgPlayerManager.safeThreshold(player);if(threshold<=0 || totemUsed || canProtect(player,source))return;
        boolean failed=player.getHealth()<=0 || player.isDeadOrDying();
        if(!failed && (player.isRemoved()||player.getHealth()>threshold))return;
        var server=player.level().getServer();Component name=player.getDisplayName().copy();float health=player.getHealth();
        net.minecraft.world.entity.Entity attacker=source.getEntity(),direct=source.getDirectEntity();String damageType=source.getMsgId();
        OrgMenuNativeEffects.admit(server,()->{
            var message=TisCommandContinuations.then(displayName(attacker),attackerName->TisCommandContinuations.then(displayName(direct),directName->{
                String key="carpet-org-addition.command.playerManager.safeafk.trigger.";
                Component report=Component.literal(translated(key+"info.attacker","Attacker:%s",attackerName.getString()))
                    .append(Component.literal("\n"+translated(key+"info.source","Source:%s",directName.getString())))
                    .append(Component.literal("\n"+translated(key+"info.type","Damage type:%s",damageType)))
                    .append(Component.literal("\n"+translated(key+"info.amount","Amount:%s",String.valueOf(amount))));
                String text=failed?translated(key+"fail","%s's safe AFK trigger failed",name.getString())
                    :translated(key+"success","%s triggered safe AFK when their health reached %s",name.getString(),decimal(health));
                return java.util.concurrent.CompletableFuture.completedFuture(Component.literal(text)
                    .withStyle(style->style.withColor(failed?ChatFormatting.RED:ChatFormatting.GRAY).withItalic(true).withHoverEvent(new HoverEvent.ShowText(report))));
            }));
            return TisCommandContinuations.then(message,notice->TisCommandContinuations.then(
                OrgCommandNativeEffects.global(server,()->OrgCommandNativeEffects.broadcast(server,notice)).thenCompose(value->value),
                ignored->TisCommandContinuations.then(TisCommandContinuations.phase(null,()->{MinecraftServer.LOGGER.info("{}",notice.getString());return null;}),logged->{
                    if(failed)return java.util.concurrent.CompletableFuture.completedFuture(null);
                    return TisCommandContinuations.then(OrgMenuNativeEffects.run(player,()->{
                        player.getFoodData().setFoodLevel(20);
                        return server.getBotList().carpetRemoveBotAsync(bot,BotRemoveEvent.RemoveReason.COMMAND,null,true,false);
                    }).thenCompose(value->value),removed->Boolean.TRUE.equals(removed)?java.util.concurrent.CompletableFuture.completedFuture(null)
                        :java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("SafeAFK fake player removal was denied")));
                })));
        });
    }
    private static java.util.concurrent.CompletableFuture<Component> displayName(net.minecraft.world.entity.Entity entity){
        if(entity==null)return java.util.concurrent.CompletableFuture.completedFuture(Component.literal("null"));
        var captured=carpet.script.external.ScarpetAttribution.data(entity);
        return captured!=null?java.util.concurrent.CompletableFuture.completedFuture(captured.displayName().copy())
            :TisCommandContinuations.owned(entity,()->entity.getDisplayName().copy());
    }
    private static String translated(String key,String fallback,Object... arguments){return String.format(java.util.Locale.ROOT,OrgRuleTranslations.text(key,fallback),arguments);}
    private static String decimal(float value){return java.math.BigDecimal.valueOf(value).setScale(2,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    private static boolean canProtect(ServerPlayer player,DamageSource source) {
        if(source.is(DamageTypeTags.BYPASSES_INVULNERABILITY))return false;
        for(InteractionHand hand:InteractionHand.values())if(totem(player.getItemInHand(hand)))return true;
        String mode=GeneralCompatConfig.betterTotemOfUndying;if(mode.equals("vanilla"))return false;
        for(ItemStack stack:player.getInventory().getNonEquipmentItems()){
            if(totem(stack))return true;
            if(mode.equals("inventory_with_shulker_box")&&OrgGameplayHelper.isShulkerBox(stack)&&stack.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).nonEmptyItemCopyStream().anyMatch(OrgSafeAfk::totem))return true;
        }
        return false;
    }
    private static boolean totem(ItemStack stack){return !stack.isEmpty()&&stack.has(DataComponents.DEATH_PROTECTION);}
}
