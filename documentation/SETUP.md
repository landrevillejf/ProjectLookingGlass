# Documentation Setup Instructions

## What Has Been Created

The following documentation has been created in the `documentation/` directory:

### English Documentation
- **Architecture Diagrams** (`en/diagrams/architecture-overview.md`)
  - High-level system architecture
  - Module dependency diagram
  - Component architecture
  - Data flow diagram
  - Runtime architecture
  - Package structure

- **Sequence Diagrams** (`en/diagrams/sequence-diagrams.md`)
  - Application startup sequence
  - Window creation sequence
  - Event handling sequence
  - SwingNode integration sequence
  - Application launch sequence
  - Scene graph traversal sequence
  - Resource loading sequence
  - Configuration loading sequence
  - Gesture recognition sequence
  - Extension loading sequence

- **Class Diagrams** (`en/diagrams/class-diagrams.md`)
  - Scene graph class hierarchy
  - Widget class hierarchy
  - Display server classes
  - Geometry classes
  - Appearance classes
  - Light classes
  - Event adapter classes
  - Action classes
  - Utility classes
  - Configuration classes

- **Functional Analysis** (`en/functional-analysis/functional-analysis.md`)
  - Comprehensive functional requirements
  - Use cases
  - Functional specifications
  - Module analysis
  - Data flow
  - User interface
  - Integration points
  - Non-functional requirements

### French Documentation
- **Diagrammes d'architecture** (`fr/diagrams/architecture-overview.md`)
- **Diagrammes de séquence** (`fr/diagrams/sequence-diagrams.md`)
- **Diagrammes de classes** (`fr/diagrams/class-diagrams.md`)
- **Analyse fonctionnelle** (`fr/functional-analysis/functional-analysis.md`)

### Additional Files
- **README.md** - Documentation index with links to all documents
- **index.html** - Interactive web interface for browsing documentation
- **GitHub Pages Workflow** (`.github/workflows/deploy-docs.yml`) - Automated deployment

## Next Steps

### 1. Generate Javadoc

To generate the API documentation (Javadoc), run:

```bash
# Generate Javadoc for all modules
./gradlew javadoc

# Or for specific modules
./gradlew :lg3d-core:javadoc
./gradlew :lg3d-apps:javadoc
```

The Gradle toolchain automatically handles the Java version requirement (Java 21).

After generating Javadoc, copy the generated documentation to the documentation directory:

```bash
mkdir -p documentation/en/api documentation/fr/api
cp -r lg3d-core/build-gradle/docs/javadoc documentation/en/api/core
cp -r lg3d-apps/build-gradle/docs/javadoc documentation/en/api/apps
cp -r lg3d-incubator/build-gradle/docs/javadoc documentation/en/api/incubator
cp -r lg3d-mandela/build-gradle/docs/javadoc documentation/en/api/mandela
cp -r lg3d-widgets/build-gradle/docs/javadoc documentation/en/api/widgets
cp -r documentation/en/api/* documentation/fr/api/
```

**Status:** ✅ Javadoc has been generated and copied to both English and French API directories.

### 2. Enable GitHub Pages

To publish the documentation to GitHub Pages:

1. Go to your repository on GitHub
2. Click **Settings** → **Pages**
3. Under **Build and deployment**, select **GitHub Actions** as the source
4. Click **Save**

### 3. Push Changes

Commit and push the documentation to trigger the GitHub Pages workflow:

```bash
git add documentation/
git add .github/workflows/deploy-docs.yml
git commit -m "docs: add comprehensive documentation (EN/FR)"
git push origin main
```

The workflow will automatically deploy the documentation to GitHub Pages.

### 4. Access the Documentation

After the workflow completes, your documentation will be available at:
```
https://<your-username>.github.io/ProjectLookingGlass/
```

## Viewing Diagrams

The diagrams are written in Mermaid format. They will render automatically on:
- GitHub (when viewing markdown files)
- GitHub Pages (when published)
- VS Code (with Mermaid Preview extension)
- Mermaid Live Editor (https://mermaid.live/)

## Updating Documentation

When updating documentation:
1. Maintain both English and French versions
2. Update the README.md if adding new documents
3. Test the GitHub Pages workflow locally if needed
4. Commit changes with the `docs:` prefix

## Structure Overview

```
documentation/
├── README.md                    # Main documentation index
├── index.html                   # Interactive web interface
├── SETUP.md                     # This file
├── en/                          # English documentation
│   ├── diagrams/
│   │   ├── architecture-overview.md
│   │   ├── sequence-diagrams.md
│   │   └── class-diagrams.md
│   ├── functional-analysis/
│   │   └── functional-analysis.md
│   └── api/                      # Javadoc (to be added)
└── fr/                          # French documentation
    ├── diagrams/
    │   ├── architecture-overview.md
    │   ├── sequence-diagrams.md
    │   └── class-diagrams.md
    ├── functional-analysis/
    │   └── functional-analysis.md
    └── api/                      # Javadoc (to be added)
```

## Troubleshooting

### GitHub Pages Workflow Not Running

- Ensure GitHub Pages is enabled in repository settings
- Check that the workflow file is in `.github/workflows/deploy-docs.yml`
- Verify the workflow has the necessary permissions

### Diagrams Not Rendering

- Ensure you're viewing on GitHub or GitHub Pages
- Install the Mermaid Preview extension for VS Code
- Use Mermaid Live Editor for standalone viewing

### Javadoc Generation Fails

- Ensure Java 21 is installed and accessible
- Check that the project builds successfully: `./gradlew build`
- Verify the source files have proper Javadoc comments

## Additional Resources

- [Mermaid Documentation](https://mermaid.js.org/)
- [GitHub Pages Documentation](https://docs.github.com/en/pages)
- [Javadoc Tool Documentation](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/javadoc.html)
