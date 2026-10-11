# Project Looking Glass - Documentation

Bienvenue dans la documentation de Project Looking Glass. Welcome to the Project Looking Glass documentation.

## Table of Contents / Table des matières

- [Overview](#overview)
- [Documentation Structure](#documentation-structure)
- [English Documentation](#english-documentation)
- [Documentation Française](#documentation-française)
- [API Documentation](#api-documentation)
- [Contributing](#contributing)

## Overview

Project Looking Glass (LG3D) is a modern 3D desktop environment, ported from the 2006-era Sun Microsystems codebase to JDK 21 and Gradle. This documentation provides comprehensive technical information about the system architecture, design, and functionality.

Project Looking Glass (LG3D) est un environnement de bureau 3D moderne, porté du codebase Sun Microsystems de 2006 vers JDK 21 et Gradle. Cette documentation fournit des informations techniques complètes sur l'architecture, la conception et la fonctionnalité du système.

## Documentation Structure

```
documentation/
├── README.md                    # This file / Ce fichier
├── en/                          # English documentation / Documentation en anglais
│   ├── diagrams/
│   │   ├── architecture-overview.md    # Architecture diagrams
│   │   ├── sequence-diagrams.md         # Sequence diagrams
│   │   └── class-diagrams.md            # Class diagrams
│   ├── functional-analysis/
│   │   └── functional-analysis.md       # Functional analysis document
│   └── api/                             # API documentation (Javadoc)
└── fr/                          # French documentation / Documentation en français
    ├── diagrams/
    │   ├── architecture-overview.md    # Diagrammes d'architecture
    │   ├── sequence-diagrams.md         # Diagrammes de séquence
    │   └── class-diagrams.md            # Diagrammes de classes
    ├── functional-analysis/
    │   └── functional-analysis.md       # Document d'analyse fonctionnelle
    └── api/                             # Documentation API (Javadoc)
```

## English Documentation

### Diagrams

- **[Architecture Overview](en/diagrams/architecture-overview.md)** - High-level system architecture, module dependencies, component architecture, data flow, runtime architecture, and package structure.
- **[Sequence Diagrams](en/diagrams/sequence-diagrams.md)** - Sequence diagrams for application startup, window creation, event handling, SwingNode integration, application launch, scene graph traversal, resource loading, configuration loading, gesture recognition, and extension loading.
- **[Class Diagrams](en/diagrams/class-diagrams.md)** - Class hierarchies for scene graph, widgets, display server, geometry, appearance, lighting, event adapters, actions, utilities, and configuration.

### Functional分析

- **[Functional Analysis Document](en/functional-analysis/functional-analysis.md)** - Comprehensive functional requirements, use cases, functional specifications, module analysis, data flow, user interface, integration points, and non-functional requirements.

### API Documentation

API documentation (Javadoc) is generated from the source code. See the [API Documentation](#api-documentation) section below for details.

## Documentation Française

### Diagrammes

- **[Vue d'ensemble de l'architecture](fr/diagrams/architecture-overview.md)** - Architecture système de haut niveau, dépendances de modules, architecture des composants, flux de données, architecture d'exécution et structure des paquets.
- **[Diagrammes de séquence](fr/diagrams/sequence-diagrams.md)** - Diagrammes de séquence pour le démarrage d'application, la création de fenêtre, la gestion des événements, l'intégration SwingNode, le lancement d'application, le parcours du graphe de scène, le chargement de ressources, le chargement de configuration, la reconnaissance de gestes et le chargement d'extensions.
- **[Diagrammes de classes](fr/diagrams/class-diagrams.md)** - Hiérarchies de classes pour le graphe de scène, les widgets, le serveur d'affichage, la géométrie, l'apparence, l'éclairage, les adaptateurs d'événements, les actions, les utilitaires et la configuration.

### Analyse Fonctionnelle

- **[Document d'analyse fonctionnelle](fr/functional-analysis/functional-analysis.md)** - Exigences fonctionnelles complètes, cas d'utilisation, spécifications fonctionnelles, analyse des modules, flux de données, interface utilisateur, points d'intégration et exigences non fonctionnelles.

### Documentation API

La documentation API (Javadoc) est générée à partir du code source. Voir la section [Documentation API](#api-documentation) ci-dessous pour plus de détails.

## API Documentation

The API documentation is generated using Javadoc. To generate the API documentation:

La documentation API est générée en utilisant Javadoc. Pour générer la documentation API :

```bash
# Generate Javadoc for all modules
./gradlew javadoc

# Generate Javadoc for a specific module
./gradlew :lg3d-core:javadoc
./gradlew :lg3d-apps:javadoc
```

The generated Javadoc will be available in:
- `lg3d-core/build-gradle/docs/javadoc/`
- `lg3d-apps/build-gradle/docs/javadoc/`
- And similarly for other modules

La Javadoc générée sera disponible dans :
- `lg3d-core/build-gradle/docs/javadoc/`
- `lg3d-apps/build-gradle/docs/javadoc/`
- Et de même pour les autres modules

### Key API Packages

**Core SDK (lg3d-core):**
- `org.jdesktop.lg3d.sg` - Scene graph implementation
- `org.jdesktop.lg3d.wg` - Widget toolkit
- `org.jdesktop.lg3d.utils` - Utility classes
- `org.jdesktop.lg3d.displayserver` - Display server
- `org.jdesktop.lg3d.scenemanager` - Scene management

**Paquets API principaux :**

**SDK Core (lg3d-core) :**
- `org.jdesktop.lg3d.sg` - Implémentation du graphe de scène
- `org.jdesktop.lg3d.wg` - Boîte à outils de widgets
- `org.jdesktop.lg3d.utils` - Classes utilitaires
- `org.jdesktop.lg3d.displayserver` - Serveur d'affichage
- `org.jdesktop.lg3d.scenemanager` - Gestion de scène

## Viewing Diagrams

The diagrams in this documentation are written in Mermaid format. To view them:

Les diagrammes de cette documentation sont écrits au format Mermaid. Pour les visualiser :

1. **GitHub/GitLab:** The diagrams render automatically in GitHub and GitLab markdown viewers.
2. **VS Code:** Install the [Mermaid Preview](https://marketplace.visualstudio.com/items?itemName=bierner.markdown-mermaid) extension.
3. **Online:** Use the [Mermaid Live Editor](https://mermaid.live/).
4. **Command Line:** Use the [mmdc](https://github.com/mermaid-js/mermaid-cli) tool to convert to PNG/SVG.

1. **GitHub/GitLab :** Les diagrammes s'affichent automatiquement dans les visualiseurs markdown GitHub et GitLab.
2. **VS Code :** Installez l'extension [Mermaid Preview](https://marketplace.visualstudio.com/items?itemName=bierner.markdown-mermaid).
3. **En ligne :** Utilisez l'éditeur [Mermaid Live Editor](https://mermaid.live/).
4. **Ligne de commande :** Utilisez l'outil [mmdc](https://github.com/mermaid-js/mermaid-cli) pour convertir en PNG/SVG.

## Additional Resources

### Project Documentation

- [AGENTS.md](../AGENTS.md) - Project guidelines and conventions / Directives et conventions du projet
- [README.md](../README.md) - Project overview / Aperçu du projet
- [CHANGELOG.md](../CHANGELOG.md) - Change log / Journal des modifications
- [CONTRIBUTING.md](../CONTRIBUTING.md) - Contribution guidelines / Directives de contribution

### Module-Specific Documentation

- [lg3d-core/AGENTS.md](../lg3d-core/AGENTS.md) - Core module guidelines / Directives du module core
- [lg3d-apps/AGENTS.md](../lg3d-apps/AGENTS.md) - Apps module guidelines / Directives du module apps
- [lg3d-incubator/AGENTS.md](../lg3d-incubator/AGENTS.md) - Incubator module guidelines / Directives du module incubator

### Technical Documentation

- [docs/](../docs/) - Additional technical documentation / Documentation technique supplémentaire
  - [lg3d-native-apps.md](../docs/lg3d-native-apps.md) - Building native 3D apps
  - [lg3d-native-apps-advanced.md](../docs/lg3d-native-apps-advanced.md) - Advanced native apps
  - [swingnode.md](../docs/swingnode.md) - SwingNode integration
  - [mandela-language.md](../docs/mandela-language.md) - Mandela scripting language
  - [mandela-embedding-api.md](../docs/mandela-embedding-api.md) - Mandela embedding API

## Contributing

Contributions to the documentation are welcome! Please follow these guidelines:

Les contributions à la documentation sont les bienvenues ! Veuillez suivre ces directives :

1. Maintain both English and French versions when updating content.
2. Use Mermaid for diagrams.
3. Follow the existing documentation structure.
4. Update this README when adding new documentation.
5. Ensure all links are valid and working.

1. Maintenez les versions anglaise et française lors de la mise à jour du contenu.
2. Utilisez Mermaid pour les diagrammes.
3. Suivez la structure de documentation existante.
4. Mettez à jour ce README lors de l'ajout de nouvelle documentation.
5. Assurez-vous que tous les liens sont valides et fonctionnels.

## License

This documentation is part of Project Looking Glass and is subject to the same license as the project. See the [LICENSE](../LICENSE) file for details.

Cette documentation fait partie de Project Looking Glass et est soumise à la même licence que le projet. Voir le fichier [LICENSE](../LICENSE) pour plus de détails.

## Version

- **Documentation Version:** 1.0
- **Project Version:** 1.68.0-dev
- **Last Updated:** 2026-10-11

- **Version de la documentation :** 1.0
- **Version du projet :** 1.68.0-dev
- **Dernière mise à jour :** 2026-10-11
