package dev.jacobwasbeast.manager;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.MediaRadioPlugin;
import dev.jacobwasbeast.util.RadioItemUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * Manages active playback sessions for all radio blocks.
 * Handles chunk scheduling, play/pause/stop/seek operations.
 */
public class MediaPlaybackManager {
    private final MediaRadioPlugin plugin;
    private final Map<String, PlaybackSession> activeBlockSessions = new ConcurrentHashMap<>();
    private final Map<UUID, PlaybackSession> activePlayerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> loopPreferences = new ConcurrentHashMap<>();
    private final Map<UUID, Float> volumePreferences = new ConcurrentHashMap<>();
    private static final float MAX_VOLUME = 5.0f;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static final int MAX_MISSING_ASSET_RETRIES = 10;
    private static final long MISSING_ASSET_RETRY_DELAY_MS = 500;

    public MediaPlaybackManager(MediaRadioPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Get unique key for a block position
     */
    private String getBlockKey(Vector3i pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * Get active session for a block, or null if none
     */
    public PlaybackSession getSession(Vector3i pos) {
        return activeBlockSessions.get(getBlockKey(pos));
    }

    public PlaybackSession getSession(UUID playerId) {
        return activePlayerSessions.get(playerId);
    }

    /**
     * Start playing a track at a block position
     */
    public void play(String trackId, Vector3i blockPos, int totalChunks, int chunkDurationMs,
            Store<EntityStore> store) {
        String key = getBlockKey(blockPos);

        // Stop any existing session at this block
        PlaybackSession existing = activeBlockSessions.get(key);
        if (existing != null) {
            existing.stop();
        }

        // Create new session
        PlaybackSession session = new PlaybackSession(trackId, blockPos, totalChunks, chunkDurationMs);
        activeBlockSessions.put(key, session);

        // Start playback
        session.play();
        playCurrentChunk(session, store);

        plugin.getLogger().at(Level.INFO).log("Started playback: track=%s, chunks=%d, duration=%dms each",
                trackId, totalChunks, chunkDurationMs);
    }

    /**
     * Start playing a track for a player (handheld radio)
     */
    public void playForPlayer(MediaInfo mediaInfo, PlayerRef playerRef, int totalChunks, int chunkDurationMs,
            Store<EntityStore> store) {
        UUID playerId = playerRef.getUuid();

        if (!shouldKeepPlaying(playerRef, store)) {
            plugin.getLogger().at(Level.INFO).log("Skipping playback for %s, radio not held.",
                    playerRef.getUsername());
            return;
        }

        PlaybackSession existing = activePlayerSessions.get(playerId);
        if (existing != null) {
            existing.stop();
        }

        PlaybackSession session = new PlaybackSession(
                mediaInfo.trackId,
                playerRef,
                totalChunks,
                chunkDurationMs,
                mediaInfo.title,
                mediaInfo.artist,
                mediaInfo.thumbnailAssetPath,
                mediaInfo.url,
                mediaInfo.duration * 1000L);
        session.setLoopEnabled(loopPreferences.getOrDefault(playerId, false));
        activePlayerSessions.put(playerId, session);

        session.play();
        playCurrentChunk(session, store);

        plugin.getLogger().at(Level.INFO).log("Started playback for %s: track=%s, chunks=%d, duration=%dms each",
                playerRef.getUsername(), mediaInfo.trackId, totalChunks, chunkDurationMs);
    }

    /**
     * Resume playback at a block (after pause)
     */
    public void resume(Vector3i blockPos, Store<EntityStore> store) {
        PlaybackSession session = getSession(blockPos);
        if (session != null && session.isPaused()) {
            session.play();
            playCurrentChunk(session, store);
            plugin.getLogger().at(Level.INFO).log("Resumed playback at chunk %d", session.getCurrentChunk());
        }
    }

    public void resume(PlayerRef playerRef, Store<EntityStore> store) {
        PlaybackSession session = getSession(playerRef.getUuid());
        if (session != null && session.isPaused()) {
            session.play();
            playCurrentChunk(session, store);
            plugin.getLogger().at(Level.INFO).log("Resumed playback for %s at chunk %d",
                    playerRef.getUsername(), session.getCurrentChunk());
        }
    }

    /**
     * Pause playback at a block
     */
    public void pause(Vector3i blockPos) {
        PlaybackSession session = getSession(blockPos);
        if (session != null && session.isPlaying()) {
            session.pauseByUnheld();
            plugin.getLogger().at(Level.INFO).log("Paused playback at chunk %d (%.1f%%)",
                    session.getCurrentChunk(), session.getProgress() * 100);
        }
    }

    public void pause(PlayerRef playerRef) {
        pauseByUser(playerRef);
    }

    public void pauseByUser(PlayerRef playerRef) {
        PlaybackSession session = getSession(playerRef.getUuid());
        if (session != null && session.isPlaying()) {
            session.pauseByUser();
            plugin.getLogger().at(Level.INFO).log("Paused playback for %s at chunk %d (%.1f%%)",
                    playerRef.getUsername(), session.getCurrentChunk(), session.getProgress() * 100);
        }
    }

    public void pauseForUnheld(PlayerRef playerRef) {
        PlaybackSession session = getSession(playerRef.getUuid());
        if (session != null && session.isPlaying()) {
            session.pauseByUnheld();
            plugin.getLogger().at(Level.INFO).log("Paused playback for %s at chunk %d (%.1f%%)",
                    playerRef.getUsername(), session.getCurrentChunk(), session.getProgress() * 100);
        }
    }

    /**
     * Stop playback at a block
     */
    public void stop(Vector3i blockPos) {
        String key = getBlockKey(blockPos);
        PlaybackSession session = activeBlockSessions.remove(key);
        if (session != null) {
            session.stop();
            plugin.getLogger().at(Level.INFO).log("Stopped playback");
        }
    }

    public void stop(PlayerRef playerRef) {
        stopForPlayer(playerRef.getUuid());
    }

    public void stopForPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PlaybackSession session = activePlayerSessions.remove(playerId);
        if (session != null) {
            session.stop();
            plugin.getLogger().at(Level.INFO).log("Stopped playback for player %s", playerId);
        }
    }

    public boolean isLoopEnabled(UUID playerId) {
        return loopPreferences.getOrDefault(playerId, false);
    }

    public void setLoopEnabled(UUID playerId, boolean enabled) {
        if (playerId == null) {
            return;
        }
        loopPreferences.put(playerId, enabled);
        PlaybackSession session = activePlayerSessions.get(playerId);
        if (session != null) {
            session.setLoopEnabled(enabled);
        }
    }

    public float getVolume(UUID playerId) {
        if (playerId == null) {
            return 1.0f;
        }
        return volumePreferences.getOrDefault(playerId, 1.0f);
    }

    public void setVolume(UUID playerId, float volume) {
        if (playerId == null) {
            return;
        }
        float clamped = Math.max(0.0f, Math.min(MAX_VOLUME, volume));
        volumePreferences.put(playerId, clamped);
    }

    public float setVolumePercent(UUID playerId, float percent) {
        if (playerId == null) {
            return 100.0f;
        }
        float clampedPercent = Math.max(0.0f, Math.min(MAX_VOLUME * 100.0f, percent));
        setVolume(playerId, clampedPercent / 100.0f);
        return clampedPercent;
    }

    /**
     * Seek to a position (0.0 to 1.0)
     */
    public void seek(Vector3i blockPos, double progress, Store<EntityStore> store) {
        PlaybackSession session = getSession(blockPos);
        if (session != null && !session.isStopped()) {
            long targetMs = (long) (progress * session.getTotalDurationMs());
            session.seekToMs(targetMs);

            // If playing, play the new chunk
            if (session.isPlaying()) {
                playCurrentChunk(session, store);
            }

            plugin.getLogger().at(Level.INFO).log("Seeked to %.1f%% (chunk %d)",
                    progress * 100, session.getCurrentChunk());
        }
    }

    public void seek(PlayerRef playerRef, double progress, Store<EntityStore> store) {
        PlaybackSession session = getSession(playerRef.getUuid());
        if (session != null && !session.isStopped()) {
            long targetMs = (long) (progress * session.getTotalDurationMs());
            session.seekToMs(targetMs);

            if (session.isPlaying()) {
                playCurrentChunk(session, store);
            }

            plugin.getLogger().at(Level.INFO).log("Seeked for %s to %.1f%% (chunk %d)",
                    playerRef.getUsername(), progress * 100, session.getCurrentChunk());
        }
    }

    /**
     * Get playback status for UI
     */
    public PlaybackStatus getStatus(Vector3i blockPos) {
        PlaybackSession session = getSession(blockPos);
        if (session == null) {
            return new PlaybackStatus(false, false, true, 0, 0, 0);
        }
        return new PlaybackStatus(
                session.isPlaying(),
                session.isPaused(),
                session.isStopped(),
                session.getProgress(),
                session.getCurrentPositionMs(),
                session.getTotalDurationMs());
    }

    public PlaybackStatus getStatus(UUID playerId) {
        PlaybackSession session = getSession(playerId);
        if (session == null) {
            return new PlaybackStatus(false, false, true, 0, 0, 0);
        }
        return new PlaybackStatus(
                session.isPlaying(),
                session.isPaused(),
                session.isStopped(),
                session.getProgress(),
                session.getCurrentPositionMs(),
                session.getTotalDurationMs());
    }

    /**
     * Play the current chunk and schedule the next one
     */
    private void playCurrentChunk(PlaybackSession session, Store<EntityStore> store) {
        if (!session.isPlaying()) {
            return;
        }

        String chunkTrackId = session.getCurrentChunkTrackId();

        // Look up chunk SoundEvent
        int soundEventIndex = SoundEvent.getAssetMap().getIndex(chunkTrackId);

        if (soundEventIndex < 0) {
            plugin.getLogger().at(Level.WARNING).log("Chunk SoundEvent not found: %s", chunkTrackId);
            scheduleMissingAssetRetry(session, store);
            return;
        }
        session.resetMissingAssetRetries();
        session.markChunkStart();

        if (session.isPlayerBound()) {
            PlayerRef playerRef = session.getPlayerRef();
            if (playerRef == null || !shouldKeepPlaying(playerRef, store)) {
                if (playerRef != null) {
                    pauseForUnheld(playerRef);
                }
                return;
            }

            float volume = getVolume(playerRef.getUuid());
            SoundUtil.playSoundEvent2dToPlayer(playerRef, soundEventIndex, SoundCategory.Music, volume, 1.0f);
            plugin.getLogger().at(Level.INFO).log("Playing chunk %d/%d for %s: %s",
                    session.getCurrentChunk() + 1, session.getTotalChunks(), playerRef.getUsername(), chunkTrackId);
        } else {
            Vector3i pos = session.getBlockPosition();
            if (pos == null) {
                return;
            }

            // Play the chunk as 3D positional sound
            if (store instanceof ComponentAccessor) {
                SoundUtil.playSoundEvent3d(
                        soundEventIndex,
                        SoundCategory.Music,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        (ComponentAccessor<EntityStore>) store);

                plugin.getLogger().at(Level.INFO).log("Playing chunk %d/%d: %s",
                        session.getCurrentChunk() + 1, session.getTotalChunks(), chunkTrackId);
            }
        }

        // Schedule next chunk
        scheduleNextChunk(session, store);
    }

    /**
     * Schedule the next chunk to play after current one finishes
     */
    private void scheduleNextChunk(PlaybackSession session, Store<EntityStore> store) {
        long delayMs = session.getChunkDurationMs();

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (session.isPlaying() && session.advanceChunk()) {
                // Use world thread to play sound
                store.getExternalData().getWorld().execute(() -> {
                    playCurrentChunk(session, store);
                });
            }
        }, delayMs, TimeUnit.MILLISECONDS);

        session.setScheduledNextChunk(future);
    }

    private void scheduleMissingAssetRetry(PlaybackSession session, Store<EntityStore> store) {
        int attempts = session.incrementMissingAssetRetries();
        if (attempts > MAX_MISSING_ASSET_RETRIES) {
            plugin.getLogger().at(Level.WARNING).log("SoundEvent still missing after %d attempts, stopping playback.", attempts);
            session.stop();
            return;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (!session.isPlaying()) {
                return;
            }
            store.getExternalData().getWorld().execute(() -> {
                playCurrentChunk(session, store);
            });
        }, MISSING_ASSET_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);

        session.setScheduledNextChunk(future);
    }

    /**
     * Shutdown the scheduler
     */
    public void shutdown() {
        // Stop all sessions
        for (PlaybackSession session : activeBlockSessions.values()) {
            session.stop();
        }
        activeBlockSessions.clear();
        for (PlaybackSession session : activePlayerSessions.values()) {
            session.stop();
        }
        activePlayerSessions.clear();
        scheduler.shutdown();
    }

    private boolean shouldKeepPlaying(PlayerRef playerRef, Store<EntityStore> store) {
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        var playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return false;
        }
        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null) {
            return false;
        }
        return RadioItemUtil.isRadioHeld(player);
    }

    /**
     * Status record for UI
     */
    public record PlaybackStatus(
            boolean isPlaying,
            boolean isPaused,
            boolean isStopped,
            double progress,
            long positionMs,
            long durationMs) {
    }
}
