package fun.bm.lophine.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import fun.bm.lophine.config.modules.function.LanguageConfig;
import io.papermc.paper.ServerBuildInfo;
import me.earthme.luminol.config.ConfigManager;
import me.earthme.luminol.config.ConfigsInstance;
import net.minecraft.locale.DeprecatedTranslationsInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/*
 * authored by: Helvetica Volubi <suisuroru@blue-millennium.fun>
 * modified by: Lumine1909 <133463833+Lumine1909@users.noreply.github.com>
 * Some of diff form Leaves
 */
public class ServerI18nUtil {
    private static final Logger logger = LogUtils.getClassLogger();
    private static final String VERSION = ServerBuildInfo.buildInfo().minecraftVersionId();
    private static final String BASE_PATH = "cache/lophine/" + VERSION + "/";
    private static final Set<String> registeredLanguageBasePath = new HashSet<>();
    private static final String defaultLophineLangName = "en_us.json";
    private static final String manifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final String resourceBaseUrl = "https://resources.download.minecraft.net/";
    // pre-load
    private static CompletableFuture<Void> preloadTask;
    // paths
    private static String langPath;
    private static String assetsPath;
    private static String versionPath;
    private static String manifestPath;
    private static String langJsonPath;
    private static String targetLangFileName;

    private static void register() {
        register("assets/lophine/lang");
    }

    private static void register(String base) {
        if (!base.endsWith("/")) {
            base += "/";
        }
        registeredLanguageBasePath.add(base);
    }

    public static void init() {
        if (Objects.equals(LanguageConfig.lang, "en_us")) {
            return;
        }
        register();
        langPath = BASE_PATH + "lang/" + LanguageConfig.lang + ".json";
        langJsonPath = "minecraft/lang/" + LanguageConfig.lang + ".json";
        targetLangFileName = LanguageConfig.lang + ".json";
        logger.info("Starting load language: {}", LanguageConfig.lang);
        if (LanguageConfig.full_blocking_load) {
            loadI18n(LanguageConfig.lang, 2);
        } else {
            preloadTask.thenAcceptAsync(_ -> loadI18n(LanguageConfig.lang, 2));
        }
    }

    public static void preInit() {
        assetsPath = BASE_PATH + "assets.json";
        versionPath = BASE_PATH + VERSION + ".json";
        manifestPath = BASE_PATH + "manifest.json";
        preloadTask = CompletableFuture.runAsync(() -> preLoadI18n(2));
    }

    private static void preLoadI18n(int retryTime) {
        try {
            if (!Files.exists(Path.of(assetsPath))) {
                downloadAssets(true);
            }
        } catch (UnsupportedLanguageException e) {
            logger.warn("Unsupported language: {}", LanguageConfig.lang);
            // Fallback to English
            final ConfigsInstance configsInstance = ConfigManager.getConfigs("lophine");
            configsInstance.setConfig(new String[]{"function", "language", "lang"}, "en_us");
            configsInstance.reloadAsync(true);
        } catch (Exception e) {
            if (e instanceof MalformedJsonException malformedJson) {
                malformedJson.clean();
            }
            logger.warn("Failed to download language list file: ", e);
            if (retryTime > 0) {
                preLoadI18n(retryTime - 1);
            } else {
                logger.error("Failed to download language list file for many times, skip pre-load language.");
            }
        }
    }

    private static void loadI18n(String lang, int retryTime) {
        try {
            if (!Files.exists(Path.of(langPath))) {
                downloadLang(true);
            }
            Language.inject(createLangInstance());
            logger.info("Successfully loaded language: {}", lang);
            if (LanguageConfig.allowAutoResetComments) {
                logger.info("Start trying to load localized comments.");
                ConfigManager.reloadComments();
                logger.info("Loaded all comments.");
            }
        } catch (Exception e) {
            if (e instanceof MalformedJsonException malformedJson) {
                malformedJson.clean();
            }
            logger.warn("Failed to load language file for {}", lang, e);
            if (retryTime > 0) {
                loadI18n(lang, retryTime - 1);
            } else {
                logger.error("Failed to load for many times, use default lang \"en_us\" instead");
            }
        }
    }

    private static void downloadLang(boolean fetchFromAssets) throws Exception {
        JsonObject json;
        if (!Files.exists(Path.of(assetsPath)) || (json = loadJson(assetsPath)) == null) {
            if (fetchFromAssets) {
                downloadAssets(true);
                downloadLang(false);
            }
            return;
        }

        JsonObject langEntry = json.getAsJsonObject("objects").getAsJsonObject(langJsonPath);

        if (langEntry == null) {
            throw new UnsupportedLanguageException();
        }

        String hash = langEntry.get("hash").getAsString();
        if (hash == null || hash.length() < 2) {
            throw new IllegalArgumentException("Invalid hash value");
        }

        String langUrl = resourceBaseUrl + hash.substring(0, 2) + "/" + hash;
        fetchAndSave(langUrl, langPath);
    }

    private static void downloadAssets(boolean fetchFromVersion) throws Exception {
        JsonObject json;
        if (!Files.exists(Path.of(versionPath)) || (json = loadJson(versionPath)) == null) {
            if (fetchFromVersion) {
                downloadVersion(true);
                downloadAssets(false);
            }
            return;
        }

        JsonObject assetIndex = json.getAsJsonObject("assetIndex");
        String assetUrl = assetIndex.get("url").getAsString();
        fetchAndSave(assetUrl, assetsPath);
    }

    private static void downloadVersion(boolean fetchFromManifest) throws Exception {
        JsonObject json;
        if (!Files.exists(Path.of(manifestPath)) || (json = loadJson(manifestPath)) == null) {
            if (fetchFromManifest) {
                fetchAndSave(manifestUrl, manifestPath);
                downloadVersion(false);
            }
            return;
        }

        String versionUrl = null;
        for (JsonElement element : json.getAsJsonArray("versions")) {
            String id = element.getAsJsonObject().get("id").getAsString();
            String url = element.getAsJsonObject().get("url").getAsString();
            if (VERSION.equals(id)) {
                versionUrl = url;
                break;
            }
        }

        if (versionUrl == null) {
            throw new RuntimeException("Could not find version URL");
        }

        fetchAndSave(versionUrl, versionPath);
    }

    private static byte[] fetch(String urlString) throws IOException, InterruptedException {
        try {
            HttpResponse<String> response;
            try (HttpClient httpClient = HttpClient.newHttpClient()) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(urlString))
                        .timeout(Duration.ofSeconds(10))
                        .build();
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }

            int responseCode = response.statusCode();
            if (responseCode != 200) {
                logger.info("Unexpected response code: {}", responseCode);
                logger.info("Response body: {}", response.body());
                throw new UnsupportedEncodingException("Unexpected response code");
            } else {
                return response.body().getBytes();
            }
        } catch (Exception e) {
            logger.warn("Error in getting info!");
            throw e;
        }
    }

    private static void fetchAndSave(String url, String savePath) throws IOException, InterruptedException {
        byte[] data = fetch(url);
        Path outputPath = Path.of(savePath);
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, data, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private static JsonObject loadJson(String path) {
        try {
            byte[] data = Files.readAllBytes(Paths.get(path));
            return JsonParser.parseString(new String(data)).getAsJsonObject();
        } catch (JsonSyntaxException e) {
            logger.warn("Corrupt json file!");
            throw new MalformedJsonException(e, path);
        } catch (Exception e) {
            logger.warn("Failed to load local JSON!");
            return null;
        }
    }

    private static Language createLangInstance() throws IOException {
        DeprecatedTranslationsInfo deprecatedTranslationsInfo = DeprecatedTranslationsInfo.loadFromDefaultResource();
        Map<String, String> map = new HashMap<>();
        parseTranslations(map::put);
        loadLophineI18n(map::put);
        deprecatedTranslationsInfo.applyToMap(map);
        final Map<String, String> map1 = Map.copyOf(map);
        return new Language() {
            @Override
            public @NotNull String getOrDefault(@NotNull String key, @NotNull String defaultValue) {
                return map1.getOrDefault(key, defaultValue);
            }

            @Override
            public boolean has(@NotNull String id) {
                return map1.containsKey(id);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public @NotNull FormattedCharSequence getVisualOrder(@NotNull FormattedText text) {
                return sink -> text.visit(
                                (style, content) -> StringDecomposer.iterateFormatted(content, style, sink) ? Optional.empty() : FormattedText.STOP_ITERATION,
                                Style.EMPTY
                        )
                        .isPresent();
            }
        };
    }

    private static void loadLophineI18n(BiConsumer<String, String> bi) {
        loadLophineI18nDefault(bi);
        for (String targetPath : registeredLanguageBasePath) {
            String targetFile = targetPath + targetLangFileName;
            if (Language.class.getResource(targetFile) != null) {
                Language.parseTranslations(bi, targetFile);
            }
        }
    }

    public static void loadLophineI18nDefault(BiConsumer<String, String> bi) {
        for (String targetPath : registeredLanguageBasePath) {
            String targetFile = targetPath + defaultLophineLangName;
            if (Language.class.getResource(targetFile) != null) {
                Language.parseTranslations(bi, targetFile);
            }
        }
    }

    private static void parseTranslations(BiConsumer<String, String> output) throws IOException {
        Path filePath = Path.of(langPath);
        try (InputStream fileStream = Files.newInputStream(filePath)) {
            Language.loadFromJson(fileStream, output);
        } catch (NoSuchFileException e) {
            logger.warn("Couldn't find language file: {}", langPath);
            throw e;
        } catch (JsonSyntaxException e) {
            throw new MalformedJsonException(e, langPath);
        } catch (Exception e) {
            logger.warn("Failed to load language from filesystem {}", filePath);
            throw e;
        }
    }

    public static String getLocalizedComment(String key) {
        String current = Language.getInstance().getOrDefault(key, "");
        if (!current.isBlank()) return current;
        return Language.DEFAULT_INSTANCE.getOrDefault(key, "");
    }

    private static class UnsupportedLanguageException extends Exception {
    }

    private static class MalformedJsonException extends RuntimeException {
        private final Path path;

        protected MalformedJsonException(Throwable root, String path) {
            super(root);
            this.path = Path.of(path);
        }

        public void clean() {
            try {
                Files.delete(path);
            } catch (IOException e) {
                logger.info("Failed to delete malformed JSON file: {}", path);
            }
        }
    }
}