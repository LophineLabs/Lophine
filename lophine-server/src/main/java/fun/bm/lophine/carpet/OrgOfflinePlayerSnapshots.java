// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import com.mojang.serialization.DynamicOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/** Immutable offline data, never a fake player or a new spendable shadow alias. */
public final class OrgOfflinePlayerSnapshots {
    private OrgOfflinePlayerSnapshots() {}

    public static final class Snapshot {
        private final UUID player;
        private final List<ItemStack> inventory, enderItems, recoveredCursor;
        private final int experienceLevel, totalExperience;
        private final float experienceProgress;
        private final CompoundTag fileData, nativeData;
        private Snapshot(UUID player,List<ItemStack> inventory,List<ItemStack> enderItems,List<ItemStack> recoveredCursor,
                         CompoundTag fileData,CompoundTag nativeData){
            this.player=player;this.inventory=detached(inventory);this.enderItems=detached(enderItems);this.recoveredCursor=detached(recoveredCursor);
            this.experienceLevel=nativeData.getIntOr("XpLevel",0);this.totalExperience=nativeData.getIntOr("XpTotal",0);this.experienceProgress=nativeData.getFloatOr("XpP",0);
            if(experienceLevel<0||totalExperience<0||!Float.isFinite(experienceProgress)||experienceProgress<0||experienceProgress>1)throw new IllegalArgumentException("Invalid offline experience state");
            this.fileData=fileData.copy();this.nativeData=nativeData.copy();
        }
        public UUID player(){return player;}
        /** Native Inventory indices 0..35 and EQUIPMENT_SLOT_MAPPING indices 36..42. */
        public List<ItemStack> inventory(){return detached(inventory);}
        public List<ItemStack> enderItems(){return detached(enderItems);}
        public List<ItemStack> recoveredCursor(){return detached(recoveredCursor);}
        public int experienceLevel(){return experienceLevel;}
        public int totalExperience(){return totalExperience;}
        public float experienceProgress(){return experienceProgress;}
        /** Original bytes' logical NBT before DFU, for exact durable file compare. */
        public CompoundTag fileData(){return fileData.copy();}
        /** Converted native data with every unrelated player field retained. */
        public CompoundTag nativeData(){return nativeData.copy();}
    }

    /** UUID custody is drained before acquiring the file admission lease, so login needed
     * to resolve an older escrow is never blocked behind this read-only request. */
    public static CompletableFuture<Snapshot> read(MinecraftServer server,UUID player){
        Objects.requireNonNull(server);Objects.requireNonNull(player);
        return CompletableFuture.allOf(OrgInventoryTransfers.whenAvailable(server,player),OrgExperienceTransfers.whenAvailable(server,player))
            .thenCompose(ignored->OrgPlayerFileLease.withLease(server,player,"offline inventory/experience snapshot",lease->
                CompletableFuture.supplyAsync(()->{
                    rejectOnline(server,player);
                    try{return loadFile(server,player);}catch(IOException failure){throw new CompletionException(failure);}
                }).thenCompose(raw->parse(server,player,raw))
                    .thenApply(snapshot->{rejectOnline(server,player);return snapshot;})));
    }

    /** Caller owns the UUID file lease and supplied an immutable native file snapshot.
     * This method does no file writing, player registration, alias restore, or DFU-on-disk. */
    public static CompletableFuture<Snapshot> parse(MinecraftServer server,UUID player,CompoundTag raw){
        Objects.requireNonNull(raw);CompoundTag before=raw.copy();
        return CompletableFuture.supplyAsync(()->{
            rejectOnline(server,player);
            CompoundTag data=ca.spottedleaf.dataconverter.minecraft.MCDataConverter.convertTag(
                ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry.PLAYER,DataFixTypes.PLAYER,before.copy(),
                NbtUtils.getDataVersion(before),ca.spottedleaf.dataconverter.minecraft.util.Version.getCurrentVersion());
            var ops=server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            if(data.contains("UUID")&&!UUIDUtil.CODEC.parse(ops,data.get("UUID")).getOrThrow().equals(player))throw new IllegalArgumentException("Offline file UUID does not match its admission identity");
            return data;
        }).thenCompose(data->attemptParse(server,player,before,data));
    }

    private static CompletableFuture<Snapshot> attemptParse(MinecraftServer server,UUID player,CompoundTag raw,CompoundTag data){
        return CompletableFuture.supplyAsync(()->{
            rejectOnline(server,player);
            try{return parseOnce(server,player,raw,data);}
            catch(IOException failure){throw new CompletionException(failure);}
        }).thenCompose(attempt->{
            if(attempt.completed())return CompletableFuture.completedFuture(attempt.value());
            // Only actual live group custody is busy. No region worker waits, no unknown
            // IO is treated as zero items, and the saved descriptor never becomes an alias.
            return CompletableFuture.runAsync(()->{},CompletableFuture.delayedExecutor(50,TimeUnit.MILLISECONDS))
                .thenCompose(ignored->attemptParse(server,player,raw,data));
        });
    }

    private static OrgItemShadowGroups.Attempt<Snapshot> parseOnce(MinecraftServer server,UUID player,CompoundTag raw,CompoundTag data)throws IOException{
        var ops=server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        List<ItemStack> inventory=empty(Inventory.INVENTORY_SIZE+Inventory.EQUIPMENT_SLOT_MAPPING.size());
        readSlots(data,"Inventory",inventory,Inventory.INVENTORY_SIZE,ops);
        if(data.contains("equipment")){
            EntityEquipment equipment=EntityEquipment.CODEC.parse(ops,data.get("equipment")).getOrThrow();
            for(var entry:Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet())inventory.set(entry.getIntKey(),equipment.get(entry.getValue()).copy());
        }
        int enderSize=9*fun.bm.lophine.carpet.config.modules.GeneralCompatConfig.effectiveEnderChestRows();
        for(Tag value:data.getListOrEmpty("EnderItems"))if(value instanceof CompoundTag slot){int index=slot.getByteOr("Slot",(byte)0)&255;if(index>=54)throw new IllegalArgumentException("Offline ender slot exceeds the native maximum");enderSize=Math.max(enderSize,9*(index/9+1));}
        List<ItemStack> ender=empty(enderSize);readSlots(data,"EnderItems",ender,enderSize,ops);
        List<ItemStack> cursor=data.contains("CarpetOrgEscrowCursor")?new ArrayList<>(ItemStack.OPTIONAL_CODEC.listOf().parse(ops,data.get("CarpetOrgEscrowCursor")).getOrThrow()):new ArrayList<>();
        var identities=new HashMap<UUID,ItemStack>();var occupied=new HashSet<String>();
        var latestDescriptors=new HashMap<UUID,CompoundTag>();
        for(Tag value:data.getListOrEmpty("CarpetOrgEscrowShadows")){
            if(!(value instanceof CompoundTag descriptor))throw new IllegalArgumentException("Malformed offline private shadow descriptor");
            UUID id=UUID.fromString(descriptor.getStringOr("id",""));var previous=latestDescriptors.get(id);
            if(previous!=null&&previous.getLongOr("revision",-1)==descriptor.getLongOr("revision",-1))for(String field:List.of("item","count","patch"))if(!Objects.equals(previous.get(field),descriptor.get(field)))throw new IllegalArgumentException("Conflicting offline private descriptors at one revision");
            if(previous==null||previous.getLongOr("revision",-1)<descriptor.getLongOr("revision",-1))latestDescriptors.put(id,descriptor);
        }
        for(var entry:latestDescriptors.entrySet()){
            var attempt=OrgItemShadowGroups.readonly(server,entry.getValue());if(!attempt.completed())return new OrgItemShadowGroups.Attempt<>(false,null);identities.put(entry.getKey(),attempt.value());
        }
        for(Tag value:data.getListOrEmpty("CarpetOrgEscrowShadows")){
            if(!(value instanceof CompoundTag descriptor))throw new IllegalArgumentException("Malformed offline private shadow descriptor");
            int kind=descriptor.getIntOr("kind",-1),slot=descriptor.getIntOr("slot",-1);
            List<ItemStack> target=switch(kind){case 0->inventory;case 1->ender;case 2->cursor;default->throw new IllegalArgumentException("Unknown offline shadow inventory kind");};
            if(slot<0||slot>=target.size()||!occupied.add(kind+":"+slot))throw new IllegalArgumentException("Invalid/duplicate offline shadow slot");
            UUID id=UUID.fromString(descriptor.getStringOr("id",""));ItemStack canonical=identities.get(id);
            target.set(slot,canonical.copy());
        }
        rejectUnresolvedPdc(data);
        return new OrgItemShadowGroups.Attempt<>(true,new Snapshot(player,inventory,ender,cursor,raw,data));
    }

    private static void rejectUnresolvedPdc(CompoundTag data){
        var values=data.getCompoundOrEmpty("BukkitValues");
        if(values.contains("lophine:carpet_org_inventory_hold")||values.contains("lophine:carpet_org_xp_credit_escrow"))throw new IllegalStateException("Offline file still contains unresolved inventory/experience custody");
    }
    private static void readSlots(CompoundTag data,String key,List<ItemStack> target,int nativeSize,DynamicOps<Tag> ops){
        var occupied=new HashSet<Integer>();
        for(Tag value:data.getListOrEmpty(key)){
            ItemStackWithSlot slot=ItemStackWithSlot.CODEC.parse(ops,value).getOrThrow();
            if(!slot.isValidInContainer(nativeSize)||!occupied.add(slot.slot()))throw new IllegalArgumentException("Invalid/duplicate offline "+key+" slot");
            target.set(slot.slot(),slot.stack().copy());
        }
    }
    private static CompoundTag loadFile(MinecraftServer server,UUID player)throws IOException{
        Path directory=server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize(),file=directory.resolve(player+".dat");
        if(Files.isSymbolicLink(directory)||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Offline native player file is unavailable: "+player);
        return NbtIo.readCompressed(file,NbtAccounter.create(64L*1024L*1024L));
    }
    private static void rejectOnline(MinecraftServer server,UUID player){
        if(server.getPlayerList().getPlayer(player)!=null||server.getBotList()!=null&&server.getBotList().getBot(player)!=null)throw new IllegalStateException("An online player requires an actual owner snapshot: "+player);
    }
    private static List<ItemStack> empty(int size){return new ArrayList<>(java.util.Collections.nCopies(size,ItemStack.EMPTY));}
    private static List<ItemStack> detached(List<ItemStack> items){return items.stream().map(ItemStack::copy).toList();}
}
