# Project Looking Glass - Architecture Overview

## High-Level Architecture Diagram

```mermaid
graph TB
    subgraph "User Interface Layer"
        A[3D Desktop UI]
        B[2D Desktop UI]
        C[Applications]
    end

    subgraph "Core SDK Layer"
        D[lg3d-core<br/>Scene Graph & Windowing]
        E[lg3d-escher<br/>X11 Protocol Library]
        F[lg3d-widgets<br/>Widget API]
    end

    subgraph "Application Layer"
        G[lg3d-apps<br/>Production Apps]
        H[lg3d-incubator<br/>Experimental Apps]
    end

    subgraph "Supporting Modules"
        I[lg3d-mandela<br/>Scripting Language]
        J[lpm-console<br/>Package Manager]
        K[update-manager<br/>Software Updates]
        L[db-manager<br/>Database Client]
        M[ftp-client<br/>FTP Client]
    end

    subgraph "External Dependencies"
        N[Java 3D<br/>Jogamp 1.7.2]
        O[JavaFX<br/>OpenJFX]
        P[Jackson<br/>JSON]
        Q[Apache Commons<br/>Various Libraries]
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

## Module Dependency Diagram

```mermaid
graph LR
    subgraph "Foundation"
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

    subgraph "Supporting Tools"
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

## Component Architecture

```mermaid
graph TB
    subgraph "Display Server"
        DS[DisplayServer]
        DM[DesktopMode]
        EC[EventProcessor]
    end

    subgraph "Scene Graph"
        SG[Scene Graph<br/>org.jdesktop.lg3d.sg]
        VU[VirtualUniverse]
        TG[TransformGroup]
        BG[BranchGroup]
    end

    subgraph "Widget Toolkit"
        WG[Widget Toolkit<br/>org.jdesktop.lg3d.wg]
        C3D[Component3D]
        F3D[Frame3D]
        SN[SwingNode]
    end

    subgraph "Utilities"
        UTIL[Utilities<br/>org.jdesktop.lg3d.utils]
        SHAPE[Shape Utilities]
        ACTION[Action Utilities]
        EVENT[Event Adapters]
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

## Data Flow Diagram

```mermaid
graph TD
    USER[User Input] --> INPUT[EventProcessor]
    INPUT --> EVENT[Event Dispatch]
    EVENT --> ACTION[Action Execution]
    ACTION --> SCENE[Scene Graph Update]
    SCENE --> RENDER[Rendering Pipeline]
    RENDER --> DISPLAY[Display Output]

    INPUT --> LOG[Logging]
    SCENE --> LOG

    USER -->|Mouse/Keyboard| INPUT
    USER -->|Gesture| INPUT

    DISPLAY --> USER

    style INPUT fill:#2196F3,color:#fff
    style EVENT fill:#4CAF50,color:#fff
    style ACTION fill:#FF9800,color:#fff
    style SCENE fill:#9C27B0,color:#fff
    style RENDER fill:#E91E63,color:#fff
```

## Runtime Architecture

```mermaid
graph TB
    subgraph "JVM Process"
        subgraph "Main Thread"
            MAIN[Main Entry Point]
            INIT[Initialization]
        end

        subgraph "Event Thread"
            EVENT[EventProcessor]
            QUEUE[Event Queue]
        end

        subgraph "Render Thread"
            RENDER[Rendering Loop]
            SCENE[Scene Graph Traversal]
        end

        subgraph "Application Threads"
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

## Package Structure

```mermaid
graph TB
    subgraph "org.jdesktop.lg3d"
        SG[sg<br/>Scene Graph]
        WG[wg<br/>Widgets]
        UTILS[utils<br/>Utilities]
        DS[displayserver<br/>Display Server]
        SM[scenemanager<br/>Scene Manager]
        APPS[apps<br/>Applications]
        APPKIT[appkit<br/>Application Kit]
    end

    subgraph "Scene Graph Packages"
        SG --> NODE[Node Hierarchy]
        SG --> GEOM[Geometry]
        SG --> APPEAR[Appearance]
        SG --> LIGHT[Lighting]
    end

    subgraph "Widget Packages"
        WG --> COMP[Components]
        WG --> FRAME[Frames]
        WG --> LAYOUT[Layout]
    end

    subgraph "Utility Packages"
        UTILS --> SHAPE[Shapes]
        UTILS --> ACTION[Actions]
        UTILS --> EVENT[Events]
    end

    style SG fill:#4CAF50,color:#fff
    style WG fill:#2196F3,color:#fff
    style UTILS fill:#FF9800,color:#fff
    style DS fill:#9C27B0,color:#fff
    style SM fill:#E91E63,color:#fff
    style APPS fill:#00BCD4,color:#fff
    style APPKIT fill:#607D8B,color:#fff
```
