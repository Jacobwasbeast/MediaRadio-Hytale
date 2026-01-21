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
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.MediaRadioPlugin;
import com.hypixel.hytale.server.core.Message;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class RadioConfigPage extends InteractiveCustomUIPage<RadioConfigPage.RadioPageData> {
    private static final long TIME_UPDATE_PERIOD_MS = 500;
    private static final Map<UUID, ScheduledFuture<?>> TIME_UPDATERS = new ConcurrentHashMap<>();

    private final PlayerRef playerRef;

    public RadioConfigPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, RadioConfigPage.RadioPageData.CODEC);
        this.playerRef = playerRef;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder, @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/MediaRadio/RadioConfig.ui");

        // Populate Now Playing
        var session = MediaRadioPlugin.getInstance().getPlaybackManager().getSession(playerRef.getUuid());
        if (session != null && !session.isStopped()) {
            commandBuilder.set("#NowPlayingTitle.Text", session.getTitle().isEmpty() ? "Unknown Title" : session.getTitle());
            commandBuilder.set("#NowPlayingArtist.Text", session.getArtist());
            commandBuilder.set("#PauseButton.Text", session.isPaused() ? "Resume" : "Pause");
            commandBuilder.set("#NowPlayingTime.Text", formatTime(session.getCurrentPositionMs()) + " / "
                    + formatTime(session.getTotalDurationMs()));
        } else {
            commandBuilder.set("#NowPlayingTitle.Text", "No Media Playing");
            commandBuilder.set("#NowPlayingArtist.Text", "");
            commandBuilder.set("#PauseButton.Text", "Pause");
            commandBuilder.set("#NowPlayingTime.Text", "0:00 / 0:00");
        }

        if (session != null && !session.getUrl().isEmpty()) {
            commandBuilder.set("#UrlInput.Value", session.getUrl());
        }

        // Populate Library List
        var library = MediaRadioPlugin.getInstance().getMediaLibrary();
        var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
        if (library != null) {
            int i = 0;
            String playerId = playerRef.getUuid().toString();
            for (dev.jacobwasbeast.manager.MediaLibrary.SavedSong song : library.getSongsForPlayer(playerId)) {
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

        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#PlayButton",
                EventData.of("@Url", "#UrlInput.Value"), false);

        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#PauseButton",
                EventData.of("Action", "Pause"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#StopButton",
                EventData.of("Action", "Stop"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                EventData.of("Action", "Cancel"), false);

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
                    String playerId = playerRef.getUuid().toString();
                    library.removeSong(playerId, finalRemoveUrl);
                    if (!library.isUrlReferencedByOtherPlayers(playerId, finalRemoveUrl)) {
                        MediaRadioPlugin.getInstance().getMediaManager().deleteMediaForUrl(finalRemoveUrl);
                    }
                }
                var manager = MediaRadioPlugin.getInstance().getPlaybackManager();
                var session = manager.getSession(playerRef.getUuid());
                if (session != null && finalRemoveUrl != null && finalRemoveUrl.equals(session.getUrl())) {
                    manager.stop(playerRef);
                }
                player.getPageManager().openCustomPage(ref, store, new RadioConfigPage(playerRef));
            });
            return;
        } else if ("Pause".equals(data.action)) {
            data.action = null; // Consume
            store.getExternalData().getWorld().execute(() -> {
                var manager = MediaRadioPlugin.getInstance().getPlaybackManager();
                var session = manager.getSession(playerRef.getUuid());
                if (session != null) {
                    if (session.isPaused()) {
                        manager.resume(playerRef, store);
                        player.sendMessage(Message.translation("Resumed playback."));
                    } else {
                        manager.pauseByUser(playerRef);
                        player.sendMessage(Message.translation("Paused playback."));
                    }
                    player.getPageManager().openCustomPage(ref, store, new RadioConfigPage(playerRef));
                }
            });
            return;
        } else if ("Stop".equals(data.action)) {
            data.action = null; // Consume
            store.getExternalData().getWorld().execute(() -> {
                MediaRadioPlugin.getInstance().getPlaybackManager().stop(playerRef);
                player.sendMessage(Message.translation("Stopped playback."));
                player.getPageManager().openCustomPage(ref, store, new RadioConfigPage(playerRef));
            });
            return;
        }

        if (data.seekValue != null) {
            double seek = data.seekValue;
            data.seekValue = null; // Consume
            store.getExternalData().getWorld().execute(() -> {
                // Seek expects 0.0-1.0, slider is 0-100
                MediaRadioPlugin.getInstance().getPlaybackManager().seek(playerRef, seek / 100.0, store);
                player.sendMessage(Message.translation("Seeked to " + (int) seek + "%"));
            });
            return;
        }

        String url = data.url;
        if (url == null || url.isEmpty()) {
            url = data.directUrl;
        }
        if (url != null && !url.isEmpty()) {
            final String finalUrl = url;
            data.url = null;
            data.directUrl = null;
            var library = MediaRadioPlugin.getInstance().getMediaLibrary();
            if (library != null) {
                library.upsertSongStatus(playerRef.getUuid().toString(), finalUrl, "Downloading...", null, null, null, 0, null,
                        null);
                player.getPageManager().openCustomPage(ref, store, new RadioConfigPage(playerRef));
            }
            player.sendMessage(Message.translation("Requesting media..."));

            MediaRadioPlugin.getInstance().getMediaManager().requestMedia(finalUrl).thenAccept(mediaInfo -> {
                store.getExternalData().getWorld().execute(() -> {
                    // Start playback
                    MediaRadioPlugin.getInstance().getMediaManager().playSound(mediaInfo, playerRef, store);

                    // Save to library
                    if (library != null) {
                        library.upsertSongStatus(
                                playerRef.getUuid().toString(),
                                mediaInfo.url,
                                "Ready",
                                mediaInfo.title != null ? mediaInfo.title : "Unknown Title",
                                mediaInfo.artist != null ? mediaInfo.artist : "Unknown Artist",
                                mediaInfo.thumbnailUrl != null ? mediaInfo.thumbnailUrl : "",
                                mediaInfo.duration,
                                mediaInfo.trackId,
                                mediaInfo.thumbnailAssetPath);
                    }

                    // Feedback
                    Player p = store.getComponent(ref, Player.getComponentType());
                    if (p != null) {
                        p.sendMessage(Message
                                .translation("Playing: " + (mediaInfo.title != null ? mediaInfo.title : "Unknown")));

                        // Refresh UI to show new song details
                        p.getPageManager().openCustomPage(ref, store, new RadioConfigPage(playerRef));
                    }
                });
            }).exceptionally(e -> {
                store.getExternalData().getWorld().execute(() -> {
                    Player updatedPlayer = store.getComponent(ref, Player.getComponentType());
                    if (updatedPlayer != null) {
                        updatedPlayer.sendMessage(Message.translation("Failed to load media. Removed from library."));
                    }
                    if (library != null) {
                        String playerId = playerRef.getUuid().toString();
                        library.removeSong(playerId, finalUrl);
                        if (!library.isUrlReferencedByOtherPlayers(playerId, finalUrl)) {
                            MediaRadioPlugin.getInstance().getMediaManager().deleteMediaForUrl(finalUrl);
                        }
                        player.getPageManager().openCustomPage(ref, store, new RadioConfigPage(playerRef));
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
                .append(new KeyedCodec<>("SeekValue", Codec.DOUBLE), (d, v) -> d.seekValue = v, d -> d.seekValue)
                .add()
                .build();

        public String action;
        public String url;
        public String directUrl;
        public Double seekValue;
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
        var session = MediaRadioPlugin.getInstance().getPlaybackManager().getSession(playerRef.getUuid());
        UICommandBuilder commandBuilder = new UICommandBuilder();
        if (session != null && !session.isStopped()) {
            commandBuilder.set("#NowPlayingTime.Text", formatTime(session.getCurrentPositionMs()) + " / "
                    + formatTime(session.getTotalDurationMs()));
        } else {
            commandBuilder.set("#NowPlayingTime.Text", "0:00 / 0:00");
        }
        sendUpdate(commandBuilder, false);
        return true;
    }
}
