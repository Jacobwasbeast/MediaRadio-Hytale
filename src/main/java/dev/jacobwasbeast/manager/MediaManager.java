package dev.jacobwasbeast.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetLoadResult;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import dev.jacobwasbeast.MediaRadioPlugin;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Collections;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.security.MessageDigest;
import java.util.logging.Level;
import it.unimi.dsi.fastutil.booleans.BooleanObjectPair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

public class MediaManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String RUNTIME_PACK_NAME = "MediaRadioRuntime";
    private static final String RUNTIME_ASSETS_DIR = "media_radio_assets";
    private static final String STORAGE_DIR = "songs";

    private final MediaRadioPlugin plugin;
    private final Path runtimeAssetsPath;
    private final Path commonAudioPath;
    private final Path serverSoundEventsPath;
    private final Path thumbnailPath;
    private final Path storagePath;
    private final Path songsIndexFile;

    private final Map<String, StoredSong> storedSongs = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<MediaInfo>> inFlightRequests = new ConcurrentHashMap<>();

    public MediaManager(MediaRadioPlugin plugin) {
        this.plugin = plugin;
        Path baseDir = MediaRadioPlugin.resolveRuntimeBasePath();
        this.runtimeAssetsPath = baseDir.resolve(RUNTIME_ASSETS_DIR).toAbsolutePath();
        this.storagePath = baseDir.resolve(STORAGE_DIR).toAbsolutePath();
        this.songsIndexFile = storagePath.resolve("song_index.json");
        // Pack structure with Common/Server separation matching Vanilla
        // Audio files: Common/Sounds/media_radio/<path>
        this.commonAudioPath = runtimeAssetsPath.resolve("Common/Sounds/media_radio");
        // SoundEvent definitions: Server/Audio/SoundEvents/
        this.serverSoundEventsPath = runtimeAssetsPath.resolve("Server/Audio/SoundEvents");
        this.thumbnailPath = runtimeAssetsPath.resolve("Common/UI/Custom/Pages/MediaRadio/Thumbs");
    }

    public void init() {
        try {
            cleanupRuntimeFolders();
            ensureDirectories();
            loadSongIndex();
            registerRuntimePack();
            logExternalToolStatus();
            plugin.getLogger().at(Level.INFO).log("MediaManager init completed successfully.");
        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to initialize MediaManager");
        }
    }

    private void ensureDirectories() throws IOException {
        Files.createDirectories(commonAudioPath);
        Files.createDirectories(serverSoundEventsPath);
        Files.createDirectories(thumbnailPath);
        Files.createDirectories(storagePath);
        plugin.getLogger().at(Level.INFO).log("Ensured directories exist at: %s", runtimeAssetsPath);
    }

    private void cleanupRuntimeFolders() {
        deleteDirectory(commonAudioPath);
        deleteDirectory(serverSoundEventsPath);
    }

    private void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(this::deleteFile);
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to delete directory %s", dir);
        }
    }

    private void registerRuntimePack() {
        try {
            // Create minimal manifest
            PluginManifest manifest = PluginManifest.CoreBuilder.corePlugin(MediaRadioPlugin.class)
                    .description("Runtime assets for Media Radio")
                    .build();
            manifest.setName("MediaRadioRuntime");
            manifest.setVersion(Semver.fromString("1.0.0"));

            // Write manifest to disk (required by AssetModule?) - actually AssetModule
            // takes the manifest object directly
            // But we should place a manifest.json there for completeness if we were a real
            // pack

            plugin.getLogger().at(Level.INFO).log("Registering runtime asset pack at: %s", runtimeAssetsPath);
            AssetModule.get().registerPack(RUNTIME_PACK_NAME, runtimeAssetsPath, manifest);

        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to register runtime asset pack");
        }
    }

    private void loadSongIndex() {
        if (!Files.exists(songsIndexFile)) {
            return;
        }
        try (java.io.Reader reader = Files.newBufferedReader(songsIndexFile)) {
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, StoredSong>>() {
            }.getType();
            Map<String, StoredSong> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                storedSongs.putAll(loaded);
            }
            plugin.getLogger().at(Level.INFO).log("Loaded %d stored songs from %s", storedSongs.size(), songsIndexFile);
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to load song index");
        }
    }

    private void saveSongIndex() {
        try (Writer writer = Files.newBufferedWriter(songsIndexFile)) {
            GSON.toJson(storedSongs, writer);
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to save song index");
        }
    }

    public CompletableFuture<MediaInfo> requestMedia(String url) {
        String normalizedUrl = normalizeUrl(url);
        String trackId = getTrackIdForUrl(normalizedUrl);

        // If we have the audio stored, we can re-resolve metadata for freshness.

        plugin.getLogger().at(Level.INFO).log("Processing media request: %s -> %s", normalizedUrl, trackId);
        return inFlightRequests.computeIfAbsent(trackId, key -> CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Fetch Metadata first
                MediaInfo metadata = resolveMetadata(normalizedUrl, trackId);

                // 2. Ensure the full audio is downloaded to storage
                Path storedAudio = storagePath.resolve(trackId + ".ogg");
                if (!Files.exists(storedAudio)) {
                    downloadMedia(normalizedUrl, trackId);
                }
                StoredSong stored = storedSongs.get(trackId);
                if (stored == null) {
                    storedSongs.put(trackId, new StoredSong(trackId, normalizedUrl, metadata.title, metadata.artist,
                            metadata.duration));
                    saveSongIndex();
                }
                String thumbnailAssetPath = ensureThumbnail(normalizedUrl, trackId);
                return new MediaInfo(trackId, normalizedUrl, metadata.title, metadata.artist, metadata.thumbnailUrl,
                        metadata.duration, 0, thumbnailAssetPath);
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to process media request: %s", message);
                throw new RuntimeException(e);
            }
        }).whenComplete((info, err) -> inFlightRequests.remove(trackId)));
    }

    private MediaInfo resolveMetadata(String url, String trackId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                requireYtDlpCommand(),
                "--dump-json",
                "--no-playlist",
                url);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("yt-dlp not available for metadata fetch. Run /setup_radio.", e);
        }
        StringBuilder jsonOutput = new StringBuilder();
        try (java.util.Scanner s = new java.util.Scanner(process.getInputStream())) {
            while (s.hasNextLine()) {
                jsonOutput.append(s.nextLine());
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("yt-dlp metadata fetch failed code " + exitCode);
        }

        JsonObject root = com.google.gson.JsonParser.parseString(jsonOutput.toString()).getAsJsonObject();
        String title = root.has("title") ? root.get("title").getAsString() : "Unknown Title";
        String uploader = root.has("uploader") ? root.get("uploader").getAsString() : "Unknown Artist";
        String thumbnail = root.has("thumbnail") ? root.get("thumbnail").getAsString() : "";
        long duration = root.has("duration") ? root.get("duration").getAsLong() : 0;

        return new MediaInfo(trackId, url, title, uploader, thumbnail, duration, 0, "");
    }

    private void downloadMedia(String url, String trackId) throws Exception {
        // Don't include extension in -o template - yt-dlp adds it automatically with
        // --audio-format
        Path outputPathBase = storagePath.resolve(trackId);
        Path expectedOutputPath = storagePath.resolve(trackId + ".ogg");

        // Command: yt-dlp -x --audio-format vorbis --audio-quality 0 -o "trackId" "url"
        // yt-dlp will create "trackId.ogg" after audio extraction
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(requireYtDlpCommand());
        command.add("-x");
        command.add("--audio-format");
        command.add("vorbis");
        command.add("--audio-quality");
        command.add("0");

        String ffmpegCmd = resolveFfmpegCommand();
        if (ffmpegCmd != null && !ffmpegCmd.equals("ffmpeg")) {
            // If it's a path to the executable, we need to pass the directory
            Path ffmpegPath = Paths.get(ffmpegCmd);
            if (Files.isRegularFile(ffmpegPath)) {
                // Pass the directory containing ffmpeg/ffprobe
                command.add("--ffmpeg-location");
                command.add(ffmpegPath.getParent().toString());
            }
        }

        command.add("-o");
        command.add(outputPathBase.toString());
        command.add(url);

        plugin.getLogger().at(Level.INFO).log("Executing yt-dlp command: %s", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);

        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("yt-dlp not available for media download. Run /setup_radio.", e);
        }

        // Read output to log
        try (java.util.Scanner s = new java.util.Scanner(process.getInputStream()).useDelimiter("\\A")) {
            while (s.hasNext()) {
                plugin.getLogger().at(Level.INFO).log("[yt-dlp] %s", s.next());
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("yt-dlp exited with code " + exitCode);
        }

        // yt-dlp creates <name>.ogg when using --audio-format vorbis
        if (!Files.exists(expectedOutputPath)) {
            throw new RuntimeException("Output file not found: " + expectedOutputPath);
        }
    }

    private int splitAudio(String trackId, double segmentDuration) throws Exception {
        Path inputFile = storagePath.resolve(trackId + ".ogg");
        // Output pattern: trackId_Chunk_000.ogg
        String outputPattern = commonAudioPath.resolve(trackId + "_Chunk_%03d.ogg").toString();

        plugin.getLogger().at(Level.INFO).log("Splitting audio %s into %.1fms chunks...", trackId,
                segmentDuration * 1000.0);

        String ffmpegCommand = resolveFfmpegCommand();
        if (ffmpegCommand == null) {
            throw new RuntimeException(
                    "ffmpeg not available. Run /setup_radio and place it at " + getExpectedFfmpegPath());
        }

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegCommand,
                "-i", inputFile.toString(),
                "-map", "0:a:0",
                "-f", "segment",
                "-segment_time", String.valueOf(segmentDuration),
                "-reset_timestamps", "1",
                "-ac", "1",
                "-c:a", "libvorbis",
                "-q:a", "4",
                outputPattern);

        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("ffmpeg not available for audio split. Is it installed and on PATH?", e);
        }

        // Read output
        try (java.util.Scanner s = new java.util.Scanner(process.getInputStream()).useDelimiter("\\A")) {
            while (s.hasNext()) {
                // plugin.getLogger().at(Level.INFO).log("[ffmpeg] %s", s.next()); // Too noisy
                s.next();
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("ffmpeg exited with code " + exitCode);
        }

        // Count generated chunks
        int chunkCount = 0;
        while (Files.exists(commonAudioPath.resolve(String.format("%s_Chunk_%03d.ogg", trackId, chunkCount)))) {
            Path chunkPath = commonAudioPath.resolve(String.format("%s_Chunk_%03d.ogg", trackId, chunkCount));
            // Touch file to trigger watcher
            try {
                Files.setLastModifiedTime(chunkPath,
                        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException ignored) {
            }
            chunkCount++;
        }

        // Touch directory too
        try {
            Files.setLastModifiedTime(commonAudioPath,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }

        // Give watcher a moment
        Thread.sleep(1000);

        plugin.getLogger().at(Level.INFO).log("Split complete. Generated %d chunks.", chunkCount);
        return chunkCount;
    }

    private void createSoundEvents(String trackId, int chunkCount) {
        // Create SoundEvent for each chunk
        for (int i = 0; i < chunkCount; i++) {
            String chunkTrackId = String.format("%s_Chunk_%03d", trackId, i);
            Path jsonPath = serverSoundEventsPath.resolve(chunkTrackId + ".json");

            // Map file path: Sounds/media_radio/trackId_Chunk_000.ogg
            String soundFilePath = String.format("Sounds/media_radio/%s_Chunk_%03d.ogg", trackId, i);

            Map<String, Object> layer = new HashMap<>();
            layer.put("Files", Collections.singletonList(soundFilePath));
            layer.put("Volume", 1.0); // Full volume, controlled by category/distance

            Map<String, Object> soundEvent = new HashMap<>();
            soundEvent.put("Layers", Collections.singletonList(layer));
            soundEvent.put("Volume", 0.0);
            soundEvent.put("Pitch", 0.0);
            soundEvent.put("MaxDistance", 60); // 60 blocks audible distance
            soundEvent.put("StartAttenuationDistance", 10);
            soundEvent.put("Parent", "SFX_Attn_Quiet");

            try (Writer writer = Files.newBufferedWriter(jsonPath)) {
                GSON.toJson(soundEvent, writer);
            } catch (IOException e) {
                plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to write SoundEvent for chunk %d", i);
            }
            touchSoundEvent(jsonPath);
            notifyAssetChange(jsonPath);
        }
        touchSoundEventDir();
        notifyAssetChange(serverSoundEventsPath);
        plugin.getLogger().at(Level.INFO).log("Created %d SoundEvent configs for %s", chunkCount, trackId);
    }

    private void registerCommonSoundAssets(String trackId, int chunkCount) {
        CommonAssetModule commonAssetModule = CommonAssetModule.get();
        if (commonAssetModule == null) {
            plugin.getLogger().at(Level.WARNING).log("CommonAssetModule not available; cannot register sound assets.");
            return;
        }

        for (int i = 0; i < chunkCount; i++) {
            String fileName = String.format("%s_Chunk_%03d.ogg", trackId, i);
            Path chunkPath = commonAudioPath.resolve(fileName);
            if (!Files.exists(chunkPath)) {
                continue;
            }
            String assetName = "Sounds/media_radio/" + fileName;
            if (CommonAssetRegistry.hasCommonAsset(assetName)) {
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(chunkPath);
                commonAssetModule.addCommonAsset(RUNTIME_PACK_NAME, new FileCommonAsset(chunkPath, assetName, bytes));
            } catch (IOException e) {
                plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to register sound asset %s", assetName);
            }
        }
    }

    private void loadSoundEventAssets(String trackId, int chunkCount) {
        if (chunkCount <= 0) {
            return;
        }
        java.util.List<Path> paths = new java.util.ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            String chunkTrackId = String.format("%s_Chunk_%03d", trackId, i);
            Path jsonPath = serverSoundEventsPath.resolve(chunkTrackId + ".json");
            if (Files.exists(jsonPath)) {
                paths.add(jsonPath);
            }
        }
        if (paths.isEmpty()) {
            return;
        }
        try {
            AssetLoadResult<String, SoundEvent> result = SoundEvent.getAssetStore()
                    .loadAssetsFromPaths(RUNTIME_PACK_NAME, paths, AssetUpdateQuery.DEFAULT, true);
            if (result.hasFailed()) {
                plugin.getLogger().at(Level.WARNING).log("Some SoundEvent assets failed to load for %s", trackId);
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to load SoundEvent assets for %s", trackId);
        }
    }

    private void touchSoundEvent(Path jsonPath) {
        try {
            Files.setLastModifiedTime(jsonPath,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
    }

    private void touchSoundEventDir() {
        try {
            Files.setLastModifiedTime(serverSoundEventsPath,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
    }

    private void notifyAssetChange(Path path) {
        var assetMonitor = AssetModule.get().getAssetMonitor();
        if (assetMonitor != null) {
            assetMonitor.markChanged(path);
        }
    }

    public CompletableFuture<Void> playSound(MediaInfo mediaInfo, PlayerRef playerRef, Store<EntityStore> store) {
        if (playerRef == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        prepareRuntimeAssetsAsync(mediaInfo, 750).thenAccept(totalChunks -> {
            if (totalChunks <= 0) {
                plugin.getLogger().at(Level.WARNING).log("No chunks available for %s", mediaInfo.trackId);
                result.completeExceptionally(new RuntimeException("Failed to prepare media assets (0 chunks)"));
                return;
            }
            store.getExternalData().getWorld().execute(() -> {
                MediaInfo updated = withChunkCount(mediaInfo, totalChunks);
                plugin.getPlaybackManager().playForPlayer(updated, playerRef, totalChunks, 750, store);
                result.complete(null);
            });
        }).exceptionally(ex -> {
            result.completeExceptionally(ex);
            return null;
        });
        return result;
    }

    public CompletableFuture<Void> playSoundAtBlock(MediaInfo mediaInfo, Vector3i blockPos, int chunkDurationMs,
            Store<EntityStore> store) {
        if (mediaInfo == null || blockPos == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        prepareRuntimeAssetsAsync(mediaInfo, chunkDurationMs).thenAccept(totalChunks -> {
            if (totalChunks <= 0) {
                plugin.getLogger().at(Level.WARNING).log("No chunks available for %s", mediaInfo.trackId);
                result.completeExceptionally(new RuntimeException("Failed to prepare media assets (0 chunks)"));
                return;
            }
            store.getExternalData().getWorld().execute(() -> {
                MediaInfo updated = withChunkCount(mediaInfo, totalChunks);
                plugin.getPlaybackManager().playAtBlock(updated, blockPos, chunkDurationMs, store);
                result.complete(null);
            });
        }).exceptionally(ex -> {
            result.completeExceptionally(ex);
            return null;
        });
        return result;
    }

    public int getChunkCount(String trackId) {
        return resolveChunkCount(trackId);
    }

    private CompletableFuture<Integer> prepareRuntimeAssetsAsync(MediaInfo mediaInfo, int chunkDurationMs) {
        return CompletableFuture.supplyAsync(
                () -> ensureRuntimeAssets(mediaInfo, chunkDurationMs),
                com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR);
    }

    private MediaInfo withChunkCount(MediaInfo mediaInfo, int chunkCount) {
        return new MediaInfo(
                mediaInfo.trackId,
                mediaInfo.url,
                mediaInfo.title,
                mediaInfo.artist,
                mediaInfo.thumbnailUrl,
                mediaInfo.duration,
                chunkCount,
                mediaInfo.thumbnailAssetPath);
    }

    private int ensureRuntimeAssets(MediaInfo mediaInfo, int chunkDurationMs) {
        if (mediaInfo == null) {
            return 0;
        }
        String trackId = mediaInfo.trackId;
        Path storedAudio = storagePath.resolve(trackId + ".ogg");
        if (!Files.exists(storedAudio)) {
            try {
                downloadMedia(mediaInfo.url, trackId);
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e)
                        .log("Failed to download audio for %s", trackId);
                return 0;
            }
        }

        int chunkCount = resolveChunkCount(trackId);
        if (chunkCount <= 0) {
            try {
                double seconds = Math.max(0.1, chunkDurationMs / 1000.0);
                chunkCount = splitAudio(trackId, seconds);
                if (chunkCount > 0) {
                    registerCommonSoundAssets(trackId, chunkCount);
                    createSoundEvents(trackId, chunkCount);
                    loadSoundEventAssets(trackId, chunkCount);
                }
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e)
                        .log("Failed to prepare runtime assets for %s", trackId);
                return 0;
            }
        } else {
            String firstChunkId = String.format("%s_Chunk_%03d", trackId, 0);
            if (SoundEvent.getAssetMap().getIndex(firstChunkId) < 0) {
                registerCommonSoundAssets(trackId, chunkCount);
                if (!Files.exists(serverSoundEventsPath.resolve(firstChunkId + ".json"))) {
                    createSoundEvents(trackId, chunkCount);
                }
                loadSoundEventAssets(trackId, chunkCount);
            }
        }
        return chunkCount;
    }

    public String getTrackIdForUrl(String url) {
        String normalizedUrl = normalizeUrl(url);
        String hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(normalizedUrl.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (int i = 0; i < encodedhash.length; i++) {
                String hex = Integer.toHexString(0xff & encodedhash[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            hash = hexString.toString().substring(0, 16);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }

        return "Track_" + capitalizeFirst(hash);
    }

    private String capitalizeFirst(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (Character.isUpperCase(first)) {
            return value;
        }
        return Character.toUpperCase(first) + value.substring(1);
    }

    public String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        try {
            URI uri = new URI(trimmed);
            String host = uri.getHost();
            if (host == null) {
                return trimmed;
            }
            String lowerHost = host.toLowerCase();
            String path = uri.getPath() != null ? uri.getPath() : "";
            if (lowerHost.contains("youtube.com")) {
                if ("/watch".equals(path)) {
                    String videoId = getQueryParam(uri.getRawQuery(), "v");
                    if (!videoId.isEmpty()) {
                        return "https://youtu.be/" + videoId;
                    }
                } else if (path.startsWith("/shorts/")) {
                    String videoId = extractPathSegment(path, "/shorts/");
                    if (!videoId.isEmpty()) {
                        return "https://youtu.be/" + videoId;
                    }
                } else if (path.startsWith("/embed/")) {
                    String videoId = extractPathSegment(path, "/embed/");
                    if (!videoId.isEmpty()) {
                        return "https://youtu.be/" + videoId;
                    }
                }
            } else if (lowerHost.contains("youtu.be")) {
                String videoId = extractPathSegment(path, "/");
                if (!videoId.isEmpty()) {
                    return "https://youtu.be/" + videoId;
                }
            }
        } catch (Exception ignored) {
        }
        return trimmed;
    }

    private String extractPathSegment(String path, String prefix) {
        if (path == null) {
            return "";
        }
        String trimmed = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        int slash = trimmed.indexOf('/');
        if (slash >= 0) {
            trimmed = trimmed.substring(0, slash);
        }
        return trimmed;
    }

    private String getQueryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        String[] parts = rawQuery.split("&");
        for (String part : parts) {
            int eq = part.indexOf('=');
            String k = eq >= 0 ? part.substring(0, eq) : part;
            if (!key.equals(k)) {
                continue;
            }
            String value = eq >= 0 ? part.substring(eq + 1) : "";
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return value;
            }
        }
        return "";
    }

    public String getThumbnailAssetPath(String trackId) {
        return "UI/Custom/Pages/MediaRadio/Thumbs/" + trackId + ".png";
    }

    public boolean hasThumbnail(String trackId) {
        return Files.exists(thumbnailPath.resolve(trackId + ".png"));
    }

    public CompletableFuture<String> ensureThumbnailAsync(String url, String trackId) {
        return CompletableFuture.supplyAsync(() -> ensureThumbnail(url, trackId));
    }

    public void warmThumbnails(MediaLibrary library) {
        if (library == null) {
            return;
        }
        for (MediaLibrary.SavedSong song : library.getAllSongs()) {
            if (song.url == null || song.url.isEmpty()) {
                continue;
            }
            String trackId = song.trackId != null ? song.trackId : getTrackIdForUrl(song.url);
            if (hasThumbnail(trackId)) {
                if (song.thumbnailAssetPath == null || song.thumbnailAssetPath.isEmpty()) {
                    song.trackId = trackId;
                    song.thumbnailAssetPath = getThumbnailAssetPath(trackId);
                    library.save();
                }
                continue;
            }

            ensureThumbnailAsync(song.url, trackId).thenAccept(assetPath -> {
                if (assetPath == null || assetPath.isEmpty()) {
                    return;
                }
                song.trackId = trackId;
                song.thumbnailAssetPath = assetPath;
                library.save();
            });
        }
    }

    private String ensureThumbnail(String url, String trackId) {
        Path pngPath = thumbnailPath.resolve(trackId + ".png");
        if (Files.exists(pngPath)) {
            registerThumbnailAsset(pngPath, getThumbnailAssetPath(trackId));
            return getThumbnailAssetPath(trackId);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    requireYtDlpCommand(),
                    "--write-thumbnail",
                    "--skip-download",
                    "-o", thumbnailPath.resolve(trackId).toString(),
                    url);
            pb.redirectErrorStream(true);
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                plugin.getLogger().at(Level.WARNING).withCause(e)
                        .log("yt-dlp not available for thumbnail download. Run /setup_radio.");
                return "";
            }

            try (java.util.Scanner s = new java.util.Scanner(process.getInputStream()).useDelimiter("\\A")) {
                while (s.hasNext()) {
                    s.next();
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                plugin.getLogger().at(Level.WARNING).log("yt-dlp thumbnail download failed code %d", exitCode);
                return "";
            }

            Path downloaded = findThumbnailFile(trackId);
            if (downloaded == null) {
                plugin.getLogger().at(Level.WARNING).log("Thumbnail file not found for %s", trackId);
                return "";
            }

            if (!downloaded.getFileName().toString().endsWith(".png")) {
                String ffmpegCommand = resolveFfmpegCommand();
                if (ffmpegCommand == null) {
                    plugin.getLogger().at(Level.WARNING).log("ffmpeg not available for thumbnail conversion.");
                    return "";
                }
                ProcessBuilder ffmpeg = new ProcessBuilder(
                        ffmpegCommand,
                        "-y",
                        "-i", downloaded.toString(),
                        pngPath.toString());
                ffmpeg.redirectErrorStream(true);
                Process ffmpegProcess;
                try {
                    ffmpegProcess = ffmpeg.start();
                } catch (IOException e) {
                    plugin.getLogger().at(Level.WARNING).withCause(e)
                            .log("ffmpeg not available for thumbnail conversion.");
                    return "";
                }
                try (java.util.Scanner s = new java.util.Scanner(ffmpegProcess.getInputStream()).useDelimiter("\\A")) {
                    while (s.hasNext()) {
                        s.next();
                    }
                }
                int ffmpegExit = ffmpegProcess.waitFor();
                if (ffmpegExit != 0) {
                    plugin.getLogger().at(Level.WARNING).log("ffmpeg thumbnail conversion failed code %d", ffmpegExit);
                    return "";
                }
            } else if (!downloaded.equals(pngPath)) {
                Files.move(downloaded, pngPath);
            }

            touchThumbnail(pngPath);
            notifyAssetChange(pngPath);
            notifyAssetChange(thumbnailPath);
            registerThumbnailAsset(pngPath, getThumbnailAssetPath(trackId));
            return getThumbnailAssetPath(trackId);
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to download thumbnail for %s", trackId);
            return "";
        }
    }

    private Path findThumbnailFile(String trackId) throws IOException {
        try (var stream = Files.newDirectoryStream(thumbnailPath, trackId + ".*")) {
            for (Path path : stream) {
                return path;
            }
        }
        return null;
    }

    private void touchThumbnail(Path pngPath) {
        try {
            Files.setLastModifiedTime(pngPath, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
        try {
            Files.setLastModifiedTime(thumbnailPath,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
    }

    private void registerThumbnailAsset(Path pngPath, String assetPath) {
        CommonAssetModule commonAssetModule = CommonAssetModule.get();
        if (commonAssetModule == null || assetPath == null || assetPath.isEmpty()) {
            return;
        }
        if (CommonAssetRegistry.hasCommonAsset(assetPath)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(pngPath);
            commonAssetModule.addCommonAsset(RUNTIME_PACK_NAME, new FileCommonAsset(pngPath, assetPath, bytes));
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to register thumbnail asset %s", assetPath);
        }
    }

    private int resolveChunkCount(String trackId) {
        int chunkCount = 0;
        while (Files.exists(commonAudioPath.resolve(String.format("%s_Chunk_%03d.ogg", trackId, chunkCount)))) {
            chunkCount++;
        }
        return chunkCount;
    }

    public CompletableFuture<Void> deleteMediaForUrl(String url) {
        if (url == null || url.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var playerLibrary = plugin.getMediaLibrary();
        if (playerLibrary != null) {
            boolean stillReferenced = playerLibrary.getAllSongs()
                    .stream()
                    .anyMatch(song -> url.equals(song.url));
            if (stillReferenced) {
                return CompletableFuture.completedFuture(null);
            }
        }
        String trackId = getTrackIdForUrl(url);
        var playbackManager = plugin.getPlaybackManager();
        if (playbackManager != null) {
            playbackManager.stopAllForTrackId(trackId);
        }
        return CompletableFuture.runAsync(() -> {
            cleanupRuntimeAssets(trackId);
        }, com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR);
    }

    public void cleanupRuntimeAssets(String trackId) {
        int chunkCount = resolveChunkCount(trackId);
        for (int i = 0; i < chunkCount; i++) {
            String fileName = String.format("%s_Chunk_%03d.ogg", trackId, i);
            Path chunkPath = commonAudioPath.resolve(fileName);
            deleteCommonAsset("Sounds/media_radio/" + fileName, chunkPath);
        }

        for (int i = 0; i < chunkCount; i++) {
            String chunkTrackId = String.format("%s_Chunk_%03d", trackId, i);
            Path jsonPath = serverSoundEventsPath.resolve(chunkTrackId + ".json");
            removeSoundEventAsset(jsonPath);
            deleteFile(jsonPath);
        }
    }

    public void cleanupRuntimeAssetsAsync(String trackId) {
        CompletableFuture.runAsync(
                () -> cleanupRuntimeAssets(trackId),
                com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR);
    }

    private void removeSoundEventAsset(Path jsonPath) {
        if (!Files.exists(jsonPath)) {
            return;
        }
        try {
            SoundEvent.getAssetStore().removeAssetWithPath(jsonPath, AssetUpdateQuery.DEFAULT);
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to remove SoundEvent asset %s", jsonPath);
        }
    }

    private void deleteCommonAsset(String assetName, Path filePath) {
        CommonAssetModule commonAssetModule = CommonAssetModule.get();
        if (commonAssetModule != null && assetName != null && !assetName.isEmpty()) {
            BooleanObjectPair<CommonAssetRegistry.PackAsset> removed = CommonAssetRegistry
                    .removeCommonAssetByName(RUNTIME_PACK_NAME, assetName);
            if (removed != null) {
                ObjectArrayList<CommonAssetRegistry.PackAsset> removedAssets = new ObjectArrayList<>();
                removedAssets.add(removed.second());
                commonAssetModule.sendRemoveAssets(removedAssets, true);
            }
        }
        deleteFile(filePath);
    }

    private void deleteFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to delete file %s", path);
        }
    }

    private void logExternalToolStatus() {
        logEnvironmentDiagnostics();
        ensureYtDlpAvailable();
        String ytDlpCommand = resolveYtDlpCommand();
        if (ytDlpCommand != null) {
            logToolStatus(ytDlpCommand, "--version");
        } else {
            plugin.getLogger().at(Level.WARNING).log("yt-dlp not found. Use /setup_radio for setup details.");
        }
        logToolStatus("ffmpeg", "-version");
        if (isYtDlpAvailable() && isFfmpegAvailable()) {
            plugin.getLogger().at(Level.INFO).log("MediaRadio tools ready: yt-dlp + ffmpeg detected.");
        }
    }

    private void logToolStatus(String tool, String versionArg) {
        ProcessBuilder pb = new ProcessBuilder(tool, versionArg);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            String firstLine = "";
            try (java.util.Scanner s = new java.util.Scanner(process.getInputStream())) {
                if (s.hasNextLine()) {
                    firstLine = s.nextLine();
                }
            }
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                plugin.getLogger().at(Level.INFO).log("%s available: %s", tool, firstLine.isEmpty() ? "ok" : firstLine);
            } else {
                plugin.getLogger().at(Level.WARNING).log("%s returned exit %d (%s)", tool, exitCode,
                        firstLine.isEmpty() ? "no output" : firstLine);
            }
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log(
                    "Couldn't find %s on PATH. Did you add it to your system PATH? PATH=%s", tool, getPathEnv());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Interrupted while checking %s", tool);
        }
    }

    private void ensureYtDlpAvailable() {
        resolveYtDlpCommand();
    }

    private boolean isToolAvailable(String tool, String versionArg) {
        ProcessBuilder pb = new ProcessBuilder(tool, versionArg);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String requireYtDlpCommand() {
        String resolved = resolveYtDlpCommand();
        if (resolved == null || resolved.isEmpty()) {
            throw new RuntimeException(
                    "yt-dlp not available. Run /setup_radio and place it at " + getExpectedYtDlpPath());
        }
        return resolved;
    }

    private String resolveYtDlpCommand() {
        if (isToolAvailable("yt-dlp", "--version")) {
            return "yt-dlp";
        }
        Path expected = getExpectedYtDlpPath();
        if (Files.exists(expected)) {
            return expected.toString();
        }
        return null;
    }

    public boolean isYtDlpAvailable() {
        return resolveYtDlpCommand() != null;
    }

    public Path getExpectedYtDlpPath() {
        return runtimeAssetsPath.resolve("bin").resolve(resolveYtDlpAssetName());
    }

    public boolean isFfmpegAvailable() {
        return isToolAvailable("ffmpeg", "-version") || Files.exists(getExpectedFfmpegPath());
    }

    public Path getExpectedFfmpegPath() {
        return runtimeAssetsPath.resolve("bin").resolve(resolveFfmpegAssetName());
    }

    private String resolveYtDlpAssetName() {
        if (isWindows()) {
            return "yt-dlp.exe";
        }
        if (isMac()) {
            return "yt-dlp_macos";
        }
        return "yt-dlp";
    }

    private String resolveFfmpegCommand() {
        if (isToolAvailable("ffmpeg", "-version")) {
            return "ffmpeg";
        }
        Path expected = getExpectedFfmpegPath();
        if (Files.exists(expected)) {
            return expected.toString();
        }
        return null;
    }

    private String resolveFfmpegAssetName() {
        if (isWindows()) {
            return "ffmpeg.exe";
        }
        return "ffmpeg";
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    private boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac");
    }

    private void logEnvironmentDiagnostics() {
        String cwd = Paths.get("").toAbsolutePath().toString();
        String runtimeBase = MediaRadioPlugin.resolveRuntimeBasePath().toString();
        plugin.getLogger().at(Level.INFO).log("MediaRadio env: cwd=%s runtimeBase=%s", cwd, runtimeBase);
        String pathEnv = getPathEnv();
        if (pathEnv != null && !pathEnv.isEmpty()) {
            plugin.getLogger().at(Level.INFO).log("MediaRadio env: PATH=%s", pathEnv);
        } else {
            plugin.getLogger().at(Level.INFO).log("MediaRadio env: PATH is empty or unset");
        }
    }

    private String getPathEnv() {
        String pathEnv = System.getenv("PATH");
        return pathEnv != null ? pathEnv : "";
    }

    public static class StoredSong {
        public String trackId;
        public String url;
        public String title;
        public String artist;
        public long duration;

        public StoredSong() {
        }

        public StoredSong(String trackId, String url, String title, String artist, long duration) {
            this.trackId = trackId;
            this.url = url;
            this.title = title;
            this.artist = artist;
            this.duration = duration;
        }
    }

}
