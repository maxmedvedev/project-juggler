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
- `runIde` passes `-Dproject.juggler.base.dir=/Users/max/.project-juggler-test` (a machine-specific absolute path).
- Registered extensions: one notification group `project-juggler.notifications` (balloon), four `postStartupActivity` entries (`ShutdownSignalServiceStartupActivity`, `PluginSyncOnUpdateActivity`, `StaleMainProjectCleanupActivity`, `IdePathsCheckActivity`), three actions in the File menu after "Open...": Open with Project Juggler, Recent Juggled Projects (`Cmd/Ctrl+Shift+P`), Open a Project Copy.

## 2. Initialization and DI

- `KoinInit.init(pluginModule)` is called lazily at the entry of every action (`actionPerformed`), inside `ShutdownSignalService.init`, and in the two startup activities (`PluginSyncOnUpdateActivity`, `StaleMainProjectCleanupActivity`). It is idempotent, so the first caller wins; there is no single startup hook.
- `pluginModule` declares one singleton: `IdeInstallationService(IdeRegistry)`. Access is through `IdeInstallationService.getInstance()` (Koin `GlobalContext`) or the shortcut `IdeInstallationService.currentIdeConfigRepository`.
- `IdeInstallationService`:
  - `currentInstallation` (lazy): `currentIdeExecutable(homePath, ApplicationNamesInfo.scriptName, platform)` (`services/CurrentIdeExecutable.kt`) resolves `<home>/MacOS/<script>` (macOS), `<home>/bin/<script>.sh` (Linux), `<home>/bin/<script>64.exe` (Windows), so PyCharm, Android Studio etc. get their own launcher. When the file does not exist a warning is logged (launches from this IDE will fail) and the path is used anyway. Then `IntelliJLocator.findInstallation(path)`. The home directory is never the key.
  - `currentRepository` (lazy): first `IdeRegistry.adoptLegacyRepository(currentInstallation, listOf(homePath))` (core-spec §4), which moves a dir older plugin versions keyed on the home directory to the executable-keyed one; adoption is logged at info, a failure is logged as a warning (`Throwable` caught, `ControlFlowException` rethrown) and startup continues. Then `IdeRegistry.getRepository(currentInstallation)` followed by `IdePathSyncDetector.autoPopulateIfNeeded(repo)`.
- `IdePathSyncDetector.autoPopulateIfNeeded(repo, current = CurrentIdePaths.detect())` (§7): runs only in the main instance (`current.configDir` is not `DirectoryManager.isIsolatedConfigDir`), so an isolated instance, e.g. one started by `sync-helper`, never stores its isolated dirs as base. For each `IdePathKind` it sets the stored path to the current one when the stored value is null or lies under `<ideDir>/projects/` (an isolated dir captured earlier, repaired); the vmoptions path only when the current file exists. All fills happen in one `repository.update`, nothing is written when no kind needs filling, and the vmoptions hash is refreshed when the vmoptions path was filled. Paths that are set and outside the ide dir are never touched. Errors are logged (`Throwable` caught, `ControlFlowException` rethrown), not surfaced. `CurrentIdePaths.detect()` is a default argument, so a failure there propagates instead.
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

### 4.2 `launchOrFocusProject(project, path, repository = current)` (`actions/Launch.kt`)

`repo` below is `repository`, the current installation's repository unless a caller (Open with <IDE>, §5.5) passes another installation's. The main-project prompt only runs for the current installation's repository.

1. If `shouldPromptForMainProject(repo, currentRepo, PathManager.getConfigDir())` (`services/OpenWithIde.kt`: `repo` is the current installation's, and `MainProjectService.shouldPromptForMainProject`: no main project, not suppressed, running in main instance): on EDT show the main-project prompt (§4.3), then on a pooled thread:
   - `SetAsMain`: `setMainProject` (persist, `ProjectCleaner.cleanStaleMainProjectEntry()`, `notification.success.set.main`) and open the project in the current IDE (`openProjectInCurrentIde`, focuses it if already open).
   - `OpenIsolated`: continue to step 2.
   - `Cancelled`: nothing.
2. Otherwise queue a non-cancellable `Task.Backgroundable` titled `progress.opening.project` that runs the step returned by `planLaunch(repo, path, PathManager.getConfigDir())` (`services/LaunchProject.kt`). `planLaunch` picks one step. If the project belongs to the current IDE (`belongsToCurrentIde`: the main project in the main instance, or an isolated project whose `<projRoot>/config` is `PathManager.getConfigDir()`), open it here (`OPEN_IN_CURRENT_IDE`): focus its frame if already open (`ProjectUtil.findProject`), else `ProjectUtil.openOrImport` in a new frame (on a pooled thread). Else the main project is always launched (`LAUNCH`, `launchMain`; a running main IDE opens or focuses it). Else focus when `ProjectLockUtils.isProjectOpen(repo, path)` (`FOCUS`, `focusExistingProject`, §4.4), launch otherwise (`LAUNCH`, `launchProject`, §4.5).

### 4.3 Main-project prompt (`MainProjectPromptService`)

Yes/No/Cancel dialog (`dialog.main.project.prompt.*`) with a "Don't ask again" checkbox. Result mapping: Yes -> `SetAsMain`, No -> `OpenIsolated`, anything else -> `Cancelled`. When the checkbox is selected and the exit code is not Cancel, `dontAskAboutMainProject = true` is persisted before returning. Must be called on EDT; the dialog has no parent project.

### 4.4 Focus (`services/FocusExistingProject.kt`)

`readPidFromLock` -> null means `notification.error.focus.no.pid`. `isProcessRunning(pid)` false means `notification.error.focus.process.not.found`. Then `WindowFocuser.focus(pid)`; each non-`Success` result maps to one error message (`focus.process.not.found`, `focus.window.not.found`, `focus.failed`, `focus.tool.missing`). Any other `Throwable` -> `focus.exception` (logged as a warning). `ControlFlowException` is rethrown. Success is silent.

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
| Open with <IDE> | `launchOrFocusProject` against that installation's repository (`IdeInstallationService.getRepository`); no main-project prompt; the project is tracked and recorded in that IDE's `<ideDir>`, the current IDE's recents are untouched. Focuses that IDE's running instance of the project, else `ProjectLauncher.launch` with that IDE's executable (its own main project goes through `launchMain`). |
| Set / Unset Main Project | OK/Cancel confirm (`dialog.confirm.set.main.*` / `unset.main.*`) on EDT, then on a pooled thread `setMainProject(path)` (persist, `ProjectCleaner.cleanStaleMainProjectEntry()`, success notification) or `clearMainProject()` with a success notification. |
| Sync All Settings / VM Options / Configuration / Plugins | §6.1 for this project. Not shown for the main project. |
| Remove Project | OK/Cancel warning dialog (`dialog.confirm.remove.project.*`, naming the project and `<projRoot>`) on EDT after the popup closes; Cancel does nothing. On OK, a non-cancellable background task (`progress.removing.project`) runs `removeRecentProject`: an unknown project is a no-op; it refuses with `notification.error.remove.project.running` while the isolated instance is open (`isIsolatedInstanceOpen`); otherwise `ProjectCleaner.cleanProject(metadata)` (removes the recent entry, then deletes `<projRoot>` recursively, core-spec §9); a failure shows `notification.error.remove.project.failed` and is logged as a warning (the recent entry is already gone). The popup is reshown from the task when it finishes. Not offered for the main project. |

## 6. Sync from the plugin (`actions/Sync.kt`)

`SyncType` is a sealed class: `All`, `VmOptions`, `Config`, `Plugins`, with `displayName` from bundle keys `sync.type.*` ("all settings", "VM options", "configuration", "plugins"), `cliFlag` (`--all`, `--vmoptions`, `--config`, `--plugins`, the sync-helper flag) and three derived booleans passed to `ProjectLauncher.syncProject`.

Both entry points ask `SyncPlanner` (`services/SyncPlanner.kt`) for a `SyncTarget` and dispatch on it: `InProcess(projects)` -> `performSyncWithProgress`, `SelfShutdown(helperArgs)` -> `performSelfShutdownSync` (§6.5), `NoTarget` -> nothing. `helperArgs` is `--ide <executablePath> (--path <projectPath> | --all-projects) <cliFlag>`.

### 6.1 Single project (`SyncSettingsSubAction`)

`SyncPlanner.forProject(repo, path, syncType)`:

- If `IsolatedInstance.isCurrentProject(repo, path)` (§6.3): `SelfShutdown` with helper args `--ide <executablePath> --path <projectPath> <cliFlag>`; the info text is `notification.info.sync.self.shutdown.project`.
- Else load metadata (`NoTarget` if missing, the action silently returns) and `InProcess` for that one project. Title `progress.sync.project.type`, success `notification.success.sync.single.project.type`, failure `notification.error.sync.settings.failed`.

### 6.2 All projects (`SyncAllProjectsAction`)

`SyncPlanner.forAllProjects(repo, syncType)`:

- Targets are `ProjectManager.listSyncable()`: every tracked project except the main project (a stale tracked entry for the main project would otherwise make core send a stop request to the main IDE, which never polls for one, and time out after 60 s). If any target's id is the current isolated project id, `SelfShutdown` with `--ide <executablePath> --all-projects <cliFlag>`; the info text is `notification.info.sync.self.shutdown.all`.
- Else `InProcess` over the targets, possibly empty (gap 16). Title `progress.sync.all.projects.type`, success `notification.success.sync.all.projects.type` (type, count), failure `notification.error.sync.projects.failed`.

### 6.3 `IsolatedInstance` (`services/IsolatedInstance.kt`)

`projectIdFromConfigPath(configPath)` splits the path on both `/` and `\` (the value is written by `Path.toString()` on the launching OS) and returns `<id>` when it contains `projects/<id>/config`, null otherwise. `currentProjectId()` applies it to the `idea.config.path` system property. `isCurrentProject(repo, path, currentProjectId = currentProjectId())` is true when the target project's id from metadata equals the current id; false when the property is absent or the target has no metadata. This is the test for "syncing this would stop me". `SyncPlanner` and `ShutdownSignalService` (§10) both use it.

### 6.4 `performSyncWithProgress`

Cancellable-by-API `Task.Backgroundable` (default cancellable flag) that runs `syncProjects(launcher, projects, syncType, sink)` (`services/SyncProjects.kt`) with a `SyncProgressSink` adapter over the `ProgressIndicator` and `ProgressManager.checkCanceled()`. `syncProjects` uses `SyncOptions(stopIfRunning = true, autoRestart = true, shutdownTimeout = 60)`. Progress text follows `SyncProgress`: `Stopping` -> `progress.sync.stopping.ide`, `Syncing` -> `progress.sync.syncing.type`, `Restarting` -> `progress.sync.restarting.ide`; `Error` is ignored. Per project: `checkCanceled()`, text `progress.sync.project.type`, fraction `index/size` when more than one. The first `Throwable` propagates out of `syncProjects`, aborting the remaining projects; the task rethrows `ControlFlowException`, logs a warning and shows the error notification. Projects that are running are stopped via the signal protocol and restarted (core-spec §8).

### 6.5 Self-shutdown sync (`performSelfShutdownSync`)

1. Info notification (passed by the caller).
2. `BundledSyncHelper.getExecutable()` (§9).
3. Start `<executable> <helperArgs>` (the `SyncType.cliFlag` is already the last helper arg) with inherited IO, no wait.
4. `Thread.sleep(100)`, then `ApplicationManager.getApplication().exit()` on EDT. The IDE exits; the helper detects the lock release, syncs and relaunches.
5. Any `Throwable` except `ControlFlowException` -> `notification.error.sync.self.shutdown.failed`, logged as a warning. The IDE does not exit in that case.

## 7. IDE path drift (`IdePathSyncDetector`)

Stored base paths can go stale after an IDE update or relocation. The running IDE's paths are a `CurrentIdePaths(configDir, pluginsDir, vmOptionsFile)` (`services/CurrentIdePaths.kt`); `CurrentIdePaths.detect()` reads `PathManager.getConfigDir()`, `PathManager.getPluginsDir()` and the vmoptions file the IDE reads: `VMOptionsCopy.getUserOptionsFile()` if that file exists, else `getPlatformOptionsFile()`. It is the default argument of `detectMismatches`, `updatePaths` and `autoPopulateIfNeeded` (§2), so it is evaluated before their `try`.

The three base paths are the `IdePathKind` entries `CONFIG`, `PLUGINS`, `VM_OPTIONS` (`services/IdePathKind.kt`), each with its stored and current value; `displayName` comes from `ide.path.kind.*` ("Config", "Plugins", "VM Options").

`detectMismatches(repo, current)` returns an empty list when `current.configDir` is an isolated dir (`DirectoryManager.isIsolatedConfigDir`, core-spec §3). It does not use `MainProjectService.isRunningInMainInstance`, because after drift the stored `baseConfigPath` is exactly what is stale. Otherwise it returns, in `IdePathKind` order, each kind whose stored value is non-null and not `PathUtils.isSamePath` the current one (normalized, real paths when both exist, so trailing slashes, `.` segments and symlinks are not drift).

`updatePaths(repo, notificationHandler, current)` overwrites config and plugins paths, overwrites the vmoptions path only if the current file exists, refreshes the vmoptions hash when it exists, and notifies `notification.success.paths.updated` or `notification.error.paths.update.failed` (plus a logged error) through the handler; `UpdatePathsAction` passes `IntelliJNotificationHandler(project)`.

It is checked from the popup item (§5.1) and at startup: `startup/IdePathsCheckActivity` (`ProjectActivity`) inits Koin and, on `Dispatchers.IO`, calls `IdePathsStartupCheck.mismatchesToReport(repo, CurrentIdePaths.detect())`, which returns the mismatches only on its first call per IDE session (an `AtomicBoolean`) and is empty in an isolated instance. When non-empty the activity shows a warning balloon `notification.paths.out.of.sync` with the kinds and an expiring "Update paths" action (`notification.action.update.paths`, via `showNotification(..., actions)` in `Utils.kt`) that runs `updatePaths(repo, IntelliJNotificationHandler(project), CurrentIdePaths.detect())` on a pooled thread. Stored paths are never changed without that click. Any `Throwable` is logged; `ControlFlowException` is rethrown. An IDE that stays on the welcome screen runs no project activity and gets no balloon; the popup item still covers it.

## 8. Open a Project Copy (git worktree)

`OpenProjectCopyAction` shows `OpenProjectCopyDialog`, then hands the input to a pooled thread:

1. `OpenProjectCopyDialog` (`dialog.project.copy.*`, a `DialogWrapper`) has three fields: source repository (text with folder browse, `file.chooser.source.*`), new branch, and worktree folder. The worktree folder follows `suggestDestination(source, branch)` = `<source parent>/<source name>-<branch with '/' replaced by '-'>` until the user edits it; its browse button (`file.chooser.destination.*`) picks the *parent* folder and appends the current (or suggested) folder name, since a folder chooser cannot select a folder that does not exist yet. Cancel does nothing. No Koin, no I/O while the dialog is open.
2. On OK, `doValidate` runs `validateProjectCopyInput` (`services/OpenProjectCopy.kt`) and shows the first failure inline on its field; the dialog stays open. Checks in field order, values trimmed, paths resolved with `PathUtils.resolveAbsolute` (same as `ProjectManager.resolvePath`): blank source (`dialog.project.copy.error.blank.source`), unparsable path (`dialog.project.copy.error.invalid.path`), source is a directory (`notification.error.not.directory`), source is a git repo (`GitWorktreeManager.isGitRepository`, a `.git` stat; `notification.error.not.git.repository`), blank branch, blank or unparsable destination, destination does not exist (`notification.error.destination.exists`). Messages name the trimmed text as typed. These are at most three local file stats and never a git process, so they run on EDT by design.
3. On a pooled thread, `openProjectCopy(input, IntelliJNotificationHandler(project))` re-validates (authoritative; an `Invalid` result becomes an error notification and stops), posts `notification.info.worktree.started` (source folder name), resolves the repository (`KoinInit.init(pluginModule)`, `IdeInstallationService.currentIdeConfigRepository`) and calls `createAndLaunchProjectCopy(repo, request, handler)`: `GitWorktreeManager.createWorktree(source, dest, branch, null)` and `launcher.launch(dest)` (no `onFailure`, no main-project prompt, no focus check). Success -> `notification.success.worktree.launched` (destination name, branch); any `Throwable` in either function -> `notification.error.worktree.failed` plus a logged error; control-flow exceptions are rethrown. The default launcher is resolved before the `try`.

## 9. Bundled sync-helper (`BundledSyncHelper`)

`getExecutable()` returns a cached path if it still exists. Otherwise it extracts `sync-helper/bin` and `sync-helper/lib` from plugin resources into a fresh `Files.createTempDirectory("sync-helper-")`, handling both `jar:` URLs (iterate JAR entries with the prefix) and plain file URLs (dev mode, `Files.walk`). Executable is `bin/sync-helper.bat` on Windows, `bin/sync-helper` elsewhere, made executable on Unix. Missing resource or executable -> `IllegalStateException`. Extracted directories are never removed.

The helper's CLI contract (`sync-helper`): `--ide <path>` required; exactly one of `--path <project>` or `--all-projects`; any of `--all`, `--vmoptions`, `--config`, `--plugins` (none means nothing is synced but the stop/restart cycle still runs). It builds `IdeInstallation(idePath, "IDE")`, resolves the repository, and runs `syncProject` with `STOP_AND_RESTART` semantics and a 60 s timeout for each project. Unknown args are ignored.

## 10. Shutdown signal handling (`shutdown/`)

- `ShutdownSignalServiceStartupActivity` is a `ProjectActivity`; it only instantiates the app-level `ShutdownSignalService`. The service therefore starts when the first project opens, not at application start.
- `ShutdownSignalService.init`: init Koin; `IsolatedInstance.currentProjectId()` (§6.3); load its metadata from the current repository (`Throwable` caught and logged). If this is not an isolated instance (or metadata fails to load), monitoring is disabled and logged.
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
| Remove Project deletion and popup reshow | `Task.Backgroundable` |
| Open a Project Copy dialog validation (local stats) | EDT |
| Popup building, import | pooled thread |
| Open a Project Copy worktree validation, creation, launch, Koin init | pooled thread |
| Set/Unset main project config write and stale-entry cleanup | pooled thread |
| `ShutdownSignalService` polling | `Dispatchers.IO` coroutine |
| `PluginSyncOnUpdateActivity` (`syncPluginsOnIdeUpdate`), `StaleMainProjectCleanupActivity` cleanup, `IdePathsCheckActivity` check | `Dispatchers.IO` |
| `IdePathsCheckActivity` "Update paths" | pooled thread |

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
| `UtilsTest` | `rethrowIfControlFlow`, `userMessage` |
| `ProjectJugglerBundleTest` | formerly hardcoded keys are present, `SyncType` names come from the bundle |
| `actions/recent/SyncTypeTest` | sync booleans, `cliFlag`, `displayName` per type |
| `actions/recent/RecentProjectPopupBuilderTest` | `buildItems`: item order, main project, filtering, `maxRecentProjects` limit, path-mismatch item, git branch, `isOpen` for other IDEs' and the current IDE's projects, toggle text |
| `actions/recent/RecentProjectPopupTest` | `projectSubActions` (with and without Open with entries), speed-search and list texts |
| `actions/recent/CompactPathTest` | `compactPath` |
| `util/BundledSyncHelperTest` | extraction, caching, re-extraction |
| `util/IntelliJNotificationHandlerTest` (test application) | notification group, type and content |
| `services/IsolatedInstanceTest` | `projectIdFromConfigPath`, `isCurrentProject` |
| `shutdown/StopRequestTrackerTest` | fresh, repeated, stale and unparseable stop requests |
| `services/SyncPlannerTest` | `forProject`, `forAllProjects` targets and helper args |
| `services/SyncProjectsTest` | sync calls, options, progress texts and fractions, failure and cancellation |
| `services/LaunchPlanTest` | `planLaunch` step per target and current IDE, another installation's repository, no directories created |
| `services/OpenWithIdeTest` | `openWithInstallations` exclusions, base-settings filter and order; `shouldPromptForMainProject` per target repository |
| `services/CurrentIdeProjectTest` | `belongsToCurrentIde` for isolated, unregistered and main projects; `isProjectRunning` platform vs lock |
| `services/LaunchProjectTest` | launch, crash and start-failure notifications, control-flow exceptions propagate |
| `services/FocusExistingProjectTest` | each focus outcome and its message, non-`Exception` errors, control-flow exceptions propagate |
| `services/MainProjectActionsTest` | `setMainProject`, `clearMainProject` |
| `services/RemoveProjectTest` | `removeRecentProject`: removal, unknown path, refusal while running, failed deletion (Unix) |
| `services/RecentProjectsImportTest` | `importableProjects`, `importProjects` |
| `services/IdePathSyncDetectorTest` | `detectMismatches` (normalized and symlinked paths, isolated instance), `updatePaths`, `autoPopulateIfNeeded` (isolated instance, per-field fill, repair of isolated paths) |
| `services/CurrentIdePathsTest` (test application) | `detect().vmOptionsFile`: platform file without, with a missing, and user file with an existing `jb.vmOptionsFile` |
| `services/IdePathsStartupCheckTest` | `mismatchesToReport`: first call only, no mismatch, isolated instance |
| `services/SyncPluginsOnIdeUpdateTest` | main-instance check, build bookkeeping, plugin sync of isolated projects only, per-project and outer failures |
| `services/StaleMainProjectCleanupTest` | `cleanStaleMainProjectEntryIfMainInstance` in isolated and main instances |
| `services/OpenProjectCopyTest` | `validateProjectCopyInput` per field and check, `suggestDestination`, `openProjectCopy` re-check failure, `createAndLaunchProjectCopy` success and worktree failure |
| `services/CurrentIdeExecutableTest` | `currentIdeExecutable` per platform and script name |
| `services/IdeInstallationServiceTest` (test application) | current installation keyed on the script-name launcher, adoption of a legacy home-keyed dir, repository in the sandbox IDE, auto-populated base paths, `VMOptionsCopy` files |

Not covered: `showMainProjectPrompt` and the other `Messages` dialogs, `RecentProjectPopup`/renderer Swing behavior, `performSelfShutdownSync` (process spawn and IDE exit), the `ShutdownSignalService` polling loop, `openProjectInCurrentIde` (`ProjectUtil.findProject`/`openOrImport`), `isOpenInCurrentIde` (`ProjectUtil.findProject`), `WindowFocuser` (core), the `OpenProjectCopyDialog` Swing behavior, and the actions and startup activities themselves.

Gaps pinned by tests (the test name carries the number): 11 (`BundledSyncHelperTest` cleans up after itself), 16 (`SyncPlannerTest`).

## Known gaps

11. `BundledSyncHelper` extracts a full distribution into a new temp directory once per IDE session and never deletes it. Extraction happens on the calling thread. The helper process inherits the IDE's stdio.
12. `ShutdownSignalService` starts only after a project is opened; an isolated IDE sitting on the welcome screen ignores stop requests, so stop-and-sync times out against it. The JSON `autoRestart`/`timeoutSeconds` fields are not honored by the receiver.
13. `PluginSyncOnUpdateActivity` performs network-free but potentially long sync work (stopping and restarting every isolated IDE) on first project open with only a balloon as notice and no cancel. `performSyncWithProgress` aborts the whole batch on the first failure, unlike the update activity which continues.
14. `KoinInit.init` is called on EDT in several actions (marked `todo move to BGT`). `ToggleMainProjectSubAction` performs config file I/O under a file lock on EDT. `ImportRecentProjectsAction` casts `RecentProjectsManager` to `RecentProjectsManagerBase`, an implementation class.
15. `runIde` hardcodes a user-specific base dir. The plugin has no settings page or startup health check for missing base paths.
16. Sync all projects with no syncable target (only the main project tracked) still runs an in-process sync of zero projects and reports success for 0 projects.
