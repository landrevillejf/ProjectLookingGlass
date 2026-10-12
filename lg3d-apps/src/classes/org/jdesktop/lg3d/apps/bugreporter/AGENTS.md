# Bug Reporter

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** desktop utility (the manual half of the crash-reporting tool) |
| Entry point | `BugReporter.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (Swing panel on a `SwingNode` quad under a glassy title bar) |
| Start-menu name / group | Bug Reporter / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.bugreporter.BugReporter` |
| Descriptor | `src/config/bugreporter.lgcfg` → `config/demo` |
| Icon | `resource:///resources/images/icon/bugreporter.png` (in-tool `BugInsect` glyph, red tile) |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **BugReporter** — entry point; installs the hosted Metal LAF and shows the window.
- **BugReporterPanel** — Swing form: subject, steps, expected/actual, severity,
  typed evidence list, crash-archive picker, live preview of the exact issue
  text, and the status line. Mirrors widgets into the draft; paints from the
  draft; holds no rule of its own.
- **BugReportDraft** — headless model (no Swing): when the form may be filed
  (`isSubmittable` — a non-blank subject is the only blocker), what it advises
  about (`gaps`), and what report the form freezes into (`toReport`).

## The pipeline it sits on

Everything consequential is `lg3d-core`'s
`org.jdesktop.lg3d.displayserver.crash` engine (PR #343): this app is a face
on `CrashReporter.shared()`. Submit calls the **explicit** path,
`report(CrashReport)`, which spools the redacted report to
`~/.lg3d/crash-reports` and hands the prefilled
`github.com/.../issues/new` link to the browser. **Nothing is ever POSTed** —
no credential exists anywhere in this path, and the human presses GitHub's
real submit button after reading the draft issue. The crash picker lists the
same spool the automatic capture writes: attaching a captured crash keeps its
signature, trace and environment and layers the human narrative on top, so a
filed-by-hand report about a real crash never splits into two issues.

Two rules are load-bearing for anyone editing this app:

- `toReport()` pins `occurrences(1)` because `CrashStore.store` **adds** the
  incoming count to the file's tally — one click must register one sighting.
- Evidence is **typed or pasted**, never chosen through a file chooser: modal
  dialogs escape the SwingNode capture (core rulebook), and
  `CrashReport.Builder.addAttachment` reduces every path to a bare, redacted
  file name anyway — the report claims the file exists, it does not deliver
  it.

## Roles

- **Architect** — Depend only on the public crash-engine API
  (`CrashReporter`/`CrashReport`/`CrashStore`/`IssuePrefill`); never reach
  into package-private engine internals from here, and never duplicate
  redaction or budget logic — the engine owns both. Keep `BugReportDraft`
  free of UI so it stays headless-testable.
- **Engineer / Developer** — Follow the core UI/UX rulebook: Metal LAF
  (`installHostedLookAndFeel`) before the panel exists, no modal dialogs or
  file choosers, disk and browser work off the EDT on the named daemon
  executor (`BugReporterPanel-worker`) with results marshalled back via
  `invokeLater`, in-panel status line instead of popups, `setOnClose` +
  `close()` for the hosts. The panel must stay constructible with
  `java.awt.headless=true`.
- **QA** — `BugReportDraftTest` (10 cases) covers the rules with no Swing;
  `BugReporterPanelTest` (7 cases) builds and drives the form headless on the
  EDT, including the initial archive load and `close()`. The live SwingNode
  view is verified with the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`). Never click the real Submit in a test — it
  spools to the developer's home and opens a browser.
- **Business Analyst** — Turns "this desktop misbehaved" into an actionable
  public issue in under a minute, for bugs that never throw — the half the
  automatic capture structurally cannot see.
- **Functional Analyst** — Spec: subject is the only hard requirement;
  everything else is advice the reporter can overrule; the preview must show
  the *exact* issue text; filing is reviewable (browser draft), never silent.
- **Project Manager** — Commit scope `lg3d-apps` (the `PANEL_APPS` entry
  touches `lg3d-core`). Done = build + `./run-lg3d.sh` + screencapture/log
  evidence in the PR. Branch → PR against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: the glassy `TitledSwingWindow` frame. 2D: the
  same panel in an MDI internal frame via `Desktop2DAppRegistry.PANEL_APPS`.
  One class serves both; the header states the delivery model where it is
  always visible, because a reporter who believes Submit POSTs is a surprised
  reporter.

## Communication & coherence

Single source of truth: this file (app) → module `AGENTS.md` → core UI/UX
rulebook → root `AGENTS.md`; the crash engine documents itself in
`lg3d-core/AGENTS.md` (*Crash Reporting*). On conflict the higher file wins;
fix here in the same PR. Every PR states the surface (SwingNode), any
descriptor change, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no
version bump. Stage only intended paths (never `git add -A`).
