package dev.jacobwasbeast.manager;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.MediaRadioPlugin;
import dev.jacobwasbeast.util.CommonAssetUtil;
import dev.jacobwasbeast.util.VolumeUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Manages rolling window of batches for layered playback system.
 * Each batch is a sound event with 3 layers, delayed for staggered playback.
 * Maintains a rolling window of 4 batches (24 seconds total).
 */
public class BatchPlaybackManager {
    private final MediaRadioPlugin plugin;
    private final MediaManager mediaManager;

    // Map of trackId -> Set of active batch IDs currently registered
    private final Map<String, Set<String>> activeBatches = new ConcurrentHashMap<>();

    // Map of batchId -> trackId (for cleanup)
    private final Map<String, String> batchToTrack = new ConcurrentHashMap<>();

    // Map of trackId -> current batch index
    private final Map<String, Integer> trackBatchIndex = new ConcurrentHashMap<>();

    // Map of trackId -> latest volume (dB) to use for newly loaded batches
    private final Map<String, Float> trackVolumes = new ConcurrentHashMap<>();

    public BatchPlaybackManager(MediaRadioPlugin plugin, MediaManager mediaManager) {
        this.plugin = plugin;
        this.mediaManager = mediaManager;
    }

    /**
     * Get unique batch ID for a track and batch index
     */
    public static String getBatchId(String trackId, int batchIndex) {
        return String.format("%s_Batch_%03d", trackId, batchIndex);
    }

    /**
     * Get chunk indices for a batch (3 chunks per batch)
     */
    public static int[] getChunkIndicesForBatch(int batchIndex) {
        int startChunk = batchIndex * MediaManager.LAYERS_PER_BATCH;
        return new int[] {
            startChunk,
            startChunk + 1,
            startChunk + 2
        };
    }

    /**
     * Ensure batches are loaded for current playback position (async)
     * Maintains rolling window of 4 batches (24 seconds)
     */
    public void ensureBatchesLoaded(String trackId, int currentChunk, float volumeDb) {
        if (trackId == null || trackId.isEmpty() || mediaManager == null) {
            return;
        }
        float effectiveVolume = trackVolumes.getOrDefault(trackId, volumeDb);

        // Run async to avoid blocking the world thread
        CompletableFuture.runAsync(() -> {
            // Calculate which batches we need (rolling window of 4 batches)
            int currentBatch = currentChunk / MediaManager.LAYERS_PER_BATCH;
            int startBatch = Math.max(0, currentBatch);
            int endBatch = startBatch + MediaManager.ROLLING_WINDOW_BATCHES;

            // Check total chunks available
            int totalChunks = mediaManager.getChunkCount(trackId);
            if (totalChunks <= 0) {
                return; // No chunks available yet
            }

            Set<String> neededBatchIds = new HashSet<>();
            for (int batchIndex = startBatch; batchIndex < endBatch; batchIndex++) {
                // Check if all chunks for this batch exist
                int[] chunkIndices = getChunkIndicesForBatch(batchIndex);
                boolean allChunksExist = true;
                for (int chunkIndex : chunkIndices) {
                    if (chunkIndex >= totalChunks) {
                        allChunksExist = false;
                        break;
                    }
                }
                if (allChunksExist) {
                    neededBatchIds.add(getBatchId(trackId, batchIndex));
                }
            }

            // Get currently active batches for this track
            Set<String> activeBatchIds = activeBatches.computeIfAbsent(trackId, k -> new HashSet<>());

            // Load missing batches async
            List<CompletableFuture<Void>> loadFutures = new ArrayList<>();
            for (String batchId : neededBatchIds) {
                if (!activeBatchIds.contains(batchId)) {
                    int batchIndex = extractBatchIndex(batchId);
                    activeBatchIds.add(batchId);
                    batchToTrack.put(batchId, trackId);
                    loadFutures.add(loadBatchAsync(trackId, batchIndex, effectiveVolume));
                }
            }

            // Wait for all batch loads to complete (non-blocking for world thread)
            CompletableFuture.allOf(loadFutures.toArray(new CompletableFuture[0]))
                    .exceptionally(ex -> {
                        plugin.getLogger().at(Level.WARNING).withCause(ex).log("Error loading batches for track %s", trackId);
                        return null;
                    });
        }, com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR);
    }

    public void setTrackVolume(String trackId, float volumeDb) {
        if (trackId == null || trackId.isEmpty()) {
            return;
        }
        trackVolumes.put(trackId, volumeDb);
    }

    /**
     * Load a batch asynchronously (sound event with 3 layers)
     */
    private CompletableFuture<Void> loadBatchAsync(String trackId, int batchIndex, float volumeDb) {
        return CompletableFuture.runAsync(() -> {
            if (mediaManager == null) {
                plugin.getLogger().at(Level.WARNING).log("MediaManager not available, cannot load batch");
                return;
            }

            int[] chunkIndices = getChunkIndicesForBatch(batchIndex);
            String batchId = getBatchId(trackId, batchIndex);

            try {
                // Ensure all chunk assets for this batch are registered (async file I/O)
                for (int chunkIndex : chunkIndices) {
                    mediaManager.ensureChunkAssetRegistered(trackId, chunkIndex);
                }

                // Create sound event JSON with 3 layers (uses primary chunk path for reference)
                String primarySoundFilePath = String.format("Sounds/media_radio/%s_Chunk_%03d.ogg", trackId, chunkIndices[0]);
                mediaManager.createBatchSoundEvent(trackId, batchIndex, primarySoundFilePath, volumeDb);

                // Load the sound event asset (async)
                mediaManager.loadSoundEventAsset(batchId);

                plugin.getLogger().at(Level.FINE).log("Loaded batch %s for track %s", batchId, trackId);
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).withCause(e).log("Failed to load batch %s for track %s", batchId, trackId);
            }
        }, com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR);
    }

    /**
     * Unload a batch and remove its assets
     */
    private void unloadBatch(String batchId) {
        String trackId = batchToTrack.get(batchId);
        if (trackId == null) {
            return;
        }

        // Remove sound event asset silently
        CommonAssetUtil.removeCommonAssetSilent("MediaRadioRuntime", batchId);

        plugin.getLogger().at(Level.FINE).log("Unloaded batch %s for track %s", batchId, trackId);
    }

    /**
     * Extract batch index from batch ID
     */
    private int extractBatchIndex(String batchId) {
        // Format: trackId_Batch_XXX
        int lastUnderscore = batchId.lastIndexOf('_');
        if (lastUnderscore < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(batchId.substring(lastUnderscore + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Cleanup all batches for a track
     */
    public void cleanupBatches(String trackId) {
        Set<String> batchIds = activeBatches.remove(trackId);
        if (batchIds != null) {
            for (String batchId : batchIds) {
                unloadBatch(batchId);
                batchToTrack.remove(batchId);
            }
        }
        trackBatchIndex.remove(trackId);
    }

    /**
     * Get all active batch IDs for a track
     */
    public Set<String> getActiveBatches(String trackId) {
        Set<String> batches = activeBatches.get(trackId);
        return batches != null ? new HashSet<>(batches) : new HashSet<>();
    }

    /**
     * Get batch indices for all active batches of a track
     */
    public List<Integer> getActiveBatchIndices(String trackId) {
        Set<String> batchIds = getActiveBatches(trackId);
        List<Integer> indices = new ArrayList<>();
        for (String batchId : batchIds) {
            indices.add(extractBatchIndex(batchId));
        }
        return indices;
    }

}
