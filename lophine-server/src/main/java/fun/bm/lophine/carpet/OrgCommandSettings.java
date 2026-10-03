// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/** Original Org global command configuration, applied after the complete server command tree is built. */
public final class OrgCommandSettings {
    private static final List<String> SERVER_COMMANDS=List.of("itemshadowing","creeper","xpTransfer","spectator","finder","killMe","locations","playerAction","ruleSearch","playerManager","navigate","mail","orange","runtime");
    private static final Path DIRECTORY=Path.of("config","carpetorgaddition");
    private static final class Holder { static final Settings VALUE=read(DIRECTORY.resolve("carpet-org-addition.json"),DIRECTORY.resolve("custom_command_name.json")); }
    record Settings(int sortingItems,Map<String,List<String>> commands) {
        Settings { commands=Map.copyOf(commands); }
        List<String> names(String original){return commands.getOrDefault(original,List.of(original));}
    }
    private OrgCommandSettings() {}
    public static int sortingItemLimit(){return Holder.VALUE.sortingItems();}
    public static String availableName(String original){return Holder.VALUE.names(original).getFirst();}
    /** Generated server buttons use the same configured name as the installed real command tree. */
    public static String rewriteCommand(String command){return rewriteCommand(Holder.VALUE,command);}
    static String rewriteCommand(Settings settings,String command){
        int start=command.startsWith("/")?1:0,space=command.indexOf(' ',start);if(space<0)space=command.length();String original=command.substring(start,space);
        if(!SERVER_COMMANDS.contains(original))return command;
        return command.substring(0,start)+settings.names(original).getFirst()+command.substring(space);
    }
    static Settings read(Path file,Path legacy){
        JsonObject object=new JsonObject();
        if(Files.isRegularFile(file))try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){object=JsonParser.parseReader(reader).getAsJsonObject();}
        catch(Exception failure){MinecraftServer.LOGGER.warn("Cannot read Carpet Org global command configuration; using defaults",failure);}
        if(!object.has("custom_command_name")&&Files.isRegularFile(legacy))try(var reader=Files.newBufferedReader(legacy,StandardCharsets.UTF_8)){
            JsonObject old=JsonParser.parseReader(reader).getAsJsonObject();if(old.has("commands"))object.add("custom_command_name",old.get("commands").deepCopy());
        }catch(Exception failure){MinecraftServer.LOGGER.warn("Cannot read legacy Carpet Org command names; using defaults",failure);}
        return parse(object);
    }
    static Settings parse(JsonObject object){
        int max=16;try{if(object.has("player_action_max_sorting_items"))max=object.get("player_action_max_sorting_items").getAsInt();}
        catch(RuntimeException failure){MinecraftServer.LOGGER.warn("Invalid Carpet Org sorting item limit; using 16",failure);}
        var names=new LinkedHashMap<String,List<String>>();JsonElement configured=object.get("custom_command_name");
        if(configured instanceof JsonObject custom)for(String original:SERVER_COMMANDS){
            JsonElement value=custom.get(original);if(value==null)continue;var aliases=new LinkedHashSet<String>();
            if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString())aliases.add(value.getAsString());
            else if(value.isJsonArray())for(JsonElement element:value.getAsJsonArray())if(element.isJsonPrimitive())aliases.add(element.getAsString());
            if(aliases.isEmpty())aliases.add(original);
            names.put(original,List.copyOf(aliases));
        }
        return new Settings(Math.clamp(max,1,256),names);
    }
    /** Invoke once after public, hidden and Finder Org roots have all been registered. */
    public static void apply(CommandDispatcher<CommandSourceStack> dispatcher){apply(dispatcher,Holder.VALUE);}
    static void apply(CommandDispatcher<CommandSourceStack> dispatcher,Settings settings){
        var roots=new LinkedHashMap<String,LiteralCommandNode<CommandSourceStack>>();
        for(String original:SERVER_COMMANDS){var node=dispatcher.getRoot().getChild(original);if(node instanceof LiteralCommandNode<CommandSourceStack> literal)roots.put(original,literal);}
        // Keep immutable references to every original root before any configured rename.
        // A name shared by two Org commands is merged in the same order as upstream registration.
        for(String original:roots.keySet())if(!settings.names(original).contains(original))dispatcher.getRoot().removeCommand(original);
        roots.forEach((original,node)->{
            for(String alias:settings.names(original)){
                if(alias.equals(original)&&dispatcher.getRoot().getChild(original)==node)continue;
                var renamed=new LiteralCommandNode<>(alias,node.getCommand(),node.getRequirement(),node.getRedirect(),node.getRedirectModifier(),node.isFork());
                node.getChildren().forEach(renamed::addChild);dispatcher.getRoot().addChild(renamed);
            }
        });
    }
}
