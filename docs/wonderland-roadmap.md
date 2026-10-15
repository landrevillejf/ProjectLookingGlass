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
- **P0.2 Culling / PVS.** An explicit potential-visible-set in
  `wonderland-client` that mounts only cells near the eye - a prerequisite for
  occlusion (P2.4) and large multiplayer worlds.

### P1 - Real physical interaction

- **P1.1 Collision.** Replace the `triggerCollision` boolean with an
  AABB/`BoundingVolume` test against the cell being approached; clamp/refuse
  the move in `MovableAvatarComponent.localMoveRequest`. Add a per-cell
  `CollisionComponent`.
- **P1.2 Trigger volumes.** `onEnter`/`onExit` zones (room doorways, a stage) -
  the substrate for scripted behaviours (P5).

### P2 - Materials, shaders, water, shadows

- **P2.1 Multi-layer materials.** Extend `MaterialAppearance` /
  `TextureBindingPlan` with a normal map (and specular map); `J3dMeshFactory`
  binds the extra stages.
- **P2.2 Shaders.** A GLSL `ShaderProgram` on `Shape3D`s, with a documented
  fixed-function fallback where Java 3D 1.7.2 cannot express the effect.
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

- **P5.1 Cell behaviours.** A `MandelaBehaviorEngine` in `wonderland-client`
  (the JSR-223 engine) with per-cell `onEnter`/`onCollision`/`onChat`/`onTick`
  callbacks; `AvatarCell`/`MovableAvatarComponent` are the hook points.
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
