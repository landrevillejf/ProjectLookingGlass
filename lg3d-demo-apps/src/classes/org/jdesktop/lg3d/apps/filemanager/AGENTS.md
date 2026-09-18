# File Manager Application

## Overview

File Manager is a 100% lg3d-native 3D file browser (no SwingNode, no Swing
widgets): a `Frame3D` built entirely from the shared glassy widget kit in
`org.jdesktop.lg3d.apps.uikit` (translucent `GlassyPanel`s, `GlassyText2D`
labels, `Button3D`, `ScrollList3D`), following the same pure-3D vocabulary as
Image Studio.

## Purpose

- Provide file system navigation as a native 3D desktop citizen
- Demonstrate the shared `uikit` widget kit (buttons, wheel-scrolled lists)
- Integrate with dock stacks (Documents/Downloads) via the initial-dir argument

## Key Components

- **FileManager** - Main entry point (`java ...filemanager.FileManager [dir]`),
  constructs `FileManagerFrame3D` and calls `changeEnabled/changeVisible(true)`
- **FileManagerFrame3D** - The whole UI: toolbar, path line, listing, status
- **uikit** (`org.jdesktop.lg3d.apps.uikit`) - Shared widgets:
  - `Ui3D` - factory helpers: colours, panels, labels, Component3D wrapping
  - `Button3D` - glassy push button (hover highlight, `setText`, `setLit`,
    `setEnabled`)
  - `ScrollList3D` - wheel-scrollable viewport over caller-built rows; only
    visible rows are shown/pickable
  - `Gauge3D` - horizontal fill gauge (used by Control Center)

## Architecture

### Scene Graph Structure
```
FileManagerFrame3D (Frame3D + standard decoration)
├── Component3D (window backdrop GlassyPanel)
├── Component3D (title label)
├── Button3D x7 (Back, Fwd, Up, Home, Refresh, Open, Delete)
├── Component3D (path line GlassyText2D)
├── ScrollList3D (file rows: type chip + name + size/kind)
└── Component3D (status line GlassyText2D)
```

### Window Sizing / Maximize

The preferred size matches the usable screen aspect
(`screenH - Taskbar.getReservedBottomHeight()`) so the decoration's
aspect-preserving maximize fills the viewport on both axes.

### Command-Line Integration

```bash
java org.jdesktop.lg3d.apps.filemanager.FileManager /path/to/directory
```

- Directory argument: opens at that location
- File argument: opens its parent directory
- No argument: opens `user.home`
- Leading spaces are trimmed

### Behaviour Notes

- Click a folder row to navigate into it; click a file row to select it
- Open hands the selection to `Opener` (xdg-open via the desktop)
- Delete is a two-click confirm (button retitles to "Confirm?", 4s disarm)
  and moves the file to the trash via `Opener.trash`
- Listing is capped at 400 rows; the wheel scrolls the `ScrollList3D`
- There is no native text input, so rename/new-folder are not offered

## Dependencies

- LG3D Core: `Frame3D`, `Component3D`, `GlassyPanel`, `GlassyText2D`,
  event adapters/actions, `Opener`, `Taskbar` (reserved height)
- `org.jdesktop.lg3d.apps.uikit` (shared with Task Manager / Control Center)
- Java NIO (`Files.list`, `Path`)

## Testing

Launch from the desktop Start Menu (System group) or:
```bash
./gradlew :lg3d-demo-apps:run -Papp=filemanager
```

## Extension Points

- **Row kinds**: extend `makeRow` with icons/permissions columns
- **Operations**: add copy/move via drag between two FileManagerFrame3D windows
- **uikit reuse**: new native apps should build on `uikit` rather than Swing
