# Task Manager Application

## Overview

Task Manager is a 100% lg3d-native 3D process monitor (no SwingNode, no Swing
widgets): a `Frame3D` built from the shared glassy widget kit in
`org.jdesktop.lg3d.apps.uikit`, backed by the core `ProcessService`
(/proc + `ps` parsing).

## Purpose

- Live process/CPU/memory monitoring as a native 3D desktop citizen
- Process signalling (SIGTERM / SIGKILL) through `ProcessService`
- Demonstrate gated periodic refresh in a pure-3D app

## Key Components

- **TaskManager** - Main entry point (`java ...taskmanager.TaskManager`),
  constructs `TaskManagerFrame3D` and calls `changeEnabled/changeVisible(true)`
- **TaskManagerFrame3D** - The whole UI: aggregate header, sort/action
  buttons, column header, process list, status line
- **uikit** (`org.jdesktop.lg3d.apps.uikit`) - Shared widgets (`Ui3D`,
  `Button3D`, `ScrollList3D`, `Gauge3D`), also used by File Manager and
  Control Center

## Architecture

### Scene Graph Structure
```
TaskManagerFrame3D (Frame3D + standard decoration)
├── Component3D (window backdrop GlassyPanel)
├── Component3D (title + aggregate header GlassyText2D)
├── Button3D x6 (By CPU, By MEM, By Name, End Task, Force Quit, Refresh)
├── Component3D (column header labels)
├── ScrollList3D (process rows: pid+name | cpu, memory, state)
└── Component3D (status line GlassyText2D)
```

### Refresh + Threading Model

- A `javax.swing.Timer` (2s) triggers `refresh()`; sampling runs on a daemon
  thread (`ProcessService.snapshot()` + `systemLoad()`) and results are
  applied on the EDT via `SwingUtilities.invokeLater` - the scene graph is
  never touched from the sampler thread.
- `setEnabled`/`setVisible` overrides gate the timer, so a minimized or
  closed window stops polling /proc (`isEnabled() && isVisible()`).
- A `refreshing` flag drops overlapping ticks.

### Window Sizing / Maximize

Preferred size matches the usable screen aspect
(`screenH - Taskbar.getReservedBottomHeight()`) so the decoration's
aspect-preserving maximize fills the viewport on both axes.

### Behaviour Notes

- Sort modes: CPU (desc), memory (desc), name (case-insensitive); the active
  sort button stays lit (`Button3D.setLit`)
- Click a row to select (highlighted via appearance swap); End Task sends
  SIGTERM, Force Quit SIGKILL through `ProcessService` (with its pkexec
  fallback for foreign processes); the result (`TerminateResult.getKind()`)
  is shown in the status line
- Listing is capped at 300 rows after sorting; the wheel scrolls

## Dependencies

- LG3D Core: `Frame3D`, `Component3D`, `GlassyPanel`, `GlassyText2D`, event
  adapters/actions, `ProcessService`, `Taskbar` (reserved height)
- `org.jdesktop.lg3d.apps.uikit`

## Testing

Launch from the desktop Start Menu (System group) or:
```bash
./gradlew :lg3d-demo-apps:run -Papp=taskmanager
```

## Extension Points

- **Graphs**: add a CPU-history strip using `Gauge3D`/textured quads
- **Filtering**: no native text input yet; a letter-chip filter row is the
  native-friendly approach
- **Refresh rate**: make `REFRESH_MS` configurable
