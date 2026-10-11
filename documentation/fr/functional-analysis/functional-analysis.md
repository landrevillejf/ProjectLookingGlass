# Project Looking Glass - Document d'analyse fonctionnelle

## Informations sur le document

| Champ | Valeur |
|-------|--------|
| Projet | Project Looking Glass (LG3D) |
| Version | 1.68.0-dev |
| Version du document | 1.0 |
| Date | 2026-10-11 |
| Langue | Français |

## Table des matières

1. [Introduction](#introduction)
2. [Vue d'ensemble du système](#vue-densemble-du-système)
3. [Exigences fonctionnelles](#exigences-fonctionnelles)
4. [Cas d'utilisation](#cas-dutilisation)
5. [Spécifications fonctionnelles](#spécifications-fonctionnelles)
6. [Analyse des modules](#analyse-des-modules)
7. [Flux de données](#flux-de-données)
8. [Interface utilisateur](#interface-utilisateur)
9. [Points d'intégration](#points-dintégration)
10. [Exigences non fonctionnelles](#exigences-non-fonctionnelles)

## Introduction

### Objectif

Ce document fournit une analyse fonctionnelle complète de Project Looking Glass, un environnement de bureau 3D modernisé à partir du codebase Sun Microsystems de 2006. Il décrit la fonctionnalité du système, les cas d'utilisation et les spécifications techniques.

### Portée

Ce document couvre :
- L'environnement de bureau 3D principal (lg3d-core)
- Les applications de production (lg3d-apps)
- Les applications expérimentales (lg3d-incubator)
- Les modules de support (lg3d-widgets, lg3d-mandela, etc.)
- L'intégration avec les bibliothèques et frameworks externes

### Public cible

- Architectes logiciels
- Développeurs
- Intégrateurs de systèmes
- Ingénieurs assurance qualité
- Chefs de projet

## Vue d'ensemble du système

### Description du système

Project Looking Glass est un environnement de bureau 3D qui fournit une interface de calcul spatiale immersive. Le système rend un espace de travail tridimensionnel où les applications existent sous forme d'objets 3D plutôt que de fenêtres plates. Les utilisateurs peuvent interagir avec les applications par le biais de gestes, de la souris et de commandes clavier dans un contexte spatial.

### Objectifs du système

- Fournir un environnement de bureau 3D pour le calcul moderne
- Supporter à la fois les applications 3D natives et les applications 2D traditionnelles
- Permettre l'organisation spatiale des applications et des fenêtres
- Maintenir la compatibilité avec les applications Java existantes
- Supporter le développement d'applications extensibles via un SDK complet

### Contexte du système

```
┌─────────────────────────────────────────────────────────┐
│                     Utilisateur                         │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              Project Looking Glass                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ Bureau 3D    │  │ Bureau 2D    │  │ Applications │ │
│  └──────────────┘  └──────────────┘  └──────────────┘ │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │ Graphe scène │  │ Système evt  │  │ Gestion conf │ │
│  └──────────────┘  └──────────────┘  └──────────────┘ │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              Dépendances externes                       │
│  Java 3D (Jogamp) │ JavaFX │ Jackson │ Apache Commons  │
└─────────────────────────────────────────────────────────┘
```

## Exigences fonctionnelles

### EF-1 : Environnement de bureau

**EF-1.1 :** Le système doit fournir un environnement de bureau 3D où les applications peuvent être lancées et gérées.

**EF-1.2 :** Le système doit supporter un mode de bureau 2D pour la compatibilité avec les applications traditionnelles.

**EF-1.3 :** Le système doit permettre aux utilisateurs de basculer entre les modes de bureau 3D et 2D.

**EF-1.4 :** Le système doit fournir un menu de démarrage pour la découverte et le lancement d'applications.

**EF-1.5 :** Le système doit fournir une barre des tâches pour la gestion des applications en cours d'exécution.

**EF-1.6 :** Le système doit supporter la personnalisation de l'arrière-plan du bureau.

### EF-2 : Gestion des fenêtres

**EF-2.1 :** Le système doit supporter les fenêtres 3D (Frame3D) avec un positionnement spatial.

**EF-2.2 :** Le système doit supporter les fenêtres 2D via l'intégration SwingNode.

**EF-2.3 :** Le système doit permettre le déplacement, le redimensionnement et la minimisation des fenêtres.

**EF-2.4 :** Le système doit maintenir l'ordre z des fenêtres pour une occlusion correcte.

**EF-2.5 :** Le système doit supporter la transparence et le mélange des fenêtres.

**EF-2.6 :** Le système doit fournir des décorations de fenêtre (barres de titre, bordures).

### EF-3 : Framework d'application

**EF-3.1 :** Le système doit fournir un SDK pour le développement d'applications 3D.

**EF-3.2 :** Le système doit fournir une boîte à outils de widgets pour les composants d'interface utilisateur.

**EF-3.3 :** Le système doit supporter l'enregistrement et la découverte d'applications.

**EF-3.4 :** Le système doit fournir la gestion du cycle de vie des applications (lancement, terminaison).

**EF-3.5 :** Le système doit supporter la communication inter-applications.

### EF-4 : Gestion des entrées

**EF-4.1 :** Le système doit traiter les entrées de souris pour l'interaction 3D.

**EF-4.2 :** Le système doit traiter les entrées de clavier pour la saisie de texte et les commandes.

**EF-4.3 :** Le système doit supporter la reconnaissance de gestes pour les commandes spatiales.

**EF-4.4 :** Le système doit fournir la distribution d'événements aux applications.

**EF-4.5 :** Le système doit supporter les écouteurs d'événements personnalisés.

### EF-5 : Rendu

**EF-5.1 :** Le système doit rendre des scènes 3D en utilisant Java 3D (Jogamp).

**EF-5.2 :** Le système doit supporter le rendu accéléré par matériel.

**EF-5.3 :** Le système doit maintenir un taux de trames constant (objectif 60 FPS).

**EF-5.4 :** Le système doit supporter les effets d'éclairage et d'ombrage.

**EF-5.5 :** Le système doit supporter le mappage de textures et les matériaux.

**EF-5.6 :** Le système doit supporter la transparence et le mélange alpha.

### EF-6 : Configuration

**EF-6.1 :** Le système doit charger la configuration à partir de fichiers XML.

**EF-6.2 :** Le système doit supporter les modifications de configuration au moment de l'exécution.

**EF-6.3 :** Le système doit persister les préférences utilisateur.

**EF-6.4 :** Le système doit fournir la validation de la configuration.

**EF-6.5 :** Le système doit supporter les configurations spécifiques à la plateforme.

### EF-7 : Gestion des ressources

**EF-7.1 :** Le système doit charger les ressources (images, modèles, sons) à partir du classpath.

**EF-7.2 :** Le système doit mettre en cache les ressources fréquemment utilisées.

**EF-7.3 :** Le système doit supporter le rechargement à chaud des ressources en mode développement.

**EF-7.4 :** Le système doit fournir la localisation des ressources.

### EF-8 : Scripting

**EF-8.1 :** Le système doit fournir un langage de script (Mandela).

**EF-8.2 :** Le système doit supporter l'exécution de scripts depuis le bureau.

**EF-8.3 :** Le système doit fournir un REPL pour le scriptage interactif.

**EF-8.4 :** Le système doit supporter l'intégration de scripts dans les applications.

### EF-9 : Extensions

**EF-9.1 :** Le système doit supporter les extensions d'application via SPI.

**EF-9.2 :** Le système doit fournir un gestionnaire d'extensions.

**EF-9.3 :** Le système doit supporter les permissions et la sécurité des extensions.

**EF-9.4 :** Le système doit permettre le chargement dynamique d'extensions.

## Cas d'utilisation

### CU-1 : Lancer une application

**Acteur :** Utilisateur

**Préconditions :**
- Le bureau est en cours d'exécution
- L'application est enregistrée

**Flux principal :**
1. L'utilisateur ouvre le menu de démarrage
2. L'utilisateur sélectionne une application dans le menu
3. Le système charge la configuration de l'application
4. Le système lance le processus d'application
5. La fenêtre de l'application apparaît sur le bureau

**Postconditions :**
- L'application est en cours d'exécution
- La fenêtre de l'application est visible

### CU-2 : Déplacer une fenêtre

**Acteur :** Utilisateur

**Préconditions :**
- La fenêtre est visible
- La fenêtre n'est pas maximisée

**Flux principal :**
1. L'utilisateur clique sur la barre de titre de la fenêtre
2. L'utilisateur fait glisser la souris vers la nouvelle position
3. Le système met à jour la position de la fenêtre en temps réel
4. L'utilisateur relâche la souris
5. Le système finalise la position de la fenêtre

**Postconditions :**
- La fenêtre est à la nouvelle position
- L'ordre z de la fenêtre est maintenu

### CU-3 : Redimensionner une fenêtre

**Acteur :** Utilisateur

**Préconditions :**
- La fenêtre est visible
- La fenêtre est redimensionnable

**Flux principal :**
1. L'utilisateur positionne le curseur sur le bord de la fenêtre
2. L'utilisateur clique et fait glisser le bord
3. Le système met à jour les dimensions de la fenêtre en temps réel
4. L'utilisateur relâche la souris
5. Le système finalise la taille de la fenêtre

**Postconditions :**
- La fenêtre a de nouvelles dimensions
- Le contenu de la fenêtre est re-rendu

### CU-4 : Basculer le mode de bureau

**Acteur :** Utilisateur

**Préconditions :**
- Le bureau est en cours d'exécution

**Flux principal :**
1. L'utilisateur accède au sélecteur de mode de bureau
2. L'utilisateur sélectionne le mode souhaité (3D ou 2D)
3. Le système effectue la transition vers le nouveau mode
4. Le système réorganise les fenêtres pour le nouveau mode

**Postconditions :**
- Le bureau est dans le mode sélectionné
- Les applications continuent de s'exécuter

### CU-5 : Configurer le bureau

**Acteur :** Utilisateur

**Préconditions :**
- Le bureau est en cours d'exécution
- L'utilisateur a les permissions de configuration

**Flux principal :**
1. L'utilisateur ouvre l'application de paramètres
2. L'utilisateur modifie les valeurs de configuration
3. L'utilisateur enregistre la configuration
4. Le système valide la configuration
5. Le système applique les modifications

**Postconditions :**
- La configuration est persistée
- Les modifications sont appliquées au bureau

### CU-6 : Exécuter un script

**Acteur :** Utilisateur

**Préconditions :**
- Le bureau est en cours d'exécution
- Le langage de script Mandela est disponible

**Flux principal :**
1. L'utilisateur ouvre l'éditeur de script ou le REPL
2. L'utilisateur saisit ou charge un script
3. L'utilisateur exécute le script
4. Le système compile le script en bytecode
5. Le système exécute le bytecode dans la VM
6. Les résultats sont affichés

**Postconditions :**
- L'exécution du script est terminée
- La sortie est disponible pour l'utilisateur

### CU-7 : Installer une extension

**Acteur :** Utilisateur

**Préconditions :**
- Le bureau est en cours d'exécution
- Le JAR de l'extension est disponible

**Flux principal :**
1. L'utilisateur ouvre le gestionnaire d'extensions
2. L'utilisateur sélectionne le JAR de l'extension
3. Le système valide l'extension
4. Le système installe l'extension
5. L'utilisateur accorde les permissions
6. L'extension devient disponible

**Postconditions :**
- L'extension est installée
- L'extension est enregistrée

## Spécifications fonctionnelles

### SF-1 : Serveur d'affichage

Le DisplayServer est le point d'entrée principal de l'environnement de bureau. Il initialise le graphe de scène, le traitement des événements et la boucle de rendu.

**Composants clés :**
- `Main` : Point d'entrée et initialisation
- `DisplayServerControl` : Interface pour le contrôle du serveur
- `DesktopMode` : Gère le basculement des modes 3D/2D
- `EventProcessor` : Gère la distribution des événements d'entrée

**Spécifications :**
- Initialise au démarrage de la JVM
- Charge la configuration à partir de `lgconfig.xml`
- Crée VirtualUniverse pour le rendu 3D
- Démarre le thread de traitement des événements
- Démarre la boucle de rendu à l'objectif de 60 FPS

### SF-2 : Graphe de scène

Le graphe de scène fournit une structure hiérarchique pour les objets 3D.

**Composants clés :**
- `VirtualUniverse` : Racine du graphe de scène
- `Locale` : Conteneur pour les branches du graphe de scène
- `BranchGroup` : Nœuds de branche au moment de la compilation
- `TransformGroup` : Nœuds avec des capacités de transformation
- `Shape3D` : Nœuds feuilles avec géométrie et apparence

**Spécifications :**
- Supporte l'organisation hiérarchique des nœuds
- Fournit des bits de capacité pour le contrôle lecture/écriture
- Supporte les états live/compilé
- Implémente le calcul des limites pour l'élagage
- Supporte le picking et l'intersection

### SF-3 : Boîte à outils de widgets

La boîte à outils de widgets fournit des composants d'interface utilisateur pour les applications 3D.

**Composants clés :**
- `Component3D` : Classe de base pour tous les widgets
- `Container3D` : Conteneur pour les composants enfants
- `Frame3D` : Conteneur de fenêtre
- `SwingNode` : Pont pour les composants Swing 2D
- `GlassyPanel` : Composant de panneau stylisé
- `GlassyText2D` : Composant de rendu de texte

**Spécifications :**
- Fournit un positionnement spatial (translation, rotation, échelle)
- Supporte les écouteurs d'événements pour l'interaction
- Implémente le retour de curseur
- Supporte la transparence et le mélange
- Ponte Swing 2D vers texture 3D

### SF-4 : Système d'événements

Le système d'événements traite les entrées utilisateur et distribue les événements aux applications.

**Composants clés :**
- `EventProcessor` : Boucle principale de traitement des événements
- `EventAdapter` : Classe de base pour les gestionnaires d'événements
- Adaptateurs d'événements : MouseClicked, MouseEntered, KeyPressed, etc.
- `Action` : Interface pour les réponses aux événements

**Spécifications :**
- Traite les événements AWT des périphériques d'entrée
- Distribue les événements aux écouteurs enregistrés
- Supporte le filtrage et la transformation des événements
- Fournit un framework d'action pour les réponses
- S'exécute sur un thread d'événements dédié

### SF-5 : Système de configuration

Le système de configuration gère les paramètres du bureau et des applications.

**Composants clés :**
- `LgConfig` : Chargeur de configuration
- `ConfigControl` : Interface de configuration
- `DefaultConfigControl` : Implémentation par défaut
- Fichiers de configuration XML

**Spécifications :**
- Charge la configuration à partir de fichiers XML
- Supporte l'accès aux propriétés (chaîne, booléen, int, float)
- Fournit la validation des valeurs de configuration
- Supporte les mises à jour de configuration au moment de l'exécution
- Persiste les préférences utilisateur

### SF-6 : Gestion des ressources

Le système de ressources gère le chargement et la mise en cache des assets.

**Composants clés :**
- Chargement de ressources basé sur le classpath
- Mécanisme de mise en cache des ressources
- Support de la localisation des ressources

**Spécifications :**
- Charge les ressources à partir du classpath sous le préfixe `resources/`
- Met en cache les ressources fréquemment utilisées
- Supporte le rechargement à chaud en mode développement
- Fournit la résolution des chemins de ressources

## Analyse des modules

### lg3d-core

**Objectif :** SDK principal et environnement de bureau

**Principaux paquets :**
- `org.jdesktop.lg3d.sg` : Implémentation du graphe de scène
- `org.jdesktop.lg3d.wg` : Boîte à outils de widgets
- `org.jdesktop.lg3d.utils` : Classes utilitaires
- `org.jdesktop.lg3d.displayserver` : Serveur d'affichage
- `org.jdesktop.lg3d.scenemanager` : Gestion de scène

**Dépendances :**
- lg3d-escher (protocole X11)
- Java 3D (Jogamp 1.7.2)
- Jackson (traitement JSON)

**Fonctionnalité :**
- Gestion du graphe de scène
- Boîte à outils de widgets
- Serveur d'affichage
- Traitement des événements
- Gestion de la configuration

### lg3d-apps

**Objectif :** Applications de bureau de production

**Principales applications :**
- Calculator : Calculs mathématiques
- Media Writer : Création de fichiers médias
- Paint : Édition d'images
- File Manager : Navigation du système de fichiers
- Task Manager : Surveillance des processus
- Control Center : Paramètres système
- Help Center : Visionneuse de documentation
- Software Update : Gestion des mises à jour
- Espresso : Éditeur de texte avancé

**Dépendances :**
- lg3d-core
- JavaFX (pour le navigateur web)
- Apache Commons (diverses bibliothèques)
- PDFBox (visionneuse PDF)
- Jakarta Mail (client email)

**Fonctionnalité :**
- Utilitaires quotidiens
- Implémentations de référence
- Intégration du menu de démarrage
- Support double surface 2D/3D

### lg3d-incubator

**Objectif :** Applications expérimentales

**Principales applications :**
- Image Studio : Édition d'images avancée
- Agenda 3D : Calendrier et planification
- Games : Collection de jeux 3D
- Mail 3D : Client email 3D
- jmf23D : Enregistrement et lecture vidéo

**Dépendances :**
- lg3d-core
- lg3d-mandela
- Diverses bibliothèques héritées (bundle)

**Fonctionnalité :**
- Fonctionnalités expérimentales
- Cas d'utilisation avancés
- Ports d'applications héritées

### lg3d-widgets

**Objectif :** Couche de widgets de bureau

**Composants :**
- Widget horloge
- Widget température
- Moniteur CPU
- Moniteur mémoire
- API de widgets

**Dépendances :**
- lg3d-core

**Fonctionnalité :**
- Widgets de bureau
- Système de plugins de widgets
- Gestion de la couche de widgets

### lg3d-mandela

**Objectif :** Langage de script

**Composants :**
- Analyseur lexical
- Analyseur syntaxique
- Compilateur de bytecode
- VM de pile
- Bibliothèque standard
- Moteur JSR-223
- REPL

**Dépendances :**
- Aucune (Java pur)

**Fonctionnalité :**
- Langage de script
- API d'intégration
- Automatisation du bureau
- Scripting d'extensions

## Flux de données

### Flux de lancement d'application

```
Entrée utilisateur → Menu démarrage → Reg. applications → Chargeur config → Chargeur app → Application
                                                                    ↓
                                                            Serveur affichage
```

### Flux de traitement des événements

```
Périphérique entrée → Processeur événements → File événements → Distribution evt → Écouteur → Action → Mise à jour graphe scène → Rendu
```

### Flux de rendu

```
Boucle rendu → Parcours graphe scène → Élagage → Transformation → Éclairage → Rastérisation → Affichage
```

### Flux de configuration

```
Fichier XML → Chargeur config → Validation → Application → Exécution → Persistance
```

## Interface utilisateur

### Bureau 3D

**Caractéristiques :**
- Organisation spatiale des fenêtres
- Manipulation des fenêtres 3D
- Interaction basée sur les gestes
- Organisation basée sur la profondeur

**Composants :**
- Menu de démarrage (panneau 3D)
- Barre des tâches (bande 3D)
- Fenêtres (cadres 3D)
- Arrière-plan (environnement 3D)

### Bureau 2D

**Caractéristiques :**
- Disposition traditionnelle des fenêtres
- MDI (Interface à documents multiples)
- Contrôles de fenêtre standard
- Apparence plate

**Composants :**
- Menu de démarrage (panneau 2D)
- Barre des tâches (bande 2D)
- Fenêtres (cadres 2D)
- Arrière-plan (image 2D)

### Méthodes d'entrée

**Souris :**
- Clic : Sélectionner/activer
- Glisser : Déplacer/redimensionner
- Défilement : Naviguer/zoomer
- Survol : Retour de curseur

**Clavier :**
- Saisie de texte
- Raccourcis clavier
- Séquences de commandes

**Gestes :**
- Reconnaissance de traits
- Multi-tactile (futur)
- Gestes spatiaux (futur)

## Points d'intégration

### Bibliothèques externes

**Java 3D (Jogamp 1.7.2) :**
- Pipeline de rendu 3D
- Implémentation du graphe de scène
- Accélération matérielle

**JavaFX (OpenJFX) :**
- Rendu du navigateur web
- Support multimédia riche
- Composants d'interface modernes

**Jackson :**
- Sérialisation/désérialisation JSON
- Analyse de configuration
- Persistance des données

**Apache Commons :**
- Opérations d'E/S de fichiers
- Compression (support d'archives)
- Opérations réseau

### Intégration native

**X11 (Escher) :**
- Implémentation de protocole X11 pure Java
- Gestion des fenêtres
- Transfert d'entrée

**Futur :**
- Support Wayland
- Intégration de compositeur natif

## Exigences non fonctionnelles

### Performance

**ENF-1 :** Le système doit maintenir un taux de trames minimum de 30 FPS pendant le fonctionnement normal.

**ENF-2 :** Le système doit atteindre un taux de trames cible de 60 FPS sur le matériel répondant aux spécifications minimales.

**ENF-3 :** Le temps de lancement d'application ne doit pas dépasser 3 secondes pour les applications typiques.

**ENF-4 :** L'utilisation de la mémoire ne doit pas dépasser 2 Go pour l'environnement de bureau avec des applications typiques.

### Fiabilité

**ENF-5 :** Le système doit avoir un temps moyen entre pannes (MTBF) d'au moins 48 heures.

**ENF-6 :** Le système doit récupérer gracieusement des plantages d'application sans affecter l'environnement de bureau.

**ENF-7 :** Le système doit fournir une journalisation des erreurs pour le dépannage.

### Évolutivité

**ENF-8 :** Le système doit supporter au moins 20 applications simultanées.

**ENF-9 :** Le système doit supporter au moins 100 fenêtres simultanément.

**ENF-10 :** Le système doit gérer des graphes de scène avec au moins 10 000 nœuds.

### Utilisabilité

**ENF-11 :** Le système doit fournir des contrôles intuitifs pour la manipulation 3D.

**ENF-12 :** Le système doit fournir des raccourcis clavier pour les opérations courantes.

**ENF-13 :** Le système doit fournir un retour visuel pour toutes les interactions utilisateur.

### Sécurité

**ENF-14 :** Le système doit valider toutes les entrées de configuration.

**ENF-15 :** Le système doit implémenter un accès aux extensions basé sur les permissions.

**ENF-16 :** Le système doit isoler les ressources des applications.

### Maintenabilité

**ENF-17 :** Le système doit avoir une architecture modulaire pour une extension facile.

**ENF-18 :** Le système doit fournir une documentation API complète.

**ENF-19 :** Le système doit supporter les tests automatisés.

### Portabilité

**ENF-20 :** Le système doit fonctionner sur les distributions Linux.

**ENF-21 :** Le système doit supporter JDK 21 et versions ultérieures.

**ENF-22 :** Le système doit être indépendant de l'architecture (x86_64, ARM64).

## Annexe

### Glossaire

- **Frame3D :** Conteneur de fenêtre 3D dans le graphe de scène
- **SwingNode :** Composant pont pour l'intégration de panneaux Swing 2D en 3D
- **Graphe de scène :** Structure de données hiérarchique pour les objets 3D
- **VirtualUniverse :** Conteneur racine du graphe de scène
- **Locale :** Conteneur pour les branches du graphe de scène
- **EventProcessor :** Composant qui traite et distribue les événements d'entrée
- **Mandela :** Le langage de script utilisé dans LG3D
- **Jogamp :** La bibliothèque Java 3D maintenue par Jogamp
- **Escher :** Bibliothèque de protocole X11 pure Java

### Références

- [AGENTS.md](../../AGENTS.md) - Directives et conventions du projet
- [lg3d-core/AGENTS.md](../../lg3d-core/AGENTS.md) - Directives du module core
- [lg3d-apps/AGENTS.md](../../lg3d-apps/AGENTS.md) - Directives du module apps
- [README.md](../../README.md) - Aperçu du projet

### Historique des révisions

| Version | Date | Auteur | Modifications |
|---------|------|--------|---------------|
| 1.0 | 2026-10-11 | Équipe documentation | Version initiale |
