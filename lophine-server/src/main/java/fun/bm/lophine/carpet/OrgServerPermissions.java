// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1.
package fun.bm.lophine.carpet;

import carpet.script.external.WeakIdentityMap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Original world permission.json and orange permission operate on the real subcommand predicates. */
public final class OrgServerPermissions {
    private static final Map<String,String> DEFAULTS=Map.ofEntries(
        Map.entry("finder.block","true"),Map.entry("finder.item","true"),Map.entry("finder.item.from.offline_player","true"),Map.entry("finder.trade","true"),Map.entry("finder.worldEater","true"),
        Map.entry("mail.intercept","2"),Map.entry("navigate.death","true"),Map.entry("playerAction.player.bedrock.ai","true"),Map.entry("playerManager.autologin","true"),Map.entry("playerManager.schedule.relogin","true"));
    private static final WeakIdentityMap<MinecraftServer,State> STATES=new WeakIdentityMap<>();
    private static final class State {
        final Path file;
        final Object fileLock=new Object();
        final AtomicReference<Map<String,String>> levels;
        State(MinecraftServer server){file=OrgWorldFormat.directory(server).resolve("permission.json");levels=new AtomicReference<>(read(file));}
    }
    private OrgServerPermissions(){}
    static String canonical(String value){return switch(value){case "true","0"->"true";case "false"->"false";case "ops","2"->"2";case "1","3","4"->value;default->throw new IllegalArgumentException("Invalid Org permission level");};}
    static int ordinal(String level){return switch(level){case "true"->0;case "1"->1;case "2"->2;case "3"->3;case "4"->4;case "false"->5;default->throw new IllegalArgumentException("Invalid Org permission level");};}
    static List<String> nodes(){return DEFAULTS.keySet().stream().filter(node->OrgHiddenPlayerActions.enabled()||!node.equals("finder.worldEater")&&!node.equals("playerAction.player.bedrock.ai")).sorted().toList();}
    static Map<String,String> read(Path file){
        var levels=new LinkedHashMap<String,String>(DEFAULTS);
        if(Files.isRegularFile(file))try(var reader=Files.newBufferedReader(file,java.nio.charset.StandardCharsets.UTF_8)){
            JsonObject root=JsonParser.parseReader(reader).getAsJsonObject();JsonObject permissions=root.getAsJsonObject("permission");if(permissions!=null)for(var entry:permissions.entrySet()){
                if(!nodes().contains(entry.getKey()))continue;
                try{levels.put(entry.getKey(),canonical(entry.getValue().getAsString()));}catch(RuntimeException failure){MinecraftServer.LOGGER.warn("Unable to parse Org permission node {}",entry.getKey());}
            }
        }catch(IOException|RuntimeException failure){MinecraftServer.LOGGER.warn("Cannot load Carpet Org world permissions",failure);}
        return Map.copyOf(levels);
    }
    /** Source initialization is synchronous once, before a predicate can temporarily grant a denied node. */
    public static void initialize(MinecraftServer server){STATES.computeIfAbsent(server,State::new);}
    public static void close(MinecraftServer server){State state=STATES.get(server);if(state!=null)STATES.remove(server,state);OrgWorldFormat.close(server);}
    public static boolean allowed(CommandSourceStack source,String node){
        String level=DEFAULTS.get(node);if(level==null)throw new IllegalArgumentException("Unknown Org permission node");
        MinecraftServer server=source.getServer();if(server!=null){initialize(server);level=STATES.get(server).levels.get().get(node);}
        if(!CarpetCommandPermissions.canUse(source,level))return false;
        var sender=source.getBukkitSender();return sender==null||!sender.isPermissionSet(node)||sender.hasPermission(node);
    }
    static CompletableFuture<Integer> set(CommandSourceStack source,String node,String requested){
        return OrgMenuNativeEffects.admit(source.getServer(),()->{
            if(!nodes().contains(node))return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown Org permission node"));
            String level=canonical(requested);initialize(source.getServer());State state=STATES.get(source.getServer());
            // Source changes the live permission and command trees before saving its file.
            var changed=OrgCommandNativeEffects.global(source.getServer(),()->{
                state.levels.updateAndGet(before->{var after=new LinkedHashMap<>(before);after.put(node,level);return Map.copyOf(after);});
                var recipients=new java.util.ArrayList<CompletableFuture<Void>>();for(var player:List.copyOf(source.getServer().getPlayerList().getPlayers()))recipients.add(OrgMenuNativeEffects.run(player,()->{var actual=player.isRemoved()?CompletableFuture.<Void>completedFuture(null):source.getServer().getCommands().carpetReloadCommands(player);carpet.script.external.ScarpetNativeWork.record(actual);return actual;}).thenCompose(java.util.function.Function.identity()));
                return CompletableFuture.allOf(recipients.toArray(CompletableFuture[]::new));
            }).thenCompose(java.util.function.Function.identity());
            return TisCommandContinuations.then(changed,ignored->OrgCommandNativeEffects.file(source.getServer(),()->{
                synchronized(state.fileLock){
                    JsonObject data=new JsonObject(),permissions=new JsonObject();data.addProperty("data_version",3);Map<String,String> snapshot=state.levels.get();for(String known:nodes())permissions.addProperty(known,snapshot.get(known));data.add("permission",permissions);
                    try{OrgPlayerManager.atomicText(state.file,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data)+"\n");if(!data.equals(JsonParser.parseString(Files.readString(state.file)).getAsJsonObject()))throw new IOException("Cannot verify saved Org permissions");}
                    catch(IOException failure){throw new java.util.concurrent.CompletionException(failure);}return ordinal(level);
                }
            }));
        });
    }
    static void register(CommandDispatcher<CommandSourceStack> dispatcher){dispatcher.register(Commands.literal("orange").then(Commands.literal("permission").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
        .then(Commands.argument("node",StringArgumentType.string()).suggests((context,builder)->SharedSuggestionProvider.suggest(nodes().stream().map(StringArgumentType::escapeIfRequired),builder))
            .then(Commands.argument("level",StringArgumentType.string()).suggests((context,builder)->SharedSuggestionProvider.suggest(new String[]{"true","false","ops","0","1","2","3","4"},builder))
                .executes(context->OrgCommandNativeEffects.command(context.getSource(),1,()->set(context.getSource(),StringArgumentType.getString(context,"node"),StringArgumentType.getString(context,"level"))))))));}
}
