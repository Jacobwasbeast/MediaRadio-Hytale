package dev.jacobwasbeast.mediatools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MediaTools {
    private static final String RESOURCE_ROOT = "/tools";

    private final Path toolsRoot;
    private final OsFamily osFamily;
    private final Arch arch;
    private final String platformFolder;
    private final String archFolder;
    private final Map<ToolKind, ToolResolution> resolved = new EnumMap<>(ToolKind.class);

    public MediaTools(Path toolsRoot) {
        this.toolsRoot = toolsRoot;
        this.osFamily = OsFamily.detect();
        this.arch = Arch.detect();
        this.platformFolder = osFamily.resourceFolder();
        this.archFolder = arch.resourceFolder();
    }

    public boolean isYtDlpAvailable() {
        return resolveTool(ToolKind.YT_DLP).isPresent();
    }

    public boolean isFfmpegAvailable() {
        return resolveTool(ToolKind.FFMPEG).isPresent();
    }

    public String requireYtDlpCommand() {
        return requireToolCommand(ToolKind.YT_DLP);
    }

    public String requireFfmpegCommand() {
        return requireToolCommand(ToolKind.FFMPEG);
    }

    public String resolveYtDlpCommand() {
        return resolveTool(ToolKind.YT_DLP).map(ToolResolution::path).map(Path::toString).orElse(null);
    }

    public String resolveFfmpegCommand() {
        return resolveTool(ToolKind.FFMPEG).map(ToolResolution::path).map(Path::toString).orElse(null);
    }

    public Path getExpectedYtDlpPath() {
        return getExpectedPath(ToolKind.YT_DLP);
    }

    public Path getExpectedFfmpegPath() {
        return getExpectedPath(ToolKind.FFMPEG);
    }

    public Path getFfmpegLocationForYtDlp() {
        return resolveTool(ToolKind.FFMPEG).map(ToolResolution::path).map(Path::getParent).orElse(null);
    }

    public void logToolStatus(Logger logger) {
        logToolVersion(logger, ToolKind.YT_DLP, "--version");
        logToolVersion(logger, ToolKind.FFMPEG, "-version");
    }

    public String getEmbeddedMissingMessage(ToolKind toolKind) {
        String[] candidates = toolKind.filenames(osFamily, arch);
        if (candidates.length == 0) {
            return "Unsupported " + toolKind.displayName() + " platform: " + osFamily.displayName()
                    + " (" + archFolder + "). Supported archs: x86_64, arm64.";
        }
        String resourcePath = RESOURCE_ROOT + "/" + platformFolder + "/" + archFolder + "/"
                + candidates[0];
        return "Missing embedded " + toolKind.displayName() + " for " + osFamily.displayName()
                + " (" + archFolder + "). Expected resource: " + resourcePath;
    }

    private Path getExpectedPath(ToolKind toolKind) {
        return toolsRoot.resolve(platformFolder).resolve(archFolder)
                .resolve(toolKind.preferredFilename(osFamily, arch));
    }

    private String requireToolCommand(ToolKind toolKind) {
        Optional<ToolResolution> resolution = resolveTool(toolKind);
        if (resolution.isEmpty()) {
            throw new RuntimeException(getEmbeddedMissingMessage(toolKind));
        }
        return resolution.get().path().toString();
    }

    private Optional<ToolResolution> resolveTool(ToolKind toolKind) {
        ToolResolution cached = resolved.get(toolKind);
        if (cached != null) {
            return Optional.of(cached);
        }

        ToolResolution loaded = loadTool(toolKind);
        if (loaded != null) {
            resolved.put(toolKind, loaded);
            return Optional.of(loaded);
        }
        return Optional.empty();
    }

    private ToolResolution loadTool(ToolKind toolKind) {
        Path platformRoot = toolsRoot.resolve(platformFolder);
        Path platformArchRoot = platformRoot.resolve(archFolder);
        for (String filename : toolKind.filenames(osFamily, arch)) {
            Path target = platformArchRoot.resolve(filename);
            if (Files.exists(target)) {
                ensureExecutable(target);
                return new ToolResolution(target, ToolSource.CACHED);
            }
            String resourcePath = RESOURCE_ROOT + "/" + platformFolder + "/" + archFolder + "/" + filename;
            try (InputStream stream = MediaTools.class.getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    Path legacyTarget = platformRoot.resolve(filename);
                    if (Files.exists(legacyTarget)) {
                        ensureExecutable(legacyTarget);
                        return new ToolResolution(legacyTarget, ToolSource.CACHED);
                    }
                    String legacyResourcePath = RESOURCE_ROOT + "/" + platformFolder + "/" + filename;
                    try (InputStream legacyStream = MediaTools.class.getResourceAsStream(legacyResourcePath)) {
                        if (legacyStream == null) {
                            continue;
                        }
                        Files.createDirectories(legacyTarget.getParent());
                        Files.copy(legacyStream, legacyTarget, StandardCopyOption.REPLACE_EXISTING);
                        ensureExecutable(legacyTarget);
                        return new ToolResolution(legacyTarget, ToolSource.EMBEDDED);
                    }
                }
                Files.createDirectories(target.getParent());
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
                ensureExecutable(target);
                return new ToolResolution(target, ToolSource.EMBEDDED);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load embedded " + toolKind.displayName(), e);
            }
        }
        return null;
    }

    private void ensureExecutable(Path target) {
        if (osFamily == OsFamily.WINDOWS) {
            return;
        }
        try {
            if (!target.toFile().setExecutable(true, true)) {
                Files.setPosixFilePermissions(target, ToolPermissions.DEFAULT_POSIX);
            }
        } catch (Exception ignored) {
        }
    }

    private void logToolVersion(Logger logger, ToolKind toolKind, String versionArg) {
        Optional<ToolResolution> resolution = resolveTool(toolKind);
        if (resolution.isEmpty()) {
            logger.log(Level.WARNING, getEmbeddedMissingMessage(toolKind));
            return;
        }
        ProcessBuilder pb = new ProcessBuilder(resolution.get().path().toString(), versionArg);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            String firstLine = "";
            try (java.util.Scanner scanner = new java.util.Scanner(process.getInputStream())) {
                if (scanner.hasNextLine()) {
                    firstLine = scanner.nextLine();
                }
            }
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.log(Level.INFO, "{0} available: {1}", new Object[] { toolKind.displayName(),
                        firstLine.isEmpty() ? "ok" : firstLine });
            } else {
                logger.log(Level.WARNING, "{0} returned exit {1} ({2})",
                        new Object[] { toolKind.displayName(), exitCode, firstLine.isEmpty() ? "no output" : firstLine });
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to execute embedded " + toolKind.displayName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while checking embedded " + toolKind.displayName(), e);
        }
    }

    private enum ToolSource {
        EMBEDDED,
        CACHED
    }

    private record ToolResolution(Path path, ToolSource source) {
    }
}
