# Launcher Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Functional tool** (application launcher creator) — now fully functional |
| Entry point | `LauncherFrame.main` (NetBeans-generated Swing frame) |
| Surface | **2D Swing** frame; launches apps via `AppLaunchAction`; drag the icon onto the 2D taskbar's quick-launch strip to pin it |
| Start-menu name / group | Application Launcher — available in both 2D and 3D desktops |
| Command | `java org.jdesktop.lg3d.apps.launcher.LauncherFrame` |
| Descriptor | `src/config/launcher.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

**Components:** `LauncherFrame` (NetBeans-generated) + `ApplicationDescription` +
`AppLaunchAction` + `LauncherSaver` (saves user launchers to `~/.config/lg3d/launchers/`)
+ `QuickLaunchDrag` (drag the built launcher onto the 2D taskbar's quick-launch
strip). Icon picking, save and drag-to-quick-launch are **now implemented**.

## Roles

- **Architect** — A functional launcher creator built on `ApplicationDescription` +
  `AppLaunchAction` (the same launch primitives the real start menu uses). It
  saves user-created launchers to `~/.config/lg3d/launchers/` which are
  automatically discovered by the desktop's start menu.
- **Engineer / Developer** — The frame is NetBeans-generated Swing (`LauncherFrame` +
  `.form`); regenerate rather than hand-editing generated blocks. Launch through
  `AppLaunchAction` on the EDT. Icon selection (JFileChooser) and save
  (LauncherSaver) are now fully implemented. Jogamp packages only where 3D is used.
- **QA** — Verify the frame opens, that icon selection works, that save creates a
  valid .lgcfg file in `~/.config/lg3d/launchers/`, and that the saved launcher
  appears in the start menu after a desktop restart. On a live X display, verify
  dragging the icon onto the 2D taskbar's quick-launch strip pins it (the strip
  highlights while an acceptable drag hovers); headless, `QuickLaunchDragTest`
  covers the payload builder and the transferable.
- **Business Analyst** — Functional tool for users to create custom application
  launchers. Saved launchers persist across desktop sessions and are discoverable
  in the start menu.
- **Functional Analyst** — Spec as a functional tool: users can specify name,
  description, command, icon, and menu group; the launcher is saved as a .lgcfg
  file and automatically discovered by the desktop. A launcher can also be dragged
  from the frame's icon onto the 2D taskbar's quick-launch strip to pin it right
  away — no restart, no start-menu round-trip — through the shared same-JVM
  `Desktop2D.QUICK_LAUNCH_FLAVOR` (the strip is the drop target).
- **Project Manager** — Commit scope `lg3d-apps`. Medium priority; useful utility.
  Branch → PR against `main`.
- **UI/UX (3D & 2D)** — **2D** Swing launcher frame. Keep it consistent with the
  platform LAF. The icon button doubles as a drag handle onto the taskbar's
  quick-launch strip (its tooltip advertises this); a drag with a blank command
  starts nothing, so the button stays a plain click target until there is
  something to pin.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

## Overview

Launcher is a Swing-based application creator tool that allows users to define and launch custom application launchers. It provides a form interface for specifying launcher properties (name, description, command, classpath, icon) and can launch applications via AppLaunchAction.

## Purpose

- Create custom application launchers for LG3D
- Demonstrate AppLaunchAction integration
- Provide GUI for launcher configuration
- Test application launching mechanisms

## Key Components

- **LauncherFrame** - Main JFrame with form UI (NetBeans-generated)
- **ApplicationDescription** - Launcher metadata container
- **AppLaunchAction** - Executes application launch commands

## Architecture

### UI Structure

```
LauncherFrame (JFrame)
└── jPanel1 (GridBagLayout)
    ├── jPanel2 (Launcher properties)
    │   ├── jLabel1: "Name"
    │   ├── launcherName (JTextField)
    │   ├── jLabel2: "Description"
    │   ├── launcherDescription (JTextField)
    │   ├── jLabel3: "Command"
    │   ├── launcherCommand (JTextField)
    │   ├── jLabel4: "Classpath"
    │   └── launcherClasspath (JTextField)
    ├── jPanel4 (Icon selection)
    │   └── launcherIcon (JButton)
    └── jPanel3 (Action buttons)
        ├── launcherLaunch (JButton)
        ├── launcherSave (JButton)
        └── launcherCancel (JButton)
```

### Launch Flow

1. User fills in launcher properties
2. Click "Launch" button
3. ApplicationDescription populated from form fields
4. Classpath jars parsed (if provided)
5. AppLaunchAction created with command and classloader
6. Application launched via `performAction(null)`

## Development Guidelines

### ApplicationDescription

```java
ApplicationDescription appDesc = new ApplicationDescription();
appDesc.setName(launcherName.getText());
appDesc.setDescription(launcherDescription.getText());

// Optional: set classpath
if (!launcherClasspath.getText().isEmpty()) {
    appDesc.setClasspathJars(launcherClasspath.getText());
}
```

### AppLaunchAction

```java
AppLaunchAction appLaunch = new AppLaunchAction(
    launcherCommand.getText(),
    appDesc.getClassLoader()
);
appLaunch.performAction(null);
```

### Form Fields

| Field | Purpose | Format |
|-------|---------|--------|
| Name | Launcher display name | String |
| Description | Launcher description | String |
| Command | Java command to execute | e.g., "java org.jdesktop.lg3d.apps.MyApp" |
| Classpath | Additional JARs for classpath | Colon-separated paths |
| Icon | Launcher icon (TODO) | Image file path |

## Best Practices

- **Command Format**: Use full class names with package
- **Classpath**: Provide absolute paths or relative to working directory
- **Validation**: Validate command format before launching
- **Error Handling**: Catch IOException from setClasspathJars
- **Icon Path**: Currently hardcoded - needs file chooser implementation

## Dependencies

- LG3D Utils: AppLaunchAction, ApplicationDescription
- LG3D SceneManager: ApplicationDescription
- Java Swing: JFrame, JTextField, JButton, standard Swing components
- Java IO: IOException for classpath parsing

## Resources

- Icon: `resources/images/icon/launcher.png` (currently hardcoded path)

## Testing

Launch standalone:
```bash
./gradlew :lg3d-apps:run -Papp=launcher
```

## Extension Points

- **Icon Selection**: Implement JFileChooser for icon selection
- **Save Functionality**: Implement launcher persistence (XML/JSON)
- **Load Functionality**: Load saved launcher configurations
- **Validation**: Add form validation before launch
- **Recent Launchers**: Maintain history of recent launches
- **Template System**: Pre-defined launcher templates

## Known Limitations

- NetBeans-generated form code (do not modify manually)
- Classpath field is not used by the save function (saved .lgcfg files use the command field only)
- No load functionality to edit existing launchers
- No template system for common launcher types
- Drag-to-pin targets the **2D** taskbar's quick-launch strip only; the 3D
  taskbar's shortcut shelf is not a drop target. A custom picked icon file is not
  shown on the strip (the 2D desktop resolves app icons by name via `AppIcons`).
