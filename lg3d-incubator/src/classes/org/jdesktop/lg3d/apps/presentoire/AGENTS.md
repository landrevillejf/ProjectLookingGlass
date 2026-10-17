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
| Formats | `presentoire.format` — `DeckCodecs` sniffs a deck by magic bytes and reads/writes four formats through the one model: native `.zip`, `.pptx` + legacy `.ppt` (Apache POI XSLF/HSLF), and pure-Java `.odp` (OpenDocument); `SlideGeometry` fits an office page inside the square `-0.1..0.1` slide |
| Surface | **3D slide viewer** (`Presentoire`/`PresentoireDocument`/`Slide`, `elements/` `ImageElement`/`ListElement`/`TextElement`/`ElementFactory`, `transition/` `Scale`/`Rotation`/`Transparency`/`Script` + `TransitionFactory`, `Colors`) **and** a 2D Swing document viewer (toolbar, scrollable slide canvas, status line) |
| Start-menu name / group | Presentoire / **Office** — descriptor `presentoire.lgcfg` lives in `lg3d-apps/src/config` (→ `config/demo`, scanned); the incubator's own `src/config/presentoire.lgcfg` is a legacy unscanned stub |
| Command | `java org.jdesktop.lg3d.apps.presentoire.Presentoire` |
| Icon | the app's own bundled `resources/images/icon/presentoire.png`, resolved from the incubator jar by absolute package path (the Periodic Table precedent) |
| Runtime notes | Opens on a built-in 16-slide *Looking Glass 3D and 2D* deck (`LOOKING_GLASS_XML`, text/vector-only); a real deck (`.zip`/`.pptx`/`.ppt`/`.odp`) is chosen with a standard `JFileChooser` and routed through `DeckCodecs.open` (the 2006 modal `FileChooser`/`JDialog` hack and its hardcoded `/home/debian/...` path are gone). The 2D panel's **Save As…** exports the open deck to any of the four; the 3D `Frame3D` stays view-only (no save affordance). Design docs in `lg3d-incubator/docs/presentoire/` |
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
  A second seam, `presentoire.format`, converts foreign decks to and from that
  same model: `DeckCodecs.open` sniffs the format by magic bytes (not extension)
  and `DeckCodecs.export` writes it back, so an imported `.pptx`/`.ppt`/`.odp`
  becomes a `PresentoireModel` both faces render identically. Fidelity is bounded
  to the Presentoire vocabulary — text blocks, bullet lists and pictures; themes,
  custom fonts, animations, tables, charts and grouped/rotated shapes are dropped
  on import and never emitted on export.
- **Engineer / Developer** — Obey the core UI/UX rulebook (upload texture pixels
  before attach for image slides, wrap nodes in `Component3D`, sort translucency).
  Keep `presentoire.model` plain-Java (no AWT, no Java 3D) so it unit-tests headless.
  The panel wears only LAF colours — the sole painted pixels are inside the rendered
  slide bitmap, which is document *content* (a white page and the deck's own ink),
  exactly as a PDF page is. `Transition.runTransition` is private, so subclass
  animations never ran even in 2006; slides advance by the base swap — preserve that
  reliable behaviour rather than activating unverified scene-graph animation. Jogamp
  only; dev mode routes no keyboard focus, so the 3D face is click-driven.
  Keep `presentoire.format` headless too: the codecs are pure ZIP+DOM
  (`.zip`/`.odp`) or Apache POI text/picture extraction returning `byte[]`
  (`.pptx`/`.ppt`) — no rendering, so they unit-test without a display. Images
  ride in memory via `PresentoireModel.of(...)` + its `inlineImages` map, so an
  imported/exported deck is no longer tied to an on-disk `ZipFile`.
  `SlideGeometry` maps an office page onto the square slide fit-inside,
  aspect-preserving and centred (Y flips: a page grows down, the slide grows up),
  mirroring `SlideRenderer`'s bottom-left (text/list) and centre (image)
  anchoring so an import→export round trip lands where the renderer already
  draws. POI is the only new dependency; the bundled `ext/commons-io-1.0.jar` is
  filtered off the incubator `test` classpath so it cannot shadow POI's
  `commons-io` (see `lg3d-incubator/build.gradle`).
- **QA** — headless: `PresentoireModelTest` (14 — the pure parser, the `ModelColor`
  `/255` fix + `toHex`, the JavaScript-transition no-throw fix, the real-ZIP image
  round-trip, the 16-slide default `LOOKING_GLASS_XML` deck and `of(...)` inline
  images), `PresentoirePanelTest` (11 — the `SlideRenderer` white-page/ink raster,
  navigation/zoom clamps, open/reject, and per-format open + `saveFile` export), and
  the `presentoire.format` codec suite: `DeckCodecsTest` (9 — magic-byte sniffing +
  routing), `NativeDeckCodecTest` (6), `PptxDeckCodecTest` (3), `PptDeckCodecTest`
  (3), `OdpDeckCodecTest` (4) and `SlideGeometryTest` (7 — the fit-inside inverse
  transform). Each codec round-trips a built deck (import→assert, export→re-open).
  The 3D view still needs the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); a black host capture under Wayland is not a defect.
  Report that evidence rather than claiming an untested 3D success.
- **Business Analyst** — A slide-deck viewer that reads AND writes Microsoft
  PowerPoint (`.pptx` and legacy `.ppt`) and LibreOffice Impress (`.odp`) beside
  its own native `.zip`, now a reachable Office-menu productivity app on both
  desktops rather than a dormant prototype. Opens on a built-in *Looking Glass 3D
  and 2D* deck so it is never an empty frame and ships a real presentation of the
  desktop it runs on.
- **Functional Analyst** — Spec: document → slides → elements (text / image /
  bullet list) → transitions, plus the file-open and Save-As-export flows across
  the four formats. Record that the descriptor lives in `lg3d-apps/src/config`
  (Office group), that the built-in Looking Glass deck is the default document,
  that a malformed/unknown deck is reported on the status line without losing the
  open one, that the status line names the detected source format, and that export
  is a 2D-panel affordance only.
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
