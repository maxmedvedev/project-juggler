# Plugin module behavioral specification

This document describes what the `plugin` module does today, as observed from the code. It is the user-facing surface of Project Juggler and the only consumer of `sync-helper`. Behavior that lives in `core` is referenced, not repeated; see [core-spec.md](core-spec.md). Questionable behavior is described as-is and listed under [Known gaps](#known-gaps).

Conventions:

- "Current IDE" is the IntelliJ process the plugin runs in. "Current repository" is `IdeInstallationService.currentIdeConfigRepository`, the `IdeConfigRepository` for that installation.
- "Main instance" is an IDE whose config dir is the base config dir (core-spec §7.2). "Isolated instance" is an IDE launched by Project Juggler with `idea.config.path` under `<projRoot>/config`.
- Message keys refer to `messages/ProjectJugglerBundle.properties`. Any user-visible string not listed there is hardcoded in code and flagged in the gaps.
- `<base>`, `<ideDir>`, `<projRoot>` as in the core spec.

## 1. Scope and packaging

- Plugin id `maxmedvedev.project-juggler`, name "Project Juggler", depends only on `com.intellij.modules.platform`. Version is set in `plugin/build.gradle.kts` (`0.0.6`); `sinceBuild` 251, built against `intellijIdea("2025.1.1")`, JVM toolchain 17.
- Depends on `:core` and Koin. Tests: Kotest in `plugin/src/test`, see §13.
- `processResources` depends on `:sync-helper:installDist` and copies the distribution into plugin resources under `sync-helper/` (`bin/`, `lib/`). This is what `BundledSyncHelper` extracts at runtime (§9).
- `runIde` passes `-Dproject.juggler.base.dir=/Users/max/.project-juggler-test` (a machine-specific absolute path).
- Registered extensions: one notification group `project-juggler.notifications` (balloon), three `postStartupActivity` entries (`ShutdownSignalServiceStartupActivity`, `PluginSyncOnUpdateActivity`, `StaleMainProjectCleanupActivity`), three actions in the File menu after "Open...": Open with Project Juggler, Recent Juggled Projects (`Cmd/Ctrl+Shift+P`), Open a Project Copy.

## 2. Initialization and DI

- `KoinInit.init(pluginModule)` is called lazily at the entry of every action (`actionPerformed`), inside `ShutdownSignalService.init`, and in the two startup activities (`PluginSyncOnUpdateActivity`, `StaleMainProjectCleanupActivity`). It is idempotent, so the first caller wins; there is no single startup hook.
- `pluginModule` declares one singleton: `IdeInstallationService(IdeRegistry)`. Access is through `IdeInstallationService.getInstance()` (Koin `GlobalContext`) or the shortcut `IdeInstallationService.currentIdeConfigRepository`.
- `IdeInstallationService`:
  - `currentInstallation` (lazy): resolves the executable from `PathManager.getHomePath()` as `MacOS/idea` (macOS), `bin/idea.sh` (Linux), `bin/idea64.exe` (Windows). If it exists, `IntelliJLocator.findInstallation(path)`; otherwise an `IdeInstallation(homePath, extractDisplayName(homePath))` keyed on the home directory rather than an executable.
  - `currentRepository` (lazy): `IdeRegistry.getRepository(currentInstallation)` followed by `IdePathSyncDetector.autoPopulateIfNeeded(repo)`.
- `IdePathSyncDetector.autoPopulateIfNeeded(repo, current = CurrentIdePaths.detect())` (§7): no-op when `baseConfigPath` is already set. Otherwise writes `baseConfigPath = current.configDir`, `basePluginsPath = current.pluginsDir`, and, if the file exists, `baseVmOptionsPath = current.vmOptionsFile`, then updates the vmoptions hash. Errors are logged (`Throwable` caught, `ControlFlowException` rethrown), not surfaced. `CurrentIdePaths.detect()` is a default argument, so a failure there propagates instead.
  - `getAllInstallations()` / `getRepository(installation)` delegate to `IdeRegistry`.
- `VMOptionsCopy` is a copy of the platform's `VMOptions` file-location logic: user file from `jb.vmOptionsFile` if outside the IDE home, else `PathManager.getCustomOptionsDirectory()/<script>[64][.exe].vmoptions`; platform file is `<bin>/<same name>`.

## 3. Entry points (actions)

Actions are thin; all run `update` on BGT. None requires an open project (`e.project` may be null and is used only as the parent for dialogs, progress and notifications).

| Action id | Behavior |
|---|---|
| `OpenWithProjectJuggler` | Always enabled. Runs `showFileChooserAndLaunch` (§4.1). |
| `ShowRecentProjects` | On a pooled thread, init Koin and `RecentProjectPopupBuilder(project).show()` (§5). |
| `OpenProjectCopy` | Git worktree flow (§8). |

## 4. Launch flow

### 4.1 File chooser

`showFileChooserAndLaunch(project)`: single file-or-folder chooser (`file.chooser.title/description`). On selection, `ProjectManager.resolvePath(selectedFile.path)` (tilde expansion, absolute, normalized) and `launchOrFocusProject(project, path)`. Runs on the calling thread (EDT for actions).

### 4.2 `launchOrFocusProject(project, path)` (`actions/Launch.kt`)

1. If `MainProjectService.shouldPromptForMainProject(repo, PathManager.getConfigDir())` (no main project, not suppressed, running in main instance): on EDT show the main-project prompt (§4.3), then on a pooled thread:
   - `SetAsMain`: `setMainProject` (persist, `ProjectCleaner.cleanStaleMainProjectEntry()`, `notification.success.set.main`) and open the project in the current IDE via `ProjectUtil.openOrImport(path, currentProject, forceOpenInNewFrame = true)`.
   - `OpenIsolated`: continue to step 2.
   - `Cancelled`: nothing.
2. Otherwise queue a non-cancellable `Task.Backgroundable` titled `progress.opening.project` that runs, in order, the steps returned by `planLaunch(repo, path, PathManager.getConfigDir())` (`services/LaunchProject.kt`):
   - `OPEN_IN_CURRENT_IDE` when the project is registered and its isolated `config` dir equals the current config dir, i.e. the user is reopening the project that this isolated IDE was started for: open it in the current IDE with `ProjectUtil.openOrImport` (asynchronous, on a pooled thread). It is always followed by one of the next two steps (gap 1).
   - `FOCUS` when `ProjectLockUtils.isProjectOpen(repo, path)`: `focusExistingProject` (§4.4). Otherwise `LAUNCH`: `launchProject` (§4.5).

`planLaunch` computes all steps before any runs. It calls `DirectoryManager.ensureProjectDirectories` for a registered project, so checking creates the isolated directories as a side effect.

### 4.3 Main-project prompt (`MainProjectPromptService`)

Yes/No/Cancel dialog (`dialog.main.project.prompt.*`) with a "Don't ask again" checkbox. Result mapping: Yes -> `SetAsMain`, No -> `OpenIsolated`, anything else -> `Cancelled`. When the checkbox is selected and the exit code is not Cancel, `dontAskAboutMainProject = true` is persisted before returning. Must be called on EDT; the dialog has no parent project.

### 4.4 Focus (`services/FocusExistingProject.kt`)

`readPidFromLock` -> null means `notification.error.focus.no.pid`. `isProcessRunning(pid)` false means `notification.error.focus.process.not.found`. Then `WindowFocuser.focus(pid)`; each non-`Success` result maps to one error message (`focus.process.not.found`, `focus.window.not.found`, `focus.failed`, `focus.tool.missing`). Any other exception -> `focus.exception`. `ControlFlowException` is rethrown. Success is silent.

### 4.5 Launch (`services/LaunchProject.kt`)

`launchProject(path, repo, notificationHandler, launcher = ProjectLauncher.getInstance(repo))` calls `launcher.launch(path, onFailure)`. On a `LaunchFailure` within the core's 10 s window, `notification.error.launch.crashed` with project name, exit code and up to 500 chars of stderr (or stdout, or `&lt;No output&gt;`). Any `Throwable` from `launch` -> `notification.error.launch.failed` (a failure to resolve the default launcher propagates, it is evaluated before the `try`). Core decides between main launch and isolated launch (core-spec §7).

### 4.6 Notifications

`showInfoNotification` / `showErrorNotification` post balloons in group `project-juggler.notifications`, attached to the given project or application-wide when null. `IntelliJNotificationHandler(project)` adapts them to core's `NotificationHandler`.

## 5. Recent Juggled Projects popup

### 5.1 Building the item list (`RecentProjectPopupBuilder`, background thread)

`show()` resolves the current repository and `CurrentIdePaths.detect()` and calls `RecentProjectPopupBuilder.buildItems(repo, currentPaths)` (companion object), which does steps 1-3:

1. `RecentProjectsIndex.getRecent(20)`, filtered to paths that exist on disk.
2. For each entry: `GitUtils.detectGitBranch`, `ProjectLockUtils.isProjectOpen`, `MainProjectService.isMainProject` -> `OpenRecentProjectAction(path, branch, isOpen, isMainProject)`.
3. Item order:
   1. `UpdatePathsAction(mismatches)` if `IdePathSyncDetector.detectMismatches(repo, currentPaths)` is non-empty (§7).
   2. `OpenFileChooserAction` ("Browse...").
   3. The main project (built from `mainProjectPath`, even if not in recents) with `isMainProject = true`.
   4. Recent items that are not the main project.
   5. If there is at least one project (recent or main): `SyncAllProjectsAction` for All, VmOptions, Config, Plugins, then `ToggleSyncOnUpdateAction(syncPluginsOnIdeUpdate)`.
   6. Otherwise: `ImportRecentProjectsAction`.
4. `show()` then shows `RecentProjectPopup` on EDT with `showInFocusCenter()`.

Any `Throwable` (except `ControlFlowException`) -> `notification.error.recent.projects.load.failed` and a logged error.

### 5.2 Popup behavior (`RecentProjectPopup`)

`ListPopupImpl` with title `popup.recent.projects.title`, speed search enabled. Speed-search index for a project item is `"<name> [<branch> ]<path>"`; for other items it is their label. Only `OpenRecentProjectAction` has a substep (right arrow / click on arrow): a sub-popup built by `projectSubActions(item)` with, in order, Open Project, Set/Unset Main Project, Sync All Settings, Sync VM Options, Sync Configuration, Sync Plugins, Remove Project. The four Sync entries are omitted for the main project item (`isMainProject`), since it runs on base settings and has nothing to sync. "Open with <other IDE>" sub-actions exist in code but are commented out and would throw `TODO()`.

Final choice on a project item runs `launchOrFocusProject` (§4.2).

### 5.3 Rendering (`RecentProjectPopupActionRenderer`)

- Project item: IntelliJ's project icon (`RecentProjectsManagerBase.getProjectIcon`), with a green badge (`0x5CB85C`) when `isOpen` and a dimmed star (`icons/starDimmed.svg`) beneath when `isMainProject`. Three lines: name, `[branch]` (grayed, small; omitted when null), compacted path (`compactPath(path, home)`: home replaced by `~`; otherwise if longer than 60 chars, `.../<parent>/<name>`).
- Action items: icon + label, with a top separator for "Sync all projects" and "Import recent projects...". Icons: Browse = `Nodes.Folder`, sync and toggle = `Actions.Refresh`, import = `ToolbarDecorator.Import`, update paths = `General.Warning`.

### 5.4 Top-level popup actions

| Item | Behavior |
|---|---|
| Browse... | `showFileChooserAndLaunch` after the popup closes. |
| Sync ... (all projects) | §6.2 with `syncType`. |
| Auto-sync plugins on IDE update | Toggles `syncPluginsOnIdeUpdate` via `repository.update`, then rebuilds and reshows the popup. Label shows a check mark prefix when enabled. |
| Import recent projects from IntelliJ... | Reads `RecentProjectsManagerBase.getRecentPaths()`, keeps existing directories; if none, info dialog `dialog.import.projects.no.projects`; else Yes/No confirm with count. The path filter is `importableProjects(paths)` (`services/RecentProjectsImport.kt`). On Yes, on a pooled thread `importProjects(repo, projects)` runs `ProjectManager.registerOrUpdate` and `RecentProjectsIndex.recordOpen` per project, then the popup is reshown. Import does not launch anything or create isolated dirs. |
| IDE paths out of sync | `IdePathSyncDetector.updatePaths` then reshow (§7). |

### 5.5 Per-project sub-actions

| Sub-action | Behavior |
|---|---|
| Open Project | `launchOrFocusProject`. |
| Set / Unset Main Project | OK/Cancel confirm (`dialog.confirm.set.main.*` / `unset.main.*`) on EDT, then on a pooled thread `setMainProject(path)` (persist, `ProjectCleaner.cleanStaleMainProjectEntry()`, success notification) or `clearMainProject()` with a success notification. |
| Sync All Settings / VM Options / Configuration / Plugins | §6.1 for this project. Not shown for the main project. |
| Remove Project | On a pooled thread: `RecentProjectsIndex.remove(id)` then `ProjectCleaner.cleanProject(metadata)` (deletes `<projRoot>` recursively, core-spec §9). The popup is rebuilt immediately, in parallel with the deletion. No confirmation. |

## 6. Sync from the plugin (`actions/Sync.kt`)

`SyncType` is a sealed class: `All`, `VmOptions`, `Config`, `Plugins`, with `displayName` ("all settings", "VM options", "configuration", "plugins"), `cliFlag` (`--all`, `--vmoptions`, `--config`, `--plugins`, the sync-helper flag) and three derived booleans passed to `ProjectLauncher.syncProject`.

Both entry points ask `SyncPlanner` (`services/SyncPlanner.kt`) for a `SyncTarget` and dispatch on it: `InProcess(projects)` -> `performSyncWithProgress`, `SelfShutdown(helperArgs)` -> `performSelfShutdownSync` (§6.5), `NoTarget` -> nothing. `helperArgs` is `--ide <executablePath> (--path <projectPath> | --all-projects) <cliFlag>`.

### 6.1 Single project (`SyncSettingsSubAction`)

`SyncPlanner.forProject(repo, path, syncType)`:

- If `IsolatedInstance.isCurrentProject(repo, path)` (§6.3): `SelfShutdown` with helper args `--ide <executablePath> --path <projectPath> <cliFlag>`.
- Else load metadata (`NoTarget` if missing, the action silently returns) and `InProcess` for that one project. Title `progress.sync.project.type`, success `notification.success.sync.single.project.type`, failure `notification.error.sync.settings.failed`.

### 6.2 All projects (`SyncAllProjectsAction`)

`SyncPlanner.forAllProjects(repo, syncType)`:

- Targets are `ProjectManager.listSyncable()`: every tracked project except the main project (a stale tracked entry for the main project would otherwise make core send a stop request to the main IDE, which never polls for one, and time out after 60 s). If any target's id is the current isolated project id, `SelfShutdown` with `--ide <executablePath> --all-projects <cliFlag>`.
- Else `InProcess` over the targets, possibly empty (gap 16). Title `progress.sync.all.projects.type`, success `notification.success.sync.all.projects.type` (type, count), failure `notification.error.sync.projects.failed`.

### 6.3 `IsolatedInstance` (`services/IsolatedInstance.kt`)

`projectIdFromConfigPath(configPath)` splits the path on `/` and returns `<id>` when it contains `projects/<id>/config`, null otherwise (gap 2). `currentProjectId()` applies it to the `idea.config.path` system property. `isCurrentProject(repo, path, currentProjectId = currentProjectId())` is true when the target project's id from metadata equals the current id; false when the property is absent or the target has no metadata. This is the test for "syncing this would stop me". `SyncPlanner` and `ShutdownSignalService` (§10) both use it.

### 6.4 `performSyncWithProgress`

Cancellable-by-API `Task.Backgroundable` (default cancellable flag) that runs `syncProjects(launcher, projects, syncType, sink)` (`services/SyncProjects.kt`) with a `SyncProgressSink` adapter over the `ProgressIndicator` and `ProgressManager.checkCanceled()`. `syncProjects` uses `SyncOptions(stopIfRunning = true, autoRestart = true, shutdownTimeout = 60)`. Progress text follows `SyncProgress`: `Stopping` -> `progress.sync.stopping.ide`, `Syncing` -> `progress.sync.syncing.type`, `Restarting` -> `progress.sync.restarting.ide`; `Error` is ignored. Per project: `checkCanceled()`, text `progress.sync.project.type`, fraction `index/size` when more than one. The first exception propagates out of `syncProjects`, aborting the remaining projects; the task catches `Exception`, rethrows `ControlFlowException` and shows the error notification. Projects that are running are stopped via the signal protocol and restarted (core-spec §8).

### 6.5 Self-shutdown sync (`performSelfShutdownSync`)

1. Info notification (hardcoded text, see gaps).
2. `BundledSyncHelper.getExecutable()` (§9).
3. Start `<executable> <helperArgs>` (the `SyncType.cliFlag` is already the last helper arg) with inherited IO, no wait.
4. `Thread.sleep(100)`, then `ApplicationManager.getApplication().exit()` on EDT. The IDE exits; the helper detects the lock release, syncs and relaunches.
5. Any `Exception` -> hardcoded error notification. The IDE does not exit in that case.

## 7. IDE path drift (`IdePathSyncDetector`)

Stored base paths can go stale after an IDE update or relocation. The running IDE's paths are a `CurrentIdePaths(configDir, pluginsDir, vmOptionsFile)` (`services/CurrentIdePaths.kt`); `CurrentIdePaths.detect()` reads `PathManager.getConfigDir()`, `PathManager.getPluginsDir()` and `VMOptionsCopy.getUserOptionsFile() ?: getPlatformOptionsFile()`. It is the default argument of `detectMismatches`, `updatePaths` and `autoPopulateIfNeeded` (§2), so it is evaluated before their `try`.

`detectMismatches(repo, current)` returns an empty list when `current.configDir` is under `repo.baseDir` (isolated instance). (Core's `DirectoryManager.isIsolatedConfigDir` implements the stricter, normalized `<ideDir>/projects/` rule; this check could delegate to it.) Otherwise it compares, as strings, `baseConfigPath`, `basePluginsPath` and `baseVmOptionsPath` to the three current paths, each only when the stored value is non-null. Labels: "Config", "Plugins", "VM Options".

`updatePaths(repo, notificationHandler, current)` overwrites config and plugins paths, overwrites the vmoptions path only if the current file exists, refreshes the vmoptions hash when it exists, and notifies `notification.success.paths.updated` or `notification.error.paths.update.failed` (plus a logged error) through the handler; `UpdatePathsAction` passes `IntelliJNotificationHandler(project)`.

It is only invoked from the popup item; nothing checks at startup despite the class comment.

## 8. Open a Project Copy (git worktree)

`OpenProjectCopyAction`, all on the calling thread until the final step:

1. Folder chooser for source (`file.chooser.source.*`), folder chooser for destination (`file.chooser.destination.*`), input dialog for branch name (`input.dialog.branch.*`). Cancel or blank branch aborts silently.
2. Resolve both paths with `ProjectManager.resolvePath`, then `validateProjectCopy(source, dest)` (`services/OpenProjectCopy.kt`) returns the message of the first failing check, shown as an error notification: source is a directory (`notification.error.not.directory`), source is a git repo (`GitWorktreeManager.isGitRepository`, `notification.error.not.git.repository`), destination does not exist (`notification.error.destination.exists`). The messages name the resolved paths.
3. `notification.info.worktree.started`, then on a pooled thread `createAndLaunchProjectCopy(repo, source, dest, branch, IntelliJNotificationHandler(project))`: `GitWorktreeManager.createWorktree(source, dest, branch, null)` and `launcher.launch(dest)` (no `onFailure`, no main-project prompt, no focus check). Success -> `notification.success.worktree.launched` (destination name, branch); any `Throwable` -> `notification.error.worktree.failed` plus a logged error; `ControlFlowException` is rethrown. The default launcher is resolved before the `try`.

## 9. Bundled sync-helper (`BundledSyncHelper`)

`getExecutable()` returns a cached path if it still exists. Otherwise it extracts `sync-helper/bin` and `sync-helper/lib` from plugin resources into a fresh `Files.createTempDirectory("sync-helper-")`, handling both `jar:` URLs (iterate JAR entries with the prefix) and plain file URLs (dev mode, `Files.walk`). Executable is `bin/sync-helper.bat` on Windows, `bin/sync-helper` elsewhere, made executable on Unix. Missing resource or executable -> `IllegalStateException`. Extracted directories are never removed.

The helper's CLI contract (`sync-helper`): `--ide <path>` required; exactly one of `--path <project>` or `--all-projects`; any of `--all`, `--vmoptions`, `--config`, `--plugins` (none means nothing is synced but the stop/restart cycle still runs). It builds `IdeInstallation(idePath, "IDE")`, resolves the repository, and runs `syncProject` with `STOP_AND_RESTART` semantics and a 60 s timeout for each project. Unknown args are ignored.

## 10. Shutdown signal handling (`shutdown/`)

- `ShutdownSignalServiceStartupActivity` is a `ProjectActivity`; it only instantiates the app-level `ShutdownSignalService`. The service therefore starts when the first project opens, not at application start.
- `ShutdownSignalService.init`: init Koin; `IsolatedInstance.currentProjectId()` (§6.3); load its metadata from the current repository (`Exception` caught and logged). If this is not an isolated instance (or metadata fails to load), monitoring is disabled and logged.
- Monitoring (coroutine on `Dispatchers.IO`): `ShutdownSignalManager.cleanupStaleSignals(project, 5)` once, then every 1 s `readStopRequest`. Whether to act is decided by `StopRequestTracker.shouldAct(signal)` (`shutdown/StopRequestTracker.kt`): true once per `requestId` (in-memory set) and only if its timestamp is at most 5 whole minutes old (the age is compared in minutes, so 5 min 59 s still counts); stale or unparseable timestamps are logged, never acted on and never remembered. Action: `ApplicationManager.getApplication().exit()` on the main dispatcher, which runs the normal save/confirm flow. `autoRestart` and `syncTypes` in the signal are logged but otherwise unused; restart is the sync initiator's job. Errors are logged and polling continues.

## 10a. Stale main-project entry cleanup (`startup/StaleMainProjectCleanupActivity`)

`ProjectActivity`, so it runs per project open. Init Koin, then on `Dispatchers.IO` call `cleanStaleMainProjectEntryIfMainInstance(repo, PathManager.getConfigDir())` (`services/StaleMainProjectCleanup.kt`): null unless `isRunningInMainInstance`, else `ProjectCleaner.cleanStaleMainProjectEntry()` (core-spec §9). The activity logs the removed entry, if any. Any `Throwable` is logged; `ControlFlowException` is rethrown. Together with the call in `setMainProject`, this guarantees the main project does not keep a `projects/<id>/` entry once its isolated instance has exited.

## 11. Plugin sync on IDE update (`startup/PluginSyncOnUpdateActivity`)

`ProjectActivity`, so it runs per project open. It inits Koin, resolves the current repository, the current build (`ApplicationInfo.build.asString()`) and an `IntelliJNotificationHandler(project)`, then calls `syncPluginsOnIdeUpdate(repo, PathManager.getConfigDir(), currentBuild, handler)` (`services/SyncPluginsOnIdeUpdate.kt`) on `Dispatchers.IO`. Failures before that call (Koin, repository) and in resolving the default `ProjectLauncher` propagate to the platform. The function:

1. Returns unless `MainProjectService.isRunningInMainInstance(repo, currentConfigDir)`.
2. Reads `lastKnownBuildNumber`, then always stores the current build.
3. Return if there was no previous build, the build is unchanged, `syncPluginsOnIdeUpdate` is false, or `ProjectManager.listSyncable()` (tracked projects minus the main project) is empty.
4. Notify `notification.sync.on.update.started` (build, count). For each syncable project `syncProject(syncPlugins = true only, stopIfRunning = true, autoRestart = true, 60 s)`; per-project failures are logged as warnings and the loop continues. Then `notification.sync.on.update.complete`.
5. Any other `Throwable` -> `notification.sync.on.update.failed` and a logged error. `ControlFlowException` is rethrown.

Because the build number is stored before the checks, only the first project opened after an update triggers the sync. Running isolated IDEs are stopped and restarted without a prompt.

## 12. Threading summary

| Code | Thread |
|---|---|
| File choosers, `Messages` dialogs, main-project prompt | EDT (dialogs) |
| `showFileChooserAndLaunch` config I/O (`resolvePath`, Koin init) | caller (EDT for actions) |
| `launchOrFocusUnderProgress`, `performSyncWithProgress` | `Task.Backgroundable` |
| Popup building, import, remove, worktree | pooled thread |
| Set/Unset main project config write and stale-entry cleanup | pooled thread |
| `ShutdownSignalService` polling | `Dispatchers.IO` coroutine |
| `PluginSyncOnUpdateActivity` (`syncPluginsOnIdeUpdate`), `StaleMainProjectCleanupActivity` cleanup | `Dispatchers.IO` |

## 13. Tests

Kotest `StringSpec` + MockK specs in `plugin/src/test/kotlin/com/projectjuggler/plugin/`, run by `./gradlew :plugin:test` (also part of `./gradlew test`). They target the plain functions in `services/`, `shutdown/` and `actions/recent/` that the UI classes call, not Swing or the IDE.

Setup:

- Helpers shared with core come from core's test fixtures (`core/src/testFixtures`): `KoinTestExtension` (every spec that reaches a `getInstance(...)` registers it) and `createTempDir`.
- `plugin/test/`: `TestApplicationExtension` boots IntelliJ's headless test application in `beforeSpec` (`initTestApplication()`, `TestFrameworkType.JUnit5`); only specs that need a real `Application` (notifications, `PathManager`, `ApplicationNamesInfo`) register it. `expectLoggedError { ... }` runs a block that must log exactly one `LOG.error` and returns the logged `Throwable` (IntelliJ's logger turns logged errors into test failures); it needs `test-framework-core`, hence the extra `testFramework(TestFrameworkType.Platform)`. Every test that reaches a `LOG.error` path is wrapped in it. `RecordingNotificationHandler` records info/error messages; `PluginTestSupport.kt` has `testRepository`, `registerProject`, `markIsolatedInstanceOpen` (writes a `.lock` with the live PID) and `writeGitHead`.
- The `test` task sets `project.juggler.base.dir` to `plugin/build/project-juggler-test`, so the real `IdeRegistry` never touches `~/.project-juggler`.
- Kotest, MockK, koin-test and the core test fixtures pull `kotlinx-coroutines-core` 1.7.0, which shadows the IDE's patched coroutines and breaks the test application (`NoSuchMethodError ... DebugProbesImpl.install`). Each test dependency excludes that module (`withoutCoroutines()`); a `configurations.all` exclude would also remove the IDE's copy and break `compileKotlin`. `junit:junit` is a runtime-only test dependency because the IntelliJ test framework still references JUnit 4 classes. None of these end up in the plugin zip.

Covered, by spec:

| Spec | Covers |
|---|---|
| `actions/recent/SyncTypeTest` | sync booleans, `cliFlag`, `displayName` per type |
| `actions/recent/RecentProjectPopupBuilderTest` | `buildItems`: item order, main project, filtering, path-mismatch item, git branch, `isOpen`, toggle text |
| `actions/recent/RecentProjectPopupTest` | `projectSubActions`, speed-search and list texts |
| `actions/recent/CompactPathTest` | `compactPath` |
| `util/BundledSyncHelperTest` | extraction, caching, re-extraction |
| `util/IntelliJNotificationHandlerTest` (test application) | notification group, type and content |
| `services/IsolatedInstanceTest` | `projectIdFromConfigPath`, `isCurrentProject` |
| `shutdown/StopRequestTrackerTest` | fresh, repeated, stale and unparseable stop requests |
| `services/SyncPlannerTest` | `forProject`, `forAllProjects` targets and helper args |
| `services/SyncProjectsTest` | sync calls, options, progress texts and fractions, failure and cancellation |
| `services/LaunchPlanTest` | `planLaunch` steps and its directory side effect |
| `services/LaunchProjectTest` | launch, crash and start-failure notifications |
| `services/FocusExistingProjectTest` | each focus outcome and its message |
| `services/MainProjectActionsTest` | `setMainProject`, `clearMainProject` |
| `services/RemoveProjectTest` | `removeRecentProject` |
| `services/RecentProjectsImportTest` | `importableProjects`, `importProjects` |
| `services/IdePathSyncDetectorTest` | `detectMismatches`, `updatePaths`, `autoPopulateIfNeeded` |
| `services/SyncPluginsOnIdeUpdateTest` | main-instance check, build bookkeeping, plugin sync of isolated projects only, per-project and outer failures |
| `services/StaleMainProjectCleanupTest` | `cleanStaleMainProjectEntryIfMainInstance` in isolated and main instances |
| `services/OpenProjectCopyTest` | `validateProjectCopy`, `createAndLaunchProjectCopy` success and worktree failure |
| `services/IdeInstallationServiceTest` (test application) | current installation and repository in the sandbox IDE, auto-populated base paths, `VMOptionsCopy` files |

Not covered: `showMainProjectPrompt` and the other `Messages` dialogs, `RecentProjectPopup`/renderer Swing behavior, `performSelfShutdownSync` (process spawn and IDE exit), the `ShutdownSignalService` polling loop, `ProjectUtil.openOrImport`, `WindowFocuser` (core), and the actions and startup activities themselves.

Gaps pinned by tests (the test name carries the number): 1 (`LaunchPlanTest`), 2 (`IsolatedInstanceTest`), 8 (`RecentProjectPopupBuilderTest`), 9 (`IdePathSyncDetectorTest`), 11 (`BundledSyncHelperTest` cleans up after itself), 16 (`SyncPlannerTest`). Gap 10 is named in `IdeInstallationServiceTest`, but the sandbox IDE has its launcher, so the test runs the launcher branch and the home-directory fallback is not exercised.

## Known gaps

1. `launchOrFocusUnderProgress` does not return after `isSameIdeInstance` opens the project in-process, so it also runs the focus/launch branch. In practice the isolated `.lock` exists, so it tries to focus its own window.
2. `IsolatedInstance.projectIdFromConfigPath` (used by sync planning and `ShutdownSignalService`) splits the path on `/`; on Windows (`\`) isolated detection fails, so self-shutdown sync and stop signals never trigger there.
3. Hardcoded user-visible strings: self-shutdown notifications ("IntelliJ will close to sync ... and reopen automatically...", "Failed to initiate self-shutdown sync: ..."), "Open with <IDE>" label, `"Unknown error"` fallbacks. `SyncType.displayName` values are also hardcoded and interpolated into bundle messages.
4. `performSelfShutdownSync` catches `Exception`, not `Throwable` (as do `performSyncWithProgress` and `ShutdownSignalService`'s metadata load and polling). `launchProject` catches `Throwable` without rethrowing `ControlFlowException`.
5. `OpenProjectCopyAction` uses a folder chooser for the destination, which can only select existing directories, while the validation requires the destination not to exist. The user must type a new path into the chooser for the flow to succeed. Validation and git checks run on EDT.
6. `OpenWithIdeSubAction` is unimplemented (`TODO()`); multi-IDE launching is not reachable from the UI even though the data model supports it.
7. Remove Project has no confirmation and deletes the whole isolated directory; the popup is reshown before deletion finishes, so a project may still appear briefly. `removeRecentProject` removes from recents twice (eagerly and inside `cleanProject`).
8. Popup shows up to 20 recents while core caps the index at `maxRecentProjects` (10 by default), so the limit is effectively 10. The main project item's `isOpen` relies on `isProjectOpen`, which is false when `baseConfigPath` is unset.
9. `IdePathSyncDetector` compares paths as raw strings and is only checked from the popup. `autoPopulateIfNeeded` runs the first time the repository is touched in whichever instance that is; if that is an isolated instance started by `sync-helper` with no prior config, the isolated dirs become the "base" paths.
10. `detectCurrentInstallation` falls back to an `IdeInstallation` keyed on the home directory when the executable is missing, which produces a different `directoryName` than an installation found by `IntelliJLocator`, splitting state across two `<ideDir>`s.
11. `BundledSyncHelper` extracts a full distribution into a new temp directory once per IDE session and never deletes it. Extraction happens on the calling thread. The helper process inherits the IDE's stdio.
12. `ShutdownSignalService` starts only after a project is opened; an isolated IDE sitting on the welcome screen ignores stop requests, so stop-and-sync times out against it. The JSON `autoRestart`/`timeoutSeconds` fields are not honored by the receiver.
13. `PluginSyncOnUpdateActivity` performs network-free but potentially long sync work (stopping and restarting every isolated IDE) on first project open with only a balloon as notice and no cancel. `performSyncWithProgress` aborts the whole batch on the first failure, unlike the update activity which continues.
14. `KoinInit.init` is called on EDT in several actions (marked `todo move to BGT`). `ToggleMainProjectSubAction` performs config file I/O under a file lock on EDT. `ImportRecentProjectsAction` casts `RecentProjectsManager` to `RecentProjectsManagerBase`, an implementation class.
15. `runIde` hardcodes a user-specific base dir. The plugin has no settings page or startup health check for missing base paths.
16. Sync all projects with no syncable target (only the main project tracked) still runs an in-process sync of zero projects and reports success for 0 projects.
