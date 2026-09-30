# Firewall Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (firewall management) |
| Entry point | `Firewall.main` → `TitledSwingWindow.show(...)` hosting `FirewallPanel` |
| Surface | **SwingNode-in-Frame3D** (700x500); the same panel is reused in the 2D desktop |
| Start-menu name / group | Firewall / **System** |
| Command | `java org.jdesktop.lg3d.apps.firewall.Firewall` |
| Descriptor | `src/config/firewall.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

**Components:** `FirewallPanel` (Swing UI + 5s refresh `Timer`) +
`FirewallService` (firewalld/iptables backend) +
`FirewallStatus`/`FirewallRule` (data models).

## Roles

- **Architect** — Production firewall manager hosted through `TitledSwingWindow`/
  `SwingNode`; keep the panel plain Swing so it drives both desktops. The panel owns
  its refresh `Timer` and exposes `setOnClose(Runnable)` so the host can disable the
  `Frame3D` — preserve that lifecycle seam.
- **Engineer / Developer** — Follow the core UI/UX rulebook: offscreen SwingNode
  paint, no modal dialogs (in-panel confirm for enable/disable), EDT hops from lg3d
  listeners, `dispose()` on discard, Metal LAF via `installHostedLookAndFeel`.
  **Always stop the refresh timer on close** (leak guard). Jogamp packages only.
- **QA** — Unit-test `FirewallService` command parsing headless (no real firewall
  changes in CI). Verify the hosted window and 5s refresh with the in-JVM probe +
  internal screencapture; a black host capture under Wayland is not a defect.
- **Business Analyst** — A shipped system utility (manage firewall rules and
  network security). Production standards apply; note enable/disable is a sensitive
  action requiring confirmation.
- **Functional Analyst** — Spec user-visible function (status display, rule table,
  enable/disable, refresh) plus the contract with core. Known gaps: rule editing
  not implemented, no add/remove rule UI — track these as backlog, not defects.
- **Project Manager** — Commit scope `lg3d-apps`. Done = build +
  `./run-lg3d.sh` + capture/log evidence in the PR. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the Swing status/rule table panel, identical in the 2D desktop.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference

## Overview

Firewall is a system security application that displays firewall status and active rules for Linux firewalld/iptables systems. It provides a Swing-based UI with a table showing firewall rules and controls to enable/disable the firewall, hosted within a 3D Frame3D using SwingNode.

## Purpose

- Monitor firewall status and active rules
- Enable/disable the firewall
- Demonstrate system command integration in SwingNode
- Provide network security management interface

## Key Components

- **Firewall** - Main entry point, creates Frame3D and SwingNode
- **FirewallPanel** - Main Swing JPanel with status display and rule table
- **FirewallService** - Backend service for firewalld/iptables interaction
- **FirewallStatus** - Data class for firewall status and rules
- **FirewallRule** - Data class for individual firewall rules
- **SwingNode** - Bridge between Swing and 3D scenegraph

## Architecture

### Scene Graph Structure

```
Frame3D (Firewall)
└── SwingNode
    └── FirewallPanel (JPanel)
        ├── Status header (enabled/disabled indicator)
        ├── JTable (rule listing)
        └── Control buttons (Enable, Disable, Refresh, Close)
```

### Refresh Timer

The panel maintains its own five-second refresh timer:
- Updates firewall status every 5 seconds
- Reloads rule list on each refresh
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

- **Native Width**: 700 pixels
- **Native Height**: 500 pixels
- Converted to physical units via `Toolkit3D.widthNativeToPhysical()`

### Firewall Backend

The service auto-detects the firewall backend:
- **firewalld**: Uses `firewall-cmd` commands (preferred)
- **iptables**: Falls back to `iptables` commands
- Commands requiring privileges use `pkexec` for authentication

### Timer Management

```java
// In FirewallPanel
private Timer refreshTimer;

public void stop() {
    timer.stop();
}
```

### Rule Table

Use `DefaultTableModel` for rule data:
```java
DefaultTableModel model = new DefaultTableModel(
    new String[]{"Protocol", "Source", "Destination", "Port", "Action", "Target"}, 0);
JTable table = new JTable(model);
```

## Best Practices

- **Timer Cleanup**: Always stop timer on window close to prevent memory leaks
- **Thread Safety**: Swing updates must be on EDT (Timer handles this)
- **Privilege Escalation**: Use `pkexec` for commands requiring root access
- **Confirmation**: Always confirm before enable/disable operations
- **Error Handling**: Gracefully handle missing firewall tools
- **Backend Detection**: Auto-detect firewalld vs iptables

## Dependencies

- LG3D Core: Frame3D, SwingNode, Toolkit3D
- Java Swing: JTable, TableModel, Timer, standard Swing components
- Java Lang: ProcessBuilder (for system commands)
- Java IO: BufferedReader, InputStreamReader (for command output)

## Testing

Launch standalone:
```bash
./gradlew :lg3d-apps:run -Papp=firewall
```

## Extension Points

- **Rule Editing**: Add UI to create/edit/delete rules
- **Logging**: Add firewall log viewer
- **Profiles**: Add saved rule profiles
- **Advanced Rules**: Support for complex iptables rules
- **Network Zones**: Display firewalld zone information
- **Refresh Rate**: Make refresh interval configurable

## Known Limitations

- Five-second refresh interval is fixed (not configurable)
- No rule editing functionality implemented
- No add/remove rule UI
- Limited to firewalld/iptables (no nftables support)
- Requires pkexec/polkit for privilege escalation
- Limited to basic rule information
