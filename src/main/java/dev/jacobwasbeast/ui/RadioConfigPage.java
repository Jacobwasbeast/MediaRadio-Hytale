package dev.jacobwasbeast.ui;

import au.ellie.hyui.builders.ButtonBuilder;
import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.ImageBuilder;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.SliderBuilder;
import au.ellie.hyui.builders.TextFieldBuilder;
import au.ellie.hyui.events.UIContext;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.MediaRadioPlugin;
import dev.jacobwasbeast.manager.PlaybackSession;
import dev.jacobwasbeast.manager.PlaylistManager;
import dev.jacobwasbeast.util.PlaybackScopeUtil;
import dev.jacobwasbeast.util.VolumeUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class RadioConfigPage {
    private static final long TIME_UPDATE_PERIOD_MS = 1000;
    private static final long SEEK_DEBOUNCE_MS = 250;
    private static final long VOLUME_COOLDOWN_MS = 5000;
    private static final int VOLUME_STEP_PERCENT = 10;
    private static final int VOLUME_DEFAULT_PERCENT = VolumeUtil.DEFAULT_PERCENT;
    private static final String DEFAULT_IMAGE = "MediaRadio/Icons/placeholder.png";

    private static final Map<UUID, ActivePage> ACTIVE_PAGES = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> TIME_UPDATERS = new ConcurrentHashMap<>();
    private static final Map<UUID, ScrubState> SCRUB_STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_VOLUME_CHANGE_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> VOLUME_EDITING = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SELECTED_TAB = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SELECTED_PLAYLIST = new ConcurrentHashMap<>();
    private static final Map<UUID, String> PLAYLIST_SOURCE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> EDITING_SONG_URL = new ConcurrentHashMap<>();

    private final PlayerRef playerRef;
    @Nullable
    private final Vector3i blockPos;

    private RadioConfigPage(PlayerRef playerRef, @Nullable Vector3i blockPos) {
        this.playerRef = playerRef;
        this.blockPos = blockPos;
    }

    public static void open(PlayerRef playerRef, Store<EntityStore> store, @Nullable Vector3i blockPos) {
        if (playerRef == null || store == null) {
            return;
        }
        RadioConfigPage controller = new RadioConfigPage(playerRef, blockPos);
        controller.openInternal(store);
    }

    private void openInternal(Store<EntityStore> store) {
        stopTimeUpdater(playerRef.getUuid());
        UiModel model = buildModel(store);
        PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                .withLifetime(CustomPageLifetime.CanDismiss)
                .loadHtml("Pages/MediaRadio/RadioConfig.html", model.templateVars);

        registerEvents(builder, store, model);
        HyUIPage page = builder.open(store);
        ACTIVE_PAGES.put(playerRef.getUuid(), new ActivePage(page, this));
        startTimeUpdater();
    }

    private void registerEvents(PageBuilder builder, Store<EntityStore> store, UiModel model) {
        addButtonHandler(builder, "tab-now", ctx -> handleAction(store, ActionData.forAction("TabNow")));
        addButtonHandler(builder, "tab-library", ctx -> handleAction(store, ActionData.forAction("TabLibrary")));
        addButtonHandler(builder, "tab-playlists", ctx -> handleAction(store, ActionData.forAction("TabPlaylists")));

        addButtonHandler(builder, "play-url", ctx -> {
            ActionData data = ActionData.forAction("PlayUrl");
            data.directUrl = ctx.getValue("url-input", String.class).orElse(null);
            handleAction(store, data);
        });
        addButtonHandler(builder, "queue-url", ctx -> {
            ActionData data = ActionData.forAction("QueueUrl");
            data.directUrl = ctx.getValue("url-input", String.class).orElse(null);
            handleAction(store, data);
        });
        addButtonHandler(builder, "play-pause-button", ctx -> handleAction(store, ActionData.forAction("PlayPause")));
        addButtonHandler(builder, "stop-button", ctx -> handleAction(store, ActionData.forAction("Stop")));
        addButtonHandler(builder, "prev-button", ctx -> handleAction(store, ActionData.forAction("Prev")));
        addButtonHandler(builder, "next-button", ctx -> handleAction(store, ActionData.forAction("Next")));
        addButtonHandler(builder, "loop-button", ctx -> handleAction(store, ActionData.forAction("Loop")));
        addButtonHandler(builder, "shuffle-button", ctx -> handleAction(store, ActionData.forAction("Shuffle")));
        addButtonHandler(builder, "cancel-button", ctx -> handleAction(store, ActionData.forAction("Cancel")));

        if (builder.getById("seek-slider", SliderBuilder.class).isPresent()) {
            builder.addEventListener("seek-slider", CustomUIEventBindingType.ValueChanged, Integer.class, (value, ctx) -> {
                if (value == null) {
                    return;
                }
                ActionData data = new ActionData();
                data.seekValue = value.floatValue();
                handleAction(store, data);
            });
        }

        if (builder.getById("volume-input", TextFieldBuilder.class).isPresent()) {
            builder.addEventListener("volume-input", CustomUIEventBindingType.FocusGained, (ignored, ctx) ->
                    handleAction(store, ActionData.forAction("VolumeFocusGained")));
            builder.addEventListener("volume-input", CustomUIEventBindingType.FocusLost, (ignored, ctx) -> {
                ActionData data = ActionData.forAction("VolumeFocusLost");
                data.volumeText = ctx.getValue("volume-input", String.class).orElse(null);
                handleAction(store, data);
            });
        }
        addButtonHandler(builder, "volume-up", ctx -> handleAction(store, ActionData.forAction("VolumeUp")));
        addButtonHandler(builder, "volume-down", ctx -> handleAction(store, ActionData.forAction("VolumeDown")));

        addButtonHandler(builder, "playlist-new", ctx -> handleAction(store, ActionData.forAction("PlaylistNew")));
        addButtonHandler(builder, "playlist-save", ctx -> {
            ActionData data = ActionData.forAction("PlaylistSave");
            data.name = ctx.getValue("playlist-name", String.class).orElse(null);
            data.description = ctx.getValue("playlist-description", String.class).orElse(null);
            data.icon = ctx.getValue("playlist-icon", String.class).orElse(null);
            handleAction(store, data);
        });
        addButtonHandler(builder, "playlist-delete", ctx -> handleAction(store, ActionData.forAction("PlaylistDelete")));
        addButtonHandler(builder, "playlist-load", ctx -> handleAction(store, ActionData.forAction("PlaylistLoad")));
        addButtonHandler(builder, "playlist-play", ctx -> handleAction(store, ActionData.forAction("PlaylistPlay")));

        addButtonHandler(builder, "song-edit-save", ctx -> {
            ActionData data = ActionData.forAction("SongEditSave");
            data.title = ctx.getValue("song-edit-title", String.class).orElse(null);
            data.artist = ctx.getValue("song-edit-artist", String.class).orElse(null);
            data.description = ctx.getValue("song-edit-description", String.class).orElse(null);
            data.icon = ctx.getValue("song-edit-icon", String.class).orElse(null);
            handleAction(store, data);
        });
        addButtonHandler(builder, "song-edit-cancel", ctx -> handleAction(store, ActionData.forAction("SongEditCancel")));

        addButtonHandler(builder, "source-boombox", ctx -> handleAction(store, ActionData.forAction("SourceBoombox")));
        addButtonHandler(builder, "source-player", ctx -> handleAction(store, ActionData.forAction("SourcePlayer")));

        for (QueueItemView item : model.queueItems) {
            int index = item.index;
            addButtonHandler(builder, item.playId, ctx -> {
                ActionData data = ActionData.forAction("QueuePlay");
                data.index = index;
                handleAction(store, data);
            });
            addButtonHandler(builder, item.removeId, ctx -> {
                ActionData data = ActionData.forAction("QueueRemove");
                data.index = index;
                handleAction(store, data);
            });
            addButtonHandler(builder, item.upId, ctx -> {
                ActionData data = ActionData.forAction("QueueUp");
                data.index = index;
                handleAction(store, data);
            });
            addButtonHandler(builder, item.downId, ctx -> {
                ActionData data = ActionData.forAction("QueueDown");
                data.index = index;
                handleAction(store, data);
            });
        }

        for (LibraryItemView item : model.libraryItems) {
            addButtonHandler(builder, item.playId, ctx -> {
                ActionData data = ActionData.forAction("PlayUrl");
                data.directUrl = item.url;
                handleAction(store, data);
            });
            addButtonHandler(builder, item.queueId, ctx -> {
                ActionData data = ActionData.forAction("QueueAdd");
                data.directUrl = item.url;
                handleAction(store, data);
            });
            addButtonHandler(builder, item.addId, ctx -> {
                ActionData data = ActionData.forAction("PlaylistAdd");
                data.directUrl = item.url;
                handleAction(store, data);
            });
            addButtonHandler(builder, item.editId, ctx -> {
                ActionData data = ActionData.forAction("SongEdit");
                data.directUrl = item.url;
                handleAction(store, data);
            });
            addButtonHandler(builder, item.removeId, ctx -> {
                ActionData data = ActionData.forAction("Remove");
                data.directUrl = item.url;
                handleAction(store, data);
            });
        }

        for (PlaylistView item : model.playlists) {
            addButtonHandler(builder, item.selectId, ctx -> {
                ActionData data = ActionData.forAction("SelectPlaylist");
                data.playlistId = item.playlistId;
                handleAction(store, data);
            });
        }

        for (PlaylistItemView item : model.playlistItems) {
            int index = item.index;
            addButtonHandler(builder, item.playId, ctx -> {
                ActionData data = ActionData.forAction("PlaylistItemPlay");
                data.index = index;
                handleAction(store, data);
            });
            addButtonHandler(builder, item.removeId, ctx -> {
                ActionData data = ActionData.forAction("PlaylistItemRemove");
                data.index = index;
                handleAction(store, data);
            });
            addButtonHandler(builder, item.upId, ctx -> {
                ActionData data = ActionData.forAction("PlaylistItemUp");
                data.index = index;
                handleAction(store, data);
            });
            addButtonHandler(builder, item.downId, ctx -> {
                ActionData data = ActionData.forAction("PlaylistItemDown");
                data.index = index;
                handleAction(store, data);
            });
        }
    }

    private void addButtonHandler(PageBuilder builder, String id, java.util.function.Consumer<UIContext> handler) {
        try {
            builder.addEventListener(id, CustomUIEventBindingType.Activating, (ignored, ctx) -> handler.accept(ctx));
        } catch (IllegalArgumentException ignored) {
            // Element isn't present in this tab/page variant.
        }
    }

    private void handleAction(Store<EntityStore> store, ActionData data) {
        if (data == null) {
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || store == null) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        String scopeId = getScopeId(store);
        String playerScopeId = PlaybackScopeUtil.playerScopeId(playerRef.getUuid());
        boolean isBoombox = blockPos != null;
        PlaylistManager playlistManager = MediaRadioPlugin.getInstance().getPlaylistManager();
        String source = PLAYLIST_SOURCE.get(playerRef.getUuid());
        if (!isBoombox) {
            source = "player";
        } else if (source == null || source.isEmpty()) {
            source = "boombox";
        }
        PLAYLIST_SOURCE.put(playerRef.getUuid(), source);
        String playlistScopeId = isBoombox && "player".equals(source) ? playerScopeId : scopeId;
        String action = data.action;

        if ("Cancel".equals(action)) {
            closePage(store);
            return;
        }
        if ("TabNow".equals(action)) {
            SELECTED_TAB.put(playerRef.getUuid(), "Now");
            refreshUiAfterAction(store);
            return;
        }
        if ("TabLibrary".equals(action)) {
            SELECTED_TAB.put(playerRef.getUuid(), "Library");
            refreshUiAfterAction(store);
            return;
        }
        if ("TabPlaylists".equals(action)) {
            SELECTED_TAB.put(playerRef.getUuid(), "Playlists");
            refreshUiAfterAction(store);
            return;
        }
        if ("SourceBoombox".equals(action)) {
            PLAYLIST_SOURCE.put(playerRef.getUuid(), "boombox");
            refreshUiAfterAction(store);
            return;
        }
        if ("SourcePlayer".equals(action)) {
            PLAYLIST_SOURCE.put(playerRef.getUuid(), "player");
            refreshUiAfterAction(store);
            return;
        }

        if ("Loop".equals(action)) {
            if (playlistManager != null) {
                PlaylistManager.QueueState queue = playlistManager.getQueue(scopeId);
                PlaylistManager.LoopMode nextMode = PlaylistManager.LoopMode.OFF;
                if (queue != null) {
                    if (queue.loopMode == PlaylistManager.LoopMode.OFF) {
                        nextMode = PlaylistManager.LoopMode.ONE;
                    } else if (queue.loopMode == PlaylistManager.LoopMode.ONE) {
                        nextMode = PlaylistManager.LoopMode.ALL;
                    }
                }
                playlistManager.setLoopMode(scopeId, nextMode);
                PlaybackSession session = resolveSession();
                if (session != null) {
                    session.setLoopEnabled(nextMode == PlaylistManager.LoopMode.ONE);
                }
            }
            refreshUiAfterAction(store);
            return;
        }
        if ("Shuffle".equals(action)) {
            if (playlistManager != null) {
                playlistManager.shuffleQueue(scopeId);
            }
            refreshUiAfterAction(store);
            return;
        }
        if ("Prev".equals(action) || "Next".equals(action)) {
            if (playlistManager != null) {
                PlaylistManager.QueueState queue = playlistManager.getQueue(scopeId);
                if (queue != null && queue.items != null && !queue.items.isEmpty()) {
                    int index = queue.index;
                    if ("Prev".equals(action)) {
                        index = Math.max(0, index - 1);
                    } else {
                        index = index + 1;
                        if (index >= queue.items.size()) {
                            index = queue.loopMode == PlaylistManager.LoopMode.ALL ? 0 : queue.items.size() - 1;
                        }
                    }
                    playlistManager.setQueueIndex(scopeId, index);
                    playQueueIndex(scopeId, index, store);
                }
            }
            return;
        }

        if ("QueuePlay".equals(action)) {
            if (data.index != null) {
                playQueueIndex(scopeId, data.index, store);
            }
            return;
        }
        if ("QueueRemove".equals(action)) {
            if (playlistManager != null && data.index != null) {
                removeQueueIndex(scopeId, data.index);
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("QueueUp".equals(action) || "QueueDown".equals(action)) {
            if (playlistManager != null && data.index != null) {
                int from = data.index;
                int to = "QueueUp".equals(action) ? from - 1 : from + 1;
                moveQueueIndex(scopeId, from, to);
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("QueueAdd".equals(action) || "QueueUrl".equals(action)) {
            String url = resolveUrl(data);
            if (url != null && playlistManager != null) {
                PlaylistManager.PlaylistItem item = resolvePlaylistItem(scopeId, url);
                if (item != null) {
                    appendQueueItem(scopeId, item);
                }
            }
            refreshUiAfterAction(store);
            return;
        }
        if ("PlayPause".equals(action)) {
            PlaybackSession session = resolveSession();
            if (session != null && !session.isStopped() && !session.isPaused()) {
                store.getExternalData().getWorld().execute(() -> {
                    if (blockPos != null) {
                        MediaRadioPlugin.getInstance().getPlaybackManager().pause(blockPos);
                    } else {
                        MediaRadioPlugin.getInstance().getPlaybackManager().pauseByUser(playerRef);
                    }
                    refreshUiAfterAction(store);
                });
            } else if (playlistManager != null) {
                if (session != null && session.isPaused()) {
                    store.getExternalData().getWorld().execute(() -> {
                        if (blockPos != null) {
                            MediaRadioPlugin.getInstance().getPlaybackManager().resume(blockPos, store);
                        } else {
                            MediaRadioPlugin.getInstance().getPlaybackManager().resume(playerRef, store);
                        }
                        refreshUiAfterAction(store);
                    });
                } else {
                    PlaylistManager.QueueState queue = playlistManager.getQueue(scopeId);
                    if (queue != null && queue.items != null && !queue.items.isEmpty()) {
                        playQueueIndex(scopeId, queue.index, store);
                    }
                }
            }
            return;
        }
        if ("PlayNow".equals(action)) {
            PlaybackSession session = resolveSession();
            if (session != null && session.isPaused()) {
                store.getExternalData().getWorld().execute(() -> {
                    if (blockPos != null) {
                        MediaRadioPlugin.getInstance().getPlaybackManager().resume(blockPos, store);
                    } else {
                        MediaRadioPlugin.getInstance().getPlaybackManager().resume(playerRef, store);
                    }
                    refreshUiAfterAction(store);
                });
            } else if (playlistManager != null) {
                PlaylistManager.QueueState queue = playlistManager.getQueue(scopeId);
                if (queue != null && queue.items != null && !queue.items.isEmpty()) {
                    playQueueIndex(scopeId, queue.index, store);
                }
            }
            return;
        }
        if ("PlayUrl".equals(action)) {
            String url = resolveUrl(data);
            if (url != null) {
                PlaylistManager.PlaylistItem item = resolvePlaylistItem(scopeId, url);
                if (item != null && playlistManager != null) {
                    playlistManager.setQueue(scopeId, List.of(item), 0, null, null);
                }
                playUrlForScope(scopeId, url, store);
            }
            return;
        }
        if ("SelectPlaylist".equals(action)) {
            if (data.playlistId != null && !data.playlistId.isEmpty()) {
                SELECTED_PLAYLIST.put(playerRef.getUuid(), data.playlistId);
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("PlaylistNew".equals(action)) {
            if (playlistManager != null) {
                PlaylistManager.Playlist playlist = playlistManager.createPlaylist(playlistScopeId, "New Playlist");
                SELECTED_PLAYLIST.put(playerRef.getUuid(), playlist.id);
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("PlaylistSave".equals(action)) {
            if (playlistManager != null) {
                String playlistId = SELECTED_PLAYLIST.get(playerRef.getUuid());
                playlistManager.updatePlaylist(playlistScopeId, playlistId, data.name, data.description, data.icon);
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("PlaylistDelete".equals(action)) {
            if (playlistManager != null) {
                String playlistId = SELECTED_PLAYLIST.get(playerRef.getUuid());
                playlistManager.deletePlaylist(playlistScopeId, playlistId);
                SELECTED_PLAYLIST.remove(playerRef.getUuid());
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("PlaylistAdd".equals(action)) {
            String url = resolveUrl(data);
            if (playlistManager != null && url != null) {
                String playlistId = SELECTED_PLAYLIST.get(playerRef.getUuid());
                PlaylistManager.PlaylistItem item = resolvePlaylistItem(scopeId, url);
                if (item != null) {
                    if (playlistId == null || playlistId.isEmpty()) {
                        PlaylistManager.Playlist playlist = playlistManager.createPlaylist(playlistScopeId, "New Playlist");
                        playlistId = playlist.id;
                        SELECTED_PLAYLIST.put(playerRef.getUuid(), playlistId);
                    }
                    playlistManager.addItem(playlistScopeId, playlistId, item);
                }
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("PlaylistItemRemove".equals(action)) {
            if (playlistManager != null && data.index != null) {
                String playlistId = SELECTED_PLAYLIST.get(playerRef.getUuid());
                playlistManager.removeItem(playlistScopeId, playlistId, data.index);
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("PlaylistItemUp".equals(action) || "PlaylistItemDown".equals(action)) {
            if (playlistManager != null && data.index != null) {
                String playlistId = SELECTED_PLAYLIST.get(playerRef.getUuid());
                int from = data.index;
                int to = "PlaylistItemUp".equals(action) ? from - 1 : from + 1;
                playlistManager.moveItem(playlistScopeId, playlistId, from, to);
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("PlaylistItemIcon".equals(action)) {
            if (playlistManager != null && data.index != null) {
                String playlistId = SELECTED_PLAYLIST.get(playerRef.getUuid());
                PlaylistManager.Playlist playlist = playlistManager.getPlaylist(playlistScopeId, playlistId);
                if (playlist != null && playlist.items != null && data.index >= 0
                        && data.index < playlist.items.size()) {
                    PlaylistManager.PlaylistItem item = playlist.items.get(data.index);
                    var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
                    String iconPath = resolveItemIcon(item, mediaManager);
                    if (iconPath != null && !iconPath.isEmpty()) {
                        playlistManager.updatePlaylist(playlistScopeId, playlistId, null, playlist.description, iconPath);
                    }
                }
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("PlaylistLoad".equals(action) || "PlaylistPlay".equals(action) || "PlaylistItemPlay".equals(action)) {
            if (playlistManager != null) {
                String playlistId = SELECTED_PLAYLIST.get(playerRef.getUuid());
                PlaylistManager.Playlist playlist = playlistManager.getPlaylist(playlistScopeId, playlistId);
                if (playlist != null && playlist.items != null) {
                    int startIndex = 0;
                    if ("PlaylistItemPlay".equals(action) && data.index != null) {
                        startIndex = data.index;
                    }
                    playlistManager.setQueue(scopeId, playlist.items, startIndex, playlistScopeId, playlistId);
                    if ("PlaylistPlay".equals(action) || "PlaylistItemPlay".equals(action)) {
                        playQueueIndex(scopeId, startIndex, store);
                    }
                }
            }
            return;
        }
        if ("SongEdit".equals(action)) {
            String url = resolveUrl(data);
            if (url != null) {
                EDITING_SONG_URL.put(playerRef.getUuid(), url);
                refreshUiAfterAction(store);
            }
            return;
        }
        if ("SongEditSave".equals(action)) {
            var library = MediaRadioPlugin.getInstance().getMediaLibrary();
            String url = EDITING_SONG_URL.get(playerRef.getUuid());
            if (library != null && url != null) {
                library.updateCustomMetadata(scopeId, url, data.title, data.artist, data.description, data.icon);
            }
            EDITING_SONG_URL.remove(playerRef.getUuid());
            refreshUiAfterAction(store);
            return;
        }
        if ("SongEditCancel".equals(action)) {
            EDITING_SONG_URL.remove(playerRef.getUuid());
            refreshUiAfterAction(store);
            return;
        }
        if ("Remove".equals(action)) {
            String removeUrl = resolveUrl(data);
            final String finalRemoveUrl = removeUrl;
            store.getExternalData().getWorld().execute(() -> {
                var library = MediaRadioPlugin.getInstance().getMediaLibrary();
                if (library != null && finalRemoveUrl != null && !finalRemoveUrl.isEmpty()) {
                    library.removeSong(scopeId, finalRemoveUrl);
                    if (!library.isUrlReferencedByOtherPlayers(scopeId, finalRemoveUrl)) {
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
                refreshUiAfterAction(store);
            });
            return;
        }
        if ("Pause".equals(action)) {
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
                    refreshUiAfterAction(store);
                }
            });
            return;
        }
        if ("Stop".equals(action)) {
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
                                scopeId,
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
                refreshUiAfterAction(store);
            });
            return;
        }

        if (data.seekValue != null) {
            float seek = data.seekValue;
            data.seekValue = null;
            handleSeekScrub(seek, store);
            return;
        }

        if ("VolumeFocusGained".equals(action)) {
            VOLUME_EDITING.put(playerRef.getUuid(), true);
            return;
        }
        if ("VolumeFocusLost".equals(action)) {
            VOLUME_EDITING.remove(playerRef.getUuid());
        }
        if ("VolumeUp".equals(action) || "VolumeDown".equals(action)) {
            boolean up = "VolumeUp".equals(action);
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

                updateVolumeField(Math.round(nextClamped));
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

                    updateVolumeField(Math.round(nextClamped));
                });
            }
        }
    }

    private void refreshUiAfterAction(Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            open(playerRef, store, blockPos);
            return;
        }
        store.getExternalData().getWorld().execute(() -> open(playerRef, store, blockPos));
    }

    private UiModel buildModel(Store<EntityStore> store) {
        String scopeId = getScopeId(store);
        String playerScopeId = PlaybackScopeUtil.playerScopeId(playerRef.getUuid());
        boolean isBoombox = blockPos != null;
        PlaylistManager playlistManager = MediaRadioPlugin.getInstance().getPlaylistManager();
        PlaylistManager.QueueState queue = playlistManager != null ? playlistManager.getQueue(scopeId) : null;

        String selectedTab = SELECTED_TAB.getOrDefault(playerRef.getUuid(), "Now");
        boolean tabNow = "Now".equals(selectedTab);
        boolean tabLibrary = "Library".equals(selectedTab);
        boolean tabPlaylists = "Playlists".equals(selectedTab);

        String source = PLAYLIST_SOURCE.get(playerRef.getUuid());
        if (!isBoombox) {
            source = "player";
        } else if (source == null || source.isEmpty()) {
            source = "boombox";
        }
        PLAYLIST_SOURCE.put(playerRef.getUuid(), source);
        String playlistScopeId = isBoombox && "player".equals(source) ? playerScopeId : scopeId;

        String selectedPlaylistId = SELECTED_PLAYLIST.get(playerRef.getUuid());
        PlaylistManager.Playlist selectedPlaylist = playlistManager != null
                ? playlistManager.getPlaylist(playlistScopeId, selectedPlaylistId)
                : null;
        String selectedPlaylistName = selectedPlaylist != null && selectedPlaylist.name != null
                ? selectedPlaylist.name
                : "None Selected";
        String playlistTarget;
        playlistTarget = "Target playlist: " + selectedPlaylistName;

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", "Media Radio");
        vars.put("tabNowLabel", "Now Playing");
        vars.put("tabLibraryLabel", "Library");
        String playlistsTabLabel = isBoombox && "boombox".equals(source)
                ? "Boombox Playlists"
                : "My Playlists";
        vars.put("tabPlaylistsLabel", playlistsTabLabel);
        vars.put("tabNow", tabNow);
        vars.put("tabLibrary", tabLibrary);
        vars.put("tabPlaylists", tabPlaylists);
        vars.put("showSourceToggle", isBoombox);
        vars.put("playlistTarget", playlistTarget);

        PlaybackSession session = resolveSession();
        var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
        String nowTitle = "No Media Playing";
        String nowArtist = "";
        String nowTime = "0:00 / 0:00";
        int seekValue = 0;
        String pauseLabel = "Pause";
        String nowThumb = DEFAULT_IMAGE;
        String playPauseIcon = "MediaRadio/Icons/play.png";
        if (session != null && !session.isStopped()) {
            nowTitle = session.getTitle().isEmpty() ? "Unknown Title" : session.getTitle();
            nowArtist = session.getArtist();
            pauseLabel = session.isPaused() ? "Resume" : "Pause";
            nowTime = formatTime(session.getCurrentPositionMs()) + " / " + formatTime(session.getTotalDurationMs());
            seekValue = (int) (session.getProgress() * 100);
            nowThumb = resolveNowThumbnail(session, queue, mediaManager);
            playPauseIcon = session.isPaused() ? "MediaRadio/Icons/play.png" : "MediaRadio/Icons/pause.png";
        }

        int volumePercent = VOLUME_DEFAULT_PERCENT;
        if (session != null && !session.isStopped() && session.getVolume() > 0) {
            volumePercent = Math.round(VolumeUtil.clampPercent(VolumeUtil.eventDbToPercent(session.getVolume())));
        } else {
            var playbackManager = MediaRadioPlugin.getInstance().getPlaybackManager();
            if (blockPos != null && playbackManager != null) {
                volumePercent = Math.round(
                        VolumeUtil.clampPercent(
                                VolumeUtil.eventDbToPercent(playbackManager.getBlockVolume(blockPos, store))));
            } else if (playbackManager != null) {
                volumePercent = Math.round(
                        VolumeUtil.clampPercent(
                                VolumeUtil.eventDbToPercent(playbackManager.getPlayerVolume(playerRef.getUuid()))));
            }
        }

        vars.put("nowTitle", nowTitle);
        vars.put("nowArtist", nowArtist);
        vars.put("nowTime", nowTime);
        vars.put("seekValue", seekValue);
        vars.put("pauseLabel", pauseLabel);
        vars.put("nowThumb", nowThumb);
        vars.put("playPauseIcon", playPauseIcon);
        vars.put("volumeLabel", "Volume");
        vars.put("volumeHint", "Max 200%");
        vars.put("volumeValue", String.valueOf(volumePercent));
        vars.put("playLabel", "Play");
        vars.put("stopLabel", "Stop");
        vars.put("prevLabel", "Prev");
        vars.put("nextLabel", "Next");
        vars.put("loopLabel", resolveLoopLabel(queue));
        // shuffle label removed from UI
        vars.put("queueLabel", "Queue");
        vars.put("queueLabelShort", "Queue");
        vars.put("libraryLabel", "Library");
        vars.put("playlistsLabel", "Playlists");
        vars.put("selectedPlaylistLabel", "Selected Playlist");
        vars.put("playlistItemsLabel", "Playlist Items");
        vars.put("addLabel", "Add");
        vars.put("editLabel", "Edit");
        vars.put("removeLabel", "Remove");
        vars.put("editSongLabel", "Edit Song");
        vars.put("saveLabel", "Save");
        vars.put("cancelLabel", "Cancel");
        vars.put("closeLabel", "Close");
        vars.put("sourceBoomboxLabel", "This Boombox");
        vars.put("sourcePlayerLabel", "My Playlists");
        vars.put("playlistNameLabel", "Playlist Name");
        vars.put("newPlaylistLabel", "New");
        vars.put("deletePlaylistLabel", "Delete");
        vars.put("loadPlaylistLabel", "Load to Queue");
        vars.put("playPlaylistLabel", "Play Playlist");
        vars.put("selectLabel", "Select");
        vars.put("setIconLabel", "Set Icon");
        vars.put("songTitleLabel", "Song Title");
        vars.put("songArtistLabel", "Artist Name");
        vars.put("descriptionLabel", "Description");
        vars.put("iconLabel", "Icon Asset");

        vars.put("urlPlaceholder", "Enter YouTube URL...");
        vars.put("urlValue", session != null && session.getUrl() != null ? session.getUrl() : "");

        List<QueueItemView> queueItems = new ArrayList<>();
        if (queue != null && queue.items != null) {
            for (int i = 0; i < queue.items.size(); i++) {
                PlaylistManager.PlaylistItem item = queue.items.get(i);
                QueueItemView view = new QueueItemView(i);
                view.title = resolveItemTitle(item);
                view.artist = resolveItemArtist(item);
                view.status = "";
                view.thumb = resolveItemIcon(item, mediaManager);
                if (view.thumb == null || view.thumb.isEmpty()) {
                    view.thumb = DEFAULT_IMAGE;
                }
                view.playLabel = "Play";
                view.upLabel = "Up";
                view.downLabel = "Dn";
                view.removeLabel = "Del";
                queueItems.add(view);
            }
        }
        vars.put("queueItems", queueItems);

        List<LibraryItemView> libraryItems = new ArrayList<>();
        var library = MediaRadioPlugin.getInstance().getMediaLibrary();
        if (library != null) {
            for (dev.jacobwasbeast.manager.MediaLibrary.SavedSong song : library.getSongsForPlayer(scopeId)) {
                LibraryItemView view = new LibraryItemView();
                view.url = song.url;
                view.title = resolveSongTitle(song);
                view.artist = resolveSongArtist(song);
                view.status = song.status != null ? song.status : "";
                view.thumb = resolveSongIcon(song, mediaManager, library);
                if (view.thumb == null || view.thumb.isEmpty()) {
                    view.thumb = DEFAULT_IMAGE;
                }
                view.playLabel = "Play";
                view.queueLabelShort = "Queue";
                view.addLabel = "Add";
                view.editLabel = "Edit";
                view.removeLabel = "Del";
                libraryItems.add(view);
            }
        }
        vars.put("libraryItems", libraryItems);

        List<PlaylistView> playlists = new ArrayList<>();
        if (playlistManager != null) {
            for (PlaylistManager.Playlist playlist : playlistManager.getPlaylists(playlistScopeId)) {
                PlaylistView view = new PlaylistView();
                view.playlistId = playlist.id;
                view.name = playlist.name != null ? playlist.name : "Playlist";
                int count = playlist.items != null ? playlist.items.size() : 0;
                view.count = count + " songs";
                view.selectLabel = "Open";
                String icon = playlist.iconAssetPath;
                if ((icon == null || icon.isEmpty()) && playlist.items != null && !playlist.items.isEmpty()) {
                    icon = resolveItemIcon(playlist.items.get(0), mediaManager);
                }
                view.icon = (icon == null || icon.isEmpty()) ? DEFAULT_IMAGE : icon;
                playlists.add(view);
            }
        }
        vars.put("playlists", playlists);

        List<PlaylistItemView> playlistItems = new ArrayList<>();
        if (selectedPlaylist != null && selectedPlaylist.items != null) {
            for (int i = 0; i < selectedPlaylist.items.size(); i++) {
                PlaylistManager.PlaylistItem item = selectedPlaylist.items.get(i);
                PlaylistItemView view = new PlaylistItemView(i);
                view.title = resolveItemTitle(item);
                view.artist = resolveItemArtist(item);
                view.thumb = resolveItemIcon(item, mediaManager);
                if (view.thumb == null || view.thumb.isEmpty()) {
                    view.thumb = DEFAULT_IMAGE;
                }
                view.playLabel = "Play";
                view.upLabel = "Up";
                view.downLabel = "Dn";
                view.removeLabel = "Del";
                playlistItems.add(view);
            }
        }
        vars.put("playlistItems", playlistItems);

        String editingUrl = EDITING_SONG_URL.get(playerRef.getUuid());
        boolean showSongEdit = editingUrl != null && !editingUrl.isEmpty();
        vars.put("showSongEdit", showSongEdit);
        if (showSongEdit && library != null) {
            dev.jacobwasbeast.manager.MediaLibrary.SavedSong song = findSongByUrl(library, scopeId, editingUrl);
            vars.put("songEditTitle", song != null ? resolveSongTitle(song) : "");
            vars.put("songEditArtist", song != null ? resolveSongArtist(song) : "");
            vars.put("songEditDescription", song != null && song.customDescription != null ? song.customDescription : "");
            vars.put("songEditIcon", song != null && song.customIcon != null ? song.customIcon : "");
        } else {
            vars.put("songEditTitle", "");
            vars.put("songEditArtist", "");
            vars.put("songEditDescription", "");
            vars.put("songEditIcon", "");
        }

        if (selectedPlaylist != null) {
            vars.put("playlistName", selectedPlaylist.name != null ? selectedPlaylist.name : "");
            vars.put("playlistDescription", selectedPlaylist.description != null ? selectedPlaylist.description : "");
            vars.put("playlistIcon", selectedPlaylist.iconAssetPath != null ? selectedPlaylist.iconAssetPath : "");
        } else {
            vars.put("playlistName", "");
            vars.put("playlistDescription", "");
            vars.put("playlistIcon", "");
        }

        return new UiModel(vars, queueItems, libraryItems, playlists, playlistItems);
    }

    private void startTimeUpdater() {
        UUID playerId = playerRef.getUuid();
        TIME_UPDATERS.compute(playerId, (id, existing) -> {
            if (existing != null && !existing.isDone() && !existing.isCancelled()) {
                return existing;
            }
            return HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
                if (!updateTimeDisplay()) {
                    stopTimeUpdater(playerId);
                }
            }, TIME_UPDATE_PERIOD_MS, TIME_UPDATE_PERIOD_MS, TimeUnit.MILLISECONDS);
        });
    }

    private static void stopTimeUpdater(UUID playerId) {
        ScheduledFuture<?> future = TIME_UPDATERS.remove(playerId);
        if (future != null) {
            future.cancel(false);
        }
        SCRUB_STATES.remove(playerId);
        VOLUME_EDITING.remove(playerId);
    }

    private boolean updateTimeDisplay() {
        var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
        ActivePage active = ACTIVE_PAGES.get(playerRef.getUuid());
        if (active == null || active.controller != this) {
            return false;
        }
        HyUIPage page = active.page;
        if (!isPageOpen(page)) {
            ACTIVE_PAGES.remove(playerRef.getUuid());
            return false;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();

        ScrubState scrubState = SCRUB_STATES.get(playerRef.getUuid());
        if (scrubState != null && scrubState.isScrubbing) {
            long now = System.currentTimeMillis();
            if (now - scrubState.lastScrubAtMs < 1200) {
                return true;
            }
            scrubState.isScrubbing = false;
        }

        PlaybackSession session = resolveSession();
        PlaylistManager playlistManager = MediaRadioPlugin.getInstance().getPlaylistManager();
        PlaylistManager.QueueState queue = playlistManager != null ? playlistManager.getQueue(getScopeId(store)) : null;

        if (session != null && !session.isStopped()) {
            updateLabel(page, "now-title", session.getTitle().isEmpty() ? "Unknown Title" : session.getTitle());
            updateLabel(page, "now-artist", session.getArtist());
            updateLabel(page, "now-time",
                    formatTime(session.getCurrentPositionMs()) + " / " + formatTime(session.getTotalDurationMs()));
            updateSlider(page, "seek-slider", (int) (session.getProgress() * 100));
            updateLabel(page, "loop-label", resolveLoopLabel(queue));
            // shuffle label removed from UI
            updateImage(page, "play-pause-icon",
                    session.isPaused() ? "MediaRadio/Icons/play.png" : "MediaRadio/Icons/pause.png");

            String nowThumb = resolveNowThumbnail(session, queue, mediaManager);
            updateImage(page, "now-thumb", nowThumb);

            if (!Boolean.TRUE.equals(VOLUME_EDITING.get(playerRef.getUuid()))) {
                int volumePercent = Math.round(VolumeUtil.clampPercent(VolumeUtil.eventDbToPercent(session.getVolume())));
                updateTextField(page, "volume-input", String.valueOf(volumePercent));
            }
        } else {
            updateLabel(page, "now-title", "No Media Playing");
            updateLabel(page, "now-artist", "");
            updateLabel(page, "now-time", "0:00 / 0:00");
            updateSlider(page, "seek-slider", 0);
            updateLabel(page, "loop-label", resolveLoopLabel(queue));
            // shuffle label removed from UI
            updateImage(page, "now-thumb", DEFAULT_IMAGE);
            updateImage(page, "play-pause-icon", "MediaRadio/Icons/play.png");

            int volumePercent = VOLUME_DEFAULT_PERCENT;
            var playbackManager = MediaRadioPlugin.getInstance().getPlaybackManager();
            if (blockPos != null && playbackManager != null) {
                volumePercent = Math.round(
                        VolumeUtil.clampPercent(
                                VolumeUtil.eventDbToPercent(playbackManager.getBlockVolume(blockPos, store))));
            } else if (playbackManager != null) {
                volumePercent = Math.round(
                        VolumeUtil.clampPercent(
                                VolumeUtil.eventDbToPercent(playbackManager.getPlayerVolume(playerRef.getUuid()))));
            }
            if (!Boolean.TRUE.equals(VOLUME_EDITING.get(playerRef.getUuid()))) {
                updateTextField(page, "volume-input", String.valueOf(volumePercent));
            }
        }

        page.updatePage(false);
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
        state.lastScrubAtMs = System.currentTimeMillis();

        long previewMs = (long) (session.getTotalDurationMs() * (seekPercent / 100.0f));
        ActivePage active = ACTIVE_PAGES.get(playerRef.getUuid());
        if (active != null) {
            updateLabel(active.page, "now-time",
                    formatTime(previewMs) + " / " + formatTime(session.getTotalDurationMs()));
            updateSlider(active.page, "seek-slider", (int) seekPercent);
            active.page.updatePage(false);
        }

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

    private void updateVolumeField(int value) {
        ActivePage active = ACTIVE_PAGES.get(playerRef.getUuid());
        if (active == null) {
            return;
        }
        updateTextField(active.page, "volume-input", String.valueOf(value));
        active.page.updatePage(false);
    }

    private boolean isPageOpen(HyUIPage page) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        return player != null && player.getPageManager().getCustomPage() == page;
    }

    private void closePage(Store<EntityStore> store) {
        stopTimeUpdater(playerRef.getUuid());
        ActivePage active = ACTIVE_PAGES.remove(playerRef.getUuid());
        if (active != null) {
            active.page.close();
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || store == null) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    private static void updateLabel(HyUIPage page, String id, String text) {
        page.getById(id, LabelBuilder.class).ifPresent(label -> label.withText(text));
    }

    private static void updateButton(HyUIPage page, String id, String text) {
        page.getById(id, ButtonBuilder.class).ifPresent(button -> button.withText(text));
    }

    private static void updateSlider(HyUIPage page, String id, int value) {
        page.getById(id, SliderBuilder.class).ifPresent(slider -> slider.withValue(value));
    }

    private static void updateTextField(HyUIPage page, String id, String value) {
        page.getById(id, TextFieldBuilder.class).ifPresent(field -> field.withValue(value));
    }

    private static void updateImage(HyUIPage page, String id, String asset) {
        page.getById(id, ImageBuilder.class).ifPresent(image -> image.withImage(asset));
    }

    private PlaybackSession resolveSession() {
        var manager = MediaRadioPlugin.getInstance().getPlaybackManager();
        return blockPos != null ? manager.getSession(blockPos) : manager.getSession(playerRef.getUuid());
    }

    private String resolveLoopLabel(PlaylistManager.QueueState queue) {
        PlaylistManager.LoopMode mode = queue != null ? queue.loopMode : PlaylistManager.LoopMode.OFF;
        if (mode == PlaylistManager.LoopMode.ONE) {
            return "Loop: One";
        }
        if (mode == PlaylistManager.LoopMode.ALL) {
            return "Loop: All";
        }
        return "Loop: Off";
    }

    private String resolveShuffleLabel(PlaylistManager.QueueState queue) {
        boolean enabled = queue != null && queue.shuffle;
        return enabled ? "Shuffle: On" : "Shuffle: Off";
    }

    private String getScopeId(Store<EntityStore> store) {
        if (blockPos == null) {
            return PlaybackScopeUtil.playerScopeId(playerRef.getUuid());
        }
        if (store != null && store.getExternalData() != null) {
            World world = store.getExternalData().getWorld();
            return PlaybackScopeUtil.boomboxScopeId(world, blockPos);
        }
        return PlaybackScopeUtil.boomboxScopeId("world", blockPos);
    }

    private String resolveSongTitle(dev.jacobwasbeast.manager.MediaLibrary.SavedSong song) {
        if (song == null) {
            return "Unknown";
        }
        if (song.customTitle != null && !song.customTitle.isEmpty()) {
            return song.customTitle;
        }
        return song.title != null && !song.title.isEmpty() ? song.title : "Unknown";
    }

    private String resolveSongArtist(dev.jacobwasbeast.manager.MediaLibrary.SavedSong song) {
        if (song == null) {
            return "";
        }
        if (song.customArtist != null && !song.customArtist.isEmpty()) {
            return song.customArtist;
        }
        return song.artist != null ? song.artist : "";
    }

    private String resolveSongIcon(dev.jacobwasbeast.manager.MediaLibrary.SavedSong song,
            dev.jacobwasbeast.manager.MediaManager mediaManager,
            dev.jacobwasbeast.manager.MediaLibrary library) {
        if (song == null) {
            return "";
        }
        if (song.customIcon != null && !song.customIcon.isEmpty()) {
            return song.customIcon;
        }
        String assetPath = song.thumbnailAssetPath;
        if ((assetPath == null || assetPath.isEmpty()) && mediaManager != null && song.url != null) {
            String trackId = song.trackId != null ? song.trackId : mediaManager.getTrackIdForUrl(song.url);
            if (mediaManager.hasThumbnail(trackId)) {
                assetPath = mediaManager.getThumbnailAssetPath(trackId);
                song.trackId = trackId;
                song.thumbnailAssetPath = assetPath;
                if (library != null) {
                    library.save();
                }
            }
        }
        return normalizeUiAssetPath(assetPath);
    }

    private String resolveItemTitle(PlaylistManager.PlaylistItem item) {
        if (item == null) {
            return "Unknown";
        }
        if (item.customTitle != null && !item.customTitle.isEmpty()) {
            return item.customTitle;
        }
        return item.title != null && !item.title.isEmpty() ? item.title : "Unknown";
    }

    private String resolveItemArtist(PlaylistManager.PlaylistItem item) {
        if (item == null) {
            return "";
        }
        if (item.customArtist != null && !item.customArtist.isEmpty()) {
            return item.customArtist;
        }
        return item.artist != null ? item.artist : "";
    }

    private String resolveItemIcon(PlaylistManager.PlaylistItem item,
            dev.jacobwasbeast.manager.MediaManager mediaManager) {
        if (item == null) {
            return "";
        }
        if (item.customIcon != null && !item.customIcon.isEmpty()) {
            return item.customIcon;
        }
        String assetPath = item.thumbnailAssetPath;
        if ((assetPath == null || assetPath.isEmpty()) && mediaManager != null && item.url != null) {
            String trackId = item.trackId != null ? item.trackId : mediaManager.getTrackIdForUrl(item.url);
            if (trackId != null && mediaManager.hasThumbnail(trackId)) {
                assetPath = mediaManager.getThumbnailAssetPath(trackId);
                item.trackId = trackId;
                item.thumbnailAssetPath = assetPath;
            }
        }
        return normalizeUiAssetPath(assetPath);
    }

    private String resolveNowThumbnail(PlaybackSession session, PlaylistManager.QueueState queue,
            dev.jacobwasbeast.manager.MediaManager mediaManager) {
        if (session == null || session.isStopped()) {
            return DEFAULT_IMAGE;
        }
        if (queue != null && queue.items != null && queue.index >= 0 && queue.index < queue.items.size()) {
            String icon = resolveItemIcon(queue.items.get(queue.index), mediaManager);
            if (icon != null && !icon.isEmpty()) {
                return icon;
            }
        }
        String url = session.getUrl();
        if (mediaManager != null && url != null && !url.isEmpty()) {
            String trackId = mediaManager.getTrackIdForUrl(url);
            if (trackId != null && mediaManager.hasThumbnail(trackId)) {
                return normalizeUiAssetPath(mediaManager.getThumbnailAssetPath(trackId));
            }
            if (trackId != null) {
                mediaManager.ensureThumbnailAsync(url, trackId);
            }
        }
        String raw = session.getThumbnailUrl();
        if (raw != null && !raw.isEmpty() && !raw.startsWith("http")) {
            return normalizeUiAssetPath(raw);
        }
        return DEFAULT_IMAGE;
    }

    private static String normalizeUiAssetPath(String assetPath) {
        if (assetPath == null) {
            return "";
        }
        if (assetPath.startsWith("UI/Custom/")) {
            return assetPath.substring("UI/Custom/".length());
        }
        if (assetPath.startsWith("Common/UI/Custom/")) {
            return assetPath.substring("Common/UI/Custom/".length());
        }
        return assetPath;
    }

    private dev.jacobwasbeast.manager.MediaLibrary.SavedSong findSongByUrl(
            dev.jacobwasbeast.manager.MediaLibrary library, String scopeId, String url) {
        if (library == null || scopeId == null || url == null) {
            return null;
        }
        for (dev.jacobwasbeast.manager.MediaLibrary.SavedSong song : library.getSongsForPlayer(scopeId)) {
            if (song != null && url.equals(song.url)) {
                return song;
            }
        }
        return null;
    }

    private String resolveUrl(ActionData data) {
        if (data == null) {
            return null;
        }
        String url = data.url;
        if (url == null || url.isEmpty()) {
            url = data.directUrl;
        }
        return (url != null && !url.isEmpty()) ? url : null;
    }

    private PlaylistManager.PlaylistItem resolvePlaylistItem(String scopeId, String url) {
        var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
        String normalized = mediaManager != null ? mediaManager.normalizeUrl(url) : url;
        var library = MediaRadioPlugin.getInstance().getMediaLibrary();
        if (library != null) {
            dev.jacobwasbeast.manager.MediaLibrary.SavedSong song = findSongByUrl(library, scopeId, normalized);
            if (song != null) {
                return PlaylistManager.fromSong(song);
            }
        }
        PlaylistManager.PlaylistItem item = new PlaylistManager.PlaylistItem();
        item.url = normalized;
        item.title = normalized;
        return item;
    }

    private void appendQueueItem(String scopeId, PlaylistManager.PlaylistItem item) {
        if (item == null) {
            return;
        }
        PlaylistManager playlistManager = MediaRadioPlugin.getInstance().getPlaylistManager();
        if (playlistManager == null) {
            return;
        }
        PlaylistManager.QueueState queue = playlistManager.getQueue(scopeId);
        List<PlaylistManager.PlaylistItem> items = new ArrayList<>();
        if (queue != null && queue.items != null) {
            items.addAll(queue.items);
        }
        items.add(item);
        String sourceScopeId = queue != null ? queue.sourceScopeId : null;
        String sourcePlaylistId = queue != null ? queue.sourcePlaylistId : null;
        int index = queue != null ? queue.index : 0;
        playlistManager.setQueue(scopeId, items, index, sourceScopeId, sourcePlaylistId);
    }

    private void removeQueueIndex(String scopeId, int index) {
        PlaylistManager playlistManager = MediaRadioPlugin.getInstance().getPlaylistManager();
        if (playlistManager == null) {
            return;
        }
        PlaylistManager.QueueState queue = playlistManager.getQueue(scopeId);
        if (queue == null || queue.items == null || index < 0 || index >= queue.items.size()) {
            return;
        }
        List<PlaylistManager.PlaylistItem> items = new ArrayList<>(queue.items);
        items.remove(index);
        int newIndex = Math.max(0, Math.min(queue.index, items.size() - 1));
        playlistManager.setQueue(scopeId, items, newIndex, queue.sourceScopeId, queue.sourcePlaylistId);
    }

    private void moveQueueIndex(String scopeId, int from, int to) {
        PlaylistManager playlistManager = MediaRadioPlugin.getInstance().getPlaylistManager();
        if (playlistManager == null) {
            return;
        }
        PlaylistManager.QueueState queue = playlistManager.getQueue(scopeId);
        if (queue == null || queue.items == null) {
            return;
        }
        int size = queue.items.size();
        if (from < 0 || from >= size || to < 0 || to >= size || from == to) {
            return;
        }
        List<PlaylistManager.PlaylistItem> items = new ArrayList<>(queue.items);
        PlaylistManager.PlaylistItem item = items.remove(from);
        items.add(to, item);
        playlistManager.setQueue(scopeId, items, queue.index, queue.sourceScopeId, queue.sourcePlaylistId);
    }

    private void playQueueIndex(String scopeId, int index, Store<EntityStore> store) {
        PlaylistManager playlistManager = MediaRadioPlugin.getInstance().getPlaylistManager();
        if (playlistManager == null) {
            return;
        }
        PlaylistManager.QueueState queue = playlistManager.getQueue(scopeId);
        if (queue == null || queue.items == null || queue.items.isEmpty()) {
            return;
        }
        int clamped = Math.max(0, Math.min(index, queue.items.size() - 1));
        playlistManager.setQueueIndex(scopeId, clamped);
        PlaylistManager.PlaylistItem item = queue.items.get(clamped);
        if (item != null && item.url != null) {
            playUrlForScope(scopeId, item.url, store);
        }
    }

    private void playUrlForScope(String scopeId, String url, Store<EntityStore> store) {
        if (url == null || url.isEmpty()) {
            return;
        }
        var mediaManager = MediaRadioPlugin.getInstance().getMediaManager();
        if (mediaManager == null) {
            return;
        }
        String normalized = mediaManager.normalizeUrl(url);
        var library = MediaRadioPlugin.getInstance().getMediaLibrary();
        if (library != null) {
            library.upsertSongStatus(scopeId, normalized, "Downloading...", null, null, null, 0, null, null);
        }

        mediaManager.requestMedia(normalized).thenAccept(mediaInfo -> {
            store.getExternalData().getWorld().execute(() -> {
                if (library != null) {
                    library.upsertSongStatus(
                            scopeId,
                            mediaInfo.url,
                            "Preparing...",
                            mediaInfo.title,
                            mediaInfo.artist,
                            mediaInfo.thumbnailUrl,
                            mediaInfo.duration,
                            mediaInfo.trackId,
                            mediaInfo.thumbnailAssetPath);
                }
                if (blockPos != null) {
                    mediaManager.playSoundAtBlock(mediaInfo, blockPos,
                            MediaRadioPlugin.getInstance().getConfig().getChunkDurationMs(), store)
                            .thenRun(() -> store.getExternalData().getWorld().execute(() -> {
                                if (library != null) {
                                    library.upsertSongStatus(
                                            scopeId,
                                            mediaInfo.url,
                                            "Playing",
                                            mediaInfo.title,
                                            mediaInfo.artist,
                                            mediaInfo.thumbnailUrl,
                                            mediaInfo.duration,
                                            mediaInfo.trackId,
                                            mediaInfo.thumbnailAssetPath);
                                }
                                refreshUiAfterAction(store);
                            }))
                            .exceptionally(ex -> {
                                store.getExternalData().getWorld().execute(() -> {
                                    if (library != null) {
                                        library.upsertSongStatus(
                                                scopeId,
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
                    mediaManager.playSound(mediaInfo, playerRef, store)
                            .thenRun(() -> store.getExternalData().getWorld().execute(() -> {
                                if (library != null) {
                                    library.upsertSongStatus(
                                            scopeId,
                                            mediaInfo.url,
                                            "Playing",
                                            mediaInfo.title,
                                            mediaInfo.artist,
                                            mediaInfo.thumbnailUrl,
                                            mediaInfo.duration,
                                            mediaInfo.trackId,
                                            mediaInfo.thumbnailAssetPath);
                                }
                                refreshUiAfterAction(store);
                            }))
                            .exceptionally(ex -> {
                                store.getExternalData().getWorld().execute(() -> {
                                    if (library != null) {
                                        library.upsertSongStatus(
                                                scopeId,
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
        }).exceptionally(ex -> {
            store.getExternalData().getWorld().execute(() -> {
                String reason = extractFailureReason(ex);
                sendChatAndClose(store,
                        reason.isEmpty()
                                ? "Failed to load media."
                                : "Failed to load media: " + reason);
                if (library != null) {
                    library.upsertSongStatus(scopeId, normalized, "Failed", null, null, null, 0, null, null);
                }
            });
            return null;
        });
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

    private void sendChatAndClose(Store<EntityStore> store, String message) {
        if (message != null && !message.isEmpty()) {
            playerRef.sendMessage(Message.raw(message));
        }
        closePage(store);
    }

    private String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private static final class ActivePage {
        private final HyUIPage page;
        private final RadioConfigPage controller;

        private ActivePage(HyUIPage page, RadioConfigPage controller) {
            this.page = page;
            this.controller = controller;
        }
    }

    private static final class ScrubState {
        private boolean isScrubbing;
        private boolean wasPlaying;
        private double lastPercent;
        private ScheduledFuture<?> finalizeTask;
        private long lastScrubAtMs;
    }

    private static final class UiModel {
        private final Map<String, Object> templateVars;
        private final List<QueueItemView> queueItems;
        private final List<LibraryItemView> libraryItems;
        private final List<PlaylistView> playlists;
        private final List<PlaylistItemView> playlistItems;

        private UiModel(Map<String, Object> templateVars, List<QueueItemView> queueItems,
                List<LibraryItemView> libraryItems, List<PlaylistView> playlists,
                List<PlaylistItemView> playlistItems) {
            this.templateVars = templateVars;
            this.queueItems = queueItems;
            this.libraryItems = libraryItems;
            this.playlists = playlists;
            this.playlistItems = playlistItems;
        }
    }

    private static final class QueueItemView {
        private final int index;
        private String thumb;
        private String title;
        private String artist;
        private String status;
        private String playLabel;
        private String upLabel;
        private String downLabel;
        private String removeLabel;
        private final String playId;
        private final String upId;
        private final String downId;
        private final String removeId;

        private QueueItemView(int index) {
            this.index = index;
            this.playId = "queue-play-" + index;
            this.upId = "queue-up-" + index;
            this.downId = "queue-down-" + index;
            this.removeId = "queue-remove-" + index;
        }
    }

    private static final class LibraryItemView {
        private String url;
        private String thumb;
        private String title;
        private String artist;
        private String status;
        private String playLabel;
        private String queueLabelShort;
        private String addLabel;
        private String editLabel;
        private String removeLabel;
        private final String playId;
        private final String queueId;
        private final String addId;
        private final String editId;
        private final String removeId;

        private LibraryItemView() {
            String id = UUID.randomUUID().toString().replace("-", "");
            this.playId = "library-play-" + id;
            this.queueId = "library-queue-" + id;
            this.addId = "library-add-" + id;
            this.editId = "library-edit-" + id;
            this.removeId = "library-remove-" + id;
        }
    }

    private static final class PlaylistView {
        private String playlistId;
        private String name;
        private String count;
        private String icon;
        private String selectLabel;
        private final String selectId;

        private PlaylistView() {
            String id = UUID.randomUUID().toString().replace("-", "");
            this.selectId = "playlist-select-" + id;
        }
    }

    private static final class PlaylistItemView {
        private final int index;
        private String thumb;
        private String title;
        private String artist;
        private String playLabel;
        private String upLabel;
        private String downLabel;
        private String removeLabel;
        private final String playId;
        private final String upId;
        private final String downId;
        private final String removeId;

        private PlaylistItemView(int index) {
            this.index = index;
            this.playId = "playlist-item-play-" + index;
            this.upId = "playlist-item-up-" + index;
            this.downId = "playlist-item-down-" + index;
            this.removeId = "playlist-item-remove-" + index;
        }
    }

    private static final class ActionData {
        private String action;
        private String url;
        private String directUrl;
        private String playlistId;
        private Integer index;
        private String name;
        private String description;
        private String icon;
        private String title;
        private String artist;
        private Float seekValue;
        private String volumeText;

        private static ActionData forAction(String action) {
            ActionData data = new ActionData();
            data.action = action;
            return data;
        }
    }

}
