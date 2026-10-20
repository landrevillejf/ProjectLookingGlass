# Wonderland JDK 21 / Jogamp Port

## Reality check (evidence, not opinion)
- `wonderland/Wonderland.jar` is a **400 MB installer**, **no source**, **class version 49 = Java 5**.
- App surface = **~791 Wonderland classes** in `wonderland-client.jar` (565: `jme` renderer, `cell`, `comms`, `assetmgr`, `hud`, `login`, `softphone`, `input`, `scenemanager`) + `wonderland-common.jar` (182) + `wonderland-wfs.jar` (44) + **71 NetBeans-style module jars**.
- Bundled third-party/legacy server stack: **GlassFish Embedded 3.0** (26,206 classes), **Sun Game Server (SGS)**, **MINA**, **winp** (JNI), **stun**.
- Only in-tree legacy refs are `javax/vecmath`, `java/awt`, `javax/swing`; the Java 3D scene graph was pulled over **Java Web Start / JNLP at launch** (removed in JDK 9+).
- **Feasible**: decompiler is present (`.../intellij-idea-ultimate/plugins/java-decompiler/lib/java-decompiler.jar`, Fernflower `ConsoleDecompiler`).
- Scope is team-scale / many months. No milestone will claim global "100% parity"; parity is measured per-milestone by tests + the in-JVM probe described in `lg3d-core/AGENTS.md`.

## Standing decisions baked in (veto at confirm)
1. **Unrecoverable tech is rebuilt, not ported.** JNLP->plain `main()` launched via a `run-wonderland.sh` / `:lg3d-core:run`-style classpath; `javax.media.j3d`/`com.sun.j3d`/`javax.vecmath` -> house `org.jogamp.java3d`/`org.jogamp.vecmath` (shims already in `lg3d-core/src/contrib`); GlassFish+SGS+MINA -> a **modern session server** (start: JDK `java.net`/`java.nio` length-prefixed TCP or WebSocket; single-user loopback first, socket later); `winp` -> `java.lang.ProcessHandle`; `darkstar` + `sas`/`sasxremwin`/`xremwin` -> **dropped** (their scene/applet functions are covered by the already-migrated Jogamp stack + `SwingNode`).
2. **Module layout** follows the repo's dependency rule (`lg3d-core` is the SDK; nothing imports up into it). New tree, each its own Gradle project so pure layers stay testable without a display:
    - `wonderland-common` (data model; vecmath only) -> no lg3d dep
    - `wonderland-client` (cell/comms/render) -> depends `wonderland-common` + Jogamp + `lg3d-core`
    - `wonderland-server` (session/host) -> depends `wonderland-common`
    - `wonderland-modules` (feature plugins) -> depends `wonderland-client`
3. **Every shipped module** gets `useJUnitPlatform()`, a JaCoCo `coverageFloors` entry (raised per PR as in the root [build.gradle](build.gradle)), and no source may `import` pre-migration packages (enforced by the existing `legacy-j3d.mnd` gate).

## Milestones (each = one green PR off `main`)
### M0 - scaffold + common data model (THIS session's deliverable)
- Register `wonderland-*` projects in [settings.gradle](settings.gradle); add module dirs; wire Jogamp + vecmath deps as in [lg3d-core/build.gradle](lg3d-core/build.gradle).
- Fernflower-decompile `wonderland-common.jar` + `wonderland-wfs.jar` into `wonderland-common/src/main/java`.
- Port to JDK 21: vecmath imports, remove `sun.*`/JNLP refs, generics/`record` cleanup where safe.
- Tests + `./gradlew :wonderland-common:build` **green headless**.
- Write `docs/wonderland-migration.md`: full subsystem inventory, per-module parity status table, and the unrecoverable-tech decision list above.

### M1 - cell/comms model + protocol codecs (no networking)
- Decompile/port `client/cell`, `client/comms` message types into `wonderland-common`/`wonderland-client`; round-trip tests for every codec.

### M2 - scene-graph renderer on Jogamp
- Port `client/jme` + `cellrenderer` to `org.jogamp.java3d`; verify with the in-JVM probe + internal screencapture (report evidence, do not claim untested success).

### M3 - multiplayer session server (keep multiplayer)
- `wonderland-server` speaks the M1 protocol over TCP/WebSocket; M3a single-JVM loopback, M3b real sockets; concurrency + reconnect tests.

### M4 - plugin/module loader + first modules
- Reimplement the NetBeans module layer as a plain ServiceLoader/classpath loader; port low-risk modules first: `text-chat-module`, `imageviewer`, `user-list`, `stickynote`, `whiteboard`.

### M5 - asset pipeline + bundled worlds
- `colladaloader`/`artimport`/`assetmgr`; load the 7 `wonderland-*-world.jar` scenes.

### M6 - 2D + 3D desktop integration
- 3D: `Frame3D`/scene-graph embedding; 2D: `SwingNode` MDI internal frame (mirror the update-manager/db-manager pattern in [settings.gradle](settings.gradle)); start-menu `.lgcfg` descriptors + icons per `lg3d-apps` convention.

### M7 - high-risk features (may descope with a written reason)
- `voicebridge`/`softphone` (VoIP), `avatar*`, `animation*`, `videoplayer` (OpenJFX available in-tree), `pdfviewer`.

## Verification per milestone
`./gradlew build` (compile+tests), `./gradlew :lg3d-core:runtimeResources`, `./gradlew :lg3d-mandela:repositoryChecks`, and for any scene-graph change the `lg3d-core/AGENTS.md` probe. Conventional Commit per module, PR to `main` via `gh`, delete branch both sides after merge.

## Git / approval
Feature branch off `main`, module-scoped Conventional Commits, PR via `gh`; **no release/tag ever without explicit approval**. M0 is proposed as the concrete first PR; M1+ proceed only after M0 lands and you re-confirm direction.

## First action on approval (M0 only)
Decompile + scaffold + build `wonderland-common` green and author `docs/wonderland-migration.md`. Nothing beyond M0 is started without a fresh go-ahead.
