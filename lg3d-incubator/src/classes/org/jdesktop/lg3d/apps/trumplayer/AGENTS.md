# Trumplayer (JLayer MP3 Player)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, needs an external JLayer jar, not start-menu registered) |
| Entry point | `trumplayer.Main` (`public static void main` → `startup()`); `Frame`/`Player extends PlayerBase` drive playback |
| Surface | **Hybrid 3D + SwingNode**: `AlbumShelf extends ShelfBase` (3D album rack) with `SwingNode`-hosted `SearchSwingPanel` and `PlayListManagerSwingNode extends ExtendedDragComponent3D`; `mp3player.JLayerMP3Player` decodes audio, `mp3util.ID3Tag(Reader)` reads tags |
| Start-menu name / group | trumplayer : JLayer-based MP3 Player for LG3D / **Media** — descriptor `trumplayer.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.trumplayer.Main` |
| Runtime blocker | Needs **JLayer** (`jl1.0.jar`, referenced as `../jl1.0.jar` in the descriptor `classpathJars`) — not in-repo, not on the Gradle classpath |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A JLayer-based MP3 player: a 3D album shelf plus SwingNode-hosted
  search/playlist panels. Moved off JMF to JLayer (the old `MP3PluginChecker` path is
  dead code). Dormant: it needs an external `jl1.0.jar` and its descriptor is unscanned.
  Keep the `base`/`mp3player`/`mp3util`/`utils` layering (model vs decode vs UI) intact.
- **Engineer / Developer** — Decode audio off the EDT/render thread; the `updateThread`
  and search must not block the scene graph. Host Swing panels on `SwingNode` with a null
  layout + explicit bounds (no modal dialogs), `dispose()` when discarded. Obey the core
  UI/UX rulebook (texture pixels before attach, `Component3D` wrapping, translucency
  sorting, EDT hops). Jogamp packages only.
- **QA** — Classify honestly: **compiles, but cannot run** without JLayer on the classpath.
  Verify the shelf/panels construct via the in-JVM probe + internal screencapture; a black
  host capture under Wayland is not a defect. Do not file "no audio" as a regression when
  the jar is absent.
- **Business Analyst** — Music player demonstrator. Niche/historical; blocked on an
  unmaintained third-party MP3 library.
- **Functional Analyst** — Spec album browse/search, playlist management, and playback,
  plus the JLayer dependency contract. Record the descriptor-not-scanned fact.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — track the `jl1.0.jar`
  run-classpath dependency as the gating integration risk.
- **UI/UX (3D & 2D)** — **Both**: a 3D album shelf (`ShelfBase`) and 2D Swing search /
  playlist panels on `SwingNode`. Follow the glassy vocabulary, depth ordering, and the
  SwingNode contract (offscreen paint, no modal dialogs) from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
