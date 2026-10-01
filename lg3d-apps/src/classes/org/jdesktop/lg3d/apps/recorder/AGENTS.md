# Recorder

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** audio/video recorder (microphone capture + screen capture) |
| Entry point | `Recorder.main` → `TitledSwingWindow.show(...)`; `RecorderClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (hosts `RecorderPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Recorder / **Media** |
| Command | `java org.jdesktop.lg3d.apps.recorder.Recorder` |
| Descriptor | `src/config/recorder.lgcfg` → `config/demo` |
| Format | Audio is captured **natively** to WAV via `javax.sound.sampled` (`TargetDataLine`); the JDK has no screen encoder, so screen capture is handed to an external `ffmpeg`/`avconv` writing an MP4 (`x11grab`, optionally muxing the microphone via PulseAudio) |
| Persistence | Jackson JSON under `~/.lg3d/recorder` (settings + capture history) via `RecorderStore`; override dir with `-Dlg3d.recorder.dir`. Captures themselves are written to the configured output folder (default `~/Recordings`) |
| Security | No secret and no captured media is stored in config — only the output folder, format preferences and finished file paths. The screen process is stopped gracefully (`q` on stdin, then destroy) so the MP4 is finalised, not truncated |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **Recorder** — thin 3D entry point; installs the hosted look and feel and shows
  `RecorderPanel` in a `TitledSwingWindow`, wiring `setOnClose` to stop any live
  capture and `frame.changeEnabled(false)`.
- **RecorderClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running the
  recorder outside the desktop; never calls `System.exit`.
- **RecorderPanel** — the one Swing UI (no-arg constructor, no Java 3D): an Audio
  tab (format + Record/Stop + elapsed), a Screen tab (source / size / fps /
  microphone + Record/Stop + elapsed) and an EAST history list. No capture device
  is opened, no process started and no dialog shown until the user presses Record;
  the capture runs on a daemon thread and the elapsed clock on a `javax.swing.Timer`
  started only while recording.
- **RecorderBackend** — the AWT-free recording seam: builds the native WAV
  `AudioFormat` (a device-free descriptor), the exact `ffmpeg` `x11grab` command
  line (resolution / fps / optional microphone), a timestamped default file name
  and the recorder resolution. Pure and side-effect free, so the whole table is
  unit-testable headless.
- **Recording / RecordingSettings** — Jackson beans: a finished capture
  (`Kind { AUDIO, VIDEO }`, path, duration) and the clamped preferences (output
  dir, sample rate, channels, screen source, resolution, fps, recorder).
- **RecorderStore** — defensive JSON persistence (a corrupt/missing file yields
  defaults / an empty history, never throws).

## Roles

- **Architect** — Everything lives in this package: the beans, the AWT-free
  `RecorderBackend`, `RecorderStore` (Jackson I/O), `RecorderPanel` (the one Swing
  UI) and the two thin entry points. The same panel drives both desktops: 3D via
  `TitledSwingWindow`, 2D via `Desktop2DAppRegistry.PANEL_APPS` keyed on
  `Recorder`. **No new dependency is added** — audio is `javax.sound.sampled`;
  screen capture is delegated to an installed `ffmpeg`. Keep all command/format
  logic in the backend (testable without a display), never in the panel.
- **Engineer / Developer** — Keep the native-audio / external-screen split in
  `RecorderBackend`; the panel only opens the `TargetDataLine` or launches the
  guarded `ProcessBuilder` on a Record press. Run the WAV write on a daemon thread
  and join it on stop; stop `ffmpeg` with `q` on stdin before destroying it so the
  file is finalised. Guard every device/process/`JFileChooser` path so it is only
  reached from a user action, never the constructor, so headless tests can build
  the panel. Never call `System.exit`. Obey the core UI/UX rulebook.
- **QA** — `RecorderBackendTest`, `RecordingTest`, `RecorderStoreTest` and
  `RecorderPanelTest` run headless (24 tests): the backend suite asserts the WAV
  format clamps, the `x11grab` command line (resolution / fps clamp / optional
  microphone), resolution validation, default file naming and recorder
  resolution; the bean/store suites assert invariants, JSON round-trips and
  corrupt-file resilience; the panel suite asserts construction, idle-stop safety,
  history growth/reload and output-folder resolution — never opening a device. For
  the 3D host use the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); a black capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver capture tool: record the microphone to WAV
  with no external tool, and record the screen to MP4 through `ffmpeg`, with a
  history of finished captures, in both desktops. Value = built-in capture with an
  honest, dependency-free audio path.
- **Functional Analyst** — Spec this app as the *capture contract*: record audio
  natively, record the screen through an external recorder, and persist the
  resulting files as history — with the format/command decisions hidden behind
  `RecorderBackend`. Screen recording is specified as "hand off to ffmpeg", never
  as a claim the JDK encodes video.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `RecorderPanel` in an MDI internal frame.
  Conventional tabbed capture chrome with a live elapsed clock and a history dock,
  never a click-cycling 3D idiom; keep both surfaces pixel-identical. A missing
  recorder or an unavailable microphone must surface in the status line as
  guidance, not a silent failure.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `RecorderBackend` seam,
the native-audio / external-screen split and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
