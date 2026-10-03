package fun.bm.lophine.carpet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Logical aliases use immutable canonical data; no actor writes another actor's mutable stack. */
public final class OrgItemShadowGroups {
    private static final java.lang.ref.ReferenceQueue<Group> COLLECTED_GROUPS = new java.lang.ref.ReferenceQueue<>();
    private static final Map<UUID, GroupReference> GROUPS = new ConcurrentHashMap<>();
    // A durable draft is explicit custody. Its strong pins are removed only after confirmed
    // publication, unlike the weak index of ordinary live Java aliases.
    private static final Map<java.nio.file.Path,Map<UUID,Group>> DURABLE_PINS = new ConcurrentHashMap<>();
    private static java.util.concurrent.Executor journalExecutor = java.util.concurrent.ForkJoinPool.commonPool();
    private static final class GroupReference extends java.lang.ref.WeakReference<Group> {
        final UUID id;
        GroupReference(UUID id, Group group) { super(group, COLLECTED_GROUPS); this.id = id; }
    }
    private static void collectGroups() {
        GroupReference reference;
        while ((reference = (GroupReference) COLLECTED_GROUPS.poll()) != null) GROUPS.remove(reference.id, reference);
    }
    private static final ScopedValue<Map<UUID, Borrow>> BORROWS = ScopedValue.newInstance();
    private static final ScopedValue<NativeLoan> NATIVE_LOAN=ScopedValue.newInstance();
    private static final class NativeLoan {
        final NativeLoan parent;
        final net.minecraft.world.entity.Entity owner;
        final Map<UUID,Borrow> borrowed;
        volatile boolean closed;
        NativeLoan(NativeLoan parent,net.minecraft.world.entity.Entity owner,Map<UUID,Borrow> borrowed){this.parent=parent;this.owner=owner;this.borrowed=Map.copyOf(borrowed);}
        <T> T call(Supplier<T> operation){
            if(closed)return parent==null?operation.get():parent.call(operation);
            // Temporary logical group cells can follow native callbacks across actors. Only
            // the short native supplier is serialized; never wait for its returned future.
            if(parent!=null)return parent.call(()->locked(operation));
            return locked(operation);
        }
        private <T> T locked(Supplier<T> operation){synchronized(this){
            if(closed)return parent==null?operation.get():parent.call(operation);
            Map<UUID,Borrow> current=BORROWS.isBound()?new HashMap<>(BORROWS.get()):new HashMap<>();current.putAll(borrowed);
            return ScopedValue.where(NATIVE_LOAN,this).where(BORROWS,current).call(operation::get);
        }}
    }
    public static final class NativeBorrowScopes {
        private final NativeLoan loan;
        private NativeBorrowScopes(NativeLoan loan){this.loan=loan;}
        public <T> T call(Supplier<T> operation){return loan==null?operation.get():loan.call(operation);}
    }
    private static NativeLoan currentNativeLoan(){return NATIVE_LOAN.isBound()?NATIVE_LOAN.get():null;}
    public static NativeBorrowScopes captureNativeBorrowScopes(){return new NativeBorrowScopes(currentNativeLoan());}
    public static boolean nativeLoanFor(net.minecraft.world.entity.Entity owner){for(NativeLoan loan=currentNativeLoan();loan!=null;loan=loan.parent)if(!loan.closed&&loan.owner==owner)return true;return false;}
    public static <T> T withoutNativeBorrowScopes(Supplier<T> operation){
        NativeLoan loan=currentNativeLoan();if(loan==null)return operation.get();
        Map<UUID,Borrow> current=BORROWS.isBound()?new HashMap<>(BORROWS.get()):new HashMap<>();for(NativeLoan next=loan;next!=null;next=next.parent)next.borrowed.keySet().forEach(current::remove);
        NativeLoan empty=new NativeLoan(null,null,Map.of());return ScopedValue.where(NATIVE_LOAN,empty).where(BORROWS,current).call(operation::get);
    }

    private record State(Holder<Item> item, int count, int popTime, PatchedDataComponentMap components, long revision, long absoluteCount) {}
    private static final class Group {
        volatile State state;
        boolean busy;
        boolean nativeBorrow, journalHeld, pendingSave, receiptAuthority, custodyHistory;
        UUID transaction;
        OrgShadowReceiptStore receiptStore;
        com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> receiptOps;
        final java.util.concurrent.atomic.AtomicBoolean journalRetry = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.Set<UUID> completedTransactions = new java.util.HashSet<>();
        final java.util.Set<String> pins = new java.util.HashSet<>();
        final Map<ObserverKey, Observer> observers = new ConcurrentHashMap<>();
        Group(State state) { this.state = state; }
    }
    private record Borrow(UUID id, Group group, State initial, ItemStack temporary, boolean transaction) {}
    public record Change(UUID id, long revision, ItemStack before, ItemStack after) {}
    private record ObserverKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> world, long position) {}
    private static final class Observer {
        final java.lang.ref.WeakReference<net.minecraft.world.level.block.entity.BlockEntity> block;
        final java.lang.ref.WeakReference<net.minecraft.server.level.ServerLevel> world;
        final net.minecraft.core.BlockPos pos;
        final java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
        Observer(net.minecraft.world.level.block.entity.BlockEntity block, net.minecraft.server.level.ServerLevel world) {
            this.block = new java.lang.ref.WeakReference<>(block); this.world = new java.lang.ref.WeakReference<>(world); this.pos = block.getBlockPos().immutable();
        }
    }

    private OrgItemShadowGroups() {}

    public static UUID share(ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalArgumentException("An empty stack cannot establish a shadow group");
        if (stack.carpetOrgShadowId != null) return stack.carpetOrgShadowId;
        UUID id = UUID.randomUUID();
        State state = new State(stack.typeHolder(), stack.getCount(), stack.getPopTime(), stack.components.copy(), 0L, 0L);
        collectGroups();
        Group group = new Group(state);
        GROUPS.put(id, new GroupReference(id, group));
        stack.carpetOrgShadowAnchor = group;
        bind(stack, id, false);
        return id;
    }

    public static void bind(ItemStack stack, UUID id, boolean detached) {
        if (stack == ItemStack.EMPTY) throw new IllegalArgumentException("The shared empty singleton cannot join a shadow group");
        stack.carpetOrgShadowId = id; stack.carpetOrgShadowDetached = detached;
        stack.components.carpetOrgShadowId = detached ? null : id;
        stack.carpetOrgShadowAnchor = group(id);
        stack.components.carpetOrgShadowAnchor = detached ? null : stack.carpetOrgShadowAnchor;
    }

    public static boolean managed(ItemStack stack) {
        return stack.carpetOrgShadowId != null && !stack.carpetOrgShadowDetached;
    }
    public static boolean managed(PatchedDataComponentMap map) { return map.carpetOrgShadowId != null; }

    private static Group group(UUID id) {
        collectGroups();
        var reference = GROUPS.get(id);
        Group group = reference == null ? null : reference.get();
        if (group == null) throw new IllegalStateException("Missing item shadow group " + id);
        return group;
    }
    private static Borrow borrow(UUID id) { return BORROWS.isBound() ? BORROWS.get().get(id) : null; }

    public static boolean empty(ItemStack stack) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) return borrow.temporary.isEmpty();
        State state = group(stack.carpetOrgShadowId).state;
        return state.count <= 0 || state.item.value() == Items.AIR;
    }
    public static Holder<Item> item(ItemStack stack) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) return borrow.temporary.typeHolder();
        State state = group(stack.carpetOrgShadowId).state;
        return state.count <= 0 ? Items.AIR.builtInRegistryHolder() : state.item;
    }
    public static long revision(ItemStack stack) { return managed(stack) ? group(stack.carpetOrgShadowId).state.revision : stack.carpetOrgShadowRevision; }
    public static boolean sameAlias(ItemStack first, ItemStack second) { return first == second || first.carpetOrgShadowId != null && first.carpetOrgShadowId.equals(second.carpetOrgShadowId); }
    public static boolean borrowed(ItemStack stack) { return stack.carpetOrgShadowId != null && borrow(stack.carpetOrgShadowId) != null; }
    public static boolean any(List<ItemStack> stacks) { for (ItemStack stack : stacks) if (managed(stack)) return true; return false; }

    private static void requireWritable(Group group) {
        if (group.transaction != null || group.journalHeld) throw new IllegalStateException("An item shadow transaction or unknown durable receipt holds this group; retry the operation on its owner");
    }
    private static void requireWritable(Borrow borrow) {
        if (borrow.transaction) throw new IllegalStateException("The item shadow transaction exposes only a receipt snapshot; native actions must retry on their owner");
    }

    /** A restored descriptor is trusted server NBT, never client component data. */
    public static ItemStack restore(UUID id, long revision, ItemStack descriptor) {
        return restore(id, revision, descriptor, java.util.Set.of());
    }
    static ItemStack restore(UUID id, long revision, ItemStack descriptor, java.util.Set<UUID> completed) {
        return restore(id, revision, descriptor, completed, false);
    }
    private static ItemStack restore(UUID id, long revision, ItemStack descriptor, java.util.Set<UUID> completed, boolean journal) {
        collectGroups();
        // Restore can run on two player owners at once. Establish one canonical group and
        // its strong descriptor anchor together, without nesting any group monitor.
        Group group;
        synchronized (GROUPS) {
            var existing = GROUPS.get(id);
            group = existing == null ? null : existing.get();
            if (group == null) {
                group = new Group(new State(descriptor.carpetOrgOriginalHolder(), descriptor.carpetOrgOriginalCount(), descriptor.getPopTime(), descriptor.components.copy(), revision, 0L));
                GROUPS.put(id, new GroupReference(id, group));
            }
        }
        boolean changed = false;
        synchronized (group) {
            // A completed receipt is emitted only after both participant credits and the
            // complete ledger are verified. It survives final ledger deletion failure.
            if (group.transaction != null && completed.contains(group.transaction)) {
                group.transaction = null; group.busy = group.journalHeld; changed = true;
            }
            if (revision > group.state.revision) {
                if (group.busy && !(journal && group.journalHeld && !group.nativeBorrow)) throw new IllegalStateException("Newer item shadow state conflicts with unresolved native/escrow custody");
                group.state = state(descriptor, revision); changed = true;
            } else if (revision == group.state.revision && !completed.isEmpty() && !matches(group.state, descriptor)) {
                throw new IllegalStateException("Completed item shadow receipts disagree at the same revision");
            }
            group.completedTransactions.addAll(completed);
            if (!journal && !completed.isEmpty()) group.receiptAuthority = true;
        }
        descriptor.carpetOrgShadowAnchor = group;
        bind(descriptor, id, true); descriptor.carpetOrgShadowRevision = revision;
        if (changed) publish(id, group);
        return descriptor;
    }

    static java.util.Set<UUID> completedReceipts(ItemStack stack) {
        if (stack.carpetOrgShadowId == null) return java.util.Set.of();
        Borrow borrowed = borrow(stack.carpetOrgShadowId);
        if (borrowed != null && borrowed.transaction) return java.util.Set.of(); // Projected in-flight after state is not a finished canonical receipt.
        Group group = group(stack.carpetOrgShadowId);
        synchronized (group) { return java.util.Set.copyOf(group.completedTransactions); }
    }
    static boolean persistenceBound(ItemStack stack) {
        if (stack.carpetOrgShadowId == null) return false;
        Group group = group(stack.carpetOrgShadowId);
        synchronized (group) { return group.transaction != null || group.journalHeld || !group.completedTransactions.isEmpty() || !group.pins.isEmpty() || group.custodyHistory; }
    }

    static void pin(net.minecraft.server.MinecraftServer server,String relative,List<ItemStack> stacks) {
        java.nio.file.Path root=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        java.nio.file.Path file=root.resolve(relative).normalize();if(!file.startsWith(root)||file.equals(root))throw new IllegalArgumentException("Shadow custody path leaves its world");
        var identities=new java.util.TreeMap<UUID,ItemStack>();for(ItemStack stack:stacks)if(stack.carpetOrgShadowId!=null)identities.putIfAbsent(stack.carpetOrgShadowId,stack);
        var changes=identities.entrySet().stream().map(entry->{ItemStack snapshot=snapshot(entry.getValue());return new Change(entry.getKey(),snapshot.carpetOrgShadowRevision,snapshot,snapshot);}).toList();
        bindPersistence(server,changes);
        var held=DURABLE_PINS.computeIfAbsent(file,ignored->new ConcurrentHashMap<>());
        for(var entry:identities.entrySet()){Group group=group(entry.getKey());held.put(entry.getKey(),group);synchronized(group){group.pins.add(relative);group.custodyHistory=true;}persistLatest(entry.getValue());}
    }
    static void unpin(net.minecraft.server.MinecraftServer server,String relative) {
        java.nio.file.Path file=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().resolve(relative).normalize();
        Map<UUID,Group> held=DURABLE_PINS.get(file);if(held==null)return;
        for(var entry:new java.util.TreeMap<>(held).entrySet()){
            Group group=entry.getValue();synchronized(group){if(group.transaction!=null||group.journalHeld)throw new IllegalStateException("Persistent shadow custody remains unresolved");group.pins.remove(relative);}
            ItemStack stack=plain(group.state,group.state.count);bind(stack,entry.getKey(),false);persistLatest(stack);
            held.remove(entry.getKey(),group);boolean retire;synchronized(group){retire=!group.custodyHistory&&group.pins.isEmpty()&&group.completedTransactions.isEmpty()&&group.transaction==null&&!group.journalHeld;}
            if(retire&&group.receiptStore!=null)group.receiptStore.retire();
        }
        if(held.isEmpty())DURABLE_PINS.remove(file,held);
    }
    /** Historical private descriptors consume their latest durable count, then detach like native Java alias reload. */
    static ItemStack restoreCustody(net.minecraft.server.MinecraftServer server,ItemStack descriptor){
        var change=new Change(descriptor.carpetOrgShadowId,descriptor.carpetOrgShadowRevision,descriptor,descriptor);bindPersistence(server,List.of(change));Group group=group(descriptor.carpetOrgShadowId);
        boolean active,history;synchronized(group){active=!group.pins.isEmpty();history=group.custodyHistory;if(group.journalHeld)throw new IllegalStateException("Native shadow custody receipt is unreadable");}
        if(!history)return null;ItemStack latest=materialize(descriptor);return active?latest:latest.copy();
    }

    /** Reads detached canonical data without registering an alias or acquiring spendable custody. */
    static Attempt<ItemStack> readonly(net.minecraft.server.MinecraftServer server,net.minecraft.nbt.CompoundTag descriptor)throws java.io.IOException{
        UUID id=UUID.fromString(descriptor.getStringOr("id",""));
        long revision=descriptor.getLongOr("revision",-1L);if(revision<0)throw new java.io.IOException("Malformed offline shadow revision");
        var ops=server.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        var store=new OrgShadowReceiptStore(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),id);
        collectGroups();var reference=GROUPS.get(id);Group current=reference==null?null:reference.get();State state=null;
        if(current!=null)synchronized(current){
            if(current.busy||current.nativeBorrow||current.transaction!=null||current.journalHeld)return new Attempt<>(false,null);
            if(current.receiptStore!=null&&!current.receiptStore.file.equals(store.file))throw new java.io.IOException("Offline shadow belongs to another persistence world");
            state=current.state;
        }
        net.minecraft.nbt.CompoundTag disk=store.read(),latest=descriptor;
        if(disk!=null){
            long diskRevision=disk.getLongOr("revision",-1L);
            if(diskRevision==revision)for(String field:List.of("item","count","patch"))if(!Objects.equals(disk.get(field),descriptor.get(field)))throw new java.io.IOException("Conflicting offline shadow receipts at one revision");
            if(diskRevision>revision)latest=disk;
        }
        ItemStack result=OrgShadowInventoryCodec.plainDescriptor(latest,ops);
        if(current!=null)synchronized(current){
            // Actual IO ran outside the monitor. Never report a state sampled across a
            // native write or an unresolved transaction, and never adopt it into GROUPS.
            if(current.state!=state||current.busy||current.nativeBorrow||current.transaction!=null||current.journalHeld)return new Attempt<>(false,null);
            long selected=latest.getLongOr("revision",-1L);
            if(state.revision==selected&&!matches(state,result))throw new java.io.IOException("Live and durable offline shadow data disagree");
            if(state.revision>=selected)result=plain(state,state.count);
        }
        return new Attempt<>(true,result);
    }

    /** Bind before acquiring new input, and before exposing restored escrow identities. */
    static void bindPersistence(net.minecraft.server.MinecraftServer server, List<Change> changes) {
        var root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        var ops = server.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        for (Change change : changes) {
            Group group = group(change.id); OrgShadowReceiptStore store = new OrgShadowReceiptStore(root, change.id);
            synchronized (group) {
                if (group.receiptStore != null) {
                    if (!group.receiptStore.file.equals(store.file)) throw new IllegalStateException("An item shadow cannot cross server persistence roots");
                    continue;
                }
                group.receiptStore = store; group.receiptOps = ops;
            }
            try { adoptReceipt(change.id, group, store.read()); }
            catch (java.io.IOException | RuntimeException failure) {
                holdJournal(change.id, group, null);
                throw new IllegalStateException("Canonical shadow receipt is unreadable; preserving its custody", failure);
            }
        }
    }
    private static void adoptReceipt(UUID id, Group group, net.minecraft.nbt.CompoundTag saved) throws java.io.IOException {
        if (saved == null) return;
        var completed = new java.util.HashSet<UUID>();
        for (var value : saved.getListOrEmpty("completed")) {
            UUID receipt = UUID.fromString(value.asString().orElseThrow());
            if (group.receiptStore.pending(receipt)) completed.add(receipt);
        }
        ItemStack value = OrgShadowInventoryCodec.plainDescriptor(saved, group.receiptOps);
        restore(id, saved.getLongOr("revision", -1L), value, completed, true);
        for(var token:saved.getListOrEmpty("custody")){
            String relative=token.asString().orElseThrow();java.nio.file.Path root=group.receiptStore.file.getParent().getParent();java.nio.file.Path file=root.resolve(relative).normalize();
            if(!file.startsWith(root)||java.nio.file.Files.isSymbolicLink(file))throw new java.io.IOException("Shadow custody path leaves its world");
            if(java.nio.file.Files.isRegularFile(file)){
                if(java.nio.file.Files.size(file)>64L*1024*1024)throw new java.io.IOException("Shadow custody document is too large");
                boolean nativeFile=file.getFileName().toString().endsWith(".dat");
                var draft=nativeFile?net.minecraft.nbt.NbtIo.readCompressed(file,net.minecraft.nbt.NbtAccounter.create(64L*1024*1024)):net.minecraft.nbt.NbtIo.read(file);
                if(draft!=null&&(draft.getBooleanOr("_lophine_draft",false)||nativeFile&&draft.getBooleanOr("_lophine_offline_inventory_custody",false))){
                    synchronized(group){group.pins.add(relative);}DURABLE_PINS.computeIfAbsent(file,ignored->new ConcurrentHashMap<>()).put(id,group);
                }
            }
        }
        if (saved.getBooleanOr("native_completion", false)) synchronized (group) { group.receiptAuthority = true; }
        if(saved.getBooleanOr("custody_history",false))synchronized(group){group.custodyHistory=true;}
    }
    private static net.minecraft.nbt.CompoundTag canonical(UUID id, Group group, State state, java.util.Set<UUID> completed) {
        ItemStack value = plain(state, state.count); bind(value, id, true); value.carpetOrgShadowRevision = state.revision;
        var descriptor = OrgShadowInventoryCodec.descriptor(value, group.receiptOps, false);
        var receipts = new net.minecraft.nbt.ListTag(); completed.stream().sorted().forEach(receipt -> receipts.add(net.minecraft.nbt.StringTag.valueOf(receipt.toString())));
        if (!receipts.isEmpty()) descriptor.put("completed", receipts);
        descriptor.putBoolean("native_completion", group.receiptAuthority);
        descriptor.putBoolean("custody_history",group.custodyHistory);
        var custody=new net.minecraft.nbt.ListTag();synchronized(group){group.pins.stream().sorted().forEach(pin->custody.add(net.minecraft.nbt.StringTag.valueOf(pin)));}if(!custody.isEmpty())descriptor.put("custody",custody);
        return descriptor;
    }
    private static void writeReceipt(UUID id, Group group, State state, java.util.Set<UUID> completed) {
        writeReceipt(id, group, state, completed, true);
    }
    private static void writeReceipt(UUID id, Group group, State state, java.util.Set<UUID> completed, boolean authoritative) {
        if (group.receiptStore == null) return;
        var desired = canonical(id, group, state, completed);
        desired.putBoolean("native_completion", authoritative);
        try { group.receiptStore.write(desired); }
        catch (java.io.IOException | RuntimeException failure) {
            holdJournal(id, group, desired);
            throw new IllegalStateException("Shadow canonical write is unknown; its assets remain held", failure);
        }
    }
    static boolean hasDurableCompletion(UUID id, UUID transaction) {
        Group group = group(id);
        synchronized (group) { return group.receiptAuthority && group.completedTransactions.contains(transaction); }
    }
    /** A native save preserves post-completion canonical changes while an old ledger awaits deletion. */
    static void persistLatest(ItemStack stack) {
        if (stack.carpetOrgShadowId == null) return;
        UUID id = stack.carpetOrgShadowId; Group group = group(id); State state; java.util.Set<UUID> completed;
        Borrow borrow = borrow(id);
        synchronized (group) {
            if (group.receiptStore == null || group.transaction != null) return;
            if (borrow != null || group.nativeBorrow) { group.pendingSave = true; return; }
            if (group.journalHeld) throw new IllegalStateException("Canonical shadow IO remains held until readback is known");
            group.journalHeld = true; group.busy = true; state = group.state; completed = java.util.Set.copyOf(group.completedTransactions);
        }
        writeReceipt(id, group, state, completed);
        synchronized (group) { group.journalHeld = false; group.busy = group.transaction != null; }
        publish(id, group);
    }
    private static void holdJournal(UUID id, Group group, net.minecraft.nbt.CompoundTag desired) {
        synchronized (group) { group.journalHeld = true; group.busy = true; }
        if (!group.journalRetry.compareAndSet(false, true)) return;
        // The real task closure strongly pins Group and its immutable desired record. Neither
        // the weak UUID index nor a caller cancellation can drop unknown persistent custody.
        Runnable[] task = new Runnable[1]; var firstFailure = new java.util.concurrent.atomic.AtomicBoolean();
        task[0] = () -> {
            try {
                synchronized (group) {
                    if (!group.journalHeld) { group.journalRetry.set(false); task[0] = null; return; }
                    if (group.nativeBorrow) { scheduleJournal(task[0]); return; }
                }
                var saved = group.receiptStore.read(); adoptReceipt(id, group, saved);
                net.minecraft.nbt.CompoundTag next = desired;
                synchronized (group) {
                    if (next == null || group.state.revision > next.getLongOr("revision", -1L)) next = canonical(id, group, group.state, java.util.Set.copyOf(group.completedTransactions));
                }
                group.receiptStore.write(next); adoptReceipt(id, group, next);
                synchronized (group) { group.journalHeld = false; group.busy = group.transaction != null; }
                group.journalRetry.set(false); task[0] = null; publish(id, group);
            } catch (java.io.IOException | RuntimeException failure) {
                if (firstFailure.compareAndSet(false, true)) com.mojang.logging.LogUtils.getLogger().error("Canonical item shadow custody is waiting for readable storage: {}", id, failure);
                scheduleJournal(task[0]);
            }
        };
        java.util.concurrent.CompletableFuture.runAsync(task[0], journalExecutor);
    }
    private static void scheduleJournal(Runnable retry) {
        java.util.concurrent.CompletableFuture.runAsync(retry, java.util.concurrent.CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS, journalExecutor));
    }

    public static boolean transactionBound(ItemStack stack) {
        if (stack.carpetOrgShadowId == null) return false;
        Group group = group(stack.carpetOrgShadowId);
        synchronized (group) { return group.transaction != null; }
    }

    /** Acquire all group leases before taking any player input custody. No owner is waited on. */
    public static boolean prepare(UUID transaction, List<Change> changes, boolean recovering) {
        List<Group> acquired = new ArrayList<>();
        var ordered = new java.util.TreeMap<UUID, Change>();
        for (Change change : changes) if (ordered.put(change.id, change) != null) throw new IllegalArgumentException("Duplicate item shadow transition");
        for (Change change : ordered.values()) {
            Group group = group(change.id);
            boolean rejected;
            synchronized (group) {
                if (group.completedTransactions.contains(transaction)) continue;
                rejected = group.busy && !transaction.equals(group.transaction)
                    || !transaction.equals(group.transaction) && (recovering ? group.state.revision > change.revision : group.state.revision != change.revision || !matches(group.state, change.before));
                if (!rejected && !transaction.equals(group.transaction)) {
                    if (recovering) group.state = state(change.before, change.revision);
                    group.busy = true; group.transaction = transaction; acquired.add(group);
                }
            }
            if (rejected) {
                for (Group held : acquired) synchronized (held) { held.busy = false; held.transaction = null; }
                return false;
            }
        }
        return true;
    }

    public static <T> T transaction(UUID id, List<Change> changes, boolean after, Supplier<T> action) {
        if (changes.isEmpty()) return action.get();
        Map<UUID, Borrow> borrowed = BORROWS.isBound() ? new HashMap<>(BORROWS.get()) : new HashMap<>();
        for (Change change : changes) {
            Group group = group(change.id);
            synchronized (group) {
                if (group.completedTransactions.contains(id)) continue;
                if (!id.equals(group.transaction)) throw new IllegalStateException("Missing item shadow transaction lease");
                ItemStack temporary = (after ? change.after : change.before).copy(true);
                borrowed.put(change.id, new Borrow(change.id, group, group.state, temporary, true));
            }
        }
        return ScopedValue.where(BORROWS, borrowed).call(action::get);
    }

    public static void finish(UUID transaction, List<Change> changes, boolean commit) {
        for (Change change : changes) {
            Group group = group(change.id);
            State next; java.util.Set<UUID> completed;
            synchronized (group) {
                if (group.completedTransactions.contains(transaction)) continue;
                if (!transaction.equals(group.transaction)) throw new IllegalStateException("Item shadow transaction lease was lost");
                next = commit ? state(change.after, group.state.revision + (matches(group.state, change.after) ? 0L : 1L)) : group.state;
                completed = new java.util.HashSet<>(group.completedTransactions); completed.add(transaction);
            }
            writeReceipt(change.id, group, next, completed);
            synchronized (group) {
                // A verified journal reader may already have exposed this result and a newer
                // owner change. Its completed receipt makes this old finish idempotent.
                if (group.completedTransactions.contains(transaction)) continue;
                if (!transaction.equals(group.transaction)) throw new IllegalStateException("Item shadow finish changed custody while writing its receipt");
                group.state = next; group.journalHeld = false; group.receiptAuthority = true;
            }
        }
        for (Change change : changes) {
            Group group = group(change.id);
            synchronized (group) {
                if (group.completedTransactions.contains(transaction)) continue;
                if (!transaction.equals(group.transaction)) throw new IllegalStateException("Item shadow transaction lease was lost");
                group.completedTransactions.add(transaction); group.transaction = null; group.busy = group.journalHeld;
            }
            publish(change.id, group);
        }
    }
    /** A durable COMPLETE record confirms an old result; it cannot roll newer canonical data back. */
    static void recoverComplete(UUID transaction, List<Change> changes, boolean commit, boolean authoritative) {
        for (Change change : new java.util.TreeMap<UUID, Change>(changes.stream().collect(java.util.stream.Collectors.toMap(Change::id, Function.identity()))).values()) {
            Group group = group(change.id);
            State next; java.util.Set<UUID> completed;
            synchronized (group) {
                if (group.completedTransactions.contains(transaction)) continue;
                if (group.busy && !transaction.equals(group.transaction)) throw new IllegalStateException("Completed shadow recovery conflicts with active custody");
                ItemStack desired = commit ? change.after : change.before;
                long revision = change.revision + (commit && !matches(state(change.before, change.revision), desired) ? 1L : 0L);
                next = group.state.revision < revision ? state(desired, revision) : group.state;
                if (group.state.revision == revision && !matches(group.state, desired)) throw new IllegalStateException("Conflicting completed shadow result at the same revision");
                completed = new java.util.HashSet<>(group.completedTransactions); completed.add(transaction);
                group.transaction = transaction; group.busy = true;
            }
            writeReceipt(change.id, group, next, completed, authoritative);
            synchronized (group) {
                if (group.completedTransactions.contains(transaction)) continue;
                group.state = next; group.journalHeld = false; group.receiptAuthority |= authoritative;
                group.completedTransactions.add(transaction);
                if (transaction.equals(group.transaction)) { group.transaction = null; group.busy = false; }
            }
            publish(change.id, group);
        }
    }
    public static void forget(UUID transaction, List<Change> changes) {
        for (Change change : changes) {
            Group group = group(change.id); boolean retire;
            synchronized (group) { group.completedTransactions.remove(transaction); retire = !group.custodyHistory && group.completedTransactions.isEmpty() && group.transaction == null && !group.journalHeld && group.pins.isEmpty(); }
            if (retire && group.receiptStore != null) group.receiptStore.retire();
        }
    }

    private static boolean matches(State state, ItemStack stack) {
        return state.count == stack.carpetOrgOriginalCount() && state.item.equals(stack.carpetOrgOriginalHolder()) && state.components.equals(stack.components);
    }
    private static State state(ItemStack stack, long revision) {
        return new State(stack.carpetOrgOriginalHolder(), stack.carpetOrgOriginalCount(), stack.getPopTime(), stack.components.copy(), revision, 0L);
    }
    public static int count(ItemStack stack) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) return borrow.temporary.getCount();
        State state = group(stack.carpetOrgShadowId).state;
        return state.item.value() == Items.AIR ? 0 : Math.max(0, state.count);
    }
    public static int rawCount(ItemStack stack) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        return borrow == null ? group(stack.carpetOrgShadowId).state.count : borrow.temporary.carpetOrgOriginalCount();
    }
    public static int popTime(ItemStack stack) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        return borrow == null ? group(stack.carpetOrgShadowId).state.popTime : borrow.temporary.getPopTime();
    }
    public static void popTime(ItemStack stack, int value) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) { requireWritable(borrow); borrow.temporary.setPopTime(value); return; }
        Group group = group(stack.carpetOrgShadowId);
        synchronized (group) { requireWritable(group); State old = group.state; group.state = new State(old.item, old.count, value, old.components, old.revision, old.absoluteCount); }
    }

    public static void count(ItemStack stack, int value) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) { requireWritable(borrow); borrow.temporary.setCount(value); return; }
        Group group = group(stack.carpetOrgShadowId);
        synchronized (group) { requireWritable(group); State old = group.state; if (old.count == value) return; group.state = new State(old.item, value, old.popTime, old.components, old.revision + 1L, old.absoluteCount + 1L); }
        publishChanged(stack.carpetOrgShadowId, group);
    }
    public static void grow(ItemStack stack, int amount) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) { requireWritable(borrow); borrow.temporary.grow(amount); return; }
        Group group = group(stack.carpetOrgShadowId);
        synchronized (group) {
            requireWritable(group);
            State old = group.state;
            int count = (old.item.value() == Items.AIR ? 0 : Math.max(0, old.count)) + amount;
            if (old.count == count) return;
            group.state = new State(old.item, count, old.popTime, old.components, old.revision + 1L, old.absoluteCount);
        }
        publishChanged(stack.carpetOrgShadowId, group);
    }

    public static ItemStack split(ItemStack stack, int amount) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) { if (borrow.transaction) return ItemStack.EMPTY; return borrow.temporary.split(amount); }
        Group group = group(stack.carpetOrgShadowId);
        ItemStack result;
        synchronized (group) {
            if (group.busy) return ItemStack.EMPTY;
            State old = group.state;
            int count = old.item.value() == Items.AIR ? 0 : Math.max(0, old.count);
            int taken = Math.min(amount, count);
            if (taken <= 0) return ItemStack.EMPTY;
            result = plain(old, taken);
            group.state = new State(old.item, count - taken, old.popTime, old.components, old.revision + 1L, old.absoluteCount);
        }
        publishChanged(stack.carpetOrgShadowId, group);
        return result; // Native split creates a new object; it does not create another alias.
    }

    public static ItemStack clear(ItemStack stack) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) { if (borrow.transaction) return ItemStack.EMPTY; return borrow.temporary.copyAndClear(); }
        Group group = group(stack.carpetOrgShadowId);
        ItemStack result;
        synchronized (group) {
            State old = group.state;
            if (group.busy || old.count <= 0 || old.item.value() == Items.AIR) return ItemStack.EMPTY;
            result = plain(old, old.count);
            group.state = new State(old.item, 0, old.popTime, old.components, old.revision + 1L, old.absoluteCount + 1L);
        }
        publishChanged(stack.carpetOrgShadowId, group); return result;
    }

    public static ItemStack consumeAndReturn(ItemStack stack, int amount) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) { if (borrow.transaction) return ItemStack.EMPTY; return borrow.temporary.consumeAndReturn(amount, null); }
        Group group = group(stack.carpetOrgShadowId);
        ItemStack result;
        synchronized (group) {
            State old = group.state;
            if (group.busy || old.count <= 0 || old.item.value() == Items.AIR) return ItemStack.EMPTY;
            result = plain(old, amount);
            group.state = new State(old.item, old.count - amount, old.popTime, old.components, old.revision + 1L, old.absoluteCount);
        }
        publishChanged(stack.carpetOrgShadowId, group); return result;
    }

    public static PatchedDataComponentMap components(PatchedDataComponentMap map) {
        Borrow borrow = borrow(map.carpetOrgShadowId);
        return borrow == null ? group(map.carpetOrgShadowId).state.components : borrow.temporary.components;
    }
    public static <T> T mutate(PatchedDataComponentMap map, Function<PatchedDataComponentMap, T> operation) {
        Borrow borrow = borrow(map.carpetOrgShadowId);
        if (borrow != null) { requireWritable(borrow); return operation.apply(borrow.temporary.components); }
        Group group = group(map.carpetOrgShadowId);
        T result;
        boolean changed;
        synchronized (group) {
            State old = group.state;
            PatchedDataComponentMap copy = old.components.copy();
            requireWritable(group);
            result = operation.apply(copy);
            changed = !copy.equals(old.components);
            if (changed) group.state = new State(old.item, old.count, old.popTime, copy, old.revision + 1L, old.absoluteCount);
        }
        if (changed) publishChanged(map.carpetOrgShadowId, group); return result;
    }
    public static void item(ItemStack stack, Item item) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) { requireWritable(borrow); borrow.temporary.setItem(item); return; }
        Group group = group(stack.carpetOrgShadowId);
        synchronized (group) {
            requireWritable(group);
            State old = group.state;
            Holder<Item> holder = item.builtInRegistryHolder();
            PatchedDataComponentMap components = PatchedDataComponentMap.fromPatch(holder.components(), old.components.asPatch());
            if (holder.equals(old.item) && components.equals(old.components)) return;
            group.state = new State(holder, old.count, old.popTime, components, old.revision + 1L, old.absoluteCount);
        }
        publishChanged(stack.carpetOrgShadowId, group);
    }

    private static ItemStack plain(State state, int count) {
        ItemStack result = new ItemStack(state.item, count, state.components.asPatch());
        result.setPopTime(state.popTime); return result;
    }

    public static ItemStack copy(ItemStack stack, boolean originalItem) {
        Borrow borrow = borrow(stack.carpetOrgShadowId);
        if (borrow != null) return borrow.temporary.copy(originalItem);
        State state = group(stack.carpetOrgShadowId).state;
        return !originalItem && (state.count <= 0 || state.item.value() == Items.AIR) ? ItemStack.EMPTY : plain(state, state.count);
    }

    public static boolean holds(net.minecraft.world.entity.Entity owner, ItemStack stack) {
        if (owner instanceof net.minecraft.world.entity.player.Player player) {
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack current = player.getInventory().getItem(slot);
                if (current == stack || stack.carpetOrgShadowId != null && stack.carpetOrgShadowId.equals(current.carpetOrgShadowId)) return true;
            }
        } else if (owner instanceof net.minecraft.world.entity.LivingEntity living) {
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.VALUES) {
                ItemStack current = living.getItemBySlot(slot);
                if (current == stack || stack.carpetOrgShadowId != null && stack.carpetOrgShadowId.equals(current.carpetOrgShadowId)) return true;
            }
        }
        return false;
    }

    /** Snapshot metadata preserves identity explicitly. Ordinary ItemStack.copy remains detached. */
    public static ItemStack snapshot(ItemStack stack) {
        if (stack == ItemStack.EMPTY) return ItemStack.EMPTY;
        if (managed(stack)) {
            Borrow borrow = borrow(stack.carpetOrgShadowId);
            State state = group(stack.carpetOrgShadowId).state;
            ItemStack result = borrow == null ? plain(state, state.count) : borrow.temporary.copy(true);
            bind(result, stack.carpetOrgShadowId, true); result.carpetOrgShadowRevision = state.revision;
            return result;
        }
        ItemStack result = stack.carpetOrgShadowId == null ? stack.copy() : stack.copy(true);
        if (stack.carpetOrgShadowId != null) { bind(result, stack.carpetOrgShadowId, true); result.carpetOrgShadowRevision = stack.carpetOrgShadowRevision; }
        return result;
    }

    public static ItemStack materialize(ItemStack snapshot) {
        if (snapshot.carpetOrgShadowId == null) return snapshot.copy();
        ItemStack result = snapshot.copy(true); bind(result, snapshot.carpetOrgShadowId, false); return result;
    }

    /** One owner previews both sides against explicit group identities, including aliases split down to zero. */
    public static final class Preview {
        private final Map<UUID, ItemStack> before = new HashMap<>(), temporary = new HashMap<>();
        private final java.util.IdentityHashMap<ItemStack, ItemStack> identities = new java.util.IdentityHashMap<>();
        public List<ItemStack> copies(List<ItemStack> stacks) { return stacks.stream().map(this::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new)); }
        private ItemStack copy(ItemStack source) {
            if (source.carpetOrgShadowId == null) return identities.computeIfAbsent(source, OrgItemShadowGroups::snapshot);
            ItemStack stable = snapshot(source), previous = before.putIfAbsent(stable.carpetOrgShadowId, stable);
            if (previous != null && (previous.carpetOrgShadowRevision != stable.carpetOrgShadowRevision
                || previous.carpetOrgOriginalCount() != stable.carpetOrgOriginalCount() || !previous.carpetOrgOriginalHolder().equals(stable.carpetOrgOriginalHolder())
                || !previous.components.equals(stable.components))) throw new IllegalStateException("Item shadow view changed; refresh the remote inventory before clicking");
            return temporary.computeIfAbsent(stable.carpetOrgShadowId, ignored -> snapshot(stable));
        }
        public List<Change> changes() {
            List<Change> result = new ArrayList<>();
            before.forEach((id, original) -> result.add(new Change(id, original.carpetOrgShadowRevision, snapshot(original), snapshot(temporary.get(id)))));
            return result;
        }
    }

    public static List<ItemStack> snapshots(List<ItemStack> originals) {
        Map<UUID, ItemStack> groups = new HashMap<>();
        java.util.IdentityHashMap<ItemStack, ItemStack> identities = new java.util.IdentityHashMap<>();
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : originals) {
            ItemStack copy = stack.carpetOrgShadowId != null
                ? groups.computeIfAbsent(stack.carpetOrgShadowId, ignored -> snapshot(stack))
                : identities.computeIfAbsent(stack, OrgItemShadowGroups::snapshot);
            result.add(copy);
        }
        return result;
    }

    /** A busy group keeps its visible value; item entities and containers must not mistake held items for empty slots. */
    public record Attempt<T>(boolean completed, T value) {}
    public static <T> T action(ItemStack stack, Supplier<T> action) { return action(List.of(stack), action); }
    public static <T> T action(List<ItemStack> stacks, Supplier<T> action) {
        Attempt<T> result = attempt(stacks, action);
        if (!result.completed) throw new IllegalStateException("Shared item action is busy; it must be retried on its owner");
        return result.value;
    }

    public static <T> Attempt<T> attempt(List<ItemStack> stacks, Supplier<T> action) {
        Map<UUID, Borrow> borrowed = BORROWS.isBound() ? new HashMap<>(BORROWS.get()) : new HashMap<>();
        List<Borrow> acquired = new ArrayList<>();
        java.util.SortedMap<UUID, ItemStack> ordered = new java.util.TreeMap<>();
        for (ItemStack stack : stacks) if (managed(stack)) ordered.putIfAbsent(stack.carpetOrgShadowId, stack);
        for (ItemStack stack : ordered.values()) {
            Borrow existing = borrowed.get(stack.carpetOrgShadowId);
            if (existing != null && existing.transaction) { release(acquired); return new Attempt<>(false, null); }
            if (!managed(stack) || borrowed.containsKey(stack.carpetOrgShadowId)) continue;
            Group group = group(stack.carpetOrgShadowId);
            boolean unavailable;
            synchronized (group) {
                State old = group.state;
                unavailable = group.busy;
                if (!unavailable && old.count > 0 && old.item.value() != Items.AIR) {
                    Borrow borrow = new Borrow(stack.carpetOrgShadowId, group, old, plain(old, old.count), false);
                    group.busy = true;
                    group.nativeBorrow = true;
                    borrowed.put(borrow.id, borrow); acquired.add(borrow);
                }
            }
            if (unavailable) { release(acquired); return new Attempt<>(false, null); }
        }
        if (acquired.isEmpty()) return new Attempt<>(true, action.get());
        try { return new Attempt<>(true, ScopedValue.where(BORROWS, borrowed).call(action::get)); }
        finally { release(acquired); }
    }

    /** A dedicated native loan ends after the true body, every dynamic child and canonical release. */
    public static <T> Attempt<java.util.concurrent.CompletableFuture<T>> attemptNative(net.minecraft.world.entity.Entity owner,List<ItemStack> stacks,Supplier<java.util.concurrent.CompletableFuture<T>> action){
        NativeLoan parent=currentNativeLoan();Map<UUID,Borrow> borrowed=parent==null?new HashMap<>():new HashMap<>(parent.borrowed);
        List<Borrow> acquired=new ArrayList<>();var ordered=new java.util.TreeMap<UUID,ItemStack>();for(ItemStack stack:stacks)if(managed(stack))ordered.putIfAbsent(stack.carpetOrgShadowId,stack);
        for(ItemStack stack:ordered.values()){
            Borrow existing=borrowed.get(stack.carpetOrgShadowId);if(existing!=null){if(existing.transaction){release(acquired);return new Attempt<>(false,null);}continue;}
            Group group=group(stack.carpetOrgShadowId);boolean unavailable;
            synchronized(group){State old=group.state;unavailable=group.busy;if(!unavailable&&old.count>0&&old.item.value()!=Items.AIR){Borrow borrow=new Borrow(stack.carpetOrgShadowId,group,old,plain(old,old.count),false);group.busy=true;group.nativeBorrow=true;borrowed.put(borrow.id,borrow);acquired.add(borrow);}}
            if(unavailable){release(acquired);return new Attempt<>(false,null);}
        }
        if(acquired.isEmpty())return new Attempt<>(true,action.get());
        NativeLoan loan=new NativeLoan(parent,owner,borrowed);
        java.util.concurrent.CompletableFuture<T> body;
        try{body=loan.call(action);}catch(Throwable failure){withoutNativeBorrowScopes(()->{release(acquired);return null;});loan.closed=true;throw failure;}
        var actual=new java.util.concurrent.CompletableFuture<T>(){@Override public boolean cancel(boolean interrupt){return false;}};
        carpet.script.external.ScarpetNativeWork.record(actual);carpet.script.external.ScarpetNativeWork.aliasDependency(actual,body);
        var completion=carpet.script.external.ScarpetRuntime.captureNativeConsumer((T value,Throwable failure)->{
            Throwable problem=failure;
            try{withoutNativeBorrowScopes(()->{release(acquired);return null;});}catch(Throwable releaseFailure){if(problem==null)problem=releaseFailure;else problem.addSuppressed(releaseFailure);}
            finally{loan.closed=true;}
            if(problem==null)actual.complete(value);else actual.completeExceptionally(problem);
        });body.whenComplete(completion);
        return new Attempt<>(true,actual);
    }

    private static void release(List<Borrow> acquired) {
        for (Borrow borrow : acquired) {
                boolean changed;
                synchronized (borrow.group) {
                    State current = borrow.group.state, initial = borrow.initial;
                    ItemStack result = borrow.temporary;
                    Holder<Item> item = result.carpetOrgOriginalHolder();
                    // Preserve another actor's later legitimate component writes; apply this actor's component delta.
                    Holder<Item> holder = initial.item.equals(item) ? current.item : item;
                    PatchedDataComponentMap merged = PatchedDataComponentMap.fromPatch(holder.components(), current.components.asPatch());
                    java.util.Set<DataComponentType<?>> types = new java.util.HashSet<>(initial.components.keySet()); types.addAll(result.components.keySet());
                    for (DataComponentType<?> type : types) {
                        Object before = initial.components.get(type), after = result.components.get(type);
                        if (!Objects.equals(before, after)) setUnchecked(merged, type, after);
                    }
                    int count = current.count + result.carpetOrgOriginalCount() - initial.count;
                    int popTime = initial.popTime == result.getPopTime() ? current.popTime : result.getPopTime();
                    changed = !holder.equals(current.item) || count != current.count || !merged.equals(current.components);
                    borrow.group.state = new State(holder, count, popTime, merged, current.revision + (changed ? 1L : 0L), current.absoluteCount);
                    borrow.group.nativeBorrow = false;
                    borrow.group.busy = borrow.group.journalHeld;
                }
                boolean persist;
                synchronized (borrow.group) { persist = borrow.group.pendingSave || changed && !borrow.group.pins.isEmpty(); borrow.group.pendingSave = false; }
                if (persist && borrow.group.receiptStore != null) {
                    try {
                        ItemStack value = plain(borrow.group.state, borrow.group.state.count); bind(value, borrow.id, false); persistLatest(value);
                    } catch (RuntimeException failure) { com.mojang.logging.LogUtils.getLogger().error("Accepted shadow native work ended with held canonical storage", failure); }
                }
                if (changed) publish(borrow.id, borrow.group);
        }
    }

    /** Subscribe only from the container's owner; notifications schedule the receiving owner. */
    public static void observe(net.minecraft.world.level.block.entity.BlockEntity block) {
        if (!(block instanceof net.minecraft.world.Container container) || !(block.getLevel() instanceof net.minecraft.server.level.ServerLevel level)
            || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, block.getBlockPos())) return;
        ObserverKey key = new ObserverKey(level.dimension(), block.getBlockPos().asLong());
        for (ItemStack stack : container(container)) if (managed(stack)) group(stack.carpetOrgShadowId).observers.computeIfAbsent(key, ignored -> new Observer(block, level));
    }

    private static void publish(UUID id, Group group) {
        group.observers.forEach((key, observer) -> {
            var level = observer.world.get(); var block = observer.block.get();
            if (level == null || block == null) { group.observers.remove(key, observer); return; }
            if (!observer.queued.compareAndSet(false, true)) return;
            org.bukkit.Bukkit.getRegionScheduler().runDelayed(org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE, level.getWorld(), observer.pos.getX() >> 4, observer.pos.getZ() >> 4, task -> {
                observer.queued.set(false);
                var current = observer.block.get();
                if (current == null || level.getBlockEntity(observer.pos) != current || !(current instanceof net.minecraft.world.Container container)) { group.observers.remove(key, observer); return; }
                boolean contains = false;
                for (ItemStack stack : container(container)) if (id.equals(stack.carpetOrgShadowId)) { contains = true; break; }
                if (contains) current.setChanged(); else group.observers.remove(key, observer);
            }, 1L);
        });
    }

    private static void publishChanged(UUID id,Group group){
        boolean pinned;synchronized(group){pinned=!group.pins.isEmpty();}
        if(pinned){ItemStack stack=plain(group.state,group.state.count);bind(stack,id,false);persistLatest(stack);}
        publish(id,group);
    }
    public static <T> T actor(net.minecraft.world.entity.Entity owner, List<ItemStack> stacks, Supplier<T> action, T deferred,
                              java.util.function.Predicate<net.minecraft.world.entity.Entity> valid, java.util.function.Consumer<T> accept) {
        return actor(owner, () -> stacks, action, deferred, valid, accept);
    }
    public static <T> T actor(net.minecraft.world.entity.Entity owner, Supplier<List<ItemStack>> stacks, Supplier<T> action, T deferred,
                              java.util.function.Predicate<net.minecraft.world.entity.Entity> valid, java.util.function.Consumer<T> accept) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(owner, "Shared item work must start on its owner");
        var job=new ActorAdmission(owner);
        var immediate = new java.util.concurrent.atomic.AtomicReference<T>(deferred);
        var synchronousFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var actual = carpet.script.external.ScarpetNativeWork.observeNative(owner, () -> {
            try {
                if(owner instanceof net.minecraft.server.level.ServerPlayer player&&carpet.script.external.ScarpetPlayerInventoryGate.paused(player)){
                    defer(owner,stacks,action,valid,accept,job);return null;
                }
                Attempt<T> result = attempt(stacks.get(), job.operation(action));
                if (result.completed) immediate.set(result.value);
                else defer(owner, stacks, action, valid, accept,job);
            } catch (RuntimeException | Error failure) { synchronousFailure.set(failure); throw failure; }
            return null;
        });
        carpet.script.external.ScarpetNativeWork.aliasDependency(job.finished,actual);
        actual.whenComplete((ignored,failure)->{if(failure==null)job.finished.complete(null);else job.finished.completeExceptionally(failure);});
        reportActorFailure(actual);
        if (synchronousFailure.get() instanceof RuntimeException failure) throw failure;
        if (synchronousFailure.get() instanceof Error failure) throw failure;
        return immediate.get();
    }
    private static final class ActorAdmission {
        final net.minecraft.world.entity.Entity owner;
        final java.util.concurrent.CompletableFuture<Void> finished=new java.util.concurrent.CompletableFuture<>();
        boolean admitted;
        ActorAdmission(net.minecraft.world.entity.Entity owner){this.owner=owner;}
        <T> Supplier<T> operation(Supplier<T> action){return ()->{
            // Busy before the action owns a group is a queued intent. Tracking it as accepted
            // would make an escrow snapshot await the release that the snapshot must perform.
            if(!admitted){admitted=true;if(owner instanceof net.minecraft.server.level.ServerPlayer player)carpet.script.external.ScarpetPlayerInventoryGate.trackAccepted(player,finished);}
            try(var accepted=owner instanceof net.minecraft.server.level.ServerPlayer player?carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player):null){return action.get();}
        };}
    }
    private static <T> void defer(net.minecraft.world.entity.Entity owner, Supplier<List<ItemStack>> stacks, Supplier<T> action,
                                  java.util.function.Predicate<net.minecraft.world.entity.Entity> valid, java.util.function.Consumer<T> accept,ActorAdmission job) {
        var done = new java.util.concurrent.CompletableFuture<Void>();
        carpet.script.external.ScarpetNativeWork.record(done);
        java.util.function.Supplier<Void>[] continuation=new java.util.function.Supplier[1];
        java.util.function.Supplier<Void> inherited = carpet.script.external.ScarpetRuntime.captureOwnerOperation(() -> {
            try {
                if (owner.isRemoved()) throw new IllegalStateException("Shared item owner retired");
                if (!valid.test(owner)) { done.complete(null); return null; }
                if(!job.admitted&&owner instanceof net.minecraft.server.level.ServerPlayer player&&carpet.script.external.ScarpetPlayerInventoryGate.paused(player)){
                    carpet.script.external.ScarpetPlayerInventoryGate.whenOpen(player).whenComplete((ignored,failure)->{if(failure!=null)done.completeExceptionally(failure);else scheduleActor(owner,continuation[0],done);});return null;
                }
                Attempt<T> result = attempt(stacks.get(), job.operation(action));
                if (result.completed) {try(var accepted=owner instanceof net.minecraft.server.level.ServerPlayer player?carpet.script.external.ScarpetPlayerInventoryGate.acceptedScope(player):null){accept.accept(result.value);}}
                else defer(owner, stacks, action, valid, accept,job);
                done.complete(null);
            } catch (Throwable failure) { done.completeExceptionally(failure); }
            return null;
        });
        continuation[0]=inherited;scheduleActor(owner,inherited,done);
    }
    private static void scheduleActor(net.minecraft.world.entity.Entity owner,java.util.function.Supplier<Void> inherited,java.util.concurrent.CompletableFuture<Void> done){
        try {
            boolean scheduled = owner.getBukkitEntity().taskScheduler.schedule(owned -> {
                if (owned != owner) { done.completeExceptionally(new IllegalStateException("Shared item owner changed")); return; }
                try { inherited.get(); } catch (Throwable failure) { done.completeExceptionally(failure); }
            }, retired -> done.completeExceptionally(new IllegalStateException("Shared item owner retired")), 1L);
            if (!scheduled) done.completeExceptionally(new IllegalStateException("Shared item scheduler retired"));
        } catch (Throwable failure) { done.completeExceptionally(failure); }
    }
    private static void reportActorFailure(java.util.concurrent.CompletableFuture<?> actual) {
        actual.whenComplete((ignored, failure) -> {
            if (failure != null) com.mojang.logging.LogUtils.getLogger().error("Shared item native operation failed", failure);
        });
    }

    public static List<ItemStack> inventory(net.minecraft.world.entity.player.Player player, Iterable<net.minecraft.world.inventory.Slot> slots) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) stacks.add(player.getInventory().getItem(slot));
        for (var slot : slots) stacks.add(slot.getItem());
        stacks.add(player.containerMenu.getCarried());
        return stacks;
    }
    public static List<ItemStack> container(net.minecraft.world.Container... containers) {
        List<ItemStack> stacks = new ArrayList<>();
        for (var container : containers) if (container != null) for (int slot = 0; slot < container.getContainerSize(); slot++) stacks.add(container.getItem(slot));
        return stacks;
    }
    public static List<ItemStack> equipment(net.minecraft.world.entity.LivingEntity owner) {
        List<ItemStack> stacks = new ArrayList<>();
        for (var slot : net.minecraft.world.entity.EquipmentSlot.VALUES) stacks.add(owner.getItemBySlot(slot));
        return stacks;
    }
    public static boolean owned(net.minecraft.world.Container container) {
        if (container == null) return true;
        if (container instanceof net.minecraft.world.entity.Entity entity) return ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity);
        if (container instanceof net.minecraft.world.level.block.entity.BlockEntity block) return block.getLevel() instanceof net.minecraft.server.level.ServerLevel level
            && ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, block.getBlockPos());
        return false;
    }

    public static void block(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, Supplier<List<ItemStack>> stacks,
                             Runnable action, java.util.function.BooleanSupplier valid) {
        if (!valid.getAsBoolean()) return;
        if (attempt(stacks.get(), () -> { action.run(); return true; }).completed) return;
        org.bukkit.Bukkit.getRegionScheduler().runDelayed(org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE, level.getWorld(), pos.getX() >> 4, pos.getZ() >> 4,
            task -> block(level, pos, stacks, action, valid), 1L);
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) private static void setUnchecked(PatchedDataComponentMap map, DataComponentType type, Object value) { map.set(type, value); }
}
