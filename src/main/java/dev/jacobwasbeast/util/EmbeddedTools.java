package dev.jacobwasbeast.util;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;

public final class EmbeddedTools {
    private static final String MEDIA_TOOLS_CLASS = "dev.jacobwasbeast.mediatools.MediaTools";

    private final Object delegate;
    private final boolean present;

    private EmbeddedTools(Object delegate, boolean present) {
        this.delegate = delegate;
        this.present = present;
    }

    public static EmbeddedTools create(Path toolsRoot) {
        try {
            Class<?> cls = Class.forName(MEDIA_TOOLS_CLASS);
            Object instance = cls.getConstructor(Path.class).newInstance(toolsRoot);
            return new EmbeddedTools(instance, true);
        } catch (Throwable ignored) {
            return new EmbeddedTools(null, false);
        }
    }

    public boolean isPresent() {
        return present;
    }

    public boolean isSupportedPlatform() {
        return callBool("isSupportedPlatform").orElse(false);
    }

    public String getStatusSummary() {
        return callString("getStatusSummary").orElse("media-tools plugin not found or disabled.");
    }

    public String requireYtDlpCommand() {
        return callString("requireYtDlpCommand")
                .orElseThrow(() -> new RuntimeException("media-tools plugin not found or disabled."));
    }

    public String requireFfmpegCommand() {
        return callString("requireFfmpegCommand")
                .orElseThrow(() -> new RuntimeException("media-tools plugin not found or disabled."));
    }

    public String resolveYtDlpCommand() {
        return callString("resolveYtDlpCommand").orElse(null);
    }

    public String resolveFfmpegCommand() {
        return callString("resolveFfmpegCommand").orElse(null);
    }

    public boolean isYtDlpAvailable() {
        return callBool("isYtDlpAvailable").orElse(false);
    }

    public boolean isFfmpegAvailable() {
        return callBool("isFfmpegAvailable").orElse(false);
    }

    public Path getExpectedYtDlpPath() {
        return callPath("getExpectedYtDlpPath").orElse(null);
    }

    public Path getExpectedFfmpegPath() {
        return callPath("getExpectedFfmpegPath").orElse(null);
    }

    public Path getFfmpegLocationForYtDlp() {
        return callPath("getFfmpegLocationForYtDlp").orElse(null);
    }

    public void logToolStatus(Object logger) {
        if (!present) {
            if (logger instanceof java.util.logging.Logger jl) {
                jl.log(Level.WARNING, "media-tools plugin not found or disabled.");
            } else {
                logViaHytaleLogger(logger, Level.WARNING, "media-tools plugin not found or disabled.", null);
            }
            return;
        }
        try {
            Method method = delegate.getClass().getMethod("logToolStatus", java.util.logging.Logger.class);
            if (logger instanceof java.util.logging.Logger jl) {
                method.invoke(delegate, jl);
                return;
            }
            if (logger != null) {
                java.util.logging.Logger adapter = java.util.logging.Logger.getLogger("media-tools");
                method.invoke(delegate, adapter);
                logViaHytaleLogger(logger, Level.INFO, "media-tools status logged via JUL adapter.", null);
                return;
            }
        } catch (Exception e) {
            if (logger instanceof java.util.logging.Logger jl) {
                jl.log(Level.WARNING, "Failed to log media-tools status.", e);
            } else {
                logViaHytaleLogger(logger, Level.WARNING, "Failed to log media-tools status.", e);
            }
        }
    }

    private void logViaHytaleLogger(Object logger, Level level, String message, Throwable error) {
        try {
            Class<?> hlClass = Class.forName("com.hypixel.hytale.server.core.log.HytaleLogger");
            if (!hlClass.isInstance(logger)) {
                return;
            }
            Object atLogger = hlClass.getMethod("at", Level.class).invoke(logger, level);
            if (error != null) {
                atLogger.getClass().getMethod("withCause", Throwable.class).invoke(atLogger, error);
            }
            atLogger.getClass().getMethod("log", String.class).invoke(atLogger, message);
        } catch (Exception ignored) {
        }
    }

    private Optional<String> callString(String method) {
        if (!present) {
            return Optional.empty();
        }
        try {
            Method m = delegate.getClass().getMethod(method);
            Object result = m.invoke(delegate);
            return Optional.ofNullable(result != null ? result.toString() : null);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> callBool(String method) {
        if (!present) {
            return Optional.empty();
        }
        try {
            Method m = delegate.getClass().getMethod(method);
            Object result = m.invoke(delegate);
            return result instanceof Boolean ? Optional.of((Boolean) result) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Path> callPath(String method) {
        if (!present) {
            return Optional.empty();
        }
        try {
            Method m = delegate.getClass().getMethod(method);
            Object result = m.invoke(delegate);
            return result instanceof Path ? Optional.of((Path) result) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
