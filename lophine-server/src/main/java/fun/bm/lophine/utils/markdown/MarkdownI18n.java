package fun.bm.lophine.utils.markdown;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class MarkdownI18n {
    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final String RESOURCE_ROOT = "assets/lophine/markdown/";

    private static volatile Map<String, MarkdownTemplate> cache = Map.of();

    public static void reload(String targetLang, String defaultLang) {
        Map<String, MarkdownTemplate> compiled = new LinkedHashMap<>();
        loadLang(defaultLang, compiled);
        if (targetLang != null && !targetLang.isBlank() && !defaultLang.equals(targetLang)) {
            loadLang(targetLang, compiled);
        }
        cache = Map.copyOf(compiled);
    }

    public static @NotNull MarkdownTemplate get(String key) {
        MarkdownTemplate template = cache.get(key);
        return template != null ? template : MarkdownParser.parse("");
    }

    private static void loadLang(String lang, Map<String, MarkdownTemplate> into) {
        String directory = RESOURCE_ROOT + lang + "/";
        try {
            for (String fileName : listFiles(lang)) {
                String source = read(directory + fileName);
                if (source == null) {
                    continue;
                }
                into.put(fileName.substring(0, fileName.length() - 3), MarkdownParser.parse(strip(source)));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load markdown for language {}", lang, e);
        }
    }

    private static List<String> listFiles(String lang) throws Exception {
        Enumeration<URL> roots = MarkdownI18n.class.getClassLoader().getResources(RESOURCE_ROOT + lang);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        while (roots.hasMoreElements()) {
            names.addAll(listFiles(roots.nextElement()));
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(null);
        return sorted;
    }

    private static List<String> listFiles(URL url) throws Exception {
        URI uri = url.toURI();
        if ("file".equals(uri.getScheme())) {
            return listDirectory(Path.of(uri));
        }
        if (!"jar".equals(uri.getScheme())) {
            return List.of();
        }
        String uriString = uri.toString();
        int split = uriString.indexOf("!/");
        if (split < 0) {
            return List.of();
        }
        URI jarUri = URI.create(uriString.substring(0, split));
        String dir = uriString.substring(split + 2);
        if (!dir.endsWith("/")) {
            dir += "/";
        }
        FileSystem fileSystem = null;
        boolean created = false;
        try {
            fileSystem = FileSystems.getFileSystem(jarUri);
        } catch (FileSystemNotFoundException ignored) {
            fileSystem = FileSystems.newFileSystem(jarUri, Map.of());
            created = true;
        }
        try {
            return listDirectory(fileSystem.getPath(dir));
        } finally {
            if (created && fileSystem != null) {
                fileSystem.close();
            }
        }
    }

    private static List<String> listDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .toList();
        }
    }

    private static String read(String path) {
        try (InputStream stream = MarkdownI18n.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to read markdown resource {}", path, e);
            return null;
        }
    }

    private static String strip(String source) {
        if (source.startsWith("\uFEFF")) {
            source = source.substring(1);
        }
        source = source.replace("\r\n", "\n").replace('\r', '\n');
        if (source.endsWith("\n")) {
            return source.substring(0, source.length() - 1);
        }
        return source;
    }
}
