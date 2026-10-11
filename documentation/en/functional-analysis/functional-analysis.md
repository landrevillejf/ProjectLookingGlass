# Project Looking Glass - Functional Analysis Document

## Document Information

| Field | Value |
|-------|-------|
| Project | Project Looking Glass (LG3D) |
| Version | 1.68.0-dev |
| Document Version | 1.0 |
| Date | 2026-10-11 |
| Language | English |

## Table of Contents

1. [Introduction](#introduction)
2. [System Overview](#system-overview)
3. [Functional Requirements](#functional-requirements)
4. [Use Cases](#use-cases)
5. [Functional Specifications](#functional-specifications)
6. [Module Analysis](#module-analysis)
7. [Data Flow](#data-flow)
8. [User Interface](#user-interface)
9. [Integration Points](#integration-points)
10. [Non-Functional Requirements](#non-functional-requirements)

## Introduction

### Purpose

This document provides a comprehensive functional analysis of Project Looking Glass, a 3D desktop environment modernized from the 2006-era Sun Microsystems codebase. It describes the system's functionality, use cases, and technical specifications.

### Scope

This document covers:
- The core 3D desktop environment (lg3d-core)
- Production applications (lg3d-apps)
- Experimental applications (lg3d-incubator)
- Supporting modules (lg3d-widgets, lg3d-mandela, etc.)
- Integration with external libraries and frameworks

### Target Audience

- Software architects
- Developers
- System integrators
- Quality assurance engineers
- Project managers

## System Overview

### System Description

Project Looking Glass is a 3D desktop environment that provides an immersive, spatial computing interface. The system renders a three-dimensional workspace where applications exist as 3D objects rather than flat windows. Users can interact with applications through gestures, mouse input, and keyboard commands in a spatial context.

### System Objectives

- Provide a 3D desktop environment for modern computing
- Support both 3D native applications and traditional 2D applications
- Enable spatial organization of applications and windows
- Maintain compatibility with existing Java applications
- Support extensible application development through a comprehensive SDK

### System Context

```
┌─────────────────────────────────────────────────────────┐
│                     User                                │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              Project Looking Glass                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │  3D Desktop  │  │  2D Desktop  │  │ Applications │ │
│  └──────────────┘  └──────────────┘  └──────────────┘ │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ Scene Graph  │  │ Event System │  │ Config Mgmt  │ │
│  └──────────────┘  └──────────────┘  └──────────────┘ │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              External Dependencies                       │
│  Java 3D (Jogamp) │ JavaFX │ Jackson │ Apache Commons  │
└─────────────────────────────────────────────────────────┘
```

## Functional Requirements

### FR-1: Desktop Environment

**FR-1.1:** The system shall provide a 3D desktop environment where applications can be launched and managed.

**FR-1.2:** The system shall support a 2D desktop mode for compatibility with traditional applications.

**FR-1.3:** The system shall allow users to switch between 3D and 2D desktop modes.

**FR-1.4:** The system shall provide a start menu for application discovery and launch.

**FR-1.5:** The system shall provide a taskbar for managing running applications.

**FR-1.6:** The system shall support desktop background customization.

### FR-2: Window Management

**FR-2.1:** The system shall support 3D windows (Frame3D) with spatial positioning.

**FR-2.2:** The system shall support 2D windows through SwingNode integration.

**FR-2.3:** The system shall allow window movement, resizing, and minimization.

**FR-2.4:** The system shall maintain window z-ordering for proper occlusion.

**FR-2.5:** The system shall support window transparency and blending.

**FR-2.6:** The system shall provide window decorations (title bars, borders).

### FR-3: Application Framework

**FR-3.1:** The system shall provide an SDK for developing 3D applications.

**FR-3.2:** The system shall provide a widget toolkit for UI components.

**FR-3.3:** The system shall support application registration and discovery.

**FR-3.4:** The system shall provide application lifecycle management (launch, terminate).

**FR-3.5:** The system shall support inter-application communication.

### FR-4: Input Handling

**FR-4.1:** The system shall process mouse input for 3D interaction.

**FR-4.2:** The system shall process keyboard input for text entry and commands.

**FR-4.3:** The system shall support gesture recognition for spatial commands.

**FR-4.4:** The system shall provide event dispatching to applications.

**FR-4.5:** The system shall support custom event listeners.

### FR-5: Rendering

**FR-5.1:** The system shall render 3D scenes using Java 3D (Jogamp).

**FR-5.2:** The system shall support hardware-accelerated rendering.

**FR-5.3:** The system shall maintain a consistent frame rate (target 60 FPS).

**FR-5.4:** The system shall support lighting and shading effects.

**FR-5.5:** The system shall support texture mapping and materials.

**FR-5.6:** The system shall support transparency and alpha blending.

### FR-6: Configuration

**FR-6.1:** The system shall load configuration from XML files.

**FR-6.2:** The system shall support runtime configuration changes.

**FR-6.3:** The system shall persist user preferences.

**FR-6.4:** The system shall provide configuration validation.

**FR-6.5:** The system shall support platform-specific configurations.

### FR-7: Resource Management

**FR-7.1:** The system shall load resources (images, models, sounds) from the classpath.

**FR-7.2:** The system shall cache frequently used resources.

**FR-7.3:** The system shall support resource hot-reloading in development mode.

**FR-7.4:** The system shall provide resource localization.

### FR-8: Scripting

**FR-8.1:** The system shall provide a scripting language (Mandela).

**FR-8.2:** The system shall support script execution from the desktop.

**FR-8.3:** The system shall provide a REPL for interactive scripting.

**FR-8.4:** The system shall support script embedding in applications.

### FR-9: Extensions

**FR-9.1:** The system shall support application extensions through SPI.

**FR-9.2:** The system shall provide an extension manager.

**FR-9.3:** The system shall support extension permissions and security.

**FR-9.4:** The system shall allow dynamic extension loading.

## Use Cases

### UC-1: Launch Application

**Actor:** User

**Preconditions:**
- Desktop is running
- Application is registered

**Main Flow:**
1. User opens start menu
2. User selects application from menu
3. System loads application configuration
4. System launches application process
5. Application window appears on desktop

**Postconditions:**
- Application is running
- Application window is visible

### UC-2: Move Window

**Actor:** User

**Preconditions:**
- Window is visible
- Window is not maximized

**Main Flow:**
1. User clicks on window title bar
2. User drags mouse to new position
3. System updates window position in real-time
4. User releases mouse
5. System finalizes window position

**Postconditions:**
- Window is at new position
- Window z-ordering is maintained

### UC-3: Resize Window

**Actor:** User

**Preconditions:**
- Window is visible
- Window is resizable

**Main Flow:**
1. User positions cursor over window edge
2. User clicks and drags edge
3. System updates window dimensions in real-time
4. User releases mouse
5. System finalizes window size

**Postconditions:**
- Window has new dimensions
- Window content is re-rendered

### UC-4: Switch Desktop Mode

**Actor:** User

**Preconditions:**
- Desktop is running

**Main Flow:**
1. User accesses desktop mode switch
2. User selects desired mode (3D or 2D)
3. System transitions to new mode
4. System reorganizes windows for new mode

**Postconditions:**
- Desktop is in selected mode
- Applications continue running

### UC-5: Configure Desktop

**Actor:** User

**Preconditions:**
- Desktop is running
- User has configuration permissions

**Main Flow:**
1. User opens settings application
2. User modifies configuration values
3. User saves configuration
4. System validates configuration
5. System applies changes

**Postconditions:**
- Configuration is persisted
- Changes are applied to desktop

### UC-6: Run Script

**Actor:** User

**Preconditions:**
- Desktop is running
- Mandela scripting language is available

**Main Flow:**
1. User opens script editor or REPL
2. User enters or loads script
3. User executes script
4. System compiles script to bytecode
5. System executes bytecode in VM
6. Results are displayed

**Postconditions:**
- Script execution is complete
- Output is available to user

### UC-7: Install Extension

**Actor:** User

**Preconditions:**
- Desktop is running
- Extension JAR is available

**Main Flow:**
1. User opens extension manager
2. User selects extension JAR
3. System validates extension
4. System installs extension
5. User grants permissions
6. Extension becomes available

**Postconditions:**
- Extension is installed
- Extension is registered

## Functional Specifications

### FS-1: Display Server

The DisplayServer is the main entry point for the desktop environment. It initializes the scene graph, event processing, and rendering loop.

**Key Components:**
- `Main`: Entry point and initialization
- `DisplayServerControl`: Interface for server control
- `DesktopMode`: Manages 3D/2D mode switching
- `EventProcessor`: Handles input event dispatching

**Specifications:**
- Initializes on JVM startup
- Loads configuration from `lgconfig.xml`
- Creates VirtualUniverse for 3D rendering
- Starts event processing thread
- Starts rendering loop at target 60 FPS

### FS-2: Scene Graph

The scene graph provides a hierarchical structure for 3D objects.

**Key Components:**
- `VirtualUniverse`: Root of the scene graph
- `Locale`: Container for scene graph branches
- `BranchGroup`: Compile-time branch nodes
- `TransformGroup`: Nodes with transform capabilities
- `Shape3D`: Leaf nodes with geometry and appearance

**Specifications:**
- Supports hierarchical node organization
- Provides capability bits for read/write control
- Supports live/compiled states
- Implements bounds calculation for culling
- Supports picking and intersection

### FS-3: Widget Toolkit

The widget toolkit provides UI components for 3D applications.

**Key Components:**
- `Component3D`: Base class for all widgets
- `Container3D`: Container for child components
- `Frame3D`: Window container
- `SwingNode`: Bridge for 2D Swing components
- `GlassyPanel`: Styled panel component
- `GlassyText2D`: Text rendering component

**Specifications:**
- Provides spatial positioning (translation, rotation, scale)
- Supports event listeners for interaction
- Implements cursor feedback
- Supports transparency and blending
- Bridges 2D Swing to 3D texture

### FS-4: Event System

The event system processes user input and dispatches events to applications.

**Key Components:**
- `EventProcessor`: Main event processing loop
- `EventAdapter`: Base class for event handlers
- Event adapters: MouseClicked, MouseEntered, KeyPressed, etc.
- `Action`: Interface for event responses

**Specifications:**
- Processes AWT events from input devices
- Dispatches events to registered listeners
- Supports event filtering and transformation
- Provides action framework for responses
- Runs on dedicated event thread

### FS-5: Configuration System

The configuration system manages desktop and application settings.

**Key Components:**
- `LgConfig`: Configuration loader
- `ConfigControl`: Configuration interface
- `DefaultConfigControl`: Default implementation
- XML configuration files

**Specifications:**
- Loads configuration from XML files
- Supports property access (string, boolean, int, float)
- Provides validation of configuration values
- Supports runtime configuration updates
- Persists user preferences

### FS-6: Resource Management

The resource system manages loading and caching of assets.

**Key Components:**
- Classpath-based resource loading
- Resource caching mechanism
- Resource localization support

**Specifications:**
- Loads resources from classpath under `resources/` prefix
- Caches frequently used resources
- Supports hot-reloading in development mode
- Provides resource path resolution

## Module Analysis

### lg3d-core

**Purpose:** Core SDK and desktop environment

**Key Packages:**
- `org.jdesktop.lg3d.sg`: Scene graph implementation
- `org.jdesktop.lg3d.wg`: Widget toolkit
- `org.jdesktop.lg3d.utils`: Utility classes
- `org.jdesktop.lg3d.displayserver`: Display server
- `org.jdesktop.lg3d.scenemanager`: Scene management

**Dependencies:**
- lg3d-escher (X11 protocol)
- Java 3D (Jogamp 1.7.2)
- Jackson (JSON processing)

**Functionality:**
- Scene graph management
- Widget toolkit
- Display server
- Event processing
- Configuration management

### lg3d-apps

**Purpose:** Production desktop applications

**Key Applications:**
- Calculator: Mathematical calculations
- Media Writer: Media file creation
- Paint: Image editing
- File Manager: File system navigation
- Task Manager: Process monitoring
- Control Center: System settings
- Help Center: Documentation viewer
- Software Update: Update management
- Espresso: Advanced text editor

**Dependencies:**
- lg3d-core
- JavaFX (for web browser)
- Apache Commons (various libraries)
- PDFBox (PDF viewer)
- Jakarta Mail (email client)

**Functionality:**
- Daily-driver utilities
- Reference implementations
- Start-menu integration
- 2D/3D dual-surface support

### lg3d-incubator

**Purpose:** Experimental applications

**Key Applications:**
- Image Studio: Advanced image editing
- Agenda 3D: Calendar and scheduling
- Games: 3D games collection
- Mail 3D: 3D email client
- jmf23D: Video recording and playback

**Dependencies:**
- lg3d-core
- lg3d-mandela
- Various legacy libraries (bundled)

**Functionality:**
- Experimental features
- Advanced use cases
- Legacy application ports

### lg3d-widgets

**Purpose:** Desktop widget layer

**Components:**
- Clock widget
- Temperature widget
- CPU monitor
- Memory monitor
- Widget API

**Dependencies:**
- lg3d-core

**Functionality:**
- Desktop widgets
- Widget plugin system
- Widget layer management

### lg3d-mandela

**Purpose:** Scripting language

**Components:**
- Lexer
- Parser
- Bytecode compiler
- Stack VM
- Standard library
- JSR-223 engine
- REPL

**Dependencies:**
- None (pure Java)

**Functionality:**
- Scripting language
- Embedding API
- Desktop automation
- Extension scripting

## Data Flow

### Application Launch Flow

```
User Input → Start Menu → App Registry → Config Loader → App Loader → Application
                                                                    ↓
                                                            Display Server
```

### Event Processing Flow

```
Input Device → Event Processor → Event Queue → Event Dispatch → Listener → Action → Scene Graph Update → Render
```

### Rendering Flow

```
Render Loop → Scene Graph Traversal → Culling → Transform → Lighting → Rasterization → Display
```

### Configuration Flow

```
XML File → Config Loader → Validation → Application → Runtime → Persistence
```

## User Interface

### 3D Desktop

**Characteristics:**
- Spatial window arrangement
- 3D window manipulation
- Gesture-based interaction
- Depth-based organization

**Components:**
- Start menu (3D panel)
- Taskbar (3D strip)
- Windows (3D frames)
- Background (3D environment)

### 2D Desktop

**Characteristics:**
- Traditional window layout
- MDI (Multiple Document Interface)
- Standard window controls
- Flat appearance

**Components:**
- Start menu (2D panel)
- Taskbar (2D strip)
- Windows (2D frames)
- Background (2D image)

### Input Methods

**Mouse:**
- Click: Select/activate
- Drag: Move/resize
- Scroll: Navigate/zoom
- Hover: Cursor feedback

**Keyboard:**
- Text entry
- Keyboard shortcuts
- Command sequences

**Gestures:**
- Stroke recognition
- Multi-touch (future)
- Spatial gestures (future)

## Integration Points

### External Libraries

**Java 3D (Jogamp 1.7.2):**
- 3D rendering pipeline
- Scene graph implementation
- Hardware acceleration

**JavaFX (OpenJFX):**
- Web browser rendering
- Rich media support
- Modern UI components

**Jackson:**
- JSON serialization/deserialization
- Configuration parsing
- Data persistence

**Apache Commons:**
- File I/O operations
- Compression (archive support)
- Network operations

### Native Integration

**X11 (Escher):**
- Pure-Java X11 protocol implementation
- Window management
- Input forwarding

**Future:**
- Wayland support
- Native compositor integration

## Non-Functional Requirements

### Performance

**NFR-1:** The system shall maintain a minimum frame rate of 30 FPS during normal operation.

**NFR-2:** The system shall achieve a target frame rate of 60 FPS on hardware meeting minimum specifications.

**NFR-3:** Application launch time shall not exceed 3 seconds for typical applications.

**NFR-4:** Memory usage shall not exceed 2 GB for the desktop environment with typical applications.

### Reliability

**NFR-5:** The system shall have a mean time between failures (MTBF) of at least 48 hours.

**NFR-6:** The system shall recover gracefully from application crashes without affecting the desktop environment.

**NFR-7:** The system shall provide error logging for troubleshooting.

### Scalability

**NFR-8:** The system shall support at least 20 concurrent applications.

**NFR-9:** The system shall support at least 100 windows simultaneously.

**NFR-10:** The system shall handle scene graphs with at least 10,000 nodes.

### Usability

**NFR-11:** The system shall provide intuitive controls for 3D manipulation.

**NFR-12:** The system shall provide keyboard shortcuts for common operations.

**NFR-13:** The system shall provide visual feedback for all user interactions.

### Security

**NFR-14:** The system shall validate all configuration inputs.

**NFR-15:** The system shall implement permission-based extension access.

**NFR-16:** The system shall isolate application resources.

### Maintainability

**NFR-17:** The system shall have a modular architecture for easy extension.

**NFR-18:** The system shall provide comprehensive API documentation.

**NFR-19:** The system shall support automated testing.

### Portability

**NFR-20:** The system shall run on Linux distributions.

**NFR-21:** The system shall support JDK 21 and later.

**NFR-22:** The system shall be architecture-independent (x86_64, ARM64).

## Appendix

### Glossary

- **Frame3D:** 3D window container in the scene graph
- **SwingNode:** Bridge component for embedding 2D Swing panels in 3D
- **Scene Graph:** Hierarchical data structure for 3D objects
- **VirtualUniverse:** Root container of the scene graph
- **Locale:** Container for scene graph branches
- **EventProcessor:** Component that processes and dispatches input events
- **Mandela:** The scripting language used in LG3D
- **Jogamp:** The Java 3D library fork maintained by Jogamp
- **Escher:** Pure-Java X11 protocol library

### References

- [AGENTS.md](../../AGENTS.md) - Project guidelines and conventions
- [lg3d-core/AGENTS.md](../../lg3d-core/AGENTS.md) - Core module guidelines
- [lg3d-apps/AGENTS.md](../../lg3d-apps/AGENTS.md) - Apps module guidelines
- [README.md](../../README.md) - Project overview

### Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-10-11 | Documentation Team | Initial version |
