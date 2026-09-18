# Launcher Application

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
