# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build                  # Build all modules
./gradlew test                   # Run all tests (only core has tests)
./gradlew :core:test             # Core tests only
./gradlew :plugin:compileKotlin  # Quick compile check for plugin
./gradlew :plugin:buildPlugin    # Build IntelliJ plugin zip
./gradlew :plugin:runIde         # Run sandbox IDE (uses ~/.project-juggler-test as base dir)
./gradlew :plugin:verifyPlugin   # Plugin Verifier against recommended IDEs
./gradlew :core:koverHtmlReport  # Core coverage report (core/build/reports/kover/html)
```

Run a single test class or test:
```bash
./gradlew :core:test --tests "com.projectjuggler.core.ProjectManagerTest"
./gradlew :core:test --tests "com.projectjuggler.core.ProjectManagerTest.should expand tilde in path strings"
```

Gradle is pinned to a JDK 17 home in `gradle.properties` (`org.gradle.java.home`) because Kotlin 2.1.0 cannot parse newer Java version strings. If the build fails with a JDK error on another machine, that path is the first thing to check. Plugin version lives in `plugin/build.gradle.kts` (`version = ...`); `sinceBuild` is 251 (2025.1+).

The `.github/workflows/release.yml` references a `:cli` module that no longer exists; it is stale.

## Architecture

**Three-module Gradle project** (`core` -> `sync-helper` and `plugin` both depend on core):

- **core/** - Pure Kotlin business logic, no IntelliJ dependencies. Packages: `config/` (persistence and models), `core/` (ProjectManager, DirectoryManager, VMOptionsGenerator, ShutdownSignalManager, PortAllocator), `process/` (ProjectLauncher, IntelliJLauncher, ProcessLauncher, WindowFocuser), `locators/` (IntelliJLocator, ConfigLocator, PluginLocator), `di/` (Koin setup), `util/`.
- **sync-helper/** - Standalone CLI (`SyncHelperMain.kt`) that runs `ProjectLauncher.syncProject()` out of process. Built with the `application` plugin; its `installDist` output is bundled into plugin resources under `sync-helper/` by the plugin's `processResources` task.
- **plugin/** - IntelliJ plugin. `actions/` are thin entry points, `actions/recent/` is the Recent Projects popup (builder, renderer, sub-actions, `SyncType` sealed class), `services/` holds the logic (IdeInstallationService, LaunchProject, SetMainProject, etc.), `shutdown/` is the signal-polling service, `util/BundledSyncHelper` extracts and runs the bundled CLI.

### Multi-IDE data model (v2)

Everything is keyed by **IDE installation**, not globally. `IdeInstallation(executablePath, displayName)` derives a hash-based `directoryName`; `IdeRegistry` discovers installations and hands out one `IdeConfigRepository` per installation rooted at `~/.project-juggler/v2/<ide-dir>/`. That directory holds `config.json` (`IdeConfig`: base VM options/config/plugins paths, main project, sync-on-update flag), `recent.json`, and `projects/<project-id>/` with `metadata.json` plus the isolated `config`/`system`/`logs`/`plugins` dirs. All JSON writes go through `FileLockUtils.withFileLock`.

The base dir can be overridden with the `project.juggler.base.dir` system property (used by `runIde`).

### Dependency injection (Koin, scoped per IDE)

- `KoinInit.init(vararg modules)` starts Koin once; the plugin passes `pluginModule`, sync-helper passes nothing. `coreModule` holds true singletons (`IdeRegistry`, `ProcessLauncher`).
- `ideScopedModule` declares everything else inside the `IDE_SCOPE` Koin scope. `IdeScopeManager` creates one scope per `IdeConfigRepository` and `declare`s the repository into it, so scoped services receive the right repository via `get()`.
- Services expose `getInstance(ideConfigRepository)` companions that resolve through `ideConfigRepository.getScope().get()`. Constructors are `internal`; always go through `getInstance`. Default-argument constructors (e.g. `ProjectLauncher`) exist so tests can inject mocks.
- In the plugin, `IdeInstallationService.currentIdeConfigRepository` is the repository for the running IDE (detected from `PathManager`), and auto-populates base paths on first use.

### Project identity

`ProjectPath` is an inline value class. `ProjectIdGenerator` builds `ProjectId` as `<raw file name>-<16 hex of SHA-256(path)>`; this id is the on-disk directory name, so it must never change for existing projects. `ProjectPath.name` is the display name and deliberately differs for file-based projects (`repo [MODULE.bazel]`). Keep id and display-name logic separate.

### Launch and sync flow

- **Launch**: `ProjectLauncher.launch` checks `MainProjectService.isMainProject` (main project opens with base settings via `launchMain`); otherwise registers metadata, records in `RecentProjectsIndex`, and `IntelliJLauncher` starts the IDE with `idea.config.path`/`system`/`log`/`plugins` pointing at the isolated dirs and a per-project debug port (`PortAllocator`). The plugin wraps this in `launchOrFocusProject` (`actions/Launch.kt`), which may prompt to set a main project and focuses an existing window if already open.
- **Sync of another project**: `ProjectLauncher.syncProject` takes a project lock (`ProjectLockUtils`), and if the target IDE is running and `SyncOptions.stopIfRunning` is set, writes `stop-request.json` via `ShutdownSignalManager`, waits with `ShutdownWaiter`, syncs, then relaunches if `autoRestart`.
- **Sync of the current IDE**: the plugin cannot sync itself while running, so `actions/Sync.kt` spawns the bundled `sync-helper` process (`--ide <path> --path <project>|--all-projects --all|--vmoptions|--config|--plugins`), which performs the above flow from outside.
- **Shutdown side**: `ShutdownSignalService` (app-level, started by a `postStartupActivity`) detects it is an isolated instance by parsing `idea.config.path` for `projects/<id>/config`, polls `stop-request.json` once per second, and exits the IDE gracefully when one appears.
- `PluginSyncOnUpdateActivity` re-syncs plugins on IDE build-number change when `syncPluginsOnIdeUpdate` is enabled.

## Testing

Kotest `StringSpec` + MockK, only in `core/`. Register `KoinTestExtension()` in any spec that calls `getInstance(...)`; it starts Koin per spec and closes IDE scopes after each test. Build repositories directly with `IdeConfigRepository(tempDir, IdeInstallation("/test/ide", "Test IDE"))`; `TempDirExtension` is available for temp dirs.
Locators are tested through `LocatorEnvironment` with fake IDE trees in temp dirs, not against the real machine.

## Development Guidelines

- **Always deduplicate code** - Extract shared logic into reusable functions or classes
- **Actions must not contain business logic** - Keep action classes thin; implement logic in `plugin/services` or core (e.g. `IdeInstallationService`, `launchProject`, `setMainProject`)
- **Always use planning mode** - Work on all tasks in planning mode first before implementing
- When doing try-catch, always catch `Throwable` and log it. If the exception is `ControlFlowException`, rethrow it without handling.
- User-visible strings go in `messages/ProjectJugglerBundle.properties` via `ProjectJugglerBundle.message(...)`.

## MCP

**NEVER use these tools:** `Grep`, `Glob`, `Read`, `Edit`, `Write`, `Task(Explore)`.
**ALWAYS use JetBrains MCP equivalents instead.**
