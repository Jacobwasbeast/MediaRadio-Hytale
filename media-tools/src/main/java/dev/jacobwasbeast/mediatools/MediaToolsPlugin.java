package dev.jacobwasbeast.mediatools;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import javax.annotation.Nonnull;

public class MediaToolsPlugin extends JavaPlugin {
    public MediaToolsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getLogger().at(java.util.logging.Level.INFO).log("media-tools is ready (setup).");
    }

    @Override
    protected void start() {
        getLogger().at(java.util.logging.Level.INFO).log("media-tools is ready.");
    }
}
