// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;

/** Vanilla 27-slot client GUI; every accepted server click has a durable file actor after image. */
final class OrgMailDraftMenu extends ChestMenu {
    final ServerPlayer player;
    final OrgMailService mail;
    final int parcel;
    final SimpleContainer top;
    boolean closed;
    private CompletableFuture<Void> tail=CompletableFuture.completedFuture(null);
    OrgMailDraftMenu(int id,Inventory inventory,ServerPlayer player,OrgMailService mail,int parcel){this(id,inventory,player,mail,parcel,new SimpleContainer(27));}
    private OrgMailDraftMenu(int id,Inventory inventory,ServerPlayer player,OrgMailService mail,int parcel,SimpleContainer top){super(MenuType.GENERIC_9x3,id,inventory,top,3);this.player=player;this.mail=mail;this.parcel=parcel;this.top=top;}
    @Override public void clicked(int slot,int button,ContainerInput click,Player viewer){
        if(viewer!=player||closed)return;
        var before=tail;tail=OrgMenuNativeEffects.admit(player.level().getServer(),()->{
            var clicked=TisCommandContinuations.then(before.handle((ignored,error)->null),ignored->mail.draftClick(this,slot,button,click));
            return TisCommandContinuations.then(clicked.handle((success,error)->error),error->error==null?CompletableFuture.completedFuture(null):TisCommandContinuations.owned(player,()->{player.sendSystemMessage(OrgMailPresentation.mail("send.multiple.error"));if(player.containerMenu==this)broadcastFullState();return null;}));
        });
    }
    void previewClick(int slot,int button,ContainerInput click){super.clicked(slot,button,click,player);}
    @Override public void removed(Player viewer){
        super.removed(viewer);if(closed)return;closed=true;
        // Durable draft assets belong to the file actor, so close/retirement does not drop
        // or restore an old in-memory snapshot over a later legitimate inventory write.
        var before=tail;tail=OrgMenuNativeEffects.admit(player.level().getServer(),()->TisCommandContinuations.then(before.handle((ignored,error)->null),ignored->mail.publishDraft(parcel)).thenApply(ignored->null));
    }
}
