# Task Manager Application

## Overview

Task Manager is a system monitoring application that displays running processes with CPU usage information. It provides a Swing-based UI with a table showing process details and real-time CPU sampling, hosted within a 3D Frame3D using SwingNode.

## Purpose

- Monitor system processes and CPU usage
- Demonstrate real-time data updates in SwingNode
- Provide process management interface
- Showcase timer-based refresh in 3D environment

## Key Components

- **TaskManager** - Main entry point, creates Frame3D and SwingNode
- **TaskManagerPanel** - Main Swing JPanel with process table
- **ProcessTableModel** - TableModel for process listing
- **SwingNode** - Bridge between Swing and 3D scenegraph

## Architecture

### Scene Graph Structure

```
Frame3D (Task Manager)
└── SwingNode
    └── TaskManagerPanel (JPanel)
        ├── JTable (process listing)
        ├── Toolbar (refresh, kill process)
        └── Status bar
```

### Refresh Timer

The panel maintains its own two-second refresh timer:
- Updates process list every 2 seconds
- Samples CPU usage for each process
- Stops timer when window is closed

### Close Handler

Panel provides an `setOnClose(Runnable)` callback:
```java
panel.setOnClose(new Runnable() {
    @Override
    public void run() {
        frame3d.changeEnabled(false);
    }
});
```

## Development Guidelines

### Panel Dimensions

- **Native Width**: 680 pixels
- **Native Height**: 480 pixels
- Converted to physical units via `Toolkit3D.widthNativeToPhysical()`

### Process Table

Use `ProcessTableModel` for process data:
```java
ProcessTableModel model = new ProcessTableModel();
JTable table = new JTable(model);
```

### Timer Management

```java
// In TaskManagerPanel
private Timer refreshTimer;

public void startRefresh() {
    refreshTimer = new Timer(2000, new ActionListener() {
        public void actionPerformed(ActionEvent e) {
            refreshProcessList();
        }
    });
    refreshTimer.start();
}

public void stopRefresh() {
    if (refreshTimer != null) {
        refreshTimer.stop();
        refreshTimer = null;
    }
}
```

### CPU Sampling

CPU usage is sampled over time:
- Store initial CPU time
- Sample current CPU time
- Calculate percentage based on elapsed time and CPU cores

## Best Practices

- **Timer Cleanup**: Always stop timer on window close to prevent memory leaks
- **Thread Safety**: Swing updates must be on EDT (Timer handles this)
- **Performance**: Limit refresh rate for large process lists
- **Sorting**: Allow table sorting by column (PID, name, CPU, memory)
- **Killing Processes**: Confirm before terminating processes
- **Permissions**: Handle permission errors for process operations

## Dependencies

- LG3D Core: Frame3D, SwingNode, Toolkit3D
- Java Swing: JTable, TableModel, Timer, standard Swing components
- Java Lang: ProcessHandle (for process enumeration on JDK 9+)
- Java Util: List, for process data storage

## Testing

Launch standalone:
```bash
./gradlew :lg3d-demo-apps:run -Papp=taskmanager
```

## Extension Points

- **Process Actions**: Add kill, renice, suspend/resume operations
- **Filtering**: Add search/filter for process names
- **Sorting**: Implement column sorting
- **Graphs**: Add CPU history graph
- **System Info**: Display total CPU, memory usage
- **Refresh Rate**: Make refresh interval configurable
- **Process Tree**: Show parent-child relationships

## Known Limitations

- Two-second refresh interval is fixed (not configurable)
- No process killing functionality implemented
- No sorting or filtering
- No CPU history/graph
- Limited to basic process information
