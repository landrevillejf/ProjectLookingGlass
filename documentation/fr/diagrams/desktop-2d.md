# Project Looking Glass - Diagrammes du Bureau 2D

> Ces diagrammes décrivent le **bureau Swing conventionnel** que la coquille
> lance quand Java 3D est indisponible (la surface `lg.desktop2d`, package
> `org.jdesktop.lg3d.displayserver.desktop2d`). Rien ici ne touche le graphe de
> scène : chaque classe est du Swing/AWT pur, si bien que le bureau démarre sur
> une JVM où les jars Java 3D sont entièrement absents. Voir l'
> [Analyse fonctionnelle du Bureau 2D](../functional-analysis/desktop-2d.md)
> companion.

## Architecture des composants

Tout le bureau 2D tient dans un seul `JFrame` sans bordure, maximisé : un
`JDesktopPane` de fond d'écran (le canevas MDI) avec la `Desktop2DTaskbar`
amarrée en bas. Les superpositions (toasts, voile de luminosité, teinte
nocturne, sélecteur de fenêtres, aperçu d'ancrage) siègent sur les couches du
pane ; les modèles sont des objets purs, testables sans écran, propriété de
`Desktop2D`.

```mermaid
graph TB
    subgraph Coquille["Desktop2D (la coquille)"]
        ENTREE["Desktop2D.start()<br/>→ new Desktop2D().show()"]
        FRAME["JFrame<br/>sans bordure, maximisé"]
        PANE["WallpaperDesktopPane<br/>canevas MDI + fond"]
        TB["Desktop2DTaskbar<br/>barre du bas"]
        MENU["Desktop2DStartMenu<br/>JPopupMenu depuis .lgcfg"]
        CFG["Desktop2DMenuConfig<br/>MenuModel / ItemSpec"]
    end

    subgraph Fenetres["Couche fenêtres"]
        WIN["Desktop2DWindow<br/>JInternalFrame (une app)"]
        MGR["SnappingDesktopManager<br/>DesktopManager"]
        SNAP["WindowSnap + SnapPreview<br/>zones tuilage / maximiser"]
        CYC["WindowCycler + WindowCyclerOverlay<br/>Alt-Tab"]
    end

    subgraph Modeles["Modèles purs (headless)"]
        WS["WorkspaceModel<br/>bureaux virtuels"]
        QL["QuickLaunchModel<br/>raccourcis épinglés"]
        NOTIF["NotificationModel + DoNotDisturb"]
        SESS["SessionManager + SessionSnapshot"]
        REG["Desktop2DAppRegistry<br/>classify(commande) → Kind"]
    end

    subgraph Habillage["Chrome de la barre"]
        PAG["WorkspacePager"]
        SYS["NotificationTray + ToastLayer"]
        HRL["horloge + CalendarPopup"]
        IND["TaskbarIndicators<br/>réseau/volume/batterie/…"]
    end

    subgraph Apparence["Look & feel"]
        TRANSP["DesktopTransparency<br/>+ TranslucentSurface"]
        ICONES["IconPackManager / AppIcons / CategoryIcons"]
        LUM["BrightnessDimmer + NightTintOverlay"]
        DCFG["DesktopConfig<br/>(préférences)"]
    end

    ENTREE --> FRAME --> PANE
    FRAME --> TB
    TB --> MENU --> CFG
    PANE --> WIN
    PANE --> MGR --> SNAP
    MENU --> REG
    CFG --> REG
    REG -->|PANEL| WIN
    TB --> PAG --> WS
    TB --> SYS --> NOTIF
    TB --> HRL
    TB --> IND
    WIN -. implémente .-> TRANSP
    PANE --> TRANSP
    DCFG --> TRANSP
    DCFG --> ICONES
    DCFG --> LUM
    SESS --> WIN
    CYC --> WS

    style ENTREE fill:#4CAF50,color:#fff
    style PANE fill:#2196F3,color:#fff
    style TB fill:#2196F3,color:#fff
    style REG fill:#FF9800,color:#fff
    style WIN fill:#9C27B0,color:#fff
    style WS fill:#00BCD4,color:#fff
```

## Séquence de lancement d'une application (`Desktop2D.openApp`)

Chaque lanceur — entrée du menu Démarrer, icône de lancement rapide, item du menu
dossier, boîte Exécuter, résultats de recherche — passe par une seule méthode,
`openApp(ItemSpec)`. Le registre classe la commande du descripteur et la
coquille branche selon le `Kind`.

```mermaid
sequenceDiagram
    autonumber
    actor U as Utilisateur
    participant SM as Menu / QL / RunDialog
    participant D as Desktop2D
    participant R as Desktop2DAppRegistry
    participant P as JDesktopPane
    participant W as Desktop2DWindow
    participant N as ToastLayer

    U->>SM: clique une entrée (ItemSpec)
    SM->>D: openApp(item)
    D->>R: classify(item.command)
    R-->>D: Kind

    alt PANEL (un panneau Swing hébergé)
        D->>R: instancie le panneau par réflexion
        R-->>D: JComponent panneau
        D->>W: new Desktop2DWindow(titre, icône, panneau, …)
        W->>P: add(fenêtre)
        D->>W: showIn(desktop) — pack, clamp, cascade, toFront
        W-->>U: fenêtre interne dans le bureau
    else SWING_FRAME (son propre JFrame)
        D->>R: launchSwingFrame(command)
        R-->>D: tourne dans la JVM, sa propre fenêtre
        D->>N: toast « lancé hors du bureau »
    else EXTERNAL (un exécutable natif)
        D->>R: launchExternal(command)
        R-->>D: enfant ProcessBuilder (DISPLAY posés)
        D->>N: toast « démarré comme fenêtre séparée »
    else UNAVAILABLE (nécessite Java 3D)
        D->>N: toast « Nécessite le bureau 3D »
    end
```

## Modèle de classes

```mermaid
classDiagram
    class Desktop2D {
        -JFrame frame
        -WallpaperDesktopPane desktop
        -Desktop2DTaskbar taskbar
        -WorkspaceModel workspaces
        +start()$
        +openApp(ItemSpec)
        +applyDesktopConfig()
    }
    class Desktop2DWindow {
        -String appName
        -String command
        +showIn(JDesktopPane)
        +transparencySurface() WINDOW
    }
    class Desktop2DAppRegistry {
        <<utility>>
        +classify(String) Kind$
        +launchExternal(String) boolean$
        +launchSwingFrame(String)$
    }
    class Kind {
        <<enumeration>>
        PANEL
        SWING_FRAME
        EXTERNAL
        UNAVAILABLE
    }
    class WorkspaceModel {
        -int count
        -int current
        +switchTo(int) int
        +next() int
        +assign(String,int)
        +isOnCurrent(String) boolean
    }
    class SnappingDesktopManager {
        +iconifyFrame(JInternalFrame)
        +dragFrame(JComponent,x,y)
        +endDraggingFrame(JComponent)
    }
    class TranslucentSurface {
        <<interface>>
        +transparencySurface() TransparencySurface
    }
    Desktop2D --> Desktop2DTaskbar
    Desktop2D --> WorkspaceModel
    Desktop2D ..> Desktop2DAppRegistry : classify()
    Desktop2DAppRegistry --> Kind
    Desktop2D ..> Desktop2DWindow : crée (PANEL)
    Desktop2DWindow ..|> TranslucentSurface
    Desktop2D ..> SnappingDesktopManager : setDesktopManager
```

## Disposition de la barre des tâches (gauche → droite)

```mermaid
graph LR
    DEB[Bouton<br/>Démarrer] --> QL[Raccourcis<br/>épinglés]
    QL --> SEP((séparateur))
    SEP --> FEN[Un bouton par<br/>fenêtre ouverte]
    subgraph DROITE[Groupe de droite]
        DOC[Menu Dossier<br/>Documents]
        TEL[Menu Dossier<br/>Téléchargements]
        PAG[Bascule de<br/>bureaux]
        SYS[Vitrine de<br/>notifications]
        HRL[Horloge +<br/>calendrier]
        QUIT[Quitter]
    end
    FEN --> DOC
```

## Zones d'ancrage des fenêtres (`WindowSnap`)

Faire glisser la barre de titre d'une fenêtre près d'un bord la met en tuile ;
le modèle pur `WindowSnap` transforme la position du pointeur en zone et
`SnapPreview` la met en évidence sur la couche palette du pane avant que le
relâchement redimensionne la fenêtre.

```mermaid
graph TB
    subgraph Bornes["Bornes du bureau"]
        direction TB
        HAUT["bord haut → maximiser"]
        GAUCHE["bord gauche → moitié gauche"]
        DROITE["bord droit → moitié droite"]
        MILIEU["loin des bords →<br/>déplacement MDI libre"]
    end
    GLisser["glisser la barre de titre"] --> PROX{"pointeur dans le<br/>seuil d'un bord ?"}
    PROX -->|haut| HAUT
    PROX -->|gauche| GAUCHE
    PROX -->|droite| DROITE
    PROX -->|non| MILIEU
    HAUT --> REDIM["setBounds(zone) au relâchement"]
    GAUCHE --> REDIM
    DROITE --> REDIM
```

## États des bureaux virtuels (`WorkspaceModel`)

`WorkspaceModel` est indépendant de la coquille (le `WorkspaceRegistry` 3D
réutilise les mêmes sémantiques) : un nombre borné (1..9, défaut 4), un index
courant enveloppé, et une carte id-fenêtre → bureau.

```mermaid
stateDiagram-v2
    [*] --> B0
    B0: Bureau 0 (affiché)
    B1: Bureau 1
    B2: Bureau 2
    B3: Bureau 3
    B0 --> B1: next()
    B1 --> B2: next()
    B2 --> B3: next()
    B3 --> B0: next() (boucle)
    B0 --> B3: previous() (boucle)
    B0 --> B2: switchTo(2) / clic pager / raccourci déplacer-vers
```

## Pipeline de transparence des surfaces

`Desktop2D` estompe quatre surfaces réglables (fenêtres, barre des tâches,
widgets, notifications). Un composant estompable implémente `TranslucentSurface` ;
le pane achemine les enfants estompés via `DesktopTransparency.paintFaded`. À
100 %, le rendu est identique octet pour octet au chemin de peinture hérité.

```mermaid
graph LR
    CC["Centre de contrôle →<br/>Personnalisation → Transparence"] --> CFG["DesktopConfig<br/>set*Opacity (25..100)"]
    CFG --> APPL["Desktop2D.applyDesktopConfig()"]
    APPL --> CACHE["DesktopTransparency.refresh()<br/>(cache mémoire)"]
    CACHE --> PEINT["WallpaperDesktopPane.paintChildren"]
    PEINT --> SOLIDE{"isSolid ?"}
    SOLIDE -->|oui 100%| HERIT["peinture héritée (inchangée)"]
    SOLIDE -->|non| ESTOM["paintFaded →<br/>image tampon → blit @ alpha"]
```

---

**Voir aussi :** [Vue d'ensemble de l'architecture](architecture-overview.md) ·
[Diagrammes de séquence](sequence-diagrams.md) ·
[Diagrammes de classes](class-diagrams.md) ·
[Analyse fonctionnelle du Bureau 2D](../functional-analysis/desktop-2d.md)
