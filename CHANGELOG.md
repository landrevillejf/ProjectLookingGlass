# Changelog

All notable changes to this **modernization port** of Project Looking Glass are
documented here. The format follows [Keep a Changelog](https://keepachangelog.com/),
grouped by Added / Changed / Removed / Fixed.

The original 2006 Sun codebase is the baseline; everything below describes the
work to make it build and run on a current toolchain.

## [Unreleased] — 1.69.0-dev — Gradle / JDK 21 modernization

### Added
- **The Wonderland VoIP softphone lands on ice4j + Java Sound + in-tree G.711
  (`wonderland-modules` + `lg3d-apps`, M7d)** — the fourth and last M7
  replacement, and the one that was three dead technologies braided together:
  the 2006 phone bundled a closed SIP binary driven over a stdin/stdout line
  protocol, watched it with **winp** (native JNI, Windows-only, retired),
  looked up its NAT reflexive address with **stun.jar** (never open-sourced),
  and carried voice through **JMF RTP** (`javax.media`, unrecoverable). The
  port splits the same job along the M4 headless line: `wonderland-modules/softphone`
  is a dependency-free package — `SoftphoneControl` (the 2006
  `SoftphoneControlImpl`'s STATE half ported method-for-method: call id,
  mute, visibility, quality, recorder rules, listener fan-out, and the
  `exitNotificationSent` "fire softphoneExited AT MOST ONCE per process"
  rule kept verbatim in spirit), `SoftphoneCommand` / `SoftphoneEvent` (the
  SAME line vocabulary the 2006 binary spoke — "register", "callID=", "mute"/
  "Unmute", "quality", "recordAudio=", "linetest", "PlaceCall=…", "Shutdown",
  and the stdout echoes — now typed, serialised and parsed, so a modern host
  softphone is scripted against the identical protocol), `RtpPacket` (the
  RFC 3550 fixed header as an immutable value class — V|P|X|CC and M|PT in
  their spec octets, the modulo-2^16 sequence circle, and refusals for what
  the 2006 stack never spoke), `G711` (the public-domain mu-law quantiser
  from Sun's `g711.c` in pure Java, exhaustively tested: all 65536 shorts
  encode, all 256 codes decode, the round trip lands within one quantisation
  step, the two ISDN zeros expand to silence), `JitterBuffer` (the TreeMap
  de-jitter reorder on extended sequence positions with its capacity, gap
  and late-drop counters), `VuMeter` (the peak-follower needle that emits
  the 2006 "Microphone level: N%" strings and latches the gain warning),
  `AudioQuality` (the four 2006 presets value-for-value), the listener
  triad, `SoftphoneBackend` + `NoopSoftphoneBackend` + the
  `SoftphonePlugin.installBackendFactory` seam — the eighth shipped
  ServiceLoader provider. The **live half** is `lg3d-apps`: an
  `ExternalSoftphoneBackend` spawns and supervises the child with
  `ProcessBuilder` + `java.lang.ProcessHandle` (the winp replacement — its
  protocol framing is headless-tested against an in-memory `EngineProcess`
  stub), an `Ice4jNatProbe` asks **ice4j** (Apache-2.0, the Jitsi engine on
  Maven Central) the same "where is my public address behind this NAT"
  question stun.jar asked (probe-only — a fabricated STUN server would prove
  nothing), and a `JavaSoundVoiceBridge` moves voice over `javax.sound.sampled`
  + plain UDP datagrams, feeding the CI-proven G.711/RtpPacket/JitterBuffer
  stack (its device-free frame packing and little-endian conversion are
  asserted; the four worker threads that touch a mic, a speaker or a socket
  are verified live, per the standing rule). **99 new headless cases** (88
  `wonderland-modules` across eight softphone suites — the crown G711
  encode/decode sweeps over every short — + 11 `lg3d-apps`); `ShippedModuleSetTest`
  now discovers eight providers; `coverageFloors[wonderland-modules]` raised
  `[0.980, 0.935] → [0.985, 0.945]` (measured 98.88 % / 95.32 %). No audio,
  process or network live call is claimed from CI.
- **Wonderland avatar animation lands on COLLADA keyframes (`wonderland-modules` +
  `wonderland-renderer` + `wonderland-avatars` + `wonderland-desktop`, M7c)** —
  the third M7 replacement: the 2006 `AvatarJME` drove skeletal animation
  through jME-1.x's `Animator` against a `.jme` binary pool that was fetched by
  Java Web Start and is unrecoverable, so the port ships a **COLLADA-driven
  keyframe animator** instead, consuming the animation-name string the wire has
  carried since M6b (`AvatarTriggerState.lastAnimationName`). The parse half
  lives in `wonderland-modules/collada`: `ColladaAnimation` keeps the raw
  `<library_animations>` vocabulary — sources with their float data and
  accessor stride (under `<technique_common>`, as the 1.4.1 spec nests it),
  samplers with hash-stripped INPUT/OUTPUT refs, the interpolation attribute
  and the flat index pairs, channels with the target string untouched — and
  the Blender group-animation flatten merges child records into the parent;
  interpretation deliberately does not live here. The playable half is a new
  pure `org.jdesktop.wonderland.renderer.animation` package: `ChannelTarget`
  (the `node/sid.element[.ARRAY]` vocabulary), `KeyframeTrack` (right-
  continuous LINEAR/STEP sampling, clamped outside the key span),
  `AnimationClip` (per-node tracks composed into a `Matrix4d` pose via
  Rodrigues), `ColladaClips` (the raw-to-playable factory: the stride-4
  axis+angle rotation bundle, the stride-3 Euler refusal — dropped, not
  misread — BEZIER collapses to LINEAR, times normalise to t=0, an
  unresolvable channel degrades instead of throwing), `SyntheticClips` (the
  idle/walking/waving fallback so an unrigged body never freezes, exactly as
  on the 2006 desktop), `AnimationLibrary` and `AvatarAnimationDirector` (the
  playback policy on an injected clock: LOOPING names wrap, gestures play once
  then revert to idle, the alias table maps walk/run to walking, unknown names
  fall back to idle). The **live half** is probe-only: `AvatarRenderer` now
  builds one nested LOCAL `TransformGroup` per Collada node (replacing M6b's
  flat world-matrix soup, so an animated joint drags its subtree), ticks the
  director from a 33 ms daemon clock wired to `WonderlandWorldContainer`
  mount/unmount, and writes the sampled pose overrides each tick; a trigger
  press feeds the director immediately. The bundled `humanoid-a.dae` gained
  hand-authored `walking` (bob + axis-angle sway) and `idle` clips against its
  real node id — bodies B and C deliberately ship no rig, exercising the
  synthetic path. **77 new headless cases** (7 `ColladaAnimationTest`, 70
  across eight renderer suites, the crown `AvatarAnimationBundledPoolTest`
  driving the REAL shipped `.dae` bytes through the whole chain with no
  stubbing); `wonderland-renderer` floor raised `[0.530, 0.560] → [0.660,
  0.660]` (measured 66.9 % / 67.0 %). Live playback is verified on the
  desktop probe, per the standing rule.
- **The Wonderland pdfviewer lands on Apache PDFBox (`wonderland-modules` +
  `lg3d-apps`, M7b)** — the second M7 replacement: the 2006 pdfviewer rasterised
  pages with jpedalSTD, a dead library with no maintained JDK 21 build, and the
  modern equivalent — **Apache PDFBox** — is already in-tree behind this
  desktop's own PDF Viewer, so the port now ships ONE PDF backend, not two. The
  module keeps the M4 headless shape: `PdfViewModel` is the pure document model
  — the EMPTY/LOADING/LOADED/ERROR lifecycle (a blank source is refused so a
  typo cannot blank an open document; a late `markLoaded` for a replaced
  document is refused; a zero-page document is an ERROR, not a loaded one), the
  clamped 1-based paging walk (`gotoPage` clamps into range, `nextPage` /
  `previousPage` stick at the edges), and the clamped zoom (`[0.1, 8.0]`, a NaN
  or non-positive scale resets to 100 %, and `zoomOut` clamps at the minimum
  itself so a step from 0.25 cannot fall through the nonsense-reset path); the
  pixels live behind a new `PageRasterizer` seam that speaks only encoded PNG
  bytes and an int page count — no AWT type crosses into the module — reached
  through the `PdfViewerPlugin` `installRasterizerFactory` slot (the M6b/M7a
  installer idiom), with a shipped `NoopPageRasterizer` so a headless JVM lands
  an open honestly on ERROR instead of pretending. The **live half** is the
  `lg3d-apps` `PdfBoxRasterizer`, a thin adapter over the existing
  `pdfviewer/PdfDocument`: it keeps exactly one parsed document open (page
  flips must not re-load), replaces it on a URI change, PNG-encodes through
  `ImageIO` for the seam, and returns −1 / null on missing or unparsable files.
  Because PDFBox runs under `java.awt.headless=true` (the standing
  `PdfDocumentTest` precedent), this adapter is **CI-provable, not probe-only**:
  `PdfBoxRasterizerTest` resolves and rasterises real generated A4 PDFs and
  asserts the PNG decodes back at the expected ~595×842 px for 100 % and ~half
  for 50 % zoom. **30 new headless cases** (14 `PdfViewModelTest`, 9
  `PdfViewerPluginTest` incl. the `ServiceLoader` discovery, 7
  `PdfBoxRasterizerTest`); the module joins the shipped set (now seven). Live
  in-world page display is still verified on the desktop, per the standing rule.
- **The Wonderland videoplayer lands on OpenJFX Media (`wonderland-modules` +
  `lg3d-apps`, M7a)** — the first of the four M7 replacements, and the answer to
  the retired `javax.media` (JMF): the 2006 videoplayer drove JMF's `Player` /
  `MediaHandler`, which has no JDK 21 build and is unrecoverable, so the modern
  equivalent is OpenJFX `Media` / `MediaPlayer` / `MediaView` — the same engine
  already in-tree behind the Web Browser. The module is split the pure-vs-sink
  way every Wonderland slice now is. The **pure half** lives in a new
  `org.jdesktop.wonderland.modules.videoplayer` package with **no toolkit type
  at all** (so `wonderland-modules`' hard headless floor holds): `MediaSurface`
  is the sink seam (`open` / `play` / `pause` / `stop` / `seekTo` / `setVolume`
  / `release`), `VideoTransport` is the guarded lifecycle machine — `load`
  refuses a null/empty URI and stops the old media first, `play` rewinds from
  `ENDED`/`STOPPED`, `pause` only lands from `PLAYING`, `seek` clamps to
  `[0, duration]` but stays unclamped for a live/unknown-length stream, `tick`
  advances only while `PLAYING` and walks once to `ENDED` + a single `stop`, and
  `setVolume` clamps with `NaN` → silent — `Playlist` is the wrapping-cursor
  catalog whose `removeAt` keeps the cursor on the successor, and
  `VideoPlayerPlugin` is the `ServiceLoader` provider holding a static
  `installSurfaceFactory` slot (the M6b installer idiom): headless it drives a
  shipped `NoopMediaSurface`, and `tickAndAdvance` moves the cursor **before**
  the load so an ended item is never replayed and its successor never skipped.
  The **live sink** is `lg3d-apps`' `JfxMediaSurface` — it boots the toolkit via
  the browser's `JFXPanel`-on-the-EDT pattern, marshals every mutating call
  through `Platform.runLater`, and converts a bare file path to a `file:` URI
  for local playlist items; it wires in through `VideoPlayerPlugin.installSurfaceFactory`,
  so `lg3d-apps` gains an `implementation project(':wonderland-modules')` dep
  (no cycle: `wonderland-modules` depends only on `wonderland-common`). JavaFX
  is initialised **only** in this 2D-desktop JVM, never in the Java 3D process,
  exactly the standing constraint. **56 new headless cases** (`VideoTransportTest`,
  `PlaylistTest`, `VideoPlayerPluginTest` incl. the `ServiceLoader` discovery and
  the auto-advance ordering), so
  [`coverageFloors[wonderland-modules]`](build.gradle) branch rises `0.930 → 0.935`
  (measured 98.52 % / 93.71 %). `JfxMediaSurface` is compile-checked in CI; live
  playback is verified on the desktop, **not** claimed as tested headless.
- **The Wonderland scene-graph bridge lands — a cell's world mounts inside the lg3d
  desktop (`wonderland-desktop`, new module)** — M6c1, the first slice of the desktop
  integration milestone, and the **first Wonderland module to depend on
  `lg3d-core`** (strictly one-way: nothing in `lg3d-core` imports Wonderland). A
  loaded cell finally has somewhere to *live*: M6a/M6b build a renderer's
  `BranchGroup`, and this slice is what mounts it into the desktop's virtual
  universe and keeps it there as the server moves it. The slice is split the
  same pure-vs-probe way as `wonderland-renderer`. The **pure half** is
  `CellFrameBridge`, the mount/unmount/transform/visibility *decision* layer:
  it owns the mounted-set (a `LinkedHashSet`, so the snapshot is in mount
  order), guards a null cellId or renderer, ignores a double mount and an
  unmount of a cell that is not mounted, drops a transform or visibility
  update for a cell that unmounted between the server message and the render
  tick, and folds the `CellTransform` through M6a's `WorldTransformMath` into
  the row-major `double[16]` — but it never touches a Jogamp type: the *how*
  goes through the `FrameMounter` seam interface, so every decision is
  headless-tested against a recording stub (including that the translation
  lands on the last column, indices 3/7/11, and that a null transform degrades
  to the identity feed rather than throwing). `WonderlandWorldContainer`
  implements that seam for real — it extends `lg3d-core`'s `Component3D` and
  wraps each mounted renderer's scene in its own `ALLOW_TRANSFORM_WRITE`
  `TransformGroup`, so `applyTransform` is a live `setTransform` — and is
  **probe-only**: its constructor reaches through `AppConnectorPrivate` and
  cannot run headless; only its type-only scene-extraction branch
  (`extractScene`: `ModelRenderer` / `AvatarRenderer` → its `BranchGroup`,
  anything else → null) is package-private and asserted in CI. `WonderlandDesktop`
  is the bootstrap (`installRenderer()` filling M6b's installer slot once, then
  `cellLoaded` / `cellUnloaded` / `cellTransformUpdate` driving the bridge),
  and `WonderlandDesktopPlugin` registers the whole thing as an M4 `WonderlandPlugin`
  `ServiceLoader` provider — `dispose()` unmounts every cell, and the bridge
  it discovers is checked by a real `ServiceLoader.load` in the test.
  **27 new headless cases** (19 `CellFrameBridgeTest`, 5
  `WonderlandDesktopPluginTest`, 3 `WonderlandWorldContainerTest`), so
  [`coverageFloors[wonderland-desktop]`](build.gradle) starts at
  `[line: 0.420, branch: 0.540]` (measured 43.8 % / 61.5 % — the gap is
  exactly the probe-only `Component3D` / bootstrap classes, the same honest
  shape as `wonderland-renderer`'s floor). What M6c1 deliberately does **not**
  claim: the container's `setVisible` is a documented no-op until the `Switch`
  node is wired per cell, and the `SwingNode` HUD / login panels, the `.lgcfg`
  start-menu descriptor and the pick / input routing are **M6c2**. Live
  mounting is verified by launching the desktop + a bundled world through the
  in-JVM probe (`lg3d-core/AGENTS.md`) and reading the `lgscreen-0-N.png`
  screencapture — **not** claimed as tested in CI, per the standing rule.
- **The Wonderland avatar renderer lands on the bundled `.dae` pool (`wonderland-avatars`
  new module, `wonderland-renderer` M6b)** — M6b, the second slice of M6, replaces
  `AvatarCell`'s M2f deferred `null` with a live Java 3D avatar hanging on the
  same `CellRenderer` seam M6a opened. Because 2006 pulled bodies from the
  jME-1.x `.jme` binary pool the port never recovers, this slice ships its own
  content: a **new leaf module `wonderland-avatars`** — a pure resource jar
  depending on nothing — carrying three hand-authored placeholder humanoid
  COLLADA 1.4.1 bodies (`humanoid-a`, `humanoid-b`, `humanoid-c`) and the
  `AvatarPool` catalog naming them; real artist-supplied CC0 humanoids swap in
  as pure content edits, the filenames and the `wla://` URL shape are the code
  contract. The pool is reached through a **new `ClasspathWorldStore`** added
  beside M5's `DirectoryWorldStore` in `wonderland-modules/asset` — same
  `wla://<module>/<path>` contract resolved through a `ClassLoader`, the
  `../` confinement carried over unchanged. On the wire,
  `AvatarCellServerState` gains an optional `avatarModelUrl` `@XmlAttribute`;
  a pre-M6b server still sends only `userName`, the field decodes to null,
  and the client falls back to `AvatarPool.urlFor(AvatarPool.defaultName())`
  — `CellMessagesCodecTest` pins both branches so the extension is
  **backward compatible**. Because `wonderland-client` cannot import
  `wonderland-renderer` (that would cycle), the seam is a **static installer
  slot** in the client — an `AvatarRendererInstaller`
  `@FunctionalInterface` and an `AvatarRendererInstallers` volatile static
  holder — and `AvatarCell.createCellRenderer` changes from `return null;` to
  a null-guarded delegation, so a headless test JVM (nothing installed) keeps
  the M2f quirk and a live desktop (installed by
  `WonderlandRendererBootstrap.install()` at boot) gets the renderer back;
  `AvatarCell` also gains a typed `getServerState()` /
  `setServerState(AvatarCellServerState)` pair the factory reads the URL
  through. The renderer's **pure half** is unit-tested end-to-end in a new
  `wonderland-renderer/…/avatar` package: `AvatarLoadResolver` (URL selection
  — explicit wins, null/empty falls back to the pool default — reading the
  bytes through `ClasspathWorldStore` and parsing with `ColladaParser`, every
  failure degrading to an `AvatarLoadResult.error` rather than throwing, a
  documented deviation from `ModelLoader.load` since the pool ships raw `.dae`
  bytes and not a `.dep` indirection), `AvatarTriggerState` (the mutable
  value class the renderer owns — pressed, collision height / on, last
  animation name for M7, and the nullable goto override — deciding
  `chooseDiffuseColour()` yellow when pressed else neutral grey and
  `chooseYOffset()` the height when the flag is on else zero),
  `AvatarTransformFeed` (composing the base `CellTransform`, the
  goto-override short-circuit and the collision-Y offset into the same
  row-major `double[16]` M6a uses, with the offset landing on vecmath's
  last-column index 7), and `AvatarRendererFactory` (the seam dispatch
  mirrored on `ModelRendererFactory` — `RENDERER_JME` + loaded → delegate,
  `NONE` / `RENDERER_2D` / null / errored load / null supplier → null — all
  asserted headless with a stub supplier). The renderer's **probe-only half**
  is `AvatarRenderer implements CellRenderer, AvatarCell.AvatarActionTrigger`
  — a Jogamp `BranchGroup` → `Switch` → `TransformGroup` → per-node
  `TransformGroup` → `Shape3D` chain reusing M6a's `J3dMeshFactory` and
  `ColladaMeshBuilder` exactly as `ModelRenderer` does, driven by
  `cellTransformUpdate` through `AvatarTransformFeed` and re-tinted in place
  on every trigger / collision by writing through the `Material` and
  `Appearance` `ALLOW_*_WRITE` capabilities `J3dMeshFactory` already sets,
  plus `WonderlandRendererBootstrap.install()` composing the client-slot
  installer from an injected resolver + factory and pulling the optional
  `avatarModelUrl` off `AvatarCell.getServerState()`. Real skeletal animation
  is a **documented M7 descope with written reason**: the 2006 `AvatarJME`
  used jME-1.x's `Animator` on a `.jme` binary pool the port never recovers,
  so M6b ships the *state-driven* visual subset only — trigger press tints
  yellow, collision lifts / drops by the reported height, a goto teleports
  the pose — with the animation-name string carried on
  `AvatarTriggerState.lastAnimationName` for M7's animator subsystem to
  consume. **97 new headless cases** across five modules — `AvatarPoolTest`
  (7, driving the real bundled `.dae` bytes), `ClasspathWorldStoreTest` (9,
  open / present / confinement / null-degrade), `AvatarRendererInstallersTest`
  (7, install/get/require/uninstall and the `AvatarCell` delegation both null
  and installed), the extended `CellMessagesCodecTest` (one case widened for
  the null default, one new for a set URL round-tripping through
  `MessagePacker`), and the five renderer files `AvatarLoadResolverTest` (10,
  driving the real pool through the real store with no stubbing),
  `AvatarTriggerStateTest` (12), `AvatarTransformFeedTest` (9),
  `AvatarRendererFactoryTest` (10) and `WonderlandRendererBootstrapTest` (9).
  `coverageFloors[wonderland-avatars]` is set to
  `[line: 0.950, branch: 0.900]` (the catalog class is tiny, pure and fully
  testable; the shipped `.dae` files are resource bytes not code and do not
  appear in JaCoCo); `wonderland-renderer`'s floor stays at
  `[line: 0.530, branch: 0.560]` — measured after M6b is above the M6a
  measurement, so the floor remains an honest shade under the pure-code
  carrier; `wonderland-client`, `wonderland-common` and `wonderland-modules`
  floors unchanged (the installer slot, the `serverState` field, the
  `ClasspathWorldStore` and the optional wire attribute are additive and
  fully tested). Imports stay `org.jogamp.java3d.*` / `org.jogamp.vecmath.*`
  only — the `legacy-j3d.mnd` gate enforces it. Live avatar rendering is
  verified by launching the desktop + a bundled world through the in-JVM
  probe (`lg3d-core/AGENTS.md`), walking a peer into view, clicking it to
  fire a trigger, and reading the `lgscreen-0-N.png` screencapture — **not**
  claimed as tested in CI, per the standing rule. M6c (the 2D + 3D desktop
  integration hosting a cell's `BranchGroup` in `lg3d-core`'s `Frame3D` /
  `SceneGraphView`) re-plans as it unblocks, per the
  every-slice-requires-reconfirmation rule.
- **The Wonderland Collada cell renderer lands on the Jogamp stack (`wonderland-renderer`,
  new module)** — M6a, the first slice of the milestone that finally *renders* what
  M5 read. A new leaf module, `wonderland-renderer`, sits on `wonderland-client`
  (the `CellRenderer` / `RendererType` seam), `wonderland-modules` (the M5
  `ColladaDocument` API), `wonderland-common` and `org.jogamp.java3d:java3d-core:1.7.2`
  directly. It is a **new module and not inside `wonderland-client`** by design:
  `wonderland-client` runs its 164-case suite under `java.awt.headless=true` at a hard
  `[0.980, 0.950]` floor, and the repo rule is that Jogamp scene-graph objects
  (`Shape3D`, `GeometryArray`, `BranchGroup`, `Appearance`, `Texture`) cannot be
  constructed there — dropping the renderer into `wonderland-client` would add
  uncoverable Java 3D lines and break that floor. The renderer is split **pure-vs-probe**
  so every line of *data conversion* is headless-tested and only the Java 3D assembly
  is deferred to the in-JVM probe. Pure and unit-tested: `ColladaMeshData`/`ColladaMeshBuilder`
  (walks a `ColladaGeometry`'s `Mesh.sources()`/`vertices()`/`primitives()`, resolves
  each `Input` semantic→source by `sourceById`, applies `stride`/`offset`, fans
  `triangles`/`polylist`/`trifan`/`tristrip` into a flat index buffer, tolerates a
  missing normals or texcoord binding, an unresolvable source ref (reads zeros), a
  secondary `set=` texcoord (prefers `set="0"`/unset), and a dangling tail below three
  vertices); `WorldTransformMath` (folds a `ColladaNode.WorldMatrix` — an
  `org.jogamp.vecmath.Matrix4d` — into the row-major `double[16]` feed Java 3D's
  `Transform3D` constructor accepts, vecmath's translation-in-last-column layout at
  indices 3, 7, 11; derives the same feed from a `CellTransform`); `TextureBindingPlan`
  (per-material `MaterialAppearance`: diffuse RGB with the effect's transparency folded
  into alpha even when the diffuse is missing, and the sampler-to-present-texture
  binding decision); `ModelRendererFactory` (the seam: a `RendererSupplier`
  `@FunctionalInterface` lets a test install a stub so the *dispatch decision* —
  `RENDERER_JME` + loaded → delegate, `NONE`/`RENDERER_2D`/null/errored → null — is
  exercised headless); and `WonderlandModelCell` (the `Cell` subclass the desktop hands
  out for a deployed model, overriding `createCellRenderer` to delegate). Probe-only,
  compile-tested but asserted by the in-JVM probe and **not claimed as tested in CI**:
  `J3dMeshFactory` (builds the real `IndexedTriangleArray` — the 5-arg form when there
  is no texcoord, the 7-arg one with `texCoordSetMap` when there is — plus `Shape3D`,
  `Appearance`, `Material`, `Texture`, `ImageComponent2D`; alpha expressed through
  `TransparencyAttributes.BLENDED` because this fork has no `Material.setTransparencyRatio`)
  and `ModelRenderer implements CellRenderer` (the live `BranchGroup` → `Switch` →
  `TransformGroup` chain; `Switch.setWhichChild(0 / CHILD_NONE)` for show/hide because
  this fork has no `Node.setEnable` on `Shape3D`; `cellTransformUpdate` walks through
  `WorldTransformMath`; `setStatus` maps `VISIBLE`/`RENDERING` to shown). Imports stay
  `org.jogamp.java3d.*` / `org.jogamp.vecmath.*` only — the `legacy-j3d.mnd` gate
  enforces it; the `lg3d-core` `Frame3D` bridge is deliberately **not** a dependency
  yet, arriving at M6c. **52 new headless cases** across `ColladaMeshBuilderTest`,
  `WorldTransformMathTest`, `TextureBindingPlanTest`, `ModelRendererFactoryTest` and
  `WonderlandModelCellTest` — measured **line 55.44 % / branch 59.29 %**, so
  [`coverageFloors[wonderland-renderer]`](build.gradle) is set one shade under at
  `[line: 0.530, branch: 0.560]`; the gap to `wonderland-client`'s floor is exactly
  the probe-only classes, an honest reflection of what a headless JVM can prove rather
  than an oversight. Live rendering is verified by launching the desktop + a bundled
  world through the in-JVM probe (`lg3d-core/AGENTS.md`) and confirming the loaded
  model appears in the `lgscreen-0-N.png` screencapture — **not** claimed as tested
  in CI, per the standing rule. M6b (avatar renderer, replacing `AvatarCell`'s
  deferred null) and M6c (2D+3D desktop integration hosting a cell's `BranchGroup`
  in `lg3d-core`'s `Frame3D` / `SceneGraphView`) re-plan as each unblocks, per the
  migration's every-slice-requires-reconfirmation rule.
- **The Wonderland COLLADA / deployed-model / asset pipeline lands, headless**
  (`wonderland-modules`, `src/main/java/…/modules/collada/`,
  `src/main/java/…/modules/artimport/`, `src/main/java/…/modules/asset/`,
  `src/test/java/…/modules/`, `src/test/resources/wonderland-almostempty-world/`) —
  M5, the milestone that lets a bundled world's 3D models be **read**. Three
  packages: **M5a `collada`** is a namespace-safe, DOCTYPE/entity-refusing DOM
  reader of COLLADA 1.4.1 — the asset header, geometries (source
  `stride`/`count` and the packed-index `positions()` walk), nodes with every
  transform folded to an `org.jogamp.vecmath.Matrix4d` (the column-major
  `<matrix>`, the degrees→radians `<rotate>`, the `<lookat>` basis and its
  degenerate identity, the component-defaulted translate/scale), the
  effect/material/image inventory and the texture-path chase, and tolerant
  numeric coercion (a bad token contributes nothing, never an abort). **M5b
  `artimport`** reads the deployed-model (`.dep`) and loader-data (`.ldr`) XML
  into `DeployedModel` / `LoaderData` / `DeployedTexture` plus the
  `AssetLoadStatus` machine. **M5c `asset`** is the read side: the `wla://`
  `WorldStore` abstraction, `DirectoryWorldStore` (the `<base>/<module>/<path>`
  mapping and the `../` confinement that cannot escape a module), `ModelLoader`
  (a `.dep` → Collada document, textures resolved against the **`.dae`**
  directory the way the 2006 `KmzLoader` did, gzip sniffed by magic byte, the
  present/missing texture split, every failure degrading to an `ERROR` result
  rather than throwing) and `WorldLoader` (each `-wlc.xml` → a
  `ModelCellServerState` through a JAXB handler that skips a corrupt or
  model-less cell rather than aborting the world). Driving the reader against
  the **real** vendored `.dae` bytes surfaced three genuine defects the
  synthetic fixtures hid — `AxisAngle4d` built with `(angle,x,y,z)` instead of
  `(x,y,z,angle)`, a degenerate `<lookat>` returning a zeroed rather than the
  identity matrix, and an effect reader that accepted only `<technique_common>`
  and so silently dropped every sampler in the shipped 2006 `<technique
  sid="COMMON">` exports — all three fixed. The crown `WorldLoaderTest` drives
  the whole chain over a real almost-empty world. **90 new headless cases**
  (140 total), so
  [`coverageFloors[wonderland-modules]`](build.gradle) branch floor rises to
  `[line: 0.980, branch: 0.930]` (measured 98.23 % / 93.33 %); the residue is
  defensive branches genuinely unreachable without fault injection (the I/O
  catches, the JAXB / `ParserConfigurationException` guards, the non-namespace
  `local()` fallback, the transform `switch` default and the geometry
  `stride==0` / short-`<p>` guards). Depends only on `wonderland-common`; **no**
  `lg3d-core` / Java 3D import — the scene graph that *renders* these loaded
  models is the deferred **M6** seam, and no rendering is claimed working here.
- **The Wonderland plugin loader lands, headless, with the first five modules**
  (`wonderland-modules`, `src/main/java/…/modules/`, `src/main/java/…/modules/api/`,
  `src/main/resources/META-INF/services/`, `src/test/java/…/modules/`) —
  M4, the milestone that replaces the **NetBeans Platform module system**
  (`org.openide`, the NBM toolchain — both unrecoverable) and the dead
  scannotation class scan with a plain `java.util.ServiceLoader` plugin layer.
  `WonderlandPlugin` / `PluginContext` are the SPI, bridged to the module
  metadata already ported in `wonderland-common` (`ModuleInfo` /
  `ModuleRequires`). `WonderlandPluginLoader` does ServiceLoader discovery with
  duplicate/missing-name guards, **topological ordering** so a dependency
  initialises before its dependents, skip-and-cascade for an unsatisfiable
  requirement, a loud `PluginCycleException` on a cycle, ordered `initialize`
  and reverse-order `dispose`. Five low-risk modules ship as ServiceLoader
  providers — **chat** (conversation transcript), **imageviewer** (image
  load-status machine), **userlist** (present-user roster), **stickynote**
  (note content) and **whiteboard** (stroke surface + bounds) — each with a
  fully headless domain model; their Swing / Java 3D presentation is the
  documented deferred seam M2f used, landing with the desktop integration at
  **M6/M7** and NOT claimed working here. **50 new headless cases** back it,
  so [`coverageFloors[wonderland-modules]`](build.gradle) is set to
  `[line: 0.980, branch: 0.920]` (measured 99.80 % / 93.57 %); the residue is
  defensive branches — the `ServiceConfigurationError` catch, the shutdown
  per-pass dispose guard — unreachable without fault injection. The module
  depends only on `wonderland-common`; **no** `wonderland-client` / `lg3d-core`
  / Java 3D import yet.
- **The Wonderland session/host server binds real sockets and multiplexes one
  session over a client's three connections**
  (`wonderland-server`, `src/main/java/…/server/ws/`,
  `src/main/resources/application.properties`, `src/test/java/…/server/ws/`) —
  M3b, the second increment of the M3 milestone. It lifts M3a's "a socket owns
  its session" note and puts the router behind real, port-bound transport with
  **no** GlassFish. `application.properties` binds the Spring Boot lifecycle
  (`WonderlandServerApplication`) to a real embedded-Tomcat port serving both the
  `/comms` WebSocket and the `/api/world` REST surface (tests boot the same
  context on `RANDOM_PORT`, so parallel CI never collides). `CommsWebSocketHandler`
  now reproduces the faithful 2006 SGS model where ONE authenticated session
  carries the client's THREE cell connections as separate channels: a client's
  cache / channel / edit sockets are three distinct TCP connections, so the first
  attach mints a `ServerSession` and answers it with the `SessionInitialization`
  id, and each follow-on channel echoes that id in its `AttachClientMessage`
  properties (`SESSION_ID_KEY`, the id in hex) to rejoin the SAME session — one
  `SessionRegistry` entry per session, not per socket, which keeps the router's
  per-session semantics (echo suppression, broadcast scoping, the one view cell
  per session) intact. A single shared `FrameSink` **dispatches by
  `ConnectionType`** through a live per-session socket map, so a cache-targeted
  `LOAD_CELL` fan-out and a channel-targeted move `MOVED` broadcast each leave on
  the right one of that session's sockets, and the view-cell chain is flushed
  type-correctly to the session's cache socket. A socket close detaches just its
  own channel; the session (and its view cell) is torn down only when its last
  channel goes. **17 new cases** prove it: Mockito multi-socket one-session units
  (`CommsWebSocketHandlerTest`) plus a **real-socket**
  `@SpringBootTest(RANDOM_PORT)` + `StandardWebSocketClient` integration test
  (`CommsSocketIntegrationTest`) that boots embedded Tomcat and drives the login
  handshake, the three-sockets-one-session fan-out, a cross-session move over
  sockets and the REST world view — all through the real M1 codec, with **no
  functionality lost**. The pre-existing `ProtocolRouterTest` /
  `LoopbackClientServerTest` unused-import warnings are cleared; the module's
  [`coverageFloors[wonderland-server]`](build.gradle) branch floor now sits at
  `[line: 0.950, branch: 0.840]` (measured 96.30 % / 85.00 %), the residue being
  defensive transport branches that want a fault-injection seam.
- **The Wonderland session/host server lands on Spring Boot**
  (`wonderland-server`, `src/main/java/…/server/`, `src/test/java/…/server/`) —
  M3a, the first increment of the M3 milestone and the replacement for the
  retired **GlassFish Embedded 3.0 + Sun Game Server (SGS) + Apache MINA +
  JAX-RS** stack. `WonderlandServerApplication` is the one Spring Boot lifecycle
  that owns embedded Tomcat, the WebSocket endpoint and the REST controllers.
  `SessionRegistry` / `ServerSession` mint the BigInteger session ids and the
  per-connection short client ids the wire stamps (the id policy the 2006 SGS
  server used), and reach the client through a transport-agnostic `FrameSink`.
  `WorldModel` / `WorldCell` hold the authoritative cell hierarchy (with the
  environment-root remove guard and the `applyMove` authority). `ProtocolRouter`
  is the transport-agnostic brain both the M3a loopback and the M3b WebSocket
  call: the `SET_VIEW` handshake answers `ViewCreateResponse` and fans the world
  out as `LOAD_CELL`s; it registers a real `ViewCell`-typed world cell and pushes
  its `LOAD_CELL` through a **deferred post-reply flush** — the deterministic
  reproduction of the async socket ordering the client's `ViewCell →
  LocalAvatar.viewCellConfigured → setViewCell` walk depends on (a frame
  delivered mid-`sendAndWait` would walk the view cell against a not-yet-set
  id); an authoritative `MovableMessage` gets its `MOVE_MODIFIED` reply routed by
  `MessageID` plus a `senderID`-stamped broadcast every peer uses to suppress its
  own echo; a reparent echoes `CONFIGURE_CELL`; a corrupt frame is rejected.
  `WorldRestController` (Spring MVC), `CommsWebSocketHandler` + `WebSocketConfig`
  (`/comms`) compile and are wired but not yet bound to a port (M3b). Spring
  Boot's own **serialization-free** `MessagePacker` was hardened: `unpack` now
  null-guards its stream close so a malformed frame fails as a clean
  `PackerException` instead of an NPE (`wonderland-common`). **52 new headless
  cases**, the crown being `LoopbackClientServerTest` — a **real**
  `wonderland-client` `CellClientSession` driven against the **real** Spring
  router over the real M1 codec, proving handshake, world fan-out, the avatar
  command chain, the multiplayer move round trip, echo suppression and
  departure-unload with **no functionality lost** and **no** GlassFish.
  [`coverageFloors[wonderland-server]`](build.gradle) is set to
  `[line: 0.950, branch: 0.850]` (measured 96.21 % / 86.73 %).
- **The Wonderland view/avatar movement core lands, headless**
  (`wonderland-client`, `src/main/java/…/client/cell/`,
  `src/main/java/…/client/cell/view/`, `src/main/java/…/client/utils/`) —
  M2f, the sixth and last headless increment of the M2 milestone.
  `Throttle` is the shared rate limiter, keeping the 2006 quirk that
  `clear()` cannot cancel a pending task (the `ScheduledFuture` is never
  stored). `MovableComponent` moves a cell: a throttled (2/sec) move request
  whose first schedule runs inline, an inbound receiver that ignores the
  server echo of the session's own moves (sender identity read through the
  restored `CellCache.getSession()`), the LOCAL-applies-immediately /
  REMOTE-notifies change split, and the move-modified / error reply paths on
  the response listener — its `@UsesCellComponent` injection is a documented
  seam because the real channel is the cell's `getChannel()`, resolved at the
  `ACTIVE` rung, not a component-map entry. `MovableAvatarComponent` carries
  the trigger / pressed / animation / height / collision payload, keeps the
  trigger path deliberately **UNthrottled** (an animation must not be
  rate-limited away) and answers a non-null move-modified listener with the
  original `Not supported`. `ViewCell` + `LocalAvatar` land the configure
  handshake — the session now owns its avatar the way 2006 did, and
  `viewCellConfigured` fires as the status walk crosses `ACTIVE` / `INACTIVE`;
  `cache.setViewCell()` is restored with the original's **unconditional**
  roots-to-VISIBLE walk (even a null view walks the tree — pinned as a quirk),
  and the load guard regains its `instanceof ViewCell` arm. `AvatarCell`
  lands as the headless shell: the three trigger entry points keep the
  renderer-type guard and dispatch, while `createCellRenderer` returns null
  because the `client/jme` `AvatarJME` stack is **rebuilt at M6** on
  `lg3d-core`'s Jogamp bridge — the scene-graph half is not claimed working.
  **33 new headless cases** (164 total), so
  [`coverageFloors[wonderland-client]`](build.gradle) rises to
  `[line: 0.980, branch: 0.950]` (measured 98.37 % / 95.77 %); the residue is
  the branches a headless JVM cannot reach — `Throttle`'s dead cancel arm and
  the renderer status walk that wants a live Java 3D canvas. Still **no**
  `lg3d-core` / Java 3D import.
- **The Wonderland connection/session layer lands, headless**
  (`wonderland-client`, `src/main/java/…/client/comms/` +
  `src/main/java/…/client/cell/`, `src/test/java/…/comms/`) — M2e, the fifth
  increment of the M2 milestone. `BaseConnection` (the status pair, the
  `Not connected` send guard, the request-reply routing that consumes a
  `ResponseMessage` for a waiting listener before `handleMessage` ever sees
  it) over a seam-reduced `WonderlandSession` interface — the SGS login
  surface is **dropped, not stubbed**, and rebuilt on the M3 Spring session.
  The three cell connections land as the 2006 protocol machine, verbatim
  quirks and all: `CellCacheConnection` fans hierarchy messages out to the
  cache (including the documented `DELETE_CELL` fall-through) and registers
  the `ClientView` with the server on connect; `CellChannelConnection`
  demultiplexes inbound `CellMessage`s through a per-cell delivery that
  **queues before the receiver exists and flushes when the cell walks
  through `ACTIVE`**, with the original Noop-receiver blinds and the
  scheduled unload drop; `CellEditChannelConnection` is the typed edit front
  and makes M2d's `MessageSink` seam real — the cache's reparent requests now
  ride the edit channel. `ChannelComponent` lands as the loaded cell's actual
  `MessageChannel` (the receiver-map contract, the wrong-`CellID` SEVERE, the
  fill-in-your-own-id send), and `CellClientSession` wires the whole machine
  — tested end-to-end against an in-JVM **loopback `WonderlandSession`** (the
  M3 endpoint's stand-in): view registration over `sendAndWait`, hierarchy
  `LOAD` → cache → real channel → inbound message reaches the cell's
  receiver, missing-parent `LOAD` → `CellReparentMessage` out of the edit
  connection. **53 new headless cases** (131 total), so
  [`coverageFloors[wonderland-client]`](build.gradle) rises to
  `[line: 0.975, branch: 0.945]` (measured 98.06 % / 95.15 %); the residue is
  the renderer paths a headless JVM cannot run and the `ViewCell` / avatar
  branches that arrive with **M2f**. Still **no** `lg3d-core` / Java 3D
  import.
- **The Wonderland cell cache registry lands, headless**
  (`wonderland-client`, `src/main/java/…/client/cell/`,
  `src/test/java/…/cell/`) — M2d, the fourth increment of the M2 milestone.
  `CellCache` (the headless interface; the session / `ViewCell` /
  connection getters are dropped seams, documented per
  `docs/wonderland-migration.md`) and its registry implementation
  `CellCacheBasicImpl`: cells are instantiated by reflection through the now
  canonical `Cell(CellID, CellCache)` contract — `Cell` gains the cache
  back-reference (`getCellCache()`) the 2006 loader always passed — and the
  cache owns the load/get/configure/unload/reparent lifecycle: tree linkage
  with the `MultipleParentException` catches, a child whose parent the cache
  does not (yet) hold sends a `CellReparentMessage` over the new `MessageSink`
  seam (a session-level `Message` transport, distinct from M2c's per-cell
  `MessageChannel`, because `CellEditMessage` and `CellMessage` are siblings),
  the **synchronous** `DISK → … → VISIBLE` ordinal walk replaces the 2006
  async URL-downloading `ParallelCellStatusManager` (its own header defers
  the real manager to M2e), `setHasView` drives the whole-tree walk, a
  thread-local marks the cell currently in a status transition, and loads are
  timed through the ported pure `CellStatistics` layer (swappable SPI with the
  no-op default; `StringCellStat` / `LongCellStat` / `TimeCellStat` unit
  suffixes / `SizeCellStat` b-kb-mb formatting pinned verbatim). `EnvironmentCell`
  lands as the abstract `Cell` subclass the registry special-cases (loaded
  straight to `ACTIVE`, never in the root set). **29 new headless cases**
  (`CellCacheBasicImplTest` 21 + `CellStatisticsTest` 8) close the slice at
  100 % of its reachable branches, so
  [`coverageFloors[wonderland-client]`](build.gradle) rises to
  `[line: 0.965, branch: 0.935]` (measured 96.80 % / 93.95 %); the residue
  stays what a headless JVM cannot run — the live Java 3D renderer paths and
  the defensive listener guards — and the `ViewCell` / camera / avatar and
  connection layers arrive with **M2e**. Still **no** `lg3d-core` / Java 3D
  import.
- **The Wonderland `Cell` base class lands, headless**
  (`wonderland-client`, `src/main/java/…/client/cell/`,
  `src/test/java/…/cell/`; `wonderland-common`, `src/main/java/com/jme/…`) —
  M2c, the third increment of the M2 milestone. This is the class every earlier
  milestone circled: the `Cell` base object (ported from the decompiled
  Wonderland 0.5 `client.cell.Cell`) together with its `CellComponent`,
  `CellManager`, the five change-listener interfaces, the
  `CellClientStateMessage` / `CellComponentMessage` receivers and a new
  `MessageChannel` seam. It carries the cell **tree** (add/remove child, the
  2006 `MultipleParentException` contract), the `@ComponentLookupClass`
  interface-keyed component map, the `DISK → INACTIVE → ACTIVE` **ordinal status
  walk** in both directions (components and renderers are stepped one rung at a
  time; the DISK-down branch tears the map and unregisters receivers), the
  `@UsesCellComponent` reflective field injection, transform propagation with
  **world-bounds aggregation**, and component injection driven from a
  `CellClientState` over the classloader. Three unrecoverable seams are replaced
  per `docs/wonderland-migration.md` rather than stubbed: `ChannelComponent`
  (needs the M3 `ClientConnection`) becomes the `MessageChannel` interface, the
  `ClientContext.getRendererType()` lookup becomes a `Cell`-owned static holder,
  and the module classloader (`session.getSessionManager().getClassloader()`,
  an SGS relic) becomes `Cell.class.getClassLoader()`. To back the bounds
  aggregation the `com.jme` shim gains `BoundingVolume.mergeLocal` (AABB and
  sphere unions), which surfaced a **latent `Vector3f.normalize()` bug** — it
  called the non-mutating `divide()` and so never scaled in place; that is fixed
  and pinned. **25 new headless cases** (`CellTest` 15 +
  `CellComponentMessageReceiverTest` 10, plus `BoundingVolumeTest` merge pins)
  cover the reachable surface, so
  [`coverageFloors[wonderland-client]`](build.gradle) rises to
  `[line: 0.960, branch: 0.920]` (measured 96.42 % / 92.31 %); the residue is
  what genuinely cannot run in a headless JVM — `createCellRendererImpl` and the
  renderer status walk want a live Java 3D canvas, and the `CellCache` /
  session / login branches arrive with **M2d**. Still **no** `lg3d-core` /
  Java 3D import.
- **The Wonderland client gains its first real logic — the cell registry and the
  comms response substrate**
  (`wonderland-client`, `src/main/java/…/client/{cell,comms}/`,
  `src/test/java/…/{cell/utils,comms}/`) — M2b, the second increment of the M2
  milestone. M2 was re-sliced by what CI can actually verify **headless** (the
  excluded AWT peer means a live scene graph is not testable, and the repo
  mandates per-module coverage), so M2b ships the first code that does real work
  yet still depends on nothing beyond `wonderland-common` + the JDK, with no
  `lg3d-core` / Java 3D import: `CellSelectionRegistry` (the process-wide
  singleton that registers / reads back the M2a `CellSelectionSPI`, blanking the
  slot only when the *same* instance unregisters it, so a stale provider cannot
  drop the current one); the headless view / renderer **contracts** `ClientView`
  and `CellRenderer` (interfaces that name no scene-graph type — only the common
  `CellID` / `CellTransform` / `CellStatus` data they trade); and the comms
  response substrate the M3 session will sit on — the `ConnectionFailure` /
  `LoginFailure` / `ServerUnavailable` exception hierarchy (with the
  cause-message-folding `LoginFailureException(Throwable)` shape pinned), the
  `LoginParameters` / `WonderlandServerInfo` value types, and the
  `WaitResponseListener` / `OKErrorResponseListener` blocking request-reply pair
  (a caller parked in `waitForResponse` wakes when the reader thread feeds
  `responseReceived`, and the OK/Error/unexpected three-way dispatch routes it —
  exercised with a helper thread, no socket). **16 new headless cases**
  (`CellSelectionRegistryTest` + `ResponseListenerTest` + `CommsValueTypesTest`,
  on M2a's 8) cover **every line and all 16 branches**, so
  [`coverageFloors[wonderland-client]`](build.gradle) rises to
  `[line: 0.950, branch: 0.900]` (measured 100 % / 100 %) — the branch floor is
  real now that the module has branch logic, replacing M2a's `0.000`
  placeholder. What is deliberately **not** here is everything that reaches
  `Cell` itself: the `Cell` base class, the connection / session layer
  (`BaseConnection` / `WonderlandSession`), the login package and the `client/jme`
  renderer all pull `lg3d-core` (Java 3D) and the jME scene-graph shim, so they
  land as **M2c**, probe-verified in-JVM per `lg3d-core/AGENTS.md` rather than
  claimed green; they proceed only after this PR lands and the direction is
  re-confirmed.
- **The Wonderland client's cell layer starts landing bottom-up**
  (`wonderland-client`, `src/main/java/…/client/cell/`, `src/test/java/…/cell/`)
  — M2a, the first increment of the migration plan's M2 milestone. The full M2
  (port `client/jme` + `cellrenderer` onto Jogamp `org.jogamp.java3d`) cannot
  land as one PR, so M2a ships only the self-contained cell *leaf* slice that
  depends on nothing beyond `wonderland-common` + the JDK: the `@CellFactory`,
  `@CellComponentFactory` and `@UsesCellComponent` annotations, the
  `CellFactorySPI` / `CellComponentFactorySPI` / `CellSelectionSPI` SPIs, the
  `AssetPreloader` callback interface and `CellCreationException`. Two headless
  suites (**8 cases**: `CellFactorySpiTest` + `CellRegistryAnnotationsTest`)
  pin the shape the M2b registry and renderer compile against — above all the
  generic covariant-default-state contract
  `<T extends CellServerState> T getDefaultCellServerState(Properties)`, whose
  anonymous implementations prove a caller assigns the erased return back to
  `ModelCellServerState` with no cast, exactly what the decompiled
  `CellServerState` subclasses rely on. `wonderland-client` graduates from M0's
  scaffold to a building, testing module: its `build.gradle` now declares the
  `wonderland-common` dependency and the JUnit 5 + AssertJ + JaCoCo wiring, and
  a new [`coverageFloors[wonderland-client]`](build.gradle) entry
  (`[line: 0.900, branch: 0.000]`) gates it — the slice is interfaces and
  annotations whose classfiles carry no executable lines, so the floor is
  measured against `CellCreationException`, the only coded class, at 100 %;
  it rises with M2b's real logic. No `lg3d-core` / Java 3D import — the
  module's first real logic arrived in M2b (the cell selection registry and the
  comms response substrate, both still headless); everything that reaches
  `Cell` itself — the base class, the connection / session layer and the
  `client/jme` renderer — lands as M2c, probe-verified in-JVM per
  `lg3d-core/AGENTS.md`, and proceeds only after this PR lands and the direction
  is re-confirmed.
- **The Wonderland wire protocol is now a tested contract**
  (`wonderland-common`, `src/test/java/…/messages/`, `…/comms/messages/`,
  `…/cell/messages/`) — M1, the second milestone on the migration plan. M0
  shipped the ~150-class data model compiling green on JDK 21 but with no
  proof that its Java-serialisation shape still worked; that shape *is* the
  wire contract between the ported client and the M3 Spring Boot session
  server (M3 carries these exact bytes over a `BinaryWebSocketHandler`), so
  any decompilation drift in a field name, an erased generic, a `serialVersionUID`
  or the Externalizable `MessageID` envelope would silently corrupt the
  protocol at runtime. Three new suites add **43 round-trip cases on top of
  M0's 77**, every one of them packing a message through
  `MessagePacker.pack(msg, clientID)` and reading it back through
  `MessagePacker.unpack(buf)` — the same two entry points M3 will call:
  `MessagePackerTest` (11) pins the `MessageID` Externalizable path
  (long value + `Long.MIN_VALUE` "no-reply-to" sentinel + the
  `generateMessageID()` monotonic counter), the `ErrorMessage` cause/text
  pair, `ProtocolSelectionMessage` with a concrete
  `DefaultProtocolVersion`, the `MessageList` nested-message case, the
  empty `WonderlandSetupMessage`, the `clientID` short including 0 and
  negatives, and that two consecutive packs in the same JVM stay
  independent — the pin for `WonderlandObjectOutputStream.descToId` being
  read-only; `CommsMessagesCodecTest` (7) pins the session handshake: the
  `AttachClient` / `AttachedClient` / `DetachClient` trio, the
  `SessionInternalConnectionType` marker subclass surviving as its own
  class (not the `ConnectionType` base), and `SessionInitializationMessage`
  carrying a `BigInteger` session id alongside a `WonderlandIdentity`;
  `CellMessagesCodecTest` (25) covers every concrete subclass under
  `common/cell/messages/` — the `CellEditMessage` family
  (`Create` / `Delete` / `Duplicate` / `Reparent` each with its `EditType`),
  the `CellServerState{Request,Response,Set,Update}Message` group with
  `AvatarCellServerState` / `ModelCellServerState` concrete subclasses
  resolving back to their own class (that is what
  `ScannedClassLoader.getInstance(Class)` needs), the `Component` add /
  remove messages with the `ComponentAction` enum, the `Client` state
  messages, the `CellHierarchy{,Unload}Message` pair (which carries the
  jME `BoundingVolume` + `CellTransform` + `CellClientState` triple that
  M2's renderer will consume), `MovableAvatarMessage` (the only
  `coreClass` message that crosses every one of the nine cached class
  descriptors — Vector3f, Quaternion, CellID, CellMessage, Message, etc.
  — in a single pack), the `MovableMessage` `MOVED` / `MOVE_REQUEST`
  action split, and `MovableMessageResponse` / `ViewCreateResponseMessage`.
  The raw-`Set` field on `CellServerStateUpdateMessage` — an artefact of
  Fernflower erasing the generic — is exercised with a `ComponentHolder`
  wrapper so the hashCode-based collection still resolves after
  deserialisation. Two shim bugs surfaced on the way and were fixed: the
  `Set` cast had to be a two-step through `Set<?>` to keep `-Xlint`
  quiet, and `CellHierarchyMessage` does not carry the cell name on the
  `AVATAR_CELL_{ADDED,REMOVED}` branches, which the test asserts rather
  than papers over. Measured coverage jumped from **17.56% / 17.90%** to
  **32.36% / 21.48%**, so [`coverageFloors[wonderland-common]`](build.gradle)
  is raised to `[line: 0.300, branch: 0.200]` in the same PR. The
  [migration doc](docs/wonderland-migration.md) M1 row is flipped to
  🟨 in progress and its **session/host server** decision is now
  recorded as **Spring Boot 3** (embedded Tomcat + Spring WebSocket +
  REST controllers) rather than the earlier "plain `java.net` /
  `java.nio`" placeholder — chosen because the client already needs an
  HTTP surface for WFS, and one Spring Boot process gives both transports
  from the same lifecycle. M2 (Jogamp scene-graph renderer), M3 (the
  Spring Boot session server itself), M4 (NetBeans-layer plugin loader),
  M5 (asset pipeline + bundled worlds), M6 (2D + 3D desktop integration)
  and M7 (`voicebridge` / `avatar` / `animation` / `videoplayer` /
  `pdfviewer`) still proceed only after this PR lands and the direction
  is re-confirmed.
- **The Wonderland migration begins with a JDK-21 data-model foundation**
  (`wonderland-common`, `wonderland-client`, `wonderland-server`,
  `wonderland-modules`; `docs/wonderland-migration.md`) — the repo has shipped
  `wonderland/Wonderland.jar` (the 400 MB Java-5 installer, `.gitignore`d) for
  years with no way to build or run it on a current toolchain. M0 is the first
  PR on the approved milestone plan (`docs/wonderland-migration.md` records
  the subsystem inventory, the per-milestone parity table, and every
  rebuild-vs-port decision for the unrecoverable tech). What landed: four
  Gradle modules wired into `settings.gradle` and inheriting the root
  `subprojects` block (JDK 21 toolchain, JaCoCo, Checkstyle, CycloneDX);
  146 classes decompiled with Fernflower from `wonderland-common.jar` +
  `wonderland-wfs.jar` and hand-migrated to JDK 21 (vecmath imports renamed to
  `org.jogamp.vecmath`, 11 raw-collection for-each loops recovered, 4 JAXB
  `XmlAdapter` subclasses re-parameterised, `ChecksumList` parameterised +
  given its erased `@XmlElement(type = Checksum.class)` so JAXB actually
  marshals on JDK 21); `ScannedClassLoader` rebuilt from `sun.misc.Service` +
  scannotation onto `java.util.ServiceLoader` + `org.reflections:reflections:
  0.10.2` while preserving its raw-`Iterator` public surface so
  `CellServerStateFactory` stays source-compatible; **a from-scratch 10-file
  in-tree `com.jme.*` shim** (`math.{Vector3f,Quaternion,Matrix3f,Matrix4f,
  FastMath}`, `bounding.{BoundingVolume,BoundingBox,BoundingSphere}`,
  `renderer.ColorRGBA`, `scene.state.MaterialState`) that implements exactly
  the surface the ported data model calls, because jME 1.x was fetched via
  Java Web Start at launch and is *not* in Wonderland.jar — no bytecode to
  port; `javax.xml.bind` restored against `jakarta.xml.bind:jakarta.xml.bind-api:
  2.3.3` + `org.glassfish.jaxb:jaxb-runtime:2.3.9` (the 2.3.x line still uses
  the `javax.xml.bind` package name). 77 headless JUnit-5 tests across
  `com.jme.{math,bounding}`, `Math3DUtils`, `CellTransform` (both the pure-shim
  `transform/sub/clone` path and the vecmath `mul/invert` path),
  `ChecksumList` (an end-to-end JAXB round-trip that also proves the
  dependency wiring), and `ScannedClassLoader` (stable singleton, drainable
  iterators, `ClassScanningError` cause wrapping). Measured 17.56% line /
  17.90% branch on the new module, floor set to
  [`coverageFloors[line: 0.150, branch: 0.150]`](build.gradle) and raised per
  milestone. `.gitignore` gained an anchored `/wonderland/` rule so the
  400 MB installer stays out without also hiding the
  `wonderland-common/src/main/java/org/jdesktop/wonderland/` package tree.
  No milestone claims global 100% parity; M1 (`client/cell` + `client/comms`
  protocol round-trips), M2 (Jogamp scene-graph renderer), M3 (rebuilt
  session server to replace GlassFish/SGS/MINA), M4 (NetBeans-layer plugin
  loader), M5 (asset pipeline + bundled worlds), M6 (2D + 3D desktop
  integration via `SwingNode`) and M7 (`voicebridge` / `avatar` / `animation`
  / `videoplayer` / `pdfviewer`) proceed only after this PR lands and the
  direction is re-confirmed. `darkstar` + `sas`/`sasxremwin`/`xremwin` are
  dropped outright: their scene/applet functions are already covered by the
  Jogamp stack plus `SwingNode` (see [docs/swingnode.md](docs/swingnode.md)).
- **A published page demonstrating the language in everyday use**
  (`lg3d-mandela/docs/USAGE.md`, mirrored on the GitHub Pages site as
  `documentation/mandela/docs/USAGE.md`) — the module had a reference, an
  architecture and a history, and nothing that answered *what do I write this in
  for, on a Tuesday*. The new page is a demonstration rather than a manual, and
  every block of it was executed rather than recalled: one ~80-line program that
  does what almost every desktop script does (**walk** a tree with `fs.list` +
  `fs.isDirectory`, **scan** the text it found with `indexOf`/`substring` in a
  loop, **classify** with a `match` expression, **answer** through
  `json.stringify`) followed by the CLI's real output, which shows 10 shipped
  programs, the 8 wired gate tasks, and the security model in a single JSON field
  — the script could read the tree but `env.get` came back
  `refused by the host (PermissionError)`. A table then puts the four hosts
  side by side with what each actually grants, read out of the code that builds
  the engine: the CLI's `desktop(root)` at 50 M steps, Espresso's
  `mandela-run`/`-check`/`-bytecode`/template actions on the `console()` profile
  (no files at all — an unsaved buffer is not a sandbox), the browser's
  `webPage()` at 5 M steps with `page`/`inject`/`note` bound and `println`
  *bound* to the browser log rather than granted, and any JVM through the facade
  or JSR-223. The page also documents what the host accepts instead of a regular
  expression — the `// @match` glob is `*`, `?` and literals, translated by the
  host — closes with the four rules a first script learns (`-s DIR`, an
  immutable `let`, `+` refusing `Str` + number, and `Str.indexOf` taking one
  argument), and reproduces real `check`, `dump` and runaway-`eval` transcripts
  with the exit codes a build can branch on. The site copy is the module copy's
  mirror with the links that leave `documentation/` made absolute, both module
  indexes and the site READMEs list it, and the portal gained a card in each
  language; the two stale "VM instruction set" blurbs in the site README were
  corrected on the way past, since no guide in the tree contains one.
- **A published history of the language** (`lg3d-mandela/docs/HISTORY.md`, mirrored on
  the GitHub Pages site as `documentation/mandela/docs/HISTORY.md`) — the module told
  you what Mandela is and how it is built, and never once why it is called *Mandela*,
  who made it, or when any of it arrived. The new page answers all three: the homage to
  Nelson Mandela stated as the record has it (imprisoned from 1962 until his release on
  11 February 1990, president 1994–1999, died 5 December 2013 — he did **not** die in
  prison, which is the telling this page exists to correct), the creator attribution to
  Jean-François Landreville publishing as Dr. Land Evil, the four reasons a language of
  its own was written rather than bought, and a dated milestone table read out of
  `git log` — the module landing whole in one commit on 2026-10-10, then the scripts,
  the guides, the BDD suite, the defect campaign and the eight gates. The module's copy
  is canonical and the site copy is its mirror with the links that leave
  `documentation/` made absolute, exactly as the other four guides are; both indexes
  link it and the portal gained a card in each language.
- **The Help Center now writes its own What's New page from the CHANGELOG**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.help` + `scripts/mandela/`) — the built-in JavaHelp
  guide used to say only what a person remembered to write into a topic, so it went stale on
  the day its refresh PR merged, and the desktop's own release notes were unreadable from
  inside the desktop. `whats-new.mnd` now renders the `[Unreleased]` section and the three
  most recently released ones into a nineteenth topic (`whats-new.html`), which the existing
  `generateHelpSearchIndex` task indexes like any other because it globs every `*.html` in
  the directory, so release notes are full-text searchable from the Search tab. The same
  script is the gate: run without its `write` word it re-renders in memory and fails when the
  committed topic no longer matches `CHANGELOG.md`, which is what turns "remember to refresh
  the help" into a build failure, and it reports how many `Added` entries are still not named
  in any topic — the candidates for a real new page. The rendered topic is a committed
  generated file: edit `CHANGELOG.md` and re-run `:lg3d-mandela:whatsNewRefresh`, never the
  HTML.
- **Project Mandela's module documentation is now published on the GitHub Pages portal**
  (`documentation/`, mirrors `lg3d-mandela/docs/`) — the four module guides (overview,
  architecture, development, functional analysis) live under `documentation/mandela/docs/`
  and are reached from the bilingual landing page, where two new cards open them through
  `viewer.html`. The links that leave `documentation/` were rewritten to absolute GitHub
  URLs so they resolve on the published site — Pages serves this directory as the site root,
  so a `../` link 404s there even though it works in the module tree — and the site README
  gained the matching structure entry and a bilingual *Project Mandela* section.
- **A crash-report pipeline that turns a crash into a GitHub issue without ever
  holding a credential** (`lg3d-core`, `org.jdesktop.lg3d.displayserver.crash`) —
  the desktop has had a crash *dialog* since 2006 and no way to make one into a bug
  report: its button opened a bare tracker page and expected the reporter to retype
  what the dialog was already showing. Eight classes now do that end to end.
  `CrashSignature` hashes the root cause plus up to six non-synthetic frames to 12
  hex characters — deliberately *not* the message and not the version, so an upgrade
  does not re-file a known bug and a crash loop stays one bug; its `ofNarrative`
  gives a hand-written report, which has no frames to sign, an identity taken from
  its own words and normalised for whitespace — reformatting a draft does not open a
  second archive entry, and two hand-written bugs are no longer merged into one by
  the builder's no-throwable fallback, which hashes the message and therefore gave
  every draft whose title went to the summary the same bucket; `Redactor` replaces
  home paths, the runtime user name, e-mail addresses, socket paths, GitHub / AWS /
  Google keys and `key = secret` pairs before anything is written, with linear
  patterns so a report cannot be a ReDoS against its own author; `ReportEnvironment`
  collects the twelve fields that diagnose without identifying anyone (version, JVM,
  OS, display, locale, memory — no user name, no host name); `CrashReport` is the
  one immutable text that is simultaneously the spool file, the clipboard content
  and the issue body, so what a reporter copies is what a maintainer reads;
  `CrashStore` keeps `~/.lg3d/crash-reports` grouped by signature with an
  occurrence count, written atomically and pruned under both a 50-report and an
  8 MB ceiling; `IssuePrefill` builds the
  `github.com/landrevillejf/ProjectLookingGlass/issues/new` link — titled, labelled
  `crash`, assigned, with the body clipped to a 7600-byte *encoded* budget rather
  than a character count, because percent-escaping triples what a URL can actually
  carry; `CrashReporter` is the facade on one daemon thread; and `CrashWatcher` is
  the `Thread.setDefaultUncaughtExceptionHandler` hook installed on the first line
  of `Main.main`, which still prints the dump the JDK would have printed — installing
  a handler *silences* it — and still chains to the handler the host already had.
  **There is no HTTP call anywhere in it.** The only network-facing artefact is a
  link handed to the browser, so the person stays the one who submits, the review
  step an unattended upload would skip is kept, and a kiosk image has no token to
  leak.
- **The noise rules are what makes it usable** (`lg3d-core`, `CrashReporter`) — one
  signature opens one browser tab per session, a bug already in the archive is not
  reopened by a new session, and a per-session budget caps how many *distinct* bugs
  may interrupt anyone; past it reports are still captured, silently, because
  capture is local and cheap and interruption is the only real cost. A report a
  *person* asked for — the dialog's new **Report this bug** button, the manual Bug
  Reporter app to come — overrides every one of those rules. Everything is
  configurable without a rebuild through `lg.crashreport.*`: `enabled`, `repo`,
  `labels`, `assignee`, `maxPerSession`, `dir`, `maxReports`, `maxBytes`. The
  assignee is the "notify the admin" answer: GitHub mails the person pre-filled in
  the link, so there is no SMTP configuration and no address baked into a build.
  A kill switch never silences a hand-written report, and only the literal word
  `false` flips it — a typo is not a reason to go back to twenty years of
  unreported crashes.
- **A SEVERE log record is a bug report too** (`lg3d-core`, `LogHandler`) — the
  trigger is no longer only an escaping exception, and the record's own text is now
  carried into the report: without that, the desktop's most common complaint
  (`cannot open {0}`, throwing nothing) filed a form with nothing in it. `describe`
  does the `{i}` substitution by hand, because `MessageFormat` eats apostrophes. The
  link the pipeline assembles is handed to the error dialog through `setReportLink`
  — off the EDT, on a `volatile`, since a slow disk must not delay the warning — and
  nothing at all is captured when there is neither a stack nor a text.
- **123 headless tests for the crash package**
  (`lg3d-core`, `src/test/java/org/jdesktop/lg3d/displayserver/crash/`) — one suite
  per class, none constructing a widget or touching the network, with a recording
  sink standing in for the browser. Writing them found six defects that reading
  the same code had missed: every *automatic* capture was bypassing dedupe and the
  budget (`doReport` passed `explicit = true`, so a crash loop would have opened a
  browser tab per exception), a console echo that threw skipped the chained host
  handler, a second `install()` stacked a second watcher and captured every crash
  twice, a re-read archive could no longer tell an uncaught exception from a log
  record (`CrashStore.read` hardcoded the reporter), a `prune()` that mutated
  the list it was iterating would have thrown `ConcurrentModificationException`
  from inside the crash reporter — a crash in the bug reporter — while its byte
  ceiling deleted the very report that had just been written, and a SEVERE record
  with no throwable filed a report with no content in it. Designing the Bug
  Reporter panel that consumes it turned up three more, all on the archive's round
  trip: a *named evidence file* kept the absolute path a file chooser hands back —
  the reporter's own account name, in a public issue, while `addAttachment`'
  javadoc claimed it had been shortened; `record.json` stored none of the human
  half of a report, so re-filing a stored bug came back as
  `"[MINOR] severe error"` with no steps, no expected/actual and no evidence — the
  work a person typed, discarded; and the body's `(no message)` placeholder was
  read back as an actual message, giving a re-filed report a title ending in
  `": (no message)"`. `CrashStoreTest.manualNarrativeSurvivesTheArchive` pins the
  round trip now.
- **A Bug Reporter app — the manual half of the crash-reporting tool**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.bugreporter`; `lg3d-core`,
  `Desktop2DAppRegistry`) — the automatic capture structurally cannot see the
  bugs that never throw: a task bar that wraps, a splash that stays, a resize
  that loses a workspace. Start Menu → Utilities → **Bug Reporter** opens a
  form for exactly those: subject, repro steps, expected/actual, severity, a
  typed evidence list (paths are named, never uploaded, and reduced to bare
  redacted file names before anything is filed), a live preview of the *exact*
  issue text, and the same prefilled `github.com/.../issues/new` link the crash
  pipeline produces — still no token, still the human pressing the real button.
  A crash already in the spool can be **attached**, and the filed report then
  keeps the crash's signature, trace and environment with the human narrative
  layered on top, so one bug never splits into two issues; a hand-written
  report with no crash signs itself through `CrashSignature.ofNarrative`. One
  `BugReporterPanel` serves both desktops — `TitledSwingWindow`/SwingNode in
  3D, an MDI internal frame via `PANEL_APPS` in 2D — with all the consequences
  in a headless `BugReportDraft` (a non-blank subject is the only blocker,
  everything else is advice), disk and browser work off the EDT on a named
  daemon executor, and no modal dialog anywhere. 17 headless tests
  (`BugReportDraftTest` 10, `BugReporterPanelTest` 7).
- **Internationalisation of the shell — the desktop can now speak French**
  (`lg3d-core`, `org.jdesktop.lg3d.i18n`; `lg3d-apps`, Control Center) — the
  2006 codebase shipped zero user-facing i18n machinery (the three
  `ExceptionStrings.properties` files are remnants of a bundle system that no
  longer exists), so every label was welded into the Java. Tranche 1 builds the
  mechanism and translates the *shell*: `LgLocale` resolves the run's language
  once at start (`-Dlg.locale` > the persisted `desktop.locale` preference >
  the JVM default, and any unknown pin lands on English — visible-but-English
  beats blank or crashed), and `Messages.tr(key, args…)` reads
  `messages.properties` (English source of code) or its French twin through a
  `ResourceBundle.Control` whose fallback is *null*, so an unsupported host
  locale never hijacks the language. A miss echoes the key and a broken
  `MessageFormat` pattern echoes the pattern — the painting path cannot throw.
  Extracted: the whole 2D shell (taskbar, start menu, tray, pager, run dialog,
  context menu, About box, launch notifications, power/exit confirms), the 3D
  chrome (start-menu panel/disc/orbit labels, dock folder stacks, the 3D run
  card), and the `.lgcfg` descriptors *without touching the 83 XML files* —
  both readers look up `lgcfg.<slug-of-name>.name/.desc` and fall back to the
  XML literal, so an untranslated descriptor simply keeps its published name
  and `startmenu-audit.mnd` is untouched. Control Center → Customization gains
  a Language selector (System default / English / Français, persisted, applied
  at the next desktop restart — no hot-swap by design, a live scene graph
  re-tinting its own text was never on the table). 288 keys in each bundle —
  97 shell labels plus 191 start-menu descriptor entries — pinned three ways:
  `LgLocaleTest`/`MessagesTest` headless, a descriptor
  round trip in `Desktop2DMenuConfigTest`, and the new
  `scripts/mandela/i18n-keys.mnd` gate in `:lg3d-mandela:repositoryChecks`
  (every `tr` literal exists, en/fr key sets are equal, `{n}` placeholders
  agree). All `Test` tasks run with `lg.locale=en` so the extraction cannot
  quietly change what existing assertions read. The apps themselves
  (~2,000 strings) are tranche 2, one PR per start-menu group.
- **The runaway-loop guard finally has a test, and two gates join the repository
  checks** (`lg3d-mandela`, `vm/VM` + `MachineHostTest`; `scripts/mandela/`) — the
  step budget is the sandbox's headline promise (a mistyped `loop { }` cannot hang
  the desktop) yet it was the one machine limit asserted *nowhere*: `MachineHostTest`
  pinned the call-depth and host re-entrancy guards, but a change to the retired-loop
  check could regress silently. Three tests now pin it — a `loop`/`while` runaway is
  retired at the ceiling and the message quotes `maxSteps` back (the number a host
  tunes against), honest work *under* the ceiling runs (the other side of the `>`),
  and tripping it is recoverable, so a host can `try` around untrusted code and keep
  its engine. Two gates join `:lg3d-mandela:repositoryChecks` (seven at this
  writing, eight with the What's New gate above):
  `legacy-j3d.mnd` turns the Java 3D migration rule into a build truth — no source the
  build compiles may `import` a pre-migration package (`javax.media.j3d` /
  `javax.vecmath` / `com.sun.j3d`), scoped so the do-not-edit `lg3d-docs`, the retired
  `lg3d-awt` toolkit and the two deserialisation shims stay legitimately out of scope —
  and `scripts-hygiene.mnd` closes the rot neither the compiler nor Gradle's own wiring
  can see: a gate pointing at a missing script, a registered task the `repositoryChecks`
  aggregate silently omits, or a shipped `.mnd` absent from the scripts README (and
  vice versa). Both were proven to bite on a planted defect before shipping green.

### Fixed
- **A parsed COLLADA document lost the author's order, and the avatar clip list
  shuffled itself** (`wonderland-modules`, `wonderland-renderer`) —
  `ColladaDocument` kept every library (`animations`, `effects`, `visualScenes`,
  `geometries`, `images`, `materials`) as a `Map.copyOf` copy while its own
  javadoc promised *parse order*, and `ColladaClips.fromDocument` documented that
  its clip map "matches the library's parse order" on top of that. `Map.copyOf`
  returns an unordered immutable map: its iteration sequence comes from the keys'
  hashes and is not specified, so `AnimationLibrary.names()` (the clip listing),
  `AnimationClip.animatedNodes()` / `sampleAt()` (the sequence the live renderer
  walks to write the per-node LOCAL transforms) and `ColladaDocument.texturePaths()`
  (documented *encounter* order) all silently disagreed with their contract. It
  bit M7c's `AvatarAnimationBundledPoolTest.bodyAclipsParseFromTheShippedBytes`,
  which asks the shipped `humanoid-a.dae` for `[walking, idle]` and got
  `[idle, walking]` back on some JVM runs and the expected pair on others — a
  four-in-six failure rate measured on the branch before the fix, i.e. a flaky CI
  step over what is really an ordering bug. The parsers were already building
  `LinkedHashMap`s; the views now wrap them with `Collections.unmodifiableMap`
  (same immutability, real document order), so the sequence is fixed by the file
  rather than by a hash. Pinned by the new
  `AnimationLibraryTest.clipAndNodeOrderIsTheAuthorsNotTheHashesOfTheNames`, which
  asserts the guarantee at all three levels — catalog, clip, and two `<animation>`
  elements parsed end to end — with names chosen so a hash order would differ
  from the written order.
- **`indexOf` now honours its start index instead of ignoring it**
  (`lg3d-mandela`, `rt/StandardLibrary`) — `s.indexOf(needle, from)` read only
  `args[0]`, so it answered the *first* occurrence and called it the answer: a
  script asking for the closing quote after the opening one got the opening one
  back, no error, and a wrong number. Found while writing
  [`Mandela at Work`](lg3d-mandela/docs/USAGE.md), whose demo printed "0 wired
  gate task(s)" until the scan was bisected (measured before the fix: both calls
  returned index 13 on the same line). Both spellings are fixed together —
  `Str.indexOf` and `List.indexOf` — because leaving the start index honoured in
  one and ignored in the other would have kept the defect and only moved it. The
  rules are the ones a Java-shaped surface promises: a negative start is `0` and
  not a count from the end, so the `-1` a failed lookup answers cannot scramble
  the search that follows it; a start past the end answers `-1` rather than
  refusing; a `Long` too big for an index is clamped instead of wrapping
  (`"a-b-c".indexOf("c", 9223372036854775807)` is `-1`, not a crash and not a
  wrong hit); a non-number is the `TypeError` the rest of the library raises.
  Thirteen new cases in `StandardLibraryTest` pin every branch through the public
  facade, including the scanner idiom the page now teaches
  (`t.indexOf("'", t.indexOf("'") + 1)` is the *second* quote), and
  §9 of the [language guide](docs/mandela-language.md) documents the argument
  where the member table previously listed only the name. No shipped script
  passed a second argument, so nothing in the tree was relying on the old
  behaviour; the page's demo was rewritten to the direct call and its transcript
  re-run, which is byte-identical apart from the timestamp — the fix changes the
  call, not the answer the workaround had been forced to reach.
- **The release train no longer cuts releases by itself** (`.github/workflows/release-train.yml`)
  — a merge to `main` could complete the one open milestone and the train then auto-cut a
  `release/X.Y.Z` branch without any human deciding to ship; with the `RELEASE_PAT` secret
  absent the cut died halfway (the release PR cannot be created by `GITHUB_TOKEN`), leaving
  orphan stale branches — three of them, `release/1.67.0`, `release/1.68.1` and
  `release/1.69.0`, accumulated before this fix. The root confusion was a stale milestone
  title (`1.28.0`, open since September, permanently "complete") that `sync-milestone` reused
  without checking it against the derived version. Now `cut-rc` fires only on an explicit
  `workflow_dispatch`, the merge-assign job no longer dispatches a release re-evaluation, the
  daily schedule is bookkeeping only, and `sync-milestone` renames a drifted milestone title
  to the derived version. The canonical release path is the local `./make-release.sh`, which
  pushes the annotated tag with real credentials so `release.yml` actually publishes
  — documented as such in [`docs/release-process.md`](docs/release-process.md).
- **A class's construction counts its initialiser's parameters, not its fields**
  (`lg3d-mandela`, `vm/VM`) — `class Box { var v = 1 }` has one field and takes no
  argument, but the upper bound of the count was read off the declaration's field
  list, so `Box(42)` was accepted and overwrote the body's default. The refusal now
  names the count the author wrote, and a named call that leaves a required field
  with no default as a hole is refused on that field's name instead of handing the
  machine's own `<absent>` marker to the body. Pinned by `MachineDispatchTest`.
- **The host re-entrancy limit reports the depth that was reached**
  (`lg3d-mandela`, `vm/VM`) — the sentence was built after the counter was restored
  to the outer frame, so it understated the recursion by one and pointed a host
  tuning its budget at a number that would have been enough; the allowed budget is
  now named in the message too. Pinned by `MachineHostTest`.
- **An `Int` operation that overflows promotes to `Double` instead of answering with
  its left operand** (`lg3d-mandela`, `values/Operators`) — the promotion branch had
  been written as an expression that cancelled the right-hand side away, so
  `1 + 9223372036854775807` answered `1.0`; `2 ** 63` let a raw `ArithmeticException`
  escape the language's error model and print a Java stack trace. The documented rule
  is now in the guide and pinned by `ArithmeticOverflowTest`.
- **A closure can capture a variable owned by a grandparent function**
  (`lg3d-mandela`, `code/Compiler`) — the intermediate leg of the capture chain was
  never emitted, so a closure built two functions below the one that owns the name
  read a slot its own frame does not have and the machine died with
  `ArrayIndexOutOfBoundsException`. Pinned by `ClosureCaptureTest`.
- **The guide described a range as exclusive of its end** (`docs/mandela-language.md`)
  — the machine has always answered `5` for `(1..5).length`, as `MandelaRange` and
  `OperatorRulesTest` agree, while the prose said `a..b` stops before `b`. A reader
  following the sentence would have written `1..n` for a loop that must not overshoot;
  the guide now states the inclusive rule and names `a..<b>` as the exclusive form.
- **A host function's Java answer is converted at the machine, like a bound name is**
  (`lg3d-mandela`, `vm/VM`) — `HostFunction.of("count", 1, args -> text.length())` is
  the shape the embedding guide tells a host to write, and `length()` answers a Java
  `int`. The call pushed it unchanged, so the script held a value the language has no
  name for: `typeOf(...)` answered `Host`, `count("abc") == 3` was false
  (`Integer.equals(Long)`), `1 + count("abc")` was a `TypeError`, and a member read off
  the number fell through to a raw `java.lang.Integer` path that no script could tell
  was there. The documented conversion table is now applied by `VM.callHost` itself,
  which also means an object with no Mandela form is refused with its class named
  instead of handed to the script. Pinned by `HostBoundaryTest`; the two
  `instanceof Integer` accommodations in `StandardLibrary` are gone with it, one of
  which (`whole(...)`) answered `0` for any `Integer` it was given.
- **A bare `## [Unreleased]` header no longer turns `release-check` red**
  (`scripts/mandela/release-check.mnd`) — the gate read the version out of the
  CHANGELOG's *first* `## [` header, so the legitimate state between a release cut
  and the next bump chore — a feature branch opening an unnamed `[Unreleased]`
  section above the newest release — was reported as a self-contradictory
  repository (`CHANGELOG.md says Unreleased, build.gradle says 1.68.0`). It now
  walks the headers and returns the first one that actually carries a version, so
  `:lg3d-mandela:repositoryChecks` is green in all three states a branch moves
  through: released, development (`## [Unreleased] — 1.69.0-dev`) and in-between.
- **`std.json` refuses the numbers RFC 8259 refuses** (`lg3d-mandela`, `rt/Json`) —
  the reader's own javadoc says it "stays strict on purpose, because a document that
  two parsers disagree about is a bug nobody can find later", but it read `01` as the
  `Int` `1`. A hand-written settings file, or an octal-looking literal from a lenient
  producer, therefore parsed here and failed (or parsed as a different value) at the
  other end of the wire; `json.isValid` answered `true` for it, which is the worse
  half, since a script that validates before parsing was told the document was JSON.
  A second digit after a `0` is now a `JsonError` naming the line and column.
- **A Java value handed to the writer crosses the table the machine uses**
  (`lg3d-mandela`, `rt/Json`) — `Json.encode` and `Json.stringify` are public Java
  entry points, and the writer special-cased exactly one host type, `Integer`. So
  `Json.encode("abc".length())` answered `3` while `Json.encode(1.5f)`,
  `Json.encode(List.of(1, 2))` and `Json.stringify(Map.of("k", "v"))` all raised
  *json cannot encode a Host* — an arbitrary line for a host, and the same leak
  `VM.callHost` was just fixed for. The writer now runs its input through the
  documented widening at every node, which is what lets the `Integer` special case go
  away.
- **A wide `Double` is written with a signed exponent** (`lg3d-mandela`, `rt/Json`) —
  the language's own number rendering keeps an exponent for a value too wide for
  plain digits, and Java spells that form as `1.0E16` while the JSON grammar requires
  a sign on the exponent. `json.encode(1e16)` therefore produced a document this
  reader accepts and a stricter one rejects; it now writes `1.0E+16`. All three are
  pinned by the new `JsonTest` (113 cases), which takes `rt/Json` from no suite at all
  to every line and branch of it covered.
- **`abs` of the most negative `Int` promotes instead of wrapping** (`lg3d-mandela`,
  `rt/StandardLibrary`) — `Math.abs(Long.MIN_VALUE)` is `Long.MIN_VALUE`, so
  `abs(0 - 9223372036854775807 - 1)` answered the same negative number it was
  handed, the exact roll-over the guide promises an `Int` never suffers. It now
  answers `Double`, the way the unary minus already did at that edge.
- **`padStart`/`padEnd` honour the text ceiling `repeat` already enforced**
  (`lg3d-mandela`, `rt/StandardLibrary`) — the ceiling was applied to the
  builder's *capacity*, not to the loop, so `"x".padStart(20000000, "0")` built
  the 20-million-character string instead of refusing it; a width read off a bad
  value was a heap eat, not a `LimitError`.
- **`int(...)` refuses a double with no `Int` instead of saturating**
  (`lg3d-mandela`, `rt/StandardLibrary`) — `longValue()` answers `MAX_VALUE` for
  any double past the range, so `int(math.pow(2, 63))` quietly returned a number
  that was not the one asked for. Outside `-2**63 … 2**63-1` is now a
  `LimitError`, the same shape the size ceilings answer.
- **A `Range` offers only the copying sorting spellings** (`lg3d-mandela`,
  `rt/StandardLibrary`) — the delegating table registered the mutating `sortBy`
  name but the copying `sortedBy` body, so `range.sortedBy(…)` was an unknown
  member while `range.sortBy(…)` "worked" by reordering a throwaway materialised
  copy and answering it — a sort that could never change the range. The
  delegation now spells `sortedBy`, `sortBy` is not a `Range` member (a `TypeError`
  like any unknown member), and the guide's table matches.
- **A call missing its second argument is told it needs two**
  (`lg3d-mandela`, `rt/StandardLibrary`) — the two argument helpers spelled the
  same sentence separately, and the numbered branch read "needs its 2th
  argument". The shared spelling says how many arguments the call needs.
- **A downward `Range` lost its last value** (`lg3d-mandela`, `values/MandelaRange`) —
  the count added the inclusive end's unit in the *bounds'* direction instead of the
  *stride's*, so every range that counts down was one value short: `8..0 step -1`
  walked 8 to 2, answered `7` for `.length` and `2` for `.last`, and printed eight
  numbers in a `for` loop while `0 in (8..0 step -1)` said `true` underneath it.
  `(5..1 step -1).sum()` answered `12` where the closed form the library uses says
  `15`. The count is now measured along the stride, so `.length`, `.last`,
  `toList()`, `for` and `in` are one answer in both directions — and the bounds are
  still never overflowed: a span a `long` cannot name answers `MAX_VALUE` as "more
  than a count" rather than a negative number. Pinned by `IterationTest`; the two
  `LanguageTest` expectations that had encoded the old rule, with a comment
  rationalising it as `ceil(7 / 2)`, are corrected with it.
- **`==` compared only the top level of a container** (`lg3d-mandela`,
  `values/MandelaList`, `MandelaMap`, `MandelaInstance`) — each element was asked
  through Java's `Object.equals`, so `[1] == [1.0]` was false while `1 == 1.0` is
  true, `json.parse("[1.0]") == [1]` was false for a document read off the wire,
  `1.0 in [1]` and `[1] in [[1.0]]` were false, `distinct()` kept what the language
  calls one value twice, and `P(1) == P(1.0)` disagreed with the record's own
  structural promise. There is now one equality, `Values.equal`, and every
  container delegates to it per element.
- **A container's hash disagreed with its equality** (`lg3d-mandela`, `values`), the
  other half of the same bug: `1` and `1.0` are equal but hashed apart, so a script
  that used a `List` as a set or a map key got a different bucket for the same
  value. `Values.hash` is the canonical hash for every value — a number hashes as a
  `double` whichever side it arrived on, a map ignores its insertion order, a `type`
  instance hashes its fields — and the three containers delegate to it.
- **Two `Double`s that are the same number were unequal** (`lg3d-mandela`,
  `values/Values`) — `-0.0 == 0.0` answered `false` while `-0.0 < 0.0` answered
  `true`, an equality and an ordering that cannot both be right, and a `NaN` was
  never told about at all. Numbers compare by value, so `-0.0` and `0.0` are one
  number, with the one documented exception that `NaN` equals itself: that is what
  lets `distinct()` answer `[1, 2, NaN]` for `[1, 2, NaN, NaN]` instead of keeping a
  value the script cannot tell apart.
- **A big `Int` comparison collapsed two distinct numbers** (`lg3d-mandela`,
  `values/Values`) — ordering converted both sides to `double`, so
  `9223372036854775807 > 9223372036854775806` answered `false` and `sorted()` could
  reorder a list `==` calls equal. Two `Int`s are compared with `Long.compare`, and
  only a real `Double` in the pair takes the floating path.
- **A `Double` was printed with placeholder zeros** (`lg3d-mandela`,
  `values/Values`) — `1e-6` displayed as `0.0000010`, breaking the promise
  `formatDouble`'s own javadoc makes (the shortest decimal that round-trips) and
  putting text on the console that a reader cannot paste back as the value it came
  from. The placeholder digits are stripped; a whole `Double` still keeps its `.0`.
- **`x as Any` returned the text of the value** (`lg3d-mandela`, `values/Values`) —
  the `Any` branch of the conversion table shared the `Str` path, so `42 as Any`
  handed back `"42"`: a conversion that silently changed the type the rest of the
  program tests with `is`. `Any` asks for no shape, so it is now the identity.

### Changed
- **The `lg3d-core` coverage floor is raised to `0.225` line / `0.310` branch**
  (`build.gradle`) — it sat at `0.010 / 0.020`, a floor written when the module had
  two small suites. The measured numbers are now `22.83% / 31.56%`, and
  `org.jdesktop.lg3d.displayserver.crash` alone is at `92.43%` line / `82.16%`
  branch; the rest of the gap is the 2006-era scene graph, which needs a live 3D
  desktop and is verified with the in-JVM probe instead (see `AGENTS.md`).
  A regression of the crash pipeline now fails `check` rather than quietly
  reopening the gap.
- **The language guide now says what the machine does** (`docs/mandela-language.md`) —
  three sentences were wrong, and each is the kind an author repeats without
  checking. *Truthiness*: the guide said only `false` and `null` are false, while the
  engine also calls `0`, `0.0` and `""` false and an empty `[]`, `{}` and `1..0` true.
  *Iteration*: the guide listed the pair spellings but never said that a single name
  binds the **value** — so a map loop hands out values, not keys — nor which six
  shapes may be walked, that `null` is the empty sequence, or that the loop reads the
  live container. *Operators*: the guide now states the `==` rules the engine applies
  (numbers across kinds, contents all the way down for a `List`, a `Map` and a `type`,
  identity for a `class` and an `enum` constant), the `as Any` identity conversion,
  and that a downward inclusive range reaches its end.
- **The `values` package is at every line covered** (`lg3d-mandela`) — `ValuesTest`,
  `IterationTest` (the `for` cursor and range counting) and `ValueTypesTest` (the
  Java surface a host and the machine share: what a `Callable` reports about itself,
  a class about its constructor, a wrapped host exception, and where a container
  says no) join the suite: **1142** headless cases, `89.3%` line coverage for the
  module, and `org.jdesktop.lg3d.mandela.values` at 809 of 809 lines.
- **The `lg3d-mandela` coverage floor is raised to `0.890` line / `0.780` branch**
  (`build.gradle`) — the ratchet was set at the measured `78.58% / 64.38%` when the
  Cucumber layer landed, and the campaign has since taken `values`, `rt/Json` and
  `rt/StandardLibrary` to every line covered. The floor follows the measurement
  (now `89.30% / 78.95%`), so a regression of the equality, hash or range-counting
  work above — or of any line this tranche covered — fails `check` instead of
  quietly reopening the gap.
- **The root `AGENTS.md` no longer claims that no coverage gate is enforced** — the
  *Test coverage* status said JaCoCo was report-only with nothing enforced; the
  root `build.gradle` has since grown a `coverageFloors` map (eight modules) whose
  `jacocoTestCoverageVerification` task `check` depends on, so a regression below a
  module's floor does fail the build. The section now separates what is enforced
  (the per-module floor, raised as coverage improves) from what is not (the 100 % /
  0-mutant target itself: PIT runs only for `lg3d-mandela`, on demand and
  report-only; Checkstyle runs with `ignoreFailures`; a module without tests has no
  floor). The `lg3d-mandela` test count in *Testing* is corrected with it.
- **Name-resolution tags in the compiler are an enum (`Compiler.Ref`), not `int`
  constants** — the load and store switches are now checked for totality by the
  compiler instead of ending in a guard no script could reach, and a field store
  lowers itself in `storeSlot` rather than needing a special case in `assign`.
- **`Operators.div` dropped the two conditions it could never need** — `requireNumbers`
  has already proved both operands are numbers, and `Values.isInt` *is* `instanceof
  Long`, so the `isNum(right) ? asLong(right) : 0L` fallback and the second
  `instanceof Long` test were branches no script could reach and no mutation could
  kill. The integer path now reads dividend / divisor / exact-or-double.
- **The language guide's `LimitError` entry covers the library's ceilings too**
  (`docs/mandela-language.md`) — it named only the step and depth budgets while
  the standard library had already been raising the kind for `repeat` and
  `toList` sizes; it now says a ceiling, wherever the library enforces one.
  `StandardLibraryTest` (270 cases) pins every bullet above and takes
  `rt/StandardLibrary` to every line of it covered.

### Removed
- **The value surface no receiver can reach** (`lg3d-mandela`, `values`) — a
  `MandelaIterator` was never asked whether it yields pairs or had started, and its
  `index()` / `source()` accessors have no caller in the machine or in a script; the
  cursor is now one field and one constructor argument. `MandelaClass.methods()`,
  `declaresField()` and `visibleMethodNames()`, `Ref.empty()` and the
  `MandelaInstance(owner, initial)` constructor are referenced nowhere in the
  repository — a host builds an instance and assigns its fields, which is what the
  one-argument constructor already does — and `MandelaRange.span()` went with the
  count rewrite above.
- **`Ast.BlockValue`, an expression node no syntax could produce** (`lg3d-mandela`) —
  no parser rule ever built it, so the compiler's lowering case, the tree walker's
  case and the node itself are gone; an expression-bodied function answers through
  `Ast.Stmt.ExprValue`, as it already did.
- **`StandardLibrary.needList`, a guard no script could reach** (`lg3d-mandela`) —
  a member lookup binds the list vocabulary only to a receiver already matched
  against `MandelaList`, and the `Range` spellings hand over a materialised copy,
  so the refusal branch was dead code and a mutant no test could kill; the call
  sites now cast, naming the invariant in the javadoc instead of checking it.

## [1.68.0] — 2026-10-11 — Gradle / JDK 21 modernization

### Fixed
- **Mermaid diagrams now render on the Pages documentation site** (`documentation/`) —
  the portal (`documentation/index.html`) linked its cards straight to the `.md` files,
  and GitHub Pages serves a `.md` as plain text, so "View Diagrams" showed the raw
  markdown with unfenced ` ```mermaid ` code instead of a picture. A new
  `documentation/viewer.html` fetches the markdown, renders it with `marked`, and draws
  the ` ```mermaid ` fences with `mermaid` (`securityLevel: loose`, so the diagrams' own
  `<br/>` node labels render); the eight portal cards now route through it. If the CDN
  is unreachable it falls back to the raw text, so a page is never blank. Browsing the
  `.md` on GitHub itself is unchanged, and no build/CI step was added — the viewer is a
  static file under `documentation/` that the existing deploy already copies.
- **The async test recorders no longer race their readers** (`lg3d-apps`) —
  `P2pProtocolTest`, `P2pNodeTest`, `P2pServerTest`, `P2pSideChannelTest` and
  `BuildToolsLiveTest` collected callbacks in a
  `Collections.synchronizedList` while the producing thread (the messenger's
  socket reader, the server accept loop, the EDT build reporter) kept adding
  and the polling assertions called `stream()` on it. A synchronized wrapper's
  spliterator acquires no mutex — its own javadoc says it "MUST be manually
  synchronized" — so the iteration raced a structural modification and the
  suites intermittently died with `ConcurrentModificationException`: seen on a
  PR run *and* on a `main` push run, green again on a re-run, which reads as
  infrastructure flakiness. The recorders now hold a `CopyOnWriteArrayList`,
  whose iterator works on a stable snapshot, so a red run means a regression;
  each recorder's javadoc states why the list type is load-bearing. The four
  suites that already snapshot under an explicit lock (`SecureChannelTest`,
  `FileTransferManagerTest`, `LanDiscoveryTest`, `IrcProtocolTest`) were
  audited and left alone. No production code is touched.
- **Espresso tabs are closable from the tab header** (`lg3d-apps`) — every
  editor tab now carries a small **×** button on its title strip, so a tab can
  be closed by click rather than only Ctrl+W / middle-click. The header is a
  plain `JLabel` + `JButton` (offscreen-safe for the 3D capture), keeps the
  dirty dot next to the title, and resolves the tab's index at click time so
  closing one tab never mis-closes its neighbour. The existing unsaved-changes
  confirmation is unchanged.
- **Espresso's project tree starts on the file system** (`lg3d-apps`) — before
  any document was opened the west `ProjectTreePanel` showed Swing's stock
  `JTree` demo model (`colors` / `sports` / `food`). The tree now boots with an
  empty model and re-roots on a real directory (`defaultStartRoot()` — the user
  home when readable, else the first file-system root), so the panel shows the
  actual file system from the first paint; opening a file still re-roots on its
  project as before.
- **Espresso shows its branded cup icon, not an "ES" initials tile**
  (`lg3d-core`) — the 2D desktop rendered the generic `espresso.png` cup
  artwork as a generated "ES" glass tile, because `AppIcons` only loads genuine
  per-app descriptor PNGs for resources in its preferred set and Espresso's name
  matches no semantic family. `resources/images/icon/espresso.png` joins the
  launcher rocket in that set, so the window frame, taskbar button and start
  menu all show the cup the 3D desktop already carries.
- **2D descriptor icons resolve from a jar / IDE launch** (`lg3d-core`) — the
  cup (and the launcher rocket) still showed initials when the desktop was
  started outside the Gradle `:run` task. Descriptors name artwork under the
  legacy `resources/` classpath prefix, a tree assembled only by
  `:lg3d-core:runtimeResources`; a packaged jar or an IDE module classpath
  carries the same PNG at the *unprefixed* root (`images/icon/…`) instead, so
  `Desktop2DStartMenu.loadIcon`'s prefixed `getResource` lookup missed and
  `AppIcons` degraded to a generated tile. `loadIcon` now falls back to the
  prefix-stripped path (`resolveResource`), so genuine per-app artwork renders
  in every launch topology.
- **Espresso extensions card filters actions by the selected extension**
  (`lg3d-apps`) — the right-hand "Extension Actions" list used to dump every
  command from every provider regardless of what was selected on the left.
  Selecting an extension in the Installed Extensions list now narrows the actions
  panel to just that extension's commands under its category header; clearing the
  selection restores the full grouped view. Each action row keeps its host flat
  index, so Run / double-click still invoke exactly the right action — the filter
  only hides rows, it never renumbers them.
- **JVM Build Tools actions now have keyboard accelerators** (`lg3d-apps`) —
  Compile/Run/Debug Java and Compile/Run Kotlin and Clean Output previously
  shipped with no shortcut because the plain `Ctrl+Alt+[A-Z0-9]` space is
  exhausted by the other twelve providers (only `Ctrl+Alt+0` was free). They now
  claim a distinct `Ctrl+Alt+Shift` family — Compile Java `+J`, Run Java `+R`,
  Debug Java `+D`, Compile Kotlin `+K`, Run Kotlin `+L`, Clean Output `+X` —
  collision-free and still user-rebindable like every other accelerator.
- **JVM Build Tools: a successful rebuild now refreshes the Output panel**
  (`lg3d-apps`) — every run, success included, writes a block to the south
  console (`javac output` / `kotlinc output` with an `OK — <class> compiled,
  no errors` body on green builds), so fixing an error and recompiling always
  visibly updates the editor. The stale `// ---- <tool> output ----` comment
  block left in documents by the pre-console builds is now migrated out
  automatically: the next build action (or **Clean Output**) strips the trailing
  block from the document. WRITE is therefore kept on the provider's manifest —
  used only for this one-time migration strip.
- **Eleven dead links in the live documentation** (`docs`, contracts) — reported
  by `scripts/mandela/doc-links.mnd` and each one confirmed against the tree
  before being touched, in four files. `lpm-lg3d-app-contract.md` (4): two links
  pointed at `lfs-x11-contract.md` as though it sat beside the contract, where it
  lives in `docs/`, and it referenced `docs/lpm.md` / `docs/LPM_DOCUMENTATION.md`,
  which do not exist — replaced by the one `lpm-console/README.md` that does.
  `system-management-contract.md` (2): the same stale contract path, twice.
  `lpm-console/README.md` (3): its References list carried `../lfs-x11-contract.md`
  instead of `../docs/…`, and two links into nonexistent `docs/` files became
  links to its own `#usage` and `#architecture` headings. `lg3d-incubator`'s
  `org/jdesktop/lg3d/utils/AGENTS.md` (2): written one directory too shallow, so
  its links to the UI/UX rulebook and the root `AGENTS.md` resolved to nothing.
  Anchors are now checked too, by the same script: a `#fragment` is verified
  against the headings its target really offers, not assumed.
- **The language guide contradicted the tested implementation**
  (`docs/mandela-language.md`, `docs/mandela-embedding-api.md`,
  `lg3d-mandela/AGENTS.md`) — found by writing scripts and running them, not by
  reading. `/` is **true division** and answers in the type the arithmetic
  deserves (`8 / 2` is the `Int` `4`, `7 / 2` is the `Double` `3.5`, exactly as
  `LanguageTest` pins it); the guide's operator table claimed integer division
  between integers, which is the kind of sentence that makes an author write
  `.floor()` twice. A **single-quoted string does not interpolate** — `'a ${x} b'`
  keeps `${x}` verbatim while `"a ${x} b"` substitutes — and the quote row said
  nothing about the difference. The CLI's exit codes are `0` ok / `1` usage /
  `2` program failed / `3` unreadable file / `4` blocking findings, and the two
  places that tabulated them stopped at `2`.

### Added
- **Screen saver for the 2D desktop** (`lg3d-core`, `lg3d-apps`) — an idle
  screen saver, configurable in **Control Center → Screen Saver**. The core
  (`displayserver.desktop2d.screensaver`) hosts a `ScreensaverService` armed at
  the end of `Desktop2D.show()`: a global `AWTEventListener` plus a one-second
  pointer poll timestamp user activity, and when the idle timeout expires it
  covers every screen with an always-on-top `JWindow` running the selected
  saver's `ScreenSaverView`; any input dismisses it. Engagement is postponed
  while a maximized window fills the screen so a presentation is never blanked
  mid-act, and a saver that throws while building its view degrades to the
  always-available `BlankSaver` failsafe rather than crashing the EDT. Savers
  are discovered through `ServiceLoader` behind a `ScreenSaver` SPI, so the
  desktop core depends on the contract only and a third-party jar can add more
  without an SDK. The Control Center panel edits the persisted
  `ScreensaverSettings` (enable, timeout, cycle-all, suppress-fullscreen, the
  saver picker and each saver's option schema rendered generically from
  `ScreenSaverOption`s — `JList` selectors, no combo boxes, offscreen-safe),
  shows a live preview and a timed **Test** on the 2D desktop, and still saves
  for the next start when no service is running (headless or the 3D shell). The
  whole service is a no-op on a headless JVM, so tests and CI are unaffected.
- **Documentation site (bilingual prose) on GitHub Pages** (`documentation/`,
  `.github/workflows/deploy-docs.yml`) — the architecture, sequence and class
  diagrams (Mermaid) and the functional analysis ship in English and French under
  `documentation/{en,fr}/`, published as a static Pages site (`.nojekyll`). The
  Javadoc API reference is **not** published: it is generated on demand with
  `./gradlew documentationSite` into `documentation/en/api/`, which is now git-ignored,
  so the ~4k generated files never enter the repository or the site — the Pages
  workflow *fails* if an `api/` tree is ever committed. The API reference is generated
  from English sources and exists exactly once; the prose is bilingual, the Javadoc is
  not duplicated per language.
  Two build fixes came with it: `:lg3d-escher:javadoc` no longer aborts on the
  Big5-encoded comment in `gnu/x11/test/Chinese.java` (its Javadoc now reads sources
  in ISO-8859-1, exactly like its compiler does), and the broken `{@link}` targets in
  the Espresso extension SPI (`TextEditorPermission` and friends) now resolve — they
  pointed at `EditorContext` members that live on `DocumentContext`, and at
  `TextEditorExtension`, which sits in the parent package.
- **Desktop 2D documentation** (`documentation/`) — the conventional-Swing
  fallback desktop now has its own functional analysis and Mermaid diagram set,
  in English and French: `documentation/{en,fr}/diagrams/desktop-2d.md`
  (component architecture, the `Desktop2D.openApp` launch flow, the class model,
  taskbar layout, `WindowSnap` edge-tiling zones, the `WorkspaceModel` state
  machine and the transparency pipeline) and
  `documentation/{en,fr}/functional-analysis/desktop-2d.md` (the `PANEL` /
  `SWING_FRAME` / `EXTERNAL` / `UNAVAILABLE` classification, the start menu,
  taskbar chrome, window management, workspaces, notifications, transparency,
  theming and session restore), all grounded in
  `org.jdesktop.lg3d.displayserver.desktop2d`. Four new portal cards route the
  docs through `viewer.html`, so their diagrams render rather than showing code.
- **`html-links.mnd` — a fourth Mandela gate** (`scripts/mandela`,
  `lg3d-mandela/build.gradle`) — `doc-links.mnd` guards the markdown, but the
  Pages site's HTML was unguarded: a portal card is a `viewer.html?path=<file.md>`
  reference, and a typo there is a runtime "document not found" that nothing in the
  markdown pass sees. The new gate resolves every `href`/`src` in
  `documentation/**/*.html`, checks each viewer `path=` names a real document, and
  verifies every Mermaid fence in that tree is balanced and opens with a real
  diagram keyword — the two failure modes that rendered a diagram as raw code or
  blanked it. Wired into `repositoryChecks` (so CI runs it with no workflow edit),
  and proven against planted dead links, a missing `src`, a non-diagram fence and
  an unclosed fence.
- **Desktop 2D surface transparency** (`lg3d-core`, `lg3d-widgets`, `lg3d-apps`) —
  the conventional desktop's four surfaces each got their own opacity, set in
  **Control Center → Customization → Transparency (2D desktop)**: one slider per
  surface for **windows**, **taskbar**, **widgets** and **notifications**, from
  25% (maximally faded, still readable — the floor is deliberate, so a desktop
  cannot be faded into unusability) to 100% (fully solid, the default and
  pixel-identical to the old look). They are independent: translucent windows do
  not fade the bar. Windows and widget cards fade **whole** — a window hosts
  third-party Swing panels that fill themselves opaque, so dimming only the chrome
  would show no wallpaper at all — while the taskbar and a toast fade only their
  **backdrop** and keep their buttons, titles and text fully opaque. The shell,
  not the component, does the compositing: `DesktopTransparency` renders a
  `TranslucentSurface` child into a scratch image and blits it at the configured
  `AlphaComposite` from the desktop pane, which is the only party that knows what
  lies behind it, and paints the bar's own translucent band from the wallpaper
  strip that would sit under it. Because the fade lives in the 2D shell, the same
  widget card stays solid through a `SwingNode` on the 3D desktop, where an opaque
  background is what makes the texture capture reliable. Opacities persist as
  `window/taskbar/widget/notification.opacity` preferences, are read into a paint
  cache (never from `java.util.prefs` during a paint), and apply live on
  `Desktop2D.applyDesktopConfig()` — no restart.
- **Espresso: Gradle and Maven build extensions** (`lg3d-apps`) — two new
  bundled providers, **Gradle Build** (`lg3d.gradle-build`) and **Maven Build**
  (`lg3d.maven-build`), a new **Build** category, take Espresso from compiling
  the *file* (`JvmBuildTools`) to building the *project* the document lives in.
  Each contributes six toolbar actions — Gradle **Build / Run Tests / Run
  Application / Clean / List Tasks / Outline Build File** and Maven **Compile /
  Run Tests / Package / Run Application / Clean / Outline POM** — on the two
  remaining halves of the `Ctrl+Alt+Shift` family (`G T U C S` / `E I P W Q`,
  disjoint from JVM Build Tools' `J R D K L X` and Mandela's `M A B`; the two
  outline actions stay unbound). Discovery walks up from the document to a
  `settings.gradle[.kts]` / `build.gradle[.kts]` or a `pom.xml` (deepest match
  wins, the same 10-level limit the west project tree uses) and runs the
  project's **own wrapper** (`./gradlew`, `./mvnw`), falling back to
  `gradle`/`mvn` on `PATH`; a missing project or launcher is a status-line
  answer that spawns no process. The build runs on a virtual thread with plain
  console output (`--console=plain`, `-B --no-transfer-progress`, `TERM=dumb`,
  `JAVA_HOME` only when the parent has none), lands its full log in the Output
  panel, and is then **interpreted**: compiler and test failures become
  `Diagnostic`s filed against the file that owns them (Maven's
  `[ERROR] file:[line,col]`, Gradle's `file.java:line:`, surefire's per-test
  lines — deduped because both tools print every error twice, capped at 100),
  while verdict, timing, the failing goal's headline and the error/warning
  counts compress into one ≤220-char status line carrying the HTML test-report
  path when one was written. Because a build reports on files other than the
  focused tab, the panel now **fans a multi-path report out per open tab**,
  un-marks the tabs the next build no longer mentions, and opens a closed file
  *with* its markers when its Problems row is activated — a single document's
  own analysis keeps the previous focused-tab behaviour, and a green build
  clears the stale squiggles. Maven's **Run Application** is deliberately
  `java -cp target/classes <mainClass>` rather than `exec:java`, so it works
  offline (stated in the tooltip, not hidden in a failure), and both outline
  actions parse the *live document text*, so unsaved edits and half-typed files
  outline honestly. 63 new headless JUnit 5 tests — `BuildToolsTest` (24, the
  pure root/launcher/argv/environment and log-interpretation rules over verbatim
  Gradle 8.14 and Maven 3.9.11 output), `GradleBuildToolsTest` (18) and
  `MavenBuildToolsTest` (20) driving every action through a synchronous `Runner`
  seam so no test spawns a build, plus the panel fan-out test — bring the
  text-editor surface to **45 suites / 480 deterministic tests** and the editor
  to **89 toolbar actions across 23 extensions**. Four more in
  `BuildToolsLiveTest` run the real `./gradlew` and `mvn` end-to-end and are
  opt-in (`-Dlg3d.build.integration=true`, skipped by default and in CI). The
  parsers were cut against those live runs, which is how the Gradle outline was
  fixed to prefer `build.gradle` over the `settings.gradle` that wins the root
  lookup.
- **Espresso: Java/Kotlin IDE groundwork (Phase 0)** (`lg3d-apps`) — the editor's
  south split now hosts a four-tab **bottom panel** — **Output** (the existing
  console), **Problems** (`ProblemsPanel`, a sortable `Diagnostic` table),
  **Structure** (`StructurePanel`, a `JTree` outline) and **Debug** (`DebugPanel`,
  breakpoints + call stack + locals + transcript) — with the `EditorTab` gutter
  painting per-line diagnostic dots, wavy underlines and breakpoint rings. The
  extension SPI grew additively (third-party source compatibility preserved via
  delegating constructors and a new internal `EditorSinks` carrier): two
  permissions (**`DIAGNOSE`**, **`DEBUG`**), two value records (**`Diagnostic`**,
  **`StructureSymbol`**), a debounced **`onDocumentChanged`** hook, caret/selection
  accessors on `DocumentContext`, and `DIAGNOSE`/`DEBUG`-gated diagnostics,
  structure and debugger capabilities on `EditorContext`, all wired to the live
  chrome through `ExtensionBroker`'s shared permission-gated dispatch. This lays
  the SPI + chrome foundation the following IDE phases (live javac/kotlinc
  diagnostics, structure/navigation/refactoring, a `com.sun.jdi` debugger and
  dependency-free completion) build on; no behaviour of the existing editor
  changed. 26 new headless JUnit 5 tests across `IdeChromeTest` and
  `ExtensionBrokerDispatchTest` (28 text-editor suites, 260 tests).
- **Espresso: live Java/Kotlin diagnostics (Phase 1)** (`lg3d-apps`) — a
  fourteenth bundled provider, **Java Diagnostics** (`lg3d.java-diagnostics`,
  category `Java/Kotlin`, READ + DIAGNOSE, no toolbar actions), feeds the Phase-0
  Problems panel, gutter dots and squiggles from the real compiler. It analyses
  the document's *current* text on open, on save and on the debounced
  `onDocumentChanged`: **Java** is compiled in memory with
  `ToolProvider.getSystemJavaCompiler()` + a `DiagnosticCollector` (nothing is
  ever written to the user's file; class output drops into a throwaway temp
  dir), and **Kotlin** shells out to `kotlinc` from `PATH` and parses both its
  classic `file.kt:line:col: severity: msg` and newer `e:/w: file.kt: (line,
  col): msg` output. Analysis runs on a virtual thread and is delivered on the
  EDT; a generation counter drops superseded results so typing never blocks and
  stale markers never paint; a non-JVM or empty document clears stale
  diagnostics. No new dependency and no popup — the compiler output lands in
  the already-wired DIAGNOSE-gated context path. The `kotlinc` lookup, process
  runner and scheduler are injectable seams, so the 12 new `JavaDiagnosticsToolsTest`
  tests are headless (no test spawns a real `kotlinc`; the in-memory `javac` does
  run headless). Text-editor surface now **29 suites / 272 tests**.
- **Espresso: structure, navigation and single-file refactoring (Phase 2)**
  (`lg3d-apps`) — a fifteenth bundled provider, **Java Structure**
  (`lg3d.java-structure`, category `Java/Kotlin`, READ + WRITE + DIAGNOSE +
  TOOLBAR), fills the Phase-0 Structure tab and adds four toolbar actions:
  **Go to Definition**, **Find Occurrences**, **Rename Symbol** and **Organize
  Imports**. The outline comes from the JDK's own compiler frontend
  (`JavacTask.parse()` + `Trees`/`SourcePositions` — exact lines, no string
  guessing) with a regex fallback for broken sources, and a documented
  best-effort regex/indent scan for Kotlin (no Kotlin AST exists in the JDK).
  The caret-word resolution and occurrence scan mask out string/char literals
  and comments, so a rename never rewrites a literal. Rename applies every
  whole-word occurrence in a single `setFullText` write (one undo step) and
  takes the new name behind an injectable prompt seam — headless tests never
  construct a dialog. Organize-imports drops unused single-type imports,
  dedupes and sorts the block (statics first, wildcards kept), leaving
  import-free files untouched. The SPI grew one additive capability, the
  `READ`-gated `EditorContext.navigateTo(path, line)`, delegated to the panel
  which selects the owning tab and jumps the caret — the same jump the
  Problems and Structure tabs' double-click already use. 16 new headless
  `JavaStructureToolsTest` tests cover the AST outline lines, the fallbacks,
  the masked occurrence scan and all four actions driven through capturing
  `EditorSinks` with the permission gates. Text-editor surface now
  **30 suites / 288 tests**.
- **Espresso: a real JVM debugger (Phase 3)** (`lg3d-apps`) — a sixteenth
  bundled provider, **Java Debugger** (`lg3d.java-debug`, category
  `Java/Kotlin`, READ + FILE_IO + DEBUG + TOOLBAR), adds six toolbar actions
  to the Phase-0 Debug tab: **Start Debugging**, **Resume**, **Step Over**,
  **Step Into**, **Step Out** and **Stop Debugging**. The line-number gutter
  becomes click-to-toggle breakpoints and a `DEBUG`-gated read-back
  (`EditorContext.getBreakpoints(path)`) lets the extension see them. The
  debugger compiles the document's *current* text with the JDK's own `javac`,
  launches it as a **child** JVM with the JDWP agent bound to **loopback only**
  (`transport=dt_socket,server=y,suspend=y,address=127.0.0.1:<ephemeral>`),
  attaches with `com.sun.jdi`, installs breakpoint requests on the gutter
  lines at class-prepare, and paints the call stack and locals
  (`visibleVariables()`) at every suspension while the program's stdout
  streams into the Output tab. Every `com.sun.jdi` call lives behind the
  `Backend`/`Session` seams in `JdiDebugBackend`, and every session end
  detaches and `destroyForcibly()`s the child — no debuggee can outlive the
  session. Kotlin debugging is refused politely (no supported line-table
  story without the compiler daemon). 16 new headless `JavaDebugToolsTest`
  tests drive the whole compile → launch → suspend → step → stop state
  machine through fake seams with inline delivery — no test spawns a JVM —
  and the real attach path is covered by one opt-in integration test
  (`-Dlg3d.debug.integration=true`). Text-editor surface now
  **31 suites / 304 tests**.
- **Espresso: dependency-free code completion (Phase 4)** (`lg3d-apps`) — a
  seventeenth bundled provider, **Java Completion** (`lg3d.java-completion`,
  category `Java/Kotlin`, READ + TOOLBAR), adds one toolbar action **List
  Completions** and a fifth bottom strip, **Completions** (a plain `JList` —
  no popups, so the editor stays offscreen-safe when captured). Candidates
  come from three always-available sources: the current file's own outline
  symbols (the Phase-2 AST outline), the public members of imported (or
  `java.lang`) types via plain reflection — cached, and consulted only once
  the caret prefix carries two characters — and the Java keyword set; they
  are ranked starts-with-then-contains and capped at 50. Typing publishes
  through the new `READ`-gated `EditorContext.publishCompletions(path,
  list)` sink on every debounced document change; after `recv.` the
  receiver's declared type (capitalized receiver = static access, otherwise
  the last `Type recv` declaration) lists its members, and unresolved
  receivers or non-Java files complete to nothing — a deliberately *shallow*
  engine, deep javac-internal completion was rejected for reliability.
  Accepting a row (Enter/Tab in the strip, or double-click) replaces the
  identifier run around the caret in a single undo step
  (`EditorTab.insertCompletion`) — the editor's own Tab and Enter are never
  hijacked. 15 new headless `JavaCompletionToolsTest` tests cover the pure
  engine (prefix, receiver, import/`java.lang` resolution, real reflection,
  ranking cap) and the publish path through the READ gate. Text-editor
  surface now **32 suites / 319 tests**.
- **Espresso: project menu and Git GUI integration (Phase 5)** (`lg3d-apps`,
  `lg3d-art`) — a seventh toolbar button **Project…** opens the new
  `ProjectCard`, Espresso's in-panel project menu (no popups, offscreen-safe):
  *New Project…* scaffolds `src/Main.java` + `README.md` (validated name, a
  non-empty directory is never clobbered), *Open Project Folder…* and a
  persisted recent-projects list (`EditorSettings.pushProject`, capped at 10)
  attach the west `ProjectTreePanel` root, *Re-root to Current File* and
  *Close Project* keep the tree honest. *Git GUI…* integrates the desktop Git
  client directly: the very `gitgui.GitGuiPanel` is embedded as its own card
  and opened on the current project root — stage/commit/branch/history without
  leaving the editor. The start-menu icon becomes the branded **espresso cup**
  (`espresso.png`, drawn in-tool by `GenerateAppIcons` on a brown tile; the
  legacy `text-editor.png` stays generated for other consumers). 8 new
  headless tests (`ProjectCardTest` routing + panel project/Git paths).
  Text-editor surface now **33 suites / 327 tests**.
- **Espresso: configurable toolbar, inline completion and new tools (Phase 6)**
  (`lg3d-apps`) — three additive capabilities, all third-party-source-compatible.
  The `ToolbarContribution` SPI grew an optional `javax.swing.Icon` (new
  delegating constructors + `getIcon()`, so no existing provider breaks), fed by a
  tiny in-tree `EditorGlyphs` vector-glyph factory so toolbar buttons always have
  art even where `IconManager` is absent (the test classpath). A **user-configured,
  persisted toolbar**: `ToolbarButtonConfig` (a plain, headless-testable ordered
  model of `id[:mode]` entries with `{ICON, TEXT, ICON_TEXT}` display modes) is
  stored in `EditorSettings` under the `toolbarButtons` key (a `'|'`-joined list,
  round-tripped like `recentFiles`), rendered on its **own second toolbar row**
  (`Commands:` + the chosen buttons + `Customize…` pinned right — the built-ins
  alone already overflow the panel's 960px width, and a `JToolBar` clips
  everything past its right edge, so buttons appended to that row would never be
  visible or clickable; the strip's `FlowLayout` wraps instead), with each
  command's accelerator bound, and edited through a new **Customize
  Toolbar…** card (`ToolbarCustomizeCard` — a `JList`/`JButton` two-pane
  add/remove/reorder/mode editor, offscreen-safe, committing only on Apply; stale
  ids from a disabled extension are dropped at render, never on load).
  **Completion now paints inline at the caret** as well as in the bottom strip:
  `publishCompletions` feeds both surfaces, the new `CompletionPopup` is layered
  inside the editor `JScrollPane`'s `JLayeredPane` (never a `JWindow`/`JPopupMenu`,
  so it composites into the SwingNode capture), positioned from
  `modelToView2D(offset)` and clamped to the viewport, driven by the pure
  `CompletionTrigger` show/hide decision and Up/Down/Enter/Tab/Esc/Ctrl+Space keys;
  accept reuses the strip's `EditorTab.insertCompletion`. Three new bundled
  providers exercise both: **Insert & Snippets** (`lg3d.snippets`, category Insert,
  READ+WRITE+TOOLBAR — UUID/timestamp/date/lorem/hex-colour buttons that insert at
  the caret and publish `${snippet}` tokens as inline completions), **TODO & Task
  scan** (`lg3d.todo`, category Project, READ+DIAGNOSE+TOOLBAR — scans
  TODO/FIXME/NOTE/HACK into info diagnostics with a jump-to-next) and **Comment
  Toggle** (`lg3d.comment`, category Text, READ+WRITE+TOOLBAR — a language-aware
  add/remove/toggle line-comment action via the `Languages` catalogue; the plan's
  fold/unfold half is intentionally dropped as a `JTextPane` has no folding model).
  New and updated headless JUnit 5 suites — `ToolbarContributionTest`,
  `ToolbarButtonConfigTest`, `CompletionTriggerTest`, `ToolbarCustomizeCardTest`,
  `SnippetsToolsTest`, `TodoScanToolsTest`, `CommentToolsTest`, an `EditorSettings`
  round-trip and panel glue proving both completion surfaces — bring the
  text-editor surface to **40 suites / 383 tests**.
- **The Advanced Text Editor is now Espresso** (`lg3d-apps`) — the editor's
  user-facing name: the Start Menu shows **Espresso** under Office, the 3D
  window title (and the 2D MDI frame title, which follows the menu item) is
  **Espresso**, and the descriptor tooltip reads "Espresso: edit text and
  source files…". Internal identifiers are unchanged on purpose
  (`org.jdesktop.lg3d.apps.texteditor.AdvancedTextEditor[Panel]`, the
  `texteditor.lgcfg` descriptor and the `text-editor.png` icon), so file
  associations, launcher commands and existing preferences keep working.
- **Advanced Text Editor: west project tree and south output console**
  (`lg3d-apps`) — the editor window is now two `JSplitPane`s around the tabs:
  a south **`OutputConsole`** (read-only monospaced transcript, `---- title ----`
  block grammar, drop-oldest cap at 2 000 lines, `Clear` button) where extension
  tool actions land their output — the document is never polluted again — and a
  west **`ProjectTreePanel`**, a lazily-loaded file tree re-rooted on the
  *project* of the document being edited (walk-up to `.git`/`pom.xml`/
  `build.gradle`/`package.json`/… markers, 10 levels deep, directories before
  files, hidden entries skipped); double-clicking a file opens it in a tab.
  `EditorContext` gains two FILE_IO-gated capabilities, `showOutput(title, body)`
  and `clearOutput()`, wired through the `ExtensionBroker`, so third-party
  extensions get the same console. The JVM Build Tools write every
  compile/run/debug output there instead of appending a
  `// ---- <tool> output ----` comment block, and its **Clean Output** action
  now clears the console. 26 headless suites / 235 tests in the texteditor
  package.
- **Advanced Text Editor: compile, run and debug Java/Kotlin from the editor**
  (`lg3d-apps`) — a thirteenth bundled provider, **JVM Build Tools**
  (`lg3d.jvm-build-tools`, category `Java/Kotlin`), takes the extension surface
  beyond text rewriting into the real toolchain with six toolbar actions (each on
  a `Ctrl+Alt+Shift` accelerator, see the fix above). **Compile Java**
  stages the document's *current* text into a fresh temp directory (the user's
  file is never touched, unsaved drafts work) and runs the running JDK's own
  `javac`; success and failure both land a block in the output console (see
  the entry above). **Run Java** uses the JDK 11+
  single-file source launcher and captures stdout+stderr into the console.
  **Debug Java** is an honest crash-analysis run: assertions enabled (`-ea`),
  the output scanned for the exception heading and the first stack frame in
  this file, reported as `Debug: java.lang.IllegalStateException: boom at
  Foo.java:42` with the full trace kept in the console (interactive breakpoint debugging
  is deliberately out of scope here). **Compile Kotlin** / **Run Kotlin** drive
  `kotlinc` + the `kotlin` launcher from `PATH` and degrade to a clear status
  message — spawning nothing — when no Kotlin install is found; **Clean
  Output** clears the console. Processes run on a virtual thread and results are
  delivered on the EDT, so the editor never freezes, and a package-private
  `Runner` seam keeps every action headless-testable with a synchronous fake
  (`Toolchain` itself is pure command builders, class-name derivation,
  `Foo.kt`→`FooKt` mapping and stack/exception parsing). The
  panel now also refreshes the extensions' document snapshot immediately before
  each action dispatch, so *every* action — not just the build ones — operates
  on the live text rather than the file-as-opened. New `JvmBuildToolsTest` (18
  tests) plus panel count/live-text assertions bring the text-editor surface to
  **52 actions across 13 providers**; the whole module build stays green.
- **Advanced Text Editor: Spring Boot / JSON / Hash extensions, user-editable
  shortcuts and a permission grant UI** (`lg3d-apps`) — three purely additive
  follow-ups on the extension surface, all in one train. **Three more bundled
  providers** join the nine: **Spring Boot Tools** (`lg3d.spring-boot-tools`,
  category `Spring`) converts `application.properties`↔`application.yml` —
  `propertiesToYaml` builds a nested `LinkedHashMap` tree and renders it with
  two-space indent (comments/blank lines kept as a header), `yamlToProperties`
  walks an indent stack and indexes block sequences as `key[n]` (Spring's relaxed
  binding form) — plus two Spring-config workflow helpers on the same provider:
  `normalizeKeys` rewrites each property key to canonical kebab-case (folding
  camelCase humps like `contextPath`, acronym humps like `HTTPServer`, and
  `_`/uppercase like `SERVER_PORT`, values and comments preserved) and
  `listPlaceholders` scans every value for `${...}` tokens and appends a
  `# Referenced placeholders:` summary block (deduped, first-occurrence order,
  `:default` suffixes stripped, nested `${a-${b}}` handled with a depth counter,
  and idempotent — the marker comment short-circuits a second run); **JSON Tools**
  (`lg3d.json-tools`, category `Data`) minifies and
  pretty-prints (4-space indent) with a scanner that honours string literals and
  backslash escapes, so it never corrupts quoted content and needs no JSON parse;
  **Hash Tools** (`lg3d.hash-tools`, category `Encoding`) emits MD5 / SHA-1 /
  SHA-256 hex of the selection via `MessageDigest` (UTF-8). The curated order
  gains Spring and Data, and the toolbar now carries **46 actions across twelve
  providers**. **User-editable shortcuts**: `EditorSettings` gains an
  `acceleratorOverrides` map (contribution id → `KeyStroke` spec; a blank value
  deliberately unbinds, an absent key keeps the declared default) round-tripped
  through `toMap`/`fromMap` and persisted with the other settings; the panel
  binds and shows the *effective* accelerator (override wins) and exposes
  `Host.getAccelerator` / `setAccelerator(index, spec)`, and the `ExtensionsCard`
  grows a shortcut bar (current shortcut + **Rebind** + **Clear**) where Rebind
  arms a one-shot key grab on the actions list (only A–Z / 0–9 with
  Ctrl/Alt/Shift/Meta are assignable). **Extension permission grant UI**: the
  card's management column now lists each selected extension's declared
  `TextEditorPermission`s as checkboxes, fed by `Host.permissionsFor(id)` and
  driven through `Host.setPermission(id, name, boolean)` into the existing
  `ExtensionRegistry.grant` (which persists); toggling TOOLBAR immediately adds
  or drops that extension's actions, and `show()` preserves the selection across
  a rebuild. All new `Host` methods are default no-ops, so third-party hosts stay
  source-compatible, and nothing existing is scrapped. Covered by three new
  headless suites (`SpringBootToolsTest`, `JsonToolsTest`, `HashToolsTest`) plus
  accelerator-rebind / override-persistence / permission-grant panel tests and
  card rebind / permission wiring tests; `AdvancedTextEditorPanelTest` now
  isolates the extension store via the `lg3d.texteditor.dir` property so
  enable/permission writes never touch `~/.lg3d`. The whole module build stays
  green.
- **Advanced Text Editor: extension accelerators, an enable/disable manager and
  two more dev extensions** (`lg3d-apps`) — purely additive follow-up to the
  category grouping below. A `ToolbarContribution` can now carry an optional
  `accelerator` (a `KeyStroke` spec such as `"control alt A"`); the panel binds
  every declared accelerator on the editor's own input map, so an action runs
  with a quick keystroke straight from the text area without flipping to the
  Extensions card (the chosen `Ctrl+Alt+<key>` range never collides with the
  editor's `Ctrl+…` bindings; the first action to claim a KeyStroke wins and
  later duplicates are skipped). Bundled actions gain mnemonic defaults across
  every category. The `ExtensionsCard` shows the accelerator next to each action
  and grows a left-hand list of installed extensions with **Enable/Disable**
  buttons — a new `Host.extensionInfos()` / `setExtensionEnabled(id, boolean)`
  pair (default methods, so third-party hosts stay source-compatible); toggling
  re-scans the enabled set, rebuilds the flat action list and re-binds the
  accelerators, so `runExtensionAction(0)` stays "Sort Lines (A-Z)". Two more
  bundled providers join the seven: **Base64 Tools** (`lg3d.base64-tools`,
  category `Encoding`) encodes/decodes the selection as Base64 (malformed input
  is returned unchanged, never thrown); **Markdown Tools** (`lg3d.markdown-tools`,
  category `Markdown`) toggles `**bold**`, `*italic*`, `` `inline code` `` and
  `- ` bullet lists on the selection, each an idempotent two-way switch. The
  curated category order gains Encoding and Markdown, and the toolbar now
  carries **37 actions across nine providers**. Covered by two new headless
  suites (`Base64ToolsTest`, `MarkdownToolsTest`) plus panel accelerator /
  `extensionInfos` tests and a card Enable/Disable test; the whole module build
  stays green.
- **Advanced Text Editor: extensions grouped by category + Java/Kotlin & Web
  dev extensions** (`lg3d-apps`) — the extension manager (`ExtensionsCard`) now
  files every contributed action under a bold, inert category header instead of
  a flat list: each `TextEditorExtension` declares a free-form `category()`
  (a new SPI default, `"General"`), the `ExtensionBroker` pairs it with each
  contribution, and the card groups them in a curated order (Text, Code,
  Java/Kotlin, Web, Analysis, General). Grouping reorders the display only, so
  running an action by index is unchanged. Three more bundled, purely additive
  providers join the existing four: **Java/Kotlin Tools** (`lg3d.java-tools`,
  category `Java/Kotlin`) sorts import statements in place, escapes/unescapes
  Java/Kotlin string-literal control characters and quotes, and toggles `//`
  line comments on a selection; **Web Tools** (`lg3d.web-tools`, category `Web`)
  escapes/unescapes HTML/XML entities (single-pass, so `&amp;lt;` never
  double-decodes) and percent-encodes/decodes URLs (malformed escapes are left
  as-is, never thrown); **Formatting Tools** (`lg3d.format-tools`, category
  `Code`) converts tabs↔spaces and normalises line endings to LF or CRLF. Every
  new transform is a pure `String` static covered by three more headless JUnit 5
  suites (`JavaDevToolsTest`, `WebToolsTest`, `FormatToolsTest`) plus a
  grouped-card test; the existing extensions gained categories, grouping them
  across the bundled providers. All additive — no panel, broker
  or existing extension behaviour is scrapped.
- **Advanced Text Editor: three new bundled extensions** (`lg3d-apps`) — the
  editor's `TextEditorExtension` plug-in surface (discovered via `ServiceLoader`
  and `META-INF/services`) ships three more built-in providers beside the
  existing "Text Tools", all additive — no panel, broker or existing extension
  is changed. **Code Tools** (`lg3d.code-tools`, READ/WRITE/TOOLBAR) adds six
  whole-document line operations: Indent Lines, Outdent Lines, Remove Blank
  Lines, Remove Duplicate Lines, Reverse Lines and Number Lines. **Case Tools**
  (`lg3d.case-tools`, READ/WRITE/TOOLBAR) adds five selection-only conversions —
  Title, Sentence, camelCase, snake_case and kebab-case — that no-op on an empty
  selection and never dirty an unchanged document. **Document Stats**
  (`lg3d.text-stats`, READ/TOOLBAR, deliberately write-less) reports
  `N lines, M words, K characters` to the status line on demand and echoes a
  summary on save, exercising the `onEditorStarted`/`onDocumentSaved` hooks.
  Every transform is a pure `String` static covered by three new headless JUnit 5
  suites (`CodeToolsTest`, `CaseToolsTest`, `TextStatsToolTest`).
- **Apps now raise desktop notifications** (`lg3d-core`, `lg3d-apps`,
  `lg3d-incubator`) — the notification pipeline that already served the 2D
  desktop internally (toast layer, taskbar tray log, Do-Not-Disturb gate) and the
  3D HUD (`NotificationService`) gains one public, shell-agnostic entry point:
  `NotificationService.notify(title, message, kind)` routes to the running
  desktop (`Desktop2D.postNotification`, now public, on the 2D shell, the HUD
  service otherwise), so any in-JVM app can toast where the user will see it.
  **Mail** wires it up on both surfaces: a new `NewMailNotifier` state machine
  keeps a seen-set of inbox message keys — the first listing only takes a
  baseline, afterwards each batch of unseen *unread* inbox arrivals raises one
  "New Mail" toast naming the newest sender and subject — and the periodic
  auto-check (`MailSettings` interval, previously only restartable from the
  settings dialog) now starts when the panel opens, so "you got mail" arrives
  while Mail sits in the background; a new *Notify on new mail* toggle in the
  Behaviour tab gates it (on by default). **Instant Messenger** raises a
  "Messenger — <conversation>" toast for every incoming chat line from another
  peer (self-echoes and presence traffic never toast) and a toast on inbound
  file offers, beside the existing status line, honouring its *Notify on new
  message* setting and the desktop's Do-Not-Disturb. Covered by new headless
  suites (`NewMailNotifierTest`, Mail panel reload/poll tests, messenger
  toast-gating tests) and the facade routing test in `lg3d-core`.
- **Looking Glass wordmark wallpaper** (`lg3d-art`) — the logo wallpaper's
  sibling: `resources/images/background/LookingGlass-Wordmark.jpg` (2560x1440),
  the same slate-blue glass backdrop and the same `PROJECT LOOKING GLASS` caption
  in the same place, with the mascot left out for desktops where the mark would
  compete with the icons. It is deliberately not a second image recipe — it is
  [`lg3d-art/tools/make_logo_wallpaper.py`](lg3d-art/tools/make_logo_wallpaper.py)
  run with the new `--no-mark`, which drops the mark, its blurred ghost and its
  drop shadow as one decision rather than three, so the pair stays in step
  whenever the palette or the light bands are retuned. The existing
  `LookingGlass-Logo.jpg` is untouched, byte for byte. No registration is needed
  for either file; the wallpaper choosers enumerate the directory.
- **Looking Glass logo wallpaper** (`lg3d-art`) — a new brand wallpaper,
  `resources/images/background/LookingGlass-Logo.jpg` (2560x1440), built from the
  desktop's own brand icon — `resources/images/icon/lg3d-logo.png`, the
  Looking-Glass mascot the start menu, taskbar, 2D splash and About window all
  reflect — by the new
  [`lg3d-art/tools/make_logo_wallpaper.py`](lg3d-art/tools/make_logo_wallpaper.py).
  That icon ships at 64x64 and has no larger original anywhere in the tree, so the
  upscale is the whole problem: the mark is resampled in *premultiplied* space (the
  black RGB hiding under a transparent pixel would otherwise fringe the
  silhouette), its alpha is re-cut with a melt-and-refreeze pass so the pixel
  staircase becomes a smooth outline, and a median + minimum filter pair stitches
  the mascot's one-pixel black stroke back into one continuous line. It sits on a
  generated slate-blue glass backdrop sampled from the brand palette (#666699 /
  #d32b30) with diagonal light bands, an oversized blurred ghost of the mark
  behind it and a letter-spaced "PROJECT LOOKING GLASS" wordmark — but no mirror
  reflection, since the mascot is drawn standing on its own glass platform.
  Keeping the recipe in code (the same reason
  `GenerateAppIcons.java` exists) means the wallpaper re-cuts at any resolution
  (`--width` / `--height`), re-tints from the two brand colours, picks its
  encoder from the output extension, and has `--reflection` / `--no-ghost` /
  `--no-wordmark` variants; JPEG is the default because the lossless PNG of the
  same dithered gradient costs 9x the payload of the rest of the collection.
  16:9 is deliberate: `SimpleImageBackground` and the 2D `WallpaperDesktopPane`
  both stretch the image to the screen, so a square source distorts the mark.
  No registration is needed — every wallpaper chooser enumerates the directory
  at runtime — and `:lg3d-core:runtimeResources` assembles the new file into the
  classpath tree. `.gitignore` gains `__pycache__/` / `*.pyc` for the Python
  asset tools.
- **Advanced Text Editor** (`lg3d-apps`, `lg3d-core`, `lg3d-art`) — a new
  production plain-text and source-code editor (`org.jdesktop.lg3d.apps.texteditor`)
  in the start-menu **Office** group, served by one Swing `AdvancedTextEditorPanel`
  that both desktops host: in 3D the `AdvancedTextEditor` wrapper shows it on a
  `SwingNode` inside a `Frame3D` via `TitledSwingWindow`, and in the 2D/Swing
  desktop `Desktop2DAppRegistry.PANEL_APPS` opens the identical panel as an MDI
  internal frame (with the reflective `openFile(File)` file-association hook). It
  offers multi-tab editing with dirty tracking and safe close, syntax highlighting
  for 16 languages plus Plain Text (a pure `SyntaxHighlighter` tokeniser/painter
  gated by a 400k-char cap), literal + regex find/replace with live match counts,
  replace-one/replace-all (single undo step) and go-to-line, a line-number gutter,
  smart indent, bracket matching, and an undo stack (`MergingUndoManager`) that
  folds a typing burst into one step. File IO is safe and atomic
  (`TextFileIO`: write-to-temp-then-move, a 10 MB open cap, BOM/charset sniffing,
  ISO-8859-1 fallback with a warning, and a binary reject), settings (font, light/
  dark theme, indent, wrap, highlight) and the recent-file list persist through
  `java.util.prefs`, and a `TextEditorExtension` SPI (discovered via `ServiceLoader`,
  bundled `BuiltinTextTools` = "Text Tools") exposes whole-document/selection
  actions. The AWT-free engine and every Swing surface are covered by 12 headless
  JUnit 5 suites (105 tests); the icon (`text-editor.png`) is generated by
  `GenerateAppIcons`.
- **Image Studio on the 2D desktop** (`lg3d-incubator`, `lg3d-core`) — the JAI
  image editor now ships a plain Swing `ImageStudioPanel` (category tabs +
  operation grid, armed parameter slider with the same continuous-edit/single-
  undo semantics, fit-to-view canvas, RGB histogram, `~/Pictures` filmstrip and
  Open/Save/Save As), so the shared start-menu descriptor hosts it as an MDI
  frame in the 2D/Swing desktop while the 3D desktop keeps building the native
  `Frame3D`. The operation catalog moved into a shared `OpCatalog` rendered by
  both surfaces, and its parameter formatter fixes a latent
  `IllegalFormatConversionException` that silently blanked the 3D slider's
  value label for every int-formatted op.
- **Security Center: live scan / definition-update progress** (`lg3d-apps`) — the
  Antivirus tab now shows a real progress bar and live monitoring instead of a
  static "Scanning…" label. Scans stream ClamAV's output line by line (a new
  incremental `AntivirusBackend.ScanOutputParser`, the streaming twin of
  `parseScanOutput`) through a non-buffering `execStreaming`, so the panel can
  pre-count the target's files (`countFiles`) and drive a **determinate** bar
  (`files scanned / total (%)`) plus a live status line (current file, threats
  found so far, elapsed time); the `clamscan` database-load phase
  (`Loading:`/`Compiling:` ratios) and any target whose file count is unknown
  degrade honestly to a pulsing bar. **Update Definitions** elevates `freshclam`
  through polkit (`pkexec` via `PrivilegedRunner`) - `/etc/freshclam.conf` and the
  virus database are root-owned, so an unprivileged update could never succeed -
  streams its output and tracks its download percentage; a dismissed
  authorization prompt is reported as a cancellation, and hosts without polkit
  fall back to a plain run. `ProcessRunner`/`PrivilegedRunner` (`lg3d-core`)
  gained an optional per-stdout-line sink so the bar stays live under `pkexec`.
  EDT repaints are throttled to ~10/s (a
  newly-found threat always pushes immediately). The scan now runs ClamAV without
  `--infected` so a line per file is available for progress; the findings list
  still shows only detections, and the pure parsers/command builders are
  headless-tested.
- **Encrypted peer-to-peer (P2P) transport for the Instant Messenger and Video
  Conference** (`lg3d-apps`) — a new reusable, dependency-free, pure-JDK encrypted
  P2P transport (`org.jdesktop.lg3d.apps.p2p`) lets both apps talk to a peer
  *directly*, with no server in the middle. The crypto mirrors the project's
  `VaultCrypto` precedent (pure statics, AWT-free, headless-testable, injectable
  randomness): **X25519** key agreement, **HKDF-SHA256**, a **Noise-XX**-style
  mutually-authenticated handshake that yields forward-secret send/receive keys,
  and **AES-256-GCM** framing with a per-direction counter nonce (never reused
  under a long-lived key). Identity is the SHA-256 **fingerprint** of a node's
  static X25519 public key, with **TOFU** pinning (`IdentityStore` / `TrustDecision`)
  that raises a loud MITM warning on a later mismatch; the private key is stored at
  rest under `~/.lg3d/p2p` with `0600` (like an SSH host key). A `P2pNode` runs a
  `ServerSocket` plus outbound client over length-prefixed `SecureFrame`s (max-frame
  guard, CONTROL JSON and DATA binary types), with `LanDiscovery` announcing and
  listening over UDP multicast (TTL expiry) and manual host:port for reachability.
  `FileTransfer` / `FileTransferManager` stream 64 KiB chunks with an incremental
  SHA-256 digest, offer/accept/reject/progress/cancel and an atomic temp→final
  rename hardened against path-traversal ("Zip-Slip"). The **Instant Messenger**
  gains a native `P2pProtocol` (id `p2p`, "P2P (Direct)") registered in
  `ProtocolRegistry.standard()` — so it appears in the Add-Account dialog with no
  panel change — plus a capability-driven `MessengerProtocol.Capability.FILE_TRANSFER`
  and `AccountConfig.peerFingerprint`; `MessengerPanel` adds a *Send File* action
  gated on the capability, an incoming-file accept prompt and progress row, a
  *Find Peers* LAN list and a fingerprint pin field. The **Video Conference** gains
  a **Direct (P2P)** tab (`P2pSideChannel`) carrying encrypted meeting **signaling,
  chat and file transfer** — the audio/video itself is unchanged and still runs
  through Jitsi Meet in the browser. Both panels are headless-safe (inert
  construction, guarded starts, no socket or thread until the user acts). The scope
  is honest: LAN discovery is LAN-scoped, internet peers need a reachable host or a
  forwarded port (no fake NAT hole-punching), and the mesh suits 1:1 and small
  groups. The transport core, `P2pProtocol`, `P2pSideChannel` and both panels are
  covered by headless unit and loopback end-to-end tests; the Help Center
  (*Internet and the Web*, *Security and Privacy*) and both per-app `AGENTS.md`
  guides document the feature.
- **Help Center: four new topics and a full content refresh** (`lg3d-apps`) — the
  built-in JavaHelp user guide (Help Center, in the *Utilities* group) grows from
  14 to 18 HTML topics to document the applications and Control Center panels added
  since it was last written. Four new deep-dive topics join the reference set:
  **Internet and the Web** (web-browser tabs and shortcuts, the eight search
  engines, the inline find bar, reader mode, the address-bar security indicator and
  site-info popup, bookmarks/history/downloads, extensions and the Private (Tor)
  enforcement, plus Mail, Instant Messenger, Video Conference, FTP, SSH and Remote
  Viewer), **Security and Privacy** (the Security Center grade, hardening advisor
  and activity log, Private (Tor) mode and its taskbar shield, the VPN client's
  auto-reconnect / kill switch / tunnel verification, Firewall, Password Manager and
  Backup), **Music, Photos and Video**, and **Office and Productivity**. The stale
  **Built-in Applications** index is rewritten as a categorised master table with a
  3D/2D availability column (~50 apps, derived from the authoritative
  `Desktop2DAppRegistry`); **Customizing the Desktop** now covers all 24 Control
  Center panels plus the Appearance (window glass / rounded corners / corner logo /
  wallpaper and slideshow / fonts) and Customization (themes / accent / icon packs)
  options; **The Start Menu** group table is corrected (adds *Education*, drops the
  unlinked Demos/Tests); and **About** carries the current version. `toc.xml`,
  `map.jhm`, `index.xml` and `HelpContentTest`'s guarded target/resource arrays are
  wired for the new topics; the full-text `JavaSearch` index regenerates at build
  time (the indexer auto-discovers every `*.html`), so search needs no manual step.
- **3D desktop: a choice of corner-logo model** (`lg3d-core`, `lg3d-apps`) — the
  decorative 3D model that floats in the corner of the image backgrounds is now
  selectable. The fixed `JavaLogo` (the four `Java-logo-*.png` panels that tilt
  with the mouse) is replaced by a `CornerLogo`
  (`org.jdesktop.lg3d.scenemanager.utils.background`) whose outer node is a
  *stable* drag handle — the `PickableRegion` the `WindowRotator` /
  `SceneTempZoomer` gestures bind to — while the visible artwork lives in a child
  that `setModel(Model)` rebuilds in place, so the model swaps live without
  re-wiring the scene. Two models ship: `JAVA` (the classic logo, unchanged) and
  `MASCOT`, the Looking-Glass mascot (`resources/images/icon/lg3d-logo.png`, the
  same icon the 2D splash and the About window reflect) rendered as a single
  textured panel with the same mouse-driven tilt. The choice is persisted on
  `DesktopConfig` (`desktop.cornerLogo`, default `java`) and picked in **Control
  Center ▸ Appearance ▸ Corner Logo** (a 3D-only `JList`; applying posts a
  `DesktopConfigChangeEvent` that `LayeredImageBackground`, `PanoImageBackground`
  and `SwayingSimpleImageBackground` listen for and swap on the spot). The pure
  id parsing (`DesktopConfig` normalization, `CornerLogo.Model.fromConfig`) is
  headless-tested. The Control Center window's default content height is raised
  (500 → 620px, in `ControlCenter` / `ControlCenterPanel`) so the Appearance
  panel's now-three stacked 3D-only sections stay fully visible without having
  to maximize the window.
- **Private (Tor) mode: a Whonix-like desktop-wide anonymity switch**
  (`lg3d-core`, `lg3d-apps`) — a new shared `TorPrivateMode`
  (`org.jdesktop.lg3d.utils.system`) forces *all* desktop traffic through tor and
  fails closed. `enable()` probes the tor service (§4.7 `PrivacyService`), starts
  it through polkit only if it is not running, then applies JVM SOCKS enforcement
  (`socksProxyHost=127.0.0.1`, `socksProxyPort=9050`, overridable via
  `lg.tor.socksPort`, `socksNonProxyHosts=localhost|127.*`) plus a SOCKS5
  `ProxySelector` with remote DNS, and arms a 5 s daemon monitor. The moment tor
  stops while the mode is on, the monitor trips the state machine
  (`OFF/ENABLING/ON → CUT`) and raises the new global `NetworkCut` flag: because
  the SOCKS endpoint is now dead, every JVM/env-var client socket is *refused*
  rather than silently falling back to clearnet, so a leak is impossible by
  construction. `leakCheck()` fetches `check.torproject.org/api/ip` through the
  proxy only and parses the verdict (`TOR_CONFIRMED` / `NOT_TOR` / `UNREACHABLE`).
  Every decision (`transition` / `monitorTick` / `proxyProperties` / `proxyEnv` /
  `parseTorCheck`) is pure and injectable, so it is headless-tested without
  spawning tor. External child processes launched by the 2D desktop inherit
  `http_proxy`/`https_proxy`/`all_proxy=socks5h://127.0.0.1:9050` while the mode is
  on (`Desktop2DAppRegistry.launchExternal`). A new taskbar **privacy indicator**
  (`PrivacyStatus` + `TaskbarIndicators`) shows a shield glyph (`Tor >>` on,
  `Tor ..` enabling, `Tor !!` cut, hidden while off), turns red on a cut, opens the
  Security Center on click, and a cut raises a warning toast. The Security Center's
  **Privacy** tab gains a *Private (Tor) mode* section (Enable / Disable / *Verify
  no leak*, a live state line and a red CUT banner), and the **web browser**
  enforces the mode: while cut it refuses remote navigations and renders an honest
  `TOR_CUT` page (new `LoadFailure.Reason` + `TorCutGuard`), and while on it forces
  a non-persisting cookie session.
- **VPN client: live status, auto-reconnect, kill switch and tunnel verification**
  (`lg3d-apps`) — the VPN front end (`apps/vpn`) grows from a manual
  connect/disconnect panel into a production-grade tunnel client while keeping the
  same honest, dependency-free delegation to `nmcli` / `openvpn` / `wg-quick`. A
  5 s Swing-Timer poll of `VpnBackend.parseStatus` now tracks the live tunnel state
  while the panel is open (started in `addNotify`, stopped in `removeNotify`, so a
  headless-constructed panel spawns nothing), replacing manual-only Refresh. An
  unexpected drop of a profile marked *auto-connect* drives a pure
  `ReconnectPolicy` (exponential backoff, 2 s→60 s ceiling, 5-attempt cap,
  overflow-safe) that reschedules the connect until the tunnel returns or the cap
  is reached. A new per-profile **kill switch** (a `killSwitch` flag on
  `VpnProfile`, migration-safe off for older `profiles.json`) reuses PR 1's global
  `NetworkCut` seam: an armed tunnel that drops unexpectedly cuts the desktop's
  network clients so traffic can never silently fall back to the clearnet, and the
  cut lifts automatically on reconnect (or on un-arming / closing the panel). A
  **Verify tunnel** button records the public egress IP just before the tunnel
  comes up and re-checks it on demand through `java.net.http`, classifying the
  result with the pure `TunnelVerdict` (`TUNNELED` / `LEAK` / `UNREACHABLE`) — an
  unchanged IP is reported as a leak, never a false pass. **New…** / **Edit…**
  dialogs (name, type, gateway, auto-connect, kill switch) complement the existing
  Import / Remove. Every decision (backoff, leak classification) is pure and
  headless-tested; the panel still constructs with no timer, thread, process or
  dialog.
- **Web browser: address-bar security indicator + site-info popup** (`lg3d-apps`)
  — a clickable indicator now sits next to the address bar (the status-bar lock
  stays as its echo). It classifies the current URL through the new JavaFX-free
  `SecurityInfo` (`SECURE` / `NOT_SECURE` / `LOCAL` / `INTERNAL` / `UNKNOWN`,
  built on `UrlNormalizer`) and opens a popup showing the connection security,
  host, scheme, JavaScript/cookie state and this site's blocked-navigation and
  blocked-popup counts. A new `SiteStats` (host → counters) is fed by two new
  `FxBrowser.Listener` callbacks, `onNavigationBlocked` / `onPopupBlocked`, fired
  when the extension broker vetoes a navigation or a popup.
- **Web browser: inline find bar with live match counts** (`lg3d-apps`) — the
  modal find dialog is replaced by a docked find bar (Ctrl+F or the Find button;
  Enter = next, Shift+Enter = previous, Esc = close and clear). It highlights
  every match and shows “n of m”, powered by the new JavaFX-free `FindScript`,
  which safely escapes the query into a JS string literal (no injection through
  the find field) and parses the returned counts; `FxBrowser` gains
  `find`/`findNext`/`findPrev`/`clearFind` and an `onFindResults` callback.
- **Web browser: reader mode** (`lg3d-apps`) — a new **Reader** toolbar button
  extracts the active page's main article and reopens it, clean and ad-free, in a
  new tab (the same pattern as view-source). The readability-style DOM heuristic
  runs in WebKit (`ReaderExtractor.EXTRACT_JS`); the JSON parse and the styled,
  fully-escaped page rendering are JavaFX-free (`ReaderArticle` /
  `ReaderExtractor`) and unit-tested headlessly.
- **Web browser: four more search engines** (`lg3d-apps`) — `Brave Search`,
  `Ecosia`, `Mojeek` and `Wikipedia` join `SearchEngine`; the settings combo
  already lists every value, so they are selectable with no other change.
- **2D start menu: icons on the category sub-menus** (`lg3d-core`) — the category
  folders in the 2D desktop's start menu (Internet, Utilities, Games, Media,
  Office, Education, Developers, System, ...) now carry a recognisable
  IconManager glyph instead of a bare text label, matching the iconned
  application entries inside them. A new `CategoryIcons` maps each group name to
  a curated glyph (Internet → WebComponent, Utilities → Preferences, Games →
  Play, Media → Movie, Office → Normal, Education → Information, Developers →
  Application, System → Host, Demos → TipOfTheDay, Early Prototypes → Applet,
  ...) and falls back to the `AppIcons` seam, so an uncurated group still gets a
  semantic glyph or an initials tile and honours the user's active icon pack.
  `Desktop2DStartMenu.appendGroups` sets the icon on each category `JMenu`, and
  the cache is cleared alongside `AppIcons` on an icon-pack switch so the
  categories refresh too.
- **2D splash: a looking-glass "mirror" with the mascot** (`lg3d-core`) — the
  start-up splash of the conventional 2D/Swing desktop is no longer a bare
  name+version card: it now opens with a rounded, lit "looking-glass" mirror in
  which the project mascot — the same `lg3d-logo.png` icon the About window
  shows — is seen through the glass, over a faded vertical reflection of itself
  on the surface below (a nod to the remote-viewing conceit behind the product's
  name). `Desktop2DSplash` loads the mascot from the runtime `resources/` tree
  and builds the mirror as a headless-paintable `JComponent`; when the asset is
  not on the classpath (e.g. headless tests) the mirror is simply omitted and the
  splash degrades to the previous name+version card.
- **Security Center: a Whonix-like security hub — grade, hardening advisor and
  activity log** (`lg3d-apps`, `lg3d-core`) — the Security Center grows from an
  antivirus scanner with a passive posture card into an aggregate *security hub*
  that ties the host posture, the private (Tor) mode and the VPN tunnel together.
  A new pure `SecurityScore` weights six defense-in-depth checks (SELinux
  enforcing, firewall running, an antivirus scanner installed, its definitions
  known, private (Tor) mode on, and a VPN tunnel up with its kill switch armed)
  into a 0-100 total and an A-F `Grade` shown in the Overview header, coloured
  green/amber/red; the two anonymity layers are weighted so a fully patched host
  that simply runs no tunnel still grades a B. A new pure `HardeningRules`
  derives an ordered, worst-first `Recommendation` list (each with a `Severity`
  and an `Action`) that replaces the old flat concerns list, and a **Remediate**
  button acts on the selected one — switch to the Privacy/Antivirus tab, run
  `freshclam`, re-probe, or, where the fix lives outside the app, surface honest
  guidance. The Overview posture grid gains **Private (Tor) mode** and **VPN
  tunnel** rows, read from the in-memory `TorPrivateMode.state()` and a read-only
  `nmcli` query (`VpnBackend`/`VpnStatus`, degrading to unknown, never a guessed
  "connected"), with the kill-switch state read from the VPN app's own profile
  store. A new **Activity** tab is an append-only audit trail (`AuditEvent`,
  persisted by `SecurityCenterStore.saveAudit`/`loadAudit`, capped at 500
  entries) recording every scan, definition update, posture read, private-mode
  transition, network cut and VPN tunnel change; the live tor/cut listeners are
  registered in `addNotify` and dropped in `removeNotify`, so a headless-built
  panel leaves no global listener behind. The grade is published to core through
  the new side-effect-free `SecurityPosture` seam (apps → core), letting the 2D
  taskbar privacy shield tooltip double as the aggregate security indicator and
  paint a caution colour on a poor (D/F) grade. Every new decision (`SecurityScore`,
  `HardeningRules`, `AuditEvent`, `SecurityPosture`, `SecurityProbe.describeVpn`,
  the `PrivacyStatus` grade overloads) is pure and headless-tested.
- **Project Mandela: the desktop's own scripting language** (`lg3d-mandela`,
  `lg3d-apps`, `lg3d-core`) — a tenth Gradle module, `lg3d-mandela`, ships a
  complete language tool chain in **one dependency-free jar** (`java.base` +
  `javax.script` only — no AWT, no Swing, no Java 3D, no lg3d-core, no third-party
  library): lexer, recursive-descent parser and AST, a bytecode compiler and stack
  VM with a disassembler, the value/object model, a standard library
  (`std.fs`/`std.json`/`std.math`/`std.time`/`std.env`), a CLI
  (`mandela run|check|eval|dump|about|repl`, exit codes `0` ok / `1` usage /
  `2` program failed / `3` unreadable file / `4` blocking findings) and an
  interactive REPL. The syntax is Java/Kotlin-flavoured and deliberately legible — `fun`,
  `let`/`var`,
  `if`/`for`/`while`/`match` expressions, string interpolation, records and classes,
  `{ x -> … }` lambdas **including the Kotlin trailing-lambda call form**
  (`prices.map { x -> x * 1.05 }`, where the block must open on the call's line and
  carry a `->` header, which is what keeps `for x in items() { … }` a loop body and
  `match f(2) { 1 => … }` a match block) — inheritance reads like Kotlin too (a
  subclass reaches its base's fields and methods **by bare name**, own members
  shadowing inherited ones) — with contextual rather than reserved
  soft keywords, so no script loses a name.
  **Security is a language property, not a wrapper:** every engine runs under an
  explicit `Capabilities` profile of named grants (`STDOUT`…`THREAD`) plus an
  instruction and frame budget, with four shipped profiles — `open()` (the CLI),
  `console()` (a pasted snippet), `desktop(root)` (a file-confined automation) and
  `webPage()` (nothing granted, 5 M steps) — and refusals are never silent: a denied
  side effect raises a catchable `PermissionError` naming the grant, a runaway loop
  raises `LimitError`. Host values cross only through `api.Interop` (Java
  collections/maps, `java.util.function` and **Kotlin lambdas** included), and an
  arbitrary object is *refused* rather than reflected over — that refusal is the
  sandbox.
  **The developer API and two shipped hosts:** `api.Mandela` (facade),
  `rt.Runtime` + `Runtime.Builder` (bind, compile-once/run-many, child-engine
  `import`), `api.Interop`, `rt.Capabilities`, `api.EditorServices` (keywords,
  outline, vocabulary, completion, starter templates — the same tables the parser
  reads, so a host never re-implements a grammar) and a JSR-223
  `MandelaScriptEngine` registered under `META-INF/services`. **Espresso** now
  recognises `.mnd` as a first-class language (`Languages` gains a **Mandela**
  entry whose keyword set is read from `mandela.lang.Keywords`, so painter and
  parser cannot disagree) and gains the bundled `MandelaTools` extension
  (`lg3d.mandela`, new `Mandela` toolbar category, READ+WRITE+FILE_IO+TOOLBAR+
  DIAGNOSE, 4 actions): live diagnostics into the Problems tab and gutter, the
  Structure outline, Completions, `Ctrl+Alt+Shift+M` to run the buffer **in-JVM**
  under `Capabilities.console()`, `+A` to check and `+B` to dump bytecode. The **Web
  Browser** gains `MandelaUserscriptsExtension` (`lg3d.mandela.userscripts`): the
  `.mnd` files in `~/.lg3d/webbrowser/userscripts` run against each committed page
  behind the existing `CONTENT_SCRIPT` gate, sandboxed to `webPage()`, with only
  `page`/`inject`/`note`/the console bound and the `// @match` header parsed
  host-side — so the feature needs no new consent dialog. The jar is wired onto the
  `:lg3d-core:run` / `releaseBundle` classpath and into the release bundle.
  Docs: [`docs/mandela-language.md`](docs/mandela-language.md) (the grammar,
  library and capability model, with the rationale for every refusal),
  [`docs/mandela-embedding-api.md`](docs/mandela-embedding-api.md) (the host
  contract) and `lg3d-mandela/AGENTS.md`. Tests: **369** headless JUnit 5 cases in
  the new module (`SyntaxTest`, `LanguageTest`, `StandardLibraryTest`,
  `EmbeddingApiTest`, `InteropTest`, `ScriptEngineTest`, `EditorServicesTest`,
  `CliTest`, `DocumentationTest`, `ShippedScriptsTest`), plus 34 `MandelaToolsTest` and 23
  `MandelaUserscriptsExtensionTest` cases for the two hosts. The documentation is
  a tested artifact: the language
  guide's `mandela` code blocks are extracted and put through the real parser
  **and** the real compiler in `DocumentationTest` (the only finding a prose
  fragment is allowed is the `undefined-name` one for a value the surrounding text
  introduced), so the tutorial cannot drift from the grammar or teach a program
  that will not run; and a
  finding carries a stable `rule` id (`"syntax"`, `"undefined-name"`,
  `"param-order"`) for a host to branch on instead of message text.
- **Project Mandela dogfooded: the repository's automation is written in Mandela**
  (`lg3d-mandela`, `scripts/mandela`, CI) — `scripts/mandela/` (see its
  [`README.md`](scripts/mandela/README.md)) ships five `.mnd` programs that do
  real jobs against this tree, in the language's own standard library and under
  its own sandbox: **`doc-links.mnd`** resolves every relative link and
  `#anchor` in the documentation (every relative link in 119 markdown files, in
  well under a second — 426 of them on the day it was wired up), joining each
  target against the *containing* file and
  verifying a fragment against the headings the target really offers (GitHub's
  slug rules), so `#…` links cannot rot silently; **`startmenu-audit.mnd`**
  indexes the 5 677 classpath locations the assembled runtime exposes from 11
  modules — reproducing the `resources/` merge `:lg3d-core:runtimeResources`
  performs — then resolves every `resource:///…` and `java <class>` reference in
  the 91 `.lgcfg` start-menu descriptors, prints the live inventory by group
  (57 items: Utilities 13, System 8, Internet 8, Office 6, Media 6, Developers
  6, Games 4, Education 1, ungrouped 5) and separates the 7 references that sit
  in directories the desktop never scans from anything a user could call broken;
  **`release-check.mnd`** compares the four canonical version references
  (`build.gradle`, `AGENTS.md`, `README.md`, `CHANGELOG.md`) and cross-checks
  `settings.gradle` against the module directories in *both* directions, so a
  module that never builds and a stale coordinate are both caught before a
  release; **`asset-report.mnd`** weighs the shipped artwork (319 assets,
  41 606 KiB) into a human table or `std.json` (`json`, `out=PATH`), which
  round-trips back through `json.parse`; **`userscript-new.mnd`** is a Mandela
  program that authors Mandela programs *for another host* — it writes a Web
  Browser userscript into the browser's `userscripts/` directory in the
  `webPage()` vocabulary, and the generated file was run through
  `Capabilities.webPage()` to prove that vocabulary is the one the browser
  binds. The three gates run in CI as
  `./gradlew :lg3d-mandela:repositoryChecks` (`docLinksCheck`, `releaseCheck`,
  `startMenuAudit` — new `JavaExec` tasks in `lg3d-mandela/build.gradle` that
  pass the `-s <repo root>` sandbox and need no jar); they are an explicit
  workflow step rather than part of `check`, because they audit the whole tree
  and their output *is* the diagnosis. `ShippedScriptsTest` (3 new cases, the
  module now at 369) compiles every shipped `.mnd` with **no** finding excused —
  a shipped script is a complete program, unlike a guide fragment — binds `args`
  the way the CLI's `check` does, and pins the header conventions (shebang,
  one-line purpose, the `-s` sandbox the script must run with) that keep the
  directory usable without a wiki, so a grammar change that would break real
  automation fails the build instead of the next user's script.

### Changed
- **The 2D desktop no longer lists 3D-only applications** (`lg3d-core`) — a pure
  Java 3D app has no scene to render into on the 2D/Swing desktop, so its
  start-menu entry is now **omitted entirely** instead of being shown greyed out
  with the “Requires the 3D desktop” tooltip. `Desktop2DStartMenu.createItem`
  returns null for `Kind.UNAVAILABLE` commands (like it already did for an
  external command whose executable is missing), and `StartMenuSearch` filters
  the same commands out of its live-search matches so the result count, the
  rendered rows and “launch top match” stay consistent. Empty categories are
  skipped as before, the 3D start menu is untouched, and a 3D-only command
  reached another way (e.g. a restored session) still reports that it needs the
  3D desktop via `Desktop2D.openApp`.
- **Two apps moved to a better-fitting start-menu group** (`lg3d-apps`) — **PDF
  Viewer** moves from **Media** to **Office** (it is a document reader, not a
  multimedia player) and **Remote Viewer** moves from **Developers** to
  **Internet** (a remote-desktop client, alongside SSH / VPN / FTP). Only the
  `menuGroup` in each `.lgcfg` descriptor changes; the launch command, icon and
  the 2D-desktop `Desktop2DAppRegistry.PANEL_APPS` mapping are untouched.

### Removed
- **CallViewer dropped from the start menu** (`lg3d-apps`) — the 3D source-code
  call-graph visualizer (a preliminary developer sample) did not run reliably, so
  its start-menu descriptor `src/config/callviewer.lgcfg` was deleted and it is no
  longer discovered or posted. The source stays in-tree and still compiles, and the
  app can be launched manually in-JVM; only the menu entry is removed.
- **Tutorials dropped from the start menu** (`lg3d-apps`) — the teaching samples
  `Tutorial 1/2/3` (menu group **Tests**) are no longer posted: their descriptors
  `src/config/tutorial{1,2,3}.lgcfg` were deleted. The source stays in-tree and
  still compiles and can be launched manually in-JVM; only the menu entries are
  removed. (The **Tests** group is not linked from `Main`, so it is never
  rendered; its remaining `swingtest` / `swingnode` entries are removed below.)
- **Luncher and Natural Language Control dropped from the start menu**
  (`lg3d-apps`, `lg3d-incubator`) — both are 3D-only sample/experimental apps (a
  glassy-cube card launcher menu, and a voice/typed-command controller that is
  also runtime-blocked on a microphone + the Stanford NLP model), the same family
  as the tutorials/CallViewer above. Their descriptors `src/config/luncher.lgcfg`
  and `src/config/nlc.lgcfg` were deleted, so neither is discovered or posted to
  any menu. Both still compile in `lg3d-incubator` and can be launched manually
  in-JVM; only the menu entries are removed.
- **Swing Test and Swing Node Test dropped from the start menu** (`lg3d-apps`) —
  the two `Tests`-group developer harnesses (a plain-Swing `JFrame` capture
  fixture and the custom-`SwingNodeRenderer` cloth demo) are no longer posted:
  their descriptors `src/config/swingtest.lgcfg` and `src/config/swingnode.lgcfg`
  were deleted. The `Tests` group was already unreachable from `Main` (a leftover
  of the earlier Demos unlinking), so neither entry was visible in any case; this
  makes the removal explicit. Both sources stay in-tree and still compile and can
  be launched manually in-JVM — `TestFrame` remains classified as a conventional
  Swing-frame app in `Desktop2DAppRegistry` — only the menu entries are removed.

### Fixed
- **2D taskbar Start button: Looking Glass logo, "Start" label and full button
  height restored** (`lg3d-core`) — the start button drew a 16 px star glyph
  while the quick-launch buttons draw 22 px, so it sat visibly shorter than its
  neighbours, and the icon-only label default silently dropped the "Start"
  text. The fixed chrome buttons (start, documents, downloads, exit,
  notification tray) now all draw at the same icon edge as the quick-launch
  glyphs, Start carries the `lg3d-logo.png` Looking Glass logo, and the default
  taskbar label style is back to icon-plus-text; icon-only remains selectable
  in the Control Center Desktop panel, and a style persisted there still wins
  over the new default.
- **P2P transport: a peer whose link dies during registration no longer leaks**
  (`lg3d-apps`) — when the remote socket closed between the Noise handshake and
  the peer-map registration, `onClosed` fired before `byChannel.put` and reaped
  nothing, so the dead peer stayed in the mesh forever (surfaced in CI as a
  flaky failure of `P2pNodeTest`'s trust-rejection test). `registerChannel` now
  re-checks `isOpen()` after registering and reaps the channel there; a
  deterministic regression test stalls the adoption inside the peer verifier so
  the teardown lands in exactly that window.
- **Maximized hosted Swing windows: the 3D cursor clicked the wrong control**
  (`lg3d-core`) — maximizing a `SwingNode`-hosted window (Control Center, Task
  Manager, …) resizes the panel natively via `SwingNode.setHostedSize` but left
  the hidden host `JFrame` at the size it was packed to in `setJPanel`, so the
  input path disagreed with itself: `SwingNodeRenderer.calcPositionInPanel`
  scaled the picked 3D point by the stale frame width/height and AWT retargeted
  the forwarded event into that too-small frame, landing hits offset (normal
  size was unaffected because frame and panel still matched). `setHostedSize`
  now re-packs the hidden frame after revalidating the panel so the dispatch
  root tracks the panel, and `calcPositionInPanel` scales by the panel's own
  pixel size (frame as fallback) so the mapped coordinate is always in the
  space the Swing children are laid out in.
- **Web browser: honest error pages, a load watchdog and robust downloads**
  (`lg3d-apps`) — a failed navigation no longer leaves a blank tab with only a
  status string. `FxBrowser` now classifies the failure with the new JavaFX-free
  `LoadFailure` (walking the whole cause chain, then falling back to WebKit's
  message text) and renders a styled, XSS-escaped `ErrorPage` with recovery tips
  and a “Try again” button when retrying makes sense; the toolbar Reload retries
  the original URL. A per-navigation watchdog on a shared daemon scheduler cancels
  a load that stalls past the new `pageTimeoutSeconds` setting and shows a TIMEOUT
  page, and `Worker.State.CANCELLED` now resets progress/status instead of being
  ignored. Downloads stream through a buffered copy loop with incremental
  progress, a `downloadTimeoutSeconds` request timeout and a client connect
  timeout, name the file from `Content-Disposition` (RFC 6266/5987, via the new
  `ContentDisposition`) before falling back to the URL, and can be cancelled from
  the downloads dialog (the partial file is removed); IO failures are reported
  with the same readable vocabulary.
- **Web browser: thread-safety between the EDT and the JavaFX thread**
  (`lg3d-apps`) — `TabModel` is now internally synchronised, its mutable `Tab`
  fields are `volatile` and `list()` returns an immutable snapshot copy, so the
  EDT iterating the tab strip while the FX thread mutates it can no longer hit a
  `ConcurrentModificationException`. `FxBrowser`'s view map is a
  `ConcurrentHashMap` and its read-only getters (`canGoBack`/`canGoForward`/
  `getLocation`/`getZoom`) read a `volatile` navigation snapshot refreshed on the
  FX thread instead of reaching into `WebHistory`/`WebView` off-thread; the zoom
  label is likewise updated via `SwingUtilities.invokeLater`. Session restore is
  defensive (skips blank/`view-source:` entries, caps the restored tab count, and
  one bad URL can no longer abort startup), and closing a tab or shutting down now
  cancels its watchdog, in-flight download and cached error state.
- **Oversized taskbar icon for plugins that build their icon early** (`lg3d-core`)
  — the Terminator (exit) taskbar item's Jolly Roger icon could render far larger
  than its neighbours in the right-hand group and, on its hover-grow, loom over and
  intercept clicks meant for the exit dialog's ✓ / ✗ buttons. Root cause: a
  `SceneManagerPlugin` (e.g. `Terminator`) constructs its `Pseudo3DIcon` before the
  taskbar publishes `DesktopConfig`'s icon scale, baking the default 1.0 scale,
  and because the icon is wrapped in a `Tapp` the `rescaleIcons()` pass — which
  only visited *direct* `Pseudo3DIcon` children of the `shortcuts` / `themes` rows
  — never corrected it. `GlassyTaskbar` and `AdvancedGlassyTaskbar.addTaskbarItem`
  now normalise any `Pseudo3DIcon` nested in a newly added item to the configured
  icon scale, and the rescale pass recurses into container wrappers so later
  icon-size changes reach wrapped icons too.
- **"Lg3d Homepage" start-menu item pointed at a dead URL** (`lg3d-core`) — the
  Developers-group link launched `firefox http://lg3d-core.dev.java.net`, the
  original Sun/java.net project home that has been dead for ~20 years. It now
  opens this project's GitHub repository,
  `https://github.com/landrevillejf/ProjectLookingGlass`. Only the descriptor
  `command` in `lg3d-core/src/etc/lg3d/startmenu.lgcfg` changes; the item name,
  icon and group are untouched.
- **Swept the remaining dead `dev.java.net` URLs in live sources** (multiple
  modules) — the original Sun/java.net project domain has been dead for ~20
  years, so it is replaced with this GitHub repository in the orgchart sample
  `contacts.xml` (contributor `photoURL` / `url` entries), the jmf23D
  `JXTAManager` JXTA module-spec URI, the legacy Ant `build.xml` javadoc footer
  and `build-x11.xml` developer-guide hint, the retired `lg3d-awt` `JawtToolkit`
  javadoc link, and the `Issue381` javadoc tracker reference. The historical
  website snapshots under the various `www/` trees, the packaging templates
  (`dpkg` / `win32`) and the sample `.msg` mailing-list data still carry the old
  domain and are intentionally left untouched (archival, not user-facing).
- **A benign JavaFX/WebKit `SEVERE` log no longer pops the desktop crash dialog**
  (`lg3d-core`) — visiting a site that serves a `Content-Encoding` JavaFX 21's
  WebKit loader cannot decode (Brotli, zstd, or a non-standard `none`, as
  `www.cam4.com` does) makes `com.sun.webkit.network.URLLoader` log
  “Unknown encoding type '…' found, discarding” at `SEVERE`. `LogHandler` used to
  raise the full-screen “save your work / crash reporter” `ErrorDialog` for *any*
  `SEVERE` record from *any* logger, so merely loading such a page looked like a
  fatal crash even though the desktop kept running. `LogHandler` now suppresses
  `SEVERE` records whose logger or source class is a third-party UI/web engine
  (`com.sun.javafx.`, `com.sun.webkit.`, `javafx.`) — genuine `lg.*` severe
  errors still raise the dialog — and builds it on the EDT. `ErrorDialog` is also
  modernised: the dead 2006 personal crash-reporter URL
  (`pinaraf.robertlan.eu.org`) is replaced by this project's live issue tracker
  (overridable via the `lg.crashreport.url` property), a **Copy details** button
  is added, the message/trace panes are read-only and selectable, the
  window-close button now matches **OK** (so a fatal error still exits), and the
  message text is assembled by a pure, unit-tested `buildMessage`. The page
  itself still renders empty because JavaFX 21 discards the undecodable body — an
  engine limitation fixed only in JavaFX 24+ (needs JDK 22+, beyond this port's
  JDK 21 pin).
- **Web browser: blank pages from an undecodable `Content-Encoding` now recover**
  (`lg3d-apps`) — a follow-up to the crash-dialog fix above. JavaFX 21's WebKit
  still *discards* the body of a page served with an encoding it cannot decode
  (Brotli, zstd, or a non-standard `none`), so the load reports `SUCCEEDED` yet
  renders blank. `FxBrowser` now probes the committed DOM (a cheap element/text
  count, no serialisation) and, when it is an empty skeleton for a network URL,
  re-fetches the page with the JDK `java.net.http` client — which does not
  advertise `Accept-Encoding` and so is normally answered with a plain body, and
  decodes gzip/deflate itself — then renders it via `loadContent` with an injected
  `<base>` tag so relative links and images still resolve, keeping the real URL in
  the address bar, tab and history. It is one-shot per navigation; if the body is
  genuinely undecodable (br/zstd) the tab shows an honest `UNDECODABLE_BODY` error
  page instead of staying blank. Every decision (blank-DOM detection,
  `Content-Encoding` decode, charset resolution, base-tag injection) lives in the
  JavaFX-free `EncodingFallback`, unit-tested headlessly.
- **JavaFX/WebKit engine noise no longer floods the desktop console** (`lg3d-core`)
  — the crash-dialog suppression above stopped the *dialog*, but the logging
  `ConsoleHandler` (level `ALL`) still printed every benign engine record to
  stderr, so browsing a heavy site spammed repeated
  `SEVERE: Unknown encoding type 'none' found, discarding`, the
  `Unsupported JavaFX configuration: … 'unnamed module'` warning and WebKit
  media-player `Unrecognized file signature!` warnings. `logging.properties` now
  turns the third-party engine loggers (`com.sun.javafx`, `com.sun.webkit`,
  `javafx`, `com.sun.media.jfxmedia`) `OFF`, so those records are never published.
  The desktop's own `lg.*` logging is untouched, and any single engine logger can
  still be raised there without a code change.
- **The "multiple SLF4J providers" startup warning is gone** (`lg3d-core`) — the
  bundled `libs/IconManager-1.6.0.jar` is a shaded fat jar that registers
  `slf4j-simple` through `META-INF/services`, which collided with the desktop's
  own silent `slf4j-nop` provider, so SLF4J logged
  `Class path contains multiple SLF4J providers` at every launch before picking
  one. A new `:lg3d-core:slimIconManagerJar` task repackages that jar verbatim
  minus the single provider-registration entry, and both the desktop run classpath
  and the release bundle now use the slimmed copy, leaving `slf4j-nop` as the sole
  provider. The child-process fat jars (swing-ide / openapi-editor / payloadman)
  are deliberately left untouched — each keeps its own provider in its own JVM.
- **Web browser toolbar now uses IconManager glyphs** (`lg3d-apps`) — the JavaFX
  Web Browser's Swing chrome (`BrowserPanel`) drew its navigation and tool buttons
  with plain text labels ("Back", "Forward", "Reload", …). They now load the
  bundled `IconManager` toolbar glyphs — navigation (Back/Forward/Home/Up/Down),
  Refresh, Stop, Bookmarks, Find, ZoomIn/ZoomOut, Save (downloads), History,
  Preferences (settings) and WebComponent (extensions) — matching the icon
  vocabulary the rest of the desktop (e.g. `RemoteViewerPanel`) already uses, with
  each button's text cleared so the icon stands alone over its existing tooltip.
  Icon loading is guarded: IconManager is a compile-only jar on the desktop run
  classpath, so a missing jar or unknown glyph leaves the button's text label
  intact rather than breaking the toolbar.

## [1.66.0] — 2026-10-07 — Gradle / JDK 21 modernization

### Added
- **Software Update: OpenPGP signature verification is now enforced by default**
  (`update-manager`) — now that every release is signed by the CI release-signing
  key and the matching public key ships inside the jar (v1.64.0 was the first
  signed release, verified end-to-end: `gpg --verify` = *Good signature*, a
  one-byte-tampered bundle = *BAD signature*), the client flips
  `update.signature.enabled` to **`true`**. `UpdateService.verifySignature` now runs
  `UpdateSignatureGate` on every update, so a present-but-invalid signature (a
  tampered `lg3d-<version>.zip`) fails the install instead of being ignored; the
  SHA-256 checksum still gates it too. `update.signature.required` is also flipped
  to **`true`** (fail-closed): an update whose signature cannot be verified —
  tampered *or* missing — is now rejected outright rather than merely warned about.
  This is enabled directly because signing is proven end-to-end and every release
  from v1.64.0 on is signed; the operational consequence is that the
  `RELEASE_SIGNING_KEY` CI secret must stay configured, or fail-closed clients
  cannot install a future unsigned release. The signing private key and its
  revocation certificate are escrowed for offline backup (never committed; see
  `docs/release-process.md` §13.5). A new
  `testPackagedDefaultsEnableSignatureVerification` locks the shipped defaults
  (enabled, required, key id `9A5DAD01CF4F5054`, fingerprint) and
  `docs/release-process.md` §13.1/§13.4/§13.5 + `update-manager/AGENTS.md` are
  updated to match.
- **LFS system management: front-end contract + shared init-system backend
  (Phase 0)** (`lg3d-core`, docs) — adds `system-management-contract.md`, a
  normative (RFC 2119) contract making the lg3d desktop a *front-end* that drives
  the LFS/BLFS-installed CLIs (`systemctl`/`rc-service`/`sv`/`s6-svc`,
  `lfs-update`, `nmcli`, `cryptsetup`, `nft`, `tor`, …) and never re-implements,
  locks or second-guesses them. It inherits `lfs-x11-contract.md` and the LPM §5
  integration rules (argument vectors not shell interpolation, separate
  stdout/stderr capture, per-operation polkit escalation via `PrivilegedRunner`,
  unprivileged read-only paths, serialized mutating ops, verbatim error fidelity,
  exit codes as state) and maps every subsystem to a lg3d surface (Control Center
  panels / existing apps). Adds the shared `org.jdesktop.lg3d.utils.system.InitSystemService`
  socle: pure init detection (systemd → openrc → runit → s6 → sysvinit) and
  operation → CLI argument-vector mapping mirroring the builder's
  `06b-service-management.sh`, with 20 headless JUnit 5 tests covering the
  detection matrix, every per-init vector, and graceful degradation. No app
  behaviour changes yet; the per-subsystem panels land in later phases.
- **LFS system management Phase 1: a Services control-center panel**
  (`lg3d-apps`, `lg3d-core`) — adds `ServicesPanel`, a thin front-end over
  `InitSystemService` registered as the Control Center's 22nd category
  (“Services”). It detects the running supervisor (systemd / openrc / runit /
  s6 / sysvinit), lists its units in a `JList` (never a combo box, so it survives
  offscreen `SwingNode` hosting), shows a read-only `status` view and drives
  start/stop/restart/enable/disable through that supervisor's native CLI.
  Read-only ops run unprivileged via `ProcessRunner`; mutating ops are confirmed
  and escalated per-operation via `PrivilegedRunner` (polkit), and are disabled
  (panel becomes read-only) when `pkexec` is absent or the operation is
  unsupported for the detected init (sysvinit has no enable/disable). Non-zero
  exits are shown verbatim as a result, not a crash. `InitSystemService` gains a
  pure, headless-tested `parseServiceList`/`listServiceEntries` (systemd
  `list-units`, openrc `rc-status`, and the `ls`-based backends) plus
  `ServiceEntry`; 6 new parser tests join the 20 Phase 0 tests and a headless
  `ServicesPanelTest` covers construction, lifecycle and lazy registration.
- **LFS system management Phase 2: a System Update control-center panel**
  (`lg3d-apps`, `lg3d-core`) — adds `SystemUpdatePanel`, a thin front-end over
  the new `LfsUpdateService`, registered as the Control Center's 23rd category
  (“System Update”). It drives the builder-installed `/usr/bin/lfs-update`
  (`check`/`status`/`upgrade`) and never re-implements the updater (upgrade
  itself backs up `/etc`+`/boot`, runs `lpm update-db`/`upgrade` and rebuilds the
  kernel + GRUB). Read-only `check`/`status` run unprivileged; the mutating
  `upgrade` is confirmed and escalated per-operation via `PrivilegedRunner`
  (polkit), runs **off the EDT** behind an indeterminate progress bar with every
  control disabled while in flight (serialization), and shows a non-zero exit
  verbatim as state. Output is ANSI-stripped (`lfs-update` colourises with no
  disable switch) and the check exit code is read as state (1 = up to date). The
  panel states the three-layer update model the contract mandates —
  `update-manager` (the lg3d bundle), `lpm` (packages) and `lfs-update` (the whole
  system) — and degrades to a read-only note when `lfs-update` is absent (a
  non-LFS host). `LfsUpdateService` is pure/injectable (command vectors,
  `stripAnsi`, `interpretCheck`, lenient `parseStatus`) with 9 headless tests,
  plus a headless `SystemUpdatePanelTest`.
- **LFS system management Phase 3a: a Storage & LUKS control-center panel**
  (`lg3d-apps`, `lg3d-core`) — adds `StoragePanel`, a thin front-end over the new
  `StorageService`/`LuksService`, registered as the Control Center's 24th category
  (“Storage”). It lists block devices read-only from `lsblk -P` in a `JList` (never
  a combo box, so it survives offscreen `SwingNode` hosting), reads a device UUID
  with `blkid` and shows `cryptsetup status`, and drives the LUKS lifecycle through
  the owning CLIs — it never re-implements encryption and never edits
  `/etc/crypttab`/`/etc/fstab` directly. `luksOpen`/`luksClose`/`luksAddKey` are
  **mutating** (confirmed, escalated per-operation via `PrivilegedRunner`/polkit);
  `luksFormat` and the `lfs-encrypt-disk` helper are **destructive** (they erase all
  data) so they additionally require an explicit **typed** confirmation of the
  device path before the polkit prompt. Passphrases are collected into a
  `JPasswordField` and fed through **stdin**, never as command-line arguments. Every
  op runs **off the EDT** behind an indeterminate progress bar with all controls
  disabled while in flight (serialization), and a non-zero exit is shown verbatim as
  state. The panel degrades to read-only when `cryptsetup` is absent and to an
  “unavailable” note (spawning nothing) when `lsblk` is absent. `StorageService`
  (lsblk/blkid vectors, `parseDevices`/`BlockDevice`) and `LuksService` (the exact
  §4.5 `cryptsetup`/`lfs-encrypt-disk` vectors, mutating/destructive `Operation`
  classification, `parseStatus`) are pure/injectable, with 8 + 13 headless tests plus
  a headless `StoragePanelTest`.
- **LFS system management Phase 3b: Network panel backend detection & fallback**
  (`lg3d-apps`, `lg3d-core`) — extends `NetworkPanel` from a NetworkManager-only
  view into a thin front-end over the new `NetworkService`, which detects the
  running network backend in the contract's §4.4 order — NetworkManager (`nmcli`
  or the `NetworkManager` daemon) → dhcpcd → systemd-networkd (`networkctl`) → a
  read-only `ip` fallback — and maps list / status / up / down onto that backend's
  native CLI (it never re-implements networking and never edits config directly).
  The panel lists connections/links in a `JList` (never a combo box, so it survives
  offscreen `SwingNode` hosting), shows read-only link/address state and drives
  connect/disconnect through the owning tool. Read-only probes run **unprivileged**
  via `ProcessRunner`; mutations are **confirmed**, run **off the EDT** behind an
  indeterminate progress bar with every control disabled while in flight
  (serialization), and are escalated per-operation via `PrivilegedRunner` (polkit)
  on `dhcpcd`/`networkd` — while on NetworkManager `nmcli` runs unprivileged and the
  daemon raises its own single prompt (§7 item 5). The read-only `ip` fallback and a
  no-backend host disable connect/disconnect and show a note rather than failing.
  `NetworkService` (the backend-detection matrix, the exact §4.4 argument vectors,
  and pure parsers for `nmcli -t` escaped colons, `ip -o link`, `networkctl list`
  and `/etc/dhcpcd.conf`) is injectable with 30 headless tests, plus a headless
  `NetworkPanelTest`. The legacy `desktop2d.NetworkConnections` nmcli-only seam is
  left intact (still unit-tested); `NetworkPanel` now routes uniformly through
  `NetworkService`.
- **LFS system management Phase 4a: Security — nftables / AppArmor / SELinux /
  SSH over real backends** (`lg3d-apps`, `lg3d-core`) — adds the shared
  `org.jdesktop.lg3d.utils.system.SecurityService`, the §4.6 host-security
  front-end: it detects which backends are installed (`nft`,
  `apparmor_status`/`aa-status`, `getenforce`/`selinuxenabled`, `sshd`) and builds
  the exact argument vectors — `nft list ruleset` (read) and
  `nft -f /etc/nftables.conf` (**mutating**, escalated, confirmed), `aa-status`,
  `getenforce`, `selinuxenabled` (exit code = enabled?) and the sshd lifecycle —
  delegating sshd status/start/stop to `InitSystemService` (§4.1) so no init logic
  is duplicated. It never re-implements firewall/MAC logic and never writes `/etc`
  directly (§6). The `firewall` app gains an **nftables** tab (resolving its
  long-standing “no nftables support” gap): a read-only `nft list ruleset` view plus
  a confirmed, polkit-escalated “apply `/etc/nftables.conf`” action, both off the
  EDT behind an indeterminate progress bar with the controls disabled while in
  flight (serialization) and a non-zero exit shown verbatim as state; the legacy
  firewalld/iptables Rules tab and the panel’s 5s-refresh `Timer` + `setOnClose`
  lifecycle seam are preserved, and construction stays headless-safe (no process
  until the user acts). The `securitycenter` Security Overview grows **AppArmor**
  and **SSH daemon** rows read through `SecurityService` (read-only, unprivileged)
  alongside the existing SELinux/firewalld/antivirus posture, a missing tool or an
  unprivileged read degrading to an honest label rather than a false “secure”; the
  existing `SecurityProbe`/`SecuritySnapshot` rating seam is untouched.
  `SecurityService` (the detection matrix, the §4.6 vectors and pure parsers for
  `getenforce`, `selinuxenabled`, `aa-status`, `nft list ruleset` and the
  init-specific `status sshd` shapes) is injectable with 27 headless tests, plus a
  headless `FirewallPanelTest` and a new `renderHostServices` overview case.
- **LFS system management Phase 4b: Privacy — tor over the init abstraction**
  (`lg3d-apps`, `lg3d-core`) — adds the shared
  `org.jdesktop.lg3d.utils.system.PrivacyService`, the §4.7 privacy front-end: it
  detects whether `tor` and a supported init system are present and whether
  `/etc/tor/torrc` and `/var/log/tor/notices.log` exist, then drives the tor
  lifecycle (status / start / stop / restart) by **delegating entirely to
  `InitSystemService`** (§4.1) with `tor` as the service name — there is no
  tor-specific reimplementation (§6). The config and the notices log are shown
  **read-only** through bounded NIO file reads (a trailing 256 KiB window, so a
  large log cannot exhaust memory); the panel never writes `/etc` or `/var/log`.
  The `securitycenter` app gains a third **Privacy** tab (`PrivacyPanel`): the
  status read and both file views run **unprivileged** (no escalation for a
  read-only view, §4.4), while start/stop/restart are **confirmed** and escalated
  per-operation via `PrivilegedRunner` (polkit), run **off the EDT** behind an
  indeterminate progress bar with the controls disabled while in flight
  (serialization, §3.3), and report a non-zero exit or a cancelled prompt verbatim
  as state (§3.2, §7 item 5). A missing tor, an undetected init or an absent
  polkit agent degrades the buttons and shows honest guidance rather than a false
  result; an unreadable root-only log degrades to an honest privilege note. The
  existing Antivirus / Security Overview tabs and the `SecurityProbe` rating seam
  are untouched. `PrivacyService` (the detection matrix, the init-delegated
  vectors, the pure `parseTorState` / `describeTor` and the bounded `readFile` /
  `FileContent`) is injectable with 22 headless tests, plus a headless
  `PrivacyPanelTest` and a new three-tab `SecurityCenterPanelTest` case.

## [1.64.0] — 2026-10-07 — Gradle / JDK 21 modernization

### Added
- **Signed releases: a live PGP signing key wired end-to-end** (`update-manager`,
  `.github/workflows/release.yml`, `docs`) — the release-signing machinery already
  existed on both ends (CI signs an armored detached `lg3d-<version>.zip.asc`;
  `UpdateSignatureGate` / `UpdateSignatureVerifier` / `PGPKeyManager` verify it),
  but was dormant: no key, no bundled public key and every client flag off. A
  dedicated *Project Looking Glass Release Signing* RSA-4096 key (long id
  `9A5DAD01CF4F5054`, fingerprint `C0D85590B541798C5280C7F69A5DAD01CF4F5054`,
  no expiry, no passphrase) is now configured in the `RELEASE_SIGNING_KEY` +
  `RELEASE_SIGNING_KEY_ID` repository secrets, so every release cut from now on is
  signed and publishes `.zip.asc` + `public-key.asc` with `signatureUrl` /
  `signingKeyId` populated in `version.json`. The matching **public** key is
  committed at `update-manager/src/main/resources/public-key.asc` and
  `update.signature.key.id` / `.fingerprint` are pre-set to it, so the strict
  verification path is ready. Client *enforcement* stays deliberately off for this
  first pass (`update.signature.enabled=false`, `update.signature.required=false`)
  so an older client without the bundled key is not stranded; flipping it on is a
  documented follow-up. The legacy checksum-only `UpdateVerifier.verifyWithSignature`
  is now `@Deprecated` with an honest notice (it never cryptographically verified
  the signature — real enforcement is `UpdateSignatureGate`), replacing the
  misleading `TODO: Implement PGP signature verification` stubs. New
  `docs/release-process.md` §13 documents the key, the out-of-band
  `gpg --verify` check, the enforcement roll-out and the rotation/escrow procedure.
- **Archive: a multi-format archive browser for the desktop** (`lg3d-apps`
  `org.jdesktop.lg3d.apps.archive`) — a new Utilities start-menu app that opens
  an archive, lists its entries, extracts it to a chosen folder and creates a new
  archive from a directory. The Swing `ArchivePanel` is hosted on a `SwingNode`
  inside a `Frame3D` via `TitledSwingWindow` in the 3D desktop and, registered in
  `Desktop2DAppRegistry.PANEL_APPS`, opens as an MDI internal frame in the 2D/Swing
  desktop — one panel, both desktops. Archive I/O runs off the EDT on a
  `SwingWorker` and is delegated to the AWT-free, path-traversal-hardened
  `ArchiveManager`, built on Apache Commons Compress so it handles **every common
  open format** rather than just ZIP: it reads ZIP, 7-Zip, TAR, TAR wrapped in any
  auto-detected compressor (`.tar.gz`/`.tgz`, `.tar.bz2`, `.tar.xz`, Z, LZMA),
  CPIO, AR and lone single-file compressor streams (`.gz`/`.bz2`/`.xz`), and
  creates ZIP, TAR, TAR.GZ, TAR.BZ2 and TAR.XZ (the format follows the output file
  name). RAR (proprietary) and Zstandard/Brotli (extra native codecs) are
  deliberately out of scope. Ships an in-tool `PackageBox` start-menu icon
  (`resources/images/icon/archive.png`) and headless `ArchiveManagerTest`
  multi-format round-trip + Zip-Slip / tar-slip guard coverage.
- **Software Update: apply a release bundle to the installed desktop (the LFS
  production path)** (`update-manager` `com.protonmail.landrevillejf.swingide.update`)
  — the update pipeline could already check, download and verify (SHA-256 +
  optional PGP) the published `lg3d-<version>.zip`, but the ported `UpdateInstaller`
  only knew how to replace a *single* running JAR (`cp new.jar current.jar` +
  `java -jar current.jar`), so it could never apply lg3d's multi-jar, classpath-launched
  bundle — the desktop could not actually be updated in place. A new
  `BundleUpdateInstaller` + `InstallLocation` close that gap: `InstallLocation`
  resolves the release-bundle root (the `update.install.dir` config key → the
  `lg3d.install.dir` system property → inferred from `<root>/lib/update-manager-*.jar`,
  validated by the `lib/` + `lg3d.sh` layout), and when a root is detected *and*
  the verified artifact is a ZIP, `UpdateService.installNow` /
  `applyStagedUpdateOnExit` route to the bundle installer instead of the single-jar
  one (which stays the fallback for a plain jar or a development launch, so nothing
  else changes). The bundle installer extracts the archive into a staging directory
  behind a zip-slip guard, validates the staged layout, then hands a deferred bash
  script the destructive work once the JVM has exited: snapshot the managed entries
  (`lib/ resources/ etc/ ext/ lg3d.sh README.txt VERSION`) into a timestamped
  rollback directory that carries a standalone `restore.sh`, replace them with the
  staged tree, and optionally relaunch `lg3d.sh`. A read-only install prefix (the
  common distro case) is applied under `pkexec` when privilege escalation is
  enabled. Relaunch defaults to **off** (`update.bundle.relaunch=false`): under the
  LFS deployment lg3d *is* the X session started by systemd/xinit, so the session
  manager restarts it and an in-app `System.exit` must not try to relaunch the
  session itself; a windowed user can opt in. **New headless JUnit 5 tests**:
  `InstallLocationTest` (property override, jar-path inference, invalid/blank/
  throwing inputs, layout validation), `BundleUpdateInstallerTest` (staging,
  zip-slip rejection, magic-header detection, layout validation, apply/restore
  script generation with and without relaunch, both `pkexec`/`bash` launch-command
  branches, accessors) and `UpdateServiceBundleInstallTest` (bundle vs single-jar
  routing, the config-driven install dir, the relaunch flag and installer
  injection); the destructive `System.exit` / real `pkexec` spawn stay overridden,
  so no test kills the JVM or touches a real install. See
  `update-manager/AGENTS.md`.
- **Software Update: an end-to-end release-bundle install proof + the LFS
  acceptance runbook** (`scripts/update`, `docs`, `update-manager`) — the
  `BundleUpdateInstaller` unit tests deliberately stub the two destructive steps
  (the real `pkexec` / `System.exit` spawn), so nothing yet proved the *whole*
  chain against a real archive. A new `scripts/update/bundle-install-proof.sh`
  closes that gap: it runs the real installer (taken from the release bundle's own
  `lib/`) against the real `:lg3d-core:releaseBundle` `lg3d-<version>.zip` in a
  throwaway sandbox — stage → snapshot → deferred apply script → verify the NEW
  tree (`VERSION`, the full `lib/` jar set, the stale marker gone) → `restore.sh`
  rollback — for both `installBundle` and `installBundleOnExit`. It never touches a
  real install, never escalates (`escalationEnabled=false`) and never relaunches
  (`relaunchEnabled=false`). `docs/lfs-x11-contract.md` gains a new **§7 "In-place
  update of the lg3d desktop"** (how the bundle is applied, install-root resolution,
  the normative "relaunch stays off under LFS" rule, rollback, and the §7.5
  acceptance gate that runs this harness to PASS); the README's LFS deployment
  section and `update-manager/AGENTS.md` (QA) now point at it.
- **Web Browser: a developer extension/plugin API (Java SPI), an in-browser
  manager, built-in reference extensions, browser-completeness upgrades and a
  proper window icon** (`lg3d-apps` `org.jdesktop.lg3d.apps.webbrowser` + a new
  `...webbrowser.ext` SPI package) — the JavaFX/WebKit browser gains a
  first-class extension system. `BrowserExtension` is the SPI (all-default
  hooks: `onNavigate`, `onPopup`, `onPageLoaded`, `onBrowserStarted/Stopping`,
  `toolbarContributions`), published with immutable value types
  (`ExtensionManifest`, a `Permission` enum, `NavigationRequest`/`Decision`,
  `PopupRequest`/`Decision`, `PageContext`, `BrowserContext`,
  `ToolbarContribution`) that hold no AWT/JavaFX reference, so extensions are
  headless-testable. `ExtensionRegistry` discovers built-ins through
  `ServiceLoader` (`META-INF/services`) *and* third-party jars dropped into
  `~/.lg3d/webbrowser/extensions` via a scoped child `URLClassLoader`; a
  third-party extension starts **disabled with nothing granted** until the user
  approves it in the new **Extensions** manager dialog (`ExtensionManagerDialog`:
  enable/disable, an explicit permission-grant gate, per-extension permission
  editing, rescan, open-folder), and the enable/grant state persists as
  `extensions.json` through `BrowserStore`. `ExtensionBroker` dispatches every
  hook behind the granted-permission gate and isolates failures (a throwing
  extension is logged and skipped, never fatal); `FxBrowser` consults it for the
  popup handler (a `POPUP` veto returns `null`, suppressing the window),
  `loadInternal` (a `NAVIGATE` BLOCK skips the load, a REDIRECT loads the
  target) and `Worker.State.SUCCEEDED` (the `onPageLoaded` content-script point,
  whose script runner is live only for a `CONTENT_SCRIPT` grant). Three built-in
  reference extensions ship in-tree and exercise the same SPI: **Popup Blocker**
  (blocks non-user-initiated popups), **Tracker Blocker** (vetoes a bundled
  tracker/ad host list + hides common ad containers) and **HTTPS Upgrade**
  (redirects http→https except local hosts). Browser completeness: window-level
  shortcuts (Ctrl+T/W/L/R/F, Ctrl+Tab / Ctrl+Shift+Tab), a per-tab close button
  in the strip, and Settings **Clear history** / **Clear cookies** privacy
  actions. The standalone/child-process `WebBrowserApp` frame now sets its icon
  (`setIconImage` from the assembled `resources/images/icon/webbrowser.png`),
  fixing the bare-`JFrame` OS-default "home folder" glyph; the 2D MDI frame and
  taskbar already resolve the descriptor icon. The security model is documented
  honestly as a **consent/UX boundary, not a JVM sandbox** (in-JVM code is
  trusted). **New headless JUnit 5 tests**: `ExtensionManifestTest`,
  `ExtensionRegistryTest` (classpath + temp-dir jar discovery, the permission
  gate, enable/grant persistence), `ExtensionBrokerTest` (dispatch, exception
  isolation, permission enforcement, block/redirect decisions, content-script
  gating, toolbar de-dup), `BrowserStoreExtensionsTest` (extensions.json
  round-trip + defensive reads) and `PopupBlocker`/`TrackerBlocker`/
  `HttpsUpgradeExtensionTest`. See `docs/webbrowser-extensions.md`.
- **Web Browser: a dedicated release workflow for the extension API jar**
  (`lg3d-apps` build + `.github/workflows/release-webbrowser-api.yml`) — the
  `org.jdesktop.lg3d.apps.webbrowser.ext` SPI can now be published on its own, so
  third-party extension developers compile against a small, stable
  `lg3d-webbrowser-ext-api-<version>.jar` (plus a `-sources.jar`) instead of the
  whole `lg3d-apps`. Two on-demand Gradle tasks (`:lg3d-apps:webBrowserApiJar` /
  `:webBrowserApiSourcesJar`) package exactly the developer contract — the
  `BrowserExtension` interface and its immutable value types — excluding the
  browser's internal runtime (`ExtensionRegistry` / `ExtensionBroker` /
  `ExtensionState`) and the `ext.builtin` reference implementations. A new manual
  (`workflow_dispatch`) workflow builds both jars and publishes a **dedicated**
  GitHub Release tagged `webbrowser-api-v<version>` with the jar, sources and a
  `SHA256SUMS.txt`; that tag namespace deliberately does not match the desktop
  bundle's `v*` trigger, so the two release lines stay independent, and a
  `-dev`/`-rc` version publishes as a pre-release (never `releases/latest`).
  Verified locally: the produced jar is self-consistent (a third-party
  `BrowserExtension` compiles against it alone) and `-PreleaseVersion` stamps the
  version.
- **Advanced Search: a streaming file &amp; content finder bound to Ctrl+Shift+F**
  (`lg3d-core` `org.jdesktop.lg3d.utils.search` +
  `...displayserver.desktop2d`; `lg3d-apps` `org.jdesktop.lg3d.apps.search`) — a
  new production search utility for the desktop and the minimal LFS system, with
  no external indexer dependency (it works on a bare X11/Wayland session with only
  a JVM). A new headless engine (`SearchEngine` + an immutable `SearchQuery`
  builder) walks one or more roots with a fixed pool of daemon workers sized to
  the CPU count and *streams* each hit to the caller the moment it is found, so
  results appear live instead of after the whole tree is scanned. It supports
  name matching in three modes (case-sensitive substring, shell glob and regex,
  each compiled once and shared read-only across workers), an optional content
  grep that detects binaries by a NUL sniff and skips huge files, and type /
  min-max size / modified-within-days filters, with a hard result cap and a
  cancel handle (`SearchHandle`) so a runaway walk can be stopped. Every hit is
  graded for relevance (exact &gt; prefix &gt; mid-string name match, shallower path,
  recency, number of content hits) and the UI re-ranks best-first on completion.
  Robustness first: unreadable directories, permission errors, dangling symlinks
  and vanished files are skipped rather than thrown, symlinked folders are never
  descended into (so loops cannot hang the walk), and an invalid glob/regex
  matches nothing instead of crashing. The `SearchPanel` UI uses only
  SwingNode-safe widgets (JList selectors, plain JButtons, JTextField, JTable,
  JTextArea) per the offscreen rule, shows a content-preview pane with matching
  line numbers and snippets, and opens a hit or its containing folder through the
  shared `Opener`. It is a Start-Menu &rarr; Utilities app (`search.lgcfg`, a new
  magnifier `search.png` icon) registered as a hosted panel in
  `Desktop2DAppRegistry` (so the same panel serves the 2D MDI frame and the 3D
  `SwingNode` window), and is wired to a new desktop-wide `Ctrl+Shift+F`
  accelerator via `ShortcutMap.SEARCH` / `Shortcuts.Target.search()`.
- **System-tray indicators: volume + network mirrored into the host tray (2D
  desktop)** (`lg3d-core` `org.jdesktop.lg3d.displayserver.desktop2d` +
  `...utils.prefs`; `lg3d-apps` `...controlcenter`) — the master-volume and
  network-link indicators can now be mirrored into the host
  `java.awt.SystemTray` as real tray icons, opt-in from the control center, so
  the 2D desktop can surface live volume/link glyphs in the desktop environment's
  own tray rather than only in the lg3d taskbar. A new `IndicatorIcons` factory
  draws the glyphs procedurally with `Graphics2D` into a transparent
  `BufferedImage` (the same offscreen technique `NotificationTray.badgedIcon`
  uses) because `IconManager` ships a static `Volume` GIF but no network / wi-fi
  / mute artwork and a tray icon has to reflect live *state*: a speaker cone with
  zero-to-three sound waves scaled to the level (or a red mute cross), and a
  wi-fi fan / RJ45 ethernet plug drawn grey with a red slash when offline. A new
  `SystemTrayBridge` polls the existing `VolumeStatus`/`NetworkStatus` platform
  seams (1 s volume, 5 s network) and pushes each reading into a `TrayIcon`'s
  image and tooltip; it degrades gracefully — when the host has no system tray
  (`SystemTray.isSupported()` false, the common case on GNOME/Wayland, which
  dropped the XEmbed tray protocol) or the JVM is headless, every operation is a
  no-op. `Desktop2D` owns the bridge lifecycle, building/tearing it down from
  `reapplyConfig()` so a control-center toggle takes effect live, and stopping it
  in `exit()`. Two new `DesktopConfig` prefs persist the choice — an
  `indicators.systemTray` master enable (`isIndicatorsSystemTray()`/
  `setIndicatorsSystemTray()`, off by default) and a per-indicator
  `indicator.show.<NAME>` visibility over a new `Indicator` `{VOLUME, NETWORK}`
  enum (`isIndicatorShown()`/`setIndicatorShown()`, all shown by default), both
  wired through load/save/reset. The Desktop control-center panel gains a **System
  tray indicators** block (a mirror Off/On list plus a Volume/Network shown/hidden
  toggle) using JList selectors throughout, per the offscreen `SwingNode` rule.
  The lg3d **taskbar is unchanged** — this is an additional host-tray mirror, not
  a rework of the in-bar indicator cluster. **New headless JUnit 5 tests**:
  `IndicatorIconsTest` (ARGB size/type, painted-pixel presence per state, size
  clamping, null level/kind safety, Icon wrappers), `DesktopConfigIndicatorsTest`
  (defaults, round-trips, independence, null-safety, reset, save/load persistence)
  and `SystemTrayBridgeTest` (null-tray no-op lifecycle, support parity, and the
  pure image/tooltip helpers), none of which write real user prefs or need a
  display.
- **Desktop Customization: colour themes + accent (2D desktop)** (`lg3d-core`
  `org.jdesktop.lg3d.displayserver.desktop2d` + `...utils.prefs`; `lg3d-apps`
  `...controlcenter`) — a new **Customization** control-center category (registered
  right after Appearance) that gathers the personalisation controls that go
  *beyond* the wallpaper on the conventional 2D desktop, so they live together
  instead of being buried in Appearance. The existing Metal-theme manager **moves**
  out of `AppearancePanel` into a new `CustomizationPanel` (Appearance keeps the
  wallpaper + slideshow on 2D and the window-glass/rounded selector on 3D); the new
  panel is gated on `Desktop2D.MODE_PROPERTY` and degrades to a "2D desktop only"
  note on the 3D shell, and uses JList selectors throughout (never a combo box,
  per the offscreen `SwingNode` rule). Three new built-in `MetalThemeSpec` palettes
  ship alongside the existing six — **Midnight** (deep navy), **Rosewood**
  (reddish-brown) and **Sand** (warm beige). A new **"Accent colour…"** button
  recolours the whole shell from a single picked colour via
  `MetalThemeSpec.fromAccent(...)`, and the accent is persisted separately as a new
  `DesktopConfig` pref `theme.accent` (`getAccentColor()`/`setAccentColor()`, stored
  `#rrggbb`, default `""` = derive from the active theme; normalised + wired through
  load/save/reset). `MetalThemeManager.accentColor()` resolves the persisted accent
  or the active theme's `primary2` (falling back to Steel), the seam the upcoming
  window-decoration styling will read. Defaults keep today's behaviour, so nothing
  changes until the user opts in. **New/extended headless JUnit 5 tests**: extended
  `MetalThemeSpecTest` (the three new built-ins populated, the exact `builtIns()`
  list, and `accentColor()` resolution) plus a new `DesktopConfigAccentTest`
  (blank default, round-trip + lower-casing, malformed/null/blank rejection, reset),
  none of which write real user prefs.
- **Desktop Customization: icon packs (2D desktop)** (`lg3d-core`
  `org.jdesktop.lg3d.displayserver.desktop2d` + `...utils.prefs`; `lg3d-apps`
  `...controlcenter`) — the Customization panel gains an **Icon Pack** section that
  re-themes the 2D desktop's application icons (start menu, quick-launch strip,
  taskbar and window-frame icons) without touching the wallpaper or the theme. A
  new pure model, `IconPack`, resolves one PNG by base name from a *bundled*
  classpath pack (`resources/images/icon-packs/<id>`), an *imported* folder of PNGs
  or an imported `.zip`, scaling synchronously to the requested edge; a new
  `IconPackManager` lists what is available (default + discovered bundled packs +
  the imported pack), picks the persisted active pack (falling back to default when
  it disappears) and returns a single override for an app, keyed first by the
  descriptor icon's base name (e.g. `mail3d.png`) then by an app-name slug (e.g.
  `Mail 3D` → `mail-3d.png`) so procedurally-generated icons can be overridden too.
  `AppIcons.iconFor(...)` now consults that override **first** and adds
  `clearCache()`, so a pack replaces only the icons it ships and everything else
  keeps its generated art. Two bundled packs ship — **Mono** (neutral greyscale)
  and **Vivid** (saturation/contrast boost) — rendered from the committed app icons
  by the new manual `lg3d-art/tools/GenerateIconPacks` tool and committed under
  `lg3d-core/src/resources/images/icon-packs/`. Two new `DesktopConfig` prefs,
  `icon.pack` and `icon.packDir`, persist the selection (normalised + wired through
  load/save/reset), and `Desktop2D.applyIconPack(...)` applies it live by clearing
  the icon cache, rebuilding the start menu, refreshing the taskbar
  (`Desktop2DTaskbar.refreshIcons()`) and re-setting open windows' frame icons
  (`Desktop2DWindow.refreshFrameIcon()`). Defaults keep today's behaviour, so
  nothing changes until the user opts in. **New/extended headless JUnit 5 tests**:
  a new `IconPackManagerTest` (bundled discovery, import from a temp folder *and* a
  temp zip, base-name + slug keys, active-pack exclusivity, fallback to default,
  path helpers) and `DesktopConfigIconPackTest` (blank defaults, round-trip,
  null/reset), plus an extended `AppIconsTest` (override precedence + `clearCache`),
  none of which write real user prefs.
- **Desktop Customization: create icon packs from IconManager glyphs (2D
  desktop)** (`lg3d-core` `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps`
  `...controlcenter`) — the Icon Pack section gains **Create Pack…** and **Delete
  Pack**, so the user can *compose* a pack from the desktop's own bundled
  IconManager glyph library rather than only choosing between the built-in
  Mono/Vivid packs or importing PNGs. A new modal `IconPackBuilderDialog` lists the
  themeable applications down the left (from the same start-menu descriptors the
  2D menu is built from) and the glyph catalogue — category `JList` driving a
  live-preview glyph `JList` — on the right; the user assigns one glyph per app and
  saves under a name. `IconPack` gains a `Source.USER` and `IconPackManager` gains
  user-pack discovery + persistence: packs are written as PNGs into named folders
  under `~/.config/lg3d/icon-packs/<id>` (`saveUserPack`/`deleteUserPack`, id
  sanitised from the name), discovered by scanning that root (`userPacks()`), and
  listed by `available()` alongside the default, bundled and imported packs, so
  several user packs coexist and the persisted `icon.pack` selects any of them with
  no new preference. A new never-throw `IconGlyphLibrary` bridges IconManager for
  the builder (category/glyph enumeration, `MissingIcon`→null, bundled-edge load +
  resize, and rendering a glyph to a `BufferedImage` at `PACK_ICON_EDGE` for
  saving), degrading to an empty catalogue when the jar is absent; the app
  catalogue is exposed as `IconPackManager.appIconTargets()`, keyed by the
  descriptor icon's base name else the app-name slug. All choice controls are
  `JList` selectors per the offscreen `SwingNode` rule, and defaults are unchanged
  so nothing themes until the user builds and applies a pack. **New/extended
  headless JUnit 5 tests**: a new `IconGlyphLibraryTest` (category/label
  enumeration, size-suffix stripping, blank/unknown-glyph → null, real-glyph
  load + render) plus seven more `IconPackManagerTest` cases (`sanitizePackId`,
  the `userRoot` override, save-and-discover a USER pack + resolve its override,
  blank-name/empty-icon rejection, delete, `targetFor` base-name/slug keys and
  `appIconTargets` safety), none of which write real user prefs.
- **Desktop Customization: icon-pack studio — style packs with the full
  IconManager vocabulary (2D desktop)** (`lg3d-core`
  `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps` `...controlcenter`) —
  the Create Pack… builder becomes a full **icon studio** that drives the bundled
  IconManager library to the extent of its API instead of only dropping a raw
  glyph per app. A new immutable `IconRecipe` (+ `Builder`, `toBuilder`,
  `forApp`) describes one icon end-to-end: a `Source` (the bundled `GLYPH` *or*
  any generative IconManager tile — `GLASS`, `COLOR`, `GRADIENT`, `CIRCULAR`,
  `NEUMORPHISM`, `TEXT`, `PATTERN`, `STATUS`) plus an appearance chain (tint
  colour + strength, gradient accent, `IconStyle`, `PatternType`, `StatusType`,
  the six `IconEffect` post-processors, rounded-corner radius, drop shadow,
  brightness/contrast, rotation and horizontal/vertical flip), with seven
  `Preset` templates (flat, mono tint, glass, gradient, neumorphism, rounded
  shadow, sepia vintage). `IconGlyphLibrary` gains a never-throw
  `render(recipe,size)`/`renderIcon` pipeline that builds the source then runs
  each appearance step individually guarded (a single unsupported effect degrades
  rather than blanks the icon), plus the vocabulary accessors the studio lists
  (`colors`/`styles`/`effects`/`patterns`/`statuses`, `color(IconColor)`,
  `label(Enum)`) and a catalogue-wide `searchGlyphs(query)`. The rewritten
  `IconPackBuilderDialog` adds a searchable glyph catalogue and a style studio
  (preset/source/colour/style/pattern/status/effect `JList`s + tint/corner/
  brightness/contrast/rotate sliders and shadow/flip toggles) with a large live
  preview; one coherent pack style is stamped onto every assigned app's glyph at
  save. All choice controls stay `JList` selectors per the offscreen `SwingNode`
  rule, and defaults are unchanged. **New headless JUnit 5 tests**: a new
  `IconRecipeRenderTest` (builder defaults + clamping, defensive effect set,
  `forApp`/`toBuilder` immutability, preset templates, every generative source,
  plain/tinted/gradient glyph, the full appearance chain, enum vocabularies and
  search) — none of which write real user prefs.
- **Default Applications: per-file-type application associations (2D desktop)**
  (`lg3d-core` `org.jdesktop.lg3d.utils.prefs` + `...utils.system` +
  `...displayserver.desktop2d`; `lg3d-apps` `...controlcenter`) — the user can now
  configure **which application opens which kind of file** (for example the
  desktop's own PDF Viewer opens `.pdf`), and the choice is honoured everywhere a
  file is opened. A new `Preferences`-backed `FileAssociations` model maps a
  canonical type key (`ext:<ext>` or `mime:<type>`) to a handler command written
  in the start-menu vocabulary (`java <class>` / `swingapp <class>` / an external
  executable) with a `%f` file-path token, one pref key per association (prefix
  `assoc.`) so a command containing `;`/`=` cannot corrupt the store; it ships
  ~38 curated common types plus an extension→MIME fallback, resolves a path's
  handler by extension first then MIME, and exposes pure helpers
  (`normalizeTypeKey`/`extensionKey`/`mimeKey`/`expand`/`mimeTypeOf`/`labelFor`).
  `Opener.open(Path)` — the single seam every caller already routes through (file
  manager, desktop Documents/Downloads folder menus, dock stacks) — now consults
  the association before falling back to `xdg-open`, and a new
  `Opener.openWith(command,path)` runs an explicit handler: an *internal*
  application is offered to a new `FileAssociationLauncher` callback (registered
  by `Desktop2D` at start-up, cleared on exit, so `utils.system` keeps no
  compile-time dependency on the Swing desktop) that hosts the app's panel and
  hands it the document via a new reflective `Desktop2DAppRegistry.openFile`
  (`openFile(File)`/`openFile(Path)`), while anything else runs as an external
  child process through `ProcessRunner` with `%f` expanded into an argv vector
  (no shell, so spaces are safe). A new **Default Applications** control-center
  panel (`FileAssociationsPanel`, registered right after Quick Launch) lists the
  file types on the left and the desktop's installed applications on the right,
  with **Open with this** / **Custom command…** / **Use system default** and an
  **Add type…** affordance for a custom extension, using `JList` selectors
  throughout per the offscreen `SwingNode` rule. Defaults are unchanged, so a
  file type keeps its `xdg-open` behaviour until the user opts in. **New/extended
  headless JUnit 5 tests**: a new `FileAssociationsTest` (14 cases — the pure key
  vocabulary, `%f` expansion shapes, common-type well-formedness, MIME fallback,
  the persisted round-trip, extension-beats-MIME precedence and null/blank
  safety, snapshotting and restoring every key it touches so a developer's real
  `.pdf` handler is never clobbered) plus four more `Desktop2DAppRegistryTest`
  cases pinning the reflective `openFile` (File hook, Path fallback, a declined
  result, null/no-hook safety).
- **Taskbar: icon-only tray + fully configurable contents (2D desktop)**
  (`lg3d-core` `...utils.prefs` + `...displayserver.desktop2d`; `lg3d-apps`
  `...controlcenter`) — the taskbar's tray no longer spells out its buttons:
  **Documents**, **Downloads** and **Notifications** now render as **icon only**,
  with the descriptive text moved to the hover tooltip (and the notification
  unread count kept at-a-glance as a small badge composited onto the glyph, plus
  in the tooltip), and the same icon-only treatment applies to **Start**. The
  whole bar is now **100% configurable from the Control Center**: `DesktopConfig`
  gains a per-item visibility set (`taskbar.show.<ITEM>` for Start, quick-launch
  strip, Documents, Downloads, workspace pager, system indicators, Notifications,
  clock and Exit - each independently hideable, all shown by default) and a
  button label style (`taskbar.labels`, `ICONS_ONLY` default / `ICONS_AND_TEXT`),
  wired through load/save/reset. `Desktop2DTaskbar.applyConfig()` honours both
  live - hidden pieces are removed from the flow layout so the bar reclaims their
  space - and the **Desktop** control-center panel gains a *Taskbar buttons*
  editor (a `JList` of the fixed buttons with a shown/hidden toggle, plus an
  *Icons only / Icons and text* selector) alongside the existing thickness /
  position / icon-size / auto-hide / font controls, so every aspect of the bar is
  editable in one place. Defaults keep every piece visible, only the label style
  changes (icon-only), so nothing disappears until the user opts in. **New/
  extended headless JUnit 5 tests**: a new `DesktopConfigTaskbarContentsTest`
  (all-shown + icon-only defaults, per-item round-trip and independence, null
  safety, label round-trip, reset) plus extended `NotificationTrayTest` cases
  (icon-only default hides the text and badges the glyph, the tooltip wording,
  and `badgedIcon` returning the base glyph when there is nothing unread), none
  of which write real user prefs.
- **Sound: master volume slider + output-device management (2D desktop)**
  (`lg3d-core` `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps`
  `...controlcenter`) — the Control Center **Sound** panel is now a complete
  mixer instead of a decile preset list: the master volume is a **drag
  `JSlider`** (0-100, live percentage read-out, applied on release so dragging
  does not fire a CLI call per pixel) beside the mute toggle, and below it an
  **Output devices** `JList` *shows and manages the audio interfaces* — every
  sink/card with its description, volume, `[muted]` and `[default]` markers, with
  **Set as Default** to switch the active output and **Refresh** to re-enumerate.
  The slider (not a combo box, whose heavyweight popup cannot be hosted
  offscreen) works on both desktops because `SwingNode`'s renderer forwards drag
  events to the hidden frame. `VolumeStatus` gains the device seam behind it:
  a `Device(id,description,isDefault,percent,muted)` record, `devices()`
  enumerating most-authoritative-first (**PipeWire** `wpctl status` →
  **PulseAudio** `pactl list sinks` → **plain ALSA** `aplay -l`, the Linux From
  Scratch / minimal case), `setDefault(id)` and per-device `setVolume(id,pct)` /
  `setMuted(id,bool)`, plus a `deviceLabel(Device)` formatter. The parsers
  (`parseWpctlSinks`/`parsePactlSinks`/`parseAlsaCards`), the label and the
  per-backend device command arrays are pure, and every probe degrades to an
  empty list / no-op when no tool exists (headless CI, no sound card), so the
  panel simply disables the switch button. **New/extended headless JUnit 5
  tests**: seven more `VolumeStatusTest` cases (the three device parsers,
  `deviceLabel`, the exact device command arrays and a devices()/per-device
  mutator no-throw guard) and a rewritten `SoundPanelTest` asserting the panel
  hosts a 0-100 `JSlider` and an output-device `JList` — none of which invoke a
  real audio tool or touch a sound card.

### Fixed
- **JOGL's `AppContextInfo` reflection warning is gone from the dev-mode
  console log** (`lg3d-core`, the `:lg3d-core:run` task) — JOGL's AWT/JAWT
  bridge (exercised when attaching the GL layer on macOS) reflectively calls
  `sun.awt.AppContext.getAppContext()` via `setAccessible(true)`. JDK 21's
  strong `java.desktop` encapsulation let the existing `--add-exports
  java.desktop/sun.awt.image=ALL-UNNAMED` through for direct access but not
  for that *reflective* `setAccessible` on the (different) `sun.awt` package,
  which needs it *opened*, so every launch logged an
  `InaccessibleObjectException`/`IllegalAccessException` stack trace from the
  `J3D-Renderer` and `AppKit` threads. The `run` task now also passes
  `--add-opens java.desktop/sun.awt=ALL-UNNAMED` (a superset of the existing
  export, harmless on every platform) alongside it.
  **Note:** an attempt in this same change to also force real exclusive
  full-screen on macOS via `GraphicsDevice#setFullScreenWindow` was reverted
  before merging — forcing an already-realized `Canvas3D`'s native GL surface
  into AWT exclusive full-screen mode is **not safe to do after the window is
  shown** and broke the running desktop in manual testing. The underlying
  "dev-mode `NoBorderFullScreen` doesn't cover the real screen on macOS" issue
  (menu bar / Dock painting over the undecorated frame) is **still open** and
  needs a safer fix verified with an attached display, not headless log
  checks.
- **Web Browser: WebSocket pages no longer spam `UnsatisfiedLinkError` stack
  traces (2D desktop + standalone)** (`lg3d-apps`
  `org.jdesktop.lg3d.apps.webbrowser`) — the Linux WebKit native library shipped
  with JavaFX 21 (`libjfxwebkit.so`) omits the JNI implementations of
  `com.sun.webkit.network.SocketStreamHandle.twkDidOpen`/`twkDidClose`, so any
  page that opens a **WebSocket** threw `UnsatisfiedLinkError` on the JavaFX
  Application Thread from inside JavaFX's own networking callback — far from any
  `try/catch` the app controls — printing a full stack trace on every socket event
  that read like a crash (upstream **JDK-8346250**; verified the loaded `.so`
  exports neither the symbols nor their strings). The error is benign (the socket
  never opens; the page still renders and stays interactive), and a real fix needs
  JavaFX 24+, which requires JDK 22+ and is out of reach on this JDK-21-pinned
  desktop. A new scoped `WebKitThreadGuard`, installed on the FX Application
  Thread at the top of `BrowserPanel.bootJavaFx` (so it covers both the in-process
  2D host and the spawned standalone child process), recognises exactly that one
  error — an `UnsatisfiedLinkError` whose trace passes through `com.sun.webkit` —
  logs a single concise warning the first time and swallows it, while **every
  other throwable is re-dispatched untouched** so a genuine defect is never
  masked. **New headless JUnit 5 test** `WebKitThreadGuardTest` pins the
  classification (known vs unrelated link errors, other `LinkageError`s, null) and
  the swallow-vs-delegate behaviour without a display, toolkit or network.

- **Sound: master volume now works on PipeWire, PulseAudio and plain-ALSA hosts
  (2D desktop)** (`lg3d-core` `org.jdesktop.lg3d.displayserver.desktop2d`) — the
  Control Center **Sound** panel, the taskbar volume indicator and the
  system-indicators widget all read the master volume through the `VolumeStatus`
  seam, which only ever probed the `javax.sound.sampled` master `Port`. On a
  modern Linux audio stack that port is either absent (PipeWire's ALSA
  compatibility layer exposes PCM playback but **no** `MASTER_GAIN`/`MUTE`
  control, so the JDK's Java Sound provider finds nothing) or reports a stale
  ALSA view that does not track the real sink volume, so the panel wrongly
  degraded to "No audio device (master volume unavailable)" and Apply/Mute were
  disabled **even though audio worked fine**. `VolumeStatus` now tries the
  host's native audio tooling most-authoritative-first — **PipeWire** via
  `wpctl`, then **PulseAudio** via `pactl`, then **plain ALSA** via `amixer`
  (the Linux From Scratch / minimal case with no sound server, driving the card
  directly through `alsa-utils`; `Master` then `PCM` controls) — and only falls
  back to the Java Sound `Port` when none of those tools exist (macOS, Windows,
  a bare JDK). The Java Sound fallback's port filter is also **fixed**: it
  previously required `Port.Info.isSource()` (a *capture* port), but a master
  *output* gain lives on a *target* (playback) port, so the filter inverted the
  match and never found the control even on hosts that expose one; it now scans
  target ports and picks the first that supports `MASTER_GAIN`/`MUTE`. Reads
  parse the tools' real output (`Volume: 1.40 [MUTED]`,
  `front-left: 91749 / 140% / 8.77 dB`, `[100%] [on]`), percentages are clamped
  to 0-100, and writes route to the first backend that succeeds; every probe
  still degrades to empty / no-op when no tool and no port exist (headless CI,
  no sound card). The parsing, the dB↔percentage maths and the per-backend
  command arrays are pure and unchanged in signature, so no caller
  (`SoundPanel`, `TaskbarIndicators`, `SystemIndicatorsCard`) needed edits.
  **New headless JUnit 5 tests** extend `VolumeStatusTest` with the
  `wpctl`/`pactl`/`amixer` parsers (scale, mute marker, over-100% clamping,
  malformed/null rejection), the exact backend command arrays, and a guard that
  the mutators no-op without throwing when no backend is present — none of which
  invoke a real audio tool or touch a sound card.

- **Control Center opens instantly, and the taskbar settings are discoverable (2D
  desktop)** (`lg3d-apps` `...controlcenter`) — the control center used to build
  *all* 21 category panels inside its constructor before the window was shown, and
  19 of them block on platform I/O while they build (Network / Bluetooth /
  Printing / Date & Time / Language & Region / Mouse & Keyboard shell out to
  `nmcli` / `bluetoothctl` / `rfkill` / `lpstat` / `timedatectl` / `localectl` /
  `xset`, Appearance scans the wallpaper folder and decodes thumbnails, System
  reads `/proc`, Desktop enumerates every font), so the window stayed blank and
  apparently frozen for seconds. `ControlPanelRegistry` now registers each category
  as a lazy `PanelDescriptor` (name + memoised factory) and `ControlCenterPanel`
  populates its navigation from those names immediately, constructing a panel **on
  demand** the first time its category is selected — behind a progress overlay (an
  indeterminate bar plus a "Loading <category>" status line) that is painted
  *before* the blocking build (deferred one EDT tick via a one-shot `Timer`), so the
  user always sees which category is loading instead of a frozen window; a category
  that cannot be built in this JVM degrades to a graceful "unavailable" card rather
  than taking the whole control center down. The Desktop panel's *Taskbar buttons*
  editor also moves to the **top-left** of the panel (it was a second row that fell
  below the fold of the 720x500 window, which is why the taskbar settings looked
  absent). **New headless JUnit 5 test**: `ControlPanelRegistryTest` (descriptors
  list every category without building it, construction is lazy and memoised, and a
  build failure degrades to `null`), which writes no real user prefs.

## [1.53.1] — 2026-10-06 — Gradle / JDK 21 modernization

### Added
- **X11 compositor: shared pixel pipeline (foundation for native apps inside the
  2D desktop)** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.nativewindow.x11`) — refactored the
  compositor's pixel readback into a **single source feeding two sinks**, so the
  same redirected-window readback can drive the existing 3D `NativeWindow3D`
  texture *and* an upcoming Swing panel hosted **inside** a `Desktop2DWindow`
  (making a native X11 application appear within the 2D desktop instead of
  escaping as a separate top-level window). `CompositeWindowImageLoader` now
  `implements WindowPixelSource` (a `readRegion(x,y,w,h)` contract over the
  Composite `NameWindowPixmap`) and its Z-pixmap scanline assembly was extracted
  **verbatim** into a pure, `Display`-free static `decodeZPixmap(...)` — the one
  decoder both sinks read through. A new `CompositedWindowSink` interface
  (`present(region,x,y,w,h)` plus `resized`/`dispose` defaults) and a
  `CompositedWindowPipeline` (implements `X11Compositor.DamageListener`; on a
  damage report it clamps negative origins, reads the region and presents it, and
  forwards resize/dispose) form the presentation-agnostic spine that holds no
  `gnu.x11.Display` and no Java 3D reference, so the whole dispatch decision is
  unit-testable headlessly with a fake source and a fake sink. The refactor is
  **behaviour-preserving** for the live 3D tile path
  (`X11Client.setupCompositeImageSource` is untouched) and adds two documented
  hardenings to the decoder (empty/negative geometry and a null/short buffer now
  decode to `null` instead of throwing). **16 new headless JUnit 5 tests**
  (`CompositeWindowImageLoaderDecodeTest` and `CompositedWindowPipelineTest`,
  taking the X11 package to 94) pin the scanline assembly (32/24/16-bpp, LSB/MSB,
  stride/padding, truncation, the geometry/bpp/null guards) and the pipeline
  (read-then-present, origin clamping, empty/null short-circuits, resize/dispose
  forwarding, constructor null-checks, sink default no-ops). Roadmap §5 gains a
  **Phase B.5 — shared pixel pipeline** entry.
- **X11 compositor: 2D Swing sink + input forwarder (native apps shown inside
  the 2D desktop)** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.nativewindow.x11`) — the two ends the shared
  pixel pipeline needs to render a composited native window *inside* the
  conventional 2D desktop. **`SwingCompositedWindowSink`** is the 2D
  `CompositedWindowSink`: it keeps a full-window `BufferedImage` canvas, blits
  each damage region into it at its window offset (under a lock shared with the
  painting surface so a blit never tears), repaints only the damaged rectangle,
  reallocates-and-preserves on `resized`, and drops the canvas on `dispose`; its
  `getComponent()` `JPanel` is what a `Desktop2DWindow` embeds, so a foreign X11
  window's pixels appear within the desktop instead of escaping as a separate
  top-level window. **`SwingX11InputForwarder`** is the 2D sibling of
  `X11InputForwarder`: it attaches AWT mouse/motion/wheel/key listeners to that
  component and re-injects the input into the real client window via XTest —
  because the Swing canvas is a 1:1 pixel copy, a component-local point maps
  straight to `(winX+px, winY+py)` with no scene-graph pick to invert, key
  mapping reuses the already-tested `X11InputForwarder.vkToKeysym`, and focus
  follows pointer-enter/press. **16 new headless JUnit 5 tests**
  (`SwingCompositedWindowSinkTest`, `SwingX11InputForwarderMappingTest`; X11
  package now 110) pin canvas accumulation/offset placement, resize-preserve,
  dispose, degenerate-size clamping, the headless paint path, and the forwarder's
  pure seams (`mapPanelToRoot` clamping, `mapAwtButton`, and the
  `keysyms_per_keycode`-aware `keysymToKeycode`) — all with no live
  `gnu.x11.Display`, XTest or Java 3D. Roadmap §5 gains a **Phase B.6 — 2D Swing
  sink** entry.
- **X11 compositor: 2D desktop host wiring (native windows as MDI windows)**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d` +
  `...nativewindow.x11`) — the desktop-side plumbing that turns a composited
  native X11 window into an ordinary MDI window **inside** the conventional 2D
  desktop. A Java-3D-free **`CompositedWindowHost`** seam (x11) speaks only in
  terms of a `JComponent` + lifecycle so the 2D shell package can consume it
  without dragging in the scene graph; its production impl
  **`X11CompositedWindowHost`** wires a `CompositeWindowImageLoader` source to a
  `SwingCompositedWindowSink` through a `CompositedWindowPipeline`, registers the
  pipeline as the window's `DamageListener`, primes it with a full-window read,
  and on `resized` re-issues `NameWindowPixmap` before re-reading — handling the
  *display* half only (input stays the window manager's glue via
  `SwingX11InputForwarder`, keeping the host free of `X11Client`).
  **`Desktop2DCompositorHost`** is the desktop-side controller: it owns the
  `windowId -> Desktop2DWindow` map and folds the compositor's map/resize/retitle/
  unmap/shutdown notifications into it (a re-map of a live window folds into a
  resize+retitle rather than a duplicate; unmap disposes the surface and closes
  the MDI window). It reaches X exclusively through the seam, so the whole
  lifecycle is unit-testable headlessly: **8 new JUnit 5 tests**
  (`Desktop2DCompositorHostTest`, over a fake host + a fake opener building a real
  headless `Desktop2DWindow`) pin map/resize/retitle/remap-fold/unmap/disposeAll
  and the constructor null-checks. Roadmap §5 gains a **Phase B.7 — 2D desktop
  host** entry.
- **X11 compositor Phase C: multi-window focus & stacking model** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.nativewindow.x11`) — the pure brain for
  managing N simultaneous composited windows. **`CompositedWindowSet`** tracks the
  live window set, its sibling z-order (bottom→top) and the input focus under one
  of two policies — `POINTER` (focus-follows-pointer: `pointerEnter` moves focus
  without restacking) and `CLICK` (click-to-focus: focus moves only on an explicit
  `activate`, which also raises) — with `add`/`remove`/`raise`/`lower`/`retitle`/
  `activate`/`clear` and unmodifiable `stackTopDown()`/`windowsBottomUp()` views.
  It holds **no X and no Java 3D state**, so both the 3D desktop (ordering
  `NativeWindow3D` quads) and the 2D desktop can consult the same model.
  `Desktop2DCompositorHost` now maintains a `CompositedWindowSet` alongside its
  window map (a new 3-arg constructor takes the `FocusPolicy`; the 2-arg one
  defaults to `POINTER`) and exposes `focusWindow`, `pointerEnter`,
  `focusedWindowId`, `stackTopDown` and `getFocusPolicy`. **21 new headless JUnit
  5 tests** (`CompositedWindowSetTest` 13 + 4 more on `Desktop2DCompositorHostTest`)
  pin stacking order, focus-transfer-on-remove, raise/lower, both focus policies,
  and the controller's model sync. The live `X11WindowManager` InputOnly branch
  and X z-order still require a bare-Xorg session to validate (roadmap §5 Phase
  C).
- **X11 compositor Phase D: EWMH subset decision layer** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.nativewindow.x11`) — the pure, spec-correct
  brain behind the `_NET_WM_STATE` / `_NET_WM_ALLOWED_ACTIONS` /
  `_NET_ACTIVE_WINDOW` hints the compositor sets on every managed native window.
  **`NetWmState`** is a static function over enums and `EnumSet`s with **no
  `gnu.x11.Display` and no atom ids**, so the EWMH semantics are unit-testable
  headlessly: `applyChange` implements the REMOVE/ADD/TOGGLE client-message
  semantics (§5.9, null-safe, never mutates the input set), `defaultStateFor`
  derives a window type's initial state (dialog → `SKIP_TASKBAR`+`MODAL`;
  splash/utility/toolbar/menu → `SKIP_TASKBAR`; normal/desktop/dock → empty),
  `allowedActions` derives the action set from state + capabilities (CLOSE iff
  closable; MOVE always; RESIZE only when resizable and neither maximized nor
  fullscreen; MAXIMIZE_* only when maximizable and not fullscreen; MINIMIZE
  unless `SKIP_TASKBAR`; FULLSCREEN iff capable; ABOVE/BELOW mutually exclusive
  with the current state), and `decideActive` implements focus-stealing
  prevention (an application-initiated activation is refused → `DEMANDS_ATTENTION`;
  a pager/unspecified one is honoured → `ACTIVATE`). `X11WindowManagerHints`
  keeps the Display-bound half: its previously-stubbed `setNetWmState` /
  `setNetAllowedActions` (which hard-coded only the DIALOG case) now delegate to
  `NetWmState`, translating each enum to its atom name via the 1:1
  `stateAtomName`/`actionAtomName` convention and **filtering to the atoms the WM
  already advertises in `_NET_SUPPORTED`** so the property never claims an
  unsupported capability; a new `windowTypeFor(atomId)` maps the client's
  `_NET_WM_WINDOW_TYPE_*` id back to the enum, and the client-list / stacking
  half of EWMH is served by `CompositedWindowSet` (Phase C).
  **18 new headless JUnit 5 tests** (`NetWmStateTest`, taking the X11 package to
  140) pin the wire codes (`ChangeAction.fromCode`, `ActiveSource.fromCode`), the
  atom-name mapping, all three state-change actions plus null-safety, every
  window type's default state, each allowed-action rule branch (maximized /
  fullscreen / skip-taskbar / above-below), both focus decisions and the
  unmodifiable `readOnly` view — all with no live Display. Applying the hints to
  a real window still requires a bare-Xorg session to validate (roadmap §5 Phase
  D).
- **X11 compositor Phase E: damage coalescing, frame pacing & readback-path
  planner** (`lg3d-core`, `org.jdesktop.lg3d.displayserver.nativewindow.x11`) —
  the performance brain that stops the compositor re-reading and re-uploading
  unchanged pixels. Three pure, X-free/clock-free classes make the perf path
  unit-testable headlessly: **`DamageAccumulator`** coalesces the damage
  rectangles reported between frames into one bounding region (clamping negative
  origins, ignoring empty rects, counting merged rects, `drain()`ing to an
  immutable `Region`) so a frame does one readback + one texture upload instead
  of one per damage event; **`FramePacer`** throttles presents to a minimum
  interval (clock-injected via an explicit `nowMillis`, first present always due,
  interval 0 = always due, `tryAcquire` is side-effect-free on refusal); and
  **`ReadbackPlanner`** chooses the MIT-SHM fast path over a core `XGetImage`
  round-trip (SHM only when the extension is attached, shared pixmaps are
  supported and the region fits the segment) plus the ZPixmap `regionBytes` /
  `bytesPerPixel` sizing. **`CompositedWindowPipeline`** is wired to all three
  behaviour-preservingly: it now accumulates each `damageReported` and presents
  the coalesced region only when its pacer says one is due, with a new
  `flush(windowId)` (present any paced-out damage, e.g. once per rendered frame)
  and `hasPendingDamage()`; the existing 2-arg constructor delegates with an
  always-due pacer, so the live 3D tile path and all prior pipeline tests are
  unchanged. **29 new headless JUnit 5 tests** (`DamageAccumulatorTest` 9,
  `FramePacerTest` 8, `ReadbackPlannerTest` 8, +4 coalescing/pacing cases on
  `CompositedWindowPipelineTest`; X11 package now 169) pin the union/clamp/drain
  geometry, the throttle window and refusal semantics, every SHM-vs-XGetImage
  branch and the sizing math, and the pipeline's coalesce-until-flush behaviour.
  The live SHM segment attach/detach and measuring against a video-playing client
  still require a bare-Xorg session (roadmap §5 Phase E).
- **X11 compositor Phases F & G: GL/DRI3 bring-up planner + session lifecycle**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.nativewindow.x11`) — the pure
  decision brains for the two host bring-up phases, code-complete and headless-
  tested with the live proof (a real GPU GLX context; the `xinit`/systemd
  hand-off) deferred to the bare-Xorg/LFS target. **`GlBringUpPlanner`** (Phase F)
  turns a live GL probe (`glxPresent` / `directRendering` / `dri3Present` /
  resolved own-window id) into one `Verdict` — `READY`, `PIN_OWN_WINDOW_ID`
  (GL fine but lg3d's `Canvas3D` window unresolved → the operator must set
  `-Dlg3d.x11.ownwindowid` or the screen is black), `SOFTWARE_ONLY` (no DRI3/
  direct rendering) or `ABORT` (no GLX) — with the operator-facing `remediation`
  text and a `resolveOwnWindowId(override, discovered)` helper that codifies the
  override-then-discovery fallback `X11Compositor.exemptOwnWindow()` already
  implements; it encodes the `docs/lfs-x11-contract.md` §5 acceptance checks.
  **`SessionLifecycle`** (Phase G) is the session state machine for lg3d as the
  sole X session client — `INIT → STARTING → RUNNING → SHUTTING_DOWN → STOPPED`
  with `CRASHED`/`RECOVERING` branches, a bounded crash-recovery budget
  (`canRecover`), refused (not thrown) illegal transitions, and the ordered
  `shutdownSteps()` teardown list — and is **wired into `X11Compositor`**: the
  constructor advances it `START → READY` (or `FAILURE` if a required extension
  is missing) and `shutdown()` drives `SHUTTING_DOWN → STOPPED`, exposed via a
  new `getSessionState()` for a live session supervisor. **21 new headless JUnit
  5 tests** (`GlBringUpPlannerTest` 9, `SessionLifecycleTest` 12; X11 package now
  190) pin every verdict branch, the remediation/own-window resolution, and the
  full transition table incl. the recovery budget and refused transitions.
  Obtaining a hardware GLX/DRI3 context and exercising the systemd/`xinit`
  hand-off, clean shutdown and crash recovery still require a bare-Xorg session
  (roadmap §5 Phases F/G).
- **X11 compositor: WM → 2D-desktop lifecycle bridge — native apps now routed
  *into* the 2D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.nativewindow.x11` +
  `org.jdesktop.lg3d.displayserver.desktop2d`) — closes the last gap that kept a
  real external X11 application from appearing as an ordinary window inside the
  conventional 2D desktop. The pixel path (Phase B.5/B.6) and the desktop-side
  host controller (Phase B.7) both existed, but **nothing connected the window
  manager to the desktop**: `X11WindowManager` fired only the 3D texture path on
  Map/Unmap/Configure/PropertyNotify and never notified
  `Desktop2DCompositorHost`, so a redirected client had no route into a
  `Desktop2DWindow`. This adds that route. **`WindowLifecycleListener`** (x11) is
  a `gnu.x11`-free, Java-3D-free notification seam speaking only in window ids,
  titles and pixel sizes (`windowMapped`/`windowResized`/`windowRetitled`/
  `windowActivated`/`windowUnmapped`). **`X11WindowManager`** now holds a
  `volatile` listener (installed via `setWindowLifecycleListener`) and fires it
  from `mapNotify` (skipping lg3d's own window and InputOnly clients),
  `configureNotify` (resize), `propertyNotify` WM_NAME (retitle), `activate`
  (activation) and `unmapNotify`/`destroyNotify` (release); each callback is
  isolated in a try/catch so a desktop-side failure is logged and never kills the
  X event loop, and with no listener registered every fire is a no-op
  (behaviour-preserving). **`CompositedWindowBridge`** (desktop2d) implements the
  listener and forwards to a `Desktop2DCompositorHost`, marshalling each call onto
  the Swing EDT through an injectable `Consumer<Runnable>` (`SWING_EDT` in
  production, `DIRECT` in tests). The full chain a native app now travels is
  documented in roadmap §5 Phase B.8: X redirect → `CompositeWindowImageLoader` →
  `CompositedWindowPipeline` → `SwingCompositedWindowSink` → `Desktop2DWindow`
  content pane, with the lifecycle driven WM → `WindowLifecycleListener` →
  `CompositedWindowBridge` → `Desktop2DCompositorHost`. **7 new headless JUnit 5
  tests** (`CompositedWindowBridgeTest`) pin map→open, resize/retitle/activate/
  unmap forwarding, EDT-runner marshalling, and that both a controller failure
  and a runner refusal are swallowed rather than thrown back to the X thread.
  The remaining live step — the `Desktop2D` startup hook that constructs
  `X11CompositedWindowHost` + `Desktop2DCompositorHost` + `CompositedWindowBridge`
  and registers it once the WM has claimed the display — is the Phase G
  session-integration step and needs a bare-Xorg host (roadmap §5 Phase B.8).
- **X11 compositor Phase G: the 2D desktop now discovers a live compositor and
  hosts native X11 apps as MDI windows** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.nativewindow.x11` +
  `org.jdesktop.lg3d.displayserver.desktop2d`) — delivers the `Desktop2D` startup
  hook the WM→desktop bridge left open, so a conventional 2D desktop running in
  the *same JVM* as a live X11 compositor can host redirected native clients as
  ordinary MDI windows. **`X11CompositorSession`** (x11) is a discovery holder
  that `X11IntegrationModule` publishes once Composite redirection succeeds and
  `X11Compositor.shutdown()` clears; its `Session` exposes only the Java-3D-free
  interface types — a `CompositedWindowHost` and the new **`WindowLifecycleRegistrar`**
  public seam that the package-private `X11WindowManager` now implements — so the
  `desktop2d` package never drags in the scene graph or `gnu.x11`.
  **`CompositedDesktopWiring`** (desktop2d) is the turnkey assembly: a pure
  `shouldInstall(optIn, sessionLive, headless)` decision, a
  `desktopOpener(JDesktopPane)`, and `install(host, opener, registrar)` returning
  a `Handle` torn down via `dispose(unregister)`. `Desktop2D.show()` calls the
  guarded `installCompositedWindows()`, which reads
  `X11CompositorSession.current()` and installs only when the operator opted in
  via **`lg3d.x11.composite2d`**, a session is live and the JVM is not headless;
  composited windows open through a rich opener (taskbar button + workspace
  assignment + cascading placement, but *not* session-persisted, since a native
  client is transient) and `exit()` disposes the wiring. In every topology
  without a live compositor session (dev mode, the `*_nox` configs, compositing
  disabled) nothing is published and the 2D shell is unchanged. **16 new headless
  JUnit 5 tests** (`X11CompositorSessionTest` 7, `CompositedDesktopWiringTest` 9)
  pin the holder's publish/null-clear/replace lifecycle, the install truth table
  and null guards, the default opener, and the full map→window→dispose path over
  a real headless `JDesktopPane` (EDT-flushed). The one remaining live step needs
  a *combined* bare-Xorg launch that runs the 2D Swing desktop and the X11
  WM/compositor in one JVM — no production topology does yet (`Main` treats 3D
  and 2D as mutually exclusive), so the registration path is exercised headlessly
  only (roadmap §5 Phase G).

### Added
- **Metal theme collection for the conventional 2D desktop, incl. a Glassy
  theme that reproduces the 3D desktop's window-glass look** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — `MetalThemeManager.builtIns()`
  now offers six palettes instead of two: *Steel* and *Ocean* (the stock JDK
  Metal themes) plus four new ones — *Graphite* (neutral slate-grey), *Emerald*
  (forest-green accent), *Sunset* (warm amber/coral accent) and **Glassy**, an
  ice-blue palette built from colours lifted directly out of the native 3D
  desktop's rendering code rather than picked freehand: its primary accent
  shades are the exact edge/title blues `WidgetPanel.BORDER` (`#6094D6`) and
  `TITLE_COLOR` (`#8CB9F5`) paint on every glassy HUD card and `Frame3D` window
  decoration, and its highlight shade is `FrostedGlassPanel.DEFAULT_TINT`
  (`#D9E6FF`) — the cool, lightly-blue frosted-white tint every `Frame3D` glass
  panel is washed with — so the 2D Control Center's "Metal Theme" manager can
  now dress the Swing desktop in the same translucent-glass material as the 3D
  one (as faithfully as an opaque, six-colour Metal palette allows). No new UI
  wiring was needed: the Control Center's theme list already iterates
  `MetalThemeManager.available()`. Covered by two new headless JUnit 5 tests
  in `MetalThemeSpecTest` (built-ins enumeration, exact Glassy colour
  provenance).
- **X11 compositor end-to-end spike on stock JDK 21** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.nativewindow.x11`) — validated and hardened
  the pure-Java Escher/`gnu.x11` native-X11 compositor path that lets lg3d act
  as the window manager + compositor on a **bare Xorg** (the LFS production
  target), with no forked JDK, agent or `sun.awt.*` peer. Added **31 headless
  JUnit 5 tests** for the previously untested pure seams (virtual-key→keysym and
  button mapping, the 3D-pick→client-pixel coordinate mapping, and `XGetImage`
  pixel / TrueColor-channel decoding), instrumented `X11InputForwarder` with
  `java.util.logging` so a live run is diagnosable, and fixed a
  `ConcurrentModificationException` in `X11WindowAssociator.removeAllRules`.
  Re-established the launch wiring — `run-lg3d.sh --nested [<display>]` and
  `-Plgserverdisplay=<display>` target an **already-running** X display (the
  script never starts one), closing the drift where the LFS X11 contract already
  referenced `--nested`. Added `scripts/x11/live-proof.sh`, a Stage-4 harness
  mapped to the contract's §5 acceptance criteria that **never** spawns a nested
  X server, injects synthetic input or takes screenshots, plus
  `docs/x11-compositor-spike-roadmap.md` (Stage-0–3 evidence, deferred work with
  rationale, and the spike→production roadmap).
- **X11 compositor Phase B test hardening** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.nativewindow.x11`) — **25 new headless JUnit 5
  tests** (the X11 package is now 56, all green under `:lg3d-core:test`/`build`)
  covering the pure X-protocol wire decoders that the spike deferred, with **no
  production change and no `gnu.x11.Display` fake**: their reading constructors
  (`Data(byte[])`, `Event(Display,byte[],int)`, `ConfigureNotify(Display,byte[])`)
  only *store* the display and parse purely from the buffer, so a `null` display
  plus a synthetic buffer written through `Data`'s own byte-order-agnostic
  `writeN` helpers exercises them directly. New suites pin
  `X11ShmExt.GetImageReply` (header fields + the size-clamped `pixels()` copy that
  never over-reads a short reply), `X11CompositeExt.OverlayReply` (overlay window
  id), `X11FixesExt.FetchRegionReply` (rectangle count + `Enum` iteration) and
  `CursorNotifyEvent` (fields + the synthetic-flag bit), `X11DamageExt.NotifyEvent`
  (every accessor, signed area/geometry origins, `area()`/`geometry()`,
  `toString`), and `ConfigureNotifyBugFixed` (the signed 16-bit coordinate
  sign-extension that repairs the stock Escher unsigned read). The
  `X11WindowAssociator` rule matcher stays deferred — it genuinely needs an
  injectable `X11Client`/`LgEventConnector` fake. Roadmap §4.3/§5 updated.
- **X11 compositor Phase B: window-association rule matcher covered**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.nativewindow.x11`) — the last
  pure seam the previous entry left deferred is now tested. The cls/name/title
  decision inside `WindowAssociationRuleEntry.getTargetWindow` was extracted
  **verbatim** into a package-private static
  `X11WindowAssociator.matches(ruleCls, ruleName, ruleTitle, cls, name, title)`
  — a behaviour-preserving extract-method (both the sub-window and
  focused-window halves now delegate to it, and the unused `Matcher` import was
  dropped), so the associator's real decision logic is exercised with plain
  Strings and **no `X11Client`** (which extends `gnu.x11.Window` and needs a live
  `Display`). **8 new headless JUnit 5 tests** (`X11WindowAssociatorMatcherTest`,
  taking the X11 package to 64) pin the semantics: null criteria are wildcards,
  non-null cls/name match exactly (case-sensitive) and veto a null candidate
  value, the title pattern must `matches()` the whole title (not merely be found
  in it) with regex alternation/anchors, all criteria must match together, and
  empty strings are literal criteria. The associator's *orchestration*
  (`getAssociatedWindow` over live clients + the constructor's
  listener/prefs wiring) stays deferred pending an injectable
  `X11Client`/`LgEventConnector` fake. Roadmap §4.3/§5 updated.
- **X11 compositor Phase B: window-association orchestration covered**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.nativewindow.x11`) — the
  associator's *orchestration*, deferred by the two entries above, is now
  headless-tested. A package-private `WindowAssociationTarget` interface (the
  three reads the rules need — `getName`/`getResClass`/`getResName`) was
  extracted and implemented by `X11Client`; `X11WindowAssociator` was retyped
  onto it and gained a no-wiring `X11WindowAssociator(boolean)` test constructor
  (the no-arg one delegates with `true`, so production is unchanged) plus a
  package-private `setFocusedWindow` hook, so the rule engine can be driven with
  lightweight fakes and **no `X11Client`** — which cannot even be class-loaded in
  a test JVM without running its own static `new X11WindowAssociator()`
  (`LgEventConnector` + prefs). **14 new headless JUnit 5 tests**
  (`X11WindowAssociatorOrchestrationTest`, taking the X11 package to 78) pin
  `getAssociatedWindow` (the null-focus guard, sub-window/target-clause gating,
  first-match-wins ordering, and one-time-rule retirement) and `removeAllRules`
  (drops a window's one-time rules, clears focus when that window was focused,
  leaves other windows' rules intact, and no-ops on `null`). The refactor is
  behaviour-preserving for every input that did not previously throw; the one
  intentional change hardens the focused-window rule path against a missing
  WM_CLASS (it dereferenced `classHint` unconditionally — an NPE — and now reads
  it null-safely), which a dedicated test pins. Only the constructor's *live*
  listener/prefs wiring (needs a running desktop) and X11-scoped PIT remain
  deferred. Roadmap §4.3/§5 updated.
- **Remote Viewer** (`lg3d-apps`, `org.jdesktop.lg3d.apps.remoteviewer`) — a
  port of the standalone jrdesktop / Remote Viewer RMI remote-desktop tool,
  hosting its original `MainFrame` GUI (server start/stop, status, viewer
  connect, file transfer, about, exit) unchanged as `RemoteViewerPanel`: a
  SwingNode-in-`Frame3D` host in 3D via `TitledSwingWindow` and an MDI internal
  frame in 2D via `Desktop2DAppRegistry.PANEL_APPS`. Registered as the
  *Developers* start-menu app `remoteviewer.lgcfg` with a purpose-drawn
  `remoteviewer.png` icon.
- **Guitar / bass tuner that listens to the microphone** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.tuner`) — a production-grade chromatic and
  per-instrument tuner that analyses the microphone **natively and in-process**:
  `javax.sound.sampled` opens the default capture line (16-bit mono little-endian
  PCM) and a daemon thread streams frames into an AWT-free **`PitchDetector`**
  implementing the **YIN** algorithm (difference function → cumulative-mean
  normalised difference → absolute threshold → parabolic interpolation), so the
  fundamental is resolved with no external tool, no codec and nothing to install.
  An equal-temperament **`Note`** model turns the frequency into a note and a
  cents deviation, and a flexible **`Tuning`** model (chromatic, guitar standard /
  drop-D / open-G, bass 4- and 5-string, ukulele, plus **user-defined tunings**
  such as Drop C built from any string set) names the nearest string. Custom
  tunings are added and removed from the toolbar (`+` / `−`) through an editor and
  persisted as JSON under `~/.lg3d/tuner` by **`TuningStore`** (overridable via
  `lg3d.tuner.dir`), so any instrument tuning survives a restart. The one
  **`TunerPanel`** (big detected-note card, a painted cent-deviation
  **`TuningMeter`** whose needle turns green inside a ±5-cent window, and a string
  strip that highlights the nearest string) serves both desktops — a SwingNode-in-
  `Frame3D` host in 3D via `TitledSwingWindow` and an MDI internal frame in 2D via
  `Desktop2DAppRegistry.PANEL_APPS` — plus a standalone `TunerClient`. The
  microphone is opened **only** on a Start press and released on Stop / Close /
  window-close, so nothing is recorded and the panel constructs headless. The UI
  carries **no hardcoded colours**: every hue is resolved live from the active
  look-and-feel through **`TunerTheme`** (`UIManager` keys, with toolkit/blend
  fallbacks; only the meter's green/amber/red traffic-light status hues are
  semantic constants), so the panel matches the system L&F in 2D and the hosted
  Metal L&F in the 3D SwingNode. Registered as the *Media* start-menu app
  `tuner.lgcfg` with a purpose-drawn tuning-fork `tuner.png` icon. Covered by
  headless JUnit 5 suites (`PitchDetectorTest` feeding synthetic sine waves and
  recovering every guitar/bass open string, `NoteTest`, `TuningTest` incl. custom
  Drop C, `TuningStoreTest` round-trip, `TunerThemeTest`, `AddTuningDialogTest`,
  `TuningMeterTest` painting into a `BufferedImage`, `TunerPanelTest` driving the
  `applyReading` and add/remove-tuning seams) plus a `Desktop2DAppRegistryTest`
  classification case — no device is ever opened in CI.
- **2D desktop now shows a start-up splash** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.Desktop2DSplash`) — the conventional 2D/Swing
  desktop (`lg.fws.mode=2d` and `swing`) previously showed nothing while its
  shell, start menu and restored session were assembled on the EDT, so a cold
  start looked like a hang. It now raises a plain, undecorated, always-on-top
  window carrying **only** the product name ("Project Looking Glass") and the
  resolved build version, shown before the shell build is queued and disposed the
  moment the desktop is on screen. The version is resolved exactly as the About
  box does it — the `lg.version` system property first, then the generated
  `LgBuildInfo` version, then `unknown` — so no version literal is hardcoded and a
  bump cannot miss it. Deliberately minimal and 2D-only: it never loads the 3D
  artwork splash and leaves the 3D `SplashStarter`/`SplashWindow` path untouched.
- **PayloadMan API testing tool joins the Developers menu** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.payloadman`) — the external `tests-suite` project
  (PayloadMan), a production-grade desktop HTTP/API testing workbench (request
  builder, nested collections with JSON import/export, environments with
  `{{variable}}` resolution, bearer/basic/API-key auth, raw/form/multipart
  bodies, persistent history, cookie jar, collection runner, cURL export,
  proxy and TLS options), is now launchable from both lg3d desktops. Like the
  IDE and the OpenAPI Contract Editor it uses the **external child-process
  topology**: a dependency-free `PayloadMan` launcher resolves the tool's
  self-contained fat jar (`libs/payloadman.jar`, git-ignored, built by the
  external project's `fatJar` task and fetchable on demand via
  `:fetchPayloadManJar`) and forks it with `java -jar` on the lg3d display, so
  its own `JFrame` appears as an ordinary top-level window while its bundled
  Jackson/SnakeYAML/slf4j/logback and swing-ide plugin jars stay isolated from
  the desktop classpath. Registered in `Desktop2DAppRegistry.SWING_FRAME_APPS`
  (2D) and via `payloadman.lgcfg` in the *Developers* start-menu group (3D),
  with a purpose-drawn paper-plane `payloadman.png` icon and a `payloadman.jar`
  system property wired through `:lg3d-core:run`, the release `lg3d.sh` and
  `releaseBundle`.
- **Agenda now sends real meeting invitations** (`lg3d-incubator`,
  `org.jdesktop.lg3d.apps.orgchart.ui.agenda`) — inviting attendees no longer
  stops at the local attendee list: both agenda surfaces (native-3D `Agenda3D`
  and 2D/Swing `AgendaPanel`) gained a **Send Invites** control that composes a
  real **RFC 5545 iCalendar `METHOD:REQUEST`** for the selected appointment's
  occurrence in the displayed week and e-mails it through the Mail app's
  existing Jakarta Mail/SMTP backend — so recipients get an `invite.ics`
  (`text/calendar; method=REQUEST`) their mail client can accept, tentatively
  accept or decline. `InvitationBuilder` is a pure, AWT-free composer (UTC
  `DTSTART`/`DTEND`, RFC 5545 TEXT escaping, octet-safe 75-column folding,
  `ORGANIZER`/`RSVP=TRUE` `ATTENDEE` lines) and `InvitationSender` is a
  UI-agnostic send seam over `MailSessionManager`: it resolves the desktop's
  **default mail account**, skips invitees without an address, and turns every
  failure (no/incomplete account, no mailable invitee, SMTP error) into a
  user-safe dialog — never a stack trace, never a logged password. Credentials
  follow the Mail app's rules: SAVED-mode accounts send silently, ASK-mode
  accounts prompt in the 2D panel (Swing dialog) and report the manager's
  user-safe error in the 3D app (which, like `Mail3D`, installs no prompt).
  The blocking SMTP round trip runs on a daemon thread off the EDT/scene
  thread. `AgendaGrid.dateFor` became public so `Agenda3D` can date an invite
  for the displayed week. Covered by new headless JUnit 5 suites
  (`InvitationBuilderTest` 13 tests pinning the wire format, folding and
  escaping; `InvitationSenderTest` 7 tests over a recording fake
  `MailService`) plus 2 new `AgendaPanelTest` cases driving the panel's send
  seam.
- **OpenAPI Contract Editor** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.openapieditor`) — the external
  **OpenAPI-Contract-Editor** project (a full-featured YAML/OpenAPI specification
  editor: RSyntaxTextArea syntax highlighting, swagger-parser validation,
  OpenAPI Generator code generation, Swagger-UI-coloured endpoint tree, YAML
  structure navigator, find/replace, undo/redo, drag-and-drop) integrated as a
  *Developers* start-menu app. Like the IDE, the editor is **not** loaded into
  the desktop JVM: it ships as a self-contained fat jar
  (`libs/openapi-editor.jar`, ~25 MB, built by the external project's own
  Gradle/shadow build and fetched on demand via a new
  `:fetchOpenApiEditorJar` task, so it stays out of git) and the new
  `OpenApiEditor` launcher forks it as a **separate child process**
  (`java -jar`) on the lg3d display. The isolation is load-bearing: the editor
  calls `System.exit` when its main window closes (`EXIT_ON_CLOSE`), which
  would otherwise tear down the desktop, and its fat jar bundles unrelocated
  third-party libraries (snakeyaml, swagger-parser, openapi-generator,
  RSyntaxTextArea, Jackson) that could clash with the desktop classpath —
  neither can reach the desktop JVM because the jar is never on it. The child's
  own `JFrame` therefore appears as an ordinary top-level window: composited
  over the 3D scene and beside the 2D/Swing desktop.
  `openapieditor.lgcfg` registers the menu item with the `java <class>` verb
  and a new `openapi-editor.png` icon (purple glass tile with a
  contract-page-and-braces glyph drawn in `GenerateAppIcons`); the jar path is
  resolved from a new `openapieditor.jar` system property (set by
  `:lg3d-core:run` and the release `lg3d.sh`, and the jar is bundled into
  `releaseBundle`), then `<lg.appcodebase>/libs`, then the working directory,
  degrading to a readable "unavailable" message when absent.
  `Desktop2DAppRegistry` classifies the launcher as a `SWING_FRAME` app so the
  2D desktop runs its (child-forking) main beside the desktop. Covered by a new
  headless JUnit 5 suite (`OpenApiEditorTest`, 12 tests pinning the jar-path
  precedence, child-command shape and display selection without ever spawning a
  process) and a `Desktop2DAppRegistryTest` classification case.
- **Cron Task Scheduler for the 2D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.utils.taskscheduler`; Control Center panel in `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter.TaskSchedulerPanel`) — a complete,
  `crontab`-style scheduler that runs user programs and start-menu apps on a
  schedule, entirely separate from (and leaving untouched) the existing
  wallpaper/lighting `ScheduleService`/`SchedulePanel` day-night feature. A
  from-scratch `CronExpression` parses the classic five-field `min hour dom month
  dow` form plus a six-field seconds form, ranges/steps/lists, month and day
  names, `7`=Sunday, the Vixie-cron "either day field" OR rule and the
  `@yearly`/`@monthly`/`@weekly`/`@daily`/`@midnight`/`@hourly`/`@minutely`/`@reboot`
  macros, and computes the next fire time (impossible dates such as 30 February
  return none). `ScheduledTask`/`Trigger` model a job as **CRON**, a fixed
  **INTERVAL** or a one-shot **ONCE** instant, running either an external
  **COMMAND** or a start-menu **APP**, each with its own timeout, misfire policy,
  concurrency guard, retries, working directory and environment overlay.
  **Secure by construction** — `ProcessTaskRunner` executes the argv vector
  verbatim through `ProcessBuilder` with **no shell**, so an argument can never be
  re-interpreted as a shell metacharacter; `TaskValidator` gates every save on
  required fields and resource limits (argv/env/name/timeout/retry bounds, NUL
  rejection) and `DISPLAY` is pointed at the lg3d server so GUI jobs open on the
  right screen. **Reliable** — `TaskSchedulerEngine` runs a single daemon tick
  (1 s, giving cron sub-minute resolution) whose body is fully guarded so no
  exception can cancel the loop, fires tasks on a worker pool (a slow job never
  delays the others), recomputes the next fire at dispatch so a long run cannot
  re-fire-storm, skips overlaps when `skipIfRunning` is set, retries failures, and
  on start-up runs `@reboot` jobs and reconciles anything that came due while the
  desktop was down per each task's `MisfirePolicy` (FIRE_ONCE_NOW / IGNORE /
  FIRE_ON_NEXT_TICK). Tasks and a capped, secret-scrubbed run history persist to
  the user `Preferences` tree via `PrefsTaskStore` (structured argv/env stored as
  indexed keys so any character round-trips exactly; every backing-store failure
  is logged and swallowed so the desktop always starts). The `TaskScheduler`
  singleton facade is started from `Desktop2D` and drives the Control Center's
  **Task Scheduler** panel — a `JList`-based (SwingNode-safe, no combo boxes)
  editor with cron presets and a live next-run preview, New/Save/Delete/Run
  now/Refresh, an environment editor and the recent-runs history. Covered by
  headless JUnit 5 tests (`CronExpressionTest`, `TriggerTest`,
  `ScheduledTaskTest`, `TaskExecutionRecordTest`, `TaskValidatorTest`,
  `PrefsTaskStoreTest` against a throwaway preferences node, `ProcessTaskRunnerTest`
  including an argv-injection-is-inert proof, `TaskSchedulerEngineTest` over an
  in-memory store/runner, and `TaskSchedulerPanelTest`) — no real process or
  preference is touched in CI beyond the Unix-guarded runner tests.
- **Real IMAP/SMTP Mail client** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.mail`)
  — the former local-only demo mailbox is replaced by a **real, configurable,
  secure IMAP/SMTP client** on **Jakarta Mail 2.x** via the Eclipse Angus provider
  (`org.eclipse.angus:angus-mail`). One shared AWT-free model + service layer
  (`MailMessage`/`MailAddress`/`MailAttachment`/`MailFolder`/`MailAccount`,
  `MailService`/`ImapSmtpMailService`, `MailSessionManager`) backs **both**
  desktops: the 2D/Swing `MailPanel` (three-pane account/folder tree · sortable
  message table · reading pane, toolbar, status bar, compose with attachments,
  server-side IMAP search, tabbed `MailSettingsDialog` + `MailAccountDialog` with
  "Test connection") and the native-3D `Mail3D`/`MailView` browse-and-triage
  surface. Fully customisable and configurable: multiple accounts, appearance
  (fonts, light/dark theme + accent, list density, reading-pane position), sorting,
  auto-check interval, and filter rules (`MailRule`/`MailRuleStore`: from/subject/to
  · contains/equals/regex → move/mark-read/flag/delete) applied on fetch. **Secure
  by default** — SSL/STARTTLS with certificate validation on the JDK truststore,
  explicit connect/read/write timeouts, plaintext only on explicit `Security.NONE`,
  HTML mail opt-in with remote content blocked. Per-account credentials are either
  **in-memory only (ASK — prompt each connect)** or **obfuscated at rest (SAVED)**
  via AES-GCM (`CredentialVault`) under a per-install random secret; ASK passwords
  are never persisted and secrets are never logged. Config persists to the shared
  `/mail/accounts`, `/mail/settings`, `/mail/rules` and `/mail/.secret`
  `Preferences` nodes. angus-mail is wired through the version catalog and onto the
  hand-assembled `:lg3d-core:run` classpath and `releaseBundle`; the legacy
  `javax.mail` `ext/mail.jar` stays only so `blackgoat` compiles. Protocol
  correctness is proven headlessly against **GreenMail** (in-JVM IMAP/SMTP) in
  `ImapSmtpMailServiceTest` (connect/list/open/search/flag/move/delete + send with
  an attachment), alongside store/rule/settings/vault/session-manager/panel tests.
- **Audio CD ripping + album-cover music library** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.audioplayer` and `...cdviewer`) — the Audio Player can
  now **rip an audio CD to MP3 or WAV** with a choice of sampling rate (8000 –
  48000 Hz) and MP3 bitrate (128/192/256/320 kbps), tag and organise the results
  into a persistent **music library**, and browse that library **by album cover**
  as a carousel in *both* desktops. Following the established honest external-tool
  split (`AudioBackend`, `RecorderBackend`), no codec is bundled: a new
  **`Rip CD` tab** (`CdRipPanel`) reads the disc TOC via `cdparanoia` (fallback
  `cd-info`), extracts each selected track and encodes/resamples it with `ffmpeg`
  (fallback `lame`) over a guarded `ProcessBuilder`, and when a tool is missing
  the status line says so plainly. All decisions live in pure, headless-tested
  seams — `CdRipBackend` (command builders + ripper/encoder/device resolution),
  `Toc`/`TocParser` (cdparanoia + cd-info output), `MusicBrainzDiscId` (the
  MusicBrainz disc-ID SHA-1 + custom base64, verified against the published
  vector) and `AudioCdDb` (a `java.net.http` client for the **MusicBrainz**
  disc-ID lookup and **Cover Art Archive** `front-500` art, with a pure
  `parseRelease` and never-throw fetch) — so album / artist / track names and the
  cover are prefilled automatically from the audio CD database. `MediaItem` gains
  optional `artist`/`album`/`trackNumber`/`mbid`/`coverFile` tags (old
  `library.json` still loads), `RipSettings` persists the rip preferences, and
  `AudioPlayerStore` adds the `covers/` + `music/` folders. The library is grouped
  into albums by `AlbumIndex` and shown two ways: a Swing **`AlbumCoverFlow`**
  cover carousel on the new **Albums** tab (select queues, double-click plays; the
  2D desktop's surface, `JList`-based so it is `SwingNode`-safe), and the real 3D
  **`CDViewer`**, now library-aware — one textured disc per album (the cover is
  the disc texture and the front-disc `Thumbnail`, with an album caption under the
  raised disc), keeping its bundled `CD1-4.png` demo strip whenever the library is
  empty. Covered by headless JUnit 5 tests (`CdRipBackendTest`, `TocParserTest`,
  `MusicBrainzDiscIdTest`, `AudioCdDbTest`, `AlbumIndexTest`, `RipSettingsTest`,
  extended `AudioPlayerStoreTest`/`MediaItemTest`, `AlbumCoverFlowTest`); real
  end-to-end ripping needs a physical drive + `cdparanoia`/`ffmpeg` and the 3D
  carousel is verified via the in-JVM probe + `lgscreen` capture, not in CI.
- **3D window carousel switcher** (`lg3d-core`,
  `org.jdesktop.lg3d.scenemanager.utils.switcher`) — the native 3D desktop's
  window switcher now presents open windows as a `CDViewer`-style circular
  carousel instead of a list of titles: one card per window seated on a circle
  with the selected window at the front, enlarged and centred, and each card
  textured with that window's own live miniature so it is recognisable by its
  content. Because a `Frame3D`'s `Thumbnail` is a single-parented node already
  owned by the taskbar (it cannot be reparented onto the HUD), a new
  `WindowThumbnailSource` seam shares the window `SwingNode`'s live `Texture2D`
  (`SwingNodeThumbnailSource` walks the frame subtree and observes it via
  `SwingNode.addTextureListener`), with a titled glass card as the honest
  fallback for pure-3D apps that expose no such texture. The carousel keeps the
  existing Ctrl+Alt+Tab MRU cycle and idle-commit behaviour and adds pointer
  navigation on top: the mouse wheel spins the ring, clicking a card commits that
  window straight to front, and pointing at the carousel pauses the idle-commit
  timer so the user can aim. The circular positioning arithmetic is lifted into a
  pure, Java 3D-free `CarouselLayout` so it is headless-testable
  (`CarouselLayoutTest` — front seat, tangential rotation spacing, receding depth
  stack, focus emphasis, revolve wrap and the empty/single-card cases); the
  scene-graph node (`WindowCarousel3D`) is verified at runtime via the in-JVM
  probe + `lgscreen` capture, and supersedes the removed `WindowSwitcherPanel`.
- **Quick launchers on the 2D desktop taskbar** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — the Swing 2D desktop now mirrors
  the 3D `GlassyTaskbar` shortcut shelf with a pinned, reorderable quick-launch
  strip beside the Start button. A new observable `QuickLaunchModel` holds an
  ordered list of pinned apps, de-duplicated by launch command and capped at 24
  entries, that writes straight through a `QuickLaunchStore` seam —
  `PrefsQuickLaunchStore` persists it under the user preferences, following the
  existing Run-history / Session pattern. Left-clicking a strip icon launches the
  app through the normal `Desktop2D.openApp` path; right-clicking offers Move
  Left / Move Right / Unpin, and every running window's taskbar button gains a
  Pin/Unpin toggle so an open app can be kept on the bar. On first run the
  desktop seeds up to six launchable defaults, preferring the native in-desktop
  apps and then filling any remaining slots with available external commands,
  guarded by a persisted seeded flag so a user who un-pins everything is not
  re-seeded on the next start. Covered by headless JUnit 5 tests
  (`QuickLaunchEntryTest` — encode/decode round trip, delimiter/blank/corrupt
  tolerance and the `ItemSpec` bridge; `QuickLaunchModelTest` — pin/un-pin,
  reorder, de-dup, cap, write-through persistence and change notifications)
  exercised against an in-memory store so nothing touches real preferences.
- **Control Center → Quick Launch panel** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter.QuickLaunchPanel`; hooks in `lg3d-core`) —
  the 2D taskbar's pinned quick-launch strip is now configurable from the Control
  Center: a `JList` of the pinned launchers in taskbar order with Move Up / Move
  Down / Remove, a second `JList` of the available start-menu applications with
  Add, and a Reset to Defaults button. It drives the strip through new EDT-safe
  `Desktop2D` control-center hooks (`quickLaunchPinned`, `quickLaunchCandidates`,
  `quickLaunchPin`, `quickLaunchUnpin`, `quickLaunchMove`,
  `quickLaunchResetDefaults` and the public `Desktop2D.QuickLaunchItem` record),
  so a change persists and the live taskbar updates immediately. Like the
  Workspaces / Notifications panels it uses only `JList` / `JButton` (never a
  combo box, which an offscreen `SwingNode` cannot reliably deliver) and degrades
  to an empty strip when no 2D shell is running. Covered by headless tests
  (`QuickLaunchPanelTest`, plus the no-shell hook path in
  `Desktop2DControlHooksTest`).
- **Drag an Application Launcher onto the 2D quick-launch bar** (`lg3d-apps`
  `org.jdesktop.lg3d.apps.launcher` + `lg3d-core` taskbar) — the Application
  Launcher frame (`LauncherFrame`) now lets a launcher be dragged straight onto
  the taskbar's pinned quick-launch strip: dragging its icon exports the current
  name / command / icon, and the strip (a `DropTarget`) pins the dropped launcher
  through the same `QuickLaunchModel.pin` path the window right-click popup uses —
  so a freshly created launcher can be pinned without first appearing in the start
  menu. Both ends share one same-JVM `DataFlavor` (`Desktop2D.QUICK_LAUNCH_FLAVOR`,
  carrying the public `Desktop2D.QuickLaunchItem` by reference); the strip
  highlights while an acceptable drag hovers and keeps a drop zone even when it is
  empty. The drag glue lives in a testable helper (`QuickLaunchDrag`) outside the
  NetBeans-generated form. Covered by headless tests (`QuickLaunchDragTest`, plus
  the flavour assertion in `Desktop2DControlHooksTest`); the live drag gesture
  itself needs an X display.
- **Application Launcher can pick a built-in icon from the icon library**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.launcher`) — beside the existing
  **Choose Icon** file browser, the launcher frame now has an **Icon Library**
  button that opens a picker over the desktop's own bundled `IconManager` glyphs
  (`libs/IconManager-1.6.0.jar`): a category list (General, Development, Text,
  Media, Navigation, Tables) driving a live-preview list of every glyph in that
  category. The chosen glyph is exported to a PNG under
  `~/.config/lg3d/launchers/icons/` and stored as the launcher's icon path, so it
  flows through `LauncherSaver` and the drag-to-quick-launch payload exactly like
  a custom image file does; the icon button now also shows a preview of either
  pick. The IconManager bridge (`IconLibrary`) is a pure, never-throw seam — it
  reduces the bundled `"<Name><edge>.gif"` file names to distinct base glyph
  names, treats an unknown name's `MissingIcon` as "no glyph" and degrades to an
  empty catalogue when the jar is absent — covered by headless JUnit 5 tests
  (`IconLibraryTest`); the picker dialog (`IconLibraryDialog`) needs a display and
  is verified at runtime.
- **Contacts — a production address book for the 2D/Swing and 3D desktops**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.contacts`; shared store in `lg3d-core`,
  `org.jdesktop.lg3d.contacts`; registration in `lg3d-core`, icon in `lg3d-core`
  resources) — the desktop's contact surface is now a fully functional address
  book replacing the read-only 2006-era Contact 3D card browser. The data layer
  is a new **desktop-wide shared store in lg3d-core** (the only module every
  consumer depends on): a `Contact` bean (names, nickname, multi-valued
  e-mails/phones/tags, organisation, title, notes, favourite flag) and a
  `ContactStore` with synchronized CRUD, search and favourites-first ordering
  over `~/.lg3d/contacts/contacts.json` (Jackson — promoted to an `api`
  dependency of lg3d-core so consumers see the annotations on `Contact`), with
  atomic temp-file + `ATOMIC_MOVE` saves, defensive reads (missing/corrupt file
  degrades to an empty book, never throws) and a `-Dlg3d.contacts.dir` override
  for tests. The book **starts empty and is never seeded with demo data**. The
  one `ContactsPanel` (plain Swing, no Java 3D) serves both desktops: an
  incremental-search toolbar, New / Edit / Delete (confirmed), vCard 3.0
  import/export for interoperability with external address books, a
  favourites-first list, a read-only detail card and an in-panel CardLayout
  editor (never a modal escaping the SwingNode capture). In 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `Contacts` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`. Registered in the start menu (**Office** group) via
  `contacts.lgcfg` in `lg3d-apps/src/config`, with an address-book icon in
  `lg3d-core/src/resources/images/icon/contacts.png`. One book, many readers:
  the Agenda/Mail `ContactDirectory`, the Messenger **Save** button and the
  Video Conference contacts tab all read/write the same store (see Changed).
  Covered by headless JUnit 5 tests (`ContactStoreTest` — CRUD, persistence,
  search, ordering, corrupt-file recovery, display-name fallbacks;
  `ContactsPanelTest` — construction, empty state, create/edit/cancel flows,
  incremental search, vCard codec round-trip and defensive parse) plus a
  `Desktop2DAppRegistryTest` mapping assertion.
- **Docker Manager for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.dockermanager`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production Docker workbench ported **verbatim** from
  the Swing IDE docker plugin (`ide-docker-plugin`) with its appearance preserved
  exactly: the plugin's `DockerManagementPanel` (a tabbed **Terminal** /
  **Containers** / **Files** / **Images** workspace) plus its `ContainerInfo` /
  `ImageInfo` / `ContainerTableModel` / `ImageTableModel` / `StatusCellRenderer` /
  `FileTreeCellRenderer` support classes were copied into the desktop and adapted
  only where the plugin-API shell was stripped (package rename, Lombok `@Slf4j` /
  `@Data` replaced by an explicit SLF4J logger and plain getters/setters, and the
  IDE's `Project`/`ProjectManager` coupling reduced to a `File projectRoot` with a
  no-arg constructor for reflective hosting). Every toolbar button, tab and
  context-menu glyph still resolves through the bundled `IconManager` library
  (`libs/IconManager-1.6.0.jar`, already on the `lg3d-core` run classpath and now a
  compile-only + test dep of `lg3d-apps`), which is what keeps the look identical
  to the source plugin; there is **no bundled docker-java and no Java 3D** — the
  panel drives the system `docker` CLI over `ProcessBuilder`/`Runtime.exec` on a
  cached thread pool, and with no daemon on the PATH it degrades to a readable
  "not running" status rather than failing. The one panel serves both desktops: in
  2D `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  new `DockerManager` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`. Registered in the start menu (**Developers** group) via
  `docker.lgcfg` in `lg3d-apps` `src/config`, with a Docker whale-and-containers
  icon in `lg3d-core/src/resources/images/icon/docker.png`. Covered by the headless
  `DockerManagementPanelTest` (no-arg + project-root constructors build without
  throwing, tabs lay out, `cleanup()`/`refreshAll()` are safe) plus a
  `Desktop2DAppRegistry` mapping; real container/image round-trips need a live
  daemon and are verified via the in-JVM probe + `lgscreen-*.png` capture.
- **Web Browser for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.webbrowser`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production full-featured web browser and the
  **first OpenJFX dependency in the repo**: it renders the modern web through a
  JavaFX `WebView` (WebKit) engine, wired via `org.openjfx:javafx-{base,
  graphics,controls,media,web,swing}:21.0.12` with the `linux` classifier
  (GPLv2 + Classpath Exception), mirroring the Jogamp natives pattern and
  resolved onto both the `lg3d-apps` compile path and the hand-assembled
  `:lg3d-core:run` / `releaseBundle` classpath (the `javafxLibs` detached
  configuration). The engine is deliberately split by surface: because
  `SwingNode.captureNow` paints Swing offscreen into a `BufferedImage` while a
  JavaFX surface is a heavyweight native peer that yields a **blank quad**
  offscreen (and initialising JavaFX in the Java 3D JVM risks an OpenGL toolkit
  clash), the full interactive browser lives only where a real heavyweight host
  exists. In 2D `Desktop2DAppRegistry.PANEL_APPS` opens `BrowserPanel` as an MDI
  internal frame — a tab strip, a navigation toolbar (back / forward / reload /
  stop / home, a smart URL field, bookmarks menu, find, zoom, view-source,
  downloads, history, settings) and a status/progress bar with an HTTPS
  indicator — with a `JFXPanel` centre hosting the `FxBrowser` scene. In 3D the
  `WebBrowser` wrapper instead shows a pure-Swing `BrowserPreviewPanel` (product
  art, a painted chrome mock and an "Open Full Browser" button that spawns
  `WebBrowserApp` — a top-level `JFrame` holding `BrowserPanel` — into a detached
  child-process JVM with `DISPLAY` propagated, degrading to an in-JVM thread
  launch if the spawn fails), so JavaFX never touches the Java 3D GL context.
  Every `javafx.scene.web` reference is confined to `FxBrowser`, which owns one
  `WebView`/`WebEngine` per tab (keyed on `TabModel.Tab` id), wires location /
  title / `LoadWorker` progress + state / `createPopupHandler` listeners, applies
  settings (JavaScript, user agent, cookies via `java.net.CookieManager`), and
  does zoom, find-in-page (`window.find`), view-source and best-effort downloads
  (`java.net.http.HttpClient` to the download dir, then `xdg-open`); all mutators
  marshal through `Platform.runLater` and report back on the FX thread. The
  AWT-free model (`UrlNormalizer` smart address bar — scheme passthrough /
  `https://` for host-like text / search otherwise, `SearchEngine`,
  `BookmarkStore`, `HistoryStore` capped + de-duplicated + transient-filtered,
  `TabModel`, `BrowserSettings`, `DownloadRecord`) persists as JSON under
  `~/.lg3d/webbrowser` via `BrowserStore` (Jackson, defensive reads:
  missing/corrupt yields defaults, never a throw), and restores the prior
  session's tabs on launch. Registered in the start menu (**Internet** group) via
  `webbrowser.lgcfg` in `lg3d-apps` `src/config`, with a programmatic
  browser-window-and-globe icon generated by `lg3d-art/tools/GenerateAppIcons`.
  Features WebKit/`WebView` cannot do natively (a full download manager, true
  DevTools) are delegated or surfaced honestly rather than faked. Covered by
  headless JUnit 5 tests (`UrlNormalizerTest`, `SearchEngineTest`,
  `BookmarkStoreTest`, `HistoryStoreTest`, `TabModelTest`, `BrowserSettingsTest`,
  `DownloadRecordTest`, `BrowserStoreTest`) plus a `Desktop2DAppRegistryTest`
  mapping assertion; the GUI itself has no coverage/mutation gate and is verified
  via the in-JVM probe + `lgscreen-*.png` capture.
- **Git GUI client for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.gitgui`; registration in `lg3d-core`, icon in `lg3d-core`
  resources) — a production GitKraken / GitHub Desktop style graphical Git client
  built as a single Swing panel, `GitGuiPanel`: a toolbar (Open, Refresh, Init,
  Fetch, Pull, Push + the current-branch label), a left column of Changes with
  Stage / Unstage over a commit-message box (Commit), and a right column of
  Branches with Checkout / New Branch, a commit History list and a monospace diff
  viewer for the selected change or commit. There is **no bundled JGit**: the
  working tree is honestly driven through the system `git` executable behind an
  AWT-free seam — `GitCommands` builds machine-readable argv (`status
  --porcelain=v1 --branch`, `for-each-ref --format=%(HEAD)%09%(refname:short)`, a
  `%x1f`/`%x1e`-delimited `log`, `-c core.quotePath=false -c color.ui=false
  --no-optional-locks`), `GitParsers` turns the raw stdout into `GitChange` /
  `GitBranch` / `GitCommit` records (rename `old -> new`, conflict codes,
  ahead/behind, detached HEAD), and `GitRepository` wires them to an injectable
  `Runner` over `ProcessRunner` with LOCAL (30 s) / NETWORK (120 s) timeouts. A
  folder that is not a repository, or a missing `git`, surfaces as guidance drawn
  from the tool's real exit / output, never a fabricated state; a failed mutation
  reports `git`'s own stderr. Every network / disk command runs on a daemon
  thread, never the EDT, and the panel's one-shot `actionMessage` survives the
  post-action refresh. The one panel serves both desktops: in 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `GitGui` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`; a standalone `GitClient` (`DISPOSE_ON_CLOSE`) runs it
  outside the desktop. Registered in the start menu (**Developers** group) via
  `gitgui.lgcfg` in `lg3d-apps` `src/config`, with a programmatic git-branch icon
  generated by `lg3d-art/tools/GenerateAppIcons`. Covered by 48 headless JUnit 5
  tests (`GitCommandsTest`, `GitParsersTest`, `GitRepositoryTest`,
  `GitGuiPanelTest`) against a `FakeGitRunner` plus a `Desktop2DAppRegistryTest`
  mapping assertion.
- **VPN client for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.vpn`; registration in `lg3d-core`, icon in `lg3d-core`
  resources) — a production tunnel front-end built as a single Swing panel,
  `VpnPanel`: a profile dock (NetworkManager connections discovered live, plus
  imported `.ovpn` / `.conf`), a detail / status centre with Connect and
  Disconnect, a backend picker, Import and Remove. There is **no in-tree VPN
  stack**: the connection is honestly delegated to an installed tool through the
  AWT-free `VpnBackend` seam, which resolves which tool to drive (`nmcli`
  preferred — it reuses NetworkManager's stored VPN connections and secrets;
  `openvpn` / `wg-quick` for an imported config), builds the exact
  `nmcli connection up/down`, `openvpn --config … --daemon` and `wg-quick up/down`
  command lines, and parses terse `nmcli` output (escape-aware on `\:`) into a
  `VpnProfile` / `VpnStatus`. A missing tool, or a connect that needs privilege
  the session lacks, surfaces as guidance drawn from the tool's real exit / output
  (`describeResult`), never a fake "connected". The store holds **no secret** —
  NetworkManager keeps its own credentials and an imported config keeps its own
  keys where the user left them (only the path is remembered); preferences and
  imported profiles persist as JSON under `~/.lg3d/vpn` via `VpnStore` (Jackson,
  defensive reads), and no process, probe or dialog runs until the user acts (each
  external command runs on a daemon thread). The one panel serves both desktops:
  in 2D `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D
  the `Vpn` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`; a standalone `VpnClient` (`DISPOSE_ON_CLOSE`, tearing the
  tunnel down on close) runs it outside the desktop. Registered in the start menu
  (**Internet** group) via `vpn.lgcfg` in `lg3d-apps` `src/config`, with a
  programmatic globe-lock icon generated by `lg3d-art/tools/GenerateAppIcons`.
  Covered by 42 headless JUnit 5 tests (`VpnBackendTest`, `VpnModelTest`,
  `VpnStoreTest`, `VpnPanelTest`) plus a `Desktop2DAppRegistryTest` mapping
  assertion.
- **Password Manager for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.passwordmanager`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production credential vault built as a single Swing
  panel, `PasswordManagerPanel`: a **lock card** (create-vault with confirmation
  and a live strength meter, or unlock) and a **master/detail** workspace (entry
  list, category filter, add / edit / remove, a masked password field with reveal
  + copy, and a generator with length / class toggles and a strength bar). There
  is **no in-tree crypto library** — `VaultCrypto` uses only the JDK: it derives
  the key with `PBKDF2WithHmacSHA256` (210,000 iterations, 256-bit) and seals the
  vault with `AES/GCM/NoPadding` (128-bit tag, 12-byte IV, 16-byte salt), so a
  wrong master password fails GCM authentication (`AEADBadTagException`) and is
  reported as "incorrect password", distinct from a corrupt file. The master
  password, the derived key and the decrypted vault live in memory **only while
  unlocked** and are never written; only the sealed `VaultEnvelope` (Base64 salt /
  iv / iterations / ciphertext) persists, as JSON under `~/.lg3d/passwordmanager`
  via `VaultStore` (Jackson, defensive reads: missing/corrupt yields defaults,
  never a throw), with non-secret preferences in `settings.json` and an auto-lock
  that re-seals after idle (disabled at 0 minutes). Entry passwords are masked by
  default and excluded from search. The one panel serves both desktops: in 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `PasswordManager` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`; a standalone `PasswordManagerClient` (`DISPOSE_ON_CLOSE`)
  runs it outside the desktop. Registered in the start menu (**System** group) via
  `passwordmanager.lgcfg` in `lg3d-apps` `src/config`, with a programmatic padlock
  icon generated by `lg3d-art/tools/GenerateAppIcons`. Covered by 45 headless
  JUnit 5 tests (`VaultCryptoTest`, `VaultEnvelopeTest`, `PasswordVaultTest`,
  `VaultStoreTest`, `PasswordManagerPanelTest`) plus a `Desktop2DAppRegistryTest`
  mapping assertion.
- **Antivirus / Security Center for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.securitycenter`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production security utility built as a single Swing
  panel, `SecurityCenterPanel`, with an **Antivirus** tab (scan target + Browse,
  recursive / quarantine / update-first options, a scanner picker, Update / Scan /
  Stop, a summary line and a findings list), a **Security Overview** tab (a rating
  header, SELinux / Firewall / Antivirus posture cards, a recommendations list and
  Refresh) and an EAST scan-history dock. There is **no in-tree virus engine**:
  scanning is honestly delegated to an installed **ClamAV** through the AWT-free
  `AntivirusBackend` seam, which resolves the scanner (`clamdscan` daemon
  preferred, `clamscan` standalone fallback), builds the exact
  `clamdscan`/`clamscan`/`freshclam` command lines and parses their output into an
  immutable `ScanReport` / `Detection` / `VersionInfo`; a stopped `clamd` (summary
  with errors and nothing scanned) is detected via `ScanReport.ranSuccessfully()`
  and falls back to `clamscan` for the rest of the session. Infected files are
  **quarantined (moved), never deleted**. Host posture is aggregated by the
  AWT-free `SecurityProbe` seam — `getenforce` (SELinux) and `firewall-cmd --state`
  (firewalld, where an authorization failure is `UNKNOWN`, not "not running") —
  into a `SecuritySnapshot` that derives plain-language `concerns()` and a
  `rating()`. Settings and scan history persist as JSON under
  `~/.lg3d/securitycenter` via `SecurityCenterStore` (Jackson, defensive reads:
  missing/corrupt yields defaults/empty, never a throw); no scanner, probe or
  dialog runs until the user acts. The one panel serves both desktops: in 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `SecurityCenter` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`; a standalone `SecurityCenterClient` (`DISPOSE_ON_CLOSE`)
  runs it outside the desktop. Registered in the start menu (**System** group) via
  `securitycenter.lgcfg` in `lg3d-apps` `src/config`, with a programmatic shield
  icon generated by `lg3d-art/tools/GenerateAppIcons`. Covered by 39 headless
  JUnit 5 tests (`AntivirusBackendTest`, `SecurityProbeTest`,
  `SecurityCenterStoreTest`, `SecurityCenterPanelTest`) plus a
  `Desktop2DAppRegistryTest` mapping assertion.
- **Advanced File Manager operations: compression, burning, network sharing
  and volume mounting** (`lg3d-apps`, `org.jdesktop.lg3d.apps.filemanager`) —
  the production File Manager gains four advanced capabilities, each backed by
  an AWT-free, headless-testable engine so the Swing panel stays thin.
  **Compression** via `ArchiveOperations`, a pure-`java.util.zip` engine that
  creates / extracts / lists archives (zip, jar, war, ear, apk, cbz, zipx),
  writes atomically through a `.part` temp then move, and hardens extraction
  against Zip-Slip path traversal (both `../` and absolute entries are refused).
  **Burning** reuses the existing `MediaWriterEngine`
  (`org.jdesktop.lg3d.apps.mediawriter`) rather than duplicating destructive
  device-write logic — a multi-item selection is staged into a temp folder and
  burned as a data disc, degrading gracefully to *Create ISO image only* when no
  optical drive is present. **Network sharing** via `ShareOperations`, a JDK
  `com.sun.net.httpserver` folder share (no external daemon, no root) that
  serves a browsable directory listing and file downloads on the LAN,
  percent-encodes hrefs (RFC 3986) and refuses path traversal with a 4xx; the
  active share is stopped when the panel closes. **Volume mounting** via
  `VolumeOperations`, which detects mountable volumes by parsing `lsblk -b -P`
  and mounts / unmounts through `udisksctl` (polkit, no password for removable
  media) with a `pkexec` fallback. The panel wires these into the toolbar
  (**Share**, **Mounts**) and the right-click context menu (**Compress…**,
  **Extract**, **Burn to Disc…**, **Share This Folder** / **Stop Sharing**,
  **Mounts / Volumes…**), running each operation off the EDT on a `SwingWorker`
  with a progress dialog. Covered by 32 headless JUnit 5 tests
  (`ArchiveOperationsTest`, `ShareOperationsTest`, `VolumeOperationsTest`).
- **Audio Player for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.audioplayer`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production music player built as a single Swing
  panel, `AudioPlayerPanel`: a playlist dock, transport controls (play / pause /
  stop / previous / next / seek), a volume + mute strip and a status line.
  Uncompressed formats (WAV / AU / AIFF) play **natively** through
  `javax.sound.sampled` (`Clip` / `SourceDataLine`); because the JDK ships no
  MP3/stream decoder, compressed sources (MP3, network streams, AM/FM radio,
  podcasts) are handed to an external player command through the AWT-free
  `AudioBackend` seam, which resolves the installed player, builds the exact
  command line and describes the format honestly rather than claiming in-tree
  codec support. Playlists, per-track metadata and settings persist as JSON under
  `~/.lg3d/audioplayer` via `AudioPlayerStore` (Jackson), with defensive reads
  (missing/corrupt yields empty/defaults, never a throw). The one panel serves
  both desktops: in 2D `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI
  internal frame, in 3D the `AudioPlayer` wrapper hosts it on a `SwingNode`
  inside a `Frame3D` via `TitledSwingWindow`; a standalone `AudioPlayerClient`
  (`DISPOSE_ON_CLOSE`) runs it outside the desktop. Registered in the start menu
  (**Media** group) via `audioplayer.lgcfg` in `lg3d-apps` `src/config`, with a
  programmatic music-note icon generated by `lg3d-art/tools/GenerateAppIcons`.
  Covered by 32 headless JUnit 5 tests plus a `Desktop2DAppRegistryTest` mapping
  assertion.
- **Video Player for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.videoplayer`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production VLC-style video player built as a single
  Swing panel, `VideoPlayerPanel`: a media library, transport controls and a
  status line. Because no video codec or embeddable playback engine ships
  in-tree, playback is delegated to an external player (`mpv` / `vlc` /
  `ffplay`, resolved by the AWT-free `VideoBackend` seam which builds the exact
  command line and probes availability), so the panel is an honest library +
  launcher rather than a fake decoder. The library and settings persist as JSON
  under `~/.lg3d/videoplayer` via `VideoPlayerStore` (Jackson), with defensive
  reads. The one panel serves both desktops: in 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `VideoPlayer` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`; a standalone `VideoPlayerClient` (`DISPOSE_ON_CLOSE`) runs
  it outside the desktop. Registered in the start menu (**Media** group) via
  `videoplayer.lgcfg` in `lg3d-apps` `src/config`, with a programmatic
  play-on-screen icon generated by `lg3d-art/tools/GenerateAppIcons`. Covered by
  22 headless JUnit 5 tests plus a `Desktop2DAppRegistryTest` mapping assertion.
- **Image Editor for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.imageeditor`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production GIMP-style raster editor built as a single
  Swing panel, `ImageEditorPanel`: a NORTH tool / colour / size toolbar, a CENTER
  scrollable canvas, an EAST layers dock (add / reorder / delete) and a SOUTH
  filter + undo/redo bar. It is **fully native Java 2D** — no codec, no
  third-party library, no Java 3D: the AWT-free `EditorDocument` / `ImageLayer`
  model composites an ordered layer stack, the pure `ToolEngine` draws (line,
  rect, ellipse, fill, text, erase), the pure `FilterEngine` returns a **new**
  image for every filter (brightness, contrast, grayscale, invert, sepia,
  threshold, blur, sharpen, edges) and a bounded `UndoStack` snapshots before each
  destructive edit so any stroke or filter can be undone. Load/save is `ImageIO`
  PNG; `JFileChooser` / `JColorChooser` are created lazily so the panel builds
  headless. The one panel serves both desktops: in 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `ImageEditor` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`; a standalone `ImageEditorClient` (`DISPOSE_ON_CLOSE`) runs
  it outside the desktop. Registered in the start menu (**Media** group) via
  `imageeditor.lgcfg` in `lg3d-apps` `src/config`, with a programmatic
  layered-sheets icon generated by `lg3d-art/tools/GenerateAppIcons`. Covered by
  38 headless JUnit 5 tests plus a `Desktop2DAppRegistryTest` mapping assertion.
- **Photo Viewer for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.photoviewer`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production tagged photo gallery and viewer built as a
  single Swing panel, `PhotoViewerPanel`: a WEST filter dock (tag combo,
  keyword, minimum rating), a CENTER split of a thumbnail gallery and a large
  preview with previous/next, and a SOUTH tag / rating editor. Decode is **fully
  native `ImageIO`** — no codec, no third-party library, no Java 3D: thumbnails
  and previews load lazily and every `ImageIO.read` is guarded so a missing or
  corrupt image shows a placeholder rather than throwing. The AWT-free
  `PhotoLibrary` model de-duplicates by path and answers a declarative
  `filter(tag, keyword, minRating)` query, while the tagged `PhotoItem` beans
  persist as JSON under `~/.lg3d/photoviewer` via `PhotoViewerStore` (Jackson,
  defensive reads). The one panel serves both desktops: in 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `PhotoViewer` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`; a standalone `PhotoViewerClient` (`DISPOSE_ON_CLOSE`) runs
  it outside the desktop. Registered in the start menu (**Media** group) via
  `photoviewer.lgcfg` in `lg3d-apps` `src/config`, with a programmatic
  landscape-photo icon generated by `lg3d-art/tools/GenerateAppIcons`. Covered by
  24 headless JUnit 5 tests plus a `Desktop2DAppRegistryTest` mapping assertion.
- **Audio/Video Recorder for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.recorder`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production capture tool built as a single Swing
  panel, `RecorderPanel`: an Audio tab (format + Record/Stop + elapsed), a Screen
  tab (source / size / fps / microphone + Record/Stop + elapsed) and an EAST
  capture-history dock. Microphone audio is captured **natively** to WAV via
  `javax.sound.sampled` (`TargetDataLine`); because the JDK has no screen encoder,
  screen capture is handed to an external `ffmpeg` / `avconv` writing an MP4
  (`x11grab`, optionally muxing the microphone via PulseAudio). The AWT-free
  `RecorderBackend` owns the WAV `AudioFormat`, the exact `x11grab` command line
  and the recorder resolution, and the screen process is stopped gracefully (`q`
  on stdin, then destroy) so the MP4 is finalised, not truncated. Settings and
  history persist as JSON under `~/.lg3d/recorder` via `RecorderStore` (Jackson,
  defensive reads); captures are written to the configured output folder (default
  `~/Recordings`). No capture device is opened and no process started until the
  user presses Record. The one panel serves both desktops: in 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `Recorder` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`; a standalone `RecorderClient` (`DISPOSE_ON_CLOSE`) runs it
  outside the desktop. Registered in the start menu (**Media** group) via
  `recorder.lgcfg` in `lg3d-apps` `src/config`, with a programmatic microphone
  icon generated by `lg3d-art/tools/GenerateAppIcons`. Covered by 24 headless
  JUnit 5 tests plus a `Desktop2DAppRegistryTest` mapping assertion.
- **Per-application About box for every 2D/Swing desktop window** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — a single reusable
  `AboutDialog` helper gives every panel application the 2D desktop hosts a
  consistent About box with no per-application code. Each `Desktop2DWindow` now
  builds a window menu bar carrying **Help → About&nbsp;&lt;App&gt;**, which opens
  a modal dialog showing the application icon, title and description read
  straight from the shared `.lgcfg` start-menu descriptor
  (`Desktop2DMenuConfig.ItemSpec`) — the same source that already feeds the menu
  label and window title — followed by a fixed author credit
  (**Jean-Francois Landreville**), the resolved build version and a
  *Part of Project Looking Glass* footer. `Desktop2D.openPanelApp` passes the
  descriptor's description into the window, and session restore resolves it back
  by command (`descriptionForCommand`) since the persisted `WindowRecord` stores
  only name/command/icon. The version is never hardcoded: it is resolved from the
  `lg.version` system property, falling back to the jar manifest and then
  `unknown`, so a version bump cannot miss it. All content logic lives in pure,
  headless-testable statics (`displayTitle`/`displayDescription`/
  `resolveVersion`/`creditLines`/`buildContent`); only `show` builds the modal
  dialog. Because the hook sits in the shared window, it reaches **all** PANEL
  (MDI) apps at once; SWING_FRAME apps (own top-level `JFrame`) and EXTERNAL apps
  are not covered by this central change. This is the per-application About box,
  distinct from the desktop-wide product About window
  (`org.jdesktop.lg3d.apps.about`). Covered by 17 headless JUnit 5 tests
  (`AboutDialogTest` — normalisation, the version fallback chain, credit lines
  and content assembly — and `Desktop2DWindowTest` — the metadata accessors and
  the Help → About menu bar on every constructor).
- **Firewall GUI for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.firewall`; registration in `lg3d-apps`) — a production
  firewall management application providing a Swing panel with status display,
  active rule table, and enable/disable controls. The panel auto-detects the
  firewall backend (firewalld via `firewall-cmd` preferred, falling back to
  iptables), queries status and rules every five seconds via `FirewallService`,
  and uses `pkexec` for privilege escalation on enable/disable operations. The
  same panel serves both desktops: in 3D the `Firewall` wrapper hosts it on a
  `SwingNode` inside a `Frame3D` via `TitledSwingWindow`. The app is registered
  in the start menu (**System** group) via `firewall.lgcfg` in `lg3d-apps`
  `src/config`. Known limitations: rule editing not implemented, no add/remove
  rule UI, fixed 5-second refresh interval.
- **Video Conference (Visioconférence) app for the 2D/Swing and 3D desktops**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.videoconference`; registration in
  `lg3d-core`, icon in `lg3d-core` resources) — a production **Jitsi Meet**
  conference client: a Swing lobby, address book and launcher. The user types or
  picks a room name, optionally sets a display name / e-mail and mute-on-join
  preferences, and presses **Join Meeting**; the client builds the correct Jitsi
  Meet deep link (`JitsiUrlBuilder`, with `#config.*` / `#userInfo.*` fragments
  and RFC 3986 percent-encoding) and hands it to the system browser
  (`java.awt.Desktop.browse`) or an external meeting command, where the real
  WebRTC audio/video session runs. It offers saved rooms (with per-room
  moderator / lock / mute overrides), contacts, a recent-call history and a
  settings dialog, plus a camera preview that degrades gracefully to an animated
  placeholder through the pluggable `CameraCapture` seam (no native A/V codec or
  embeddable HTML engine ships in-tree, so the browser owns the live session).
  Rooms, contacts, history and settings persist as JSON under
  `~/.lg3d/videoconference` via `VideoConferenceStore` (Jackson). The one panel
  serves both desktops: in 2D `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI
  internal frame, in 3D the `VideoConference` wrapper hosts it on a `SwingNode`
  inside a `Frame3D` via `TitledSwingWindow`; a standalone `VideoConferenceClient`
  (`DISPOSE_ON_CLOSE`) runs it outside the desktop. Registered in the start menu
  (**Internet** group) via `videoconference.lgcfg` in `lg3d-apps` `src/config`.
- **Instant Messenger for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.messenger`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production, multi-protocol chat client built as a
  single Swing panel, `MessengerPanel` (account list, per-conversation list,
  coloured `JTextPane` transcript, `/slash`-command input, status bar). It ships
  a complete **native IRC backend** (`IrcProtocol`, RFC 2812 over a plain or TLS
  `SSLSocket`, **no third-party dependency**): `PASS`/`NICK`/`USER` registration,
  nickname-in-use (`433`) recovery, server `PING` keep-alive and dead-link
  detection, `PRIVMSG`/`NOTICE`, CTCP `ACTION`/`VERSION`/`PING`/`TIME`,
  `JOIN`/`PART`/`QUIT`/`NICK`/`TOPIC`, `001`/`332`/`353`/`376` handling, optional
  NickServ `IDENTIFY`, 512-octet line splitting and mIRC formatting stripping
  (`IrcCodec`), plus automatic reconnect with backoff. Because "every known
  protocol" cannot honestly mean one in-tree implementation, the other networks
  (**XMPP, Matrix, Telegram, WhatsApp, Signal, SMS, SIP**) are first-class
  `BridgeProtocol` backends that hand a conversation to the system browser via a
  deep link or to an external command, and an extensible `ProtocolRegistry` SPI
  makes adding a native backend a one-line `register` call with no UI change.
  **Security:** server / NickServ passwords are `@JsonIgnore` and transient, so
  they live in memory only for the session and are **never written to disk**;
  with `useTls` the certificate chain is validated against the JDK trust store.
  Accounts, trimmed transcript history and settings persist as JSON under
  `~/.lg3d/messenger` via `MessengerStore` (Jackson), with defensive reads
  (missing/corrupt yields empty/defaults, never a throw) and every browser,
  clipboard and dialog path guarded on `!isHeadless()`. The one panel serves both
  desktops: in 2D `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal
  frame, in 3D the `Messenger` wrapper hosts it on a `SwingNode` inside a
  `Frame3D` via `TitledSwingWindow`; a standalone `MessengerClient`
  (`DISPOSE_ON_CLOSE`) runs it outside the desktop. Registered in the start menu
  (**Internet** group) via `messenger.lgcfg` in `lg3d-apps` `src/config`, with a
  programmatic chat-bubble icon generated by `lg3d-art/tools/GenerateAppIcons`.
  Covered by 92 headless JUnit 5 tests (`IrcMessageTest`, `IrcCodecTest`,
  `MessengerModelTest`, `MessengerStoreTest` — including the no-password-persisted
  assertion, `ProtocolRegistryTest`, `MessengerPanelTest`, and `IrcProtocolTest`,
  which drives the real socket against an in-JVM `ServerSocket` mock IRC server)
  plus a `Desktop2DAppRegistryTest` mapping assertion.
- **Backup & Restore tool for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.backup`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production data-protection utility built as a single
  Swing panel, `BackupPanel`: a WEST profile list (New / Duplicate / Delete), a
  CENTER editor (name, archive base name, destination chooser, source list with
  Add File / Add Folder / Remove, compression level, exclude patterns,
  include-hidden and timestamp-archive-name options) and a SOUTH action /
  progress / log strip (Back Up Now, Restore, Browse Archive, Cancel). Archives
  are standard **ZIP** files written with the JDK's built-in `java.util.zip`
  (**no third-party dependency and no native library**), so any unzip tool reads
  a backup and the tool restores any standard ZIP; the AWT-free `BackupEngine`
  owns all filesystem logic — walking sources, pruning glob excludes (matched
  against both the archive-relative path and the bare filename, with excluded
  directories skipped wholesale via `SKIP_SUBTREE`), optionally including hidden
  files, preserving empty directories, honouring an optional cancel signal and
  reporting per-entry progress through `BackupProgressListener`. **Security:**
  restore is **Zip-Slip hardened** — every entry is resolved against the
  normalised destination and rejected if it escapes (absolute names or `../`
  traversal), so a hostile archive can never overwrite files outside the chosen
  folder; unsafe entries are skipped with a logged warning rather than aborting
  the run. **Reliability:** a backup streams into a sibling `.part` file and is
  atomically moved onto the target only once complete, so an interrupted run
  never leaves a truncated `.zip`, and unreadable files are skipped with a
  warning. Reusable profiles persist as JSON under `~/.lg3d/backup` via
  `BackupStore` (Jackson), with defensive reads (missing/corrupt yields empty,
  never a throw); override the dir with `-Dlg3d.backup.dir`. The one panel serves
  both desktops: in 2D `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI
  internal frame, in 3D the `Backup` wrapper hosts it on a `SwingNode` inside a
  `Frame3D` via `TitledSwingWindow`; a standalone `BackupClient`
  (`DISPOSE_ON_CLOSE`) runs it outside the desktop. Registered in the start menu
  (**System** group) via `backup.lgcfg` in `lg3d-apps` `src/config`, with a
  programmatic archive-tray icon generated by
  `lg3d-art/tools/GenerateAppIcons`. Covered by 50 headless JUnit 5 tests
  (`BackupEngineTest` — including both Zip-Slip vectors, the atomic-write, glob
  exclude, hidden-file, empty-directory, compression-level, cancellation,
  listing and progress assertions — `BackupProfileTest`, `BackupStoreTest` and
  `BackupPanelTest`) plus a `Desktop2DAppRegistryTest` mapping assertion.

### Changed
- **Screen Snapshot now saves to `~/Documents/Screenshots` by default**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.screencapture`) — the screen-capture
  config frame seeded its save location with `System.getProperty("user.dir")`,
  the process working directory, which inside the desktop is the `lg3d-core`
  source tree — so snapshots landed as stray `lgscreen-*.png` files in the
  checkout instead of somewhere the user would look for them. The default is now
  `Screenshots` inside the user's `Documents` folder (`~/Documents/Screenshots`),
  resolved by the pure, headless-testable `defaultScreenshotDirectory()` helper
  and shown (still fully overridable) in the frame's location field. Both capture
  paths — the 2D/Swing `captureDesktopToPng` PNG writer and the 3D
  `ScreenCaptureEvent` handled by `ScreenCaptureBehavior` — now get a destination
  that is created on demand (`mkdirs`) before the first write, so a fresh profile
  with no `Screenshots` folder still captures instead of silently failing.
- **Agenda, Mail, Messenger and Video Conference now share one address book**
  (`lg3d-incubator` `org.jdesktop.lg3d.apps.orgchart.ui.agenda` +
  `org.jdesktop.lg3d.apps.mail`; `lg3d-apps`
  `org.jdesktop.lg3d.apps.messenger` / `.videoconference`) — every contact
  consumer was rewired from its own private or demo-seeded data to the shared
  `org.jdesktop.lg3d.contacts.ContactStore`. The agenda/mail `ContactDirectory`
  keeps its class name and API but is now a read-only adapter over the core
  store, so Agenda 3D / `AgendaPanel` attendees and Mail 3D / `MailPanel`
  recipients come from the real desktop-wide book with **no `java.util.prefs`
  `/contacts` reads and no bundled demo `contacts.xml` seeding on this path**.
  The fake free/busy presence flag was removed with it (an address book holds
  identity, not presence): agenda attendee chips and week-grid dots now show
  **known (green) vs unknown (grey)** instead of busy/free. The Video Conference
  contacts tab became a live view of the shared book — its private `Contact`
  model, `contacts.json` and the `loadContacts`/`saveContacts` store methods are
  gone, and add/edit/delete in the tab go straight to `ContactStore`, so an
  invitee added there is instantly visible in the Contacts app and the agenda.
  The Messenger grows a **Save** toolbar button that writes the selected
  private-chat peer into the shared book (tagged `messenger`, deduplicated
  case-insensitively on nickname/display name). Covered by updated headless
  tests (`ContactDirectoryTest`, `AgendaPanelTest`, `MailPanelTest`,
  `VideoConferencePanelTest`, `VideoConferenceStoreTest`,
  `MessengerPanelTest`) — all pointed at temp dirs, never the real
  `~/.lg3d/contacts`.
### Removed
- **The legacy read-only 2D contact card browser and the Contact 3D start-menu
  entry** (`lg3d-incubator`
  `org.jdesktop.lg3d.apps.orgchart.ui.contact.ContactCardsPanel` + its test;
  `lg3d-apps/src/config/orgchart-contact.lgcfg`) — superseded by the production
  Contacts app above. The native-3D `Contact3D` card browser stays in-tree but
  dormant (no descriptor); it and the Chart org app remain the only readers of
  the legacy `/contacts` Preferences tree, which the org chart still needs for
  its `manager` hierarchy attribute.
### Fixed
- **The Firewall start-menu entry no longer fails to load its icon**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.firewall`; icon in `lg3d-core`
  resources) — the Firewall manager was added to the desktop without its icon:
  `firewall.lgcfg` pointed `displayResourceUrlName` at a package-relative
  `resource:///org/jdesktop/lg3d/apps/firewall/resources/images/icon/firewall.png`
  that is not part of the assembled `resources/` runtime tree (and no such PNG
  was ever committed), so opening the start menu logged `Failed getInputStream`,
  a `NullPointerException` in `ResourceURLConnection.connect` and a
  JAI / `TextureLoader` cascade, and the menu item rendered with no icon. The
  descriptor now points at the shared
  `resource:///resources/images/icon/firewall.png` — the same convention every
  other start-menu app uses (e.g. `securitycenter.lgcfg`) — and a purpose-drawn
  running-bond brick-wall `firewall.png` (48×48 RGBA on a red tile) is generated
  into `lg3d-core/src/resources/images/icon` by
  `lg3d-art/tools/GenerateAppIcons` and assembled by
  `:lg3d-core:runtimeResources`.
- **The Remote Viewer's Exit button no longer tears down the whole desktop**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.remoteviewer`) — the ported jrdesktop GUI
  is hosted inside the lg3d desktop JVM (a 3D `Frame3D` via `TitledSwingWindow`
  and a 2D MDI internal frame via `Desktop2DAppRegistry.PANEL_APPS`), but its
  Exit button — together with the system-tray "Exit" (`Main.exit`) and the
  standalone `ViewerGUI` window-close — still ended in `System.exit(0)`, so
  quitting the Remote Viewer killed the entire desktop session instead of just
  its window. `RemoteViewerPanel` now exposes the canonical
  `setOnClose(Runnable)` host hook (wired directly by the 3D `RemoteViewer`
  wrapper and reflectively by the 2D `Desktop2DAppRegistry.setCloseCallback`);
  its confirmed-Exit action runs `exitApplication()`, which stops the RMI server
  if it is bound and then delegates the close to the host callback
  (`RemoteViewer.close()` → `frame.changeEnabled(false)` plus a singleton reset
  so a later launch reopens), never `System.exit`. `Main.exit()` (the tray path)
  now closes the hosted window and `ViewerGUI`'s close always `dispose()`s.
  The same fix relocates the app's persistent config / keystore (`config`,
  `server.config`, `viewer.config`, `keystore`, `truststore`) off the process
  working directory — which inside the desktop is the `lg3d-core` source tree,
  where `viewer.config` / `server.config` were landing a **plaintext password**
  in the checkout — to per-user `~/.lg3d/remoteviewer/`
  (`FileUtility.getConfigDirectory()`), and makes `Server`'s screen-capture
  `robot` lazy so merely loading `Server` (e.g. the panel's status refresh
  calling `isRunning()`) no longer requires a display / throws
  `HeadlessException`. Covered by headless JUnit 5 tests (`RemoteViewerPanelTest`
  pins construction, the host size, the `setOnClose` contract and that Exit runs
  the host callback rather than the JVM; a
  `Desktop2DAppRegistryTest#remoteViewerIsHostedPanel` classification case).
- **The Remote Viewer's live-viewing window is now hosted inside the desktop**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.remoteviewer`; new core API in
  `lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D`) — the main
  panel was already integrated, but on Connect the viewer opened its
  live-viewing UI as a `ViewerGUI extends JFrame` created eagerly by `Recorder`.
  A top-level `JFrame` is an OS window owned by the host window manager: it is
  neither a child of the 2D desktop's `JDesktopPane` nor part of the 3D scene, so
  the internal screen capture (which paints each top-level `Frame` into its own
  `lgscreen-<i>-*.png`) never showed the viewer in the desktop's
  `lgscreen-0-0.png`. The viewer content is extracted into a plain
  `ViewerPanel extends JPanel` (toolbar + `ScreenPlayer`, no window chrome) that
  a new `ViewerHost` embeds in whichever desktop is running: a 2D MDI internal
  frame via the new reusable `Desktop2D.openHostedPanel(title, icon, content)`
  (which returns null when no 2D desktop is live, so it is headless- and
  unit-test-safe), else a 3D `Frame3D` via `TitledSwingWindow`, else — standalone
  CLI `viewer` mode only — the thin `ViewerGUI` `JFrame` wrapper. `Recorder` no
  longer opens a window at construction (it just builds `viewerPanel`), and
  `Viewer.Start()` calls `ViewerHost.show(recorder)` then
  `recorder.viewerPanel.startRecording()`. The panel reports state upward through
  three host hooks (`setOnClose`, `setOnTitleChange`, `setOnMaximizeToggle`) that
  each host maps onto its own window idiom, so Close never disposes a stray frame
  or kills the JVM; **full-screen becomes maximize-within-desktop when hosted**
  (2D `JInternalFrame.setMaximum`, 3D `HostedWindowResizer.resize`), keeping
  exclusive OS full-screen for the standalone `JFrame` only. Supporting
  headless-safety: `ClipbrdUtility` initialises the system clipboard lazily,
  `HostProperties.getLocalProperties()` skips `Toolkit` screen metrics headless,
  and `ScreenPlayer` skips its `DropTarget` headless. Covered by headless JUnit 5
  tests (`ViewerPanelTest` pins that constructing the panel creates **no
  top-level `Window`** — the regression that caused this bug — plus the host-hook
  contract and that Close/maximize delegate to the host callback;
  `Desktop2DHostedPanelTest` asserts `openHostedPanel` returns null with no 2D
  instance).
- **The 2D start-up splash now paints instead of showing a grey rectangle**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.Desktop2DSplash`) — the splash
  added for the 2D/Swing desktop mapped its window and then immediately handed
  the event dispatch thread to the (EDT-blocking) desktop build, so the freshly
  mapped window's expose/paint was not dispatched until the build finished and
  the user saw only the window's bare grey peer background for the whole
  start-up. `show()` now sets an opaque white window background and forces one
  synchronous `paintImmediately` of the content before returning, so the product
  name and version are drawn into the peer before the build occupies the EDT;
  the window is tracked before the paint so a paint failure can never orphan a
  visible splash. The 3D splash path is untouched. Covered by a headless
  off-screen paint assertion in `Desktop2DSplashTest` (the card renders
  predominantly white with dark text, never a grey block).
- **The About box no longer advertises 3D or Sun Microsystems on the 2D/Swing
  desktop** (`lg3d-apps`, `org.jdesktop.lg3d.apps.about`) — the product About
  window (`AboutPanel`/`AboutInfo`) is shared by both desktops, so on the
  conventional 2D/Swing desktop it described an "immersive 3D desktop built on
  Java 3D", listed a **Java 3D** runtime row the 2D shell never loads, and
  credited the ported-base lineage to Sun Microsystems — none of which is true
  of the 2D desktop the user is actually running. `AboutInfo` now derives the
  desktop mode from the canonical `lg.fws.mode` property (reusing
  `DesktopMode.MODE_2D`/`MODE_SWING`, no 3D probe) and, when it resolves to the
  conventional 2D/Swing shell, serves 2D-specific variants: a "desktop
  environment for the Java platform" tagline and description with no "3D"
  claim, an omitted Java 3D runtime row, and attribution solely to
  **Jean-Francois Landreville** (no Sun Microsystems line). The mode selectors
  are pure, headless-testable helpers (`is2DMode`, `tagline`/`description`/
  `credits(boolean)`, `getFields(boolean)`); the 3D desktop's About window is
  unchanged and still carries the full description, Java 3D row and port
  lineage. Covered by new headless JUnit 5 cases in `AboutInfoTest`.
- **The Task Scheduler editor now lays out as a compact form in the Control
  Center** (`lg3d-apps`, `org.jdesktop.lg3d.apps.controlcenter.TaskSchedulerPanel`)
  — the editor column was a `GridLayout(0,1)`, which forces every row to an equal
  tall band and vertically centres each `FlowLayout` row inside it, so the form
  showed large empty gaps between Name / Enabled / Schedule and pushed the cron
  trigger card off the bottom edge (clipped mid-row). The editor is now a
  top-packed vertical `BoxLayout` in which each row keeps its own preferred
  height (capped via `setMaximumSize`) and stretches to the column width, so the
  fields read top-to-bottom and the whole card scrolls cleanly; the cron card's
  over-wide titled border was shortened and the preset / environment lists
  trimmed so the column fits without a spurious horizontal scrollbar.
- **The 2D quick-launch strip no longer shows red-X placeholders for
  family-matched apps** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d.AppIcons`) — the taskbar's pinned
  quick-launch strip renders its icons at 22px, but `AppIcons.semanticGlyph`
  passed that raw edge straight to `IconManager.loadIcon`, whose bundled
  `toolbarButtonGraphics` glyphs only ship at 16 and 24px (`loadIcon` builds
  `<name><height>.gif`), so every application whose name matches a semantic
  family (mail, browser, media, ...) silently resolved to IconManager's red-X
  `MissingIcon` on the strip while the 16px start menu was unaffected.
  `semanticGlyph` now loads a bundled edge and resizes it to the requested size
  with `IconManager.resizeIcon`, and treats a `MissingIcon` (unknown glyph
  name) as "no family glyph" so the entry falls back to its initials tile
  instead of a placeholder. Covered by
  `AppIconsTest#nonBundledSizeResizesGlyph`.
- **The Application Launcher now shows its rocket icon in the 2D/Swing desktop**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d.AppIcons`) — the 2D
  start menu, window frame and taskbar button showed a generated "AL" initials
  tile while the 3D desktop showed the launcher's real rocket artwork
  (`resources/images/icon/launcher.png` from `launcher.lgcfg`). The 2D desktop
  resolves every app icon through `AppIcons.iconFor`, which derives an
  IconManager glyph from the application *name* (or, failing a family match, an
  initials tile) and only used the descriptor PNG as a fallback when IconManager
  was absent — so at runtime, with IconManager present, the launcher's genuine
  artwork was never reached and "Application Launcher" (which matches no semantic
  family) fell through to the "AL" tile. `AppIcons` gains a small opt-in set of
  descriptor icon resources that are genuine per-application artwork
  (`DESCRIPTOR_ICON_PREFERRED`, currently the launcher rocket) and now loads that
  PNG first — via the same cached `Desktop2DStartMenu.icon` loader the fallback
  already used — so the 2D icon matches the 3D desktop exactly across the menu,
  frame and taskbar; if the artwork cannot be resolved it still degrades to the
  name-derived icon, never a blank entry. Every other app is unchanged (still
  name-derived). Covered by headless JUnit 5 tests in `AppIconsTest`.
- **Desktop widget layout now survives removal across a restart** (`lg3d-widgets`,
  `org.jdesktop.lg3d.widgets.api.WidgetConfigStore` plus the 2D
  `swing.SwingWidgetLayer` and 3D `host.WidgetHost` loaders) — removing every
  desktop widget (the starter clock and temperature, or any the user added) no
  longer resurrects them on the next launch. Previously both hosts' `loadPersisted`
  seeded the default widgets whenever the persisted instance list was empty, with
  no way to tell a genuinely fresh desktop (never configured, so the starters
  should appear) from one the user had deliberately emptied — so an emptied layout
  came back as clock + temperature at their **default** positions on restart,
  discarding both the removal and any positions the user had dragged them to.
  `WidgetConfigStore` gains a persisted `initialized` flag (`isInitialized()` /
  `markInitialized()`, written on the first load and surviving widget removal
  because it is not namespaced under an instance id); the defaults are now seeded
  only when that flag is absent, and once the layout is initialized an empty
  instance list is honoured as "the user removed everything". A layout written
  before the flag existed — or by the other desktop, since both share
  `~/.config/lg3d/widgets.properties` — is adopted and marked initialized so it is
  never reseeded. Drag-to-reposition and single-widget removal already persisted
  correctly and are unchanged. Covered by headless JUnit 5 regression tests in
  `SwingWidgetLayerTest` (an emptied layout stays empty, removing every widget
  survives a restart, and the first load persists the initialized flag).

## [1.28.0] — 2026-09-30 — Gradle / JDK 21 modernization

### Added
- **User launcher creator for the 2D/Swing desktop** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.launcher.LauncherFrame`; utilities in `lg3d-core`,
  `org.jdesktop.lg3d.utils.LauncherSaver`) — enables users to create custom
  application launchers through a Swing form (name, description, command, icon
  selection via JFileChooser). Saved launchers are written as `.lgcfg` files to
  `~/.config/lg3d/launchers/` and are automatically discovered by the desktop's
  start menu after a restart. The launcher creator is now registered in
  `Desktop2DAppRegistry.SWING_FRAME_APPS` so it appears in the start menu of both
  2D and 3D desktops. Previously, the icon picker and save functionality were
  stubs; they are now fully implemented with validation and user feedback.
- **Weather app for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.weather`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production weather reader rewritten for JDK 21 as an
  idiomatic Swing panel, `WeatherPanel`: a preset-city `JList`, a
  current-conditions card (place, large temperature, condition, high/low,
  feels-like / humidity / wind) with a hand-painted sky glyph, and a five-day
  forecast strip, plus `°C/°F` and Refresh controls and a status line. Weather is
  fetched from the free **Open-Meteo** forecast API through the AWT-free
  `OpenMeteo` seam — the same key-less data source the desktop Weather widget
  (`lg3d-widgets` `WeatherCard`) uses — over the JDK `java.net.http` client with a
  dependency-free recursive-descent JSON reader, so **no third-party library** is
  added; data is always requested in Celsius / km-h and converted for display, so
  toggling units never re-fetches, and fetches run on a background daemon
  scheduler (never the EDT) with a 15-minute re-poll and graceful "Unavailable"
  fallback offline. The one panel serves both desktops: in 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `Weather` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`. This replaces the dormant 2006-era `lg3d-incubator`
  `org.jdesktop.lg3d.apps.weather` stub (a green `Box` in a `Frame3D` plus a
  `JFrame` config editor, no descriptor and no data feed), which is removed. The
  app is registered in the start menu (**Utilities** group) via `weather.lgcfg` in
  `lg3d-apps/src/config` (bundled to `config/demo`, the scanned path), with a
  programmatic sun-behind-cloud icon generated by
  `lg3d-art/tools/GenerateAppIcons`. Covered by headless JUnit 5 tests
  (`OpenMeteoTest` exercises URL building, the JSON reader, the WMO
  condition/sky maps, unit conversion and document parsing with no network) and a
  `Desktop2DAppRegistryTest` assertion for the new panel mapping.
- **PDF Viewer for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.pdfviewer`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production document reader built as an idiomatic
  Swing panel, `PdfViewerPanel`: a toolbar (Open, first / previous /
  page&nbsp;n&nbsp;of&nbsp;N / next / last, zoom out / percent / zoom in / fit
  width), a scrollable page canvas that centres the rendered sheet when it is
  smaller than the viewport, and a status line naming the file, page and zoom.
  Pages are rasterised by the AWT-free `PdfDocument` seam on the open-source
  **Apache PDFBox** (`org.apache.pdfbox:pdfbox`, Apache-2.0):
  `Loader.loadPDF` + `PDFRenderer.renderImageWithDPI` at `72 * zoom` DPI with
  1-based page numbers. The one panel serves both desktops: in 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `PdfViewer` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`. This replaces the dormant 2006-era native-3D prototype,
  which rendered through the commercial **JPedal** library (absent, not on Maven
  Central) and whose click-only 3D chrome nested a second frame and offered no
  usable navigation; that package is removed. PDFBox is an `lg3d-apps`
  dependency and — because the desktop run classpath is hand-assembled from
  packaged jars — is resolved onto `:lg3d-core:run` (and the `releaseBundle`)
  through the `pdfboxLibs` detached configuration, otherwise the in-JVM launch
  would die with `NoClassDefFoundError: org/apache/pdfbox/Loader`. The app is
  registered in the start menu (**Media** group) via `pdfviewer.lgcfg` in
  `lg3d-apps/src/config` (bundled to `config/demo`, the scanned path), with a
  programmatic document-page icon generated by `lg3d-art/tools/GenerateAppIcons`.
  Covered by headless JUnit 5 tests (`PdfDocumentTest` and `PdfViewerPanelTest`
  build a throwaway multi-page PDF in memory with PDFBox — no bundled sample —
  and assert open / page-count / clamped paging / zoomed raster size and the
  panel's navigation, zoom and status readouts; a `Desktop2DAppRegistryTest`
  mapping assertion).
- **Power/session actions in the 2D desktop context menu** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — the desktop background
  right-click menu grows a power/session group between the window-arrangement
  entries and Refresh: **Lock Screen**, **Suspend**, a separator, then
  **Reboot...** and **Shut Down...**. A new `SessionPowerStatus` seam follows the
  established `PrinterStatus` / `TimeZoneStatus` shape: the command builders are
  pure and unit-tested (`lockCommand` prefers `loginctl lock-session`, falling
  back to `xdg-screensaver lock`, else null; `suspend`/`reboot`/`poweroff` map to
  their `systemctl` verbs), the `available()` probes are thin wrappers over
  `PrinterStatus.exec`, and the actions are fire-and-forget child processes
  launched like `Desktop2DAppRegistry.launchExternal` (the `DISPLAY` inherited,
  the output drained, no exception propagated). Each entry is availability-gated
  exactly like the existing Terminal item, so on a host without
  `loginctl`/`systemctl`/`xdg-screensaver` it simply does not appear; Reboot and
  Shut Down ask for confirmation (the `confirmExit()` pattern) while Lock and
  Suspend act immediately. Covered by headless JUnit 5 tests
  (`SessionPowerStatusTest` for the builders and lock fallback precedence;
  `Desktop2DContextMenuTest` extended for the new entries' presence, ordering
  and gating).
- **Periodic Table Swing panel for the 2D desktop** (`lg3d-incubator`,
  `org.jdesktop.lg3d.apps.periodictable`; registered in `lg3d-core`'s
  `Desktop2DAppRegistry`, descriptor in `lg3d-apps/src/config`) — the
  native-3D `PeriodicTable3D` app (untouched) gains a pure-Swing counterpart,
  `PeriodicTablePanel`, that renders the standard 18x7 grid plus the detached
  lanthanide and actinide rows, colours each cell by category with a legend,
  shows the mass and category as a hover tooltip, and fills a detail line under
  the grid when an element is clicked. All 118 elements (with modern symbols and
  names — Copernicium rather than the 2006 "Uub", plus Flerovium, Livermorium,
  Nihonium, Moscovium, Tennessine and Oganesson) are embedded as a compact
  string constant parsed by a pure static helper into an immutable `Element`
  record list, and the grid placement is a pure `gridPosition` function; the
  panel uses a null layout with explicit `setBounds` and a fixed preferred size
  (the `CalculatorPanel`/`ChessPanel` SwingNode-offscreen rule). The one
  start-menu descriptor (keyed on the 3D main class) now launches the panel as an
  MDI frame in the 2D desktop while the 3D desktop keeps building the `Frame3D`.
  Because discovery only scans `config/demo` and `config/incubator` — and the
  incubator's own `src/config` bundles to the unscanned jar-root `config/` — the
  descriptor lives in `lg3d-apps/src/config` (bundled to `config/demo`),
  following the Chess 3D / Agenda 3D precedent, so the app is genuinely reachable
  from the start menu for the first time. The entry files under a new
  **Education** category: the group is declared in
  `lg3d-core/src/etc/lg3d/startmenu.lgcfg` (a `StartMenuGroupConfig` plus a link
  from `Main`, shared by the 3D and the 2D start menus) — an item naming an
  undeclared group would otherwise be appended to the menu root as an orphan
  instead of under its category. Covered by headless JUnit 5 tests
  (`PeriodicTablePanelTest` for the 118-element parse, contiguous atomic numbers,
  categories, layout and selection; a `Desktop2DAppRegistryTest` mapping
  assertion).

## [1.27.0] — 2026-09-26 — Gradle / JDK 21 modernization

### Added
- **Frosted-glass taskbar shelf and a rounded-corner toggle** (`lg3d-core`,
  `org.jdesktop.lg3d.scenemanager.utils.taskbar` / `utils.decoration` /
  `utils.shape` / `utils.prefs`; `lg3d-apps` control center) — the **Frosted
  (GPU)** window-glass style now extends to the glass taskbar shelf, not just
  window decorations. In both the active `GlassyTaskbar` and the
  (currently descriptor-disabled) `AdvancedGlassyTaskbar`, when the frosted
  style is chosen and the shader program assembles the shelf becomes a
  frosted slab: a square `FrostedGlassPanel` quad for the top face (the bar's
  pickable hover surface) plus a second frosted quad rotated into the
  viewer-facing front edge, so the bar keeps the classic shelf's visible
  thickness while staying the same transparent, frosted glass as the windows.
  Both quads sit in a `TransparencyOrderedGroup` for deterministic
  back-to-front blending and resize in place via `setSize`, so a live Shape3D
  child is never removed; with shaders unavailable or the classic style
  chosen, the original `GlassyPanel` box is built exactly as before — the
  classic path is untouched. Applying the Window-glass choice in the Control
  Center now restyles the running desktop **immediately**: both shelf styles
  are built up front and parked in a scene-graph `Switch` (same for the
  window-decoration glass bodies), and the Apply button persists the choice
  and posts a `DesktopConfigChangeEvent` that flips every open window and the
  bar in place — no restart, no "applies to newly opened windows". The
  `-Pshaders` dev flag pre-selects the frosted style in memory only, and
  `FrostedGlassPanel` gained a live `setCornerRadius` (uniform write) so the
  rounded-corner toggle also takes effect on the fly.
  To make the frost work on a square panel, `FrostedGlassPanel`'s frosted-edge
  band is decoupled from the corner radius (new `frostBand` `create` overloads
  plus a clamped, headless-tested `clampFrostBand`): previously the band was
  derived as `radius x 0.6`, so a zero radius collapsed the frost to nothing and
  left a flat, uniform tint. Frosted window decorations now pass the band
  explicitly too, so toggling rounded corners off yields square corners that
  still show the frosted edges. A new **Rounded corners** On/Off selector joins
  the Window Glass section of the Control Center's *Appearance* panel (3D-only,
  a `JList` for the offscreen `SwingNode`), persisted as `window.roundedCorners`
  on `DesktopConfig` (default **on**, matching the look the frosted windows have
  always had); it applies to window decorations only — the bar shelf keeps
  square corners, and the classic `GlassyPanel` path is always square and
  unaffected. Covered by headless JUnit 5 tests
  (`DesktopConfigWindowGlassTest`, extended to 6 for the rounded-corners default
  / round-trip / reset; `FrostedGlassPanelTest`, extended to 7 for the frost-band
  clamp).
- **FTP / FTPS / SFTP file-transfer client** (`ftp-client`, a new self-contained
  Gradle module `org.jdesktop.lg3d.ftpclient`, wrapped by an `lg3d-apps` host
  shim) — a protocol-neutral, FileZilla-style transfer client surfaced as the
  **FTP Client** start-menu app (Internet group), hosted on a `SwingNode` in the
  3D desktop (`FtpClient` → `TitledSwingWindow`) and as an MDI internal frame in
  the 2D desktop (`Desktop2DAppRegistry.PANEL_APPS` → `FtpClientPanel`). It
  manages site profiles over **FTP**, **FTPS** (explicit/implicit TLS via Apache
  Commons Net) and **SFTP** (SSH via the JSch *mwiede* fork, trust-on-first-use
  host-key verification against `~/.lg3d/ftpclient/known_hosts`), browses the
  local filesystem and the remote server side by side, and moves files through a
  queue that retries with an exponential backoff, resumes from a partial
  destination (FTP `REST` / SFTP skip-or-append), reports byte-level progress and
  honours a cooperative cancel. Security posture: FTPS/SFTP are the secure
  defaults, plain FTP is visibly flagged insecure, passwords are **not** stored
  unless the user opts in (and then only obfuscated, `obf1:`, with a warning),
  and every blocking network call runs off the EDT on a `SwingWorker`. The module
  has no `lg3d-core` dependency (mirroring `db-manager`); the `:lg3d-core:run`
  and `releaseBundle` classpaths add its jar plus commons-net / jsch / jackson /
  slf4j / bouncycastle, and a programmatic 48x48 glass-tile start-menu icon with
  an upload/download transfer glyph is generated by
  `lg3d-art/tools/GenerateAppIcons`. Covered by headless JUnit 5 tests (138 in
  `ftp-client` — in-JVM MockFtpServer for FTP, a mocked JSch `ChannelSftp` for
  SFTP — plus a `FtpClientPanelTest` host-shim construction test and a
  `Desktop2DAppRegistry` panel-mapping assertion); a live FTPS/SFTP transfer is
  manual smoke evidence, since no external server runs in CI.
- **Four new fully-controllable Control Center system panels over new platform
  seams** (`lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — the Control Center grows from thirteen
  categories to seventeen by surfacing four standard system settings, each backed
  by a new pure platform seam that follows the established `PrinterStatus` /
  `NetworkConnections` shape (parsing, formatting and command-building are pure
  and unit-tested; the public probes degrade to empty / false / a read-only note
  when the tool, hardware or X server is absent rather than throwing). Four new
  panels — **Date & Time** (`TimeZoneStatus` over `timedatectl`: a time-zone list
  + Set and an NTP toggle), **Language & Region** (`LocaleStatus` over
  `localectl`: the current LANG + a locale list + Set, with an “applies to new
  sessions” note), **Mouse & Keyboard** (`InputSettings` over `xset`: pointer
  acceleration/threshold and key-repeat rate/delay presets + a repeat toggle, with
  a Wayland warning) and **Bluetooth** (`BluetoothStatus` over
  `bluetoothctl`/`rfkill`: a power toggle, controller and device lists with
  Connect/Disconnect, and a hard-/soft-block note when no adapter is present) —
  are pure Swing (`JList` / `JCheckBox` / `JButton` only, never a combo box or raw
  key-capture, so they keep working hosted offscreen in a `SwingNode`) and actually
  change OS settings rather than only displaying them; these persist in the OS
  through their own tools, not in `DesktopConfig`. All four are registered in
  `ControlPanelRegistry` in the final seventeen-panel order, and the
  `controlcenter` `AGENTS.md` was refreshed (the stale panel list and the
  “tabbed”/`JTabbedPane` and “UI-only, no persistence” claims were corrected to
  the real `JList` + `CardLayout` shell, the `ControlPanel` interface and the seam
  inventory). Covered by headless JUnit 5 tests (`TimeZoneStatusTest` 5,
  `LocaleStatusTest` 5, `InputSettingsTest` 6, `BluetoothStatusTest` 6, and one
  construction smoke test per panel — `DateTimePanelTest` 4, `LocalePanelTest` 4,
  `InputPanelTest` 5, `BluetoothPanelTest` 4).
- **Five new fully-controllable Control Center panels over existing seams**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d`,
  `org.jdesktop.lg3d.utils.prefs`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — the Control Center grows from eight
  categories to thirteen by surfacing capabilities the desktop already had. Five
  new panels — **Sound**, **Power**, **Notifications**, **Workspaces** and
  **Shortcuts** — are pure Swing (`JList` / `JCheckBox` / `JButton` /
  `JTextField` only, never a combo box or raw key-capture, so they keep working
  hosted offscreen in a `SwingNode`) and actually change settings rather than
  only displaying them. *Sound* drives the master volume and mute through the
  `VolumeStatus` seam; *Power* shows the battery (`BatteryStatus`), a brightness
  preset list driving the `BrightnessStatus` backlight seam and the thermal
  sensors (`ThermalService`) on a 2 s refresh; *Notifications* and *Workspaces*
  reach the live 2D shell — Do Not Disturb, the notification log, the workspace
  count and workspace switching — through new public EDT-safe `Desktop2D` hooks
  that no-op / return an empty snapshot in 3D mode or headless and persist to
  `DesktopConfig` where a key exists; *Shortcuts* rebinds the global keys through
  a new `DesktopConfig` `shortcuts.custom` override (`ShortcutMap.mergeBindings`
  overlays it on the built-in defaults) applied live via
  `Desktop2D.applyShortcuts()`. To expose these, `VolumeStatus.setVolume`/
  `setMuted` and `BrightnessStatus.setBrightness` were widened from
  package-private to `public` (bodies unchanged), and every panel degrades to an
  explanatory read-only / empty state when its backing hardware, tool or 2D shell
  is absent. Covered by headless JUnit 5 tests (`ShortcutMapTest` +5,
  `DesktopConfigShortcutsTest` 7, `Desktop2DControlHooksTest` 6, and one
  construction smoke test per panel — `SoundPanelTest` 4, `PowerPanelTest` 4,
  `NotificationsPanelTest` 4, `WorkspacesPanelTest` 4, `ShortcutsPanelTest` 6).
- **Schedule-based daylight/nightlight wallpapers** (`lg3d-core`,
  `org.jdesktop.lg3d.utils.schedule`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — an opt-in, time-of-day wallpaper
  scheduler customizable from the Control Center. `DesktopConfig` gains seven
  persisted keys (`schedule.enabled`, daylight/nightlight hour+minute, and the
  daylight/nightlight wallpaper filenames; default off, 07:00 → 20:00, clamped
  to valid ranges). `ScheduleService` is a daemon-timer service that re-checks
  every minute, resolves the wallpaper for the current time (correctly handling
  a daylight window that crosses midnight) and applies it — through a
  `BackgroundChangeRequestEvent` on the 3D desktop and `Desktop2D.setWallpaper`
  on the conventional 2D/Swing desktop — only when it changes. It is started
  from `Desktop2D.show()` so a persisted schedule is honoured at login, and the
  new Control Center **Schedule** panel (registered after Printing) lets the
  user enable it, set both transition times and pick each wallpaper from the
  bundled background collection, with an “Apply Now” shortcut. The pure
  clock-decision logic is covered headlessly; the live wallpaper application is
  verified at runtime.
- **Daylight/nightlight scene lighting on the schedule** (`lg3d-core`,
  `org.jdesktop.lg3d.utils.schedule`, `scenemanager.utils.globallights`,
  `displayserver.desktop2d`; `lg3d-apps` control center) — the daylight/nightlight
  schedule now modulates the desktop's *lighting*, not just its wallpaper. On the
  native 3D desktop, `StandardGlobalLights` keeps its ambient + key + fill lights
  reachable and `ALLOW_COLOR_WRITE`-capable so `ScheduleService` can re-tint them
  live between a daylight palette (the historical rig, so daytime is unchanged)
  and a dimmer, cooler nightlight palette. On the conventional 2D/Swing desktop —
  which has no scene graph — a `NightTintOverlay` on the desktop pane's popup layer
  paints a cool translucent veil over the wallpaper and windows (the taskbar stays
  crisp). Both are driven by one pure, clock-injected blend factor
  (`DayNightCurve.dayFactor`) that ramps smoothly across a configurable window
  centred on each transition and handles a daylight window crossing midnight.
  A new `schedule.rampMinutes` preference (default 30, 0–180) sets the fade width.
  The wallpaper and lighting schedules are now **independent on/off toggles**
  (`schedule.wallpaperEnabled` / `schedule.lightingEnabled`), so either, both or
  neither can run off the same daylight/nightlight times, and switching lighting
  off restores neutral daylight. All of it is exposed in the Control Center
  **Schedule** panel, which also gained a fix for the daylight/nightlight time
  spinners previously sharing one model. The curve, config clamping, toggle
  independence and veil painting are covered headlessly; the live 3D light re-tint
  is verified at runtime.
- **GPU shader foundation + soft drop shadows for the native 3D desktop**
  (`lg3d-core`, `org.jdesktop.lg3d.utils.shape`; `org.jdesktop.lg3d.sg`) — the
  first increment of the 3D-modernization roadmap: an opt-in GLSL effect library
  that starts using the programmable-shader path the port inherited from Java 3D
  1.7 but never exercised (until now only `cdviewer`'s hard-coded `dimple` demo
  touched it, and even that had its uniforms commented out). `ShaderEffects` is a
  small factory over the `sg` facade's `ShaderAppearance`/`GLSLShaderProgram`/
  `SourceCodeShader` plumbing that loads GLSL from classpath resources
  (`utils/shape/resources/`, the same model as `dimple.vert`) and is gated behind
  a new runtime toggle `lg.shaders` (default **off**), so the desktop renders
  pixel-identically through the fixed-function path until the effects are turned
  on and live-verified; a `null` program (missing resources, or no GL context —
  e.g. the headless unit-test JVM) makes every caller fall back to the legacy
  widget rather than throwing mid-scene-graph-build. The first effect,
  `SoftShadow`, supersedes the 2006 baked `RectShadow` behind `Frame3D` windows:
  instead of a 28-vertex ring of Gouraud-interpolated per-vertex alpha, a single
  quad runs an SDF (signed-distance-field) fragment shader that ramps the shadow
  alpha from the window edge out to transparent across a per-side penumbra
  (`uSoftNESW`), giving a smooth, resolution-independent falloff that stays a
  constant world width at any window size and resizes in place (`uHalfWin` is
  written live, matching `RectShadow.setSize`). `Frame3DWindowDecoration` now
  prefers `SoftShadow` when `lg.shaders` is on and keeps `RectShadow` otherwise.
  Enabling uniforms at all required completing the facade: `ShaderAttributeSet`,
  `ShaderAttributeValue`, `ShaderAttributeObject` and `ShaderAttribute` had
  `createWrapped()` stubs that threw `"Not Implemented"` and no `j3dwrapper`
  delegates, so a facade shader could compile a program but had no way to pass a
  uniform value — those delegates are now implemented against the raw jogamp
  objects (this is why `dimple` baked its uniforms as constants). The pure seams
  (`lg.shaders` parsing, the uniform binding order, the missing-resource
  fallback and the shadow-quad layout math) are covered by headless JUnit 5 tests
  (`ShaderEffectsTest` 4, `SoftShadowTest` 3), and the GLSL render itself is
  verified by an offscreen Java 3D probe through the *facade* path (dark core →
  smooth penumbra ramp → background, pixel-sampled).
- **GPU frosted-glass rounded panel for the native 3D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.utils.shape`) — the second increment of the 3D-modernization
  shader roadmap, built on the same opt-in `ShaderEffects` GLSL factory and
  `lg.shaders` gate as `SoftShadow`. `FrostedGlassPanel` is the additive, modern
  alternative to the 2006 `GlassyPanel` (left untouched — it has ~65 callers): a
  single quad whose per-fragment colour and alpha come from the exact signed
  distance to a rounded rectangle, so fragments outside the silhouette are
  discarded (genuinely round, anti-aliased corners rather than a tessellated box
  with hard aliasing edges), a thin `fwidth`-driven band smooths the border at any
  zoom, and a frosted band just inside the edge lightens the tint and lifts its
  opacity toward the clear-glass centre. Like `SoftShadow` it is a non-pickable
  `Shape3D` sized in place (`setSize` rewrites the vertex buffer and the live
  `uHalfWin` uniform), needs `ShaderEffects.frostedGlassProgram()`, and is created
  through `FrostedGlassPanel.create(...)` which returns `null` on a missing
  program (no GL context, resources unavailable) so a caller can fall back to a
  fixed-function `GlassyPanel` rather than throwing mid-scene-graph-build; a caller
  that adopts it as a pickable window body must call `setPickable(true)` —
  `Frame3DWindowDecoration` now does exactly that, preferring the frosted body
  over the fixed-function `GlassyPanel` when `lg.shaders` is on (re-enabling
  pickability so the flip/rotate gesture handle survives) and keeping
  `GlassyPanel` otherwise; the `:lg3d-core:run` task grew a `-Pshaders` passthrough
  so the GLSL path can be turned on for live verification. The pure
  seams (the frosted-glass uniform binding order, the missing-resource fallback and
  the quad-layout / corner-radius clamp math) are covered by headless JUnit 5 tests
  (`ShaderEffectsTest` +2, `FrostedGlassPanelTest` 4).
- **Window-glass style choice in the Control Center (Appearance)** (`lg3d-core`,
  `org.jdesktop.lg3d.utils.prefs`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — frosted-vs-glassy is now a user
  setting, not just a launch flag: `AppearancePanel` grew a "Window Glass"
  section with a two-entry `JList` (Glassy (classic) / Frosted (GPU)) and an
  Apply button that persists the new `DesktopConfig` preference
  (`window.frostedGlass`, `isFrostedGlass`/`setFrostedGlass`, default Glassy).
  `Frame3DWindowDecoration` reads it when it builds a window body, so the choice
  applies to newly opened windows on the 3D desktop; the `-Pshaders` dev flag
  still force-enables the whole shader path. Covered by headless JUnit 5 tests
  (`DesktopConfigWindowGlassTest` 3).
- **Printing and network management in the Control Center, on both desktops**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — two new category panels, registered
  in every desktop mode so the same management UI is reachable from the 3D
  desktop and the conventional Swing (2D) desktop. *Printing* lists the CUPS
  queues (`lpstat -a`), marks the system default and offers Set Default
  (`lpoptions -d`) and Print Test Page (`lp`); *Network* lists the saved
  NetworkManager connections with type and active device (`nmcli -t connection
  show`) and offers Connect / Disconnect (`nmcli connection up/down`). Both are
  pure Swing over two new platform seams that follow the established
  probe+pure-parse shape (`PrinterStatus`, `NetworkConnections`: parsing,
  formatting and command-building are pure and unit-tested; the probes degrade
  to empty / false on a host without CUPS / NetworkManager rather than
  throwing). Covered by headless JUnit 5 tests (`PrinterStatusTest` 6,
  `NetworkConnectionsTest` 6).
- **Metal theme manager for the conventional 2D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps`,
  `org.jdesktop.lg3d.apps.controlcenter`) — the 2D desktop can now change and
  create Swing *Metal* themes, and the Control Center's Appearance panel shows a
  "Metal Theme" manager in place of the classic/frosted window-glass selector
  (which is 3D-only, driving `Frame3D` decoration, and is now gated to the 3D
  desktop). The manager lists the built-in *Steel* and *Ocean* palettes plus any
  user themes, and offers Apply / New… / Delete: *New…* derives a full palette
  from a name and one accent colour (`JColorChooser`), and *Delete* removes a
  user theme (built-ins are protected). A *System Look* button reverts the shell
  to the native platform look-and-feel (GTK/Synth) it started on, clearing the
  persisted selection so the next start-up keeps the native look too. Because
  the 2D shell starts on the
  platform look-and-feel (GTK/Synth) and Metal themes only affect Metal,
  applying a theme switches the shell onto Metal with the chosen palette and
  refreshes every open window live. The selection and the encoded custom-theme
  list persist through two new `DesktopConfig` preferences (`metal.theme`,
  `metal.customThemes`), and `Desktop2D.reapplyConfig()` re-applies the stored
  theme at start-up — a no-op while none is chosen (the blank default), so the
  desktop keeps its native look until the user picks one. The pure seam
  (`MetalThemeSpec`: the palettes, the compact encode/decode serialisation and
  the accent derivation) and the `CustomMetalTheme` bridge are display-
  independent; covered by headless JUnit 5 tests (`MetalThemeSpecTest` 7,
  `DesktopConfigMetalThemeTest` 4).
- **Global keyboard shortcuts + Alt+F2 run dialog for the native 3D desktop**
  (`lg3d-core`, `org.jdesktop.lg3d.scenemanager.utils.run`;
  `org.jdesktop.lg3d.displayserver.desktop2d`) — Alt+F2 raises a translucent
  "run command" card on the HUD layer that resolves a typed application name or
  external command and launches it through `AppLaunchAction`; Ctrl+Alt+T opens
  the first terminal emulator on the PATH. The first Phase-5 feature ported off
  the taskbar, without touching `GlassyTaskbar`; it is the `Frame3D` counterpart
  of the 2D desktop's `KeyEventDispatcher` + `RunDialog`. The seams are *not*
  re-invented: `RunDialogPlugin` reuses the shared `ShortcutMap` binding table,
  the `RunResolver` decision table and the persisted `RunHistory`/
  `RunHistoryStore`, so both desktops share one shortcut table, one resolver and
  one command history (those 2D classes were widened from package-private to
  `public` *in place* to expose the seam). The dialog itself is the
  headless-testable pure-Swing `RunDialogPanel` hosted on the HUD by
  `RunDialog3D` on a `SwingNode`. Because a hosted `SwingNode` only delivers
  `KeyEvent3D` while it holds pointer-following lg3d focus, the plugin owns one
  global key listener and, while the card is up, marshals every keystroke onto
  the EDT straight to `RunDialogPanel.dispatch` instead of relying on AWT focus;
  a `volatile` showing flag is flipped synchronously on the lg3d thread so a
  keystroke arriving right after Alt+F2 still routes to the card. The plugin
  claims only the run-dialog and open-terminal bindings — the snap, show-desktop,
  window-close and workspace bindings stay with `WindowSnapPlugin` and
  `WorkspacePlugin`, so the plugins never double-bind. `getPluginRoot()` returns
  null because the card is parented to the HUD layer (registered after
  `DesktopHudPlugin` in `glassy.lgcfg` so its `layer()` is live at init). Covered
  by headless JUnit 5 tests (`RunDialogPanelTest` 17: type/backspace, up/down
  history recall, submit resolve/not-found/persist, Escape close, ellipsize and
  dispatch filtering).
- **Alt+Tab-style window switcher for the native 3D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.scenemanager.utils.switcher`;
  `org.jdesktop.lg3d.displayserver.desktop2d`) — Ctrl+Alt+Tab steps forward and
  Ctrl+Alt+Shift+Tab steps backward through the open `Frame3D` windows in
  most-recently-used order, showing a translucent card of window titles on the HUD
  layer and committing the highlighted window to front on a short idle timer. The
  third window-management feature ported off the taskbar, without touching
  `GlassyTaskbar`; it is the `Frame3D` counterpart of the 2D desktop's
  `WindowCyclerOverlay`. The MRU list and cycle state machine are *not*
  re-invented: the package-private 2D `WindowCycler` was generalized to a `public`
  generic `WindowCycler<T>` *in place* (the 2D desktop keeps
  `WindowCycler<Desktop2DWindow>`, the 3D one uses `WindowCycler<Frame3D>`), so both
  share one model. The plugin tracks the live window set through
  `Frame3DAddedEvent`/`Frame3DRemovedEvent` and the MRU order through
  `Component3DToFrontEvent`, and binds the trigger on a global `KeyEvent3D`
  listener. Plain Alt+Tab is deliberately not used (the host window manager grabs
  it in dev mode) and committing is an idle timer rather than a key release
  (modifier-release detection is unreliable), exactly mirroring the 2D overlay's
  proven semantics. The card lists window *titles* (highlighted selected row) —
  the same information the 2D overlay's icon+name rows convey — rather than live
  3D thumbnails, because a `Frame3D`'s `Thumbnail` is a single-parented
  `Component3D` already owned by the taskbar and cannot be reparented onto the HUD
  without stealing it from the bar. Covered by headless JUnit 5 tests
  (`WindowSwitcherKeysTest` 4, `WindowSwitcherPanelTest` 11, plus the generalized
  `WindowCyclerTest`) and probe-verified on the live desktop (three real windows
  tracked in MRU order, trigger opens the session and steps the highlight, the
  card paints with the correct selected row at each step, and the 600 ms
  idle-commit brings the selection to front and hides the card, with `lgscreen`
  captures at each step).
- **Edge window snapping for the native 3D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.scenemanager.utils.snap`; `org.jdesktop.lg3d.displayserver.desktop2d`)
  — drag a `Frame3D` so its left / right / top edge reaches the matching screen
  edge and, on release, it fills that half of the usable area (or the whole area
  at the top), with a translucent preview quad on the HUD layer lighting the
  target while the drag is over it. The second window-management feature ported
  off the taskbar, without touching `GlassyTaskbar`; it is the `Frame3D`
  counterpart of the 2D desktop's `SnappingDesktopManager` + `SnapPreview`. The
  zone vocabulary and the half/maximise target rule are *not* re-invented: the
  package-private 2D `WindowSnap` model was promoted to `public` *in place* with
  float (`zoneForRect`/`boundsForRect`/`centreOf`) overloads that both desktops
  now share, and the pure world-space plumbing (`WindowSnap3D`) maps a dragged
  window's centre translation + preferred size + uniform scale onto that model,
  on a usable region that mirrors the decoration's maximise (screen minus the
  taskbar's reserved strips and title-bar headroom). Because
  `Component3DMover` posts a `Component3DManualMoveEvent` only on drag start and
  release (it calls `setTranslation` directly during the drag), `WindowSnapPlugin`
  (registered in `glassy.lgcfg` right after `WorkspacePlugin`) arms a short poll
  timer on drag start to track the preview and commits on release exactly the way
  the decoration maximises: a hosted Swing window is resized in native pixels
  (`HostedWindowResizer`) so its text stays crisp, a pure-3D window is uniformly
  scaled with an aspect-preserving fit, then both re-centre and come to front.
  Covered by headless JUnit 5 tests (`WindowSnap3DTest`, 10; extended
  `WindowSnapTest` with the float-rect cases) and probe-verified on the live
  desktop (LEFT / RIGHT / MAXIMIZE resolve, preview visible mid-drag, committed
  extents matching the usable rect, with `lgscreen` captures at each step).
- **Multiple workspaces (virtual desktops) for the native 3D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.scenemanager.utils.workspace`; `org.jdesktop.lg3d.displayserver.desktop2d`)
  — the first window-management feature ported off the taskbar, without touching
  `GlassyTaskbar`. `WorkspacePlugin` (registered in `glassy.lgcfg` right after
  `ToastOverlayPlugin`, so `DesktopHudPlugin.layer()` is live at init) partitions
  the open `Frame3D`s of the single `StandardAppContainer`: each frame is tracked
  through `Frame3DAddedEvent`/`Frame3DRemovedEvent`, assigned to the workspace that
  is current when it opens, and shown or hidden with `setVisible` on every switch.
  The pure bookkeeping lives in `WorkspaceRegistry` (a window id → visibility-sink
  map on top of the desktop-agnostic `WorkspaceModel`, promoted to `public` *in
  place*), whose one rule is "a registered window is visible exactly when it sits
  on the current workspace"; every mutator re-applies it, so a switch can never
  show a window on two workspaces or hide one on the workspace being entered. The
  multi-`AppContainer` plumbing was evaluated and rejected (its own comments say
  `setCurrentAppContainer` does not add the container to the scene graph,
  `setEnabled` only flips a flag, and `StandardAppContainer.initialize()` registers
  global to-front listeners that would migrate frames back into container 0). A
  clickable HUD pager (`WorkspacePager3D`, a `SwingNode` over the pure-Swing
  `WorkspacePagerPanel`: one numbered cell per workspace with per-workspace window
  dots, flanked by prev/next arrows) is mounted in the top-left corner of the HUD
  layer; keystrokes mirror the 2D desktop's `ShortcutMap` — Alt+Shift+Page
  Down/Page Up page, Alt+Shift+1..9 move the front window — because the host window
  manager grabs Ctrl+Alt+arrow in dev mode. Activating a window from the
  (workspace-unaware) taskbar follows it to its workspace instead of focusing an
  invisible frame, and teardown releases every window so none stays hidden.
  Covered by headless JUnit 5 tests (`WorkspaceRegistryTest` 18, `WorkspaceKeysTest`
  9, `WorkspacePagerPanelTest` 16) and probe-verified on the live desktop
  (open/switch/move/pager-click/release with `lgscreen` captures at each step).
- **Calendar widget for the native 3D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-widgets`,
  `org.jdesktop.lg3d.widgets.builtin`; `lg3d-incubator` Agenda 3D) — the 2D
  taskbar clock's calendar popup is now a placeable built-in widget on the 3D
  desktop (gallery → *Clock*), the counterpart of the 2D `CalendarPopup`. It lays
  the month out as the same ISO Monday-first grid, highlights today, tints
  statutory holidays (red) and weekend days (blue), steps between months with the
  `<` / `>` header arrows or the mouse wheel, and — on a double-click of a day
  cell — opens the Agenda 3D app navigated to that day's week. It reuses the *same*
  pure `CalendarModel` grid and `HolidayCalendar` region/holiday classifier the 2D
  popup uses (both promoted to `public` *in place*, no files moved), so both
  desktops mark identical days from the identical persisted preference. Like every
  built-in card it is pure Swing (no Java 3D): the 3D `CalendarWidget` hosts it on
  a `SwingNode` and wires the double-click to launch
  `java …agenda.Agenda3D <ISO-date>` in-JVM through the same `AppLaunchAction` the
  start menu uses (loose coupling — no compile dependency on the incubator app);
  `Agenda3D.main` parses the optional date and the new `AgendaGrid.jumpToDate`
  centres that week. Interaction is driven from `mouseClicked` (a hosted
  `SwingNode` panel reliably receives `MOUSE_CLICKED` with the click count
  preserved, but not always the `PRESSED`/`RELEASED` pair a `JButton` needs), and
  the pixel→date / pixel→nav hit-tests are pure statics. Covered by headless
  JUnit 5 tests (`CalendarCardTest`, 12; the extended catalogue/provider/registry
  tests now expect seven built-ins).
- **System-indicator widget for the native 3D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-widgets`,
  `org.jdesktop.lg3d.widgets.builtin`) — network link, master volume, screen
  brightness and battery charge now appear on the 3D desktop as a placeable
  built-in widget (gallery → *System*), the counterpart of the 2D/Swing taskbar's
  `TaskbarIndicators`. It reuses the *same* pure platform seams — `NetworkStatus`,
  `VolumeStatus`, `BrightnessStatus` and `BatteryStatus` — so both desktops read
  and format the hardware identically; to let the widget card import them those
  package-private seams were promoted to `public` *in place* (no files moved).
  `SystemIndicatorsCard` polls every 5 s into volatile fields and paints one row
  per present indicator (accent bullet, glyph text, and a percentage bar for
  volume/brightness/battery); each indicator hides itself when its probe reports
  nothing (no sound card / backlight / battery, non-Linux host), the network row
  always shows (online/offline) and a fully-barren host falls back to a single dim
  "No indicators" row. The pure row assembly (`SystemIndicatorsCard.rows`) is
  covered by headless JUnit 5 tests (`SystemIndicatorsCardTest`, 8; the extended
  catalogue/provider/registry tests now expect six built-ins).
- **Notification toasts on the native 3D desktop** (`lg3d-core`
  `org.jdesktop.lg3d.scenemanager.utils.hud`; `lg3d-widgets`
  `org.jdesktop.lg3d.widgets.hud`) — the first end-to-end feature ported from the
  2D/Swing desktop onto the front-most HUD layer, without touching `GlassyTaskbar`.
  `NotificationService` (lg3d-core) is the 3D desktop's notification entry point:
  a thread-safe singleton wrapping the existing 2D models (`NotificationModel` log,
  `ToastQueue` transient popups, `DoNotDisturb` gate), so a notification posted
  from any thread is logged, gated (errors always pass DND) and raised as a toast.
  To reuse them from the 3D plugins those package-private models were promoted to
  `public` *in place* (no files moved). `ToastOverlayPlugin` (lg3d-widgets,
  registered from `glassy.lgcfg` right after `DesktopHudPlugin` so its `layer()`
  is live at init) mounts a `ToastOverlay3D` on the HUD layer: a pool of up to four
  fixed-size, opaque, mouse-transparent `ToastCard3D` cards (accent stripe by
  `Notification.Kind`, bold title, dimmed body, each a `SwingNode`) anchored in the
  bottom-right, newest at the bottom and stacked upward. A repeating EDT timer
  polls `visibleToasts()` for the auto-fade and a service listener refreshes
  immediately on post/dismiss. The pure stacking-layout math and the service's
  log/toast split, DND gate, TTL expiry and dismissal are covered by headless
  JUnit 5 tests (`NotificationServiceTest`, 15; `ToastOverlay3DTest`, 4).
- **Front-most desktop HUD overlay layer for the native 3D desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.scenemanager.utils.hud`) — foundation for porting the
  2D/Swing desktop's transient overlay chrome (notification toasts, the Alt+Tab
  window switcher, the run dialog, the desktop context menu, the window snap
  preview, the workspace pager and the brightness dim) to the 3D desktop without
  touching `GlassyTaskbar`. `DesktopHudPlugin` (registered from `glassy.lgcfg`)
  contributes a screen-sized `DesktopHudLayer` that floats *in front of* every
  application window: it is lifted toward the eye to `eye.z * 0.4` and its node
  scale is multiplied by the perspective-compensation factor `r = (eye.z - frontZ)
  / eye.z`, mirroring `StartMenuModel.compensatedFrontPose`, so it still spans the
  whole screen at natural size while sorting over a maximized window. Overlays are
  placed by fractional screen coordinates (like the behind-apps `WidgetLayer`) and
  z-stacked for concurrent popups; the live layer is reachable in-JVM via
  `DesktopHudPlugin.layer()`. The pure front-pose, placement and on-screen clamp
  math is covered by headless JUnit 5 tests (`DesktopHudLayerTest`).
- **Double-click a calendar day to open the Agenda at that date** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-incubator` Agenda panel) —
  in the 2D/Swing desktop, double-clicking a day cell in the taskbar clock's
  calendar popup now opens (or brings forward) the Agenda app navigated to the
  week containing that day, with the creation cursor placed on it. The day cells
  are plain `JLabel`s, not `MenuElement`s, so the popup's `MenuSelectionManager`
  leaves them alone and never dismisses on a click over them; they therefore
  receive the physical mouse events through normal Swing dispatch and can see the
  second click of a double-click. Because `lg3d-core` cannot depend on
  `lg3d-incubator`, `Desktop2D.openAgendaAt` reaches the hosted `AgendaPanel`
  through a new reflective `Desktop2DAppRegistry.showDate` hook (mirroring
  `setCloseCallback`) that calls the panel's new public `jumpToDate(LocalDate)`;
  a panel without the hook is left untouched. Covered by headless JUnit 5 tests
  (extended `CalendarPopupTest`, `Desktop2DAppRegistryTest` and
  `AgendaPanelTest`).
- **Weekend and holiday marking in the 2D/Swing desktop Agenda** (`lg3d-incubator`,
  `org.jdesktop.lg3d.apps.orgchart.ui.agenda`) — the Swing `AgendaPanel` week grid
  (the 2D/Swing counterpart of the native-3D `AgendaGrid`) now colours weekends and
  statutory holidays to match the 3D app: each column is anchored to a real date,
  weekend columns get a blue wash, holiday columns a red wash, and the current
  week's today column an accent bar, with a header legend keying the three. Holiday
  and weekend classification is routed through the shared public
  `org.jdesktop.lg3d.utils.prefs.HolidayRegions` seam backed by the bundled
  `jbusinessday` library (`libs/jbusinessday-0.9.1-SNAPSHOT.jar`), honouring the
  same persisted, locale-resolved region the taskbar calendar and the 3D agenda use
  (Control Center → Desktop, or `-Dlg.agenda.holidayRegion` as an explicit
  override) and re-reading it per repaint so a region change applies without a
  restart; the classification degrades to plain untinted columns if the library is
  ever absent. Covered by headless JUnit 5 tests (extended `AgendaPanelTest`, +5).

### Changed
- **The Schedule's wallpaper and lighting schedules are now fully independent**
  (`lg3d-core`, `org.jdesktop.lg3d.utils.schedule` / `utils.prefs`; `lg3d-apps`
  control center) — the daylight/nightlight schedule no longer conflates a
  wallpaper swap with a lighting fade sharing one pair of times. The **wallpaper
  schedule** becomes a dynamic, ordered list of `(time -> wallpaper)` entries the
  user adds or removes freely (two, four, ten…), modelled by a new pure
  `ScheduleEntry` value type and persisted under indexed `schedule.wp.count` /
  `schedule.wp.<i>.{hour,minute,file}` keys; `ScheduleService.resolveWallpaper`
  now picks the latest entry at or before the clock, wrapping to the previous
  day's last entry before the first of the day (replacing the fixed two-slot
  day/night pick). The **lighting schedule** keeps its day/night fade but gains
  its **own** `schedule.lightingDawn{Hour,Minute}` / `schedule.lightingDusk*`
  times alongside the existing `schedule.rampMinutes`, so it no longer borrows
  the wallpaper times. The Control Center **Schedule** panel is rebuilt as two
  separately bordered sections: a wallpaper entry list with Add/Remove and a
  per-entry time + wallpaper editor, and a photo-free lighting section with
  dawn/dusk/transition spinners only (still `JList`, never a combo box, for the
  offscreen `SwingNode`). Legacy daylight/nightlight preferences are migrated
  into the entry list on first load rather than discarded. Covered by headless
  JUnit 5 tests (`DesktopConfigScheduleTest`, `ScheduleServiceTest` and a
  two-section `SchedulePanelTest` layout check).

## [1.14.0] — 2026-09-25 — Gradle / JDK 21 modernization

### Added
- **Generated application icons for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — the start menu, the window
  frame and the taskbar button of an application now share one icon resolved
  deterministically from its name through the bundled IconManager library
  (`libs/IconManager-1.6.0.jar`). A name that clearly matches a known family
  (mail, help, media, database, browser, preferences, terminal, text, ...)
  takes the matching toolbar glyph; every other application gets a distinctive
  glass tile carrying its initials in a stable colour derived from its name, so
  two different apps never share an icon and the same app always resolves to the
  same one. The resolution logic lives in a Java 3D-free `AppIcons` seam; the
  library is a compile-only dependency placed on the desktop *run* classpath by
  the build (and shipped in the release bundle), and every call degrades to the
  legacy descriptor PNG — then to a placeholder tile — if it is ever absent, so
  the desktop still starts. Covered by headless JUnit 5 tests (`AppIconsTest`,
  7).

- **Software brightness fallback and tray gauges for the 2D/Swing desktop**
  (`lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d`) — the taskbar
  brightness slider now works even on hosts whose hardware backlight is not
  controllable unprivileged (a root-owned `/sys/class/backlight` node with no
  polkit agent). `BrightnessStatus.setBrightness` is layered (direct sysfs write,
  then a user-space helper — `brightnessctl`/`light`/`xbacklight` — then at most
  one `pkexec` escalation per run) and verifies the write by reading the node
  back, so a silently-refused value reports failure; `isControllable` lets the
  shell detect the case. When the hardware refuses, `Desktop2D` installs a
  `BrightnessDimmer` glass pane that paints a translucent wash over the whole
  desktop (opacity growing as brightness falls) while never intercepting input
  (`contains()` is always false), and the requested percentage sticks instead of
  snapping back to the unchanged hardware reading. The battery and brightness
  indicators also gain small IconManager progress gauges, and the battery gauge
  grades green→orange→red as the charge drains (`BatteryStatus.color`). Covered
  by headless JUnit 5 tests (`BrightnessDimmerTest`, 4; extended
  `BatteryStatusTest`, `BrightnessStatusTest` and `TaskbarIndicatorsTest`).

- **Multiple workspaces for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — the desktop now groups its MDI
  windows into several virtual workspaces (four by default). A `WorkspacePager`
  of numbered buttons on the taskbar (left of the system indicators) highlights
  the current workspace and shows each one's window count; clicking a button, or
  `Alt+Shift+PageDown`/`PageUp`, switches workspace, and `Alt+Shift+1..9` moves
  the focused window. Switching hides the windows on the workspace left and
  shows those on the one entered (frames are only made invisible, never
  disposed); new windows open on the current workspace, and both the window
  switcher and the taskbar window buttons list only the current workspace's
  windows. Following the codebase's headless-testable split, all the logic — the
  workspace count (clamped 1..9), the current index with wrapping
  next/previous/switchTo, and the window-to-workspace assignment — lives in a
  pure `WorkspaceModel` seam keyed by the window's app name, while `Desktop2D`
  drives the MDI frame visibility and the taskbar from it. The count persists in
  `DesktopConfig` (`workspace.count`). Covered by headless JUnit 5 tests
  (`WorkspaceModelTest`, 16; extended `ShortcutMapTest` and `ShortcutsTest` for
  the new bindings); the Help Center *The 2D and Swing Desktops* topic documents
  the feature.

- **Wallpaper slideshow for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps` Appearance panel) —
  the desktop backdrop can now cycle automatically instead of showing one image.
  The Control Center's *Appearance* panel gains a **Wallpaper Slideshow** section
  (a `JList` on/off selector, a `JList` interval from 10 seconds to an hour, and
  a folder chooser) that drives the running shell through new `Desktop2D`
  statics; the slideshow cycles the images directly inside the chosen folder, or
  the wallpapers bundled with the shell when no folder is set. The on/off,
  interval and folder persist in `DesktopConfig` (`wallpaper.slideshowEnabled`,
  `wallpaper.slideshowIntervalSec`, `wallpaper.slideshowFolder`) and are restored
  on start. Following the codebase's headless-testable split, the cycling
  arithmetic (wraparound next/previous/at, defensive on empty and single-image
  lists) lives in a pure `WallpaperSlideshow` seam and the folder scan in a
  static `Desktop2D.scanFolder`, while a `javax.swing.Timer` owned by `Desktop2D`
  advances the model and calls the existing `setWallpaper(URL)`; the timer starts
  and stops with the shell. On the 3D desktop the new controls are inert. Covered
  by headless JUnit 5 tests (`WallpaperSlideshowTest`, `Desktop2DWallpaperScanTest`,
  `DesktopConfigSlideshowTest`); the Help Center *The 2D and Swing Desktops* topic
  documents the feature.

- **Do Not Disturb for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — a DND switch that suppresses
  the transient notification toasts without dropping anything from the log: the
  `NotificationModel` still records every notification (so the tray/history and
  the calendar agenda stay complete), only the pop-up is gated. `ERROR`
  notifications always surface, so a genuine failure is never silenced. DND is
  toggled from the notification-tray popup (an on/off checkbox plus a "for 1
  hour" entry) or from the desktop right-click context menu, and while active the
  tray button is prefixed with a `[DND]` marker. The state (on/off and an
  optional absolute deadline) persists in `DesktopConfig`
  (`notifications.dndEnabled` / `notifications.dndUntil`) and is restored on
  startup, where an already-expired deadline comes back off rather than stuck.
  Following the codebase's headless-testable split, the state machine and the
  suppression matrix live in a pure `DoNotDisturb` seam that takes the current
  time rather than reading a clock, apart from the thin `NotificationTray` /
  `Desktop2DContextMenu` wiring. Covered by headless JUnit 5 tests
  (`DoNotDisturbTest`, 11; extended `NotificationTrayTest` and
  `Desktop2DContextMenuTest`).

- **Screen-brightness control for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — a brightness glyph joins the
  taskbar's system-indicator cluster, beside the volume control. It renders a
  compact `Bri 60%` label with a detailed tooltip, and clicking it opens a small
  slider popup that drives the panel backlight, polled on the existing slow
  (5 s) indicator timer. Following the codebase's headless-testable split, the
  scaling/formatting lives in a pure `BrightnessStatus` seam (raw↔percentage
  maths, the sysfs parse, the glyph/label) over `/sys/class/backlight/*`, apart
  from the thin Swing `TaskbarIndicators` wiring whose `applyBrightness` takes an
  already-read value. The write is best-effort — it tries the sysfs node
  directly and escalates once through `pkexec` only if it is not user-writable —
  and, as with the volume/battery/network probes, a host with no controllable
  backlight (a desktop, non-Linux, headless CI) simply hides the glyph rather
  than showing garbage. Covered by headless JUnit 5 tests
  (`BrightnessStatusTest`, 8; extended `TaskbarIndicatorsTest`).

- **Help Center coverage for the 2D/Swing desktop features** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.help`) — the JavaHelp user guide now documents the
  desktop conveniences added this cycle. *The 2D and Swing Desktops* topic gains
  sections for type-to-search Start Menu filtering, edge window snapping, the
  **Alt+`** window switcher, the global keyboard-shortcut table, the **Alt+F2**
  run-command dialog, notifications and toasts, the volume / network / battery
  taskbar indicators, the clock's calendar popup and session restore; *The
  Taskbar* and *The Start Menu* topics cross-link to it, and *Built-in
  Applications* now lists the **About** app. The keyword index gains an entry for
  each concept. This is a content-only change — no new topic — so `map.jhm`,
  `toc.xml` and the `HelpContentTest` target set are unchanged, and the full-text
  search index is regenerated at build time by
  `:lg3d-apps:generateHelpSearchIndex`.

- **Alt+F2 run-command dialog for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — pressing `Alt+F2` opens a
  lightweight run box that launches a start-menu application by name or runs an
  external command, with `Enter` to run, `Escape` to close and `Up`/`Down` to
  recall the last 20 entries (persisted beside the saved session in the user
  preferences). The decision logic splits into a pure, headless-testable
  `RunResolver` (exact case-insensitive app-name match → that `ItemSpec`, else an
  available external command → `Desktop2DAppRegistry.launchExternal`, else "not
  found") and a `RunHistory` model (add/promote/trim plus a URL-encoded persisted
  form) behind a `RunHistoryStore` seam (`PrefsRunHistoryStore`); the `RunDialog`
  itself is a `JPopupMenu`, so the whole widget constructs headless. Covered by
  headless JUnit 5 tests (`RunResolverTest`, `RunHistoryTest`).

- **Calendar popup for the 2D/Swing desktop clock** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — clicking the taskbar clock now
  opens a calendar above it: a month grid laid out ISO Monday-first with today
  highlighted and neighbouring-month days padded/dimmed, `«`/`»` buttons to step
  through the months, and a small "agenda" footer listing the notifications
  raised today (reusing the desktop's `NotificationModel`), or "No events today".
  Following the codebase's headless-testable split, the date maths lives in a
  pure `java.time` `CalendarModel` (`firstCellOfMonth`, the 6×7
  `weeksOfMonth` grid, `cellDate`, `isToday`/`isInMonth`, the month `title`) that
  takes an injected `Clock`, apart from the thin Swing `CalendarPopup` view whose
  clock is injectable and whose agenda filter is a pure static helper — so the
  grid layout (including leap-year February and every start-weekday) and the
  popup's month navigation are covered without a display. Covered by headless
  JUnit 5 tests (`CalendarModelTest`, 10; `CalendarPopupTest`, 6 — 16 tests
  total).

- **Holiday and weekend marking in the 2D/Swing desktop calendar** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`; `lg3d-apps` Control Center) — the
  clock's calendar popup now tints statutory holidays (red) and weekend days
  (blue) in its month grid, classified by the bundled `jbusinessday` library
  through a new region-aware `HolidayCalendar` seam (memoised per year, and
  defensive: a missing `jbusinessday`/slf4j runtime or an unknown region simply
  leaves the day untinted rather than breaking the calendar). The region defaults
  to `AUTO` — resolved from the system locale (Canada → Canadian federal,
  otherwise US federal) — and is user-configurable via a new *Calendar holiday
  region* list in the Control Center's **Desktop** panel, persisted in
  `DesktopConfig` (`calendar.holidayRegion`) beside the other desktop settings;
  explicit tokens cover `US`, `CA`, `CA:<PROVINCE>` (e.g. `CA:QUEBEC`) and
  `US:<STATE>`. The region-token grammar and the `jbusinessday` calls live in a
  new shared public `HolidayRegions` helper (`lg3d-core`,
  `org.jdesktop.lg3d.utils.prefs`) that `HolidayCalendar` delegates to, so the
  orgchart **Agenda** grid (`lg3d-incubator`) now marks the *same* holidays from
  the *same* persisted, locale-resolved preference instead of its old
  US-defaulting `lg.agenda.holidayRegion` system property — which is still
  honoured as an explicit override and re-read on each redraw, so a Control
  Center change applies to both surfaces without a restart. `jbusinessday` +
  slf4j move onto lg3d-core's compile/test classpath (previously
  run-classpath-only). Covered by headless JUnit 5 tests (`HolidayRegionsTest`,
  10; `HolidayCalendarTest`, 5).

- **Global keyboard shortcuts for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — a set of desktop-wide key
  bindings that work wherever the desktop frame has the focus: **Ctrl+Alt+D**
  show-desktop (minimise all), **Alt+Shift+←/→** snap the focused window to the
  left/right half and **Alt+Shift+↑** maximise it (reusing the drag-snap
  geometry), **Ctrl+Alt+T** open a terminal, **Ctrl+W** close the focused window,
  and **Alt+F2** reserved for the run-command dialog. `Desktop2D` installs a
  `KeyEventDispatcher` on the `KeyboardFocusManager` that only acts while its own
  frame is frontmost, resolves the press through the table and consumes it; the
  Alt+` window switcher is deliberately *not* in the table, so it falls through
  untouched, and combos the host window manager grabs (Super+key, Ctrl+Alt+arrow)
  are avoided. Following the codebase's headless-testable split, the table
  (`ShortcutMap`: keystroke-spec → action id, with tolerant parsing) and the
  resolve-and-dispatch switch (`Shortcuts.handle` / `Shortcuts.dispatch` over a
  small `Shortcuts.Target` interface) are pure, so a fake target records which
  action fired without a focus manager or display; `Desktop2D` supplies the real
  target. Covered by headless JUnit 5 tests (`ShortcutMapTest`, 11;
  `ShortcutsTest`, 7 — 18 tests total).

- **Taskbar system indicators for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — a volume / network / battery
  cluster in the taskbar's right-hand row, left of the clock, in the style of a
  conventional system tray. Each indicator renders a compact text glyph
  (`Vol 42%` / `Vol x` when muted, `Net ==` ethernet / `Net ))` wi-fi / `Net --`
  offline, `Bat 85%` with a `+` while charging) plus a detailed tooltip; clicking
  the volume glyph opens a small slider popup that drives the master gain, and two
  `javax.swing.Timer`s keep the readings live (1 s for volume, 5 s for
  network/battery). Every probe degrades gracefully — no sound card, no battery or
  a non-Linux host simply hides that glyph rather than showing garbage — and the
  cluster is stopped with the bar on shutdown. Following the codebase's
  headless-testable split, the parsing/formatting lives in pure seams
  (`BatteryStatus` reading `/sys/class/power_supply/BAT*`, `NetworkStatus` walking
  `NetworkInterface`, `VolumeStatus` over the `javax.sound.sampled` master port)
  apart from the thin Swing `TaskbarIndicators` panel, whose `applyX` methods take
  an already-read value so they are driven headless. Covered by headless JUnit 5
  tests (`BatteryStatusTest`, 7; `NetworkStatusTest`, 8; `VolumeStatusTest`, 7;
  `TaskbarIndicatorsTest`, 6 — 28 tests total).

- **Type-to-search start menu for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — the start menu now opens with a
  search field across the top: typing filters every configured application live
  to a flat, ranked list, and clearing the field restores the normal category
  tree. Ranking is predictable — an exact name match beats a name prefix, which
  beats a word-boundary match ("man" in "File Manager"), which beats a plain name
  substring, which beats a match only in the description or command — with ties
  keeping descriptor order and results capped at 12 so the popup stays short.
  Enter launches the top match and Esc closes the menu; each open starts fresh
  from the full tree with the caret in the field. Following the codebase's
  headless-testable split, the matching lives in a pure `AppSearch` (no Swing, no
  I/O) and the popup wiring in `StartMenuSearch`, which reuses the existing
  `Desktop2DStartMenu` item renderer and category-tree builder. Covered by
  headless JUnit 5 tests (`AppSearchTest`, 9; `StartMenuSearchTest`, 6).




- **About** (`lg3d-apps`, `org.jdesktop.lg3d.apps.about`) — a new *Utilities*
  start-menu application showing the product identity, the resolved build
  version, the host runtime facts (Java 3D provider, Java version/vendor,
  platform) and the attribution / licence text. Following the
  `Calculator`/`HelpCenter` pattern it splits into a headless model
  (`AboutInfo`), a plain Swing panel (`AboutPanel`) and a 3D wrapper (`About`,
  via `TitledSwingWindow`), so the same panel is hosted on a `SwingNode` in the
  3D desktop and as an MDI internal frame in the 2D/Swing desktop (registered in
  `Desktop2DAppRegistry.PANEL_APPS`). The version is not hardcoded: it resolves
  from a new `lg.version` system property (set by `:lg3d-core:run` to the
  canonical `project.version`), falling back to the lg3d-apps jar manifest
  `Implementation-Version` (stamped from `project.version`) and then `unknown`.
  Covered by headless JUnit 5 tests (`AboutInfoTest`, `AboutPanelTest`, plus a
  new `Desktop2DAppRegistryTest` case).

- **Window snapping for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — dragging an application window
  to a desktop edge snaps it on release: the left/right edges tile the window to
  that half and the top edge maximises it, with a translucent preview overlay
  showing the target rectangle while the pointer is in the edge zone (48px by
  default, configurable). The behaviour is added by replacing the desktop pane's
  single-icon `DesktopManager` with a `SnappingDesktopManager` that extends
  `DefaultDesktopManager`, so minimise-still-hides-the-desktop-icon is preserved.
  The logic splits into a pure, headless-testable `WindowSnap` (edge-zone
  detection + snap rectangles), a `SnapPreview` (translucent highlight painted on
  the pane's palette layer) and the `SnappingDesktopManager` glue; because
  `DefaultDesktopManager.dragFrame` renders through `Graphics.copyArea` /
  `setXORMode` and needs a realized pane, the manager exposes the decision steps
  as `updateSnap` / `takePendingZone` / `applyPendingSnap` so the tests drive the
  snap logic headless without invoking super's rendering. Covered by headless
  JUnit 5 tests (`WindowSnapTest`, 12 tests; `SnapPreviewTest`, 6 tests;
  `SnappingDesktopManagerTest`, 11 tests).
- **Window switcher for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — a keyboard window cycler that
  raises a translucent overlay listing the open application windows in
  most-recently-used order and steps a highlight through them. The trigger is
  **Alt+` (Alt+grave)** forward and **Alt+Shift+`** backward — deliberately
  *not* Alt+Tab: the 2D desktop is an ordinary window under the host window
  manager (GNOME/Mutter, Xwayland), which grabs Alt+Tab, Super+Tab and the
  Ctrl+Alt+arrow workspace keys before they reach the JVM, so those never
  arrive. Alt+grave is the conventional in-application window-cycle shortcut,
  is not reserved by the common host WMs and clashes with no Swing/MDI default.
  Because modifier-release detection is unreliable across platforms, the
  selection commits itself on a short idle timer once the user stops pressing
  (no separate confirm key, and no global binding is placed on Escape/Enter).
  The logic splits into a pure, headless-testable `WindowCycler` (MRU tracking +
  cycle state machine) and a `WindowCyclerOverlay` (key bindings + painting on
  the desktop pane's popup layer) driven by a fakeable `WindowSource` seam;
  `Desktop2D` installs it, feeds it MRU touch/forget events from `track()`, and
  gains a non-toggling `focusWindow()` so committing always raises the window
  rather than minimising it. Covered by headless JUnit 5 tests
  (`WindowCyclerTest`, 15 tests; `WindowCyclerOverlayTest`, 11 tests).
- **Session restore for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — the desktop now remembers which
  application windows were open when it last exited and reopens them, at their
  saved size and position and in their saved minimised/maximised state, on the
  next start. The session is captured on every window open/close and once more on
  exit (so the final placement is what is stored), and is persisted through the
  same user `java.util.prefs` store the desktop configuration and widget layer
  use, so it survives a restart. Restoring relaunches each saved app quietly
  (a failure is logged, never shown in a modal, so an app that has since become
  unavailable cannot greet the user with a dialog at startup) and clamps every
  restored window back on-screen, so a session saved on a larger or differently
  arranged monitor never strands a window off-screen; windows are relaunched
  back-to-front so the original stacking order is preserved. Only windows with a
  start-menu descriptor command (the panel apps) are saved, so an ad hoc window
  that cannot be relaunched is skipped rather than persisted unrestorable. The
  logic splits into pure, headless-testable pieces — `WindowRecord` (one window's
  identity, bounds and state, plus the on-screen clamping), `SessionSnapshot`
  (the ordered set and its tolerant single-string encode/decode, where a corrupt
  or partial value degrades to empty instead of throwing) and `SessionManager`
  (capture plus save/load) behind a fakeable `SessionStore` seam — with
  `PrefsSessionStore` the thin preferences-backed implementation and `Desktop2D`
  the relaunch glue. Covered by headless JUnit 5 tests (`WindowRecordTest`,
  12 tests; `SessionSnapshotTest`, 9 tests; `SessionManagerTest`, 10 tests).
- **Notification area & toasts for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — a taskbar notification tray and
  transient toast popups, the 2D desktop's counterpart of a system notification
  area. A new taskbar button ("Notifications", carrying a live unread badge)
  opens a popup listing the notifications raised so far — newest first, each
  tinted by severity, click to dismiss one, "Clear all" to empty the log — and
  opening it marks everything read. Raising a notification also pops it as a
  toast card stacked up from the bottom-right of the desktop, which fades on its
  own after a few seconds or dismisses on a click. The toast overlay spans the
  desktop pane's popup layer but overrides `contains(x, y)` to claim only the
  pixels a card covers, so a lingering toast never steals clicks from the windows
  below. As the first real producers, launching a `SWING_FRAME` or `EXTERNAL` app
  — which opens *beside* the desktop with no taskbar button of its own — now
  raises an info notification so the user sees it started. Following the
  codebase's headless-testable split, the logic lives in pure, clock-injectable
  classes (`Notification` value + severity `Kind`, the capped observable
  `NotificationModel` log, the expiring `ToastQueue`, the `NotificationColors`
  palette) apart from the Swing glue (`ToastLayer` overlay, `NotificationTray`
  button/popup); `Desktop2D` owns the model and overlay, installs the overlay on
  the popup layer, exposes `raiseNotification()` (plus a static
  `postNotification()` that no-ops when the 2D desktop is not running, mirroring
  `applyDesktopConfig()`) and hands the model to the taskbar. Covered by headless
  JUnit 5 tests (`NotificationTest`, 6; `NotificationModelTest`, 12;
  `ToastQueueTest`, 8; `NotificationColorsTest`, 3; `ToastLayerTest`, 17 incl.
  geometry/wrap/ellipsize and a `BufferedImage` paint smoke; `NotificationTrayTest`,
  5 — 51 tests total).
- **Automated release train** (`ci`, `.github/workflows`, `scripts/release`) — a
  turnkey, event-driven release lifecycle so shipping a version needs no manual
  git/tag/notes work beyond a single approval click. A new `release-train.yml`
  orchestrator keeps exactly one open GitHub **milestone** titled with the next
  version (derived from the Conventional Commits merged since the last stable tag:
  `feat`→minor, `fix`→patch, `BREAKING`→major), attaches every PR merged to `main`
  to it, and — the moment that milestone is complete (no open items) — auto-cuts a
  **release candidate**: it opens a `release/X.Y.Z` PR that dates the CHANGELOG and
  drops the `-dev` suffix from the four canonical version refs, then pushes
  `vX.Y.Z-rc.N`, which `release.yml` builds and publishes as a GitHub
  **pre-release** (never `releases/latest`, so the update-manager is untouched by
  an RC); further pushes to the branch re-cut `rc.N+1`. The one manual step is
  merging the release PR, which promotes it: the train tags the final `vX.Y.Z`
  (published as the latest release + `version.json`), closes the milestone and
  auto-opens the follow-up `chore: bump to <next>-dev` PR that re-opens the dev
  cycle. Release bodies are generated (`release-notes.sh`) from the curated
  CHANGELOG section plus an attributed appendix of the merged PRs and closed
  milestone issues, degrading gracefully when `gh` is unavailable. All decision
  logic lives in a unit-tested shell library (`scripts/release/lib.sh` +
  `next-version.sh` / `prepare-release.sh` / `reopen-dev.sh`) covered by 63
  self-tests (`scripts/release/tests/run.sh`) that run in a throwaway fixture repo,
  wired into CI via a new `release-ci` job (YAML-parse + `bash -n` + ShellCheck +
  self-tests) in `build.yml`; every stage also has a `workflow_dispatch` override,
  and idempotency + a daily schedule make triggering race-free. Tag/branch pushes
  use a `RELEASE_PAT` secret so the hand-off to `release.yml` fires (GitHub's
  anti-recursion guard ignores `GITHUB_TOKEN`-pushed tags). Documented in
  `docs/release-process.md`.

### Changed
- **Desktop JVM memory tuning** (`lg3d-core`, `lg3d-incubator`) — raised the
  2006-era `-Xms128m -Xmx512m` launch ceiling, which was far too small for a
  desktop that hosts several `SwingNode` power-of-two ARGB textures (a heap
  `BufferedImage` *plus* a GPU texture each), the Jogamp/Java3D runtime and
  embedded apps (IDE, DB manager, JavaHelp) and so forced constant G1 churn and
  risked `OutOfMemoryError`. The `:lg3d-core:run` task and the release-bundle
  `lg3d.sh` it generates now default to `-Xms256m -Xmx1536m`, select G1
  explicitly with a `-XX:MaxGCPauseMillis=100` target to keep the render loop
  smooth, and set `-XX:MetaspaceSize=128m` so the reflection-heavy start-menu
  class loading does not thrash early (deliberately **no** hard
  `MaxMetaspaceSize` cap, which would trade a footprint limit for a real
  `OOM:Metaspace` regression). Both heap ceilings are overridable without editing
  the build — `-PlgMinHeap=` / `-PlgMaxHeap=` for the Gradle task and
  `LG3D_MIN_HEAP` / `LG3D_MAX_HEAP` for the generated launcher — so a small
  machine can dial down and a workstation dial up. The legacy standalone
  `zoetrope.sh` launcher gets the same env-overridable heap (default `-Xmx1024m`,
  a single 3D app rather than the whole desktop). Launcher/build JVM flags only;
  no scene-graph, rendering or API change.
- **Bounded stack-icon cache** (`lg3d-core`,
  `org.jdesktop.lg3d.scenemanager.utils.taskbar.stack`) — the on-disk MIME-icon
  cache behind the dock folder stacks (`~/.cache/lg3d/stack-icons/`, one small
  PNG per file-type extension ever seen) previously grew without limit. It is now
  capped at 128 entries with least-recently-used eviction: every cache hit bumps
  the file's modified time and a write that pushes the directory past the cap
  deletes the oldest-used icons first (non-PNG files are never touched, and
  housekeeping failures are logged and swallowed so a stack never breaks because
  of pruning). The eviction is extracted into a package-private
  `StackIconCache.evictLru(dir, maxEntries)` seam covered by a new headless JUnit
  5 suite (`StackIconCacheTest`, 5 tests over a temp dir with explicit mtimes).
- **Copyright attribution** — corrected the source-file headers across the tree
  so the modernization work is credited to its actual author instead of the
  inherited upstream notice. Authorship is taken from git history: every file
  added after the initial upstream import (`26e7ee1`) now carries
  `Copyright (c) 2026, Jean-Francois Landreville` — the from-scratch
  `lg3d-widgets` and `db-manager` modules plus the 70 post-import application
  files in `lg3d-apps` (calculator, controlcenter, filemanager, taskmanager,
  mediawriter, paint, dbmanager, update, swingide, `TitledSwingWindow`, most of
  help). Files that arrived in the cloned 2006 Sun base keep Sun's original
  notice (as the GPL requires) with an added
  `Portions Copyright (c) 2026, Jean-Francois Landreville` line for the
  Gradle/JDK 21 modernization and improvements. `lg3d-docs/**` is left
  untouched (historical, do-not-update). Comment-only; no functional change.
- **2D copyright attribution (follow-up)** — the earlier pass missed the whole 2D
  desktop surface. Every file in the `displayserver/desktop2d` subsystem (2D
  window manager, taskbar, start/context menus, notification tray + toasts,
  session restore, window snapping/cycling, and their JUnit suites) plus the
  SwingNode 2D capture helpers (`SwingNodeWindowCapture`, `HostedWindowResizer`)
  is a post-import original, yet 26 of them still carried the inherited Sun
  Microsystems notice; those now credit `Jean-Francois Landreville` alone. The
  standalone 2D Swing modules `lpm-console` (16 files) and `update-manager`
  (67 files) — also originals — had no header at all and now carry the same
  `Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved` notice as
  the already-corrected `db-manager` / `lg3d-widgets` (83 files). `SwingNode.java`
  itself arrived in the cloned 2006 Sun base, so its Sun notice is retained (as
  the GPL requires) with the existing `Portions` line. Authorship is taken from
  git history (files added after the `26e7ee1` import). Comment-only; no
  functional change — `lg3d-core`, `lpm-console` and `update-manager` all still
  compile.

### Fixed
- **Calendar month navigation in the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — the `«`/`»` prev/next-month
  controls in the clock's calendar popup did nothing on a real click. They were
  plain `JButton`s nested in a header `JPanel` inside the `JPopupMenu`, but a
  popup routes mouse events through Swing's `MenuSelectionManager`, which only
  dispatches real clicks to direct `MenuElement` children — so the button actions
  never fired and a click merely dismissed the popup. The existing unit tests
  missed this because they called `next()`/`prev()` directly (and `doClick()`
  fires programmatically). The controls are now `JMenuItem`s, which a popup does
  dispatch to; selecting one dismisses the popup, so `next()`/`prev()` re-open it
  on the next EDT tick to keep it visible at the new month. The null-anchor
  headless construction path is unchanged.

## [1.9.0] — 2026-09-24 — Gradle / JDK 21 modernization

### Added
- **IDE** (`lg3d-apps`, `org.jdesktop.lg3d.apps.swingide`) — the external
  **swing-ide** project (a full-featured modular Java Swing IDE: multi-language
  code editor, Maven/Gradle/Ant build tools, Git, debugger, database explorer,
  plugin system) integrated as a *Developers* start-menu app. Unlike every other
  desktop app, the IDE is **not** loaded into the desktop JVM: it ships as a
  self-contained fat jar (`libs/swing-ide.jar`, built by swing-ide's own Gradle
  9.7.1 build/CI and fetched on demand via a new `:fetchSwingIdeJar` task, since
  its ~138 MB exceeds GitHub's 100 MB per-file limit) and the new `SwingIde`
  launcher forks it as a **separate child process** (`java -jar`) on the lg3d
  display. The isolation is load-bearing: the IDE calls `System.exit` when its
  main window closes (which would otherwise tear down the desktop) and its fat
  jar bundles unrelocated third-party libraries (slf4j, logback, Jackson,
  JFreeChart) that could clash with the desktop classpath — neither can reach
  the desktop JVM because the jar is never on it. The child's own `JFrame`
  therefore appears as an ordinary top-level window: composited over the 3D
  scene and beside the 2D/Swing desktop. `swingide.lgcfg` registers the menu
  item with the `java <class>` verb and a scaled `swing-ide.png` icon; the jar
  path is resolved from a new `swingide.jar` system property (set by
  `:lg3d-core:run` and the release `lg3d.sh`), then `<lg.appcodebase>/libs`, then
  the working directory, degrading to a readable "unavailable" message when
  absent. `Desktop2DAppRegistry` classifies the launcher as a `SWING_FRAME` app
  so the 2D desktop runs its (child-forking) main beside the desktop. Covered by
  a new headless JUnit 5 suite (`SwingIdeTest`, 12 tests pinning the jar-path
  precedence, child-command shape and display selection without ever spawning a
  process) and a `Desktop2DAppRegistryTest` classification case.
- **Database Manager** (`db-manager`, `org.jdesktop.lg3d.apps.dbmanager`) — a new
  standalone, **driver-agnostic JDBC database client** styled after DBeaver, added
  as a *Developers* start-menu app. The `db-manager` module is a plain Java 21 /
  Swing library (Maven layout, **no `lg3d-core` dependency**, mirroring
  `update-manager` / `lpm-console`) layered `model` → `jdbc` → `session` → `ui`:
  connection-profile CRUD with a **test** action and JSON persistence under
  `~/.lg3d/dbmanager/` (Jackson; passwords **not** saved by default, and only
  *obfuscated* — not encrypted — on explicit opt-in, with a warning); a metadata
  navigator that lazily reads catalogs/schemas/tables/views/columns/PK/FK through
  the standard `DatabaseMetaData`; a multi-tab SQL editor with syntax highlighting
  and multi-statement splitting; **asynchronous** execution off the EDT with
  `Statement.cancel()` stop, `fetchSize` paging and a row cap; a read-only results
  grid (paging + sort) with CSV export; a CREATE TABLE DDL viewer; and transaction
  control (auto-commit toggle, commit, rollback). It bundles the SQLite + H2
  (embedded, offline) and PostgreSQL + MariaDB JDBC drivers, and supports a
  user-added custom driver/JAR (`URLClassLoader` + shim) for any other engine.
  Inline grid cell-editing is intentionally deferred — `DmlBuilder` is implemented
  and tested to back it, but the grid is read-only and edits go through the SQL
  editor. On the desktop, `DbManagerPanel` (a plain `JPanel` with a non-throwing
  constructor that degrades to an "unavailable" pane on `LinkageError`) embeds the
  module's `DbManagerMainPanel` and is hosted on a `SwingNode` inside a `Frame3D`
  via `TitledSwingWindow` in 3D and, through a new `Desktop2DAppRegistry` panel
  mapping, as an MDI internal frame in the 2D/Swing desktop; `dbmanager.lgcfg`
  registers the menu item and a generated `dbmanager.png` icon. Because the
  `:lg3d-core:run` and `:lg3d-core:releaseBundle` classpaths are hand-assembled,
  both add the `db-manager` jar plus a detached configuration carrying its runtime
  deps (jackson-databind, slf4j-api/nop and the four bundled JDBC drivers). SQLite,
  H2, PostgreSQL and the MariaDB client were added to the version catalog.
  Covered by a new headless JUnit 5 suite in `db-manager` (132 tests running **real
  JDBC** against in-memory H2 / temp-file SQLite across the model, jdbc, session
  and ui layers), a `Desktop2DAppRegistryTest` classification case and a new
  headless `DbManagerPanelTest`; a conservative `db-manager` coverage floor is
  pinned in the root build and report-only PIT is wired. New `db-manager/AGENTS.md`
  and `dbmanager` per-app `AGENTS.md` document the module and host shim.
- **Desktop background context menu** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — right-clicking the 2D/Swing
  desktop wallpaper (anywhere not covered by an app window or widget) now opens a
  conventional desktop context menu. A new Java 3D-free static builder,
  `Desktop2DContextMenu`, assembles the `JPopupMenu` driven entirely by an
  `Actions` callback (the same testable pattern as `Desktop2DStartMenu` /
  `Desktop2DFolderMenu`), so the menu structure and wiring are unit-tested without
  a live desktop. It offers launchers (**Open Terminal** — shown only when a
  terminal executable is installed — and **Open File Manager**), personalisation
  (**Change Wallpaper**, a submenu enumerating the bundled `resources/images/background`
  backdrops with a hard-coded fallback when the directory cannot be listed, e.g.
  running from a jar, plus **Desktop Settings...** opening the Control Center),
  window arrangement (**Cascade** / **Tile** / **Minimize All** / **Restore All**,
  gated off when no windows are open) over the MDI `JDesktopPane`, and session
  (**Refresh**, **Exit...**). `Desktop2D` registers the popup trigger on both
  press and release (which one fires is platform-specific) and rebuilds the menu
  each time so the arrangement entries reflect the windows currently open.
  Covered by a headless JUnit 5 test (`Desktop2DContextMenuTest`, 10 tests)
  asserting the entry order/separators, the conditional Terminal entry, the
  wallpaper submenu contents and placeholder, the arrangement gating, the
  callback wiring and the `isImage`/`displayName` helpers.
- **Office apps in the 2D/Swing desktop** (`lg3d-incubator`, `lg3d-core`) — the four
  pure-3D *Office* start-menu apps (Mail 3D, Agenda 3D, Contact 3D, Chart 3D) now
  launch in the 2D/Swing desktop instead of appearing disabled with a "Requires the
  3D desktop" tooltip. Each gains a plain-Swing `JPanel` counterpart in
  `lg3d-incubator` — `MailPanel`, `AgendaPanel`, `ContactCardsPanel` and `ChartPanel`
  — registered in `Desktop2DAppRegistry.PANEL_APPS` on the app's existing 3D main
  class, so the *same shared `.lgcfg` descriptors* now flip from `UNAVAILABLE` to
  `PANEL` in the 2D menu with no descriptor, icon or run-classpath change (the
  incubator jar is already on the `:lg3d-core:run` classpath, so the reflective
  lookup resolves — the same precedent as `WidgetGalleryPanel` and `LPMConsolePanel`).
  The panels are idiomatic, keyboard-editable Swing (folder combo + `JList` + compose
  card; custom-painted week grid with title/day/hour/duration/attendee editing;
  contact list + detail card; `JTree` org hierarchy + name query) that reuse each
  app's AWT-free model and the **same shared user `Preferences` stores**
  (`/mail/messages`, `/agenda/appointments`, `/contacts`), so state written in one
  desktop is visible in the other. None loads a Java 3D class, so a 3D-less JVM still
  runs them; the week-grid constants are duplicated in `AgendaPanel` rather than
  referenced from the `Component3D` `AgendaGrid`. A new `lg3d-incubator` JUnit 5 test
  source set exercises all four panels headless (25 tests) against the ephemeral CI
  `Preferences` root, `Desktop2DAppRegistryTest` asserts the four commands classify as
  `PANEL` and map to their panel FQNs, and a conservative `lg3d-incubator` coverage
  floor is pinned in the root build. The `mail` and `orgchart` per-app `AGENTS.md`
  UI/UX rows now document both surfaces.
- **Games in the 2D/Swing desktop** (`lg3d-incubator`, `lg3d-core`) — the four
  pure-3D *Games* start-menu apps (Tic-Tac-Toe 3D, Sudoku 3D, Chess 3D, Solitaire 3D)
  now launch in the 2D/Swing desktop instead of appearing disabled with a "Requires
  the 3D desktop" tooltip. Each gains a plain-Swing `JPanel` counterpart in
  `lg3d-incubator` — `TicTacToePanel`, `SudokuPanel`, `ChessPanel` and
  `SolitairePanel` — registered in `Desktop2DAppRegistry.PANEL_APPS` on the game's
  existing 3D main class, so the *same shared `.lgcfg` descriptors* now flip from
  `UNAVAILABLE` to `PANEL` in the 2D menu with no descriptor, icon or run-classpath
  change (the incubator jar is already on the `:lg3d-core:run` classpath, so the
  reflective lookup resolves — the same precedent as `WidgetGalleryPanel` and
  `LPMConsolePanel`). The panels are idiomatic Swing (a 3x3 button grid; a 9x9 grid of
  keyboard-editable fields with a difficulty combo and conflict highlighting; a
  custom-painted 8x8 click-to-move board with Unicode piece glyphs; a custom-painted
  Klondike table with click-to-select / click-to-move and double-click-to-foundation)
  that reuse each game's **AWT-free engine** (`TicTacToeModel`, `SudokuModel`,
  `ChessModel`, `SolitaireModel`), so rules and play are identical across both
  desktops. None loads a Java 3D class, so a 3D-less JVM still runs them. A new
  `lg3d-incubator` JUnit 5 test source set exercises all four panels headless
  (42 tests), `Desktop2DAppRegistryTest` asserts the four commands classify as
  `PANEL` and map to their panel FQNs, a conservative `lg3d-incubator` coverage floor
  is pinned in the root build, report-only PIT is wired, and the `games` per-app
  `AGENTS.md` Surface / UI-UX rows now document both surfaces.
- **Per-module role guides** (`AGENTS.md`) — every built module now ships a
  role-aware `AGENTS.md` following one shared template so all roles read each
  other's guidance coherently: *Module at a glance*, *How the roles work
  together*, and dedicated **Architect / Engineer-Developer / QA / Business
  Analyst / Functional Analyst / Project Manager** sections, plus a **UI/UX
  (3D & 2D)** section for every module with a user interface (marked *not
  applicable* for the `lg3d-escher` protocol library), a *Communication &
  coherence* rule and the module-scoped *Commit / PR* flow. New files for
  `lg3d-escher`, `lg3d-apps`, `lg3d-incubator`, `lg3d-widgets` and
  `lpm-console`; `lg3d-core` and `update-manager` gain the same role sections
  while keeping their existing content (`lg3d-core` remains the canonical UI/UX
  rulebook every module defers to). The root `AGENTS.md` replaces its "consider
  creating" note with a *Module AGENTS.md index & shared role model* table.
  Documentation only — no code, build or version change.
- **Per-application role guides** (`AGENTS.md`) — extending the per-module effort,
  *every* application package in the two app modules now ships its own condensed
  role-aware `AGENTS.md` beside its sources: 18 in `lg3d-apps` and 35 in
  `lg3d-incubator` (native-3D showcases, ported apps, dormant prototypes,
  framework/library trees, and the **excluded** apps). Each uses the shared role
  template with an *App at a glance* table whose first row is a **Status**
  classifier (**Production** / **Production-grade** / **Sample-Tutorial** /
  **Dormant prototype** / **Excluded from build**), plus entry point, window
  surface, start-menu descriptor location and runtime blockers. Multi-app package
  trees are covered by one guide at the package root (`games/`, `orgchart/`). The
  existing 13 `lg3d-apps` per-app files were converted to the template with
  their original in-depth reference preserved underneath. The root `AGENTS.md` and
  both module files gained a *Per-app guides* index note, and `lg3d-apps` was
  reframed to make clear the module name is legacy while its apps are
  production-grade. Documentation only — no code, build or version change.
- **Software Update** (`update-manager`, `org.jdesktop.lg3d.apps.update`) — a
  self-contained Swing update pipeline adapted from an external module and
  integrated as a *Utilities* start-menu app. The module checks a release
  endpoint for a newer version, downloads and verifies it (SHA-256 plus optional
  OpenPGP detached signature via Bouncycastle), backs up the current install,
  installs, and offers rollback / downgrade / scheduling, a channel model
  (stable / beta / nightly), a system-tray notifier, a changelog viewer and a
  settings form. The adaptation is deliberately minimal: the original
  `com.protonmail.landrevillejf.swingide.update` package, Lombok and slf4j are
  kept, and only swing-ide-specific identifiers and user-facing strings were
  re-pointed at Project Looking Glass (config dir `~/.lg3d/`, `LG3D_UPDATE_TOKEN`,
  `lg3d.version`, the release `version.json` URL, "Project Looking Glass"
  wording). The removed `:ide-core` dependency is replaced by a local synchronous
  exact-type `EventBus`; Jackson, Bouncycastle, Lombok, Mockito and AssertJ were
  added to the version catalog, and a build-time `generateVersionFile` task feeds
  `ApplicationVersion`. On the desktop, `UpdateManagerPanel` (a plain `JPanel`)
  embeds the module's settings form behind an `UpdatePresenter` and is hosted on a
  `SwingNode` inside a `Frame3D` via `TitledSwingWindow` in 3D and, through a new
  `Desktop2DAppRegistry` panel mapping, as an MDI internal frame in the 2D/Swing
  desktop; `updatemanager.lgcfg` registers the menu item, and the
  `update-manager` jar plus its runtime deps are added to the hand-assembled
  `:lg3d-core:run` classpath. Covered by the module's headless JUnit 5 suite, a
  `Desktop2DAppRegistryTest` classification case and a new headless
  `UpdateManagerPanelTest`. A new `.github/workflows/release.yml` publishes the
  `version.json` manifest (and the signed `lg3d-<version>.zip` bundle) to the
  GitHub Releases `latest` redirect that `update.url` targets, so a live check
  now resolves instead of degrading to `UpdateServerUnavailableException`.
- **Release workflow** (`.github/workflows/release.yml`) +
  **`:lg3d-core:releaseBundle`** task — publishes the update metadata the Software
  Update app checks for. On a pushed `v*` version tag (the normal way to cut a
  release), a published Release, or `workflow_dispatch` for a tag, it builds and
  tests the tree, assembles `lg3d-<version>.zip` (the module
  jars, their third-party runtime dependencies, the assembled `resources/` tree,
  the `etc/` config tree, `ext/app` and a `lg3d.sh` launcher), computes its size +
  SHA-256, optionally signs it with an armored detached PGP signature (`.zip.asc`,
  the format `UpdateSignatureVerifier` reads) when the `RELEASE_SIGNING_KEY` /
  `RELEASE_SIGNING_PASSPHRASE` secrets are set (publishing the public key as
  `public-key.asc`), generates the `version.json` manifest
  (`UpdateRepository.parseUpdateInfo` schema) and uploads `version.json`,
  `changelog.md` and the bundle to the Release. The `push: tags: v*` trigger means
  CI now cuts the release end-to-end — the publish step `gh release create`s the
  GitHub Release when the tag does not yet have one, so a downstream consumer
  (e.g. an LFS host, or the Software Update app) fetching
  `.../releases/latest/download/version.json` resolves as soon as the tag is
  pushed, without anyone first clicking "Publish release" in the UI. Signing is
  secret-driven and never
  committed; without the secrets the release is published unsigned and the
  client's default checksum-only verification applies.
- **LPM Console** (`lpm-console`, `org.lpmconsole`) — a graphical package-manager
  front-end for LPM (the BLFS package manager), delivered as a standalone Java 21
  Swing module and wired into the lg3d desktop start menu under the *System*
  group. It is a real GUI (category rail over a sortable, searchable package
  table with a details pane, action toolbar, collapsible log and status bar),
  not a terminal: package listings are read read-only from LPM's database
  (`/var/lib/lpm`), while every mutation shells out to `/usr/bin/lpm` with
  `--no-color`, previews with `--dry-run`, and confirms destructive actions in
  an in-panel overlay (modal dialogs escape the SwingNode capture). It honours
  the LPM Control Application Contract (`lpm-lg3d-app-contract.md`): no
  re-implementation of dependency resolution, checksums, locking or rollback.
  The panel is registered with `Desktop2DAppRegistry` so it is hosted as an MDI
  internal frame in the 2D/Swing desktop, and its `swingapp` descriptor lets
  `SwingNodeWindowCapture` texture its window into the 3D desktop; its jar is
  added to the `:lg3d-core:run` classpath and a drawn package-box start-menu
  icon (`lpm-console.png`) is generated.
- **Help Center** (`lg3d-apps`, `org.jdesktop.lg3d.apps.help`) — a complete,
  navigable desktop user guide built on the latest **JavaHelp**
  (`javax.help:javahelp:2.0.05`), replacing the old static-image *Simple Sample
  Help* as the desktop's real documentation (that sample is left in place as a
  scene-graph demo). A `JHelp` viewer (Contents / Index / full-text Search) is
  embedded in a plain-Swing `HelpCenterPanel`, hosted on a `SwingNode` inside a
  `Frame3D` via `TitledSwingWindow` in the 3D desktop and, through a new
  `Desktop2DAppRegistry` panel mapping, as an MDI internal frame in the 2D/Swing
  desktop. The HelpSet ships fourteen authored HTML topics (overview, getting
  started, desktop tour, windows, start menu, taskbar, widgets, gestures, the 2D
  desktop, built-in apps, customizing, package management, troubleshooting and
  about) with a shared stylesheet, a target map, a hierarchical TOC and a keyword
  index; the Search navigator's database is generated at build time by JavaHelp's
  own indexer (new `:lg3d-apps:generateHelpSearchIndex` task) and bundled
  beside the HelpSet. JavaHelp is added to the version catalog, to
  `lg3d-apps`, and (as a detached configuration) to the hand-assembled
  `:lg3d-core:run` classpath so the in-JVM launch resolves `javax.help.*`. A new
  `helpcenter.lgcfg` registers it under the *Utilities* start-menu group. Covered
  by a headless JUnit 5 test (`HelpContentTest`, new `lg3d-apps/src/test`
  source set) asserting the HelpSet parses with the expected title and all three
  navigators, every map target resolves to a topic URL, and `JHelp` constructs
  under JDK 21; the 2D classification is covered in `Desktop2DAppRegistryTest`.
- **Gradle build** (wrapper 8.14) replacing the 2006-era Ant `source 1.5` build,
  with a JDK 21 toolchain. Modules: `lg3d-escher`, `lg3d-core`, `lg3d-apps`,
  `lg3d-incubator`; jars are emitted to `<module>/build-gradle/libs/` so the
  legacy per-module `build`/`clean` scripts are left untouched.
- **`run-lg3d.sh`** launcher at the repository root — auto-detects/pins the JDK 21
  toolchain, defaults `DISPLAY`, and starts the desktop. Options: `-2`
  (conventional Swing 2D desktop, MDI internal frames), `-w` / `--swing` (Swing
  desktop, MDI internal frames + Metal look and feel), `-b` (3D `pinguin.j3f`
  background), `-x` (X11 compositor/WM mode), `-c` (clean first), `-r`
  (reassemble runtime resources), `-h` (help), and `--` pass-through to Gradle.
- **`lg3d-core:run`** task (`JavaExec`) launching `org.jdesktop.lg3d.displayserver.Main`
  in development mode (`lg.fws.mode=dev`) with the AWT foundation window system,
  pinned to the JDK 21 launcher. Accepts `-Pbackground3d` to opt into the 3D model
  background and `-Pcompositor` to run lg3d as its own X11 window
  manager/compositor.
- **Non-3D (2D) fallback desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d` + `DesktopMode`) — a machine with
  no Java 3D, or with the jars present but no working GL context, no longer dies
  at boot with `SevereRuntimeError`. `Main` now resolves the desktop mode through
  a pure, unit-tested `DesktopMode`: `lg.fws.mode=2d` forces the Swing desktop,
  `lg.fws.mode=3d` keeps the historical fail-loudly 3D boot, and any other value
  (including the default `dev`) **probes** the machine — Java 3D on the classpath
  and a 3D-capable `GraphicsConfiguration`, both reflectively — falling back to
  2D only after a modal confirmation dialog ("3D unavailable: … Start in 2D
  mode?"; **Exit** preserves today's error). A `Throwable` escaping
  `new ServerHandler()` (classes present, GL init fails) re-runs the same
  resolver, and `lg.2d.simulateNo3D=true` forces the fallback branch so the
  dialog is exercisable on 3D-capable hardware. The 2D shell itself is pure JDK
  Swing and touches no Java 3D, so it also runs where the Java 3D jars are
  missing entirely: one undecorated, maximised `JFrame` holding an MDI
  `JDesktopPane` over the usual wallpaper, with a Swing taskbar (Start button,
  per-window buttons, Documents/Downloads folder menus reusing the 3D-free
  `FolderStackModel`, clock, Exit). Its start menu is built from the *same*
  `.lgcfg` descriptors as the 3D menu — read as plain XML (`Desktop2DMenuConfig`)
  rather than decoded into 3D beans — and classifies each entry
  (`Desktop2DAppRegistry`): panel apps (**File Manager**, **Task Manager**,
  **Control Center**, **Calculator**, **Media Writer**) are hosted in internal
  frames via reflection (so lg3d-core never depends on lg3d-apps nor loads a
  3D wrapper); conventional Swing apps (**Paint**, **Swing Test**) launch in-JVM
  without the 3D window capture; external commands run as child processes as in
  3D; and pure-3D apps appear **disabled** with a "Requires the 3D desktop"
  tooltip. Two latent 3D couplings that would crash apps in 2D were also fixed:
  the Control Center omits its Appearance/Desktop panels when `lg.desktop2d` is
  set, and `PaintApp` tolerates a missing hosted look-and-feel. Selected with
  `run-lg3d.sh -2` / `-Pdesktop2d`. Covered by JUnit 5 tests in a new
  `lg3d-core/src/test/java` source set (mode resolver, descriptor reader, app
  registry, folder-menu listing); the 3D boot path is otherwise unchanged.
- **Swing desktop flavour (`--swing`)** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d.DesktopSwing`) — the same MDI
  desktop as `--2d`, wearing the **Metal** look and feel: one maximised window
  whose `JDesktopPane` hosts each application in a `JInternalFrame`, so windows
  stay integrated with the desktop and minimise into it rather than escaping to
  the host taskbar. (`--2d` keeps the host system look and feel.) A real
  top-level `JFrame` cannot be a child of a `JDesktopPane` and iconifies into the
  host window manager, outside the desktop, so `JInternalFrame` — stock Swing's
  MDI window — is the correct component here. `DesktopSwing extends Desktop2D`
  and adds only the Metal look and feel and its own window title: the shell,
  wallpaper `JDesktopPane`, internal-frame hosting and de-duplication, start
  menu, Documents/Downloads folder menus, taskbar, external-command and
  conventional-Swing-app launches and exit handling are all inherited unchanged,
  so nothing is duplicated. Selected with `lg.fws.mode=swing` (`run-lg3d.sh -w` /
  `--swing`, equivalently `-PdesktopSwing`); `DesktopMode` resolves it as an
  explicit, never-confirmed choice and the automatic no-3D fallback still lands
  on the MDI 2D desktop, so `--2d` is unchanged. Covered by `DesktopModeTest`;
  the internal-frame hosting under Metal was verified at runtime (panel apps open
  as `JInternalFrame`s inside the desktop pane and minimise into it, Metal is
  active).
- **`lg3d-core:runtimeResources`** task — assembles the legacy top-level
  `resources/` classpath tree from `lg3d-art` (wallpapers, splash, models, GDM
  theme), `lg3d-core` (icons, buttons, default wallpapers) and the incubator
  background manager (`BgConfig.xml`, per-background directories, taskbar icons),
  and wires it onto the `run` classpath so the desktop comes up fully populated.
- **`lg3d-core:generateBuildInfo`** task — reproduces the legacy `LgBuildInfo.java`
  `@TOKEN@` substitution into `build-gradle/generated-src`, keeping the checked-in
  tree clean.
- **In-tree contrib replacements** under `lg3d-core/src/contrib/java` for the
  dropped `j3d-contrib-utils` classes: `Math3D`, the `TreeScan` /
  `NodeChangeProcessor` / `ProcessNodeInterface` traverser, and
  `TransparencyOrderedGroup` / `TransparencyOrderController`.
- **`J3fLoader`** (`org.jdesktop.j3d.loaders.wrappers.J3fLoader`) reimplemented
  against the Jogamp scene-graph IO API, so the `pinguin.j3f` background model can
  be read again (it is loaded reflectively via `Class.forName` in `ModelBackground`).
- **Legacy-name compatibility shims** so pre-existing `.j3f` files (which bake in
  the old class names) deserialise under Jogamp: `javax.media.j3d.AmbientLight`
  and `com.sun.j3d.utils.scenegraph.io.state.javax.media.j3d.AmbientLightState`.
- **Jogamp native runtime dependencies** — GlueGen / JOGL / JOAL 2.6.0
  platform-classifier jars, selected from `os.name`/`os.arch`.
- **X11 compositing integration** (`lg3d-core/.../displayserver/nativewindow/x11/`)
  — lg3d can run as its own X11 **window manager + compositor**, displaying real
  X11 client apps as textured `NativeWindow3D` quads in the 3D scene. Built on new
  pure-Java Escher extension bindings (`X11CompositeExt`, `X11DamageExt`,
  `X11ShmExt`) plus `X11Compositor` (WM takeover + `CompositeRedirectSubwindows`
  + event loop), `CompositeWindowImageLoader` (Damage-triggered pixmap → texture),
  and `X11InputForwarder` (3D pick → XTest pointer/keyboard injection). Opt-in via
  `-Pcompositor` / `run-lg3d.sh -x` (`lgconfig_1p_x_composite.xml`); no JNI, no
  JNA, no patched JDK. Requires a bare Xorg with no other WM already holding
  `SubstructureRedirect`. The legacy native `fws/x11` path stays excluded.
- **Desktop shell: widget framework (`lg3d-widgets`)** — a new in-tree module
  providing a public, pluggable widget API (`org.jdesktop.lg3d.widgets.api`:
  `Widget`, `AbstractWidget`, `WidgetContext`, `WidgetDescriptor`, the
  `WidgetProvider` SPI and `WidgetRegistry`), a desktop widget layer/host
  (`...widgets.host`) that renders draggable widgets whose layout persists to
  `~/.config/lg3d/widgets.properties`, and built-in clock, temperature, CPU and
  memory widgets (`...widgets.builtin`). Third parties add widgets by dropping a
  jar carrying a `META-INF/services/...WidgetProvider` entry; placed widgets are
  managed through the **Widget Gallery** app.
- **Desktop shell: Weather widget** (`lg3d-widgets`, `WeatherWidget`) — a new
  built-in desktop widget showing current conditions from the free
  **Open-Meteo** forecast API (no API key): temperature, a sky glyph drawn from
  the WMO weather code, condition text, location, today's high/low, feels-like,
  humidity and wind. The **mouse wheel** cycles a preset list of major cities
  (persisted) and a **click** toggles &deg;C/&deg;F (defaulted from the system
  locale); a custom `lat`/`lon`/`label` can be pinned in
  `~/.config/lg3d/widgets.properties`. Fetches run on the shared widget
  scheduler thread every 15 minutes (and immediately after a city change) via
  the JDK `java.net.http` client with a small dependency-free JSON reader, so
  neither the EDT nor the 3D event loop is ever blocked; a failed refresh keeps
  the last reading and flags it "stale". Listed in the Widget Gallery under
  **Web**.
- **Desktop widgets on the 2D / Swing desktop** (`lg3d-widgets` +
  `lg3d-core`) — the widgets are no longer 3D-only and their start-menu entry no
  longer says *“Requires the 3D desktop”*. The visual/logic core of each
  built-in was extracted into pure-Swing `WidgetCard`s
  (`...widgets.builtin`: model + tick + `Graphics2D` paint + click handling,
  catalogued by `BuiltinWidgetCards`/`WidgetCardSpec`); the five 3D `*Widget`
  classes are now thin delegates that host the *same* card on a `SwingNode`
  texture, so nothing is duplicated between desktops. A new `SwingWidgetLayer`
  (`...widgets.swing`) renders the cards as draggable Swing components on the
  2D desktop's `JDesktopPane` (above the wallpaper, below application windows),
  shares the 3D host's scheduler and persists to the *same*
  `~/.config/lg3d/widgets.properties`, so a layout placed on one desktop is
  restored on the other. `WidgetGalleryPanel` is the pure-Swing Widget Gallery;
  `Desktop2DAppRegistry` maps the gallery's `.lgcfg` command to it as a panel
  app (hosted in an internal frame), and `Desktop2D` installs/uninstalls the
  layer reflectively — one hook that covers both `--2d` and `--swing`
  (`DesktopSwing extends Desktop2D`), with lg3d-core still carrying no
  dependency on lg3d-widgets and degrading gracefully when the module is absent.
  The 3D widget path is unchanged. Covered by JUnit 5 tests in a new
  `lg3d-widgets/src/test/java` source set (catalog, card behaviour, layer
  persistence/relayout, gallery panel, offscreen render) plus registry tests in
  `lg3d-core`.
- **Desktop shell: dock folder stacks** — Documents and Downloads stacks on the
  taskbar's right side
  (`[Documents] [Downloads] [Background] [Exit]`), each raising **the same
  glassy vertical list the start menu uses for its application groups**
  (`org.jdesktop.lg3d.scenemanager.utils.taskbar.stack`, registered from
  `glassy.lgcfg`): hovering the dock icon raises the list anchored above the
  icon and opening leftward (the stacks sit at the right screen edge), and
  leaving the icon or the list hides it again — including in front of a
  maximized full-width window, through the same eye-distance lift as the app
  list. Once lowered the list leaves nothing above the bar: its row column is
  detached while hidden (a dock stack has no taskbar button for the shrunken
  column to sink into, unlike the start menu's) and re-attached on the next
  raise. Rows are the folder's most-recently-modified entries (newest first,
  capped at 12) drawn with the desktop's own MIME icons (painted once into
  `~/.cache/lg3d/stack-icons/` PNGs, since 3D icon textures load from URLs),
  plus a trailing **Show in File Manager** row; the mouse wheel cycles the rows
  exactly as in the app list. Files open with `xdg-open`; folders and the
  trailing row open the folder in the file manager.
- **Desktop shell: system apps** (`lg3d-apps`) — **File Manager**
  (tree + list browsing with copy / move / rename / delete-to-trash / new-folder,
  multi-select, drag-and-drop, keyboard shortcuts), **Task Manager** (live
  process table from procfs with End Task / Force Quit / Change Priority), and
  **Control Center** (Display via `xrandr` with a timed auto-revert, Users via
  `pkexec`, live System info, and an Appearance wallpaper chooser). All three sit
  under a new **System** start-menu group.
- **Pure-Java Linux system backends** (`org.jdesktop.lg3d.utils.system` in
  `lg3d-core`) — `ProcessRunner` / `PrivilegedRunner` (`pkexec`), `Opener`
  (`xdg-open`, freedesktop trash), `Proc` (procfs readers), `ProcessService`,
  `ThermalService`, `DisplayService` (`xrandr`), `UserService` and
  `SystemInfoService`. No JNI/JNA; every service degrades gracefully (read-only
  or "n/a") when a tool or file is absent.
- **Standard 3D window decoration for all `Frame3D` apps** — a reusable
  `Frame3DWindowDecoration` (`org.jdesktop.lg3d.scenemanager.utils.decoration`)
  is auto-attached by `StandardAppContainer.addFrame3D`, giving every pure-3D
  window (File Manager, Task Manager, Control Center, Widget Gallery and the
  demos) native-style **minimize / maximize / close** buttons plus
  3D rotation: **right-click** on the window's green border flips it over to a
  `StickyNote` back side and **middle-drag** free-spins it. Previously this chrome
  existed only for native X11 windows (`GlassyNativeWindowLookAndFeel`), an excluded
  code path, so
  dev-mode apps had no window buttons and could not be rotated. Frames that build
  their own chrome (e.g. `Lg3dHelp`) opt out via the
  `lg3d.frame3d.decoration.optOut` property.
- **Image Studio** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.imagestudio`) — a
  full-featured image editor with an **lg3d-native 3D UI** (no Swing editing
  surface), built on the **bundled Java Advanced Imaging API** (`javax.media.jai`)
  in `lg3d-incubator/ext`. A `Frame3D` window (with the standard decoration)
  lays out a textured-quad image canvas (mouse-wheel zoom, reset view), a left
  toolbar of categorised operations — **Geometry** (scale, rotate, flip, crop,
  border, pixelate), **Color** (brightness, contrast, gamma, grayscale, sepia,
  invert, posterize, threshold), **Filter** (blur, sharpen, emboss, edge) and
  **Math** (add/subtract/multiply constant, absolute, and/or/xor, noise) — driven
  by a live 3D parameter slider, a log-scaled 256-bin RGB histogram, and a bottom
  filmstrip of `~/Pictures` thumbnails. Bounded undo/redo/reset; images open from
  the filmstrip or a native `JFileChooser`, and save back to the current path or
  export to `~/Pictures/lg3d-imagestudio/` (PNG/JPEG via `ImageIO`, TIFF/BMP via
  the JAI codec). Registered in the start menu (Utilities) by
  `lg3d-apps/src/config/imagestudio.lgcfg`. The `lg3d-core:run` task now
  puts the two genuine JAI jars (`jai_core.jar`, `jai_codec.jar`) on the desktop
  classpath and exports `java.desktop/sun.awt.image` so JAI's `RasterAccessor`
  fast path works under JDK 21.
- **Three more `lg3d-incubator` apps ported, registered and verified launching** —
  apps whose sources merely predated the current core API snapshot were brought up
  to date, built, and confirmed to start on the desktop: **Luncher**
  (`luncher.Luncher1`, a 3D glassy-cube card launcher), **Natural Language
  Control** (`nlc.Main`, a command-driven 3D mascot) and the **org chart** apps
  (`orgchart.ui.chart.Chart3D`, `orgchart.ui.contact.Contact3D`). Each is
  registered in the desktop start menu by a new descriptor under
  `lg3d-apps/src/config` (`luncher`, `nlc`, `orgchart-chart`,
  `orgchart-contact`), following the Image Studio precedent. The compile-time
  drift fixed was small and self-contained: vecmath's dropped
  `Color3f/Color4f(java.awt.Color)` constructors,
  `AppLaunchAction(String,ClassLoader)` /
  `Pseudo3DShortcut(URL,String,ClassLoader)` signatures,
  `SimpleAppearance.setTexture(URL)`, and `FuzzyEdgePanel.setSize(float,float,float,float)`.
  Making them actually *run* also required fixing legacy resource/API drift that
  only surfaces at launch: luncher's `MenuConfigFileReader` falls back to the
  `MenuConfigFile.xml` bundled beside the class and its menu icon now resolves
  from the bundled `GlassyCardIcon.png` (the legacy `etc/lg3d/` and
  `resources/images/icon/` install paths are not populated by this port);
  `Luncher1.setShortcuts` no longer calls `Container3D.setLayout` on an
  already-populated container (the modern API rejects that — `MenuConfigFileReader`
  sets the layout while the container is still empty); nlc's `StanfordFactory`
  loads its `englishPCFG.ser.gz` grammar model from the jar (copying it to a temp
  file) and `Main`/`knowledge.xml` point at the bundled `conf/` resources rather
  than the absent `/etc/lg3d` paths; and `Chart3D.upLevel` guards on
  `numChildren > 1` before reading `getChild(1)` (child 0 is the Up button), so a
  stray keypress at the top level no longer throws. Runtime dependencies are
  supplied by putting the *specific* bundled `ext` jars these apps need on the
  `lg3d-core:run` classpath — `nanoxml-lite` + `javanlp` (nlc), `prefuse`
  (orgchart) and the two JAI jars (Image Studio) — deliberately **not** the whole
  `ext` tree, because `ext/axis/xercesImpl.jar` registers itself as the JAXP
  `DocumentBuilderFactory` and references `org.w3c.dom.ls.DocumentLS` (long
  removed from the JDK), which breaks `java.util.prefs` and hence `DesktopConfig`
  and the background manager on every desktop. `wilkoaim3d` remains excluded: it
  needs a whole removed 2004-era utility vocabulary (`Frame3DToFrontEvent`,
  `ComponentMover`, `ResilientRotateAction`, `NaturalMotion*`,
  `ColorAlphaChangeAction`) and its AOL AIM backend was discontinued in 2017, so
  it could never run — its exclusion rationale was corrected (the `com.wilko`
  `jaimlib.jar` is in fact present).
- **Agenda 3D** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.orgchart.ui.agenda`) —
  a new native-3D week-agenda app that interacts **one-way** with the ported
  **Contact 3D**. Both run in the same JVM and share the user `Preferences` root,
  so `Agenda3D` reads the very same `/contacts` node `Contact3D` imports (falling
  back to the bundled `contacts.xml` if Contact 3D has not run yet) and offers
  those contacts as meeting attendees, drawing each invitee's live free/busy
  presence as a coloured chip on the appointment block — **Contact 3D itself is
  unchanged**. A `Frame3D` (with the standard decoration) lays out an `AgendaGrid`
  week view — seven day columns by ten one-hour rows (08:00–18:00) rasterised into
  a single live texture with the `Histogram3D` `ImageComponent2D.set` recipe — over
  a strip of runtime-drawn `AgendaButton` controls (New / Del / Title / Today /
  Att- / Att+ and Day / Hr / Dur nudges). Clicking a cell selects an appointment
  or moves the creation cursor; appointments are **user-created only** (the agenda
  starts empty) and persist under `/agenda/appointments`, mirroring how Contact 3D
  stores contacts. Registered in the start menu (Office) by
  `lg3d-apps/src/config/agenda3d.lgcfg`.
- **Agenda 3D week grid now marks business days, holidays and weekends** — the
  `AgendaGrid` columns are anchored to real `LocalDate`s (Monday of the current
  week + offset) instead of bare indices, and each day is classified with the
  bundled **`jbusinessday`** library (`libs/jbusinessday-0.9.1-SNAPSHOT.jar`):
  `JBusinessDay.isWeekend` / `isBusinessDay` against a per-year cached federal
  holiday list (`AmericanHolidayUtil.getFederalHolidays` by default, or
  `CanadianHolidayUtil.getCanadianFederalHolidays` when started with
  `-Dlg.agenda.holidayRegion=CA`). A title band across the top of the grid shows
  the displayed week's month range **and year** (e.g. `September 14–20, 2026`, or
  `Dec 28, 2026 – Jan 3, 2027` when the week straddles a year), the day header is
  two lines — the day name over a short month + day-of-month (e.g. `Mon` /
  `Sep 14`) — and weekend and holiday columns get distinct header tints plus a
  faint full-height body wash, with an accent bar over today. A new navigation
  row (`Yr-`/`Mo-`/`Wk-` and their `+` counterparts) cycles the displayed week
  back and forth through the calendar, and `Today` snaps back to the current
  week; the today-highlight only appears when the displayed week is the current
  one. `jbusinessday` logs through slf4j and touches
  `org.slf4j.LoggerFactory` in a static initializer, so `slf4j-api` + the silent
  `slf4j-nop` provider (2.0.16) are added to the incubator runtime classpath and
  resolved onto the `lg3d-core:run` classpath alongside the jar.
- **Mail 3D** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.mail`) — a new native-3D
  e-mail client. A `Frame3D` (standard decoration) lays out a single live-texture
  `MailView` — a message list beside a reading / compose pane under a slim folder
  header, rasterised with the same `ImageComponent2D.set` recipe as `AgendaGrid` —
  over a strip of the agenda's runtime-drawn `AgendaButton` controls. The mailbox
  is **local-only** (no SMTP/IMAP): "sending" files a message into Sent, which
  keeps the compose / reply / send loop fully exercisable offline, and the store
  persists under `/mail/messages` in the user `Preferences` tree, seeded with a
  few sample messages on first run. Interaction is button-driven (dev mode has no
  keyboard focus routing): click a row to open and mark it read; Inbox / Sent
  switch folders and Next walks the selection; New / Reply open a draft whose
  To / Subj / Body cycle presets (recipients drawn from the same shared
  `/contacts` directory Contact 3D populates, via `ContactDirectory`); Send files
  it and jumps to Sent, Back discards it. A 48x48 `mail3d.png` icon (INDIGO tile +
  `SendMail` glyph) is generated by `lg3d-art/tools/GenerateAppIcons.java`, and the
  app is registered in the start menu (Office) by
  `lg3d-apps/src/config/mail3d.lgcfg`.
- **App icons via the bundled `IconManager` library** (`libs/IconManager-1.6.0.jar`)
  — the six start-menu apps that previously fell back to the generic
  `defaultapp.png` (**Image Studio**, **Luncher**, **Natural Language Control**,
  **Chart 3D**, **Contact 3D**, **Agenda 3D**) now each get a distinct 48x48
  icon: a per-app coloured gradient/glass tile with the most fitting
  `toolbarButtonGraphics` glyph overlaid, generated by
  `lg3d-art/tools/GenerateAppIcons.java` (IconManager `createGradientIcon` +
  `createCompositeIcon(OVERLAY)` + `exportIcon`) into
  `lg3d-core/src/resources/images/icon/` and referenced from each app's `.lgcfg`.
- **`jmf23D` (Algea3D) ported but intentionally not menu-registered** — the
  JMF-backed 3D media player now compiles, and its `main` guards against a null
  `Player` so it degrades gracefully instead of throwing an NPE, but it is a
  *media player*: its default clip (`GoMonkeyDemo.ogg`) is not shipped in the
  repository and its Ogg demuxer needs the `fobs4jmf` **native** library, which
  has no modern x86-64 build. With no playable media and no available codec it
  cannot render anything from a start-menu click, so no `.lgcfg` descriptor is
  installed; it stays usable from the command line via `java ...Algea3D -m <url>`
  wherever a suitable JMF codec exists. Its `jmf`/`fobs4jmf`/`jl1.0`/`commons-cli`
  jars are still on the run classpath and its transport-button models are merged
  into `runtimeResources` for that command-line path.
- **UI/UX developer documentation** — a new top-level `docs/` tree (distinct from
  the historical `lg3d-docs/`): `docs/lg3d-native-apps.md` (building native 3D
  apps — `Frame3D`/`Component3D`, layout, the glassy widget vocabulary, event
  adapters + actions, transparency ordering, the live-graph texture-upload rule,
  and Start-Menu `.lgcfg` registration) and `docs/swingnode.md` (embedding Swing
  via `SwingNode` — offscreen texture pipeline, input forwarding, custom
  renderers, lifecycle/`dispose()`). A nested `lg3d-core/AGENTS.md` captures the
  UI/UX rules for agents, and the root `AGENTS.md` now links both.
- **Live taskbar miniatures for Swing windows** — `TitledSwingWindow` now sets a
  content thumbnail (the `Lg3dHelp` `HelpThumbnail` pattern: glass plate, drop
  shadow and a `FuzzyEdgePanel` textured with the window's own image) instead of
  falling back to the blank coloured `DefaultThumbnail` plate. To make this
  possible `SwingNode` gained a public `TextureListener` API
  (`addTextureListener` / `removeTextureListener`): observers are notified on
  the EDT whenever the rendered `Texture2D` is recreated (first capture and
  resizes), so the miniature binds — and re-binds — to the same live texture
  the window renders from, tracking panel repaints in real time.
- **Desktop configuration (Control Center → Desktop)** — a user-friendly,
  persisted settings surface for the desktop shell: **taskbar thickness**,
  **docking position** (bottom/top; left/right reserved for a later phase),
  **icon size**, the **Swing application UI font** (family + size), and a
  taskbar **auto-hide** toggle. Settings are held in a new lg3d-core
  `DesktopConfig` singleton backed by `java.util.prefs`
  (`LgPreferencesHelper`), edited from a new `DesktopPanel` in the Control
  Center, and applied **live**: the panel saves the prefs and posts a
  `DesktopConfigChangeEvent`, which the active `AdvancedGlassyTaskbar` consumes
  to re-lay-out (thickness/position/icon scale) on the fly, while
  `TitledSwingWindow` re-applies the configured font to the Swing
  `UIManager` defaults. Taskbar docking now publishes a top *or* bottom
  reserved strip (`Taskbar.get/setReservedTopHeight`), and window maximize
  clearance fills the usable band between them. Auto-hide slides the bar mostly
  off its docking edge on mouse-exit and back on edge hover (self-contained in
  the taskbar, as the legacy dormant `HideEvent` path has no poster).
- **Four native-3D games** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.games`) —
  **Tic-Tac-Toe 3D**, **Sudoku 3D**, **Chess 3D** and **Solitaire 3D**, each a
  self-contained pure-3D app on the Agenda 3D pattern: a plain-Java game **model**
  (no AWT, unit-tested headless), a live-texture `Component3D` **view** that
  rasterises the board into one `ImageComponent2D` with the `Histogram3D` `.set`
  recipe, and a `Frame3D` **host** (with the standard decoration) laying the view
  over a strip of runtime-drawn `AgendaButton` controls. Interaction is entirely
  **click-driven** — dev mode routes no keyboard focus to a `Frame3D` — picking the
  textured quad and mapping the hit back to a board cell or card. **Tic-Tac-Toe**
  plays an unbeatable full-width **minimax** opponent (perfect from either side).
  **Sudoku** generates a puzzle from a solved grid across three difficulty levels
  (Easy / Medium / Hard = 44 / 34 / 27 givens) with live row/column/box **conflict
  highlighting**, hints, solve and reset-to-puzzle. **Chess** implements the full
  ruleset — castling, en passant, promotion, check / checkmate / stalemate and
  insufficient-material draws — against a **negamax + alpha-beta** engine with
  quiescence search and piece-square evaluation (perft-verified to depth 4:
  20 / 400 / 8902 / 197281 nodes), plus legal-move highlighting, undo and board
  flip. **Solitaire** is Klondike with a recycling stock, four foundations, seven
  tableau piles, run dragging, auto-finish, undo and hints; its cards are drawn
  with **vector suit shapes** (`Path2D` / `Ellipse2D`) so it needs no extended
  font. Each game is registered in a new **Games** start-menu group by a descriptor
  under `lg3d-apps/src/config` (`tictactoe`, `sudoku`, `chess`, `solitaire`)
  and gets a distinct 48x48 `IconManager` icon from `GenerateAppIcons.java`.
- **Calculator** (`lg3d-apps`, `org.jdesktop.lg3d.apps.calculator`) — an
  advanced scientific calculator whose Swing `JPanel` is hosted on a `SwingNode`
  inside a `Frame3D` via `TitledSwingWindow` (title bar, min/max/close, live
  taskbar thumbnail). A headless recursive-descent **expression engine**
  (`CalculatorEngine`: parentheses, `^` powers, factorial, `%`, `mod`, DEG/RAD
  trigonometry and inverses, `ln`/`log`/`sqrt`/`abs`, `pi`/`e`/`Ans`, memory
  register MC/MR/M+/M-/MS) drives an editable expression field with a **live
  result preview**, a six-column key pad and a clickable **history** list;
  keyboard input reaches the field through the `KeyEvent3D` forwarding. It is
  registered in the **Utilities** start-menu group (`calculator.lgcfg`) and gets
  a 48x48 icon whose keypad glyph is drawn inside `GenerateAppIcons.java`, the
  bundled glyph set carrying nothing calculator shaped.
- **Media Writer** (`lg3d-apps`, `org.jdesktop.lg3d.apps.mediawriter`) — a
  full-featured disc and USB imaging tool whose Swing `JPanel` is hosted on a
  `SwingNode` inside a `Frame3D` via `TitledSwingWindow`. A headless engine
  (`MediaWriterEngine`) drives the **real** Linux media tools — `growisofs` /
  `wodim` / `xorriso` for optical burns, `dd` for USB imaging and cloning,
  `wipefs` / `parted` / `mkfs.*` for formatting — across five modes: **burn an
  ISO to CD/DVD** (speed selection, `-dvd-compat`), **write a raw image or ISO
  to a USB key** (with `isohybrid` master-boot-record fix-up and optional
  bootable-partition handling), **clone a disc/device**, **format a removable
  key** (vfat/exfat/ntfs/ext4/ext2, optional msdos partition table, volume
  label) and **build a data disc** from a folder (`xorriso -as mkisofs`,
  Rock Ridge + Joliet, optionally burned straight to a drive). Devices are
  enumerated by parsing `lsblk -b -P` (optical / USB / internal-disk
  classification, mount points, media state from `/proc/sys/dev/cdrom/info`);
  images are probed for ISO-9660 and hybrid-magic before writing. Safety: an
  internal disk is never a writable target, mounted filesystems are unmounted
  first, destructive tools run under `pkexec` when not root, every write is
  gated by an explicit inline confirmation, and an optional **SHA-256 verify**
  re-reads the written media. The panel adds cancellation, live progress
  (parsed from `dd`/`growisofs` output) and a scrolling command log. Because
  `SwingNode` captures only its own panel, file picking and confirmation use
  **in-panel overlays** instead of modal dialogs. Registered in the
  **Utilities** start-menu group (`mediawriter.lgcfg`); its 48x48 icon gets a
  disc glyph drawn inside `GenerateAppIcons.java`, the bundled glyph set
  carrying nothing disc shaped.
- **Top-level Swing window capture in `SwingNode`** (`lg3d-core`,
  `org.jdesktop.lg3d.wg.internal.swingnode`) — a conventional, *unmodified* Swing
  application now integrates into the 3D desktop. A single global
  `AWTEventListener` (`SwingNodeWindowCapture`) watches every top-level `Window`
  the JVM opens and, instead of letting it pop onto the host desktop where the
  offscreen texture capture cannot reach it: presents a captured `JFrame` as a
  real desktop window (`CapturedFrameHost` — a `Frame3D` + `SwingNode` with the
  standard title bar / spine titles / min-max-close `Frame3DWindowDecoration`, a
  live taskbar thumbnail, and resize / title / close sync between the real frame
  and its 3D window), and paints a captured `JDialog` / `JWindow` (`JOptionPane`,
  `JFileChooser`, popups) as a centred in-scene **overlay** inside the owning
  node's texture, routing forwarded mouse and key input to it with modal
  semantics preserved (a modal dialog's own secondary EDT loop processes the
  events the node dispatches). `SwingNode.captureNow` composites the captured
  overlays on top of the hosted panel, its `RepaintManager` marks the owning node
  dirty when a captured dialog repaints, and `SwingNodeRenderer` resolves each
  input event's target through the capture registry (topmost modal dialog first,
  else the overlay under the pointer, else the node's own hidden frame). This
  removes the technical reason for the "in-panel overlays instead of modal
  dialogs" workaround noted for Media Writer above — that app is intentionally
  left as-is, but real modal dialogs now render in-scene for any app that opens
  them.
- **`SwingAppLauncher` + `--swing-app` launcher** (`lg3d-core`,
  `org.jdesktop.lg3d.utils`) — runs a conventional Swing application's `main`
  inside the desktop JVM on a dedicated thread so every window it creates is
  captured. Wired through `DisplayServerControl` (after "Start-up configuration
  completed", reading `-Dlg.swingapp`), the `:lg3d-core:run` task
  (`-PswingApp="<fqcn> [args...]"` and `-PswingAppCp=<path[:path...]>` for the
  app's classes/jar) and `run-lg3d.sh` (`--swing-app <fqcn> [args...]`,
  `--swing-app-cp <paths>`).
- **Paint drawing app** (`lg3d-apps`, `org.jdesktop.lg3d.apps.paint`) — a
  conventional Swing `JFrame` raster editor (brush/pencil/shape/fill/eyedropper
  tools, layers, selections, image ops, undo/redo) registered in the Start menu
  under *Utilities* via `paint.lgcfg`. Its descriptor uses the new `swingapp`
  command verb, which opts the app into 3D window capture and launches it in-JVM,
  so its frame is captured and presented as an integrated 3D desktop window.
- **Test coverage reporting (JaCoCo)** — the `jacoco` plugin now applies to every
  module and each `test` task finalizes `jacocoTestReport`, emitting HTML + XML
  under `<module>/build-gradle/reports/jacoco/test/`. It is wired **report-only**
  (no failing threshold): the first measured baseline is lg3d-core ≈ 1.4% and
  lg3d-widgets ≈ 40% line coverage (74 passing tests), far below the 100% goal in
  `AGENTS.md`, so a gate would red-line every build until coverage improves.
- **Build-quality & supply-chain tooling (report-only)** — a second audit-
  remediation pass wires the tooling that makes the `AGENTS.md` 100%-coverage /
  0-mutant goal and the supply-chain gaps *measurable* without enforcing a hard
  gate that would red-line the ~200k-line legacy port:
  - a **JaCoCo coverage ratchet** — `jacocoTestCoverageVerification` joins
    `check` for the two modules with test suites, each with a floor set just
    below current coverage (lg3d-core 1.0% line / 2.0% branch; lg3d-widgets
    48% line / 40% branch), so a regression fails but ordinary churn stays green;
  - **Checkstyle** static analysis from a single shared high-signal config
    (`config/checkstyle/checkstyle.xml`, bug-prone patterns only) with
    `ignoreFailures=true`, publishing XML+HTML reports;
  - **PIT** mutation testing (`info.solidsoft.pitest`) on `lg3d-core` and
    `lg3d-widgets`, on-demand via `./gradlew :lg3d-core:pitest`, with
    `avoidCallsTo` excluding `java.awt`/`javax.swing`/`org.jogamp.*`;
  - a **CycloneDX SBOM** per module (`./gradlew cyclonedxBom`);
  - **OWASP Dependency-Check** (`./gradlew dependencyCheckAggregate`) across every
    module's resolved dependencies, report-only (`failBuildOnCVSS=11`) with an
    optional `-PnvdApiKey`.
  Plugin/tool versions are declared in `gradle/libs.versions.toml`.
- **`lpm-console` unit tests + coverage ratchet** — a headless JUnit 5 suite
  (`lpm-console/src/test/java`, wired into the Gradle `test` task with
  `java.awt.headless=true`) now covers the module's Java 3D-free, GUI-free logic:
  the `LpmPackage` / `OperationResult` / `LPMCommand` / `LPMExecutionException`
  value objects (100% line and branch), the read-only `LpmDatabase` parser
  (pointed at a `@TempDir` through the documented `-Dlpm.dbdir` override) and the
  `LPMExecutor` / `PrivilegeEscalator` command builders (exercised only on the
  `/usr/bin/lpm`-absent path so no process is spawned and CI needs no LPM
  install). The Swing frame and 853-line panel need a peer and stay probe-verified
  per `lg3d-core/AGENTS.md`, so `lpm-console` joins the JaCoCo ratchet with a
  floor just below its measured 26.7% line / 36.9% branch (the panel is the bulk
  of the uncovered lines), and PIT is wired on-demand via
  `./gradlew :lpm-console:pitest`.
- **`lg3d-widgets` widget-API unit tests** — a headless JUnit 5 suite
  (`lg3d-widgets/src/test/java`, `java.awt.headless=true`) now covers the
  module's Java 3D-free, peer-free logic: the immutable `WidgetDescriptor`
  value object (field defaults, argument guards, `create()` factory,
  `toString`), the `ServiceLoader`-backed `WidgetRegistry` singleton
  (discovery, unmodifiable view, id lookup and both `create()` paths — a
  test-only `StubWidgetProvider` registered through
  `src/test/resources/META-INF/services` lets the success path run without
  building a Java 3D `Component3D`), `BuiltinWidgetProvider.descriptors()`
  (mirrors the shared card catalogue), and the two logic pieces of
  `WeatherCard` — the WMO weather-code → text map and its compact
  dependency-free JSON reader (objects, arrays, string escapes, exponents,
  literals, whitespace tolerance and malformed-input rejection, driven
  reflectively). Together with the existing card/layer/gallery tests this
  lifts lg3d-widgets from ~40% to **50.1% line / 41.6% branch** (52 passing
  tests), and the JaCoCo ratchet floor is raised to match. Under PIT the
  targeted pure-logic units are fully killed (`WidgetDescriptor` 19/19,
  `BuiltinWidgetProvider` 2/2, `WeatherCard.condition` 16/16); the only
  survivors are behaviourally-equivalent mutants (redundant whitespace skips,
  early-return pointer tweaks, log-call removals). The Swing card painting and
  the `WeatherCard` network fetch stay probe-verified per
  `lg3d-core/AGENTS.md`.
- **Gradle version catalog** (`gradle/libs.versions.toml`) — centralizes the
  Java 3D (1.7.2), Jogamp-natives (2.6.0), JUnit (5.11.4) and SLF4J (2.0.16)
  versions. `lg3d-core`, `lg3d-widgets` and `lg3d-incubator` now reference
  `libs.*` aliases instead of hardcoding `group:name:version` strings, so a
  dependency bump is a one-line change.
- **`CONTRIBUTING.md`** — a contributor guide covering prerequisites (JDK 21, and
  why Gradle 8.14 cannot run on Java 25+), build/run/test commands, the
  single-repo module layout, the branch → PR flow, the Conventional Commit
  convention and scopes, coding rules (Jogamp packages, generated-file and
  exclusion cautions) and the versioning policy.
- **`SECURITY.md`** — a security policy with a private vulnerability-disclosure
  process (GitHub Security Advisories), supported-version scope, security design
  notes (`ProcessBuilder` / `pkexec` / no telemetry / X11 compositor), and an
  explicit known-risk section for the ~26 end-of-life 2006-era jars bundled under
  `lg3d-incubator/ext/`.

### Changed
- **2D/Swing app names no longer carry the "3D" marker** (`lg3d-core`) — the
  start-menu descriptors are shared with the 3D desktop, so apps that run in the
  2D/Swing desktop as plain Swing panels were still labelled "Mail 3D", "Chess
  3D", "Agenda 3D", "Chart 3D", "Contact 3D", "Sudoku 3D", "Solitaire 3D" and
  "Tic-Tac-Toe 3D" in both the menu entry and the window/taskbar title — even
  though the 2D desktop never renders a 3D window. `Desktop2DMenuConfig` now
  drops the "3D" marker from the display name of any item the desktop can
  actually run (a `PANEL`, `SWING_FRAME` or `EXTERNAL` command), so they read
  "Mail", "Chess", etc. Pure-3D apps it cannot run keep their name unchanged
  (they appear disabled behind the "Requires the 3D desktop" tooltip, where the
  "3D" is the point). Only leading/trailing/word-attached markers are stripped;
  interior brand tokens such as "Lg3d Homepage" are preserved. The underlying
  command and descriptor are untouched, so the 3D desktop is unaffected. Covered
  by new `Desktop2DMenuConfigTest` cases and verified with an in-JVM probe over
  the real descriptors.
- **Module rename** — `lg3d-demo-apps` → `lg3d-apps`. The module ships the
  production-grade desktop applications that come with LG3D (plus a few
  tutorial/sample apps); "demo" was a misleading legacy label. The Gradle
  project, directory, jar (`archiveBaseName = 'lg3d-apps'`), the
  `settings.gradle` include, `lg3d-core`'s project references and run-classpath
  variable (`demoAppsJar` → `appsJar`), the CI artifact path, and every doc /
  source reference were updated. The `config/demo` runtime resource path and the
  `Demos` start-menu group are **legacy names intentionally left unchanged** so
  lg3d-core's descriptor discovery keeps resolving them; historical
  `lg3d-docs/**` was not touched.
- **CI workflow** (`.github/workflows/build.yml`) — added a dedicated, visible
  `./gradlew test --continue` step (the tests already ran implicitly inside
  `build` via `check`) and a `lg3d-test-reports` artifact publishing the JUnit +
  JaCoCo reports on every run, including failures. Removed the inaccurate "the
  lg3d modules live in git submodules / check out recursively" claim and its
  `submodules: recursive` checkout: this is a single repository with no
  submodules, so a plain checkout fetches every module's sources. The build job
  now also uploads the Checkstyle reports alongside the coverage reports, and a
  new `security` job generates the CycloneDX SBOM and runs OWASP Dependency-Check
  (continue-on-error) on `main`/`master` pushes, a weekly schedule and manual
  dispatch — skipped on pull requests to avoid the heavy first NVD download. CI
  stays **ubuntu-latest only**: lg3d is a Linux X11 desktop, so there is no
  macOS/Windows host to validate.
- **Documentation accuracy** — `README.md` and `AGENTS.md` now state plainly that
  this is a single repository (no git submodules), list `lpm-console` among the
  built modules, and correct stale `1.0.1-dev` jar-name examples to the current
  `1.9.0-dev`; `AGENTS.md`'s test-coverage and CI status notes were synced with
  the new JaCoCo + explicit-test-step reality, and `AUDIT.md` gained a
  *Remediation Status* section recording what this pass addressed and deferred.
- **Java 3D** migrated from the Sun `javax.media.j3d` / `javax.vecmath` stack to
  the Jogamp-maintained **1.7.2** fork (`org.jogamp.java3d` / `org.jogamp.vecmath`)
  — the only readily available release preserving the 1.5-era API the sources rely
  on (`ShaderError`, `Node.ALLOW_PARENT_READ`,
  `VirtualUniverse.addGraphStructureChangeListener`, …). Every source file was
  migrated to the renamed packages (`javax.media.jai` left alone).
- **Modern-JDK API drift** fixed across the tree, e.g.
  `Behavior.processStimulus(Enumeration)` → `Iterator<WakeupCriterion>`, and
  `Group.getAllChildren()` / `getAllScopes()` now return `Iterator` instead of
  `Enumeration`.
- **`SatinGestureModule`** rewritten as a geometric stroke classifier, replacing
  the SATIN/Rubine stack from the dropped `satin-v2.3.jar`.
- **`lg3d-incubator`** now compiles the whole `src/classes` tree as one source set
  against `lg3d-core` + the bundled `ext` jars, mirroring the legacy per-app
  `failonerror="false"` behaviour by excluding apps that cannot build.
- **Documentation** — the root and per-module `README.md` files rewritten to
  describe the port, build/run instructions, module map, and exclusions.
- **`lg3d-art` raster assets modernised for high-resolution displays** — the
  wallpapers shipped as 512x512 JPEGs are stretched full-screen by
  `SimpleImageBackground`, so they looked soft and blocky on modern 1080p/4K
  panels. A new reproducible tool, `lg3d-art/tools/modernize_assets.py`
  (Pillow + NumPy + SciPy), cleans and enlarges them in place while keeping the
  *same content*: JPEGs get a mild variance-gated (Wiener-like) denoise at
  native resolution to dissolve compression blockiness, a Lanczos upscale to a
  2048px longest side (capped at 4x so small icons are not over-inflated), and a
  high-quality progressive re-encode; PNGs (icons, splash art) are lossless
  already, so only the Lanczos upscale + optimised re-encode is applied, with
  alpha preserved. Per the agreed policy there is **no sharpening and no
  contrast/colour retouch** — the look is preserved, only cleaned and enlarged.
  Filenames, formats and aspect ratios are unchanged, so every runtime reference
  (BgConfig, start-menu icons, splash, GDM theme) keeps working, and the tool is
  idempotent (images already >= the target are never re-enlarged). The website
  thumbnails under `www/` and the fixed-size GDM chrome buttons are excluded.
  93 assets processed; the art payload grows ~6.8 MB -> ~37.8 MB.
- **Window flip-to-sticky gesture now requires CTRL + right-click** — the
  `Frame3DWindowDecoration` flip (and the matching flip-back on the sticky note)
  was bound to a plain BUTTON3, which never reached the frame for Swing-to-Node or
  native LG3D apps: those reserve a bare right-click for their own context menus
  and their content is non-propagatable, so `PickEngine` stopped the event before
  the frame-level listener ever saw it. Rebinding both listeners to CTRL + BUTTON3
  disambiguates the desktop gesture from app context menus; it still arrives
  through a propagatable handle (the title bar / window chrome).
- **Taskbar right-hand group reordered and inset from the screen edge** — the
  dock group now reads `[Documents] [Downloads] [Background] [Exit]` (Documents
  `-4`, Downloads `-3`, background `-2`, Exit `-1`) instead of background-first,
  and the right-aligned group is inset by a quarter bar height so the rightmost
  (Exit) icon sits on the tapered tip of the tilted glass shelf rather than
  hanging off the end of the bar.

### Removed
- **Bundled `j3d-contrib-utils.jar` and `satin-v2.3.jar`** — compiled against the
  legacy `javax.media.j3d` packages and binary-incompatible with the Jogamp
  rename; superseded by the in-tree replacements above.
- **`lg3d-awt`** excluded from the build — the optional custom AWT Toolkit/peer
  implementation depends on the `java.awt.peer.*` SPI (changed after JDK 5) and
  unexported `sun.awt.*` internals. Not needed: it is opt-in via
  `lg.use3dtoolkit=true` (default false) and loaded dynamically, so `lg3d-core`
  has no compile-time dependency on it. Sources kept in-tree.
- **Native X11 integration** excluded from `lg3d-core`
  (`displayserver/fws/x11`, `apps/x11integration`, `sun.awt.X11.*` shims), and the
  **`lg3d-x11`** native X server module left out of the build — dev mode uses the
  AWT foundation window system instead.
- **Unused RMI scene-graph transport** (`sg/internal/rmi`, `wg/internal/rmi`).
- **ODE physics nodes** (`wg/.../j3dnodes/Ode*`) — superseded by an in-tree
  spring-damper system; the bundled `odejava` jar is not used.
- **Incubator apps that cannot build** (silently skipped by the legacy build too):
  `nu/koidelab` (Cosmo), `archviz3d`, `intel3d`, `browser`, `browser3d`,
  `wilkoaim3d`. (`luncher`, `orgchart`, `nlc` and `jmf23D` were previously in
  this list and have since been ported — see Added.)
- **`Demos` start-menu entry** (`lg3d-core`) — the root `Main` group in
  `startmenu.lgcfg` no longer links to the `Demos` folder, so it (and its
  `Tests` / `Early Prototypes` sub-folders — the tutorial/sample/prototype
  descriptors) is dropped from both the 2D/Swing and the 3D start menu. No
  production application lives under `Demos`; the group *definitions* are kept
  in the descriptor so their items stay grouped (and unreachable) rather than
  becoming orphan items re-appended to the menu root.

### Fixed
- **`UpdateScheduler` could leave a phantom pending update** (`update-manager`) —
  `scheduleUpdate` handed the task to the `ScheduledExecutorService` *before*
  publishing it into `scheduledTasks`. An install time inside the current second
  makes `ChronoUnit.SECONDS.between(now, installTime)` truncate to a **zero**
  delay, so the worker thread could run `executeScheduledUpdate` and call
  `scheduledTasks.remove(taskId)` before the caller's `put` executed; the remove
  was then a no-op, the entry was added afterwards and lingered forever, so
  `getPendingCount()` never returned to zero. This surfaced as an intermittent
  `UpdateSchedulerTest.testListenerReceivesLifecycleEvents` failure (the
  `awaitPendingCount(0)` assertion) on loaded CI runners, where the worker wins
  the race more often than on a fast dev machine. The task is now published to
  `scheduledTasks` before it is scheduled: `executor.schedule(...)` establishes a
  happens-before edge, so the worker's `remove` always sees the entry. Verified
  with a contention harness (buggy ordering leaked 8/4000 phantom entries, fixed
  ordering 0/4000) and 15 `UpdateSchedulerTest` runs under saturated CPUs (0
  failures).
- **2D/Swing desktop widgets could not be dragged** — in the conventional Swing
  desktop (`SwingWidgetLayer`, the `--2d`/`--swing` widget host) grabbing a
  widget card and moving it made the card fly off the cursor instead of
  tracking it, so widgets could not be repositioned. `mouseDragged` called
  `SwingUtilities.convertPointToScreen(press, card)` on every event, but that
  method mutates its `Point` argument in place and `press` was captured once on
  mouse-press in card-local coordinates: after the first drag it already held
  screen coordinates, so each later event re-converted it and compounded the
  card's own movement into a runaway delta. The handler now recomputes the
  pointer in the desktop pane's space from the raw local point each event
  (`SwingUtilities.convertPoint(card, e.getPoint(), desktop)`) and never mutates
  the stored press point, so the card follows the pointer exactly and its new
  fractional position still persists on release. Covered by a new headless
  `SwingWidgetLayerTest` drag case (synthetic press/drag/release asserts the
  card lands on the pointer rather than flying off).
- **2D/Swing desktop showed minimised windows on a second row** — under the
  Synth (GTK) look-and-feel the `JDesktopPane` installed its own MDI taskbar
  strip that re-listed minimised windows above the shell's taskbar, so a
  minimised application icon appeared twice and the bar read as two rows. The
  desktop pane now pins `BasicDesktopPaneUI` (no built-in strip) and a desktop
  manager hides the classic MDI desktop icon, leaving the shell taskbar button
  as the single representation; clicking it still restores the window.
- **2D/Swing taskbar looked like two rows when made thicker** — the bar imposed
  its own thickness from the 3D `barScale` setting, so above the default the
  button row sat in a taller slab with an empty band that read as a second row.
  The 2D bar now carries no thickness of its own: it packs to the height of the
  buttons it holds (only auto-hide still overrides the height), and each button
  row is centred vertically.
- **Control Center showed fewer categories in the 2D/Swing desktop** — the
  control center listed only Display, Users and System under `--2d`/`--swing`
  but all five on the 3D desktop: `ControlPanelRegistry` deliberately dropped
  the Appearance and Desktop panels when `lg.desktop2d` was set, because both
  drove the scene graph through `LgEventConnector`
  (`BackgroundChangeRequestEvent`, `DesktopConfigChangeEvent`) with nothing
  listening on the conventional desktop. All five panels now register in every
  mode, and each drives whichever desktop is running: on the 2D desktop
  `AppearancePanel` calls `Desktop2D.setWallpaper(url)` (the backdrop image is
  now swappable at runtime) and `DesktopPanel` calls
  `Desktop2D.applyDesktopConfig()`, which re-applies the persisted
  `DesktopConfig` live — Swing UI font defaults, taskbar docking edge
  (top/bottom), thickness (`barScale`), chrome-icon scale (`iconScale`) and
  auto-hide (a pointer-proximity poll collapses the bar to a sliver). The 2D
  shell also honours the saved config at startup, and the Java 3D event path is
  guarded behind the mode property so it is never touched on a 3D-less JVM. The
  3D desktop path is unchanged. Verified with in-JVM probes: the panel list
  reports `count=5 [Display, Users, System, Appearance, Desktop]` under
  `lg.desktop2d=true`, and driving a live `Desktop2D` re-docked the taskbar to
  `North` at `preferredHeight=54` (barScale 1.6) and swapped the wallpaper
  image; the scale/height math is covered by `Desktop2DTaskbarConfigTest`.
- **Screen Snapshot would not launch in the 2D/Swing desktop** — the
  conventional Swing `ScreenCaptureConfigFrame` (a plain `JFrame` with a `main`,
  like Paint and Swing Test) was missing from `Desktop2DAppRegistry`'s
  `SWING_FRAME_APPS`, so `classify` fell through to `UNAVAILABLE` and its
  Start-menu entry (Utilities) was greyed out with "Requires the 3D desktop". It
  is now registered as a Swing-frame app, so it launches in-JVM beside the
  desktop under both `--2d` and `--swing`. Two 3D couplings that would misbehave
  in the shared desktop JVM were fixed at the same time: its `EXIT_ON_CLOSE`
  (which would tear the desktop down on close) is now `DISPOSE_ON_CLOSE`, and
  "Take Snapshot" no longer posts a `ScreenCaptureEvent` in 2D — that path makes
  `AppConnectorPrivate` boot Java 3D, which cannot work with no 3D — so it paints
  the visible desktop window(s) straight into `lgscreen-<i>-<n>.png` in the
  chosen folder (the same naming as the 3D `ScreenCaptureBehavior`), avoiding
  `java.awt.Robot`, which cannot grab a rootless/Wayland display. The 3D capture
  path is unchanged. Covered by `Desktop2DAppRegistryTest`.
- **Maximizing a hosted Swing window magnified its text and left it narrow** —
  `Frame3DWindowDecoration.toggleMaximized` maximized every window by uniformly
  scaling the `Frame3D` to fit the usable screen area
  (`min(screenW/w, usableH/h)`). For a `SwingNode`-hosted window (Task Manager,
  and any `TitledSwingWindow`) that scaled a fixed-resolution offscreen texture,
  so the content — text included — was blown up and blurry, and the
  aspect-preserving `min` letterboxed a narrow window instead of filling the
  width. A real `JFrame` re-lays-out its content at native size on maximize.
  Hosted windows now do the same: `HostedWindowResizer` (a
  `WeakHashMap<Frame3D,Resizer>` registry in `lg3d-core`) lets
  `TitledSwingWindow` register a per-frame resizer; `toggleMaximized` detects a
  registered frame and resizes the Swing panel to the usable area in native
  pixels (`SwingNode.setHostedSize`) instead of scaling, then re-lays-out the
  title bar, spines and decoration. The panel repaints at its new native size
  (crisp text) and the quad grows to full width. The decoration tracks the new
  size **in place** — `GlassyPanel`/`RectShadow` are resized through their
  `setSize` (both carry `ALLOW_COORDINATE_WRITE`), never removed and rebuilt,
  since detaching non-`BranchGroup` children from a live graph throws
  `RestrictedAccessException` and re-inserting a detached backdrop trips
  `MultipleParentException`. Un-maximizing restores the original pixel size;
  pure-3D windows keep the uniform scale-to-fit maximize. Verified with an
  in-JVM probe: a 680x480 hosted panel maximized to 1920x852 (full width, native
  pixels) with no scene-graph exceptions.
- **A maximized window masked the start-menu application list** — the full-screen
  hosted maximize above exposed a latent draw-order bug in the start menu. The
  menu is a child of the taskbar, which docks at `z = -0.04`
  (`GlassyTaskbar.barZ`), while `ZLayeredLayout` places the front-most app window
  at `z ~= -0.004`. `StartMenuModel.changeVisible` only raised the menu to a local
  `z = 0.02` (world `~= -0.02`), i.e. *behind* every window; that went unnoticed
  because ordinary windows never overlap the menu's bottom-corner popup region,
  but a full-screen maximized window does, so the app list disappeared behind it.
  Raising the menu in depth alone is not enough, and neither is a small Z lift:
  transparent shapes are sorted back-to-front by the distance from the eye to
  each shape's bounding-sphere centre, *not* by Z. A maximized window quad is
  centred on-axis (distance ~= eye.z), while the menu is docked in a screen
  corner, so its off-axis centre stays *farther* from the eye even at a nearer Z
  and it keeps sorting behind the window. The raise now lifts the menu to a
  fraction (`FRONT_WORLD_Z_FRACTION = 0.4`) of the eye distance, which pulls its
  bounding-sphere centre well inside the window's so every menu shape sorts - and
  is picked - in front; the lift is perspective-compensated (world X/Y and node
  scale multiplied by `r = (eyeZ - zNew)/(eyeZ - zRef)`) so the list's on-screen
  position and size are unchanged. Verified in a framebuffer capture: the hovered
  application list pops up on top of a maximized full-width window while the rest
  of the desktop stays put.
- **Min/max/close buttons sat above the title text** — `Frame3DWindowDecoration`
  pinned the window buttons to the top corner of the frame
  (`y = frameHeight/2 - inset`), while `TitledSwingWindow` centres its title text
  in the reserved title strip (`y = frameHeight/2 - titleBarHeight/2`), so on a
  hosted window the three buttons floated visibly higher than the title. The
  decoration now reads a new `TITLE_BAR_HEIGHT_PROPERTY` frame property; when a
  frame publishes its title-strip height (as `TitledSwingWindow` does before
  `changeEnabled`) the buttons are centred on that strip, aligned with the title
  text, and pure-3D frames without a strip keep the corner placement. Verified in
  framebuffer captures in both the normal and the maximized state.
- **Maximized window pushed its title bar off the top edge** - filling exactly to
  the screen top left the title strip (and the minimize/maximize/close buttons)
  flush against / past the top edge where they cannot be clicked. Maximize now
  reserves a small top headroom band (`0.012`) in addition to the taskbar
  reserves, so the whole title bar and its buttons stay comfortably inside the
  visible area while the window still fills the usable width and height.
- **Maximized hosted window kept its Swing content at the old size** — follow-up
  to the hosted maximize above. `SwingNode.setHostedSize` resized the panel then
  called `revalidate()` + `doLayout()`, but `doLayout()` only lays out the panel's
  *immediate* children, so a nested `JScrollPane` -> viewport -> `JTable` subtree
  kept its pre-resize interior bounds: the grown window showed the old small
  content stranded in a top corner with the rest of the area blank ("the frame
  content doesn't adapt"), which also left the title bar / window buttons looking
  detached from a mis-rendered body. `setHostedSize` now runs a full recursive
  `invalidate()` + `validate()` (`validateTree`) over the Swing hierarchy so every
  nested layout manager reflows at the new native size. Verified with an in-JVM
  probe plus an lg3d framebuffer screenshot: a hosted table window maximized to
  1920x852 with its columns stretched full-width and all rows visible, and the
  taskbar stayed clear below the maximized window.
- **Closing an in-JVM Swing app could tear down the whole desktop** — conventional
  apps run inside the desktop JVM (the `java` / `swingapp` command verbs), and they
  routinely default to `EXIT_ON_CLOSE`, so clicking their close button fired
  `System.exit` and killed lg3d along with the app (e.g. Screen Capture).
  `SwingNodeWindowCapture.onWindowOpened` now rewrites `EXIT_ON_CLOSE` to
  `DISPOSE_ON_CLOSE` on every non-host `JFrame`, so closing an app window only
  disposes that frame. (An app that calls `System.exit` directly from a menu
  handler is still out of scope.) Verified with an in-JVM probe: a plain
  `JFrame` opened as `EXIT_ON_CLOSE` (op 3) was rewritten to `DISPOSE_ON_CLOSE`
  (op 2) by the hook while the desktop kept running.
- **Window capture hijacked every conventional Swing app** — the global
  `SwingNodeWindowCapture` hook captured *all* top-level `JFrame`s unconditionally,
  so apps that used to run as normal host windows with native input (Screen
  Capture, Image Studio, Calculator, …) were hidden and re-presented as 3D windows
  driven by synthetic in-scene input, leaving their buttons unresponsive. Capture
  is now **opt-in per app**: `SwingNodeWindowCapture.registerCapturePackage` records
  the launching app's package and the hook only captures a `JFrame` whose class is
  in a registered package. The `swingapp <mainClass>` command verb (and
  `-Dlg.swingapp` / `--swing-app`) register that package; the plain `java <class>`
  verb used by every other Start-menu app does not, so those apps keep their
  native windows and working input. Only Paint opts in today.
- **Captured conventional Swing `JFrame` opened two windows on Wayland** — the
  capture layer hid the real frame only by relocating it to `(-32000,-32000)`,
  but a compositor-managed window manager (GNOME/Mutter under Wayland/XWayland)
  ignores that, so the host `JFrame` stayed mapped beside the 3D window, stole
  native input, and closing it exited the app. `SwingNodeWindowCapture` now
  unmaps the frame (`setVisible(false)`) and `SwingNode.captureNow` paints its
  **root pane** (a `JComponent`) instead of the hidden `Window`, which paints
  blank offscreen; `CapturedFrameHost` sizes the quad to the content area. The
  app now shows as a single integrated desktop window.
- **Could not type into a flipped sticky note (or any `SwingNode` text field)** —
  `SwingNodeRenderer` forwarded `KeyEvent3D`s with `target.dispatchEvent(...)`, but
  the offscreen `SwingNodeJFrame` is displayable yet never *shown*, so AWT never
  installs a focus owner: `hiddenFrame.getFocusOwner()` stayed `null`, the keys fell
  back to the content pane, and a direct dispatch of a `KeyEvent` to a component in
  an unfocused window is dropped before it ever reaches the `JTextArea`'s
  `WHEN_FOCUSED` input map. The old build relied on the excluded `lg3d-awt` peer
  toolkit (`Lg3dComponentPeer.setGlobalFocusOwner`) for this, which is unavailable
  on JDK 21 (strong encapsulation). The renderer now emulates click-to-focus —
  it remembers the deepest Swing component under a mouse press — and delivers
  keystrokes through `KeyboardFocusManager.redispatchEvent(target, evt)`, which
  hands the event straight to that component so editable widgets receive typed
  characters. Verified against a hidden-frame probe on JDK 21 with no reflection
  and no `--add-opens`.
- **Sticky-note typing came out reversed and kept losing focus** — typing "salut"
  produced "tulas" and the caret had to be re-clicked constantly. The
  `SwingNodeRenderer` input listeners run on the lg3d event thread while the
  `SwingNode` capture timer repaints the hosted panel on the EDT; mutating Swing
  state (caret / document / focus) off the EDT races with that repaint and pins
  the caret at 0, so every character inserts at position 0 (reversed text) and
  keystrokes/focus are intermittently dropped. All Swing dispatch in
  `SwingNodeRenderer` (mouse, enter/exit focus and key forwarding) is now
  marshalled onto the EDT with `SwingUtilities.invokeLater`, which serialises it
  with the capture repaint. Reproduced and verified with an off-EDT + capture-timer
  probe: off-EDT gave "tulas"/caret 0, EDT-marshalled gave "salut"/advancing caret.
- **Right-click flip to the sticky note did nothing on any app** — two compounding
  faults. (1) `Frame3DWindowDecoration.createStickyNote()` called
  `StickyNote.initialize(...)` *before* `setEnabled(true)`, but `initialize()`
  dereferences the Swing panel / title field / text area that only `enable()`
  (run from `setEnabled(true)`) creates, so every flip threw a
  `NullPointerException` that the event loop swallowed and the window never
  turned — on native 3D apps and Swing-to-Node windows alike. The native
  look-and-feel has always enabled first and initialised second; the decoration
  now does the same. (2) The flip is a frame-level `BUTTON3` listener, so it only
  fires where the pick propagates to the frame, and a decorated `Frame3D` had no
  propagatable surface to right-click: app content is deliberately
  non-propagatable (it keeps its own context menus) and the decoration backdrop
  was `setPickable(false)`. The backdrop border is now pickable and mouse-event
  propagatable — it sits *behind* the content so it never occludes or intercepts
  app clicks, yet a plain right-click on the exposed green border (or on
  `TitledSwingWindow`'s title bar) reaches the frame's flip listener. The flip is
  bound to a plain `BUTTON3` again, matching the 2006 / native X11 idiom.
- **Dock stack fan crashed on repeated hover and showed stale content** — the
  Documents/Downloads fan is shown and hidden with `Frame3D.changeEnabled`, and
  every re-enable re-ran `StandardAppContainer.addFrame3D`, which re-created the
  window animation. Replacing the animation destroys the previous
  `NaturalMotionWithSwayAnimation` *after* its target `Component3D` reference is
  cleared, so `removeListenerFromComponent3D` dereferenced a null `WeakReference`
  and threw a `NullPointerException` in the `EventProcessor`; the aborted
  `show()` also left the previously-open fan (e.g. Documents) on screen when
  hovering the other stack (Downloads). `addFrame3D` now performs its one-time
  setup (translucency listener, animation, window decoration) only once per
  frame, `Component3DAnimationTarget` tolerates an already-cleared target when
  adding/removing listeners, and opening one stack fan dismisses the other.
- **Window flip showed a plain green back instead of the sticky note** — the
  right-click flip of `Frame3DWindowDecoration` worked on both Swing
  (`TitledSwingWindow`) and pure-3D app windows, but the `StickyNote` was
  placed at `z = -1.1 x BODY_DEPTH`, inside the opaque decoration backdrop
  slab (which spans `[-2 x BODY_DEPTH, -BODY_DEPTH]` because `GlassyPanel`
  grows backwards from its local z=0). After the PI flip the slab's opaque
  back face is closest to the viewer and completely hid the note. The note
  now sits just outside the back face (`z = -2 x BODY_DEPTH - 0.0002`), so
  the flipped window shows the editable yellow note; flipping back also
  disposes the note's offscreen Swing resources, which previously leaked a
  hidden `SwingNodeJFrame` per flip cycle.
- **`TitledSwingWindow` windows could be parked but never rotated** — every
  rotation gesture of the desktop lives in frame-level listeners
  (`ZLayeredMovableLayout`'s CTRL spinner and `Frame3DWindowDecoration`'s
  middle-button spinner), and `PickEngine` only walks picked events up the
  ancestor chain while each source it meets is mouse-event propagatable; the
  Swing quad must stay non-propagatable (or Swing loses its own gestures) and
  the title bar was too, so no spin gesture ever reached the frame. The title
  bar is now propagatable, which turns it into the window's full gesture
  handle: left-drag moves, middle-drag or CTRL+left-drag rotates and
  right-click flips to the sticky note — the same idioms as pure-3D windows,
  with no duplicate listeners (the native window look-and-feel uses the same
  trick for its title panel).
- **`TitledSwingWindow` windows could be parked but not left-clicked back** —
  `StandardAppContainer` unparks a window when the *frame* receives a BUTTON1
  click (`Frame3D` click -> `Component3DToFrontEvent` -> migrate back to the
  main container), and a native window body is covered by a propagatable move
  region so a click anywhere on it reaches the frame. The Swing quad is
  deliberately non-propagatable and the only propagatable strip (the title bar)
  is edge-on once `BookshelfLayout` turns the parked frame +/-90deg, so a left
  click on the visible content died at the quad and never unparked. The quad is
  now made propagatable for the parked state only (via a
  `Component3DParkedEventAdapter`), so a click on a parked window travels up to
  the frame and restores it exactly like a native window, while live Swing
  input keeps the quad non-propagatable and untouched.
- **Vertical spine titles floating beside the green window edge** — the rotated
  edge titles built by `TitledSwingWindow` sit on the pale green side face of
  the decoration backdrop: each pre-rotated +/-90deg spine quad is placed just
  outside the backdrop side (`x = +/-(contentW/2 + DECO_WIDTH)`) with its glyph
  band centred on the slab depth (`z = -1.5 x BODY_DEPTH`, half a glyph proud
  of each glass face, the sign following the pre-rotation), so on a turned
  window or on the bookshelf the title reads on the green edge like a book
  spine instead of floating over the window rim. Placement derives from the
  public `Frame3DWindowDecoration.BODY_DEPTH` / `DECO_WIDTH` constants so the
  app helper and the decoration cannot drift apart.
- **Blank desktop (missing icons/wallpapers/background chooser)** — caused by the
  `resources/` classpath-prefix mismatch: lg3d requests artwork under a top-level
  `resources/` prefix, but the per-module Gradle builds emit those assets at other
  paths. Resolved by the additive `runtimeResources` assembly (no jar
  restructuring). The background manager's taskbar icon
  (`resources/images/icon/bgicon*.png`) was included in the same fix, clearing the
  `downImage cannot be null` error.
- **Startup halt on modern JVMs** — removed `Main.java`'s obsolete blocking
  "upgrade to JDK 1.6 (Mustang)" dialog, which fired on any non-1.6 JVM.
- **`StringIndexOutOfBoundsException` at startup** — `LgBuildInfo`'s `JAVA_VERSION`
  token must be a full version string (`21.0.12`), because `Main.java` takes
  `substring(0,5)`; a bare `21` crashed.
- **`UnsatisfiedLinkError` during `VirtualUniverse` init** — the Jogamp native
  classifier jars are not pulled transitively and had to be added explicitly.
- **Compile/runtime Java-version guard** — the `run` task now uses
  `javaLauncher = javaToolchains.launcherFor { languageVersion = 21 }` so the
  desktop runs on the same JDK it was compiled with, not the (possibly newer) JVM
  that launched Gradle.
- **Negative taskbar indices** — `Taskbar.addTaskbarItem(item, -n)` now places
  the item n-th from the right of the right-hand group (`-1` rightmost) instead
  of clamping every negative index to append-at-end, so the dock stacks sit
  immediately before Exit regardless of plugin initialisation order.
- **`SwingNode` blank quads + stray `JFrame`s** — Swing content (desktop
  widgets, the file/task manager, control center, dock stack popups and the
  SwingNode/StickyNote demos) rendered as an empty white rectangle while the
  hidden `SwingNodeJFrame` popped up as a real window. The offscreen capture
  lived in the excluded `lg3d-awt` peer toolkit (`lg.use3dtoolkit`, off in this
  build); `SwingNode` now paints its panel into a power-of-two `Texture2D`
  directly on stock JDK 21, driven by a `RepaintManager` repaint hook, and never
  maps the hidden frame.
- **`SwingNode` content nearly invisible (over-transparent)** — the offscreen
  capture above used an alpha-less `RGB` texture under `TextureAttributes.REPLACE`
  with `TransparencyAttributes.FASTEST` (screen-door); on Jogamp the fragment
  alpha came out ~0, so apps and widgets faded to barely-visible even when they
  called `setTransparency(0.0f)`. The capture now uses an `RGBA` texture laid over
  an opaque backdrop, and `DefaultSwingNodeRenderer` blends with
  `BLENDED` + `SRC_ALPHA`/`ONE_MINUS_SRC_ALPHA` (the same configuration
  `SimpleAppearance` uses to render native windows opaque) defaulting to fully
  opaque; translucency stays opt-in via `setTransparency`.
- **Terminal launcher dropped when `xterm` is absent** — the taskbar and
  start-menu Terminal items hard-referenced `xterm`, so on a host without it
  `ApplicationDescription.isApplicationAvailable` returned false, discovery
  logged `Executable xterm not found, ignoring taskbar item` and the item
  silently disappeared. Both configs now carry a portable fallback list
  (`alternateExec` on the taskbar `ApplicationDescription`, `alternateCommands`
  on the start-menu `StartMenuItemConfig`) of `gnome-terminal`, `konsole`,
  `xfce4-terminal`, `mate-terminal`, `lxterminal`, `xterm`, so whichever emulator
  is installed is used and the item (renamed **Terminal**) is shown. In dev mode
  the native terminal still opens as an ordinary host window, not embedded in the
  3D scene — embedding real X11 clients needs the separate `-Pcompositor` path.
- **All `GlassyText2D` labels invisible** (window titles, taskbar/button text,
  Image Studio toolbar) — two compounding Jogamp migration bugs. (1) The glyph
  texture was built with `ImageComponent2D(..., byReference=true)`, so its pixels
  were never uploaded and every label sampled a fully transparent texture;
  `GlassyTextTextureGenerator` now copies the image (`byReference=false`).
  (2) The texture was uploaded `yUp=true` while the quad's texture coordinates
  sample `v` in `[0, heightRatio]`, so the sampled region missed the glyph rows
  entirely; the generator now uploads with the image origin at the upper left
  (`yUp=false`) and `GlassyText2D` maps the quad bottom edge to `v=0` so the text
  reads upright.
- **Glass panels washed out to near-invisible white** — `GlassyPanel` sets white
  per-vertex `COLOR_4` values, which under Java 3D *replace* the `Material`
  ambient/diffuse, so the themed tint (e.g. the green window decoration) never
  showed. `GlassyPanel` now tints its vertex colors by the material's diffuse
  color (reading it via a new `Material.ALLOW_COMPONENT_READ` capability on
  `SimpleAppearance`).
- **Image Studio histogram channels swapped** — for `TYPE_3BYTE_BGR` images the
  JAI histogram band order is `{2,1,0}`, so band 0 is red; `Histogram3D` now maps
  bands to R/G/B with the identity `{0,1,2}` instead of reversing them.
- **Image Studio open (file chooser / filmstrip) appeared to do nothing** —
  `ImageCanvas3D.setImage` attached the new `Texture2D` to the live appearance
  (`Appearance.setTexture`) while its `ImageComponent2D` still held no pixels;
  under Jogamp that makes `TextureRetained.setLive` dereference null image data
  and throw, and the NPE propagated back through `EditorModel.setImage` into
  `FileStrip3D.loadPath`'s catch, which reported "Could not open ..." and left
  the canvas on the old image. The canvas now paints and uploads the pixels
  before building/attaching the texture, so opening a file updates the viewport
  and histogram.
- **`Frame3D` maximize only enlarged the window in place and covered the taskbar**
  — clicking maximize on a native 3D app window scaled it about its current
  origin without re-centering, so an off-center window merely grew (~2x), the
  pre-maximize position was lost on restore, and filling the full screen height
  made the window overlap the bottom taskbar. `Frame3DWindowDecoration.toggleMaximized`
  now saves both the final scale and translation, fits the window into the
  *usable* desktop area above the taskbar (a new `Taskbar.getReservedBottomHeight()`
  published by `AdvancedGlassyTaskbar`/`GlassyTaskbar`), applies an aspect-preserving
  uniform scale (`min(screenW/frameW, usableH/frameH) * margin`, so the aspect
  ratio is never distorted and the width stays proportional), centers the window
  in that usable band on the front plane, and restores the original scale *and*
  position on toggle-off.
- **Image Studio maximized without filling the screen width** — the frame's
  preferred size used fixed fractions of the raw screen (0.62 W x 0.66 H), an
  aspect narrower than the usable desktop band, so the aspect-preserving
  maximize scale hit the height bound first and left wide empty margins left
  and right while the height looked correct. `ImageStudioFrame3D` now derives
  its preferred size from the usable area (`screenHeight -
  Taskbar.getReservedBottomHeight()`) with the matching aspect, so a maximized
  window fills the viewport on both axes (uniform 5% margin) and the normal
  window keeps screen proportions.

### Known non-fatal runtime messages
These are harmless and expected in dev mode:
- `Executable <app> not found, ignoring taskbar item` — a sample taskbar or
  start-menu item references an app that is not installed on the host and has no
  available alternate (e.g. Firefox/Thunderbird). The **Terminal** item no longer
  triggers this: it now resolves through its `alternateExec` / `alternateCommands`
  fallback list to an installed emulator.
- `Could not lock System prefs` — the JDK preferences backing store warning.
- `No default preferences file found: /etc/lg3d/skel/prefs.xml` — first-run
  defaults are created instead.
- `PluginJ3fData` user-data `ClassNotFoundException` in `J3fLoader` — the original
  J3dFly plugin class is not present; the model geometry still loads.
