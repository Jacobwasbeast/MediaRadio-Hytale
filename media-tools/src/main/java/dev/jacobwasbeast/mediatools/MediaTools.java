package dev.jacobwasbeast.mediatools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

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

    public boolean isSupportedPlatform() {
        return ToolKind.YT_DLP.filenames(osFamily, arch).length > 0
                && ToolKind.FFMPEG.filenames(osFamily, arch).length > 0;
    }

    public String getPlatformKey() {
        return osFamily.resourceFolder() + "/" + arch.resourceFolder();
    }

    public String getStatusSummary() {
        if (!isSupportedPlatform()) {
            return "Unsupported platform: " + osFamily.displayName() + " (" + archFolder + ").";
        }
        boolean yt = isYtDlpAvailable();
        boolean ff = isFfmpegAvailable();
        if (yt && ff) {
            return "media-tools ready for " + getPlatformKey() + ".";
        }
        if (!yt && !ff) {
            return "Missing embedded yt-dlp and ffmpeg for " + getPlatformKey() + ".";
        }
        if (!yt) {
            return "Missing embedded yt-dlp for " + getPlatformKey() + ".";
        }
        return "Missing embedded ffmpeg for " + getPlatformKey() + ".";
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

    public void logToolStatus(java.util.logging.Logger logger) {
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
            String resourcePath = RESOURCE_ROOT + "/" + platformFolder + "/" + archFolder + "/" + filename;
            try (InputStream stream = MediaTools.class.getResourceAsStream(resourcePath)) {
                if (stream != null) {
                    Files.createDirectories(target.getParent());
                    Path hashPath = getHashPath(target);
                    String existingHash = readHash(hashPath);
                    Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
                    String resourceHash = copyWithDigest(stream, tmp);
                    if (existingHash == null && Files.exists(target)) {
                        existingHash = computeFileHash(target);
                    }
                    if (existingHash != null && existingHash.equalsIgnoreCase(resourceHash) && Files.exists(target)) {
                        Files.deleteIfExists(tmp);
                        writeHash(hashPath, resourceHash);
                        ensureExecutable(target);
                        return new ToolResolution(target, ToolSource.CACHED);
                    }
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    writeHash(hashPath, resourceHash);
                    ensureExecutable(target);
                    return new ToolResolution(target, ToolSource.EMBEDDED);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to load embedded " + toolKind.displayName(), e);
            }
            if (Files.exists(target)) {
                ensureExecutable(target);
                return new ToolResolution(target, ToolSource.CACHED);
            }
            Path legacyTarget = platformRoot.resolve(filename);
            String legacyResourcePath = RESOURCE_ROOT + "/" + platformFolder + "/" + filename;
            try (InputStream legacyStream = MediaTools.class.getResourceAsStream(legacyResourcePath)) {
                if (legacyStream != null) {
                    Files.createDirectories(legacyTarget.getParent());
                    Path hashPath = getHashPath(legacyTarget);
                    String existingHash = readHash(hashPath);
                    Path tmp = Files.createTempFile(legacyTarget.getParent(), legacyTarget.getFileName().toString(), ".tmp");
                    String resourceHash = copyWithDigest(legacyStream, tmp);
                    if (existingHash == null && Files.exists(legacyTarget)) {
                        existingHash = computeFileHash(legacyTarget);
                    }
                    if (existingHash != null && existingHash.equalsIgnoreCase(resourceHash) && Files.exists(legacyTarget)) {
                        Files.deleteIfExists(tmp);
                        writeHash(hashPath, resourceHash);
                        ensureExecutable(legacyTarget);
                        return new ToolResolution(legacyTarget, ToolSource.CACHED);
                    }
                    Files.move(tmp, legacyTarget, StandardCopyOption.REPLACE_EXISTING);
                    writeHash(hashPath, resourceHash);
                    ensureExecutable(legacyTarget);
                    return new ToolResolution(legacyTarget, ToolSource.EMBEDDED);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to load embedded " + toolKind.displayName(), e);
            }
            if (Files.exists(legacyTarget)) {
                ensureExecutable(legacyTarget);
                return new ToolResolution(legacyTarget, ToolSource.CACHED);
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

    private Path getHashPath(Path target) {
        return target.resolveSibling(target.getFileName().toString() + ".sha256");
    }

    private String readHash(Path hashPath) {
        try {
            if (Files.exists(hashPath)) {
                return Files.readString(hashPath).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private void writeHash(Path hashPath, String hash) {
        try {
            Files.writeString(hashPath, hash + System.lineSeparator());
        } catch (IOException ignored) {
        }
    }

    private String computeFileHash(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            return computeHash(stream);
        }
    }

    private String copyWithDigest(InputStream stream, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 not available", e);
        }
        try (InputStream in = stream; java.io.OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return bytesToHex(digest.digest());
    }

    private String computeHash(InputStream stream) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 not available", e);
        }
        try (InputStream in = stream) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return bytesToHex(digest.digest());
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void logToolVersion(java.util.logging.Logger logger, ToolKind toolKind, String versionArg) {
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
