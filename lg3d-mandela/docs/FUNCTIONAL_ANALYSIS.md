# Functional Analysis - Project Mandela

This document provides a functional analysis of Project Mandela, including functional requirements, use cases, and specifications.

## Purpose

Project Mandela is a scripting language designed for the Project Looking Glass desktop environment. Its primary purpose is to provide a secure, lightweight, and embeddable scripting solution for desktop automation, application integration, and user scripting.

## Target Users

### Primary Users

1. **Desktop Users** - End users who write scripts to automate tasks within the Looking Glass desktop
2. **Application Developers** - Developers who embed Mandela in their applications (e.g., Espresso text editor, Web Browser)
3. **System Integrators** - Users who integrate desktop applications through scripting

### Secondary Users

1. **Language Developers** - Contributors who maintain and extend the language implementation
2. **Test Engineers** - QA personnel who verify language correctness and security

## Functional Requirements

### FR1: Language Execution

**FR1.1** The system shall parse Mandela source code (`.mnd` files) and execute it.

**FR1.2** The system shall support interactive execution through a REPL (Read-Eval-Print Loop).

**FR1.3** The system shall support batch execution of scripts from the command line.

**FR1.4** The system shall compile source code to bytecode and execute it via a stack-based virtual machine.

**FR1.5** The system shall provide syntax checking without execution.

### FR2: Language Features

**FR2.1** The system shall support primitive types: Int, Double, Bool, Str, Null.

**FR2.2** The system shall support collection types: List, Map, Range.

**FR2.3** The system shall support function definitions and lambdas.

**FR2.4** The system shall support control flow: if/else, while, for, loop, break, continue.

**FR2.5** The system shall support pattern matching with match expressions.

**FR2.6** The system shall support error handling with try/catch/finally and defer.

**FR2.7** The system shall support user-defined types: type (records), class, enum.

**FR2.8** The system shall support modules via import and use statements.

**FR2.9** The system shall support string interpolation.

**FR2.10** The system shall support operators: arithmetic, logical, comparison, pipeline.

### FR3: Standard Library

**FR3.1** The system shall provide a standard library module for JSON operations (`std.json`).

**FR3.2** The system shall provide a standard library module for mathematical operations (`std.math`).

**FR3.3** The system shall provide a standard library module for filesystem operations (`std.fs`).

**FR3.4** The system shall provide a standard library module for environment access (`std.env`).

**FR3.5** The system shall provide a standard library module for time operations (`std.time`).

**FR3.6** The system shall provide global functions: print, println, eprintln, str, int, double, bool, length, typeOf, min, max, abs, error, assert, keys, valuesOf.

**FR3.7** The system shall provide value member methods for Str, List, Map, Range, Int, and Double.

### FR4: Security Model

**FR4.1** The system shall enforce capability-based security for all operations.

**FR4.2** The system shall support the following grants: STDOUT, STDERR, STDIN, FILE_READ, FILE_WRITE, ENV, NETWORK, JVM, PROCESS, THREAD.

**FR4.3** The system shall provide pre-configured capability profiles: open, console, desktop(root), webPage.

**FR4.4** The system shall confine filesystem operations to a specified root directory.

**FR4.5** The system shall enforce instruction budgets to prevent runaway loops.

**FR4.6** The system shall enforce depth limits to prevent stack overflow.

**FR4.7** The system shall raise PermissionError when a capability is not granted.

**FR4.8** The system shall raise LimitError when budgets are exceeded.

**FR4.9** The system shall allow JVM type access only via explicit allow-list.

### FR5: Embedding API

**FR5.1** The system shall provide a Java API for embedding the language in applications.

**FR5.2** The system shall support binding of host values and functions to script globals.

**FR5.3** The system shall support conversion between Java/Kotlin types and Mandela types.

**FR5.4** The system shall provide a builder pattern for engine configuration.

**FR5.5** The system shall support JSR-223 ScriptEngine interface.

**FR5.6** The system shall provide editor services: keywords, completion, outline, diagnostics.

**FR5.7** The system shall allow hosts to redirect console output.

**FR5.8** The system shall allow hosts to configure the import root.

### FR6: Command-Line Interface

**FR6.1** The system shall provide a `mandela` command-line tool.

**FR6.2** The system shall support commands: run, check, eval, repl, dump, about, help.

**FR6.3** The system shall support sandbox options for file access confinement.

**FR6.4** The system shall support trace mode for debugging.

**FR6.5** The system shall return appropriate exit codes: 0 (success), 1 (usage error), 2 (program failed), 3 (file unreadable), 4 (check findings).

### FR7: Error Handling

**FR7.1** The system shall provide compile-time diagnostics with source location.

**FR7.2** The system shall provide runtime errors with kind and message.

**FR7.3** The system shall support error kinds: TypeError, ValueError, KeyError, IndexError, RangeError, NullError, IOError, JsonError, SyntaxError, MatchError, AssertError, PermissionError, LimitError, RuntimeError.

**FR7.4** The system shall allow scripts to catch and handle errors.

**FR7.5** The system shall provide defer statements for cleanup.

### FR8: Performance

**FR8.1** The system shall execute scripts without requiring JIT compilation.

**FR8.2** The system shall support configurable instruction budgets (default: 200 million).

**FR8.3** The system shall support configurable depth limits (default: 1024 frames).

**FR8.4** The system shall support compile-once, run-many execution model.

### FR9: Dependencies

**FR9.1** The system shall have no third-party dependencies.

**FR9.2** The system shall depend only on java.base and javax.script.

**FR9.3** The system shall not depend on AWT, Swing, or Java 3D.

**FR9.4** The system shall not depend on other Project Looking Glass modules.

### FR10: Testing

**FR10.1** The system shall have comprehensive test coverage.

**FR10.2** The system shall test parser behavior and error messages.

**FR10.3** The system shall test end-to-end language semantics.

**FR10.4** The system shall test every standard library function.

**FR10.5** The system shall test the embedding API contract.

**FR10.6** The system shall test value conversion.

**FR10.7** The system shall test JSR-223 integration.

**FR10.8** The system shall test editor services.

**FR10.9** The system shall test the command-line interface.

**FR10.10** The system shall test language guide code blocks.

**FR10.11** The system shall test repository automation scripts.

## Use Cases

### UC1: Desktop Automation

**Actor**: Desktop User

**Description**: A user writes a script to automate a repetitive task in the Looking Glass desktop.

**Preconditions**: User has Looking Glass desktop installed.

**Main Flow**:
1. User creates a `.mnd` script file.
2. User writes automation logic using Mandela syntax.
3. User runs the script via the desktop's script runner.
4. Script executes with appropriate capabilities (e.g., filesystem access).
5. Script performs the automated task.
6. User views output or results.

**Postconditions**: Task is completed automatically.

**Alternative Flows**:
- Script has syntax error: User receives diagnostic with location.
- Script lacks capability: User receives PermissionError with required grant.
- Script exceeds budget: User receives LimitError with exceeded limit.

### UC2: Text Editor Scripting

**Actor**: Application Developer (Espresso)

**Description**: The Espresso text editor integrates Mandela to allow users to run scripts within the editor.

**Preconditions**: Espresso is running with Mandela extension enabled.

**Main Flow**:
1. User opens a `.mnd` file in Espresso.
2. Editor provides syntax highlighting using EditorServices.
3. User types code and receives real-time diagnostics.
4. User requests code completion.
5. Editor provides completions via EditorServices.
6. User runs the script via keyboard shortcut.
7. Editor creates a Runtime with console profile.
8. Script executes and output appears in editor panel.
9. User views results and any errors.

**Postconditions**: Script execution results are displayed in editor.

**Alternative Flows**:
- Syntax error: Editor shows diagnostic in gutter.
- Runtime error: Editor shows error with stack trace.
- User requests bytecode: Editor shows disassembled instructions.

### UC3: Web Browser Userscripts

**Actor**: Web Browser User

**Description**: A user writes a userscript for the Web Browser that interacts with web pages.

**Preconditions**: Web Browser is running with Mandela extension enabled.

**Main Flow**:
1. User creates a userscript in `~/.lg3d/webbrowser/userscripts/`.
2. User writes script using page API (page.url, page.title, inject, note).
3. User navigates to a web page.
4. Browser loads the userscript.
5. Browser creates Runtime with webPage profile (no file access, no console).
6. Script executes with page context.
7. Script injects JavaScript or logs notes.
8. User sees script effects on the page.

**Postconditions**: Userscript modifies page behavior.

**Alternative Flows**:
- Script tries file access: PermissionError raised.
- Script tries console output: PermissionError raised (unless host binds console functions).
- Script exceeds budget: LimitError raised, script terminated.

### UC4: Repository Automation

**Actor**: CI/CD System

**Description**: The project's own automation scripts (written in Mandela) audit the repository.

**Preconditions**: Repository is checked out, Mandela is built.

**Main Flow**:
1. CI triggers repository checks.
2. Gradle runs `:lg3d-mandela:repositoryChecks`.
3. Each automation script runs with repository root as sandbox.
4. Script walks the file tree.
5. Script performs checks (e.g., documentation links, version consistency).
6. Script reports findings via error() or exit codes.
7. CI fails if any script reports a blocking finding.

**Postconditions**: Repository is validated, CI passes or fails accordingly.

**Alternative Flows**:
- Script finds broken link: Script reports with error(), CI fails.
- Script finds version mismatch: Script reports with error(), CI fails.
- Script completes successfully: CI passes.

### UC5: Application Embedding

**Actor**: Application Developer

**Description**: A developer embeds Mandela in a custom application to provide scripting capabilities.

**Preconditions**: Developer has a Java/Kotlin application.

**Main Flow**:
1. Developer adds lg3d-mandela dependency to build file.
2. Developer imports `org.jdesktop.lg3d.mandela.api.Mandela`.
3. Developer creates a Runtime with appropriate capabilities.
4. Developer binds application-specific functions and values.
5. Developer loads and runs user scripts.
6. Scripts interact with application via bound functions.
7. Application handles script results and errors.

**Postconditions**: Application has scripting capabilities.

**Alternative Flows**:
- Script tries unauthorized operation: PermissionError raised.
- Script error occurs: Application catches MandelaError and displays to user.
- Type conversion fails: Interop refuses conversion with clear message.

## Functional Specifications

### Language Syntax

The Mandela language syntax is specified in detail in the [Language Guide](../../docs/mandela-language.md). Key specifications include:

- **Lexical rules**: Comments, strings, numbers, identifiers, reserved words
- **Types**: Int, Double, Bool, Str, List, Map, Range, Null, Fun, Class, Instance, Error
- **Operators**: Precedence levels from pipeline to access
- **Statements**: Binding, functions, control flow, match, errors
- **Data structures**: List and map operations
- **User-defined types**: type, class, enum
- **Modules**: import and use

### Standard Library

The standard library is organized into modules:

- **Globals**: Available without import
- **std.json**: JSON parsing and encoding
- **std.math**: Mathematical functions and constants
- **std.fs**: Filesystem operations (requires FILE_READ/FILE_WRITE)
- **std.env**: Environment access (requires ENV)
- **std.time**: Time operations (sleep requires THREAD)

Each module is specified in the [Language Guide](../../docs/mandela-language.md) §9.

### Security Model

The security model is capability-based:

- **Grants**: Fine-grained permissions for operations
- **Profiles**: Pre-configured grant sets for common scenarios
- **Budgets**: Instruction and depth limits to prevent resource exhaustion
- **Confinement**: Filesystem operations confined to a root directory
- **JVM access**: Explicit allow-list only, no reflection

Detailed specification in [Language Guide](../../docs/mandela-language.md) §10.

### Embedding API

The embedding API provides:

- **Mandela facade**: Static methods for common operations
- **Runtime.Builder**: Engine configuration
- **Runtime**: Script execution
- **Interop**: Value conversion
- **EditorServices**: Editor integration
- **MandelaScriptEngine**: JSR-223 implementation

Detailed specification in [Embedding API Guide](../../docs/mandela-embedding-api.md).

### Command-Line Interface

The CLI provides commands:

- **run**: Execute a script file
- **check**: Compile without executing
- **eval**: Execute a program from command line
- **repl**: Interactive session
- **dump**: Print bytecode
- **about**: Version information
- **help**: Usage information

Exit codes are part of the contract with scripts and CI.

## Non-Functional Requirements

### NFR1: Performance

- Scripts shall execute without JIT compilation
- Instruction budgets shall be configurable
- Depth limits shall be configurable
- Compile-once, run-many model shall be supported

### NFR2: Security

- All side effects shall go through capability checks
- Filesystem operations shall be confined
- JVM access shall be explicit allow-list only
- No reflection-based binding of arbitrary objects

### NFR3: Reliability

- Comprehensive test coverage (366 tests)
- Language guide code blocks shall be tested
- Repository automation scripts shall be tested
- Error messages shall be clear and actionable

### NFR4: Maintainability

- Zero third-party dependencies
- Clear package structure with dependency rules
- Comprehensive documentation
- Tested code blocks in documentation

### NFR5: Portability

- No AWT, Swing, or Java 3D dependencies
- Can run headless
- Can embed in any JVM application
- JSR-223 standard interface

### NFR6: Usability

- Java/Kotlin-shaped syntax for familiarity
- Clear error messages with source locations
- Interactive REPL for experimentation
- Editor services for IDE integration

## Constraints

### C1: Dependencies

- Must have zero third-party dependencies
- Must depend only on java.base and javax.script
- Must not depend on other Project Looking Glass modules

### C2: JDK Version

- Must compile and run on JDK 21
- Gradle toolchain pinned to JDK 21

### C3: Security

- Must maintain capability-based security model
- Must not allow reflection-based access to arbitrary objects
- Must enforce budgets to prevent resource exhaustion

### C4: Compatibility

- Language version 1.0 must be stable within 1.x
- Public API must remain stable within 1.x
- Contextual keywords must only be added where parser previously failed

## Assumptions

### A1: Host Environment

- Host applications provide a JVM (Java 21 or compatible)
- Host applications can redirect console output
- Host applications can provide file roots for sandboxing

### A2: User Knowledge

- Script authors have basic programming knowledge
- Script authors understand Java/Kotlin syntax
- Embedding developers understand Java/Kotlin

### A3: Use Context

- Scripts are primarily for desktop automation
- Scripts are not for high-performance computing
- Scripts are not for system-level operations

## Risks

### R1: Security

- **Risk**: Capability model may be too restrictive for some use cases
- **Mitigation**: Provide flexible profiles and allow custom capability configuration

### R2: Performance

- **Risk**: Interpreted execution may be too slow for some tasks
- **Mitigation**: Document performance characteristics, recommend heavy computation in host

### R3: Adoption

- **Risk**: Users may prefer existing languages (Python, JavaScript)
- **Mitigation**: Emphasize security, zero dependencies, and desktop integration

### R4: Maintenance

- **Risk**: Language evolution may break existing scripts
- **Mitigation**: Maintain language version stability, use contextual keywords

## Success Criteria

- **SC1**: All functional requirements are implemented
- **SC2**: All use cases are supported
- **SC3**: Test coverage meets or exceeds 90%
- **SC4**: Zero surviving mutants in PIT mutation testing
- **SC5**: Language guide code blocks all compile
- **SC6**: Repository automation scripts all pass
- **SC7**: No third-party dependencies
- **SC8**: Security model is enforced for all operations
- **SC9**: Embedding API is stable and well-documented
- **SC10**: CLI exit codes are consistent and documented

## References

- [Language Guide](../../docs/mandela-language.md) - Complete language reference
- [Embedding API Guide](../../docs/mandela-embedding-api.md) - Host integration
- [Architecture](ARCHITECTURE.md) - Internal architecture
- [Development Guide](DEVELOPMENT.md) - Contributor guide
- [AGENTS.md](../AGENTS.md) - Module-specific guidance
