# File Manager Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (file browser) |
| Entry point | `FileManager.main` → `TitledSwingWindow.show(...)` hosting `FileManagerPanel` |
| Surface | **SwingNode-in-Frame3D** (760x500); the same panel is reused in the 2D desktop |
| Start-menu name / group | File Manager / **System** |
| Command | `java org.jdesktop.lg3d.apps.filemanager.FileManager` (accepts an initial-dir arg) |
| Descriptor | `src/config/filemanager.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

**Components:** `FileManagerPanel` (Swing UI) + `FileTableModel` + `FileOperations`.

## Roles

- **Architect** — Production file browser hosted through `TitledSwingWindow`/
  `SwingNode`; keep the panel plain Swing so it drives both desktops. The
  initial-directory argument is the integration seam used by dock folder stacks
  (`FolderStackPopup.openInFileManager`) — preserve that contract.
- **Engineer / Developer** — Follow the core UI/UX rulebook: offscreen SwingNode
  paint (null layout + explicit bounds), no modal dialogs (in-panel overlays for
  rename/delete/confirm), EDT hops from lg3d listeners, `dispose()` on discard,
  Metal LAF via `installHostedLookAndFeel`. Keep blocking file I/O off the EDT.
  Jogamp packages only.
- **QA** — Unit-test `FileTableModel`/`FileOperations` logic headless. Verify the
  hosted window (and the open-in-file-manager handoff from a dock stack) with the
  in-JVM probe + internal screencapture; a black host capture under Wayland is not a
  defect. Read the log for `EventProcessor` warnings first.
- **Business Analyst** — A shipped system utility (browse and manage files and
  folders) and the target of the dock "open in file manager" flow. Production
  standards apply.
- **Functional Analyst** — Spec user-visible function (navigate, sort, open, file
  operations) plus the contract with core (SwingNode surface, initial-dir argument,
  descriptor fields).
- **Project Manager** — Commit scope `lg3d-apps`. Done = build +
  `./run-lg3d.sh` + capture/log evidence in the PR. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the Swing file table/toolbar panel, identical in the 2D desktop.
  Verify overlay ordering over a maximized window.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

## Overview

File Manager is a 3D file browser that provides a Swing-based UI for navigating and managing the filesystem. It demonstrates hosting a complex Swing application (JTable with file operations) within a 3D Frame3D using SwingNode.

## Purpose

- Provide file system navigation in 3D environment
- Demonstrate complex Swing UI integration
- Support file operations (copy, move, delete, rename)
- Integrate with dock stacks (Documents/Downloads)

## Key Components

- **FileManager** - Main entry point, creates Frame3D and SwingNode
- **FileManagerPanel** - Main Swing JPanel with file browser UI
- **FileTableModel** - Table model for file listing
- **FileOperations** - File operation implementations (copy, move, delete)
- **SwingNode** - Bridge between Swing and 3D scenegraph

## Architecture

### Scene Graph Structure
```
Frame3D (File Manager)
└── SwingNode
    └── FileManagerPanel (JPanel)
        ├── JTable (file listing)
        ├── Toolbar (navigation buttons)
        └── Status bar
```

### Command-Line Integration

The application accepts an optional initial directory argument:
```bash
java org.jdesktop.lg3d.apps.filemanager.FileManager /path/to/directory
```

- If argument is a directory: opens at that location
- If argument is a file: opens its parent directory
- No argument: opens user home directory
- Arguments may have leading spaces (trimmed before use)

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

- **Native Width**: 760 pixels
- **Native Height**: 500 pixels
- Converted to physical units via `Toolkit3D.widthNativeToPhysical()`

### File Operations

Use `FileOperations` class for common operations:
- `copy(Path source, Path target)`
- `move(Path source, Path target)`
- `delete(Path path)`
- `rename(Path path, String newName)`

### Directory Parsing

```java
private static Path parseInitialDir(String[] args) {
    // Trim and validate arguments
    // Check if path is directory
    // If file, use parent directory
    // Default to user.home
}
```

## Best Practices

- **Path Handling**: Use `java.nio.file.Path` for modern file I/O
- **Error Handling**: Show user-friendly error dialogs for failed operations
- **Performance**: Lazy-load file listings for large directories
- **Thread Safety**: File operations should run off-EDT to prevent UI freeze
- **Permissions**: Check file permissions before attempting operations

## Dependencies

- LG3D Core: Frame3D, SwingNode, Toolkit3D
- Java NIO: Path, Paths, Files (modern file I/O)
- Java Swing: JTable, TableModel, standard Swing components

## Testing

Launch with specific directory:
```bash
./gradlew :lg3d-apps:run -Papp=filemanager -Pargs="/home/user/Documents"
```

Launch default (home directory):
```bash
./gradlew :lg3d-apps:run -Papp=filemanager
```

## Integration Points

- **Dock Stacks**: Documents and Downloads dock stacks use "Open folder" action
- **Start Menu**: Launches with no arguments (opens home directory)
- **File Associations**: Could be extended to open specific file types

## Known Limitations

- No file search functionality
- No bookmark/favorites support
- Limited to local filesystem (no network mounts)
- No thumbnail preview for images
