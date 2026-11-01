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

`directoryName` is only the name given to a new `<ideDir>`. Lookup is by executable path (§4): a dir created under one display name keeps serving the same executable under another.

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
  launcher-logs/                            # launch-<millis>-<n>.out/.err, §7.3
  v2/
    <ideDir>/
      config.json                           # IdeConfig
      recent.json                           # RecentProjectsIndex
      sync-helper.json                      # SyncHelperInstallation, §3.4
      projects/
        <ProjectId>/
          metadata.json                     # ProjectMetadata
          idea.vmoptions                    # generated, see §6
          config/  system/  logs/  plugins/ # isolated IDE dirs
          self-sync-result.json             # SelfSyncResult, §8.2
          signals/
            stop-request.json               # §8
            sync-in-progress.lock           # permanent, §8
```

All JSON is pretty-printed with `encodeDefaults = false`. `config.json`, `metadata.json` and `recent.json` are read and written under an exclusive OS file lock (`FileLockUtils.withFileLock`: a per-file in-JVM `ReentrantLock` keyed by the normalized absolute path, then a blocking `FileChannel.lock()`; threads of one JVM queue on the first, processes on the second, since an OS lock taken by a second thread of the same JVM throws `OverlappingFileLockException`. Creates parent dirs and the file if missing, tolerating a concurrent creation). When both are needed, `config.json` is read before the `recent.json` lock is taken; locks are never nested.

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

### 3.4 `sync-helper.json` (`SyncHelperInstallation`)

```json
{ "javaHome": "<JBR of the IDE>", "installDir": "<extracted sync-helper distribution>" }
```

Written by the plugin at every IDE start for its own installation and read by the sync-helper to hand a command over to that IDE's helper (sync-helper-spec §4). `SyncHelperInstallationStore` (IDE-scoped) reads and writes it under the file lock; a missing, empty or corrupt file reads as null (corruption is logged to stderr). `SyncHelperInstallation.command(baseDir?, args, platform)` builds `<javaHome>/bin/java[.exe] -cp <installDir>/lib/* [-Dproject.juggler.base.dir=<baseDir>] com.projectjuggler.synchelper.SyncHelperMainKt <args>`, the only builder of that command line; `isUsable()` checks that the `java` binary and `lib/sync-helper.jar` exist.

## 4. Repository and registry

- `IdeRegistry` (singleton) lists installations: registered ones are `v2/*` directories whose `config.json` parses and has a non-blank `installation.executablePath` (`IdeConfigRepository.loadFromDir` returns null otherwise, so corrupt dirs are omitted); `getAllInstallations()` is detected (§10) plus registered, deduplicated by `executablePath`. `getRepository(installation)` resolves, in order: (1) `v2/<installation.directoryName>` when it has a `config.json`, with the given installation; (2) the first registered dir whose stored `installation.executablePath` equals the given one (another display name), with the stored installation, so its scope keys on the real dir name (`findRepository`); (3) a new `v2/<installation.directoryName>`. Nothing is created until the first write.
- `findRepository(executablePath)` returns the first registered dir whose stored `installation.executablePath` equals the given one, with the stored installation, or null. It never creates a dir; `sync-helper` uses it so it only works on data the plugin already has.
- `adoptLegacyRepository(installation, legacyExecutablePaths)` moves one dir registered under a legacy executable path (the plugin passes the IDE home directory older versions keyed on) to `v2/<installation.directoryName>` and stores `installation` in its `config.json`; `projects/` and `recent.json` move with it. It returns null and changes nothing unless the target dir does not exist, exactly one registered dir matches, and none of that dir's `projects/*/config/.lock` files exists (`ProjectLockUtils.hasIsolatedInstanceLock`, live or stale). A failed move is rethrown as `IllegalStateException` naming both paths. Isolated `idea.vmoptions` files keep the old absolute paths until the next launch rewrites their marker block (§6).
- `IdeConfigRepository` exposes `load()`, `save()`, `update(transform)` (one lock held across read, transform and write, so concurrent updates from other processes are not lost; a corrupt file is transformed from the default), `saveProjectMetadata`, `loadProjectMetadata(path|id)` (null when missing or corrupt), `loadAllProjects()` (unsorted, skips dirs without a readable `metadata.json`), `deleteProjectMetadata(path)` (removes only the JSON file).
- `ProjectManager` wraps metadata operations: `registerOrUpdate` increments `openCount`, sets `lastOpened = now`, preserves `debugPort` and `baseVmOptionsHash`; `listAll()` sorts by `lastOpened` descending; `listSyncable()` returns all tracked projects except the main project (§8); `ensureDebugPort` allocates and persists a port when missing; `debugPortFor(project, basePath?)` calls `ensureDebugPort` when `VMOptionsGenerator.containsJdwp(basePath)`, otherwise returns the stored `debugPort` without allocating; `updateBaseVmOptionsHash(project, hash)` reloads the stored metadata (so a just-saved `debugPort` is kept), sets the hash and saves; `remove(path)` deletes metadata only; `get(path)` and `resolvePath(string)` as above; `validatePathExists(string)` has no callers.

## 5. Dependency injection

Koin, started once by `KoinInit.init(vararg extraModules)` (idempotent; extra modules are loaded onto a running Koin).

- `coreModule`: true singletons `IdeRegistry`, `ProcessLauncher` (`ProcessLauncherImpl`).
- `ideScopedModule`: a Koin scope with qualifier `IDE_SCOPE`. `IdeScopeManager.getScope(repository)` creates one scope per `IdeConfigRepository.installation.directoryName` and `declare`s the repository into it. Scoped services: `ProjectManager`, `DirectoryManager`, `BaseVMOptionsTracker`, `ShutdownSignalManager`, `SelfSyncResultStore`, `RecentProjectsIndex`, `IntelliJLauncher`, `ProjectLauncher`, `ProjectCleaner`.

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

Base config copy exclusions: any directory named `plugins`; files named `.lock`, `recentProjects.xml`, `recentProjectDirectories.xml`; relative path `options/recentProjects.xml`. Plugin copies have no exclusions. A failed first-open copy is rolled back (destination emptied) so the next launch retries.

Base paths: `baseConfigPath` / `basePluginsPath` from config, else auto-detected (§10). Unset and undetectable means the copy is skipped on launch and throws `IllegalStateException` on sync.

### 7.2 Main project launch

`MainProjectService.isMainProject` is an exact string comparison of `mainProjectPath` with `pathString`. For the main project, `launch` calls `IntelliJLauncher.launchMain`: start the executable with the project path and `IDEA_VM_OPTIONS` set to the base vmoptions file when one is configured and exists, otherwise removed from the child environment. No directories, metadata, recents, or vmoptions generation.

`shouldPromptForMainProject(repo, currentConfigDir)` is true when `mainProjectPath` is null, `dontAskAboutMainProject` is false, and the caller runs in the main instance. `isRunningInMainInstance(repo, currentConfigDir)` decides "main instance": false when `DirectoryManager.isIsolatedConfigDir(currentConfigDir)` (the dir, after `toAbsolutePath().normalize()`, lies under `<ideDir>/projects/`; symlinks are not resolved), regardless of `baseConfigPath`. Otherwise true when `baseConfigPath` is null, or when it names the same directory as `currentConfigDir` after `toAbsolutePath().normalize()`, comparing real paths (`toRealPath()`) when both exist so symlinked locations match (`PathUtils.isSamePath`, §11).

### 7.3 Process launching (`ProcessLauncherImpl`)

- macOS/Linux: `<exe> <args>` with stdin inherited and stdout/stderr redirected to `<base>/launcher-logs/launch-<millis>-<n>.out` and `.err` (files, not pipes, so nothing has to drain them while the IDE runs). If the directory or files cannot be created, a line is logged to stderr and the output is discarded. Windows: `cmd /c start "" <exe> <args>`, no redirect.
- Extra env is merged over the inherited environment; a `null` value removes the variable.
- A daemon thread named `project-juggler-launch-watcher` waits up to 10 s (constructor parameter `watchTimeout`) for the process, so a CLI JVM can exit as soon as its `main` returns. On a non-zero exit in that window it passes the last 64 KiB (UTF-8) of both files to `onFailure(LaunchFailure(exitCode, stdout, stderr))` and keeps them; on exit 0 or still running it invokes no callback and deletes them (a running child keeps writing to the unlinked files).
- Each launch first deletes, best effort, regular files in `launcher-logs/` last modified more than 7 days ago.

### 7.4 Running detection

`ProjectLockUtils.isProjectOpen(repo, path)` returns true iff `<dir>/.lock` exists as a regular file and, when its trimmed content parses as an int, a process with that PID is alive. Locks with any other content count as live (existence only). `<dir>` is `<projRoot>/config` for isolated projects and `DirectoryManager.getBaseConfigPath()` for the main project (configured `baseConfigPath`, else `ConfigLocator`-detected; false when neither yields a dir). `isIsolatedInstanceOpen(repo, path)` applies the same check to `<projRoot>/config/.lock`, even for the main project. `readPidFromLock` uses the same `<dir>` and returns the file's trimmed content as an int, or null. `isProcessRunning(pid)` uses `ProcessHandle.of(pid).isAlive` (no external command); any error means false. All of these need a started Koin, since they resolve `DirectoryManager` from the IDE scope.

### 7.5 Window focusing (`WindowFocuser.focus(pid)`)

Returns `Success`, `ProcessNotFound`, `WindowNotFound`, `CommandFailed(msg)` or `ToolNotInstalled(name)`; the sixth `FocusResult`, `LockNotFound`, comes only from `focusRunningProject` (§7.6). macOS uses `osascript` on System Events; Linux uses `wmctrl` then `xdotool`; Windows runs a temporary PowerShell script using `EnumWindows`/`SetForegroundWindow`. Not unit tested, by decision: it only shells out to these platform tools.

### 7.6 Launch or focus from outside the IDE (`process/LaunchOrFocus.kt`)

The rule both the plugin's popup (plugin-spec, `planLaunch`) and `sync-helper open` use once the project does not belong to the calling IDE:

- `planOpen(repo, path)`: `LAUNCH` for the main project (a running main IDE opens or focuses it on launch, which a lock-based focus could not do); `FOCUS` when `isProjectOpen` (§7.4); `LAUNCH` otherwise. Creates no directories.
- `focusRunningProject(repo, path, focus = WindowFocuser::focus)`: `readPidFromLock`, else `LockNotFound`; a dead PID gives `ProcessNotFound(pid)` without calling `focus`; otherwise `focus(pid)`'s result.

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
   then poll `isProjectOpen` once per second up to `shutdownTimeout`, emitting `SyncProgress.Stopping(seconds)`. On success sleep 500 ms and count the IDE as *stopped*; on timeout throw `SyncException` (the request file is left for cleanup in step 6; the IDE still runs, so it is not stopped and step 5 does not relaunch it). `syncTypes` lists the requested parts in the order vmoptions, config, plugins; `timeoutSeconds` is `SyncOptions.shutdownTimeout`.
4. Emit `Syncing`, then `ensureProjectDirectories` and:
   - vmoptions: require an existing base vmoptions file (else `IllegalStateException`); `debugPortFor` (allocates only when the base contains `-agentlib:jdwp=`); `generate(..., forceRegenerate = true)`; persist `computeBaseHash(base)` via `updateBaseVmOptionsHash`.
   - config: wipe `<projRoot>/config/` contents **except the excluded entries** and recopy base config with the §7.1 exclusions. A running isolated IDE therefore keeps its `.lock` and its own recent projects lists.
   - plugins: wipe `<projRoot>/plugins/` and recopy base plugins.
5. If step 3 stopped the IDE and `autoRestart` is set, relaunch it whatever the outcome of step 4 (in a `finally`; an exception from step 4 propagates after the relaunch, so a failed sync does not leave the IDE closed): emit `Restarting` and call `ProjectLauncher.launch(project.path, onFailure)` (metadata, recents and vmoptions-hash bookkeeping as in §7.1). A crash within 10 s is reported as `SyncProgress.Error("IntelliJ exited with code <n> after restart of '<name>': <output>")`, possibly after `syncProject` has returned, so `onProgress` consumers must tolerate a late `Error`; exceptions from `launch` become `SyncProgress.Error("Failed to restart IntelliJ: ...")` as well. Neither fails the sync.
6. Always: delete every file in `signals/` except `sync-in-progress.lock`, then release the sync lock. The lock file is never deleted.

If the project is running and `stopIfRunning` is false, the sync proceeds against a live IDE.

### 8.1 Expectations on the running IDE

The isolated IDE (plugin side) is expected to poll `signals/stop-request.json`, exit gracefully when one appears, and thereby remove `config/.lock`. `ShutdownSignalManager.cleanupStaleSignals(project, maxAgeMinutes = 5)` deletes files in `signals/` other than `sync-in-progress.lock` whose embedded timestamp is older than the limit, and any such file that does not parse as a stop request. The plugin acts on a request only while its age is at most `timeoutSeconds`.

### 8.2 Self-sync

A process cannot sync its own IDE while running. The expected pattern (used by the plugin and implemented by `sync-helper`, see [sync-helper-spec.md](sync-helper-spec.md)) is to spawn an external process that runs `ProjectLauncher.syncProject` with `stopIfRunning = true, autoRestart = true`. The running IDE is stopped through the stop request like any other (§8.1), not by exiting itself.

The external process records the outcome with `SelfSyncResultStore.write(project, SelfSyncResult)` in `<projRoot>/self-sync-result.json`, outside `signals/` because step 6 empties that dir. `SelfSyncResult` is `{ timestamp, syncTypes, succeeded, message?, restartError? }`: `syncTypes` uses the stop-request names (`SyncTypeNames.of`), `message` is the failure message when `succeeded` is false, `restartError` the last `SyncProgress.Error` seen. `consume(project)` reads and deletes the file under `FileLockUtils.withFileLock`; a missing, empty or corrupt file gives null (a corrupt one is deleted, logged to stderr). Both use the stop request's JSON settings (`ShutdownSignalManager.json`). `SelfSyncResultStore.getInstance(repository)` resolves it from the IDE scope.

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

- `GitUtils.detectGitBranch(path)`: walk up from `projectRoot(path)` to the first `.git` (directory, or file with `gitdir:` resolved relative to its location). Read `HEAD`: `ref: refs/heads/X` -> `X`; 40- or 64-hex SHA -> `"HEAD (detached)"`; else `git rev-parse --abbrev-ref HEAD` with a 2 s timeout. Any error -> null.
- `GitWorktreeManager.createWorktree(src, dst, branch, out?)`: validates (src exists, is a dir, has `.git`; dst absent; branch non-blank; `IllegalArgumentException` otherwise), runs `git worktree add -b <branch> <dst>` in `src` with a 30 s timeout, throws a single `IOException("Failed to create git worktree: <output>")` on non-zero exit (stdout and stderr are merged into the error message), `IOException("git worktree timed out after 30 s")` on timeout, and lets the `IOException` from a missing `git` propagate unwrapped.
- `GitCommandRunner` (internal) runs `git` with merged output and a timeout; used by both. On timeout it destroys the process and its descendants. The timeout path is covered by the runner's `waitFor(timeout)` contract, not by a unit test.
- `DirectoryCopier.copyIfFirstOpen(src, dst, ...)` copies only when `dst` is missing or empty, returns whether it copied; on error it logs to stderr, rolls back (empties `dst`) and returns false. `copy(src, dst, ...)` clears `dst` except entries matching the exclusions (they are kept in place), then copies; throws if `src` is invalid. Files are copied with `REPLACE_EXISTING`, attributes not preserved, symlinks followed.
- `PathUtils.resolveAbsolute(raw)`: tilde expansion (`expandTilde`), then absolute, then normalized; throws `InvalidPathException` like `Path.of`. Used by `ProjectManager.resolvePath` and the plugin's Open a Project Copy validation.
- `PathUtils.isSamePath(a, b)`: true when both are equal after `toAbsolutePath().normalize()`, or both exist and have equal `toRealPath()` (symlinks match); a real-path failure is logged to stderr and counts as different. Used by `MainProjectService.isRunningInMainInstance` and the plugin's IDE path drift check.
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

Behavior above that is likely unintended, kept here so the spec stays honest. Not fixed by this document. Numbers are stable: a fixed gap is removed and its number is not reused, so a test named after a gap keeps pointing at it.

7. Linux locators look in `~/.local/share/JetBrains/IntelliJIdea*/plugins`; actual Linux layout is `~/.config/JetBrains/<product>` for config and `~/.local/share/JetBrains/<product>` for plugins. Only `IntelliJIdea*` (Ultimate) is matched, versions sort lexicographically, and the chosen config dir ignores which installation the repository is for.
8. `findInstallation` fallback names the IDE after the executable file. Toolbox scanning supports only the `ch-0` layout. `findIntelliJ()` is unused.
13. On Windows `ProcessLauncherImpl` reports the exit code of `cmd start` and captures no child output.
14. `config.json` and `metadata.json` are decoded without `ignoreUnknownKeys`. A file written by a newer version with an extra field reads as corrupt: `IdeRegistry` drops that ide dir (so `findRepository` and Open with miss it), and the same IDE falls back to `IdeConfig.default` after a plugin downgrade, losing the main project and base paths.
15. `IdeConfig.baseVmOptionsHash` is written (`BaseVMOptionsTracker.updateHash`, §7.1 step 1) but never read for a decision; regeneration is decided per project from `ProjectMetadata.baseVmOptionsHash` (§6, §7.1 step 4).
16. `ShutdownSignalManager.acquireSyncLock` returns null on any exception, so an I/O error (permissions, read-only disk) is reported as "Sync already in progress".
17. `ensureDebugPort` reads all metadata, picks a port and writes without a cross-process lock; two concurrent launches from different processes can allocate the same port.
18. The `IllegalStateException` messages for a missing base vmoptions, config or plugins path (§8 step 4) tell the user to run `project-juggler config --base-... <path>`, a CLI that no longer exists; the plugin shows them verbatim in its sync failure notifications.
19. `IdeConfigRepository.load()` (and `loadProjectMetadata`) treat any failure while reading, including an I/O or lock error, as a corrupt file: it is logged as "Ignoring corrupt ..." and the defaults (or null) are returned, so a caller that saves what it loaded can overwrite valid settings.
