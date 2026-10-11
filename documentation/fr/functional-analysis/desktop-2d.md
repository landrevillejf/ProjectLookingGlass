# Project Looking Glass - Analyse fonctionnelle du Bureau 2D

## Informations sur le document

| Champ | Valeur |
| --- | --- |
| **Sujet** | Le bureau Swing conventionnel (la coquille `lg.desktop2d`) |
| **Package** | `org.jdesktop.lg3d.displayserver.desktop2d` |
| **Companion** | [Diagrammes du Bureau 2D](../diagrams/desktop-2d.md) |
| **Statut** | Production - le bureau de repli utilisé dès que Java 3D est indisponible |

## Introduction

### Objectif

Le Bureau 2D est la coquille que le projet lance quand le graphe de scène Java 3D
ne peut être créé - une machine sans GPU/3D, une JVM dépourvue des jars Java 3D,
ou un utilisateur choisissant explicitement le bureau conventionnel. Il fournit
les réflexes du bureau quotidien (menu Démarrer, barre des tâches, fenêtres
redimensionnables, bureaux virtuels, notifications, session) entièrement en
Swing/AWT, afin que le produit reste utilisable et démontrable sans aucune
dépendance GPU/3D.

### Portée

Cette analyse couvre la coquille et ses collaborateurs du package `desktop2d` :
démarrage, classification du registre d'applications, menu Démarrer, barre des
tâches et son habillage, couche de fenêtres en `JInternalFrame`, bureaux
virtuels, notifications, transparence, thématisation, restauration de session et
boîte Exécuter. Elle ne couvre **pas** le bureau 3D (`Frame3D`/graphe de scène)
sauf là où les deux partagent délibérément un modèle pur (bureaux virtuels) ou
lisent la même source de descripteurs.

### Public cible

Architectes et développeurs maintenant le serveur d'affichage, QA vérifiant la
coquille 2D sans écran, et analystes ayant besoin du contrat comportemental du
bureau de repli.

## Vue d'ensemble du système

### Description du système

`Desktop2D` est un seul `JFrame`, sans bordure et maximisé, qui occupe tout
l'affichage. À l'intérieur se trouve un `WallpaperDesktopPane` (un `JDesktopPane`
qui peint le fond d'écran et héberge les fenêtres applicatives en `JInternalFrame`)
avec la `Desktop2DTaskbar` amarrée en bas. La classe est lancée par le serveur
d'affichage quand le mode 3D n'est pas sélectionné, et seule via le point
d'entrée statique `Desktop2D.start()`, qui se place sur l'EDT et appelle
`new Desktop2D().show()`. Une instance unique en cours est publiée dans un champ
statique `instance` pour que les panneaux de réglages du Centre de contrôle
atteignent le bureau vivant.

La coquille pose la propriété système `lg.desktop2d` (`MODE_PROPERTY`) tant
qu'elle tourne ; les panneaux de fonctionnalités réservées à la 3D lisent ce
drapeau et se retirent au lieu de lever une exception. **Rien dans le package ne
touche Java 3D**, ce qui est précisément ce qui permet au bureau entier de
démarrer sur une JVM dépourvue des jars Java 3D.

### La contrainte qui façonne tout : le MDI est du Swing pur

Le bureau 2D est un canevas MDI Swing. Il ne **reparente** ni ne **composite**
les fenêtres X11 étrangères en lui. Ce seul fait architectural explique que les
applications natives (Firefox, xterm, `javaws`) apparaissent comme des fenêtres
de premier plan séparées, gérées par le gestionnaire de fenêtres de l'hôte, *à
côté* du bureau - un comportement voulu, non un défaut. Composer une telle
fenêtre exigerait d'être le compositeur/gestionnaire de fenêtres X11 (la voie 3D
`run-lg3d.sh -x`, encore en développement), ou d'envelopper la capacité dans un
panneau Swing hébergé.

## Exigences fonctionnelles

### EF-B2D-1 : Démarrage du bureau

| Élément | Détail |
| --- | --- |
| **Exigence** | Démarrer un bureau conventionnel plein écran sans Java 3D. |
| **Entrée** | `Desktop2D.start()` → `SwingUtilities.invokeLater` → `new Desktop2D().show()`. |
| **Coquille** | `JFrame` sans bordure, maximisé, titré « Project Looking Glass (2D desktop) ». |
| **Fond** | Un fond d'écran résolu depuis le préfixe de classpath `resources/` (replis GrandCanyon → DreamLakeReflections). |
| **Drapeau de mode** | Publie `lg.desktop2d` ; instance unique gardée dans un champ statique pour les panneaux de réglages. |

### EF-B2D-2 : Classification et lancement des applications

Chaque commande du menu Démarrer est classée par
`Desktop2DAppRegistry.classify(commande)` dans l'un des quatre `Kind`, et
`Desktop2D.openApp(ItemSpec)` agit en conséquence :

| Kind | Forme de commande | Comportement |
| --- | --- | --- |
| **PANEL** | `java <classe>` dont l'UI est un panneau Swing simple | Instancie *le panneau* par réflexion (jamais le `main` de l'enveloppe 3D) et l'héberge dans une fenêtre interne `Desktop2DWindow`. |
| **SWING_FRAME** | `java`/`swingapp <classe>` affichant son propre `JFrame` | Lancé dans le processus ; il s'ouvre à côté du bureau (une fenêtre MDI ne peut adopter un `JFrame`). Signalé « hors du bureau ». |
| **EXTERNAL** | toute commande ne commençant pas par `java `/`swingapp ` (p. ex. `firefox`) | Démarrée comme processus enfant de l'OS via `ProcessRunner`/`ProcessBuilder` avec `DISPLAY` posé ; la fenêtre est gérée par le WM hôte. |
| **UNAVAILABLE** | applications Java 3D pures sans panneau enregistré | Refusée (nécessite le bureau 3D). |

L'ensemble PANEL (`PANEL_APPS`) associe la classe principale d'un descripteur 3D
à un panneau Swing sans AWT de son package : File Manager, Task Manager, Control
Center, Calculator, Archive, Media Writer, Help Center, About, Widget Gallery,
LPM Console, Software Update, Database Manager, FTP Client, Mail, Agenda, Chart,
Contacts, et d'autres. La recherche est **réflexive** car `lg3d-core` ne peut
dépendre de `lg3d-apps`, et sur une machine sans 3D la classe d'enveloppe
`Frame3D` de l'application ne doit jamais être chargée - seulement son panneau.

### EF-B2D-3 : Menu Démarrer

`Desktop2DStartMenu` construit un `JPopupMenu` à partir des *mêmes* descripteurs
`.lgcfg` que lit le menu Démarrer 3D (via `Desktop2DMenuConfig.MenuModel`/
`ItemSpec`). Le graphe de groupes est parcourable dans les deux sens
(« Main » ↔ « Demos »), si bien que les sous-menus sont bâtis avec une **garde
d'ancêtre** et une **profondeur maximale** (`MAX_DEPTH = 4`) ; un lien de retour
vers un ancêtre est écarté plutôt que de récursionner. Les applications que le
bureau 2D ne peut lancer sont traitées selon le `Kind` : un exécutable externe
absent et une application 3D pure (`UNAVAILABLE`) sont **entièrement omis** pour
que le menu ne liste que ce que ce bureau peut réellement lancer. Les sous-menus
de catégorie portent un glyphe reconnaissable (`CategoryIcons`) ; les entrées
utilisent le pack d'icônes actif (`AppIcons`).

### EF-B2D-4 : Barre des tâches et son habillage

`Desktop2DTaskbar` est un `JPanel` du bas qui remplace la barre 3D vitreuse. De
gauche à droite :

- le bouton **Démarrer** (ouvre le menu Démarrer),
- la bande d'**ancrage rapide** épinglé (l'équivalent 2D du conteneur `shortcuts`
  3D ; adossée à `QuickLaunchModel`, amorcée à `DEFAULT_QUICK_LAUNCHERS = 6` au
  premier lancement, épinglage par glisser-déposer via un `DropTarget`),
- un **bouton par fenêtre** `Desktop2DWindow` ouverte (clic pour réduire/rétablir,
  tenu dans un `LinkedHashMap` pour que l'ordre suive l'ordre d'ouverture),
- à droite : les menus de dossiers **Documents** et **Téléchargements**
  (`Desktop2DFolderMenu`), le **basculeur de bureaux** (`WorkspacePager`), la
  **vitrine de notifications** (`NotificationTray`), l'**horloge** avec un
  **calendrier** surgissant (`CalendarPopup`), les `TaskbarIndicators` (réseau/
  volume/batterie/luminosité/bluetooth/imprimante/locale/fuseau) et **Quitter**.

La barre est configurable : hauteur via un `barScale` (base 34px, plancher 18px)
et un **masquage automatique** optionnel qui réduit la barre à une lanière de
5px, rétablie par une sonde de proximité du pointeur (`AUTOHIDE_POLL_MS = 250`).

### EF-B2D-5 : Gestion des fenêtres

Chaque application hébergée est une `Desktop2DWindow` (une `JInternalFrame`),
dimensionnée à la taille préférée du panneau, **bornée** au pane et **cascadeée**
(`CASCADE_STEP = 28`) pour que les fenêtres ne s'empilent pas exactement. Elle
installe une barre de menus unique *Help → About* alimentant la réutilisable
`AboutDialog`. Un `SnappingDesktopManager` (un `DefaultDesktopManager`) régit le
pane :

- **réduire garde une seule représentation** - le bouton de la barre des tâches,
  non une icône parasite sur le bureau (`iconifyFrame` masque l'icône du bureau),
- **tuilage par ancrage en bord** - pendant le glisser de la barre de titre, la
  proximité du pointeur à un bord est résolue en `WindowSnap.Zone` (moitié
  gauche/droite ou maximiser), prévisualisée par un `SnapPreview` sur la couche
  palette du pane, puis appliquée (`setBounds`) au relâchement ; loin de tout
  bord le glisser se comporte comme du MDI standard.

La *décision* d'ancrage vit dans le `WindowSnap` pur et testable sans écran ; le
gestionnaire n'est que la plomberie Swing du glisser. Le cycle `Alt-Tab` est fait
par un `WindowCycler`/`WindowCyclerOverlay`.

### EF-B2D-6 : Bureaux virtuels

`WorkspaceModel` est un modèle indépendant de la coquille : un nombre borné
(`MIN_COUNT` 1 .. `MAX_COUNT` 9, défaut `DEFAULT_COUNT` 4), un index courant
enveloppé, et une carte `id-fenêtre → bureau` (les ids sont les `appName` des
fenêtres). `switchTo`, `next` et `previous` enveloppent via `Math.floorMod`, si
bien qu'on ne peut jamais sélectionner un bureau inexistant ; réduire le nombre
ramène les fenêtres orphelines dans la plage. `Desktop2D` pilote depuis lui la
visibilité des fenêtres MDI et les boutons de la barre, et le même modèle
alimente le `WorkspaceRegistry` du bureau 3D - les deux surfaces offrent des
sémantiques de bureaux identiques.

### EF-B2D-7 : Notifications

Un `NotificationModel` conserve les notifications publiées ; un `ToastLayer` rend
des bannières transitoires, une `NotificationTray` sur la barre les liste, et un
`DoNotDisturb` les supprime. Les issues de lancement (p. ex. « démarré hors du
bureau », « Nécessite le bureau 3D ») passent par ce chemin.

### EF-B2D-8 : Transparence des surfaces

La coquille estompe quatre surfaces réglables - **fenêtres, barre des tâches,
widgets, notifications** - via `DesktopTransparency`, configuré en direct dans
Centre de contrôle → Personnalisation → Transparence (25..100, défaut 100 =
opaque, persisté par `DesktopConfig`). Un composant estompable implémente
`TranslucentSurface` et déclare sa `TransparencySurface` ;
`WallpaperDesktopPane.paintChildren` achemine les enfants estompés par
`paintFaded` (rendu dans une image tampon transparente, blit à l'alpha de la
surface). L'opacité est lue depuis un **cache en mémoire rafraîchi sur l'EDT**
(`refresh()` au démarrage et depuis `Desktop2D.applyDesktopConfig()`) - le code
de peinture ne lit jamais `DesktopConfig` dans une passe de peinture - et chaque
crochet sort court sur `isSolid`/`allSolid` pour que **100 % soit identique octet
pour octet au chemin hérité**.

### EF-B2D-9 : Thématisation et apparence

`Desktop2D.applyDesktopConfig()` applique le `DesktopConfig` à Swing : une portée
de clés de polices `UIManager` (`FONT_KEYS`), l'échelle d'icônes, l'échelle de
barre et les opacités. Les packs d'icônes sont gérés par
`IconPackManager`/`AppIcons`/`CategoryIcons` (les fenêtres ouvertes re-résolvent
leur icône en direct via `Desktop2DWindow.refreshFrameIcon()`), les thèmes Metal
par `MetalThemeManager`, et un `BrightnessDimmer` logiciel doublé d'un
`NightTintOverlay` voilent tout le bureau depuis le panneau de verre.

### EF-B2D-10 : Session, boîte Exécuter, recherche, agenda

Un `SessionManager` capture les fenêtres ouvertes (chaque `Desktop2DWindow` garde
sa `commande` de descripteur et son `iconResource` pour pouvoir relancer) et les
restaure, adossé à `SessionSnapshot`/`PrefsSessionStore`. Une `RunDialog` +
`RunResolver` + `RunHistory` offrent une boîte de commande façon Win-R (renvoyant
une commande brute par `openApp`). `AppSearch`/`StartMenuSearch` rendent le menu
consultable. Un `CalendarPopup` ouvre l'Agenda à une date double-cliquée, et
`ScheduleService`/`TaskScheduler` pilotent les rappels.

## Cas d'utilisation

### CU-B2D-1 : Lancer une application à panneau

1. L'utilisateur clique une entrée du menu Démarrer (ou une icône de lancement rapide).
2. Le `Launcher` appelle `Desktop2D.openApp(item)`.
3. `Desktop2DAppRegistry.classify` renvoie `PANEL`.
4. Le registre instancie le panneau Swing de l'application par réflexion.
5. `Desktop2D` l'enveloppe dans une `Desktop2DWindow`, l'ajoute au pane et appelle
   `showIn` (pack, borner, cascade, toFront, focus).
6. Un bouton correspondant apparaît sur la barre ; la fenêtre est sensible aux
   bureaux et estompable comme toute autre fenêtre.

### CU-B2D-2 : Lancer un exécutable natif

1. L'utilisateur clique p. ex. une entrée navigateur ; `classify` renvoie `EXTERNAL`.
2. `Desktop2DAppRegistry.launchExternal` démarre le processus avec `DISPLAY` posé.
3. L'application s'ouvre comme **sa propre fenêtre de premier plan** à côté du
   bureau ; la coquille publie un toast d'information. C'est voulu - le canevas
   MDI n'incorpore pas les fenêtres étrangères.

### CU-B2D-3 : Mettre une fenêtre en tuile par glisser

1. L'utilisateur glisse la barre de titre d'une fenêtre vers le bord gauche.
2. `SnappingDesktopManager.dragFrame` interroge `WindowSnap.zoneFor(pointeur,...)`
   et allume un `SnapPreview` sur la moitié gauche.
3. Au relâchement, `endDraggingFrame`/`applyPendingSnap` redimensionne la fenêtre
   sur cette région. Le lâcher en haut maximise ; loin d'un bord, déplacement libre.

### CU-B2D-4 : Déplacer une fenêtre vers un autre bureau

1. L'utilisateur invoque le basculeur de la barre ou le raccourci déplacer-vers-bureau.
2. `WorkspaceModel.assign(idFenetre, index)` (enveloppé) relogement la fenêtre.
3. `Desktop2D` ne réaffiche que les fenêtres dont `isOnCurrent` est vrai ; le
   basculeur reflète les compteurs.

## Non-objectifs et contraintes

- **Pas de graphe de scène.** La coquille ne bâtit jamais de `Frame3D` ; elle
  doit démarrer avec les jars Java 3D absents.
- **Pas d'incorporation de fenêtres étrangères par défaut.** Les applications
  natives vivent hors du canevas MDI ; les composer est une capacité distincte du
  compositeur 3D (`Desktop2DCompositorHost`/`CompositedWindowBridge`), pas le
  chemin 2D de base.
- **Les modèles purs restent sans écran.** `WorkspaceModel`, `WindowSnap` et le
  registre ne détiennent aucun Swing, d'où leur test unitaire sans affichage.
- **Pas de `System.exit` des swingapps intra-JVM** à la fermeture - cela
  ferait tomber tout le bureau.
- **100 % de transparence ne doit rien coûter.** Le chemin opaque est identique
  octet pour octet à la peinture héritée.

---

**Voir aussi :** [Diagrammes du Bureau 2D](../diagrams/desktop-2d.md) ·
[Analyse fonctionnelle](functional-analysis.md) ·
[Vue d'ensemble de l'architecture](../diagrams/architecture-overview.md)
