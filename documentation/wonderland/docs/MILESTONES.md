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
| **M6** | 2D + 3D desktop integration; rebuild the deferred scene-graph renderer | ⏳ not started | Also rebuilds the M2 `client/jme` / `cellrenderer` / `AvatarCell` renderer half on `lg3d-core`'s Jogamp bridge. |
| **M7** | High-risk features: VoIP, avatar/animation, videoplayer, pdfviewer | ⏳ not started | May descope per-feature with a written reason. |

## A note on what "landed" means

A ✅ row means the *headless, tested* slice of that milestone landed — the domain
logic, the protocol, the data model, the reader. It does **not** mean the feature
is visible in a running 3D desktop. Every milestone so far keeps the same
discipline: the rendering / scene-graph presentation is deliberately deferred to
**M6**, where it is rebuilt on the already-migrated Jogamp stack and verified with
the in-JVM probe, and no PR claims rendering it did not prove.

Back to the **[overview](README.md)**.
