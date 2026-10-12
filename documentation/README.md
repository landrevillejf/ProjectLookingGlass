# Project Looking Glass - Documentation

Bienvenue dans la documentation de Project Looking Glass. Welcome to the Project Looking Glass documentation.

## Table of Contents / Table des matières

- [Overview](#overview)
- [Documentation Structure](#documentation-structure)
- [English Documentation](#english-documentation)
- [Documentation Française](#documentation-française)
- [Project Mandela](#project-mandela)
- [API Documentation](#api-documentation)
- [Contributing](#contributing)

## Overview

Project Looking Glass (LG3D) is a modern 3D desktop environment, ported from the 2006-era Sun Microsystems codebase to JDK 21 and Gradle. This documentation provides comprehensive technical information about the system architecture, design, and functionality.

Project Looking Glass (LG3D) est un environnement de bureau 3D moderne, porté du codebase Sun Microsystems de 2006 vers JDK 21 et Gradle. Cette documentation fournit des informations techniques complètes sur l'architecture, la conception et la fonctionnalité du système.

## Documentation Structure

```
documentation/
├── README.md                    # This file / Ce fichier
├── index.html                   # Documentation portal (bilingual landing page)
├── mandela/                     # Project Mandela module docs / Docs du module Mandela
│   └── docs/
│       ├── README.md            # Language overview (index)
│       ├── ARCHITECTURE.md      # Module architecture & design decisions
│       ├── DEVELOPMENT.md       # Contributor guide (build, test, CLI)
│       └── FUNCTIONAL_ANALYSIS.md # Functional requirements & VM instruction set
├── en/                          # English documentation / Documentation en anglais
│   ├── diagrams/
│   │   ├── architecture-overview.md    # Architecture diagrams
│   │   ├── sequence-diagrams.md         # Sequence diagrams
│   │   ├── class-diagrams.md            # Class diagrams
│   │   └── desktop-2d.md                # Desktop 2D (fallback) diagrams
│   └── functional-analysis/
│       ├── functional-analysis.md       # Functional analysis document
│       └── desktop-2d.md                # Desktop 2D (fallback) functional analysis
└── fr/                          # French documentation / Documentation en français
    ├── diagrams/
    │   ├── architecture-overview.md    # Diagrammes d'architecture
    │   ├── sequence-diagrams.md         # Diagrammes de séquence
    │   ├── class-diagrams.md            # Diagrammes de classes
    │   └── desktop-2d.md                # Diagrammes du Bureau 2D (repli)
    └── functional-analysis/
        ├── functional-analysis.md       # Document d'analyse fonctionnelle
        └── desktop-2d.md                # Analyse fonctionnelle du Bureau 2D (repli)
```

The prose published here is bilingual. The Javadoc API reference is **not** part of
this site: it is never committed or published. Generate it locally when you need it
(see [API Documentation](#api-documentation) below).

La prose publiée ici est bilingue. La référence d'API Javadoc ne fait **pas** partie de
ce site : elle n'est jamais commitée ni publiée. Générez-la localement au besoin (voir
[Documentation API](#documentation-api) plus bas).

## English Documentation

### Diagrams

- **[Architecture Overview](en/diagrams/architecture-overview.md)** - High-level system architecture, module dependencies, component architecture, data flow, runtime architecture, and package structure.
- **[Sequence Diagrams](en/diagrams/sequence-diagrams.md)** - Sequence diagrams for application startup, window creation, event handling, SwingNode integration, application launch, scene graph traversal, resource loading, configuration loading, gesture recognition, and extension loading.
- **[Class Diagrams](en/diagrams/class-diagrams.md)** - Class hierarchies for scene graph, widgets, display server, geometry, appearance, lighting, event adapters, actions, utilities, and configuration.
- **[Desktop 2D Diagrams](en/diagrams/desktop-2d.md)** - Mermaid diagrams of the conventional-Swing fallback desktop: component architecture, the `openApp` launch flow, the class model, taskbar layout, window snapping, workspaces and surface transparency.

### Functional Analysis

- **[Functional Analysis Document](en/functional-analysis/functional-analysis.md)** - Comprehensive functional requirements, use cases, functional specifications, module analysis, data flow, user interface, integration points, and non-functional requirements.
- **[Desktop 2D Functional Analysis](en/functional-analysis/desktop-2d.md)** - The behavioural contract of the 2D shell: application classification and launch, the start menu, taskbar chrome, window management, workspaces, notifications, transparency, theming, session restore and the Run box.

### API Documentation

Javadoc for the built modules is generated on demand and is **not** published here.
See [API Documentation](#api-documentation) below for how to build it locally.

## Documentation Française

### Diagrammes

- **[Vue d'ensemble de l'architecture](fr/diagrams/architecture-overview.md)** - Architecture système de haut niveau, dépendances de modules, architecture des composants, flux de données, architecture d'exécution et structure des paquets.
- **[Diagrammes de séquence](fr/diagrams/sequence-diagrams.md)** - Diagrammes de séquence pour le démarrage d'application, la création de fenêtre, la gestion des événements, l'intégration SwingNode, le lancement d'application, le parcours du graphe de scène, le chargement de ressources, le chargement de configuration, la reconnaissance de gestes et le chargement d'extensions.
- **[Diagrammes de classes](fr/diagrams/class-diagrams.md)** - Hiérarchies de classes pour le graphe de scène, les widgets, le serveur d'affichage, la géométrie, l'apparence, l'éclairage, les adaptateurs d'événements, les actions, les utilitaires et la configuration.
- **[Diagrammes du Bureau 2D](fr/diagrams/desktop-2d.md)** - Diagrammes Mermaid du bureau de repli Swing conventionnel : architecture des composants, flux de lancement `openApp`, modèle de classes, disposition de la barre, ancrage des fenêtres, bureaux virtuels et transparence des surfaces.

### Analyse Fonctionnelle

- **[Document d'analyse fonctionnelle](fr/functional-analysis/functional-analysis.md)** - Exigences fonctionnelles complètes, cas d'utilisation, spécifications fonctionnelles, analyse des modules, flux de données, interface utilisateur, points d'intégration et exigences non fonctionnelles.
- **[Analyse fonctionnelle du Bureau 2D](fr/functional-analysis/desktop-2d.md)** - Le contrat comportemental de la coquille 2D : classification et lancement des applications, menu Démarrer, habillage de la barre, gestion des fenêtres, bureaux virtuels, notifications, transparence, thématisation, restauration de session et boîte Exécuter.

### Documentation API

La Javadoc des modules construits est générée à la demande et n'est **pas** publiée ici.
Voir [Documentation API](#documentation-api) plus bas pour la construire localement.

## Project Mandela

The documentation of the desktop's own scripting language, **Project Mandela**
(`lg3d-mandela`), is published here as a module snapshot. These are the module
guides; the full language reference and the embedding API live in the repository's
[`docs/`](https://github.com/landrevillejf/ProjectLookingGlass/tree/main/docs) directory.

- **[Mandela Overview](mandela/docs/README.md)** - What Mandela is, its module structure, security model and quick start
- **[Architecture](mandela/docs/ARCHITECTURE.md)** - Internal architecture, package structure and design decisions
- **[Development Guide](mandela/docs/DEVELOPMENT.md)** - Contributor guide: build, test, tooling and coding standards
- **[Functional Analysis](mandela/docs/FUNCTIONAL_ANALYSIS.md)** - Functional requirements, use cases and the VM instruction set

> The published copies under `documentation/mandela/docs/` mirror the module guides
> in `lg3d-mandela/docs/`. When a module guide changes, refresh the copy and keep the
> links that leave `documentation/` **absolute** - GitHub Pages serves this directory
> as the site root, so a `../` link would 404 on the published site.

## Projet Mandela

La documentation du langage de script du bureau, **Project Mandela** (`lg3d-mandela`),
est publiée ici comme instantané du module. Ce sont les guides du module ; la référence
complète du langage et l'API d'intégration se trouvent dans le répertoire
[`docs/`](https://github.com/landrevillejf/ProjectLookingGlass/tree/main/docs) du dépôt.

- **[Aperçu Mandela](mandela/docs/README.md)** - Ce qu'est Mandela, sa structure de modules, son modèle de sécurité et son démarrage rapide
- **[Architecture](mandela/docs/ARCHITECTURE.md)** - Architecture interne, structure des paquets et décisions de conception
- **[Guide de développement](mandela/docs/DEVELOPMENT.md)** - Guide contributeur : build, test, outillage et normes de code
- **[Analyse fonctionnelle](mandela/docs/FUNCTIONAL_ANALYSIS.md)** - Exigences fonctionnelles, cas d'utilisation et jeu d'instructions de la VM

## API Documentation

The API reference is **not committed and not published**. It is generated on demand,
locally, from the sources of each module into `documentation/en/api/` (one directory
per module plus a landing page). That directory is git-ignored, so it never enters the
repository or the Pages site. It is a build artifact - never edit those files by hand.

La référence d'API n'est **ni commitée ni publiée**. Elle est générée à la demande, en
local, à partir des sources de chaque module dans `documentation/en/api/` (un
répertoire par module plus une page d'accueil). Ce répertoire est ignoré par git : il
n'entre donc jamais dans le dépôt ni sur le site Pages. C'est un artefact de build -
ne jamais éditer ces fichiers à la main.

```bash
# Generate the whole reference (all ten modules) locally into documentation/en/api/ (git-ignored, never committed)
./gradlew documentationSite

# Javadoc for a single module only (output stays under <module>/build-gradle/docs/javadoc/)
./gradlew :lg3d-core:javadoc
./gradlew :lg3d-apps:javadoc
```

`documentationSite` fails if any module produces an empty tree, so a module cannot
silently vanish from the generated reference.

`documentationSite` échoue si un module produit une arborescence vide : un module ne
peut donc pas disparaître silencieusement de la référence générée.

**Key API packages:**

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

Links out of `documentation/` are absolute: GitHub Pages publishes this directory as
the site root, so a `../` link would 404 on the published site while still working on
GitHub. / Les liens sortant de `documentation/` sont absolus : GitHub Pages publie ce
répertoire comme racine du site, donc un lien `../` renverrait une erreur 404 sur le
site publié tout en fonctionnant sur GitHub.

- [AGENTS.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/AGENTS.md) - Project guidelines and conventions / Directives et conventions du projet
- [README.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/README.md) - Project overview / Aperçu du projet
- [CHANGELOG.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/CHANGELOG.md) - Change log / Journal des modifications
- [CONTRIBUTING.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/CONTRIBUTING.md) - Contribution guidelines / Directives de contribution

### Module-Specific Documentation

- [lg3d-core/AGENTS.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/lg3d-core/AGENTS.md) - Core module guidelines / Directives du module core
- [lg3d-apps/AGENTS.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/lg3d-apps/AGENTS.md) - Apps module guidelines / Directives du module apps
- [lg3d-incubator/AGENTS.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/lg3d-incubator/AGENTS.md) - Incubator module guidelines / Directives du module incubator

### Technical Documentation

Directory: [`docs/`](https://github.com/landrevillejf/ProjectLookingGlass/tree/main/docs) - Additional technical documentation / Documentation technique supplémentaire

- [lg3d-native-apps.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/docs/lg3d-native-apps.md) - Building native 3D apps
- [lg3d-native-apps-advanced.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/docs/lg3d-native-apps-advanced.md) - Advanced native apps
- [swingnode.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/docs/swingnode.md) - SwingNode integration
- [mandela-language.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/docs/mandela-language.md) - Mandela scripting language
- [mandela-embedding-api.md](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/docs/mandela-embedding-api.md) - Mandela embedding API

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

This documentation is part of Project Looking Glass and is subject to the same license
as the project. There is no repository-root `LICENSE` file: the historic Sun/Java
Research terms and the port's GNU GPL v2 header live per module, see
[lg3d-core/LICENSE](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/lg3d-core/LICENSE)
and the project [README](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/README.md#license).

Cette documentation fait partie de Project Looking Glass et est soumise à la même licence
que le projet. Il n'y a pas de fichier `LICENSE` à la racine du dépôt : les termes
historiques Sun/Java Research et l'en-tête GNU GPL v2 de la portabilité sont définis par
module, voir [lg3d-core/LICENSE](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/lg3d-core/LICENSE)
et le [README](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/README.md#license) du projet.

## Version

- **Documentation Version:** 1.0
- **Project Version:** 1.68.0-dev
- **Last Updated:** 2026-10-11

- **Version de la documentation :** 1.0
- **Version du projet :** 1.68.0-dev
- **Dernière mise à jour :** 2026-10-11
