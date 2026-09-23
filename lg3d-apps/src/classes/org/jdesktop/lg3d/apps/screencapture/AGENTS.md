# Screen Capture Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (screen snapshot) |
| Entry point | `ScreenCaptureConfigFrame.main` (a Swing `JFrame` config dialog) |
| Surface | **2D Swing config frame**; capture itself is a desktop event, not a 3D window |
| Start-menu name / group | Screen Snapshot / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.screencapture.ScreenCaptureConfigFrame` |
| Descriptor | `src/config/screencapture.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

## Roles

- **Architect** — Decoupled by design: the config frame collects the delay/target
  and then **posts a `ScreenCaptureEvent` via `AppConnectorPrivate`** after a `Timer`
  delay; the actual capture is performed by the desktop, not by this app. Keep the
  event-based seam (do not capture in-process). It is held as a `WeakReference`
  singleton so repeated launches reuse one frame.
- **Engineer / Developer** — This is a conventional Swing `JFrame` (real layout,
  EDT). Post the event, then let the timer fire; do not block the EDT. The
  `JFileChooser` save path is intentionally disabled for now — do not wire it to a
  blocking dialog without revisiting the capture flow. Jogamp packages only where 3D.
- **QA** — Verify by launching the desktop, triggering Screen Snapshot, and checking
  that the internal screencapture (`lg3d-core/lgscreen-*.png`) is produced after the
  configured delay. A black *host* capture under Wayland is not a defect — use lg3d's
  internal capture. Unit-test the delay/config logic headless where possible.
- **Business Analyst** — A shipped utility (take a screen snapshot). It is also the
  mechanism the project itself relies on for UI verification evidence, so reliability
  matters. Production standards apply.
- **Functional Analyst** — Spec user-visible function (choose delay → capture fires
  → snapshot saved) plus the event contract with core (`ScreenCaptureEvent`). Note
  the disabled file-chooser as a known limitation.
- **Project Manager** — Commit scope `lg3d-apps`. Done = build + `./run-lg3d.sh`
  + a captured snapshot as evidence. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — **2D** only: a small Swing config frame. Keep it simple and
  consistent with the platform LAF; the snapshot output is the real UX artifact.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

## Overview

Screen Capture is a configuration tool for taking timed snapshots of the LG3D desktop. It provides a Swing UI for specifying save directory and delay, then posts a ScreenCaptureEvent to trigger the capture after the specified delay.

## Purpose

- Configure screen capture parameters
- Trigger delayed screen snapshots
- Demonstrate event posting to display server
- Provide UI for capture settings

## Key Components

- **ScreenCaptureConfigFrame** - Main JFrame with configuration UI
- **ScreenCaptureEvent** - Event posted to trigger capture
- **AppConnectorPrivate** - Interface for posting events to display server

## Architecture

### UI Structure

```
ScreenCaptureConfigFrame (JFrame)
├── titlePanel (BorderLayout.NORTH)
│   └── jLabel1: "Screen Capture Configuration"
├── jPanel1 (BorderLayout.CENTER)
│   ├── jLabel2: "File Location"
│   ├── locationTF (JTextField)
│   ├── browseButton (JButton) - currently disabled
│   ├── jLabel3: "Snapshot Delay (secs)"
│   └── delaySpinner (JSpinner)
└── bottomPanel (BorderLayout.SOUTH)
    ├── snapshotButton (JButton)
    └── cancelButton (JButton)
```

### Capture Flow

1. User configures save directory and delay
2. Click "Take Snapshot" button
3. Frame closes and disposes
4. TimerTask scheduled with specified delay
5. After delay, ScreenCaptureEvent posted to AppConnector
6. Display server handles actual capture

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
TimerTask taskPerformer = new TimerTask() {
    public void run() {
        AppConnectorPrivate.getAppConnector().postEvent(
            new ScreenCaptureEvent(saveDirectory.getAbsolutePath()),
            null
        );
    }
};
Timer timer = new Timer();
timer.schedule(taskPerformer, snapshotDelay * 1000);
```

### Event Posting

```java
AppConnectorPrivate.getAppConnector().postEvent(
    new ScreenCaptureEvent(saveDirectory.getAbsolutePath()),
    null
);
```

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| Save Directory | `user.dir` | Directory where snapshots are saved |
| Snapshot Delay | 2 seconds | Delay before capture trigger |

## Best Practices

- **WeakReference**: Use WeakReference for singleton to allow GC
- **Timer Cleanup**: Timer is not explicitly cancelled (relies on GC)
- **Directory Validation**: Check write permissions before accepting directory
- **Error Handling**: Show error dialogs for invalid directories
- **UI Feedback**: Disable snapshot button during countdown

## Dependencies

- LG3D DisplayServer: AppConnectorPrivate, ScreenCaptureEvent
- Java Swing: JFrame, JTextField, JSpinner, JButton, JFileChooser
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

## Known Limitations

- JFileChooser not functional in LG3D environment
- No visual feedback during countdown
- Timer not cleaned up on premature close
- No capture format options
- No resolution configuration
- No multiple capture support
