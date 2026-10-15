# Wonderland 3D Roadmap

A forward plan for the Wonderland port's rendering, interaction, audio and
scripting. It is grounded in the code as it stands on `main`, not in the 2006
original's feature list. Each item below says what is *present*, what is
*stubbed*, and what is *absent*, with the file that proves it, so a reader can
verify the claim rather than trust it.

Companion documents: `wonderland-migration.md` (how the modules were ported)
and the per-module `AGENTS.md`.

## Current state (verified against the tree)

| Capability | State | Evidence |
|---|---|---|
| Multiplayer / sessions | **Real, wired** | `wonderland-server` `ServerSession`, `SessionRegistry`, `ProtocolRouter`, WebSocket + chat relay; cells/transforms broadcast. Missing: interest management, persistence, auth. |
| Collision | **Message stub only** | `MovableAvatarComponent` -> `AvatarCell.triggerCollision(height, bool)` -> `AvatarActionTrigger`. A notification, not detection/resolution. `com.jme.bounding.BoundingVolume` is used for cell *bounds*, not collision. |
| Ambient lighting | **Per-material ambient, no rig** | No `Light` node is created in the renderer; `AvatarRenderer` sets `Material.setAmbientColor`. `J3dMeshFactory.buildAppearance` deliberately omits a `Material` for shipped art. |
| Culling | **Delegated to Java 3D** | No Wonderland culler; relies on the scene-graph's automatic culling. No PVS. |
| Shaders | **Present, opt-in** | `ShaderAppearancePlan` holds a validated GLSL 1.20 Blinn-Phong pair; `J3dMeshFactory.buildShaderAppearance` runs it per pixel on a lit material's `ShaderAppearance`. `WlProbe43` drove the shipping `createShape` and measured a facing normal at `(255,255,255)` against a grazing one at the `(31,31,31)` ambient floor. **Off by default** (`-Dlg.wonderland.shader=true` to arm it), so pre-lit worlds stay a byte-identical fixed-function `Appearance`. |
| Water shading | **Degraded/removed** | `MtgSceneReader` skips `JMEWaterInstance` records. |
| Normal mapping | **Kernel live, layer ceiling** | The GLSL normal/spec stages compile and shade, but `WlProbe42` measured this fork binding exactly ONE texture per program (a second `sampler2D` re-reads unit 0), so a lit material lights from its diffuse and `hasNormalMap`/`hasSpecMap` stay 0. Multi-layer needs a direct-JOGL program that binds its own samplers. |
| Occlusion | **Absent** | No occlusion query, no PVS, no SSAO. |
| Barycentric / decals | **Absent** | Hand-built triangles (`tagQuad`, `flatQuad`); no barycentric utilities. |
| Cinematic (PBR/HDR/bloom/shadow maps/DOF/spline camera) | **Absent** | `WorldViewer` fixed-eye framing; `ShadowArt` ellipse decals; `SkyArt` textured box. |
| Audio | **Absent from the port** | No audio/sound class in `wonderland-*`. The 2006 client's spatial audio was **not carried over**; it must be built fresh, not re-wired. |

### The pre-lit constraint (why lighting is opt-in, not global)

`wonderland-renderer` `J3dMeshFactory.buildAppearance` documents, and
`build-gradle/scratch/WlProbe7` measured, that the shipped COLLADA art is
**pre-lit**: the 2006 client baked light into the textures, so the faithful
rendering is a flat `ColoringAttributes` with a `DECAL` texture, *not* a shaded
`Material`. Enabling Java 3D lighting on that art darkens it to roughly 18 % of
the authored colour.

Java 3D applies a `Light` only to an `Appearance` that carries a `Material`, so
installing a light rig is **inert** for the pre-lit `ColoringAttributes` path.
The roadmap therefore treats lighting as an **additive, opt-in capability**:
default rendering stays byte-identical; a material that asks to be lit and a
world that installs a rig get shading, while pre-lit worlds are untouched.

### Platform ceiling

The renderer is built on **Jogamp Java 3D 1.7.2**, a fixed-function /
vertex-program-era API, not a modern GLSL pipeline. PBR, HDR, bloom and SSAO
will be bounded by what `ShaderProgram`/`GLSLProgram` and a `Canvas3D`
readback can do, and some "2026 cinematic" terms may require a direct-JOGL
post pass. The fork has already hit Java 3D quirks (`Background` paints nothing,
back-face culling, `PolygonAttributes` semantics). A roadmap that ignores this
ceiling would promise results the stack cannot deliver; the phases below mark
those items explicitly.

That ceiling is no longer a guess -- four probes measured it on the repo's live
X:0 / GL 4.6 radeonsi context (all in `build-gradle/scratch`, none committed):

- **WlProbe39** - a scene `Light` does NOT reach a custom program:
  `gl_LightSource[0]` read back its OpenGL default for a facing and a grazing
  normal alike, even with a correctly bounded `DirectionalLight`
  (`setInfluencingBounds`, not the `setSchedulingBounds` upstream documents -
  this fork renamed the setter). A GLSL program must therefore be handed its
  light as its OWN uniforms.
- **WlProbe40** - `ShaderAttributeSet` + `program.setShaderAttrNames(...)`
  delivers named uniforms to the program, and one `TextureUnitState` binds one
  `sampler2D` that samples correctly. A uniform must be named on the program
  before it is set, and `ShaderAttributeValue` refuses `float[]` - pass a
  vecmath `Tuple` or a boxed `Float`/`Integer`.
- **WlProbe41** - the corrected production GLSL shades per fragment through
  those custom uniforms + `gl_NormalMatrix`: a lit quad facing the key read
  `(255,255,255)`, a grazing one the `(31,31,31)` ambient floor.
- **WlProbe42** - this fork binds exactly ONE texture per program: a second
  `sampler2D` reads the first unit's image, not its own. Per-material
  normal/spec/env layers therefore need a direct-JOGL pass that binds its own
  samplers - out of this fixed-era `ShaderAppearance` route.

What this buys: real GPU per-pixel lighting was earlier (wrongly) filed as a
platform impossibility; it is now live and wired. What it still costs: a
layered material can light from one texture, not three.

## Roadmap

Phases are independently deliverable, each a module-scoped PR. Ordered by how
much visible value each unlocks per unit of risk.

### P0 - Rendering foundations

- **P0.1 Opt-in lighting rig** *(this PR)*. A pure `WorldScene.Lighting` value
  and a `MaterialAppearance` lighting model; `J3dMeshFactory` builds a Java 3D
  `Material` only when a material opts in; `WorldScene.build(entries, pictures,
  lighting)` installs an `AmbientLight` + `DirectionalLight` scoped to the
  world when a caller asks. The faithful pre-lit default path is unchanged.
  Verified headless for the pure model; the on-screen shading is verified with
  the live `WorldViewer` probe (a display is required).
- **P0.2 Culling / PVS - reframed after reading the code.** A proximity
  selector already exists: `WorldScene.focusWithin` picks the cells near a focus
  point and unions them (it is how framing picks the subject), so a second
  distance filter would be a duplicate, not a feature. The genuine gap is
  *dynamic* mount/unmount while walking - the graph is built once by
  `WorldScene.build` and the eye moves through `WorldViewer` - and that cannot be
  trusted from a headless test (it needs a live render context to prove the
  scene still looks right as cells drop in and out). Kept on the list for that
  reason, not dropped; scheduled as a live-verified PR, not a pure one.
  **Now wired** (`WorldScene`): the built graph wraps each cell's `Link` in a
  `Switch` gate driven per eye-move by `WorldScene.cullTo`, whose visibility set
  comes from the world's own `WorldPVS` over a `WorldInventory`; the `Switch`
  write needs a live GL context, so the set arithmetic is headless-proven
  (`WorldInventoryTest`) and the pixel delta stays a `WlProbe` sentence.

### P1 - Real physical interaction

- **P1.1 Collision - reframed after reading the code.** The horizontal
  separation math (does a walker-sphere overlap an approach cell) is pure and
  headless-testable. But a real AABB needs the cell's *vertical* extent, and the
  `Entry` handed to the renderer carries only an authored horizontal footprint
  (`footprintMetres`); the true height comes from `WorldScene.measuredOf`, which
  walks the model's geometry and therefore needs the Java 3D native stack - it is
  not constructible in a headless test. So the collision kernel can be built and
  unit-tested against boxes supplied by the caller, but populating it with real
  per-cell heights is a live/render-path concern. Plan: pure kernel first (its
  box inputs are injected), geometry-population wired where bounds are already
  computed. **Now wired** (`WorldScene.blockStep` seeds an `AvatarCollider` over
  the inventory's obstacles so a step into a wall is refused and slides along its
  face; the horizontal clamp is headless-proven, the walk needs a live window).
- **P1.2 Trigger volumes.** `onEnter`/`onExit` zones (room doorways, a stage) -
  the substrate for scripted behaviours (P5).

### P2 - Materials, shaders, water, shadows

- **P2.1 Multi-layer materials - kernel delivered, layer binding at the measured
  ceiling.** `MaterialAppearance` carries a `normalMapPath` and a
  `specularMapPath`, `TextureBindingPlan` classifies each authored sampler into
  the diffuse / normal / specular layer it names, and `ShaderAppearancePlan`'s
  GLSL has a tangent-space normal stage and a specular-mask stage. What the
  platform will not do is bind all three to one program: `WlProbe42` measured
  this fork exposing exactly one texture per GLSL program, so
  `J3dMeshFactory.buildShaderAppearance` binds the diffuse and leaves
  `hasNormalMap`/`hasSpecMap` at 0. The extra layers are parsed, named and
  compiled; a host that can drive a direct-JOGL program binding its own samplers
  is what flips them on - not a missing plan.
- **P2.2 Shaders - wired and measured (was: probe-gated).** A GLSL `ShaderProgram`
  on `Shape3D`s, with a documented fixed-function fallback where Java 3D 1.7.2
  cannot express the effect. This repo's Jogamp fork moved the shader entry point
  off upstream 1.6's `Appearance.setShaderProgram` onto a `ShaderAppearance`
  subclass (`setShaderProgram`/`setShaderAttributeSet`), and a program validates
  only against a live GL context. That validation is now done: `WlProbe39..42`
  settled what the fork's `ShaderAppearance` can and cannot do (see *Platform
  ceiling*), and `WlProbe43` drove the shipping `J3dMeshFactory.createShape` and
  measured per-pixel Blinn-Phong reach the screen - a lit textured material reads
  `(255,255,255)` facing the key and the `(31,31,31)` ambient floor grazing it.
  The wiring is no longer "deliberately unapplied": `buildShaderAppearance` is
  live behind a triply-conservative gate - the `lg.wonderland.shader` switch (off
  by default), `MaterialAppearance.lit()` (a pre-lit material never asks to be
  shaded), and a resolved diffuse texture (the program needs one to sample) - so
  any one failing leaves the fixed-function path byte-identical to every prior
  probe run. The scene key reaches the program as its OWN `lightDirection /
  lightColor / ambientColor` uniforms (the scene `Light` is inert here), pushed
  from `WorldScene`'s rig through `J3dMeshFactory.setLightRig`.
- **P2.3 Water shading - delivered as re-honoured data + a scrollable look.**
  Re-honour the `JMEWaterInstance` records `MtgSceneReader` currently drops, as
  an animated water material. Delivered: `WaterInstance` parses the fifteen-
  token record (`MtgSceneReader.readWaterInstances`, carried to the renderer on
  `ModelLoadResult.waterInstances`), and `WaterArt` supplies the honest fixed-
  pipeline stand-in for jME's water shader -- a headless-rasterised seamless
  tile with per-axis scroll-offset maths (`planeOf` folds the record's plane).
  Placing the pond quad and driving `TexCoordGeneration` from a frame clock are
  the probe-side step, not yet a painted pixel.
- **P2.4 Real shadows / occlusion - delivered as projector maths + a PVS owner.**
  Replace `ShadowArt` decals with projected or shadow-map shadows; occlusion via
  the PVS from P0.2. Delivered: `ShadowProjector` - parallel-light box
  projection as pure arithmetic (the pool travels in the direction the light
  travels, low sun lengthens, horizon sun caps at `MAX_STRETCH`, a vertical or
  absent light answers `null` so the round `ShadowArt` pool stays the physically
  right fallback) - and `WorldPVS`, the lifecycle owner that turns P0.2's
  stateless `OctantCuller` answer into a per-frame consultation: cells register
  and detach, a re-attached cell replaces rather than doubles, and padded
  bounding spheres keep the promise (conservative for drawing - a far cell may
  be kept, a near cell is never dropped). Re-pointing `WorldScene.addShadows`
  at the projector's yawed quad, and any true shadow-map occlusion, need a live
  GL context - probe-side, not yet a painted pixel.
- **P2.5 Environment reflection / refraction - delivered as kernel + a
  probe-gated plan (added scope).** Reflection and refraction were never in the
  original P0..P5 list; they name later as the missing "modern 3D" beat on the
  water (P2.3) and any glossy surface. Delivered in `wonderland-renderer/shade`:
  `EnvMapKernel` - the five operations an environment pass is built from, as
  pure arithmetic on `float[]` vectors and packed ARGB pixels with nothing but
  `java.base` (the reflect vector; Snell refraction with its
  total-internal-reflection refusal answered `null`; the Schlick fresnel between
  `F0` and a grazing mirror; the canonical cube-map face-and-uv a direction
  samples; and two `mix`/`composite` blends whose `coverage == 0` is the exact
  base pixel, the mirror of `BloomKernel.combine`'s strength-zero identity) -
  and `EnvironmentPlan`, which repeats P2.2's shape: a fixed Java 3D 1.7.2
  pipeline can bind a cube texture but cannot compute a per-fragment fresnel
  blend or a bent lookup, so an environment material takes SHADER only when
  `ShaderAppearancePlan.Capability` reports the shader path live -- a probe has
  since validated the fork's shader API (P2.2 above), so that gate can now open.
  The live ceiling that keeps env probe-side is the same single-texture limit
  `WlProbe42` measured: a program that already samples a diffuse `sampler2D`
  cannot also bind a `samplerCube envMap` on this fork, so an env-only glossy
  surface (no diffuse) is what a live program can paint today. Otherwise the
  material takes FIXED *with the
  lost effects named*, and the plan reuses `ShaderAppearancePlan.Capability` so
  one host answers "is the shader path live" for both features. The GLSL 1.20
  environment fragment (`samplerCube envMap`, `reflect`/`refract`, Schlick
  fresnel, TIR fallback) ships beside the decision and asserts it names the same
  operators the kernel computes. A material with no cube map and `ior == 1`
  requests nothing, so no pre-P2.5 world changes a pixel. Binding a live cube map
  and running the program on `WorldScene`'s glossy cells is the probe-side step -
  the ceiling is the deliverable's shape, the same as P2.2 and P3.2, not an
  apology.

### P3 - Cinematic system

- **P3.1 Camera rig - delivered as kernels.** Move `WorldViewer` from
  fixed-eye framing to a spline-follow avatar, smooth transitions, and
  letterboxing where the pipeline allows. Delivered in
  `wonderland-client/rig`: `FollowRig` - frame-rate-independent exponential
  smoothing toward a seat behind the subject (one lag time constant folds
  `dt` through `1 - e^(-dt/tau)`, so 60 fps and a one-second stall converge
  identically), a teleport radius that SNAPS instead of smearing, and an
  output that IS an `EyeProbe.EyePose` - the framing probe grades the rig
  with no adapter, and `headingYaw()` speaks `SpatialAudio`'s yaw so the
  audio ear rides the camera in one call - plus `CameraPath`, a Catmull-Rom
  tour that passes exactly through its waypoints and clamps a clock that
  overshoots rather than throwing on the render thread. Letterboxing and
  re-seating `WorldViewer`'s live `Transform3D` are pipeline-bound wiring the
  probe owns, not claimed here; the 16 headless cases prove the curves, not
  the ride. **Now wired** (`WorldViewer`): the eye glides toward its target
  through `FollowRig`'s smoothing on a frame timer instead of teleporting, and
  `-Dlg.wonderland.camera=off` restores the instant-step walk.
- **P3.2 Post-processing - delivered at the documented ceiling (stretch).** A
  bloom/HDR pass via `Canvas3D` offscreen buffer. **Marked stretch**: bounded
  by the Java 3D 1.7.2 ceiling above. Delivered in `wonderland-renderer/post`:
  `BloomKernel` - the three operations every bloom is (smooth-kneed bright
  pass, separable clamped box blur, saturating add) as pure arithmetic on
  packed ARGB pixels -- and `PostProcessPlan`, which states the live-3D truth
  as a checked decision: the fork has no scene-to-texture post pipeline, so
  the per-frame `OFFSCREEN_READBACK` route is probe-gated on a MEASURED
  readback cost, and until a `WlProbe` run produces that measurement the
  route is `SOFTWARE_CAPTURE` -- blooming probe captures and 2D composites,
  headless, today. The ceiling is the deliverable's shape, not an apology:
  the kernel is the reference any future GPU pass must match pixel for pixel.

### P4 - Spatial audio (build, not re-wire)

- **P4.1 Audio engine - delivered as kernel + JavaSound backend.** `AudioManager`
  / `AudioNode` / `AudioListener` with position/distance/attenuation driven by
  cell transforms, on the JOAL natives already in the dependency set. This is a
  from-scratch port of the 2006 audio subsystem, not a wiring job. Delivered in
  `wonderland-client/audio`: `SpatialAudio` (the OpenAL inverse-distance gain
  and the ear-radius pan as pure arithmetic), `AudioManager` (the lifecycle
  owner in the `WorldPVS` shape -- register/move/unregister sources, one ear,
  per-frame `update()` that starts, poses and stops what the kernel says), and
  `JavaSoundBackend` behind an `AudioBackend` seam. Reframed on the JOAL point:
  JavaSound ships in the JDK and needs no natives or module rules, so it is the
  first backend; a JOAL one can drop behind the same seam later. The kernel and
  the manager's bookkeeping are fully headless-tested (21 cases); the mixer
  itself -- opening a `Clip` on a real device -- cannot be verified by a
  headless runner and belongs to a machine with speakers. **Now wired**
  (`WorldViewer.installAudio`/`updateAudio`): each eye write points the
  `AudioManager` listener at the live pose; with no shipped world audio and no
  device the manager is silent, so the wiring changes nothing a user hears until
  a world ships sound.

### P5 - Mandela scripting layer

Mandela is dependency-free (`java.base` + `javax.script` only) and embeds
anywhere, which makes it the right **logic/behaviour layer** - never a render
layer (Mandela must not import AWT/Swing/Java 3D/lg3d-core). The renderer/app
*call into* Mandela; Mandela returns values (a transform, a colour, an event)
that the renderer applies to Java 3D nodes. This keeps the dependency direction
legal and the engine embeddable.

- **P5.1 Cell behaviours** *(this PR)*. Reframed after reading the API. There is no
  "register these packages as Mandela modules" step and no host-type import: by
  design a Mandela script sees only `java.base` + `javax.script` and the values
  its host hands it. The real surface is `Mandela.eval(source, Map<String,Object>)`
  (each map entry becomes a bound name, run through `Interop.toMandela`) plus
  `Runtime.Builder.bind(name, Interop.fn1(...))` for host functions. So a cell's
  behaviour is: the host binds that cell's plain data (label, x/y/z, ...) and any
  callbacks as functions, runs a script, and reads back a value the host then
  applies. The bridge cannot live in `wonderland-client` (that module has no
  Mandela dependency); it belongs in `lg3d-apps`, the one module already on both
  `project(':lg3d-mandela')` and the renderer - the same place the Web Browser's
  userscript bridge embeds Mandela today. This is fully headless-testable (the
  engine runs without a display); only the *effect* of a behaviour on the live
  scene needs a screen.

  Delivered as `WorldBehavior` in `lg3d-apps`: a world ships one
  `behaviour.mnd` beside its art, read through the same cached store and the
  same `/api/world/asset` endpoint the models already use (the server serves
  any module-root file, and an absent behaviour is the normal case, not an
  error). For each model cell the host binds its plain facts (`label`, `name`,
  `cellType`, `x/y/z`, `width/depth`, `hasModel`, `isAvatar`, `isGui` - a
  missing number binds as `0.0` so script arithmetic never trips), runs
  `Mandela.eval(source, Map)`, and reads back one string: the cell's
  annotation, which replaces the label floating over it. A null, blank,
  throwing or empty-valued script all collapse to the label the cell was
  authored with - no exception ever reaches the render path. The decision and
  the safety are the 14 headless cases of `WorldBehaviorTest`; the effect of
  an annotation on the live scene stays the walk-in probe's job, exactly as
  P0.1 left its on-screen shading to the `WorldViewer` probe.
- **P5.2 Shipped scripts - delivered.** Behaviours under `scripts/mandela/`,
  compiled by `ShippedScriptsTest`. The first real world behaviour ships as
  `wonderland-outpatient-clinic/behaviour.mnd`, beside the clinic art and read
  through the same `/api/world/asset` endpoint the models already use; the
  shipped file's compile gate keeps it honest and `ClinicBehaviourTest` asserts
  what each kind of cell gets annotated as.
- **P5.3 Probe harness - delivered headless-side.** Formalise the in-JVM
  `WorldViewer` eye probe as Mandela-driven checks of framing/behaviour, so more
  of the renderer becomes verifiable without a hand. `EyeProbe` is the framing
  kernel (where the eye points, what a rig would see), fully tested in-JVM; the
  screencapture half that proves painted pixels still needs a live display.
- **P5.4 Multiplayer depth - delivered as kernels.** The server session layer's
  three missing halves now exist headless in `wonderland-server`:
  `InterestManager` answers *who is in this event's audience* (per-session
  watch of place + radius, union-of-radii reach, replace-not-double on a move,
  NaN refused) — deliberately its own O(n) scan over sessions rather than a
  `WorldPVS` import, because the module depends on neither `wonderland-client`
  nor `lg3d-core`. `CellSnapshot` saves/restores each cell's translation as
  text — `Float.toHexString` so `0.1f` round-trips bit-identical, the
  environment root never written, orientation and scale left untouched because
  the snapshot only claims to know *where*, a missing file is a first boot and
  a torn or foreign one is refused rather than half-applied.
  `PasscodeAuthenticator` is the gate the transport was missing: a stateless
  HMAC-SHA256 bearer token `username|hex`, constant-time verified, null (never
  a throw) for every malformed or forged shape, called at the one seam that
  matters — before `SessionRegistry.create(user, sink)`. What remains a
  running server's proof: fanning audience lists onto real sockets, snapshot
  autosave policy, token expiry/rotation, and Mandela scripts driving
  *networked* events (P5.1 ships only local annotations). **22 new headless
  cases** (`InterestManagerTest` 7, `CellSnapshotTest` 7,
  `PasscodeAuthenticatorTest` 8).

## Recommended order

`P0.1 -> P0.2 -> P1 -> P5 (probes/behaviour) -> P2 -> P4 -> P3`

P0.1 gives the strongest visual gain for the least risk. P5 early, because the
Mandela probe harness makes every later phase verifiable without claiming
untested success.
