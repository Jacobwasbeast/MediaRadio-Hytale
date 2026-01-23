package dev.jacobwasbeast.manager;

import java.util.concurrent.ScheduledFuture;
import javax.annotation.Nullable;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Represents the playback state for a single radio block.
 * Tracks current chunk, elapsed time, and pause state.
 */
public class PlaybackSession {
    private final String trackId;
    @Nullable
    private final Vector3i blockPosition;
    @Nullable
    private final PlayerRef playerRef;
    private final String title;
    private final String artist;
    private final String thumbnailUrl;
    private final String url;
    private final int totalChunks;
    private final int chunkDurationMs;
    private final long totalDurationMs;

    private int currentChunk = 0;
    private int missingAssetRetries = 0;
    private long currentChunkStartMs = 0;
    private long pausedAtMs = 0;
    private long pausedOffsetMs = 0;
    private long lastScheduleLagMs = 0;
    private boolean isPaused = false;
    private boolean pausedByUser = false;
    private boolean isStopped = true;
    private boolean loopEnabled = false;
    private ScheduledFuture<?> scheduledNextChunk;

    public PlaybackSession(String trackId, Vector3i blockPosition, int totalChunks, int chunkDurationMs) {
        this(trackId, blockPosition, totalChunks, chunkDurationMs, "", "", "", "", 0);
    }

    public PlaybackSession(String trackId, Vector3i blockPosition, int totalChunks, int chunkDurationMs, String title,
            String artist, String thumbnailUrl, String url, long durationMs) {
        this.trackId = trackId;
        this.blockPosition = blockPosition;
        this.playerRef = null;
        this.title = title != null ? title : "";
        this.artist = artist != null ? artist : "";
        this.thumbnailUrl = thumbnailUrl != null ? thumbnailUrl : "";
        this.url = url != null ? url : "";
        this.totalChunks = totalChunks;
        this.chunkDurationMs = chunkDurationMs;
        this.totalDurationMs = durationMs > 0 ? durationMs : (long) totalChunks * chunkDurationMs;
    }

    public PlaybackSession(String trackId, PlayerRef playerRef, int totalChunks, int chunkDurationMs, String title,
            String artist, String thumbnailUrl, String url, long durationMs) {
        this.trackId = trackId;
        this.blockPosition = null;
        this.playerRef = playerRef;
        this.title = title != null ? title : "";
        this.artist = artist != null ? artist : "";
        this.thumbnailUrl = thumbnailUrl != null ? thumbnailUrl : "";
        this.url = url != null ? url : "";
        this.totalChunks = totalChunks;
        this.chunkDurationMs = chunkDurationMs;
        this.totalDurationMs = durationMs > 0 ? durationMs : (long) totalChunks * chunkDurationMs;
    }

    public String getTrackId() {
        return trackId;
    }

    @Nullable
    public Vector3i getBlockPosition() {
        return blockPosition;
    }

    @Nullable
    public PlayerRef getPlayerRef() {
        return playerRef;
    }

    public boolean isPlayerBound() {
        return playerRef != null;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getUrl() {
        return url;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public int getChunkDurationMs() {
        return chunkDurationMs;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public int getCurrentChunk() {
        return currentChunk;
    }

    public void setCurrentChunk(int chunk) {
        this.currentChunk = Math.max(0, Math.min(chunk, totalChunks - 1));
    }

    public boolean isPaused() {
        return isPaused;
    }

    public boolean isPausedByUser() {
        return pausedByUser;
    }

    public boolean isLoopEnabled() {
        return loopEnabled;
    }

    public void setLoopEnabled(boolean loopEnabled) {
        this.loopEnabled = loopEnabled;
    }

    public boolean isStopped() {
        return isStopped;
    }

    public boolean isPlaying() {
        return !isPaused && !isStopped;
    }

    /**
     * Start or resume playback
     */
    public void play() {
        if (isStopped) {
            // Fresh start
            currentChunk = 0;
            missingAssetRetries = 0;
            currentChunkStartMs = System.currentTimeMillis();
            pausedOffsetMs = 0;
            lastScheduleLagMs = 0;
        } else if (isPaused) {
            // Resume within current chunk
            currentChunkStartMs = System.currentTimeMillis() - pausedOffsetMs;
        }
        isPaused = false;
        pausedByUser = false;
        isStopped = false;
    }

    /**
     * Pause playback at current position
     */
    public void pause() {
        pauseInternal(false);
    }

    public void pauseByUser() {
        pauseInternal(true);
    }

    public void pauseByUnheld() {
        pauseInternal(false);
    }

    private void pauseInternal(boolean byUser) {
        if (isPlaying()) {
            isPaused = true;
            pausedByUser = byUser;
            pausedAtMs = System.currentTimeMillis();
            pausedOffsetMs = Math.max(0, pausedAtMs - currentChunkStartMs);
            cancelScheduledChunk();
        }
    }

    /**
     * Stop playback and reset to beginning
     */
    public void stop() {
        isStopped = true;
        isPaused = false;
        currentChunk = 0;
        currentChunkStartMs = 0;
        pausedAtMs = 0;
        pausedOffsetMs = 0;
        pausedByUser = false;
        missingAssetRetries = 0;
        lastScheduleLagMs = 0;
        cancelScheduledChunk();
    }

    public void resetMissingAssetRetries() {
        missingAssetRetries = 0;
    }

    public int incrementMissingAssetRetries() {
        return ++missingAssetRetries;
    }

    /**
     * Seek to a specific time in milliseconds
     */
    public void seekToMs(long positionMs) {
        if (positionMs < 0)
            positionMs = 0;
        if (positionMs > totalDurationMs)
            positionMs = totalDurationMs - 1;

        int targetChunk = (int) (positionMs / chunkDurationMs);
        setCurrentChunk(targetChunk);
        long offsetInChunk = positionMs - ((long) targetChunk * chunkDurationMs);

        if (isPaused) {
            pausedOffsetMs = offsetInChunk;
            pausedAtMs = System.currentTimeMillis();
        } else if (!isStopped) {
            currentChunkStartMs = System.currentTimeMillis() - offsetInChunk;
            pausedOffsetMs = 0;
        } else {
            currentChunkStartMs = 0;
            pausedOffsetMs = 0;
        }
    }

    /**
     * Seek to a specific chunk
     */
    public void seekToChunk(int chunk) {
        setCurrentChunk(chunk);
        seekToMs((long) chunk * chunkDurationMs);
    }

    /**
     * Get current playback position in milliseconds
     */
    public long getCurrentPositionMs() {
        if (isStopped)
            return 0;
        long offsetInChunk = isPaused ? pausedOffsetMs : Math.max(0, System.currentTimeMillis() - currentChunkStartMs);
        long position = ((long) currentChunk * chunkDurationMs) + offsetInChunk;
        return Math.min(position, totalDurationMs);
    }

    /**
     * Get playback progress as 0.0 to 1.0
     */
    public double getProgress() {
        if (totalDurationMs == 0)
            return 0;
        return Math.min(1.0, (double) getCurrentPositionMs() / totalDurationMs);
    }

    /**
     * Advance to next chunk. Returns true if there are more chunks.
     */
    public boolean advanceChunk() {
        if (currentChunk < totalChunks - 1) {
            currentChunk++;
            currentChunkStartMs = System.currentTimeMillis();
            pausedOffsetMs = 0;
            return true;
        }
        if (loopEnabled && totalChunks > 0) {
            currentChunk = 0;
            currentChunkStartMs = System.currentTimeMillis();
            pausedOffsetMs = 0;
            return true;
        }
        // Reached end
        stop();
        return false;
    }

    public void markChunkStart() {
        currentChunkStartMs = System.currentTimeMillis();
        pausedOffsetMs = 0;
    }

    public long getCurrentChunkStartMs() {
        return currentChunkStartMs;
    }

    public long getLastScheduleLagMs() {
        return lastScheduleLagMs;
    }

    public void setLastScheduleLagMs(long lastScheduleLagMs) {
        this.lastScheduleLagMs = Math.max(0, lastScheduleLagMs);
    }

    public ScheduledFuture<?> getScheduledNextChunk() {
        return scheduledNextChunk;
    }

    public void setScheduledNextChunk(ScheduledFuture<?> future) {
        cancelScheduledChunk();
        this.scheduledNextChunk = future;
    }

    private void cancelScheduledChunk() {
        if (scheduledNextChunk != null && !scheduledNextChunk.isDone()) {
            scheduledNextChunk.cancel(false);
        }
        scheduledNextChunk = null;
    }

    /**
     * Get the chunk filename for a given chunk index
     */
    public String getChunkTrackId(int chunkIndex) {
        return trackId + "_Chunk_" + String.format("%03d", chunkIndex);
    }

    /**
     * Get the current chunk's track ID
     */
    public String getCurrentChunkTrackId() {
        return getChunkTrackId(currentChunk);
    }
}
