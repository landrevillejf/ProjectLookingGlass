# Changelog

All notable changes to Controlcenter are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Cron Task Scheduler for the 2D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.utils.taskscheduler`; Control Center panel in `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter.TaskSchedulerPanel`) — a complete,
  `crontab`-style scheduler that runs user programs and start-menu apps on a
  schedule, entirely separate from (and leaving untouched) the existing
  wallpaper/lighting `ScheduleService`/`SchedulePanel` day-night feature. A
  from-scratch `CronExpression` parses the classic five-field `min hour dom month
  dow` form plus a six-field seconds form, ranges/steps/lists, month and day
  names, `7`=Sunday, the Vixie-cron "either day field" OR rule and the
  `@yearly`/`@monthly`/`@weekly`/`@daily`/`@midnight`/`@hourly`/`@minutely`/`@reboot`
  macros, and computes the next fire time (impossible dates such as 30 February
  return none). `ScheduledTask`/`Trigger` model a job as **CRON**, a fixed
  **INTERVAL** or a one-shot **ONCE** instant, running either an external
  **COMMAND** or a start-menu **APP**, each with its own timeout, misfire policy,
  concurrency guard, retries, working directory and environment overlay.
  **Secure by construction** — `ProcessTaskRunner` executes the argv vector
  verbatim through `ProcessBuilder` with **no shell**, so an argument can never be
  re-interpreted as a shell metacharacter; `TaskValidator` gates every save on
  required fields and resource limits (argv/env/name/timeout/retry bounds, NUL
  rejection) and `DISPLAY` is pointed at the lg3d server so GUI jobs open on the
  right screen. **Reliable** — `TaskSchedulerEngine` runs a single daemon tick
  (1 s, giving cron sub-minute resolution) whose body is fully guarded so no
  exception can cancel the loop, fires tasks on a worker pool (a slow job never
  delays the others), recomputes the next fire at dispatch so a long run cannot
  re-fire-storm, skips overlaps when `skipIfRunning` is set, retries failures, and
  on start-up runs `@reboot` jobs and reconciles anything that came due while the
  desktop was down per each task's `MisfirePolicy` (FIRE_ONCE_NOW / IGNORE /
  FIRE_ON_NEXT_TICK). Tasks and a capped, secret-scrubbed run history persist to
  the user `Preferences` tree via `PrefsTaskStore` (structured argv/env stored as
  indexed keys so any character round-trips exactly; every backing-store failure
  is logged and swallowed so the desktop always starts). The `TaskScheduler`
  singleton facade is started from `Desktop2D` and drives the Control Center's
  **Task Scheduler** panel — a `JList`-based (SwingNode-safe, no combo boxes)
  editor with cron presets and a live next-run preview, New/Save/Delete/Run
  now/Refresh, an environment editor and the recent-runs history. Covered by
  headless JUnit 5 tests (`CronExpressionTest`, `TriggerTest`,
  `ScheduledTaskTest`, `TaskExecutionRecordTest`, `TaskValidatorTest`,
  `PrefsTaskStoreTest` against a throwaway preferences node, `ProcessTaskRunnerTest`
  including an argv-injection-is-inert proof, `TaskSchedulerEngineTest` over an
  in-memory store/runner, and `TaskSchedulerPanelTest`) — no real process or
  preference is touched in CI beyond the Unix-guarded runner tests.

- **Control Center → Quick Launch panel** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter.QuickLaunchPanel`; hooks in `lg3d-core`) —
  the 2D taskbar's pinned quick-launch strip is now configurable from the Control
  Center: a `JList` of the pinned launchers in taskbar order with Move Up / Move
  Down / Remove, a second `JList` of the available start-menu applications with
  Add, and a Reset to Defaults button. It drives the strip through new EDT-safe
  `Desktop2D` control-center hooks (`quickLaunchPinned`, `quickLaunchCandidates`,
  `quickLaunchPin`, `quickLaunchUnpin`, `quickLaunchMove`,
  `quickLaunchResetDefaults` and the public `Desktop2D.QuickLaunchItem` record),
  so a change persists and the live taskbar updates immediately. Like the
  Workspaces / Notifications panels it uses only `JList` / `JButton` (never a
  combo box, which an offscreen `SwingNode` cannot reliably deliver) and degrades
  to an empty strip when no 2D shell is running. Covered by headless tests
  (`QuickLaunchPanelTest`, plus the no-shell hook path in
  `Desktop2DControlHooksTest`).

### Fixed

- **The Task Scheduler editor now lays out as a compact form in the Control
  Center** — the editor column was a `GridLayout(0,1)`, which forces every row to an equal
  tall band and vertically centres each `FlowLayout` row inside it, so the form
  showed large empty gaps between Name / Enabled / Schedule and pushed the cron
  trigger card off the bottom edge (clipped mid-row). The editor is now a
  top-packed vertical `BoxLayout` in which each row keeps its own preferred
  height (capped via `setMaximumSize`) and stretches to the column width, so the
  fields read top-to-bottom and the whole card scrolls cleanly; the cron card's
  over-wide titled border was shortened and the preset / environment lists
  trimmed so the column fits without a spurious horizontal scrollbar.

## [1.27.0] — 2026-09-26

### Added

- **Four new fully-controllable Control Center system panels over new platform
  seams** (`lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — the Control Center grows from thirteen
  categories to seventeen by surfacing four standard system settings, each backed
  by a new pure platform seam that follows the established `PrinterStatus` /
  `NetworkConnections` shape (parsing, formatting and command-building are pure
  and unit-tested; the public probes degrade to empty / false / a read-only note
  when the tool, hardware or X server is absent rather than throwing). Four new
  panels — **Date & Time** (`TimeZoneStatus` over `timedatectl`: a time-zone list
  + Set and an NTP toggle), **Language & Region** (`LocaleStatus` over
  `localectl`: the current LANG + a locale list + Set, with an “applies to new
  sessions” note), **Mouse & Keyboard** (`InputSettings` over `xset`: pointer
  acceleration/threshold and key-repeat rate/delay presets + a repeat toggle, with
  a Wayland warning) and **Bluetooth** (`BluetoothStatus` over
  `bluetoothctl`/`rfkill`: a power toggle, controller and device lists with
  Connect/Disconnect, and a hard-/soft-block note when no adapter is present) —
  are pure Swing (`JList` / `JCheckBox` / `JButton` only, never a combo box or raw
  key-capture, so they keep working hosted offscreen in a `SwingNode`) and actually
  change OS settings rather than only displaying them; these persist in the OS
  through their own tools, not in `DesktopConfig`. All four are registered in
  `ControlPanelRegistry` in the final seventeen-panel order, and the
  `controlcenter` `AGENTS.md` was refreshed (the stale panel list and the
  “tabbed”/`JTabbedPane` and “UI-only, no persistence” claims were corrected to
  the real `JList` + `CardLayout` shell, the `ControlPanel` interface and the seam
  inventory). Covered by headless JUnit 5 tests (`TimeZoneStatusTest` 5,
  `LocaleStatusTest` 5, `InputSettingsTest` 6, `BluetoothStatusTest` 6, and one
  construction smoke test per panel — `DateTimePanelTest` 4, `LocalePanelTest` 4,
  `InputPanelTest` 5, `BluetoothPanelTest` 4).

- **Five new fully-controllable Control Center panels over existing seams**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d`,
  `org.jdesktop.lg3d.utils.prefs`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — the Control Center grows from eight
  categories to thirteen by surfacing capabilities the desktop already had. Five
  new panels — **Sound**, **Power**, **Notifications**, **Workspaces** and
  **Shortcuts** — are pure Swing (`JList` / `JCheckBox` / `JButton` /
  `JTextField` only, never a combo box or raw key-capture, so they keep working
  hosted offscreen in a `SwingNode`) and actually change settings rather than
  only displaying them. *Sound* drives the master volume and mute through the
  `VolumeStatus` seam; *Power* shows the battery , a brightness
  preset list driving the `BrightnessStatus` backlight seam and the thermal
  sensors on a 2 s refresh; *Notifications* and *Workspaces*
  reach the live 2D shell — Do Not Disturb, the notification log, the workspace
  count and workspace switching — through new public EDT-safe `Desktop2D` hooks
  that no-op / return an empty snapshot in 3D mode or headless and persist to
  `DesktopConfig` where a key exists; *Shortcuts* rebinds the global keys through
  a new `DesktopConfig` `shortcuts.custom` override (`ShortcutMap.mergeBindings`
  overlays it on the built-in defaults) applied live via
  `Desktop2D.applyShortcuts()`. To expose these, `VolumeStatus.setVolume`/
  `setMuted` and `BrightnessStatus.setBrightness` were widened from
  package-private to `public` (bodies unchanged), and every panel degrades to an
  explanatory read-only / empty state when its backing hardware, tool or 2D shell
  is absent. Covered by headless JUnit 5 tests (`ShortcutMapTest` +5,
  `DesktopConfigShortcutsTest` 7, `Desktop2DControlHooksTest` 6, and one
  construction smoke test per panel — `SoundPanelTest` 4, `PowerPanelTest` 4,
  `NotificationsPanelTest` 4, `WorkspacesPanelTest` 4, `ShortcutsPanelTest` 6).

- **Schedule-based daylight/nightlight wallpapers** (`lg3d-core`,
  `org.jdesktop.lg3d.utils.schedule`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — an opt-in, time-of-day wallpaper
  scheduler customizable from the Control Center. `DesktopConfig` gains seven
  persisted keys (`schedule.enabled`, daylight/nightlight hour+minute, and the
  daylight/nightlight wallpaper filenames; default off, 07:00 → 20:00, clamped
  to valid ranges). `ScheduleService` is a daemon-timer service that re-checks
  every minute, resolves the wallpaper for the current time (correctly handling
  a daylight window that crosses midnight) and applies it — through a
  `BackgroundChangeRequestEvent` on the 3D desktop and `Desktop2D.setWallpaper`
  on the conventional 2D/Swing desktop — only when it changes. It is started
  from `Desktop2D.show()` so a persisted schedule is honoured at login, and the
  new Control Center **Schedule** panel (registered after Printing) lets the
  user enable it, set both transition times and pick each wallpaper from the
  bundled background collection, with an “Apply Now” shortcut. The pure
  clock-decision logic is covered headlessly; the live wallpaper application is
  verified at runtime.

- **Window-glass style choice in the Control Center (Appearance)** (`lg3d-core`,
  `org.jdesktop.lg3d.utils.prefs`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — frosted-vs-glassy is now a user
  setting, not just a launch flag: `AppearancePanel` grew a "Window Glass"
  section with a two-entry `JList` (Glassy (classic) / Frosted (GPU)) and an
  Apply button that persists the new `DesktopConfig` preference
  (`window.frostedGlass`, `isFrostedGlass`/`setFrostedGlass`, default Glassy).
  `Frame3DWindowDecoration` reads it when it builds a window body, so the choice
  applies to newly opened windows on the 3D desktop; the `-Pshaders` dev flag
  still force-enables the whole shader path. Covered by headless JUnit 5 tests
  (`DesktopConfigWindowGlassTest` 3).

- **Printing and network management in the Control Center, on both desktops**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — two new category panels, registered
  in every desktop mode so the same management UI is reachable from the 3D
  desktop and the conventional Swing (2D) desktop. *Printing* lists the CUPS
  queues , marks the system default and offers Set Default and Print Test Page ; *Network* lists the saved
  NetworkManager connections with type and active device and offers Connect / Disconnect . Both are
  pure Swing over two new platform seams that follow the established
  probe+pure-parse shape (`PrinterStatus`, `NetworkConnections`: parsing,
  formatting and command-building are pure and unit-tested; the probes degrade
  to empty / false on a host without CUPS / NetworkManager rather than
  throwing). Covered by headless JUnit 5 tests (`PrinterStatusTest` 6,
  `NetworkConnectionsTest` 6).

- **Metal theme manager for the conventional 2D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — the 2D desktop can now change and
  create Swing *Metal* themes, and the Control Center's Appearance panel shows a
  "Metal Theme" manager in place of the classic/frosted window-glass selector
  (which is 3D-only, driving `Frame3D` decoration, and is now gated to the 3D
  desktop). The manager lists the built-in *Steel* and *Ocean* palettes plus any
  user themes, and offers Apply / New… / Delete: *New…* derives a full palette
  from a name and one accent colour , and *Delete* removes a
  user theme (built-ins are protected). A *System Look* button reverts the shell
  to the native platform look-and-feel (GTK/Synth) it started on, clearing the
  persisted selection so the next start-up keeps the native look too. Because
  the 2D shell starts on the
  platform look-and-feel (GTK/Synth) and Metal themes only affect Metal,
  applying a theme switches the shell onto Metal with the chosen palette and
  refreshes every open window live. The selection and the encoded custom-theme
  list persist through two new `DesktopConfig` preferences , and `Desktop2D.reapplyConfig()` re-applies the stored
  theme at start-up — a no-op while none is chosen (the blank default), so the
  desktop keeps its native look until the user picks one. The pure seam
  (`MetalThemeSpec`: the palettes, the compact encode/decode serialisation and
  the accent derivation) and the `CustomMetalTheme` bridge are display-
  independent; covered by headless JUnit 5 tests (`MetalThemeSpecTest` 7,
  `DesktopConfigMetalThemeTest` 4).
