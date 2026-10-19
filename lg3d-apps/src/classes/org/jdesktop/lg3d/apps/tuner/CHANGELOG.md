# Changelog

All notable changes to Tuner are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Guitar / bass tuner that listens to the microphone** — a production-grade chromatic and
  per-instrument tuner that analyses the microphone **natively and in-process**:
  `javax.sound.sampled` opens the default capture line (16-bit mono little-endian
  PCM) and a daemon thread streams frames into an AWT-free **`PitchDetector`**
  implementing the **YIN** algorithm (difference function → cumulative-mean
  normalised difference → absolute threshold → parabolic interpolation), so the
  fundamental is resolved with no external tool, no codec and nothing to install.
  An equal-temperament **`Note`** model turns the frequency into a note and a
  cents deviation, and a flexible **`Tuning`** model (chromatic, guitar standard /
  drop-D / open-G, bass 4- and 5-string, ukulele, plus **user-defined tunings**
  such as Drop C built from any string set) names the nearest string. Custom
  tunings are added and removed from the toolbar (`+` / `−`) through an editor and
  persisted as JSON under `~/.lg3d/tuner` by **`TuningStore`** (overridable via
  `lg3d.tuner.dir`), so any instrument tuning survives a restart. The one
  **`TunerPanel`** (big detected-note card, a painted cent-deviation
  **`TuningMeter`** whose needle turns green inside a ±5-cent window, and a string
  strip that highlights the nearest string) serves both desktops — a SwingNode-in-
  `Frame3D` host in 3D via `TitledSwingWindow` and an MDI internal frame in 2D via
  `Desktop2DAppRegistry.PANEL_APPS` — plus a standalone `TunerClient`. The
  microphone is opened **only** on a Start press and released on Stop / Close /
  window-close, so nothing is recorded and the panel constructs headless. The UI
  carries **no hardcoded colours**: every hue is resolved live from the active
  look-and-feel through **`TunerTheme`** (`UIManager` keys, with toolkit/blend
  fallbacks; only the meter's green/amber/red traffic-light status hues are
  semantic constants), so the panel matches the system L&F in 2D and the hosted
  Metal L&F in the 3D SwingNode. Registered as the *Media* start-menu app
  `tuner.lgcfg` with a purpose-drawn tuning-fork `tuner.png` icon. Covered by
  headless JUnit 5 suites (`PitchDetectorTest` feeding synthetic sine waves and
  recovering every guitar/bass open string, `NoteTest`, `TuningTest` incl. custom
  Drop C, `TuningStoreTest` round-trip, `TunerThemeTest`, `AddTuningDialogTest`,
  `TuningMeterTest` painting into a `BufferedImage`, `TunerPanelTest` driving the
  `applyReading` and add/remove-tuning seams) plus a `Desktop2DAppRegistryTest`
  classification case — no device is ever opened in CI.
