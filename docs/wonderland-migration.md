# Wonderland migration: JDK 21 + Jogamp port

This document tracks the ongoing port of **OpenWonderland** (the
`wonderland/Wonderland.jar` installer in the repo root, ~400 MB, Java 5
bytecode, class-file major version **49**) onto the same JDK 21 + Jogamp
foundation that the rest of ProjectLookingGlass uses. It is the standing
reference for what has been ported, what is left, and what will never be
ported because the underlying technology is unrecoverable and must be
rebuilt instead.

The plan this document supports lives in
[the milestone table below](#per-milestone-parity-status): **M0**, **M1** and
**M2** landed (**M2a**–**M2f** — the cell leaf,
the `Cell` base class, the cell cache, the connection/session layer and the
view/avatar movement core, all headless and JaCoCo-gated; the `client/jme` +
`cellrenderer` scene-graph renderer is the one M2 slice that cannot land
headless and is **folded into M6**, where it is rebuilt on `lg3d-core`'s
Jogamp bridge),
and **M3a** has landed (the Spring Boot session server, single-JVM loopback);
**M3b** – **M7** are proposed, not started. Nothing beyond the currently
active milestone proceeds without a fresh go-ahead.

---

## Reality check (evidence, not opinion)

- `wonderland/Wonderland.jar` is **not** a source archive: it is a 400 MB
  installer for the shipped application, and there is **no source** in the
  tree. It is `.gitignore`d and stays out of the repository.
- Application surface, measured from the nested jars:
  - `wonderland-client.jar`  ~565 classes (`jme` renderer, `cell`, `comms`,
    `assetmgr`, `hud`, `login`, `softphone`, `input`, `scenemanager`)
  - `wonderland-common.jar`  ~182 classes (the data model, cell states,
    messages, module metadata, WFS types)
  - `wonderland-wfs.jar`     ~44 classes (JAX-RS-annotated wire types)
  - 71 NetBeans-style module jars (feature plugins)
- Bundled third-party server stack (all pre-JDK 8, all unreachable today):
  **GlassFish Embedded 3.0** (~26,206 classes), **Sun Game Server (SGS)**,
  **MINA**, **winp** (JNI), **stun**.
- Only **three** external Java packages are referenced by Wonderland's own
  classes: `javax.vecmath`, `java.awt`, `javax.swing`. The Java 3D scene
  graph (`javax.media.j3d`, `com.sun.j3d`) and `com.jme` (jMonkeyEngine 1.x)
  were **fetched over Java Web Start / JNLP at launch** and are therefore
  **not in the bundle at all**.
- The `javax.vecmath` package was migrated repo-wide to
  `org.jogamp.vecmath` and `javax.media.j3d` to `org.jogamp.java3d` as part
  of the earlier Looking Glass migration; that migration is enforced by the
  `legacy-j3d.mnd` gate and does not permit `import javax.vecmath.*` or
  `import javax.media.j3d.*` in new code.
- Decompiler available and used: IntelliJ-bundled Fernflower
  (`java-decompiler.jar`, `ConsoleDecompiler`). Everything under
  `wonderland-common/src/main/java/org/jdesktop/wonderland/**` was produced
  this way and is then hand-adapted to JDK 21.

Scope is team-scale and many months. **No milestone claims global "100%
parity"**. Parity is measured per-milestone by the tests in each Gradle
module plus the in-JVM probe described in `lg3d-core/AGENTS.md`; anything
that cannot be exercised headless is reported as untested, not as working.

---

## Subsystem inventory (Wonderland.jar as extracted)

The full inventory of Wonderland's own bytecode, grouped by the Gradle
module that will end up owning each. Files counts are the shipped (nested)
class-file counts, not the number of source files in the port.

| Subsystem | Location (inside Wonderland.jar) | Classes | Target Gradle module | Notes |
|-----------|---------------------------------|---------|----------------------|-------|
| **Data model** | `wonderland-common.jar!/org/jdesktop/wonderland/common/**` | 182 | `wonderland-common` | Cell state, messages, module metadata, WFS types. Only depends on `javax.vecmath` (→ `org.jogamp.vecmath`) and JAXB. Ported in M0. |
| **jMonkeyEngine 1.x** | **NOT BUNDLED** — was JNLP | (~50 used) | `wonderland-common` (in-tree shim) | `com.jme.math`, `com.jme.bounding`, `com.jme.renderer`, `com.jme.scene.state` — reimplemented as a minimal shim on top of `org.jogamp.vecmath`, driven by what the data model actually calls. |
| **Client: cell renderer / comms / asset manager** | `wonderland-client.jar!/org/jdesktop/wonderland/client/**` | ~565 | `wonderland-client` | `cell`, `cellrenderer`, `comms`, `jme`, `input`, `hud`, `login`, `assetmgr`, `scenemanager`, `softphone`. Depends on the jME shim, `lg3d-core`, and Jogamp Java 3D. Ported M1/M2/M5/M7. |
| **Session/host server** | `wonderland-common.jar!/wonderland-server.jar` (GlassFish-based) | 26,206 (GlassFish) + SGS + MINA | `wonderland-server` (rebuilt on Spring Boot) | **Unrecoverable**. Rebuilt in M3 on **Spring Boot 3** (embedded Tomcat + Spring WebSocket): the Wonderland wire protocol is carried over a Spring WebSocket handler and REST controllers; no port of GlassFish / SGS / MINA. Chosen over a hand-rolled `java.net`/`java.nio` server because the client already needs an HTTP / REST surface for WFS (`wonderland-wfs.jar`'s JAX-RS calls) and Spring Boot gives both transports from one lifecycle. |
| **WFS (Web Frame Sharing) wire types** | `wonderland-wfs.jar!/org/jdesktop/wonderland/wfs/**` | ~44 | `wonderland-common` (data types), `wonderland-client` (JAX-RS calls) | JAX-RS-annotated; the *types* (CellDescriptor, CellList, CellPath, WorldRoot, WorldRootList, WFSRecordingList) landed in M0 (no JAX-RS dependency in the annotations Wonderland used). The JAX-RS *calls* land in M3. |
| **Feature modules** (NetBeans) | 71 nested module jars | varies | `wonderland-modules` | NetBeans Platform module layer is **unrecoverable** (no NBM toolchain, no `org.openide`). Reimplemented in M4 as a plain `ServiceLoader` / classpath plugin loader. Low-risk modules port first. |
| **Bundled worlds** | 7 `wonderland-*-world.jar` | data | loaded at M5 | Collada + texture assets. Loaded via the M5 asset pipeline (`colladaloader` / `artimport` / `assetmgr`). |
| **VoIP / softphone** | `client/softphone/**`, `voicebridge`, `stun`, `winp` | varies | `wonderland-modules` (M7, may descope) | stun (dead library), winp (JNI, retired). Rework or explicit descope with written reason. |
| **Legacy Java 3D applets + darkstar** | `darkstar`, `sas`, `sasxremwin`, `xremwin` | varies | **dropped** | Their applet / 2D-window embedding is covered by the already-migrated Jogamp stack + `SwingNode` (see [docs/swingnode.md](swingnode.md)). Dropped in M0 decision list, no reimplementation planned. |

---

## Target module layout

Follows the repo's dependency rule (see `AGENTS.md` at the root):
`lg3d-core` is the SDK, nothing imports *up* into it, and pure layers stay
testable without a display. Each Wonderland target is its own Gradle
project so the pure data-model layer keeps running headless in CI.

```
wonderland-common/     data model only; vecmath + JAXB + Reflections; no lg3d dep
wonderland-client/     cell / comms / render; depends on wonderland-common + Jogamp + lg3d-core
wonderland-server/     session / host; depends on wonderland-common; rebuilt, no GlassFish
wonderland-modules/    feature plugins; depends on wonderland-client; ServiceLoader-based
```

Every shipped Wonderland module:

- inherits `java-library` + `jacoco` + `checkstyle` + `cyclonedx` from the
  root [build.gradle](../build.gradle) `subprojects` block;
- declares a `coverageFloors` entry (raised per PR as tests accumulate);
- must not `import javax.vecmath.*`, `javax.media.j3d.*`, `com.sun.j3d.*`
  or `sun.*` — enforced by the pre-existing `legacy-j3d.mnd` gate;
- runs on the JDK 21 toolchain pinned by the root `subprojects.java` block
  (JDK 21.0.12+1 in `.jdks/`).

---

## Per-milestone parity status

**Legend:** ✅ landed & tested · 🟨 landed & compiling, tests partial · ⏳
not started · ❌ will not be ported (decision below)

| Milestone | Scope | Status | Evidence |
|-----------|-------|--------|----------|
| **M0** | Scaffold + `wonderland-common` data model + jME shim + JAXB wiring | ✅ landed (PR #355) | `./gradlew :wonderland-common:build` green; 77 unit tests across `com.jme.{math,bounding}`, `Math3DUtils`, `CellTransform`, `ChecksumList` (JAXB round-trip), `ScannedClassLoader`. JaCoCo floor raised from [0.15, 0.15] to [0.30, 0.20] with M1. |
| **M1** | Wire protocol codec round trips: `MessagePacker` + every `common/{messages,comms/messages,cell/messages}` type + `client/cell` pure-data types | ✅ landed | 43 new cases (11 in `MessagePackerTest`, 7 in `CommsMessagesCodecTest`, 25 in `CellMessagesCodecTest`) pack each message through `MessagePacker.pack` and read it back through `MessagePacker.unpack` — the same entry points the M3 Spring Boot WebSocket handler will call. `MessageID` Externalizable, `clientID` short, the descriptor-id cache, `WonderlandIdentity`, `SessionInternalConnectionType`, and every `CellServerState` / `CellClientState` concrete subclass resolve back to their own class through Java serialisation. |
| **M2** | `client/jme` + `cellrenderer` on Jogamp `org.jogamp.java3d` | 🟨 M2a–M2f landed; the scene-graph renderer slice moved into M6 | **M2a** landed the self-contained cell *leaf* layer (annotations, SPIs, `CellCreationException`). **M2b** added the first real headless logic: `CellSelectionRegistry`, the `ClientView` / `CellRenderer` contracts and the comms response substrate. **M2c** landed the `Cell` base class headless behind documented seams (`MessageChannel`, the static renderer-type holder, `Cell.class.getClassLoader()`): the tree, the `@ComponentLookupClass` map, the DISK→…→VISIBLE ordinal status walk, `@UsesCellComponent` injection, transform propagation. **M2d** landed the cell cache registry (`CellCacheBasicImpl`, the `MessageSink` reparent seam, the thread-local active cell), `CellStatistics`, `EnvironmentCell`. **M2e** landed the connection/session layer — `BaseConnection` (status pair, "Not connected" guard, request-reply routing), the three cell connections (hierarchy fan-out, the delivery demultiplexer's queue-before-receiver race, the typed edit channel making `MessageSink` real), `ChannelComponent` making `MessageChannel` the loaded cell's actual channel, and `CellClientSession` wiring them over an in-JVM loopback `WonderlandSession` (the M3 endpoint's stand-in; SGS's login surface dropped, rebuilt in M3) — 131 headless cases, floor `[line: 0.975, branch: 0.945]`. **M2f** landed the view/avatar movement core headless: `Throttle` (with the 2006 quirk that `clear()` cannot cancel — the `ScheduledFuture` is never stored), `MovableComponent` (throttled move request, echo suppression by sender identity, LOCAL/REMOTE apply, move-modified and error reply paths; the `@UsesCellComponent` injection is a documented seam — the channel resolves from `cell.getChannel()` at the ACTIVE rung), `MovableAvatarComponent` (trigger payload, UNthrottled trigger path, `Not supported` modified-listener), `ViewCell` + `LocalAvatar` (the session owns its avatar; `viewCellConfigured` fires across the ACTIVE/INACTIVE rungs) and the `AvatarCell` headless shell — `createCellRenderer` returns null because the `client/jme` `AvatarJME` stack is rebuilt at **M6** on `lg3d-core`'s Jogamp bridge and is *not* claimed here; `CellCache.getSession()`/`setViewCell()` were restored with the original's unconditional roots-to-VISIBLE walk pinned as a quirk — 164 cases, floor `[line: 0.980, branch: 0.950]`. |
| **M3** | `wonderland-server`: **Spring Boot 3** session server speaking M1's protocol (embedded Tomcat + Spring WebSocket + REST controllers) | 🟨 M3a landed; M3b (real sockets) not started | **M3a** rebuilt the GlassFish / SGS / MINA / JAX-RS server as a Spring Boot 3 module — `WonderlandServerApplication` (the one lifecycle owning embedded Tomcat, the `/comms` WebSocket endpoint and the REST controllers), `SessionRegistry` + `ServerSession` (the BigInteger session ids and per-connection short client ids SGS minted, reaching the client through a transport-agnostic `FrameSink`), `WorldModel` + `WorldCell` (the authoritative hierarchy, environment-root remove guard, `applyMove`), and `ProtocolRouter` (the transport-agnostic brain both the loopback and the WebSocket call: `SET_VIEW` → `ViewCreateResponse` + world `LOAD_CELL` fan-out, a real `ViewCell`-typed world cell pushed through a **deferred post-reply flush** that reproduces the 2006 async-socket ordering the `ViewCell → viewCellConfigured → setViewCell` walk needs, authoritative `MovableMessage` reply routed by `MessageID` + `senderID`-stamped broadcast for client-side echo suppression, reparent → `CONFIGURE_CELL`, corrupt frame rejected). `MessagePacker.unpack` was hardened against a null stream close so a malformed frame fails clean (`wonderland-common`). The `/comms` handler + config and the REST controller compile and are wired but **not yet bound to a port**; **M3b** puts them behind real sockets. **52 headless cases**, the crown `LoopbackClientServerTest` driving a **real** `wonderland-client` `CellClientSession` against the **real** Spring router over the M1 codec — handshake, fan-out, avatar command chain, multiplayer move round trip, echo suppression, departure unload, no functionality lost, floor `[line: 0.950, branch: 0.850]`. |
| **M4** | Plugin/module loader + first low-risk modules (`text-chat`, `imageviewer`, `user-list`, `stickynote`, `whiteboard`) | ⏳ | ServiceLoader |
| **M5** | `colladaloader` / `artimport` / `assetmgr`; load the 7 bundled worlds | ⏳ | |
| **M6** | 2D + 3D desktop integration (`Frame3D`, `SwingNode` MDI mirror of the update-manager/db-manager pattern; `.lgcfg` start-menu descriptors) | ⏳ | also rebuilds the deferred M2 scene-graph renderer (`client/jme` + `cellrenderer` + the `AvatarCell` renderer half) on `lg3d-core`'s Jogamp bridge, probe-verified per `lg3d-core/AGENTS.md` |
| **M7** | High-risk features: `voicebridge` / `softphone` (VoIP), `avatar*`, `animation*`, `videoplayer`, `pdfviewer` | ⏳ | may descope per-feature with a written reason |

No milestone claims global 100% parity. Each milestone's PR reports what it
verified and what it did not, and never silently drops a test path.

---

## Unrecoverable tech: rebuild vs. port decisions

This is the standing decision list. Every entry here is a *veto-at-confirm*
item — the plan was approved with these in place, but any of them can be
reversed before its milestone lands.

| Bundled tech | Fate | Reasoning | Replacement strategy |
|--------------|------|-----------|----------------------|
| **Java Web Start / JNLP launcher** | ❌ rebuilt | JNLP removed in JDK 9; the `.jnlp` file fetched jME + Java 3D at launch. | Plain `main()` launched via `run-wonderland.sh` and a `:lg3d-core:run`-style classpath task. |
| **`javax.vecmath`** | ❌ rebuilt | Removed from JDK; house rule (`legacy-j3d.mnd`) forbids the import. | `org.jogamp.vecmath` — already the repo-wide vecmath since the LG3D migration. Wired in `wonderland-common/build.gradle`. |
| **`javax.media.j3d` + `com.sun.j3d`** | ❌ rebuilt | Never bundled in Wonderland.jar (was fetched by JNLP); removed from the JDK; house rule forbids the import. | `org.jogamp.java3d` — already the repo-wide Java 3D since the LG3D migration. |
| **`com.jme.math.{Vector3f,Quaternion,Matrix3f,Matrix4f,FastMath}`** | ❌ rebuilt | jME 1.x was fetched via JNLP at launch and is **not** in Wonderland.jar — there is no bytecode to port. | Minimal in-tree shim under `com.jme.math` implementing exactly the surface the data model uses, on top of `org.jogamp.vecmath` (for `Matrix4d`, `Quat4d`, `Vector3d`) and plain float math otherwise. |
| **`com.jme.bounding.{BoundingVolume,BoundingBox,BoundingSphere}`** | ❌ rebuilt | Same as above. | In-tree shim; the data model only uses these as *data* (centre / extents / radius, `contains`, `transform`, `clone`), never the jME collision pipeline. |
| **`com.jme.renderer.ColorRGBA`, `com.jme.scene.state.MaterialState`** | ❌ rebuilt | Same as above. | Plain value holders. |
| **`javax.xml.bind.*` (JAXB)** | ❌ rebuilt | Removed from the JDK in Java 11. | `jakarta.xml.bind:jakarta.xml.bind-api:2.3.3` (which still uses the `javax.xml.bind` package namespace) + `org.glassfish.jaxb:jaxb-runtime:2.3.9` on the runtime path. Verified by `ChecksumListTest`. |
| **`sun.misc.Service.providers(...)`** | ❌ rebuilt | Internal, removed in JDK 9. | `java.util.ServiceLoader.load(clazz, TCCL)` inside the rebuilt `ScannedClassLoader.getAll(...)`. |
| **scannotation** | ❌ rebuilt | Dead library; cannot read modern class files. | `org.reflections:reflections:0.10.2` in `ScannedClassLoader.getClasses/getInstances`, scoped to `org.jdesktop.wonderland` on the default path so the scan stays cheap. |
| **`@SuppressWarnings` + raw `Iterator` in `ScannedClassLoader`** | ⚠ preserved deliberately | `CellServerStateFactory` assigns `getInstances(...)` to `Iterator<CellState>` without a cast. Returning a parameterised `Iterator<Object>` would break the call sites. | `Iterator` raw return + `@SuppressWarnings("unchecked")` inside the loader. Documented in the class javadoc. |
| **GlassFish Embedded 3.0 (26,206 classes)** | ❌ rebuilt | Retired upstream; drags in a whole application server. | `wonderland-server` (M3) is built on **Spring Boot 3** (embedded Tomcat + Spring WebSocket). The Wonderland wire protocol rides a `TextWebSocketHandler` / `BinaryWebSocketHandler`, WFS-style REST resources become `@RestController`s, and the client's `javax.ws.rs` calls (currently annotation-only on the wire types) gain a real JAX-RS client (or Spring's `WebClient`, chosen at M3). |
| **Sun Game Server (SGS)** | ❌ rebuilt | Retired upstream; the session/host layer is the piece SGS was used for. | Folded into `wonderland-server` M3 as a plain Spring service (`@Service`) that owns the session registry; SGS's channel abstraction maps onto the M1 `SessionInternalConnectionType`. |
| **Apache MINA** | ❌ rebuilt | Only used by SGS. | Spring's `WebSocketHandler` provides the codec-per-frame loop; no explicit NIO pipeline is written, and no MINA / Netty dependency is added. |
| **winp (native JNI)** | ❌ rebuilt | Retired; only used to enumerate processes. | `java.lang.ProcessHandle` (JDK 9+). |
| **stun** | ⏳ deferred | VoIP / softphone (M7). | Replaced by `ice4j` if M7 keeps VoIP, otherwise dropped with a written reason. |
| **darkstar + `sas`/`sasxremwin`/`xremwin`** | ❌ dropped | Their scene / applet embedding functions are covered by the already-migrated Jogamp stack plus `SwingNode` (see [docs/swingnode.md](swingnode.md)). | No reimplementation; the migration path is the SwingNode pattern used by the update-manager / db-manager modules. |
| **NetBeans Platform module system** (71 nested `modules/*.jar`, `org.openide`) | ❌ rebuilt | NBM toolchain gone; no upstream source in the bundle. | M4 reimplements as a plain classpath / `ServiceLoader` plugin loader; port low-risk modules first. |

**Deferred but not rejected** (may still be dropped at their milestone with
a written reason):

- `wonderland-common/src/main/java/org/jdesktop/wonderland/common/NetworkAddress.java`
  — only used by the GlassFish server stack; deferred to **M3**.
- `wonderland-common/src/main/java/com/sun/.../ComposerVector3f.java`
  — only referenced by `avatar`/`animation` code paths; deferred to **M7**.

---

## M0 specifics (this PR)

What actually landed in this first PR:

### New Gradle projects (registered in [settings.gradle](../settings.gradle))

- `wonderland-common` – source set compiles, tests, jar.
- `wonderland-client` – scaffold only (`build.gradle` + `package-info.java`).
- `wonderland-server` – scaffold only.
- `wonderland-modules` – scaffold only.

### `wonderland-common/src/main/java` source set (156 files)

- **146** files decompiled via Fernflower from `wonderland-common.jar` +
  `wonderland-wfs.jar`, then hand-migrated to JDK 21. The migration is:
  `sed 's/javax\.vecmath/org.jogamp.vecmath/g'` for the vecmath rename,
  plus the small number of hand edits listed under "hand fixes" below.
- **10** files are the in-tree `com.jme.*` shim, authored from scratch (see
  the javadoc on each class for the exact surface the ported data model
  needs).
- Within the 146, the 6 files under `org/jdesktop/wonderland/common/wfs/`
  (`CellDescriptor`, `CellList`, `CellPath`, `WFSRecordingList`,
  `WorldRoot`, `WorldRootList`) come from `wonderland-wfs.jar`. Their
  JAX-RS annotations (`javax.ws.rs.*`) resolved against
  `javax.ws.rs:javax.ws.rs-api` transitively in the original build, but
  they do not actually use JAX-RS client/server code paths in the data
  model, so no explicit dependency was added here.
- Excluded from M0 (deferred per decision list):
  `NetworkAddress` → M3, `ComposerVector3f` → M7.

### Hand fixes on top of the decompilation

- 11 raw-collection for-each loops (decompilation erased the generics):
  added `(java.util.Set<Map.Entry>)` / `(java.util.Set<String>)` /
  `(java.util.Collection<T>)` casts on the receiver. Sites:
  `CellPhysicsPropertiesComponentServerState` (2),
  `CellServerState` (3), `ChecksumList` (3, superseded by parameterising
  the two public fields), `ModuleJarWriter` (2), `ModuleInfo` (2).
- Four JAXB `XmlAdapter` subclasses (`JarURIAdapter`,
  `Vector3fAdapter`, `QuaternionAdapter`, `BoundingVolumeAdapter`): added
  the missing `<ValueType, BoundType>` parameters so the compiler stops
  refusing the generic `marshal`/`unmarshal` overrides.
- `Vector3f.clone()` / `Quaternion.clone()` return the **concrete** type
  (covariant override), because `CellTransform` assigns the result to a
  `Quaternion` / `Vector3f` field without a cast.
- `Quaternion.mult(q, result)` and `Vector3f.cross(v1, v2)` write into
  saved locals before touching `this`, because the callers alias `result =
  this` (jME 1.x had the same shape).
- `ScannedClassLoader` rewritten end-to-end on top of `Reflections` +
  `ServiceLoader` (see the class javadoc); returns raw `Iterator` on the
  two SPI methods to keep the existing call sites in `CellServerStateFactory`
  source-compatible.
- `ChecksumList` public fields parameterised to `LinkedList<Checksum>` /
  `Map<String, Checksum>`; its `@XmlElements` annotation given the explicit
  `type = Checksum.class` that the decompiler could not recover once the
  generics were erased. This is the change that lets the JAXB round-trip
  test actually marshal the list on JDK 21.

### Tests (`wonderland-common/src/test/java`)

- `com.jme.math.Vector3fTest` – 13 cases: statics, mutating arithmetic,
  `dot`, `cross` (right-hand rule), `length`, `normalize` (including the
  zero-vector corner), `distance`, `angleBetween`, `equals`/`hashCode`.
- `com.jme.math.QuaternionTest` – 13 cases: identity default, copy
  constructor, covariant `clone()`, `multLocal` rotation of unit axes,
  `fromAngleAxis` → `toAngleAxis` round-trip, `mult` composition,
  `normalize`, `toAngles`.
- `com.jme.math.FastMathTest` – 6 cases: `acos` / `asin` clamp their
  arguments so a rounding-overshoot returns the boundary angle rather
  than `NaN`.
- `com.jme.bounding.BoundingVolumeTest` – 10 cases: box / sphere centre,
  extents / radius, `contains`, `clone(result)`, and the
  scale → rotate → translate pipeline.
- `org.jdesktop.wonderland.common.Math3DUtilsTest` – 10 cases: the five
  `encloses(...)` overloads, `epsilonEquals` at float / `Vector3f` /
  `Quaternion`, `pointLineDistance`, `toEuler`.
- `org.jdesktop.wonderland.common.cell.CellTransformTest` – 15 cases: the
  pure-shim path (`transform(Vector3f)`, `sub`, `clone`), the vecmath
  path (`mul`, `invert`), `epsilonEquals`, `getLookAt`,
  `transform(BoundingVolume)`, `toString`.
- `org.jdesktop.wonderland.common.checksums.ChecksumListTest` – 4 cases:
  end-to-end JAXB round-trip (`encode` → XML → `decode` → lookups) and
  the `putChecksum` / `getChecksumMap` / `toHexString` helpers.
- `org.jdesktop.wonderland.common.utils.ScannedClassLoaderTest` – 6 cases:
  stable singleton, drainable iterators on empty scans, ServiceLoader +
  annotation-scan union in `getAll`, `ClassScanningError` cause wrapping.

Total: 77 tests, all green under `./gradlew :wonderland-common:test`.

### JaCoCo coverage floor

Measured after M0's tests: **17.56% line**, **17.90% branch**. Floor set
to `[line: 0.150, branch: 0.150]` in the root
[build.gradle](../build.gradle) `coverageFloors` map. Raised with M1
(see below).

---

## M1 specifics

What actually landed in the second PR:

### New tests (`wonderland-common/src/test/java`)

- `org.jdesktop.wonderland.common.messages.MessagePackerTest` – **11**
  cases. The protocol envelope itself: `MessageID` Externalizable round
  trip, `Long.MIN_VALUE` "no reply-to" sentinel, `generateMessageID()`
  monotonic counter, `ErrorMessage` with cause + text and without,
  `ProtocolSelectionMessage` with a concrete `DefaultProtocolVersion`,
  `MessageList` with nested messages carrying their own IDs, empty
  `WonderlandSetupMessage`, `clientID` short including 0 and negatives,
  and that two consecutive packs in one JVM stay independent (the pin
  for the `WonderlandObjectOutputStream.descToId` static map being
  read-only).
- `org.jdesktop.wonderland.common.comms.messages.CommsMessagesCodecTest`
  – **7** cases. The session handshake: `AttachClientMessage` with
  `ConnectionType` + `Properties`, `AttachedClientMessage` carrying the
  assigned short `clientID` + `reply-to`, `DetachClientMessage` for
  negative IDs, `SessionInternalConnectionType` marker subclass
  deserialising as itself (not the base class),
  `SessionInitializationMessage` with a big `BigInteger` session id and
  a full `WonderlandIdentity`, and the anonymous-handshake null-identity
  path.
- `org.jdesktop.wonderland.common.cell.messages.CellMessagesCodecTest`
  – **25** cases. Every concrete subclass in `common/cell/messages/`:
  `CellMessage.cellID` + `senderID` (via the flagship `MovableMessage`
  round trip), the three `MovableMessage.ActionType` shapes and
  `MovableMessage.getCellTransform()` reconstruction,
  `MovableMessageResponse.newMoveModifiedMessage`, `MovableAvatarMessage`
  with trigger / pressed / animation / height / collision, the four
  `CellEditMessage` subclasses with their `EditType` tags, `CellCreatedMessage`,
  `CellServerState{Request,Response,Set,Update}Message` resolving their
  concrete `CellServerState` subclasses (`AvatarCellServerState`,
  `ModelCellServerState`, `PositionComponentServerState`,
  `ModelCellComponentServerState`), the `CellServerComponentMessage` /
  `CellClientComponentMessage` add + remove pair with their
  `ComponentAction` enums, `CellClientStateMessage`,
  `CellChannelComponentRequestMessage`, `CellHierarchyMessage` with a
  `BoundingSphere` + `CellTransform` + `ModelCellClientState` triple,
  `CellHierarchyUnloadMessage` (which uses the no-arg `msgType =
  UNLOAD_CELL` path), and `ViewCreateResponseMessage`. `CellServerStateUpdateMessage`
  exercises the raw-`Set` field via a `ComponentHolder` wrapper so
  hashCode-based collection identity survives the round trip.
- `RoundTrip` package-private helper (`MessagePackerTest` shares it) that
  does pack + unpack in one call so every test reads the same way.

Total after M1: **120 headless tests** under
`./gradlew :wonderland-common:test`, all green.

### Hand fixes on top of M0

- None in production code. The three tests caught shape issues in the
  tests themselves (a first draft assumed the descriptor-id cache was a
  length-reducing "warm" cache; it is not — it is populated once for a
  fixed `coreClass` list and never mutated), and one decompilation
  artefact (raw `Set` on `CellServerStateUpdateMessage`) is exercised
  rather than papered over by a `ComponentHolder` wrapper.

### JaCoCo coverage floor

Measured after M1's 43 new cases: **32.36% line**, **21.48% branch**.
Floor raised in the root [build.gradle](../build.gradle) `coverageFloors`
map to `[line: 0.300, branch: 0.200]`. The remaining ~65% is the
still-untested data-model periphery (modules, security, jaxb adapters,
login, some of the comms internals) that each later milestone pulls into
scope.

### Standing decision update: Spring Boot 3 replaces the earlier
"plain `java.net` / `java.nio`" plan for M3's session server. The
**GlassFish Embedded 3.0**, **Sun Game Server (SGS)** and **Apache MINA**
rows in [the decision table above](#unrecoverable-tech-rebuild-vs-port-decisions)
now name Spring Boot explicitly; no port of any of the three.

---

## Building / running

Same toolchain as the rest of the repo (JDK 21 pinned via Gradle toolchain
resolver). From the repo root:

```sh
# headless build of the ported data model
JAVA_HOME=/path/to/jdk-21 ./gradlew :wonderland-common:build

# just the tests
JAVA_HOME=/path/to/jdk-21 ./gradlew :wonderland-common:test

# coverage verification
JAVA_HOME=/path/to/jdk-21 ./gradlew :wonderland-common:jacocoTestCoverageVerification
```

The `wonderland/` installer directory is `.gitignore`d and never committed.
Every Wonderland module uses `build-gradle/` as its output directory (see
the root `subprojects` block), so `wonderland-common/build-gradle/` holds
the JaCoCo HTML / XML reports and Checkstyle output locally.

---

## Git flow

- Feature branch off `main`, Conventional Commit per module.
- Open PR with `gh`, review, squash-merge with `--delete-branch` on both
  sides.
- **No release / tag ever without explicit approval.**
- **M0 does not unlock M1**. Each milestone PR must be re-confirmed before
  the next one starts.
