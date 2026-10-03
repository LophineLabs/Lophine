package carpet.script.api;

import carpet.script.CarpetContext;
import carpet.script.Expression;
import carpet.script.exception.InternalExpressionException;
import carpet.script.utils.SystemInfo;
import carpet.script.value.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Monitoring {
    private static final Map<String, MobCategory> MOB_CATEGORY_MAP = Arrays.stream(MobCategory.values()).collect(Collectors.toMap(MobCategory::getName, Function.identity()));

    public static void apply(Expression expression) {
        expression.addContextFunction("system_info", -1, (c, t, lv) ->
        {
            if (lv.isEmpty()) {
                return SystemInfo.getAll();
            }
            if (lv.size() == 1) {
                String what = lv.get(0).getString();
                Value res = SystemInfo.get(what, (CarpetContext) c);
                if (res == null) {
                    throw new InternalExpressionException("Unknown option for 'system_info': " + what);
                }
                return res;
            }
            throw new InternalExpressionException("'system_info' requires one or no parameters");
        });
        // game processed snooper functions
        expression.addContextFunction("get_mob_counts", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            ServerLevel world = cc.level();
            var info = fun.bm.lophine.carpet.CarpetMobcaps.dimension(world.dimension().identifier().toString());
            if (info == null) {
                return Value.NULL;
            }
            Map<MobCategory, Integer> mobcounts = info.counts();
            int chunks = info.chunks();
            if (lv.isEmpty()) {
                Map<Value, Value> retDict = new HashMap<>();
                for (MobCategory category : mobcounts.keySet()) {
                    int currentCap = info.limits().getOrDefault(category, 0);
                    retDict.put(
                            new StringValue(category.getSerializedName().toLowerCase(Locale.ROOT)),
                            ListValue.of(
                                    new NumericValue(mobcounts.getOrDefault(category, 0)),
                                    new NumericValue(currentCap))
                    );
                }
                return MapValue.wrap(retDict);
            }
            String catString = lv.get(0).getString();
            MobCategory cat = MOB_CATEGORY_MAP.get(catString.toLowerCase(Locale.ROOT));
            if (cat == null) {
                throw new InternalExpressionException("Unreconized mob category: " + catString);
            }
            return ListValue.of(
                    new NumericValue(mobcounts.getOrDefault(cat, 0)),
                    new NumericValue(info.limits().getOrDefault(cat, 0))
            );
        });
    }
}
