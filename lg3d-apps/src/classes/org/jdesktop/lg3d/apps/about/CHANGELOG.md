# Changelog

All notable changes to About are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Per-application About box for every 2D/Swing desktop window** — a single reusable
  `AboutDialog` helper gives every panel application the 2D desktop hosts a
  consistent About box with no per-application code. Each `Desktop2DWindow` now
  builds a window menu bar carrying **Help → About&nbsp;&lt;App&gt;**, which opens
  a modal dialog showing the application icon, title and description read
  straight from the shared `.lgcfg` start-menu descriptor — the same source that already feeds the menu
  label and window title — followed by a fixed author credit
  (**Jean-Francois Landreville**), the resolved build version and a
  *Part of Project Looking Glass* footer. `Desktop2D.openPanelApp` passes the
  descriptor's description into the window, and session restore resolves it back
  by command since the persisted `WindowRecord` stores
  only name/command/icon. The version is never hardcoded: it is resolved from the
  `lg.version` system property, falling back to the jar manifest and then
  `unknown`, so a version bump cannot miss it. All content logic lives in pure,
  headless-testable statics (`displayTitle`/`displayDescription`/
  `resolveVersion`/`creditLines`/`buildContent`); only `show` builds the modal
  dialog. Because the hook sits in the shared window, it reaches **all** PANEL
  (MDI) apps at once; SWING_FRAME apps (own top-level `JFrame`) and EXTERNAL apps
  are not covered by this central change. This is the per-application About box,
  distinct from the desktop-wide product About window . Covered by 17 headless JUnit 5 tests
  (`AboutDialogTest` — normalisation, the version fallback chain, credit lines
  and content assembly — and `Desktop2DWindowTest` — the metadata accessors and
  the Help → About menu bar on every constructor).

### Fixed

- **The About box no longer advertises 3D or Sun Microsystems on the 2D/Swing
  desktop** — the product About
  window (`AboutPanel`/`AboutInfo`) is shared by both desktops, so on the
  conventional 2D/Swing desktop it described an "immersive 3D desktop built on
  Java 3D", listed a **Java 3D** runtime row the 2D shell never loads, and
  credited the ported-base lineage to Sun Microsystems — none of which is true
  of the 2D desktop the user is actually running. `AboutInfo` now derives the
  desktop mode from the canonical `lg.fws.mode` property (reusing
  `DesktopMode.MODE_2D`/`MODE_SWING`, no 3D probe) and, when it resolves to the
  conventional 2D/Swing shell, serves 2D-specific variants: a "desktop
  environment for the Java platform" tagline and description with no "3D"
  claim, an omitted Java 3D runtime row, and attribution solely to
  **Jean-Francois Landreville** (no Sun Microsystems line). The mode selectors
  are pure, headless-testable helpers (`is2DMode`, `tagline`/`description`/
  `credits(boolean)`, `getFields(boolean)`); the 3D desktop's About window is
  unchanged and still carries the full description, Java 3D row and port
  lineage. Covered by new headless JUnit 5 cases in `AboutInfoTest`.

## [1.14.0] — 2026-09-25

### Added

- **About** — a new *Utilities*
  start-menu application showing the product identity, the resolved build
  version, the host runtime facts (Java 3D provider, Java version/vendor,
  platform) and the attribution / licence text. Following the
  `Calculator`/`HelpCenter` pattern it splits into a headless model , a plain Swing panel and a 3D wrapper (`About`,
  via `TitledSwingWindow`), so the same panel is hosted on a `SwingNode` in the
  3D desktop and as an MDI internal frame in the 2D/Swing desktop (registered in
  `Desktop2DAppRegistry.PANEL_APPS`). The version is not hardcoded: it resolves
  from a new `lg.version` system property (set by `:lg3d-core:run` to the
  canonical `project.version`), falling back to the lg3d-apps jar manifest
  `Implementation-Version` (stamped from `project.version`) and then `unknown`.
  Covered by headless JUnit 5 tests (`AboutInfoTest`, `AboutPanelTest`, plus a
  new `Desktop2DAppRegistryTest` case).
