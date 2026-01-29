package dev.jacobwasbeast.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MediaRadioConfig {
    private int chunkDurationMs = 750;
    private List<String> ytDlpArgs = new ArrayList<>();
    private List<String> ytDlpMetadataArgs = new ArrayList<>();

    public int getChunkDurationMs() {
        return chunkDurationMs;
    }

    public List<String> getYtDlpArgs() {
        return ytDlpArgs != null ? ytDlpArgs : List.of();
    }

    public List<String> getYtDlpMetadataArgs() {
        return ytDlpMetadataArgs != null ? ytDlpMetadataArgs : List.of();
    }

    public static MediaRadioConfig load(Path baseDir) {
        Path configPath = baseDir.resolve("media_radio_config.json").toAbsolutePath();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        if (!Files.exists(configPath)) {
            MediaRadioConfig config = new MediaRadioConfig();
            config.save(configPath, gson);
            return config;
        }
        try (Reader reader = Files.newBufferedReader(configPath)) {
            MediaRadioConfig config = gson.fromJson(reader, MediaRadioConfig.class);
            if (config == null) {
                config = new MediaRadioConfig();
                config.save(configPath, gson);
            } else if (config.ytDlpArgs == null) {
                config.ytDlpArgs = new ArrayList<>();
            }
            if (config.ytDlpMetadataArgs == null) {
                config.ytDlpMetadataArgs = new ArrayList<>();
            }
            return config;
        } catch (IOException e) {
            MediaRadioConfig config = new MediaRadioConfig();
            config.save(configPath, gson);
            return config;
        }
    }

    private void save(Path configPath, Gson gson) {
        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                gson.toJson(this, writer);
            }
        } catch (IOException ignored) {
        }
    }
}
