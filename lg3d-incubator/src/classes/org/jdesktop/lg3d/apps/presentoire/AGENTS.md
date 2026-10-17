# Presentoire (Presentation Viewer)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Live** — resurrected and ported to JDK 21 (2026, Jean-Francois Landreville); start-menu registered on both desktops |
| Entry point | `presentoire.Presentoire` (`public static void main`, native-3D `Frame3D`) |
| 2D face | `presentoire.PresentoirePanel` (plain Swing slide viewer), hosted as an MDI frame via `Desktop2DAppRegistry.PANEL_APPS` keyed on the 3D main class |
| Shared model | `presentoire.model` — the AWT-free / Java 3D-free seam (`PresentoireModel`, `SlideModel`, `SlideElement`, `ModelColor`, `ScriptTransitionModel`) both faces read; `SlideRenderer` rasterises a slide with Java 2D for the panel |
| Surface | **3D slide viewer** (`Presentoire`/`PresentoireDocument`/`Slide`, `elements/` `ImageElement`/`ListElement`/`TextElement`/`ElementFactory`, `transition/` `Scale`/`Rotation`/`Transparency`/`Script` + `TransitionFactory`, `Colors`) **and** a 2D Swing document viewer (toolbar, scrollable slide canvas, status line) |
| Start-menu name / group | Presentoire / **Office** — descriptor `presentoire.lgcfg` lives in `lg3d-apps/src/config` (→ `config/demo`, scanned); the incubator's own `src/config/presentoire.lgcfg` is a legacy unscanned stub |
| Command | `java org.jdesktop.lg3d.apps.presentoire.Presentoire` |
| Icon | the app's own bundled `resources/images/icon/presentoire.png`, resolved from the incubator jar by absolute package path (the Periodic Table precedent) |
| Runtime notes | Opens on a built-in demo deck; a real deck is chosen with a standard `JFileChooser` (the 2006 modal `FileChooser`/`JDialog` hack and its hardcoded `/home/debian/...` path are gone). Design docs in `lg3d-incubator/docs/presentoire/` |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — One AWT-free model, two renderers. `presentoire.model` parses the
  deck ZIP (`content.xml` + images) once; the native-3D `PresentoireDocument`/`Slide`
  build `Component3D` nodes from it and `SlideRenderer`/`PresentoirePanel` paint the
  very same `SlideModel` tree with Java 2D, so the two desktops can never disagree
  about a deck. The JDK 21 fix lives here: the 2006 original built a JavaScript
  `ScriptEngine` *while parsing*, so any deck with a `language="JavaScript"`
  transition threw at load (Nashorn was removed in JDK 15). The model now parses a
  `<transition>` to inert `ScriptTransitionModel` data and the 3D `ScriptTransition`
  degrades to a plain slide swap when no engine is present — it never throws.
- **Engineer / Developer** — Obey the core UI/UX rulebook (upload texture pixels
  before attach for image slides, wrap nodes in `Component3D`, sort translucency).
  Keep `presentoire.model` plain-Java (no AWT, no Java 3D) so it unit-tests headless.
  The panel wears only LAF colours — the sole painted pixels are inside the rendered
  slide bitmap, which is document *content* (a white page and the deck's own ink),
  exactly as a PDF page is. `Transition.runTransition` is private, so subclass
  animations never ran even in 2006; slides advance by the base swap — preserve that
  reliable behaviour rather than activating unverified scene-graph animation. Jogamp
  only; dev mode routes no keyboard focus, so the 3D face is click-driven.
- **QA** — `PresentoireModelTest` (11) and `PresentoirePanelTest` (9) are headless:
  they cover the pure parser, the `ModelColor` `/255` fix, the JavaScript-transition
  no-throw fix, the real-ZIP image round-trip, the `SlideRenderer` white-page/ink
  raster and the panel's navigation/zoom clamps and open/reject flow. The 3D view
  still needs the in-JVM probe + internal screencapture (`lg3d-core/lgscreen-*.png`);
  a black host capture under Wayland is not a defect. Report that evidence rather
  than claiming an untested 3D success.
- **Business Analyst** — A slide-deck viewer for Presentoire `.zip` decks, now a
  reachable Office-menu productivity app on both desktops rather than a dormant
  prototype. Opens on a built-in demo deck so it is never an empty frame.
- **Functional Analyst** — Spec: document → slides → elements (text / image /
  bullet list) → transitions, plus the file-open flow. Record that the descriptor
  lives in `lg3d-apps/src/config` (Office group), that the demo deck is the default
  document, and that a malformed deck is reported on the status line without losing
  the open one.
- **Project Manager** — Commit scope `lg3d-incubator`; the PR also spans `lg3d-apps`
  (descriptor, About attribution) and `lg3d-core` (`PANEL_APPS` + registry test).
  Attribution for the port and subsequent modifications is Jean-Francois Landreville
  (file headers, `@author`, commit sign-off and the About box's ported-applications
  line).
- **UI/UX (3D & 2D)** — **3D slides** (images/text/lists) rendered as scene-graph
  nodes with the glassy vocabulary and depth ordering; **2D** an idiomatic document
  viewer (toolbar, centred scrollable slide, status line) in LAF colours. Both read
  the one model, so content is identical across desktops.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
