# Spec review fixes: defects and architecture seams

Fixes for the defects and seams found in the spec review of 2026-10-31. The specs in `docs/` already describe each
defect as a known gap: core gaps 14-18 (`docs/core-spec.md`), plugin gaps 17-19 (`docs/plugin-spec.md`),
sync-helper gap 1 (`docs/sync-helper-spec.md`). Each session fixes code, tests and the spec sections together and
removes the closed gap entries (gap numbers are never reused, see the core spec's Known gaps intro).

## Problem

- `config.json`/`metadata.json` are decoded strictly; an extra field from a newer plugin version makes the file
  "corrupt" and the IDE loses its settings or vanishes from `IdeRegistry` (core gap 14).
- `IsolatedInstance.projectIdFromConfigPath` takes the first `projects` segment; a base dir such as
  `~/projects/pj` makes an isolated IDE behave as the main one (plugin gap 17). "Am I isolated" is decided in three
  unrelated ways across the plugin.
- Sync failure messages refer to a removed CLI (core gap 18); `acquireSyncLock` reports I/O errors as "already in
  progress" (core gap 16); `IdeConfig.baseVmOptionsHash` is dead state (core gap 15); debug ports are allocated
  without a cross-process lock (core gap 17).
- The main-project prompt writes `config.json` on EDT (plugin gap 18); the helper-exit callback can lose an
  exception (plugin gap 19); sync-helper exits before the relaunch crash watch can report (sync-helper gap 1).
- Seams: `SyncType` lives in `actions/recent/` but services depend on it and duplicate the CLI flag mapping of
  `SyncHelperArgs`; core logs with `println` so nothing reaches `idea.log`; core's `NotificationHandler` is unused
  and first-open copy failures are silent; `IdeScopeManager` is a hand-rolled singleton beside Koin; the main project
  is matched by exact string.

Decisions made with the user:

- `NotificationHandler` stays in core and is used for first-open copy failures (not moved to the plugin).
- Both the `CoreLog` seam and moving `IdeScopeManager` into Koin are in scope.
- Gap 17 gets a one-line stopgap in session 2 (`lastIndexOf`), then the real fix in session 6 (`CurrentInstance`).
- Main project identity: normalized `Path` equality without filesystem access (both writers already store
  `PathUtils.resolveAbsolute` output); `isSamePath` would stat on every lock poll.

## Target behavior

| Case | After |
|---|---|
| `config.json` has a field this version does not know | Loads; the field is dropped on the next write |
| Base dir `~/projects/pj`, isolated IDE | Polls stop requests, self-sync uses the helper, no base-path capture |
| Sync with an unset base config path | Error names Settings > Tools > Project Juggler, not a CLI |
| `sync-in-progress.lock` cannot be opened (permissions) | Error says why, not "already in progress" |
| Two IDE processes launch new projects at once | Different debug ports |
| "Don't ask again" in the main-project prompt | Persisted on a pooled thread |
| Relaunched IDE crashes within 10 s after a self-sync | `restartError` in `self-sync-result.json`, shown next start |
| First-open copy of base config or plugins fails | Error notification naming source, destination and cause |
| Core diagnostics inside the IDE | In `idea.log` under the `com.projectjuggler` categories |
| Main project stored as `/a/b/../c` style path | Still recognized as main (normalized comparison) |

## Implementation

### A. Lenient JSON (core gap 14)

`core/.../config/IdeConfigRepository.kt`: add `ignoreUnknownKeys = true` to the private `json`. Same for
`RecentProjectsIndex.json` (harmless, keeps the three readers alike). Core-spec §3: "Unknown fields are ignored".

### B. Stopgap for the `projects` segment (plugin gap 17)

`plugin/.../services/IsolatedInstance.kt:21`: use `parts.lastIndexOf("projects")`. Spec §6.3: "the last
`projects` segment". Keep gap 17 open with a note that session 6 removes the parser.

### C. Error messages (core gap 18)

`DirectoryManager.syncConfigFromBase`/`syncPluginsFromBase`, `ProjectLauncher.doSync`: messages become
"Base config directory is not set and could not be detected. Set it in the Project Juggler settings
(Settings > Tools > Project Juggler)." etc. Core has no bundle; the plugin shows `userMessage()` verbatim.

### D. Prompt write off EDT (plugin gap 18)

`plugin/.../services/MainProjectPromptService.kt`: `showMainProjectPrompt` returns
`MainProjectPrompt(result: MainProjectPromptResult, dontAskAgain: Boolean)` and no longer takes the repository.
`Launch.kt` `askToOpenAsMainProject`: inside the existing `executeOnPooledThread`, call
`MainProjectService.setDontAskAboutMainProject(repository, true)` when `dontAskAgain` before the `when`.
Plugin-spec §4.3 and §12.

### E. Helper-exit callback (plugin gap 19)

`plugin/.../actions/Sync.kt`: `process.onExit().thenAccept { reportHelperExit(project, it.exitValue(), logFile,
appClosing.get(), connection) }` and disconnect inside `reportHelperExit`'s `try` (first statement). Spec §6.5.

### F. Sync lock errors (core gap 16)

`ShutdownSignalManager.acquireSyncLock`: only `OverlappingFileLockException` and a null `tryLock()` mean "in
progress" (return null); any other `Throwable` propagates as `SyncException("Cannot lock sync for '<name>': <msg>")`
(constructor gains `cause`). `readStopRequest`, `SyncLock.close`: `catch (e: Throwable)`. Core-spec §8 step 1, §12.

### G. Port allocation lock (core gap 17)

`ProjectManager.ensureDebugPort`: wrap read-all, pick and `saveProjectMetadata` in
`FileLockUtils.withFileLock(ideConfigRepository.baseDir.resolve("ports.lock"))`. `IdeConfigRepository` gets
`val portsLockFile: Path` next to `getConfigFile()`. Core-spec §3 layout (`ports.lock`), §4 `ensureDebugPort`.
Lock order: this lock is taken before any `metadata.json` lock and never while holding one (document in §3).

### H. Drop the IDE-level vmoptions hash and unused members (core gap 15, gap 8 note)

Depends on A (old files keep the field). Remove `IdeConfig.baseVmOptionsHash`, `BaseVMOptionsTracker.hasChanged`
and `updateHash`, the step-1 block in `ProjectLauncher.launch`, and the `updateHash()` calls in
`plugin/.../services/JugglerSettings.kt:114` and `IdePathSyncDetector.kt:65,107` (drop the now-unused
`vmOptionsChanged`/`filled` logic only where it existed solely for the hash). Delete `ProjectManager.validatePathExists`
and `IntelliJLocator.findIntelliJ`. Specs: core §3.1 table, §6 (`BaseVMOptionsTracker` paragraph), §7.1 step 1;
plugin §2 (`autoPopulateIfNeeded` "hash is refreshed"), §7 (`updatePaths`), §7a ("refreshes `baseVmOptionsHash`").
Gap 8: drop the `findIntelliJ()` sentence.

### I. Wait for the relaunch watch (sync-helper gap 1)

`ProcessLauncher.launch` returns `CompletableFuture<Void>` completed when the watcher thread finishes (after the
exit or `watchTimeout`), also on the Windows path. `IntelliJLauncher.launch`/`launchMain` and
`ProjectLauncher.launch` return it. `ProjectLauncher.syncProject` returns `CompletableFuture<Void>`: the relaunch's
future when it relaunched, else `CompletableFuture.completedFuture(null)`; `restart()` stores it. `SyncHelperMain.run`
calls `.join()` on it before `writeResult`, so `restartError` carries a crash within the watch window and the
launcher-log files are deleted. Plugin callers ignore the value. Mocks: every `every { launch(...) } just runs` and
`syncProject(...) just runs` in core, plugin and sync-helper tests becomes `returns
CompletableFuture.completedFuture(null)` (26 sites, `grep -rn 'launch(any()'`). Specs: core §7.3 ("returns a future
that completes when the watch ends"), §8 step 5; sync-helper §4 step 5 and remove gap 1.

### J. `SyncType` to services, flags in core

`core/.../core/SyncOptions.kt` `SyncTypeNames`: add `const val ALL = "all"`, `fun flag(name) = "--$name"`, and
`val FLAGS = listOf(ALL, VM_OPTIONS, CONFIG, PLUGINS).map(::flag)`. `SyncHelperArgs.parseArgs` matches
`SyncTypeNames.flag(...)` instead of literals; `USAGE` stays literal (it is documentation). Move `SyncType` to
`plugin/.../services/SyncType.kt` (same `sealed class`, `cliFlag` = `SyncTypeNames.flag(name)`, add `val name`
with the core constant). Update imports in `actions/recent/*`, `actions/Sync.kt`, `services/SyncPlanner.kt`,
`services/SyncProjects.kt`, `services/SelfSyncReport.kt` (`SYNC_TYPES_BY_NAME` can use `SyncType.name`), tests
`SyncTypeTest` (move to `services`), `ProjectJugglerBundleTest`. CLAUDE.md: remove "`SyncType` sealed class" from
`actions/recent/`, add it to `services/`. Plugin-spec §6 first paragraph, §13 table rows; sync-helper §3, §5.4.

### K. `CurrentInstance` (plugin gap 17, real fix)

New `plugin/.../services/CurrentInstance.kt`:

```kotlin
internal sealed interface CurrentInstance {
    data object Main : CurrentInstance
    data class Isolated(val projectId: ProjectId) : CurrentInstance
    companion object {
        fun of(repository: IdeConfigRepository, configDir: Path): CurrentInstance  // pure, for tests
        // of(IdeInstallationService.currentIdeConfigRepository, PathManager.getConfigDir()), cached lazily
        fun current(): CurrentInstance
    }
}
```

`of` resolves `configDir` against `DirectoryManager.getInstance(repository).projectsDir` (make `projectsDir` a
public `val`): `Isolated(ProjectId(<first segment of the relative path>))` when the normalized dir is
`projectsDir/<id>/config`, `Main` otherwise. Replace: `IsolatedInstance.currentProjectId()` in
`ShutdownSignalService.detectCurrentProject`, `actions/Sync.kt reportHelperExit`, `SelfSyncResultActivity`;
`IsolatedInstance.isCurrentProject` in `SyncPlanner` (default parameter becomes `CurrentInstance.current()`);
`IdePathSyncDetector.isMainInstance` and `ProjectJugglerConfigurable.kt:58` (`CurrentInstance.of(repo,
current.configDir)
is Main`); `belongsToCurrentIde` keeps `isRunningInMainInstance` for the main project and uses `of(...)` for the
isolated branch. `MainProjectService.isRunningInMainInstance` stays in core (it also needs `baseConfigPath`) and
starts with `DirectoryManager.isIsolatedConfigDir` as today. Delete `IsolatedInstance.kt` and its test; new
`CurrentInstanceTest` (pure, `testRepository`). Plugin-spec: §6.3 becomes "`CurrentInstance`", update §2, §7, §7a,
§10, §10b, §12, §13; remove gap 17. CLAUDE.md: `IsolatedInstance` -> `CurrentInstance` in the plugin line and the
"Shutdown side" paragraph.

### L. `CoreLog` seam

New `core/.../util/CoreLog.kt`: `interface CoreLog { fun info(msg: String); fun warn(msg: String, t: Throwable? =
null); fun error(msg: String, t: Throwable? = null) }` and `object StderrLog : CoreLog`. `coreModule` registers
`single<CoreLog> { StderrLog }`; core code resolves it through a top-level `internal fun coreLog(): CoreLog =
GlobalContext.get().get()`. The plugin's `pluginModule` binds `IntelliJCoreLog`
(`Logger.getInstance("#com.projectjuggler.core")`) instead; `KoinInit.init` must call `allowOverride(true)` on the
`KoinApplication` (koin-core 4.1.1 sources jar in the Gradle cache, `commonMain/org/koin/core/KoinApplication.kt:85`).
Replace every `println`/`System.err.println` in `ProcessLauncherImpl` (11), `IntelliJLauncher` (5),
`IdeConfigRepository` (2), `RecentProjectsIndex`, `DirectoryCopier`, `PathUtils`, `ShutdownSignalManager`,
`SelfSyncResultStore` (1 each). sync-helper keeps the default `StderrLog`, so `sync-helper.log` is unchanged.
Core-spec §1: "Core logs through `CoreLog` (stderr by default; the plugin binds it to the IDE logger)"; drop the
"print status lines to stdout/stderr" sentence and the "logged to stderr" phrases in §3.1-§3.3, §8.2, §11.

### M. Copy failures through `NotificationHandler`

Depends on L. `DirectoryCopier.copyIfFirstOpen` returns a `CopyResult` (`Copied`, `Skipped`, `Failed(cause)`).
`DirectoryManager.ensureProjectDirectories(project, notifications: NotificationHandler? = null)` reports a `Failed`
with "Could not copy base <config|plugins> from <src> to <dst>: <cause>. The isolated IDE starts empty; use Sync to
retry." `IntelliJLauncher.launch` and `ProjectLauncher.launch(path, onFailure, notifications)` pass it through;
the plugin passes its `IntelliJNotificationHandler` from `launchProject` and `createAndLaunchProjectCopy`. No new
bundle key (core text; core has no bundle). Core-spec §1 ("`NotificationHandler` is used for first-open copy
failures"), §7.1 ("Copy failures are reported through the handler and otherwise swallowed").

### N. `IdeScopeManager` in Koin

`coreModule`: `single { IdeScopeManager() }`; `IdeConfigRepository.getScope()` resolves `GlobalContext.get().get<
IdeScopeManager>()`; drop the companion `getInstance`/`reset`/`instance`. `KoinInit.stop()`:
`GlobalContext.get().get<IdeScopeManager>().closeAllScopes(); stopKoin()`. `KoinTestExtension.afterTest` resolves the
same way; `afterSpec` just `stopKoin()`. `IdeScopeManagerTest`, `KoinInitTest` adjust. Core-spec §5.

### O. Main project identity

`MainProjectService.isMainProject` and `ProjectManager.listSyncable`: compare
`Path.of(a).toAbsolutePath().normalize() == Path.of(b).toAbsolutePath().normalize()` through a new
`PathUtils.isSameNormalizedPath(a: String, b: String)` (no I/O). `setMainProject` stores
`PathUtils.resolveAbsolute(pathString).toString()`. Core-spec §7.2 and §4 (`listSyncable`), §11 (`PathUtils`).

## Tests

Kotest `StringSpec` + MockK; plugin specs per `docs/plugin-spec.md` §13 (`KoinTestExtension`,
`TestApplicationExtension` only where a real `Application` is needed, `expectLoggedError` around `LOG.error`).

- A: `IdeConfigRepositoryTest`: "loads config.json with an unknown field"; `ModelSerializationTest` likewise for
  metadata. `IdeRegistryTest`: a dir whose config has an extra field is still registered.
- B/K: `IsolatedInstanceTest` (B): "/u/projects/pj/v2/ide/projects/p-1/config" gives `p-1`. `CurrentInstanceTest`
  (K): main config dir, isolated dir, isolated dir under a base dir with a `projects` segment, `system` dir under the
  project is `Main`, Windows-style path via `Path.of`.
- C: `ProjectLauncherSyncTest`/`DirectoryManagerTest`: message contains "Project Juggler settings".
- D: no Swing test; `LaunchPlanTest` unchanged. Manual: open a project from the main IDE, tick "Don't ask again",
  check `config.json` has `dontAskAboutMainProject: true`.
- E: not unit-testable (process callback); manual run in the sandbox (`./gradlew :plugin:runIde`, sync the current
  project, kill the helper from a terminal, expect a notification and no silent failure in `idea.log`).
- F: `ShutdownSignalManagerTest`: a lock file that is a directory throws `SyncException` with the cause message;
  overlapping lock still returns null.
- G: `ProjectManagerTest`: "allocates distinct ports from two repositories on the same ide dir in parallel" (two
  `IdeConfigRepository` instances, same dir, 20 threads, all ports distinct).
- H: delete `BaseVMOptionsTrackerTest` cases for `hasChanged`/`updateHash`; fix `ModelSerializationTest:52`,
  `IntegrationTest`, `LaunchProjectTest`, `ProjectLauncherLaunchTest`, `IdePathSyncDetectorTest`,
  `JugglerSettingsTest` where they assert the IDE-level hash.
- I: `ProcessLauncherImplTest`: the returned future completes after a fast-exiting process and after `watchTimeout`
  for a sleeping one. `ProjectLauncherSyncTest`: `syncProject` returns a completed future without relaunch, the
  launcher's future with one. `SyncHelperRunTest`: a launcher whose `syncProject` returns a future that completes
  with an `Error` progress 100 ms later yields `restartError` in the result file.
- J: `SyncTypeTest` (moved): `cliFlag` equals `SyncTypeNames.flag(name)`; `SyncHelperArgsTest` unchanged
  behavior; `SyncPlannerTest` unchanged.
- L: `CoreLogTest`: `coreLog()` is `StderrLog` under `KoinTestExtension`; `PluginKoinTest`: after
  `IdeInstallationService.getInstance()` the `CoreLog` is `IntelliJCoreLog`.
- M: `DirectoryManagerTest`: an unreadable source (or a destination made read-only on Unix, as `RemoveProjectTest`
  does) records one error through a `RecordingNotificationHandler`-like core fake; `LaunchProjectTest` (plugin):
  the handler passed to `launchProject` reaches `launcher.launch`.
- N: `KoinInitTest`: `stop()` closes scopes; `IdeScopeManagerTest` resolves the manager from Koin.
- O: `MainProjectServiceTest`: `/a/./b` vs `/a/b` is main; `ProjectManagerTest.listSyncable` excludes it.

## Docs

Spec sections per item above (`docs/core-spec.md`, `docs/plugin-spec.md`, `docs/sync-helper-spec.md`); each
session removes the gap entries it closes. CLAUDE.md: `SyncType` placement (J), `IsolatedInstance` ->
`CurrentInstance` (K), a sentence under Development Guidelines: "Core logs through `CoreLog` (`coreLog()`), never
`println`" (L). README needs no change.

## Sessions

Current model at its current effort; each session at most 25% of context. Sessions 1-2 are independent of each
other; 3 needs 1; 4, 5 are independent of everything before; 6 needs 2; 7 needs 4 (both touch `ProcessLauncherImpl`);
8 needs 7; 9 needs 8 (last, does Finishing).

1. **Core quick fixes** (A, C, F, G): `IdeConfigRepository`, `RecentProjectsIndex`, `DirectoryManager`,
   `ProjectLauncher.doSync`, `ShutdownSignalManager`, `ProjectManager`; tests listed; core-spec §3, §3.1-3.3, §4, §8,
   §12; remove gaps 14, 16, 17, 18. Reads first: core-spec §3, §4, §8, `FileLockUtils.kt`. Done when
   `./gradlew :core:test` passes. ~15%.
2. **Plugin quick fixes** (B, D, E): `IsolatedInstance.kt`, `MainProjectPromptService.kt`, `Launch.kt`, `Sync.kt`;
   `IsolatedInstanceTest`; plugin-spec §4.3, §6.3, §6.5, §12; remove gaps 18, 19, annotate 17. Reads first:
   plugin-spec §4.2-4.3, §6.5. Done when `./gradlew :plugin:test` passes and the manual check for D is noted in the
   commit body. ~12%.
3. **Dead state** (H): files in H; core and plugin tests in H; specs in H; remove gap 15. Reads first: core-spec §6,
   §7.1; `BaseVMOptionsTracker.kt`, `IdePathSyncDetector.kt`, `JugglerSettings.kt`. Done when `./gradlew test`
   passes. ~15%.
4. **Launch watch future** (I): `ProcessLauncher.kt`, `IntelliJLauncher.kt`, `ProjectLauncher.kt`,
   `SyncHelperMain.kt`, the 26 mock sites; tests in I; core-spec §7.3, §8; sync-helper §4, remove gap 1. Reads
   first: core-spec §7.3, §8 step 5; sync-helper §4; `ProcessLauncherImplTest.kt`. Done when `./gradlew test`
   passes. ~20%.
5. **SyncType and flags** (J): files in J; `SyncTypeTest`, `SyncHelperArgsTest`; specs and CLAUDE.md in J. Reads
   first: `SyncOptions.kt`, `SyncHelperArgs.kt`, `SyncType.kt`. Done when `./gradlew test` passes. ~12%.
6. **CurrentInstance** (K): files in K; `CurrentInstanceTest`, adjust `SyncPlannerTest`, `IdePathSyncDetectorTest`,
   `IdePathsStartupCheckTest`, `CurrentIdeProjectTest`; plugin-spec and CLAUDE.md in K; remove gap 17. Reads
   first: plugin-spec §2, §6.3, §7, §10, §10b; `IsolatedInstance.kt`, `IdePathSyncDetector.kt`, `CurrentIdeProject.kt`,
   `ShutdownSignalService.kt`. Done when `./gradlew :plugin:test` passes and the sandbox IDE, started isolated from
   the popup, logs "ShutdownSignalService initialized for project". ~22%.
7. **CoreLog** (L): files in L, `CoreModule.kt`, `KoinInit.kt`, `PluginModule.kt`; `CoreLogTest`, `PluginKoinTest`;
   core-spec §1 and the "stderr" phrases; CLAUDE.md guideline. Reads first: `KoinInit.kt`, `PluginModule.kt`, the Koin
   override API (see L). Done when `./gradlew test` passes and `runIde` shows `[ProcessLauncher]` lines in
   `idea.log`. ~20%.
8. **Copy failures and main-project identity** (M, O): files in M and O; tests in M and O; core-spec §1, §4, §7.1,
   §7.2, §11. Reads first: `DirectoryCopier.kt`, `DirectoryManager.kt`, `LaunchProject.kt`, `OpenProjectCopy.kt`.
   Done when `./gradlew test` passes. ~18%.
9. **IdeScopeManager in Koin and finishing** (N): files in N; `KoinInitTest`, `IdeScopeManagerTest`,
   `KoinTestExtension`; core-spec §5; then the full Finishing below. Reads first: `IdeScopeManager.kt`, `KoinInit.kt`,
   `KoinTestExtension.kt`. ~12%.

## Verification

1. `./gradlew :core:test` (sessions 1, 3, 4, 7, 8, 9), `./gradlew :plugin:test` (2, 3, 5, 6, 7, 8, 9),
   `./gradlew :sync-helper:test` (4, 5).
2. `./gradlew build` before each commit (also compiles the plugin against the platform).
3. `./gradlew :plugin:verifyPlugin` after session 9.
4. Manual, in `./gradlew :plugin:runIde`: open a project isolated from the popup; in it, Sync All Settings for
   itself and confirm the IDE closes, reopens and shows "Synced all settings for <name>"; with the sandbox base dir
   overridden to a path containing `projects` (`-PprojectJuggler.baseDir=/tmp/projects/pj`), repeat (sessions 2
   and 6); tick "Don't ask again" in the prompt and check `config.json` (session 2).

## Finishing

- Make sure every closed gap entry is gone from the three specs and the surviving ones still describe the code;
  CLAUDE.md reflects `CurrentInstance`, `SyncType` in `services/`, and the `CoreLog` guideline.
- Delete `plans/spec-review-fixes.md` in the same commit as session 9's implementation.
- Commit per session: `git status`/`git diff` first; stage only this work's files by path (never `git add -A`);
  subject in the imperative lowercase style of the recent commits ("relaunch a stopped ide after a failed sync ..."),
  body with what changed and why, ending with the attribution line the session's system reminder gives. Each session
  marks its entry above `**Done** in <subject>` and records deviations that later sessions need. List anything left
  out of the commit in the reply.
