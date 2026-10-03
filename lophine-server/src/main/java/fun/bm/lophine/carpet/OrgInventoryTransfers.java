package fun.bm.lophine.carpet;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.leavesmc.leaves.bot.ServerBot;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nonblocking inventory actors. Detached items live in a durable escrow until delivery.
 */
public final class OrgInventoryTransfers {
    private static final NamespacedKey SOURCE = new NamespacedKey("lophine", "carpet_org_inventory_source");
    private static final NamespacedKey TARGET = new NamespacedKey("lophine", "carpet_org_inventory_target");
    private static final NamespacedKey HOLD = new NamespacedKey("lophine", "carpet_org_inventory_hold");
    private static final ScopedValue<List<ItemStack>> PREVIEW_DROPS = ScopedValue.newInstance();
    private static final Map<MinecraftServer, Coordinator> COORDINATORS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<MinecraftServer, Long> LOAD_FAILURES = new WeakHashMap<>();
    private static final Map<ServerPlayer, List<ItemStack>> RECOVERED_CURSOR = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ServerPlayer, List<ItemStack>> DEFERRED_RETURNS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ServerPlayer, List<CompoundTag>> SHADOW_LOADS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final String CURSOR_TAG = "CarpetOrgEscrowCursor";
    private static final String SHADOW_TAG = "CarpetOrgEscrowShadows";

    private OrgInventoryTransfers() {
    }

    public static List<ItemStack> preview(Runnable action) {
        List<ItemStack> drops = new ArrayList<>();
        ScopedValue.where(PREVIEW_DROPS, drops).run(action);
        return copies(drops);
    }

    public static boolean captureDrop(ItemStack stack) {
        if (!PREVIEW_DROPS.isBound()) return false;
        if (!stack.isEmpty()) PREVIEW_DROPS.get().add(OrgItemShadowGroups.snapshot(stack));
        return true;
    }

    public static boolean isPreview() {
        return PREVIEW_DROPS.isBound();
    }

    /**
     * Immediate admission metadata, established before either actor asks to drain old native work.
     */
    public static boolean participantBlocked(ServerPlayer player) {
        Coordinator coordinator = COORDINATORS.get(player.level().getServer());
        if (coordinator == null) return false;
        UUID id = coordinator.leases.get(player.getUUID());
        return id != null && coordinator.transactions.containsKey(id);
    }

    public static CompletableFuture<Void> participantCompletion(ServerPlayer player) {
        Coordinator coordinator = COORDINATORS.get(player.level().getServer());
        if (coordinator == null) return CompletableFuture.completedFuture(null);
        UUID id = coordinator.leases.get(player.getUUID());
        Transaction transaction = id == null ? null : coordinator.transactions.get(id);
        return transaction == null ? CompletableFuture.completedFuture(null) : transaction.completed.copy();
    }

    public static List<ItemStack> viewerState(ServerPlayer player) {
        requireOwner(player);
        List<ItemStack> result = new ArrayList<>(player.getInventory().getContainerSize() + 1);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            result.add(player.getInventory().getItem(slot));
        result.add(player.containerMenu.getCarried());
        return copies(result);
    }

    public static List<ItemStack> targetState(ServerPlayer player, boolean ender) {
        requireOwner(player);
        Container container = ender ? player.getEnderChestInventory() : player.getInventory();
        List<ItemStack> result = new ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) result.add(container.getItem(slot));
        return copies(result);
    }

    public static List<ItemStack> installPreview(ServerPlayer player) {
        return installPreview(player, new OrgItemShadowGroups.Preview());
    }

    public static List<ItemStack> installPreview(ServerPlayer player, OrgItemShadowGroups.Preview preview) {
        requireOwner(player);
        List<ItemStack> references = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack original = player.getInventory().getItem(slot);
            references.add(original);
        }
        ItemStack original = player.containerMenu.getCarried();
        references.add(original);
        List<ItemStack> copies = preview.copies(references);
        for (int slot = 0; slot < references.size() - 1; slot++) player.getInventory().setItem(slot, copies.get(slot));
        player.containerMenu.setCarried(copies.getLast());
        return references;
    }

    public static void restoreReferences(ServerPlayer player, List<ItemStack> references) {
        requireOwner(player);
        for (int slot = 0; slot < references.size() - 1; slot++)
            player.getInventory().setItem(slot, references.get(slot));
        player.containerMenu.setCarried(references.getLast());
    }

    /**
     * Used only immediately after preview on the viewer's owner, before returning to the tick.
     */
    public static void restorePreview(ServerPlayer player, List<ItemStack> before) {
        requireOwner(player);
        for (int slot = 0; slot < before.size() - 1; slot++) {
            if (!same(player.getInventory().getItem(slot), before.get(slot)))
                player.getInventory().setItem(slot, before.get(slot).copy());
        }
        player.containerMenu.setCarried(before.getLast().copy());
    }

    public static boolean same(ItemStack first, ItemStack second) {
        if (!first.isEmpty() || !second.isEmpty())
            if (!Objects.equals(first.carpetOrgShadowId, second.carpetOrgShadowId)) return false;
        return first.isEmpty() && second.isEmpty() || first.getCount() == second.getCount() && ItemStack.isSameItemSameComponents(first, second);
    }

    public static boolean same(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (!same(first.get(i), second.get(i))) return false;
        return true;
    }

    static CompoundTag inventoryTag(ServerPlayer player) {
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, player.registryAccess());
        player.getInventory().save(output.list("Inventory", net.minecraft.world.ItemStackWithSlot.CODEC));
        if (!player.getInventory().equipment.isEmpty())
            output.store("equipment", net.minecraft.world.entity.EntityEquipment.CODEC, player.getInventory().equipment);
        player.getEnderChestInventory().storeAsSlots(output.list("EnderItems", net.minecraft.world.ItemStackWithSlot.CODEC));
        saveCursor(player, output);
        return output.buildResult();
    }

    private static void requireOwner(ServerPlayer player) {
        if (!TickThread.isTickThreadFor(player))
            throw new IllegalStateException("Inventory actor executed outside its owner's region");
    }

    public static List<ItemStack> copies(List<ItemStack> stacks) {
        return OrgItemShadowGroups.snapshots(stacks);
    }

    public static boolean submit(ServerPlayer viewer, UUID target, boolean ender, List<ItemStack> targetBefore,
                                 List<ItemStack> targetAfter, List<ItemStack> viewerBefore, List<ItemStack> viewerAfter,
                                 List<ItemStack> drops, Runnable finished) {
        return submit(viewer, target, ender, targetBefore, targetAfter, viewerBefore, viewerAfter, drops, List.of(), finished);
    }

    public static boolean submit(ServerPlayer viewer, UUID target, boolean ender, List<ItemStack> targetBefore,
                                 List<ItemStack> targetAfter, List<ItemStack> viewerBefore, List<ItemStack> viewerAfter,
                                 List<ItemStack> drops, List<OrgItemShadowGroups.Change> shadows, Runnable finished) {
        return submit(viewer, target, ender, targetBefore, targetAfter, viewerBefore, viewerAfter, drops, shadows, finished, null, null);
    }

    /**
     * A durable file actor uses the same participant, alias and source-credit protocol as remote menus.
     */
    public record Vault(UUID participant, String relativePath, boolean compressed, CompoundTag before,
                        CompoundTag after) {
        public Vault {
            before = before == null ? null : before.copy();
            after = after.copy();
        }

        @Override
        public CompoundTag before() {
            return before == null ? null : before.copy();
        }

        @Override
        public CompoundTag after() {
            return after.copy();
        }
    }

    public static boolean submitVault(ServerPlayer viewer, Vault vault, List<ItemStack> targetBefore, List<ItemStack> targetAfter,
                                      List<ItemStack> viewerBefore, List<ItemStack> viewerAfter, List<OrgItemShadowGroups.Change> shadows, Runnable finished) {
        return submit(viewer, vault.participant(), false, targetBefore, targetAfter, viewerBefore, viewerAfter, List.of(), shadows, finished, vault, null);
    }

    public static boolean submitVault(ServerPlayer viewer, Vault vault, List<ItemStack> targetBefore, List<ItemStack> targetAfter,
                                      List<ItemStack> viewerBefore, List<ItemStack> viewerAfter, List<OrgItemShadowGroups.Change> shadows, java.util.function.Consumer<Boolean> settled) {
        return submit(viewer, vault.participant(), false, targetBefore, targetAfter, viewerBefore, viewerAfter, List.of(), shadows, null, vault, settled);
    }

    public static boolean submitVault(ServerPlayer viewer, Vault vault, List<ItemStack> targetBefore, List<ItemStack> targetAfter,
                                      List<ItemStack> viewerBefore, List<ItemStack> viewerAfter, List<ItemStack> drops, List<OrgItemShadowGroups.Change> shadows, java.util.function.Consumer<Boolean> settled) {
        return submit(viewer, vault.participant(), false, targetBefore, targetAfter, viewerBefore, viewerAfter, drops, shadows, null, vault, settled);
    }

    /**
     * An already durable file's custody can progress while its original player is offline.
     */
    public static CompletableFuture<Boolean> transformVault(MinecraftServer server, Vault vault, List<ItemStack> before, List<ItemStack> after, List<OrgItemShadowGroups.Change> shadows) {
        if (before.size() != after.size())
            throw new IllegalArgumentException("A file transform needs matching slot counts");
        Transaction transaction = new Transaction();
        transaction.id = UUID.randomUUID();
        transaction.viewer = vault.participant();
        transaction.target = vault.participant();
        transaction.vault = vault;
        transaction.vaultOnly = true;
        transaction.targetBefore = copies(before);
        transaction.targetAfter = copies(after);
        transaction.viewerBefore = Collections.nCopies(44, ItemStack.EMPTY);
        transaction.viewerAfter = transaction.viewerBefore;
        transaction.drops = List.of();
        transaction.shadows = List.copyOf(shadows);
        var result = new CompletableFuture<Boolean>();
        transaction.settled = result::complete;
        try {
            if (!coordinator(server).beginFileOnly(transaction)) result.complete(false);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    public static CompletableFuture<Void> whenAvailable(MinecraftServer server, UUID participant) {
        try {
            Coordinator coordinator = coordinator(server);
            UUID id = coordinator.leases.get(participant);
            Transaction transaction = id == null ? null : coordinator.transactions.get(id);
            if (id == null) return CompletableFuture.completedFuture(null);
            if (transaction == null) {
                var auxiliary = coordinator.auxiliary.get(id);
                return auxiliary == null ? CompletableFuture.runAsync(() -> {
                }, CompletableFuture.delayedExecutor(10, java.util.concurrent.TimeUnit.MILLISECONDS)) : auxiliary.copy();
            }
            return transaction.vault != null && participant.equals(transaction.target) ? transaction.vaultAvailable.copy() : transaction.completed.copy();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Metadata-only file work cannot add, remove or replace any asset payload.
     */
    public static CompletableFuture<Void> updateVaultMetadata(MinecraftServer server, UUID participant, String relativePath,
                                                              boolean compressed, java.util.function.UnaryOperator<CompoundTag> update) {
        var result = new CompletableFuture<Void>();
        try {
            coordinator(server).metadata(participant, relativePath, compressed, update, result);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /**
     * Native player-file schema maintenance borrows the existing participant custody;
     * callers also hold OrgPlayerFileLease. Cancellation of its caller view cannot
     * release actual backup, conversion, write or uncertain-readback work.
     */
    static <T> CompletableFuture<T> withNativePlayerData(MinecraftServer server, UUID participant,
                                                         java.util.function.Function<NativePlayerData, CompletableFuture<T>> operation) {
        var actual = new CompletableFuture<T>();
        carpet.script.external.ScarpetNativeWork.record(actual);
        carpet.script.external.ScarpetNativeWork.trackNative(server, actual);
        try {
            coordinator(server).nativePlayerData(participant, operation, actual);
        } catch (Throwable failure) {
            actual.completeExceptionally(failure);
        }
        var caller = actual.copy();
        carpet.script.external.ScarpetNativeWork.aliasDependency(caller, actual);
        return caller;
    }

    static final class NativePlayerData {
        private final Coordinator coordinator;
        private final UUID participant, token;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private NativePlayerData(Coordinator coordinator, UUID participant, UUID token) {
            this.coordinator = coordinator;
            this.participant = participant;
            this.token = token;
        }

        CompletableFuture<Void> replace(String relativePath, CompoundTag expected, CompoundTag desired) {
            var actual = new CompletableFuture<Void>();
            carpet.script.external.ScarpetNativeWork.record(actual);
            carpet.script.external.ScarpetNativeWork.trackNative(coordinator.server, actual);
            if (!active.get()) {
                actual.completeExceptionally(new IllegalStateException("Native player-data custody has ended"));
                return actual;
            }
            // Private pointer and escrow descriptions are durable custody, never a DFU output inventory.
            for (String field : List.of(SHADOW_TAG, CURSOR_TAG, "BukkitValues", "CarpetOrgVaultTransaction", OrgOfflineInventorySessions.CUSTODY))
                if (!Objects.equals(expected.get(field), desired.get(field))) {
                    actual.completeExceptionally(new IllegalArgumentException("Native player-data upgrade changed private custody: " + field));
                    return actual;
                }
            try {
                coordinator.replaceNativePlayerData(this, relativePath, expected.copy(), desired.copy(), actual);
            } catch (Throwable failure) {
                actual.completeExceptionally(failure);
            }
            var caller = actual.copy();
            carpet.script.external.ScarpetNativeWork.aliasDependency(caller, actual);
            return caller;
        }
    }

    private static boolean submit(ServerPlayer viewer, UUID target, boolean ender, List<ItemStack> targetBefore,
                                  List<ItemStack> targetAfter, List<ItemStack> viewerBefore, List<ItemStack> viewerAfter, List<ItemStack> drops,
                                  List<OrgItemShadowGroups.Change> shadows, Runnable finished, Vault vault, java.util.function.Consumer<Boolean> settled) {
        requireOwner(viewer);
        if (viewer.getUUID().equals(target))
            throw new IllegalArgumentException("Self menus use the local inventory path");
        if (!same(viewerState(viewer), viewerBefore)) return false;
        Coordinator coordinator;
        try {
            coordinator = coordinator(viewer.level().getServer());
        } catch (RuntimeException exception) {
            report(exception);
            return false;
        }
        Transaction transaction = new Transaction();
        transaction.id = UUID.randomUUID();
        transaction.viewer = viewer.getUUID();
        transaction.target = target;
        transaction.ender = ender;
        transaction.targetBefore = copies(targetBefore);
        transaction.targetAfter = copies(targetAfter);
        transaction.viewerBefore = copies(viewerBefore);
        transaction.viewerAfter = copies(viewerAfter);
        transaction.drops = copies(drops);
        transaction.finished = finished;
        transaction.vault = vault;
        transaction.settled = settled;
        var touched = new java.util.HashSet<UUID>();
        for (int slot : changed(targetBefore, targetAfter)) {
            if (targetBefore.get(slot).carpetOrgShadowId != null) touched.add(targetBefore.get(slot).carpetOrgShadowId);
            if (targetAfter.get(slot).carpetOrgShadowId != null) touched.add(targetAfter.get(slot).carpetOrgShadowId);
        }
        for (int slot : changed(viewerBefore, viewerAfter)) {
            if (viewerBefore.get(slot).carpetOrgShadowId != null) touched.add(viewerBefore.get(slot).carpetOrgShadowId);
            if (viewerAfter.get(slot).carpetOrgShadowId != null) touched.add(viewerAfter.get(slot).carpetOrgShadowId);
        }
        for (ItemStack drop : drops) if (drop.carpetOrgShadowId != null) touched.add(drop.carpetOrgShadowId);
        transaction.shadows = shadows.stream().filter(change -> touched.contains(change.id())
                || change.before().carpetOrgOriginalCount() != change.after().carpetOrgOriginalCount() || !change.before().carpetOrgOriginalHolder().equals(change.after().carpetOrgOriginalHolder())
                || !change.before().components.equals(change.after().components)).toList();
        transaction.menuId = viewer.containerMenu.containerId;
        transaction.menu = viewer.containerMenu;
        return coordinator.begin(viewer, transaction);
    }

    private static Coordinator coordinator(MinecraftServer server) {
        synchronized (COORDINATORS) {
            Coordinator result = COORDINATORS.get(server);
            if (result == null) {
                Long failed = LOAD_FAILURES.get(server);
                long now = System.nanoTime();
                if (failed != null && now - failed < java.util.concurrent.TimeUnit.SECONDS.toNanos(60))
                    throw new IllegalStateException("Inventory escrow loading is waiting to retry");
                try {
                    result = new Coordinator(server);
                    COORDINATORS.put(server, result);
                    LOAD_FAILURES.remove(server);
                } catch (RuntimeException exception) {
                    LOAD_FAILURES.put(server, now);
                    if (failed == null) report(exception);
                    throw exception;
                }
            }
            return result;
        }
    }

    /**
     * Cursor custody is serialized with the player's commit marker in the same atomic player save.
     */
    public static void saveCursor(ServerPlayer player, ValueOutput output) {
        Coordinator coordinator = COORDINATORS.get(player.level().getServer());
        UUID lease = coordinator == null ? null : coordinator.leases.get(player.getUUID());
        Transaction transaction = lease == null ? null : coordinator.transactions.get(lease);
        if (transaction != null && !transaction.shadows.isEmpty()) {
            String receipt = player.getBukkitEntity().getPersistentDataContainer().get(transaction.viewer.equals(player.getUUID()) ? SOURCE : TARGET, PersistentDataType.STRING);
            boolean after = !transaction.refund && receipt != null && (receipt.startsWith(transaction.id + ":delivery:") || receipt.startsWith(transaction.id + ":source_hold:")
                    || receipt.startsWith(transaction.id + ":target_delivery:") || receipt.startsWith(transaction.id + ":target_hold:") || receipt.equals(transaction.id + ":committed")
                    || receipt.equals(transaction.id + ":complete") || receipt.startsWith(transaction.id + ":drop:"));
            OrgItemShadowGroups.transaction(transaction.id, transaction.shadows, after, () -> {
                saveCursorOwned(player, output, true);
                return true;
            });
        } else saveCursorOwned(player, output, false);
    }

    private static void saveCursorOwned(ServerPlayer player, ValueOutput output, boolean projection) {
        var pdc = player.getBukkitEntity().getPersistentDataContainer();
        List<ItemStack> recovered = RECOVERED_CURSOR.get(player);
        List<ItemStack> saved = new ArrayList<>();
        if (recovered != null) saved.addAll(copies(recovered));
        List<ItemStack> returning = DEFERRED_RETURNS.get(player);
        if (returning != null) saved.addAll(copies(returning));
        if (player.containerMenu != null && !player.containerMenu.getCarried().isEmpty())
            saved.add(OrgItemShadowGroups.snapshot(player.containerMenu.getCarried()));
        if (projection) {
            // Autosaves must not replace a verified after receipt with the group's frozen before view.
            player.getInventory().save(output.list("Inventory", net.minecraft.world.ItemStackWithSlot.CODEC));
            output.store("equipment", net.minecraft.world.entity.EntityEquipment.CODEC, player.getInventory().equipment);
            player.getEnderChestInventory().storeAsSlots(output.list("EnderItems", net.minecraft.world.ItemStackWithSlot.CODEC));
        }
        if (recovered != null || returning != null || pdc.has(SOURCE, PersistentDataType.STRING) || saved.stream().anyMatch(OrgItemShadowGroups::transactionBound))
            output.store(CURSOR_TAG, ItemStack.OPTIONAL_CODEC.listOf(), saved);
        List<CompoundTag> identities = new ArrayList<>();
        var ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            shadowIdentity(identities, player.getInventory().getItem(slot), 0, slot, ops);
        for (int slot = 0; slot < player.getEnderChestInventory().getContainerSize(); slot++)
            shadowIdentity(identities, player.getEnderChestInventory().getItem(slot), 1, slot, ops);
        for (int slot = 0; slot < saved.size(); slot++) shadowIdentity(identities, saved.get(slot), 2, slot, ops);
        List<CompoundTag> pending = SHADOW_LOADS.get(player);
        if (pending != null) identities.addAll(pending.stream().map(CompoundTag::copy).toList());
        if (projection || !identities.isEmpty()) output.store(SHADOW_TAG, CompoundTag.CODEC.listOf(), identities);
    }

    private static void shadowIdentity(List<CompoundTag> identities, ItemStack stack, int kind, int slot, DynamicOps<Tag> ops) {
        if (!OrgItemShadowGroups.persistenceBound(stack)) return;
        OrgItemShadowGroups.persistLatest(stack);
        CompoundTag identity = OrgShadowInventoryCodec.descriptor(stack, ops);
        identity.putInt("kind", kind);
        identity.putInt("slot", slot);
        identities.add(identity);
    }

    public static void loadCursor(ServerPlayer player, ValueInput input) {
        input.read(CURSOR_TAG, ItemStack.OPTIONAL_CODEC.listOf()).filter(items -> !items.isEmpty()).ifPresent(items -> RECOVERED_CURSOR.put(player, copies(items)));
        input.read(SHADOW_TAG, CompoundTag.CODEC.listOf()).filter(items -> !items.isEmpty()).ifPresent(items -> {
            SHADOW_LOADS.put(player, items.stream().map(CompoundTag::copy).toList());
            try {
                restoreShadowIdentities(player);
            } catch (RuntimeException exception) {
                quarantineShadowSlots(player);
                report(exception);
            }
        });
    }

    private static void restoreShadowIdentities(ServerPlayer player) {
        List<CompoundTag> identities = SHADOW_LOADS.get(player);
        if (identities == null) return;
        Coordinator coordinator = coordinator(player.level().getServer());
        var trusted = new java.util.HashSet<UUID>();
        coordinator.transactions.values().forEach(transaction -> transaction.shadows.forEach(change -> {
            if (!List.of("preparing", "aborted").contains(transaction.phase) && (!transaction.phase.equals("complete") || OrgItemShadowGroups.hasDurableCompletion(change.id(), transaction.id)))
                trusted.add(change.id());
        }));
        List<ItemStack> recovered = RECOVERED_CURSOR.get(player);
        var ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (CompoundTag identity : identities) {
            UUID id = UUID.fromString(identity.getStringOr("id", ""));
            int kind = identity.getIntOr("kind", -1), slot = identity.getIntOr("slot", -1);
            ItemStack descriptor = OrgShadowInventoryCodec.descriptor(identity, ops);
            ItemStack restored = trusted.contains(id) ? OrgItemShadowGroups.materialize(descriptor) : OrgItemShadowGroups.restoreCustody(player.level().getServer(), descriptor);
            if (restored == null) continue; // Unbound native aliases still detach as the source Java alias load does.
            if (kind == 0 && slot >= 0 && slot < player.getInventory().getContainerSize())
                player.getInventory().setItem(slot, restored);
            else if (kind == 1 && slot >= 0 && slot < player.getEnderChestInventory().getContainerSize())
                player.getEnderChestInventory().setItem(slot, restored);
            else if (kind == 2 && recovered != null && slot >= 0 && slot < recovered.size())
                recovered.set(slot, restored);
            else throw new IllegalArgumentException("Malformed saved item shadow position");
        }
        SHADOW_LOADS.remove(player);
    }

    private static void quarantineShadowSlots(ServerPlayer player) {
        List<CompoundTag> identities = SHADOW_LOADS.get(player);
        if (identities == null) return;
        List<ItemStack> held = new ArrayList<>(RECOVERED_CURSOR.getOrDefault(player, List.of()));
        List<CompoundTag> changed = new ArrayList<>();
        for (CompoundTag original : identities) {
            CompoundTag identity = original.copy();
            int kind = identity.getIntOr("kind", -1), slot = identity.getIntOr("slot", -1);
            if (kind == 0 || kind == 1) {
                Container container = kind == 0 ? player.getInventory() : player.getEnderChestInventory();
                if (slot < 0 || slot >= container.getContainerSize())
                    throw new IllegalArgumentException("Malformed quarantined item shadow position");
                ItemStack stack = container.getItem(slot);
                container.setItem(slot, ItemStack.EMPTY);
                identity.putInt("kind", 2);
                identity.putInt("slot", held.size());
                held.add(stack);
            }
            changed.add(identity);
        }
        RECOVERED_CURSOR.put(player, held);
        SHADOW_LOADS.put(player, List.copyOf(changed));
    }

    /**
     * A return deferred by a shadow lease is part of native player-file custody even if its owner retires.
     */
    public static void deferReturn(ServerPlayer player, ItemStack stack, Runnable action) {
        requireOwner(player);
        List<ItemStack> returning = DEFERRED_RETURNS.computeIfAbsent(player, ignored -> new ArrayList<>());
        if (returning.stream().noneMatch(value -> value == stack)) returning.add(stack);
        OrgItemShadowGroups.actor(player, () -> {
                    var current = OrgItemShadowGroups.container(player.getInventory());
                    current.add(stack);
                    return current;
                },
                () -> {
                    boolean completed = false;
                    try {
                        action.run();
                        completed = true;
                        return true;
                    } finally {
                        if (completed || stack.isEmpty()) returning.removeIf(value -> value == stack);
                        if (returning.isEmpty()) DEFERRED_RETURNS.remove(player);
                    }
                }, false,
                owned -> !stack.isEmpty(), ignored -> {
                });
    }

    public static void tick(ServerPlayer player) {
        List<ItemStack> returning = DEFERRED_RETURNS.get(player);
        if (returning != null) {
            returning.removeIf(ItemStack::isEmpty);
            if (returning.isEmpty()) DEFERRED_RETURNS.remove(player);
        }
        if (SHADOW_LOADS.containsKey(player)) {
            try {
                restoreShadowIdentities(player);
            } catch (RuntimeException exception) {
                return;
            }
        }
        List<ItemStack> recovered = RECOVERED_CURSOR.get(player);
        if (recovered != null && !player.isDeadOrDying() && recovered.stream().noneMatch(OrgItemShadowGroups::transactionBound)) {
            List<ItemStack> before = viewerState(player), after = copies(before), remaining = copies(recovered);
            insert(after, remaining);
            for (int slot : changed(before, after))
                player.getInventory().setItem(slot, OrgItemShadowGroups.materialize(after.get(slot)));
            if (remaining.isEmpty()) RECOVERED_CURSOR.remove(player);
            else RECOVERED_CURSOR.put(player, remaining);
        }
        if (!carpet.script.external.ScarpetNativeWork.isDraining(player.level().getServer()) && player.level().getGameTime() % 20L != 0L)
            return;
        Coordinator coordinator = COORDINATORS.get(player.level().getServer());
        if (coordinator == null) {
            Path directory = player.level().getServer().getWorldPath(LevelResource.ROOT).resolve("carpet-org-inventory-escrow");
            if (!Files.isDirectory(directory)) return;
            try {
                coordinator = coordinator(player.level().getServer());
            } catch (RuntimeException exception) {
                return;
            }
        }
        coordinator.process(player);
    }

    private static void report(Exception exception) {
        com.mojang.logging.LogUtils.getLogger().error("Carpet inventory escrow could not advance; retaining its records", exception);
    }

    private static final class Transaction {
        UUID id, viewer, target;
        boolean ender;
        boolean refund;
        volatile String phase = "prepared";
        List<ItemStack> viewerBefore, viewerAfter, targetBefore, targetAfter, drops;
        List<OrgItemShadowGroups.Change> shadows = List.of();
        // Partial destination insertion checkpoints are saved BEFORE applying the corresponding player mutation.
        List<ItemStack> remaining;
        List<ItemStack> deliveryBefore, deliveryAfter, deliveryCredits;
        List<ItemStack> targetRemaining, targetDeliveryBefore, targetDeliveryAfter, targetDeliveryCredits;
        String expectedSourceReceipt, expectedTargetReceipt;
        int delivery;
        int targetDelivery;
        int menuId;
        net.minecraft.world.inventory.AbstractContainerMenu menu;
        int dropped;
        Runnable finished;
        java.util.function.Consumer<Boolean> settled;
        boolean reported;
        boolean shadowDurable;
        Vault vault;
        boolean vaultReleased;
        boolean vaultOnly;
        boolean quiescentPrepare;
        final Map<UUID, ServerPlayer> quietOwners = new ConcurrentHashMap<>();
        final Map<UUID, ServerPlayer> quietApplicants = new ConcurrentHashMap<>();
        final Map<UUID, CompletableFuture<Void>> quietRequests = new ConcurrentHashMap<>();
        final AtomicBoolean vaultRunning = new AtomicBoolean();
        final CompletableFuture<Void> completed = new CompletableFuture<>(), vaultAvailable = new CompletableFuture<>();
        final ReentrantLock lock = new ReentrantLock();
    }

    private static final class Coordinator {
        final MinecraftServer server;
        final Path directory;
        final ConcurrentHashMap<UUID, Transaction> transactions = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, UUID> leases = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, CompletableFuture<Void>> auxiliary = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, CompletableFuture<Void>> ownerOperations = new ConcurrentHashMap<>();
        private java.util.concurrent.Executor fileActors = java.util.concurrent.ForkJoinPool.commonPool();
        final DynamicOps<Tag> ops;

        Coordinator(MinecraftServer server) {
            this.server = server;
            this.directory = server.getWorldPath(LevelResource.ROOT).resolve("carpet-org-inventory-escrow");
            this.ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            if (Files.isDirectory(directory)) {
                try (var files = Files.list(directory)) {
                    for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".nbt")).toList()) {
                        Transaction transaction = read(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
                        if (!file.getFileName().toString().equals(transaction.id + ".nbt")
                                || transactions.putIfAbsent(transaction.id, transaction) != null
                                || leases.putIfAbsent(transaction.viewer, transaction.id) != null
                                || !transaction.vaultOnly && !transaction.vaultReleased && leases.putIfAbsent(transaction.target, transaction.id) != null)
                            throw new IOException("Duplicate escrow participant or transaction");
                        if (transaction.vaultReleased) transaction.vaultAvailable.complete(null);
                    }
                    for (Transaction transaction : transactions.values()) {
                        OrgItemShadowGroups.bindPersistence(server, transaction.shadows);
                        if (!transaction.phase.equals("complete")) transaction.shadowDurable = true;
                    }
                    // Completed records may share a group with a later transaction whose participants differ.
                    for (Transaction transaction : transactions.values())
                        if (transaction.phase.equals("complete")) {
                            OrgItemShadowGroups.recoverComplete(transaction.id, transaction.shadows, !transaction.refund, transaction.shadowDurable);
                        }
                    for (Transaction transaction : transactions.values())
                        if (!transaction.phase.equals("complete") && !transaction.phase.equals("preparing")
                                && !transaction.phase.equals("aborted")
                                && !OrgItemShadowGroups.prepare(transaction.id, transaction.shadows, true))
                            throw new IOException("Conflicting item shadow escrow lease");
                    // PREPARING contains admission metadata only. No native input or group
                    // state was consumed before the durable PREPARED transition, so a
                    // restart cancels it without requiring either participant to log in.
                    for (Transaction transaction : transactions.values())
                        if (List.of("preparing", "aborted").contains(transaction.phase)) cancelPreparing(transaction);
                        else if (transaction.vaultOnly) dispatch(transaction.target);
                } catch (IOException | RuntimeException exception) {
                    throw new IllegalStateException("Cannot load the inventory escrow; existing records are preserved", exception);
                }
            }
        }

        boolean begin(ServerPlayer viewer, Transaction transaction) {
            if (viewer.getBukkitEntity().getPersistentDataContainer().has(HOLD, PersistentDataType.BYTE_ARRAY))
                return false;
            try {
                OrgItemShadowGroups.bindPersistence(server, transaction.shadows);
            } catch (RuntimeException failure) {
                report(failure);
                return false;
            }
            transaction.shadowDurable = true;
            if (!transaction.lock.tryLock()) throw new IllegalStateException("New escrow unexpectedly busy");
            try {
                if (leases.putIfAbsent(transaction.viewer, transaction.id) != null) return false;
                if (leases.putIfAbsent(transaction.target, transaction.id) != null) {
                    leases.remove(transaction.viewer, transaction.id);
                    return false;
                }
                transaction.quiescentPrepare = true;
                transaction.phase = "preparing";
                transactions.put(transaction.id, transaction);
                save(transaction);
                // Participant admission is already closed by UUID metadata. Actual owner
                // snapshots drain every old effect BEFORE this transaction takes group leases.
                dispatch(transaction.viewer);
                watchPreparing(transaction);
                return true;
            } catch (RuntimeException exception) {
                failure(transaction, exception);
                return true; // Custody or a durable prepare may already exist. The owner's tick retries it.
            } finally {
                transaction.lock.unlock();
            }
        }

        void process(ServerPlayer player) {
            requireOwner(player);
            if (player.isDeadOrDying() || player.isRemoved()) return;
            if (!leases.containsKey(player.getUUID())) return;
            UUID id = leases.get(player.getUUID());
            Transaction transaction = id == null ? null : transactions.get(id);
            if (transaction != null && !transaction.vaultOnly) {
                requestQuiet(player, transaction);
                if (transaction.quietOwners.get(player.getUUID()) != player) return;
            }
            var operation = new CompletableFuture<Void>();
            if (ownerOperations.putIfAbsent(player.getUUID(), operation) != null) return;
            carpet.script.external.ScarpetPlayerInventoryGate.whenIdle(player, () -> {
                        var actual = carpet.script.external.ScarpetNativeWork.<Void>observeNative(player, () -> {
                            // This is the previously prepared business continuation, admitted only
                            // after this exact live actor's old effects and producers were paused.
                            try (var accepted = carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player)) {
                                processOwned(player);
                            }
                            return null;
                        });
                        carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(player, actual);
                        return actual;
                    })
                    .whenComplete((ignored, failure) -> {
                        ownerOperations.remove(player.getUUID(), operation);
                        if (failure != null) operation.completeExceptionally(failure);
                        else operation.complete(null);
                    });
        }

        private void requestQuiet(ServerPlayer player, Transaction transaction) {
            ServerPlayer previous = transaction.quietApplicants.put(player.getUUID(), player);
            if (previous != null && previous != player) {
                transaction.quietRequests.remove(player.getUUID());
                transaction.quietOwners.remove(player.getUUID(), previous);
            }
            if (transaction.quietRequests.containsKey(player.getUUID())) return;
            var requested = new CompletableFuture<Void>();
            if (transaction.quietRequests.putIfAbsent(player.getUUID(), requested) != null) return;
            boolean scheduled = player.getBukkitEntity().taskScheduler.schedule(owner -> {
                if (owner != player || player.isRemoved() || player.isDeadOrDying()) {
                    requested.completeExceptionally(new IllegalStateException("Inventory participant retired before preparation"));
                    return;
                }
                try (var context = carpet.script.external.ScarpetPlayerInventoryGate.inheritAccepted(java.util.Set.of())) {
                    java.util.function.Supplier<CompletableFuture<Void>> hold = () -> {
                        if (transaction.quietApplicants.get(player.getUUID()) != player)
                            throw new IllegalStateException("Inventory participant instance changed before preparation");
                        if (player.isRemoved() || player.isDeadOrDying()) {
                            if (transaction.phase.equals("preparing")) cancelPreparing(transaction);
                            throw new IllegalStateException("Inventory participant retired while its previous native work terminated");
                        }
                        if (transaction.phase.equals("preparing")) {
                            List<ItemStack> actual = player.getUUID().equals(transaction.viewer) ? viewerState(player) : targetState(player, transaction.ender);
                            List<ItemStack> expected = player.getUUID().equals(transaction.viewer) ? transaction.viewerBefore : transaction.targetBefore;
                            if (!same(actual, expected)) {
                                cancelPreparing(transaction);
                                return transaction.completed;
                            }
                        }
                        transaction.quietOwners.put(player.getUUID(), player);
                        dispatch(transaction.viewer);
                        if (transaction.vault == null) dispatch(transaction.target);
                        return transaction.completed;
                    };
                    carpet.script.external.ScarpetNativeWork.without(() -> {
                        var pause = player.carpetActionPack == null ? carpet.script.external.ScarpetPlayerInventoryGate.whenIdle(player, hold) : OrgFakePlayerActions.whenIdle(player, hold);
                        pause.whenComplete((ignored, failure) -> {
                            transaction.quietOwners.remove(player.getUUID(), player);
                            if (failure == null) requested.complete(null);
                            else requested.completeExceptionally(failure);
                        });
                    });
                } catch (Throwable failure) {
                    requested.completeExceptionally(failure);
                }
            }, retired -> requested.completeExceptionally(new IllegalStateException("Inventory participant scheduler retired")), 1L);
            if (!scheduled)
                requested.completeExceptionally(new IllegalStateException("Inventory participant scheduler retired"));
            requested.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    transaction.quietRequests.remove(player.getUUID(), requested);
                    if (transaction.phase.equals("preparing")) cancelPreparing(transaction);
                    else {
                        transaction.quietOwners.remove(player.getUUID(), player);
                        failure(transaction, new IllegalStateException("Inventory participant's actual old work failed", failure));
                    }
                }
            });
        }

        private void cancelPreparing(Transaction transaction) {
            if (!transaction.lock.tryLock()) {
                CompletableFuture.runAsync(() -> cancelPreparing(transaction), CompletableFuture.delayedExecutor(50, java.util.concurrent.TimeUnit.MILLISECONDS, fileActors));
                return;
            }
            try {
                if (!List.of("preparing", "aborted").contains(transaction.phase)) return;
                transaction.refund = true;
                transaction.phase = "aborted";
                save(transaction);
                Files.deleteIfExists(directory.resolve(transaction.id + ".nbt"));
                transactions.remove(transaction.id, transaction);
                leases.remove(transaction.viewer, transaction.id);
                leases.remove(transaction.target, transaction.id);
                transaction.completed.complete(null);
                transaction.vaultAvailable.complete(null);
                var settled = transaction.settled;
                transaction.settled = null;
                if (settled != null) settled.accept(false);
                if (transaction.finished != null) {
                    var finished = transaction.finished;
                    transaction.finished = null;
                    finished.run();
                }
            } catch (IOException | RuntimeException failure) {
                failure(transaction, new IllegalStateException("Prepared metadata cancellation remains held", failure));
                CompletableFuture.runAsync(() -> cancelPreparing(transaction), CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS, fileActors));
            } finally {
                transaction.lock.unlock();
            }
        }

        private void watchPreparing(Transaction transaction) {
            CompletableFuture.runAsync(() -> {
                if (!transaction.phase.equals("preparing")) return;
                if (online(transaction.viewer) == null || transaction.vault == null && online(transaction.target) == null)
                    cancelPreparing(transaction);
                else watchPreparing(transaction);
            }, CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS, fileActors));
        }

        boolean beginFileOnly(Transaction transaction) {
            OrgItemShadowGroups.bindPersistence(server, transaction.shadows);
            transaction.shadowDurable = true;
            if (leases.putIfAbsent(transaction.target, transaction.id) != null) return false;
            if (!OrgItemShadowGroups.prepare(transaction.id, transaction.shadows, false)) {
                leases.remove(transaction.target, transaction.id);
                return false;
            }
            transactions.put(transaction.id, transaction);
            transaction.lock.lock();
            try {
                // All groups and the file participant are acquired before writing PREPARED.
                // No live player or world input is ever taken by this file-only actor.
                save(transaction);
                transaction.phase = "reserved";
                save(transaction);
            } catch (RuntimeException failure) {
                failure(transaction, failure);
            } finally {
                transaction.lock.unlock();
            }
            dispatch(transaction.target);
            return true;
        }

        private void processOwned(ServerPlayer player) {
            requireOwner(player);
            if (player.isDeadOrDying() || player.isRemoved()) return;
            UUID id = leases.get(player.getUUID());
            Transaction transaction = id == null ? null : transactions.get(id);
            if (transaction == null || !transaction.lock.tryLock()) return;
            try {
                save(transaction); // A failed prior ledger write must become durable before any other actor observes that phase.
                if (transaction.phase.equals("preparing")) {
                    if (!transaction.viewer.equals(player.getUUID())) {
                        dispatch(transaction.viewer);
                        return;
                    }
                    if (transaction.vault == null) {
                        ServerPlayer target = online(transaction.target);
                        if (target == null) {
                            cancelPreparing(transaction);
                            return;
                        }
                        requestQuiet(target, transaction);
                        if (!transaction.quietOwners.containsKey(transaction.target)) return;
                    }
                    // The other owner supplied only a metadata acknowledgement. Its live
                    // inventory is never read here; that owner validates again on commit.
                    if (!transaction.quietOwners.containsKey(transaction.viewer)) return;
                    if (!same(viewerState(player), transaction.viewerBefore) || !OrgItemShadowGroups.prepare(transaction.id, transaction.shadows, false)) {
                        cancelPreparing(transaction);
                        return;
                    }
                    transaction.phase = "prepared";
                    save(transaction);
                    dispatch(transaction.viewer);
                    return;
                }
                if (transaction.phase.equals("aborted")) {
                    cancelPreparing(transaction);
                    return;
                }
                if (transaction.viewer.equals(player.getUUID())) {
                    switch (transaction.phase) {
                        case "prepared" -> {
                            reserve(player, transaction);
                            dispatch(transaction.target);
                        }
                        case "reserved", "committing", "target_delivery" -> dispatch(transaction.target);
                        case "committed", "cancelled", "delivering" -> deliver(player, transaction);
                        case "complete" -> complete(transaction);
                    }
                } else if (List.of("reserved", "committing", "target_delivery").contains(transaction.phase))
                    commit(player, transaction);
            } catch (RuntimeException exception) {
                failure(transaction, exception);
            } finally {
                transaction.lock.unlock();
            }
        }

        private String marker(ServerPlayer player, NamespacedKey key) {
            return player.getBukkitEntity().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        }

        private void mark(ServerPlayer player, NamespacedKey key, String value) {
            if (value == null) player.getBukkitEntity().getPersistentDataContainer().remove(key);
            else player.getBukkitEntity().getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        }

        private void reserve(ServerPlayer viewer, Transaction transaction) {
            OrgItemShadowGroups.transaction(transaction.id, transaction.shadows, false, () -> {
                reserveOwned(viewer, transaction);
                return true;
            });
        }

        private void reserveOwned(ServerPlayer viewer, Transaction transaction) {
            String token = transaction.id + ":reserved";
            if (!token.equals(marker(viewer, SOURCE))) {
                // A prepared ledger without a player marker has not acquired custody after a restart.
                if (!same(viewerState(viewer), transaction.viewerBefore)) {
                    transaction.refund = true;
                    transaction.phase = "complete";
                    save(transaction);
                    complete(transaction);
                    return;
                }
                save(transaction);
                viewer.stopUsingItem();
                for (int slot : changed(transaction.viewerBefore, transaction.viewerAfter)) {
                    if (slot == transaction.viewerBefore.size() - 1) viewer.containerMenu.setCarried(ItemStack.EMPTY);
                    else viewer.getInventory().setItem(slot, ItemStack.EMPTY);
                }
                mark(viewer, SOURCE, token);
            }
            if (!persist(viewer, SOURCE, token))
                throw new IllegalStateException("Cannot verify the viewer's reserved inventory");
            transaction.phase = "reserved";
            save(transaction);
            viewer.containerMenu.broadcastFullState();
        }

        private void commit(ServerPlayer target, Transaction transaction) {
            requireOwner(target);
            String committed = transaction.id + ":committed";
            if (transaction.targetRemaining == null && !committed.equals(marker(target, TARGET))) {
                boolean acquired = OrgItemShadowGroups.transaction(transaction.id, transaction.shadows, false, () -> acquireTarget(target, transaction));
                if (!acquired) return;
            }
            OrgItemShadowGroups.transaction(transaction.id, transaction.shadows, true, () -> {
                creditTarget(target, transaction);
                return true;
            });
        }

        private boolean acquireTarget(ServerPlayer target, Transaction transaction) {
            Container inventory = transaction.ender ? target.getEnderChestInventory() : target.getInventory();
            String custody = transaction.id + ":target_reserved";
            if (!custody.equals(marker(target, TARGET))) {
                if (target.isRemoved() || target.isDeadOrDying() || !same(targetState(target, transaction.ender), transaction.targetBefore)) {
                    transaction.phase = "cancelled";
                    save(transaction);
                    dispatch(transaction.viewer);
                    return false;
                }
                // Acquire the target's changed stacks before exposing any replacement credit to normal gameplay.
                transaction.phase = "committing";
                save(transaction);
                target.stopUsingItem();
                for (int slot : changed(transaction.targetBefore, transaction.targetAfter))
                    inventory.setItem(slot, ItemStack.EMPTY);
                inventory.setChanged();
                mark(target, TARGET, custody);
            }
            if (!persist(target, TARGET, custody))
                throw new IllegalStateException("Cannot verify the target's escrow reservation");
            return true;
        }

        private void creditTarget(ServerPlayer target, Transaction transaction) {
            String token = transaction.id + ":committed";
            if (token.equals(marker(target, TARGET))) {
                if (!persist(target, TARGET, token))
                    throw new IllegalStateException("Cannot verify the target's committed inventory");
                transaction.phase = "committed";
                save(transaction);
                dispatch(transaction.viewer);
                return;
            }
            if (transaction.targetRemaining == null) {
                List<ItemStack> before = targetState(target, transaction.ender);
                CreditPlan plan = credit(before, transaction.targetBefore, transaction.targetAfter, false, true, transaction.ender ? before.size() : 36);
                targetCheckpoint(target, transaction, before, plan.after, plan.remaining, plan.credits);
            } else {
                applyTargetCheckpoint(target, transaction);
                if (!transaction.targetRemaining.isEmpty()) {
                    List<ItemStack> before = targetState(target, transaction.ender), after = copies(before);
                    List<ItemStack> credits = copies(transaction.targetRemaining), remaining = copies(transaction.targetRemaining);
                    insert(after, remaining, transaction.ender ? before.size() : 36);
                    if (!same(before, after)) targetCheckpoint(target, transaction, before, after, remaining, credits);
                }
            }
            if (!transaction.targetRemaining.isEmpty()) return;
            // Both sides' input custody is durable. All target credits are now acknowledged in its player file.
            mark(target, TARGET, token);
            if (!persist(target, TARGET, token))
                throw new IllegalStateException("Cannot verify the target's committed inventory");
            transaction.phase = "committed";
            save(transaction);
            target.inventoryMenu.broadcastChanges();
            target.containerMenu.broadcastChanges();
            dispatch(transaction.viewer);
        }

        private void targetCheckpoint(ServerPlayer target, Transaction transaction, List<ItemStack> before, List<ItemStack> after, List<ItemStack> remaining, List<ItemStack> credits) {
            transaction.targetDeliveryBefore = copies(before);
            transaction.targetDeliveryAfter = copies(after);
            transaction.targetRemaining = copies(remaining);
            transaction.targetDeliveryCredits = copies(credits);
            transaction.expectedTargetReceipt = marker(target, TARGET);
            transaction.targetDelivery++;
            transaction.phase = "target_delivery";
            save(transaction);
            applyTargetCheckpoint(target, transaction);
        }

        private void applyTargetCheckpoint(ServerPlayer target, Transaction transaction) {
            String token = transaction.id + ":target_delivery:" + transaction.targetDelivery;
            if ((transaction.id + ":target_hold:" + transaction.targetDelivery).equals(marker(target, TARGET))) {
                releaseHeld(target, transaction, true);
                return;
            }
            if (token.equals(marker(target, TARGET))) {
                if (!persist(target, TARGET, token))
                    throw new IllegalStateException("Cannot verify target credit receipt");
                return;
            }
            String previous = transaction.expectedTargetReceipt != null ? transaction.expectedTargetReceipt : transaction.targetDelivery == 1 ? transaction.id + ":target_reserved" : transaction.id + ":target_delivery:" + (transaction.targetDelivery - 1);
            if (!previous.equals(marker(target, TARGET)))
                throw new IllegalStateException("Unrecognized target escrow custody marker");
            List<ItemStack> current = targetState(target, transaction.ender);
            if (!same(current, transaction.targetDeliveryBefore)) {
                List<ItemStack> after = copies(current), remaining = copies(transaction.targetDeliveryCredits);
                insert(after, remaining, transaction.ender ? current.size() : 36);
                transaction.targetDeliveryBefore = current;
                transaction.targetDeliveryAfter = after;
                transaction.targetRemaining = remaining;
                save(transaction);
            }
            Container inventory = transaction.ender ? target.getEnderChestInventory() : target.getInventory();
            target.stopUsingItem();
            target.getBukkitEntity().getPersistentDataContainer().remove(HOLD);
            for (int slot : changed(transaction.targetDeliveryBefore, transaction.targetDeliveryAfter))
                inventory.setItem(slot, OrgItemShadowGroups.materialize(transaction.targetDeliveryAfter.get(slot)));
            inventory.setChanged();
            mark(target, TARGET, token);
            boolean verified;
            try {
                verified = persist(target, TARGET, token);
            } catch (RuntimeException exception) {
                holdUnverifiedCredit(target, transaction, true);
                throw exception;
            }
            if (!verified) {
                holdUnverifiedCredit(target, transaction, true);
                throw new IllegalStateException("Target credit save is unknown; affected stacks remain held until custody is verified");
            }
        }

        private void deliver(ServerPlayer viewer, Transaction transaction) {
            boolean after = !transaction.refund && !transaction.phase.equals("cancelled");
            OrgItemShadowGroups.transaction(transaction.id, transaction.shadows, after, () -> {
                deliverOwned(viewer, transaction);
                return true;
            });
        }

        private void deliverOwned(ServerPlayer viewer, Transaction transaction) {
            String finalToken = transaction.id + ":complete";
            if (finalToken.equals(marker(viewer, SOURCE))) {
                if (!persist(viewer, SOURCE, finalToken))
                    throw new IllegalStateException("Cannot verify completed inventory credit");
                transaction.phase = "complete";
                save(transaction);
                complete(transaction);
                return;
            }
            boolean refund = transaction.phase.equals("cancelled");
            if (transaction.remaining == null) {
                transaction.refund = refund;
                List<ItemStack> before = viewerState(viewer);
                CreditPlan plan = credit(before, transaction.viewerBefore, transaction.viewerAfter, refund, viewer.containerMenu == transaction.menu);
                checkpoint(viewer, transaction, before, plan.after, plan.remaining, plan.credits, finalToken);
            } else {
                String token = transaction.id + ":delivery:" + transaction.delivery;
                if (token.equals(marker(viewer, SOURCE))) {
                    if (!persist(viewer, SOURCE, token))
                        throw new IllegalStateException("Cannot verify partial inventory credit");
                } else {
                    // The last insertion checkpoint is durable but its player write was interrupted. It can be retried only against its own expected slots.
                    applyCheckpoint(viewer, transaction);
                }
                if (transaction.remaining.isEmpty()) {
                    mark(viewer, SOURCE, finalToken);
                    if (!persist(viewer, SOURCE, finalToken))
                        throw new IllegalStateException("Cannot verify final inventory marker");
                    transaction.phase = "complete";
                    save(transaction);
                    complete(transaction);
                    return;
                }
                List<ItemStack> before = viewerState(viewer);
                List<ItemStack> after = copies(before), credits = copies(transaction.remaining), remaining = copies(transaction.remaining);
                insert(after, remaining);
                if (!same(before, after))
                    checkpoint(viewer, transaction, before, after, remaining, credits, finalToken);
            }
            viewer.containerMenu.broadcastFullState();
        }

        private void checkpoint(ServerPlayer viewer, Transaction transaction, List<ItemStack> before, List<ItemStack> after, List<ItemStack> remaining, List<ItemStack> credits, String finalToken) {
            sourceCheckpoint(viewer, transaction, before, after, remaining, credits);
            if (transaction.remaining.isEmpty()) {
                mark(viewer, SOURCE, finalToken);
                if (!persist(viewer, SOURCE, finalToken))
                    throw new IllegalStateException("Cannot verify completed inventory credit");
                transaction.phase = "complete";
                save(transaction);
                complete(transaction);
            }
        }

        private void sourceCheckpoint(ServerPlayer viewer, Transaction transaction, List<ItemStack> before, List<ItemStack> after, List<ItemStack> remaining, List<ItemStack> credits) {
            transaction.deliveryBefore = copies(before);
            transaction.deliveryAfter = copies(after);
            transaction.remaining = copies(remaining);
            transaction.delivery++;
            transaction.phase = "delivering";
            transaction.deliveryCredits = copies(credits);
            transaction.expectedSourceReceipt = marker(viewer, SOURCE);
            save(transaction); // Keep both expected state and planned result before the player's marker can become durable.
            applyCheckpoint(viewer, transaction);
        }

        private void applyCheckpoint(ServerPlayer viewer, Transaction transaction) {
            String token = transaction.id + ":delivery:" + transaction.delivery;
            if ((transaction.id + ":source_hold:" + transaction.delivery).equals(marker(viewer, SOURCE))) {
                releaseHeld(viewer, transaction, false);
                return;
            }
            boolean applied = false;
            String previous = marker(viewer, SOURCE);
            if (!token.equals(previous)) {
                String expectedPrevious = transaction.expectedSourceReceipt != null ? transaction.expectedSourceReceipt : transaction.delivery == 1 ? transaction.id + ":reserved" : transaction.id + ":delivery:" + (transaction.delivery - 1);
                if (!expectedPrevious.equals(previous))
                    throw new IllegalStateException("Unrecognized escrow custody marker");
                if (!same(viewerState(viewer), transaction.deliveryBefore)) {
                    // No matching marker means this checkpoint did not persist. Replan its credit against the current state.
                    List<ItemStack> before = viewerState(viewer), after = copies(before), remaining = copies(transaction.deliveryCredits);
                    insert(after, remaining);
                    transaction.deliveryBefore = before;
                    transaction.deliveryAfter = after;
                    transaction.remaining = remaining;
                    save(transaction);
                }
                viewer.stopUsingItem();
                viewer.getBukkitEntity().getPersistentDataContainer().remove(HOLD);
                for (int slot : changed(transaction.deliveryBefore, transaction.deliveryAfter)) {
                    if (slot == transaction.deliveryBefore.size() - 1)
                        viewer.containerMenu.setCarried(OrgItemShadowGroups.materialize(transaction.deliveryAfter.get(slot)));
                    else
                        viewer.getInventory().setItem(slot, OrgItemShadowGroups.materialize(transaction.deliveryAfter.get(slot)));
                }
                mark(viewer, SOURCE, token);
                applied = true;
            }
            boolean verified;
            try {
                verified = persist(viewer, SOURCE, token);
            } catch (RuntimeException exception) {
                if (applied) holdUnverifiedCredit(viewer, transaction, false);
                throw exception;
            }
            if (!verified) {
                if (applied) holdUnverifiedCredit(viewer, transaction, false);
                throw new IllegalStateException("Cannot verify an escrow insertion");
            }
        }

        private void holdUnverifiedCredit(ServerPlayer player, Transaction transaction, boolean targetSide) {
            // No compensating snapshot is made spendable. Hold BOTH existing and newly credited contents of affected slots.
            List<ItemStack> before = targetSide ? transaction.targetDeliveryBefore : transaction.deliveryBefore;
            List<ItemStack> after = targetSide ? transaction.targetDeliveryAfter : transaction.deliveryAfter;
            List<ItemStack> current = targetSide ? targetState(player, transaction.ender) : viewerState(player);
            List<Integer> slots = changed(before, after);
            List<ItemStack> held = new ArrayList<>();
            for (int slot : slots) held.add(OrgItemShadowGroups.snapshot(current.get(slot)));
            int step = targetSide ? transaction.targetDelivery : transaction.delivery;
            CompoundTag data = new CompoundTag();
            data.putString("transaction", transaction.id.toString());
            data.putBoolean("target", targetSide);
            data.putInt("step", step);
            data.putIntArray("slots", slots.stream().mapToInt(Integer::intValue).toArray());
            stacks(data, "held", held);
            byte[] serialized;
            try (var output = new java.io.ByteArrayOutputStream()) {
                NbtIo.writeCompressed(data, output);
                serialized = output.toByteArray();
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot encode held inventory custody", exception);
            }
            player.stopUsingItem();
            player.getBukkitEntity().getPersistentDataContainer().set(HOLD, PersistentDataType.BYTE_ARRAY, serialized);
            Container container = targetSide && transaction.ender ? player.getEnderChestInventory() : player.getInventory();
            for (int slot : slots) {
                if (!targetSide && slot == before.size() - 1) player.containerMenu.setCarried(ItemStack.EMPTY);
                else container.setItem(slot, ItemStack.EMPTY);
            }
            container.setChanged();
            mark(player, targetSide ? TARGET : SOURCE, transaction.id + (targetSide ? ":target_hold:" : ":source_hold:") + step);
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastFullState();
        }

        private void releaseHeld(ServerPlayer player, Transaction transaction, boolean targetSide) {
            String receipt = marker(player, targetSide ? TARGET : SOURCE);
            byte[] serialized = player.getBukkitEntity().getPersistentDataContainer().get(HOLD, PersistentDataType.BYTE_ARRAY);
            CompoundTag held;
            try {
                held = NbtIo.readCompressed(new java.io.ByteArrayInputStream(serialized), NbtAccounter.unlimitedHeap());
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Cannot decode held inventory custody", exception);
            }
            int step = targetSide ? transaction.targetDelivery : transaction.delivery;
            if (!held.getStringOr("transaction", "").equals(transaction.id.toString()) || held.getBooleanOr("target", !targetSide) != targetSide || held.getIntOr("step", -1) != step)
                throw new IllegalStateException("Held inventory receipt does not match its escrow");
            // A marker alone is insufficient: first verify the exact held payload and currently spendable inventory on disk.
            if (!persist(player, targetSide ? TARGET : SOURCE, receipt))
                throw new IllegalStateException("Held inventory custody remains unavailable until storage is readable");
            List<ItemStack> before = targetSide ? targetState(player, transaction.ender) : viewerState(player), after = copies(before);
            List<ItemStack> credits = new ArrayList<>(stacks(held, "held")), remaining = new ArrayList<>();
            int[] slots = held.getIntArray("slots").orElseThrow();
            if (slots.length != credits.size())
                throw new IllegalStateException("Held inventory slot metadata is malformed");
            for (int index = 0; index < slots.length; index++) {
                int slot = slots[index];
                if (slot < 0 || slot >= before.size())
                    throw new IllegalStateException("Held inventory slot is out of range");
                ItemStack credit = OrgItemShadowGroups.snapshot(credits.get(index));
                if (after.get(slot).isEmpty() && (targetSide || slot != before.size() - 1 || player.containerMenu == transaction.menu))
                    after.set(slot, credit);
                else remaining.add(credit);
            }
            List<ItemStack> pending = targetSide ? transaction.targetRemaining : transaction.remaining;
            remaining.addAll(copies(pending));
            credits.addAll(copies(pending));
            insert(after, remaining, targetSide && transaction.ender ? before.size() : 36);
            if (targetSide) targetCheckpoint(player, transaction, before, after, remaining, credits);
            else sourceCheckpoint(player, transaction, before, after, remaining, credits);
        }

        private void complete(Transaction transaction) {
            OrgItemShadowGroups.transaction(transaction.id, transaction.shadows, !transaction.refund, () -> {
                completeOwned(transaction);
                return true;
            });
        }

        private void completeOwned(Transaction transaction) {
            ServerPlayer viewer = online(transaction.viewer);
            if (viewer == null || !TickThread.isTickThreadFor(viewer) || viewer.isDeadOrDying()) return;
            String receipt = marker(viewer, SOURCE);
            String prefix = transaction.id + ":drop:";
            if (receipt != null && receipt.startsWith(prefix)) {
                int acknowledged = Integer.parseInt(receipt.substring(prefix.length()));
                if (acknowledged < 0 || acknowledged > transaction.drops.size())
                    throw new IllegalStateException("Invalid world drop receipt");
                if (!persist(viewer, SOURCE, receipt))
                    throw new IllegalStateException("Cannot verify the pending drop receipt");
                if (acknowledged > transaction.dropped) {
                    transaction.dropped = acknowledged;
                    save(transaction);
                }
            }
            if (transaction.dropped > 0 && !persist(viewer, SOURCE, transaction.id + ":drop:" + transaction.dropped))
                throw new IllegalStateException("Cannot verify the completed drop receipt");
            // World effects run only after both inventory actors and the viewer credit have been saved.
            while (!transaction.refund && transaction.dropped < transaction.drops.size()) {
                UUID entityId = UUID.nameUUIDFromBytes((transaction.id + ":drop:" + transaction.dropped).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (viewer.level().getEntityInAnyDimension(entityId) == null) {
                    save(transaction);
                    viewer.drop(OrgItemShadowGroups.materialize(transaction.drops.get(transaction.dropped)), true, net.minecraft.util.Prediction.SERVER_ONLY, true,
                            item -> ((org.bukkit.craftbukkit.entity.CraftEntity) item).getHandle().setUUID(entityId));
                }
                int next = transaction.dropped + 1;
                mark(viewer, SOURCE, transaction.id + ":drop:" + next);
                if (!persist(viewer, SOURCE, transaction.id + ":drop:" + next))
                    throw new IllegalStateException("Cannot verify post-drop inventory state");
                transaction.dropped = next;
                save(transaction);
            }
            OrgItemShadowGroups.finish(transaction.id, transaction.shadows, !transaction.refund);
            try {
                Files.deleteIfExists(directory.resolve(transaction.id + ".nbt"));
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot retire a completed escrow", exception);
            }
            transactions.remove(transaction.id, transaction);
            leases.remove(transaction.viewer, transaction.id);
            leases.remove(transaction.target, transaction.id);
            OrgItemShadowGroups.forget(transaction.id, transaction.shadows);
            transaction.completed.complete(null);
            transaction.vaultAvailable.complete(null);
            Runnable finished = transaction.finished;
            transaction.finished = null;
            if (finished != null) finished.run();
            var settled = transaction.settled;
            transaction.settled = null;
            if (settled != null) settled.accept(!transaction.refund);
        }

        private void dispatch(UUID id) {
            UUID transactionId = leases.get(id);
            Transaction transaction = transactionId == null ? null : transactions.get(transactionId);
            if (transaction != null && transaction.vault != null && transaction.target.equals(id)) {
                if (transaction.vaultRunning.compareAndSet(false, true)) CompletableFuture.runAsync(() -> {
                    try {
                        processVault(transaction);
                    } finally {
                        transaction.vaultRunning.set(false);
                        if (transaction.vaultOnly && transactions.containsKey(transaction.id))
                            CompletableFuture.runAsync(() -> dispatch(transaction.target), CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS, fileActors));
                    }
                }, fileActors);
                return;
            }
            ServerPlayer player = online(id);
            if (player != null)
                player.getBukkitEntity().taskScheduler.schedule(owned -> process((ServerPlayer) owned), null, 1L);
        }

        private Path vaultPath(Vault vault) {
            Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path path = root.resolve(vault.relativePath()).normalize();
            if (!path.startsWith(root) || path.equals(root) || Files.isSymbolicLink(path))
                throw new IllegalArgumentException("Vault path leaves its world");
            for (Path parent = path.getParent(); parent != null && !parent.equals(root); parent = parent.getParent())
                if (Files.isSymbolicLink(parent))
                    throw new IllegalArgumentException("Vault directory is a symbolic link");
            return path;
        }

        private CompoundTag readVault(Path path, boolean compressed) throws IOException {
            if (!Files.exists(path)) return null;
            return compressed ? NbtIo.readCompressed(path, NbtAccounter.create(64L * 1024L * 1024L)) : NbtIo.read(path);
        }

        private <T> void nativePlayerData(UUID participant, java.util.function.Function<NativePlayerData, CompletableFuture<T>> operation, CompletableFuture<T> result) {
            UUID token = UUID.randomUUID();
            var held = new CompletableFuture<Void>();
            auxiliary.put(token, held);
            if (leases.putIfAbsent(participant, token) != null) {
                auxiliary.remove(token, held);
                whenAvailable(server, participant).whenComplete((ignored, failure) -> {
                    if (failure != null) result.completeExceptionally(failure);
                    else CompletableFuture.runAsync(() -> nativePlayerData(participant, operation, result), fileActors);
                });
                return;
            }
            NativePlayerData custody = new NativePlayerData(this, participant, token);
            CompletableFuture<T> actual;
            try {
                actual = Objects.requireNonNull(operation.apply(custody));
            } catch (Throwable failure) {
                actual = CompletableFuture.failedFuture(failure);
            }
            actual.whenComplete((value, failure) -> {
                custody.active.set(false);
                leases.remove(participant, token);
                auxiliary.remove(token, held);
                held.complete(null);
                if (failure == null) result.complete(value);
                else result.completeExceptionally(failure);
            });
        }

        private void replaceNativePlayerData(NativePlayerData custody, String relativePath, CompoundTag expected, CompoundTag desired, CompletableFuture<Void> result) {
            Path path = vaultPath(new Vault(custody.participant, relativePath, true, null, new CompoundTag()));
            Path nativePath = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(custody.participant + ".dat").toAbsolutePath().normalize();
            if (!path.equals(nativePath))
                throw new IllegalArgumentException("Native player-data maintenance cannot mutate another vault");
            var attempted = new AtomicBoolean();
            var reported = new AtomicBoolean();
            Runnable[] retry = new Runnable[1];
            retry[0] = () -> {
                try {
                    if (!custody.active.get() || !custody.token.equals(leases.get(custody.participant)))
                        throw new IllegalStateException("Native player-data participant custody changed");
                    CompoundTag current = readVault(path, true);
                    if (desired.equals(current)) {
                        result.complete(null);
                        retry[0] = null;
                        return;
                    }
                    if (!expected.equals(current)) {
                        if (attempted.get())
                            throw new IOException("Native player-data write outcome is unknown; custody remains held");
                        throw new IllegalStateException("Native player-data changed before its upgrade");
                    }
                    if (server.getPlayerList().getPlayer(custody.participant) != null || server.getBotList() != null && server.getBotList().getBot(custody.participant) != null)
                        throw new IllegalStateException("Native player-data upgrade cannot overwrite an online owner");
                    attempted.set(true);
                    writeVault(path, true, desired);
                    result.complete(null);
                    retry[0] = null;
                } catch (IOException failure) {
                    if (reported.compareAndSet(false, true)) report(failure);
                    CompletableFuture.runAsync(retry[0], CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS, fileActors));
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                    retry[0] = null;
                }
            };
            CompletableFuture.runAsync(retry[0], fileActors);
        }

        private void writeVault(Path path, boolean compressed, CompoundTag desired) throws IOException {
            Files.createDirectories(path.getParent());
            Path staging = Files.createTempFile(path.getParent(), "vault-", ".tmp");
            try {
                if (compressed) NbtIo.writeCompressed(desired, staging);
                else NbtIo.write(desired, staging);
                try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                try {
                    Files.move(staging, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!desired.equals(readVault(path, compressed)))
                    throw new IOException("Vault image readback did not match");
            } finally {
                Files.deleteIfExists(staging);
            }
        }

        private void metadata(UUID participant, String relativePath, boolean compressed,
                              java.util.function.UnaryOperator<CompoundTag> update, CompletableFuture<Void> result) {
            UUID id = UUID.randomUUID();
            CompletableFuture<Void> lease = new CompletableFuture<>();
            auxiliary.put(id, lease);
            if (leases.putIfAbsent(participant, id) != null) {
                auxiliary.remove(id, lease);
                whenAvailable(server, participant).whenComplete((ignored, failure) -> {
                    if (failure != null) result.completeExceptionally(failure);
                    else
                        CompletableFuture.runAsync(() -> metadata(participant, relativePath, compressed, update, result), fileActors);
                });
                return;
            }
            Path path;
            try {
                path = vaultPath(new Vault(participant, relativePath, compressed, null, new CompoundTag()));
            } catch (RuntimeException failure) {
                leases.remove(participant, id);
                auxiliary.remove(id, lease);
                lease.complete(null);
                result.completeExceptionally(failure);
                return;
            }
            Runnable[] retry = new Runnable[1];
            AtomicBoolean reported = new AtomicBoolean();
            retry[0] = () -> {
                try {
                    CompoundTag before = readVault(path, compressed);
                    if (before == null) throw new IllegalArgumentException("Cannot update absent vault metadata");
                    CompoundTag after = update.apply(before.copy());
                    for (String asset : List.of("items", "Inventory", "equipment", "EnderItems", CURSOR_TAG, SHADOW_TAG))
                        if (!Objects.equals(before.get(asset), after.get(asset)))
                            throw new IllegalArgumentException("Metadata work attempted to change vault assets: " + asset);
                    if (!after.equals(before)) writeVault(path, compressed, after);
                    leases.remove(participant, id);
                    auxiliary.remove(id, lease);
                    lease.complete(null);
                    result.complete(null);
                    retry[0] = null;
                } catch (IOException failure) {
                    if (reported.compareAndSet(false, true)) report(failure);
                    CompletableFuture.runAsync(retry[0], CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS, fileActors));
                } catch (RuntimeException failure) {
                    leases.remove(participant, id);
                    auxiliary.remove(id, lease);
                    lease.complete(null);
                    result.completeExceptionally(failure);
                    retry[0] = null;
                }
            };
            CompletableFuture.runAsync(retry[0], fileActors);
        }

        private void processVault(Transaction transaction) {
            if (!transaction.lock.tryLock()) return;
            try {
                if (transaction.vaultOnly) {
                    if (transaction.phase.equals("prepared")) {
                        save(transaction);
                        transaction.phase = "reserved";
                        save(transaction);
                    }
                    if (List.of("committed", "cancelled", "complete").contains(transaction.phase)) {
                        completeFileOnly(transaction);
                        return;
                    }
                }
                if (transaction.vaultReleased && List.of("committed", "cancelled", "delivering", "complete").contains(transaction.phase)) {
                    save(transaction);
                    leases.remove(transaction.target, transaction.id);
                    transaction.vaultAvailable.complete(null);
                    dispatch(transaction.viewer);
                    return;
                }
                if (!List.of("reserved", "committing").contains(transaction.phase)) return;
                save(transaction);
                Vault vault = transaction.vault;
                Path path = vaultPath(vault);
                CompoundTag desired = vault.after().copy();
                desired.putString("CarpetOrgVaultTransaction", transaction.id.toString());
                CompoundTag current = readVault(path, vault.compressed());
                if (!desired.equals(current)) {
                    // An unknown write never refunds. Read the actual receipt/image first; the
                    // exclusive file lease prevents another consumer spending unverified stock.
                    if (!Objects.equals(current, vault.before())) {
                        if (transaction.phase.equals("committing"))
                            throw new IOException("Vault write outcome is unknown; its custody remains held");
                        transaction.phase = "cancelled";
                        transaction.vaultReleased = !transaction.vaultOnly;
                        save(transaction);
                        if (transaction.vaultOnly) {
                            completeFileOnly(transaction);
                            return;
                        }
                        leases.remove(transaction.target, transaction.id);
                        transaction.vaultAvailable.complete(null);
                        dispatch(transaction.viewer);
                        return;
                    }
                    transaction.phase = "committing";
                    save(transaction);
                    writeVault(path, vault.compressed(), desired);
                }
                // The file actor can accept another parcel operation once its exact after image
                // and durable phase are verified. Source custody remains until its own completion.
                transaction.phase = "committed";
                transaction.vaultReleased = !transaction.vaultOnly;
                save(transaction);
                if (transaction.vaultOnly) {
                    completeFileOnly(transaction);
                    return;
                }
                leases.remove(transaction.target, transaction.id);
                transaction.vaultAvailable.complete(null);
                dispatch(transaction.viewer);
            } catch (IOException | RuntimeException failure) {
                failure(transaction, new IllegalStateException("Durable inventory actor remains in escrow", failure));
            } finally {
                transaction.lock.unlock();
            }
        }

        private void completeFileOnly(Transaction transaction) throws IOException {
            transaction.refund = transaction.phase.equals("cancelled") || transaction.refund;
            transaction.phase = "complete";
            save(transaction);
            OrgItemShadowGroups.finish(transaction.id, transaction.shadows, !transaction.refund);
            Files.deleteIfExists(directory.resolve(transaction.id + ".nbt"));
            transactions.remove(transaction.id, transaction);
            leases.remove(transaction.target, transaction.id);
            OrgItemShadowGroups.forget(transaction.id, transaction.shadows);
            transaction.completed.complete(null);
            transaction.vaultAvailable.complete(null);
            var settled = transaction.settled;
            transaction.settled = null;
            if (settled != null) settled.accept(!transaction.refund);
        }

        private ServerPlayer online(UUID id) {
            return server.getPlayerList().getPlayer(id);
        }

        private boolean persist(ServerPlayer player, NamespacedKey key, String token) {
            byte[] expectedHeld = player.getBukkitEntity().getPersistentDataContainer().get(HOLD, PersistentDataType.BYTE_ARRAY);
            CompoundTag expected = inventoryTag(player);
            Optional<CompoundTag> saved;
            if (player instanceof ServerBot bot) saved = server.getBotList().saveCarpetBotState(bot);
            else {
                server.getPlayerList().playerIo.save(player);
                saved = server.getPlayerList().playerIo.load(player.nameAndId());
            }
            return saved.filter(tag -> {
                for (String field : List.of("Inventory", "equipment", "EnderItems", CURSOR_TAG, SHADOW_TAG))
                    if (!Objects.equals(expected.get(field), tag.get(field))) return false;
                CompoundTag values = tag.getCompoundOrEmpty("BukkitValues");
                return java.util.Arrays.equals(expectedHeld, values.getByteArray(HOLD.toString()).orElse(null)) && values.getString(key.toString()).filter(token::equals).isPresent();
            }).isPresent();
        }

        private void save(Transaction transaction) {
            CompoundTag data = write(transaction);
            Path staging = null;
            try {
                Files.createDirectories(directory);
                staging = Files.createTempFile(directory, "inventory-", ".tmp");
                NbtIo.writeCompressed(data, staging);
                try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                Path target = directory.resolve(transaction.id + ".nbt");
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!data.equals(NbtIo.readCompressed(target, NbtAccounter.unlimitedHeap())))
                    throw new IOException("Escrow readback did not match");
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot save inventory escrow", exception);
            } finally {
                if (staging != null) try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) {
                }
            }
        }

        private CompoundTag write(Transaction transaction) {
            CompoundTag result = new CompoundTag();
            result.putInt("version", 3);
            result.putString("id", transaction.id.toString());
            result.putString("viewer", transaction.viewer.toString());
            result.putString("target", transaction.target.toString());
            if (transaction.vault != null) {
                CompoundTag vault = new CompoundTag();
                vault.putString("path", transaction.vault.relativePath());
                vault.putBoolean("compressed", transaction.vault.compressed());
                if (transaction.vault.before() != null) vault.put("before", transaction.vault.before());
                vault.put("after", transaction.vault.after());
                result.put("vault", vault);
                result.putBoolean("vaultReleased", transaction.vaultReleased);
                result.putBoolean("vaultOnly", transaction.vaultOnly);
            }
            result.putBoolean("ender", transaction.ender);
            result.putString("phase", transaction.phase);
            result.putInt("delivery", transaction.delivery);
            result.putInt("menu", transaction.menuId);
            result.putBoolean("refund", transaction.refund);
            result.putInt("dropped", transaction.dropped);
            result.putBoolean("shadowDurable", transaction.shadowDurable);
            result.putBoolean("quiescentPrepare", transaction.quiescentPrepare);
            result.putInt("targetDelivery", transaction.targetDelivery);
            if (transaction.expectedSourceReceipt != null)
                result.putString("expectedSourceReceipt", transaction.expectedSourceReceipt);
            if (transaction.expectedTargetReceipt != null)
                result.putString("expectedTargetReceipt", transaction.expectedTargetReceipt);
            stacks(result, "viewerBefore", transaction.viewerBefore);
            stacks(result, "viewerAfter", transaction.viewerAfter);
            stacks(result, "targetBefore", transaction.targetBefore);
            stacks(result, "targetAfter", transaction.targetAfter);
            stacks(result, "drops", transaction.drops);
            net.minecraft.nbt.ListTag shadows = new net.minecraft.nbt.ListTag();
            for (var change : transaction.shadows) {
                CompoundTag shadow = new CompoundTag();
                shadow.put("before", OrgShadowInventoryCodec.descriptor(change.before(), ops, false));
                shadow.put("after", OrgShadowInventoryCodec.descriptor(change.after(), ops, false));
                shadows.add(shadow);
            }
            if (!shadows.isEmpty()) result.put("shadows", shadows);
            if (transaction.remaining != null) {
                stacks(result, "remaining", transaction.remaining);
                stacks(result, "deliveryBefore", transaction.deliveryBefore);
                stacks(result, "deliveryAfter", transaction.deliveryAfter);
                stacks(result, "deliveryCredits", transaction.deliveryCredits);
            }
            if (transaction.targetRemaining != null) {
                stacks(result, "targetRemaining", transaction.targetRemaining);
                stacks(result, "targetDeliveryBefore", transaction.targetDeliveryBefore);
                stacks(result, "targetDeliveryAfter", transaction.targetDeliveryAfter);
                stacks(result, "targetDeliveryCredits", transaction.targetDeliveryCredits);
            }
            return result;
        }

        private void stacks(CompoundTag data, String key, List<ItemStack> stacks) {
            OrgShadowInventoryCodec.stacks(data, key, stacks, ops);
        }

        private List<ItemStack> stacks(CompoundTag data, String key) {
            return OrgShadowInventoryCodec.stacks(data, key, ops);
        }

        private Transaction read(CompoundTag data) {
            if (!List.of(1, 2, 3).contains(data.getIntOr("version", -1)))
                throw new IllegalArgumentException("Unsupported inventory escrow version");
            Transaction transaction = new Transaction();
            transaction.id = UUID.fromString(data.getStringOr("id", ""));
            transaction.viewer = UUID.fromString(data.getStringOr("viewer", ""));
            transaction.target = UUID.fromString(data.getStringOr("target", ""));
            transaction.ender = data.getBooleanOr("ender", false);
            transaction.phase = data.getStringOr("phase", "");
            transaction.delivery = data.getIntOr("delivery", 0);
            transaction.menuId = -1;
            if (data.contains("vault")) {
                CompoundTag vault = data.getCompoundOrEmpty("vault");
                transaction.vault = new Vault(transaction.target, vault.getStringOr("path", ""), vault.getBooleanOr("compressed", false), vault.getCompound("before").orElse(null), vault.getCompound("after").orElseThrow());
                vaultPath(transaction.vault);
                transaction.vaultReleased = data.getBooleanOr("vaultReleased", false);
                transaction.vaultOnly = data.getBooleanOr("vaultOnly", false);
                if (transaction.vaultReleased && !List.of("committed", "cancelled", "delivering", "complete").contains(transaction.phase))
                    throw new IllegalArgumentException("Invalid released vault phase");
            }
            transaction.refund = data.getBooleanOr("refund", false);
            transaction.dropped = data.getIntOr("dropped", 0);
            transaction.shadowDurable = data.getBooleanOr("shadowDurable", false);
            transaction.quiescentPrepare = data.getBooleanOr("quiescentPrepare", false);
            transaction.targetDelivery = data.getIntOr("targetDelivery", 0);
            transaction.expectedSourceReceipt = data.getString("expectedSourceReceipt").orElse(null);
            transaction.expectedTargetReceipt = data.getString("expectedTargetReceipt").orElse(null);
            if (transaction.viewer.equals(transaction.target) != transaction.vaultOnly || transaction.vaultOnly && transaction.vault == null || !List.of("preparing", "aborted", "prepared", "reserved", "committing", "target_delivery", "committed", "cancelled", "delivering", "complete").contains(transaction.phase) || transaction.phase.equals("preparing") && !transaction.quiescentPrepare)
                throw new IllegalArgumentException("Malformed inventory escrow participants or phase");
            transaction.viewerBefore = stacks(data, "viewerBefore");
            transaction.viewerAfter = stacks(data, "viewerAfter");
            transaction.targetBefore = stacks(data, "targetBefore");
            transaction.targetAfter = stacks(data, "targetAfter");
            transaction.drops = stacks(data, "drops");
            if (transaction.vaultOnly && (!transaction.drops.isEmpty() || !same(transaction.viewerBefore, transaction.viewerAfter) || transaction.viewerBefore.stream().anyMatch(stack -> !stack.isEmpty())))
                throw new IllegalArgumentException("File-only custody cannot contain player/world input");
            List<OrgItemShadowGroups.Change> shadows = new ArrayList<>();
            var groupIds = new java.util.HashSet<UUID>();
            for (Tag value : data.getListOrEmpty("shadows")) {
                if (!(value instanceof CompoundTag entry))
                    throw new IllegalArgumentException("Malformed item shadow transition");
                ItemStack before = OrgShadowInventoryCodec.descriptor(entry.getCompoundOrEmpty("before"), ops, false), after = OrgShadowInventoryCodec.descriptor(entry.getCompoundOrEmpty("after"), ops, false);
                if (!before.carpetOrgShadowId.equals(after.carpetOrgShadowId) || !groupIds.add(before.carpetOrgShadowId) || before.carpetOrgShadowRevision != after.carpetOrgShadowRevision)
                    throw new IllegalArgumentException("Conflicting item shadow transition");
                shadows.add(new OrgItemShadowGroups.Change(before.carpetOrgShadowId, before.carpetOrgShadowRevision, before, after));
            }
            transaction.shadows = List.copyOf(shadows);
            if (transaction.viewerBefore.size() != 44 || transaction.viewerAfter.size() != 44 || transaction.targetBefore.size() != transaction.targetAfter.size()
                    || !(transaction.vault != null ? transaction.targetBefore.size() <= 4096 : transaction.ender ? transaction.targetBefore.size() == 27 || transaction.targetBefore.size() == 54 : transaction.targetBefore.size() == 43))
                throw new IllegalArgumentException("Malformed inventory escrow slot count");
            if (data.contains("remaining")) {
                transaction.remaining = stacks(data, "remaining");
                transaction.deliveryBefore = stacks(data, "deliveryBefore");
                transaction.deliveryAfter = stacks(data, "deliveryAfter");
                transaction.deliveryCredits = stacks(data, "deliveryCredits");
            }
            if (data.contains("targetRemaining")) {
                transaction.targetRemaining = stacks(data, "targetRemaining");
                transaction.targetDeliveryBefore = stacks(data, "targetDeliveryBefore");
                transaction.targetDeliveryAfter = stacks(data, "targetDeliveryAfter");
                transaction.targetDeliveryCredits = stacks(data, "targetDeliveryCredits");
            }
            if (transaction.delivery < 0 || transaction.dropped < 0 || transaction.dropped > transaction.drops.size()
                    || transaction.remaining != null && (transaction.deliveryBefore.size() != 44 || transaction.deliveryAfter.size() != 44))
                throw new IllegalArgumentException("Malformed escrow delivery checkpoint");
            if (transaction.targetDelivery < 0 || transaction.targetRemaining != null && (transaction.targetDeliveryBefore.size() != transaction.targetBefore.size() || transaction.targetDeliveryAfter.size() != transaction.targetBefore.size()))
                throw new IllegalArgumentException("Malformed target escrow delivery checkpoint");
            if (transaction.phase.equals("target_delivery") && transaction.targetRemaining == null || transaction.phase.equals("delivering") && transaction.remaining == null)
                throw new IllegalArgumentException("Missing escrow delivery checkpoint");
            return transaction;
        }

        private void failure(Transaction transaction, RuntimeException exception) {
            if (!transaction.reported) {
                transaction.reported = true;
                report(exception);
            }
        }
    }

    static record CreditPlan(List<ItemStack> after, List<ItemStack> remaining, List<ItemStack> credits) {
    }

    static CreditPlan credit(List<ItemStack> current, List<ItemStack> originalBefore, List<ItemStack> originalAfter, boolean refund, boolean cursorAllowed) {
        return credit(current, originalBefore, originalAfter, refund, cursorAllowed, 36);
    }

    static CreditPlan credit(List<ItemStack> current, List<ItemStack> originalBefore, List<ItemStack> originalAfter, boolean refund, boolean cursorAllowed, int capacity) {
        List<ItemStack> wanted = refund ? originalBefore : originalAfter;
        List<ItemStack> after = copies(current), remaining = new ArrayList<>(), credits = new ArrayList<>();
        for (int slot : changed(originalBefore, originalAfter)) {
            ItemStack stack = OrgItemShadowGroups.snapshot(wanted.get(slot));
            if (stack.isEmpty()) continue;
            credits.add(OrgItemShadowGroups.snapshot(stack));
            if (after.get(slot).isEmpty() && (slot != current.size() - 1 || cursorAllowed)) after.set(slot, stack);
            else remaining.add(stack);
        }
        insert(after, remaining, capacity);
        return new CreditPlan(after, remaining, credits);
    }

    static void insert(List<ItemStack> after, List<ItemStack> remaining) {
        insert(after, remaining, Math.min(36, after.size() - 1));
    }

    static void insert(List<ItemStack> after, List<ItemStack> remaining, int capacity) {
        for (var iterator = remaining.iterator(); iterator.hasNext(); ) {
            ItemStack credit = iterator.next();
            if (credit.carpetOrgShadowId != null) {
                for (int slot = 0; slot < Math.min(capacity, after.size()); slot++)
                    if (after.get(slot).isEmpty()) {
                        after.set(slot, OrgItemShadowGroups.snapshot(credit));
                        iterator.remove();
                        break;
                    }
                continue;
            }
            for (int slot = 0; slot < Math.min(capacity, after.size()) && !credit.isEmpty(); slot++) {
                ItemStack current = after.get(slot);
                if (!current.isEmpty() && current.carpetOrgShadowId == null && ItemStack.isSameItemSameComponents(current, credit)) {
                    int count = Math.min(credit.getCount(), Math.max(0, current.getMaxStackSize() - current.getCount()));
                    current.grow(count);
                    credit.shrink(count);
                }
            }
            for (int slot = 0; slot < Math.min(capacity, after.size()) && !credit.isEmpty(); slot++) {
                if (after.get(slot).isEmpty())
                    after.set(slot, credit.split(Math.min(credit.getCount(), credit.getMaxStackSize())));
            }
        }
        remaining.removeIf(ItemStack::isEmpty);
    }

    static List<Integer> changed(List<ItemStack> before, List<ItemStack> after) {
        if (before.size() != after.size()) throw new IllegalArgumentException("Inventory state sizes differ");
        List<Integer> result = new ArrayList<>();
        for (int slot = 0; slot < before.size(); slot++) if (!same(before.get(slot), after.get(slot))) result.add(slot);
        return result;
    }
}
