# Changelog

All notable changes to Launcher are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Drag an Application Launcher onto the 2D quick-launch bar** (`lg3d-apps`
  `org.jdesktop.lg3d.apps.launcher` + `lg3d-core` taskbar) — the Application
  Launcher frame now lets a launcher be dragged straight onto
  the taskbar's pinned quick-launch strip: dragging its icon exports the current
  name / command / icon, and the strip (a `DropTarget`) pins the dropped launcher
  through the same `QuickLaunchModel.pin` path the window right-click popup uses —
  so a freshly created launcher can be pinned without first appearing in the start
  menu. Both ends share one same-JVM `DataFlavor` (`Desktop2D.QUICK_LAUNCH_FLAVOR`,
  carrying the public `Desktop2D.QuickLaunchItem` by reference); the strip
  highlights while an acceptable drag hovers and keeps a drop zone even when it is
  empty. The drag glue lives in a testable helper outside the
  NetBeans-generated form. Covered by headless tests (`QuickLaunchDragTest`, plus
  the flavour assertion in `Desktop2DControlHooksTest`); the live drag gesture
  itself needs an X display.

- **Application Launcher can pick a built-in icon from the icon library** — beside the existing
  **Choose Icon** file browser, the launcher frame now has an **Icon Library**
  button that opens a picker over the desktop's own bundled `IconManager` glyphs : a category list (General, Development, Text,
  Media, Navigation, Tables) driving a live-preview list of every glyph in that
  category. The chosen glyph is exported to a PNG under
  `~/.config/lg3d/launchers/icons/` and stored as the launcher's icon path, so it
  flows through `LauncherSaver` and the drag-to-quick-launch payload exactly like
  a custom image file does; the icon button now also shows a preview of either
  pick. The IconManager bridge is a pure, never-throw seam — it
  reduces the bundled `"<Name><edge>.gif"` file names to distinct base glyph
  names, treats an unknown name's `MissingIcon` as "no glyph" and degrades to an
  empty catalogue when the jar is absent — covered by headless JUnit 5 tests ; the picker dialog needs a display and
  is verified at runtime.

## [1.28.0] — 2026-09-30

### Added

- **User launcher creator for the 2D/Swing desktop** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.launcher.LauncherFrame`; utilities in `lg3d-core`,
  `org.jdesktop.lg3d.utils.LauncherSaver`) — enables users to create custom
  application launchers through a Swing form (name, description, command, icon
  selection via JFileChooser). Saved launchers are written as `.lgcfg` files to
  `~/.config/lg3d/launchers/` and are automatically discovered by the desktop's
  start menu after a restart. The launcher creator is now registered in
  `Desktop2DAppRegistry.SWING_FRAME_APPS` so it appears in the start menu of both
  2D and 3D desktops. Previously, the icon picker and save functionality were
  stubs; they are now fully implemented with validation and user feedback.
