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
| Shaders | **Absent** | `MaterialAppearance` = one diffuse RGBA + one `texturePath`, fixed-function `Appearance`. |
| Water shading | **Degraded/removed** | `MtgSceneReader` skips `JMEWaterInstance` records. |
| Normal mapping | **Absent** | Single `texturePath` per material; no second texture stage. |
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
  computed.
- **P1.2 Trigger volumes.** `onEnter`/`onExit` zones (room doorways, a stage) -
  the substrate for scripted behaviours (P5).

### P2 - Materials, shaders, water, shadows

- **P2.1 Multi-layer materials.** Extend `MaterialAppearance` /
  `TextureBindingPlan` with a normal map (and specular map); `J3dMeshFactory`
  binds the extra stages.
- **P2.2 Shaders - reframed after reading the fork.** A GLSL `ShaderProgram` on
  `Shape3D`s, with a documented fixed-function fallback where Java 3D 1.7.2
  cannot express the effect. The measured ceiling: this repo's Jogamp fork moved
  the shader entry point off upstream 1.6's `Appearance.setShaderProgram` onto a
  `ShaderAppearance` subclass (`setShaderProgram`/`setShaderAttributeSet`, read
  off the 1.7.2 jar), and a program validates only against a live GL context.
  Delivered: `ShaderAppearancePlan` - the headless decision (FIXED vs SHADER),
  the named-layer fallback, and the GLSL 1.20 pair - with `J3dMeshFactory`'s
  wiring deliberately unapplied until a `WlProbe` run validates the fork's API;
  until then every material takes the FIXED route and pixels are unchanged.
- **P2.3 Water shading.** Re-honour the `JMEWaterInstance` records
  `MtgSceneReader` currently drops, as an animated water material.
- **P2.4 Real shadows / occlusion.** Replace `ShadowArt` decals with projected
  or shadow-map shadows; occlusion via the PVS from P0.2.

### P3 - Cinematic system

- **P3.1 Camera rig.** Move `WorldViewer` from fixed-eye framing to a
  spline-follow avatar, smooth transitions, and letterboxing where the pipeline
  allows.
- **P3.2 Post-processing.** A bloom/HDR pass via `Canvas3D` offscreen buffer.
  **Marked stretch**: bounded by the Java 3D 1.7.2 ceiling above.

### P4 - Spatial audio (build, not re-wire)

- **P4.1 Audio engine.** `AudioManager` / `AudioNode` / `AudioListener` with
  position/distance/attenuation driven by cell transforms, on the JOAL natives
  already in the dependency set. This is a from-scratch port of the 2006 audio
  subsystem, not a wiring job.

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
- **P5.2 Shipped scripts.** Behaviours under `scripts/mandela/`, compiled by
  `ShippedScriptsTest`.
- **P5.3 Probe harness.** Formalise the in-JVM `WorldViewer` eye probe as
  Mandela-driven checks of framing/behaviour, so more of the renderer becomes
  verifiable without a hand.
- **P5.4 Multiplayer depth.** Interest management + persistence + auth on the
  (already real) server session layer; Mandela scripts drive the networked
  events.

## Recommended order

`P0.1 -> P0.2 -> P1 -> P5 (probes/behaviour) -> P2 -> P4 -> P3`

P0.1 gives the strongest visual gain for the least risk. P5 early, because the
Mandela probe harness makes every later phase verifiable without claiming
untested success.
