package fun.bm.lophine.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers files from the classpath instead of a fixed name list.
 */
public final class FileResolver {
    /**
     * Minecraft language file stems: {@code en_us}, {@code zh_cn}, {@code lzh}, {@code es_419}.
     */
    public static final String LANGUAGE_NAME = "[a-z][a-z0-9]*(?:[_-][a-z0-9]+)*";

    public record Resource(String name, String path) {
    }

    public static List<Resource> list(String directory, String namePattern, String... extensions) {
        return discover(directory, false, namePattern, extensions);
    }

    public static List<Resource> listAll(String directory, String namePattern, String... extensions) {
        return discover(directory, true, namePattern, extensions);
    }

    public static InputStream open(String path) {
        InputStream input = FileResolver.class.getResourceAsStream(path);
        if (input == null) throw new IllegalStateException("Missing resource " + path);
        return input;
    }

    private static List<Resource> discover(String directory, boolean recursive, String namePattern, String... extensions) {
        Set<String> allowed = Set.of(extensions);
        String folder = directory.startsWith("/") ? directory : "/" + directory;
        if (folder.endsWith("/")) folder = folder.substring(0, folder.length() - 1);
        List<Resource> resources = new ArrayList<>();
        for (String relative : listFileNames(folder.substring(1), recursive)) {
            String slash = relative.replace('\\', '/');
            String fileName = slash.substring(slash.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            int dot = fileName.lastIndexOf('.');
            if (dot <= 0) continue;
            if (!allowed.contains(fileName.substring(dot))) continue;
            String name = fileName.substring(0, dot);
            if (namePattern != null && !namePattern.isEmpty() && !name.matches(namePattern)) continue;
            resources.add(new Resource(name, folder + "/" + slash));
        }
        if (resources.isEmpty()) {
            throw new IllegalStateException("No matched files in " + folder);
        }
        return List.copyOf(resources);
    }

    private static List<String> listFileNames(String directory, boolean recursive) {
        try {
            Enumeration<URL> roots = FileResolver.class.getClassLoader().getResources(directory);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            while (roots.hasMoreElements()) names.addAll(listFileNames(roots.nextElement(), recursive));
            return List.copyOf(names);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to scan resources in " + directory, failure);
        }
    }

    private static List<String> listFileNames(URL url, boolean recursive) throws Exception {
        URI uri = url.toURI();
        if ("file".equals(uri.getScheme())) return listDirectory(Path.of(uri), recursive);
        if (!"jar".equals(uri.getScheme())) return List.of();
        String uriString = uri.toString();
        int split = uriString.indexOf("!/");
        if (split < 0) return List.of();
        URI jarUri = URI.create(uriString.substring(0, split));
        String dir = uriString.substring(split + 2);
        if (!dir.endsWith("/")) dir += "/";
        FileSystem fileSystem = null;
        boolean created = false;
        try {
            fileSystem = FileSystems.getFileSystem(jarUri);
        } catch (FileSystemNotFoundException ignored) {
            fileSystem = FileSystems.newFileSystem(jarUri, Map.of());
            created = true;
        }
        try {
            return listDirectory(fileSystem.getPath(dir), recursive);
        } finally {
            if (created && fileSystem != null) fileSystem.close();
        }
    }

    private static List<String> listDirectory(Path dir, boolean recursive) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = recursive ? Files.walk(dir) : Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> dir.relativize(path).toString())
                    .toList();
        }
    }
}
