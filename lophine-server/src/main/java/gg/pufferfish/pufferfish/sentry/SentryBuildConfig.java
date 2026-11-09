package gg.pufferfish.pufferfish.sentry;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Defaults supplied by the official build, rather than committed to source control. */
public final class SentryBuildConfig {
    private SentryBuildConfig() {
    }

    public static String dsn() {
        try (InputStream input = SentryBuildConfig.class.getResourceAsStream("/META-INF/lophine/sentry.properties")) {
            return read(input);
        } catch (IOException ignored) {
            return "";
        }
    }

    static String read(InputStream input) {
        if (input == null) return "";
        Properties properties = new Properties();
        try {
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException ignored) {
            return "";
        }
        if (!"true".equals(properties.getProperty("official"))
                || !"LophineLabs/Lophine".equals(properties.getProperty("repository"))) {
            return "";
        }
        return properties.getProperty("dsn", "").trim();
    }
}
