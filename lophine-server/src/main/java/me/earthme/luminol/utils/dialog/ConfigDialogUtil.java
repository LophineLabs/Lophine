package me.earthme.luminol.utils.dialog;

import fun.bm.lophine.utils.ServerI18nUtil;
import me.earthme.luminol.api.config.enums.EnumConfigData;
import me.earthme.luminol.enums.EnumDialogDataType;
import net.minecraft.core.Holder;
import net.minecraft.server.dialog.Dialog;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ConfigDialogUtil {
    public static Holder<Dialog> createHolder(String title, Map<String, Map<EnumConfigData, Object>> configs, String commandPrefix) {
        return DialogUtil.createHolder(title, parseToDialogMap(configs.values()), commandPrefix);
    }

    public static DialogUtil.DialogBuilder addInputs(Map<String, Map<EnumConfigData, Object>> configs, String commandPrefix, @NotNull DialogUtil.DialogBuilder builder) {
        return DialogUtil.addInputs(parseToDialogMap(configs.values()), commandPrefix, builder);
    }

    private static Collection<Map<EnumDialogDataType, Object>> parseToDialogMap(Collection<Map<EnumConfigData, Object>> configs) {
        List<Map<EnumDialogDataType, Object>> ret = new LinkedList<>();
        for (Map<EnumConfigData, Object> dataMap : configs) {
            Object value = dataMap.get(EnumConfigData.VALUE);
            String name = (String) dataMap.get(EnumConfigData.LOCALIZED_NAME);
            String key = String.valueOf(dataMap.get(EnumConfigData.UNIQUE_ID));
            String comment = parseAllAdditional(dataMap);

            ret.add(Map.of(
                    EnumDialogDataType.KEY, key,
                    EnumDialogDataType.NAME, name,
                    EnumDialogDataType.VALUE, value,
                    EnumDialogDataType.COMMENT, comment
            ));
        }
        return ret;
    }

    private static String parseAllAdditional(Map<EnumConfigData, Object> values) {
        boolean isWorldConfig = (boolean) values.get(EnumConfigData.CAN_DEFINED_BY_WORLD);
        String[] suggestions = (String[]) values.get(EnumConfigData.SUGGESTIONS);
        String comment = (String) values.get(EnumConfigData.COMMENT);
        String worldConfigInfo = isWorldConfig ? ServerI18nUtil.getLocalizedText("luminol.config.dialog.is_world_config") : "";

        String addition1 = "";
        if (comment != null && !comment.isEmpty()) {
            addition1 = ServerI18nUtil.getLocalizedText("luminol.config.dialog.comments") + comment;
        }

        addition1 = worldConfigInfo.isEmpty() ? addition1 : worldConfigInfo + "\n" + addition1;

        String addition2 = "";

        if (suggestions != null && suggestions.length > 0) {
            StringBuilder addition = new StringBuilder();
            addition.append(ServerI18nUtil.getLocalizedText("luminol.config.dialog.suggestions"));
            boolean first = true;
            for (String suggestion : suggestions) {
                if (!first) {
                    addition.append(", ");
                } else {
                    first = false;
                }
                addition.append(suggestion);
            }
            addition2 = addition.toString();
        }
        return addition1.isEmpty() ? addition2 : addition2.isEmpty() ? addition1 : addition1 + "\n" + addition2;
    }
}
