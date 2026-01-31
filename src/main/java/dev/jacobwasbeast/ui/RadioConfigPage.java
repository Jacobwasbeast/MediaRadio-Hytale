package dev.jacobwasbeast.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.MediaRadioPlugin;
import com.hypixel.hytale.server.core.Message;
import dev.jacobwasbeast.manager.PlaybackSession;
import dev.jacobwasbeast.util.VolumeUtil;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class RadioConfigPage extends InteractiveCustomUIPage<RadioConfigPage.RadioPageData> {
    private static final long TIME_UPDATE_PERIOD_MS = 1000;
    private static final Map<UUID, ScheduledFuture<?>> TIME_UPDATERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_TIME_SECONDS = new ConcurrentHashMap<>();
    private static final long SEEK_DEBOUNCE_MS = 250;
    private static final Map<UUID, ScrubState> SCRUB_STATES = new ConcurrentHashMap<>();
    private static final long VOLUME_COOLDOWN_MS = 5000;
    private static final Map<UUID, Long> LAST_VOLUME_CHANGE_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> VOLUME_EDITING = new ConcurrentHashMap<>();
    private static final int VOLUME_STEP_PERCENT = 10;
    private static final int VOLUME_DEFAULT_PERCENT = VolumeUtil.DEFAULT_PERCENT;

    private final PlayerRef playerRef;
    private final Vector3i blockPos;

    public RadioConfigPage(PlayerRef playerRef) {
        this(playerRef, null);
    }

    public RadioConfigPage(PlayerRef playerRef, Vector3i blockPos) {
        super(playerRef, CustomPageLifetime.CanDismiss, RadioConfigPage.RadioPageData.CODEC);
        this.playerRef = playerRef;
        this.blockPos = blockPos;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder, @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/MediaRadio/RadioConfig.ui");

        // Populate Now Playing
        PlaybackSession session = resolveSession();
        if (session != null && !session.isStopped()) {
            commandBuilder.set("#NowPlayingTitle.Text",
                    session.getTitle().isEmpty() ? "Unknown Title" : session.getTitle());
            commandBuilder.set("#NowPlayingArtist.Text", session.getArtist());
            commandBuilder.set("#PauseButton.Text", session.isPaused() ? "Resume" : "Pause");
            commandBuilder.set("#NowPlayingTime.Text", formatTime(session.getCurrentPositionMs()) + " / "
                    + formatTime(session.getTotalDurationMs()));
            commandBuilder.set("#SeekSlider.Value", (int) (session.getProgress() * 100));
            commandBuilder.set("#SeekSlider.Visible", true);
            commandBuilder.set("#LoopButton.Text", formatLoopLabel(session.isLoopEnabled()));
            String nowPlayingAsset = session.getThumbnailUrl();
            if ((nowPlayingAsset == null || nowPlayingAsset.isEmpty()) && session.getUrl() != null) {
                var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
                if (mediaManager != null) {
                    String trackId = mediaManager.getTrackIdForUrl(session.getUrl());
                    if (mediaManager.hasThumbnail(trackId)) {
                        nowPlayingAsset = mediaManager.getThumbnailAssetPath(trackId);
                    }
                }
            }
            if (nowPlayingAsset != null && !nowPlayingAsset.isEmpty()) {
                commandBuilder.set("#NowPlayingThumb.AssetPath", nowPlayingAsset);
                commandBuilder.set("#NowPlayingThumb.Visible", true);
            } else {
                commandBuilder.set("#NowPlayingThumb.Visible", false);
            }

            // Sync volume input (skip if user is editing)
            if (!Boolean.TRUE.equals(VOLUME_EDITING.get(playerRef.getUuid()))) {
                int volumePercent = Math.round(VolumeUtil.clampPercent(VolumeUtil.eventDbToPercent(session.getVolume())));
                commandBuilder.set("#VolumeInput.Value", String.valueOf(volumePercent));
            }
        } else {
            commandBuilder.set("#NowPlayingTitle.Text", "No Media Playing");
            commandBuilder.set("#NowPlayingArtist.Text", "");
            commandBuilder.set("#PauseButton.Text", "Pause");
            commandBuilder.set("#NowPlayingTime.Text", "0:00 / 0:00");
            commandBuilder.set("#SeekSlider.Value", 0);
            commandBuilder.set("#SeekSlider.Visible", false);
            int volumePercent = VOLUME_DEFAULT_PERCENT;
            if (blockPos != null) {
                var playbackManager = MediaRadioPlugin.getInstance().getPlaybackManager();
                if (playbackManager != null) {
                    volumePercent = Math.round(
                            VolumeUtil.clampPercent(
                                    VolumeUtil.eventDbToPercent(playbackManager.getBlockVolume(blockPos, store))));
                }
            } else {
                var playbackManager = MediaRadioPlugin.getInstance().getPlaybackManager();
                if (playbackManager != null) {
                    volumePercent = Math.round(
                            VolumeUtil.clampPercent(
                                    VolumeUtil.eventDbToPercent(
                                            playbackManager.getPlayerVolume(playerRef.getUuid()))));
                }
            }
            if (!Boolean.TRUE.equals(VOLUME_EDITING.get(playerRef.getUuid()))) {
                commandBuilder.set("#VolumeInput.Value", String.valueOf(volumePercent));
            }
            commandBuilder.set("#LoopButton.Text", resolveLoopLabel(null));
            commandBuilder.set("#NowPlayingThumb.Visible", false);
        }

        if (session != null && session.getUrl() != null && !session.getUrl().isEmpty()) {
            commandBuilder.set("#UrlInput.Value", session.getUrl());
        }

        // Populate Library List
        var library = MediaRadioPlugin.getInstance().getMediaLibrary();
        var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
        if (library != null) {
            int i = 0;
            String libraryOwnerId = getLibraryOwnerId(store);
            for (dev.jacobwasbeast.manager.MediaLibrary.SavedSong song : library.getSongsForPlayer(libraryOwnerId)) {
                commandBuilder.append("#LibraryList", "Pages/MediaRadio/SongEntry.ui");

                String root = "#LibraryList[" + i + "]";
                commandBuilder.set(root + " #SongTitle.Text", song.title != null ? song.title : "Unknown");
                commandBuilder.set(root + " #SongArtist.Text", song.artist != null ? song.artist : "");
                if (song.status != null && !song.status.isEmpty()) {
                    commandBuilder.set(root + " #SongStatus.Text", song.status);
                } else {
                    commandBuilder.set(root + " #SongStatus.Text", "");
                }

                String assetPath = song.thumbnailAssetPath;
                if ((assetPath == null || assetPath.isEmpty()) && mediaManager != null && song.url != null) {
                    String trackId = song.trackId != null ? song.trackId : mediaManager.getTrackIdForUrl(song.url);
                    if (mediaManager.hasThumbnail(trackId)) {
                        assetPath = mediaManager.getThumbnailAssetPath(trackId);
                        song.trackId = trackId;
                        song.thumbnailAssetPath = assetPath;
                        library.save();
                    }
                }
                if (assetPath != null && !assetPath.isEmpty()) {
                    commandBuilder.set(root + " #Thumbnail.AssetPath", assetPath);
                } else {
                    commandBuilder.set(root + " #Thumbnail.Visible", false);
                }

                // Bind play button using the song URL
                if (song.url != null && !song.url.isEmpty()) {
                    eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, root + " #PlayButton",
                            EventData.of("Url", song.url), false);
                    eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, root + " #RemoveButton",
                            EventData.of("Action", "Remove").append("Url", song.url), false);
                } else {
                    commandBuilder.set(root + " #PlayButton.Enabled", false);
                    commandBuilder.set(root + " #RemoveButton.Enabled", false);
                }

                i++;
            }
        }

        addEventBindings(eventBuilder);

        startTimeUpdater();
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull RadioPageData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        if ("Cancel".equals(data.action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        } else if ("Loop".equals(data.action)) {
            data.action = null;
            store.getExternalData().getWorld().execute(() -> {
                var manager = MediaRadioPlugin.getInstance().getPlaybackManager();
                if (blockPos != null) {
                    PlaybackSession session = manager.getSession(blockPos);
                    if (session != null) {
                        manager.setLoopEnabled(blockPos, !session.isLoopEnabled());
                    }
                } else {
                    boolean enabled = !manager.isLoopEnabled(playerRef.getUuid());
                    manager.setLoopEnabled(playerRef.getUuid(), enabled);
                }
                reopenPageIfNeeded(ref, store, player);
            });
            return;
        } else if ("Remove".equals(data.action)) {
            String removeUrl = data.url;
            if (removeUrl == null || removeUrl.isEmpty()) {
                removeUrl = data.directUrl;
            }
            data.action = null;
            data.url = null;
            data.directUrl = null;
            final String finalRemoveUrl = removeUrl;
            store.getExternalData().getWorld().execute(() -> {
                var library = MediaRadioPlugin.getInstance().getMediaLibrary();
                if (library != null && finalRemoveUrl != null && !finalRemoveUrl.isEmpty()) {
                    String libraryOwnerId = getLibraryOwnerId(store);
                    library.removeSong(libraryOwnerId, finalRemoveUrl);
                    if (!library.isUrlReferencedByOtherPlayers(libraryOwnerId, finalRemoveUrl)) {
                        MediaRadioPlugin.getInstance().getMediaManager().deleteMediaForUrl(finalRemoveUrl);
                    }
                }
                var manager = MediaRadioPlugin.getInstance().getPlaybackManager();
                var session = blockPos != null ? manager.getSession(blockPos) : manager.getSession(playerRef.getUuid());
                if (session != null && finalRemoveUrl != null && finalRemoveUrl.equals(session.getUrl())) {
                    if (blockPos != null) {
                        manager.stop(blockPos, store);
                    } else {
                        manager.stop(playerRef, store);
                    }
                }
                reopenPageIfNeeded(ref, store, player);
            });
            return;
        } else if ("Pause".equals(data.action)) {
            data.action = null; // Consume
            store.getExternalData().getWorld().execute(() -> {
                var manager = MediaRadioPlugin.getInstance().getPlaybackManager();
                var session = blockPos != null ? manager.getSession(blockPos) : manager.getSession(playerRef.getUuid());
                if (session != null) {
                    if (session.isPaused()) {
                        if (blockPos != null) {
                            manager.resume(blockPos, store);
                        } else {
                            manager.resume(playerRef, store);
                        }
                    } else {
                        if (blockPos != null) {
                            manager.pause(blockPos);
                        } else {
                            manager.pauseByUser(playerRef);
                        }
                    }
                    reopenPageIfNeeded(ref, store, player);
                }
            });
            return;
        } else if ("Stop".equals(data.action)) {
            data.action = null; // Consume
            store.getExternalData().getWorld().execute(() -> {
                PlaybackSession session = resolveSession();
                if (blockPos != null) {
                    MediaRadioPlugin.getInstance().getPlaybackManager().stop(blockPos, store);
                } else {
                    MediaRadioPlugin.getInstance().getPlaybackManager().stop(playerRef, store);
                }
                if (session != null && session.getUrl() != null && !session.getUrl().isEmpty()) {
                    var library = MediaRadioPlugin.getInstance().getMediaLibrary();
                    if (library != null) {
                        library.upsertSongStatus(
                                getLibraryOwnerId(store),
                                session.getUrl(),
                                "Ready",
                                null,
                                null,
                                null,
                                0,
                                null,
                                null);
                    }
                }
                player.sendMessage(Message.translation("Stopped playback."));
                reopenPageIfNeeded(ref, store, player);
            });
            return;
        }

        if (data.seekValue != null) {
            float seek = data.seekValue;
            data.seekValue = null; // Consume
            handleSeekScrub(seek, store);
            return;
        }

        if ("VolumeFocusGained".equals(data.action)) {
            data.action = null;
            VOLUME_EDITING.put(playerRef.getUuid(), true);
            return;
        }

        if ("VolumeFocusLost".equals(data.action)) {
            data.action = null;
            VOLUME_EDITING.remove(playerRef.getUuid());
            // fall through to process volumeText if present
        }

        if ("VolumeUp".equals(data.action) || "VolumeDown".equals(data.action)) {
            boolean up = "VolumeUp".equals(data.action);
            data.action = null;
            store.getExternalData().getWorld().execute(() -> {
                long now = System.currentTimeMillis();
                Long last = LAST_VOLUME_CHANGE_MS.get(playerRef.getUuid());
                if (last != null && (now - last) < VOLUME_COOLDOWN_MS) {
                    return;
                }
                PlaybackSession session = resolveSession();
                float currentPercent;
                var playbackManager = MediaRadioPlugin.getInstance().getPlaybackManager();
                if (session != null) {
                    currentPercent = VolumeUtil.eventDbToPercent(session.getVolume());
                } else if (blockPos != null && playbackManager != null) {
                    currentPercent = VolumeUtil.eventDbToPercent(playbackManager.getBlockVolume(blockPos, store));
                } else if (playbackManager != null) {
                    currentPercent = VolumeUtil.eventDbToPercent(playbackManager.getPlayerVolume(playerRef.getUuid()));
                } else {
                    currentPercent = VOLUME_DEFAULT_PERCENT;
                }
                LAST_VOLUME_CHANGE_MS.put(playerRef.getUuid(), now);
                float nextPercent = currentPercent + (up ? VOLUME_STEP_PERCENT : -VOLUME_STEP_PERCENT);
                float nextClamped = VolumeUtil.clampPercent(nextPercent);
                float volDb = VolumeUtil.percentToEventDb(nextClamped);

                if (session != null) {
                    session.setVolume(volDb);
                    // Replace SoundEvent configs for all chunks with new volume
                    var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
                    if (session.getTrackId() != null) {
                        mediaManager.updateTrackVolume(session.getTrackId(), session.getTotalChunks(), volDb);
                    }
                }
                if (blockPos != null && playbackManager != null) {
                    playbackManager.updateComponent(blockPos, store, component -> component.setVolume(volDb));
                } else if (playbackManager != null) {
                    playbackManager.setPlayerVolume(playerRef.getUuid(), volDb);
                }

                UICommandBuilder blockCommandBuilder = new UICommandBuilder();
                blockCommandBuilder.set("#VolumeInput.Value", String.valueOf(Math.round(nextClamped)));
                UIEventBuilder blockEventBuilder = new UIEventBuilder();
                addEventBindings(blockEventBuilder);
                sendUpdate(blockCommandBuilder, blockEventBuilder, false);
            });
            return;
        }

        if (data.volumeText != null) {
            String volumeTextValue = data.volumeText;
            data.volumeText = null;
            float percentValue = parseVolumePercent(volumeTextValue);
            if (percentValue >= 0.0f) {
                store.getExternalData().getWorld().execute(() -> {
                    PlaybackSession session = resolveSession();
                    LAST_VOLUME_CHANGE_MS.put(playerRef.getUuid(), System.currentTimeMillis());
                    float nextClamped = VolumeUtil.clampPercent(percentValue);
                    float volDb = VolumeUtil.percentToEventDb(nextClamped);
                    if (session != null) {
                        session.setVolume(volDb);
                        // Replace SoundEvent configs for all chunks with new volume
                        var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
                        if (session.getTrackId() != null) {
                            mediaManager.updateTrackVolume(session.getTrackId(), session.getTotalChunks(), volDb);
                        }
                    }
                    var playbackManager = MediaRadioPlugin.getInstance().getPlaybackManager();
                    if (blockPos != null && playbackManager != null) {
                        playbackManager.updateComponent(blockPos, store, component -> component.setVolume(volDb));
                    } else if (playbackManager != null) {
                        playbackManager.setPlayerVolume(playerRef.getUuid(), volDb);
                    }

                    UICommandBuilder blockCommandBuilder = new UICommandBuilder();
                    blockCommandBuilder.set("#VolumeInput.Value", String.valueOf(Math.round(nextClamped)));
                    UIEventBuilder blockEventBuilder = new UIEventBuilder();
                    addEventBindings(blockEventBuilder);
                    sendUpdate(blockCommandBuilder, blockEventBuilder, false);
                });
            }
            return;
        }

        String url = data.url;
        if (url == null || url.isEmpty()) {
            url = data.directUrl;
        }
        if (url != null && !url.isEmpty()) {
            var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
            final String finalUrl = mediaManager != null ? mediaManager.normalizeUrl(url) : url;
            data.url = null;
            data.directUrl = null;
            var library = MediaRadioPlugin.getInstance().getMediaLibrary();
            if (library != null) {
                library.upsertSongStatus(getLibraryOwnerId(store), finalUrl, "Downloading...", null, null, null,
                        0, null,
                        null);
                reopenPageIfNeeded(ref, store, player);
            }
            player.sendMessage(Message.translation("Requesting media..."));

            MediaRadioPlugin.getInstance().getMediaManager().requestMedia(finalUrl).thenAccept(mediaInfo -> {
                store.getExternalData().getWorld().execute(() -> {
                    if (library != null) {
                        library.upsertSongStatus(
                                getLibraryOwnerId(store),
                                mediaInfo.url,
                                "Preparing...",
                                mediaInfo.title,
                                mediaInfo.artist,
                                mediaInfo.thumbnailUrl,
                                mediaInfo.duration,
                                mediaInfo.trackId,
                                mediaInfo.thumbnailAssetPath);
                    }
                    // Start playback
                    if (blockPos != null) {
                        MediaRadioPlugin.getInstance().getMediaManager()
                                .playSoundAtBlock(mediaInfo, blockPos,
                                        MediaRadioPlugin.getInstance().getConfig().getChunkDurationMs(), store)
                                .thenRun(() -> store.getExternalData().getWorld().execute(() -> {
                                    if (library != null) {
                                        library.upsertSongStatus(
                                                getLibraryOwnerId(store),
                                                mediaInfo.url,
                                                "Playing",
                                                mediaInfo.title,
                                                mediaInfo.artist,
                                                mediaInfo.thumbnailUrl,
                                                mediaInfo.duration,
                                                mediaInfo.trackId,
                                                mediaInfo.thumbnailAssetPath);
                                    }
                                    reopenPageIfNeeded(ref, store, player);
                                }))
                                .exceptionally(ex -> {
                                    store.getExternalData().getWorld().execute(() -> {
                                        String reason = extractFailureReason(ex);
                                        sendChatAndClose(ref, store,
                                                reason.isEmpty()
                                                        ? "Playback failed while preparing."
                                                        : "Playback failed: " + reason);
                                        if (library != null) {
                                            library.upsertSongStatus(
                                                    getLibraryOwnerId(store),
                                                    mediaInfo.url,
                                                    "Failed",
                                                    mediaInfo.title,
                                                    mediaInfo.artist,
                                                    mediaInfo.thumbnailUrl,
                                                    mediaInfo.duration,
                                                    mediaInfo.trackId,
                                                    mediaInfo.thumbnailAssetPath);
                                        }
                                    });
                                    return null;
                                });
                    } else {
                        MediaRadioPlugin.getInstance().getMediaManager()
                                .playSound(mediaInfo, playerRef, store)
                                .thenRun(() -> store.getExternalData().getWorld().execute(() -> {
                                    if (library != null) {
                                        library.upsertSongStatus(
                                                getLibraryOwnerId(store),
                                                mediaInfo.url,
                                                "Playing",
                                                mediaInfo.title,
                                                mediaInfo.artist,
                                                mediaInfo.thumbnailUrl,
                                                mediaInfo.duration,
                                                mediaInfo.trackId,
                                                mediaInfo.thumbnailAssetPath);
                                    }
                                    reopenPageIfNeeded(ref, store, player);
                                }))
                                .exceptionally(ex -> {
                                    store.getExternalData().getWorld().execute(() -> {
                                        String reason = extractFailureReason(ex);
                                        sendChatAndClose(ref, store,
                                                reason.isEmpty()
                                                        ? "Playback failed while preparing."
                                                        : "Playback failed: " + reason);
                                        if (library != null) {
                                            library.upsertSongStatus(
                                                    getLibraryOwnerId(store),
                                                    mediaInfo.url,
                                                    "Failed",
                                                    mediaInfo.title,
                                                    mediaInfo.artist,
                                                    mediaInfo.thumbnailUrl,
                                                    mediaInfo.duration,
                                                    mediaInfo.trackId,
                                                    mediaInfo.thumbnailAssetPath);
                                        }
                                    });
                                    return null;
                                });
                    }
                });
            }).exceptionally(e -> {
                store.getExternalData().getWorld().execute(() -> {
                    String reason = extractFailureReason(e);
                    sendChatAndClose(ref, store,
                            reason.isEmpty()
                                    ? "Failed to load media."
                                    : "Failed to load media: " + reason);
                    if (library != null) {
                        library.upsertSongStatus(
                                getLibraryOwnerId(store),
                                finalUrl,
                                "Failed",
                                null,
                                null,
                                null,
                                0,
                                null,
                                null);
                    }
                });
                return null;
            });
        }
    }

    public static class RadioPageData {
        public static final BuilderCodec<RadioPageData> CODEC = BuilderCodec
                .builder(RadioPageData.class, RadioPageData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action)
                .add()
                .append(new KeyedCodec<>("@Url", Codec.STRING), (d, v) -> d.url = v, d -> d.url)
                .add()
                .append(new KeyedCodec<>("Url", Codec.STRING), (d, v) -> d.directUrl = v, d -> d.directUrl)
                .add()
                .append(new KeyedCodec<>("@SeekValue", Codec.FLOAT), (d, v) -> d.seekValue = v, d -> d.seekValue)
                .add()
                .append(new KeyedCodec<>("@VolumeText", Codec.STRING), (d, v) -> d.volumeText = v,
                        d -> d.volumeText)
                .add()
                .build();

        public String action;
        public String url;
        public String directUrl;
        public Float seekValue;
        public String volumeText;
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        stopTimeUpdater();
    }

    private String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private void startTimeUpdater() {
        UUID playerId = playerRef.getUuid();
        TIME_UPDATERS.compute(playerId, (id, existing) -> {
            if (existing != null && !existing.isDone() && !existing.isCancelled()) {
                return existing;
            }
            return HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
                if (!updateTimeDisplay()) {
                    stopTimeUpdater();
                }
            }, TIME_UPDATE_PERIOD_MS, TIME_UPDATE_PERIOD_MS, TimeUnit.MILLISECONDS);
        });
    }

    private void stopTimeUpdater() {
        UUID playerId = playerRef.getUuid();
        ScheduledFuture<?> future = TIME_UPDATERS.remove(playerId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private boolean updateTimeDisplay() {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        ScrubState scrubState = SCRUB_STATES.get(playerRef.getUuid());
        if (scrubState != null && scrubState.isScrubbing) {
            return true;
        }
        PlaybackSession session = resolveSession();
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UUID playerId = playerRef.getUuid();
        if (session != null && !session.isStopped()) {
            long seconds = Math.max(0, session.getCurrentPositionMs() / 1000);
            Long lastSeconds = LAST_TIME_SECONDS.get(playerId);
            if (lastSeconds != null && lastSeconds == seconds) {
                return true;
            }
            LAST_TIME_SECONDS.put(playerId, seconds);
            commandBuilder.set("#NowPlayingTime.Text", formatTime(session.getCurrentPositionMs()) + " / "
                    + formatTime(session.getTotalDurationMs()));
            commandBuilder.set("#SeekSlider.Value", (int) (session.getProgress() * 100));
            commandBuilder.set("#SeekSlider.Visible", true);
            commandBuilder.set("#LoopButton.Text", formatLoopLabel(session.isLoopEnabled()));
            String nowPlayingAsset = session.getThumbnailUrl();
            if ((nowPlayingAsset == null || nowPlayingAsset.isEmpty()) && session.getUrl() != null) {
                var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
                if (mediaManager != null) {
                    String trackId = mediaManager.getTrackIdForUrl(session.getUrl());
                    if (mediaManager.hasThumbnail(trackId)) {
                        nowPlayingAsset = mediaManager.getThumbnailAssetPath(trackId);
                    }
                }
            }
            if (nowPlayingAsset != null && !nowPlayingAsset.isEmpty()) {
                commandBuilder.set("#NowPlayingThumb.AssetPath", nowPlayingAsset);
                commandBuilder.set("#NowPlayingThumb.Visible", true);
            } else {
                commandBuilder.set("#NowPlayingThumb.Visible", false);
            }

            // Sync volume input
            if (!Boolean.TRUE.equals(VOLUME_EDITING.get(playerRef.getUuid()))) {
                int volumePercent = Math.round(VolumeUtil.clampPercent(VolumeUtil.eventDbToPercent(session.getVolume())));
                commandBuilder.set("#VolumeInput.Value", String.valueOf(volumePercent));
            }
        } else {
            LAST_TIME_SECONDS.remove(playerId);
            commandBuilder.set("#NowPlayingTime.Text", "0:00 / 0:00");
            commandBuilder.set("#SeekSlider.Value", 0);
            commandBuilder.set("#SeekSlider.Visible", false);
            commandBuilder.set("#LoopButton.Text", resolveLoopLabel(null));
            commandBuilder.set("#NowPlayingThumb.Visible", false);
            int volumePercent = VOLUME_DEFAULT_PERCENT;
            if (blockPos != null) {
                var playbackManager = MediaRadioPlugin.getInstance().getPlaybackManager();
                if (playbackManager != null) {
                    volumePercent = Math.round(
                            VolumeUtil.clampPercent(
                                    VolumeUtil.eventDbToPercent(playbackManager.getBlockVolume(blockPos, store))));
                }
            } else {
                var playbackManager = MediaRadioPlugin.getInstance().getPlaybackManager();
                if (playbackManager != null) {
                    volumePercent = Math.round(
                            VolumeUtil.clampPercent(
                                    VolumeUtil.eventDbToPercent(
                                            playbackManager.getPlayerVolume(playerRef.getUuid()))));
                }
            }
            if (!Boolean.TRUE.equals(VOLUME_EDITING.get(playerRef.getUuid()))) {
                commandBuilder.set("#VolumeInput.Value", String.valueOf(volumePercent));
            }
        }
        UIEventBuilder eventBuilder = new UIEventBuilder();
        addEventBindings(eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
        return true;
    }

    private void handleSeekScrub(float seekPercent, Store<EntityStore> store) {
        var manager = MediaRadioPlugin.getInstance().getPlaybackManager();
        PlaybackSession session = blockPos != null ? manager.getSession(blockPos)
                : manager.getSession(playerRef.getUuid());
        if (session == null || session.isStopped()) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        ScrubState state = SCRUB_STATES.computeIfAbsent(playerId, key -> new ScrubState());
        if (!state.isScrubbing) {
            state.wasPlaying = session.isPlaying();
            state.isScrubbing = true;
            if (state.wasPlaying) {
                if (blockPos != null) {
                    manager.pause(blockPos);
                } else {
                    manager.pauseByUser(playerRef);
                }
            }
        }
        state.lastPercent = seekPercent;

        long previewMs = (long) (session.getTotalDurationMs() * (seekPercent / 100.0f));
        UICommandBuilder commandBuilder = new UICommandBuilder();
        commandBuilder.set("#NowPlayingTime.Text",
                formatTime(previewMs) + " / " + formatTime(session.getTotalDurationMs()));
        commandBuilder.set("#SeekSlider.Value", (int) seekPercent);
        UIEventBuilder eventBuilder = new UIEventBuilder();
        addEventBindings(eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);

        if (state.finalizeTask != null && !state.finalizeTask.isDone()) {
            state.finalizeTask.cancel(false);
        }
        state.finalizeTask = HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            store.getExternalData().getWorld().execute(() -> {
                PlaybackSession freshSession = blockPos != null ? manager.getSession(blockPos)
                        : manager.getSession(playerId);
                if (freshSession != null && !freshSession.isStopped()) {
                    if (blockPos != null) {
                        manager.seek(blockPos, state.lastPercent / 100.0, store);
                    } else {
                        manager.seek(playerRef, state.lastPercent / 100.0, store);
                    }
                    if (state.wasPlaying) {
                        if (blockPos != null) {
                            manager.resume(blockPos, store);
                        } else {
                            manager.resume(playerRef, store);
                        }
                    }
                }
                state.isScrubbing = false;
                state.wasPlaying = false;
                state.finalizeTask = null;
            });
        }, SEEK_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private static final class ScrubState {
        private boolean isScrubbing;
        private boolean wasPlaying;
        private double lastPercent;
        private ScheduledFuture<?> finalizeTask;
    }

    private PlaybackSession resolveSession() {
        var manager = MediaRadioPlugin.getInstance().getPlaybackManager();
        return blockPos != null ? manager.getSession(blockPos) : manager.getSession(playerRef.getUuid());
    }

    private Message resolveLoopLabel(PlaybackSession session) {
        if (session != null) {
            return formatLoopLabel(session.isLoopEnabled());
        }
        if (blockPos != null) {
            return formatLoopLabel(false);
        }
        var manager = MediaRadioPlugin.getInstance().getPlaybackManager();
        return formatLoopLabel(manager.isLoopEnabled(playerRef.getUuid()));
    }

    private Message formatLoopLabel(boolean enabled) {
        String key = enabled ? "mediaRadio.customUI.loopOnLabel" : "mediaRadio.customUI.loopOffLabel";
        return Message.translation(key);
    }

    private String extractFailureReason(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null ? message : "";
    }

    private void addEventBindings(UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#PlayButton",
                EventData.of("@Url", "#UrlInput.Value"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#PauseButton",
                EventData.of("Action", "Pause"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#StopButton",
                EventData.of("Action", "Stop"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#LoopButton",
                EventData.of("Action", "Loop"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                EventData.of("Action", "Cancel"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SeekSlider",
                EventData.of("@SeekValue", "#SeekSlider.Value"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.FocusGained, "#VolumeInput",
                EventData.of("Action", "VolumeFocusGained"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.FocusLost, "#VolumeInput",
                EventData.of("Action", "VolumeFocusLost").append("@VolumeText", "#VolumeInput.Value"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#VolumeUp",
                EventData.of("Action", "VolumeUp"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#VolumeDown",
                EventData.of("Action", "VolumeDown"), false);
    }

    private void reopenPageIfNeeded(Ref<EntityStore> ref, Store<EntityStore> store, Player player) {
        if (blockPos != null) {
            return;
        }
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new RadioConfigPage(playerRef, blockPos));
        }
    }

    private String getLibraryOwnerId(Store<EntityStore> store) {
        if (blockPos == null) {
            return playerRef.getUuid().toString();
        }
        String worldId = "world";
        if (store != null && store.getExternalData() != null && store.getExternalData().getWorld() != null) {
            Object world = store.getExternalData().getWorld();
            String resolved = tryInvokeString(world, "getId");
            if (resolved == null) {
                resolved = tryInvokeString(world, "getUuid");
            }
            if (resolved == null) {
                resolved = tryInvokeString(world, "getName");
            }
            if (resolved != null && !resolved.isEmpty()) {
                worldId = resolved;
            }
        }
        return "boombox:" + worldId + ":" + blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
    }

    private String tryInvokeString(Object target, String methodName) {
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(methodName);
            Object result = method.invoke(target);
            return result != null ? result.toString() : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private float parseVolumePercent(String text) {
        if (text == null) {
            return -1.0f;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return -1.0f;
        }
        try {
            return Float.parseFloat(trimmed);
        } catch (NumberFormatException e) {
            return -1.0f;
        }
    }

    private void sendChatAndClose(Ref<EntityStore> ref, Store<EntityStore> store, String message) {
        if (message != null && !message.isEmpty()) {
            playerRef.sendMessage(Message.raw(message));
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().setPage(ref, store, Page.None);
        }
    }
}
