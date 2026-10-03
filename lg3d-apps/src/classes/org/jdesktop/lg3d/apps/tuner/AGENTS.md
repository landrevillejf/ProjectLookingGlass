# Tuner

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** guitar / bass tuner (microphone pitch analysis) |
| Entry point | `Tuner.main` → `TitledSwingWindow.show(...)`; `TunerClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (hosts `TunerPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Tuner / **Media** |
| Command | `java org.jdesktop.lg3d.apps.tuner.Tuner` |
| Descriptor | `src/config/tuner.lgcfg` → `config/demo` |
| Format | Pitch is detected **natively and in-process**: `javax.sound.sampled` opens the default capture line (`TargetDataLine`, 16-bit mono little-endian PCM) and a daemon thread streams frames into the AWT-free `PitchDetector` (the **YIN** algorithm). No external tool, no codec, no recording — analysis only |
| Persistence | None — a tuner has no state worth saving; every reading is live |
| Security | The microphone is opened **only** on a Start press and released on Stop / Close / window-close; nothing is written to disk and no audio is retained |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **Tuner** — thin 3D entry point; installs the hosted look and feel and shows
  `TunerPanel` in a `TitledSwingWindow`, wiring `setOnClose` to stop any live
  capture and `frame.changeEnabled(false)`.
- **TunerClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running the tuner
  outside the desktop; never calls `System.exit`; a window listener stops the
  capture when the frame closes.
- **TunerPanel** — the one Swing UI (no-arg constructor, no Java 3D): a tuning
  selector, Start/Stop, a big detected-note card, a cent-deviation `TuningMeter`
  and a strip of the selected tuning's strings with the nearest one highlighted.
  No capture device is opened until the user presses Start, so the panel
  constructs headless; the `applyReading` seam drives the UI without a microphone.
- **TuningMeter** — a plain `JComponent` painted with Java 2D (a flat→sharp scale
  with an eased needle that turns green inside the tolerance window). The needle
  geometry is factored into the pure static `needleFraction` / `isInTune` helpers.
- **PitchDetector** — the AWT-free DSP heart: the YIN algorithm (difference
  function → cumulative mean normalised difference → absolute threshold →
  parabolic interpolation) over a caller-supplied `float[]`, returning a `Pitch`
  (frequency + clarity, or unvoiced). Lag search is bounded to the guitar/bass
  range and an RMS noise floor rejects silence. Pure and side-effect free.
- **Note / Tuning** — the AWT-free equal-temperament model: nearest-note / cents
  resolution (`Note`) and the named string tables with nearest-string search
  (`Tuning`: chromatic, guitar standard / drop-D / open-G, bass 4- and 5-string,
  ukulele).

## Roles

- **Architect** — Everything lives in this package: the AWT-free `PitchDetector` /
  `Note` / `Tuning` model, `TuningMeter` (pure geometry + Java 2D paint),
  `TunerPanel` (the one Swing UI) and the two thin entry points. The same panel
  drives both desktops: 3D via `TitledSwingWindow`, 2D via
  `Desktop2DAppRegistry.PANEL_APPS` keyed on `Tuner`. **No new dependency is
  added** — capture is `javax.sound.sampled` and detection is in-process YIN. Keep
  all pitch/note/tuning maths in the pure model (testable without a display or a
  microphone), never in the panel.
- **Engineer / Developer** — Keep the DSP in `PitchDetector` and the maths in
  `Note` / `Tuning`; the panel only opens the `TargetDataLine` on a Start press
  and marshals each detection onto the EDT via `SwingUtilities.invokeLater`.
  Release the line on Stop / Close / window-close so the microphone is never left
  hot. Guard every device path so it is only reached from a user action, never the
  constructor, so headless tests can build the panel. Never call `System.exit`.
  Obey the core UI/UX rulebook.
- **QA** — `PitchDetectorTest`, `NoteTest`, `TuningTest`, `TuningMeterTest` and
  `TunerPanelTest` run headless (57 tests): the DSP suite feeds synthetic sine
  waves and asserts every guitar/bass open string is recovered within a couple of
  percent and that silence / noise / short buffers read unvoiced; the model suites
  assert the equal-temperament and tuning-table maths; the meter suite asserts the
  needle geometry and paints into a `BufferedImage`; the panel suite drives the
  `applyReading` seam on the EDT and asserts the note / string / cents / status
  updates — never opening a device. For the 3D host use the in-JVM probe +
  internal screencapture (`lg3d-core/lgscreen-*.png`); a black capture under
  Wayland is not a defect.
- **Business Analyst** — A daily-driver instrument tool: tune a guitar or bass
  from the microphone with a live cent meter and per-string read-out, in both
  desktops, with nothing to install. Value = a built-in, dependency-free tuner.
- **Functional Analyst** — Spec this app as the *pitch contract*: capture the
  microphone, resolve the fundamental to a note and a cents deviation against the
  selected tuning, and show it on a meter — with the DSP hidden behind
  `PitchDetector`. Detection is specified as "in-process YIN analysis", never as a
  claim about an external tuner or a recording.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `TunerPanel` in an MDI internal frame.
  Conventional tuner chrome (big note, cent meter, string strip), never a
  click-cycling 3D idiom; keep both surfaces pixel-identical. An unavailable
  microphone must surface in the status line as guidance, not a silent failure.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `PitchDetector` seam,
the native in-process capture split and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
