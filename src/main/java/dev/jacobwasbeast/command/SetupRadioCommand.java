package dev.jacobwasbeast.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.MediaRadioPlugin;
import dev.jacobwasbeast.manager.MediaManager;
import java.nio.file.Path;
import javax.annotation.Nonnull;

public class SetupRadioCommand extends AbstractPlayerCommand {
    private final MediaRadioPlugin plugin;

    public SetupRadioCommand(MediaRadioPlugin plugin) {
        super("setup_radio", "mediaRadio.commands.setup.desc");
        this.plugin = plugin;
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        MediaManager manager = plugin.getMediaManager();
        if (manager == null) {
            playerRef.sendMessage(Message.raw("MediaRadio is not ready yet."));
            return;
        }
        boolean ytDlpAvailable = manager.isYtDlpAvailable();
        boolean ffmpegAvailable = manager.isFfmpegAvailable();
        Path ytDlpPath = manager.getExpectedYtDlpPath();
        Path ffmpegPath = manager.getExpectedFfmpegPath();
        if (!ytDlpAvailable && !ffmpegAvailable) {
            playerRef.sendMessage(Message.raw(
                    "MediaRadio embedded tools missing: yt-dlp and ffmpeg not found for this platform. Expected cache paths: "
                            + ytDlpPath + " and " + ffmpegPath));
            return;
        }
        if (!ytDlpAvailable) {
            playerRef.sendMessage(
                    Message.raw("MediaRadio embedded yt-dlp missing for this platform. Expected cache path: " + ytDlpPath));
        }
        if (!ffmpegAvailable) {
            playerRef.sendMessage(
                    Message.raw("MediaRadio embedded ffmpeg missing for this platform. Expected cache path: " + ffmpegPath));
        }
        if (ytDlpAvailable && ffmpegAvailable) {
            playerRef.sendMessage(Message.raw("MediaRadio setup looks good: embedded yt-dlp + ffmpeg detected."));
        }
    }
}
