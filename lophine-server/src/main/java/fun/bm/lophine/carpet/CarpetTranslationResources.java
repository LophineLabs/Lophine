package fun.bm.lophine.carpet;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers bundled Carpet translation files from the classpath instead of a fixed language list.
 */
public final class CarpetTranslationResources {
    public record Resource(String language, String path) {
    }

    private CarpetTranslationResources() {
    }

    public static List<Resource> list(String directory, String... extensions) {
        return discover(directory, false, extensions);
    }

    public static List<Resource> listAll(String directory, String... extensions) {
        return discover(directory, true, extensions);
    }

    public static InputStream open(String path) {
        InputStream input = CarpetTranslationResources.class.getResourceAsStream(path);
        if (input == null) throw new IllegalStateException("Missing Carpet resource " + path);
        return input;
    }

    private static List<Resource> discover(String directory, boolean recursive, String... extensions) {
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
            String language = fileName.substring(0, dot);
            if (!language.matches("[a-z][a-z0-9]*_[a-z][a-z0-9]*")) continue;
            resources.add(new Resource(language, folder + "/" + slash));
        }
        if (resources.isEmpty()) {
            throw new IllegalStateException("No Carpet translation files in " + folder);
        }
        resources.sort(java.util.Comparator
                .comparing(Resource::language)
                .thenComparingInt((Resource resource) -> resource.path().contains("/lang/") ? 0 : 1)
                .thenComparing(Resource::path));
        return List.copyOf(resources);
    }

    private static List<String> listFileNames(String directory, boolean recursive) {
        try {
            Enumeration<URL> roots = CarpetTranslationResources.class.getClassLoader().getResources(directory);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            while (roots.hasMoreElements()) names.addAll(listFileNames(roots.nextElement(), recursive));
            return List.copyOf(names);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to scan Carpet resources in " + directory, failure);
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
