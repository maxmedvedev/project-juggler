# Core module behavioral specification

This document describes what the `core` module does today, as observed from the code. It is the contract that `plugin` and `sync-helper` depend on. Where current behavior is questionable, it is still described as-is and listed under [Known gaps](#known-gaps) rather than silently idealized.

Conventions:

- `<base>` is the project-juggler root. Default `~/.project-juggler`; overridable with the system property `project.juggler.base.dir`.
- `<ideDir>` is `<base>/v2/<IdeInstallation.directoryName>`, the root for one IDE installation.
- `<projRoot>` is `<ideDir>/projects/<ProjectId>`.
- "The IDE" means the IntelliJ installation a repository belongs to. "Base" means that IDE's normal, non-isolated config/plugins/vmoptions.

## 1. Scope and dependencies

`core` is pure Kotlin/JVM with no IntelliJ Platform dependency. Runtime dependencies are kotlinx.serialization (JSON) and Koin (DI). It targets JDK 17 and must run both inside an IDE process (plugin) and as a standalone CLI (sync-helper).

Core never shows UI. It reports through return values, exceptions, the `NotificationHandler` interface (implemented by callers; unused within core), and `SyncOptions.onProgress` callbacks. A few classes print status lines to stdout/stderr (`IntelliJLauncher`, `ProcessLauncherImpl`).

## 2. Identity model

### 2.1 IDE installation

`IdeInstallation(executablePath: String, displayName: String)` identifies an IDE by its executable path; the display name is cosmetic.

| Derived field   | Rule                                                                 |
|-----------------|----------------------------------------------------------------------|
| `id`            | first 16 hex chars of SHA-256(`executablePath`)                      |
| `directoryName` | `<sanitizedName>-<first 8 chars of id>`, where sanitizedName replaces spaces with `-` and drops anything outside `[a-zA-Z0-9-.]` |

Both derived fields are `@Transient`; only the two constructor fields are serialized.

### 2.2 Project path and id

`ProjectPath(pathString)` is an inline value class and serializes as a bare string. A project may be a directory or a file inside a directory (file-based project, e.g. `MODULE.bazel`).

- `fileName`: raw last path segment.
- `name` (display): the enclosing directory name; for file-based projects `"<dir> [<file>]"`. The directory is `PathUtils.projectRoot(path)`: the path itself if it is an existing directory, else its parent. A non-existent path is treated as a file.
- `id`: `ProjectId("<fileName>-<first 16 hex of SHA-256(pathString)>")`.

Invariant: the id is the on-disk directory name for the project's isolated state. It must stay stable for existing paths. Display-name rules may change; id rules may not.

`ProjectManager.resolvePath(string)` expands a leading `~` (Unix only, exactly the first segment `~`), makes the path absolute and normalizes it. It does not check existence.

## 3. On-disk layout

```
<base>/
  v2/
    <ideDir>/
      config.json                           # IdeConfig
      recent.json                           # RecentProjectsIndex
      projects/
        <ProjectId>/
          metadata.json                     # ProjectMetadata
          idea.vmoptions                    # generated, see §6
          config/  system/  logs/  plugins/ # isolated IDE dirs
          signals/
            stop-request.json               # §8
            sync-in-progress.lock           # permanent, §8
```

All JSON is pretty-printed with `encodeDefaults = false`. `config.json`, `metadata.json` and `recent.json` are read and written under an exclusive OS file lock (`FileLockUtils.withFileLock`, blocking `FileChannel.lock()`, creates parent dirs and the file if missing, tolerating a concurrent creation). When both are needed, `config.json` is read before the `recent.json` lock is taken; locks are never nested.

### 3.1 `config.json` (`IdeConfig`)

| Field                     | Type     | Default | Meaning |
|---------------------------|----------|---------|---------|
| `installation`            | object   | required| `IdeInstallation` this directory belongs to |
| `baseVmOptionsPath`       | string?  | null    | base `.vmoptions` file |
| `baseVmOptionsHash`       | string?  | null    | SHA-256 of base vmoptions at last check |
| `maxRecentProjects`       | int      | 10      | cap for `recent.json` |
| `basePluginsPath`         | string?  | null    | base plugins dir; auto-detected when null |
| `baseConfigPath`          | string?  | null    | base config dir; auto-detected when null |
| `mainProjectPath`         | string?  | null    | project opened without isolation |
| `dontAskAboutMainProject` | bool     | false   | suppress main-project prompt |
| `syncPluginsOnIdeUpdate`  | bool     | false   | plugin re-sync on build change |
| `lastKnownBuildNumber`    | string?  | null    | last IDE build seen |

Loading a missing, empty, or corrupt file yields `IdeConfig.default(installation)` (corruption is logged to stderr).

### 3.2 `metadata.json` (`ProjectMetadata`)

```json
{ "path": "/abs/path", "lastOpened": "2026-10-30T10:00:00Z", "openCount": 3, "debugPort": 5001, "baseVmOptionsHash": "<sha256>" }
```

`openCount` omitted when 0, `debugPort` and `baseVmOptionsHash` omitted when null. `debugPort` is present only once a launch or vmoptions sync ran with a base vmoptions that enables JDWP (§7.1); a port stored earlier is kept even if JDWP is later removed. `baseVmOptionsHash` is the SHA-256 of the base vmoptions `idea.vmoptions` was last written from (null when written without a base, or by a version predating the field). `name` and `id` are derived from `path`, not stored. A corrupt file is skipped (logged to stderr) and treated as missing.

### 3.3 `recent.json`

```json
[ { "projectId": "<id>", "timestamp": "<ISO instant>" }, ... ]
```

Newest first, truncated to `maxRecentProjects` on every `recordOpen`. An empty or corrupt file is treated as empty (corruption is logged to stderr). `recordOpen` and `remove` are read-modify-write under one lock; `getRecent` locks only for the read and skips entries whose metadata is missing or corrupt.

## 4. Repository and registry

- `IdeRegistry` (singleton) lists installations: registered ones are `v2/*` directories whose `config.json` parses and has a non-blank `installation.executablePath` (`IdeConfigRepository.loadFromDir` returns null otherwise, so corrupt dirs are omitted); `getAllInstallations()` is detected (§10) plus registered, deduplicated by `executablePath`. `getRepository(installation)` returns `IdeConfigRepository` at `<ideDir>`; nothing is created until the first write.
- `IdeConfigRepository` exposes `load()`, `save()`, `update(transform)` (one lock held across read, transform and write, so concurrent updates from other processes are not lost; a corrupt file is transformed from the default), `saveProjectMetadata`, `loadProjectMetadata(path|id)` (null when missing or corrupt), `loadAllProjects()` (unsorted, skips dirs without a readable `metadata.json`), `deleteProjectMetadata(path)` (removes only the JSON file).
- `ProjectManager` wraps metadata operations: `registerOrUpdate` increments `openCount`, sets `lastOpened = now`, preserves `debugPort` and `baseVmOptionsHash`; `listAll()` sorts by `lastOpened` descending; `listSyncable()` returns all tracked projects except the main project (§8); `ensureDebugPort` allocates and persists a port when missing; `debugPortFor(project, basePath?)` calls `ensureDebugPort` when `VMOptionsGenerator.containsJdwp(basePath)`, otherwise returns the stored `debugPort` without allocating; `updateBaseVmOptionsHash(project, hash)` reloads the stored metadata (so a just-saved `debugPort` is kept), sets the hash and saves; `remove(path)` deletes metadata only; `get(path)` and `resolvePath(string)` as above.

## 5. Dependency injection

Koin, started once by `KoinInit.init(vararg extraModules)` (idempotent; extra modules are loaded onto a running Koin).

- `coreModule`: true singletons `IdeRegistry`, `ProcessLauncher` (`ProcessLauncherImpl`).
- `ideScopedModule`: a Koin scope with qualifier `IDE_SCOPE`. `IdeScopeManager.getScope(repository)` creates one scope per `IdeConfigRepository.installation.directoryName` and `declare`s the repository into it. Scoped services: `ProjectManager`, `DirectoryManager`, `BaseVMOptionsTracker`, `ShutdownSignalManager`, `RecentProjectsIndex`, `IntelliJLauncher`, `ProjectLauncher`, `ProjectCleaner`.

Contract for callers: service constructors are `internal`; obtain instances via `X.getInstance(repository)`, which resolves `repository.getScope().get()`. Two repositories for the same installation share a scope and therefore the same service instances. `KoinInit.stop()` closes all scopes and stops Koin.

## 6. VM options file

`VMOptionsGenerator.generate(basePath?, dirs, debugPort?, forceRegenerate = false)` writes `<projRoot>/idea.vmoptions`.

Fresh write (file missing, or `forceRegenerate = true`):

1. If `basePath` exists: `# Base VM options from: <path>`, then every base line except those starting with `-Didea.config.path=`, `-Didea.system.path=`, `-Didea.log.path=`, `-Didea.plugins.path=`. Comments and blank lines are kept. If `debugPort` is non-null, in lines starting with `-agentlib:jdwp=` the port in `address=` is replaced, preserving any host prefix (`*:`, `host:`, or none). Then a blank line.
2. The marker line `# project-juggler overrides (auto-generated)`.
3. `-Didea.config.path=`, `-Didea.system.path=`, `-Didea.log.path=`, `-Didea.plugins.path=` pointing at `dirs`.
4. `-Dproject.juggler.base.dir=<base>` so child IDEs inherit a custom base dir.

Update (file exists, not forced): only the marker block is refreshed. The marker and the contiguous run of following lines starting with `-Didea.` or `-Dproject.juggler.` are replaced by the marker, the four path lines and the base-dir line; if there is no marker, a blank line and those lines are appended. Base options and the debug port are not reapplied on this path.

`VMOptionsGenerator.computeBaseHash(basePath?)` is `sha256(base file)`, or null when the path is null or missing.

`VMOptionsGenerator.containsJdwp(basePath?)` is true when the base file exists and has a line that, trimmed, starts with `-agentlib:jdwp=`.

`BaseVMOptionsTracker.hasChanged()` is `sha256(base file) != baseVmOptionsHash` (false when path unset or file missing; a null stored hash counts as changed). `updateHash()` persists the current hash.

## 7. Launching

### 7.1 Isolated launch (`ProjectLauncher.launch` for non-main projects)

1. If base vmoptions changed, update the IDE-level stored hash (`IdeConfig.baseVmOptionsHash`; regeneration is decided per project in step 4).
2. `ProjectManager.registerOrUpdate(path)`.
3. `RecentProjectsIndex.recordOpen(path)`: drop any existing entry for the id, insert at head, truncate to `maxRecentProjects`, save.
4. `IntelliJLauncher.launch(project)`:
   - `DirectoryManager.ensureProjectDirectories`: create `config/ system/ logs/ plugins/` (idempotent); then, if `config/` is empty, copy base config into it; if `plugins/` is empty, copy base plugins. Copy failures are swallowed.
   - `debugPortFor`: `ensureDebugPort` only when the base vmoptions contains `-agentlib:jdwp=` (range 5001..14999, first port not used by another project of this IDE); otherwise the stored port, if any, without allocating.
   - `generate(base, dirs, port, forceRegenerate = computeBaseHash(base) != project.baseVmOptionsHash)`: update mode if the file exists and the base is unchanged since the project's last write; otherwise a fresh write, after which `updateBaseVmOptionsHash` persists the new hash (null when no base).
   - Start `<installation.executablePath> <project path>` with env `IDEA_VM_OPTIONS=<projRoot>/idea.vmoptions`.

Base config copy exclusions: any directory named `plugins`; files named `.lock`, `recentProjects.xml`, `recentProjectDirectories.xml`; relative path `options/recentProjects.xml`. Plugin copies have no exclusions.

Base paths: `baseConfigPath` / `basePluginsPath` from config, else auto-detected (§10). Unset and undetectable means the copy is skipped on launch and throws `IllegalStateException` on sync.

### 7.2 Main project launch

`MainProjectService.isMainProject` is an exact string comparison of `mainProjectPath` with `pathString`. For the main project, `launch` calls `IntelliJLauncher.launchMain`: start the executable with the project path and `IDEA_VM_OPTIONS` set to the base vmoptions file when one is configured and exists, otherwise removed from the child environment. No directories, metadata, recents, or vmoptions generation.

`shouldPromptForMainProject(repo, currentConfigDir)` is true when `mainProjectPath` is null, `dontAskAboutMainProject` is false, and the caller runs in the main instance. "Main instance" means `baseConfigPath` is null or equals `currentConfigDir.toString()` exactly.

### 7.3 Process launching (`ProcessLauncherImpl`)

- Windows: `cmd /c start "" <exe> <args>`. macOS/Linux: `<exe> <args>` with inherited IO.
- Extra env is merged over the inherited environment; a `null` value removes the variable.
- A non-daemon thread polls for 10 s. If the process exits non-zero in that window, `onFailure(LaunchFailure(exitCode, stdout, stderr))` is invoked. Exit 0 or still running means no callback.

### 7.4 Running detection

`ProjectLockUtils.isProjectOpen(repo, path)` returns true iff `<dir>/.lock` exists as a regular file and, when its trimmed content parses as an int, a process with that PID is alive. Locks with any other content count as live (existence only). `<dir>` is `<projRoot>/config` for isolated projects and `DirectoryManager.getBaseConfigPath()` for the main project (configured `baseConfigPath`, else `ConfigLocator`-detected; false when neither yields a dir). `isIsolatedInstanceOpen(repo, path)` applies the same check to `<projRoot>/config/.lock`, even for the main project. `readPidFromLock` uses the same `<dir>` and returns the file's trimmed content as an int, or null. `isProcessRunning(pid)` uses `ProcessHandle.of(pid).isAlive` (no external command); any error means false. All of these need a started Koin, since they resolve `DirectoryManager` from the IDE scope.

### 7.5 Window focusing (`WindowFocuser.focus(pid)`)

Returns `Success`, `ProcessNotFound`, `WindowNotFound`, `CommandFailed(msg)` or `ToolNotInstalled(name)`. macOS uses `osascript` on System Events; Linux uses `wmctrl` then `xdotool`; Windows runs a temporary PowerShell script using `EnumWindows`/`SetForegroundWindow`.

## 8. Sync protocol

`ProjectLauncher.syncProject(project, syncVmOptions, syncConfig, syncPlugins, options)` with `SyncOptions(stopIfRunning = false, autoRestart = false, shutdownTimeout = 60, onProgress = {})`. `SyncOptions.STOP_AND_RESTART` sets both booleans.

Sequence:

0. If `project.path` is the configured `mainProjectPath`, throw `SyncException("'<name>' is the main project and uses base settings directly; there is nothing to sync.")` before any lock, running check or stop request. The main project runs on base settings, so its isolated directories are never used. Callers that sync many projects should pick targets with `ProjectManager.listSyncable()`, which already excludes it.
1. Acquire `<projRoot>/signals/sync-in-progress.lock` with a non-blocking `FileChannel.tryLock()`. Failure, including an overlapping lock in the same JVM, throws `SyncException("Sync already in progress for project '<name>'...")`.
2. `wasRunning = isProjectOpen(...)`.
3. If `wasRunning && stopIfRunning` (a stale `.lock` whose PID is dead reads as not running, so no request is sent): write `signals/stop-request.json`:
   ```json
   { "requestId": "<uuid>", "timestamp": "<ISO instant>", "requestedBy": "system",
     "autoRestart": true, "syncTypes": ["vmoptions", "config"], "timeoutSeconds": <shutdownTimeout> }
   ```
   then poll `isProjectOpen` once per second up to `shutdownTimeout`, emitting `SyncProgress.Stopping(seconds)`. On success sleep 500 ms; on timeout throw `SyncException` (the request file is left for cleanup in step 6). `syncTypes` lists the requested parts in the order vmoptions, config, plugins; `timeoutSeconds` is `SyncOptions.shutdownTimeout`.
4. Emit `Syncing`, then `ensureProjectDirectories` and:
   - vmoptions: require an existing base vmoptions file (else `IllegalStateException`); `debugPortFor` (allocates only when the base contains `-agentlib:jdwp=`); `generate(..., forceRegenerate = true)`; persist `computeBaseHash(base)` via `updateBaseVmOptionsHash`.
   - config: wipe `<projRoot>/config/` contents and recopy base config with the §7.1 exclusions.
   - plugins: wipe `<projRoot>/plugins/` and recopy base plugins.
5. If `wasRunning && stopIfRunning && autoRestart`: emit `Restarting` and call `ProjectLauncher.launch(project.path, onFailure)` (metadata, recents and vmoptions-hash bookkeeping as in §7.1). A crash within 10 s is reported as `SyncProgress.Error("IntelliJ exited with code <n> after restart of '<name>': <output>")`, possibly after `syncProject` has returned, so `onProgress` consumers must tolerate a late `Error`; exceptions from `launch` become `SyncProgress.Error("Failed to restart IntelliJ: ...")` as well. Neither fails the sync.
6. Always: delete every file in `signals/` except `sync-in-progress.lock`, then release the sync lock. The lock file is never deleted.

If the project is running and `stopIfRunning` is false, the sync proceeds against a live IDE.

### 8.1 Expectations on the running IDE

The isolated IDE (plugin side) is expected to poll `signals/stop-request.json`, exit gracefully when one appears, and thereby remove `config/.lock`. `ShutdownSignalManager.cleanupStaleSignals(project, maxAgeMinutes = 5)` deletes files in `signals/` other than `sync-in-progress.lock` whose embedded timestamp is older than the limit, and any such file that does not parse as a stop request.

### 8.2 Self-sync

A process cannot sync its own IDE while running. The expected pattern (used by the plugin and implemented by `sync-helper`) is to spawn an external process that runs `ProjectLauncher.syncProject` with `stopIfRunning = true, autoRestart = true`.

## 9. Cleaning and removal

- `ProjectCleaner.cleanProject(metadata)`: remove the id from `recent.json`, then delete `<projRoot>` recursively (including `metadata.json`).
- `ProjectCleaner.cleanStaleMainProjectEntry()`: if `mainProjectPath` is set and has a `projects/<id>/metadata.json` (the project was opened isolated before it became main), `cleanProject` it and return the metadata. Returns null, and leaves everything in place, when there is no main project, no entry, or `isIsolatedInstanceOpen` is true (an isolated IDE of that project is still running; the caller is expected to retry later). The plugin calls this when a project is set as main and at startup of the main instance.
- `ProjectManager.remove(path)`: delete `metadata.json` only; isolated dirs remain.

## 10. Locators and platform

`Platform.current()` from `os.name`: contains `mac`/`darwin` -> MACOS; contains `win` -> WINDOWS; contains `nux`/`nix`/`aix` -> LINUX; else `UnsupportedOperationException`.

`IntelliJLocator.findAllInstallations()` scans, in order, standard dirs, direct executables, Toolbox, Snap (Linux), and dedups by executable path. Every candidate must exist and be executable.

| Platform | Standard dirs | Match | Executable |
|----------|---------------|-------|------------|
| macOS    | `/Applications`, `~/Applications` | name contains `IntelliJ`, ends `.app` | `Contents/MacOS/idea` |
| Linux    | `/opt` | lowercase name contains `intellij` or equals `idea` | `bin/idea.sh` |
| Windows  | `%ProgramFiles%\JetBrains`, `%LOCALAPPDATA%\Programs` | name contains `IntelliJ` | `bin\idea64.exe` |

Toolbox: `<Toolbox>/apps/<type>/ch-0/<version>/...` where `<type>` contains `idea` or `intellij`; display name `IntelliJ IDEA[ Ultimate| CE] <version> (Toolbox)`. Snap: `/snap/intellij-idea-{ultimate,community}/current/bin/idea.sh`.

`findInstallation(executablePath)` returns the scanned match or synthesizes `IdeInstallation(path, extractDisplayName(path))`.

`ConfigLocator.findDefaultConfigDirectory()`: under `~/Library/Application Support/JetBrains` (macOS), `~/.local/share/JetBrains` (Linux), `%APPDATA%\JetBrains` (Windows), the lexicographically greatest existing child whose name starts with `IntelliJIdea`. `PluginLocator.findDefaultPluginsDirectory()` is that directory's `plugins/` child if it is a directory.

All locators resolve their roots (platform, user home, filesystem root for `/Applications`, `/opt`, `/snap`, and the `ProgramFiles`/`LOCALAPPDATA`/`APPDATA` variables) through an internal `LocatorEnvironment`; the public functions use `LocatorEnvironment.current()`.

## 11. Utilities with external contracts

- `GitUtils.detectGitBranch(path)`: walk up from `projectRoot(path)` to the first `.git` (directory, or file with `gitdir:` resolved relative to its location). Read `HEAD`: `ref: refs/heads/X` -> `X`; 40-hex SHA -> `"HEAD (detached)"`; else `git rev-parse --abbrev-ref HEAD` with a 2 s timeout. Any error -> null.
- `GitWorktreeManager.createWorktree(src, dst, branch, out?)`: validates (src exists, is a dir, has `.git`; dst absent; branch non-blank; `IllegalArgumentException` otherwise), runs `git worktree add -b <branch> <dst>` in `src`, throws `IOException` on non-zero exit.
- `DirectoryCopier.copyIfFirstOpen(src, dst, ...)` copies only when `dst` is missing or empty, returns whether it copied, swallows errors. `copy(src, dst, ...)` clears `dst` contents then copies; throws if `src` is invalid. Files are copied with `REPLACE_EXISTING`, attributes not preserved, symlinks followed.
- `HashUtils`: SHA-256 as 64 lowercase hex chars for files and UTF-8 strings.
- `PortAllocator.allocatePort()`: first port in 5001..14999 not used by a project of this IDE, else null.

## 12. Error reporting conventions

- `SyncException` for sync-protocol failures (lock contention, shutdown timeout).
- `IllegalStateException` for missing base paths needed by a sync.
- `IllegalArgumentException` for invalid inputs (worktree creation, copy source).
- Readers of config, recents and project metadata degrade (default / empty / skipped, logged to stderr) on corruption.
- Launch crashes within 10 s are reported only through `onFailure`; later crashes are not observed.
- Restart crashes after a sync surface as `SyncProgress.Error` through `onFailure`.

## Known gaps

Behavior above that is likely unintended, kept here so the spec stays honest. Not fixed by this document.

7. Linux locators look in `~/.local/share/JetBrains/IntelliJIdea*/plugins`; actual Linux layout is `~/.config/JetBrains/<product>` for config and `~/.local/share/JetBrains/<product>` for plugins. Only `IntelliJIdea*` (Ultimate) is matched, versions sort lexicographically, and the chosen config dir ignores which installation the repository is for.
8. `findInstallation` fallback names the IDE after the executable file. Toolbox scanning supports only the `ch-0` layout. `findIntelliJ()` is unused.
11. `GitWorktreeManager` double-wraps its own `IOException` and has no timeout. `GitUtils` does not treat 64-hex (SHA-256) detached heads as detached.
12. `copyIfFirstOpen` can leave a partial copy that is never retried. A forced config sync wipes the project's own `.lock` and `recentProjects.xml`.
13. `ProcessLauncherImpl` captures no output on Unix (inherited IO), reports the exit code of `cmd start` on Windows, and keeps a CLI JVM alive up to 10 s via a non-daemon thread.
14. `isRunningInMainInstance` compares paths as raw strings and assumes "main" when `baseConfigPath` is unset.
15. Untested: `WindowFocuser`, `GitWorktreeManager`, `ProcessLauncherImpl`, and the vmoptions update path. `PluginCopierTest` duplicates `DirectoryCopierTest`.
