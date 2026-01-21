package dev.jacobwasbeast.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetLoadResult;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import dev.jacobwasbeast.MediaRadioPlugin;
import dev.jacobwasbeast.manager.MediaLibrary;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Collections;
import java.io.IOException;
import java.io.Writer;
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

    private final MediaRadioPlugin plugin;
    private final Path runtimeAssetsPath;
    private final Path commonAudioPath;
    private final Path serverSoundEventsPath;
    private final Path thumbnailPath;
    private final Path libraryFile;

    private final Map<String, MediaEntry> mediaLibrary = new HashMap<>();
    private final Map<String, CompletableFuture<MediaInfo>> inFlightRequests = new ConcurrentHashMap<>();

    public MediaManager(MediaRadioPlugin plugin) {
        this.plugin = plugin;
        // Server working directory is already "run/", so don't duplicate it
        this.runtimeAssetsPath = Paths.get("media_radio_assets").toAbsolutePath();
        // Pack structure with Common/Server separation matching Vanilla
        // Audio files: Common/Sounds/media_radio/<path>
        this.commonAudioPath = runtimeAssetsPath.resolve("Common/Sounds/media_radio");
        // SoundEvent definitions: Server/Audio/SoundEvents/
        this.serverSoundEventsPath = runtimeAssetsPath.resolve("Server/Audio/SoundEvents");
        this.thumbnailPath = runtimeAssetsPath.resolve("Common/UI/Custom/Pages/MediaRadio/Thumbs");
        this.libraryFile = Paths.get("media_library.json").toAbsolutePath();
    }

    public void init() {
        try {
            ensureDirectories();
            loadLibrary();
            registerRuntimePack();
            plugin.getLogger().at(Level.INFO).log("MediaManager init completed successfully.");
        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to initialize MediaManager");
        }
    }

    private void ensureDirectories() throws IOException {
        Files.createDirectories(commonAudioPath);
        Files.createDirectories(serverSoundEventsPath);
        Files.createDirectories(thumbnailPath);
        plugin.getLogger().at(Level.INFO).log("Ensured directories exist at: %s", runtimeAssetsPath);
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

    private void loadLibrary() {
        if (Files.exists(libraryFile)) {
            try (java.io.Reader reader = Files.newBufferedReader(libraryFile)) {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, MediaEntry>>() {
                }.getType();
                Map<String, MediaEntry> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    mediaLibrary.putAll(loaded);
                }
                plugin.getLogger().at(Level.INFO).log("Loaded media library from %s with %d entries", libraryFile,
                        mediaLibrary.size());
            } catch (Exception e) {
                plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to load media library");
            }
        }
    }

    public CompletableFuture<MediaInfo> requestMedia(String url) {
        String trackId = getTrackIdForUrl(url);

        // If in library, we might have metadata (TODO: Store metadata in
        // mediaLibrary.json too)
        // For now, if we have the audio, we assume we want to re-resolve metadata or
        // just return basic info
        // Let's rely on re-fetching metadata for freshness or assume basic if failed

        plugin.getLogger().at(Level.INFO).log("Processing media request: %s -> %s", url, trackId);
        return inFlightRequests.computeIfAbsent(trackId, key -> CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Fetch Metadata first
                MediaInfo metadata = resolveMetadata(url, trackId);

                // 2. Check if we need to download audio
                // (Simple check: does the splits exist? For now, re-download/check)
                if (!Files.exists(commonAudioPath.resolve(trackId + ".ogg"))) {
                    downloadMedia(url, trackId);
                    int chunkCount = splitAudio(trackId, 1);
                    registerCommonSoundAssets(trackId, chunkCount);
                    createSoundEvents(trackId, chunkCount);
                    loadSoundEventAssets(trackId, chunkCount);
                    updateLibrary(trackId, url, chunkCount);
                }

                int chunkCount = resolveChunkCount(trackId);
                if (chunkCount == 0) {
                    chunkCount = splitAudio(trackId, 1);
                    if (chunkCount > 0) {
                        registerCommonSoundAssets(trackId, chunkCount);
                        createSoundEvents(trackId, chunkCount);
                        loadSoundEventAssets(trackId, chunkCount);
                        updateLibrary(trackId, url, chunkCount);
                    }
                } else if (!mediaLibrary.containsKey(trackId)) {
                    updateLibrary(trackId, url, chunkCount);
                }

                if (chunkCount > 0) {
                    String firstChunkId = String.format("%s_chunk_%03d", trackId, 0);
                    if (SoundEvent.getAssetMap().getIndex(firstChunkId) < 0) {
                        registerCommonSoundAssets(trackId, chunkCount);
                        if (!Files.exists(serverSoundEventsPath.resolve(firstChunkId + ".json"))) {
                            createSoundEvents(trackId, chunkCount);
                        }
                        loadSoundEventAssets(trackId, chunkCount);
                    }
                }
                String thumbnailAssetPath = ensureThumbnail(url, trackId);
                return new MediaInfo(trackId, url, metadata.title, metadata.artist, metadata.thumbnailUrl,
                        metadata.duration, chunkCount, thumbnailAssetPath);
            } catch (Exception e) {
                plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to process media request");
                throw new RuntimeException(e);
            }
        }).whenComplete((info, err) -> inFlightRequests.remove(trackId)));
    }

    private MediaInfo resolveMetadata(String url, String trackId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "yt-dlp",
                "--dump-json",
                "--no-playlist",
                url);

        Process process = pb.start();
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
        Path outputPathBase = commonAudioPath.resolve(trackId);
        Path expectedOutputPath = commonAudioPath.resolve(trackId + ".ogg");

        // Command: yt-dlp -x --audio-format vorbis --audio-quality 0 -o "trackId" "url"
        // yt-dlp will create "trackId.ogg" after audio extraction
        ProcessBuilder pb = new ProcessBuilder(
                "yt-dlp",
                "-x",
                "--audio-format", "vorbis",
                "--audio-quality", "0",
                "-o", outputPathBase.toString(),
                url);

        pb.redirectErrorStream(true);
        Process process = pb.start();

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

    private int splitAudio(String trackId, int segmentDuration) throws Exception {
        Path inputFile = commonAudioPath.resolve(trackId + ".ogg");
        // Output pattern: trackId_chunk_000.ogg
        String outputPattern = commonAudioPath.resolve(trackId + "_chunk_%03d.ogg").toString();

        plugin.getLogger().at(Level.INFO).log("Splitting audio %s into %ds chunks...", trackId, segmentDuration);

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-i", inputFile.toString(),
                "-f", "segment",
                "-segment_time", String.valueOf(segmentDuration),
                "-c", "copy", // Fast split without re-encoding
                outputPattern);

        pb.redirectErrorStream(true);
        Process process = pb.start();

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
        while (Files.exists(commonAudioPath.resolve(String.format("%s_chunk_%03d.ogg", trackId, chunkCount)))) {
            Path chunkPath = commonAudioPath.resolve(String.format("%s_chunk_%03d.ogg", trackId, chunkCount));
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
            String chunkTrackId = String.format("%s_chunk_%03d", trackId, i);
            Path jsonPath = serverSoundEventsPath.resolve(chunkTrackId + ".json");

            // Map file path: Sounds/media_radio/trackId_chunk_000.ogg
            String soundFilePath = String.format("Sounds/media_radio/%s_chunk_%03d.ogg", trackId, i);

            Map<String, Object> layer = new HashMap<>();
            layer.put("Files", Collections.singletonList(soundFilePath));
            layer.put("Volume", 1.0); // Full volume, controlled by category/distance

            Map<String, Object> soundEvent = new HashMap<>();
            soundEvent.put("Layers", Collections.singletonList(layer));
            soundEvent.put("Volume", 0.0);
            soundEvent.put("Pitch", 0.0);
            soundEvent.put("MaxDistance", 60); // 60 blocks audible distance
            soundEvent.put("StartAttenuationDistance", 10);
            soundEvent.put("AudioCategory", "AudioCat_Music");

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
            String fileName = String.format("%s_chunk_%03d.ogg", trackId, i);
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
            String chunkTrackId = String.format("%s_chunk_%03d", trackId, i);
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
            Files.setLastModifiedTime(jsonPath, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
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

    public void playSound(MediaInfo mediaInfo, PlayerRef playerRef, Store<EntityStore> store) {
        if (playerRef == null) {
            return;
        }

        int totalChunks = mediaInfo.chunkCount;
        if (totalChunks <= 0) {
            plugin.getLogger().at(Level.WARNING).log("Track info not found in library: %s", mediaInfo.trackId);
            return;
        }

        plugin.getPlaybackManager().playForPlayer(mediaInfo, playerRef, totalChunks, 990, store);
    }

    public int getChunkCount(String trackId) {
        return resolveChunkCount(trackId);
    }

    public String getTrackIdForUrl(String url) {
        String hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
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

        return "Track_" + hash;
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
                    "yt-dlp",
                    "--write-thumbnail",
                    "--skip-download",
                    "-o", thumbnailPath.resolve(trackId).toString(),
                    url);
            pb.redirectErrorStream(true);
            Process process = pb.start();

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
                ProcessBuilder ffmpeg = new ProcessBuilder(
                        "ffmpeg",
                        "-y",
                        "-i", downloaded.toString(),
                        pngPath.toString());
                ffmpeg.redirectErrorStream(true);
                Process ffmpegProcess = ffmpeg.start();
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
            Files.setLastModifiedTime(thumbnailPath, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
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
        MediaEntry entry = mediaLibrary.get(trackId);
        if (entry != null && entry.chunkCount > 0
                && Files.exists(commonAudioPath.resolve(String.format("%s_chunk_%03d.ogg", trackId, 0)))) {
            return entry.chunkCount;
        }

        int chunkCount = 0;
        while (Files.exists(commonAudioPath.resolve(String.format("%s_chunk_%03d.ogg", trackId, chunkCount)))) {
            chunkCount++;
        }
        return chunkCount;
    }

    private void updateLibrary(String trackId, String url, int chunkCount) {
        MediaEntry entry = new MediaEntry(url, "AUDIO", chunkCount);
        mediaLibrary.put(trackId, entry);
        saveMediaLibrary();
    }

    private void saveMediaLibrary() {
        try (Writer writer = Files.newBufferedWriter(libraryFile)) {
            GSON.toJson(mediaLibrary, writer);
        } catch (IOException e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to save media library");
        }
    }

    public CompletableFuture<Void> deleteMediaForUrl(String url) {
        if (url == null || url.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String trackId = getTrackIdForUrl(url);
        return CompletableFuture.runAsync(() -> {
            deleteMediaAssets(trackId);
            mediaLibrary.remove(trackId);
            saveMediaLibrary();
        }, com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR);
    }

    private void deleteMediaAssets(String trackId) {
        int chunkCount = resolveChunkCount(trackId);
        for (int i = 0; i < chunkCount; i++) {
            String fileName = String.format("%s_chunk_%03d.ogg", trackId, i);
            Path chunkPath = commonAudioPath.resolve(fileName);
            deleteCommonAsset("Sounds/media_radio/" + fileName, chunkPath);
        }

        for (int i = 0; i < chunkCount; i++) {
            String chunkTrackId = String.format("%s_chunk_%03d", trackId, i);
            Path jsonPath = serverSoundEventsPath.resolve(chunkTrackId + ".json");
            removeSoundEventAsset(jsonPath);
            deleteFile(jsonPath);
        }

        Path fullAudio = commonAudioPath.resolve(trackId + ".ogg");
        deleteCommonAsset("Sounds/media_radio/" + trackId + ".ogg", fullAudio);

        Path thumbPath = thumbnailPath.resolve(trackId + ".png");
        deleteCommonAsset(getThumbnailAssetPath(trackId), thumbPath);
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

    public static class MediaEntry {
        String source;
        String type;
        int chunkCount;

        public MediaEntry(String source, String type) {
            this(source, type, 0);
        }

        public MediaEntry(String source, String type, int chunkCount) {
            this.source = source;
            this.type = type;
            this.chunkCount = chunkCount;
        }
    }
}
