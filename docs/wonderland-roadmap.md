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
| Multiplayer / sessions | **Real, wired** | `wonderland-server` `ServerSession`, `SessionRegistry`, `ProtocolRouter`, WebSocket + chat relay; cells/transforms broadcast, with interest-managed move fan-out + snapshot autosave now wired. The passcode gate is wired too: `CommsWebSocketHandler.onAttach` calls `AuthGate.resolve(...)`, which drives `PasscodeAuthenticator`'s HMAC check before a session is minted, default-off via `wonderland.server.auth.enabled`. Missing: token expiry/rotation, still a running server's proof. |
| Collision | **Kernel wired into the walk** | `WorldScene.blockStep` seeds an `AvatarCollider` over the inventory's obstacles, so a step into a wall is refused and slides along its face. `MovableAvatarComponent` -> `AvatarCell.triggerCollision(height, bool)` -> `AvatarActionTrigger` is still the *message* path, and the vertical extent still comes from `WorldScene.measuredOf` (a live-geometry walk), not the authored footprint. |
| Ambient lighting | **Two rigs, one of them inert** | `WorldScene.installLights` hangs an `AmbientLight` + `DirectionalLight` when a caller asks, and `WlProbe32` measured that this fork's fixed pipeline lets a scene `Light` change nothing a `ColoringAttributes` surface draws -- so the shading a person sees is `BakedRig`'s CPU per-vertex bake, on by default. `J3dMeshFactory.buildAppearance` still omits a `Material` for shipped art. |
| Culling | **Wired, per frame** | `WorldScene.cullTo` writes one `Switch` gate per cell from `WorldPVS.visibleFrom`, driven wherever the eye moves; `OctantCuller` is the stateless kernel. Java 3D's own culling still runs underneath. No occlusion query; SSAO ships as a post-chain screen-space effect (P2.7), not a culling primitive. |
| Water shading | **Wired: pond quad + frame-clock scroll** | `MtgSceneReader.readWaterInstances` parses the `JMEWaterInstance` records; `WorldScene.addWater` seats one flat quad per record, tiled by `WaterArt`'s seamless image and scrolled by a `TexCoordGeneration` that `WorldViewer`'s water clock rewrites every 120 ms; `scrollWater` is the live animation path. Each pond hangs behind its own PVS `Switch` gate so culling the ground shuts the water with it. `lg.wonderland.water=off` builds no pond node at all. |
| Shaders | **Present, opt-in** | `ShaderAppearancePlan` holds a validated GLSL 1.20 Blinn-Phong pair; `J3dMeshFactory.buildShaderAppearance` runs it per pixel on a lit material's `ShaderAppearance`. `WlProbe43` drove the shipping `createShape` and measured a facing normal at `(255,255,255)` against a grazing one at the `(31,31,31)` ambient floor. **Off by default** (`-Dlg.wonderland.shader=true` to arm it), so pre-lit worlds stay a byte-identical fixed-function `Appearance`. |
| Normal mapping | **Live, multi-unit** | The GLSL normal/spec stages compile, shade and are now bound: `WlProbe48` measured three distinct `sampler2D` textures (diffuse/normal/spec) live on one program once each sampler is named AND assigned its unit, and `WlProbe49` drove the shipping `createShape` and saw a normal map tilt a facing quad from `(255,255,255)` to `(56,56,56)`. `hasNormalMap`/`hasSpecMap` are set from what actually resolved, so a lit material lights from all the layers its art carries. `WlProbe42`'s earlier "ONE texture per program" was a binding omission (it never assigned the second sampler a unit), not a platform limit. |
| Environment (reflection / refraction) | **Live, cube beside diffuse** | `EnvironmentPlan` carries a full GLSL 1.20 vertex+fragment pair and `J3dMeshFactory.buildEnvAppearance` binds the lit diffuse on unit 0 and a `samplerCube` on unit 1, laying `reflect`/`refract` cube lookups weighted by Schlick fresnel over the lit base. `WlProbe50` proved a cube map samples its own face live (+X red / +Y green / +Z blue) once faces go on with this fork's `setImage(LEVEL, FACE, image)` order; `WlProbe51` drove the shipping `createShape` and read a red diffuse quad `(255,0,0)` become `(230,97,110)` once an environment cube was attached at half reflectivity. `CubeMapArt` is the procedural sky gradient a glossy cell samples (no shipped world names a real cube image). Opt-in like P2.2 (`lg.wonderland.shader` + `lit()` + a cube map), so a pre-P2.5 world changes no pixel. |
| Occlusion | **Cull + screen-space AO** | The `WorldPVS` gate above drops what the eye cannot reach, and since P2.4 it drops each dropped caster's shadow pool with it (`WorldScene.shadowGates`). Since P2.7 a screen-space ambient occlusion (`SsaoKernel` + `GpuSsaoPass`) darkens contact on the live canvas from the frame's own depth -- but there is still no occlusion *query* and no PVS *visibility from a surface*. |
| Barycentric / decals | **Absent** | Hand-built triangles (`tagQuad`, `flatQuad`); no barycentric utilities. |
| Cinematic (PBR/HDR/bloom/shadow maps/DOF/spline camera) | **Partial** | Shadows are `ShadowProjector`'s parallel projection of the caster's box, not `ShadowArt` ellipse decals alone (P2.4); the eye glides through `FollowRig`'s smoothing on a `CameraPath` (P3.1). Bloom now ships on BOTH routes (P3.2): the `BloomKernel` + `BloomPass` software seam (`WlProbe52` measured the readback round trip, ~128 ms at 640x363 / ~419 ms native, and proved it glows a captured live frame) AND a live per-frame `GpuBloomPass` -- a zero-readback GL bright-pass/blur/composite that blits the presented framebuffer into a texture and samples it back through a patched `Canvas3D.preSwap` hook, which `WlProbe56` measured visible per frame at 94 fps (7732 halo pixels lifted onto dark scene). So the old "the fork has no scene-to-texture pipeline" ceiling is retired for post-processing: the pre-swap blit IS that pipeline. Since P3.2 the chain has grown, not stayed a single bloom: HDR tone mapping (P3.3), FXAA (P3.4) and depth-of-field (P3.5) ride the same `preSwap` blit as further `GpuPostEffect` links (order `ssao -> bloom -> hdr -> dof -> fxaa`), screen-space ambient occlusion (P2.7) reconstructs a normal from the depth the defocus blits, and Cook-Torrance PBR (P2.6) ships as a pure BRDF kernel plus a probe-gated `PbrPlan`/GLSL pair in P2.2's shape. Only shadow *maps* remain absent -- they want a light-POV render-to-texture pass the single post-hoc blit does not provide. |
| Audio | **Built, wired, silent until a world ships sound** | `wonderland-client/audio`: `SpatialAudio` (the gain and pan kernel), `AudioManager` (the lifecycle owner), `JavaSoundBackend` behind an `AudioBackend` seam; `WorldViewer.installAudio` points the listener at the live pose. The 2006 client's spatial audio was **not carried over** -- this is the fresh build, and no shipped world has audio assets to play. |

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
vertex-program-era API, not a modern GLSL pipeline. The "2026 cinematic" terms --
HDR, DOF, SSAO, FXAA -- require a direct-JOGL post pass rather than anything
`ShaderProgram`/`GLSLProgram` or a `Canvas3D` readback can express, and that pass
now exists: `GpuBloomPass` (P3.2) drives JOGL directly at a patched
`Canvas3D.preSwap` hook, and HDR (P3.3), FXAA (P3.4), DOF (P3.5) and SSAO (P2.7)
ride the same hook as further `GpuPostEffect` links, so a real-time post chain is
no longer bounded by the readback. PBR (P2.6) is the exception that stays a
per-material `ShaderAppearance` route in P2.2's shape -- a pure kernel plus a
probe-gated GLSL pair -- because it shades surfaces, not the composited frame.
The fork has already hit Java 3D quirks (`Background` paints nothing,
back-face culling, `PolygonAttributes` semantics). A roadmap that ignores this
ceiling would promise results the stack cannot deliver; the phases below mark
those items explicitly.

That ceiling is no longer a guess -- a run of probes measured it on the repo's live
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
- **WlProbe42 (retracted by WlProbe48)** - read this fork as binding exactly ONE
  texture per program, because a second `sampler2D` appeared to re-read unit 0.
  That was a binding omission in the probe, not a platform limit: WlProbe42 set
  two `TextureUnitState`s but never assigned the second sampler a unit, so both
  defaulted to unit 0.
- **WlProbe48** - the correction: EVERY `TextureUnitState` binds its own
  `sampler2D` once the sampler is named on the program (`setShaderAttrNames`) AND
  assigned its unit through a `ShaderAttributeValue(samplerName, unitIndex)`. It
  measured three distinct textures (diffuse/normal/spec) live on one program, so
  per-material normal/spec layers are real on this `ShaderAppearance` route.
- **WlProbe49** - the shipping `J3dMeshFactory.createShape` binds those units too:
  a lit facing quad read `(255,255,255)` with its diffuse and `(56,56,56)` once a
  tangent normal map was attached, the normal map tilting N off the key.
- **WlProbe50** - a `samplerCube` binds its own texture unit and samples the
  correct face live on this fork: three quads each sampling one hardcoded
  direction read +X red, +Y green, +Z blue back. The gate was the fork's
  `TextureCubeMap.setImage(LEVEL, FACE, image)` argument order - the first index
  is the mip level (`checkImageSize` shifts the expected size by it), the second
  the face 0..5, so passing a face constant first reads it as a level and throws
  "illegal image size". A cube map is therefore a real second target beside a
  diffuse, not a ceiling.
- **WlProbe51** - the shipping `J3dMeshFactory.createShape` binds a cube beside a
  diffuse on the environment route: a lit red quad read `(255,0,0)` and the same
  material with an environment cube at half reflectivity read `(230,97,110)`, its
  green and blue lifted by the sampled sky gradient while the diffuse kept it
  red-dominant. An unbound cube samples black and would have left green and blue
  at zero, so the tint separates "the cube bound" from "the cube read nothing".
- **WlProbe52** - the `PostProcessPlan` gate the P3.2 live route waits on, now
  MEASURED rather than assumed: the probe ran the fork's readback round trip on
  the live display - `readRaster` a frame, convert to packed ARGB, bloom it
  through the wired `BloomPass` - across two runs at ~58-167 ms for a 640x363
  frame and ~368-419 ms for a native 1920x1080 software bloom. It proved the
  bloom is correct on a LIVE capture, not just a synthetic array: the halo around
  a hot white quad lifted from luminance 69 to 76, lit pixels grew 50,176 ->
  54,644, and the wired `BloomPass` produced byte-identical pixels to the raw
  `BloomKernel`. So the round trip WORKS - a software-capture bloom is real - and
  its cost is the number that keeps the per-frame native `OFFSCREEN_READBACK`
  route gated instead of shipping a desktop at 2-8 fps.
- **WlProbe53** - the ceiling above, re-measured for bloom: a framebuffer blit of
  the presented scene into a texture and back (`FBO-BLIT-OK`) runs on the live
  context at 56 fps, and an inline bright-pass/blur/composite lifted a hot quad's
  halo from 7 to 13 -- a GPU post pass is real-time on this fork and the renderer
  is hookable, which is what retired the "no scene-to-texture" claim.
- **WlProbe55** - the patched `java3d-core`: an overridable `public
  Canvas3D.preSwap()` the `Renderer` calls between its sync render and the buffer
  swap (`postRender` proven UNVIABLE - FB0 black there, ~1 s/frame). An inline
  bloom in a `preSwap` override measured VISIBLE per frame at 71 fps, lifting 7732
  halo pixels onto dark scene with zero CPU readback.
- **WlProbe56** - the same measurement against the PRODUCTION `GpuBloomPass` the
  patch enabled, not an inline copy: `PROD-BLOOM-VISIBLE`, 94 fps (10.67 ms mean /
  11.54 ms max at 1280x763), the before/after FB0 diff lifting 7732 pixels, every
  one onto dark background, max lift 75, the pass never latching a GL fault -- the
  shipped chain reproduces the proven constants exactly.

What this buys: real GPU per-pixel lighting was earlier (wrongly) filed as a
platform impossibility; it is now live and wired, and a layered material lights
from every texture its art carries -- diffuse, normal and specular -- not one.

## Roadmap

Phases are independently deliverable, each a module-scoped PR. Ordered by how
much visible value each unlocks per unit of risk.

### P0 - Rendering foundations

- **P0.1 Opt-in lighting rig** *(delivered)*. A pure `WorldScene.Lighting` value
  and a `MaterialAppearance` lighting model; `J3dMeshFactory` builds a Java 3D
  `Material` only when a material opts in. `WorldScene.installLights` does hang
  an `AmbientLight` + `DirectionalLight` when a caller asks, but `WlProbe32/39`
  measured that a scene-graph `Light` changes no pixel a `ColoringAttributes` or
  a custom-program surface draws on this fork -- so the shading a person
  actually sees is `BakedRig`'s CPU per-vertex bake (the default), and on the
  opt-in shader route the key reaches the program as its OWN
  `lightDirection`/`lightColor`/`ambientColor` uniforms (P2.2), never the scene
  `Light`. The faithful pre-lit default path is unchanged. Verified headless for
  the pure model; the on-screen shading is verified with the live `WorldViewer`
  probe (a display is required).
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
- **P1.2 Trigger volumes - now wired.** `onEnter`/`onExit` zones (room doorways, a
  stage) - the substrate for scripted behaviours (P5). Delivered across three
  layers with the same honesty as P1.1's clamp: the *edge detector* was already a
  headless-tested kernel (`TriggerZone` an axis-aligned volume, `TriggerField`
  remembering the previous inside-set and reporting only membership flips in
  registration order - `TriggerFieldTest`). What was missing was anything driving
  it. `WorldScene.triggerZones()` now derives one zone per placed cell from the
  world's own inventory (footprint as the horizontal half-extents, blocking height
  as the vertical band) - deliberately derived, not authored, because the 2006
  `.mtg` format carries no trigger record and inventing an authoring schema nothing
  produces repeats the `CellSnapshot` mistake; `zonesOf` is a pure function asserted
  headless (`WorldSceneTest`). `TriggerWatcher` is the small stateful layer the
  renderer holds between frames - it primes the starting room so entry is not a
  spurious "enter", forwards each crossed edge with the walker's real pose, and
  swallows a listener that throws so a behaviour bug is silence, never a dropped
  frame (`TriggerWatcherTest`, 5 cases). `WorldViewer` installs it once the walk's
  first pose is chosen and drives it at the single place the eye is ever written
  (`apply`), behind `lg.wonderland.trigger`, walk-only, and inert for a world with
  no volumes or no listener - so the pre-P1.2 walk stays byte-identical. The edge
  reaches P5 through the seam the dependency rule forces: the renderer cannot
  import Mandela, so `WorldRender` (the walk-in host that already runs the world's
  `behaviour.mnd` for cell labels) attaches a listener and `WorldBehavior.triggerNote`
  binds `trigger`/`action`/`x`/`y`/`z` for one crossing, its answer shown in the
  window title via `WorldViewer.setStatus` (`WorldBehaviorTest`, 6 cases). The
  crossing decision, the priming, the throw-safety and the behaviour note are
  headless-proven; the title changing while a person walks is the probe-side half,
  exactly as P4.1 left its on-screen ear.

### P2 - Materials, shaders, water, shadows

- **P2.1 Multi-layer materials - wired, multi-unit.** `MaterialAppearance` carries
  a `normalMapPath` and a `specularMapPath`, `TextureBindingPlan` classifies each
  authored sampler into the diffuse / normal / specular layer it names, and
  `ShaderAppearancePlan`'s GLSL has a tangent-space normal stage and a
  specular-mask stage. `J3dMeshFactory.buildShaderAppearance` now binds all three
  to one program: diffuse on unit 0, the normal map on the next unit, the specular
  mask on the one after, each sampler named on the program and assigned its unit
  through a `ShaderAttributeValue`, with `hasNormalMap`/`hasSpecMap` set from what
  actually resolved. `WlProbe48` measured three distinct textures live on one
  program and `WlProbe49` drove the shipping `createShape` and saw the normal map
  tilt a facing quad from `(255,255,255)` to `(56,56,56)`. The earlier
  "one texture per program" reading (`WlProbe42`) was a probe that never assigned
  the second sampler a unit -- a binding omission, now corrected, not a ceiling.
- **P2.2 Shaders - wired and measured (was: probe-gated).** A GLSL `ShaderProgram`
  on `Shape3D`s, with a documented fixed-function fallback where Java 3D 1.7.2
  cannot express the effect. This repo's Jogamp fork moved the shader entry point
  off upstream 1.6's `Appearance.setShaderProgram` onto a `ShaderAppearance`
  subclass (`setShaderProgram`/`setShaderAttributeSet`), and a program validates
  only against a live GL context. That validation is now done: `WlProbe39..49`
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
- **P2.3 Water shading - delivered and wired.**
  Re-honour the `JMEWaterInstance` records `MtgSceneReader` currently drops, as
  an animated water material. Delivered: `WaterInstance` parses the fifteen-
  token record (`MtgSceneReader.readWaterInstances`, carried to the renderer on
  `ModelLoadResult.waterInstances`), and `WaterArt` supplies the honest fixed-
  pipeline stand-in for jME's water shader -- a headless-rasterised seamless
  tile with per-axis scroll-offset maths (`planeOf` folds the record's plane)
  and `planeS`/`planeT` the generation planes the scene writes every frame.
  **Now wired** (`WorldScene`/`WorldViewer`): `collectWater` gates on
  `WorldFeatures.WATER` and refuses a degenerate extent; `addWater` seats one
  `flatQuad` per pond in the composed cell matrix (`pondSeat`), hangs it behind
  its own PVS `Switch` gate, and attaches a `TexCoordGeneration` (OBJECT_LINEAR,
  `ALLOW_PLANE_WRITE`) so the tile repeats across a plane of any size; the
  viewer's water clock calls `scrollWater` every 120 ms, which rewrites both
  planes from the frame's elapsed time; `cullTo` shuts each pond's gate with the
  ground under it. `lg.wonderland.water=off` builds no pond node at all, so
  every world that never had a water record draws exactly the pixels it drew
  before. The painted ripple on a live display is the probe's remaining claim.
- **P2.4 Real shadows / occlusion - delivered, wired, and measured on screen.**
  Replace `ShadowArt` decals with projected or shadow-map shadows; occlusion via
  the PVS from P0.2. Delivered: `ShadowProjector` - parallel-light box
  projection as pure arithmetic (the pool travels in the direction the light
  travels, low sun lengthens, a horizon sun caps the run at six caster heights,
  a vertical or absent light answers `null` so the round `ShadowArt` pool stays
  the physically right fallback) - and `WorldPVS`, the lifecycle owner that turns
  P0.2's stateless `OctantCuller` answer into a per-frame consultation: cells
  register and detach, a re-attached cell replaces rather than doubles, and
  padded bounding spheres keep the promise (conservative for drawing - a far cell
  may be kept, a near cell is never dropped). **Now wired** (`WorldScene`): each
  pool is the projector's yawed quad rather than the ellipse decal, seated so its
  **near edge stays on the caster's feet** and turned by `anchoredSeat` when the
  scene's own 12 m ceiling has to shorten it (a quad clamped about its centre is a
  shadow lifted off the person casting it). The rig it projects along is the one
  *in force* -- `keyInForce` reads the caller's `Lighting` when it is enabled and
  `BakedRig`'s key otherwise, because no shipped world asks for a rig and every
  face a person sees is already raked by the bake. Each pool hangs behind its own
  `Switch` gate named after its caster, so `cullTo` shuts a dropped building's
  shadow with it (`poolsHiddenLastFrame` is the number that rules the ghost out);
  an unnamed caster is never culled. `WlProbe44` measured the painted half in six
  runs on the live display: the bake's sun darkens 3.14 % of the frame against the
  broken projection's 0.61 %, the mirrored sun runs the shadow the opposite way
  (6.89 %), and an overhead key differs by **zero pixels** from the feature off --
  the refusal proven rather than asserted.
  Wiring it is also what found the maths wrong: the first version divided by
  `sin(elevation)` where parallel projection says `tan`, and scaled the pool's
  length off the footprint instead of off the run, so a 22-degree sun's shadow
  started metres past the feet it belonged to. `ShadowProjectorTest`'s 45-degree
  case read `sqrt(2)` -- the number the bug produced -- which is the recurring
  lesson that a test written against an implementation cannot catch it; the
  anchor is now asserted as an invariant across every elevation. What remains
  probe-side is the shadow *map*, which needs a light-POV render-to-texture pass
  -- a different thing from the single post-hoc framebuffer blit P3.2's GPU bloom
  uses, and still absent.
- **P2.5 Environment reflection / refraction - wired and measured (was: probe-gated).**
  Reflection and refraction were never in the original P0..P5 list; they name
  later as the missing "modern 3D" beat on the water (P2.3) and any glossy
  surface. The arithmetic half lives in `wonderland-renderer/shade`:
  `EnvMapKernel` - the five operations an environment pass is built from, as
  pure arithmetic on `float[]` vectors and packed ARGB pixels with nothing but
  `java.base` (the reflect vector; Snell refraction with its
  total-internal-reflection refusal answered `null`; the Schlick fresnel between
  `F0` and a grazing mirror; the canonical cube-map face-and-uv a direction
  samples; and two `mix`/`composite` blends whose `coverage == 0` is the exact
  base pixel, the mirror of `BloomKernel.combine`'s strength-zero identity) -
  and `EnvironmentPlan`, which repeats P2.2's shape and now carries a complete
  GLSL 1.20 pair (`ENV_VERTEX_GLSL` + `ENV_FRAGMENT_GLSL`): the vertex stage
  writes the same eye-space `vEyeNormal`/`vViewDir` varyings P2.2 proved live,
  and the fragment samples the diffuse, lights it, then lays `reflect`/`refract`
  cube lookups weighted by Schlick fresnel over that lit base at the material's
  reflectivity - `EnvMapKernel.composite` spelled in shading language, so the
  headless kernel and the program cannot disagree.
  The binding is no longer unmeasured. `WlProbe50` proved a `samplerCube` binds
  its own texture unit and samples the correct face live on this fork (+X red,
  +Y green, +Z blue each read back their own face) once the faces go on with
  this fork's `setImage(LEVEL, FACE, image)` argument order - passing the face
  constant first reads it as a mip level and halves the expected image size, the
  same class of binding omission that once mis-read the fork as single-texture.
  `WlProbe51` then drove the SHIPPING `J3dMeshFactory.createShape`: a lit red
  diffuse quad read `(255,0,0)`, and the same material with an environment cube
  at half reflectivity read `(230,97,110)` - still red-dominant (the diffuse on
  unit 0 bound) with its green and blue lifted by the sampled sky gradient (the
  `samplerCube` on unit 1 bound beside it). An unbound cube samples black and
  would have read a darker red with green and blue at zero, so the tint is the
  proof. `buildEnvAppearance` is live behind the same triply-conservative gate as
  P2.2 (`lg.wonderland.shader`, `lit()`, a resolved diffuse) plus
  `EnvironmentPlan.decide(...).appliesEnvironment()`, so a material that names no
  cube map keeps the plain Blinn-Phong route and a pre-P2.5 world changes not a
  pixel. `CubeMapArt` is the procedural six-face environment gradient (64 px
  faces, zenith/nadir caps plus a sky-to-horizon-to-ground side ramp) a glossy
  cell samples when no shipped world's art carries a cube map - and none does,
  the 2006 `<reflectivity>` texture reference never resolving to a real image -
  rasterised headless in pure java2d (`CubeMapArtTest`); the factory wraps its
  faces into the shared `TextureCubeMap` the program samples. A material with no
  cube map and `ior == 1` requests nothing, so it stays FIXED and untouched.
- **P2.6 Physically-based shading - delivered as a kernel + probe-gated plan.** A
  metallic-roughness BRDF the lit route can run instead of Blinn-Phong. Delivered
  in `wonderland-renderer/shade`: `PbrKernel` is Cook-Torrance in pure Java --
  GGX/Trowbridge-Reitz normal distribution `D`, Smith-Schlick geometry `G`,
  Schlick Fresnel `F`, the dielectric-0.04 / metal-base-colour `F0`, and the
  assembled energy-conserving direct-light BRDF plus a `shade()` that adds ambient,
  `java.base` only. `PbrPlan` (in `mesh`) is the headless verdict of "does this
  material run Cook-Torrance", with a reason beside every branch, carrying the
  GLSL 1.20 vertex+fragment pair that transcribes `PbrKernel` (same PI, same 0.04
  `F0`, same D/G/F, same `alpha = roughness^2`). It is probe-gated exactly like
  P2.2's `ShaderAppearancePlan` SHADER path: production keeps the validated
  Blinn-Phong route until a `WlProbe` validates this program, and a material falls
  back with its metallic/roughness recorded but unapplied rather than silently
  flat. `PbrKernelTest` (10) pins the physical invariants (dielectric `F0` 0.04, a
  metal's `F0` is its base, Fresnel head-on vs grazing, GGX sharpens as roughness
  falls, Smith in (0,1], BRDF non-negative and zero below the horizon, a black
  metal reflects nothing, `shade` scales by the cosine) and `PbrPlanTest` (7) pins
  every decision branch and that the GLSL names the kernel's constants. The live
  route is probe-gated, not asserted, exactly like P2.2.
- **P2.7 Screen-space ambient occlusion - delivered, wired on the post chain.**
  Contact darkening from the frame's own depth, run FIRST on the preSwap chain so
  the scene is occluded before the cosmetic lens effects and the tone map fold it.
  Delivered in `wonderland-renderer/post`: `SsaoKernel` is the occlusion estimate
  in pure Java -- the symmetric-perspective unprojection (`near*far/(far -
  d*(far-near))`), a view normal from the reconstructed position's screen-space
  gradients flipped toward the eye, and a fixed twelve-point hemisphere sampled
  with a deterministic per-pixel rotation, a depth range-check and a self-bias,
  counting how many samples a nearer surface hides; it reuses `HdrKernel.Frame`,
  and `apply()` modulates the composited RGB (the honest approximation available
  to a pass that sees the final frame, not the direct/ambient split).
  `GpuSsaoPass` blits FB0 colour+depth into scene textures, runs the hemisphere
  sample and multiplies back into the swap buffer, zero readback, same GL state
  bracket and never-throws latch; `SSAO_FRAG` is `SsaoKernel` transcribed with the
  identical twelve literals, the identical integer rotation and a `texelFetch` per
  depth read so a sample lands on the exact texel the kernel indexes.
  `PostProcessPlan.decideSsao` answers the GPU route or NONE, never a half-applied
  software darkening. `WorldViewer` prepends the pass with the camera-matched
  `Settings` it derives from the scene's own near/far/fov and the canvas aspect
  (front clip extracted to `FRONT_CLIP_M` so the reconstruction cannot drift from
  the view `show()` sets). `SsaoKernelTest` (11) proves a flat field unoccluded, a
  pit floor beside its wall occluded but its flat middle not, a raised platform
  not, the factor in 0..1, a zero intensity the exact identity; `GpuSsaoPassTest`
  (5) and the four `decideSsao` branches in `PostProcessPlanTest`. The GL pass is
  probe-gated like every link on this chain (a `GLContext` needs a display).

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
- **P3.2 Post-processing - delivered on two routes: software capture and a live
  per-frame GPU bloom.** A bloom/HDR pass via `Canvas3D` offscreen buffer. **Was
  marked stretch**, bounded by the Java 3D 1.7.2 ceiling above; the GPU half is
  what retired that bound. Delivered in `wonderland-renderer/post`:
  `BloomKernel` - the three operations every bloom is (smooth-kneed bright
  pass, separable clamped box blur, saturating add) as pure arithmetic on
  packed ARGB pixels -- `PostProcessPlan`, which states the live-3D truth as a
  checked decision (now with a `GPU_POSTPROCESS` path beside `SOFTWARE_CAPTURE`
  and `OFFSCREEN_READBACK`), and `BloomPass`, the seam a real capture calls: hand
  it the frame a `readRaster` or a 2D composite produced plus what the host knows,
  and it returns what the plan allows -- the exact identity for `NONE`, the
  bloomed frame otherwise, bridging a `BufferedImage` to the kernel and back.
  The software measurement the plan was gated on exists: `WlProbe52` ran the round
  trip on the live display and measured ~128 ms for a 640x363
  capture-convert-bloom and ~419 ms for a native 1920x1080 software bloom on the
  reference card, and it PROVED the pass glows a captured LIVE frame - the halo
  around a hot quad lifted from luminance 69 to 76, lit pixels grew by 4,468 -
  through the wired `BloomPass`, byte-identical to the raw kernel. So the fork's
  `OFFSCREEN_READBACK` route is real and correct but costs far more than a
  real-time frame at native resolution, which is why `PostProcessPlan` keeps it
  gated behind `READBACK_PROBED_USABLE`.
  The per-frame live route is no longer absent: `GpuBloomPass` runs the same three
  operations as a zero-readback GL chain (blit the presented framebuffer into a
  scene texture, bright-pass, separable Gaussian ping-pong, additive composite)
  reached through a **patched `java3d-core`** that adds an overridable `public
  Canvas3D.preSwap()` the `Renderer` calls between its sync render and the buffer
  swap (`postRender` was proven UNVIABLE - FB0 reads black there and it cost
  ~1 s/frame). `BloomCanvas` is the thin `preSwap` adapter, and `WorldViewer`
  builds one only when the plan decides `GPU_POSTPROCESS`, gated on
  `WorldFeatures.BLOOM` and a runtime check that the patched hook is present.
  `WlProbe55` measured an inline chain visible per frame at 71 fps (7732 halo
  pixels lifted onto dark scene); `WlProbe56` re-measured the PRODUCTION
  `GpuBloomPass` at `PROD-BLOOM-VISIBLE`, 94 fps (10.67 ms mean / 11.54 ms max at
  1280x763), the before/after FB0 diff lifting the same 7732 pixels, every one
  onto dark background, max lift 75, with the pass never latching a GL fault. The
  `GpuBloomPass` GL chain and `BloomCanvas` cannot be constructed headless (a
  `GLContext` needs a display), so they are probe-verified, while the pure
  `PostProcessPlan` decision and `GpuBloomPass.Settings` are asserted
  (`PostProcessPlanTest`, `GpuBloomPassTest`). `BloomKernel` stays the reference
  the GPU shaders match pixel for pixel.
- **P3.3 HDR tone mapping - delivered, wired on the post chain.** Fold scene
  radiance into display range on the live canvas. Delivered in
  `wonderland-renderer/post`: `HdrKernel` is the pure tone-mapping reference
  (exposure, extended Reinhard with a white point, Narkowicz ACES, Rec.709
  luminance, gamma encode) over a packed ARGB `int[]` and `float` triples,
  `java.base` only. `GpuPostEffect` is the one contract every GPU pass honours
  (`render`/`dispose`/`isFailed`); `GpuBloomPass` now declares it unchanged.
  `GpuHdrPass` captures FB0 into a scene texture, runs a fullscreen tonemap and
  writes back into the swap buffer, zero CPU readback, same GL state bracket and
  never-throws latch; its GLSL is `HdrKernel.curve` transcribed. `PostProcessCanvas`
  generalizes the old single-pass `BloomCanvas` into an ordered `GpuPostEffect`
  chain that skips any failed link inside one `preSwap` (`BloomCanvas` removed).
  `PostProcessPlan.decideHdr` answers the GPU route or NONE, never a half-applied
  software curve. `WorldViewer` builds the chain from both decisions and adds
  `getHdrDecision()`. `HdrKernelTest` (12), `GpuHdrPassTest` (5) and the four
  `decideHdr` branches in `PostProcessPlanTest`; the on-screen GL pass is
  probe-gated like the bloom.
- **P3.4 Fast-approximate anti-aliasing - delivered, wired on the post chain.**
  Soften the edges of the very image the eye is shown, run LAST. Delivered in
  `wonderland-renderer/post`: `FxaaKernel` is FXAA's published subpixel stage --
  the local-contrast edge gate plus the subpixel blend toward the four-neighbour
  average, using Lottes's `FXAA_SUBPIX_TRIM` 1/4 and its `1/(1-trim)` scale,
  clamped by the subpixel quality -- reusing `HdrKernel`'s luminance and `Frame` so
  the whole chain agrees on one definition of "how bright". It is the subpixel
  stage only, not FXAA's directional span search, and claims no more.
  `GpuFxaaPass` captures FB0, runs a fullscreen subpixel pass and writes back,
  zero readback, same GL state bracket and never-throws latch; `FXAA_FRAG` is
  `FxaaKernel` transcribed. `PostProcessPlan.decideFxaa` answers the GPU route or
  NONE. `WorldViewer` appends the pass last and adds `getFxaaDecision()`.
  `FxaaKernelTest` (9) -- a flat frame is byte-for-byte unchanged, the gate needs
  both floor and luma fraction, an isolated impulse is pulled toward its average
  by exactly the published weight and nothing else moves, alpha carried through --
  `GpuFxaaPassTest` (5) and the four `decideFxaa` branches. Probe-gated GL pass.
- **P3.5 Depth of field - delivered, wired on the post chain.** Defocus by depth,
  after the tone map and before FXAA so the gather works on the display-referred
  image and the anti-alias still runs last. Delivered in `wonderland-renderer/post`:
  `DofKernel` is the scatter-as-gather defocus in pure Java -- the circle of
  confusion a normalised depth implies (`clamp01` of the depth error over the
  focus band, symmetric about the focus), the `round(coc * maxRadiusPx)` gather
  radius, and a disc average that skips an out-of-frame or outside-the-disc sample
  and divides by the count it kept -- reusing `HdrKernel.Frame`. `GpuDofPass` is the
  first link to blit DEPTH beside colour (`GL_COLOR_BUFFER_BIT |
  GL_DEPTH_BUFFER_BIT`, `GL_NEAREST` as a depth blit requires) into a paired
  `GL_DEPTH_COMPONENT24` attachment, because at `preSwap` the depth the renderer
  just wrote is still GPU-resident; `DOF_FRAG` is `DofKernel.gather` transcribed.
  `PostProcessPlan.decideDof` answers the GPU route or NONE. `WorldViewer` inserts
  the pass between the tone map and FXAA and adds `getDofDecision()`; the scene
  depth survives bloom and the tone map (both write only colour), so DOF reads the
  true depth wherever it sits. `DofKernelTest` (10) -- the CoC is 0 at the focus,
  monotonic, symmetric and saturating, the radius caps, a zero max radius is the
  exact identity, a uniform frame survives ANY depth field untouched (the invariant
  that proves the gather invents no detail), a focus pixel stays sharp while a far
  pixel blurs -- `GpuDofPassTest` (5) and the four `decideDof` branches. Probe-gated
  GL pass.

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
- **P5.4 Multiplayer depth - audience + autosave wired; auth is a tested kernel.** The server
  session layer's three missing halves exist in `wonderland-server`, and two
  of them — the audience and the autosave — are now on the live path:
  `InterestManager` answers *who is in this event's audience* (per-session
  watch of place + radius, union-of-radii reach, replace-not-double on a move,
  NaN refused) — deliberately its own O(n) scan over sessions rather than a
  `WorldPVS` import, because the module depends on neither `wonderland-client`
  nor `lg3d-core`. **Now wired** (`ProtocolRouter`): a `MovableMessage` on a
  session's OWN view cell re-homes that session's watch, and `broadcastMove`
  fans to `audienceFor(eventPlace, cellReach)` rather than `registry.all()` --
  the mover always gets its echo, and a session with no registered watch still
  receives everything (the default-open rule: `isTracked` gates filtering, so
  before any avatar walks this is byte-identical to the pre-P5.4 broadcast and
  every existing multiplayer test stands). World chat stays a global relay --
  it is addressed to the environment cell every session listens on, so it is
  not an interest event by design. A cache detach `forget`s the departed watch
  so a ghost is never a recipient. `CellSnapshot` saves/restores each cell's translation as
  text — `Float.toHexString` so `0.1f` round-trips bit-identical, the
  environment root never written, orientation and scale left untouched because
  the snapshot only claims to know *where*, a missing file is a first boot and
  a torn or foreign one is refused rather than half-applied. `CellSnapshot` was
  a memory nothing called; **`WorldAutosaver` is now the policy that runs it** —
  it polls a new `WorldModel.revision()` counter (bumped by every add/graft/move/
  remove, never by a read) and, once the world has settled for one quiet interval,
  writes the snapshot, retrying a failed write rather than silently forgetting
  where anything is. The thin `WorldSnapshotService` bean gives it a real
  `@Scheduled` clock plus a restore-on-boot, and stays inert until an operator
  names `wonderland.server.snapshot.path` (the blank default is the pre-P5.4
  server byte-for-byte).
  `PasscodeAuthenticator` is the gate the transport was missing — a stateless
  HMAC-SHA256 bearer token `username|hex`, constant-time verified, null (never
  a throw) for every malformed or forged shape — but it is still a tested
  **kernel only**: wiring it before `SessionRegistry.create(user, sink)` needs a
  deployment decision on the no-token case and a configured secret, so it is
  deliberately left uncalled rather than gated behind a half-chosen policy. What
  remains a running server's proof: that auth-at-seam wiring, token
  expiry/rotation, and Mandela scripts driving *networked* events (P5.1 ships
  only local annotations). **14 new headless cases** (`ProtocolRouterInterestTest`
  5, `WorldAutosaverTest` 6, `WorldSnapshotServiceTest` 3) prove the audience and
  autosave wiring engage; the
  kernels keep their **22** (`InterestManagerTest` 7, `CellSnapshotTest` 7,
  `PasscodeAuthenticatorTest` 8).

## Recommended order

`P0.1 -> P0.2 -> P1 -> P5 (probes/behaviour) -> P2 -> P4 -> P3`

P0.1 gives the strongest visual gain for the least risk. P5 early, because the
Mandela probe harness makes every later phase verifiable without claiming
untested success.
