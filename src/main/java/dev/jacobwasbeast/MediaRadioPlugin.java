package dev.jacobwasbeast;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.LoadAssetEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.HytaleServer;
import dev.jacobwasbeast.manager.MediaManager;
import dev.jacobwasbeast.ui.RadioConfigSupplier;
import dev.jacobwasbeast.config.MediaRadioConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

public class MediaRadioPlugin extends JavaPlugin {
    private static MediaRadioPlugin instance;
    private MediaManager mediaManager;
    private dev.jacobwasbeast.manager.MediaLibrary mediaLibrary;
    private dev.jacobwasbeast.manager.MediaPlaybackManager playbackManager;
    private dev.jacobwasbeast.config.MediaRadioConfig config;
    private volatile boolean markerCleanupDone = false;
    private ScheduledFuture<?> markerCleanupTask;

    public MediaRadioPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        this.getLogger().at(Level.INFO).log("MediaRadioPlugin setup - registering codecs...");

        // Register Custom UI Page Supplier for OpenCustomUI interaction
        // This MUST happen in setup() before assets are loaded
        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC)
                .register("MediaRadio_Config", RadioConfigSupplier.class, RadioConfigSupplier.CODEC);

        // Initialize Config
        //this.config = dev.jacobwasbeast.config.MediaRadioConfig.load(resolveRuntimeBasePath());
        this.config = new MediaRadioConfig();
        this.getLogger().at(Level.INFO).log("MediaRadioConfig initialized. Chunk duration: %dms",
                config.getChunkDurationMs());

        this.getLogger().at(Level.INFO).log("MediaRadioPlugin codecs registered.");
    }

    @Override
    protected void start() {
        this.getLogger().at(Level.INFO).log("MediaRadioPlugin starting...");

        resetMediaState(resolveRuntimeBasePath());

        // Initialize MediaManager
        this.mediaManager = new MediaManager(this);
        this.mediaManager.init();
        this.getLogger().at(Level.INFO).log("MediaManager initialized.");

        // Initialize MediaLibrary
        this.mediaLibrary = new dev.jacobwasbeast.manager.MediaLibrary(this);
        this.mediaLibrary.resetTransientStatuses();
        this.getLogger().at(Level.INFO).log("MediaLibrary initialized.");

        // Initialize MediaPlaybackManager
        this.playbackManager = new dev.jacobwasbeast.manager.MediaPlaybackManager(this);
        this.getLogger().at(Level.INFO).log("PlaybackManager initialized.");

        this.getEventRegistry().register(LoadAssetEvent.class, event -> {
            Universe.get().getWorlds().values().forEach(world -> world.execute(() -> {
                if (playbackManager != null) {
                    playbackManager.cleanupMarkerNpcsInWorld(world);
                }
            }));
        });

        if (this.mediaManager != null && this.mediaLibrary != null) {
            this.mediaManager.warmThumbnails(this.mediaLibrary);
        }

        // Register Components
        dev.jacobwasbeast.component.RadioComponent.COMPONENT_TYPE = this.getChunkStoreRegistry()
                .registerComponent(dev.jacobwasbeast.component.RadioComponent.class,
                        "media_radio:radio", dev.jacobwasbeast.component.RadioComponent.CODEC);

        // Register Systems
        com.hypixel.hytale.server.core.modules.entity.EntityModule.get().getEntityStoreRegistry()
                .registerSystem(new dev.jacobwasbeast.interaction.RadioInteractionSystem());
        com.hypixel.hytale.server.core.modules.entity.EntityModule.get().getEntityStoreRegistry()
                .registerSystem(new dev.jacobwasbeast.interaction.RadioHeldItemSwitchSystem());

        this.getEventRegistry().registerGlobal(
                com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent.class,
                event -> {
                    if (playbackManager != null) {
                        var store = event.getPlayerRef().getReference().getStore();
                        playbackManager.stopForPlayer(event.getPlayerRef().getUuid(), store);
                    }
                });
        this.getEventRegistry().registerGlobal(
                com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent.class,
                event -> {
                    if (playbackManager == null) {
                        return;
                    }
                    if (!(event.getEntity() instanceof com.hypixel.hytale.server.core.entity.entities.Player)) {
                        return;
                    }
                    com.hypixel.hytale.server.core.entity.entities.Player player = (com.hypixel.hytale.server.core.entity.entities.Player) event
                            .getEntity();
                    var ref = player.getReference();
                    if (ref == null || !ref.isValid()) {
                        return;
                    }
                    var playerRef = ref.getStore()
                            .getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (playerRef == null) {
                        return;
                    }
                    var session = playbackManager.getSession(playerRef.getUuid());
                    if (session == null) {
                        return;
                    }
                    if (!dev.jacobwasbeast.util.RadioItemUtil.isRadioHeld(player)) {
                        playbackManager.pauseForUnheld(playerRef);
                    }
                });

        this.getLogger().at(Level.INFO).log("MediaRadioPlugin started! MediaManager initialized: %s",
                this.mediaManager != null);

        this.getCommandRegistry().registerCommand(new dev.jacobwasbeast.command.SetupRadioCommand(this));

        this.getEventRegistry().registerGlobal(
                com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent.class,
                event -> {
                    if (mediaManager == null) {
                        return;
                    }
                    if (!markerCleanupDone && playbackManager != null) {
                        markerCleanupDone = true;
                        scheduleMarkerCleanupRetries();
                    }
                    boolean ytDlpAvailable = mediaManager.isYtDlpAvailable();
                    boolean ffmpegAvailable = mediaManager.isFfmpegAvailable();
                    if (ytDlpAvailable && ffmpegAvailable) {
                        return;
                    }
                    var playerRef = event.getPlayerRef();
                    if (playerRef != null) {
                        if (!ytDlpAvailable && !ffmpegAvailable) {
                            playerRef.sendMessage(Message.raw(
                                    "MediaRadio requires yt-dlp and ffmpeg. Run /setup_radio for setup details."));
                        } else if (!ytDlpAvailable) {
                            playerRef.sendMessage(Message.raw(
                                    "MediaRadio requires yt-dlp. Run /setup_radio for setup details."));
                        } else {
                            playerRef.sendMessage(Message.raw(
                                    "MediaRadio requires ffmpeg. Run /setup_radio for setup details."));
                        }
                    }
                });
    }

    @Override
    protected void shutdown() {
        this.getLogger().at(Level.INFO).log("MediaRadioPlugin shutting down!");
        if (playbackManager != null) {
            playbackManager.shutdown();
        }
    }

    public static MediaRadioPlugin getInstance() {
        return instance;
    }

    private void scheduleMarkerCleanupRetries() {
        if (markerCleanupTask != null) {
            markerCleanupTask.cancel(false);
        }
        AtomicInteger attempts = new AtomicInteger(0);
        markerCleanupTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            if (playbackManager == null) {
                return;
            }
            Universe.get().getWorlds().values().forEach(world -> world.execute(() -> {
                if (playbackManager != null) {
                    playbackManager.cleanupMarkerNpcsInWorld(world);
                }
            }));
            if (attempts.incrementAndGet() >= 10) {
                ScheduledFuture<?> task = markerCleanupTask;
                markerCleanupTask = null;
                if (task != null) {
                    task.cancel(false);
                }
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public MediaManager getMediaManager() {
        return mediaManager;
    }

    public dev.jacobwasbeast.manager.MediaLibrary getMediaLibrary() {
        return mediaLibrary;
    }

    public dev.jacobwasbeast.manager.MediaPlaybackManager getPlaybackManager() {
        return playbackManager;
    }

    public dev.jacobwasbeast.config.MediaRadioConfig getConfig() {
        return config;
    }

    public static Path resolveRuntimeBasePath() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path cwdName = cwd.getFileName();
        if (cwdName != null && "run".equalsIgnoreCase(cwdName.toString())) {
            return cwd;
        }
        Path runDir = cwd.resolve("run");
        if (Files.isDirectory(runDir)) {
            return runDir.toAbsolutePath();
        }
        return cwd;
    }

    private void resetMediaState(Path baseDir) {
        if (baseDir == null) {
            return;
        }
        Path legacySaved = baseDir.resolve("saved_songs.json");
        Path cwd = Paths.get("").toAbsolutePath();
        Path legacySavedCwd = cwd.resolve("saved_songs.json");
        if (Files.exists(legacySaved) || Files.exists(legacySavedCwd)) {
            deleteDirectory(baseDir.resolve("media_radio_assets"));
            this.getLogger().at(Level.INFO).log("MediaRadioPlugin cleaned legacy assets at %s", baseDir);
        }
    }

    private void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(this::deleteFile);
        } catch (Exception e) {
            this.getLogger().at(Level.WARNING).withCause(e).log("Failed to delete directory %s", dir);
        }
    }

    private void deleteFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            this.getLogger().at(Level.WARNING).withCause(e).log("Failed to delete file %s", path);
        }
    }
}
