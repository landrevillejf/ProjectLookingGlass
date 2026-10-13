# Wonderland port — architecture and module layout

The original shipped as `Wonderland.jar` — a 400 MB **installer**, not a source
archive; the classes themselves live in nested jars (client, common, wfs, a
GlassFish server, and 71 NetBeans-style feature modules). There is **no source**
in the tree, so the port is decompiled (IntelliJ-bundled Fernflower) and then
hand-adapted to JDK 21, or — where the technology is unrecoverable — rebuilt from
scratch. See **[Unrecoverable Tech](UNRECOVERABLE_TECH.md)** for that split.

## Subsystem inventory

Grouped by the Gradle module that ends up owning each. Class counts are the
shipped nested-jar figures, not the port's source-file counts.

| Subsystem | Classes | Target module | Port note |
|-----------|---------|---------------|-----------|
| **Data model** (cell state, messages, module metadata, WFS types) | ~182 | `wonderland-common` | vecmath (→ `org.jogamp.vecmath`) + JAXB only; ported in M0. |
| **jMonkeyEngine 1.x** (`com.jme.*`) | ~50 used | `wonderland-common` | **Not bundled** (was fetched over JNLP). Reimplemented as a minimal shim on `org.jogamp.vecmath`. |
| **Client: cell / comms / view / asset** | ~565 | `wonderland-client` | Ported across M1/M2/M5; the `jme`/`cellrenderer` scene-graph half was rebuilt at M6 as `wonderland-renderer` + `wonderland-desktop`. |
| **Session / host server** | 26,206 (GlassFish) + SGS + MINA | `wonderland-server` | **Unrecoverable.** Rebuilt on **Spring Boot 3** in M3; no GlassFish/SGS/MINA port. |
| **WFS wire types** | ~44 | `wonderland-common` (types) | JAX-RS-annotated data types landed in M0; the calls land with the server. |
| **Feature modules** (NetBeans) | 71 jars | `wonderland-modules` | NetBeans module layer **unrecoverable**; reimplemented in M4 as a plain `ServiceLoader` loader. |
| **Desktop embedding** (`scenemanager`, `hud`, `input`) | — | `wonderland-desktop` + `lg3d-apps` | Rebuilt at **M6c** on `lg3d-core`'s own scene graph: the bridge lives in `wonderland-desktop`, the Swing/`SwingNode` panels + the start-menu launchers in `lg3d-apps` (the app layer, per the repo's module convention). |
| **Bundled worlds** | 7 world jars | loaded at M5 | Collada + texture assets, read via the M5 pipeline — **[Asset Pipeline](ASSET_PIPELINE.md)**. |

## Target module layout

The port follows the repository's dependency direction: pure layers stay
testable without a display, and nothing imports *up* into the desktop SDK.
Only `wonderland-desktop` touches `lg3d-core`, and it does so one-way —
`lg3d-core` imports no Wonderland type.

```mermaid
graph TD
    modules[wonderland-modules] --> common[wonderland-common]
    client[wonderland-client] --> common
    server[wonderland-server] --> common
    renderer[wonderland-renderer] --> client
    renderer --> modules
    renderer --> avatars[wonderland-avatars]
    desktop[wonderland-desktop] --> client
    desktop --> renderer
    desktop --> core[lg3d-core]
    apps[lg3d-apps] --> desktop
    apps --> core
    common --> vecmath[org.jogamp.vecmath + JAXB]
```

```
wonderland-common/   data model only; vecmath + JAXB; no lg3d dependency
wonderland-client/   cell / comms / view; depends on wonderland-common
wonderland-server/   session / host; depends on wonderland-common; rebuilt, no GlassFish
wonderland-modules/  feature plugins + the asset pipeline; depends on wonderland-common
wonderland-renderer/ the Collada / avatar scene-graph renderers (M6a / M6b / M7c)
wonderland-avatars/  the bundled .dae avatar pool + catalog; a pure resource jar
wonderland-desktop/  the scene-graph bridge into the lg3d desktop (M6c); the ONLY module
                     depending on lg3d-core, and strictly one-way
lg3d-apps/           not a Wonderland module: the desktop-facing half — the world browser,
                     the mount HUD, the live sinks (media / PDF / softphone) and the
                     .lgcfg start-menu launchers — per the repo's app-layer convention
```

Every shipped Wonderland module:

- inherits `java-library` + `jacoco` + `checkstyle` + `cyclonedx` from the root
  Gradle build;
- declares a `coverageFloors` entry, raised per PR as tests accumulate;
- must not `import javax.vecmath.*`, `javax.media.j3d.*`, `com.sun.j3d.*` or
  `sun.*` — enforced by the `legacy-j3d.mnd` repository gate;
- runs on the pinned JDK 21 toolchain.

The `wonderland-modules` layer depends **only** on `wonderland-common` —
no `lg3d-core` / Java 3D import. That is deliberate: the scene graph that
actually *renders* a loaded model lives in `wonderland-renderer` /
`wonderland-desktop` (M6), rebuilt on `lg3d-core`'s Jogamp bridge, so the
pure layers keep running headless in CI.

Continue to **[Milestones](MILESTONES.md)** for how this layout was landed step
by step.
