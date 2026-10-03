// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemPredicateArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import fun.bm.lophine.carpet.config.modules.GeneralCompatConfig;

/** File results contain detached items; every read is admitted by the actual UUID file actor. */
public final class OrgFinderFiles {
    private static final Identifier OPEN=Identifier.fromNamespaceAndPath("carpet-org-addition","open_inventory");
    private static final Identifier TAKE_XP=Identifier.fromNamespaceAndPath("carpet-org-addition","take_offline_experience");
    private static final Identifier QUERY_NAME=Identifier.fromNamespaceAndPath("carpet-org-addition","query_player_name");
    private static final Map<MinecraftServer,Boolean> NAME_QUERIES=new WeakHashMap<>();
    private OrgFinderFiles() {}

    static int start(CommandContext<CommandSourceStack> context,boolean experience)throws CommandSyntaxException {
        var player=context.getSource().getPlayerOrException();
        try {
            var predicate=experience?null:OrgFinderCommands.itemPredicate(context);
            var handle=OrgFinderService.startExternal(player);
            var query=new Query(context.getSource(),player,handle,experience,predicate,experience?"":OrgFinderText.argument(context,"itemStack"));
            var completion=CarpetAsyncCommandResults.defer(context.getSource());
            query.actual.whenComplete((ignored,failure)->completion.complete(failure==null,failure==null?1:0));
            try{query.start();}catch(Throwable failure){query.finish(failure);}return 1;
        } catch(IllegalArgumentException|IllegalStateException failure) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(OrgFinderService.commandFailure(failure)).create();
        }
    }

    private record Row(UUID player,Component text,long count,BigInteger experience) {}
    private static final class Query {
        final CommandSourceStack source;final ServerPlayer player;final MinecraftServer server;
        final OrgFinderService.External handle;final boolean experience;final Predicate<ItemStack> predicate;
        final Component label;final String itemInput;
        final OrgFinderPlayerData.Reader reader;
        final List<Row> rows=new ArrayList<>();final CompletableFuture<Void> actual=new CompletableFuture<>();
        final Set<UUID> found=new HashSet<>();final long started=System.nanoTime();boolean warned;List<UUID> files=List.of();int index,failed;long totalItems;
        final java.util.function.Function<Runnable,CompletableFuture<Void>> output;
        final java.util.function.Consumer<net.minecraft.world.entity.Entity> nextOwned;
        BigInteger totalExperience=BigInteger.ZERO;boolean terminal,nested,progressShown;
        Query(CommandSourceStack source,ServerPlayer player,OrgFinderService.External handle,boolean experience,Predicate<ItemStack> predicate,String itemInput){
            this.source=source;this.player=player;this.server=source.getServer();this.handle=handle;this.experience=experience;this.predicate=predicate;
            this.itemInput=itemInput;this.label=experience?Component.empty():OrgFinderText.itemLabel(itemInput,predicate);
            this.reader=OrgFinderPlayerData.reader(server,handle::cancelled);
            output=carpet.script.external.ScarpetRuntime.captureNativeFunction(body->OrgFinderService.output(player,body));
            nextOwned=carpet.script.external.ScarpetRuntime.captureNativeConsumer(owner->{
                try{
                    boolean notice=!warned&&System.nanoTime()-started>20_000_000_000L;if(notice)warned=true;
                    progressShown|=System.nanoTime()-started>=1_000_000_000L;
                    Component progress=!progressShown?null:experience?OrgFinderText.finder("xp.offline_player.progress",OrgFinderText.progress(index,files.size())):OrgFinderText.finder("item.offline_player.progress",label,OrgFinderText.progress(index,files.size()));
                    if(!notice&&progress==null){next();return;}
                    output.apply(()->{if(notice)player.sendSystemMessage(OrgFinderText.waiting());if(progress!=null)player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(progress));})
                        .whenComplete((ignored,failure)->{if(failure==null)next();else finish(failure);});
                }catch(Throwable failure){finish(failure);}
            });
        }
        void start(){
            carpet.script.external.ScarpetNativeWork.record(actual);carpet.script.external.ScarpetNativeWork.trackNative(server,actual);
            CompletableFuture.supplyAsync(()->{
                var directory=server.getPlayerList().playerIo.getPlayerDir().toPath();
                if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Unable to read \"playerdata\" folder");
                try(var stream=Files.list(directory)){
                    return stream.filter(path->Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)).map(path->path.getFileName().toString())
                        .filter(name->name.endsWith(".dat")).map(name->{try{return UUID.fromString(name.substring(0,name.length()-4));}catch(IllegalArgumentException ignored){return null;}})
                        .filter(Objects::nonNull).sorted().toList();
                }catch(java.io.IOException failure){throw new CompletionException(failure);}
            }).whenComplete((ids,failure)->{if(failure!=null)finish(failure);else{files=ids;try{next();}catch(Throwable error){finish(error);}}});
        }
        void next(){
            if(handle.cancelled()){finish(new CancellationException("Finder cancelled"));return;}
            while(index<files.size()&&online(files.get(index)))index++;
            if(index>=files.size()){finish(null);return;}
            UUID uuid=files.get(index++);
            CompletableFuture<OrgOfflinePlayerSnapshots.Snapshot> read;
            try{read=reader.readForFinder(uuid);}catch(Throwable failure){read=CompletableFuture.failedFuture(failure);}
            read.whenComplete((snapshot,failure)->{
                if(failure!=null){if(!online(uuid))failed++;}
                else if(snapshot!=null&&!handle.cancelled()){
                    try { collect(uuid,snapshot); }
                    catch(RuntimeException badData){failed++;}
                }
                boolean scheduled=player.getBukkitEntity().taskScheduler.schedule(nextOwned,retired->finish(new CancellationException("Finder requester left")),1L);
                if(!scheduled)finish(new CancellationException("Finder requester retired"));
            });
        }
        boolean online(UUID uuid){return server.getPlayerList().getPlayer(uuid)!=null||server.getBotList()!=null&&server.getBotList().getBot(uuid)!=null;}
        void collect(UUID uuid,OrgOfflinePlayerSnapshots.Snapshot snapshot){
            Component name=name(source,uuid);
            if(experience){
                BigInteger value=experience(snapshot.experienceLevel(),snapshot.experienceProgress());if(value.signum()==0)return;
                int level=Math.min(OrgExperienceAmounts.MAX_EFFECTIVE_LEVEL,Math.max(0,snapshot.experienceLevel()));
                Component amount=Component.literal(OrgFinderText.decimals(level+(double)snapshot.experienceProgress()))
                    .withStyle(style->style.withColor(ChatFormatting.GRAY).withHoverEvent(new HoverEvent.ShowText(Component.literal(value.toString()))));
                Component row=OrgFinderText.finder("xp.offline_player.each",name,amount);
                rows.add(new Row(uuid,row,0,value));totalExperience=totalExperience.add(value);found.add(uuid);return;
            }
            addItems(uuid,name,snapshot.inventory(),false);addItems(uuid,name,snapshot.enderItems(),true);
        }
        void addItems(UUID uuid,Component name,List<ItemStack> inventory,boolean ender){
            var statistics=OrgFinderStatistics.count(new SimpleContainer(inventory.toArray(ItemStack[]::new)),predicate);if(statistics.total()==0)return;
            Component container=OrgFinderText.localized("carpet-org-addition.misc."+(ender?"ender_chest":"inventory")).withStyle(ender?ChatFormatting.DARK_PURPLE:ChatFormatting.YELLOW);
            Component named=name;
            if(OrgUtilityCommands.permitted(source,fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.commandPlayer)&&OrgUtilityCommands.permitted(source,GeneralCompatConfig.playerCommandOpenPlayerInventory)&&Set.of("non_whitelist","all_player").contains(GeneralCompatConfig.playerCommandOpenPlayerInventoryOption))
                named=name.copy().append(" ").append(button(OPEN,uuid,ender,"[O]"));
            Component row=OrgFinderText.finder("item.offline_player.each",named,container,OrgFinderText.count(statistics));nested|=!statistics.nested().isEmpty();
            rows.add(new Row(uuid,row,statistics.total(),BigInteger.ZERO));totalItems=Math.addExact(totalItems,statistics.total());found.add(uuid);
        }
        void finish(Throwable failure){
            synchronized(this){if(terminal)return;terminal=true;}
            if(failure!=null){Throwable cause=failure;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();Component message=cause instanceof CancellationException?OrgFinderText.finder("cancelled"):Component.literal("Finder: "+cause.getMessage());output.apply(()->player.sendSystemMessage(message)).whenComplete((ignored,error)->{handle.close();actual.completeExceptionally(failure);});return;}
            rows.sort(experience?Comparator.comparing(Row::experience).reversed():Comparator.comparingLong(Row::count).reversed());
            var output=rows.stream().map(Row::text).toList();
            this.output.apply(()->{
                String key=experience?"xp.offline_player":"item.offline_player";
                if(output.isEmpty()){player.sendSystemMessage(experience?OrgFinderText.finder(key+".cannot_find"):OrgFinderText.finder(key+".cannot_find",label));return;}
                Component people=Component.literal(Integer.toString(rows.size())).withStyle(style->style.withHoverEvent(new HoverEvent.ShowText(Component.empty().append(OrgFinderText.finder(key+".total",files.size())).append("\n").append(OrgFinderText.finder(key+".found",rows.size())))));
                Component head=experience?OrgFinderText.finder(key+".head",rows.size(),OrgFinderText.experienceLevel(totalExperience)):OrgFinderText.finder(key+".head",people,OrgFinderText.total(itemInput,totalItems,nested),label);
                head=head.copy().withStyle(style->style.withHoverEvent(new HoverEvent.ShowText(OrgFinderText.finder(key+".prompt"))));
                player.sendSystemMessage(Component.empty());player.sendSystemMessage(head);OrgPages.print(source,output);
            }).whenComplete((ignored,error)->{handle.close();if(error==null)actual.complete(null);else actual.completeExceptionally(error);});
        }
    }
    static BigInteger experience(int rawLevel,float progress){
        int level=Math.max(0,Math.min(rawLevel,OrgExperienceAmounts.MAX_EFFECTIVE_LEVEL));
        long needed=level>=30?112L+(level-30L)*9:level>=15?37L+(level-15L)*5:7L+level*2L;
        int points=level==OrgExperienceAmounts.MAX_EFFECTIVE_LEVEL?0:Math.max(0,(int)Math.floor(progress*needed));
        return OrgExperienceAmounts.forLevel(level).add(BigInteger.valueOf(points));
    }
    private static Component name(CommandSourceStack source,UUID uuid){
        MinecraftServer server=source.getServer();
        var known=server.services().nameToIdCache().get(uuid);String label=known.map(net.minecraft.server.players.NameAndId::name).orElse("[Unknown]");
        Component name=Component.literal(known.isPresent()?"["+label+"]":label).withStyle(style->style.withColor(ChatFormatting.GRAY)
            .withStrikethrough(known.isEmpty()).withClickEvent(new ClickEvent.CopyToClipboard(known.isPresent()?label:uuid.toString())).withHoverEvent(new HoverEvent.ShowText(Component.literal("UUID: "+uuid+"\n").append(Component.translatable("chat.copy.click")))));
        if(known.isEmpty())return name.copy().append(" ").append(button(QUERY_NAME,uuid,false,"[🔍]"));
        if(OrgUtilityCommands.permitted(source,fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.commandPlayer))return name.copy().append(" ").append(
            Component.literal("[↑]").withStyle(style->style.withColor(ChatFormatting.GRAY).withClickEvent(new ClickEvent.RunCommand(OrgCommandSettings.rewriteCommand("/player "+label+" spawn"))).withHoverEvent(new HoverEvent.ShowText(OrgFinderText.localized("carpet-org-addition.button.login")))));
        return name;
    }
    private static Component button(Identifier id,UUID uuid,boolean ender,String label){
        CompoundTag data=new CompoundTag();data.putString("uuid",uuid.toString());data.putBoolean("ender",ender);data.putString("inventory_type",ender?"ender_chest":"inventory");data.putInt("data_version",1);data.putInt("minecraft_data_version",net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());data.putString("action_source","CHAT");
        Component hover=OPEN.equals(id)?OrgFinderText.localized("carpet-org-addition.operation.open_inventory.hover",OrgFinderText.localized("carpet-org-addition.misc."+(ender?"ender_chest":"inventory"))):QUERY_NAME.equals(id)?Component.empty().append(OrgFinderText.localized("carpet-org-addition.operation.query_player_name.hover.first")).append("\n").append(OrgFinderText.localized("carpet-org-addition.operation.query_player_name.hover.second").withStyle(ChatFormatting.RED)):Component.literal(label);
        return Component.literal(label).withStyle(style->style.withColor(ChatFormatting.GRAY).withClickEvent(new ClickEvent.Custom(id,Optional.of(data))).withHoverEvent(new HoverEvent.ShowText(hover)));
    }
    public static void customClick(ServerPlayer player,Identifier id,Optional<Tag> payload){
        if(!OPEN.equals(id)&&!TAKE_XP.equals(id)&&!QUERY_NAME.equals(id))return;
        if(payload.isEmpty()||!(payload.get() instanceof CompoundTag data))return;
        if(data.getIntOr("data_version",1)>1){OrgMenuNativeEffects.run(player,()->{OrgPages.acceptVersion(player,data);return null;});return;}
        UUID uuid;try{uuid=UUID.fromString(data.getStringOr("uuid",""));}catch(IllegalArgumentException failure){OrgMenuNativeEffects.run(player,()->{player.sendSystemMessage(OrgFinderText.localized("carpet-org-addition.operation.unable_to_parse_string_to_uuid",data.getStringOr("uuid","")).withStyle(ChatFormatting.RED));return null;});return;}
        boolean ender=data.getBooleanOr("ender",data.getStringOr("inventory_type","inventory").equals("ender_chest"));
        OrgMenuNativeEffects.admit(player.level().getServer(),()->OrgMenuNativeEffects.run(player,()->{
            var source=player.createCommandSourceStack();
            if(carpet.script.external.ScarpetNativeWork.isDraining(source.getServer()))return CompletableFuture.<Void>completedFuture(null);
            if(OPEN.equals(id)){
                if(!OrgUtilityCommands.permitted(source,fun.bm.lophine.carpet.config.modules.FakePlayerCompatConfig.commandPlayer))return CompletableFuture.<Void>completedFuture(null);
                player.closeContainer();return OrgPlayerInventoryMenus.openOffline(player,uuid,ender).thenApply(ignored->(Void)null);
            }
            if(TAKE_XP.equals(id))return OrgHiddenPlayerActions.enabled()&&OrgUtilityCommands.permitted(source,GeneralCompatConfig.commandXpTransfer)?OrgExperienceTransfers.takeOffline(player,uuid).thenApply(ignored->(Void)null):CompletableFuture.<Void>completedFuture(null);
            Component displayUuid=Component.literal("UUID").withStyle(style->style.withHoverEvent(new HoverEvent.ShowText(Component.literal(uuid.toString()))));
            var known=source.getServer().services().nameToIdCache().get(uuid);
            if(known.isPresent()){player.sendSystemMessage(queryNameSuccess(displayUuid,known.get().name()));return CompletableFuture.<Void>completedFuture(null);}
            synchronized(NAME_QUERIES){if(NAME_QUERIES.containsKey(source.getServer())){player.sendSystemMessage(OrgFinderText.localized("carpet-org-addition.operation.wait_last").withStyle(ChatFormatting.RED));return CompletableFuture.<Void>completedFuture(null);}NAME_QUERIES.put(source.getServer(),true);}
            player.sendSystemMessage(OrgFinderText.localized("carpet-org-addition.operation.query_player_name.start"));
            CompletableFuture<com.mojang.authlib.GameProfile> resolved;
            try{resolved=net.minecraft.world.item.component.ResolvableProfile.createUnresolved(uuid).resolveProfile(source.getServer().services().profileResolver());}catch(Throwable failure){synchronized(NAME_QUERIES){NAME_QUERIES.remove(source.getServer());}throw failure;}
            carpet.script.external.ScarpetNativeWork.record(resolved);
            var result=resolved.handle((profile,error)->{synchronized(NAME_QUERIES){NAME_QUERIES.remove(source.getServer());}if(error!=null)return OrgFinderText.localized("carpet-org-addition.operation.query_player_name.fail",displayUuid).withStyle(ChatFormatting.RED);source.getServer().services().nameToIdCache().add(new net.minecraft.server.players.NameAndId(uuid,profile.name()));return queryNameSuccess(displayUuid,profile.name());});
            return TisCommandContinuations.then(result,message->OrgFinderService.output(player,()->player.sendSystemMessage(message)));
        }).thenCompose(value->value));
    }
    private static Component queryNameSuccess(Component uuid,String name){Component display=Component.literal(name).withStyle(style->style.withColor(ChatFormatting.GRAY).withClickEvent(new ClickEvent.CopyToClipboard(name)));return OrgFinderText.localized("carpet-org-addition.operation.query_player_name.success",uuid,display);}
}
