# SwingTest Application

> Role-aware per-app guide. Module: [`lg3d-demo-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Sample / test** (plain Swing harness) — not shipped |
| Entry point | `TestFrame.main` (NetBeans-generated Swing `JFrame`) |
| Surface | **2D Swing** `JFrame` (conventional widgets), brought into the desktop by window capture |
| Start-menu name / group | Swing Test / **Tests** |
| Command | `java org.jdesktop.lg3d.apps.swingtest.TestFrame` |
| Descriptor | `src/config/swingtest.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-demo-apps:build` |

**Components:** `TestFrame` (NetBeans-generated) + `DialogPanel` + `MyLabel` +
`MyTextField` — ordinary Swing widgets used to exercise hosting/capture.

## Roles

- **Architect** — A minimal conventional-Swing harness used to exercise how a plain
  `JFrame` is captured into the desktop. It is a test fixture, not a window pattern to
  copy for production apps (those use `TitledSwingWindow`/`SwingNode`).
- **Engineer / Developer** — The frame is NetBeans-generated (`TestFrame` + `.form`);
  regenerate rather than hand-editing generated blocks. All work is on the EDT. Keep
  it dependency-free. Jogamp packages only where 3D is used (none expected here).
- **QA** — Verify the frame is created and captured into the desktop (in-JVM probe +
  internal screencapture); `SwingAppLauncher` logs only on failure, so silence means
  it came up. A black host capture under Wayland is not a defect.
- **Business Analyst** — Test fixture under **Tests**; no end-user product value.
- **Functional Analyst** — Spec as a harness (show a frame with a label, text field,
  dialog panel). No product behaviour to define.
- **Project Manager** — Commit scope `lg3d-demo-apps`. Low priority; opportunistic.
  Branch → PR against `main`.
- **UI/UX (3D & 2D)** — **2D** only: a plain Swing frame. Keep it simple; it exists
  to validate capture/hosting, not to look polished.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-demo-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

## Overview

SwingTest is a comprehensive Swing component testing application that validates LG3D's Swing integration. It provides a form with various Swing components (buttons, checkboxes, text fields, combo boxes, tabbed panes, dialogs) to test event handling, rendering, and interaction in the 3D environment.

## Purpose

- Test Swing component compatibility with LG3D
- Validate event handling (mouse, focus, keyboard)
- Demonstrate dialog functionality
- Test custom components (MyLabel, MyTextField)
- Verify screen size and location reporting

## Key Components

- **TestFrame** - Main JFrame with test UI (NetBeans-generated)
- **DialogPanel** - Test dialog content
- **MyLabel** - Custom JLabel subclass
- **MyTextField** - Custom JTextField subclass

## Architecture

### UI Structure

```
TestFrame (JFrame)
└── jPanel3 (GridBagLayout)
    ├── jPanel2 (Dialog test)
    │   ├── showDialogB (JButton)
    │   └── outsideLG (JRadioButton) - disabled
    ├── jPanel1 (Component test)
    │   ├── updateLocationButton (JButton)
    │   ├── jLabel1 (MyLabel)
    │   ├── moveWindowB (JButton)
    │   ├── testCB (JCheckBox)
    │   ├── testTF (MyTextField)
    │   ├── jComboBox1 (JComboBox) - disabled
    │   ├── jLabel2: "Screen Size"
    │   ├── screenSizeTF (JTextField)
    │   ├── jLabel3: "Location"
    │   └── windowLocationTF (JTextField)
    ├── jPanel4 (Tabbed pane test)
    │   └── jTabbedPane1
    │       └── jScrollPane1
    │           └── jTextArea1
    └── jPanel5 (Event feedback)
        ├── jLabel4: "Last Event:"
        └── messageTF (JTextField)
```

## Test Scenarios

### Dialog Test
- Click "Show Dialog" to open JDialog
- Dialog contains DialogPanel
- Tests dialog creation and display

### Component Test
- **Update Location**: Displays window location in text field
- **Move Window**: Toggles window position (0,0) ↔ (50,50)
- **Checkbox**: Logs checkbox state changes
- **Text Field**: Custom MyTextField for testing
- **ComboBox**: Disabled (placeholder for future testing)

### Tabbed Pane Test
- JTabbedPane with single tab
- JTextArea in scroll pane
- Tests tabbed container functionality

### Event Feedback
- All UI actions update "Last Event" field
- Provides visual feedback for event handling

## Custom Components

### MyLabel
```java
public class MyLabel extends javax.swing.JLabel {
    // Custom label implementation
}
```

### MyTextField
```java
public class MyTextField extends javax.swing.JTextField {
    // Custom text field implementation
}
```

## Development Guidelines

### Screen Size Reporting

```java
screenSizeTF.setText(
    Toolkit.getDefaultToolkit().getScreenSize().width + ", " +
    Toolkit.getDefaultToolkit().getScreenSize().height
);
```

### Window Location

```java
Point loc = getLocation();
windowLocationTF.setText(loc.x + ", " + loc.y);
```

### Dialog Creation

```java
JDialog dialog = new JDialog(this);
dialog.add(new DialogPanel(dialog));
dialog.pack();
dialog.setSize(100, 50);
dialog.setLocation(100, 100);
dialog.setVisible(true);
```

## Best Practices

- **Event Logging**: Update feedback field on all user actions
- **Component State**: Track and display component state changes
- **Dialog Parent**: Pass parent frame to dialog for proper modal behavior
- **Custom Components**: Test custom LG3D-specific components
- **Disabled Components**: Mark unimplemented features as disabled

## Dependencies

- Java Swing: JFrame, JDialog, JButton, JCheckBox, JTextField, JComboBox, JTabbedPane, JTextArea, JScrollPane
- Java AWT: Toolkit, Point
- Custom: MyLabel, MyTextField

## Testing

Launch standalone:
```bash
./gradlew :lg3d-demo-apps:run -Papp=swingtest
```

## Extension Points

- **ComboBox**: Enable and test JComboBox functionality
- **Outside LG**: Implement external window display
- **More Components**: Add JList, JTree, JTable tests
- **Keyboard Events**: Test keyboard input and shortcuts
- **Drag and Drop**: Test DnD functionality
- **Focus Management**: Test focus traversal and focus events

## Known Limitations

- ComboBox disabled (not implemented)
- "Show Outside LG" radio button disabled
- Move window only toggles between two positions
- No keyboard event testing
- No drag and drop testing
- NetBeans-generated form code (do not modify manually)
