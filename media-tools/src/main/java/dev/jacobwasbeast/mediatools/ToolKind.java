package dev.jacobwasbeast.mediatools;

public enum ToolKind {
    YT_DLP("yt-dlp"),
    FFMPEG("ffmpeg");

    private final String displayName;

    ToolKind(String displayName) {
        this.displayName = displayName;
    }

    public String[] filenames(OsFamily osFamily, Arch arch) {
        return switch (osFamily) {
            case WINDOWS -> windowsFilenames(arch);
            case MAC -> macFilenames(arch);
            case LINUX -> linuxFilenames(arch);
        };
    }

    public String preferredFilename(OsFamily osFamily, Arch arch) {
        String[] candidates = filenames(osFamily, arch);
        return candidates.length > 0 ? candidates[0] : displayName;
    }

    public String displayName() {
        return displayName;
    }

    private String[] windowsFilenames(Arch arch) {
        if (this == YT_DLP) {
            return switch (arch) {
                case ARM64 -> new String[] { "yt-dlp_arm64.exe", "yt-dlp.exe" };
                default -> new String[] { "yt-dlp.exe" };
            };
        }
        return switch (arch) {
            case ARM64 -> new String[] { "ffmpeg_arm64.exe", "ffmpeg.exe" };
            default -> new String[] { "ffmpeg.exe" };
        };
    }

    private String[] macFilenames(Arch arch) {
        if (this == YT_DLP) {
            return new String[] { "yt-dlp_macos", "yt-dlp" };
        }
        if (arch == Arch.ARM64) {
            return new String[] { "ffmpeg_macos_arm64", "ffmpeg_macos", "ffmpeg" };
        }
        return new String[] { "ffmpeg_macos", "ffmpeg" };
    }

    private String[] linuxFilenames(Arch arch) {
        if (this == YT_DLP) {
            return switch (arch) {
                case ARM64 -> new String[] { "yt-dlp_linux_aarch64", "yt-dlp_linux", "yt-dlp" };
                case ARMV7L, ARM, X86 -> new String[] { };
                default -> new String[] { "yt-dlp_linux", "yt-dlp" };
            };
        }

        return switch (arch) {
            case ARM64 -> new String[] { "ffmpeg_linux_aarch64", "ffmpeg_linux", "ffmpeg" };
            case ARMV7L, ARM, X86 -> new String[] { };
            default -> new String[] { "ffmpeg_linux", "ffmpeg" };
        };
    }
}
