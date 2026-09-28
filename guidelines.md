# LG3D Development Guidelines

This document provides essential information for advanced developers working on Project Looking Glass (lg3d).

## 1. Build/Configuration Instructions

### Prerequisites
- **JDK 21**: The project is pinned to JDK 21. Gradle 8.14 is used as the build system and is incompatible with Java 25+.
- **X11 Display**: Development mode requires an active X display (the `DISPLAY` environment variable must be set).

### Key Commands
- **Full Build**: `./gradlew build`
- **Assemble Runtime Resources**: `./gradlew :lg3d-core:runtimeResources`
  - *Note*: Always run this when assets in `lg3d-art`, `lg3d-core/src/resources`, or incubator apps change.
- **Launch Development Desktop**: `./gradlew :lg3d-core:run` or `./run-lg3d.sh`
  - Use `./run-lg3d.sh -b` for 3D background.
  - Use `-Pwindowed` for windowed mode instead of full-screen.

### Build Output
- Gradle uses `build-gradle/` instead of `build/` to avoid conflicts with legacy Ant build scripts.
- Jars are located in `<module>/build-gradle/libs/`.

---

## 2. Testing Information

### Framework
- **JUnit 5** is the primary testing framework.
- **JaCoCo** is integrated for coverage reports (run after `test` tasks). 100% coverage mandatory
- **Pitest** is used for mutation testing. 0 mutations mandatory
- **Cucumber** is used for BDD testing. add scenarios and step definitions as needed and for all new features
- **Mockito** is used for mocking. add mocks as needed and for all new features
- **Karate** is used for API testing. add tests as needed and for all new features

### Running Tests
- **All tests**: `./gradlew test`
- **Specific test class**: `./gradlew :lg3d-core:test --tests "org.jdesktop.lg3d.displayserver.DesktopModeTest"`
- **Headless Mode**: Tests are configured to run headless by default in CI.

### Guidelines for Adding Tests
1. **Location**: Place tests in `<module>/src/test/java`.
2. **Naming**: Use the `Test` suffix (e.g., `MyServiceTest.java`).
3. **Assertions**: Use JUnit 5 assertions (`org.junit.jupiter.api.Assertions`).
4. **Mocking**: Use standard patterns; avoid heavy dependencies if possible.

### Simple Test Example
The following is a basic JUnit 5 test demonstrating the project's testing structure:

```java
package org.jdesktop.lg3d.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SimpleDemoTest {
    @Test
    @DisplayName("Verify basic arithmetic")
    void testAddition() {
        assertEquals(4, 2 + 2);
    }
}
```

---

## 3. Additional Development Information

### Java 3D (Jogamp) Migration
All new code MUST use Jogamp packages instead of legacy Sun packages:
- `org.jogamp.java3d.*` (formerly `javax.media.j3d.*`)
- `org.jogamp.vecmath.*` (formerly `javax.vecmath.*`)

### Code Style & Conventions
- **Encoding**: UTF-8 (except `lg3d-escher` which is ISO-8859-1).
- **Threading**: All Swing UI work must occur on the Event Dispatch Thread (EDT).
- **Commits**: Follow the Conventional Commits specification.
  - Format: `<type>(<scope>): <subject>`
  - Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`.
- **Git**: Do not use `git add -A`. Stage files explicitly to avoid committing runtime artifacts like `lgscreen-*.png`.

### Generated Files
Do not manually edit files in `**/build-gradle/**` or `lg3d-core/build-tools/LgBuildInfo.java`. Edit the template `lg3d-core/build-tools/LgBuildInfo.java` for build info changes.
