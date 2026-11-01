# sync-helper behavioral specification

This document describes what the `sync-helper` module does today, as observed from the code, and the contract between it
and the `plugin` that spawns it. It builds on [core-spec.md](core-spec.md); anything about the sync protocol itself
(locks, stop requests, copying, relaunch) is specified there in §8 and only referenced here. Questionable behavior is
described as-is and listed under [Known gaps](#known-gaps).

Conventions follow the core spec: `<base>`, `<ideDir>`, `<projRoot>`.

## 1. Purpose

A running IDE cannot sync its own isolated state: it would have to stop itself, copy files into the directories it is
using, and start again. `sync-helper` is a minimal standalone JVM program that performs `ProjectLauncher.syncProject`
for one project from outside the IDE process with `stopIfRunning = true, autoRestart = true`, and leaves a result file
for the IDE to report (`sync`). Syncing a *different* project is done in-process by the plugin
(`performSyncWithProgress`).

It also gives other apps (launchers, scripts, terminals) a way to open a project the way the plugin's Recent Projects
popup does (`open`): focus the window of the project's running IDE, or launch the project. They run it through the
launcher script the plugin publishes at `<base>/bin/sync-helper` (§5.6).

## 2. Module shape

- Sources: `SyncHelperArgs.kt` (argument parsing into `HelperCommand.Sync`/`Open`, `USAGE`), `HelperDispatcher.kt`
  (§4 step 3) and `SyncHelperMain.kt` (`main`, `run`, `runSync`, `runOpen`), class
  `com.projectjuggler.synchelper.SyncHelperMainKt`.
- Dependencies: `:core` and `koin-core`. No IntelliJ Platform dependency. JDK 17 toolchain.
- Built with the Gradle `application` plugin, `applicationName = "sync-helper"`. `installDist` produces
  `build/install/sync-helper/{bin,lib}`. The start scripts in `bin/` are kept for manual runs; the plugin does not use
  them (§5.3).
- Tests: `SyncHelperArgsTest` (pure parsing) and `SyncHelperRunTest` (`run` against a real registry under the test base
  dir with a mocked `ProjectLauncher`, an injected focuser and a `HelperDispatcher` with a recording `startHelper`).
  The `test` task sets `project.juggler.base.dir` to `sync-helper/build/project-juggler-test`.

## 3. Command line

```
sync-helper sync --ide <ide-path> --path <project-path> (--all | --vmoptions | --config | --plugins)...
sync-helper open --ide <ide-path> --path <project-path>
```

The first token is the command. Sync-type flags are `sync`-only:

| Argument      | Takes value | Meaning                                                                         |
|---------------|-------------|---------------------------------------------------------------------------------|
| `--ide`       | yes         | `IdeInstallation.executablePath` of the IDE whose repository to use. Required.  |
| `--path`      | yes         | The project path. `~` is expanded and the path normalized. Required. `sync` needs a tracked project, `open` any existing path. |
| `--all`       | no          | vmoptions + config + plugins.                                                   |
| `--vmoptions` | no          | Regenerate `idea.vmoptions`.                                                    |
| `--config`    | no          | Wipe and recopy `config/`.                                                      |
| `--plugins`   | no          | Wipe and recopy `plugins/`.                                                     |

Parsing rules (`parseArgs`, each violation is a `UsageException`):

- A missing or unknown command (also flags without a command, the pre-command syntax) is rejected.
- `--ide` and `--path` are required; `sync` also needs at least one sync-type flag. Sync-type flags are additive; `--all` implies the
  other three.
- Unknown tokens (including the removed `--all-projects`, and sync-type flags for `open`) are rejected.
- A value flag at the end, or followed by a token starting with `--`, is missing its value.
- `--ide` or `--path` given twice is rejected.

### Exit codes

| Code | Meaning |
|------|---------|
| `0`  | Synced; or opened (launched, or the window focused). |
| `1`  | Anything after the arguments parsed failed: no ideDir for `--ide`, unknown project or missing path, the sync, the launch or the focus. |
| `2`  | Usage error; the message and `USAGE` are printed to stderr. |

`main` is `KoinInit.init(); exitProcess(run(args))`, so the JVM always ends with the returned code.

## 4. Execution (`run`)

Common steps, then `runSync` (§4.1) or `runOpen` (§4.2):

1. Parse the arguments (§3). Print the base dir and the arguments as the first stdout line.
2. `IdeRegistry.findRepository(idePath)` (core-spec §4): the registered `<ideDir>` whose stored installation has that
   executable path, whatever its display name. The helper never creates an ideDir; without one it fails with
   "No Project Juggler data for IDE <path>" (exit 1, no result file). `<base>` is `-Dproject.juggler.base.dir` if set in
   *this* JVM (the plugin forwards its own value, §5.3, and writes it into the launcher, §5.6), else
   `~/.project-juggler`.

3. Dispatch (`HelperDispatcher`): the command runs in the helper the IDE recorded for itself
   (`<ideDir>/sync-helper.json`, core-spec §3.4), so each IDE's data is handled by that IDE's own helper and JBR
   whichever IDE published the launcher (§5.6). The helper runs `record.command(<base>, <all original args>)` with
   inherited stdio and `PROJECT_JUGGLER_HELPER_DISPATCHED=1`, prints "Running the helper of <ide>: <installDir>",
   and returns that process's exit code. It continues in this process instead when:
   - `PROJECT_JUGGLER_HELPER_DISPATCHED` is set (a dispatched helper never dispatches again);
   - there is no record (the IDE has not started with a plugin that writes one);
   - the record's `installDir` is this helper's own (the dir above the `lib/` holding this helper's jar, compared
     by real path; when this helper does not run from such a jar, any usable record is dispatched to);
   - the record is not usable (its `java` or jar is gone): "Helper of <ide> at <dir> is missing, running here" on
     stderr.

   The plugin always passes its own `--ide`, whose record is its own helper, so its syncs run in-process.

### 4.1 `sync`

3. `ProjectManager.resolvePath(raw)` and `get(path)`; a missing `metadata.json` fails with "Project not found: <raw>"
   (exit 1, no result file).
4. `ProjectLauncher.syncProject(project, …, SyncOptions(stopIfRunning = true, autoRestart = true, shutdownTimeout = 60,
   onProgress = …))`, i.e. core-spec §8: if the IDE runs (`config/.lock`), write `signals/stop-request.json`, wait for
   the IDE to exit, sync, and relaunch it, also when the sync failed. `onProgress` prints one line per event to stdout
   and keeps the last `SyncProgress.Error` message as `restartError`.
5. Write `<projRoot>/self-sync-result.json` (`SelfSyncResultStore`, core-spec §3) and return:
   - success: `SelfSyncResult(succeeded = true, restartError)`, exit 0;
   - a `Throwable` from step 4 (shutdown timeout, held sync lock, main project, missing base path, copy `IOException`):
     the stack trace is printed, then `SelfSyncResult(succeeded = false, message = <message, or the class name>,
     restartError)`, exit 1.

   `syncTypes` in the result lists the requested parts (`SyncTypeNames.of`). A failure to write the result is printed
   and does not change the exit code.

The main project is never isolated, so the plugin never sends it; if it is passed, core's `SyncException` ends up in the
result file and the exit code is 1.

### 4.2 `open`

3. A path that does not exist fails with "Path does not exist: <raw>" (exit 1). Otherwise `ProjectManager.resolvePath`.
   The project need not be tracked yet.
4. `planOpen(repo, path)` (core-spec §7.6):
   - `FOCUS`: `focusRunningProject` (PID from the lock, then `WindowFocuser.focus`). `Success` is exit 0; any other
     result prints "Cannot focus <name>: <reason>" and is exit 1.
   - `LAUNCH`: `ProjectLauncher.launch(path)` (core-spec §7.1/§7.2): registers and records an isolated project and
     starts its IDE; the main project goes to `launchMain`, so a running main IDE opens or focuses it. Exit 0.
5. A `Throwable` from step 4 prints its stack trace and "Failed to open <name>: <message>", exit 1.

`open` writes no result file and returns right after the IDE process started: a crash of the launched IDE is not
reported (same as gap 1).

## 5. Plugin contract (`BundledSyncHelper`, `Sync.kt`)

### 5.1 Bundling

`plugin/build.gradle.kts` makes `processResources` depend on `:sync-helper:installDist` and copies
`sync-helper/build/install/sync-helper/` into plugin resources under `sync-helper/`, so the plugin jar contains
`sync-helper/bin/*` and `sync-helper/lib/*.jar`.

### 5.2 Extraction (`BundledSyncHelper.getInstallDir()`)

- Must not run on EDT (`@RequiresBackgroundThread`); `@Synchronized`, so parallel calls extract once.
- Root: `PathManager.getSystemDir()/project-juggler/sync-helper/` of the running IDE (`<projRoot>/system/...` in an
  isolated instance). Target: `<root>/<fingerprint>`, the fingerprint being 16 hex chars of a SHA-256 over the names,
  sizes and CRCs of the bundled `sync-helper/bin` and `sync-helper/lib` files (file hashes in dev mode), so it changes
  with the helper's content even when the plugin version does not.
- `lib/sync-helper.jar` marks a complete extraction. If it exists in the target, the target is reused (across IDE
  sessions too) without extraction.
- Otherwise the files are extracted into `<root>/<fingerprint>.tmp-*` and the dir is moved to the target with
  `ATOMIC_MOVE`. A missing resource or jar, or any extraction failure, deletes the temp dir and throws
  `IllegalStateException`.
- After an extraction, other entries of the root and, once per JVM, legacy `java.io.tmpdir/sync-helper-*` dirs of
  earlier versions (with `bin/` and `lib/sync-helper.jar`, last modified over 1 hour ago) are deleted, best effort.

### 5.3 Command line (`BundledSyncHelper.command`, `start`)

The helper runs on the IDE's own JVM, bypassing the start scripts:

```
<java.home>/bin/java[.exe] -cp <installDir>/lib/* [-Dproject.juggler.base.dir=<value>] com.projectjuggler.synchelper.SyncHelperMainKt <args>
```

- `java.home` is the running IDE's JBR, so no system `java` is needed. `JAVA_HOME` is not set, because the relaunched
  IDE inherits the helper's environment (its terminals and Gradle runs would see the JBR).
- `-Dproject.juggler.base.dir` is passed when the IDE has that property (e.g. under `runIde`), so the helper uses the
  same `<base>`.
- `java -cp` expands the `*` itself; no shell is involved.
- stdout and stderr go to `<IDE log dir>/sync-helper.log` (overwritten per run; `<projRoot>/logs` in an isolated
  instance, next to `idea.log`), stdin is closed. The helper otherwise inherits the IDE's environment (including
  `IDEA_VM_OPTIONS` pointing at the isolated vmoptions file).

### 5.4 Decision to use the helper (`SyncPlanner`)

`IsolatedInstance.isCurrentProject(repo, path)` is true iff the running IDE is isolated (its `idea.config.path`
contains `.../projects/<id>/config` with either separator) and `<id>` equals the id of `ProjectManager.get(path)`.
The helper arguments are always `sync --ide <currentIde.executablePath> --path <project> <flag>`, the flag from `SyncType`
(`All -> --all`, `VmOptions -> --vmoptions`, `Config -> --config`, `Plugins -> --plugins`).

- `SyncSettingsSubAction` (sync one project): `SyncPlanner.forProject` gives `SelfShutdown` for the current project,
  otherwise an in-process sync.
- `SyncAllProjectsAction`: `SyncPlanner.forAllProjects` gives `SelfShutdown(helperArgs, others)` when the current
  project is among the syncable ones. The `others` sync in-process first (`performSyncWithProgress`, with progress,
  past failures, and its own success/failure notification); its `onFinished` then starts the self-sync for the current
  project. Cancelling the in-process part, or an exception from it, skips the self-sync.

### 5.5 Spawn and process watching (`performSelfShutdownSync`)

1. Show an info notification ("IntelliJ will close to sync ... and reopen automatically...").
2. In a non-cancellable `Task.Backgroundable` ("Starting sync helper..."), subscribe an `AppLifecycleListener` that
   records `appWillBeClosed`, then `BundledSyncHelper.start(args)` and keep the `Process`. The IDE does not exit by
   itself: the helper finds it running, writes the stop request, and `ShutdownSignalService` closes it (core-spec
   §8.1).
3. `process.onExit()`: disconnect the listener and, unless the app is closing or disposed, call
   `reportSelfSyncResult(repository, currentProjectId, exitCode, logFile, …)` (§6). If the IDE is closing, the helper
   stopped it, and the relaunched IDE reports at startup instead.
4. A failure to start the helper is logged as a warning and shows "Failed to initiate self-shutdown sync: ..."; the IDE
   keeps running.

### 5.6 Launcher for other apps (`BundledSyncHelper.publishLauncher`, `PublishSyncHelperActivity`)

- Once per IDE session, the first `postStartupActivity` run writes `<base>/bin/sync-helper` (`sync-helper.cmd` on
  Windows) on an IO thread. It extracts the helper first (§5.2) if needed. A failure is logged as an error.
- The script runs the §5.3 command line with an explicit `-Dproject.juggler.base.dir=<base>` and its own arguments
  appended: `#!/bin/sh` + `exec '<arg>'... "$@"` (single-quoted, mode 755), or `@echo off` + `"<arg>"... %*` on
  Windows. It uses the publishing IDE's JBR and extraction, so no system `java` is needed.
- It is written to a temp file in `<base>/bin` and moved into place with `ATOMIC_MOVE`; an existing launcher with the
  same content is left untouched. The last IDE to start wins, but its helper is only the front: before the script,
  the IDE records its own helper in `<ideDir>/sync-helper.json` (core-spec §3.4), and the front hands each command
  over to the helper of the `--ide` IDE (§4 step 3).

External usage: `~/.project-juggler/bin/sync-helper open --ide <executable> --path <project>`.

## 6. Result reporting

`SelfSyncResult` (`<projRoot>/self-sync-result.json`, core-spec §3) carries `timestamp`, `syncTypes`, `succeeded`,
`message` and `restartError`. The plugin's `reportSelfSyncResult` consumes it (reads and deletes it) and shows:

| Situation | Notification |
|-----------|--------------|
| `succeeded` | info "Synced <types> for <name>" |
| `restartError` set | additionally error "Failed to reopen <name> after the sync: <error>. Details: <log>" |
| not `succeeded` | error "Sync of <types> for <name> failed: <message>. Details: <log>" |
| no result, helper exit code not 0 | error "sync-helper exited with code <n>. Details: <log>" |
| no result at startup, or exit code 0 | nothing |

`<types>` are the `SyncType` display names (all three read as "all settings"), `<log>` is the IDE's
`sync-helper.log`. It runs in two places:

- When the helper exits while the IDE still runs (§5.5): the helper failed before stopping the IDE (usage error, no
  ideDir, unknown project, JVM failure) or hit the 60 s shutdown timeout (e.g. a blocking dialog), and the IDE was not
  stopped.
- At startup of an isolated instance (`SelfSyncResultActivity`, `exitCode = null`): the relaunched IDE shows the
  outcome of the sync that stopped it. A relaunch that failed (`launch` threw, `restartError`) is shown at the next
  manual start.

## 7. Environment and portability

- Needs only the JVM of the IDE that starts it; runs on macOS, Linux and Windows wherever `core` does and has no
  platform-specific code of its own.
- A manual run through `bin/sync-helper` needs a system `java` (`JAVA_HOME`/`PATH`) and `-Dproject.juggler.base.dir`
  through `JAVA_OPTS`/`SYNC_HELPER_OPTS` for a non-default base dir.

## Known gaps

1. A crash of the relaunched IDE after it started is not reported: the helper exits right after launching, before
   core's crash watch (core-spec §7.3) can report a `SyncProgress.Error`.
2. The `bin/` start scripts are bundled and extracted but unused by the plugin.
3. A result file nobody consumes stays in `<projRoot>` if the user never opens that project isolated again; a later
   self-sync overwrites it.
4. The launcher (§5.6) points at the JBR and extraction of the IDE that wrote it. It breaks when that IDE is
   uninstalled, or when a plugin update in that IDE re-extracts the helper without a restart (the old extraction is
   deleted), until an IDE starts again. A stale record of another IDE only costs the dispatch (§4 step 3).
5. The front parses the arguments before dispatching, so a newer target helper's commands or flags that the front
   does not know are rejected as usage errors.
