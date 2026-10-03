// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/** Original resumable controllers are immutable metadata; their real stop effects finish before inventory saving. */
final class OrgShutdownActionSnapshot {
    private final CarpetPlayerActionPack.RemovalSnapshot nativeActions;
    private final OrgFakePlayerActions.Action publicAction;
    private final JsonObject hidden;
    private final JsonObject script;
    private final CompoundTag pack;
    private final boolean sneaking;
    private final boolean usingItem;
    private final net.minecraft.world.item.ItemStack useItem;
    private final net.minecraft.world.InteractionHand useHand;
    private final long useStartTime;
    private OrgShutdownActionSnapshot(ServerPlayer player){
        nativeActions=player.carpetActionPack.captureForRemoval();publicAction=OrgFakePlayerActions.get(player);hidden=OrgHiddenPlayerActions.get(player).deepCopy();
        script=hidden.get("name").getAsString().equals("stop")?OrgFakePlayerActionCodec.write(publicAction):OrgHiddenPlayerActions.save(player).deepCopy();
        pack=OrgHiddenPlayerActions.sanitizeActionPackSnapshot(player,nativeActions.metadata());sneaking=player.isShiftKeyDown();
        usingItem=player.isUsingItem();useItem=player.getUseItem();useHand=player.getUsedItemHand();useStartTime=player.carpetOrgUseStartTime();
    }
    static OrgShutdownActionSnapshot freeze(ServerPlayer player){ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(player,"Shutdown controller freeze requires its owner");return new OrgShutdownActionSnapshot(player);}
    CompletableFuture<Void> stop(ServerPlayer player){
        var actual=carpet.script.external.ScarpetNativeWork.<Void>observeNative(player,()->{
            boolean samePublic=publicAction.kind().equals("stop")||OrgFakePlayerActions.get(player)==publicAction;
            boolean sameHidden=OrgHiddenPlayerActions.get(player).equals(hidden);
            if(samePublic&&sameHidden){
                // Only the two old, identity-checked controller terminations are privileged.
                try(var accepted=carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)){
                    OrgFakePlayerActions.set(player,OrgFakePlayerActions.Action.simple("stop",List.of()));
                }
            }
            var nativeStop=player.carpetActionPack.stopForRemoval(nativeActions);carpet.script.external.ScarpetNativeWork.record(nativeStop);
            // Hidden eating starts the native use-item controller without registering
            // a pack USE action. Stop only that frozen controller, and never a later
            // same-item/same-hand use started by a plugin or replacement action.
            if(usingItem&&player.isUsingItem()&&player.getUseItem()==useItem&&player.getUsedItemHand()==useHand&&player.carpetOrgUseStartTime()==useStartTime){
                try(var accepted=carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)){player.releaseUsingItem();}
            }
            return null;
        });
        carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(player,actual);return actual;
    }
    void apply(JsonObject profile)throws IOException{
        profile.add("script_action",script.deepCopy());var simple=new JsonObject();
        for(Tag value:pack.getListOrEmpty("actions"))if(value instanceof CompoundTag action){var entry=new JsonObject();entry.addProperty("interval",action.getIntOr("interval",1));entry.addProperty("continuous",action.getBooleanOr("continuous",false));simple.add(action.getStringOr("type","").toLowerCase(java.util.Locale.ROOT),entry);}
        profile.add("simple_action",simple);profile.addProperty("_lophine_action_pack",OrgPlayerManager.encode(pack));profile.addProperty("sneaking",sneaking);
    }
}
