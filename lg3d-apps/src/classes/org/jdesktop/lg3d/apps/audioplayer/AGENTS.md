# Audio Player

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** multimedia player (local files, internet radio, streams, podcasts) + **audio-CD ripper** and **album-cover music library** |
| Entry point | `AudioPlayer.main` → `TitledSwingWindow.show(...)`; `AudioPlayerClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (hosts `AudioPlayerPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Audio Player / **Media** |
| Command | `java org.jdesktop.lg3d.apps.audioplayer.AudioPlayer` |
| Descriptor | `src/config/audioplayer.lgcfg` → `config/demo` |
| Format | **Native** `javax.sound.sampled` for WAV / AU / AIFF; MP3 / AAC / Ogg / network streams have no in-JDK decoder, so they are handed to a real external player (mpv, mpg123, ffplay, mplayer, vlc, totem, audacious) |
| Persistence | Jackson JSON under `~/.lg3d/audioplayer` (library + settings + rip settings) via `AudioPlayerStore`; ripped tracks go to `music/`, cached covers to `covers/`; override dir with `-Dlg3d.audioplayer.dir` |
| CD ripping | No bundled codec — an honest external-tool split: `cdparanoia` (fallback `cd-info`) reads the TOC and extracts, `ffmpeg` (fallback `lame`) encodes/resamples to MP3/WAV; album/artist/track names + cover come from **MusicBrainz** + **Cover Art Archive**. A missing tool is reported in the status line, never faked |
| Security | No secret is stored — a library records only names, file paths and stream URLs. External launches go through a guarded `ProcessBuilder` (DISPLAY propagated) started only on a user action |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **AudioPlayer** — thin 3D entry point; installs the hosted look and feel and
  shows `AudioPlayerPanel` in a `TitledSwingWindow`, wiring `setOnClose` to
  `frame.changeEnabled(false)`.
- **AudioPlayerClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running the
  player outside the desktop; never calls `System.exit`.
- **AudioPlayerPanel** — the one Swing UI (no-arg constructor, no Java 3D): a WEST
  library list, a CENTER now-playing display with transport controls, and a SOUTH
  player/volume strip. `JFileChooser`s are created lazily so the panel builds
  headless; no audio device or process is touched until the user presses play.
- **AudioBackend** — the AWT-free playback seam: decides whether a location is
  natively playable (WAV/AU/AIFF) or must be handed to an external player, and
  builds the exact command line per known player. Pure and side-effect free, so
  the whole decision table is unit-testable headless.
- **Playlist** — the ordered play queue with cursor management (next/previous/
  select/remove, clamped so removing the current track keeps the cursor sane).
- **MediaItem / PlayerSettings** — Jackson model beans: a track (name, location,
  kind) and the persisted preferences (volume, preferred player).
- **AudioPlayerStore** — defensive JSON persistence (a corrupt/missing file yields
  an empty library / defaults, never throws); also persists `RipSettings`
  (`ripsettings.json`) and resolves the `covers/` (`coverFile(mbid)`) and `music/`
  folders.
- **CdRipPanel** — the `Rip CD` tab (thin EDT/worker glue): reads the disc, drives
  the MusicBrainz/Cover Art Archive lookup to prefill tags + cover, then rips the
  selected tracks and hands the tagged `MediaItem`s back to the host panel via a
  `Consumer`. Constructs headless; launches a process only on a button press.
- **CdRipBackend** — the AWT-free ripping seam (mirrors `AudioBackend` /
  `RecorderBackend`): the sample-rate/bitrate/format tables, the `cdparanoia` /
  `ffmpeg` / `lame` command builders, device detection and ripper/encoder
  resolution over a PATH predicate. Pure, so the whole decision table is
  unit-testable headless.
- **Toc / TocParser** — the disc table of contents and its tolerant parser for
  `cdparanoia -Q` and `cd-info` output.
- **MusicBrainzDiscId** — computes the MusicBrainz disc ID from a `Toc` (SHA-1 over
  the canonical ID string + the MusicBrainz base64 alphabet); pure and verified
  against the published vector.
- **AudioCdDb** — the audio-CD-database seam (mirrors `OpenMeteo`): MusicBrainz
  disc-ID lookup + Cover Art Archive `front-500` URLs, a never-throw blocking
  `HttpClient` fetch, and a pure `parseRelease(body)` testable with canned JSON.
- **RipSettings** — Jackson bean for the rip preferences (format, sample rate,
  MP3 bitrate, output dir, device, preferred ripper/encoder); setters clamp like
  `PlayerSettings`.
- **AlbumIndex** — pure grouping of the flat library into albums (by MBID, else
  `artist|album`), one sleeve per album, driving both cover carousels.
- **AlbumCoverFlow** — the Swing cover carousel (no Java 3D) on the `Albums` tab:
  a painted centre sleeve with sheared neighbours, wheel revolve, click select,
  double-click play, generated placeholder art when a cover is missing. The 2D
  desktop's album surface; the 3D counterpart is `CDViewer`.

## Roles

- **Architect** — Everything lives in this package: the model beans, the AWT-free
  `AudioBackend`, `AudioPlayerStore` (Jackson I/O), `AudioPlayerPanel` (the one
  Swing UI) and the two thin entry points. The same panel drives both desktops:
  3D via `TitledSwingWindow`, 2D via `Desktop2DAppRegistry.PANEL_APPS` keyed on
  `AudioPlayer`. **No new third-party dependency is added** — native decoding is
  `javax.sound.sampled`; the codec formats are delegated to whatever player is
  already installed. Keep all playback-decision logic in the backend (testable
  without a display), never in the panel.
- **Engineer / Developer** — Keep the native/external split in `AudioBackend`; the
  panel only calls the seam and marshals the launch onto a guarded
  `ProcessBuilder`. Guard every `JFileChooser`/process/device path so it is only
  reached from a user action, never the constructor, so headless tests can build
  the panel. Never call `System.exit`. Jogamp packages only where 3D is touched
  (none here); obey the core UI/UX rulebook.
- **QA** — `AudioBackendTest`, `MediaItemTest`, `PlaylistTest`,
  `AudioPlayerStoreTest` and `AudioPlayerPanelTest`, plus the ripping/library
  suites `CdRipBackendTest`, `TocParserTest`, `MusicBrainzDiscIdTest`,
  `AudioCdDbTest`, `AlbumIndexTest`, `RipSettingsTest` and `AlbumCoverFlowTest`,
  run headless: the backend suites drive the native-vs-external playback decision
  and the rip command lines; `MusicBrainzDiscIdTest` asserts the published disc-ID
  vector; `AudioCdDbTest` parses canned MusicBrainz JSON with no network;
  `AlbumIndexTest` asserts album grouping; the playlist suite asserts cursor
  invariants across next/previous/remove; the store suite asserts JSON round-trips
  and corrupt-file resilience; the panel suite asserts construction, library
  growth and status without opening a device. Real CD ripping needs a physical
  drive + `cdparanoia`/`ffmpeg` (not CI-gated). For the 3D host and the
  library-aware `CDViewer` carousel use the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); a black capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver music/radio player: build a library of
  local tracks and internet streams, play them natively where the JDK can and
  through the user's real player otherwise, in both desktops. Value = a working
  audio front-end with no bundled codec and no cloud dependency.
- **Functional Analyst** — Spec this app as the *playback contract*: classify a
  location (native / stream / codec), resolve an available player, build its
  command line and manage a play queue — with the format decision hidden behind
  `AudioBackend`. Playback is specified as "native where possible, external
  otherwise", never as a claim the JDK decodes MP3.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `AudioPlayerPanel` in an MDI internal
  frame. Conventional library-plus-transport chrome, never a click-cycling 3D
  idiom; keep both surfaces pixel-identical. A missing external player must
  surface in the status line so it reads as guidance, not a silent failure.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `AudioBackend` seam,
the native-vs-external split and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
