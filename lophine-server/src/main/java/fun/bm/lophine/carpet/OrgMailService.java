// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.leavesmc.leaves.bot.ServerBot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Parcel documents are file actors; live item custody uses the inventory escrow protocol.
 */
public final class OrgMailService {
    static final String DIRECTORY = "config/carpet-org-addition/express/";
    private static final Map<MinecraftServer, OrgMailService> SERVICES = Collections.synchronizedMap(new WeakHashMap<>());
    private static Executor fileExecutor = java.util.concurrent.ForkJoinPool.commonPool();
    private static final Map<ServerPlayer, Boolean> NOTICED = Collections.synchronizedMap(new WeakHashMap<>());
    private final MinecraftServer server;
    private final Path root;
    private final ConcurrentSkipListMap<Integer, CompoundTag> documents = new ConcurrentSkipListMap<>();
    private final java.util.Set<Integer> reserved = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Void> loaded;

    public enum Operation {COLLECT, RECALL, INTERCEPT}

    private OrgMailService(MinecraftServer server) {
        this.server = server;
        root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        OrgWorldFormat.directory(root);
        loaded = io(() -> {
            load();
            return null;
        });
    }

    public static OrgMailService get(MinecraftServer server) {
        synchronized (SERVICES) {
            return SERVICES.computeIfAbsent(server, OrgMailService::new);
        }
    }

    private static <T> CompletableFuture<T> io(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, fileExecutor);
    }

    private static RuntimeException failure(Exception failure) {
        return new java.util.concurrent.CompletionException(failure);
    }

    static UUID participant(int id) {
        return UUID.nameUUIDFromBytes(("CarpetOrgAddition:parcel:" + id).getBytes(StandardCharsets.UTF_8));
    }

    static String relative(int id) {
        if (id < 1) throw new IllegalArgumentException("Invalid parcel number");
        return DIRECTORY + id + ".nbt";
    }

    private Path path(int id) throws IOException {
        Path path = root.resolve(relative(id)).normalize();
        if (!path.startsWith(root) || Files.isSymbolicLink(path)) throw new IOException("Parcel path leaves its world");
        for (Path parent = path.getParent(); parent != null && !parent.equals(root); parent = parent.getParent())
            if (Files.isSymbolicLink(parent)) throw new IOException("Parcel directory is a symbolic link");
        return path;
    }

    private CompoundTag read(int id) {
        try {
            Path file = path(id);
            if (!Files.exists(file)) return null;
            if (Files.size(file) > 64L * 1024 * 1024) throw new IOException("Parcel document is too large");
            CompoundTag tag = NbtIo.read(file);
            if (tag == null) throw new IOException("Parcel document is empty");
            return tag;
        } catch (IOException error) {
            throw failure(error);
        }
    }

    private void load() {
        Path directory = root.resolve(DIRECTORY);
        try {
            if (!Files.exists(directory)) return;
            if (Files.isSymbolicLink(directory)) throw new IOException("Parcel directory is a symbolic link");
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().matches("[1-9][0-9]*\\.nbt")).toList()) {
                    int id;
                    try {
                        id = Integer.parseInt(file.getFileName().toString().replace(".nbt", ""));
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                    // A corrupt existing record reserves its number. Never overwrite its assets.
                    try {
                        CompoundTag document = read(id);
                        if (document != null) {
                            validateMetadata(document);
                            documents.put(id, document);
                            if (document.getBooleanOr("_lophine_draft", false)) publishDraft(id);
                        }
                    } catch (RuntimeException error) {
                        reserved.add(id);
                        MinecraftServer.LOGGER.error("Cannot load Org parcel {}", id, error);
                    }
                }
            }
        } catch (IOException error) {
            throw failure(error);
        }
    }

    static void validateMetadata(CompoundTag raw) {
        CompoundTag tag = format(raw);
        if (tag.getStringOr("sender", "").isBlank() || tag.getStringOr("recipient", "").isBlank())
            throw new IllegalArgumentException("Parcel is missing its sender or recipient");
        int version = tag.getIntOr("data_version", 0);
        if (version != 3) throw new IllegalArgumentException("Unsupported parcel format " + version);
        if (!(tag.get("items") instanceof ListTag))
            throw new IllegalArgumentException("Parcel is missing its item list");
        String uuid = tag.getStringOr("uuid", "");
        if (!uuid.isEmpty()) UUID.fromString(uuid);
    }

    static CompoundTag format(CompoundTag raw) {
        CompoundTag tag = raw.copy();
        if (tag.getIntOr("data_version", 0) < 3) {
            if (tag.contains("NbtDataVersion")) tag.put("minecraft_data_version", tag.get("NbtDataVersion").copy());
            if (tag.contains("cancel")) tag.put("recall", tag.get("cancel").copy());
            if (tag.contains("item")) {
                var list = new ListTag();
                list.add(tag.get("item").copy());
                tag.put("items", list);
            }
            tag.remove("NbtDataVersion");
            tag.remove("cancel");
            tag.remove("item");
            tag.putInt("data_version", 3);
        }
        return tag;
    }

    static List<ItemStack> items(MinecraftServer server, CompoundTag raw, DynamicOps<Tag> ops) {
        CompoundTag tag = format(raw);
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        int version = tag.getIntOr("minecraft_data_version", raw.getIntOr("NbtDataVersion", -1));
        if (version < 0 && raw.get("item") instanceof CompoundTag old)
            version = old.contains("Count") || old.contains("tag") ? 3465 : 3837;
        List<ItemStack> result = new ArrayList<>();
        for (Tag entry : tag.getListOrEmpty("items")) {
            Tag fixed = version >= 0 && version < current ? server.getFixerUpper().update(References.ITEM_STACK, new Dynamic<Tag>(NbtOps.INSTANCE, entry), version, current).getValue() : entry;
            ItemStack stack = ItemStack.CODEC.parse(ops, fixed).getOrThrow();
            if (!stack.isEmpty()) result.add(stack);
        }
        return result;
    }

    static CompoundTag withItems(CompoundTag before, List<ItemStack> stacks, DynamicOps<Tag> ops) {
        CompoundTag after = format(before);
        var list = new ListTag();
        for (ItemStack stack : stacks)
            if (!stack.isEmpty()) list.add(ItemStack.CODEC.encodeStart(ops, stack.copy()).getOrThrow());
        after.put("items", list);
        after.putInt("minecraft_data_version", SharedConstants.getCurrentVersion().dataVersion().version());
        return after;
    }

    static CompoundTag document(String sender, String recipient, UUID uuid, List<ItemStack> items, DynamicOps<Tag> ops) {
        var tag = new CompoundTag();
        tag.putInt("data_version", 3);
        tag.putString("sender", sender);
        tag.putString("recipient", recipient);
        if (uuid != null) tag.putString("uuid", uuid.toString());
        tag.putBoolean("recall", false);
        LocalDateTime time = LocalDateTime.now();
        tag.putIntArray("time", new int[]{time.getYear(), time.getMonthValue(), time.getDayOfMonth(), time.getHour(), time.getMinute(), time.getSecond()});
        return withItems(tag, items, ops);
    }

    private static boolean active(CompoundTag tag) {
        return tag != null && !format(tag).getListOrEmpty("items").isEmpty() && !tag.getBooleanOr("_lophine_draft", false);
    }

    private void update(int id, CompoundTag tag) {
        if (tag == null) documents.remove(id);
        else documents.put(id, tag.copy());
    }

    public CompletableFuture<List<Integer>> numbers(String player, Operation operation) {
        return loaded.thenCompose(ignored -> io(() -> {
            refresh();
            return documents.entrySet().stream().filter(entry -> active(entry.getValue())).filter(entry -> operation == Operation.INTERCEPT || entry.getValue().getStringOr(operation == Operation.COLLECT ? "recipient" : "sender", "").equals(player)).map(Map.Entry::getKey).toList();
        }));
    }

    private void refresh() {
        for (int id : documents.keySet()) {
            CompoundTag actual = read(id);
            update(id, actual);
        }
    }

    private int reserve() {
        for (int id = 1; id < Integer.MAX_VALUE; id++) {
            CompoundTag tag = documents.get(id);
            if (tag != null && (active(tag) || tag.getBooleanOr("_lophine_draft", false))) continue;
            if (reserved.add(id)) return id;
        }
        throw new IllegalStateException("All parcel numbers are occupied");
    }

    private CompletableFuture<Integer> reserveAvailable() {
        int id = reserve();
        return OrgInventoryTransfers.whenAvailable(server, participant(id)).thenCompose(ignored -> io(() -> {
            CompoundTag actual = read(id);
            update(id, actual);
            if (actual != null && (active(actual) || actual.getBooleanOr("_lophine_draft", false))) {
                reserved.remove(id);
                return false;
            }
            return true;
        })).thenCompose(available -> available ? CompletableFuture.completedFuture(id) : reserveAvailable()).whenComplete((result, error) -> {
            if (error != null) reserved.remove(id);
        });
    }

    private <T> CompletableFuture<T> owner(ServerPlayer player, Supplier<T> work) {
        return OrgFakePlayerActions.owned(player, () -> OrgFakePlayerActions.whenIdle(player, work)).thenCompose(Function.identity());
    }

    private ServerPlayer player(String name) {
        ServerPlayer found = server.getPlayerList().getPlayerByName(name);
        return found == null && server.getBotList() != null ? server.getBotList().getBotByName(name) : found;
    }

    private ServerPlayer player(UUID id) {
        ServerPlayer found = server.getPlayerList().getPlayer(id);
        return found == null && server.getBotList() != null ? server.getBotList().getBot(id) : found;
    }

    private CompletableFuture<Boolean> submit(ServerPlayer player, int id, CompoundTag before, CompoundTag after, List<ItemStack> targetBefore, List<ItemStack> targetAfter, List<ItemStack> viewerBefore, List<ItemStack> viewerAfter, List<OrgItemShadowGroups.Change> shadows) {
        var result = new CompletableFuture<Boolean>();
        boolean accepted = OrgInventoryTransfers.submitVault(player, new OrgInventoryTransfers.Vault(participant(id), relative(id), false, before, after), targetBefore, targetAfter, viewerBefore, viewerAfter, shadows, (java.util.function.Consumer<Boolean>) committed -> {
            if (committed) {
                update(id, after);
                player.containerMenu.broadcastFullState();
            }
            result.complete(committed);
        });
        if (!accepted) result.complete(false);
        return result;
    }

    public CompletableFuture<Boolean> send(ServerPlayer sender, net.minecraft.server.players.NameAndId recipient) {
        return loaded.thenCompose(ignored -> reserveAvailable()).thenCompose(id -> io(() -> read(id)).thenCompose(before -> owner(sender, () -> {
            checkRecipient(sender, recipient);
            var preview = new OrgItemShadowGroups.Preview();
            var viewerBefore = OrgInventoryTransfers.viewerState(sender);
            var references = OrgInventoryTransfers.installPreview(sender, preview);
            List<ItemStack> after;
            CompoundTag parcel;
            try {
                ItemStack hand = sender.getMainHandItem().isEmpty() ? sender.getOffhandItem() : sender.getMainHandItem();
                if (hand.isEmpty()) throw new OrgMailPresentation.Failure(OrgMailPresentation.mail("send.prompt"));
                ItemStack mailed = hand.copyAndClear();
                var ops = sender.registryAccess().createSerializationContext(NbtOps.INSTANCE);
                parcel = document(sender.getScoreboardName(), recipient.name(), recipient.id(), List.of(mailed), ops);
                after = OrgInventoryTransfers.viewerState(sender);
            } finally {
                OrgInventoryTransfers.restoreReferences(sender, references);
            }
            var afterItems = items(server, parcel, sender.registryAccess().createSerializationContext(NbtOps.INSTANCE));
            return TisCommandContinuations.then(submit(sender, id, before, parcel, java.util.Collections.nCopies(afterItems.size(), ItemStack.EMPTY), afterItems, viewerBefore, after, preview.changes()), success -> success ? sendNotices(sender.getScoreboardName(), recipient.name(), id, afterItems).thenApply(ignoredNotice -> true) : CompletableFuture.completedFuture(false));
        })).thenCompose(Function.identity()).whenComplete((success, error) -> reserved.remove(id)));
    }

    private void checkRecipient(ServerPlayer sender, net.minecraft.server.players.NameAndId recipient) {
        boolean debug = OrgHiddenPlayerActions.debug();
        if (!debug && sender.getUUID().equals(recipient.id()))
            throw new OrgMailPresentation.Failure(OrgMailPresentation.mail("send.check_player"));
        ServerPlayer target = player(recipient.id());
        if (target == null) target = player(recipient.name());
        if (!debug && target instanceof ServerBot)
            throw new OrgMailPresentation.Failure(OrgMailPresentation.mail("send.check_player"));
    }

    private static CompoundTag draft(CompoundTag metadata, List<ItemStack> slots, DynamicOps<Tag> ops) {
        CompoundTag after = withItems(metadata, slots, ops);
        after.putBoolean("_lophine_draft", true);
        OrgShadowInventoryCodec.stacks(after, "draft_slots", slots, ops);
        return after;
    }

    private List<ItemStack> draftSlots(int id, CompoundTag doc) {
        if (doc == null || !doc.getBooleanOr("_lophine_draft", false))
            throw new IllegalArgumentException("No active parcel draft #" + id);
        var slots = OrgShadowInventoryCodec.stacks(doc, "draft_slots", server.registryAccess().createSerializationContext(NbtOps.INSTANCE));
        if (slots.size() != 27) throw new IllegalArgumentException("Malformed parcel draft slot count");
        OrgItemShadowGroups.pin(server, relative(id), slots);
        return slots.stream().map(OrgItemShadowGroups::materialize).toList();
    }

    public CompletableFuture<Boolean> multiple(ServerPlayer player, net.minecraft.server.players.NameAndId recipient) {
        return loaded.thenCompose(ignored -> reserveAvailable()).thenCompose(id -> io(() -> read(id)).thenCompose(before -> owner(player, () -> {
            checkRecipient(player, recipient);
            var ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            List<ItemStack> slots = Collections.nCopies(27, ItemStack.EMPTY);
            CompoundTag doc = document(player.getScoreboardName(), recipient.name(), recipient.id(), List.of(), ops);
            doc.putString("_lophine_draft_owner", player.getUUID().toString());
            doc = draft(doc, slots, ops);
            CompoundTag desired = doc;
            return OrgInventoryTransfers.transformVault(server, new OrgInventoryTransfers.Vault(participant(id), relative(id), false, before, desired), slots, slots, List.of()).thenCompose(success -> {
                if (!success) return CompletableFuture.completedFuture(false);
                update(id, desired);
                return owner(player, () -> {
                    try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)) {
                        var opened = player.openMenu(new net.minecraft.world.SimpleMenuProvider((menuId, inventory, viewer) -> new OrgMailDraftMenu(menuId, inventory, player, this, id), OrgMailPresentation.mail("send.multiple.gui")));
                        if (opened.isEmpty()) publishDraft(id);
                        return opened.isPresent();
                    }
                });
            });
        })).thenCompose(Function.identity()).whenComplete((success, error) -> reserved.remove(id)));
    }

    CompletableFuture<Boolean> draftClick(OrgMailDraftMenu menu, int slot, int button, net.minecraft.world.inventory.ContainerInput click) {
        int id = menu.parcel;
        ServerPlayer player = menu.player;
        return OrgInventoryTransfers.whenAvailable(server, participant(id)).thenCompose(ignored -> io(() -> read(id))).thenCompose(before -> owner(player, () -> {
            if (menu.closed || player.containerMenu != menu) return CompletableFuture.completedFuture(false);
            var current = draftSlots(id, before);
            if (!OrgItemShadowGroups.attempt(current, () -> true).completed())
                return retryDraftClick(menu, slot, button, click);
            var preview = new OrgItemShadowGroups.Preview();
            var viewerBefore = OrgInventoryTransfers.viewerState(player);
            var targetBefore = OrgInventoryTransfers.copies(current);
            var references = OrgInventoryTransfers.installPreview(player, preview);
            var topReferences = new ArrayList<ItemStack>();
            for (int index = 0; index < 27; index++) topReferences.add(menu.top.getItem(index));
            var topPreview = preview.copies(current);
            for (int index = 0; index < 27; index++) menu.top.setItem(index, topPreview.get(index));
            List<ItemStack> targetAfter, viewerAfter, drops;
            try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)) {
                drops = OrgInventoryTransfers.preview(() -> menu.previewClick(slot, button, click));
                targetAfter = OrgInventoryTransfers.copies(OrgItemShadowGroups.container(menu.top));
                viewerAfter = OrgInventoryTransfers.viewerState(player);
            } finally {
                OrgInventoryTransfers.restoreReferences(player, references);
                for (int index = 0; index < 27; index++) menu.top.setItem(index, topReferences.get(index));
            }
            // Ordinary copy/split remains detached. Full pointer moves keep explicit shadow
            // descriptors, including zero-count aliases, through the durable virtual container.
            OrgItemShadowGroups.pin(server, relative(id), targetAfter);
            CompoundTag after = draft(before, targetAfter, player.registryAccess().createSerializationContext(NbtOps.INSTANCE));
            var settled = new CompletableFuture<Boolean>();
            boolean accepted = OrgInventoryTransfers.submitVault(player, new OrgInventoryTransfers.Vault(participant(id), relative(id), false, before, after), targetBefore, targetAfter, viewerBefore, viewerAfter, drops, preview.changes(), (java.util.function.Consumer<Boolean>) success -> {
                if (success) {
                    update(id, after);
                    for (int index = 0; index < 27; index++)
                        menu.top.setItem(index, OrgItemShadowGroups.materialize(targetAfter.get(index)));
                }
                if (player.containerMenu == menu) menu.broadcastFullState();
                settled.complete(success);
            });
            if (!accepted) settled.complete(false);
            return settled;
        })).thenCompose(Function.identity());
    }

    private CompletableFuture<Boolean> retryDraftClick(OrgMailDraftMenu menu, int slot, int button, net.minecraft.world.inventory.ContainerInput click) {
        return CompletableFuture.supplyAsync(() -> true, CompletableFuture.delayedExecutor(50, java.util.concurrent.TimeUnit.MILLISECONDS, fileExecutor)).thenCompose(ignored -> draftClick(menu, slot, button, click));
    }

    /**
     * File-only commit publishes copies of the latest canonical groups and never zeroes aliases.
     */
    CompletableFuture<Boolean> publishDraft(int id) {
        return OrgInventoryTransfers.whenAvailable(server, participant(id)).thenCompose(ignored -> io(() -> read(id))).thenCompose(before -> {
            if (before == null || !before.getBooleanOr("_lophine_draft", false)) return io(() -> {
                OrgItemShadowGroups.unpin(server, relative(id));
                return true;
            });
            return io(() -> {
                var slots = draftSlots(id, before);
                var preview = new OrgItemShadowGroups.Preview();
                var temporary = preview.copies(slots);
                var ordinary = new ArrayList<ItemStack>();
                var parcelItems = new ArrayList<ItemStack>();
                for (ItemStack stack : temporary) {
                    ordinary.add(stack.copy());
                    while (stack.getCount() > stack.getMaxStackSize())
                        parcelItems.add(stack.split(stack.getMaxStackSize()));
                    if (!stack.isEmpty()) parcelItems.add(stack.copy());
                }
                CompoundTag after = withItems(before, merge(parcelItems), server.registryAccess().createSerializationContext(NbtOps.INSTANCE));
                after.remove("_lophine_draft");
                after.remove("_lophine_draft_owner");
                after.remove("draft_slots");
                after.remove("draft_slots_shadows");
                return OrgInventoryTransfers.transformVault(server, new OrgInventoryTransfers.Vault(participant(id), relative(id), false, before, after), OrgInventoryTransfers.copies(slots), ordinary, preview.changes()).thenCompose(success -> {
                    if (!success) return retryPublish(id);
                    update(id, after);
                    return TisCommandContinuations.then(io(() -> {
                        OrgItemShadowGroups.unpin(server, relative(id));
                        return true;
                    }), done -> after.getListOrEmpty("items").isEmpty() ? CompletableFuture.completedFuture(done) : sendNotices(after.getStringOr("sender", ""), after.getStringOr("recipient", ""), id, items(server, after, server.registryAccess().createSerializationContext(NbtOps.INSTANCE))).thenApply(ignoredNotice -> done));
                });
            }).thenCompose(Function.identity());
        }).exceptionallyCompose(error -> {
            MinecraftServer.LOGGER.error("Org parcel draft #{} retains durable custody until publication recovers", id, error);
            return retryPublish(id);
        });
    }

    private CompletableFuture<Boolean> retryPublish(int id) {
        return CompletableFuture.supplyAsync(() -> true, CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS, fileExecutor)).thenCompose(ignored -> publishDraft(id));
    }

    static List<ItemStack> merge(List<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack original : items) {
            if (original.isEmpty()) continue;
            ItemStack stack = original.copy();
            for (ItemStack previous : result) {
                if (ItemStack.isSameItemSameComponents(previous, stack)) {
                    int count = Math.min(previous.getMaxStackSize() - previous.getCount(), stack.getCount());
                    if (count > 0) {
                        previous.grow(count);
                        stack.shrink(count);
                    }
                    if (stack.isEmpty()) break;
                }
            }
            if (!stack.isEmpty()) result.add(stack);
        }
        return result;
    }

    public CompletableFuture<Boolean> take(ServerPlayer player, int id, Operation operation) {
        return loaded.thenCompose(ignored -> OrgInventoryTransfers.whenAvailable(server, participant(id))).thenCompose(ignored -> io(() -> read(id))).thenCompose(before -> owner(player, () -> {
            if (!active(before)) throw new OrgMailPresentation.Failure(OrgMailPresentation.mail("invalid_parcel", id));
            validateMetadata(before);
            String name = player.getScoreboardName();
            if (operation == Operation.COLLECT && !before.getStringOr("recipient", "").equals(name))
                throw new OrgMailPresentation.Failure(OrgMailPresentation.mail("collect.not_myself"));
            if (operation == Operation.COLLECT && format(before).getBooleanOr("recall", false))
                return noticeAsync(player, OrgMailPresentation.mail("collect.recalled"), false).thenApply(ignoredNotice -> true);
            if (operation == Operation.RECALL && !before.getStringOr("sender", "").equals(name))
                throw new OrgMailPresentation.Failure(OrgMailPresentation.mail("recall.not_myself"));
            if (operation == Operation.INTERCEPT && !OrgServerPermissions.allowed(player.createCommandSourceStack(), "mail.intercept"))
                throw new IllegalArgumentException("Intercept permission denied");
            var ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            var originals = items(server, before, ops);
            var remaining = OrgInventoryTransfers.copies(originals);
            var preview = new OrgItemShadowGroups.Preview();
            var viewerBefore = OrgInventoryTransfers.viewerState(player);
            var references = OrgInventoryTransfers.installPreview(player, preview);
            List<ItemStack> viewerAfter;
            try {
                OrgInventoryTransfers.preview(() -> remaining.forEach(player.getInventory()::add));
                viewerAfter = OrgInventoryTransfers.viewerState(player);
            } finally {
                OrgInventoryTransfers.restoreReferences(player, references);
            }
            CompoundTag after = withItems(before, remaining, ops);
            if (operation == Operation.RECALL) after.putBoolean("recall", true);
            int received = count(originals) - count(remaining);
            int left = count(remaining);
            if (received == 0 && operation != Operation.RECALL)
                return noticeAsync(player, OrgMailPresentation.mail(operation.name().toLowerCase(java.util.Locale.ROOT) + ".insufficient_capacity"), false).thenApply(ignoredNotice -> true);
            return TisCommandContinuations.then(submit(player, id, before, after, originals, remaining, viewerBefore, viewerAfter, preview.changes()), success -> success ? takeNotices(player, before, originals, remaining, operation, received, left).thenApply(ignoredNotice -> true) : CompletableFuture.completedFuture(false));
        })).thenCompose(Function.identity());
    }

    private CompletableFuture<Void> notifyIntercept(String name, Component operator, String other, String suffix, Component hover) {
        ServerPlayer target = player(name);
        if (target == null) return CompletableFuture.completedFuture(null);
        return TisCommandContinuations.owned(target, () -> {
            Component who = OrgServerPermissions.allowed(target.createCommandSourceStack(), "mail.intercept") ? operator : Component.translatableWithFallback("carpet-org-addition.misc.operator", OrgRuleTranslations.text("carpet-org-addition.misc.operator", "Operator"));
            target.sendSystemMessage(OrgMailPresentation.gray(OrgMailPresentation.mail("notice.intercept." + suffix, who, other)).copy().withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))));
            return null;
        });
    }

    private CompletableFuture<Component> displayName(String name) {
        ServerPlayer online = player(name);
        return online == null ? CompletableFuture.completedFuture(Component.literal(name)) : TisCommandContinuations.owned(online, () -> online.getDisplayName().copy());
    }

    private static CompletableFuture<Void> noticeAsync(ServerPlayer player, Component message, boolean xp) {
        return TisCommandContinuations.owned(player, () -> {
            player.sendSystemMessage(message);
            if (xp) sound(player, true);
            return null;
        });
    }

    private CompletableFuture<Void> notifyAsync(String name, Component message, boolean xp) {
        ServerPlayer online = player(name);
        return online == null ? CompletableFuture.completedFuture(null) : noticeAsync(online, message, xp);
    }

    private CompletableFuture<Void> sendNotices(String sender, String recipient, int id, List<ItemStack> stacks) {
        ServerPlayer from = player(sender);
        if (from == null) {
            MinecraftServer.LOGGER.error("The parcel delivery is sent by non-existent player");
            return CompletableFuture.completedFuture(null);
        }
        return TisCommandContinuations.then(displayName(sender), fromName -> TisCommandContinuations.then(displayName(recipient), toName -> {
            Component display = OrgMailPresentation.display(stacks);
            int count = count(stacks);
            ServerPlayer to = player(recipient);
            var messages = new ArrayList<CompletableFuture<Void>>();
            messages.add(noticeAsync(from, OrgMailPresentation.mail("send.sender", toName, count, display, OrgMailPresentation.click("/mail recall " + id)), false));
            if (to == null)
                messages.add(noticeAsync(from, OrgMailPresentation.gray(OrgMailPresentation.mail("send.offline")), false));
            else {
                messages.add(noticeAsync(to, OrgMailPresentation.mail("send.recipient", fromName, count, display, OrgMailPresentation.click("/mail collect " + id)), true));
                messages.add(TisCommandContinuations.then(TisCommandContinuations.owned(to, () -> OrgUtilityCommands.permitted(to.createCommandSourceStack(), fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.commandMail)), allowed -> allowed ? CompletableFuture.completedFuture(null) : noticeAsync(from, OrgMailPresentation.gray(OrgMailPresentation.mail("send.permission")), false)));
            }
            MinecraftServer.LOGGER.info("{} sent {} {} to {}", sender, count, display.getString(), recipient);
            return CompletableFuture.allOf(messages.toArray(CompletableFuture[]::new));
        }));
    }

    private CompletableFuture<Void> takeNotices(ServerPlayer actor, CompoundTag document, List<ItemStack> before, List<ItemStack> after, Operation operation, int received, int left) {
        return TisCommandContinuations.then(TisCommandContinuations.owned(actor, () -> actor.getDisplayName().copy()), actingName -> {
            String prefix = operation.name().toLowerCase(java.util.Locale.ROOT);
            Component display = OrgMailPresentation.display(before);
            Component result = received == 0 ? OrgMailPresentation.mail(prefix + ".insufficient_capacity") : left == 0 ? OrgMailPresentation.mail(prefix + ".success", count(before), display) : OrgMailPresentation.mail(prefix + ".partial_reception", received, left);
            var messages = new ArrayList<CompletableFuture<Void>>();
            messages.add(TisCommandContinuations.owned(actor, () -> {
                actor.sendSystemMessage(result);
                if (received > 0) sound(actor, false);
                return null;
            }));
            String sender = document.getStringOr("sender", ""), recipient = document.getStringOr("recipient", "");
            if (operation == Operation.COLLECT && received > 0)
                messages.add(notifyAsync(sender, OrgMailPresentation.received(actingName, before, after), false));
            if (operation == Operation.RECALL)
                messages.add(notifyAsync(recipient, OrgMailPresentation.gray(OrgMailPresentation.mail("notice.recall", actingName)), false));
            if (operation == Operation.INTERCEPT && received > 0) {
                Component hover = Component.empty().append(display).append("*" + received);
                messages.add(notifyIntercept(sender, actingName, recipient, "sender", hover));
                messages.add(notifyIntercept(recipient, actingName, sender, "recipient", hover));
            }
            return CompletableFuture.allOf(messages.toArray(CompletableFuture[]::new));
        });
    }

    static int count(List<ItemStack> stacks) {
        return stacks.stream().mapToInt(ItemStack::getCount).sum();
    }

    static Component button(String title, String command) {
        return Component.literal("[" + title + "]").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand(OrgCommandSettings.rewriteCommand(command))));
    }

    private static void notice(ServerPlayer player, Component message) {
        OrgFakePlayerActions.owned(player, () -> {
            player.sendSystemMessage(message);
            return null;
        });
    }

    private static void sound(ServerPlayer player, boolean xp) {
        player.playSound(xp ? SoundEvents.EXPERIENCE_ORB_PICKUP : SoundEvents.ITEM_PICKUP, 1.0F, 1.0F);
    }

    private void notify(String name, Component message, boolean xp) {
        ServerPlayer player = player(name);
        if (player != null) OrgFakePlayerActions.owned(player, () -> {
            player.sendSystemMessage(message);
            if (xp) sound(player, true);
            return null;
        });
    }

    public CompletableFuture<List<Component>> list(ServerPlayer player) {
        return OrgFakePlayerActions.owned(player, () -> {
            String name = player.getScoreboardName();
            boolean ops = net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(player.createCommandSourceStack().permissions());
            var context = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            return loaded.thenCompose(ignored -> io(() -> {
                refresh();
                List<Component> result = new ArrayList<>();
                for (var entry : documents.entrySet()) {
                    int id = entry.getKey();
                    CompoundTag doc = format(entry.getValue());
                    if (!active(doc)) continue;
                    var stacks = items(server, doc, context);
                    String operation = doc.getStringOr("sender", "").equals(name) ? "recall" : doc.getStringOr("recipient", "").equals(name) && !doc.getBooleanOr("recall", false) ? "collect" : ops ? "intercept" : "view";
                    result.add(OrgMailPresentation.line(id, doc, stacks, operation));
                }
                return result;
            }));
        }).thenCompose(Function.identity());
    }

    /**
     * Called after join/tick only on the player's owner.
     */
    public static void observe(ServerPlayer player) {
        if (player instanceof ServerBot || NOTICED.putIfAbsent(player, true) != null) return;
        OrgMailService service = get(player.level().getServer());
        String name = player.getScoreboardName();
        var actual = OrgMenuNativeEffects.admit(player.level().getServer(), () -> TisCommandContinuations.then(service.incoming(name), messages -> messages.isEmpty() ? CompletableFuture.completedFuture(null) : TisCommandContinuations.owned(player, () -> {
            player.sendSystemMessage(Component.empty());
            OrgPages.print(player.createCommandSourceStack(), messages);
            return null;
        })));
        actual.exceptionally(error -> {
            MinecraftServer.LOGGER.error("Cannot load Org incoming parcel notice", error);
            return null;
        });
    }

    public static void retired(ServerPlayer player) {
        NOTICED.remove(player);
    }

    private CompletableFuture<List<Component>> incoming(String name) {
        return loaded.thenCompose(ignored -> io(() -> {
            refresh();
            var messages = new ArrayList<Component>();
            for (var entry : documents.entrySet()) {
                CompoundTag doc = format(entry.getValue());
                if (active(doc) && doc.getStringOr("recipient", "").equals(name) && !doc.getBooleanOr("recall", false)) {
                    var stacks = items(server, doc, server.registryAccess().createSerializationContext(NbtOps.INSTANCE));
                    messages.add(OrgMailPresentation.mail("prompt_collect", count(stacks), OrgMailPresentation.display(stacks), OrgMailPresentation.click("/mail collect " + entry.getKey())));
                }
            }
            return messages;
        }));
    }

    public CompletableFuture<Integer> overrideCount() {
        return loaded.thenCompose(ignored -> io(() -> {
            refresh();
            return documents.entrySet().stream().filter(entry -> active(entry.getValue())).map(Map.Entry::getKey).toList();
        })).thenCompose(ids -> CompletableFuture.allOf(ids.stream().map(id -> OrgInventoryTransfers.updateVaultMetadata(server, participant(id), relative(id), false, CompoundTag::copy)).toArray(CompletableFuture[]::new)).thenApply(ignored -> ids.size()));
    }

    public CompletableFuture<Void> override() {
        return overrideCount().thenApply(ignored -> null);
    }
}
