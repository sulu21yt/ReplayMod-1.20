# Recording Mod (1.20.1 port)

A lightweight, client-side recording & replay mod for Minecraft. Record a play session, then watch it back in first- or third-person (toggle with F5) as the recorded player, with full world reconstruction (other players, mobs, chunks, chat, sound) and no server connection required for playback.

This is a port of the original [1.12.2 LiteLoader mod](legacy-1.12.2/) to **Fabric 1.20.1** — this codebase is built directly on top of that original mod's design and code, carrying its recording format and feature set forward onto Minecraft's modern networking/client APIs instead of the old hand-copied packet-handling reimplementation. The original 1.12.2 source is kept in full under [`legacy-1.12.2/`](legacy-1.12.2/) for reference.

## Status

Actively being ported — core recording and playback work, but this is not a finished, polished mod yet. See [Known limitations](#known-limitations) below.

## How it works

Rather than inventing a new replay format, the recorder captures the real clientbound packet stream Minecraft already receives from the server, plus a handful of client-only bits the server never sends back to you (your own position/rotation, client-predicted animations, etc). Playback feeds that stream through a real (but disconnected) `ClientPacketListener` — the same class Minecraft itself uses for a live server connection — so world reconstruction is handled by Mojang's own, always-current logic instead of a fragile hand-copied one.

## Building & running

Requires JDK 21 to run Gradle (the mod itself still targets/runs on Java 17+ at the Minecraft level) and [ffmpeg](https://ffmpeg.org/) on your `PATH` (or configured via its path in Settings) for video export.

```sh
./gradlew build       # build the mod jar (lands in build/libs/)
./gradlew runClient   # launch a dev Minecraft client with the mod loaded
```

On Windows, `setup-and-run.bat` does all of this for you in one click (installs Java 21 and ffmpeg via `winget` if missing, then builds and launches).

To use the built mod in your own real Minecraft install instead of the dev client (e.g. to play on a real server — the dev client only has an offline account), copy the jar from `build/libs/recording_mod-*.jar` into your `mods` folder, alongside [Fabric API](https://modrinth.com/mod/fabric-api) and [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) matching the versions in `gradle.properties`.

## Features

- **Recording**: captures the real clientbound packet stream plus client-only bits (own position/rotation, predicted animations) to `recordings/<timestamp>.rec`.
- **Playback**: full world reconstruction (entities, chunks, chat, sound) via a real disconnected `ClientPacketListener`, watched as the recorded player in first- or third-person (F5 to toggle) — including their crouching, sneaking, item-use (bow draw, eating, blocking, fishing), swing/attack animations, and worn skin layers, not just their position.
- **Scrubbing**: jump forward/backward by 5s or 30s during normal playback, scroll the mouse wheel to scrub continuously, or open a draggable timeline bar to click/drag to any point.
- **Markers**: instantly bookmark a moment while recording (no typing, no interrupting what you're doing - auto-named by elapsed time), rename it afterward, and jump straight to it later from the Markers screen.
- **Blueprints & video export**: mark tick ranges as blueprints with per-region slow-motion (Blend Factor motion blur) and export them to `.mp4` via an ffmpeg pipe, with real game audio captured through an OpenAL loopback and synced to any slow-mo regions. Export runs as fast as possible rather than in real time.
- **Recordings/Markers screens**: browse saved recordings and markers, scrollable with the mouse wheel once there are more than fit on screen. Each recording shows a thumbnail (captured when recording stops) and can be renamed in place.

## Keybinds

All keybinds are **unbound by default** — bind them yourself under *Options → Controls → Recording* after first launch:

| Action | What it does |
| --- | --- |
| Toggle Recording | Start/stop recording the current session to `recordings/<timestamp>.rec` |
| Play Last Recording | Play back the most recently modified recording |
| Open Recordings | Browse and play back any saved recording |
| Open Settings | Open the recording settings screen |
| Mark Moment | Instantly bookmark the current tick while recording (auto-named, rename later from Markers) |
| Skip Back/Forward 5s | Scrub 5 seconds backward/forward during playback |
| Skip Back/Forward 30s | Scrub 30 seconds backward/forward during playback |
| Open Timeline | Open a draggable timeline bar to click/drag to any point in the recording |
| Leave Playback | Stop watching and return to the title screen |

Mouse wheel also scrubs during playback (5s per notch) — no keybind needed.

## Known limitations

- Recordings started mid-session synthesize the missing "join" state (chunks, entities, inventory, etc.) from whatever's currently loaded — very old chunks/entities far outside render distance at recording start won't be included.
- The timeline bar and duration shown in the Recordings screen only work for recordings made after this feature was added — older recordings have no sidecar metadata and fall back to just showing elapsed time.
- Renaming a recording does not update any markers that point at it by its old filename — they'll silently stop resolving.
- What's shown in your inventory screen while it's open, and what you're typing in chat character-by-character before hitting enter, are not captured — both are purely local UI state that never touches the network, unlike everything else this mod records.
- Switching from third-person to first-person partway through watching a recording that was made in third-person can leave the held item/hotbar not rendering correctly — not yet root-caused.

## Credits

Original 1.12.2 mod by [ayoeo](https://github.com/ayoeo/recording_mod).
