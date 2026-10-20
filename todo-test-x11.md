Bonne question — laisse-moi vérifier les commandes exactes et la faisabilité sur macOS plutôt que répondre de mémoire. Je regarde le launcher, le module d'intégration X11 et les docs.

Le launcher est clair. Voyons le contrat de l'hôte X11 (la commande prérequis), la sélection des natives par OS, et le module d'intégration pour être précis sur macOS.

J'ai les preuves nécessaires. Une dernière vérification sur la sélection des natives/classifier par OS (pour savoir si lg3d tourne déjà sur mac), puis je réponds.

Voici les commandes, puis le verdict pour macOS.

## Les commandes X11 (compositor)

| But | Commande |
|---|---|
| Lancer lg3d comme WM + compositor sur `DISPLAY` | `./run-lg3d.sh -x`  (= `./gradlew :lg3d-core:run -Pcompositor`) |
| Sonder les extensions X requises | `./gradlew :lg3d-core:verifyX11Extensions`  (`--args=':N'` pour un autre display) |
| Cibler un Xorg déjà lancé (dev, défaut `:1`) | `./run-lg3d.sh --nested :1`  (→ `-Plgserverdisplay=:1`, implique `--compositor`) |
| Preuve live Stage 4 | `scripts/x11/live-proof.sh` |

Le prérequis absolu (voir [docs/lfs-x11-contract.md](file:///home/fedora/Documents/ProjectLookingGlass/docs/lfs-x11-contract.md)) : un **Xorg nu sur `:0`, sans aucun autre WM**, lancé par ex. `xinit ./run-lg3d.sh -x -- Xorg :0 vt1 -nolisten tcp`. Le script ne démarre **jamais** de serveur X lui-même.

## Sur macOS : non, pas le compositor

C'est exactement le même blocage que Wayland, pour les mêmes raisons :

1. **X11 sur mac = XQuartz, qui a déjà son propre WM** (il s'intègre au window server Quartz). lg3d doit réclamer `SubstructureRedirect` sur la racine → **échec `BadAccess`** immédiat (§2.2 et §4 du contrat). Un WM tiers ne peut pas être « délogé ».
2. **Pas de Xorg nu** à viser avec `--nested` : macOS n'a ni `Xorg`, ni VT de rechange exploitable comme sur Linux (§6).
3. **Classifiers natifs** : les natives Java 3D se résolvent bien sur mac (`natives-macosx-universal`, [lg3d-core/build.gradle](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build.gradle#L28-L35)), **mais OpenJFX est épinglé au classifier `linux`** ([lg3d-apps/build.gradle](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-apps/build.gradle#L162-L163)) — donc tout ce qui passe par JavaFX (le navigateur, etc.) ne marchera pas. Seule la plateforme **Linux x86-64 est validée en CI**.

⚠️ Piège : `verifyX11Extensions` pourrait **passer** sur mac contre XQuartz (il expose Composite/DAMAGE/XFIXES/XTEST/SHAPE/MIT-SHM), mais un probe vert ne prouve **pas** que le compositor fonctionne — la prise WM (§5.3) et le compositing bout-en-bout (§5.5) échouent quand même.

## Ce que tu **peux** tester sur mac

- **Les tests unitaires X11 headless** — c'est-à-dire précisément ce qu'on vient d'écrire (décodeurs Escher + `X11WindowAssociatorMatcherTest` / `X11WindowAssociatorOrchestrationTest`). Aucun serveur X requis :
  ```bash
  ./gradlew :lg3d-core:test --tests '*nativewindow.x11*'
  ```
  Ça tourne partout, mac inclus (JDK 21 via `/usr/libexec/java_home -v 21`, que `run-lg3d.sh` détecte déjà).
- Éventuellement le **bureau 2D Swing** (`./run-lg3d.sh -2`), si son chemin n'embarque pas JavaFX.

Pour valider le compositor en vrai, il faut l'hôte **LFS Xorg nu** (Phase F). Au passage, il y a bien un PR macOS ouvert (#240, « force real macOS full screen, quiet JOGL warning ») mais il ne concerne pas le compositing X11.
