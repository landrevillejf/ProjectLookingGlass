# File Manager Application

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
./gradlew :lg3d-demo-apps:run -Papp=filemanager -Pargs="/home/user/Documents"
```

Launch default (home directory):
```bash
./gradlew :lg3d-demo-apps:run -Papp=filemanager
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
