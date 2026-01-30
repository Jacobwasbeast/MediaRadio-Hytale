# media-tools
Embedded yt-dlp and ffmpeg binaries + resolver used by MediaRadio.

## What it does
- Ships platform-specific binaries inside the jar under `src/main/resources/tools/`.
- Extracts the correct binary for the current OS/arch into a runtime cache directory.
- Provides a small Java API for MediaRadio to invoke yt-dlp/ffmpeg without relying on PATH.

## Supported platforms
- Windows: x86_64, arm64
- macOS: x86_64, arm64
- Linux: x86_64, arm64

## Resource layout
`media-tools/src/main/resources/tools/<os>/<arch>/`

- `<os>`: `windows`, `macos`, `linux`
- `<arch>`: `x86_64`, `arm64`

Example paths:
- `tools/windows/x86_64/yt-dlp.exe`
- `tools/windows/arm64/yt-dlp_arm64.exe`
- `tools/macos/x86_64/yt-dlp_macos`
- `tools/linux/x86_64/yt-dlp_linux`
- `tools/linux/arm64/yt-dlp_linux_aarch64`
- `tools/windows/x86_64/ffmpeg.exe`
- `tools/macos/arm64/ffmpeg`
- `tools/linux/arm64/ffmpeg`

## Gradle tasks
- `./gradlew :media-tools:downloadEmbeddedTools`
  - Downloads the latest yt-dlp release assets.
  - Downloads ffmpeg for macOS + Linux from Martin Riedl.
  - Downloads ffmpeg for Windows from BtbN.
- `./gradlew :media-tools:verifyBinaries`
  - Checks expected binaries exist and are non-empty.
  - Warns if a binary is missing or not executable.

## Plugin notes
This module includes a tiny plugin entrypoint that logs readiness. The mod is enabled by default in
`media-tools/src/main/resources/manifest.json`.
