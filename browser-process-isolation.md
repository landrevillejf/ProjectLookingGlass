# Isoler le Web Browser du WM (desktop 2D) + durcissement

## Problème racine
Dans le desktop 2D, `Desktop2DAppRegistry` enregistre `WebBrowser` comme **PANEL** ([`Desktop2DAppRegistry.java`](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/Desktop2DAppRegistry.java) L459-460), donc `Desktop2D.openApp` (`case PANEL` → `openPanelApp` → `createPanel`) construit `BrowserPanel` **dans le JVM du desktop**. `BrowserPanel` fait `new JFXPanel()` (L109) → JavaFX/WebKit/GL démarrent sur l'EDT du WM. Un `SIGSEGV` natif WebKit/GL ou une saturation mémoire emporte le window manager. Les garde-fous existants (`WebKitThreadGuard`, watchdog de chargement `FxBrowser`, pages d'erreur) ne capturent que les erreurs Java, jamais un crash natif. Le desktop 3D est déjà isolé ([`BrowserPreviewPanel.spawnChildProcess`](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-apps/src/classes/org/jdesktop/lg3d/apps/webbrowser/BrowserPreviewPanel.java) L225-250 → `WebBrowserApp`).

## Decision
Le desktop 2D doit lancer le navigateur comme **vrai processus enfant OS**, en réutilisant `WebBrowserApp` (déjà un `JFrame` autonome + `main`), avec un **superviseur** (détection d'exit + notification + relance bornée) et des **flags de durcissement** côté enfant. `launchSwingFrame` étant un `main` réflexif in-JVM ([`Desktop2DAppRegistry.java`](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/Desktop2DAppRegistry.java) L737-761), il ne fournit PAS l'isolation : il faut un fork réel.

## Changements

### 1. lg3d-core : nouveau `Kind.CHILD_PROCESS` dans le registre
Dans [`Desktop2DAppRegistry.java`](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/Desktop2DAppRegistry.java) :
- Ajouter `CHILD_PROCESS` à l'enum `Kind` (L77-78).
- Ajouter `private static final Map<String,String> CHILD_PROCESS_APPS` mappant la classe du descripteur vers la classe enfant : `org.jdesktop.lg3d.apps.webbrowser.WebBrowser` -> `org.jdesktop.lg3d.apps.webbrowser.WebBrowserApp`.
- **Retirer** le `panels.put(...WebBrowser, BrowserPanel)` (L459-460).
- Dans `classify` (L533-555), tester `CHILD_PROCESS_APPS` avant `PANEL_APPS` (après `SWING_FRAME`) et renvoyer `Kind.CHILD_PROCESS`.
- Ajouter un accesseur `childMainClass(String command)` symétrique de `panelClass`.
- Mettre à jour le bloc de commentaires L445-458 (le 2D n'héberge plus le WebView in-JVM).
  Effet induit : `isLaunchable` (L534-543) renvoie déjà `true` pour tout kind sauf UNAVAILABLE/EXTERNAL -> le navigateur reste dans le start-menu/quick-launch ; `restoreSession` (L1577+) ne ré-ancre que les PANEL, donc le navigateur devient non-restaurable comme les SWING_FRAME/EXTERNAL (acceptable).

### 2. lg3d-core : fork supervisé dans `Desktop2D`
Dans [`Desktop2D.java`](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/Desktop2D.java) :
- Ajouter `case CHILD_PROCESS:` dans `openApp` (L1221 switch) -> `launchChildProcessApp(item)`.
- `launchChildProcessApp` construit la commande via un helper **pur**, la démarre avec `ProcessBuilder` (DISPLAY propagé comme dans `BrowserPreviewPanel` L236-242), garde une référence à l'unique `Process` live (dédup : si vivant, notifier "Web Browser déjà ouvert dans sa propre fenêtre" sans re-fork), drain des sorties sur thread daemon, puis `process.onExit()` -> si exit != 0 de façon inattendue : `raiseNotification("Web Browser stopped", "...relance...", WARNING)` et **relance auto bornée** (ex. 3 tries, compteur réinitialisé si l'enfant a vécu > N s). En cas d'`IOException` au spawn : `showMessage` (pas de fallback in-JVM — ce serait la régression).
- Traiter `case CHILD_PROCESS` dans le chemin fichier-associé (L1663) comme `SWING_FRAME` (lancer l'enfant, ne pas tenter de créer un panneau).

### 3. lg3d-core : builder de commande pur + durcissement enfant
Nouveau fichier `lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/ChildProcessLauncher.java` (testable headless) :
- `static List<String> buildCommand(String javaBin, String classpath, List<String> jvmArgs, String mainClass, String mainArgs)` -> `[javaBin, <jvmArgs...>, "-cp", classpath, mainClass, <mainArgs...>]`.
- `static List<String> browserJvmArgs()` -> flags de robustesse : `-Xmx` plafonné (défaut `512m`, propriété `lg3d.webbrowser.childXmx`), `-Dprism.order=sw` (rendu logiciel, évite le crash GL ; surchargeable via `lg3d.webbrowser.prismOrder`), `-Dprism.allowhidpi=false`.
- `static Map<String,String> browserEnv(Map<String,String> base, String display)` -> ajoute `WEBKIT_DISABLE_COMPOSITING_MODE=1` et `WEBKIT_DISABLE_DMABUF_RENDERER=1` (mitigations de crash WebKit connues sur Linux) + `DISPLAY`.
- Chemin java : `Paths.get(System.getProperty("java.home"),"bin","java")` ; classpath : `System.getProperty("java.class.path")` (les jars OpenJFX y sont déjà — cf. racine AGENTS.md deps).

### 4. lg3d-apps : durcissement enfant (défensif, mineur)
Dans [`WebBrowserApp.java`](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-apps/src/classes/org/jdesktop/lg3d/apps/webbrowser/WebBrowserApp.java) :
- Installer `Thread.setDefaultUncaughtExceptionHandler` + `Platform.setImplicitExit(false)` avant `createAndShow`, pour qu'une exception FX non attrapée tue l'enfant proprement (code != 0, vu par le superviseur) et non le desktop.
- `windowClosed` (L79-86) : déjà `Platform.exit()+System.exit(0)` -> le superviseur voit une sortie propre (exit 0) et ne relance pas.
  Aucune réécriture de `BrowserPanel`/`FxBrowser` nécessaire (le watchdog de chargement et les pages d'erreur restent).

## Tests (headless, conformes aux regles lg3d-core/tests)
- [`Desktop2DAppRegistryTest.java`](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/src/test/java/org/jdesktop/lg3d/displayserver/desktop2d/Desktop2DAppRegistryTest.java) : `classify("java ...WebBrowser") == CHILD_PROCESS`, `panelClass(...) == null`, `childMainClass(...) == "...WebBrowserApp"` ; et que les autres PANEL (ex. FileManager) restent PANEL.
- Nouveau `ChildProcessLauncherTest` (lg3d-core) : `buildCommand` assemble l'ordre exact (javaBin, args, -cp, main, mainArgs) ; `browserJvmArgs` contient `-Dprism.order=sw` + le `-Xmx` par défaut et réagit à la propriété ; `browserEnv` ajoute les deux `WEBKIT_*` + `DISPLAY` sans écraser les clés existantes. Pure, aucune spawn.
- Pas de test de spawn réel (GUI/X requis) ; le chemin fork de `Desktop2D` est couvert par la logique pure + vérification manuelle.

## Docs / meta (même PR)
- [`webbrowser.lgcfg`](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-apps/src/config/webbrowser.lgcfg) commentaire L13-18 : le 2D lance désormais l'enfant supervisé, plus de `BrowserPanel` in-JVM.
- [`webbrowser/AGENTS.md`](file:///home/fedora/Documents/ProjectLookingGlass/lg3d-apps/src/classes/org/jdesktop/lg3d/apps/webbrowser/AGENTS.md) : noter l'isolation processus en 2D + le superviseur.
- `CHANGELOG.md` `[Unreleased]` : bullet `fix(lg3d-apps)`/`fix(lg3d-core)` (le browser ne peut plus geler/planter le WM) + bullet robustesse rendu logiciel.
- Pas de bump de version (règle : bump = PR chore séparée).

## Verification
- `./gradlew :lg3d-core:test :lg3d-apps:test` vert.
- `./gradlew build` + `:lg3d-core:runtimeResources` (aucun asset, mais non-régression).
- Manuel : `./run-lg3d.sh` (dev/2D) -> ouvrir Web Browser, vérifier fenêtre enfant hors du WM ; `kill -9` l'enfant -> le desktop reste vivant + notification + relance.

## Git
Branche `fix/webbrowser-process-isolation` off `main`, commit Conventional scope `lg3d-core`+`lg3d-apps`, Signed-off-by, push, PR via `gh` (attente approbation avant merge, comme d'habitude).

## Hors scope / compromis
- La fenêtre enfant n'a pas de bouton taskbar lg3d ni de titre MDI (comme les apps SWING_FRAME/EXTERNAL actuelles) ; en LFS avec `lg3d.x11.composite2d` activé elle est composée comme fenêtre X11 étrangère. L'integration MDI-proxy (avec IPC) a été écartée au profit de la robustesse maximale.
- WebGL/médias restent actifs mais dans l'enfant ; un crash de contenu ne touche plus le WM (contenu + relance auto).
