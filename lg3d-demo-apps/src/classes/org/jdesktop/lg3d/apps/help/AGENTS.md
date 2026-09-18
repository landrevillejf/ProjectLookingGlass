# LG3D Help Application

## Overview

LG3D Help is a preliminary help application that displays the LG3D usage guide as a static image in a 3D window. It demonstrates custom window decoration, transparency effects, and thumbnail implementation.

## Purpose

- Display LG3D usage guide to users
- Demonstrate custom window chrome (close/minimize buttons)
- Showcase transparency and visual effects
- Provide example of custom Thumbnail implementation

## Key Components

- **Lg3dHelp** - Main Frame3D with custom decoration
- **Button** - Custom 3D button with hover effects
- **ButtonAppearance** - Appearance subclass for button textures
- **HelpThumbnail** - Custom Thumbnail for taskbar/dock
- **GlassyPanel** - Glass-like decorative panel
- **FuzzyEdgePanel** - Panel with soft edges
- **RectShadow** - Rectangular shadow effect

## Architecture

### Scene Graph Structure
```
Lg3dHelp (Frame3D)
└── Component3D
    └── TransparencyOrderedGroup
        ├── RectShadow (shadow)
        ├── GlassyPanel (decoration)
        ├── FuzzyEdgePanel (body with help image)
        ├── Button (close button)
        └── Button (minimize button)
```

### Custom Window Decoration

The app opts out of automatic Frame3D window decoration:
```java
setProperty(Frame3DWindowDecoration.OPT_OUT_PROPERTY, Boolean.TRUE);
```

This allows custom button placement and styling.

### Button Behavior

Buttons implement two effects on mouse enter:
1. **Appearance Change**: Switches between off/on appearances
2. **Scale Animation**: Scales up by 15% (buttonOnSize / buttonSize)

```java
private Button(float sizeOff, Appearance appOff,
               float sizeOn, Appearance appOn) {
    // Add appearance change action
    addListener(new MouseEnteredEventAdapter(
        new AppearanceChangeAction(shape, appOn)));
    // Add scale action
    addListener(new MouseEnteredEventAdapter(
        new ScaleActionBoolean(this, sizeOn/sizeOff, 100)));
}
```

## Development Guidelines

### Sizing

- **Body Size**: 50% of screen height (square)
- **Decoration Width**: 0.005f border on all sides
- **Button Size**: 0.005f (0.00575f when hovered)
- **Thumbnail Scale**: 0.13f of full size

### Transparency

- Uses `TransparencyOrderedGroup` for proper rendering order
- Button appearances have alpha values (0.6f - 0.8f)
- Thumbnail uses 0.75f alpha

### Image Loading

```java
URL imageFilename = getClass().getResource("resources/images/lg3d-wm-usage.png");
SimpleAppearance app = new SimpleAppearance(1.0f, 1.0f, 1.0f,
    SimpleAppearance.ENABLE_TEXTURE | SimpleAppearance.DISABLE_CULLING);
app.setTexture(imageFilename);
```

## Best Practices

- **Appearance Sharing**: Make appearances non-static when using translucency effects to avoid visual issues between instances
- **Opt-Out Property**: Use `Frame3DWindowDecoration.OPT_OUT_PROPERTY` when implementing custom chrome
- **TransparencyOrderedGroup**: Use for proper rendering of overlapping transparent objects
- **Capability Setting**: Set `Shape3D.ALLOW_APPEARANCE_WRITE` for dynamic texture updates
- **Shadow Offsets**: Adjust N/E/S/W/I offsets for desired shadow depth

## Dependencies

- LG3D Core: Frame3D, Component3D, TransparencyOrderedGroup
- LG3D Utils: GlassyPanel, FuzzyEdgePanel, RectShadow, SimpleAppearance, ImagePanel
- LG3D SceneManager: Frame3DWindowDecoration
- Java 3D (Jogamp): Texture2D, Appearance, TransparencyAttributes
- Event Adapters: MouseClickedEventAdapter, MouseEnteredEventAdapter

## Resources

- Help image: `resources/images/lg3d-wm-usage.png`
- Close button: `resources/images/button/window-close.png`
- Minimize button: `resources/images/button/window-minimize.png`

## Testing

Launch standalone:
```bash
./gradlew :lg3d-demo-apps:run -Papp=help
```

## Extension Points

- **Dynamic Content**: Replace static image with rendered HTML or text
- **Navigation**: Add back/forward buttons for multi-page help
- **Search**: Implement search functionality
- **Interactive Elements**: Make help content clickable/interactive
- **Localization**: Support multiple languages

## Known Limitations

- Static image only (no interactive content)
- Preliminary implementation (not fully evolved)
- No navigation between help topics
- Fixed size (not responsive to content)
- No search or indexing
