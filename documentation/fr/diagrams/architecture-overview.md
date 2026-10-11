# Project Looking Glass - Vue d'ensemble de l'architecture

## Diagramme d'architecture de haut niveau

```mermaid
graph TB
    subgraph "Couche Interface Utilisateur"
        A[Interface Bureau 3D]
        B[Interface Bureau 2D]
        C[Applications]
    end

    subgraph "Couche SDK Core"
        D[lg3d-core<br/>Graphe de Scène & Fenêtrage]
        E[lg3d-escher<br/>Bibliothèque Protocole X11]
        F[lg3d-widgets<br/>API Widgets]
    end

    subgraph "Couche Applications"
        G[lg3d-apps<br/>Applications de Production]
        H[lg3d-incubator<br/>Applications Expérimentales]
    end

    subgraph "Modules de Support"
        I[lg3d-mandela<br/>Langage de Script]
        J[lpm-console<br/>Gestionnaire de Paquets]
        K[update-manager<br/>Mises à jour Logicielles]
        L[db-manager<br/>Client Base de Données]
        M[ftp-client<br/>Client FTP]
    end

    subgraph "Dépendances Externes"
        N[Java 3D<br/>Jogamp 1.7.2]
        O[JavaFX<br/>OpenJFX]
        P[Jackson<br/>JSON]
        Q[Apache Commons<br/>Diverses Bibliothèques]
    end

    A --> D
    B --> D
    C --> D
    C --> G
    C --> H

    D --> E
    D --> N
    D --> F

    G --> D
    G --> I
    G --> O
    G --> P
    G --> Q

    H --> D
    H --> I

    F --> D

    J --> I
    K --> I
    L --> I
    M --> I

    style D fill:#4CAF50,color:#fff
    style E fill:#2196F3,color:#fff
    style F fill:#FF9800,color:#fff
    style G fill:#9C27B0,color:#fff
    style H fill:#E91E63,color:#fff
    style I fill:#00BCD4,color:#fff
```

## Diagramme des dépendances de modules

```mermaid
graph LR
    subgraph "Fondation"
        ESCHER[lg3d-escher]
    end

    subgraph "Core"
        CORE[lg3d-core]
    end

    subgraph "Applications"
        APPS[lg3d-apps]
        INCUBATOR[lg3d-incubator]
        WIDGETS[lg3d-widgets]
    end

    subgraph "Outils de Support"
        MANDELA[lg3d-mandela]
        LPM[lpm-console]
        UPDATE[update-manager]
        DB[db-manager]
        FTP[ftp-client]
    end

    ESCHER --> CORE
    CORE --> APPS
    CORE --> INCUBATOR
    CORE --> WIDGETS
    CORE --> MANDELA
    CORE --> LPM
    CORE --> UPDATE
    CORE --> DB
    CORE --> FTP

    MANDELA --> APPS
    MANDELA --> INCUBATOR
    LPM --> APPS
    UPDATE --> APPS
    DB --> APPS
    FTP --> APPS
    WIDGETS --> APPS

    style ESCHER fill:#2196F3,color:#fff
    style CORE fill:#4CAF50,color:#fff
    style APPS fill:#9C27B0,color:#fff
    style INCUBATOR fill:#E91E63,color:#fff
    style WIDGETS fill:#FF9800,color:#fff
    style MANDELA fill:#00BCD4,color:#fff
```

## Architecture des composants

```mermaid
graph TB
    subgraph "Serveur d'Affichage"
        DS[DisplayServer]
        DM[DesktopMode]
        EC[EventProcessor]
    end

    subgraph "Graphe de Scène"
        SG[Graphe de Scène<br/>org.jdesktop.lg3d.sg]
        VU[VirtualUniverse]
        TG[TransformGroup]
        BG[BranchGroup]
    end

    subgraph "Toolkit de Widgets"
        WG[Toolkit de Widgets<br/>org.jdesktop.lg3d.wg]
        C3D[Component3D]
        F3D[Frame3D]
        SN[SwingNode]
    end

    subgraph "Utilitaires"
        UTIL[Utilitaires<br/>org.jdesktop.lg3d.utils]
        SHAPE[Utilitaires de Formes]
        ACTION[Utilitaires d'Actions]
        EVENT[Adaptateurs d'Événements]
    end

    DS --> SG
    DS --> WG
    DS --> DM
    DM --> EC

    SG --> VU
    SG --> TG
    SG --> BG

    WG --> C3D
    WG --> F3D
    WG --> SN

    UTIL --> SHAPE
    UTIL --> ACTION
    UTIL --> EVENT

    WG --> UTIL
    SG --> UTIL

    style DS fill:#f44336,color:#fff
    style SG fill:#4CAF50,color:#fff
    style WG fill:#2196F3,color:#fff
    style UTIL fill:#FF9800,color:#fff
```

## Diagramme de flux de données

```mermaid
graph TD
    USER[Entrée Utilisateur] --> INPUT[EventProcessor]
    INPUT --> EVENT[Distribution d'Événements]
    EVENT --> ACTION[Exécution d'Actions]
    ACTION --> SCENE[Mise à jour du Graphe de Scène]
    SCENE --> RENDER[Pipeline de Rendu]
    RENDER --> DISPLAY[Sortie d'Affichage]

    INPUT --> LOG[Journalisation]
    SCENE --> LOG

    USER -->|Souris/Clavier| INPUT
    USER -->|Geste| INPUT

    DISPLAY --> USER

    style INPUT fill:#2196F3,color:#fff
    style EVENT fill:#4CAF50,color:#fff
    style ACTION fill:#FF9800,color:#fff
    style SCENE fill:#9C27B0,color:#fff
    style RENDER fill:#E91E63,color:#fff
```

## Architecture d'exécution

```mermaid
graph TB
    subgraph "Processus JVM"
        subgraph "Thread Principal"
            MAIN[Point d'Entrée Principal]
            INIT[Initialisation]
        end

        subgraph "Thread d'Événements"
            EVENT[EventProcessor]
            QUEUE[File d'Événements]
        end

        subgraph "Thread de Rendu"
            RENDER[Boucle de Rendu]
            SCENE[Parcours du Graphe de Scène]
        end

        subgraph "Threads d'Application"
            APP1[Application 1]
            APP2[Application 2]
            APPN[Application N]
        end
    end

    MAIN --> INIT
    INIT --> EVENT
    INIT --> RENDER

    EVENT --> QUEUE
    QUEUE --> EVENT

    RENDER --> SCENE
    SCENE --> RENDER

    APP1 --> EVENT
    APP2 --> EVENT
    APPN --> EVENT

    EVENT --> APP1
    EVENT --> APP2
    EVENT --> APPN

    style MAIN fill:#f44336,color:#fff
    style EVENT fill:#4CAF50,color:#fff
    style RENDER fill:#2196F3,color:#fff
```

## Structure des paquets

```mermaid
graph TB
    subgraph "org.jdesktop.lg3d"
        SG[sg<br/>Graphe de Scène]
        WG[wg<br/>Widgets]
        UTILS[utils<br/>Utilitaires]
        DS[displayserver<br/>Serveur d'Affichage]
        SM[scenemanager<br/>Gestionnaire de Scène]
        APPS[apps<br/>Applications]
        APPKIT[appkit<br/>Kit d'Application]
    end

    subgraph "Paquets Graphe de Scène"
        SG --> NODE[Hiérarchie de Nœuds]
        SG --> GEOM[Géométrie]
        SG --> APPEAR[Apparence]
        SG --> LIGHT[Éclairage]
    end

    subgraph "Paquets Widgets"
        WG --> COMP[Composants]
        WG --> FRAME[Fenêtres]
        WG --> LAYOUT[Mise en Page]
    end

    subgraph "Paquets Utilitaires"
        UTILS --> SHAPE[Formes]
        UTILS --> ACTION[Actions]
        UTILS --> EVENT[Événements]
    end

    style SG fill:#4CAF50,color:#fff
    style WG fill:#2196F3,color:#fff
    style UTILS fill:#FF9800,color:#fff
    style DS fill:#9C27B0,color:#fff
    style SM fill:#E91E63,color:#fff
    style APPS fill:#00BCD4,color:#fff
    style APPKIT fill:#607D8B,color:#fff
```
