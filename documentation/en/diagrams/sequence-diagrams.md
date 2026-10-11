# Project Looking Glass - Sequence Diagrams

## Application Startup Sequence

```mermaid
sequenceDiagram
    participant User
    participant Main as Main
    participant DS as DisplayServer
    participant DM as DesktopMode
    participant VU as VirtualUniverse
    participant SG as SceneGraph
    participant Render as Renderer

    User->>Main: Execute ./gradlew :lg3d-core:run
    Main->>Main: Load configuration
    Main->>DS: Initialize DisplayServer
    DS->>DM: Determine DesktopMode (dev/production)
    DM-->>DS: Return mode
    DS->>VU: Create VirtualUniverse
    VU->>SG: Initialize Scene Graph
    SG-->>VU: Scene graph ready
    VU-->>DS: Universe ready
    DS->>Render: Start rendering loop
    Render->>Render: Begin render cycle
    Render->>SG: Traverse scene graph
    SG-->>Render: Render instructions
    Render->>User: Display output
    Render->>Render: Schedule next frame
```

## Window Creation Sequence (3D Frame)

```mermaid
sequenceDiagram
    participant App as Application
    participant Frame as Frame3D
    participant Container as Container3D
    participant SG as SceneGraph
    participant SM as SceneManager
    participant Render as Renderer

    App->>Frame: new Frame3D(title, size)
    Frame->>Container: Create Container3D
    Container->>SG: Add to scene graph
    SG-->>Container: Node added
    Frame->>Container: Set translation/rotation
    Frame->>SM: Register window
    SM-->>Frame: Window registered
    App->>Frame: addComponent(component)
    Frame->>Container: addChild(component)
    Container->>SG: Update scene graph
    SG-->>Render: Scene changed
    Render->>Render: Re-render scene
    Render-->>User: Updated display
```

## Event Handling Sequence

```mermaid
sequenceDiagram
    participant User
    participant Input as InputDevice
    participant EP as EventProcessor
    participant Queue as EventQueue
    participant Listener as EventListener
    participant Action as Action
    participant Scene as SceneGraph

    User->>Input: Mouse click / Key press
    Input->>EP: Raw input event
    EP->>EP: Parse event
    EP->>Queue: Enqueue event
    Queue->>EP: Dequeue event
    EP->>Listener: Dispatch to listeners
    Listener->>Listener: Check event type
    alt Event matches
        Listener->>Action: Trigger action
        Action->>Scene: Modify scene graph
        Scene-->>Action: Update complete
        Action-->>Listener: Action executed
    else Event doesn't match
        Listener-->>EP: Ignore event
    end
    EP->>EP: Process next event
```

## SwingNode Integration Sequence

```mermaid
sequenceDiagram
    participant App as Application
    participant Frame as Frame3D
    participant SN as SwingNode
    participant Swing as SwingPanel
    participant Image as BufferedImage
    participant Texture as Texture2D
    participant SG as SceneGraph

    App->>Frame: Create frame
    Frame->>SN: Create SwingNode
    SN->>Swing: Create Swing panel
    Swing->>Swing: Initialize components
    Swing-->>SN: Panel ready
    SN->>Image: Create BufferedImage
    SN->>Swing: Paint panel to image
    Swing->>Image: Graphics rendering
    Image-->>SN: Rendering complete
    SN->>Texture: Create Texture2D
    SN->>Texture: Upload image data
    Texture-->>SN: Texture ready
    SN->>SG: Attach texture to quad
    SG-->>SN: Attached to scene
    SN->>Swing: Register for updates
    Swing->>SN: Component changed
    SN->>Image: Re-paint to image
    SN->>Texture: Update texture
    Texture->>SG: Scene updated
```

## Application Launch Sequence

```mermaid
sequenceDiagram
    participant User
    participant Menu as StartMenu
    participant Registry as AppRegistry
    participant Config as ConfigLoader
    participant Loader as AppLoader
    participant App as Application
    participant DS as DisplayServer

    User->>Menu: Click application icon
    Menu->>Registry: Lookup application
    Registry-->>Menu: App descriptor
    Menu->>Config: Load .lgcfg file
    Config-->>Menu: Configuration loaded
    Menu->>Loader: Launch application
    Loader->>Loader: Parse command
    alt Java application
        Loader->>App: Load main class
        App->>App: Initialize
        App->>DS: Register with display server
        DS-->>App: Registration complete
        App-->>Loader: Application started
    else Native application
        Loader->>DS: Spawn native process
        DS-->>Loader: Process started
    end
    Loader-->>Menu: Launch complete
    Menu-->>User: Application visible
```

## Scene Graph Traversal Sequence

```mermaid
sequenceDiagram
    participant Render as Renderer
    participant VU as VirtualUniverse
    participant Locale as Locale
    participant BG as BranchGroup
    participant TG as TransformGroup
    participant Shape as Shape3D
    participant Geo as Geometry

    Render->>VU: Start render pass
    VU->>Locale: Get locales
    Locale-->>VU: Locale list
    VU->>BG: Traverse branch
    BG->>TG: Visit child
    TG->>TG: Apply transform
    TG->>Shape: Visit shape
    Shape->>Geo: Get geometry
    Geo-->>Shape: Geometry data
    Shape->>Shape: Calculate bounds
    Shape->>Shape: Check visibility
    alt Visible
        Shape-->>TG: Render instructions
    else Culled
        Shape-->>TG: Skip
    end
    TG->>TG: Visit next child
    TG-->>BG: Children processed
    BG->>BG: Visit next branch
    BG-->>VU: Traversal complete
    VU-->>Render: Render data ready
```

## Resource Loading Sequence

```mermaid
sequenceDiagram
    participant App as Application
    participant RM as ResourceManager
    participant CP as ClassPath
    participant FS as FileSystem
    participant Cache as ResourceCache

    App->>RM: getResource(path)
    RM->>Cache: Check cache
    alt Cache hit
        Cache-->>RM: Return cached resource
        RM-->>App: Resource data
    else Cache miss
        RM->>CP: Resolve from classpath
        CP->>FS: Load file
        FS-->>CP: File data
        CP-->>RM: Resource data
        RM->>Cache: Store in cache
        Cache-->>RM: Cached
        RM-->>App: Resource data
    end
```

## Configuration Loading Sequence

```mermaid
sequenceDiagram
    participant Main as Main
    participant LC as LgConfig
    participant FS as FileSystem
    participant XML as XMLParser
    participant DS as DefaultConfigControl
    participant Props as Properties

    Main->>LC: Initialize configuration
    LC->>FS: Locate lgconfig.xml
    FS-->>LC: File path
    LC->>XML: Parse XML file
    XML->>XML: Read configuration
    XML-->>LC: Parsed config
    LC->>DS: Apply configuration
    DS->>Props: Load properties
    Props-->>DS: Property values
    DS->>DS: Validate settings
    DS-->>LC: Configuration applied
    LC-->>Main: Configuration ready
    Main->>Main: Start display server
```

## Gesture Recognition Sequence

```mermaid
sequenceDiagram
    participant User
    participant Input as InputDevice
    participant GM as GestureModule
    participant Stroke as StrokeClassifier
    participant Action as GestureAction
    participant App as Application

    User->>Input: Mouse drag gesture
    Input->>GM: Raw motion events
    GM->>GM: Record stroke points
    User->>Input: Release mouse
    Input->>GM: Motion complete
    GM->>Stroke: Classify stroke
    Stroke->>Stroke: Analyze geometry
    Stroke->>Stroke: Match gesture pattern
    alt Gesture recognized
        Stroke-->>GM: Gesture type
        GM->>Action: Execute gesture action
        Action->>App: Trigger application action
        App-->>User: Action performed
    else Gesture not recognized
        Stroke-->>GM: Unknown gesture
        GM->>GM: Discard stroke
    end
```

## Extension Loading Sequence

```mermaid
sequenceDiagram
    participant App as Application
    participant SL as ServiceLoader
    participant Registry as ExtensionRegistry
    participant Ext as Extension
    participant Manifest as ExtensionManifest

    App->>SL: Load extensions
    SL->>SL: Scan META-INF/services
    SL-->>App: Extension implementations
    App->>Registry: Register extensions
    Registry->>Ext: Load extension class
    Ext->>Manifest: Get manifest
    Manifest-->>Ext: Extension metadata
    Ext-->>Registry: Extension info
    Registry->>Registry: Validate permissions
    Registry-->>App: Extensions loaded
    App->>Ext: Initialize extension
    Ext-->>App: Extension ready
```
