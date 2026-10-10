# Git GUI Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (graphical Git client, GitKraken / GitHub Desktop style) |
| Entry point | `GitGui.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (hosts `GitGuiPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Git GUI / **Developers** |
| Command | `java org.jdesktop.lg3d.apps.gitgui.GitGui` |
| Descriptor | `src/config/gitgui.lgcfg` → `config/demo` |
| Git backend | The **system `git` executable** (no bundled JGit) — `GitRepository` shells out through `GitCommands` (pure argv builders) + `GitParsers` (pure parsers) over `ProcessRunner`; a missing `git` degrades to honest guidance, never a fake state |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **GitGui** — thin 3D entry point; installs the hosted look and feel and shows
  `GitGuiPanel` in a `TitledSwingWindow`.
- **GitGuiPanel** — the one plain `JPanel` (no-arg constructor, no Java 3D): a
  toolbar (Open, Refresh, Init, Fetch, Pull, Push + current-branch label), a left
  column of Changes (Stage / Unstage) over a commit-message box (Commit), and a
  right column of Branches (Checkout / New Branch), commit History and a
  monospace diff viewer, plus a status line. A package-private
  `GitGuiPanel(GitRepository, boolean synchronous)` seam runs every worker pass
  inline for the headless tests.
- **GitRepository** — the executor: an injectable `Runner` (`GitResult run(argv,
  cwd, timeoutMs)` + `available()`) over `ProcessRunner`, with LOCAL (30 s) and
  NETWORK (120 s) timeouts. Reads (`isRepository`, `currentBranch`, `branches`,
  `status`, `log`, `diff`, `show`) parse canned `git` output; mutations (`stage`,
  `unstage`, `commit`, `checkout`, `createBranch`, `pull`, `push`, `fetch`,
  `init`) guard blank input and surface `git`'s own stderr on failure. The
  headless unit-test seam.
- **GitCommands / GitParsers** — pure, side-effect-free argv builders and output
  parsers. `GitCommands` pins machine-readable formats (`status --porcelain=v1
  --branch`, `for-each-ref --format=%(HEAD)%09%(refname:short)`, a
  `%x1f`/`%x1e`-delimited `log`) and prefixes reads with `-c core.quotePath=false
  -c color.ui=false --no-optional-locks`. `GitParsers` turns that raw stdout into
  `GitChange` / `GitBranch` / `GitCommit` records (rename `old -> new`, conflict
  codes, ahead/behind, detached HEAD).
- **GitResult / GitChange / GitCommit / GitBranch** — AWT-free value types; the
  porcelain column semantics (`isStaged`, `isUntracked`, `isConflict`,
  `statusLabel`) live on `GitChange`.

## Roles

- **Architect** — The whole feature lives in this package and needs **no new
  Gradle dependency**: `git` is an external executable, so the `lg3d-apps` jar
  alone on the `:lg3d-core:run` / `releaseBundle` classpath is enough (unlike
  PDFBox). The AWT-free seam (`GitCommands` + `GitParsers` + `GitRepository`'s
  `Runner`) is kept free of Swing so it is unit-testable headless. The same panel
  drives both desktops: 3D via `TitledSwingWindow`, 2D via
  `Desktop2DAppRegistry.PANEL_APPS` keyed on `GitGui` → `GitGuiPanel`. Espresso
  (the Advanced Text Editor) also embeds this very panel as one of its
  `CardLayout` cards — its Project card's *Git GUI…* action calls
  `GitGuiPanel.openRepository(projectRoot)` — so `GitGuiPanel`'s public
  no-arg constructor and `openRepository` are cross-package API consumed by
  `org.jdesktop.lg3d.apps.texteditor`; keep them stable.
- **Engineer / Developer** — Keep command construction in `GitCommands` and
  output interpretation in `GitParsers`; `GitRepository` only wires them to the
  `Runner` and never builds argv inline. Always drive `git` with machine-readable
  flags (porcelain / `for-each-ref` / delimited `log`) and parse defensively —
  a malformed record is skipped, never thrown. Every network / disk command runs
  on a daemon thread (`background(...)`), never the EDT. Keep the panel free of
  Synth-only widgets (no combo boxes — use `JList`) and of modal dialogs in the
  constructor so it paints offscreen. Test hooks return **volatile fields**, not
  labels set via `invokeLater`. Jogamp packages only; obey the core UI/UX
  rulebook.
- **QA** — `GitCommandsTest`, `GitParsersTest`, `GitRepositoryTest` and
  `GitGuiPanelTest` (48 tests) run headless against a `FakeGitRunner` — no
  repository, no `git` binary, no CI dependency on the environment. They assert
  argv shape, parser edge cases (renames, conflicts, detached HEAD, malformed
  log), executor degradation when `git` is missing, and the panel's open / stage
  / commit / branch / diff flow including the one-shot `actionMessage` that
  survives the post-action refresh. For the 3D host use the in-JVM probe +
  internal screencapture (`lg3d-core/lgscreen-*.png`); a black capture under
  Wayland is not a defect.
- **Business Analyst** — A daily-driver utility: inspect, stage, commit, branch,
  diff and sync a working tree from the desktop without a terminal. Value = a
  trustworthy GitKraken / GitHub-Desktop-style view in both desktops that reports
  `git`'s real output honestly.
- **Functional Analyst** — Spec this app as the *working-tree contract*: open a
  folder, show its branch / changes / branches / history, stage & unstage,
  commit with a message, check out & create branches, fetch / pull / push, and
  render a diff for the selected change or commit. The backend (`git`) is an
  implementation detail behind `GitRepository`; a folder that is not a repo, or a
  missing `git`, is surfaced as guidance rather than a fabricated state.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + the registry test) and the
  icon in `lg3d-core` resources and `lg3d-art/tools` — call that out. Done = build
  + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR against
  `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `GitGuiPanel` in an MDI internal frame.
  Conventional Git-client chrome (toolbar + changes/commit left, branches/history/
  diff right + status), never a click-cycling 3D idiom; keep both surfaces
  pixel-identical.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `GitRepository` /
`GitCommands` / `GitParsers` seam, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
