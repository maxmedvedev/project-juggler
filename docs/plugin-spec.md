# Plugin module behavioral specification

This document describes what the `plugin` module does today, as observed from the code. It is the user-facing surface of Project Juggler and the only consumer of `sync-helper`. Behavior that lives in `core` is referenced, not repeated; see [core-spec.md](core-spec.md). Questionable behavior is described as-is and listed under [Known gaps](#known-gaps).

Conventions:

- "Current IDE" is the IntelliJ process the plugin runs in. "Current repository" is `IdeInstallationService.currentIdeConfigRepository`, the `IdeConfigRepository` for that installation.
- "Main instance" is an IDE whose config dir is the base config dir (core-spec §7.2). "Isolated instance" is an IDE launched by Project Juggler with `idea.config.path` under `<projRoot>/config`.
- Message keys refer to `messages/ProjectJugglerBundle.properties`. Any user-visible string not listed there is hardcoded in code and flagged in the gaps. Exceptions without a message are shown as `error.unknown`.
- Every `catch` takes `Throwable`, calls `rethrowIfControlFlow()` (`plugin/Utils.kt`) first, so `ControlFlowException` and `CancellationException` always propagate, and logs; sites that also notify the user log a warning.
- `<base>`, `<ideDir>`, `<projRoot>` as in the core spec.

## 1. Scope and packaging

- Plugin id `maxmedvedev.project-juggler`, name "Project Juggler", depends only on `com.intellij.modules.platform`. Version is set in `plugin/build.gradle.kts` (`0.0.6`); `sinceBuild` 251, built against `intellijIdea("2025.1.1")`, JVM toolchain 17.
- Depends on `:core` and Koin. Tests: Kotest in `plugin/src/test`, see §13.
- `processResources` depends on `:sync-helper:installDist` and copies the distribution into plugin resources under `sync-helper/` (`bin/`, `lib/`). This is what `BundledSyncHelper` extracts at runtime (§9).
- `runIde` passes `-Dproject.juggler.base.dir=<repo>/plugin/build/project-juggler-sandbox` (absolute, next to `build/idea-sandbox` whose paths it stores, removed by `clean`); `-PprojectJuggler.baseDir=<dir>` passes that dir instead. `sync-helper` spawned from the sandbox gets the same value (§9).
- Registered extensions: one notification group `project-juggler.notifications` (balloon), five `postStartupActivity` entries (`ShutdownSignalServiceStartupActivity`, `PluginSyncOnUpdateActivity`, `StaleMainProjectCleanupActivity`, `IdePathsCheckActivity`, `SelfSyncResultActivity`), one `applicationConfigurable` (`settings.ProjectJugglerConfigurable`, id `com.projectjuggler.plugin.settings`, parent `tools`, §7a), one `applicationListeners` entry (`ShutdownSignalAppListener` on topic `com.intellij.ide.AppLifecycleListener`), three actions in the File menu after "Open...": Open with Project Juggler, Recent Juggled Projects (`Cmd/Ctrl+Shift+P`), Open a Project Copy.

## 2. Initialization and DI

- `KoinInit.init(pluginModule)` is called lazily by `IdeInstallationService.getInstance()` (`ensurePluginKoin` in `di/PluginModule.kt`: only when `IdeInstallationService` is not registered yet, which also covers a Koin started without `pluginModule`); no other call site. Every plugin path reaches Koin through `IdeInstallationService`, so this works on any thread and on dynamic plugin load without a startup hook.
- `pluginModule` declares one singleton: `IdeInstallationService(IdeRegistry)`. Access is through `IdeInstallationService.getInstance()` (Koin `GlobalContext`) or the shortcut `IdeInstallationService.currentIdeConfigRepository`.
- `IdeInstallationService`:
  - `currentInstallation` (lazy): `currentIdeExecutable(homePath, ApplicationNamesInfo.scriptName, platform)` (`services/CurrentIdeExecutable.kt`) resolves `<home>/MacOS/<script>` (macOS), `<home>/bin/<script>.sh` (Linux), `<home>/bin/<script>64.exe` (Windows), so PyCharm, Android Studio etc. get their own launcher. When the file does not exist a warning is logged (launches from this IDE will fail) and the path is used anyway. Then `IntelliJLocator.findInstallation(path)`. The home directory is never the key.
  - `currentRepository` (lazy): first `IdeRegistry.adoptLegacyRepository(currentInstallation, listOf(homePath))` (core-spec §4), which moves a dir older plugin versions keyed on the home directory to the executable-keyed one; adoption is logged at info, a failure is logged as a warning (`Throwable` caught, `ControlFlowException` rethrown) and startup continues. Then `IdeRegistry.getRepository(currentInstallation)` followed by `IdePathSyncDetector.autoPopulateIfNeeded(repo)`.
- `IdePathSyncDetector.autoPopulateIfNeeded(repo, current = CurrentIdePaths.detect())` (§7): runs only in the main instance (`current.configDir` is not `DirectoryManager.isIsolatedConfigDir`), so an isolated instance, e.g. one started by `sync-helper`, never stores its isolated dirs as base. For each `IdePathKind` it sets the stored path to the current one when the stored value is null or lies under `<ideDir>/projects/` (an isolated dir captured earlier, repaired); the vmoptions path only when the current file exists. All fills happen in one `repository.update`, nothing is written when no kind needs filling, and the vmoptions hash is refreshed when the vmoptions path was filled. Paths that are set and outside the ide dir are never touched. Errors are logged (`Throwable` caught, `ControlFlowException` rethrown), not surfaced. `CurrentIdePaths.detect()` is a default argument, so a failure there propagates instead.
  - `getAllInstallations()` / `getRepository(installation)` delegate to `IdeRegistry`.
- `VMOptionsCopy` is a copy of the platform's `VMOptions` file-location logic: user file from `jb.vmOptionsFile` if outside the IDE home, else `PathManager.getCustomOptionsDirectory()/<script>[64][.exe].vmoptions`; platform file is `<bin>/<same name>`.

## 3. Entry points (actions)

Actions are thin; all run `update` on BGT. `actionPerformed` shows choosers/dialogs on EDT and does all config I/O on a pooled thread. None requires an open project (`e.project` may be null and is used only as the parent for dialogs, progress and notifications).

| Action id | Behavior |
|---|---|
| `OpenWithProjectJuggler` | Always enabled. Runs `showFileChooserAndLaunch` (§4.1). |
| `ShowRecentProjects` | On a pooled thread, `RecentProjectPopupBuilder(project).show()` (§5). |
| `OpenProjectCopy` | Git worktree flow (§8). |

## 4. Launch flow

### 4.1 File chooser

`showFileChooserAndLaunch(project)`: single file-or-folder chooser (`file.chooser.title/description`). The chooser runs on EDT; on selection, on a pooled thread, `ProjectManager.resolvePath(selectedFile.path)` (tilde expansion, absolute, normalized) and `launchOrFocusProject(project, path)`.

### 4.2 `launchOrFocusProject(project, path, repository = current)` (`actions/Launch.kt`)

`@RequiresBackgroundThread`: it reads the repository, so every caller (file chooser, popup items) calls it on a pooled thread; the prompt hops to EDT by itself. `repo` below is `repository`, the current installation's repository unless a caller (Open with <IDE>, §5.5) passes another installation's. The main-project prompt only runs for the current installation's repository.

1. If `shouldPromptForMainProject(repo, currentRepo, PathManager.getConfigDir())` (`services/OpenWithIde.kt`: `repo` is the current installation's, and `MainProjectService.shouldPromptForMainProject`: no main project, not suppressed, running in main instance): on EDT show the main-project prompt (§4.3), then on a pooled thread:
   - `SetAsMain`: `setMainProject` (persist, `ProjectCleaner.cleanStaleMainProjectEntry()`, `notification.success.set.main`) and open the project in the current IDE (`openProjectInCurrentIde`, focuses it if already open).
   - `OpenIsolated`: continue to step 2.
   - `Cancelled`: nothing.
2. Otherwise queue a non-cancellable `Task.Backgroundable` titled `progress.opening.project` that runs the step returned by `planLaunch(repo, path, PathManager.getConfigDir())` (`services/LaunchProject.kt`). `planLaunch` picks one step. If the project belongs to the current IDE (`belongsToCurrentIde`: the main project in the main instance, or an isolated project whose `<projRoot>/config` is `PathManager.getConfigDir()`), open it here (`OPEN_IN_CURRENT_IDE`): focus its frame if already open (`ProjectUtil.findProject`), else `ProjectUtil.openOrImport` in a new frame (on a pooled thread). Else the step of core's `planOpen` (core-spec §7.6): the main project is always launched (`LAUNCH`, `launchMain`; a running main IDE opens or focuses it); else focus when `ProjectLockUtils.isProjectOpen(repo, path)` (`FOCUS`, `focusExistingProject`, §4.4), launch otherwise (`LAUNCH`, `launchProject`, §4.5).

### 4.3 Main-project prompt (`MainProjectPromptService`)

Yes/No/Cancel dialog (`dialog.main.project.prompt.*`) with a "Don't ask again" checkbox. Result mapping: Yes -> `SetAsMain`, No -> `OpenIsolated`, anything else -> `Cancelled`. When the checkbox is selected and the exit code is not Cancel, `dontAskAboutMainProject = true` is persisted before returning (a config write on EDT, gap 18). The settings page's "Ask to choose a main project" checkbox (§7a) turns it back off. Must be called on EDT; the dialog has no parent project.

### 4.4 Focus (`services/FocusExistingProject.kt`)

Calls core's `focusRunningProject` (core-spec §7.6) and maps each non-`Success` result to one error message: `LockNotFound` (no PID in the lock) -> `focus.no.pid`, `ProcessNotFound` (also a dead lock PID) -> `focus.process.not.found`, `WindowNotFound` -> `focus.window.not.found`, `CommandFailed` -> `focus.failed`, `ToolNotInstalled` -> `focus.tool.missing`. Any other `Throwable` -> `focus.exception` (logged as a warning). `ControlFlowException` is rethrown. Success is silent.

### 4.5 Launch (`services/LaunchProject.kt`)

`launchProject(path, repo, notificationHandler, launcher = ProjectLauncher.getInstance(repo))` calls `launcher.launch(path, onFailure)`. On a `LaunchFailure` within the core's 10 s window, `notification.error.launch.crashed` with project name, exit code and up to 500 chars of stderr (or stdout, or `&lt;No output&gt;` (`notification.error.launch.no.output`)). Any `Throwable` from `launch` except `ControlFlowException` -> `notification.error.launch.failed` (logged as a warning) (a failure to resolve the default launcher propagates, it is evaluated before the `try`). Core decides between main launch and isolated launch (core-spec §7).

### 4.6 Notifications

`showInfoNotification` / `showErrorNotification` post balloons in group `project-juggler.notifications`, attached to the given project or application-wide when null. `IntelliJNotificationHandler(project)` adapts them to core's `NotificationHandler`.

## 5. Recent Juggled Projects popup

### 5.1 Building the item list (`RecentProjectPopupBuilder`, background thread)

`show()` resolves the current repository and `CurrentIdePaths.detect()` and calls `RecentProjectPopupBuilder.buildItems(repo, currentPaths)` (companion object), which loads `config.json` once and does steps 1-3:

1. `RecentProjectsIndex.getRecent(maxRecentProjects)`, filtered to paths that exist on disk. `recordOpen` trims `recent.json` to the same limit, so a lowered value applies to the popup at once and to the file on the next `recordOpen`; a deleted directory takes a slot until it is pushed out.
2. For each entry: `GitUtils.detectGitBranch`, `isOpen`, `MainProjectService.isMainProject` -> `OpenRecentProjectAction(path, branch, isOpen, isMainProject)`. `isOpen` = `isProjectRunning` (`services/CurrentIdeProject.kt`): for a project that belongs to the current IDE (`belongsToCurrentIde`: the main project in the main instance, or the project of this isolated instance) whether the platform has it open (`ProjectUtil.findProject`), since the `.lock` there is the current IDE's own; otherwise `ProjectLockUtils.isProjectOpen`. For the main project seen from an isolated IDE this means the main IDE is running.
3. Item order:
   1. `UpdatePathsAction(mismatches)` if `IdePathSyncDetector.detectMismatches(repo, currentPaths)` is non-empty (§7).
   2. `OpenFileChooserAction` ("Browse...").
   3. The main project (built from `mainProjectPath`, even if not in recents) with `isMainProject = true`.
   4. Recent items that are not the main project.
   5. If there is at least one project (recent or main): `SyncAllProjectsAction` for All, VmOptions, Config, Plugins, then `ToggleSyncOnUpdateAction(syncPluginsOnIdeUpdate)`.
   6. Otherwise: `ImportRecentProjectsAction`.
4. On the same thread, `openWithInstallations` (`services/OpenWithIde.kt`) picks the other installations offered in the sub-menu (§5.2) from `IdeInstallationService.getAllInstallations()` (a filesystem scan).
5. `show()` then shows `RecentProjectPopup` on EDT with `showInFocusCenter()`.

Any `Throwable` (except `ControlFlowException`) -> `notification.error.recent.projects.load.failed` and a logged error.

### 5.2 Popup behavior (`RecentProjectPopup`)

`ListPopupImpl` with title `popup.recent.projects.title`, speed search enabled. Speed-search index for a project item is `"<name> [<branch> ]<path>"`; for other items it is their label. Only `OpenRecentProjectAction` has a substep (right arrow / click on arrow): a sub-popup built by `projectSubActions(item, openWith)` with, in order, Open Project, Open with <IDE> (`popup.recent.projects.action.open.with`) for each other installation whose base settings are recorded (not the current one, sorted by name), Set/Unset Main Project, Sync All Settings, Sync VM Options, Sync Configuration, Sync Plugins, Remove Project. The four Sync entries and Remove Project are omitted for the main project item (`isMainProject`), since it runs on base settings and has nothing to sync and no isolated data to remove.

`openWithInstallations(all, current, currentHome, repositoryFor)` excludes an installation whose `directoryName` equals the current one's or whose executable lies under `PathManager.getHomePath()` (the same IDE registered under another executable, e.g. a home-keyed dir that could not be adopted), and keeps only those whose repository has `baseConfigPath` set, i.e. the plugin has run there once. Without it core would fall back to the newest `IntelliJIdea*` config dir and `PluginLocator` (core-spec), which may belong to another IDE version.

Final choice on a project item runs `launchOrFocusProject` (§4.2).

### 5.3 Rendering (`RecentProjectPopupActionRenderer`)

- Project item: IntelliJ's project icon (`PlatformRecentProjects.projectIcon`, `services/PlatformRecentProjects.kt`: `RecentProjectsManagerBase.getProjectIcon` through a safe cast, `AllIcons.Nodes.IdeaProject` when the manager is another implementation), with a green badge (`0x5CB85C`) when `isOpen` and a dimmed star (`icons/starDimmed.svg`) beneath when `isMainProject`. Three lines: name, `[branch]` (grayed, small; omitted when null), compacted path (`compactPath(path, home)`: home replaced by `~`; otherwise if longer than 60 chars, `.../<parent>/<name>`).
- Action items: icon + label, with a top separator for "Sync all projects" and "Import recent projects...". Icons: Browse = `Nodes.Folder`, sync and toggle = `Actions.Refresh`, import = `ToolbarDecorator.Import`, update paths = `General.Warning`.

### 5.4 Top-level popup actions

| Item | Behavior |
|---|---|
| Browse... | `showFileChooserAndLaunch` (§4.1) after the popup closes. |
| Sync ... (all projects) | §6.2 with `syncType`. |
| Offer plugin sync on IDE update | On a pooled thread, toggles `syncPluginsOnIdeUpdate` via `repository.update`, then rebuilds and reshows the popup. Label shows a check mark prefix when enabled. When on, an IDE update offers a plugin sync (§11) rather than running one. |
| Import recent projects from IntelliJ... | On a pooled thread, reads `PlatformRecentProjects.recentPaths()` (`RecentProjectsManagerBase.getRecentPaths()` through a safe cast; empty for another manager implementation, logged once at info) and keeps existing directories; then on EDT: if none, info dialog `dialog.import.projects.no.projects`; else Yes/No confirm with count. The path filter is `importableProjects(paths)` (`services/RecentProjectsImport.kt`). On Yes, on a pooled thread `importProjects(repo, projects)` runs `ProjectManager.registerOrUpdate` and `RecentProjectsIndex.recordOpen` per project, then the popup is reshown. Import does not launch anything or create isolated dirs. |
| IDE paths out of sync | On a pooled thread, `IdePathSyncDetector.updatePaths` then reshow (§7). |

### 5.5 Per-project sub-actions

| Sub-action | Behavior |
|---|---|
| Open Project | `launchOrFocusProject` on a pooled thread. |
| Open with <IDE> | On a pooled thread, `launchOrFocusProject` against that installation's repository (`IdeInstallationService.getRepository`); no main-project prompt; the project is tracked and recorded in that IDE's `<ideDir>`, the current IDE's recents are untouched. Focuses that IDE's running instance of the project, else `ProjectLauncher.launch` with that IDE's executable (its own main project goes through `launchMain`). |
| Set / Unset Main Project | Label and confirm text follow `item.isMainProject` (computed when the popup was built, no config read). OK/Cancel confirm (`dialog.confirm.set.main.*` / `unset.main.*`) on EDT, then on a pooled thread `setMainProject(path)` (persist, `ProjectCleaner.cleanStaleMainProjectEntry()`, success notification) or `clearMainProject()` with a success notification. The settings page (§7a) is the second entry point and calls the same two functions. |
| Sync All Settings / VM Options / Configuration / Plugins | §6.1 for this project. Not shown for the main project. |
| Remove Project | OK/Cancel warning dialog (`dialog.confirm.remove.project.*`, naming the project and `<projRoot>`) on EDT after the popup closes; Cancel does nothing. On OK, a non-cancellable background task (`progress.removing.project`) runs `removeRecentProject`: an unknown project is a no-op; it refuses with `notification.error.remove.project.running` while the isolated instance is open (`isIsolatedInstanceOpen`); otherwise `ProjectCleaner.cleanProject(metadata)` (removes the recent entry, then deletes `<projRoot>` recursively, core-spec §9); a failure shows `notification.error.remove.project.failed` and is logged as a warning (the recent entry is already gone). The popup is reshown from the task when it finishes. Not offered for the main project. |

## 6. Sync from the plugin (`actions/Sync.kt`)

`SyncType` is a sealed class: `All`, `VmOptions`, `Config`, `Plugins`, with `displayName` from bundle keys `sync.type.*` ("all settings", "VM options", "configuration", "plugins"), `cliFlag` (`--all`, `--vmoptions`, `--config`, `--plugins`, the sync-helper flag) and three derived booleans passed to `ProjectLauncher.syncProject`.

Both entry points ask `SyncPlanner` (`services/SyncPlanner.kt`) for a `SyncTarget` on a pooled thread and dispatch on it there: `InProcess(projects)` -> `performSyncWithProgress`, `SelfShutdown(helperArgs, others)` -> `performSelfShutdownSync` (§6.5), after an in-process sync of `others` when there are any (§6.2), `NoTarget` -> nothing. `helperArgs` is always `--ide <executablePath> --path <current project path> <cliFlag>`.

### 6.1 Single project (`SyncSettingsSubAction`)

On a pooled thread, `SyncPlanner.forProject(repo, path, syncType)`:

- If `IsolatedInstance.isCurrentProject(repo, path)` (§6.3): `SelfShutdown` with helper args `--ide <executablePath> --path <projectPath> <cliFlag>`; the info text is `notification.info.sync.self.shutdown.project`.
- Else load metadata (`NoTarget` if missing, the action silently returns) and `InProcess` for that one project. Title `progress.sync.project.type`, success `notification.success.sync.single.project.type`, failure `notification.error.sync.settings.failed` (with the project's error message).

### 6.2 All projects (`SyncAllProjectsAction`)

On a pooled thread, `SyncPlanner.forAllProjects(repo, syncType)`:

- Targets are `ProjectManager.listSyncable()`: every tracked project except the main project (a stale tracked entry for the main project would otherwise make core send a stop request to the main IDE, which never polls for one, and time out after 60 s). If one target's id is the current isolated project id, `SelfShutdown` with `--ide <executablePath> --path <current project path> <cliFlag>` and `others` = the remaining targets. The action syncs `others` in process first (`performSyncWithProgress` with the titles and notifications below), and its `onFinished` (on the task's pooled thread, also when some projects failed) starts the self-sync with the info text `notification.info.sync.self.shutdown.all`. Cancelling, or an exception outside the per-project loop, skips the self-sync. With no `others`, the self-sync starts directly.
- Else `InProcess` over the targets, possibly empty (gap 16). Title `progress.sync.all.projects.type`, success `notification.success.sync.all.projects.type` (type, count), failure `notification.error.sync.projects.failed` (with the `sync.failure.summary` of the failed projects; the others are still synced).

### 6.3 `IsolatedInstance` (`services/IsolatedInstance.kt`)

`projectIdFromConfigPath(configPath)` splits the path on both `/` and `\` (the value is written by `Path.toString()` on the launching OS) and returns `<id>` from the first `projects` segment followed by `<id>/config`, null otherwise (gap 17). `currentProjectId()` applies it to the `idea.config.path` system property. `isCurrentProject(repo, path, currentProjectId = currentProjectId())` is true when the target project's id from metadata equals the current id; false when the property is absent or the target has no metadata. This is the test for "syncing this would stop me". `SyncPlanner` and `ShutdownSignalService` (§10) both use it.

### 6.4 `performSyncWithProgress`

Cancellable-by-API `Task.Backgroundable` (default cancellable flag) that runs `syncProjects(launcher, projects, syncType, sink)` (`services/SyncProjects.kt`) with a `SyncProgressSink` adapter over the `ProgressIndicator` and `ProgressManager.checkCanceled()`. `syncProjects` uses `SyncOptions(stopIfRunning = true, autoRestart = true, shutdownTimeout = 60)`. Progress text follows `SyncProgress`: `Stopping` -> `progress.sync.stopping.ide`, `Syncing` -> `progress.sync.syncing.type`, `Restarting` -> `progress.sync.restarting.ide`; `Error` is ignored. Per project: `checkCanceled()`, text `progress.sync.project.type`, fraction `index/size` when more than one, then `syncProject`. A `Throwable` from one project is logged as a warning and recorded as a `SyncFailure`, and the loop continues with the next project; control-flow exceptions (`ProcessCanceledException`) propagate. `syncProjects` returns a `SyncBatchResult(synced, failures)`. The task shows the caller's success notification only when no project failed; otherwise one error notification, the caller's error text with the detail from `describeFailures(result, total)`: the bare message (`userMessage()`) for a one-project batch, else `sync.failure.summary` ("N of M projects failed: <name>: <message>; ..."). A failure outside the projects (e.g. resolving the launcher) is logged as a warning and reported with its message through the same error text. Cancel is checked between projects only, never from the `Stopping` callback: cancelling during the stop wait would make core's `finally` delete `stop-request.json` while the target may already be exiting, leaving it stopped and never restarted; the wait is bounded by the 60 s timeout. Cancellation shows no notification. Projects that are running are stopped via the signal protocol and restarted, also when their sync failed (core-spec §8). An optional `onFinished` runs at the end of the task after the notification, but not on cancellation or when the sync threw outside the per-project loop.

### 6.5 Self-shutdown sync (`performSelfShutdownSync`)

`@RequiresBackgroundThread`; both callers run it from their pooled-thread planning.

1. Info notification (passed by the caller).
2. A non-cancellable `Task.Backgroundable` titled `progress.sync.helper.starting` subscribes an `AppLifecycleListener` on the application message bus that records `appWillBeClosed`, then calls `BundledSyncHelper.start(helperArgs)` (§9): it extracts if needed, starts the helper on the IDE's JVM with stdout and stderr redirected to `<IDE log dir>/sync-helper.log` (`PathManager.getLogDir()`, overwritten per run; `<projRoot>/logs` in an isolated instance) and stdin closed, and keeps the `Process`. The IDE does not exit by itself: the helper finds it running (`config/.lock`), writes `stop-request.json`, and `ShutdownSignalService` (§10) closes it; the helper then syncs and relaunches it (sync-helper-spec §4).
3. `process.onExit()` disconnects the listener and calls `reportHelperExit`: when the app is closing (`appWillBeClosed` seen) or disposed, nothing (the relaunched IDE reports at startup, §10b); otherwise the helper exited while this IDE still runs (it failed before stopping it, or the 60 s shutdown timed out), and `reportSelfSyncResult` (§10b) reports the result file or the exit code for `IsolatedInstance.currentProjectId()`. `Application.isExitInProgress` is not used because it is internal API. The disconnect runs outside `reportHelperExit`'s `try`, so a failure there is swallowed by the future (gap 19).
4. `onThrowable` (any failure in extraction or spawn) -> logged warning and `notification.error.sync.self.shutdown.failed`; the IDE keeps running. Cancellation cannot happen (non-cancellable).

## 7. IDE path drift (`IdePathSyncDetector`)

Stored base paths can go stale after an IDE update or relocation. The running IDE's paths are a `CurrentIdePaths(configDir, pluginsDir, vmOptionsFile)` (`services/CurrentIdePaths.kt`); `CurrentIdePaths.detect()` reads `PathManager.getConfigDir()`, `PathManager.getPluginsDir()` and the vmoptions file the IDE reads: `VMOptionsCopy.getUserOptionsFile()` if that file exists, else `getPlatformOptionsFile()`. It is the default argument of `detectMismatches`, `updatePaths` and `autoPopulateIfNeeded` (§2), so it is evaluated before their `try`.

The three base paths are the `IdePathKind` entries `CONFIG`, `PLUGINS`, `VM_OPTIONS` (`services/IdePathKind.kt`), each with its stored and current value; `displayName` comes from `ide.path.kind.*` ("Config", "Plugins", "VM Options").

`detectMismatches(repo, current)` returns an empty list when `current.configDir` is an isolated dir (`DirectoryManager.isIsolatedConfigDir`, core-spec §3). It does not use `MainProjectService.isRunningInMainInstance`, because after drift the stored `baseConfigPath` is exactly what is stale. Otherwise it returns, in `IdePathKind` order, each kind whose stored value is non-null and not `PathUtils.isSamePath` the current one (normalized, real paths when both exist, so trailing slashes, `.` segments and symlinks are not drift).

`updatePaths(repo, notificationHandler, current)` overwrites config and plugins paths, overwrites the vmoptions path only if the current file exists, refreshes the vmoptions hash when it exists, and notifies `notification.success.paths.updated` or `notification.error.paths.update.failed` (plus a logged error) through the handler; `UpdatePathsAction` passes `IntelliJNotificationHandler(project)`.

It is checked from the popup item (§5.1) and at startup: `startup/IdePathsCheckActivity` (`ProjectActivity`), on `Dispatchers.IO`, calls `IdePathsStartupCheck.problemsToReport(repo, CurrentIdePaths.detect())`. It returns a map `IdePathKind -> IdePathProblem` in kind order, only on its first call per IDE session (an `AtomicBoolean`), and empty in an isolated instance (`IdePathSyncDetector.isMainInstance`). A kind whose stored value is non-null and does not exist is `MISSING`; otherwise one in `detectMismatches` is `OUT_OF_SYNC`. The activity then shows one warning balloon (via `showNotification(..., actions)` in `Utils.kt`):

- any `MISSING`: `notification.paths.missing` with the missing kinds, actions "Use current IDE paths" (`notification.action.use.current.paths`) and "Open settings" (`notification.action.open.settings`);
- else any `OUT_OF_SYNC`: `notification.paths.out.of.sync` with the kinds, actions "Update paths" (`notification.action.update.paths`) and "Open settings";
- else nothing.

Both actions expire the balloon. The update action runs `updatePaths(repo, IntelliJNotificationHandler(project), CurrentIdePaths.detect())` on a pooled thread; "Open settings" calls `ShowSettingsUtil.showSettingsDialog(project, ProjectJugglerConfigurable::class.java)`. Stored paths are never changed without a click. Any `Throwable` is logged; `ControlFlowException` is rethrown. An IDE that stays on the welcome screen runs no project activity and gets no balloon; the popup item still covers drift there.

## 7a. Settings page (`settings/ProjectJugglerConfigurable`)

Settings > Tools > Project Juggler, a `BoundSearchableConfigurable` (Kotlin UI DSL v2) for the current IDE only. The UI-free logic is in `services/JugglerSettings.kt` (`JugglerSettings`, `loadJugglerSettings`, `validateBasePath`, `validateMainProject`, `saveJugglerSettings`, `applyMainProjectChange`, `withCurrentIdePaths`).

- IDE (read-only): display name, executable, `<ideDir>`.
- Projects: main project (file-or-folder chooser, since file-based projects such as `MODULE.bazel` are valid; empty text `settings.main.project.none`; a "Clear" link empties it), "Recent projects to keep" spinner 1..50 (default 10; comment: also the popup size, see §5.1), "Ask to choose a main project" (the inverse of `dontAskAboutMainProject`, so it can be re-enabled).
- Base settings: config dir, plugins dir and vmoptions file, each with a chooser and the running IDE's path as comment; "Use current IDE paths" fills the three fields from `CurrentIdePaths` (vmoptions only when that file exists), applied only on Apply; "Offer plugin sync on IDE update" (`syncPluginsOnIdeUpdate`, same text as the popup toggle).
- In an isolated instance (`DirectoryManager.isIsolatedConfigDir(current configDir)`) the Projects and Base settings groups are disabled and `settings.isolated.comment` is shown on top.

Apply validates first (`DialogPanel.validateAll`, a failure becomes a `ConfigurationException`), then pushes the UI into `JugglerSettings` and calls `saveJugglerSettings`:

| Field input | Saved as | Validation error |
|---|---|---|
| blank | `null` (the main instance refills it from the current IDE at next start, §2) | none |
| `~/x` or relative | absolute, normalized, `~` expanded (`PathUtils.resolveAbsolute`) | none |
| not a valid path | not saved | `settings.error.path.invalid` |
| missing path | not saved | `settings.error.path.missing` |
| config/plugins not a dir, vmoptions not a file | not saved | `settings.error.path.not.directory` / `.not.file` |
| under `<ideDir>/projects/` | not saved | `settings.error.path.isolated` |

All fields except the main project go into one `repository.update`; `maxRecentProjects` is coerced to 1..50 (`recent.json` is trimmed at the next `recordOpen`, core-spec §7.1, and the popup shows at most that many at once); a changed vmoptions path refreshes `baseVmOptionsHash`. A save failure is logged as a warning and shown as `settings.error.save.failed`. The main project is never written by the save: it returns `MainProjectChange.Set(resolved path)` when the field differs from the stored value (`PathUtils.isSamePath`), `Clear` when the field is blank and one is stored, else null (no call, no notification). A missing main project is refused (`settings.error.main.project.missing`). The change runs on a pooled thread through `setMainProject` / `clearMainProject` (§5.5) with `IntelliJNotificationHandler(null)`, so the stale-entry cleanup and the `notification.success.set.main` / `unset.main` balloons are the popup's; a failure there is logged and shown as `settings.error.main.project.failed`.

`reset()` (dialog Reset, reopen) reloads `config.json`, so a main project changed meanwhile from the popup is shown correctly.

## 8. Open a Project Copy (git worktree)

`OpenProjectCopyAction` shows `OpenProjectCopyDialog`, then hands the input to a pooled thread:

1. `OpenProjectCopyDialog` (`dialog.project.copy.*`, a `DialogWrapper`) has three fields: source repository (text with folder browse, `file.chooser.source.*`), new branch, and worktree folder. The worktree folder follows `suggestDestination(source, branch)` = `<source parent>/<source name>-<branch with '/' replaced by '-'>` until the user edits it; its browse button (`file.chooser.destination.*`) picks the *parent* folder and appends the current (or suggested) folder name, since a folder chooser cannot select a folder that does not exist yet. Cancel does nothing. No Koin, no I/O while the dialog is open.
2. On OK, `doValidate` runs `validateProjectCopyInput` (`services/OpenProjectCopy.kt`) and shows the first failure inline on its field; the dialog stays open. Checks in field order, values trimmed, paths resolved with `PathUtils.resolveAbsolute` (same as `ProjectManager.resolvePath`): blank source (`dialog.project.copy.error.blank.source`), unparsable path (`dialog.project.copy.error.invalid.path`), source is a directory (`notification.error.not.directory`), source is a git repo (`GitWorktreeManager.isGitRepository`, a `.git` stat; `notification.error.not.git.repository`), blank branch, blank or unparsable destination, destination does not exist (`notification.error.destination.exists`). Messages name the trimmed text as typed. These are at most three local file stats and never a git process, so they run on EDT by design.
3. On a pooled thread, `openProjectCopy(input, IntelliJNotificationHandler(project))` re-validates (authoritative; an `Invalid` result becomes an error notification and stops), posts `notification.info.worktree.started` (source folder name), resolves the repository (`IdeInstallationService.currentIdeConfigRepository`) and calls `createAndLaunchProjectCopy(repo, request, handler)`: `GitWorktreeManager.createWorktree(source, dest, branch, null)` and `launcher.launch(dest)` (no `onFailure`, no main-project prompt, no focus check). Success -> `notification.success.worktree.launched` (destination name, branch); any `Throwable` in either function -> `notification.error.worktree.failed` plus a logged error; control-flow exceptions are rethrown. The default launcher is resolved before the `try`.

## 9. Bundled sync-helper (`BundledSyncHelper`)

`getInstallDir()` (`@RequiresBackgroundThread`, `@Synchronized` inside) works under the root `PathManager.getSystemDir()/project-juggler/sync-helper/` of the running IDE (`<projRoot>/system/...` in an isolated instance, so `ProjectCleaner` removes it with the project). Only this IDE process uses that root.

- The extraction directory is `<root>/<fingerprint>`, where the fingerprint is the first 16 hex chars of a SHA-256 over the sorted `<relative path>:<content id>` of every regular file under `sync-helper/bin` and `sync-helper/lib` in plugin resources (content id: size and CRC from the jar's central directory, no decompression; in dev mode with a file URL, a SHA-256 of the file). It is computed once per class load. Fingerprint and extraction walk the same entry list, from `jar:` URLs (JAR entries with the prefix) or file URLs (`Files.walk`).
- `lib/sync-helper.jar` marks a complete extraction. If it exists under `<root>/<fingerprint>`, that directory is returned without extraction (also across IDE sessions); if it was deleted, the next call re-extracts.
- Otherwise the files are extracted into `<root>/<fingerprint>.tmp-*`, the jar is checked, an incomplete `<root>/<fingerprint>` is deleted, and the temp dir is moved into place with `ATOMIC_MOVE`, so a crash mid-extraction never leaves a directory that looks complete. If the move fails but a complete target exists, the temp dir is deleted and the target used. Any other failure deletes the temp dir, logs an error and throws `IllegalStateException` (also for a missing resource or jar). No executable bit is set; `bin/` is extracted for manual runs only.
- After an extraction, every other entry of the root (old fingerprints, leftover `*.tmp-*`) is deleted recursively, and once per JVM the legacy `java.io.tmpdir/sync-helper-*` directories of earlier versions are deleted when they contain `bin/` and `lib/sync-helper.jar` and were last modified more than 1 hour ago (an older plugin in another IDE may be mid-sync from one). Each deletion is best effort: failures are logged at info.

`command(installDir, javaHome, baseDir, args, platform = Platform.current())` is `<javaHome>/bin/java[.exe] -cp <installDir>/lib/* [-Dproject.juggler.base.dir=<baseDir>] com.projectjuggler.synchelper.SyncHelperMainKt <args>` (`MAIN_CLASS`). `java -cp` expands the `*` itself, so no shell is involved. The start scripts are bypassed: running on the IDE's JVM needs no system `java`, and setting no `JAVA_HOME` keeps the JBR out of the relaunched IDE's environment, which it inherits from the helper.

`start(args, logFile = <log dir>/sync-helper.log)` (`@RequiresBackgroundThread`) builds `command(getInstallDir(), System.getProperty("java.home"), System.getProperty("project.juggler.base.dir"), args)`, creates the log dir, starts it with `redirectErrorStream(true)` into `logFile` (overwritten), closes the process's stdin and logs the command line and log file at info. It returns the `Process`, which `performSelfShutdownSync` watches (§6.5).

The helper's CLI contract: `--ide <path> --path <project> (--all | --vmoptions | --config | --plugins)...`, strict parsing, exit codes 0 (synced), 1 (failure), 2 (usage); it only uses an existing ideDir (`IdeRegistry.findRepository`) and writes `<projRoot>/self-sync-result.json`. See [sync-helper-spec.md](sync-helper-spec.md).

## 10. Shutdown signal handling (`shutdown/`)

- `ShutdownSignalAppListener.appFrameCreated` (an `AppLifecycleListener`, fired on every start before IntelliJ chooses between opening a project and the welcome screen) calls `ShutdownSignalService.start()` at application start, so an isolated IDE on the welcome screen or with its project closed is monitored. `ShutdownSignalServiceStartupActivity` (`ProjectActivity`) calls it too, as the fallback for dynamic plugin loading, where `appFrameCreated` does not fire. `start()` is idempotent (`AtomicBoolean`); the constructor does no work. Detection and polling run in one `Dispatchers.IO` coroutine.
- Detection: `IsolatedInstance.currentProjectId()` (§6.3); load its metadata from the current repository (`Throwable` caught and logged, for detection as a whole and for the metadata load). If this is not an isolated instance (or detection fails), monitoring is disabled and logged.
- Monitoring (coroutine on `Dispatchers.IO`): `ShutdownSignalManager.cleanupStaleSignals(project, 5)` once, then every 1 s `readStopRequest`. Whether to act is decided by `StopRequestTracker.shouldAct(signal)` (`shutdown/StopRequestTracker.kt`): true once per `requestId` (in-memory set) and only while the request's age is at most its `timeoutSeconds` (5 minutes when `timeoutSeconds <= 0`), compared exactly; after that the requester has given up. Stale or unparseable requests are logged once per `requestId`, never acted on and never remembered as processed. Action, on the main dispatcher: log `requestId`, `autoRestart`, `syncTypes`, `timeoutSeconds`, then `ApplicationManagerEx.getApplicationEx().exit(force = false, exitConfirmed = true)`: no "Confirm exit" dialog (an unattended IDE would block the requester until its timeout), settings are still saved, and IntelliJ still asks when background tasks run. `autoRestart` and `syncTypes` are informational; restart is the requester's job (core-spec §8 step 5). Errors are logged and polling continues.

## 10a. Stale main-project entry cleanup (`startup/StaleMainProjectCleanupActivity`)

`ProjectActivity`, so it runs per project open. On `Dispatchers.IO` it calls `cleanStaleMainProjectEntryIfMainInstance(repo, PathManager.getConfigDir())` (`services/StaleMainProjectCleanup.kt`): null unless `isRunningInMainInstance`, else `ProjectCleaner.cleanStaleMainProjectEntry()` (core-spec §9). The activity logs the removed entry, if any. Any `Throwable` is logged; `ControlFlowException` is rethrown. Together with the call in `setMainProject`, this guarantees the main project does not keep a `projects/<id>/` entry once its isolated instance has exited.

## 10b. Self-sync result (`services/SelfSyncReport.kt`, `startup/SelfSyncResultActivity`)

`reportSelfSyncResult(repository, projectId, exitCode, logFile, notifications)` consumes the project's `SelfSyncResult` (`SelfSyncResultStore.consume`, core-spec §8.2) and shows, through the `NotificationHandler`:

- `succeeded`: info `notification.self.sync.succeeded` ("Synced <types> for <name>"), plus error `notification.self.sync.restart.failed` when `restartError` is set;
- not `succeeded`: error `notification.self.sync.failed` ("Sync of <types> for <name> failed: <message>. Details: <log>"), plus the restart error as above; a result without a message reads `notification.self.sync.unknown.error`;
- no result (or no metadata for the id) and `exitCode` neither null nor 0: error `notification.self.sync.helper.exited`;
- no result and `exitCode` null or 0: nothing.

`<types>` maps the result's `syncTypes` back through `SyncType`: all three give `SyncType.All.displayName`, otherwise the display names joined with ", ". `<name>` is the project's display name, `<log>` the `sync-helper.log` path. Any `Throwable` is logged as a warning; `ControlFlowException` is rethrown.

`SelfSyncResultActivity` (`ProjectActivity`, so it runs per project open) does nothing unless `IsolatedInstance.currentProjectId()` is set; then, on `Dispatchers.IO`, it calls `reportSelfSyncResult(currentRepository, id, exitCode = null, PathManager.getLogDir()/sync-helper.log, IntelliJNotificationHandler(project))`. Because the file is consumed, the result shows once, in the first project window of the relaunched IDE. Other failures are logged as errors.

## 11. Plugin sync on IDE update (`startup/PluginSyncOnUpdateActivity`)

`ProjectActivity`, so it runs per project open. It resolves the current repository, the current build (`ApplicationInfo.build.asString()`) and an `IntelliJNotificationHandler(project)`, then calls `pluginSyncTargetsAfterUpdate(repo, PathManager.getConfigDir(), currentBuild, handler)` (`services/PluginSyncTargetsAfterUpdate.kt`) on `Dispatchers.IO`. Failures before that call (Koin, repository) propagate to the platform. The function syncs nothing; it returns the projects to offer a sync for:

1. Empty unless `MainProjectService.isRunningInMainInstance(repo, currentConfigDir)`.
2. Reads `lastKnownBuildNumber`, then always stores the current build.
3. Empty if there was no previous build, the build is unchanged or `syncPluginsOnIdeUpdate` is false; else `ProjectManager.listSyncable()` (tracked projects minus the main project), possibly empty.
4. Any other `Throwable` -> `notification.sync.on.update.failed`, a logged error and an empty list. `ControlFlowException` is rethrown.

When the list is non-empty the activity shows an info balloon `notification.sync.on.update.available` (build, count) with an expiring "Sync plugins" action (`notification.sync.on.update.action`, via `showNotification(..., actions)` in `Utils.kt`). Nothing is stopped or synced until it is clicked. The click runs `performSyncWithProgress` (§6.4) over the captured targets with `SyncType.Plugins`, title `progress.sync.on.update`, success `notification.sync.on.update.complete` (count) and failure `notification.sync.on.update.failed` (with the failure summary); running isolated IDEs are stopped and restarted there, with progress and cancel between projects.

Because the build number is stored before the checks, only the first project opened after an update offers the sync. Ignoring or closing the balloon skips that build; the next IDE update offers again.

## 12. Threading summary

| Code | Thread |
|---|---|
| File choosers, `Messages` dialogs, main-project prompt | EDT (dialogs) |
| All repository/lock/git I/O after a user choice (`resolvePath`, launch, sync planning, toggles, import, project copy) | pooled thread or `Task.Backgroundable` |
| `launchOrFocusUnderProgress`, `performSyncWithProgress` | `Task.Backgroundable` |
| Self-shutdown helper extraction and spawn | `Task.Backgroundable` |
| Remove Project deletion and popup reshow | `Task.Backgroundable` |
| Open a Project Copy dialog validation (local stats) | EDT |
| Popup building, import | pooled thread |
| Open a Project Copy worktree validation, creation, launch | pooled thread |
| Set/Unset main project config write and stale-entry cleanup | pooled thread |
| `ShutdownSignalService` start and polling | `Dispatchers.IO` coroutine, from app start |
| `PluginSyncOnUpdateActivity` checks (`pluginSyncTargetsAfterUpdate`), `StaleMainProjectCleanupActivity` cleanup, `IdePathsCheckActivity` check, `SelfSyncResultActivity` report | `Dispatchers.IO` |
| Self-sync report after the helper exits while the IDE runs | the `Process.onExit()` completion thread |
| `PluginSyncOnUpdateActivity` offered sync ("Sync plugins") | `Task.Backgroundable` |
| `IdePathsCheckActivity` "Update paths" / "Use current IDE paths" | pooled thread |
| Settings page load (`createComponent`, `reset`), validation (path stats) and save of `config.json` (`apply`) | EDT (`Configurable` methods are EDT-bound) |
| Settings page main-project change (`setMainProject` / `clearMainProject`) | pooled thread |

## 13. Tests

Kotest `StringSpec` + MockK specs in `plugin/src/test/kotlin/com/projectjuggler/plugin/`, run by `./gradlew :plugin:test` (also part of `./gradlew test`). They target the plain functions in `services/`, `shutdown/` and `actions/recent/` that the UI classes call, not Swing or the IDE.

Setup:

- Helpers shared with core come from core's test fixtures (`core/src/testFixtures`): `KoinTestExtension` (every spec that reaches a `getInstance(...)` registers it, except `di/PluginKoinTest`, which starts and stops Koin itself) and `createTempDir`.
- `plugin/test/`: `TestApplicationExtension` boots IntelliJ's headless test application in `beforeSpec` (`initTestApplication()`, `TestFrameworkType.JUnit5`); only specs that need a real `Application` (notifications, `PathManager`, `ApplicationNamesInfo`) register it. `expectLoggedError { ... }` runs a block that must log exactly one `LOG.error` and returns the logged `Throwable` (IntelliJ's logger turns logged errors into test failures); it needs `test-framework-core`, hence the extra `testFramework(TestFrameworkType.Platform)`. Every test that reaches a `LOG.error` path is wrapped in it. `RecordingNotificationHandler` records info/error messages; `PluginTestSupport.kt` has `testRepository`, `registerProject`, `markIsolatedInstanceOpen` (writes a `.lock` with the live PID), `writeGitHead` and `pluginXml()`/`elements(tag)` (this plugin's `plugin.xml`).
- The `test` task sets `project.juggler.base.dir` to `plugin/build/project-juggler-test`, so the real `IdeRegistry` never touches `~/.project-juggler`.
- Kotest, MockK, koin-test and the core test fixtures pull `kotlinx-coroutines-core` 1.7.0, which shadows the IDE's patched coroutines and breaks the test application (`NoSuchMethodError ... DebugProbesImpl.install`). Each test dependency excludes that module (`withoutCoroutines()`); a `configurations.all` exclude would also remove the IDE's copy and break `compileKotlin`. `junit:junit` is a runtime-only test dependency because the IntelliJ test framework still references JUnit 4 classes. None of these end up in the plugin zip.

Covered, by spec:

| Spec | Covers |
|---|---|
| `UtilsTest` | `rethrowIfControlFlow`, `userMessage` |
| `ProjectJugglerBundleTest` | formerly hardcoded keys are present, `SyncType` names come from the bundle |
| `actions/recent/SyncTypeTest` | sync booleans, `cliFlag`, `displayName` per type |
| `actions/recent/RecentProjectPopupBuilderTest` | `buildItems`: item order, main project, filtering, `maxRecentProjects` limit, path-mismatch item, git branch, `isOpen` for other IDEs' and the current IDE's projects, toggle text |
| `actions/recent/RecentProjectPopupTest` | `projectSubActions` (with and without Open with entries), speed-search and list texts, main toggle text from `item.isMainProject` |
| `di/PluginKoinTest` | `IdeInstallationService.getInstance()` starts Koin, returns one instance, loads `pluginModule` into a Koin started without it |
| `services/PlatformRecentProjectsTest` | recent paths and icon fallbacks for another `RecentProjectsManager`, paths from `RecentProjectsManagerBase` |
| `actions/recent/CompactPathTest` | `compactPath` |
| `util/BundledSyncHelperTest` | fingerprint dir under the root, reuse without re-extraction, re-extraction after deletion, stale fingerprint and temp-dir cleanup, existing extraction left as is, legacy temp-dir cleanup by layout and age, nothing created in the system temp dir, parallel calls, fingerprint format, jar marker, `command` on Unix and Windows with and without a base dir, `start` output in the log file |
| `util/IntelliJNotificationHandlerTest` (test application) | notification group, type and content |
| `services/IsolatedInstanceTest` | `projectIdFromConfigPath`, `isCurrentProject` |
| `shutdown/StopRequestTrackerTest` | fresh, repeated, stale and unparseable stop requests; age against `timeoutSeconds` and the 5-minute fallback |
| `shutdown/ShutdownSignalRegistrationTest` | `plugin.xml` registers `ShutdownSignalAppListener` on the `AppLifecycleListener` topic (`pluginXml()` in `PluginTestSupport.kt`) |
| `services/SyncPlannerTest` | `forProject`, `forAllProjects` targets and helper args; with the current project, `--path <current>` and `others` without it; the main project excluded |
| `services/SelfSyncReportTest` (test application) | every `reportSelfSyncResult` case (success, all types, restart error, failure, helper exit code, nothing at startup or exit 0, unknown project) and that the result is consumed |
| `services/SyncProjectsTest` | sync calls, options, progress texts and fractions, continuing past failures, cancellation before each project and from a project's sync, `describeFailures` |
| `services/LaunchPlanTest` | `planLaunch` current-IDE step and mapping of core's `planOpen` (whose cases are in core's `LaunchOrFocusTest`), another installation's repository, no directories created |
| `services/OpenWithIdeTest` | `openWithInstallations` exclusions, base-settings filter and order; `shouldPromptForMainProject` per target repository |
| `services/CurrentIdeProjectTest` | `belongsToCurrentIde` for isolated, unregistered and main projects; `isProjectRunning` platform vs lock |
| `services/LaunchProjectTest` | launch, crash and start-failure notifications, control-flow exceptions propagate |
| `services/FocusExistingProjectTest` | each focus outcome and its message, non-`Exception` errors, control-flow exceptions propagate |
| `services/MainProjectActionsTest` | `setMainProject`, `clearMainProject` |
| `services/RemoveProjectTest` | `removeRecentProject`: removal, unknown path, refusal while running, failed deletion (Unix) |
| `services/RecentProjectsImportTest` | `importableProjects`, `importProjects` |
| `services/IdePathSyncDetectorTest` | `detectMismatches` (normalized and symlinked paths, isolated instance), `updatePaths`, `autoPopulateIfNeeded` (isolated instance, per-field fill, repair of isolated paths) |
| `services/CurrentIdePathsTest` (test application) | `detect().vmOptionsFile`: platform file without, with a missing, and user file with an existing `jb.vmOptionsFile` |
| `services/IdePathsStartupCheckTest` | `problemsToReport`: out of sync on the first call only, missing, both kinds, no problem, isolated instance |
| `services/JugglerSettingsTest` | `loadJugglerSettings`, `saveJugglerSettings` (blank, `~`, normalization, checkboxes, limit coercion, vmoptions hash, returned main-project change), `validateBasePath` per error, `validateMainProject`, `withCurrentIdePaths`, `applyMainProjectChange` set and clear |
| `settings/ProjectJugglerConfigurableTest` (test application) | the page builds on EDT, `reset()` reloads `config.json` (spinner value), `plugin.xml` registers it under Tools |
| `services/PluginSyncTargetsAfterUpdateTest` | main-instance check, build bookkeeping, targets are the isolated projects only, no sync or notification of its own, failures |
| `services/StaleMainProjectCleanupTest` | `cleanStaleMainProjectEntryIfMainInstance` in isolated and main instances |
| `services/OpenProjectCopyTest` | `validateProjectCopyInput` per field and check, `suggestDestination`, `openProjectCopy` re-check failure, `createAndLaunchProjectCopy` success and worktree failure |
| `services/CurrentIdeExecutableTest` | `currentIdeExecutable` per platform and script name |
| `services/IdeInstallationServiceTest` (test application) | current installation keyed on the script-name launcher, adoption of a legacy home-keyed dir, repository in the sandbox IDE, auto-populated base paths, `VMOptionsCopy` files |

Not covered: `showMainProjectPrompt` and the other `Messages` dialogs, `RecentProjectPopup`/renderer Swing behavior, `performSelfShutdownSync` (process spawn, stop through `ShutdownSignalService`, relaunch and the startup notification; checked by hand in the sandbox IDE), the `ShutdownSignalService` polling loop, `start()` and exit, `ShutdownSignalAppListener` itself, `openProjectInCurrentIde` (`ProjectUtil.findProject`/`openOrImport`), `isOpenInCurrentIde` (`ProjectUtil.findProject`), `WindowFocuser` (core), the `OpenProjectCopyDialog` Swing behavior, the settings page's apply and validation through the dialog (the logic is covered by `JugglerSettingsTest`), and the actions and startup activities themselves.

Gaps pinned by tests (the test name carries the number): 16 (`SyncPlannerTest`).

## Known gaps

16. Sync all projects with no syncable target (only the main project tracked) still runs an in-process sync of zero projects and reports success for 0 projects.
17. `IsolatedInstance.projectIdFromConfigPath` uses the first `projects` segment of `idea.config.path`. When `<base>` itself contains a `projects` segment (e.g. `-Dproject.juggler.base.dir=~/projects/pj`), the id is null and an isolated instance behaves as the main one: no stop-request polling, a self-sync is planned in process, and `autoPopulateIfNeeded` may store isolated dirs as base paths. Three different tests decide "isolated": this parser, `DirectoryManager.isIsolatedConfigDir` on `PathManager.getConfigDir()` and `MainProjectService.isRunningInMainInstance`.
18. `showMainProjectPrompt` writes `dontAskAboutMainProject` to `config.json` on EDT (§4.3).
19. In `performSelfShutdownSync`, `connection.disconnect()` in the `onExit` callback runs outside the guarded block; if the application is disposed by then, the exception is lost in the `CompletableFuture` and nothing is logged (§6.5).
