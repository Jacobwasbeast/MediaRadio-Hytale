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
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import dev.jacobwasbeast.MediaRadioPlugin;
import dev.jacobwasbeast.util.VolumeUtil;
import dev.jacobwasbeast.util.EmbeddedTools;

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
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;

public class MediaManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String RUNTIME_PACK_NAME = "MediaRadioRuntime";
    private static final String RUNTIME_ASSETS_DIR = "media_radio_assets";
    private static final String STORAGE_DIR = "songs";
    private static final int CURRENT_NORMALIZATION_VERSION = 1;
    private static final int INITIAL_ASSET_BATCH = 100;
    private static final int BACKGROUND_ASSET_BATCH = 75;
    private static final long BACKGROUND_ASSET_DELAY_MS = 750L;

    private final MediaRadioPlugin plugin;
    private final Path runtimeAssetsPath;
    private final EmbeddedTools mediaTools;
    private final Path commonAudioPath;
    private final Path serverSoundEventsPath;
    private final Path thumbnailPath;
    private final Path storagePath;
    private final Path songsIndexFile;

    private final Map<String, StoredSong> storedSongs = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<MediaInfo>> inFlightRequests = new ConcurrentHashMap<>();

    private final Path serverModelsPath;
    private final Path serverRolesPath;

    public MediaManager(MediaRadioPlugin plugin) {
        this.plugin = plugin;
        Path baseDir = MediaRadioPlugin.resolveRuntimeBasePath();
        this.mediaTools = EmbeddedTools.create(baseDir.resolve("media_radio_tools"));
        this.runtimeAssetsPath = baseDir.resolve(RUNTIME_ASSETS_DIR).toAbsolutePath();
        this.storagePath = baseDir.resolve(STORAGE_DIR).toAbsolutePath();
        this.songsIndexFile = storagePath.resolve("song_index.json");
        // Pack structure with Common/Server separation matching Vanilla
        // Audio files: Common/Sounds/media_radio/<path>
        this.commonAudioPath = runtimeAssetsPath.resolve("Common/Sounds/media_radio");
        // SoundEvent definitions: Server/Audio/SoundEvents/
        this.serverSoundEventsPath = runtimeAssetsPath.resolve("Server/Audio/SoundEvents");

        // Models and Roles
        // Models and Roles
        this.serverModelsPath = runtimeAssetsPath.resolve("Common/Models/MediaRadio");
        this.serverRolesPath = runtimeAssetsPath.resolve("Server/NPC/Roles");

        this.thumbnailPath = runtimeAssetsPath.resolve("Common/UI/Custom/Pages/MediaRadio/Thumbs");
    }

    public void init() {
        try {
            cleanupRuntimeFolders();
            ensureDirectories();
            loadSongIndex();
            registerRuntimePack();
            logExternalToolStatus();

            // Initialize static assets for markers
            ensureStaticAssets();
            registerStaticAssets();
            ensureBaseAppearance();
            ensureRole();

            plugin.getLogger().at(Level.INFO).log("MediaManager init completed successfully.");
        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to initialize MediaManager");
        }
    }

    private void ensureDirectories() throws IOException {
        Files.createDirectories(commonAudioPath);
        Files.createDirectories(serverSoundEventsPath);
        Files.createDirectories(serverModelsPath);
        Files.createDirectories(serverRolesPath);
        Files.createDirectories(thumbnailPath);
        Files.createDirectories(storagePath);
        plugin.getLogger().at(Level.INFO).log("Ensured directories exist at: %s", runtimeAssetsPath);
    }

    private void cleanupRuntimeFolders() {
        deleteDirectory(commonAudioPath);
        deleteDirectory(serverSoundEventsPath);
        deleteDirectory(runtimeAssetsPath.resolve("Common/Models/MediaRadio")); // Cleanup old path
        deleteDirectory(serverModelsPath);
        deleteDirectory(serverRolesPath);
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
                            metadata.duration, CURRENT_NORMALIZATION_VERSION));
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
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(requireYtDlpCommand());
        command.add("--dump-json");
        command.add("--no-playlist");
        command.add("--no-progress");
        command.add("--quiet");
        java.util.List<String> extraArgs = getYtDlpMetadataArgs();
        if (!extraArgs.isEmpty()) {
            command.addAll(extraArgs);
        }
        command.add(url);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("yt-dlp not available for metadata fetch. Embedded yt-dlp failed to execute.", e);
        }
        StringBuilder jsonOutput = new StringBuilder();
        try (java.util.Scanner s = new java.util.Scanner(process.getInputStream())) {
            while (s.hasNextLine()) {
                jsonOutput.append(s.nextLine()).append('\n');
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String combined = jsonOutput.toString();
            if (combined.contains("HTTP Error 403") || combined.contains("403: Forbidden")
                    || combined.contains("ERROR: Unable to download JSON metadata: HTTP Error 403")) {
                throw new RuntimeException(
                        "yt-dlp received HTTP 403 (Forbidden). This can be caused by the specific URL, region/IP blocks, "
                                + "or the embedded yt-dlp being outdated. Try another URL to confirm. If it only fails on "
                                + "one song, the source is likely blocked. Otherwise update MediaRadio/media-tools or wait "
                                + "for an update. Report the URL and logs if it persists.");
            }
            throw new RuntimeException("yt-dlp metadata fetch failed code " + exitCode);
        }

        String raw = jsonOutput.toString();
        String extracted = extractJsonObject(raw);
        com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
                new java.io.StringReader(extracted));
        reader.setLenient(true);
        JsonObject root;
        try {
            root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            String preview = raw.length() > 4000 ? raw.substring(0, 4000) + "...(truncated)" : raw;
            plugin.getLogger().at(Level.WARNING).withCause(e)
                    .log("yt-dlp metadata JSON parse failed. Raw output (truncated): %s", preview);
            throw e;
        }
        String title = root.has("title") ? root.get("title").getAsString() : "Unknown Title";
        String uploader = root.has("uploader") ? root.get("uploader").getAsString() : "Unknown Artist";
        String thumbnail = root.has("thumbnail") ? root.get("thumbnail").getAsString() : "";
        long duration = root.has("duration") ? root.get("duration").getAsLong() : 0;

        return new MediaInfo(trackId, url, title, uploader, thumbnail, duration, 0, "");
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1).trim();
        }
        return raw.trim();
    }

    private void downloadMedia(String url, String trackId) throws Exception {
        // Don't include extension in -o template - yt-dlp adds it automatically with
        // --audio-format
        Path outputPathBase = storagePath.resolve(trackId);
        Path outputPathBaseOgg = storagePath.resolve(trackId + ".ogg");
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

        Path ffmpegLocation = mediaTools.getFfmpegLocationForYtDlp();
        if (ffmpegLocation != null) {
            command.add("--ffmpeg-location");
            command.add(ffmpegLocation.toString());
        }

        command.add("-o");
        command.add(outputPathBase.toString());
        java.util.List<String> extraArgs = getYtDlpArgs();
        if (!extraArgs.isEmpty()) {
            command.addAll(extraArgs);
        }
        command.add(url);

        plugin.getLogger().at(Level.INFO).log("Executing yt-dlp command: %s", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);

        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("yt-dlp not available for media download. Embedded yt-dlp failed to execute.", e);
        }

        StringBuilder output = new StringBuilder();
        // Read output to log
        try (java.util.Scanner s = new java.util.Scanner(process.getInputStream())) {
            while (s.hasNextLine()) {
                String line = s.nextLine();
                output.append(line).append('\n');
                plugin.getLogger().at(Level.INFO).log("[yt-dlp] %s", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String combined = output.toString();
            if (combined.contains("HTTP Error 403") || combined.contains("403: Forbidden")) {
                throw new RuntimeException(
                        "yt-dlp received HTTP 403 (Forbidden). This can be caused by the specific URL, region/IP blocks, "
                                + "or the embedded yt-dlp being outdated. Try another URL to confirm. If it only fails on "
                                + "one song, the source is likely blocked. Otherwise update MediaRadio/media-tools or wait "
                                + "for an update. Report the URL and logs if it persists.");
            }
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

        String ffmpegCommand = requireFfmpegCommand();

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegCommand,
                "-i", inputFile.toString(),
                "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
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
            throw new RuntimeException("ffmpeg not available for audio split. Embedded ffmpeg failed to execute.", e);
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
        createSoundEvents(trackId, chunkCount, 0.0f);
    }

    private void createSoundEvents(String trackId, int chunkCount, float volumeDb) {
        createSoundEventsRange(trackId, 0, chunkCount, volumeDb);
    }

    private void createSoundEventsRange(String trackId, int startInclusive, int endExclusive, float volumeDb) {
        if (endExclusive <= startInclusive) {
            return;
        }
        for (int i = startInclusive; i < endExclusive; i++) {
            String chunkTrackId = String.format("%s_Chunk_%03d", trackId, i);
            Path jsonPath = serverSoundEventsPath.resolve(chunkTrackId + ".json");
            String soundFilePath = String.format("Sounds/media_radio/%s_Chunk_%03d.ogg", trackId, i);
            writeSoundEventConfig(jsonPath, soundFilePath, volumeDb);
        }
        plugin.getLogger().at(Level.INFO).log("Created SoundEvent configs for %s [%d..%d)", trackId,
                startInclusive, endExclusive);
    }

    private void writeSoundEventConfig(Path jsonPath, String soundFilePath, float volumeDb) {
        Map<String, Object> layer = new HashMap<>();
        layer.put("Files", Collections.singletonList(soundFilePath));
        layer.put("Volume", VolumeUtil.percentToLayerDb(VolumeUtil.eventDbToPercent(volumeDb)));

        Map<String, Object> soundEvent = new HashMap<>();
        soundEvent.put("StartAttenuationDistance", 10);
        soundEvent.put("MaxDistance", 60);
        soundEvent.put("Volume", volumeDb);
        soundEvent.put("Parent", "SFX_Attn_Quiet");
        soundEvent.put("Pitch", 0.0);
        soundEvent.put("Layers", Collections.singletonList(layer));

        try (Writer writer = Files.newBufferedWriter(jsonPath)) {
            GSON.toJson(soundEvent, writer);
        } catch (IOException e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to write SoundEvent at %s", jsonPath);
        }
    }

    private void registerCommonSoundAssets(String trackId, int chunkCount) {
        registerCommonSoundAssetsRange(trackId, 0, chunkCount);
    }

    private void registerCommonSoundAssetsRange(String trackId, int startInclusive, int endExclusive) {
        CommonAssetModule commonAssetModule = CommonAssetModule.get();
        if (commonAssetModule == null) {
            plugin.getLogger().at(Level.WARNING).log("CommonAssetModule not available; cannot register sound assets.");
            return;
        }

        for (int i = startInclusive; i < endExclusive; i++) {
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

    public void updateTrackVolume(String trackId, int chunkCount, float volumeDb) {
        if (chunkCount <= 0) {
            return;
        }
        for (int i = 0; i < chunkCount; i++) {
            updateChunkVolume(trackId, i, volumeDb);
        }
        plugin.getLogger().at(Level.INFO).log("Updated volume for %s to %.1f dB", trackId, volumeDb);
    }

    public void updateChunkVolume(String trackId, int chunkIndex, float volumeDb) {
        String chunkTrackId = String.format("%s_Chunk_%03d", trackId, chunkIndex);
        Path jsonPath = serverSoundEventsPath.resolve(chunkTrackId + ".json");
        String soundFilePath = String.format("Sounds/media_radio/%s_Chunk_%03d.ogg", trackId, chunkIndex);
        writeSoundEventConfig(jsonPath, soundFilePath, volumeDb);
    }

    private void loadSoundEventAssets(String trackId, int chunkCount) {
        loadSoundEventAssetsRange(trackId, 0, chunkCount);
    }

    private void loadSoundEventAssetsRange(String trackId, int startInclusive, int endExclusive) {
        if (endExclusive <= startInclusive) {
            return;
        }
        int size = endExclusive - startInclusive;
        java.util.List<Path> paths = new java.util.ArrayList<>(size);
        for (int i = startInclusive; i < endExclusive; i++) {
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

    public CompletableFuture<Void> playSound(MediaInfo mediaInfo, PlayerRef playerRef, Store<EntityStore> store) {
        if (playerRef == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        float volumeDb = VolumeUtil.percentToEventDb(VolumeUtil.DEFAULT_PERCENT);
        if (plugin.getPlaybackManager() != null) {
            volumeDb = plugin.getPlaybackManager().getPlayerVolume(playerRef.getUuid());
        }
        prepareRuntimeAssetsAsync(mediaInfo, 750, volumeDb, true)
                .thenAccept(totalChunks -> {
            if (totalChunks <= 0) {
                plugin.getLogger().at(Level.WARNING).log("No chunks available for %s", mediaInfo.trackId);
                result.completeExceptionally(new RuntimeException("Failed to prepare media assets (0 chunks)"));
                return;
            }
            store.getExternalData().getWorld().execute(() -> {
                MediaInfo updated = withChunkCount(mediaInfo, totalChunks);
                plugin.getPlaybackManager().playForPlayer(updated, playerRef, totalChunks,
                        plugin.getConfig().getChunkDurationMs(), store);
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
        float volumeDb = VolumeUtil.percentToEventDb(VolumeUtil.DEFAULT_PERCENT);
        if (plugin.getPlaybackManager() != null) {
            volumeDb = plugin.getPlaybackManager().getBlockVolume(blockPos, store);
        }
        prepareRuntimeAssetsAsync(mediaInfo, chunkDurationMs, volumeDb, true).thenAccept(totalChunks -> {
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

    private CompletableFuture<Integer> prepareRuntimeAssetsAsync(MediaInfo mediaInfo, int chunkDurationMs,
            float volumeDb, boolean waitForFullAssets) {
        return CompletableFuture
                .supplyAsync(
                        () -> ensureRuntimeAssets(mediaInfo, chunkDurationMs, volumeDb, waitForFullAssets),
                        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR)
                .thenCompose(result -> {
                    if (result == null || result.chunkCount <= 0) {
                        return CompletableFuture.completedFuture(0);
                    }
                    if (result.remainingPlan == null) {
                        return CompletableFuture.completedFuture(result.chunkCount);
                    }
                    if (!result.remainingPlan.waitForFullAssets) {
                        startBackgroundSoundEventGeneration(
                                result.remainingPlan.trackId,
                                result.remainingPlan.startChunk,
                                result.remainingPlan.totalChunks,
                                result.remainingPlan.volumeDb);
                        return CompletableFuture.completedFuture(result.chunkCount);
                    }
                    return generateRemainingSoundEventsAsync(
                            result.remainingPlan.trackId,
                            result.remainingPlan.startChunk,
                            result.remainingPlan.totalChunks,
                            result.remainingPlan.volumeDb,
                            BACKGROUND_ASSET_DELAY_MS).thenApply(ignored -> {
                                if (result.remainingPlan.createModelAfter) {
                                    createTrackModel(result.remainingPlan.trackId, result.chunkCount);
                                }
                                return result.chunkCount;
                            });
                });
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

    private AssetPreparation ensureRuntimeAssets(MediaInfo mediaInfo, int chunkDurationMs, float volumeDb,
            boolean waitForFullAssets) {
        if (mediaInfo == null) {
            return new AssetPreparation(0, null);
        }
        String trackId = mediaInfo.trackId;

        // Check for outdated version and triggers re-normalization
        StoredSong stored = storedSongs.get(trackId);
        if (stored != null && stored.version < CURRENT_NORMALIZATION_VERSION) {
            plugin.getLogger().at(Level.INFO).log("Normalizing existing track: %s (v%d -> v%d)",
                    trackId, stored.version, CURRENT_NORMALIZATION_VERSION);
            cleanupRuntimeAssets(trackId);
            stored.version = CURRENT_NORMALIZATION_VERSION;
            saveSongIndex();
        }

        Path storedAudio = storagePath.resolve(trackId + ".ogg");
        if (!Files.exists(storedAudio)) {
            try {
                downloadMedia(mediaInfo.url, trackId);
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e)
                        .log("Failed to download audio for %s", trackId);
                return new AssetPreparation(0, null);
            }
        }

        int chunkCount = resolveChunkCount(trackId);
        if (chunkCount <= 0) {
            try {
                double seconds = Math.max(0.1, chunkDurationMs / 1000.0);
                chunkCount = splitAudio(trackId, seconds);
                if (chunkCount > 0) {
                    int initialBatch = Math.min(chunkCount, INITIAL_ASSET_BATCH);
                    registerCommonSoundAssetsRange(trackId, 0, initialBatch);
                    createSoundEventsRange(trackId, 0, initialBatch, volumeDb);
                    loadSoundEventAssetsRange(trackId, 0, initialBatch);

                    // Ensure appearances (Initial Batch)
                    // With unified model, we just need to confirm the model is loaded (done in
                    // createTrackModel)

                    // Schedule background generation for the rest (SoundEvents still need partial
                    // loading if we were lazier,
                    // but we generated all SoundEvents above.
                    // Wait, splitAudio generates files, createSoundEvents generates ALL jsons.
                    // Implementation plan said: "splitAudio and createSoundEvents continue to run
                    // in background"
                    // but currently they run synchronously in `ensureRuntimeAssets` for the WHOLE
                    // track if it's new.
                    // The "Refactor for Buffered Streaming" task made them background?
                    // Ah, I see `startBackgroundAssetGeneration` was calling
                    // `createChunkAppearance`.
                    // The splitting seems to happen all at once in `splitAudio` currently?
                    // Wait, looking at lines 582: chunkCount = splitAudio(...)
                    // This seems to process everything.
                    // Let's look at `splitAudio` again. It splits the whole file.
                    // So `chunkCount` is the TOTAL.

                    // The previous code had:
                    // int initialBatch = Math.min(chunkCount, 10);
                    // loop createChunkAppearance...
                    // if chunkCount > initialBatch -> startBackgroundAssetGeneration

                    // Since we now generate ONE model with ALL keys, we just call createTrackModel
                    // once.
                    // We don't need background generation for APPEARANCES anymore.
                    if (chunkCount > initialBatch) {
                        return new AssetPreparation(
                                chunkCount,
                                new RemainingSoundEventsPlan(
                                        trackId,
                                        initialBatch,
                                        chunkCount,
                                        volumeDb,
                                        waitForFullAssets,
                                        waitForFullAssets));
                    }
                    // Create the Unified Model once all sound events exist.
                    createTrackModel(trackId, chunkCount);
                    return new AssetPreparation(chunkCount, null);

                }
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e)
                        .log("Failed to prepare runtime assets for %s", trackId);
                return new AssetPreparation(0, null);
            }
        } else {
            // Check if model exists
            String appearanceId = "medradio_marker_" + trackId;
            if (ModelAsset.getAssetMap().getAsset(appearanceId) == null) {
                int initialBatch = Math.min(chunkCount, INITIAL_ASSET_BATCH);
                registerCommonSoundAssetsRange(trackId, 0, initialBatch);
                // Ensure SoundEvents
                if (!Files.exists(serverSoundEventsPath.resolve(String.format("%s_Chunk_%03d.json", trackId, 0)))) {
                    createSoundEventsRange(trackId, 0, initialBatch, volumeDb);
                }
                loadSoundEventAssetsRange(trackId, 0, initialBatch);
                if (chunkCount > initialBatch) {
                    return new AssetPreparation(
                            chunkCount,
                            new RemainingSoundEventsPlan(
                                    trackId,
                                    initialBatch,
                                    chunkCount,
                                    volumeDb,
                                    waitForFullAssets,
                                    waitForFullAssets));
                }
                createTrackModel(trackId, chunkCount);
                return new AssetPreparation(chunkCount, null);
            }
        }
        return new AssetPreparation(chunkCount, null);
    }

    // Background generation is no longer needed for appearances since we generate
    // the single model upfront.
    // If we were splitting audio in background, that would be different, but
    // splitAudio does it all.
    // We'll keep the method empty or remove usage to satisfy the "remove loop"
    // requirement.
    private void startBackgroundAssetGeneration(String trackId, int startChunk, int totalChunks) {
        // No-op for now as model contains all states
    }

    private CompletableFuture<Void> generateRemainingSoundEventsAsync(String trackId, int startChunk, int totalChunks,
            float volumeDb, long delayMs) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        scheduleSoundEventChunk(trackId, startChunk, totalChunks, volumeDb, delayMs, completion);
        return completion;
    }

    private void scheduleSoundEventChunk(String trackId, int current, int totalChunks, float volumeDb, long delayMs,
            CompletableFuture<Void> completion) {
        if (current >= totalChunks) {
            completion.complete(null);
            return;
        }
        try {
            int end = Math.min(totalChunks, current + BACKGROUND_ASSET_BATCH);
            registerCommonSoundAssetsRange(trackId, current, end);
            createSoundEventsRange(trackId, current, end, volumeDb);
            loadSoundEventAssetsRange(trackId, current, end);
            int next = end;
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(
                    () -> scheduleSoundEventChunk(trackId, next, totalChunks, volumeDb, delayMs, completion),
                    delayMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            completion.completeExceptionally(e);
        }
    }

    private CompletableFuture<Void> startBackgroundSoundEventGeneration(String trackId, int startChunk, int totalChunks,
            float volumeDb) {
        return generateRemainingSoundEventsAsync(
                trackId,
                startChunk,
                totalChunks,
                volumeDb,
                BACKGROUND_ASSET_DELAY_MS);
    }

    private static final class AssetPreparation {
        private final int chunkCount;
        private final RemainingSoundEventsPlan remainingPlan;

        private AssetPreparation(int chunkCount, RemainingSoundEventsPlan remainingPlan) {
            this.chunkCount = chunkCount;
            this.remainingPlan = remainingPlan;
        }
    }

    private static final class RemainingSoundEventsPlan {
        private final String trackId;
        private final int startChunk;
        private final int totalChunks;
        private final float volumeDb;
        private final boolean waitForFullAssets;
        private final boolean createModelAfter;

        private RemainingSoundEventsPlan(String trackId, int startChunk, int totalChunks, float volumeDb,
                boolean waitForFullAssets, boolean createModelAfter) {
            this.trackId = trackId;
            this.startChunk = startChunk;
            this.totalChunks = totalChunks;
            this.volumeDb = volumeDb;
            this.waitForFullAssets = waitForFullAssets;
            this.createModelAfter = createModelAfter;
        }
    }

    public void createTrackModel(String trackId, int estimatedChunks) {
        String appearanceId = "medradio_marker_" + trackId;
        Path jsonPath = serverModelsPath.resolve(appearanceId + ".json");

        // If it exists, we might want to update it if the chunk count has increased
        // significantly,
        // but for now, let's assume estimatedChunks covers it (or we can just overwrite
        // if needed).
        // To be safe and support growing files, we can just overwrite it if it's the
        // initial generation,
        // or check if we need to expand. For simplicity, we overwrite if it's the first
        // batch,
        // but for "estimatedChunks" we should probably pass the TOTAL expected chunks.

        Map<String, Object> animationSets = new HashMap<>();

        // Generate animation entries for ALL estimated chunks
        // Keys: PlayChunk0, PlayChunk1, ...
        for (int i = 0; i < estimatedChunks; i++) {
            String chunkTrackId = String.format("%s_Chunk_%03d", trackId, i);

            Map<String, Object> animation = new HashMap<>();
            animation.put("Animation", "NPC/MediaRadio/Animations/radio_play.blockyanim");
            animation.put("Looping", false);
            animation.put("SoundEventId", chunkTrackId);

            Map<String, Object> animationSet = new HashMap<>();
            animationSet.put("Animations", Collections.singletonList(animation));

            animationSets.put("PlayChunk" + i, animationSet);
        }

        Map<String, Object> modelAsset = new HashMap<>();
        modelAsset.put("Model", "NPC/MISC/Empty.blockymodel");
        modelAsset.put("EyeHeight", 0.0);
        modelAsset.put("HitBox", Map.of(
                "Max", Map.of("X", 0.1, "Y", 0.1, "Z", 0.1),
                "Min", Map.of("X", -0.1, "Y", 0.0, "Z", -0.1)));
        modelAsset.put("AnimationSets", animationSets);

        // Ensure directory exists
        try {
            Files.createDirectories(jsonPath.getParent());
        } catch (IOException ignored) {
        }

        try (Writer writer = Files.newBufferedWriter(jsonPath)) {
            GSON.toJson(modelAsset, writer);
        } catch (IOException e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to write ModelAsset for %s", appearanceId);
            return;
        }

        registerCommonModelAsset(appearanceId, jsonPath);
        loadModelAsset(appearanceId);

        plugin.getLogger().at(Level.INFO).log("Created unified Track Model for %s with %d animation states", trackId,
                estimatedChunks);
    }

    private void registerCommonModelAsset(String appearanceId, Path existingPath) {
        CommonAssetModule commonAssetModule = CommonAssetModule.get();
        if (commonAssetModule == null)
            return;

        String assetName = "NPC/Models/" + appearanceId + ".json";
        if (CommonAssetRegistry.hasCommonAsset(assetName))
            return;

        try {
            byte[] bytes = Files.readAllBytes(existingPath);
            commonAssetModule.addCommonAsset(RUNTIME_PACK_NAME, new FileCommonAsset(existingPath, assetName, bytes));
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to register common model asset %s",
                    assetName);
        }
    }

    private void loadModelAsset(String appearanceId) {
        Path jsonPath = serverModelsPath.resolve(appearanceId + ".json");
        if (!Files.exists(jsonPath))
            return;

        try {
            ModelAsset.getAssetStore().loadAssetsFromPaths(RUNTIME_PACK_NAME, Collections.singletonList(jsonPath),
                    AssetUpdateQuery.DEFAULT, true);
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to load ModelAsset %s", appearanceId);
        }
    }

    private void registerStaticAssets() {
        Path animPath = runtimeAssetsPath.resolve("Common/NPC/MediaRadio/Animations/radio_play.blockyanim");
        if (!Files.exists(animPath))
            return;

        CommonAssetModule commonAssetModule = CommonAssetModule.get();
        if (commonAssetModule == null)
            return;

        String assetName = "NPC/MediaRadio/Animations/radio_play.blockyanim";
        try {
            byte[] bytes = Files.readAllBytes(animPath);
            commonAssetModule.addCommonAsset(RUNTIME_PACK_NAME, new FileCommonAsset(animPath, assetName, bytes));
            plugin.getLogger().atInfo().log("Successfully registered animation asset: " + assetName);
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to register radio_play animation");
        }

        // Register Empty.blockymodel
        Path modelPath = runtimeAssetsPath.resolve("Common/NPC/MISC/Empty.blockymodel");
        if (Files.exists(modelPath)) {
            String modelAssetName = "NPC/MISC/Empty.blockymodel";
            try {
                byte[] bytes = Files.readAllBytes(modelPath);
                commonAssetModule.addCommonAsset(RUNTIME_PACK_NAME,
                        new FileCommonAsset(modelPath, modelAssetName, bytes));
            } catch (IOException e) {
                plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to register Empty.blockymodel");
            }
        }

        // Register Empty.png
        Path texturePath = runtimeAssetsPath.resolve("Common/NPC/MISC/Empty.png");
        if (Files.exists(texturePath)) {
            String textureAssetName = "NPC/MISC/Empty.png";
            try {
                byte[] bytes = Files.readAllBytes(texturePath);
                commonAssetModule.addCommonAsset(RUNTIME_PACK_NAME,
                        new FileCommonAsset(texturePath, textureAssetName, bytes));
            } catch (IOException e) {
                plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to register Empty.png");
            }
        }
    }

    private void ensureStaticAssets() {
        // Ensure radio_play.blockyanim
        Path animPath = runtimeAssetsPath.resolve("Common/NPC/MediaRadio/Animations/radio_play.blockyanim");
        // Force regeneration to update duration
        try {
            Files.createDirectories(animPath.getParent());
            Map<String, Object> anim = new HashMap<>();
            anim.put("formatVersion", 1);
            anim.put("nodeAnimations", new HashMap<>()); // Empty, just drives sounds
            try (Writer writer = Files.newBufferedWriter(animPath)) {
                GSON.toJson(anim, writer);
            }

            plugin.getLogger().at(Level.INFO).log("Generated radio_play.blockyanim at %s", animPath);
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to ensure radio_play.blockyanim");
        }
        // Ensure Empty.blockymodel
        Path modelPath = runtimeAssetsPath.resolve("Common/NPC/MISC/Empty.blockymodel");
        if (!Files.exists(modelPath)) {
            generateEmptyModel(modelPath);
        } else {
            // Check if it's our own empty model or something else
            try {
                String content = Files.readString(modelPath);
                if (!content.contains("NPC/MISC/Empty.blockymodel") && !content.contains("Empty.blockymodel")) {
                    plugin.getLogger().at(Level.INFO)
                            .log("Regenerating Empty.blockymodel (found potentially invalid content)");
                    generateEmptyModel(modelPath);
                }
            } catch (IOException e) {
                generateEmptyModel(modelPath);
            }
        }

        // Ensure Empty.png (32x32 required by some versions of the model parser)
        Path texturePath = runtimeAssetsPath.resolve("Common/NPC/MISC/Empty.png");
        if (!Files.exists(texturePath)) {
            generateEmptyTexture(texturePath);
        } else {
            try {
                BufferedImage img = ImageIO.read(texturePath.toFile());
                if (img == null || img.getWidth() != 32 || img.getHeight() != 32) {
                    plugin.getLogger().at(Level.INFO).log("Regenerating Empty.png (invalid dimensions)");
                    generateEmptyTexture(texturePath);
                }
            } catch (Exception e) {
                generateEmptyTexture(texturePath);
            }
        }
    }

    private void generateEmptyTexture(Path path) {
        try {
            BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            // Default is transparent
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
            plugin.getLogger().at(Level.INFO).log("Generated 32x32 Empty.png at %s", path);
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to generate Empty.png");
        }
    }

    private void generateEmptyModel(Path path) {
        // Minimal valid blocky model
        // A single invisible node - trying a small box with valid transparency
        Map<String, Object> node = new HashMap<>();
        node.put("id", "0");
        node.put("name", "root");
        node.put("position", Map.of("x", 0.0, "y", 0.0, "z", 0.0));
        node.put("orientation", Map.of("x", 0.0, "y", 0.0, "z", 0.0, "w", 1.0));

        Map<String, Object> shape = new HashMap<>();
        shape.put("type", "box"); // Box is standard
        shape.put("visible", true); // We'll rely on transparent texture
        // 1 unit size
        shape.put("settings", Map.of("size", Map.of("x", 1.0, "y", 1.0, "z", 1.0)));
        // UV mapping - basic auto
        shape.put("unwrapMode", "auto");
        // Texture layout - required for parser?
        Map<String, Object> layout = new HashMap<>();
        // All faces
        for (String face : new String[] { "north", "south", "east", "west", "up", "down" }) {
            layout.put(face, Map.of(
                    "offset", Map.of("x", 0, "y", 0),
                    "mirror", Map.of("x", false, "y", false),
                    "angle", 0));
        }
        shape.put("textureLayout", layout);

        node.put("shape", shape);
        node.put("children", Collections.emptyList());

        Map<String, Object> model = new HashMap<>();
        model.put("nodes", Collections.singletonList(node));
        model.put("lod", "auto");

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(model, writer);
            }
            plugin.getLogger().at(Level.INFO).log("Generated valid Empty.blockymodel at %s", path);
        } catch (IOException e) {
            plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to generate Empty.blockymodel");
        }
    }

    private void ensureBaseAppearance() {
        String baseId = "medradio_marker";
        Path jsonPath = serverModelsPath.resolve(baseId + ".json");

        if (Files.exists(jsonPath)) {
            registerCommonModelAsset(baseId, jsonPath);
            loadModelAsset(baseId);
            return;
        }

        Map<String, Object> modelAsset = new HashMap<>();
        modelAsset.put("Model", "NPC/MISC/Empty.blockymodel");
        modelAsset.put("EyeHeight", 0.0);
        modelAsset.put("HitBox", Map.of(
                "Max", Map.of("X", 0.1, "Y", 0.1, "Z", 0.1),
                "Min", Map.of("X", -0.1, "Y", 0.0, "Z", -0.1)));

        try (Writer writer = Files.newBufferedWriter(jsonPath)) {
            GSON.toJson(modelAsset, writer);
        } catch (IOException e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to write base ModelAsset for %s", baseId);
            return;
        }

        registerCommonModelAsset(baseId, jsonPath);
        loadModelAsset(baseId);
    }

    private void ensureRole() {
        String roleName = "audio_marker";
        Path jsonPath = serverRolesPath.resolve("audio_marker.json");
        if (Files.exists(jsonPath)) {
            // Register server asset if it exists
            return;
        }

        Map<String, Object> role = new HashMap<>();
        role.put("Type", "Generic");
        role.put("Appearance", "medradio_marker");

        Map<String, Object> movement = new HashMap<>();
        movement.put("Type", "Walk");
        role.put("MotionControllerList", Collections.singletonList(movement));

        Map<String, Object> maxHealth = new HashMap<>();
        maxHealth.put("Compute", "MaxHealth");
        role.put("MaxHealth", maxHealth);

        Map<String, Object> paramVal = new HashMap<>();
        paramVal.put("Value", 1);
        paramVal.put("Description", "Minimal health for invisible audio marker");

        Map<String, Object> params = new HashMap<>();
        params.put("MaxHealth", paramVal);
        role.put("Parameters", params);

        role.put("Instructions", Collections.singletonList(new HashMap<>()));
        role.put("NameTranslationKey", "server.npcRoles.audio_marker.name");

        try (Writer writer = Files.newBufferedWriter(jsonPath)) {
            GSON.toJson(role, writer);
        } catch (IOException e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to write Role for %s", roleName);
            return;
        }
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
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(requireYtDlpCommand());
            command.add("--write-thumbnail");
            command.add("--skip-download");
            command.add("-o");
            command.add(thumbnailPath.resolve(trackId).toString());
            java.util.List<String> extraArgs = getYtDlpArgs();
            if (!extraArgs.isEmpty()) {
                command.addAll(extraArgs);
            }
            command.add(url);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                plugin.getLogger().at(Level.WARNING).withCause(e)
                        .log("yt-dlp not available for thumbnail download. Embedded yt-dlp failed to execute.");
                return "";
            }

            String output = "";
            try (java.util.Scanner s = new java.util.Scanner(process.getInputStream()).useDelimiter("\\A")) {
                if (s.hasNext()) {
                    output = s.next();
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                if (output.contains("HTTP Error 403") || output.contains("403: Forbidden")) {
                    plugin.getLogger().at(Level.WARNING).log(
                            "yt-dlp thumbnail download failed (HTTP 403). This can be URL-specific or an IP/region block, "
                                    + "or the embedded yt-dlp may be outdated. Try another URL, then update or report.");
                    return "";
                }
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
            deleteFile(chunkPath);
        }

        for (int i = 0; i < chunkCount; i++) {
            String chunkTrackId = String.format("%s_Chunk_%03d", trackId, i);
            Path jsonPath = serverSoundEventsPath.resolve(chunkTrackId + ".json");
            deleteFile(jsonPath);
        }
    }

    public void cleanupRuntimeAssetsAsync(String trackId) {
        CompletableFuture.runAsync(
                () -> cleanupRuntimeAssets(trackId),
                com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR);
    }

    private void deleteCommonAsset(String assetName, Path filePath) {
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
        mediaTools.logToolStatus(plugin.getLogger());
        if (isYtDlpAvailable() && isFfmpegAvailable()) {
            plugin.getLogger().at(Level.INFO).log("MediaRadio tools ready: yt-dlp + ffmpeg detected.");
        } else if (!isYtDlpAvailable() && !isFfmpegAvailable()) {
            plugin.getLogger().at(Level.WARNING).log(
                    "MediaRadio tools missing: embedded yt-dlp and ffmpeg were not found for this platform.");
        } else if (!isYtDlpAvailable()) {
            plugin.getLogger().at(Level.WARNING).log(
                    "MediaRadio tools missing: embedded yt-dlp was not found for this platform.");
        } else {
            plugin.getLogger().at(Level.WARNING).log(
                    "MediaRadio tools missing: embedded ffmpeg was not found for this platform.");
        }
    }


    private java.util.List<String> getYtDlpArgs() {
        if (plugin.getConfig() == null) {
            return java.util.List.of();
        }
        java.util.List<String> args = plugin.getConfig().getYtDlpArgs();
        return args != null ? args : java.util.List.of();
    }

    private java.util.List<String> getYtDlpMetadataArgs() {
        if (plugin.getConfig() == null) {
            return java.util.List.of();
        }
        java.util.List<String> args = plugin.getConfig().getYtDlpMetadataArgs();
        return args != null ? args : java.util.List.of();
    }

    private String requireYtDlpCommand() {
        return mediaTools.requireYtDlpCommand();
    }

    private String requireFfmpegCommand() {
        return mediaTools.requireFfmpegCommand();
    }

    private String resolveYtDlpCommand() {
        return mediaTools.resolveYtDlpCommand();
    }

    public boolean isYtDlpAvailable() {
        return mediaTools.isYtDlpAvailable();
    }

    public Path getExpectedYtDlpPath() {
        return mediaTools.getExpectedYtDlpPath();
    }

    public boolean isFfmpegAvailable() {
        return mediaTools.isFfmpegAvailable();
    }

    public Path getExpectedFfmpegPath() {
        return mediaTools.getExpectedFfmpegPath();
    }

    public boolean isToolProviderAvailable() {
        return mediaTools.isPresent();
    }

    public String getToolStatusSummary() {
        return mediaTools.getStatusSummary();
    }

    private String resolveFfmpegCommand() {
        return mediaTools.resolveFfmpegCommand();
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
        public int version;

        public StoredSong() {
        }

        public StoredSong(String trackId, String url, String title, String artist, long duration, int version) {
            this.trackId = trackId;
            this.url = url;
            this.title = title;
            this.artist = artist;
            this.duration = duration;
            this.version = version;
        }
    }

}
