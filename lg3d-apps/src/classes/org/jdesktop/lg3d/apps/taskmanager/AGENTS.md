# Task Manager Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (process monitor) |
| Entry point | `TaskManager.main` → `TitledSwingWindow.show(...)` hosting `TaskManagerPanel` |
| Surface | **SwingNode-in-Frame3D** (680x480); the same panel is reused in the 2D desktop |
| Start-menu name / group | Task Manager / **System** |
| Command | `java org.jdesktop.lg3d.apps.taskmanager.TaskManager` |
| Descriptor | `src/config/taskmanager.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

**Components:** `TaskManagerPanel` (Swing UI + 2s refresh `Timer`) +
`ProcessTableModel` (JDK 9+ `ProcessHandle`).

## Roles

- **Architect** — Production process monitor hosted through `TitledSwingWindow`/
  `SwingNode`; keep the panel plain Swing so it drives both desktops. The panel owns
  its refresh `Timer` and exposes `setOnClose(Runnable)` so the host can disable the
  `Frame3D` — preserve that lifecycle seam.
- **Engineer / Developer** — Follow the core UI/UX rulebook: offscreen SwingNode
  paint, no modal dialogs (in-panel confirm before kill), EDT hops from lg3d
  listeners, `dispose()` on discard, Metal LAF via `installHostedLookAndFeel`.
  **Always stop the refresh timer on close** (leak guard); sample CPU off the EDT.
  Jogamp packages only.
- **QA** — Unit-test `ProcessTableModel`/CPU-percentage math headless (no real
  process killing in CI). Verify the hosted window and 2s refresh with the in-JVM
  probe + internal screencapture; a black host capture under Wayland is not a defect.
- **Business Analyst** — A shipped system utility (view and control running
  processes). Production standards apply; note process kill is a sensitive action.
- **Functional Analyst** — Spec user-visible function (list, refresh, CPU/memory
  columns, kill) plus the contract with core. Known gaps: fixed 2s interval, kill
  not yet implemented, no sort/filter — track these as backlog, not defects.
- **Project Manager** — Commit scope `lg3d-apps`. Done = build +
  `./run-lg3d.sh` + capture/log evidence in the PR. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the Swing process table/toolbar panel, identical in the 2D desktop.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

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
./gradlew :lg3d-apps:run -Papp=taskmanager
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
