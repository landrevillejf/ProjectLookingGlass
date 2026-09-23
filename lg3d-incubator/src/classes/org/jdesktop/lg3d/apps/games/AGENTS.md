# Games (Chess · Solitaire · Sudoku · Tic-Tac-Toe)

> Role-aware guide for the four native-3D games under `games/`. This file governs
> the whole subtree (`chess/`, `solitaire/`, `sudoku/`, `tictactoe/`). Module:
> [`lg3d-incubator`](../../../../../../../AGENTS.md) · canonical UI/UX rulebook:
> [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md) · native-3D guide:
> [`docs/lg3d-native-apps.md`](../../../../../../../../docs/lg3d-native-apps.md) ·
> build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## Suite at a glance

| Game | Entry point | Start-menu name / group | Command | Descriptor (`lg3d-apps/src/config`) |
| --- | --- | --- | --- | --- |
| Tic-Tac-Toe | `tictactoe.TicTacToe3D` | Tic-Tac-Toe 3D / **Games** | `java …games.tictactoe.TicTacToe3D` | `tictactoe.lgcfg` |
| Sudoku | `sudoku.Sudoku3D` | Sudoku 3D / **Games** | `java …games.sudoku.Sudoku3D` | `sudoku.lgcfg` |
| Chess | `chess.Chess3D` | Chess 3D / **Games** | `java …games.chess.Chess3D` | `chess.lgcfg` |
| Solitaire | `solitaire.Solitaire3D` | Solitaire 3D / **Games** | `java …games.solitaire.Solitaire3D` | `solitaire.lgcfg` |

| Item | Value |
| --- | --- |
| Status | **Production-grade** native-3D apps (supported showcase) |
| Surface | **pure-3D `Frame3D`** — click-driven (no keyboard focus in dev mode) |
| Shared pattern | Three files per game: `<Game>Model` (pure Java, no AWT) · `<Game>View` (live-texture `Component3D`) · `<Game>3D` (`Frame3D` host + `AgendaButton` strip) |
| Build | `./gradlew :lg3d-incubator:build` |

**Engines:** TicTacToe = unbeatable full-width minimax (`bestMove()` opening
heuristic, `reset(boolean humanFirst)`). Sudoku = generate-from-solved by digging
holes, three levels Easy/Medium/Hard (44/34/27 givens), conflict highlight,
`hint()`/`solve()`/`resetToPuzzle()`. Chess = full rules (castling, en passant,
promotion, check/mate/stalemate, insufficient-material draws) vs negamax +
alpha-beta + quiescence + piece-square eval (perft-verified 20/400/8902/197281).
Solitaire = Klondike (recycling stock, 4 foundations, 7 tableau piles, run dragging,
auto-finish, undo, hints) with vector suit shapes (`Path2D`/`Ellipse2D`, no font dep).

## Roles

- **Architect** — All four share one proven shape: a plain-Java **model**, a
  live-texture **view**, and a `Frame3D` **host** with a runtime-drawn `AgendaButton`
  control strip. Keep the model free of AWT/Java 3D so it unit-tests headless; keep
  the view/host thin. New games must follow this three-file pattern.
- **Engineer / Developer** — Obey the core UI/UX rulebook and the **live-texture
  rule**: one fixed-size `ImageComponent2D` (`ALLOW_IMAGE_WRITE`) attached to a
  `Texture2D` once off-live; repaint + `.set()` in place, never re-attach. Use
  **power-of-two** textures (512x512 boards, 1024x1024 solitaire — this JOGL-based
  lg3d rounds texture dims up to POT). Board quads set `Geometry.ALLOW_INTERSECT` so
  `PICK_GEOMETRY` + `getLocalIntersection` maps a click to a cell. **Dev mode routes
  no keyboard focus** — all input is click/button driven. Jogamp packages only.
- **QA** — Unit-test each **model** headless: minimax/negamax move correctness, chess
  perft, sudoku generate/solve/hint, solitaire deal invariants + stack/flip/undo/
  auto-win/hint. Verify each 3D view with the in-JVM probe + internal screencapture;
  a black host capture under Wayland is not a defect. Watch for boxed-comparison
  assertion bugs that masquerade as engine failures.
- **Business Analyst** — A supported showcase suite (four playable 3D games) that
  demonstrates click-driven native-3D UI and headless-testable game engines. They are
  the **Games** start-menu group; production expectations apply.
- **Functional Analyst** — Spec each game as user-visible function (rules, difficulty,
  undo/hint) plus the core contract (Frame3D host, live-texture view, POT board,
  descriptor in `lg3d-apps`). Do not over-claim features — verify the model API
  (e.g. TicTacToe has `reset(humanFirst)`, not an "easy mode"; Sudoku has three
  levels, not four).
- **Project Manager** — Commit scope `lg3d-incubator`; the four descriptors live in
  `lg3d-apps`, so a games PR usually spans two modules — say so. Done = build +
  `:lg3d-core:runtimeResources` (icons via `GenerateAppIcons.java`) + `./run-lg3d.sh`
  + capture/log evidence.
- **UI/UX (3D & 2D)** — **3D only**: boards, pieces/cards and control buttons are all
  runtime-drawn scene-graph nodes (no PNG assets). Follow the glassy vocabulary,
  depth ordering, hover/press feedback and click-driven-input rules from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook +
`docs/lg3d-native-apps.md` → root `AGENTS.md`. On conflict the higher file wins; fix
here in the same PR. Every PR states which game(s) changed, live vs dormant status,
the descriptor location, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump.
Stage only intended paths (never `git add -A`).
