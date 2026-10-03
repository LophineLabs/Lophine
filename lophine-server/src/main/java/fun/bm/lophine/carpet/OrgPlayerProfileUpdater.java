// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Official FakePlayerSerializerDataUpdater 0..5, including the version-0 script updater. */
final class OrgPlayerProfileUpdater {
    private OrgPlayerProfileUpdater() {}
    static JsonObject update(JsonObject input) {
        JsonObject profile=input.deepCopy();
        int version=profile.has("data_version")?profile.get("data_version").getAsInt():profile.has("DataVersion")?profile.get("DataVersion").getAsInt():0;
        if(version<0||version>5)throw new IllegalStateException("Json has not been updated to the target version");
        if(version<=2){if(version==0&&profile.has("script_action"))profile.add("script_action",script(profile.getAsJsonObject("script_action")));profile.addProperty("data_version",3);version=3;}
        if(version==3){
            JsonObject updated=new JsonObject();for(var entry:profile.entrySet())switch(entry.getKey()){
                case "hand_action"->updated.add("simple_action",entry.getValue());
                case "startup"->{JsonArray startup=new JsonArray();for(var value:entry.getValue().getAsJsonArray()){
                    JsonObject action=new JsonObject();for(var part:value.getAsJsonObject().entrySet()){
                        if(part.getKey().equals("action")){JsonObject function=new JsonObject();function.addProperty("type","simple");function.add("value",part.getValue());action.add("function",function);}else action.add(part.getKey(),part.getValue());
                    }startup.add(action);
                }updated.add("startup_action",startup);}
                default->updated.add(entry.getKey(),entry.getValue());
            }updated.addProperty("data_version",4);profile=updated;version=4;
        }
        if(version==4){
            if(profile.has("script_action")){JsonObject actions=profile.getAsJsonObject("script_action");if(actions.has("rename")){JsonObject before=actions.getAsJsonObject("rename"),renamed=new JsonObject();for(var entry:before.entrySet())renamed.add(entry.getKey().equals("new_name")?"name":entry.getKey(),entry.getValue());actions.add("rename",renamed);}}
            profile.addProperty("data_version",5);
        }
        return profile;
    }
    private static JsonObject script(JsonObject old){
        JsonObject result=new JsonObject();for(var entry:old.entrySet()){
            String key=switch(entry.getKey()){case "clean"->"empty_the_container";case "fill"->"fill_the_container";case "inventory_crafting"->"inventory_craft";case "sorting"->"categorize";case "planting"->"plant";default->entry.getKey();};
            JsonObject action=entry.getValue().getAsJsonObject();
            if(entry.getKey().equals("clean")||entry.getKey().equals("fill")){
                JsonObject updated=new JsonObject();boolean any=action.has("allItem")&&action.get("allItem").getAsBoolean();String item="*";
                if(!any&&action.has("item")){var nativeItem=BuiltInRegistries.ITEM.getValue(Identifier.parse(action.get("item").getAsString()));item=BuiltInRegistries.ITEM.getKey(nativeItem).toString();}
                updated.addProperty("item",item);if(entry.getKey().equals("fill"))updated.addProperty("dropOther",!action.has("dropOther")||action.get("dropOther").getAsBoolean());action=updated;
            }result.add(key,action);
        }return result;
    }
}
