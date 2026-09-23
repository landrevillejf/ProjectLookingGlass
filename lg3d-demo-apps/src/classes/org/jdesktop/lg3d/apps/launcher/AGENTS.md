# Launcher Application

> Role-aware per-app guide. Module: [`lg3d-demo-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Sample / demo** (application launcher) — incomplete, not a shipped utility |
| Entry point | `LauncherFrame.main` (NetBeans-generated Swing frame) |
| Surface | **2D Swing** frame; launches apps via `AppLaunchAction` |
| Start-menu name / group | Application Launcher — an `ApplicationDescription` **taskbar** entry, not a start-menu item |
| Command | `java org.jdesktop.lg3d.apps.launcher.LauncherFrame` |
| Descriptor | `src/config/launcher.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-demo-apps:build` |

**Components:** `LauncherFrame` (NetBeans-generated) + `ApplicationDescription` +
`AppLaunchAction`. Icon picking and save are **not implemented**.

## Roles

- **Architect** — An early launcher prototype built on `ApplicationDescription` +
  `AppLaunchAction` (the same launch primitives the real start menu uses). It is a
  reference for wiring a launch action, not the production launcher.
- **Engineer / Developer** — The frame is NetBeans-generated Swing (`LauncherFrame` +
  `.form`); regenerate rather than hand-editing generated blocks. Launch through
  `AppLaunchAction` on the EDT. Icon/save are stubs — do not assume they persist.
  Jogamp packages only where 3D is used.
- **QA** — Verify the frame opens and that a configured entry triggers
  `AppLaunchAction` (in-JVM probe + internal screencapture). Icon/save are known
  unimplemented gaps, not defects to file.
- **Business Analyst** — Demonstration/prototype value only; superseded by the
  desktop's real start menu. No end-user product surface.
- **Functional Analyst** — Spec as a prototype (list apps, click to launch). Record
  icon-picking and save as explicit *not implemented* so nobody assumes them.
- **Project Manager** — Commit scope `lg3d-demo-apps`. Low priority; opportunistic.
  Branch → PR against `main`.
- **UI/UX (3D & 2D)** — **2D** Swing launcher frame. Keep it consistent with the
  platform LAF; it is a prototype, so polish is not expected.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-demo-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
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
./gradlew :lg3d-demo-apps:run -Papp=launcher
```

## Extension Points

- **Icon Selection**: Implement JFileChooser for icon selection
- **Save Functionality**: Implement launcher persistence (XML/JSON)
- **Load Functionality**: Load saved launcher configurations
- **Validation**: Add form validation before launch
- **Recent Launchers**: Maintain history of recent launches
- **Template System**: Pre-defined launcher templates

## Known Limitations

- Icon selection not functional (button present but not implemented)
- Save button not implemented
- No launcher persistence
- Hardcoded icon path
- No validation of command format
- No error feedback on launch failure
- NetBeans-generated form code (do not modify manually)
