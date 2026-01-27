package dev.jacobwasbeast.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.jacobwasbeast.MediaRadioPlugin;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class MediaRadioConfig {
    private int chunkDurationMs = 750;

    public int getChunkDurationMs() {
        return chunkDurationMs;
    }
}
