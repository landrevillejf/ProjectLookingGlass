# Project Mandela

Project Mandela is the desktop scripting language for Project Looking Glass. It is a small, fast, dynamically typed language with a Java/Kotlin-shaped surface, designed to be embedded in applications rather than to stand alone.

## Overview

Mandela is a complete programming language implementation in a single JAR file, containing:

- **Lexer and parser** - Recursive descent parser with full error recovery
- **AST and bytecode compiler** - Transforms source to executable bytecode
- **Stack VM** - Efficient interpreter for the bytecode
- **Capability-gated runtime** - Security model for sandboxed execution
- **Standard library** - Built-in modules for common tasks
- **JSR-223 ScriptEngine** - Standard Java scripting API integration
- **Embedding API** - Clean facade for Java and Kotlin hosts
- **CLI and REPL** - Command-line tools for development and testing

## Key Features

- **Zero dependencies** - Uses only `java.base` and `javax.script`
- **Lightweight** - Single JAR with no third-party libraries
- **Secure** - Capability-based security model with configurable profiles
- **Embeddable** - Designed to run inside host applications
- **Fast** - Interpreted bytecode with instruction budgets
- **Well-tested** - 366 headless JUnit 5 tests

## Module Structure

```
org.jdesktop.lg3d.mandela
├── api/          # Host-facing facade (Mandela, Interop, EditorServices)
├── cli/          # Command-line interface and REPL
├── code/         # Bytecode compiler and disassembler
├── lang/         # Lexer, parser, AST, diagnostics
├── rt/           # Runtime, capabilities, standard library
├── values/       # Runtime values and operators
└── vm/           # Virtual machine (frame, interpreter)
```

## Documentation

### Module Documentation

- **[Architecture](ARCHITECTURE.md)** - Internal architecture, package structure, and design decisions
- **[Development Guide](DEVELOPMENT.md)** - Contributor guide with build, test, and coding guidelines
- **[Mandela at Work](USAGE.md)** - The language in daily use: one program that walks, scans, classifies and answers, the four hosts that run it, and transcripts of the real tools
- **[Functional Analysis](FUNCTIONAL_ANALYSIS.md)** - Functional requirements, use cases, and specifications
- **[History of the Language](HISTORY.md)** - Why the name, who created it, and the record of how it landed in Project Looking Glass
- **[Roadmap](ROADMAP.md)** - The language's life outside the desktop, probing a running JVM, and what comes next on both version axes

### Language and API Documentation

The complete language and API documentation is located in the parent project:

- **[Language Guide](../../docs/mandela-language.md)** - Complete language reference including syntax, types, operators, standard library, and security model
- **[Embedding API Guide](../../docs/mandela-embedding-api.md)** - How to embed Mandela in Java and Kotlin applications, including JSR-223 integration
- **[AGENTS.md](../AGENTS.md)** - Module-specific guidance for developers working on Mandela

## Quick Start

### Running a Script

```bash
# Using Gradle
./gradlew :lg3d-mandela:mandelaRun -Pscript=hello.mnd

# Using the JAR (after build)
java -jar build-gradle/libs/lg3d-mandela-<version>.jar run hello.mnd

# Using the mandela CLI (if installed)
mandela run hello.mnd
```

### Interactive REPL

```bash
./gradlew :lg3d-mandela:mandelaRepl
```

### Building

```bash
./gradlew :lg3d-mandela:build
```

### Testing

```bash
./gradlew :lg3d-mandela:test
```

## Example Script

```mandela
#!/usr/bin/env mandela

fun greet(name: Str) -> Str { "Hello, ${name}" }

for name in ["world", "desktop"] {
    println(greet(name))
}
```

## Use Cases

Mandela is used throughout Project Looking Glass:

- **Espresso** (text editor) - Run scripts, check syntax, view bytecode
- **Web Browser** - Userscripts for page automation
- **Desktop automation** - File operations, application integration
- **Repository automation** - The project's own CI tools are written in Mandela

## Security Model

Mandela runs with a capability-based security model. Every engine carries a `Capabilities` profile that determines what a script may do:

- **File access** - Confined to a specified root directory
- **Console I/O** - Controlled output channels
- **Environment variables** - Optional access
- **JVM types** - Explicit allow-list only
- **Instruction budgets** - Prevent runaway loops

Pre-configured profiles:
- `open()` - Full access for trusted code
- `console()` - Console only, no files
- `desktop(root)` - File access under root
- `webPage()` - No access for untrusted content

## Dependencies

**None**. Mandela has no third-party dependencies and does not depend on any other Project Looking Glass module. It uses only:
- `java.base` (JDK 21)
- `javax.script` (JSR-223)

## Build Output

- **JAR**: `build-gradle/libs/lg3d-mandela-<version>.jar`
- **Main-Class**: `org.jdesktop.lg3d.mandela.cli.MandelaCli`
- **ScriptEngine**: Registered via `META-INF/services/javax.script.ScriptEngineFactory`

## Version

- Language version: 1.0
- Implementation version: the jar's own — `lg3d-mandela-<version>.jar`, the
  `mandela.version` property, gated by `scripts/mandela/mandela-version.mnd`
- Requires JDK 21

## License

Copyright © 2026 Jean-Francois Landreville (Dr. Land Evil). All rights reserved.
