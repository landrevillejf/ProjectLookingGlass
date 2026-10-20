# TODOs:

## Full featured production grade to implement, fast, secure, robust, reliable, customizable and scalable

### Processes isolation:
-On devrait isoler tous les processus des MDI, comme QNX, comme ca, ca serait robuste , fiable et recoverable, no desktop crash

### Security
-nftable pour le killswitch comme WHONIX

### Office:
-creer un pdf creator en kotlin
-creer une suite bureautique qui est compatible avec Microsoft Office et LibreOffice, ecrite en java et qui utilise Mandela

### Penetration Testing:
-creer une suite de penetration testing tools en Mandela, NSA/CIA grade

### Games:

### Wonderland World to create:
-Lire le pdf du livre d'Urantia dans /books et en faire un univers 3D interactif des plus fidèle possible 
et possible de jouer, qui respecte les lois de la physique et de la nature,
qui utilise Wonderland et Mandela, jeux de style mmorpg qui peut etre multi-joueur, qui utilise shaders, occlusion, normal mapping, water shading , 
ambient lighting, barrycentric,,etc avec habilites, pouvoir, armures , armes , spells , inventaire (style RPG), avec combats et quetes, multi niveaux, quetes, experiences, montee de niveau, etc
Audio system, Meteo System , chat system, Cinematic system, Systeme de sauvegarde, gardening, buying/selling, Moonshining (alcohol production), building, society interactions / management,  Guis, menus, controls, Un systeme d'effets (feux, glace, eclairs, etc)
A Game that is : Full featured production grade to implement, fast, secure, robust, reliable, customizable and scalable
Utiliser Wonderland, jogamp, jdk 21 , tester, 100% couvert, pas de bug, pas de cve, pas de mutation, etc

### Quality :
-le checkstyle, jacoco, pit, cucumber et tests green suite, owasp (pas de cve), sonar qube (pas de bugs, etc) doivent rouler dans le CI, 100% de couverture, pas de mutation c'est mandatory dans une pull request. comme pour une banque, les agents doivent le savoir.
tu dis : PIT reste limité à lg3d-mandela (seul module configuré) ; le zip et la page n'affichent un rapport PIT que pour ce module, et l'index le dit clairement plutôt que de simuler une couverture PIT globale. Ce n'est pas normal tous les modules doivent repondre aux memes regles de couverture PIT. jacoco, cucumber, pit, sonarqube, checkstyle, etc.

## Features to add (Wonderland) or corrections:

- Wonderland ne doit pas avoir de dependance a Looking Glass, c'est Looking Glass qui doit avoir une dependance a Wonderland
- Les mondes doit pouvoir communiquer entre eux et etre synchronises en temps reel, avec une architecture distribuee et scalable
- Wonderland doit avoir un WorldGenerator, terrainGenerator(relief, mountains, lake, rivers, etc) WorldEditor, AssetsGenerator, CharacterGenerator, un dev kit complet
- Un monde doit avoir une carte miniature de celui ci affiche en GUI, et etre synchronise en temps reel
- Wonderland doit avoir un systeme de sauvegarde et de recovery en temps reel
- Wonderland doit avoir des menus et des options pour interagir avec le monde
- Wonderland doit avoir un systeme de particules et d'effets visuels, comme des feux, glace, eclairs, etc
- Wonderland doit avoir un systeme de meteo qui influence le monde, comme la pluie, neige, vent, etc
- Wonderland doit avoir des controles permettant d'interagir avec le monde, comme de se deplacer, de regarder, de parler, de se connecter, de se deconnecter, de se téléporter, de se battre, de faire des quêtes, de gagner des expériences, de monter de niveau, etc
- Wonderland doit avoir un systeme d'animation pour les personnages et les objets
- Wonderland doit avoir un systeme de physique et de collisions pour interagir avec le monde
- Wonderland doit avoir un systeme deIA pour les PNJ et les IA, comme des quêtes, des combats, des interactions, etc
- Les characters doivent pouvoir etre personnalisables, avec des habilites, des pouvoirs, des armures, des armes, des spells, un inventaire (style RPG), avec des combats et des quetes, des niveaux, des experiences, etc
- Les characters doivent se deplacer en skeleton animation
- Les characters doivent voir leur reflet dans un mirroir, ou dans l'eau comme dans la réalité


### Applications to rewrite and integrate:

### Bugs:

- le personnage apparait dans presque tous les mondes a l'exterieur du monde et a cause de la collision je ne peux entrer dans les mondes en question

## Mandela Language

### Features

### Documentation
- Quelle est la vie de Mandela en dehors de Looking Glass ?
- Est-ce que Mandela pourrait etre utilise pour faire des probes ?
- Faire un roadmap de futures implementations de Mandela
### Development
- Mandela hardening, mettre a jour la version du module Mandela et tenir son changelog a jour
- add new language features and new scripts and publish a mandela release with new mandela version,
- repo cree github www.github.com/landrevillejf/Mandela


# Decouple Wonderland onto JOGL and fix the black-screen worlds

## Root cause (verified this session)
- The patched `java3d-core` is declared in root `allprojects.repositories` BEFORE `mavenCentral()`, and `lg3d-core` + `wonderland-renderer` `dependsOn buildPatchedJava3dCore`. So Wonderland's GPU-hook jar becomes the render foundation of the WHOLE desktop.
- That source-built jar renders every world black; the official Maven Central jar renders all 8 (proven: merge-base `34c119aa` probe PASSED, clinic 50076 bytes; my branch probe pure black `ff000000`). The patch itself is a correct no-op and the WorldViewer base setup is byte-identical at features=off, so the jar build is the culprit.
- Stock java3d 1.7.2 has NO per-frame GL seam: `Canvas3D` has `preRender/postRender/postSwap` but no `preSwap`; ZERO classes reference `GLEventListener`; `GraphicsContext3D` exposes only `readRaster`; offscreen lands in a CPU `ImageComponent2D`. Live GPU post-processing on java3d therefore REQUIRES the patch - exactly the coupling that broke the clinic. JOGL provides that seam natively.
- Prior `features=off` probe runs never took effect: the `test` task forwards only `lg.wonderland.probe`, not `lg.wonderland.features`, so the GPU chain was live in every run.

## Directive
Dependency must point `lg3d -> wonderland`. Wonderland is a standalone 3D game (2D or 3D desktop) with no dependency on lg3d-core.

## Phase 1 - Decouple and fix the black screen (small, safe, tested)
### 1. Remove the patched-jar shadowing
- `build.gradle` (root): drop the `patchedJava3d` maven repo from `allprojects { repositories {...} }` (keep `mavenCentral()`); retire the `buildPatchedJava3dCore` task + `patchedJ3d*` vars.
- `lg3d-core/build.gradle`: remove `dependsOn buildPatchedJava3dCore` (~216) + its comment (~207-215).
- `wonderland-renderer/build.gradle`: remove the `JavaCompile ... dependsOn buildPatchedJava3dCore` block (~60-66).
- Effect: every module resolves stock `org.jogamp.java3d:java3d-core:1.7.2`; the desktop renders again.
### 2. Compile/run wonderland-renderer on stock java3d (GPU route dormant)
- `view/PostProcessCanvas.java`: drop `@Override` from `preSwap()`. With stock, `preSwapHookPresent()` is false -> chain empty -> plain `Canvas3D` -> worlds render. `Gpu*Pass` stay compiled and dormant for Phase 2 reuse.
### 3. Move the lg3d-coupled bridge out of Wonderland
- Move to `lg3d-apps/src/classes/org/jdesktop/lg3d/apps/wonderland/` (repackage): `WonderlandDesktop`, `WonderlandWorldContainer`, `WorldInputRouter`, `WonderlandDesktopPlugin` + `WonderlandDesktopPluginTest`, `WonderlandWorldContainerTest`, `WorldInputRouterTest`; carry the `META-INF/services` provider entry with the plugin.
- Update `WonderlandWorld3D.java` import of `WonderlandDesktop`.
- `wonderland-desktop/build.gradle`: remove `implementation project(':lg3d-core')`; keep `java3d.core`/`vecmath` only if the remaining pure classes need them. wonderland-desktop keeps the pure routing half (SessionFeed, PickRouter, PickDecision, PickIntent, HitSource, WorldHudModel, WorldInventory, CellFrameBridge, FrameMounter) and becomes lg3d-free.
- Adjust the `wonderland-desktop` coverage floor (pure-only now); lg3d-apps panels are not floor-enforced.
### 4. Close the probe testing gap + verify
- `lg3d-apps/build.gradle` test task: forward every `lg.wonderland.*` system property to the test JVM.
- `./gradlew build` green; then `DISPLAY=:0 ./gradlew :lg3d-apps:test --tests '*WorldRenderProbeTest' -Dlg.wonderland.probe=true` -> all 8 worlds incl. outpatient-clinic render real colour (attach `render-*.png`).
- Commit module-scoped (`fix(wonderland-renderer)`, `refactor(wonderland-desktop)`, `fix(lg3d-apps)`); push to PR #411.

## Phase 2 - Rebuild Wonderland's renderer on JOGL (standalone 3D game)
Replaces the 7 java3d-coupled files; reuses the 48 pure/JOGL files (COLLADA/transform/texture/animation data + the whole `Gpu*Pass` chain).
### 1. Deps + infrastructure
- `wonderland-renderer/build.gradle`: swap `java3d.core`/`java3d.utils` -> `jogl-all` + `gluegen-rt` (+ `jogampNatives` 2.6.0 classifier); keep `vecmath` (math only, not lg3d).
- Delete `lg3d-core/src/patches/java3d-core-preswap/**`, `scripts/build-patched-java3d.sh`, `view/PostProcessCanvas.java`.
### 2. JOGL scene core (replaces J3dMeshFactory, ModelRenderer, WorldScene)
- `GlMesh`: VBO/VAO/EBO from `ColladaMeshData` + `TextureBindingPlan`/`MaterialAppearance`; GLSL from `ShaderAppearancePlan`/`PbrKernel`/`EnvironmentPlan`.
- `GlScene`: render list of meshes + `WorldTransformMath` matrices; sky/shadow/water from `SkyArt`/`ShadowArt`/`WaterArt`.
- Camera: view/projection from the existing FOV / front-clip / back-clip logic.
### 3. JOGL view + live post-process (replaces WorldViewer, NameTag, AvatarRenderer)
- `GLCanvas` + `GLEventListener`: scene -> FBO (render-to-texture), run the existing `GpuPostEffect` chain (SSAO->bloom->HDR->DOF->FXAA) with `drawable.getGLContext()`, blit to the default framebuffer. The listener is the per-frame seam java3d lacked.
- Preview capture via FBO `glReadPixels` (GPU, not CPU offscreen). NameTag billboard reuses `LabelArt`; avatars reuse the animation package + `BakedRig`.
### 4. Hosting in the 2D/3D desktop
- The moved bridge launches Wonderland's `GLCanvas` in its own window for both desktops; optional in-3D-scene embedding via `nativewindow/x11` compositing as a follow-up. Wonderland no longer mounts as an lg3d `Component3D`.
### 5. Tests, probe, docs
- Keep the pure layer's headless tests; probe-verify the JOGL renderer live (all 8 worlds, effects ON) + a WlProbe measuring live post-process fps; update the `wonderland-renderer` coverage floor for the new probe-only GL lines.
- Update `docs/wonderland-roadmap.md` (P2.6/P2.7/P3.2-P3.5 delivered on JOGL), `CHANGELOG.md` (+ `whatsNewRefresh`), root `AGENTS.md` (drop the patched-jar vendor-chain section).

## Test plan
- Phase 1: `./gradlew build`; `WorldRenderProbeTest` renders all 8 worlds (clinic included) with evidence PNGs.
- Phase 2: same probe with effects ON via the JOGL renderer + a live-fps WlProbe; reused pure-layer suites stay green; coverage floors hold or rise.

## Assumptions
- Wonderland keeps `org.jogamp.vecmath` (standalone math, not lg3d) - no lg3d coupling.
- Phase 1 ships first (fixes the clinic now); Phase 2 is the larger modern-framework renderer, delivered in its own reviewable steps.
- No release cut; work stays on `feat/wonderland-p32-gpu-bloom` -> PR #411 (rename/retarget if you prefer a fresh branch for the JOGL work).
