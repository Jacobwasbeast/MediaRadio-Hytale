package dev.jacobwasbeast.manager;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.MediaRadioPlugin;
import dev.jacobwasbeast.util.RadioItemUtil;
import dev.jacobwasbeast.util.VolumeUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;

import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Manages active playback sessions for all radio blocks.
 * Handles chunk scheduling, play/pause/stop/seek operations.
 */
public class MediaPlaybackManager {
    private final MediaRadioPlugin plugin;
    private final Map<String, PlaybackSession> activeBlockSessions = new ConcurrentHashMap<>();
    private final Map<UUID, PlaybackSession> activePlayerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> loopPreferences = new ConcurrentHashMap<>();
    private final Map<UUID, Float> playerVolumePreferences = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static final int MAX_MISSING_ASSET_RETRIES = 40;
    private static final long MISSING_ASSET_RETRY_DELAY_MS = 500;
    private static final long BASE_CHUNK_OVERLAP_MS = 15;
    private static final long MAX_CHUNK_OVERLAP_MS = 120;

    private int audioMarkerRoleIndex = Integer.MIN_VALUE;

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
        PlaybackSession existing = activeBlockSessions.remove(key);
        if (existing != null) {
            existing.stop();
            handleSessionEnded(existing, store);
        }

        // Create new session
        PlaybackSession session = new PlaybackSession(trackId, blockPos, totalChunks, chunkDurationMs);
        activeBlockSessions.put(key, session);

        // Start playback
        session.play();
        session.setVolume(getVolume(blockPos, store));
        playCurrentChunk(session, store);

        plugin.getLogger().at(Level.INFO).log("Started playback: track=%s, chunks=%d, duration=%dms each",
                trackId, totalChunks, chunkDurationMs);
    }

    /**
     * Start playing a track at a block position with metadata
     */
    public void playAtBlock(MediaInfo mediaInfo, Vector3i blockPos, int chunkDurationMs, Store<EntityStore> store) {
        if (mediaInfo == null || blockPos == null) {
            return;
        }
        int totalChunks = mediaInfo.chunkCount;
        if (totalChunks <= 0) {
            plugin.getLogger().at(Level.WARNING).log("Track info not found in library: %s", mediaInfo.trackId);
            return;
        }

        String key = getBlockKey(blockPos);
        PlaybackSession existing = activeBlockSessions.remove(key);
        if (existing != null) {
            existing.stop();
            handleSessionEnded(existing, store);
        }

        PlaybackSession session = new PlaybackSession(
                mediaInfo.trackId,
                blockPos,
                totalChunks,
                chunkDurationMs,
                mediaInfo.title,
                mediaInfo.artist,
                mediaInfo.thumbnailAssetPath,
                mediaInfo.url,
                mediaInfo.duration * 1000L);
        activeBlockSessions.put(key, session);

        session.play();
        session.setVolume(getVolume(blockPos, store));
        playCurrentChunk(session, store);

        plugin.getLogger().at(Level.INFO).log("Started block playback: track=%s, chunks=%d, duration=%dms each",
                mediaInfo.trackId, totalChunks, chunkDurationMs);
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

        PlaybackSession existing = activePlayerSessions.remove(playerId);
        if (existing != null) {
            existing.stop();
            handleSessionEnded(existing, store);
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
        session.setVolume(getPlayerVolume(playerId));
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
    public void stop(Vector3i blockPos, Store<EntityStore> store) {
        String key = getBlockKey(blockPos);
        PlaybackSession session = activeBlockSessions.remove(key);
        if (session != null) {
            session.stop();
            handleSessionEnded(session, store);
            plugin.getLogger().at(Level.INFO).log("Stopped playback");
        }
    }

    public void stop(PlayerRef playerRef, Store<EntityStore> store) {
        stopForPlayer(playerRef.getUuid(), store);
    }

    public void stopForPlayer(UUID playerId, Store<EntityStore> store) {
        if (playerId == null) {
            return;
        }
        PlaybackSession session = activePlayerSessions.remove(playerId);
        if (session != null) {
            session.stop();
            handleSessionEnded(session, store);
            plugin.getLogger().at(Level.INFO).log("Stopped playback for player %s", playerId);
        }
    }

    public int stopAllForTrackId(String trackId) {
        if (trackId == null || trackId.isEmpty()) {
            return 0;
        }
        java.util.ArrayList<PlaybackSession> toStop = new java.util.ArrayList<>();
        for (PlaybackSession session : activePlayerSessions.values()) {
            if (trackId.equals(session.getTrackId()) && !session.isStopped()) {
                toStop.add(session);
            }
        }
        for (PlaybackSession session : activeBlockSessions.values()) {
            if (trackId.equals(session.getTrackId()) && !session.isStopped()) {
                toStop.add(session);
            }
        }
        for (PlaybackSession session : toStop) {
            session.stop();
            removeSession(session);
        }
        if (!toStop.isEmpty()) {
            plugin.getLogger().at(Level.INFO).log(
                    "Stopped %d playback session(s) for track %s", toStop.size(), trackId);
        }
        return toStop.size();
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

    public void setLoopEnabled(Vector3i blockPos, boolean enabled) {
        if (blockPos == null) {
            return;
        }
        PlaybackSession session = getSession(blockPos);
        if (session != null) {
            session.setLoopEnabled(enabled);
        }
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

    public void updateComponent(Vector3i pos, Store<EntityStore> store,
            java.util.function.Consumer<dev.jacobwasbeast.component.RadioComponent> updater) {
        if (pos == null || store == null || updater == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> blockRef = getOrCreateBlockEntityRef(chunkStore, pos);
        if (blockRef == null || !blockRef.isValid()) {
            return;
        }
        chunkStore.getStore().ensureComponent(blockRef, dev.jacobwasbeast.component.RadioComponent.COMPONENT_TYPE);
        dev.jacobwasbeast.component.RadioComponent component = chunkStore.getStore()
                .getComponent(blockRef, dev.jacobwasbeast.component.RadioComponent.COMPONENT_TYPE);
        if (component == null) {
            return;
        }
        updater.accept(component);
    }

    private float getVolume(Vector3i pos, Store<EntityStore> store) {
        if (pos == null || store == null) {
            return VolumeUtil.percentToEventDb(VolumeUtil.DEFAULT_PERCENT);
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return VolumeUtil.percentToEventDb(VolumeUtil.DEFAULT_PERCENT);
        }
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> blockRef = getOrCreateBlockEntityRef(chunkStore, pos);
        if (blockRef == null || !blockRef.isValid()) {
            return VolumeUtil.percentToEventDb(VolumeUtil.DEFAULT_PERCENT);
        }
        dev.jacobwasbeast.component.RadioComponent component = chunkStore.getStore()
                .getComponent(blockRef, dev.jacobwasbeast.component.RadioComponent.COMPONENT_TYPE);
        if (component == null) {
            chunkStore.getStore().ensureComponent(blockRef, dev.jacobwasbeast.component.RadioComponent.COMPONENT_TYPE);
            component = chunkStore.getStore()
                    .getComponent(blockRef, dev.jacobwasbeast.component.RadioComponent.COMPONENT_TYPE);
        }
        return component != null ? component.getVolume() : VolumeUtil.percentToEventDb(VolumeUtil.DEFAULT_PERCENT);
    }

    public float getBlockVolume(Vector3i pos, Store<EntityStore> store) {
        return getVolume(pos, store);
    }

    public float getPlayerVolume(UUID playerId) {
        if (playerId == null) {
            return VolumeUtil.percentToEventDb(VolumeUtil.DEFAULT_PERCENT);
        }
        return playerVolumePreferences.getOrDefault(playerId,
                VolumeUtil.percentToEventDb(VolumeUtil.DEFAULT_PERCENT));
    }

    public void setPlayerVolume(UUID playerId, float volumeDb) {
        if (playerId == null) {
            return;
        }
        playerVolumePreferences.put(playerId, volumeDb);
    }

    private Ref<ChunkStore> getOrCreateBlockEntityRef(ChunkStore chunkStore, Vector3i pos) {
        if (chunkStore == null || pos == null) {
            return null;
        }
        Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ()));
        if (chunkRef == null) {
            return null;
        }
        BlockComponentChunk blockComponentChunk = chunkStore.getStore().getComponent(chunkRef,
                BlockComponentChunk.getComponentType());
        if (blockComponentChunk == null) {
            return null;
        }
        int blockIndex = ChunkUtil.indexBlockInColumn(pos.getX(), pos.getY(), pos.getZ());
        Ref<ChunkStore> blockRef = blockComponentChunk.getEntityReference(blockIndex);
        if (blockRef != null && blockRef.isValid()) {
            return blockRef;
        }
        Holder<ChunkStore> holder = ChunkStore.REGISTRY.newHolder();
        holder.putComponent(BlockModule.BlockStateInfo.getComponentType(),
                new BlockModule.BlockStateInfo(blockIndex, chunkRef));
        holder.ensureComponent(dev.jacobwasbeast.component.RadioComponent.COMPONENT_TYPE);
        return chunkStore.getStore().addEntity(holder, AddReason.SPAWN);
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

    public void cleanupMarkerNpcsInWorld(World world) {
        if (world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        store.forEachEntityParallel(
                NPCEntity.getComponentType(),
                (index, archetypeChunk, commandBuffer) -> {
                    NPCEntity npc = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
                    if (npc == null) {
                        return;
                    }
                    String roleName = npc.getRoleName();
                    boolean nameMatches = roleName != null
                            && roleName.toLowerCase().contains("audio_marker");
                    if (nameMatches) {
                        commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
                    }
                });
    }

    /**
     * Play the current chunk and schedule the next one
     */

    private void playCurrentChunk(PlaybackSession session, Store<EntityStore> store) {
        if (!session.isPlaying()) {
            return;
        }

        String trackId = session.getTrackId();
        String chunkTrackId = session.getCurrentChunkTrackId();
        int chunkIndex = session.getCurrentChunk();

        // Look up chunk SoundEvent and Track Model
        // We now use a single model "medradio_marker_<trackId>" for the whole track
        String trackAppearanceId = "medradio_marker_" + trackId;

        SoundEvent soundEvent = SoundEvent.getAssetMap().getAsset(chunkTrackId);
        ModelAsset trackModel = ModelAsset.getAssetMap().getAsset(trackAppearanceId);

        if (soundEvent == null || trackModel == null) {
            // Log less frequently or debug
            if (session.getMissingAssetRetries() % 5 == 0) {
                plugin.getLogger().at(Level.WARNING).log("Chunk Asset not ready: %s (Sound or Model missing)",
                        chunkTrackId);
            }
            scheduleMissingAssetRetry(session, store);
            return;
        }
        session.resetMissingAssetRetries();
        session.markChunkStart();


        // Lazy load audio_marker role index
        if (audioMarkerRoleIndex == Integer.MIN_VALUE) {
            audioMarkerRoleIndex = NPCPlugin.get().getIndex("audio_marker");
            if (audioMarkerRoleIndex == -1) {
                plugin.getLogger().at(Level.SEVERE).log("Failed to load audio_marker NPC role!");
                return;
            }
            plugin.getLogger().at(Level.INFO).log("Loaded audio_marker role with index: " + audioMarkerRoleIndex);
        }

        // Ensure Marker Entity Exists
        com.hypixel.hytale.component.Ref<EntityStore> marker = session.getMarkerEntity();
        if (marker == null || !marker.isValid()) {
            Vector3d spawnPos = null;
            if (session.isPlayerBound()) {
                PlayerRef pRef = session.getPlayerRef();
                if (pRef != null && pRef.isValid()) {
                    var pEntRef = pRef.getReference();
                    if (pEntRef != null && pEntRef.isValid() && store instanceof ComponentAccessor) {
                        TransformComponent transform = store.getComponent(pEntRef,
                                TransformComponent.getComponentType());
                        if (transform != null)
                            spawnPos = transform.getPosition();
                    }
                }
            } else {
                Vector3i pos = session.getBlockPosition();
                if (pos != null) {
                    spawnPos = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                }
            }

            if (spawnPos == null)
                return;

            it.unimi.dsi.fastutil.Pair<com.hypixel.hytale.component.Ref<EntityStore>, NPCEntity> npcPair = NPCPlugin
                    .get().spawnEntity(
                            store,
                            audioMarkerRoleIndex,
                            spawnPos,
                            new Vector3f(0f, 0f, 0f),
                            (Model) null,
                            (com.hypixel.hytale.function.consumer.TriConsumer<NPCEntity, com.hypixel.hytale.component.Ref<EntityStore>, Store<EntityStore>>) null);

            if (npcPair == null) {
                plugin.getLogger().at(Level.WARNING).log("Failed to spawn audio marker NPC!");
                return;
            }
            marker = npcPair.first();
            session.setMarkerEntity(marker);
            session.setNPCEntity(npcPair.second());

            // Make intangible/invulnerable logic if needed (borrowed from src-trial)
            store.ensureComponent(marker,
                    com.hypixel.hytale.server.core.modules.entity.component.Intangible.getComponentType());
            store.ensureComponent(marker,
                    com.hypixel.hytale.server.core.modules.entity.component.Invulnerable.getComponentType());

            // Initial appearance set
            NPCEntity.setAppearance(marker, trackAppearanceId, (ComponentAccessor<EntityStore>) store);
        }

        // Trigger Animation State for this chunk
        NPCEntity npc = session.getNPCEntity();
        if (npc != null) {
            String animationState = "PlayChunk" + chunkIndex;
            npc.playAnimation(marker, AnimationSlot.Action, animationState, (ComponentAccessor<EntityStore>) store);
        } else {
            plugin.getLogger().at(Level.WARNING).log("NPCEntity missing for animation!");
        }

        // Schedule next chunk
        scheduleNextChunk(session, store);

        // Update position if player bound
        if (session.isPlayerBound()) {
            PlayerRef pRef = session.getPlayerRef();
            if (pRef != null && pRef.isValid()) {
                var pEntRef = pRef.getReference();
                if (pEntRef != null && pEntRef.isValid()) {
                    TransformComponent pTransform = store.getComponent(pEntRef, TransformComponent.getComponentType());
                    if (pTransform != null) {
                        TransformComponent markerTransform = store.getComponent(marker,
                                TransformComponent.getComponentType());
                        if (markerTransform != null) {
                            markerTransform.setPosition(pTransform.getPosition());
                        }
                    }
                }
            }
        }
    }

    /**
     * Schedule the next chunk to play after current one finishes
     */
    private void scheduleNextChunk(PlaybackSession session, Store<EntityStore> store) {
        long lagMs = session.getLastScheduleLagMs();
        long overlapMs = BASE_CHUNK_OVERLAP_MS + lagMs;
        overlapMs = Math.min(MAX_CHUNK_OVERLAP_MS, overlapMs);
        long maxOverlap = Math.max(0, session.getChunkDurationMs() - 5);
        overlapMs = Math.min(overlapMs, maxOverlap);
        long delayMs = Math.max(0, session.getChunkDurationMs() - overlapMs);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (!session.isPlaying()) {
                return;
            }
            long expectedEnd = session.getCurrentChunkStartMs() + session.getChunkDurationMs();
            long lag = Math.max(0, System.currentTimeMillis() - expectedEnd);
            session.setLastScheduleLagMs(lag);
            if (session.advanceChunk()) {
                // Use world thread to play sound
                store.getExternalData().getWorld().execute(() -> {
                    playCurrentChunk(session, store);
                });
                return;
            }
            removeSession(session);
            // Use world thread to clean up
            store.getExternalData().getWorld().execute(() -> {
                handleSessionEnded(session, store);
            });
        }, delayMs, TimeUnit.MILLISECONDS);

        session.setScheduledNextChunk(future);
    }

    private void scheduleMissingAssetRetry(PlaybackSession session, Store<EntityStore> store) {
        int attempts = session.incrementMissingAssetRetries();
        if (attempts > MAX_MISSING_ASSET_RETRIES) {
            plugin.getLogger().at(Level.WARNING).log("SoundEvent still missing after %d attempts, stopping playback.",
                    attempts);
            session.stop();
            removeSession(session);
            store.getExternalData().getWorld().execute(() -> {
                handleSessionEnded(session, store);
            });
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

    private void removeSession(PlaybackSession session) {
        if (session == null) {
            return;
        }
        if (session.isPlayerBound()) {
            PlayerRef playerRef = session.getPlayerRef();
            if (playerRef != null) {
                activePlayerSessions.remove(playerRef.getUuid(), session);
            }
            return;
        }
        Vector3i pos = session.getBlockPosition();
        if (pos != null) {
            activeBlockSessions.remove(getBlockKey(pos), session);
        }
    }

    private void handleSessionEnded(PlaybackSession session, Store<EntityStore> store) {
        if (session == null) {
            return;
        }

        if (session.isPlayerBound()) {
            PlayerRef playerRef = session.getPlayerRef();
            String url = session.getUrl();
            if (playerRef != null && url != null && !url.isEmpty()) {
                MediaLibrary library = plugin.getMediaLibrary();
                if (library != null) {
                    library.upsertSongStatus(
                            playerRef.getUuid().toString(),
                            url,
                            "Ready",
                            null,
                            null,
                            null,
                            0,
                            null,
                            null);
                }
            }
        }

        // Cleanup Marker
        com.hypixel.hytale.component.Ref<EntityStore> marker = session.getMarkerEntity();
        if (marker != null && marker.isValid()) {
            // Despawn/Destroy
            try {
                // Use NPC despawn flag
                NPCEntity npc = session.getNPCEntity();
                if (npc != null) {
                    npc.setToDespawn();
                } else {
                    plugin.getLogger().at(Level.WARNING).log("Failed to find NPCEntity for marker cleanup.");
                }
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to despawn audio marker");
            }
        }
        session.setMarkerEntity(null);
        session.setNPCEntity(null);

        String trackId = session.getTrackId();
        if (trackId == null || trackId.isEmpty()) {
            return;
        }
        if (isTrackActive(trackId)) {
            return;
        }
        MediaManager manager = plugin.getMediaManager();
        if (manager != null) {
            manager.cleanupRuntimeAssetsAsync(trackId);
        }
    }

    private boolean isTrackActive(String trackId) {
        for (PlaybackSession session : activePlayerSessions.values()) {
            if (trackId.equals(session.getTrackId()) && !session.isStopped()) {
                return true;
            }
        }
        for (PlaybackSession session : activeBlockSessions.values()) {
            if (trackId.equals(session.getTrackId()) && !session.isStopped()) {
                return true;
            }
        }
        return false;
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
