# Recording Mod (1.20.1 port)

A lightweight, client-side recording & replay mod for Minecraft — "even better than OBS". Record a play session, then watch it back with a free camera, full world reconstruction (other players, mobs, chunks, chat), and no server connection required.

This is a port of the original [1.12.2 LiteLoader mod](legacy-1.12.2/) to **Fabric 1.20.1** — this codebase is built directly on top of that original mod's design and code, carrying its recording format and feature set forward onto Minecraft's modern networking/client APIs instead of the old hand-copied packet-handling reimplementation. The original 1.12.2 source is kept in full under [`legacy-1.12.2/`](legacy-1.12.2/) for reference.

## Status

Actively being ported — core recording and playback work, but this is not a finished, polished mod yet. See [Known limitations](#known-limitations) below.

## How it works

Rather than inventing a new replay format, the recorder captures the real clientbound packet stream Minecraft already receives from the server, plus a handful of client-only bits the server never sends back to you (your own position/rotation, client-predicted animations, etc). Playback feeds that stream through a real (but disconnected) `ClientPacketListener` — the same class Minecraft itself uses for a live server connection — so world reconstruction is handled by Mojang's own, always-current logic instead of a fragile hand-copied one.

## Building & running

Requires JDK 21 to run Gradle (the mod itself still targets/runs on Java 17+ at the Minecraft level):

```sh
./gradlew build       # build the mod jar
./gradlew runClient   # launch a dev Minecraft client with the mod loaded
```

## Keybinds

All keybinds are **unbound by default** — bind them yourself under *Options → Controls → Recording* after first launch:

| Action | What it does |
| --- | --- |
| Toggle Recording | Start/stop recording the current session to `recordings/<timestamp>.rec` |
| Play Last Recording | Play back the most recently modified recording |
| Open Recordings | Browse and play back any saved recording |
| Leave Playback | Stop watching and return to the title screen |

## Known limitations

- No video export yet (the original mod's native encoder is being replaced with an ffmpeg pipe — not implemented yet).
- No fast-forward / skip-ahead during playback.
- Recordings started mid-session synthesize the missing "join" state (chunks, entities, inventory, etc.) from whatever's currently loaded — very old chunks/entities far outside render distance at recording start won't be included.
- `RecordingsScreen` has no scrolling yet — only shows as many recordings as fit on screen.
- Dimension changes / respawns mid-recording are not specifically tested.

## Credits

Original 1.12.2 mod by [ayoeo](https://github.com/ayoeo/recording_mod).
