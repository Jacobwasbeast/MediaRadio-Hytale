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
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.MediaRadioPlugin;
import dev.jacobwasbeast.manager.PlaylistManager;
import dev.jacobwasbeast.util.BlockTypeUtil;
import dev.jacobwasbeast.util.PlaybackScopeUtil;
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
    private final PlaylistManager playlistManager;
    private final BatchPlaybackManager batchManager;
    private final Map<String, PlaybackSession> activeBlockSessions = new ConcurrentHashMap<>();
    private final Map<UUID, PlaybackSession> activePlayerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> loopPreferences = new ConcurrentHashMap<>();
    private final Map<UUID, Float> playerVolumePreferences = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static final int MAX_MISSING_ASSET_RETRIES = 40;
    private static final long MISSING_ASSET_RETRY_DELAY_MS = 500;
    private static final long BASE_CHUNK_OVERLAP_MS = 15;
    private static final long MAX_CHUNK_OVERLAP_MS = 120;
    private static final long MARKER_POSITION_UPDATE_INTERVAL_MS = 50; // Update marker position every 50ms for smooth tracking

    private int audioMarkerRoleIndex = Integer.MIN_VALUE;

    public MediaPlaybackManager(MediaRadioPlugin plugin) {
        this.plugin = plugin;
        this.playlistManager = plugin.getPlaylistManager();
        this.batchManager = new BatchPlaybackManager(plugin, plugin.getMediaManager());
    }

    /**
     * Get the batch manager instance
     */
    public BatchPlaybackManager getBatchManager() {
        return batchManager;
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
            handleSessionEnded(existing, store, false);
        }

        // Create new session
        PlaybackSession session = new PlaybackSession(trackId, blockPos, totalChunks, chunkDurationMs);
        activeBlockSessions.put(key, session);
        attachBlockEntityRef(session, store, blockPos);

        // Start playback
        applyQueueLoop(session, resolveScopeId(blockPos, store));
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
            handleSessionEnded(existing, store, false);
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
        attachBlockEntityRef(session, store, blockPos);

        applyQueueLoop(session, resolveScopeId(blockPos, store));
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
            handleSessionEnded(existing, store, false);
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
        applyQueueLoop(session, PlaybackScopeUtil.playerScopeId(playerId));
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
            handleSessionEnded(session, store, false);
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
            handleSessionEnded(session, store, false);
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
        if (!session.isPlayerBound()) {
            if (!isBlockPlaybackValid(session)) {
                session.stop();
                removeSession(session);
                handleSessionEnded(session, store, false);
                return;
            }
            // Double-check block is still a boombox (by block ID) and has not been destroyed.
            Vector3i blockPos = session.getBlockPosition();
            if (blockPos == null || !isBoomboxBlockStillThere(store, blockPos)) {
                session.stop();
                removeSession(session);
                handleSessionEnded(session, store, false);
                return;
            }
        }
        // For handheld radio: pause if radio is no longer in hand or offhand (events may be missed).
        if (session.isPlayerBound()) {
            PlayerRef playerRef = session.getPlayerRef();
            if (playerRef != null && !shouldKeepPlaying(playerRef, store)) {
                session.pauseByUnheld();
                return;
            }
        }

        String trackId = session.getTrackId();
        int chunkIndex = session.getCurrentChunk();
        int totalChunks = session.getTotalChunks();

        // Ensure batches are loaded for rolling window (async, non-blocking)
        float volumeDb = session.getVolume();
        batchManager.ensureBatchesLoaded(trackId, chunkIndex, volumeDb);

        // Calculate which batch this chunk belongs to
        int currentBatch = chunkIndex / MediaManager.LAYERS_PER_BATCH;
        String batchId = BatchPlaybackManager.getBatchId(trackId, currentBatch);
        boolean fullBatchAvailable = (chunkIndex + MediaManager.LAYERS_PER_BATCH - 1) < totalChunks;

        // Look up batch SoundEvent and Track Model
        // We now use a single model "medradio_marker_<trackId>" for the whole track
        String trackAppearanceId = "medradio_marker_" + trackId;

        ModelAsset trackModel = ModelAsset.getAssetMap().getAsset(trackAppearanceId);
        SoundEvent chunkSoundEvent = SoundEvent.getAssetMap()
                .getAsset(String.format("%s_Chunk_%03d", trackId, chunkIndex));

        SoundEvent batchSoundEvent = null;
        if (fullBatchAvailable) {
            batchSoundEvent = SoundEvent.getAssetMap().getAsset(batchId);
        }

        if (trackModel == null || (fullBatchAvailable && batchSoundEvent == null)
                || (!fullBatchAvailable && chunkSoundEvent == null)) {
            // Log less frequently or debug
            if (session.getMissingAssetRetries() % 5 == 0) {
                if (fullBatchAvailable) {
                    plugin.getLogger().at(Level.WARNING).log("Batch Asset not ready: %s (Sound or Model missing)",
                            batchId);
                } else {
                    plugin.getLogger().at(Level.WARNING).log("Chunk Asset not ready: %s (Sound or Model missing)",
                            String.format("%s_Chunk_%03d", trackId, chunkIndex));
                }
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

        // Trigger Animation State for this batch
        // Use the batch ID for the animation state
        NPCEntity npc = session.getNPCEntity();
        if (npc != null) {
            // For batch system, we still use chunk-based animation states for compatibility
            // But the sound event is batch-based
            String animationState = "PlayChunk" + chunkIndex;
            npc.playAnimation(marker, AnimationSlot.Action, animationState, (ComponentAccessor<EntityStore>) store);
        } else {
            plugin.getLogger().at(Level.WARNING).log("NPCEntity missing for animation!");
        }

        // Schedule next chunk
        scheduleNextChunk(session, store);

        // Start/restart periodic position updates for accurate marker tracking
        startMarkerPositionUpdates(session, store);
    }

    /**
     * Start periodic position updates for the marker entity
     * Updates position every 50ms for smooth tracking
     */
    private void startMarkerPositionUpdates(PlaybackSession session, Store<EntityStore> store) {
        // Cancel existing position update task if any
        ScheduledFuture<?> existing = session.getScheduledPositionUpdate();
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        // Schedule periodic position updates
        ScheduledFuture<?> positionUpdateTask = scheduler.scheduleAtFixedRate(() -> {
            if (!session.isPlaying()) {
                return;
            }

            // Execute on world thread
            store.getExternalData().getWorld().execute(() -> {
                updateMarkerPosition(session, store);
            });
        }, 0, MARKER_POSITION_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        session.setScheduledPositionUpdate(positionUpdateTask);
    }

    /**
     * Update marker entity position based on playback source
     */
    private void updateMarkerPosition(PlaybackSession session, Store<EntityStore> store) {
        if (!session.isPlaying()) {
            return;
        }

        com.hypixel.hytale.component.Ref<EntityStore> marker = session.getMarkerEntity();
        if (marker == null || !marker.isValid()) {
            return;
        }

        TransformComponent markerTransform = store.getComponent(marker, TransformComponent.getComponentType());
        if (markerTransform == null) {
            return;
        }

        if (session.isPlayerBound()) {
            // Update position to follow player
            PlayerRef pRef = session.getPlayerRef();
            if (pRef != null && pRef.isValid()) {
                var pEntRef = pRef.getReference();
                if (pEntRef != null && pEntRef.isValid()) {
                    TransformComponent pTransform = store.getComponent(pEntRef, TransformComponent.getComponentType());
                    if (pTransform != null) {
                        Vector3d playerPos = pTransform.getPosition();
                        // Use exact player position for accurate tracking
                        markerTransform.setPosition(playerPos);
                    }
                }
            }
        } else {
            // Update position to block center (shouldn't change, but ensure accuracy)
            Vector3i blockPos = session.getBlockPosition();
            if (blockPos != null) {
                Vector3d blockCenter = new Vector3d(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
                Vector3d currentPos = markerTransform.getPosition();
                // Only update if position has drifted (more than 0.1 blocks away)
                double distance = Math.sqrt(
                    Math.pow(currentPos.getX() - blockCenter.getX(), 2) +
                    Math.pow(currentPos.getY() - blockCenter.getY(), 2) +
                    Math.pow(currentPos.getZ() - blockCenter.getZ(), 2)
                );
                if (distance > 0.1) {
                    markerTransform.setPosition(blockCenter);
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
        // Use fixed chunk duration
        int chunkDurationMs = MediaManager.CHUNK_DURATION_MS;
        long maxOverlap = Math.max(0, chunkDurationMs - 5);
        overlapMs = Math.min(overlapMs, maxOverlap);
        long delayMs = Math.max(0, chunkDurationMs - overlapMs);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (!session.isPlaying()) {
                return;
            }
            long expectedEnd = session.getCurrentChunkStartMs() + chunkDurationMs;
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
                handleSessionEnded(session, store, true);
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
                handleSessionEnded(session, store, false);
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

    private void attachBlockEntityRef(PlaybackSession session, Store<EntityStore> store, Vector3i blockPos) {
        if (session == null || blockPos == null || store == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> blockRef = getOrCreateBlockEntityRef(chunkStore, blockPos);
        session.setBlockEntityRef(blockRef);
    }

    private boolean isBlockPlaybackValid(PlaybackSession session) {
        Ref<ChunkStore> blockRef = session.getBlockEntityRef();
        if (blockRef == null) {
            return true;
        }
        return blockRef.isValid();
    }

    /**
     * Double-check the block at the position is still a boombox (by block ID) and has not been destroyed.
     */
    private boolean isBoomboxBlockStillThere(Store<EntityStore> store, Vector3i pos) {
        if (pos == null || store == null) {
            return false;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return false;
        }
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ()));
        if (chunk == null) {
            return false;
        }
        BlockType blockType = chunk.getBlockType(pos.getX(), pos.getY(), pos.getZ());
        if (blockType == null) {
            return false;
        }
        String blockId = blockType.getId();
        return BlockTypeUtil.isBoomboxBlockId(blockId);
    }

    private void removeSession(PlaybackSession session) {
        if (session == null) {
            return;
        }
        // Cancel position update task
        ScheduledFuture<?> positionUpdate = session.getScheduledPositionUpdate();
        if (positionUpdate != null && !positionUpdate.isDone()) {
            positionUpdate.cancel(false);
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

    private void handleSessionEnded(PlaybackSession session, Store<EntityStore> store, boolean allowQueueAdvance) {
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

        if (allowQueueAdvance) {
            if (tryAdvanceQueue(session, store)) {
                // Continue with cleanup of the previous session only.
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

        // Cleanup batches for this track
        batchManager.cleanupBatches(trackId);

        if (isTrackActive(trackId)) {
            return;
        }
        if (playlistManager != null) {
            String url = session.getUrl();
            if (url != null && playlistManager.isUrlReferenced(url)) {
                return;
            }
        }
        MediaManager manager = plugin.getMediaManager();
        if (manager != null) {
            manager.cleanupRuntimeAssetsAsync(trackId);
        }
    }

    private boolean tryAdvanceQueue(PlaybackSession session, Store<EntityStore> store) {
        if (playlistManager == null || store == null) {
            return false;
        }
        String scopeId = resolveScopeId(session, store);
        if (scopeId.isEmpty()) {
            return false;
        }
        PlaylistManager.QueueState queue = playlistManager.getQueue(scopeId);
        if (queue == null || queue.items == null || queue.items.isEmpty()) {
            return false;
        }
        int currentIndex = resolveQueueIndex(queue, session.getUrl());
        int nextIndex = resolveNextQueueIndex(queue, currentIndex);
        if (nextIndex < 0 || nextIndex >= queue.items.size()) {
            return false;
        }
        playlistManager.setQueueIndex(scopeId, nextIndex);
        PlaylistManager.PlaylistItem item = queue.items.get(nextIndex);
        if (item == null || item.url == null || item.url.isEmpty()) {
            return false;
        }
        playQueueItem(item, session, store, scopeId);
        return true;
    }

    private int resolveQueueIndex(PlaylistManager.QueueState queue, String url) {
        if (queue == null || queue.items == null || queue.items.isEmpty()) {
            return 0;
        }
        int current = queue.index;
        if (current >= 0 && current < queue.items.size()) {
            PlaylistManager.PlaylistItem item = queue.items.get(current);
            if (item != null && url != null && url.equals(item.url)) {
                return current;
            }
        }
        if (url != null) {
            for (int i = 0; i < queue.items.size(); i++) {
                PlaylistManager.PlaylistItem item = queue.items.get(i);
                if (item != null && url.equals(item.url)) {
                    return i;
                }
            }
        }
        return Math.max(0, Math.min(current, queue.items.size() - 1));
    }

    private int resolveNextQueueIndex(PlaylistManager.QueueState queue, int currentIndex) {
        if (queue == null || queue.items == null || queue.items.isEmpty()) {
            return -1;
        }
        if (queue.loopMode == PlaylistManager.LoopMode.ONE) {
            return currentIndex;
        }
        int next = currentIndex + 1;
        if (next < queue.items.size()) {
            return next;
        }
        if (queue.loopMode == PlaylistManager.LoopMode.ALL) {
            return 0;
        }
        return -1;
    }

    private void playQueueItem(PlaylistManager.PlaylistItem item, PlaybackSession previous,
            Store<EntityStore> store, String scopeId) {
        MediaManager mediaManager = plugin.getMediaManager();
        if (mediaManager == null || item == null || item.url == null || item.url.isEmpty()) {
            return;
        }
        MediaLibrary library = plugin.getMediaLibrary();
        if (library != null && scopeId != null && !scopeId.isEmpty()) {
            library.upsertSongStatus(scopeId, item.url, "Downloading...", null, null, null, 0, null, null);
        }
        mediaManager.requestMedia(item.url).thenAccept(mediaInfo -> {
            store.getExternalData().getWorld().execute(() -> {
                if (previous.isPlayerBound()) {
                    PlayerRef playerRef = previous.getPlayerRef();
                    if (playerRef != null) {
                        mediaManager.playSound(mediaInfo, playerRef, store);
                        return;
                    }
                }
                Vector3i pos = previous.getBlockPosition();
                if (pos != null) {
                    mediaManager.playSoundAtBlock(mediaInfo, pos,
                            dev.jacobwasbeast.manager.MediaManager.CHUNK_DURATION_MS, store);
                }
            });
        }).exceptionally(ex -> {
            if (library != null && scopeId != null && !scopeId.isEmpty()) {
                library.upsertSongStatus(scopeId, item.url, "Failed", null, null, null, 0, null, null);
            }
            return null;
        });
    }

    private String resolveScopeId(PlaybackSession session, Store<EntityStore> store) {
        if (session == null) {
            return "";
        }
        if (session.isPlayerBound()) {
            PlayerRef playerRef = session.getPlayerRef();
            if (playerRef != null) {
                return PlaybackScopeUtil.playerScopeId(playerRef.getUuid());
            }
            return "";
        }
        Vector3i pos = session.getBlockPosition();
        if (pos == null) {
            return "";
        }
        if (store != null && store.getExternalData() != null && store.getExternalData().getWorld() != null) {
            return PlaybackScopeUtil.boomboxScopeId(store.getExternalData().getWorld(), pos);
        }
        return PlaybackScopeUtil.boomboxScopeId("world", pos);
    }

    private String resolveScopeId(Vector3i blockPos, Store<EntityStore> store) {
        if (blockPos == null) {
            return "";
        }
        if (store != null && store.getExternalData() != null && store.getExternalData().getWorld() != null) {
            return PlaybackScopeUtil.boomboxScopeId(store.getExternalData().getWorld(), blockPos);
        }
        return PlaybackScopeUtil.boomboxScopeId("world", blockPos);
    }

    private void applyQueueLoop(PlaybackSession session, String scopeId) {
        if (session == null) {
            return;
        }
        if (playlistManager != null && scopeId != null && !scopeId.isEmpty()) {
            PlaylistManager.QueueState queue = playlistManager.getQueue(scopeId);
            if (queue != null && queue.loopMode == PlaylistManager.LoopMode.ONE) {
                session.setLoopEnabled(true);
                return;
            }
        }
        session.setLoopEnabled(false);
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
