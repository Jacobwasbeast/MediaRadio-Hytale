Place embedded tool binaries in subfolders by OS and architecture.

Layout:
- tools/windows/<arch>/
- tools/macos/<arch>/
- tools/linux/<arch>/

Architectures:
- x86_64
- arm64

Examples:
- tools/windows/x86_64/yt-dlp.exe
- tools/windows/arm64/yt-dlp_arm64.exe
- tools/macos/arm64/yt-dlp_macos
- tools/linux/x86_64/yt-dlp_linux
- tools/linux/arm64/yt-dlp_linux_aarch64

ffmpeg naming is flexible; the loader will try common patterns like:
- ffmpeg.exe / ffmpeg_arm64.exe (Windows)
- ffmpeg_macos_arm64 / ffmpeg_macos / ffmpeg (macOS)
- ffmpeg_linux_aarch64 / ffmpeg_linux / ffmpeg (Linux)
Legacy fallback: tools/<platform>/<filename> (no arch folder) is still supported.
