package dev.jacobwasbeast.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.jacobwasbeast.MediaRadioPlugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class PlaylistManager {
    private final MediaRadioPlugin plugin;
    private final File playlistFile;
    private final Gson gson;
    private Map<String, PlaylistScope> scopes = new HashMap<>();

    public PlaylistManager(MediaRadioPlugin plugin) {
        this.plugin = plugin;
        this.playlistFile = resolvePlaylistFile();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    public synchronized void load() {
        if (!playlistFile.exists()) {
            save();
            return;
        }
        try (FileReader reader = new FileReader(playlistFile)) {
            java.lang.reflect.Type type = new TypeToken<Map<String, PlaylistScope>>() {
            }.getType();
            Map<String, PlaylistScope> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                scopes = loaded;
            } else {
                scopes = new HashMap<>();
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to load playlists");
            scopes = new HashMap<>();
        }
    }

    public synchronized void save() {
        if (playlistFile.getParentFile() != null && !playlistFile.getParentFile().exists()) {
            playlistFile.getParentFile().mkdirs();
        }
        try (FileWriter writer = new FileWriter(playlistFile)) {
            gson.toJson(scopes, writer);
        } catch (IOException e) {
            plugin.getLogger().at(Level.SEVERE).withCause(e).log("Failed to save playlists");
        }
    }

    private File resolvePlaylistFile() {
        Path baseDir = MediaRadioPlugin.resolveRuntimeBasePath();
        Path target = baseDir.resolve("playlists.json").toAbsolutePath();
        return target.toFile();
    }

    public synchronized PlaylistScope getScope(String scopeId) {
        if (scopeId == null || scopeId.isEmpty()) {
            return new PlaylistScope();
        }
        return scopes.computeIfAbsent(scopeId, key -> new PlaylistScope());
    }

    public synchronized List<Playlist> getPlaylists(String scopeId) {
        PlaylistScope scope = getScope(scopeId);
        if (scope.playlists == null) {
            scope.playlists = new ArrayList<>();
        }
        return scope.playlists;
    }

    public synchronized Playlist getPlaylist(String scopeId, String playlistId) {
        if (playlistId == null || playlistId.isEmpty()) {
            return null;
        }
        for (Playlist playlist : getPlaylists(scopeId)) {
            if (playlistId.equals(playlist.id)) {
                return playlist;
            }
        }
        return null;
    }

    public synchronized Playlist createPlaylist(String scopeId, String name) {
        Playlist playlist = new Playlist();
        playlist.id = UUID.randomUUID().toString();
        playlist.name = (name == null || name.isEmpty()) ? "New Playlist" : name;
        playlist.items = new ArrayList<>();
        getPlaylists(scopeId).add(playlist);
        save();
        return playlist;
    }

    public synchronized boolean deletePlaylist(String scopeId, String playlistId) {
        boolean removed = getPlaylists(scopeId).removeIf(p -> playlistId.equals(p.id));
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized boolean updatePlaylist(String scopeId, String playlistId, String name, String description,
            String iconAssetPath) {
        Playlist playlist = getPlaylist(scopeId, playlistId);
        if (playlist == null) {
            return false;
        }
        if (name != null && !name.isEmpty()) {
            playlist.name = name;
        }
        if (description != null) {
            playlist.description = description;
        }
        if (iconAssetPath != null) {
            playlist.iconAssetPath = iconAssetPath;
        }
        save();
        return true;
    }

    public synchronized boolean addItem(String scopeId, String playlistId, PlaylistItem item) {
        if (item == null || item.url == null || item.url.isEmpty()) {
            return false;
        }
        Playlist playlist = getPlaylist(scopeId, playlistId);
        if (playlist == null) {
            return false;
        }
        if (playlist.items == null) {
            playlist.items = new ArrayList<>();
        }
        playlist.items.add(item);
        save();
        return true;
    }

    public synchronized boolean removeItem(String scopeId, String playlistId, int index) {
        Playlist playlist = getPlaylist(scopeId, playlistId);
        if (playlist == null || playlist.items == null) {
            return false;
        }
        if (index < 0 || index >= playlist.items.size()) {
            return false;
        }
        playlist.items.remove(index);
        save();
        return true;
    }

    public synchronized boolean moveItem(String scopeId, String playlistId, int fromIndex, int toIndex) {
        Playlist playlist = getPlaylist(scopeId, playlistId);
        if (playlist == null || playlist.items == null) {
            return false;
        }
        int size = playlist.items.size();
        if (fromIndex < 0 || fromIndex >= size || toIndex < 0 || toIndex >= size || fromIndex == toIndex) {
            return false;
        }
        PlaylistItem item = playlist.items.remove(fromIndex);
        playlist.items.add(toIndex, item);
        save();
        return true;
    }

    public synchronized QueueState getQueue(String scopeId) {
        PlaylistScope scope = getScope(scopeId);
        if (scope.queue == null) {
            scope.queue = new QueueState();
        }
        if (scope.queue.items == null) {
            scope.queue.items = new ArrayList<>();
        }
        return scope.queue;
    }

    public synchronized void setQueue(String scopeId, List<PlaylistItem> items, int index,
            String sourceScopeId, String sourcePlaylistId) {
        QueueState queue = getQueue(scopeId);
        queue.items = new ArrayList<>(items != null ? items : List.of());
        queue.index = clampIndex(index, queue.items.size());
        queue.sourceScopeId = sourceScopeId;
        queue.sourcePlaylistId = sourcePlaylistId;
        save();
    }

    public synchronized void clearQueue(String scopeId) {
        QueueState queue = getQueue(scopeId);
        queue.items = new ArrayList<>();
        queue.index = 0;
        queue.sourceScopeId = null;
        queue.sourcePlaylistId = null;
        save();
    }

    public synchronized void setQueueIndex(String scopeId, int index) {
        QueueState queue = getQueue(scopeId);
        queue.index = clampIndex(index, queue.items != null ? queue.items.size() : 0);
        save();
    }

    public synchronized void setLoopMode(String scopeId, LoopMode loopMode) {
        QueueState queue = getQueue(scopeId);
        queue.loopMode = loopMode != null ? loopMode : LoopMode.OFF;
        save();
    }

    public synchronized void setShuffle(String scopeId, boolean shuffle) {
        QueueState queue = getQueue(scopeId);
        queue.shuffle = shuffle;
        if (shuffle && queue.items != null && queue.items.size() > 1) {
            Collections.shuffle(queue.items);
        }
        save();
    }

    public synchronized boolean isUrlReferenced(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        for (PlaylistScope scope : scopes.values()) {
            if (scope == null) {
                continue;
            }
            if (scope.queue != null && scope.queue.items != null) {
                for (PlaylistItem item : scope.queue.items) {
                    if (item != null && url.equals(item.url)) {
                        return true;
                    }
                }
            }
            if (scope.playlists != null) {
                for (Playlist playlist : scope.playlists) {
                    if (playlist == null || playlist.items == null) {
                        continue;
                    }
                    for (PlaylistItem item : playlist.items) {
                        if (item != null && url.equals(item.url)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private int clampIndex(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        if (index < 0) {
            return 0;
        }
        if (index >= size) {
            return size - 1;
        }
        return index;
    }

    public static PlaylistItem fromSong(MediaLibrary.SavedSong song) {
        if (song == null) {
            return null;
        }
        PlaylistItem item = new PlaylistItem();
        item.url = song.url;
        item.title = song.title;
        item.artist = song.artist;
        item.thumbnailUrl = song.thumbnailUrl;
        item.trackId = song.trackId;
        item.thumbnailAssetPath = song.thumbnailAssetPath;
        item.customTitle = song.customTitle;
        item.customArtist = song.customArtist;
        item.customDescription = song.customDescription;
        item.customIcon = song.customIcon;
        return item;
    }

    public static class PlaylistScope {
        public List<Playlist> playlists = new ArrayList<>();
        public QueueState queue = new QueueState();
    }

    public static class Playlist {
        public String id;
        public String name;
        public String description;
        public String iconAssetPath;
        public List<PlaylistItem> items = new ArrayList<>();
    }

    public static class PlaylistItem {
        public String url;
        public String title;
        public String artist;
        public String thumbnailUrl;
        public String trackId;
        public String thumbnailAssetPath;
        public String customTitle;
        public String customArtist;
        public String customDescription;
        public String customIcon;
    }

    public static class QueueState {
        public List<PlaylistItem> items = new ArrayList<>();
        public int index = 0;
        public boolean shuffle = false;
        public LoopMode loopMode = LoopMode.OFF;
        public String sourceScopeId;
        public String sourcePlaylistId;
    }

    public enum LoopMode {
        OFF,
        ONE,
        ALL
    }
}
