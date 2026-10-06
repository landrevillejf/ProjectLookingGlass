# LG3D Advanced Search Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (Advanced Search) |
| Entry points | `Search.main` → `TitledSwingWindow.show(...)` hosting `SearchPanel`; in the 2D desktop the same `SearchPanel` is an MDI frame via `Desktop2DAppRegistry.PANEL_APPS` |
| Surface | **SwingNode-in-Frame3D** (3D) / plain-Swing MDI frame (2D); the search itself is headless (the `org.jdesktop.lg3d.utils.search` engine in `lg3d-core`) |
| Start-menu name / group | Search / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.search.Search` |
| Accelerator | **Ctrl+Shift+F** (desktop-wide, 2D: `ShortcutMap.SEARCH` → `Shortcuts.Target.search()`; in-panel: focuses the query field) |
| Descriptors | `src/config/search.lgcfg` → `config/demo`; icon `resources/images/icon/search.png` |
| Build | `./gradlew :lg3d-apps:build` (engine: `:lg3d-core:build`) |

**Components:** `SearchPanel` (Swing UI, SwingNode-safe widgets only) +
`SearchFormat` (pure size/date/scope helpers). The engine lives in `lg3d-core`:
`SearchEngine`, `SearchQuery`, `SearchMatch`, `ContentHit`, `SearchHandle`,
`NameMatcher`, `ContentScanner`.

## Roles

- **Architect** — Keep the split: all traversal/matching/scoring logic lives in
  the headless `lg3d-core` `utils.search` engine (no AWT, no jogamp), and this app
  is a thin Swing front end over it. The engine has no dependency on the desktop;
  the panel must not grow file-walking logic of its own. One `SearchPanel` serves
  both desktops (registered in `PANEL_APPS`); never fork a 2D-only copy.
- **Engineer / Developer** — Follow the core UI/UX rulebook for the SwingNode host
  (offscreen paint, Metal LAF, EDT hops, `dispose()`). Use only SwingNode-safe
  widgets (`JList`, plain `JButton`, `JTextField`, `JTable`, `JTextArea`) — never
  `JComboBox`/check/radio/toggle, whose Synth peers NPE offscreen. Marshal engine
  callbacks to the EDT with `SwingUtilities.invokeLater`. Open hits via the shared
  `org.jdesktop.lg3d.utils.system.Opener`, never a bespoke `xdg-open`. The engine
  must stay robust: swallow per-entry I/O errors, never follow symlinked folders,
  cap results, and honour cancellation.
- **QA** — The engine and `SearchFormat` are covered by headless JUnit
  (`SearchEngineTest`, `SearchQueryTest`, `NameMatcherTest`, `ContentScannerTest`,
  `SearchFormatTest`, `SearchPanelTest`) over `@TempDir` trees; `SearchPanel`
  constructs headless. Verify live ranking/content-grep with a scratch probe
  (`SearchEngine.searchAll` over a real source tree). Confirm the hosted window and
  Ctrl+Shift+F with the in-JVM probe + internal screencapture; a black host capture
  under Wayland is not a defect.
- **Business Analyst** — The customer is any desktop/LFS user who needs to find a
  file or the text inside files fast, without a background indexer. Value: instant,
  ranked, filterable, cancellable search reachable from anywhere via one accelerator.
- **Functional Analyst** — Contract: name match (contains/glob/regex, case
  toggle), optional content grep with line+snippet evidence, type/min-max size/
  modified-within-days filters, multi-scope roots, a result cap and Stop. Results
  stream live, then re-rank best-first on completion; double-click opens, "Open
  Folder" reveals. Rationale: on-demand parallel walk (not a persistent index) keeps
  it correct on a mutable FS and dependency-free on a minimal LFS system.
- **Project Manager** — Commit scope `lg3d-apps` (+ `lg3d-core` for the engine and
  the shortcut wiring). Done = `./gradlew build` + `:lg3d-core:runtimeResources`
  (assembles `search.png`) + probe/`./run-lg3d.sh` evidence. Branch → PR vs `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the identical Swing panel in an MDI frame. Keep the filter rail,
  live status line, ranked table and content-preview pane consistent across both.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-apps`/`lg3d-core`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no
version bump; stage only intended paths (never `git add -A`).
