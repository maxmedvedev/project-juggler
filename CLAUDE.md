# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build              # Build all modules
./gradlew test               # Run all tests
./gradlew :plugin:compileKotlin  # Quick compile check for plugin
./gradlew :cli:homebrewDist  # Create Homebrew distribution tarball
./gradlew :cli:homebrewChecksum  # Generate SHA256 for Homebrew formula
./gradlew :plugin:buildPlugin    # Build IntelliJ plugin
```

Run a single test class:
```bash
./gradlew :core:test --tests "com.projectjuggler.core.ProjectManagerTest"
```

## Architecture

**Three-module Gradle project:**

- **core/** - Pure business logic with no UI dependencies
  - `config/` - Configuration persistence (ConfigRepository, GlobalConfig, ProjectMetadata)
  - `core/` - Main logic (ProjectManager, ProjectLauncher, ProjectCleaner, VMOptionsGenerator)
  - `platform/` - OS-specific code (IntelliJLocator, ConfigLocator, ProcessLauncher)
  - `util/` - Utilities (GitUtils, DirectoryCopier, PathUtils)

- **cli/** - Command-line interface
  - `framework/` - Custom lightweight CLI framework (~300 lines, no external deps)
  - Commands: OpenCommand, MainCommand, SyncCommand, ConfigCommand, etc.
  - Entry point: `Main.kt`

- **plugin/** - IntelliJ IDEA plugin (targets 2025.1+)
  - `actions/recent/` - RecentProjectsPopup with custom renderer and submenu actions
  - `services/` - MainProjectService, ShutdownSignalService, AutoConfigPopulator
  - `util/` - IdeJuggler (project launching), BundledCliManager
  - Bundles CLI distribution in resources for self-shutdown sync operations

**Key patterns:**
- Constructor-based dependency injection (no DI framework)
- ConfigRepository is the central state manager (JSON persistence with file locking)
- ProjectPath value class wraps file paths with stable hash-based ProjectId generation
- Sealed classes for type-safe action/item hierarchies (ProjectAction, PopupListItem, SyncType)

**Data storage:** `~/.project-juggler/` contains config.json and per-project isolated directories.

## Testing

Uses Kotest + MockK. Tests are in `src/test/kotlin/` mirroring main structure. Core module has extensive integration tests for ProjectManager and ConfigRepository.

## Development Guidelines

- **Always deduplicate code** - Extract shared logic into reusable functions or classes
- **Actions must not contain business logic** - Keep action/command classes thin; implement business logic in separate service objects (e.g., MainProjectService, IdeJuggler)
- **Always use planning mode** - Work on all tasks in planning mode first before implementing

## MCP

**NEVER use these tools:** `Grep`, `Glob`, `Read`, `Edit`, `Write`, `Task(Explore)`.
**ALWAYS use JetBrains MCP equivalents instead.**

