# CallViewer Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Sample / preliminary** developer tool (3D source visualizer) — not a shipped utility |
| Entry point | `SourceViewer.main` |
| Surface | **pure-3D `Frame3D`** (source rendered into textures, call lines in 3D) |
| Start-menu name / group | Source Viewer — the descriptor sets `ignoreConfig=true`, so it is **read but not posted** to the start menu |
| Command | `java org.jdesktop.lg3d.apps.callviewer.SourceViewer` |
| Descriptor | `src/config/callviewer.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

**Components:** `SourceWindow` / `SourceTexture` / `LineData` / `CalledByData` /
`Line3D` / `SourceViewer`; red = direct call lines, blue = indirect.

## Roles

- **Architect** — An experimental visualizer: source text is rasterized into
  **power-of-two textures** and call relationships are drawn as 3D lines. Treat it as
  a proving ground for texture/line techniques, not a production feature.
- **Engineer / Developer** — Obey the core UI/UX rulebook, especially **upload
  texture pixels before attaching** (else NPE) and power-of-two texture sizes. Wrap
  raw `Node`s in `Component3D`; sort translucency for the call lines. Jogamp only.
- **QA** — Verify with the in-JVM probe + internal screencapture; check the log for
  texture NPEs before calling a view broken. Preliminary code — expect rough edges.
- **Business Analyst** — Developer-facing sample; no end-user product value. Keep
  expectations aligned: it demonstrates a technique.
- **Functional Analyst** — Spec as a demonstration (render a source file, draw its
  call graph). The descriptor's `ignoreConfig=true` keeps it out of the start menu by
  design; flip that only if it is ever promoted to a shipped tool.
- **Project Manager** — Commit scope `lg3d-apps`. Low priority; changes are
  opportunistic. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — **3D only**: textured source panes + coloured call lines in
  a `Frame3D`. Follow the glassy vocabulary and depth/overlay rules from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

## Overview

CallViewer is a source code visualization tool that displays Java source files as 3D windows with call graph relationships. It renders source code as textures and draws 3D lines connecting method calls between files.

## Purpose

- Visualize source code structure in 3D space
- Display call relationships between source files
- Provide preliminary help/usage guide functionality

## Key Components

- **SourceWindow** - Main 3D frame displaying a source file as a texture
- **SourceTexture** - Custom Texture2D subclass handling power-of-two image scaling
- **LineData** - Parses and renders individual source lines
- **CalledByData** - 3D lines connecting caller to callee (red for direct, blue for indirect)
- **Line3D** - 3D line rendering utility
- **SourceViewer** - Entry point and source file management

## Architecture

### Scene Graph Structure
```
SourceWindow (Frame3D)
└── Component3D
    └── FuzzyEdgePanel (with source texture)
        └── CalledByData (Component3D)
            └── Line3D
```

### Texture Generation
1. Source file read line-by-line via BufferedReader
2. Each line rendered to BufferedImage (81 chars width, 2px per line height)
3. Image scaled to power-of-two dimensions for OpenGL compatibility
4. Texture2D created with scaling factors for proper UV mapping

### Call Graph Visualization
- Direct calls: Red lines
- Indirect calls: Blue lines
- Lines connect specific line numbers between source windows
- Uses Transform3D local-to-vworld coordinate conversion

## Development Guidelines

### Adding New Source Files
```java
URL sourceURL = getClass().getResource("path/to/Source.java");
SourceWindow window = new SourceWindow(sourceURL, locationVector3f);
window.addCalledBy(localLineNo, callerWindow, callerLineNo, isDirect);
```

### Geometry Scale
- Default: `0.01f / 81f` (scales 81-character lines to physical units)
- Adjust based on font size and line length requirements

### Texture Constraints
- Images must be power-of-two dimensions for OpenGL
- SourceTexture handles automatic scaling
- Used width/height tracked for proper UV mapping

## Best Practices

- **Texture Memory**: Source files with many lines create large textures; consider pagination for large files
- **Line Positioning**: Use `getLinePos(lineNumber)` for accurate coordinate conversion
- **Transform Caching**: Call `getLocalToVworld()` once per frame if possible
- **Appearance Sharing**: SourceTexture should be per-instance to avoid visual issues with translucency

## Dependencies

- LG3D Core: Frame3D, Component3D, FuzzyEdgePanel, SimpleAppearance
- Java 3D (Jogamp): Texture2D, ImageComponent2D, QuadArray
- Java AWT: BufferedImage, Graphics for texture generation

## Known Limitations

- Preliminary implementation - not fully evolved
- No syntax highlighting (lines rendered as simple gray strokes)
- Fixed character width (81 characters)
- No interactive navigation or search

## Testing

Launch via main method in SourceViewer or integrate with LG3D scene manager:
```bash
./gradlew :lg3d-apps:run -Papp=callviewer
```
