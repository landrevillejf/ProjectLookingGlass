# Screen Capture Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (screen snapshot) |
| Entry point | `ScreenCaptureConfigFrame.main` (a Swing `JFrame` config dialog) |
| Surface | **2D Swing config frame** (three-way area radio group); capture is either an offscreen Swing composite (2D) or a desktop event (3D), never a 3D window |
| Start-menu name / group | Screen Snapshot / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.screencapture.ScreenCaptureConfigFrame` |
| Descriptor | `src/config/screencapture.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

## Roles

- **Architect** — Decoupled by design: the config frame collects the *area*,
  delay and target, then either drives the 2D engine or **posts a
  `ScreenCaptureEvent` via `AppConnectorPrivate`** after a `Timer` delay. Keep
  the two capture back-ends behind the one `CaptureArea` choice:
  - **2D/Swing desktop** (`Desktop2D.MODE_PROPERTY` true) — `DesktopScreenshot`
    paints the desktop's own Swing windows offscreen into `BufferedImage`s. There
    is no scene graph to read and `java.awt.Robot` cannot grab a rootless/Wayland
    display, so the windows are the source of truth. Keep it that way: never
    reintroduce `Robot`.
  - **3D desktop** — post the event and let `ScreenCaptureBehavior` read the
    canvas raster back. `SELECTION` sets the event's optional crop `Rectangle`;
    the other two areas post a whole-canvas event (the scene graph already draws
    every window into one canvas). Keep the event-based seam (do not capture
    in-process).
  The frame is held as a `WeakReference` singleton so repeated launches reuse it.
- **Engineer / Developer** — This is a conventional Swing `JFrame` (real layout,
  EDT). Every capture path runs on the EDT (the 2D engine paints Swing windows;
  the selection overlay is itself a Swing window). Split pure from live: the
  rectangle arithmetic (`CaptureRegions`), naming/directory (`ScreenshotFiles`),
  window discovery (`DesktopScreenshot.findDesktopPane`/`visibleInternalFrames`)
  and the 3D crop (`lg3d-core` `RasterCrop`) are headless-tested; only the paint
  calls, the read-back raster and the `RegionSelector` overlay need a display.
  The `JFileChooser` save path is intentionally disabled for now — do not wire it
  to a blocking dialog without revisiting the capture flow. Jogamp packages only
  where 3D.
- **QA** — Verify by launching the desktop, triggering Screen Snapshot, choosing
  each of the three areas, and checking the produced `lgscreen-*.png` in
  `~/Documents/Screenshots` (Whole screen → one composite; All windows → one per
  window; Selection → one cropped image matching the drag). A black *host*
  capture under Wayland is not a defect — use lg3d's internal capture. The pure
  logic is unit-tested headless (`:lg3d-apps:test`);
  `RegionSelector`/`DesktopScreenshot` paint paths are probe-only.
- **Business Analyst** — A shipped utility (take a screen snapshot of the whole
  screen, every window, or a chosen region). It is also the mechanism the project
  itself relies on for UI verification evidence, so reliability matters.
  Production standards apply.
- **Functional Analyst** — Spec user-visible function (choose area + delay →
  capture fires → snapshot(s) saved) plus the event contract with core
  (`ScreenCaptureEvent`, now with an optional region). Note the disabled
  file-chooser and the external-native-window limitation below.
- **Project Manager** — Commit scope `lg3d-apps` (and `lg3d-core` for the event
  region + crop). Done = build + `./run-lg3d.sh` + a captured snapshot per area
  as evidence. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — **2D** only: a small Swing config frame with a three-way
  radio group plus the drag-select overlay. Keep it simple and consistent with
  the platform LAF; the snapshot output is the real UX artifact.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

## Overview

Screen Capture is a configuration tool for taking timed snapshots of the LG3D desktop. It provides a Swing UI for choosing the capture area (whole screen, all windows, or a user-selected region), the save directory and the delay, then either paints the desktop's Swing windows offscreen (2D) or posts a ScreenCaptureEvent (3D) to trigger the capture after the specified delay.

## Purpose

- Choose what to capture: whole screen, every window, or a dragged-out region
- Configure screen capture parameters (directory, delay)
- Trigger delayed screen snapshots
- Demonstrate event posting to display server
- Provide UI for capture settings

## Key Components

- **CaptureArea** - The three-way choice (`FULL_SCREEN`, `ALL_WINDOWS`, `SELECTION`); pure enum, its `label()` feeds the radio buttons
- **ScreenCaptureConfigFrame** - Main JFrame with the area radio group + configuration UI
- **DesktopScreenshot** - The 2D/Swing capture engine: composites/paints the desktop's own windows offscreen (no `Robot`)
- **RegionSelector** - Full-screen translucent `JWindow` drag-select overlay for the `SELECTION` area
- **CaptureRegions** - Pure rectangle arithmetic (normalise/union/clamp/crop) shared by the 2D paths
- **ScreenshotFiles** - Naming (`lgscreen-<index>-<n>.png`), default directory and writer, matching the 3D behavior
- **ScreenCaptureEvent** - Event posted to trigger the 3D capture; now carries an optional crop region
- **ScreenCaptureBehavior** / **RasterCrop** (lg3d-core) - Reads the canvas raster back and crops it to the event's region
- **AppConnectorPrivate** - Interface for posting events to display server

## Architecture

### UI Structure

```
ScreenCaptureConfigFrame (JFrame)
├── titlePanel (BorderLayout.NORTH)
│   └── jLabel1: "Screen Capture Configuration"
├── jPanel1 (BorderLayout.CENTER, GridBag)
│   ├── jLabel2: "File Location"
│   ├── locationTF (JTextField)
│   ├── browseButton (JButton) - currently disabled
│   ├── jLabel3: "Snapshot Delay (secs)"
│   ├── delaySpinner (JSpinner)
│   ├── areaLabel: "Capture"
│   └── areaPanel (BoxLayout Y) - areaGroup (ButtonGroup)
│       ├── fullScreenRadio: "Whole screen"        (default)
│       ├── allWindowsRadio: "All windows"
│       └── selectionRadio:  "A region I select"
└── bottomPanel (BorderLayout.SOUTH)
    ├── snapshotButton (JButton)
    └── cancelButton (JButton)
```

### Capture Flow

1. User chooses a capture area, the save directory and the delay
2. Click "Take Snapshot" button
3. Frame closes and disposes
4. TimerTask scheduled with the specified delay
5. After the delay, on the EDT, `runCapture(area, dir, desktop2d)` dispatches:
   - **SELECTION** — `RegionSelector` shows the drag overlay first; the capture
     runs once the user finishes (or is skipped if they cancel).
   - **ALL_WINDOWS** — 2D: `DesktopScreenshot.captureAllWindows` (one PNG per
     window); 3D: whole-canvas event (the scene graph renders all windows into
     one canvas).
   - **FULL_SCREEN** (default) — 2D: `DesktopScreenshot.captureFullScreen` (one
     composite PNG); 3D: whole-canvas event.
6. In 3D the `ScreenCaptureEvent` (with the region for SELECTION) is posted to
   AppConnector and `ScreenCaptureBehavior` reads/crops the raster.

## Development Guidelines

### Singleton Pattern

```java
private static WeakReference<ScreenCaptureConfigFrame> captureFrame = null;

public static ScreenCaptureConfigFrame getScreenCaptureFrame() {
    if (captureFrame == null || captureFrame.get() == null)
        captureFrame = new WeakReference(new ScreenCaptureConfigFrame());
    return captureFrame.get();
}
```

### Delayed Capture

```java
final CaptureArea area = selectedArea();
final File dir = ScreenshotFiles.ensureDirectory(saveDirectory);
final boolean desktop2d = Boolean.getBoolean(Desktop2D.MODE_PROPERTY);
TimerTask taskPerformer = new TimerTask() {
    public void run() {
        // every capture path runs on the EDT
        SwingUtilities.invokeLater(() -> runCapture(area, dir, desktop2d));
    }
};
Timer timer = new Timer();
timer.schedule(taskPerformer, snapshotDelay * 1000L);
```

### Event Posting (3D)

```java
ScreenCaptureEvent evt = new ScreenCaptureEvent(dir.getAbsolutePath());
evt.setRegion(region);   // null = whole canvas; non-null = crop to that region
AppConnectorPrivate.getAppConnector().postEvent(evt, null);
```

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| Capture Area | Whole screen | One of Whole screen / All windows / A region I select |
| Save Directory | `~/Documents/Screenshots` | Directory where snapshots are saved (created on demand; was `user.dir`) |
| Snapshot Delay | 2 seconds | Delay before capture trigger |

## Best Practices

- **WeakReference**: Use WeakReference for singleton to allow GC
- **Timer Cleanup**: Timer is not explicitly cancelled (relies on GC)
- **Directory Validation**: Check write permissions before accepting directory
- **Error Handling**: Show error dialogs for invalid directories
- **UI Feedback**: Disable snapshot button during countdown

## Dependencies

- LG3D DisplayServer: AppConnectorPrivate, ScreenCaptureEvent, Desktop2D (mode flag)
- Java Swing: JFrame, JTextField, JSpinner, JButton, JRadioButton, ButtonGroup, JFileChooser, JWindow, JDesktopPane
- Java AWT: BufferedImage, Graphics2D, Rectangle (offscreen paint + crop; no `Robot`)
- Java Util: Timer, TimerTask, WeakReference

## Known Issues

- **JFileChooser Disabled**: Browse button removed in constructor (comment: "JFileChooser does not work yet")
- **No Timer Cleanup**: Timer not cancelled if frame closed before delay
- **No Progress Feedback**: No countdown or progress indication
- **No Validation**: Directory write permissions not checked on accept

## Testing

Launch standalone:
```bash
./gradlew :lg3d-apps:run -Papp=screencapture
```

## Extension Points

- **JFileChooser**: Re-enable browse button when JFileChooser works in LG3D
- **Progress Dialog**: Show countdown dialog during delay
- **Multiple Captures**: Support burst mode or interval captures
- **Format Selection**: Allow choosing image format (PNG, JPEG)
- **Resolution**: Configure capture resolution
- **Timer Cleanup**: Cancel timer on window close
- **Foreign windows**: Enumerate composited native X11 clients (needs a
  non-`Robot` path) so "All windows" can reach beyond the desktop's own

## Known Limitations

- **External native windows**: "All windows" enumerates only the desktop's own
  Swing/3D windows; foreign X11 clients composited alongside are not captured
  (enumerating them would need `java.awt.Robot`, which cannot grab a
  rootless/Wayland display)
- JFileChooser not functional in LG3D environment
- No visual feedback during countdown
- Timer not cleaned up on premature close
- No capture format options
- No resolution configuration
- No burst/interval capture support
