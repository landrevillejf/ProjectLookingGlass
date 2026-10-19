# Changelog

All notable changes to Bugreporter are documented here.

## [Unreleased]

### Added

- **A Bug Reporter app — the manual half of the crash-reporting tool**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.bugreporter`; `lg3d-core`,
  `Desktop2DAppRegistry`) — the automatic capture structurally cannot see the
  bugs that never throw: a task bar that wraps, a splash that stays, a resize
  that loses a workspace. Start Menu → Utilities → **Bug Reporter** opens a
  form for exactly those: subject, repro steps, expected/actual, severity, a
  typed evidence list (paths are named, never uploaded, and reduced to bare
  redacted file names before anything is filed), a live preview of the *exact*
  issue text, and the same prefilled `github.com/.../issues/new` link the crash
  pipeline produces — still no token, still the human pressing the real button.
  A crash already in the spool can be **attached**, and the filed report then
  keeps the crash's signature, trace and environment with the human narrative
  layered on top, so one bug never splits into two issues; a hand-written
  report with no crash signs itself through `CrashSignature.ofNarrative`. One
  `BugReporterPanel` serves both desktops — `TitledSwingWindow`/SwingNode in
  3D, an MDI internal frame via `PANEL_APPS` in 2D — with all the consequences
  in a headless `BugReportDraft` (a non-blank subject is the only blocker,
  everything else is advice), disk and browser work off the EDT on a named
  daemon executor, and no modal dialog anywhere. 17 headless tests
  (`BugReportDraftTest` 10, `BugReporterPanelTest` 7).
