# Fix the sync-helper known gaps

## Context

`docs/sync-helper-spec.md` ("Known gaps") lists the problems of the self-sync path: the plugin spawns the bundled
`sync-helper` and calls `Application.exit()` 100 ms later, the helper syncs the current isolated project from outside
and relaunches it. Today:

| Gap | Problem |
|-----|---------|
| 1 | Restart race: the IDE often exits before the helper's `isProjectOpen` check, so it is not relaunched; otherwise both the plugin's `exit()` and the helper's stop request close it. |
| 2 | `project.juggler.base.dir` is not passed on, so under `runIde` the helper syncs `~/.project-juggler`. |
| 3 | `--path <main project>` exits 1 with a stack trace (core's `SyncException`). |
| 4 | Errors end up only in `<IDE log dir>/sync-helper.log`. When the sync fails after the IDE stopped, nothing relaunches it. |
| 5 | No `exitProcess`. A call with no sync-type flag still stops and restarts the IDE without copying anything. (The "lingers 10 s" part is stale: `ProcessLauncherImpl`'s watcher thread is already a daemon.) |
| 6 | Unknown arguments are ignored, so `--plugin` gives an empty sync. |
| 7 | `--all-projects` runs in no defined order, stops at the first failure, and reports nothing. |
| 8 | The start script needs a system `java` (`JAVA_HOME`/`PATH`); the IDE's JBR is not used. |
| 10 | `IdeInstallation(idePath, "IDE")` creates a new ideDir with display name `"IDE"` when none is registered. |
| 11 | `.github/workflows/release.yml` builds a `:cli` module that no longer exists. |
| 12 | Argument parsing and target selection have no tests. |

Decisions made with the user:

- **Gap 1, how the IDE stops:** the plugin no longer exits by itself. It spawns the helper and keeps running. The
  helper finds the IDE running (`config/.lock`), writes `signals/stop-request.json` as for any other project, and
  `ShutdownSignalService` closes the IDE. Self-sync only happens in an isolated instance, where that service polls.
  This removes both the race and the double close, and the plugin stays alive long enough to notice a helper that
  fails before it stops the IDE.
- **Gap 7, sync of all projects:** the plugin syncs the other projects in-process with the existing `syncProjects`
  (progress, keeps going past failures, one notification), then hands only the current project to the helper. The
  helper's `--all-projects` is removed.
- **Gap 4, relaunch after a failed sync:** `ProjectLauncher.doWithLock` (core) relaunches whenever it stopped a running
  IDE and `autoRestart` is set, whatever the sync outcome, then rethrows. This also covers in-process syncs of other
  projects. The helper writes a result file, and the plugin shows it as a notification (at the next startup, or right
  away if the IDE is still running when the helper exits).
- **Gap 11:** delete `release.yml`. The plugin zip, which bundles the helper, is the only artifact.

Also decided while planning (no user input needed):

- **Gaps 2 and 8:** the plugin runs the helper's main class directly with the IDE's own JVM
  (`<java.home>/bin/java -cp <dir>/lib/* -Dproject.juggler.base.dir=<value> <main class> <args>`), so the start
  scripts are bypassed. Setting `JAVA_HOME` for the script instead would leak the JBR into the relaunched IDE's
  environment (its terminals and Gradle runs), because the relaunched IDE inherits the helper's environment.
- **Gap 10:** the helper only uses an existing ideDir (`IdeRegistry.findRepository`, new) and fails if there is none.
  The plugin always has one, since the project being synced is tracked there.
- **Gap 3:** no special case in the helper. Core's `SyncException` message ends up in the result file and the exit
  code is 1. The plugin never sends the main project to the helper anyway (it is never isolated).

## Target behavior

| Case | Result |
|------|--------|
| Sync <type> for the current isolated project | Info notification "IntelliJ will close to sync …". The helper stops the IDE through the stop request, syncs and relaunches it. The relaunched IDE shows "Synced <type> for <name>". |
| Sync fails after the IDE stopped (copy `IOException`, missing base path) | The IDE is relaunched anyway and shows an error notification with the message and the helper log path. |
| The IDE does not stop within 60 s (e.g. a blocking dialog) | The helper exits 1 while the IDE still runs; the plugin shows the timeout message right away. |
| The helper fails before syncing (bad args, no ideDir, unknown project, JVM failure) | The IDE keeps running. Error notification "sync-helper exited with code <n>; see <log>", or the result file's message if there is one. |
| Sync all projects, current one included | Others synced in-process with progress and the usual success/failure notification, then the self-sync above for the current project. Cancelling the in-process part skips the self-sync. |
| Self-sync from `runIde` | The helper uses the sandbox base dir. |
| Only the IDE's JBR, no system `java` | Works (the helper runs on `java.home`). |
| Relaunch itself fails (`launch` throws) | `restartError` in the result file, shown at the next manual start. A crash of the relaunched IDE after it started is not reported (the helper exits right after launching; known limitation). |

Helper CLI after the change:

```
sync-helper --ide <ide-path> --path <project-path> (--all | --vmoptions | --config | --plugins)...
```

- At least one sync-type flag, `--ide` and `--path` are required. Unknown tokens, a value flag without a value, a
  repeated `--ide`/`--path`, and `--all-projects` are usage errors.
- Exit codes: `0` synced, `1` sync failure (anything after the arguments parsed), `2` usage error (usage printed to
  stderr). `main` always ends with `exitProcess`.

## Implementation

### Core (`core/src/main/kotlin/com/projectjuggler/`)

- `process/ProjectLauncher.kt`, `doWithLock`: track `stopped` (true once `requestShutdownAndWait` returned).
  Run `block()` in a `try`, and in its `finally` (still inside the outer `try` that cleans signals and releases the
  lock) relaunch when `stopped && options.autoRestart`, using the existing launch-and-report code (the `Restarting`
  progress, `onFailure` → `SyncProgress.Error`, exceptions from `launch` → `SyncProgress.Error`). The `block()`
  exception propagates after the relaunch. On a timeout `stopped` stays false, so there is no relaunch (the IDE is
  still running). On the success path nothing changes (`stopped` implies `wasRunning && stopIfRunning`).
- `core/SyncOptions.kt`: make `SyncTypeNames` public (the plugin maps result `syncTypes` with it).
- `config/IdeRegistry.kt`: new `fun findRepository(executablePath: String): IdeConfigRepository?`, the registered
  dir whose stored installation has that executable path (`registeredRepositories()`), or null. It never creates a
  dir. `getRepository` keeps its behavior; reuse `findRepository` for its step 2.
- New `core/SelfSyncResult.kt`:
  - `@Serializable data class SelfSyncResult(timestamp: String, syncTypes: List<String>, succeeded: Boolean,
    message: String? = null, restartError: String? = null)`, where `syncTypes` uses `SyncTypeNames` values as in the
    stop request.
  - `class SelfSyncResultStore internal constructor(repository)` with `write(project, result)` and
    `consume(project): SelfSyncResult?` (read and delete under `FileLockUtils.withFileLock`; a corrupt file is
    deleted and gives null). File: `<projRoot>/self-sync-result.json`, outside `signals/` because
    `ShutdownSignalManager.cleanup` empties that dir. Resolve `<projRoot>` the way `ShutdownSignalManager`
    resolves its signals dir. Use the same JSON settings as `ShutdownSignalManager`.
  - `getInstance(ideConfigRepository)` companion and `scoped { SelfSyncResultStore(get()) }` in
    `di/CoreModule.kt` `ideScopedModule`, like `ShutdownSignalManager`.

### sync-helper (`sync-helper/src/main/kotlin/com/projectjuggler/synchelper/`)

- New `SyncHelperArgs.kt`: `internal data class SyncHelperArgs(idePath, projectPath, syncVmOptions, syncConfig,
  syncPlugins)`, `internal class UsageException(message) : Exception`, and `internal fun parseArgs(args:
  Array<String>): SyncHelperArgs` with the rules under "Target behavior". `internal const val USAGE` holds the usage
  text.
- `SyncHelperMain.kt`:
  - `fun main(args) { KoinInit.init(); exitProcess(run(args)) }`.
  - `internal fun run(args, registry = IdeRegistry.getInstance(), launcherFor: (IdeConfigRepository) ->
    ProjectLauncher = { ProjectLauncher.getInstance(it) }): Int`:
    1. `parseArgs`; on `UsageException`, print the message and `USAGE` to stderr and return 2.
    2. `registry.findRepository(idePath)` or fail with "No Project Juggler data for IDE <path>" (exit 1, no result
       file because there is no project).
    3. `ProjectManager.resolvePath` + `get`, or fail with "Project not found: <raw>" (exit 1, no result file).
    4. `syncProject(project, …, SyncOptions(stopIfRunning = true, autoRestart = true, shutdownTimeout = 60,
       onProgress = …))`. `onProgress` prints one line per event to stdout (it lands in `sync-helper.log`) and keeps
       the last `SyncProgress.Error` message as `restartError`.
    5. Write `SelfSyncResult(succeeded = true, restartError)` and return 0. On a `Throwable` from step 4, print the
       stack trace, write `SelfSyncResult(succeeded = false, message = <message, or class name if none>,
       restartError)` and return 1. A failure to write the result is printed and does not change the exit code.
- `sync-helper/build.gradle.kts`: test deps like `core/build.gradle.kts` (`kotest-runner-junit5`, `mockk`,
  `koin-test`) plus `testImplementation(testFixtures(project(":core")))`. `tasks.test { useJUnitPlatform();
  systemProperty("project.juggler.base.dir", <build>/project-juggler-test) }`, as in `plugin/build.gradle.kts`.

### Plugin (`plugin/src/main/kotlin/com/projectjuggler/plugin/`)

- `util/BundledSyncHelper.kt`:
  - `getExecutable()` becomes `getInstallDir(): Path`. The marker for a complete extraction is
    `lib/sync-helper.jar` instead of the start script. The executable-bit step and `executableIn` go away; `bin/`
    stays in the bundle for manual runs.
  - `internal fun command(installDir, javaHome: Path, baseDir: String?, args): List<String>` builds
    `<javaHome>/bin/java[.exe] -cp <installDir>/lib/* [-Dproject.juggler.base.dir=<baseDir>] <MAIN_CLASS> <args>`.
    `MAIN_CLASS = "com.projectjuggler.synchelper.SyncHelperMainKt"`, base dir from
    `System.getProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR)`, java home from `System.getProperty("java.home")`,
    platform from core's `Platform.current()`. `java -cp` expands the `*` itself, so no shell is needed.
  - `start(args, logFile)` uses `command(...)`; the redirection stays the same.
- `actions/Sync.kt`, `performSelfShutdownSync(project, notificationMessage, helperArgs)`:
  - The task starts the helper and keeps the `Process`. Remove the `Thread.sleep(100)` and the `exit()` in
    `onSuccess`.
  - `process.onExit().thenAccept { reportHelperExit(...) }`: when the helper exits and the IDE is still running
    (the application is not disposed or exiting), report through the same service function as the startup activity
    (below) with the exit code. If the IDE is exiting, do nothing; the relaunched IDE reports at startup.
  - `performSyncWithProgress` gets `onFinished: () -> Unit = {}`, called at the end of `run` after the
    notification, also when some projects failed, but not on cancellation (`ProcessCanceledException` propagates
    from `syncProjects`).
- `services/SyncPlanner.kt`: `SyncTarget.SelfShutdown(helperArgs, others: List<ProjectMetadata> = emptyList())`.
  `forAllProjects` returns it with `--path <current project path>` and `others` = the syncable projects minus the
  current one. `helperArgs` is always `--ide <exe> --path <p> <flag>`; update the KDoc.
- `actions/recent/SyncAllProjectsAction.kt`: on `SelfShutdown` with non-empty `others`, `performSyncWithProgress(others,
  …, onFinished = { performSelfShutdownSync(...) })`; with empty `others`, self-sync directly.
  `onFinished` runs on the task's pooled thread, which is what `performSelfShutdownSync`
  (`@RequiresBackgroundThread`) needs.
- New `services/SelfSyncReport.kt`: `internal fun reportSelfSyncResult(repository, projectId: ProjectId,
  exitCode: Int?, logFile: Path, notifications: NotificationHandler)`. It consumes the `SelfSyncResult` of that
  project:
  - success: info `notification.self.sync.succeeded` ("Synced {0} for {1}"), plus an error
    `notification.self.sync.restart.failed` if `restartError` is set;
  - failure: error `notification.self.sync.failed` ("Sync of {0} for {1} failed: {2}. Details: {3}");
  - no result file and `exitCode` not null and not 0: error `notification.self.sync.helper.exited` ("sync-helper
    exited with code {0}. Details: {1}");
  - no file and `exitCode` null (startup) or 0: nothing.
  Map `syncTypes` back to display names through `SyncType`, joined with ", ". This needs `SyncTypeNames`
  (`core/SyncOptions.kt`, now `internal`) made public in session 1. The project name comes from the
  metadata (skip the report if it is gone). Catch `Throwable` and log it, per the project rules.
- New `startup/SelfSyncResultActivity.kt` (`ProjectActivity`, registered in `plugin.xml` next to the other
  `postStartupActivity` entries): in an isolated instance (`IsolatedInstance.currentProjectId()` not null), on
  `Dispatchers.IO`, `reportSelfSyncResult(currentIdeConfigRepository, id, exitCode = null,
  PathManager.getLogDir().resolve(BundledSyncHelper.LOG_FILE_NAME), IntelliJNotificationHandler(project))`. Style as
  in `StaleMainProjectCleanupActivity`.
- `messages/ProjectJugglerBundle.properties`: add the keys above. Reword `notification.info.sync.self.shutdown.all` to
  say that the other projects sync first and then IntelliJ closes to sync this one and reopens.

### Build

- Delete `.github/workflows/release.yml`, and remove its line from `CLAUDE.md`.

## Tests

- `core/.../process/ProjectLauncherSyncTest` (extend): a running project whose sync throws (e.g. vmoptions with no
  base path configured) is relaunched, and the exception propagates. A shutdown timeout does not relaunch. Assert
  through the mocked `ProcessLauncher`, as the existing restart tests do.
- `core/.../config/IdeRegistryTest` (extend): `findRepository` returns the registered dir under any display name,
  and null for an unknown executable without creating a dir.
- `core/.../core/SelfSyncResultStoreTest` (new, `KoinTestExtension`): a write followed by a consume gives the result
  and deletes the file; a second consume gives null; a corrupt file gives null and is deleted; the result file
  survives `ShutdownSignalManager.cleanup`.
- `sync-helper/src/test/kotlin/com/projectjuggler/synchelper/SyncHelperArgsTest` (new, pure): valid combinations,
  `--all` implies all three, a missing `--ide`/`--path`, no type flag, an unknown flag, `--all-projects`, a trailing
  value flag, a repeated `--path`.
- `sync-helper/.../SyncHelperRunTest` (new, `KoinTestExtension`, temp dir under the test base dir): register an
  ideDir with `IdeConfigRepository(<base>/v2/<dir>, IdeInstallation(...)).save(...)` and a project
  (`ProjectManager`), pass a mocked `ProjectLauncher` through `launcherFor`. Cases: success → 0, result file
  `succeeded`, and `syncProject` called with the flags and `stopIfRunning`/`autoRestart`; `syncProject` throws → 1
  and a failure result with the message; a `SyncProgress.Error` sent through `onProgress` → `restartError`; usage
  error → 2; unknown IDE → 1, no dir created; unknown project → 1.
- `plugin/.../util/BundledSyncHelperTest` (extend): `command` on Unix and Windows, with and without a base dir.
  Update the extraction cases for the jar marker.
- `plugin/.../services/SyncPlannerTest` (update): `forAllProjects` with the current project gives `--path <current>`
  and `others` without it; the main project is still excluded.
- `plugin/.../services/SelfSyncReportTest` (new, `TestApplicationExtension`, `RecordingNotificationHandler`): every
  case of `reportSelfSyncResult`, and that the file is consumed (a second call shows nothing).

Not testable automatically, checked by hand (Verification step 6): the spawn, the stop through
`ShutdownSignalService`, the relaunch, and the notification at startup.

## Docs

- `docs/sync-helper-spec.md`: rewrite §3 (CLI, exit codes), §4 (execution, result file, `findRepository`), §5 (java
  command line, no self-exit, process watching, sync-all split), §6 and §7. Remove the fixed gaps; keep only the
  limitations that are left (no report of a crash after relaunch; `bin/` scripts unused by the plugin; a result file
  that nobody consumes if the user never opens the project again).
- `docs/core-spec.md` §3 layout (`self-sync-result.json`), §4 (`findRepository`), §8 step 5 (relaunch also after a
  failed sync), §8.2.
- `docs/plugin-spec.md`: §1 (drop the gap-2 remark), §6 (sync all split, self-shutdown flow), §9 (command line,
  marker), a new startup-activity paragraph, §13 test table rows, and the matching known gaps.
- `CLAUDE.md`: the "Sync of the current IDE" bullet (helper CLI without `--all-projects`, stop through the stop
  request, result notification), and remove the `release.yml` sentence.

## Sessions

Opus 5.5 at the current effort, 4 sessions, in this order (each one depends on the previous).

1. **Core.** Implements "Core" and "Build" (delete `release.yml` and its `CLAUDE.md` sentence). Reads first:
   `ProjectLauncher.kt`, `IdeRegistry.kt`, `ShutdownSignalManager.kt`, `di/CoreModule.kt`, `ProjectLauncherSyncTest`
   (restart cases around lines 217–260 and 719), `IdeRegistryTest`. Tests: the core cases above. Done when
   `./gradlew :core:test` passes. ~20%.
2. **sync-helper.** Implements "sync-helper" and its tests. Reads first: `SyncHelperMain.kt`,
   `sync-helper/build.gradle.kts`, `core/src/testFixtures` (`KoinTestExtension`, `createTempDir`), the
   `SelfSyncResultStore` from session 1. Done when `./gradlew :sync-helper:test :plugin:compileKotlin` passes (the
   plugin still compiles; it passes `--all-projects` until session 3, which is acceptable in between). ~20%.
3. **Plugin launch and planning.** Implements `BundledSyncHelper`, `Sync.kt` (no self-exit, `onFinished`, keep the
   `Process`; for now `reportHelperExit` only logs and shows `notification.self.sync.helper.exited` on a non-zero
   exit), `SyncPlanner`, `SyncAllProjectsAction`, the reworded bundle text, and their tests. Reads first:
   `BundledSyncHelper.kt` and its test, `Sync.kt`, `SyncPlanner.kt` and its test, `SyncAllProjectsAction.kt`,
   `SyncSettingsSubAction.kt`. Done when `./gradlew :plugin:test` passes. ~25%.
4. **Result report, docs, finishing.** Implements `SelfSyncReport.kt`, `SelfSyncResultActivity`, the `plugin.xml`
   entry and bundle keys, wires `reportHelperExit` to `reportSelfSyncResult`, adds `SelfSyncReportTest`, does the
   manual check, updates the docs and does the full "Finishing". Reads first: `StaleMainProjectCleanupActivity.kt`,
   `IntelliJNotificationHandler.kt`, `PluginTestSupport.kt`/`RecordingNotificationHandler.kt`, then the three specs.
   ~25%.

## Verification

1. `./gradlew :core:test`
2. `./gradlew :sync-helper:test`
3. `./gradlew :plugin:test`
4. `./gradlew build`
5. `./gradlew :plugin:verifyPlugin` (sessions 3 and 4)
6. Manual check (session 4), in `./gradlew :plugin:runIde`:
   1. Configure base paths, open a project isolated from Recent Juggled Projects, and in that isolated instance run
      Sync Plugins on the same project. The IDE closes, reopens, and shows "Synced plugins for <name>". The
      helper ran against `plugin/build/project-juggler-sandbox` (first lines of `<projRoot>/logs/sync-helper.log`).
   2. In the main instance, set the base vmoptions path to a missing file in Settings > Tools > Project Juggler, then
      Sync VM Options for the isolated project from inside it. The IDE reopens and shows the failure with the log
      path.
   3. Track two more projects and run Sync Plugins (all projects) from the isolated instance: progress for the other
      two, their notification, then close, reopen, and the self-sync notification.
   4. With `PATH` and `JAVA_HOME` lacking `java` (start the sandbox IDE from a shell with them cleared, if
      practical), step 1 still works.

## Finishing

- Move the decisions above (stop through the stop request, relaunch after a failed sync, result file, sync-all split,
  JBR command line) and the remaining limitations into the docs per `## Docs`, and update `CLAUDE.md`.
- Delete `plans/sync-helper-gaps.md` in the same commit as session 4.
- Each session commits its own work: check `git status`/`git diff`, stage only its files by path (never
  `git add -A`/`-a`), and write the message like the recent commits (lowercase imperative subject, e.g.
  `relaunch after a failed self-sync and report it at startup`, then a body with what changed and why). Mark the
  session's entry above as `**Done** in <commit subject>`, and record any deviation that later sessions need. List
  changes left out of the commit in the reply.
