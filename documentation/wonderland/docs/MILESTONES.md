# Wonderland port — per-milestone parity status

Parity is measured **per milestone** by the headless tests in each Gradle module
plus the in-JVM probe described in the project's contributing notes. No milestone
claims global 100 % parity, and a slice that cannot run headless is reported as
deferred, not as working.

**Legend:** ✅ landed & tested · 🟨 landed & compiling, tests partial · ⏳ not started

| Milestone | Scope | Status | Evidence |
|-----------|-------|--------|----------|
| **M0** | `wonderland-common` data model + `com.jme` shim + JAXB wiring | ✅ landed | 77 unit tests across `com.jme.{math,bounding}`, `Math3DUtils`, `CellTransform`, `ChecksumList` (JAXB round-trip). Floor `[0.30, 0.20]`. |
| **M1** | Wire-protocol codec round trips (`MessagePacker` + every message type) | ✅ landed | 43 codec cases pack/unpack every `MessageID`/envelope and every cell/comms message through the same entry points the server calls. |
| **M2** | Client cell layer: base `Cell`, cache, connections/session, view/avatar | 🟨 M2a–M2f landed; the scene-graph renderer moved to M6 | 164 headless cases at floor `[0.980, 0.950]`. The `client/jme` + `cellrenderer` renderer is the one slice that cannot land headless; `createCellRenderer` returns null, rebuilt at M6. |
| **M3** | `wonderland-server`: Spring Boot session server on the M1 protocol | 🟨 M3a–M3b landed; desktop integration + worlds arrive at M5/M6 | 69 cases at floor `[0.950, 0.840]`. M3a loopback router; M3b real embedded-Tomcat sockets + the three-connections-one-session fan-out. No GlassFish. |
| **M4** | Plugin/module loader + first five low-risk modules | ✅ landed | `ServiceLoader` plugin loader + chat / imageviewer / userlist / stickynote / whiteboard domain models. 50 headless cases. — **[Plugin Loader](PLUGIN_LOADER.md)** |
| **M5** | `collada` / `artimport` / `asset`: read the bundled worlds' models | ✅ landed | COLLADA 1.4.1 reader + deployed-model readers + the `wla://` asset pipeline. 90 new cases (140 total) at floor `[0.980, 0.930]`. — **[Asset Pipeline](ASSET_PIPELINE.md)** |
| **M6** | 2D + 3D desktop integration; rebuild the deferred scene-graph renderer | 🟨 M6a + M6b landed; M6c re-planned as it unblocks | `wonderland-renderer` (a new leaf module on `org.jogamp.java3d` 1.7.2): the pure Collada→mesh / transform / texture-binding conversion is fully unit-tested headless, the probe-only `Shape3D` / `BranchGroup` / `Appearance` assembly is verified by the in-JVM screencapture probe, not claimed in CI. 52 new headless cases at floor `[0.530, 0.560]`. **M6b** adds the avatar renderer: a new `wonderland-avatars` pure resource-jar module shipping three bundled `.dae` bodies, a new `ClasspathWorldStore` beside M5's `DirectoryWorldStore`, an optional `avatarModelUrl` wire attribute on `AvatarCellServerState`, a static `AvatarRendererInstallers` slot in `wonderland-client` so `AvatarCell` delegates to a renderer without importing `wonderland-renderer` back, and the M6b pure half (`AvatarLoadResolver`, `AvatarTriggerState`, `AvatarTransformFeed`, `AvatarRendererFactory`) — **97 new headless cases** across all five modules; the renderer floor stays at `[0.530, 0.560]`. — **[Renderer](RENDERER.md)** |
| **M7** | High-risk features: VoIP, avatar/animation, videoplayer, pdfviewer | ✅ landed (M7a + M7b + M7c + M7d) | **Nothing is descoped** — each obsolete technology lands with a modern replacement: M7a JMF → OpenJFX Media; M7b jpedal → Apache PDFBox; **M7c** jME-1.x's `Animator` + the unrecoverable `.jme` pool → a COLLADA-driven keyframe animator (`ColladaAnimation` raw parse in `wonderland-modules`; a pure `animation` package in `wonderland-renderer` — tracks, clips, the stride-4 axis+angle factory, synthetic fallbacks, the loop/gesture/alias director — 77 headless cases incl. the real shipped `humanoid-a.dae` bytes end-to-end; the live `AvatarRenderer` nests per-node LOCAL `TransformGroup`s on a 33 ms daemon clock wired to container mount/unmount; renderer floor `0.530/0.560 → 0.660/0.660`). **M7d** the VoIP softphone: `stun.jar` → **ice4j** (`lg3d-apps` `Ice4jNatProbe`), winp → `java.lang.ProcessHandle`, JMF audio → **Java Sound + an in-tree G.711** (Sun's public-domain `g711.c` ported) — a pure, dependency-free `softphone` package in `wonderland-modules` (the `SoftphoneControl` state machine, RTP per RFC 3550, the jitter buffer, the command/event vocabularies) plus the live sinks in `lg3d-apps` (`ExternalSoftphoneBackend`, `JavaSoundVoiceBridge`), **99 new headless cases** (88 + 11); the `wonderland-modules` floor rises to `[0.985, 0.945]`. No live audio, process or STUN call is claimed from CI — those paths are probe-verified. — **[Renderer](RENDERER.md)** |

## A note on what "landed" means

A ✅ row means the *headless, tested* slice of that milestone landed — the domain
logic, the protocol, the data model, the reader. It does **not** mean the feature
is visible in a running 3D desktop. Every milestone so far keeps the same
discipline: the rendering / scene-graph presentation is deliberately deferred to
**M6**, where it is rebuilt on the already-migrated Jogamp stack and verified with
the in-JVM probe, and no PR claims rendering it did not prove.

Back to the **[overview](README.md)**.
