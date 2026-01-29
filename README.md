# MediaRadio-Hytale
MediaRadio is a Hytale mod that lets players stream web audio through a handheld radio item.
Music only plays while the radio is held (main hand or offhand), and the UI provides a media-player
style interface with play/pause/stop, seek, looping, and a per-player library.

## Features
- Plays web audio from supported URLs (YouTube, etc.) using embedded yt-dlp + ffmpeg.
- Audio is downloaded, converted to OGG, and chunked for streaming.
- Per-player libraries: each player only sees the songs they requested.
- Thumbnails are downloaded and registered as dynamic assets.
- Playback pauses automatically when the radio is not held.

## Notes
- Embedded tools live in the `media-tools` subproject and are loaded from the classpath.
- If you ship separate jars, include the MediaRadio jar and the `media-tools` jar together.
- This mod uses runtime asset packs under `run/media_radio_assets`.
- For now, client-hosted/singleplayer worlds are not supported; the mod needs a dedicated server environment and access to external tools (sandboxing prevents this). Future support is planned.

## Recipe
- Crafted at a Furniture Bench (Furniture Misc).
- Costs 15 Iron Bars, 25 Copper Bars, and 5 Rubble.

## Screenshots
![Radio in main hand](Screenshots/MainHand.png)
![Radio in offhand](Screenshots/OffHand.png)
![Media radio UI](Screenshots/UI.png)

## Setup (Prebuilt)
1. Download the latest MediaRadio mod binaries.
2. Copy the mod binary (and the `media-tools` binary if shipped separately) into your Hytale server `mods` folder.
3. Start the server and join a world.
4. Give yourself the radio item and open the UI to start playback.

## Build (From Source)
1. Add platform binaries to `media-tools/src/main/resources/tools/<os>/<arch>/`:
   - `<os>`: `windows`, `macos`, `linux`
   - `<arch>`: `x86_64`, `arm64`
   - Example: `media-tools/src/main/resources/tools/linux/x86_64/yt-dlp_linux`
2. (Optional) Download the latest binaries automatically:
   - `./gradlew downloadEmbeddedTools`
3. Build the mod:
   - `./gradlew build`
4. Find the built mod binaries in `build/` and `media-tools/build/`.
5. Copy the built mod binaries into your Hytale server `mods` folder.
6. Start the server and join a world.
7. Give yourself the radio item and open the UI to start playback.
