package dev.jacobwasbeast.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.jacobwasbeast.MediaRadioPlugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class MediaLibrary {
    private final MediaRadioPlugin plugin;
    private final File libraryFile;
    private final Gson gson;
    private Map<String, List<SavedSong>> songsByPlayer;

    public MediaLibrary(MediaRadioPlugin plugin) {
        this.plugin = plugin;
        this.libraryFile = resolveLibraryFile();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.songsByPlayer = new HashMap<>();
        load();
    }

    public void load() {
        if (!libraryFile.exists()) {
            save(); // Create empty
            return;
        }

        try (FileReader reader = new FileReader(libraryFile)) {
            Type mapType = new TypeToken<Map<String, List<SavedSong>>>() {
            }.getType();
            songsByPlayer = gson.fromJson(reader, mapType);
            if (songsByPlayer == null) {
                songsByPlayer = new HashMap<>();
            }
            int totalSongs = songsByPlayer.values().stream().mapToInt(List::size).sum();
            plugin.getLogger().at(Level.INFO).log("Loaded " + totalSongs + " songs from player libraries.");
        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to load media library");
            songsByPlayer = new HashMap<>();
        }
    }

    public void save() {
        if (libraryFile.getParentFile() != null && !libraryFile.getParentFile().exists()) {
            libraryFile.getParentFile().mkdirs();
        }

        try (FileWriter writer = new FileWriter(libraryFile)) {
            gson.toJson(songsByPlayer, writer);
        } catch (IOException e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to save media library");
        }
    }

    private File resolveLibraryFile() {
        Path baseDir = MediaRadioPlugin.resolveRuntimeBasePath();
        Path target = baseDir.resolve("songs.json").toAbsolutePath();
        Path legacy = new File("saved_songs.json").toPath().toAbsolutePath();
        if (!target.equals(legacy) && Files.exists(legacy) && !Files.exists(target)) {
            try {
                Files.createDirectories(target.getParent());
                Files.move(legacy, target);
                plugin.getLogger().at(Level.INFO).log("Migrated saved songs to %s", target);
                return target.toFile();
            } catch (IOException e) {
                plugin.getLogger().at(Level.WARNING).withCause(e)
                        .log("Failed to migrate saved songs, using legacy path %s", legacy);
                return legacy.toFile();
            }
        }
        return target.toFile();
    }

    public List<SavedSong> getSongsForPlayer(String playerId) {
        if (playerId == null || playerId.isEmpty()) {
            return List.of();
        }
        List<SavedSong> list = songsByPlayer.get(playerId);
        return list != null ? list : List.of();
    }

    public List<SavedSong> getAllSongs() {
        return songsByPlayer.values().stream().flatMap(List::stream).collect(Collectors.toList());
    }

    public boolean isUrlReferencedByOtherPlayers(String playerId, String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, List<SavedSong>> entry : songsByPlayer.entrySet()) {
            if (entry.getKey().equals(playerId)) {
                continue;
            }
            for (SavedSong song : entry.getValue()) {
                if (url.equals(song.url)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void upsertSongStatus(String playerId, String url, String status, String title, String artist, String thumbnailUrl,
            long duration, String trackId, String thumbnailAssetPath) {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (playerId == null || playerId.isEmpty()) {
            return;
        }
        List<SavedSong> songs = songsByPlayer.computeIfAbsent(playerId, key -> new ArrayList<>());
        for (SavedSong s : songs) {
            if (url.equals(s.url)) {
                s.status = status;
                if (title != null)
                    s.title = title;
                if (artist != null)
                    s.artist = artist;
                if (thumbnailUrl != null)
                    s.thumbnailUrl = thumbnailUrl;
                if (duration > 0)
                    s.duration = duration;
                if (trackId != null)
                    s.trackId = trackId;
                if (thumbnailAssetPath != null)
                    s.thumbnailAssetPath = thumbnailAssetPath;
                save();
                return;
            }
        }
        songs.add(new SavedSong(
                title != null ? title : "Unknown",
                artist != null ? artist : "",
                url,
                thumbnailUrl != null ? thumbnailUrl : "",
                duration,
                trackId,
                thumbnailAssetPath,
                status));
        save();
    }

    public void removeSong(String playerId, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (playerId == null || playerId.isEmpty()) {
            return;
        }
        List<SavedSong> songs = songsByPlayer.get(playerId);
        if (songs == null) {
            return;
        }
        songs.removeIf(s -> s.url != null && s.url.equals(url));
        if (songs.isEmpty()) {
            songsByPlayer.remove(playerId);
        }
        save();
    }

    public static class SavedSong {
        public String title;
        public String artist;
        public String url;
        public String thumbnailUrl;
        public String trackId;
        public String thumbnailAssetPath;
        public long duration;
        public String status;

        public SavedSong(String title, String artist, String url, String thumbnailUrl, long duration, String trackId,
                String thumbnailAssetPath) {
            this.title = title;
            this.artist = artist;
            this.url = url;
            this.thumbnailUrl = thumbnailUrl;
            this.duration = duration;
            this.trackId = trackId;
            this.thumbnailAssetPath = thumbnailAssetPath;
            this.status = "";
        }

        public SavedSong(String title, String artist, String url, String thumbnailUrl, long duration, String trackId,
                String thumbnailAssetPath, String status) {
            this.title = title;
            this.artist = artist;
            this.url = url;
            this.thumbnailUrl = thumbnailUrl;
            this.duration = duration;
            this.trackId = trackId;
            this.thumbnailAssetPath = thumbnailAssetPath;
            this.status = status != null ? status : "";
        }
    }
}
