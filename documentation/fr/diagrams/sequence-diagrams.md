# Project Looking Glass - Diagrammes de séquence

## Séquence de démarrage d'application

```mermaid
sequenceDiagram
    participant User
    participant Main as Main
    participant DS as DisplayServer
    participant DM as DesktopMode
    participant VU as VirtualUniverse
    participant SG as SceneGraph
    participant Render as Renderer

    User->>Main: Exécuter ./gradlew :lg3d-core:run
    Main->>Main: Charger la configuration
    Main->>DS: Initialiser DisplayServer
    DS->>DM: Déterminer DesktopMode (dev/production)
    DM-->>DS: Retourner le mode
    DS->>VU: Créer VirtualUniverse
    VU->>SG: Initialiser le graphe de scène
    SG-->>VU: Graphe de scène prêt
    VU-->>DS: Univers prêt
    DS->>Render: Démarrer la boucle de rendu
    Render->>Render: Commencer le cycle de rendu
    Render->>SG: Parcourir le graphe de scène
    SG-->>Render: Instructions de rendu
    Render->>User: Afficher la sortie
    Render->>Render: Programmer la trame suivante
```

## Séquence de création de fenêtre (Frame 3D)

```mermaid
sequenceDiagram
    participant App as Application
    participant Frame as Frame3D
    participant Container as Container3D
    participant SG as SceneGraph
    participant SM as SceneManager
    participant Render as Renderer

    App->>Frame: new Frame3D(titre, taille)
    Frame->>Container: Créer Container3D
    Container->>SG: Ajouter au graphe de scène
    SG-->>Container: Nœud ajouté
    Frame->>Container: Définir translation/rotation
    Frame->>SM: Enregistrer la fenêtre
    SM-->>Frame: Fenêtre enregistrée
    App->>Frame: addComponent(composant)
    Frame->>Container: addChild(composant)
    Container->>SG: Mettre à jour le graphe de scène
    SG-->>Render: Scène modifiée
    Render->>Render: Re-rendre la scène
    Render-->>User: Affichage mis à jour
```

## Séquence de gestion des événements

```mermaid
sequenceDiagram
    participant User
    participant Input as InputDevice
    participant EP as EventProcessor
    participant Queue as EventQueue
    participant Listener as EventListener
    participant Action as Action
    participant Scene as SceneGraph

    User->>Input: Clic de souris / Appui de touche
    Input->>EP: Événement d'entrée brut
    EP->>EP: Analyser l'événement
    EP->>Queue: Mettre en file l'événement
    Queue->>EP: Retirer de la file
    EP->>Listener: Distribuer aux écouteurs
    Listener->>Listener: Vérifier le type d'événement
    alt Événement correspond
        Listener->>Action: Déclencher l'action
        Action->>Scene: Modifier le graphe de scène
        Scene-->>Action: Mise à jour complète
        Action-->>Listener: Action exécutée
    else Événement ne correspond pas
        Listener-->>EP: Ignorer l'événement
    end
    EP->>EP: Traiter l'événement suivant
```

## Séquence d'intégration SwingNode

```mermaid
sequenceDiagram
    participant App as Application
    participant Frame as Frame3D
    participant SN as SwingNode
    participant Swing as SwingPanel
    participant Image as BufferedImage
    participant Texture as Texture2D
    participant SG as SceneGraph

    App->>Frame: Créer la fenêtre
    Frame->>SN: Créer SwingNode
    SN->>Swing: Créer le panneau Swing
    Swing->>Swing: Initialiser les composants
    Swing-->>SN: Panneau prêt
    SN->>Image: Créer BufferedImage
    SN->>Swing: Peindre le panneau sur l'image
    Swing->>Image: Rendu graphique
    Image-->>SN: Rendu complet
    SN->>Texture: Créer Texture2D
    SN->>Texture: Télécharger les données d'image
    Texture-->>SN: Texture prête
    SN->>SG: Attacher la texture au quad
    SG-->>SN: Attaché à la scène
    SN->>Swing: S'inscrire pour les mises à jour
    Swing->>SN: Composant modifié
    SN->>Image: Re-peindre sur l'image
    SN->>Texture: Mettre à jour la texture
    Texture->>SG: Scène mise à jour
```

## Séquence de lancement d'application

```mermaid
sequenceDiagram
    participant User
    participant Menu as StartMenu
    participant Registry as AppRegistry
    participant Config as ConfigLoader
    participant Loader as AppLoader
    participant App as Application
    participant DS as DisplayServer

    User->>Menu: Cliquer sur l'icône d'application
    Menu->>Registry: Rechercher l'application
    Registry-->>Menu: Descripteur d'application
    Menu->>Config: Charger le fichier .lgcfg
    Config-->>Menu: Configuration chargée
    Menu->>Loader: Lancer l'application
    Loader->>Loader: Analyser la commande
    alt Application Java
        Loader->>App: Charger la classe principale
        App->>App: Initialiser
        App->>DS: Enregistrer avec le serveur d'affichage
        DS-->>App: Enregistrement complet
        App-->>Loader: Application démarrée
    else Application native
        Loader->>DS: Créer un processus natif
        DS-->>Loader: Processus démarré
    end
    Loader-->>Menu: Lancement complet
    Menu-->>User: Application visible
```

## Séquence de parcours du graphe de scène

```mermaid
sequenceDiagram
    participant Render as Renderer
    participant VU as VirtualUniverse
    participant Locale as Locale
    participant BG as BranchGroup
    participant TG as TransformGroup
    participant Shape as Shape3D
    participant Geo as Geometry

    Render->>VU: Commencer la passe de rendu
    VU->>Locale: Obtenir les locales
    Locale-->>VU: Liste des locales
    VU->>BG: Parcourir la branche
    BG->>TG: Visiter l'enfant
    TG->>TG: Appliquer la transformation
    TG->>Shape: Visiter la forme
    Shape->>Geo: Obtenir la géométrie
    Geo-->>Shape: Données de géométrie
    Shape->>Shape: Calculer les limites
    Shape->>Shape: Vérifier la visibilité
    alt Visible
        Shape-->>TG: Instructions de rendu
    else Élagué
        Shape-->>TG: Ignorer
    end
    TG->>TG: Visiter l'enfant suivant
    TG-->>BG: Enfants traités
    BG->>BG: Visiter la branche suivante
    BG-->>VU: Parcours complet
    VU-->>Render: Données de rendu prêtes
```

## Séquence de chargement de ressources

```mermaid
sequenceDiagram
    participant App as Application
    participant RM as ResourceManager
    participant CP as ClassPath
    participant FS as FileSystem
    participant Cache as ResourceCache

    App->>RM: getResource(chemin)
    RM->>Cache: Vérifier le cache
    alt Cache touché
        Cache-->>RM: Retourner la ressource en cache
        RM-->>App: Données de ressource
    else Cache manqué
        RM->>CP: Résoudre depuis le classpath
        CP->>FS: Charger le fichier
        FS-->>CP: Données du fichier
        CP-->>RM: Données de ressource
        RM->>Cache: Stocker dans le cache
        Cache-->>RM: Mis en cache
        RM-->>App: Données de ressource
    end
```

## Séquence de chargement de configuration

```mermaid
sequenceDiagram
    participant Main as Main
    participant LC as LgConfig
    participant FS as FileSystem
    participant XML as XMLParser
    participant DS as DefaultConfigControl
    participant Props as Properties

    Main->>LC: Initialiser la configuration
    LC->>FS: Localiser lgconfig.xml
    FS-->>LC: Chemin du fichier
    LC->>XML: Analyser le fichier XML
    XML->>XML: Lire la configuration
    XML-->>LC: Configuration analysée
    LC->>DS: Appliquer la configuration
    DS->>Props: Charger les propriétés
    Props-->>DS: Valeurs de propriétés
    DS->>DS: Valider les paramètres
    DS-->>LC: Configuration appliquée
    LC-->>Main: Configuration prête
    Main->>Main: Démarrer le serveur d'affichage
```

## Séquence de reconnaissance de gestes

```mermaid
sequenceDiagram
    participant User
    participant Input as InputDevice
    participant GM as GestureModule
    participant Stroke as StrokeClassifier
    participant Action as GestureAction
    participant App as Application

    User->>Input: Geste de glissement de souris
    Input->>GM: Événements de mouvement bruts
    GM->>GM: Enregistrer les points du trait
    User->>Input: Relâcher la souris
    Input->>GM: Mouvement complet
    GM->>Stroke: Classifier le trait
    Stroke->>Stroke: Analyser la géométrie
    Stroke->>Stroke: Faire correspondre le motif de geste
    alt Geste reconnu
        Stroke-->>GM: Type de geste
        GM->>Action: Exécuter l'action de geste
        Action->>App: Déclencher l'action d'application
        App-->>User: Action effectuée
    else Geste non reconnu
        Stroke-->>GM: Geste inconnu
        GM->>GM: Abandonner le trait
    end
```

## Séquence de chargement d'extensions

```mermaid
sequenceDiagram
    participant App as Application
    participant SL as ServiceLoader
    participant Registry as ExtensionRegistry
    participant Ext as Extension
    participant Manifest as ExtensionManifest

    App->>SL: Charger les extensions
    SL->>SL: Scanner META-INF/services
    SL-->>App: Implémentations d'extensions
    App->>Registry: Enregistrer les extensions
    Registry->>Ext: Charger la classe d'extension
    Ext->>Manifest: Obtenir le manifeste
    Manifest-->>Ext: Métadonnées d'extension
    Ext-->>Registry: Informations d'extension
    Registry->>Registry: Valider les permissions
    Registry-->>App: Extensions chargées
    App->>Ext: Initialiser l'extension
    Ext-->>App: Extension prête
```
