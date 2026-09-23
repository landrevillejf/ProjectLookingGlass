# LG3D Help Application

> Role-aware per-app guide. Module: [`lg3d-demo-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (Help Center) + a legacy sample front end (`Lg3dHelp`) |
| Entry points | `HelpCenter.main` → `TitledSwingWindow.show(...)` hosting `HelpCenterPanel`; `Lg3dHelp.main` (legacy static-image sample) |
| Surface | **SwingNode-in-Frame3D** (Help Center); also hosted in the 2D desktop via `Desktop2DAppRegistry.PANEL_APPS` |
| Start-menu name / group | Help Center / **Utilities** (the legacy `Lg3dHelp` = "Simple Sample Help", a taskbar `ApplicationDescription`, not a start-menu item) |
| Command | `java org.jdesktop.lg3d.apps.help.HelpCenter` |
| Descriptors | `src/config/helpcenter.lgcfg` (production) + `src/config/help.lgcfg` (legacy sample) → `config/demo` |
| Build | `./gradlew :lg3d-demo-apps:build` (search index: `:lg3d-demo-apps:generateHelpSearchIndex`) |

**Components:** `HelpCenterPanel` (JavaHelp `javax.help:javahelp:2.0.05`, 14 HTML
topics) + the generated search index; `HelpContentTest` guards topic content.

## Roles

- **Architect** — Two front ends live here: the **production `HelpCenter`/
  `HelpCenterPanel`** (JavaHelp-backed, start-menu registered, reused in the 2D
  desktop) and the **legacy `Lg3dHelp`** sample (a static image, kept for history).
  New work targets `HelpCenter`; do not extend `Lg3dHelp`. The JavaHelp search index
  is generated at build time (`generateHelpSearchIndex`), not hand-maintained.
- **Engineer / Developer** — Follow the core UI/UX rulebook for the SwingNode host
  (offscreen paint, no modal dialogs, EDT hops, `dispose()`, Metal LAF). Help topics
  are HTML under the app's resources; after editing topics, regenerate the search
  index. Keep `HelpCenterPanel` plain Swing so the 2D desktop can host it. Jogamp only.
- **QA** — `HelpContentTest` runs headless and asserts topic content/links. Verify
  the hosted window and topic navigation with the in-JVM probe + internal
  screencapture; a black host capture under Wayland is not a defect. Confirm the
  search index is regenerated when topics change.
- **Business Analyst** — Help Center is the shipped desktop user guide (complete,
  discoverable under Utilities). Production standards apply; the legacy sample has no
  user value and exists only for reference.
- **Functional Analyst** — Spec user-visible function (browse/search 14 topics,
  navigate links) plus the contract with core (SwingNode surface, PANEL_APPS reuse,
  descriptor fields). Clearly separate `HelpCenter` (product) from `Lg3dHelp` (sample).
- **Project Manager** — Commit scope `lg3d-demo-apps`. Done = build (+ regenerated
  index) + `./run-lg3d.sh` + evidence. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency ordering.
  2D: the JavaHelp Swing panel, identical in the 2D desktop. Keep topic styling
  consistent with the desktop look.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-demo-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

## Overview

This package holds two help front ends:

- **`Lg3dHelp`** - the legacy 2006-era *Simple Sample Help*: a preliminary app
  that displays the LG3D usage guide as a static image in a custom-decorated 3D
  window. It demonstrates custom window decoration, transparency effects and
  thumbnail implementation, and is kept as a scene-graph example.
- **`HelpCenter` / `HelpCenterPanel`** - the real desktop user guide, a
  **JavaHelp** (`javax.help:javahelp:2.0.05`) `JHelp` viewer embedded in a plain
  Swing panel. See [Help Center (JavaHelp)](#help-center-javahelp) below.

The rest of this file documents the legacy `Lg3dHelp` sample.

## Help Center (JavaHelp)

- **`HelpCenterPanel`** - a plain Swing `JPanel` (no Java 3D) that loads the
  HelpSet from the classpath
  (`org/jdesktop/lg3d/apps/help/helpcontent/lg3d-help.hs`) via
  `HelpSet.findHelpSet` + `new HelpSet(loader, url)` and embeds a
  `javax.help.JHelp` viewer in a `BorderLayout`. It degrades to a readable
  "content unavailable" pane instead of throwing, so a broken bundle can never
  take down the hosting window.
- **`HelpCenter`** - the 3D-desktop entry point: mirrors `Calculator`, calling
  `TitledSwingWindow.installHostedLookAndFeel()` then
  `TitledSwingWindow.show("Help Center", new HelpCenterPanel(), WIDTH_PX,
  HEIGHT_PX)` to host the panel on a `SwingNode` inside a `Frame3D`.
- **2D/Swing desktop** - `Desktop2DAppRegistry.PANEL_APPS` maps
  `org.jdesktop.lg3d.apps.help.HelpCenter` ->
  `org.jdesktop.lg3d.apps.help.HelpCenterPanel`, so the *same* panel is hosted as
  an MDI internal frame; the 3D wrapper is never loaded there.
- **Content** lives under `helpcontent/`: `lg3d-help.hs`, `map.jhm`, `toc.xml`,
  `index.xml`, `lg3d-help.css` and fourteen HTML topics. Non-`.java` files under
  `org/**` are bundled by the module's resources source set, so they ship inside
  `lg3d-demo-apps.jar`.
- **Full-text search** - the Search navigator needs a generated `JavaSearch`
  database. The `:lg3d-demo-apps:generateHelpSearchIndex` task runs JavaHelp's
  own indexer (`com.sun.java.help.search.Indexer -db <dir> <topics...>`) over the
  HTML at build time; `processResources` copies the result beside the HelpSet.
- **Dependencies** - JavaHelp is declared in `gradle/libs.versions.toml`, added to
  `lg3d-demo-apps` as `implementation`, and resolved onto the hand-assembled
  `:lg3d-core:run` classpath as a detached configuration (the run classpath does
  not inherit the demo-apps dependencies).
- **Testing** - `HelpContentTest` (`src/test/java`, headless) asserts the HelpSet
  parses with the expected title and the TOC/Index/Search navigators, that every
  map target resolves to a topic URL, and that `JHelp` constructs under JDK 21.
- **Start menu** - `helpcenter.lgcfg` (Utilities group); the legacy
  `help.lgcfg` (Simple Sample Help) is unchanged.

## Purpose

- Display LG3D usage guide to users
- Demonstrate custom window chrome (close/minimize buttons)
- Showcase transparency and visual effects
- Provide example of custom Thumbnail implementation

## Key Components

- **Lg3dHelp** - Main Frame3D with custom decoration
- **Button** - Custom 3D button with hover effects
- **ButtonAppearance** - Appearance subclass for button textures
- **HelpThumbnail** - Custom Thumbnail for taskbar/dock
- **GlassyPanel** - Glass-like decorative panel
- **FuzzyEdgePanel** - Panel with soft edges
- **RectShadow** - Rectangular shadow effect

## Architecture

### Scene Graph Structure
```
Lg3dHelp (Frame3D)
└── Component3D
    └── TransparencyOrderedGroup
        ├── RectShadow (shadow)
        ├── GlassyPanel (decoration)
        ├── FuzzyEdgePanel (body with help image)
        ├── Button (close button)
        └── Button (minimize button)
```

### Custom Window Decoration

The app opts out of automatic Frame3D window decoration:
```java
setProperty(Frame3DWindowDecoration.OPT_OUT_PROPERTY, Boolean.TRUE);
```

This allows custom button placement and styling.

### Button Behavior

Buttons implement two effects on mouse enter:
1. **Appearance Change**: Switches between off/on appearances
2. **Scale Animation**: Scales up by 15% (buttonOnSize / buttonSize)

```java
private Button(float sizeOff, Appearance appOff,
               float sizeOn, Appearance appOn) {
    // Add appearance change action
    addListener(new MouseEnteredEventAdapter(
        new AppearanceChangeAction(shape, appOn)));
    // Add scale action
    addListener(new MouseEnteredEventAdapter(
        new ScaleActionBoolean(this, sizeOn/sizeOff, 100)));
}
```

## Development Guidelines

### Sizing

- **Body Size**: 50% of screen height (square)
- **Decoration Width**: 0.005f border on all sides
- **Button Size**: 0.005f (0.00575f when hovered)
- **Thumbnail Scale**: 0.13f of full size

### Transparency

- Uses `TransparencyOrderedGroup` for proper rendering order
- Button appearances have alpha values (0.6f - 0.8f)
- Thumbnail uses 0.75f alpha

### Image Loading

```java
URL imageFilename = getClass().getResource("resources/images/lg3d-wm-usage.png");
SimpleAppearance app = new SimpleAppearance(1.0f, 1.0f, 1.0f,
    SimpleAppearance.ENABLE_TEXTURE | SimpleAppearance.DISABLE_CULLING);
app.setTexture(imageFilename);
```

## Best Practices

- **Appearance Sharing**: Make appearances non-static when using translucency effects to avoid visual issues between instances
- **Opt-Out Property**: Use `Frame3DWindowDecoration.OPT_OUT_PROPERTY` when implementing custom chrome
- **TransparencyOrderedGroup**: Use for proper rendering of overlapping transparent objects
- **Capability Setting**: Set `Shape3D.ALLOW_APPEARANCE_WRITE` for dynamic texture updates
- **Shadow Offsets**: Adjust N/E/S/W/I offsets for desired shadow depth

## Dependencies

- LG3D Core: Frame3D, Component3D, TransparencyOrderedGroup
- LG3D Utils: GlassyPanel, FuzzyEdgePanel, RectShadow, SimpleAppearance, ImagePanel
- LG3D SceneManager: Frame3DWindowDecoration
- Java 3D (Jogamp): Texture2D, Appearance, TransparencyAttributes
- Event Adapters: MouseClickedEventAdapter, MouseEnteredEventAdapter

## Resources

- Help image: `resources/images/lg3d-wm-usage.png`
- Close button: `resources/images/button/window-close.png`
- Minimize button: `resources/images/button/window-minimize.png`

## Testing

Launch standalone:
```bash
./gradlew :lg3d-demo-apps:run -Papp=help
```

## Extension Points

> The items below describe how the *legacy* `Lg3dHelp` sample could evolve. They
> are all already realized by the JavaHelp **Help Center** (dynamic HTML content,
> TOC/index/search navigation, localization via translated HelpSets), so new work
> should extend `HelpCenter`/`HelpCenterPanel` and the `helpcontent/` bundle
> rather than this static-image app.

- **Dynamic Content**: Replace static image with rendered HTML or text
- **Navigation**: Add back/forward buttons for multi-page help
- **Search**: Implement search functionality
- **Interactive Elements**: Make help content clickable/interactive
- **Localization**: Support multiple languages

## Known Limitations

- Static image only (no interactive content)
- Preliminary implementation (not fully evolved)
- No navigation between help topics
- Fixed size (not responsive to content)
- No search or indexing
