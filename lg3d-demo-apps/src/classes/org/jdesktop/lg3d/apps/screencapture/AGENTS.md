# Screen Capture Application

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
./gradlew :lg3d-demo-apps:run -Papp=screencapture
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
