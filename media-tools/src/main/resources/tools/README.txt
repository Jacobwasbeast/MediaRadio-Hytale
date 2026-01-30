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

ffmpeg bundles should include the full archive contents so required files are present.
Expected layout examples:
- tools/windows/x86_64/ffmpeg/bin/ffmpeg.exe
- tools/windows/arm64/ffmpeg/bin/ffmpeg.exe
- tools/macos/arm64/ffmpeg/ffmpeg
- tools/linux/arm64/ffmpeg/ffmpeg
Legacy fallback: tools/<platform>/<filename> (no arch folder) is still supported.
