package dev.jacobwasbeast.manager;

public class MediaInfo {
    public final String trackId;
    public final String url;
    public final String title;
    public final String artist;
    public final String thumbnailUrl;
    public final long duration;
    public final int chunkCount;
    public final String thumbnailAssetPath;

    public MediaInfo(String trackId, String url, String title, String artist, String thumbnailUrl, long duration,
            int chunkCount, String thumbnailAssetPath) {
        this.trackId = trackId;
        this.url = url;
        this.title = title;
        this.artist = artist;
        this.thumbnailUrl = thumbnailUrl;
        this.duration = duration;
        this.chunkCount = chunkCount;
        this.thumbnailAssetPath = thumbnailAssetPath;
    }
}
