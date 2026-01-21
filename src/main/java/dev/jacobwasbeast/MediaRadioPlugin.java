package dev.jacobwasbeast;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import dev.jacobwasbeast.manager.MediaManager;
import dev.jacobwasbeast.ui.RadioConfigSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;

public class MediaRadioPlugin extends JavaPlugin {
    private static MediaRadioPlugin instance;
    private MediaManager mediaManager;
    private dev.jacobwasbeast.manager.MediaLibrary mediaLibrary;
    private dev.jacobwasbeast.manager.MediaPlaybackManager playbackManager;

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

        this.getLogger().at(Level.INFO).log("MediaRadioPlugin codecs registered.");
    }

    @Override
    protected void start() {
        this.getLogger().at(Level.INFO).log("MediaRadioPlugin starting...");

        // Initialize MediaManager
        this.mediaManager = new MediaManager(this);
        this.mediaManager.init();
        this.getLogger().at(Level.INFO).log("MediaManager initialized.");

        // Initialize MediaLibrary
        this.mediaLibrary = new dev.jacobwasbeast.manager.MediaLibrary(this);
        this.getLogger().at(Level.INFO).log("MediaLibrary initialized.");

        // Initialize MediaPlaybackManager
        this.playbackManager = new dev.jacobwasbeast.manager.MediaPlaybackManager(this);
        this.getLogger().at(Level.INFO).log("PlaybackManager initialized.");

        if (this.mediaManager != null && this.mediaLibrary != null) {
            this.mediaManager.warmThumbnails(this.mediaLibrary);
        }

        // Register Components
        dev.jacobwasbeast.component.RadioComponent.COMPONENT_TYPE = com.hypixel.hytale.server.core.modules.entity.EntityModule
                .get().getEntityStoreRegistry().registerComponent(dev.jacobwasbeast.component.RadioComponent.class,
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
                        playbackManager.stopForPlayer(event.getPlayerRef().getUuid());
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
                    com.hypixel.hytale.server.core.entity.entities.Player player =
                            (com.hypixel.hytale.server.core.entity.entities.Player) event.getEntity();
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

    public MediaManager getMediaManager() {
        return mediaManager;
    }

    public dev.jacobwasbeast.manager.MediaLibrary getMediaLibrary() {
        return mediaLibrary;
    }

    public dev.jacobwasbeast.manager.MediaPlaybackManager getPlaybackManager() {
        return playbackManager;
    }
}
