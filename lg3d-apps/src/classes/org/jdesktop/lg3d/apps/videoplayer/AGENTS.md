# Video Player

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** VLC-style video front-end (files, streams, discs) |
| Entry point | `VideoPlayer.main` → `TitledSwingWindow.show(...)`; `VideoPlayerClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (hosts `VideoPlayerPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Video Player / **Media** |
| Command | `java org.jdesktop.lg3d.apps.videoplayer.VideoPlayer` |
| Descriptor | `src/config/videoplayer.lgcfg` → `config/demo` |
| Format | **External launcher** — the JDK has no video decoder, so every file, stream or disc is handed to a real player (vlc, mpv, mplayer, totem, ffplay, kodi, xplayer, celluloid). Discs are addressed as `dvd:///dev/sr0`-style URLs |
| Persistence | Jackson JSON under `~/.lg3d/videoplayer` (library + settings) via `VideoPlayerStore`; override dir with `-Dlg3d.videoplayer.dir` |
| Security | No secret is stored — a library records only names, file paths, stream URLs and disc devices. External launches go through a guarded `ProcessBuilder` (DISPLAY propagated) started only on a user action |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **VideoPlayer** — thin 3D entry point; installs the hosted look and feel and
  shows `VideoPlayerPanel` in a `TitledSwingWindow`, wiring `setOnClose` to stop
  playback and `frame.changeEnabled(false)`.
- **VideoPlayerClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running the
  player outside the desktop; never calls `System.exit`.
- **VideoPlayerPanel** — the one Swing UI (no-arg constructor, no Java 3D): a WEST
  library list, a CENTER now-playing display with Play/Stop, and a SOUTH
  player/full-screen/volume strip. `JFileChooser`s and `JOptionPane`s are created
  lazily so the panel builds headless; no process is started until the user plays.
- **VideoBackend** — the AWT-free playback seam: classifies a location
  (file / stream / disc), maps a disc device to a URL, and builds the exact
  command line per known player including full-screen and volume flags. Pure and
  side-effect free, so the whole decision table is unit-testable headless.
- **VideoItem / VideoSettings** — Jackson model beans: a library entry
  (`Kind { FILE, STREAM, DISC }`) and the persisted preferences (volume, preferred
  player, full-screen).
- **VideoPlayerStore** — defensive JSON persistence (a corrupt/missing file yields
  an empty library / defaults, never throws).

## Roles

- **Architect** — Everything lives in this package: the model beans, the AWT-free
  `VideoBackend`, `VideoPlayerStore` (Jackson I/O), `VideoPlayerPanel` (the one
  Swing UI) and the two thin entry points. The same panel drives both desktops:
  3D via `TitledSwingWindow`, 2D via `Desktop2DAppRegistry.PANEL_APPS` keyed on
  `VideoPlayer`. **No new third-party dependency is added** — playback is entirely
  delegated to an installed external player. Keep all command-building logic in the
  backend (testable without a display), never in the panel.
- **Engineer / Developer** — Keep the classification and command building in
  `VideoBackend`; the panel only resolves a player and marshals the launch onto a
  guarded `ProcessBuilder`, destroying it in `stopPlayback`. Guard every
  `JFileChooser`/`JOptionPane`/process path so it is only reached from a user
  action, never the constructor, so headless tests can build the panel. Never call
  `System.exit`. Jogamp packages only where 3D is touched (none here); obey the
  core UI/UX rulebook.
- **QA** — `VideoBackendTest`, `VideoItemTest`, `VideoPlayerStoreTest` and
  `VideoPlayerPanelTest` run headless (22 tests): the backend suite drives
  file/stream/disc classification, disc-URL mapping and per-player command lines
  (full-screen + volume); the item/store suites assert bean invariants, JSON
  round-trips and corrupt-file resilience; the panel suite asserts construction,
  library growth and status without starting a process. For the 3D host use the
  in-JVM probe + internal screencapture (`lg3d-core/lgscreen-*.png`); a black
  capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver video front-end: build a library of movies,
  streams and discs and launch them in the user's real player, in both desktops.
  Value = a coherent VLC-style surface with no bundled codec and no cloud
  dependency.
- **Functional Analyst** — Spec this app as the *launch contract*: classify a
  location, resolve an available player, build its command line and manage the
  child process — with the decision hidden behind `VideoBackend`. Playback is
  specified as "hand off to an external player", never as a claim the JDK decodes
  video.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `VideoPlayerPanel` in an MDI internal
  frame. Conventional library-plus-transport chrome, never a click-cycling 3D
  idiom; keep both surfaces pixel-identical. A missing external player must
  surface in the status line as install guidance, not a silent failure.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `VideoBackend` seam,
the external-launcher split and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
