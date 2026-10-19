# Changelog

All notable changes to Firewall are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Firewall GUI for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.firewall`; registration in `lg3d-apps`) — a production
  firewall management application providing a Swing panel with status display,
  active rule table, and enable/disable controls. The panel auto-detects the
  firewall backend (firewalld via `firewall-cmd` preferred, falling back to
  iptables), queries status and rules every five seconds via `FirewallService`,
  and uses `pkexec` for privilege escalation on enable/disable operations. The
  same panel serves both desktops: in 3D the `Firewall` wrapper hosts it on a
  `SwingNode` inside a `Frame3D` via `TitledSwingWindow`. The app is registered
  in the start menu (**System** group) via `firewall.lgcfg` in `lg3d-apps`
  `src/config`. Known limitations: rule editing not implemented, no add/remove
  rule UI, fixed 5-second refresh interval.

### Fixed

- **The Firewall start-menu entry no longer fails to load its icon**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.firewall`; icon in `lg3d-core`
  resources) — the Firewall manager was added to the desktop without its icon:
  `firewall.lgcfg` pointed `displayResourceUrlName` at a package-relative
  `resource:///org/jdesktop/lg3d/apps/firewall/resources/images/icon/firewall.png`
  that is not part of the assembled `resources/` runtime tree (and no such PNG
  was ever committed), so opening the start menu logged `Failed getInputStream`,
  a `NullPointerException` in `ResourceURLConnection.connect` and a
  JAI / `TextureLoader` cascade, and the menu item rendered with no icon. The
  descriptor now points at the shared
  `resource:///resources/images/icon/firewall.png` — the same convention every
  other start-menu app uses (e.g. `securitycenter.lgcfg`) — and a purpose-drawn
  running-bond brick-wall `firewall.png` (48×48 RGBA on a red tile) is generated
  into `lg3d-core/src/resources/images/icon` by
  `lg3d-art/tools/GenerateAppIcons` and assembled by
  `:lg3d-core:runtimeResources`.
