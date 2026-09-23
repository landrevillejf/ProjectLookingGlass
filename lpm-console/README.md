# LPM Console

Graphical front-end for LPM (Linux Package Manager) - a Java 21 Swing application that provides a user-friendly interface for package management operations on BLFS systems.

## Overview

LPM Console is a controller application that drives LPM through its command-line interface (`/usr/bin/lpm`). It does not re-implement LPM's dependency resolution, database writes, locking, checksum verification, or transactional rollback - all critical operations are performed by LPM itself.

## Features

- **Package Management**: Install, remove, upgrade, reinstall packages
- **Package Information**: List installed packages, search database, view package details
- **Dependency Management**: View reverse dependencies, hold/unhold packages
- **System Maintenance**: Update database, clean cache, autoremove orphans
- **Integrity Verification**: Verify installed packages
- **History**: View transaction history
- **Profiles**: Manage predefined package collections
- **Kernel Operations**: Manage kernel-dependent packages, rebuild kernel
- **Build from Source**: Build packages from source or `.lpm` files
- **Dry-run Preview**: Preview all mutating operations before execution
- **Privilege Escalation**: Uses pkexec (polkit) or sudo for elevated operations
- **Progress Streaming**: Live output streaming for long-running operations

## Requirements

- **Java 21** (JDK 21 at `/opt/jdk-21` or via `JAVA_HOME`)
- **LPM 2.7.0** installed at `/usr/bin/lpm`
- **X11 display** (runs as X11 client composited by lg3d)
- **pkexec** (polkit) or **sudo** for privilege escalation

## Building

### Prerequisites

- **JDK 21** (Gradle 8.14 does not support Java 25+)
  - Install on Fedora: `sudo dnf install java-21-openjdk java-21-openjdk-devel`
  - Or set `JAVA_HOME` to JDK 21 installation
- Gradle 8.14 (wrapper included)

### Build Commands

```bash
# Set JAVA_HOME to JDK 21 if not default
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk

# Build the application
./gradlew :lpm-console:build

# The jar will be at: lpm-console/build-gradle/libs/lpm-console.jar
```

**Note**: If your system has Java 25+ as the default, you must either:
1. Install JDK 21 and set JAVA_HOME, or
2. Use a toolchain configuration in `~/.gradle/gradle.properties` to auto-provision JDK 21

## Installation

### Building the LPM Package

```bash
cd lpm-console/package
./build-package.sh
```

This creates `lpm-console-1.0.0.tar.xz` in the `lpm-console/` directory.

### Installing via LPM

```bash
# Install the package
lpm install lpm-console-1.0.0.tar.xz

# Or if the package is in a repository
lpm install lpm-console
```

The installation will:
- Copy files to `/opt/lpm-console/`
- Create launcher symlink at `/usr/bin/lpm-console`
- Install `.desktop` file at `/usr/share/applications/`
- Install polkit action at `/usr/share/polkit-1/actions/` (if pkexec available)

### Manual Installation

```bash
# Extract the package
tar -xJf lpm-console-1.0.0.tar.xz

# Copy files
cp -r opt/lpm-console /opt/
ln -s /opt/lpm-console/bin/lpm-console /usr/bin/lpm-console
cp opt/lpm-console/lpm-console.desktop /usr/share/applications/
cp opt/lpm-console/com.lpmconsole.policy /usr/share/polkit-1/actions/

# Run post-install manually
./files/post-install.sh
```

## Launching

### From lg3d Desktop

Launch LPM Console from the lg3d application launcher or menu. It will appear as a composited X11 window in the 3D scene.

### From Terminal

```bash
lpm-console
```

### Direct Java Invocation

```bash
java -jar /opt/lpm-console/lib/lpm-console.jar
```

## Usage

### Quick Actions

The main window provides quick action buttons for common operations:
- **List Packages**: Show all installed packages
- **Upgradable**: Show packages that can be upgraded
- **History**: Show recent transaction history
- **Holds**: Show held packages
- **Update DB**: Sync package database from repositories
- **Clean Cache**: Remove cached package files

### Command Execution

1. Select a command from the dropdown
2. Enter package name/arguments if required
3. Click "Execute" to run
4. For mutating operations, a dry-run preview is shown first
5. For destructive operations (remove, autoremove, upgrade), confirmation is required

### LPM Commands Mapped

| UI Command | LPM Command | Notes |
|------------|-------------|-------|
| List | `lpm --no-color list` | Show installed packages |
| Search | `lpm --no-color search <pattern>` | Search package database |
| Info | `lpm --no-color info <pkg>` | Show package details |
| Why | `lpm --no-color why <pkg>` | Show reverse dependencies |
| Install | `lpm install <pkg>` | Install package with deps |
| Remove | `lpm remove <pkg>` | Remove package |
| Update | `lpm update <pkg>` | Upgrade single package |
| Upgrade | `lpm upgrade` | Upgrade all (skips held) |
| Upgradable | `lpm --no-color upgradable` | Show upgradable packages |
| Reinstall | `lpm reinstall <pkg>` | Reinstall package |
| Autoremove | `lpm autoremove` | Remove orphan packages |
| Hold | `lpm hold <pkg>` | Pin package version |
| Unhold | `lpm unhold <pkg>` | Unpin package |
| Holds | `lpm holds` | List held packages |
| History | `lpm --no-color history [N]` | Show transaction history |
| Verify | `lpm verify [pkg]` | Check package integrity |
| Update DB | `lpm update-db` | Sync database |
| Clean | `lpm clean` | Clean cache |
| List Profiles | `lpm --no-color list-profiles` | Show profiles |
| Add Profile | `lpm add-profile <prof>` | Install profile |
| Kernel Deps | `lpm --no-color kernel-deps [--all]` | Show kernel deps |
| Rebuild Kernel | `lpm rebuild-kernel` | Rebuild kernel |
| Build | `lpm build <source>` | Build from source |
| Version | `lpm version` | Show LPM version |
| Help | `lpm help` | Show help |

## Architecture

### Design Principles

1. **Front-end Only**: All operations go through `/usr/bin/lpm` - no re-implementation of LPM logic
2. **No Direct DB Access**: Reads may use DB files for speed, but all writes go through LPM
3. **Concurrency Control**: Only one LPM operation at a time, respects `/var/lock/lpm.lock`
4. **Privilege Separation**: GUI runs unprivileged, escalates per-operation via pkexec/sudo
5. **Error Fidelity**: Surfaces LPM's verbatim error messages
6. **Dry-run First**: All mutating operations show preview before execution

### Components

- **LPMConsole**: Main Swing UI
- **LPMExecutor**: Executes LPM commands with proper argument handling
- **LPMCommand**: Enum of all supported LPM commands
- **OperationResult**: Result of command execution
- **PrivilegeEscalator**: Handles pkexec/sudo escalation
- **LPMExecutionException**: Exception for LPM failures

### Concurrency

- Uses a single lock object to serialize LPM operations
- Disables UI controls during operations
- Detects and handles "Another lpm instance is running" errors
- Offers retry affordance when lock is held

### Privilege Escalation

- Read-only operations run unprivileged
- Mutating operations escalate via pkexec (preferred) or sudo
- Polkit policy file: `com.lpmconsole.policy`
- GUI process never runs as root

## Compliance with Contract

This implementation complies with the LPM Control Application Contract:

- ✅ All required LPM commands implemented
- ✅ Dry-run preview for all mutating operations
- ✅ Confirmation for destructive operations
- ✅ Always passes `--no-color` to LPM
- ✅ Captures stdout and stderr separately
- ✅ Uses canonical command names (no aliases)
- ✅ Handles non-zero exit codes correctly
- ✅ Surfaces LPM's verbatim error messages
- ✅ Serializes LPM calls (respects lock)
- ✅ Privilege escalation via pkexec/sudo
- ✅ Long-running operations off EDT with streaming
- ✅ Runs as X11 client (no WM/compositor)
- ✅ Delivered as LPM package with hooks
- ✅ Includes .desktop file and polkit action

## Troubleshooting

### LPM Not Found

If the app shows "LPM is not available at /usr/bin/lpm":
- Ensure LPM is installed: `which lpm`
- Install LPM if missing (see BLFS stage 19)

### Lock Errors

If you see "Another lpm instance is running":
- Another LPM process is active (terminal or another UI)
- Wait for it to complete or terminate it
- Use the retry button when prompted

### Privilege Escalation Fails

If pkexec/sudo prompts fail:
- Ensure your user has sudo/polkit permissions
- Check polkit policy is installed: `/usr/share/polkit-1/actions/com.lpmconsole.policy`
- Verify pkexec is available: `which pkexec`

### Display Issues

If the window doesn't appear in lg3d:
- Ensure DISPLAY is set: `echo $DISPLAY`
- Verify lg3d is running on `:0`
- Check X11 client libraries are installed

## Development

### Project Structure

```
lpm-console/
├── build.gradle              # Gradle build configuration
├── src/
│   ├── classes/
│   │   └── org/lpmconsole/
│   │       ├── LPMConsole.java           # Main UI
│   │       ├── LPMExecutor.java          # Command executor
│   │       ├── LPMCommand.java           # Command enum
│   │       ├── OperationResult.java      # Result wrapper
│   │       ├── PrivilegeEscalator.java   # Escalation handler
│   │       └── LPMExecutionException.java # Exception class
│   └── resources/                         # Resources (icons, etc.)
└── package/
    ├── build-package.sh                  # Package build script
    ├── lpm-console.info                  # Package metadata
    ├── post-install.sh                   # Post-install hook
    ├── post-remove.sh                    # Post-remove hook
    ├── lpm-console.desktop               # Desktop entry
    └── com.lpmconsole.policy             # Polkit action
```

### Running in Development

```bash
# Run directly from Gradle
./gradlew :lpm-console:run

# Or run the built jar
java -jar build-gradle/libs/lpm-console.jar
```

## License

GPL-3.0

## References

- [LPM Control Application Contract](../lpm-lg3d-app-contract.md)
- [LFS X11 Contract](../lfs-x11-contract.md)
- [LPM Documentation](../docs/lpm.md)
- [LPM Architecture](../docs/LPM_DOCUMENTATION.md)
