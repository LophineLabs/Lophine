package me.earthme.luminol.utils;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import io.papermc.paper.ServerBuildInfo;
import me.earthme.luminol.config.modules.misc.AutoUpdateConfig;
import net.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardOpenOption.*;

public final class AutoUpdateHelper {
    private final Logger LOGGER = LogUtils.getClassLogger();
    private final Gson GSON = new Gson();

    private final String GITHUB_API_BASE = "https://api.github.com/repos/lophineMC/lophine";
    private final Path AUTO_UPDATE_DIR = Path.of("auto_update");
    private final Path CORE_PATH_FILE = AUTO_UPDATE_DIR.resolve("core.path");
    private final Path LUMINOL_UPDATE_DIR = AUTO_UPDATE_DIR.resolve("lophine");
    private final Path LATEST_PATH_FILE = LUMINOL_UPDATE_DIR.resolve("latest.path");
    private final long DAILY_TASK_PERIOD_MILLIS = TimeUnit.DAYS.toMillis(1);

    private final AtomicBoolean UPDATE_RUNNING = new AtomicBoolean(false);

    private volatile @Nullable ScheduledExecutorService scheduler;

    public synchronized void load(boolean reload) {
        if (reload) shutdown();
        ensureWorkingDirectories();

        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "Lophine Auto Update Scheduler");
            thread.setDaemon(true);
            return thread;
        });

        if (AutoUpdateConfig.checkTimes.isEmpty()) {
            LOGGER.warn("Lophine auto-update is enabled, but misc.auto_update.check_times is empty.");
            return;
        }

        final LocalTime currentTime = LocalTime.now();

        for (String time : AutoUpdateConfig.checkTimes) {
            try {
                final String[] parts = time.split(":");
                final LocalTime taskTime = LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                Duration taskDelay = Duration.between(currentTime, taskTime);
                if (taskDelay.isNegative()) {
                    taskDelay = taskDelay.plusDays(1);
                }

                scheduler.scheduleAtFixedRate(
                        this::checkForUpdatesSafely,
                        taskDelay.toMillis(),
                        DAILY_TASK_PERIOD_MILLIS,
                        TimeUnit.MILLISECONDS
                );
            } catch (Exception e) {
                LOGGER.warn("Illegal auto-update time ignored: {}", time);
            }
        }
    }

    public synchronized void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        UPDATE_RUNNING.set(false);
    }

    private void checkForUpdatesSafely() {
        if (!UPDATE_RUNNING.compareAndSet(false, true)) {
            LOGGER.info("A Lophine auto-update check is already running, skipping this schedule.");
            return;
        }

        try {
            checkForUpdates();
        } catch (Exception e) {
            LOGGER.warn("Lophine auto-update failed.", e);
        } finally {
            UPDATE_RUNNING.set(false);
        }
    }

    private void checkForUpdates() {
        final ServerBuildInfo buildInfo = ServerBuildInfo.buildInfo();
        final Optional<String> gitCommit = buildInfo.gitCommit();

        if (gitCommit.isEmpty()) {
            LOGGER.info("Current build does not expose a git hash. Skipping auto-update.");
            return;
        }

        final String currentGitHash = gitCommit.get();
        final String mcVersion = buildInfo.minecraftVersionId();
        final String gitBranch = buildInfo.gitBranch().orElse(null);

        final ReleaseAssetInfo latestRelease = getLatestCompatibleRelease(mcVersion, gitBranch);
        if (latestRelease == null) {
            LOGGER.warn("No compatible Lophine release was found for Minecraft {}{}.",
                    mcVersion,
                    gitBranch == null ? "" : " on branch " + gitBranch
            );
            return;
        }

        final UpdateStatus updateStatus = compareReleaseWithCurrentBuild(latestRelease.releaseCommitHash(), currentGitHash);
        if (updateStatus == UpdateStatus.UP_TO_DATE) {
            LOGGER.info("You are already running the latest compatible Lophine release: {}", latestRelease.tagName());
            return;
        }

        if (updateStatus == UpdateStatus.CURRENT_BUILD_AHEAD_OR_DIVERGED) {
            LOGGER.info(
                    "Current build {} is not behind release {}, skipping auto-update to avoid downgrading.",
                    currentGitHash,
                    latestRelease.tagName()
            );
            return;
        }

        if (updateStatus == UpdateStatus.UNKNOWN) {
            LOGGER.warn(
                    "Could not compare current build {} with release {}, skipping auto-update to avoid unsafe replacement.",
                    currentGitHash,
                    latestRelease.tagName()
            );
            return;
        }

        LOGGER.info("Found a newer Lophine release: {}", latestRelease.tagName());

        final Path stagedJar = downloadAndStageRelease(latestRelease);
        final Path finalJarPath = tryApplyTargetJar(stagedJar);
        writeCorePath(finalJarPath);
        writeLatestPath(finalJarPath);

        if (finalJarPath.equals(stagedJar)) {
            LOGGER.info(
                    "Downloaded the latest Lophine jar to {} and refreshed auto_update/core.path for Clip. Please restart your server.",
                    finalJarPath.toAbsolutePath()
            );
        } else {
            LOGGER.info(
                    "Updated target jar at {} using release {} and refreshed auto_update/core.path. Please restart your server.",
                    finalJarPath.toAbsolutePath(),
                    latestRelease.tagName()
            );
        }
    }

    private void ensureWorkingDirectories() {
        try {
            Files.createDirectories(LUMINOL_UPDATE_DIR);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Lophine auto update directory", e);
        }
    }

    private @Nullable ReleaseAssetInfo getLatestCompatibleRelease(String mcVersion, @Nullable String gitBranch) {
        final JsonArray releases = requestJsonArray(GITHUB_API_BASE + "/releases?per_page=100");
        if (releases == null) {
            return null;
        }

        ReleaseAssetInfo fallbackMatch = null;
        for (JsonElement element : releases) {
            if (!element.isJsonObject()) {
                continue;
            }

            final ReleaseAssetInfo releaseInfo = parseReleaseInfo(element.getAsJsonObject());
            if (releaseInfo == null) {
                continue;
            }

            if (!releaseInfo.tagName().startsWith(mcVersion + "-")) {
                continue;
            }

            if (releaseInfo.prerelease() && !AutoUpdateConfig.allowPrerelease) {
                continue;
            }

            if (gitBranch != null && gitBranch.equals(releaseInfo.targetCommitish())) {
                return releaseInfo;
            }

            if (fallbackMatch == null) {
                fallbackMatch = releaseInfo;
            }
        }

        return fallbackMatch;
    }

    private @Nullable ReleaseAssetInfo parseReleaseInfo(JsonObject releaseObject) {
        if (releaseObject.get("draft").getAsBoolean()) {
            return null;
        }

        final String tagName = releaseObject.get("tag_name").getAsString();
        final int hashSeparatorIndex = tagName.lastIndexOf('-');
        if (hashSeparatorIndex < 0 || hashSeparatorIndex == tagName.length() - 1) {
            return null;
        }

        final JsonArray assets = releaseObject.getAsJsonArray("assets");
        if (assets == null || assets.isEmpty()) {
            return null;
        }

        for (JsonElement assetElement : assets) {
            if (!assetElement.isJsonObject()) {
                continue;
            }

            final JsonObject assetObject = assetElement.getAsJsonObject();
            final String assetName = assetObject.get("name").getAsString();
            if (!assetName.endsWith(".jar")) {
                continue;
            }

            final String digest = assetObject.has("digest") && !assetObject.get("digest").isJsonNull()
                    ? assetObject.get("digest").getAsString()
                    : "";
            final String sha256 = normalizeSha256Digest(digest);
            if (sha256 == null) {
                LOGGER.warn("Skipping release {} because asset {} does not expose a SHA-256 digest.", tagName, assetName);
                continue;
            }

            return new ReleaseAssetInfo(
                    tagName,
                    tagName.substring(hashSeparatorIndex + 1),
                    releaseObject.get("target_commitish").getAsString(),
                    releaseObject.get("prerelease").getAsBoolean(),
                    assetName,
                    sha256,
                    assetObject.get("browser_download_url").getAsString()
            );
        }

        return null;
    }

    private @Nullable String normalizeSha256Digest(String digest) {
        if (digest == null || digest.isBlank()) {
            return null;
        }

        final String normalized = digest.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256:")) {
            return normalized.substring("sha256:".length());
        }

        return normalized;
    }

    private UpdateStatus compareReleaseWithCurrentBuild(String releaseGitHash, String currentGitHash) {
        if (releaseGitHash.equalsIgnoreCase(currentGitHash)) {
            return UpdateStatus.UP_TO_DATE;
        }

        final JsonObject compareObject = requestJsonObject(
                GITHUB_API_BASE + "/compare/" + releaseGitHash + "..." + currentGitHash
        );
        if (compareObject == null || !compareObject.has("status")) {
            return UpdateStatus.UNKNOWN;
        }

        return switch (compareObject.get("status").getAsString().toLowerCase(Locale.ROOT)) {
            case "identical" -> UpdateStatus.UP_TO_DATE;
            case "behind" -> UpdateStatus.NEEDS_UPDATE;
            case "ahead", "diverged" -> UpdateStatus.CURRENT_BUILD_AHEAD_OR_DIVERGED;
            default -> UpdateStatus.UNKNOWN;
        };
    }

    private @NotNull Path downloadAndStageRelease(ReleaseAssetInfo releaseInfo) {
        final Path tempPath = LUMINOL_UPDATE_DIR.resolve(releaseInfo.assetName() + ".cache");
        final Path stagedPath = LUMINOL_UPDATE_DIR.resolve(releaseInfo.assetName());
        final Path backupPath = LUMINOL_UPDATE_DIR.resolve(releaseInfo.assetName() + ".old");

        try {
            Files.deleteIfExists(tempPath);

            try (
                    ReadableByteChannel source = Channels.newChannel(openConnection(releaseInfo.downloadUrl()).getInputStream());
                    FileChannel fileChannel = FileChannel.open(tempPath, CREATE, WRITE, TRUNCATE_EXISTING)
            ) {
                fileChannel.transferFrom(source, 0, Long.MAX_VALUE);
            }

            if (!isFileValid(tempPath, releaseInfo.sha256())) {
                Files.deleteIfExists(tempPath);
                throw new IllegalStateException("SHA-256 check failed for " + releaseInfo.assetName());
            }

            Util.safeReplaceFile(stagedPath, tempPath, backupPath);
            return stagedPath;
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException("Failed to download release " + releaseInfo.tagName(), e);
        }
    }

    private @NotNull Path tryApplyTargetJar(Path stagedJar) {
        if (AutoUpdateConfig.targetJarPath.isBlank()) {
            return stagedJar;
        }

        final Path targetJar = Path.of(AutoUpdateConfig.targetJarPath).toAbsolutePath().normalize();
        final Path targetParent = targetJar.getParent();
        final Path tempTarget = LUMINOL_UPDATE_DIR.resolve(targetJar.getFileName().toString() + ".apply.cache");
        final Path backupTarget = targetJar.resolveSibling(targetJar.getFileName().toString() + ".old");

        try {
            if (targetParent != null) {
                Files.createDirectories(targetParent);
            }

            Files.deleteIfExists(tempTarget);
            Files.copy(stagedJar, tempTarget);

            if (!Util.safeReplaceOrMoveFile(targetJar, tempTarget, backupTarget, false)) {
                Files.deleteIfExists(tempTarget);
                LOGGER.warn("Failed to replace configured target jar {}. Keeping the staged update instead.", targetJar);
                return stagedJar;
            }

            return targetJar;
        } catch (IOException e) {
            throw new RuntimeException("Failed to apply update to target jar " + targetJar, e);
        }
    }

    private void writeCorePath(Path path) {
        try (BufferedWriter writer = Files.newBufferedWriter(CORE_PATH_FILE, StandardCharsets.UTF_8, CREATE, WRITE, TRUNCATE_EXISTING)) {
            writer.write(path.toAbsolutePath().normalize().toString());
        } catch (IOException e) {
            LOGGER.warn("Failed to write auto-update core path file: {}", CORE_PATH_FILE, e);
        }
    }

    private void writeLatestPath(Path path) {
        try (BufferedWriter writer = Files.newBufferedWriter(LATEST_PATH_FILE, StandardCharsets.UTF_8, CREATE, WRITE, TRUNCATE_EXISTING)) {
            writer.write(path.toAbsolutePath().normalize().toString());
        } catch (IOException e) {
            LOGGER.warn("Failed to write latest update path file: {}", LATEST_PATH_FILE, e);
        }
    }

    private boolean isFileValid(Path file, String expectedHash) {
        try (FileInputStream inputStream = new FileInputStream(file.toFile())) {
            final byte[] buffer = new byte[8192];
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");

            for (int numRead; (numRead = inputStream.read(buffer)) > 0; ) {
                digest.update(buffer, 0, numRead);
            }

            return toHexString(digest.digest()).equalsIgnoreCase(expectedHash);
        } catch (Exception e) {
            LOGGER.warn("Failed to validate file {}", file, e);
            return false;
        }
    }

    private @NotNull String toHexString(byte @NotNull [] bytes) {
        final StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private @Nullable JsonArray requestJsonArray(String url) {
        try {
            final JsonElement element = requestJsonElement(url);
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
        } catch (Exception e) {
            LOGGER.warn("Failed to request JSON array from {}", url, e);
            return null;
        }
    }

    private @Nullable JsonObject requestJsonObject(String url) {
        try {
            final JsonElement element = requestJsonElement(url);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            LOGGER.warn("Failed to request JSON object from {}", url, e);
            return null;
        }
    }

    private @Nullable JsonElement requestJsonElement(String url) throws IOException, URISyntaxException {
        final HttpURLConnection connection = openConnection(url);

        final int responseCode = connection.getResponseCode();
        if (responseCode >= 400) {
            LOGGER.warn("GitHub API request failed with status {} for {}", responseCode, url);
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            return GSON.fromJson(reader, JsonElement.class);
        } catch (JsonSyntaxException e) {
            LOGGER.warn("Failed to parse GitHub API response from {}", url, e);
            return null;
        }
    }

    private @NotNull HttpURLConnection openConnection(String url) throws IOException, URISyntaxException {
        final HttpURLConnection connection = (HttpURLConnection) new URI(url).toURL().openConnection();
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Lophine Auto Update");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        return connection;
    }

    private enum UpdateStatus {
        UP_TO_DATE,
        NEEDS_UPDATE,
        CURRENT_BUILD_AHEAD_OR_DIVERGED,
        UNKNOWN
    }

    private record ReleaseAssetInfo(
            String tagName,
            String releaseCommitHash,
            String targetCommitish,
            boolean prerelease,
            String assetName,
            String sha256,
            String downloadUrl
    ) {
    }
}
