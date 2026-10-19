# Changelog

All notable changes to Tetris are documented here.

## [Unreleased]

### Added

- **Tetris, the Games group's fifth game and its first written for both desktops at once** (`lg3d-apps`, `org.jdesktop.lg3d.apps.games.tetris`; `lg3d-core`, `lg3d-art`) —
  The four existing *Games* entries are incubator apps that were built as
  native-3D boards first and given a Swing panel afterwards, so each ships an
  AWT-free model, a live-texture view, a `Frame3D` host and a panel that
  duplicates the view's constants to avoid loading Java 3D in the 2D JVM. Tetris
  goes the other way: one plain-Swing game, hosted. `Tetris.main` installs the
  hosted Metal LAF and hands `TetrisPanel` to `TitledSwingWindow.show(...)` for
  the 3D desktop — the Calculator pattern — and the *same* descriptor
  (`tetris.lgcfg`, `Games`, icon `resources/images/icon/tetris.png`) launches it
  in the 2D/Swing desktop because the wrapper class is now a key in
  `Desktop2DAppRegistry.PANEL_APPS` mapping to `TetrisPanel`, which therefore
  keeps a no-arg constructor and never touches a Java 3D class. The well, the
  seven pieces, the ghost drop target and the `PAUSED` / `GAME OVER` overlays are
  all custom-painted, so the game ships no sprite. **Input is the interesting
  difference:** the four 3D games are click-driven because dev mode routes no
  keyboard focus to a `Frame3D`, but `SwingNodeRenderer` redispatches real
  `KeyEvent`s at a component, so the panel's focusable board carries the only
  `KeyListener` (arrows move, `Up`/`X` rotate, `Down` soft-drops, `Space`
  hard-drops, `P` pauses, and `Enter` starts a run *only* when none is on the
  board) while the sidebar buttons are `setFocusable(false)` so the board keeps
  the keys — the path both surfaces share, and the reason this game plays
  identically in either desktop. A five-button pad is the mouse twin of those keys, calling the very same engine
  methods through the same turn handler, so a host that never gives the board
  keyboard focus still has a playable game.
  `TetrisEngine` holds the rules with no Swing and no timer (the panel owns the
  clock): 40/100/300/1200 × level per 1/2/3/4 lines, a level every ten rows that
  shortens the fall delay down to a clamped 50 ms floor, and a piece generator
  injectable as a `Random` so a test can pin the sequence without seed hunting.
  Making *reset* and *losing* different states was a real fix found by reading:
  stopping the clock alone left the last piece on a cleared grid, which
  `isGameOver()` (a piece present, no run, no pause) reads as a lost game — so a
  board the player had just reset painted **GAME OVER** over an empty well, and
  `start()` and `stop()` now both go through
  `atRest()`; only a lock that has nowhere to put a piece ends a run.
  Twelve WAV clips are bundled at the package path by `lg3d-apps`' existing
  resource source set (~1.1 MB); the ported original's 33 MB victory jingle and
  every file no effect plays are **not** shipped — a released jar is weight a
  user downloads to play a game — and `TetrisAudio` loads each clip lazily and
  degrades to silence on a machine with no mixer, because a missing clip is
  otherwise indistinguishable from a soundless machine. Sound on/off, volume and
  starting speed persist through `java.util.prefs` , clamped on
  read so a hand-edited store cannot ask for a negative fall delay, and the form
  that edits them is a `JOptionPane` confirm dialog parented to the panel — the
  pattern 47 other hosted `lg3d-apps` panels already rely on, viable on the 3D
  desktop because `SwingNodeWindowCapture` parks a hosted app's dialog
  off-screen and paints it as a centred overlay into the node's own texture,
  modal input included. The icon is
  generated, not hand-placed: `GenerateAppIcons.java` gains an in-tool
  `TetrisStack` glyph (a settled stack with a gap, one piece still falling) on
  the deep-purple tile no other icon in that table generates. Documented in the
  Help Center's application inventory (`apps.html` Games table,
  `start-menu.html`, and `desktop-2d.html`, which said *four* games), in a
  per-app `AGENTS.md` carrying the **Production** status row, and proven by 53
  headless JUnit 5 cases (`TetrisEngineTest` 18, `TetrisPanelTest` 14,
  `TetrisAudioTest` 8, `TetrominoTest` 7, `TetrisSettingsTest` 6 — the two
  settings-bound suites over a unique throwaway preferences node each, removed
  in `@AfterEach`, because the test JVM does not isolate `java.util.prefs`) plus
  `Desktop2DAppRegistryTest.tetrisIsHostedPanel`; `TetrisAudioTest` compares the
  shipped files against the effects as **one explicit set**, which is the gate
  that keeps "an effect nobody bundled" from becoming a sound nobody hears.
  The 3D surface was then **run, not assumed**: the in-JVM probe recipe mounted the panel in a live
  `Frame3D` and the internal screencapture showed the glassy "Tetris" window
  with a piece falling, its ghost on the floor and the next piece queued — and
  it caught **two defects no headless test could see**: every scoreboard row
  painted its caption twice ("Score:  Score: 0", because `fieldRow` adds the
  caption and the value label repeated it) and the control pad's rotate button
  read "Rot..." at the sidebar's first width. Both are fixed, both fixes are now
  pinned by the two added `TetrisPanelTest` cases (a row's caption must not
  reappear in its own value; no control may be narrower than it asks to be), and
  the re-capture shows the corrected labels. `./gradlew build` green across every
  module (tests + JaCoCo floors) and
  `:lg3d-mandela:repositoryChecks` green over all sixteen gates, with
  `startMenuAudit` now listing **5 items in Games** and "every live descriptor
  resolves"; the *What's New* topic and the Pages release archive were
  regenerated from this changelog in the same PR.
