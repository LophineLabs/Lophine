package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** Org's actual online inventory menus, with GCA controls backed by the native Carpet action pack. */
public final class OrgPlayerInventoryMenus {
    private OrgPlayerInventoryMenus() {}

    public static java.util.concurrent.CompletableFuture<Boolean> openOffline(ServerPlayer viewer,java.util.UUID target,boolean ender){
        return OrgMenuNativeEffects.admit(viewer.level().getServer(),()->OrgFakePlayerActions.owned(viewer,()->{
            ServerPlayer live=viewer.level().getServer().getPlayerList().getPlayer(target);
            if(live==null&&viewer.level().getServer().getBotList()!=null)live=viewer.level().getServer().getBotList().getBot(target);
            if(live!=null)return allowedOnline(viewer,live)?openOnlineCommand(viewer,live,ender):java.util.concurrent.CompletableFuture.completedFuture(false);
            if(!allowedOffline(viewer,target))return java.util.concurrent.CompletableFuture.completedFuture(false);
            return OrgOfflineInventorySessions.open(viewer,target,ender);
        }).thenCompose(value->value));
    }
    private static java.util.concurrent.CompletableFuture<Boolean> openOnlineCommand(ServerPlayer viewer,ServerPlayer target,boolean ender){
        return openOnlineAsync(viewer,target,ender,true);
    }
    private static java.util.concurrent.CompletableFuture<Boolean> openOnlineAsync(ServerPlayer viewer,ServerPlayer target,boolean ender,boolean commandAccess){
        if(viewer==target)return OrgMenuNativeEffects.run(viewer,()->(!commandAccess||allowedOnline(viewer,target))&&openMode(viewer,target,ender,commandAccess));
        int previous=viewer.containerMenu.containerId;
        return OrgFakePlayerActions.owned(target,()->OrgFakePlayerActions.whenIdle(target,()->target.isRemoved()||target.isDeadOrDying()?null:RemoteSnapshot.capture(target,ender,!ender&&GeneralCompatConfig.playerCommandOpenPlayerInventoryGcaStyle)))
            .thenCompose(value->value).thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(snapshot->OrgMenuNativeEffects.run(viewer,()->{
                if(snapshot==null||viewer.isRemoved()||viewer.isDeadOrDying()||viewer.containerMenu.containerId!=previous||commandAccess&&!allowedOnline(viewer,target))return false;
                return viewer.openMenu(new SimpleMenuProvider((id,inventory,ignored)->new RemoteMenu(id,inventory,target,snapshot,-1,commandAccess),snapshot.title)).isPresent();
            })));
    }
    /** Caller already holds the true native first-read UUID lease. */
    public static java.util.concurrent.CompletableFuture<Void> recoverOfflineBeforeRead(net.minecraft.server.MinecraftServer server,java.util.UUID player){return OrgOfflineInventorySessions.recoverBeforeRead(server,player).thenCompose(ignored->OrgExperienceTransfers.recoverOfflineBeforeRead(server,player));}
    /** Root Native/Manager first-read already owns the UUID loan for this actual state file. */
    public static java.util.concurrent.CompletableFuture<Void> recoverOfflineBeforeRead(net.minecraft.server.MinecraftServer server,java.util.UUID player,java.nio.file.Path actualStateFile){return OrgOfflineInventorySessions.recoverBeforeRead(server,player).thenCompose(ignored->OrgExperienceTransfers.recoverOfflineBeforeRead(server,player,actualStateFile));}
    public static void beginDrain(net.minecraft.server.MinecraftServer server){OrgOfflineInventorySessions.beginDrain(server);}
    public static void retired(ServerPlayer player){OrgOfflineInventorySessions.retired(player);}
    static boolean optionAllowed(String option,boolean fake,boolean online,boolean operator,boolean protectedTarget){
        boolean type=fake||online&&(option.equals("online_player")||option.equals("all_player"))||!online&&(option.equals("non_whitelist")||option.equals("all_player"));
        return type&&(!option.equals("non_whitelist")||operator||!protectedTarget);
    }
    static boolean allowedOffline(ServerPlayer viewer,java.util.UUID target){
        if(!TickThread.isTickThreadFor(viewer)||viewer.isRemoved()||viewer.isDeadOrDying())return false;
        var server=viewer.level().getServer();if(server.getPlayerList().getPlayer(target)!=null||server.getBotList()!=null&&server.getBotList().getBot(target)!=null)return false;
        return permitted(viewer,new net.minecraft.server.players.NameAndId(target,target.toString()),false,false);
    }
    private static boolean allowedOnline(ServerPlayer viewer,ServerPlayer target){return permitted(viewer,target.nameAndId(),target instanceof org.leavesmc.leaves.bot.ServerBot,true);}
    private static boolean permitted(ServerPlayer viewer,net.minecraft.server.players.NameAndId target,boolean fake,boolean online){
        var source=viewer.createCommandSourceStack();if(!OrgUtilityCommands.permitted(source,GeneralCompatConfig.playerCommandOpenPlayerInventory))return false;
        String option=GeneralCompatConfig.playerCommandOpenPlayerInventoryOption;boolean operator=Commands.LEVEL_GAMEMASTERS.check(source.permissions());var list=source.getServer().getPlayerList();
        // This is the exact upstream PlayerList predicate (including its whitelist enable semantics).
        boolean protectedTarget=option.equals("non_whitelist")&&!operator&&(list.isWhiteListed(target)||list.isOp(target));
        return optionAllowed(option,fake,online,operator,protectedTarget);
    }

    public static boolean canUsePlayerExtensions(CommandSourceStack source) {
        return OrgUtilityCommands.permitted(source, GeneralCompatConfig.playerCommandOpenPlayerInventory)
            || GeneralCompatConfig.playerCommandCloseScreen || OrgPlayerExtraCommands.permitted(source);
    }

    public static boolean interact(ServerPlayer viewer, ServerPlayer target) {
        return interactAsync(viewer,target)!=null;
    }
    /** Null means this source rule does not handle the interaction; otherwise the future is the actual result. */
    public static java.util.concurrent.@org.jspecify.annotations.Nullable CompletableFuture<Boolean> interactAsync(ServerPlayer viewer,ServerPlayer target){
        String mode = GeneralCompatConfig.openPlayerInventory;
        if (!mode.equals("any_player") && !(mode.equals("fake_player") && target instanceof org.leavesmc.leaves.bot.ServerBot)) return null;
        return openAsync(viewer,target,viewer.isShiftKeyDown());
    }
    public static java.util.concurrent.CompletableFuture<Boolean> openAsync(ServerPlayer viewer,ServerPlayer target,boolean ender){
        return OrgMenuNativeEffects.admit(viewer.level().getServer(),()->OrgFakePlayerActions.owned(viewer,()->openOnlineAsync(viewer,target,ender,false)).thenCompose(value->value));
    }

    public static boolean open(ServerPlayer viewer, ServerPlayer target, boolean ender) {
        if (!TickThread.isTickThreadFor(viewer) || !TickThread.isTickThreadFor(target)) return false;
        return openMode(viewer,target,ender,false);
    }
    private static boolean openMode(ServerPlayer viewer,ServerPlayer target,boolean ender,boolean commandAccess){
        if (!TickThread.isTickThreadFor(viewer)) return false;
        if (!TickThread.isTickThreadFor(target)) return false;
        if (target.isRemoved() || target.isDeadOrDying()) return false;
        if (viewer != target) {
            RemoteSnapshot snapshot = RemoteSnapshot.capture(target, ender, !ender && GeneralCompatConfig.playerCommandOpenPlayerInventoryGcaStyle);
            return viewer.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new RemoteMenu(id, inventory, target, snapshot, -1,commandAccess), snapshot.title)).isPresent();
        }
        return viewer.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> {
            if (ender) return new EnderMenu(id, inventory, target);
            return new PlayerMenu(id, inventory, target, GeneralCompatConfig.playerCommandOpenPlayerInventoryGcaStyle);
        }, playerTitle(target))).isPresent();
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        OrgPlayerExtraCommands.register(dispatcher);
        var target = Commands.argument("player", StringArgumentType.word())
            .suggests((context, builder) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(context.getSource().getServer().getPlayerList().getPlayers().stream(), context.getSource().getServer().getBotList().bots.stream()).map(ServerPlayer::getScoreboardName).distinct(), builder));
        for (String name : List.of("inventory", "enderchest", "enderChest")) {
            target.then(Commands.literal(name).requires(source -> OrgUtilityCommands.permitted(source, GeneralCompatConfig.playerCommandOpenPlayerInventory))
                .executes(context -> {
                    ServerPlayer viewer = context.getSource().getPlayerOrException();
                    ServerPlayer selected = context.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(context, "player"));
                    String requested=StringArgumentType.getString(context,"player");var server=context.getSource().getServer();
                    return OrgMenuNativeEffects.snapshotCommand(context.getSource(),viewer,()->{
                        java.util.concurrent.CompletableFuture<java.util.UUID> identity;
                        if(selected!=null)identity=java.util.concurrent.CompletableFuture.completedFuture(selected.getUUID());
                        else{try{identity=java.util.concurrent.CompletableFuture.completedFuture(java.util.UUID.fromString(requested));}
                            catch(IllegalArgumentException notUuid){identity=java.util.concurrent.CompletableFuture.supplyAsync(()->server.services().nameToIdCache().get(requested).orElseThrow(()->new IllegalArgumentException("No offline player identity is known for "+requested)).id());}}
                        return identity.thenCompose(carpet.script.external.ScarpetRuntime.captureNativeFunction(uuid->openOffline(viewer,uuid,!name.equals("inventory"))));
                    },"Cannot open player inventory under the current option or permission");
                }));
        }
        com.mojang.brigadier.Command<CommandSourceStack> closeScreen=context -> {
            ServerPlayer selected = context.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(context, "player"));
            if (!(selected instanceof org.leavesmc.leaves.bot.ServerBot)) {
                context.getSource().sendFailure(Component.literal("The selected player must be an online fake player")); return 0;
            }
            return OrgMenuNativeEffects.command(context.getSource(),()->OrgMenuNativeEffects.run(selected,()->{selected.closeContainer();return true;}),"The fake-player screen could not close");
        };
        for(String name:List.of("esc","closeScreen"))target.then(Commands.literal(name).requires(source -> GeneralCompatConfig.playerCommandCloseScreen).executes(closeScreen));
        dispatcher.register(Commands.literal("player").then(target));
    }

    private record RemoteSnapshot(java.util.UUID target, boolean ender, boolean gca, List<ItemStack> items, List<ItemStack> buttons, Component title) {
        static RemoteSnapshot capture(ServerPlayer target, boolean ender, boolean gca) {
            List<ItemStack> buttons = new ArrayList<>();
            for (int slot = 0; slot < 18; slot++) buttons.add(gca && (slot == 0 || slot == 5 || slot == 6 || slot == 8 || slot >= 9) ? actionButton(target, slot) : ItemStack.EMPTY);
            return new RemoteSnapshot(target.getUUID(), ender, gca, OrgInventoryTransfers.targetState(target, ender), buttons, playerTitle(target));
        }
    }

    /** A private snapshot container owned exclusively by the viewer. It never reads a remote mutable inventory. */
    private static final class RemoteBridge extends SimpleContainer {
        RemoteSnapshot snapshot;
        List<ItemStack> raw;
        List<ItemStack> box;
        final int boxSlot;

        RemoteBridge(RemoteSnapshot snapshot, int boxSlot) {
            super(boxSlot >= 0 ? boxSize(snapshot.items.get(boxSlot)) : snapshot.ender ? snapshot.items.size() : 54);
            this.boxSlot = boxSlot;
            update(snapshot);
        }

        static int boxSize(ItemStack stack) {
            return GeneralCompatConfig.largeShulkerBox || stack.getOrDefault(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY).size() > 27 ? 54 : 27;
        }

        void update(RemoteSnapshot snapshot) {
            this.snapshot = snapshot; this.raw = OrgInventoryTransfers.copies(snapshot.items);
            if (boxSlot >= 0) {
                box = new ArrayList<>(raw.get(boxSlot).getOrDefault(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY).itemCopies().toList());
                while (box.size() < getContainerSize()) box.add(ItemStack.EMPTY);
            }
        }

        int nativeSlot(int slot) {
            if (slot < 0 || slot >= getContainerSize()) return -1;
            if (boxSlot >= 0 || snapshot.ender) return slot;
            if (!snapshot.gca) return slot < 36 ? slot : slot < 40 ? 75 - slot : slot < 43 ? slot : -1;
            return slot >= 1 && slot <= 4 ? 40 - slot : slot == 7 ? 40 : slot >= 18 && slot <= 44 ? slot - 9 : slot >= 45 ? slot - 45 : -1;
        }

        boolean button(int slot) { return boxSlot < 0 && snapshot.gca && nativeSlot(slot) < 0 && slot >= 0 && slot < 18; }
        @Override public ItemStack getItem(int slot) {
            int index = nativeSlot(slot);
            if (index >= 0) return (boxSlot >= 0 ? box : raw).get(index);
            return button(slot) ? snapshot.buttons.get(slot).copy() : ItemStack.EMPTY;
        }
        @Override public void setItem(int slot, ItemStack stack) { int index = nativeSlot(slot); if (index >= 0) (boxSlot >= 0 ? box : raw).set(index, stack); }
        @Override public ItemStack removeItem(int slot, int amount) { int index = nativeSlot(slot); return index < 0 ? ItemStack.EMPTY : getItem(slot).split(amount); }
        @Override public ItemStack removeItemNoUpdate(int slot) { ItemStack stack = getItem(slot); if (nativeSlot(slot) >= 0) setItem(slot, ItemStack.EMPTY); return stack; }
        @Override public void setChanged() {}
        @Override public boolean stillValid(Player player) { return true; }
        @Override public List<ItemStack> getContents() { return java.util.stream.IntStream.range(0, getContainerSize()).mapToObj(this::getItem).toList(); }
        List<ItemStack> result() {
            if (boxSlot >= 0) {
                ItemStack source = raw.get(boxSlot);
                source.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(OrgInventoryTransfers.copies(box)));
            }
            return OrgInventoryTransfers.copies(raw);
        }
    }

    private static final class RemoteMenu extends ChestMenu {
        final ServerPlayer viewer;
        final ServerPlayer target;
        final RemoteBridge bridge;
        boolean commandAccess;
        boolean pending, refreshing;
        long lastRefresh = -1, lastClick = -1;
        boolean doubleClick;

        RemoteMenu(int id, Inventory inventory, ServerPlayer target, RemoteSnapshot snapshot, int boxSlot) {
            this(id, inventory, target, new RemoteBridge(snapshot, boxSlot));
        }
        RemoteMenu(int id,Inventory inventory,ServerPlayer target,RemoteSnapshot snapshot,int boxSlot,boolean commandAccess){this(id,inventory,target,snapshot,boxSlot);this.commandAccess=commandAccess;}
        private RemoteMenu(int id, Inventory inventory, ServerPlayer target, RemoteBridge bridge) {
            super(bridge.getContainerSize() == 27 ? bridge.boxSlot >= 0 ? MenuType.SHULKER_BOX : MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6, id, inventory, bridge, bridge.getContainerSize() / 9);
            this.viewer = (ServerPlayer) inventory.player; this.target = target; this.bridge = bridge;
            for (int slot = 0; slot < bridge.getContainerSize(); slot++) {
                if (bridge.boxSlot >= 0) {
                    Slot old = this.slots.get(slot);
                    Slot guarded = new net.minecraft.world.inventory.ShulkerBoxSlot(bridge, slot, old.x, old.y);
                    guarded.index = slot; this.slots.set(slot, guarded); continue;
                }
                if (bridge.nativeSlot(slot) >= 0) continue;
                Slot old = this.slots.get(slot);
                Slot disabled = new Slot(bridge, slot, old.x, old.y) {
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                    @Override public boolean mayPickup(Player player) { return false; }
                };
                disabled.index = slot; this.slots.set(slot, disabled);
            }
        }

        @Override public boolean stillValid(Player player) {
            return player == viewer && TickThread.isTickThreadFor(player) && !player.isRemoved()
                && viewer.level().getServer().getPlayerList().getPlayer(bridge.snapshot.target) != null
                &&(!commandAccess||allowedOnline(viewer,target));
        }
        @Override public boolean canDragTo(Slot slot) { return slot.container != bridge || bridge.nativeSlot(slot.getContainerSlot()) >= 0; }
        @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return canDragTo(slot); }

        @Override public void broadcastChanges() {
            if (viewer != null && !pending && !refreshing && viewer.containerMenu == this) {
                long tick = viewer.level().getGameTime();
                if (tick - lastRefresh >= 10L) { lastRefresh = tick; refresh(); }
            }
            super.broadcastChanges();
        }

        void refresh() {
            if (refreshing) return;
            refreshing = true;
            // A refresh can originate inside the very mutation whose quiescence it
            // must read. It is an external UI snapshot after that callback returns.
            var actual = new java.util.concurrent.CompletableFuture<Void>();
            carpet.script.external.ScarpetNativeWork.trackNative(viewer.level().getServer(), actual);
            boolean ender = bridge.snapshot.ender, gca = bridge.snapshot.gca;
            Runnable begin = () -> {
                var captured = OrgFakePlayerActions.owned(target, () -> OrgFakePlayerActions.whenIdle(target,
                    () -> target.isDeadOrDying() || target.isRemoved() ? null : RemoteSnapshot.capture(target, ender, gca))).thenCompose(value -> value);
                TisCommandContinuations.then(captured, snapshot -> OrgMenuNativeEffects.run(viewer, () -> {
                    refreshing = false;
                    if (viewer.containerMenu != this || pending) return null;
                    if (snapshot == null || bridge.boxSlot >= 0 && (!OrgQuickShulker.operable(snapshot.items.get(bridge.boxSlot)) || RemoteBridge.boxSize(snapshot.items.get(bridge.boxSlot)) != bridge.getContainerSize())) {
                        viewer.closeContainer(); return null;
                    }
                    bridge.update(snapshot); super.broadcastChanges(); return null;
                })).whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((ignored, failure) -> {
                    if (failure == null) actual.complete(null); else failedRefresh(actual, failure);
                }));
            };
            if (!OrgDeferredPlayerCommands.deferIfCausal(viewer, begin, failure -> failedRefresh(actual, failure))) begin.run();
        }

        private void failedRefresh(java.util.concurrent.CompletableFuture<Void> actual, Throwable failure) {
            OrgMenuNativeEffects.run(viewer, () -> { refreshing = false; if (viewer.containerMenu == this) viewer.closeContainer(); return null; })
                .whenComplete(carpet.script.external.ScarpetRuntime.captureNativeConsumer((ignored, closingFailure) -> {
                    if (closingFailure != null && closingFailure != failure) failure.addSuppressed(closingFailure);
                    actual.completeExceptionally(failure);
                }));
        }

        private void control(int index, boolean right, boolean stop) {
            var applied = controlNative(target, () -> { if (stop) target.carpetActionPack.stopAll(); else press(target, index, right); });
            TisCommandContinuations.then(applied, ignored -> OrgMenuNativeEffects.run(viewer, () -> {
                if (viewer.containerMenu == this) refresh(); return null;
            }));
        }

        @Override public void clicked(int index, int button, ContainerInput input, Player player) {
            if (!stillValid(player) || pending) { broadcastFullState(); return; }
            if (index >= 0 && index < bridge.getContainerSize() && bridge.nativeSlot(index) < 0) {
                if (!bridge.button(index)) return;
                long tick = CarpetServerClock.gameTime();
                if (lastClick == tick) {
                    if (doubleClick) return; doubleClick = true;
                    control(index, button == 1, true);
                } else {
                    lastClick = tick; doubleClick = false;
                    control(index, button == 1, false);
                }
                return;
            }
            if (GeneralCompatConfig.quickShulker && input == ContainerInput.PICKUP && button == 1 && getCarried().isEmpty() && index >= 0 && index < slots.size()) {
                Slot slot = slots.get(index);
                if (OrgQuickShulker.operable(slot.getItem())) {
                    if (slot.container != bridge) { OrgQuickShulker.open(viewer, slot.getItem(), slot); return; }
                    if (bridge.boxSlot < 0) {
                        int source = bridge.nativeSlot(index);
                        viewer.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new RemoteMenu(id, inventory, target, bridge.snapshot, source,commandAccess), slot.getItem().getHoverName()));
                        return;
                    }
                }
            }
            RemoteSnapshot baseline = bridge.snapshot;
            List<ItemStack> before = OrgInventoryTransfers.viewerState(viewer);
            OrgItemShadowGroups.Preview preview = new OrgItemShadowGroups.Preview();
            List<ItemStack> references;
            try {
                bridge.raw = preview.copies(bridge.raw);
                references = OrgInventoryTransfers.installPreview(viewer, preview);
            } catch (IllegalStateException changed) {
                bridge.update(baseline); refresh(); return;
            }
            List<ItemStack> after, targetAfter, drops;
            List<OrgItemShadowGroups.Change> shadows;
            try {
                drops = OrgInventoryTransfers.preview(() -> super.clicked(index, button, input, player));
                targetAfter = bridge.result(); after = OrgInventoryTransfers.viewerState(viewer); shadows = preview.changes();
            } finally {
                OrgInventoryTransfers.restoreReferences(viewer, references); bridge.update(baseline);
            }
            if (OrgInventoryTransfers.same(before, after) && OrgInventoryTransfers.same(baseline.items, targetAfter) && drops.isEmpty()) return;
            // A creative clone changes only the viewer. It does not acquire any target item custody.
            if (input == ContainerInput.CLONE && viewer.hasInfiniteMaterials() && OrgInventoryTransfers.same(baseline.items, targetAfter)) {
                OrgInventoryTransfers.restorePreview(viewer, after); broadcastFullState(); return;
            }
            pending = OrgInventoryTransfers.submit(viewer, baseline.target, baseline.ender, baseline.items, targetAfter, before, after, drops, shadows,
                () -> { pending = false; if (viewer.containerMenu == this) refresh(); });
            if (!pending) { viewer.sendSystemMessage(Component.literal("An inventory transaction is already pending; retry after it finishes")); refresh(); }
            broadcastFullState();
        }

        @Override public ItemStack quickMoveStack(Player player, int index) {
            return bridge.boxSlot >= 0 || bridge.snapshot.ender ? super.quickMoveStack(player, index)
                : quickMove(this, index, bridge.snapshot.gca, this::moveItemStackTo);
        }
    }

    private static boolean valid(Player viewer, ServerPlayer target) {
        return TickThread.isTickThreadFor(viewer) && TickThread.isTickThreadFor(target) && !target.isRemoved() && !target.isDeadOrDying();
    }

    private static final class Bridge extends SimpleContainer {
        final ServerPlayer target;
        final boolean gca;

        Bridge(ServerPlayer target, boolean gca) {
            super(54);
            this.target = target; this.gca = gca;
            this.bukkitOwner = target.getBukkitEntity();
        }

        int nativeSlot(int slot) {
            if (slot < 0 || slot >= 54) return -1;
            if (!gca) return slot < 36 ? slot : slot < 40 ? 75 - slot : slot < 43 ? slot : -1;
            return slot >= 1 && slot <= 4 ? 40 - slot : slot == 7 ? 40
                : slot >= 18 && slot <= 44 ? slot - 9 : slot >= 45 ? slot - 45 : -1;
        }

        boolean button(int slot) { return gca && nativeSlot(slot) < 0 && slot >= 0 && slot < 18; }

        @Override public ItemStack getItem(int slot) {
            if (!TickThread.isTickThreadFor(target)) return ItemStack.EMPTY;
            int nativeSlot = nativeSlot(slot);
            if (nativeSlot >= 0) return target.getInventory().getItem(nativeSlot);
            return button(slot) ? actionButton(target, slot) : ItemStack.EMPTY;
        }
        @Override public ItemStack removeItem(int slot, int amount) {
            int nativeSlot = nativeSlot(slot);
            return nativeSlot >= 0 && TickThread.isTickThreadFor(target) ? target.getInventory().removeItem(nativeSlot, amount) : ItemStack.EMPTY;
        }
        @Override public ItemStack removeItemNoUpdate(int slot) {
            int nativeSlot = nativeSlot(slot);
            return nativeSlot >= 0 && TickThread.isTickThreadFor(target) ? target.getInventory().removeItemNoUpdate(nativeSlot) : ItemStack.EMPTY;
        }
        @Override public void setItem(int slot, ItemStack stack) {
            int nativeSlot = nativeSlot(slot);
            if (nativeSlot >= 0 && TickThread.isTickThreadFor(target)) target.getInventory().setItem(nativeSlot, stack);
        }
        @Override public void setChanged() { if (TickThread.isTickThreadFor(target)) target.getInventory().setChanged(); }
        @Override public boolean stillValid(Player player) { return valid(player, target); }
        @Override public List<ItemStack> getContents() { return java.util.stream.IntStream.range(0, 54).mapToObj(this::getItem).toList(); }
        @Override public boolean isEmpty() { return TickThread.isTickThreadFor(target) && target.getInventory().isEmpty(); }
    }

    private static net.minecraft.network.chat.MutableComponent buttonText(String suffix,String fallback,Object...args){String key="carpet-org-addition.button."+suffix;return Component.translatableWithFallback(key,OrgRuleTranslations.text(key,fallback),args);}
    static Component playerTitle(ServerPlayer target){
        var profile=target.getGameProfile();var resolved=profile==null?net.minecraft.world.item.component.ResolvableProfile.createUnresolved(target.getUUID()):net.minecraft.world.item.component.ResolvableProfile.createResolved(profile);
        return Component.object(new net.minecraft.network.chat.contents.objects.PlayerSprite(resolved,true)).withStyle(net.minecraft.ChatFormatting.WHITE).append(" ").append(target.getDisplayName().copy());
    }
    static Component offlineTitle(java.util.UUID target,String name){
        String key="carpet-org-addition.operation.offline_player_name";
        return Component.object(new net.minecraft.network.chat.contents.objects.PlayerSprite(net.minecraft.world.item.component.ResolvableProfile.createUnresolved(target),true)).withStyle(net.minecraft.ChatFormatting.WHITE).append(" ").append(Component.translatableWithFallback(key,OrgRuleTranslations.text(key,"%s (Offline)"),name));
    }
    private static ItemStack actionButton(ServerPlayer player, int slot) {
        CarpetPlayerActionPack pack = player.carpetActionPack;
        var attack = pack.getAction(CarpetPlayerActionPack.ActionType.ATTACK);
        var use = pack.getAction(CarpetPlayerActionPack.ActionType.USE);
        boolean active = slot >= 9 ? player.getInventory().getSelectedSlot() == slot - 9
            : slot == 5 ? attack != null && attack.interval == 12 : slot == 6 ? attack != null && attack.isContinuous() : slot == 8 && use != null && use.isContinuous();
        ItemStack icon = new ItemStack(Items.APPLE, slot >= 9 ? slot - 8 : 1);
        icon.set(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(active ? Items.BARRIER : Items.STRUCTURE_VOID));
        var state=buttonText(active?"on":"off",active?"On":"Off").withStyle(net.minecraft.ChatFormatting.BOLD,active?net.minecraft.ChatFormatting.GREEN:net.minecraft.ChatFormatting.RED);
        var name=slot>=9?buttonText("hotbar","Hotbar: %s",slot-8):slot==0?buttonText("action.stop.left","Click to stop all actions")
            :slot==5?buttonText("action.attack.interval","Attack every %s game tick: %s",12,state):slot==6?buttonText("action.attack.continuous","Hold left-click to: %s",state):buttonText("action.use.continuous","Hold right-click to: %s",state);
        icon.set(DataComponents.CUSTOM_NAME,name.withStyle(style->style.withItalic(false).withBold(true).withColor(net.minecraft.ChatFormatting.WHITE)));
        String lore=slot==0?"action.stop.right":slot==6?"action.attack.continuous.right":slot==8?"action.use.continuous.right":slot==9?"hotbar.right.1":slot==10?"hotbar.right.2":null;
        if(lore!=null)icon.set(DataComponents.LORE,new net.minecraft.world.item.component.ItemLore(List.of(buttonText(lore,switch(slot){case 0->"Right click: Sort Inventory";case 6->"Right click: Left click once";case 8->"Right click: Right click once";case 9->"Right click: Toggle Sneak";default->"Right click: Close current GUI";}).withStyle(net.minecraft.ChatFormatting.GRAY,net.minecraft.ChatFormatting.ITALIC))));
        CompoundTag marker = new CompoundTag(); marker.putBoolean("GcaClear", true); marker.putBoolean("carpet-org-addition:button_item", true);
        icon.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
        return icon;
    }

    private static final class PlayerMenu extends ChestMenu {
        private final ServerPlayer target;
        private final Bridge bridge;
        private long lastClick = -1;
        private boolean doubleClick;

        PlayerMenu(int id, Inventory inventory, ServerPlayer target, boolean gca) {
            super(MenuType.GENERIC_9x6, id, inventory, new Bridge(target, gca), 6);
            this.target = target; this.bridge = (Bridge) this.slots.getFirst().container;
            for (int index = 0; index < 54; index++) {
                Slot previous = this.slots.get(index);
                if (bridge.nativeSlot(index) >= 0) continue;
                Slot guarded = new Slot(bridge, index, previous.x, previous.y) {
                    @Override public boolean mayPlace(ItemStack stack) { return false; }
                    @Override public boolean mayPickup(Player player) { return false; }
                };
                guarded.index = index;
                this.slots.set(index, guarded);
            }
        }

        @Override public boolean stillValid(Player player) { return valid(player, target); }
        @Override public boolean canDragTo(Slot slot) { return slot.container != bridge || bridge.nativeSlot(slot.getContainerSlot()) >= 0; }
        @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return canDragTo(slot); }

        @Override public void clicked(int index, int button, ContainerInput input, Player player) {
            if (!stillValid(player)) return;
            if (index >= 0 && index < 54 && bridge.nativeSlot(index) < 0) {
                if (!bridge.button(index)) return;
                long tick = CarpetServerClock.gameTime();
                if (lastClick == tick) {
                    if (doubleClick) return;
                    controlNative(target, () -> target.carpetActionPack.stopAll()); doubleClick = true; return;
                }
                lastClick = tick; doubleClick = false;
                controlNative(target, () -> press(target, index, button == 1));
                return;
            }
            super.clicked(index, button, input, player);
        }
        @Override public ItemStack quickMoveStack(Player player, int index) { return quickMove(this, index, bridge.gca, this::moveItemStackTo); }
    }

    private static java.util.concurrent.CompletableFuture<Void> controlNative(ServerPlayer target, Runnable operation) {
        return TisCommandContinuations.then(OrgMenuNativeEffects.run(target, () -> {
            var completed = new java.util.concurrent.atomic.AtomicBoolean();
            OrgItemShadowGroups.actor(target, () -> OrgItemShadowGroups.inventory(target, target.containerMenu.slots),
                () -> { operation.run(); completed.set(true); return true; }, false, owner -> owner == target && !target.isRemoved() && !target.isDeadOrDying(), ignored -> {});
            return completed;
        }), completed -> completed.get() ? java.util.concurrent.CompletableFuture.completedFuture(null)
            : java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("The inventory control target retired before the actual operation")));
    }

    @FunctionalInterface private interface Move { boolean apply(ItemStack stack, int start, int end, boolean reverse); }
    private static ItemStack quickMove(AbstractContainerMenu menu, int index, boolean gca, Move move) {
        if (index < 0 || index >= menu.slots.size()) return ItemStack.EMPTY;
        Slot slot = menu.slots.get(index); ItemStack stack = slot.getItem(), before = stack.copy();
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (index < 54) { if (!move.apply(stack, 54, menu.slots.size(), true)) return ItemStack.EMPTY; }
        else if (!gca) { if (!move.apply(stack, 0, 41, false)) return ItemStack.EMPTY; }
        else {
            var equip = stack.get(DataComponents.EQUIPPABLE);
            int armor = equip == null ? -1 : switch (equip.slot()) { case HEAD -> 0; case CHEST -> 1; case LEGS -> 2; case FEET -> 3; default -> -1; };
            if (armor >= 0 && (!move.apply(stack, armor + 1, armor + 2, false) && !move.apply(stack, 18, 54, false)) && !move.apply(stack, 7, 8, false) && stack.isEmpty()) return ItemStack.EMPTY;
            if ((stack.has(DataComponents.FOOD) || stack.has(DataComponents.DEATH_PROTECTION))
                && (!move.apply(stack, 7, 8, false) && !move.apply(stack, 18, 54, false) && !move.apply(stack, 1, 5, false))) return ItemStack.EMPTY;
            if (!move.apply(stack, 18, 54, false) && !move.apply(stack, 1, 5, false) && !move.apply(stack, 7, 8, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return before;
    }

    private static void press(ServerPlayer target, int slot, boolean right) {
        CarpetPlayerActionPack pack = target.carpetActionPack;
        if (slot == 0) {
            if (right) sort(target); else pack.stopAll();
        } else if (slot >= 9) {
            if (right && slot == 9) pack.setSneaking(!target.isShiftKeyDown());
            else if (right && slot == 10) target.closeContainer();
            else pack.setSlot(slot - 8);
        } else if (slot == 5) {
            var current = pack.getAction(CarpetPlayerActionPack.ActionType.ATTACK);
            pack.start(CarpetPlayerActionPack.ActionType.ATTACK, current != null && current.interval == 12 ? CarpetPlayerActionPack.Action.once() : CarpetPlayerActionPack.Action.interval(12));
        } else {
            var type = slot == 6 ? CarpetPlayerActionPack.ActionType.ATTACK : CarpetPlayerActionPack.ActionType.USE;
            if (right) type.execute(target, CarpetPlayerActionPack.Action.once());
            else {
                var current = pack.getAction(type);
                pack.start(type, current != null && current.isContinuous() ? CarpetPlayerActionPack.Action.once() : CarpetPlayerActionPack.Action.continuous());
            }
        }
    }

    private static void sort(ServerPlayer target) {
        var inventory = target.getInventory();
        ArrayList<Integer> slots = new ArrayList<>();
        for (int offset = 0; offset < 36; offset++) {
            int slot = offset < 27 ? offset + 9 : offset - 27;
            ItemStack stack = inventory.getItem(slot);
            if (slot != inventory.getSelectedSlot() && stack.getCount() <= stack.getMaxStackSize()) slots.add(slot);
        }
        for (int first = 0; first < slots.size(); first++) {
            ItemStack source = inventory.getItem(slots.get(first));
            if (source.isEmpty()) continue;
            for (int later = first + 1; later < slots.size(); later++) {
                ItemStack destination = inventory.getItem(slots.get(later));
                if (destination == source || destination.isEmpty() || !ItemStack.isSameItemSameComponents(source, destination)) continue;
                int count = Math.min(source.getCount(), Math.max(0, destination.getMaxStackSize() - destination.getCount()));
                source.shrink(count); destination.grow(count);
            }
        }
        ArrayList<ItemStack> stacks = new ArrayList<>();
        for (int slot : slots) stacks.add(inventory.getItem(slot));
        sortStacks(stacks);
        for (int index = 0; index < slots.size(); index++) inventory.setItem(slots.get(index), stacks.get(index));
        inventory.setChanged();
    }
    private static void sortStacks(List<ItemStack> stacks) {
        if (stacks.isEmpty()) return;
        int start = 0, end = stacks.size() - 1; ItemStack pivot = stacks.getFirst();
        while (start < end) {
            boolean changed = false;
            while (end > start && compareStacks(pivot, stacks.get(end)) <= 0) { end--; changed = true; }
            while (end > start && compareStacks(pivot, stacks.get(start)) >= 0) { start++; changed = true; }
            if (!changed) throw new IllegalStateException("Trapped in an infinite loop while sorting items");
            java.util.Collections.swap(stacks, start, end);
        }
        java.util.Collections.swap(stacks, 0, start);
        sortStacks(stacks.subList(0, start)); sortStacks(stacks.subList(start + 1, stacks.size()));
    }

    private static int compareStacks(ItemStack first, ItemStack second) {
        if (ItemStack.isSameItemSameComponents(first, second)) return -Integer.compare(first.getCount(), second.getCount());
        if (first.isEmpty()) return 1;
        if (second.isEmpty()) return -1;
        if (first.is(second.getItem())) {
            if (OrgGameplayHelper.isShulkerBox(first)) return compareBoxes(first, second);
            int components = Integer.compare(first.getComponents().size(), second.getComponents().size());
            if (components != 0) return components;
            // The pinned source returns this equal component-size comparison when
            // different component values also have different stack counts.
            if (first.getCount() != second.getCount()) return components;
            return Integer.compare(ItemStack.hashItemAndComponents(first), ItemStack.hashItemAndComponents(second));
        }
        boolean firstBox = OrgGameplayHelper.isShulkerBox(first), secondBox = OrgGameplayHelper.isShulkerBox(second);
        if (firstBox != secondBox) return firstBox ? 1 : -1;
        return BuiltInRegistries.ITEM.getKey(first.getItem()).compareTo(BuiltInRegistries.ITEM.getKey(second.getItem()));
    }
    private static int compareBoxes(ItemStack first, ItemStack second) {
        var firstItems = first.getOrDefault(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY).nonEmptyItemCopyStream().toList();
        var secondItems = second.getOrDefault(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY).nonEmptyItemCopyStream().toList();
        if (firstItems.isEmpty() && secondItems.isEmpty()) return 0;
        if (firstItems.isEmpty()) return 1;
        if (secondItems.isEmpty()) return -1;
        var firstType = firstItems.getFirst().getItem(); var secondType = secondItems.getFirst().getItem();
        boolean firstSingle = firstItems.stream().allMatch(stack -> stack.is(firstType)), secondSingle = secondItems.stream().allMatch(stack -> stack.is(secondType));
        if (firstSingle != secondSingle) return firstSingle ? -1 : 1;
        if (firstSingle) { int item = BuiltInRegistries.ITEM.getKey(firstType).compareTo(BuiltInRegistries.ITEM.getKey(secondType)); if (item != 0) return item; }
        int count = -Integer.compare(firstItems.stream().mapToInt(ItemStack::getCount).sum(), secondItems.stream().mapToInt(ItemStack::getCount).sum());
        return count != 0 ? count : Integer.compare(ItemStack.hashItemAndComponents(first), ItemStack.hashItemAndComponents(second));
    }

    private static final class EnderMenu extends ChestMenu {
        private final ServerPlayer target;
        EnderMenu(int id, Inventory inventory, ServerPlayer target) {
            super(target.getEnderChestInventory().getContainerSize() == 54 ? MenuType.GENERIC_9x6 : MenuType.GENERIC_9x3,
                id, inventory, target.getEnderChestInventory(), target.getEnderChestInventory().getContainerSize() / 9);
            this.target = target;
        }
        @Override public boolean stillValid(Player player) { return TickThread.isTickThreadFor(player) && TickThread.isTickThreadFor(target) && !target.isRemoved(); }
        @Override public void clicked(int index, int button, ContainerInput input, Player player) {
            if (stillValid(player)) super.clicked(index, button, input, player);
        }
    }
}
