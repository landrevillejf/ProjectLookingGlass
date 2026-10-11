# Project Looking Glass - Diagrammes de classes

## Hiérarchie de classes du graphe de scène

```mermaid
classDiagram
    class SceneGraphObject {
        <<abstrait>>
        +setCapability(int cap)
        +getCapability(int cap)
        +clearCapability(int cap)
        +isLive() boolean
    }

    class Node {
        <<abstrait>>
        +getParent() Group
        +setBounds(Bounds bounds)
        +getBounds() Bounds
        +intersect(PickShape pick) boolean
    }

    class Group {
        <<abstrait>>
        +addChild(Node child)
        +removeChild(Node child)
        +getChild(int index) Node
        +numChildren() int
        +enumerateChildren() Enumeration
    }

    class Leaf {
        <<abstrait>>
    }

    class BranchGroup {
        +compile()
        +isCompiled() boolean
    }

    class TransformGroup {
        +setTransform(Transform3D t)
        +getTransform(Transform3D t)
        +getTransform() Transform3D
    }

    class Shape3D {
        +setGeometry(Geometry geom)
        +getGeometry() Geometry
        +setAppearance(Appearance app)
        +getAppearance() Appearance
    }

    SceneGraphObject <|-- Node
    Node <|-- Group
    Node <|-- Leaf
    Group <|-- BranchGroup
    Group <|-- TransformGroup
    Leaf <|-- Shape3D
```

## Hiérarchie de classes des widgets

```mermaid
classDiagram
    class Component3D {
        <<abstrait>>
        +setTranslation(Vector3f v)
        +setTranslation(float x, y, z)
        +getTranslation(Vector3f v)
        +setRotation(Quaternion q)
        +setScale(float s)
        +addListener(EventListener l)
        +removeListener(EventListener l)
        +setCursor(Cursor3D c)
    }

    class Container3D {
        +addChild(Component3D child)
        +removeChild(Component3D child)
        +getChild(int index) Component3D
        +numChildren() int
    }

    class Frame3D {
        +setTitle(String title)
        +getTitle() String
        +setSize(Vector3f size)
        +getSize() Vector3f
        +setVisible(boolean visible)
        +addComponent(Component3D c)
        +removeComponent(Component3D c)
    }

    class SwingNode {
        +setPanel(JPanel panel)
        +getPanel() JPanel
        +updateTexture()
        +dispose()
    }

    class GlassyPanel {
        +setWidth(float w)
        +setHeight(float h)
        +setColor(Color3f c)
        +setTransparency(float t)
    }

    class GlassyText2D {
        +setText(String text)
        +getText() String
        +setFont(Font f)
        +setColor(Color4f c)
        +setAlignment(int align)
    }

    Component3D <|-- Container3D
    Container3D <|-- Frame3D
    Component3D <|-- SwingNode
    Component3D <|-- GlassyPanel
    Component3D <|-- GlassyText2D
```

## Classes du serveur d'affichage

```mermaid
classDiagram
    class DisplayServer {
        -DisplayServerControl control
        -DesktopMode mode
        -EventProcessor eventProcessor
        +main(String[] args)
        +initialize()
        +start()
        +shutdown()
    }

    class DisplayServerControl {
        <<interface>>
        +getDesktopMode() DesktopMode
        +getEventProcessor() EventProcessor
        +getScreenCapture() ScreenCaptureBehavior
    }

    class DesktopMode {
        <<abstrait>>
        +initialize()
        +getSceneGraphPath() Locale
        +isDevMode() boolean
    }

    class EventProcessor {
        -Queue eventQueue
        -List listeners
        +start()
        +stop()
        +dispatchEvent(AWTEvent e)
        +addListener(EventListener l)
        +removeListener(EventListener l)
    }

    class Main {
        +main(String[] args)
        +initializeDisplayServer()
        +loadConfiguration()
    }

    class LgConfig {
        -Properties properties
        +load(String configPath)
        +get(String key) String
        +getBoolean(String key) boolean
        +getInt(String key) int
    }

    DisplayServer --> DisplayServerControl
    DisplayServer --> DesktopMode
    DisplayServer --> EventProcessor
    Main --> DisplayServer
    Main --> LgConfig
```

## Classes de géométrie

```mermaid
classDiagram
    class Geometry {
        <<abstrait>>
        +setCapability(int cap)
        +getCapability(int cap)
    }

    class GeometryArray {
        <<abstrait>>
        +setCoordinates(float[] coords)
        +getCoordinates(float[] coords)
        +setNormals(float[] normals)
        +getNormals(float[] normals)
        +setColors(float[] colors)
        +getColors(float[] colors)
        +setTexCoordIndices(int[] indices)
        +getTexCoordIndices(int[] indices)
    }

    class GeometryStripArray {
        <<abstrait>>
        +setStripVertexCounts(int[] counts)
        +getStripVertexCounts(int[] counts)
    }

    class QuadArray {
        +QuadArray(int vertexCount, int vertexFormat)
    }

    class TriangleArray {
        +TriangleArray(int vertexCount, int vertexFormat)
    }

    class TriangleStripArray {
        +TriangleStripArray(int vertexCount, int vertexFormat, int[] stripCounts)
    }

    class TriangleFanArray {
        +TriangleFanArray(int vertexCount, int vertexFormat, int[] stripCounts)
    }

    Geometry <|-- GeometryArray
    GeometryArray <|-- GeometryStripArray
    GeometryArray <|-- QuadArray
    GeometryArray <|-- TriangleArray
    GeometryStripArray <|-- TriangleStripArray
    GeometryStripArray <|-- TriangleFanArray
```

## Classes d'apparence

```mermaid
classDiagram
    class Appearance {
        +setMaterial(Material mat)
        +getMaterial() Material
        +setTexture(Texture tex)
        +getTexture() Texture
        +setTextureAttributes(TextureAttributes ta)
        +getTextureAttributes() TextureAttributes
        +setTransparencyAttributes(TransparencyAttributes ta)
        +getTransparencyAttributes() TransparencyAttributes
        +setPolygonAttributes(PolygonAttributes pa)
        +getPolygonAttributes() PolygonAttributes
        +setColoringAttributes(ColoringAttributes ca)
        +getColoringAttributes() ColoringAttributes
        +setRenderingAttributes(RenderingAttributes ra)
        +getRenderingAttributes() RenderingAttributes
    }

    class Material {
        +setAmbientColor(Color3f color)
        +getAmbientColor(Color3f color)
        +setDiffuseColor(Color3f color)
        +getDiffuseColor(Color3f color)
        +setSpecularColor(Color3f color)
        +getSpecularColor(Color3f color)
        +setEmissiveColor(Color3f color)
        +getEmissiveColor(Color3f color)
        +setShininess(float shininess)
        +getShininess() float
    }

    class Texture {
        <<abstrait>>
        +setMagFilter(int mode)
        +getMagFilter() int
        +setMinFilter(int mode)
        +getMinFilter() int
        +setBoundaryModeS(int mode)
        +getBoundaryModeS() int
        +setBoundaryModeT(int mode)
        +getBoundaryModeT() int
    }

    class Texture2D {
        +Texture2D(int format, int width, int height)
        +setImage(int level, ImageComponent2D image)
        +getImage(int level) ImageComponent2D
    }

    class TextureAttributes {
        +setTextureMode(int mode)
        +getTextureMode() int
        +setTransform(Transform3D transform)
        +getTransform(Transform3D transform)
    }

    class TransparencyAttributes {
        +setTransparencyMode(int mode)
        +getTransparencyMode() int
        +setTransparency(float t)
        +getTransparency() float
    }

    class PolygonAttributes {
        +setCullFace(int mode)
        +getCullFace() int
        +setPolygonMode(int mode)
        +getPolygonMode() int
    }

    Appearance --> Material
    Appearance --> Texture
    Appearance --> TextureAttributes
    Appearance --> TransparencyAttributes
    Appearance --> PolygonAttributes
    Texture <|-- Texture2D
```

## Classes d'éclairage

```mermaid
classDiagram
    class Light {
        <<abstrait>>
        +setColor(Color3f color)
        +getColor(Color3f color)
        +setEnable(boolean state)
        +getEnable() boolean
    }

    class AmbientLight {
        +AmbientLight()
        +AmbientLight(Color3f color)
    }

    class DirectionalLight {
        +DirectionalLight()
        +DirectionalLight(Color3f color, Vector3f direction)
        +setDirection(Vector3f direction)
        +getDirection(Vector3f direction)
    }

    class PointLight {
        +PointLight()
        +PointLight(Color3f color, Point3f position)
        +setPosition(Point3f position)
        +getPosition(Point3f position)
        +setAttenuation(float[] attenuation)
        +getAttenuation(float[] attenuation)
    }

    class SpotLight {
        +SpotLight()
        +SpotLight(Color3f color, Point3f position, Vector3f direction, float spreadAngle, float concentration)
        +setDirection(Vector3f direction)
        +getDirection(Vector3f direction)
        +setSpreadAngle(float angle)
        +getSpreadAngle() float
        +setConcentration(float concentration)
        +getConcentration() float
    }

    Light <|-- AmbientLight
    Light <|-- DirectionalLight
    Light <|-- PointLight
    PointLight <|-- SpotLight
```

## Classes d'adaptateurs d'événements

```mermaid
classDiagram
    class EventAdapter {
        <<abstrait>>
        +processEvent(AWTEvent e) boolean
    }

    class MouseClickedEventAdapter {
        -Action action
        +MouseClickedEventAdapter(Action action)
        +processEvent(AWTEvent e) boolean
    }

    class MouseEnteredEventAdapter {
        -Action action
        +MouseEnteredEventAdapter(Action action)
        +processEvent(AWTEvent e) boolean
    }

    class MousePressedEventAdapter {
        -Action action
        +MousePressedEventAdapter(Action action)
        +processEvent(AWTEvent e) boolean
    }

    class MouseDraggedEventAdapter {
        -Action action
        +MouseDraggedEventAdapter(Action action)
        +processEvent(AWTEvent e) boolean
    }

    class KeyPressedEventAdapter {
        -Action action
        +KeyPressedEventAdapter(Action action)
        +processEvent(AWTEvent e) boolean
    }

    class MouseHoverEventAdapter {
        -Action action
        +MouseHoverEventAdapter(Action action)
        +processEvent(AWTEvent e) boolean
    }

    EventAdapter <|-- MouseClickedEventAdapter
    EventAdapter <|-- MouseEnteredEventAdapter
    EventAdapter <|-- MousePressedEventAdapter
    EventAdapter <|-- MouseDraggedEventAdapter
    EventAdapter <|-- KeyPressedEventAdapter
    EventAdapter <|-- MouseHoverEventAdapter
```

## Classes d'actions

```mermaid
classDiagram
    class Action {
        <<abstrait>>
        +performAction() void
    }

    class ActionNoArg {
        <<interface>>
        +performAction() void
    }

    class ActionBoolean {
        <<interface>>
        +performAction(boolean value) void
    }

    class ActionInt {
        <<interface>>
        +performAction(int value) void
    }

    class AppearanceChangeAction {
        -Component3D target
        -Appearance appearance
        +AppearanceChangeAction(Component3D target, Appearance appearance)
        +performAction() void
    }

    class ScaleActionBoolean {
        -Component3D target
        -float targetScale
        -int duration
        +ScaleActionBoolean(Component3D target, float scale, int duration)
        +performAction(boolean value) void
    }

    class RotateActionBoolean {
        -Component3D target
        -AxisAngle4f rotation
        -int duration
        +RotateActionBoolean(Component3D target, AxisAngle4f rotation, int duration)
        +performAction(boolean value) void
    }

    class TransparencyAction {
        -Component3D target
        -float targetTransparency
        -int duration
        +TransparencyAction(Component3D target, float transparency, int duration)
        +performAction() void
    }

    class AppLaunchAction {
        -String className
        -String[] args
        +AppLaunchAction(String className)
        +AppLaunchAction(String className, String[] args)
        +performAction() void
    }

    Action <|-- ActionNoArg
    Action <|-- ActionBoolean
    Action <|-- ActionInt
    ActionNoArg <|.. AppearanceChangeAction
    ActionNoArg <|.. TransparencyAction
    ActionNoArg <|.. AppLaunchAction
    ActionBoolean <|.. ScaleActionBoolean
    ActionBoolean <|.. RotateActionBoolean
```

## Classes utilitaires

```mermaid
classDiagram
    class Toolkit3D {
        <<singleton>>
        -Toolkit3D instance
        -int screenWidth
        -int screenHeight
        +getToolkit3D() Toolkit3D
        +getScreenWidth() int
        +getScreenHeight() int
        +widthNativeToPhysical(float px) float
        +heightNativeToPhysical(float px) float
        +getEyePositionInVworld(Point3f eye) void
    }

    class Math3D {
        +normalize(Vector3f v) void
        +cross(Vector3f v1, Vector3f v2, Vector3f result) void
        +dot(Vector3f v1, Vector3f v2) float
        +distance(Point3f p1, Point3f p2) float
        +angle(Vector3f v1, Vector3f v2) float
    }

    class OriginTranslation {
        -Node node
        -Vector3f origin
        +OriginTranslation(Node node, Vector3f origin)
        +setTranslation(Vector3f translation) void
        +getTranslation(Vector3f translation) void
    }

    class Cursor3D {
        <<énumération>>
        SMALL_CURSOR
        MEDIUM_CURSOR
        LARGE_CURSOR
        MOVE_CURSOR
        RESIZE_CURSOR
        HAND_CURSOR
        TEXT_CURSOR
    }

    class SimpleAppearance {
        +SimpleAppearance(float r, float g, float b, float a)
        +SimpleAppearance(float r, float g, float b, float a, int flags)
        +DISABLE_CULLING int
        +ENABLE_LIGHTING int
    }
```

## Classes de configuration

```mermaid
classDiagram
    class ConfigControl {
        <<interface>>
        +get(String key) String
        +set(String key, String value) void
        +getBoolean(String key) boolean
        +getInt(String key) int
        +getFloat(String key) float
    }

    class DefaultConfigControl {
        -Properties properties
        -String configPath
        +DefaultConfigControl(String configPath)
        +load() void
        +save() void
        +get(String key) String
        +set(String key, String value) void
        +getBoolean(String key) boolean
        +getInt(String key) int
        +getFloat(String key) float
    }

    class PlatformConfig {
        <<interface>>
        +getOSName() String
        +getOSArch() String
        +getJavaVersion() String
        +isLinux() boolean
        +isWindows() boolean
        +isMacOS() boolean
    }

    class DefaultPlatformConfig {
        +getOSName() String
        +getOSArch() String
        +getJavaVersion() String
        +isLinux() boolean
        +isWindows() boolean
        +isMacOS() boolean
    }

    class LgNodeManager {
        -Map~String,Node~ nodeMap
        +registerNode(String id, Node node) void
        +unregisterNode(String id) void
        +getNode(String id) Node
        +hasNode(String id) boolean
    }

    ConfigControl <|.. DefaultConfigControl
    PlatformConfig <|.. DefaultPlatformConfig
```
