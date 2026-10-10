package fun.bm.lophine.carpet;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Retains the original transient audit action on the native patch platform.
 */
public final class TisDebugSettings {
    private TisDebugSettings() {
    }

    public static String validateUltra(String value, String current, CommandSourceStack source) {
        if (!value.equals("mixin_audit")) return value;
        var failures = new ArrayList<String>();
        int loaded = 0;
        try (var input = TisDebugSettings.class.getResourceAsStream("/carpet/native-audit-targets.list")) {
            if (input == null) throw new IllegalStateException("Native Carpet audit target list is missing");
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String target : reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList()) {
                    try {
                        Class.forName(target, false, TisDebugSettings.class.getClassLoader());
                        loaded++;
                    } catch (ClassNotFoundException | LinkageError failure) {
                        failures.add(target + ": " + failure);
                    }
                }
            }
        } catch (Exception failure) {
            failures.add(failure.toString());
        }
        if (source != null) {
            String response = failures.isEmpty() ? "Native Carpet class loading audit succeeded: " + loaded + " classes. This server uses native patches without a Mixin transformer."
                    : "Native Carpet class loading audit failed: " + String.join("; ", failures);
            TisRaycastCommand.feedback(source, Component.literal(response));
        }
        // Like ValidateUltraSecretSetting, an audit never stores the action token, even on failure.
        return current;
    }
}
