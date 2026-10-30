# Cover the plugin module with tests

Add a Kotest test suite to `plugin/`, extracting the decision logic out of actions, popup items and startup
activities into `plugin/services` functions first so that the tests target plain functions, not Swing or the IDE.

## Context

`plugin/` has no tests (`docs/plugin-spec.md` §1 and gap 15). Most logic sits inside `AnAction`s, popup `onChosen`
bodies and `ProjectActivity`s and reaches for globals (`IdeInstallationService.currentIdeConfigRepository`,
`PathManager`, `System.getProperty("idea.config.path")`, `ApplicationManager`). `CLAUDE.md` already requires actions to
stay thin and logic to live in `plugin/services` or core.

Decisions made with the user:

- **Extract, then test.** Decision logic moves into `plugin/services` (and `plugin/shutdown`) functions that take
  the `IdeConfigRepository`, the current IDE paths and a `NotificationHandler` as parameters. UI classes become
  shells that gather inputs, call the function and perform effects (dialogs, popups, process spawn, IDE exit).
- **Kotest `StringSpec` + MockK**, like core. Specs that need a real `Application` (notifications, `PathManager`,
  `ApplicationNamesInfo`) register a small Kotest extension that boots IntelliJ's headless test application. Pure specs
  run without it.
- **Core's test helpers become test fixtures** (`java-test-fixtures`), so the plugin reuses `KoinTestExtension` and
  `createTempDir` instead of copying them.
- **Tests pin current behavior and write the gaps down.** A test that documents a known gap carries the gap number
  in its name (`"... (gap 2)"`) and a one-line comment pointing at the spec. No gap is fixed here; fixes get their own
  plans like the core gaps did. One exception: code this plan *moves* follows the project's try-catch rule (catch
  `Throwable`, rethrow `ControlFlowException`), so the parts of gap 4 inside moved code disappear and the spec entry is
  trimmed.

Verified in a throwaway experiment on this machine (IntelliJ Platform Gradle Plugin 2.10.5, `intellijIdea("2025.1.1")`):

- Kotest runs under the plugin's `test` task; `ProjectJugglerBundle.message(...)` resolves without an `Application`.
- `com.intellij.testFramework.common.initTestApplication()` (from `TestFrameworkType.JUnit5`) called in a Kotest
  `beforeSpec` boots a headless application; it returns `Result<Unit>`. `PathManager.getConfigDir()` is then
  `plugin/build/idea-sandbox/<IDE>/config-test`, and notifications are observable on `Notifications.TOPIC`.
- Kotest, MockK and koin-test pull `kotlinx-coroutines-core` 1.7.0, which shadows the IDE's patched coroutines and
  fails `initTestApplication` with `NoSuchMethodError ... DebugProbesImpl.install`. Excluding that module *per test
  dependency* fixes it; a `configurations.all` exclude breaks `compileKotlin` (the IDE's coroutines go too).
- `java-test-fixtures` on core compiles the moved helpers (the `testFixtures` compilation sees core's `internal`
  `IdeScopeManager.reset()`) and `:core:test` passes unchanged.

## Target behavior

No user-visible change. `./gradlew :plugin:test` runs the new suite; `./gradlew test` runs core and plugin tests.
The refactoring keeps every behavior described in `docs/plugin-spec.md`, including the known gaps.

## Wiring

`build.gradle.kts` (root): add `val kotestVersion by extra("5.8.0")` and `val mockkVersion by extra("1.13.9")` next to
`koinVersion`; `core/build.gradle.kts` uses them instead of its literals.

`core/build.gradle.kts`:

```kotlin
plugins { kotlin("jvm"); kotlin("plugin.serialization"); `java-test-fixtures` }
dependencies {
    testFixturesImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testFixturesImplementation("io.insert-koin:koin-test:$koinVersion")
    // existing testImplementation lines unchanged; the test source set sees testFixtures automatically
}
```

Move (`git mv`) `core/src/test/kotlin/com/projectjuggler/di/KoinTestExtension.kt` and
`core/src/test/kotlin/com/projectjuggler/test/TempDirExtension.kt` to the same packages under
`core/src/testFixtures/kotlin/`. No content change.

`plugin/build.gradle.kts`:

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

val kotestVersion: String by rootProject.extra
val mockkVersion: String by rootProject.extra

dependencies {
    implementation(project(":core"))
    implementation("io.insert-koin:koin-core:$koinVersion")
    intellijPlatform {
        intellijIdea("2025.1.1")
        pluginVerifier()
        testFramework(TestFrameworkType.JUnit5)
    }
    // The IDE ships a patched kotlinx-coroutines; a second copy from the test libraries breaks the test application
    fun ModuleDependency.withoutCoroutines() =
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    testImplementation(testFixtures(project(":core"))) { withoutCoroutines() }
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion") { withoutCoroutines() }
    testImplementation("io.mockk:mockk:$mockkVersion") { withoutCoroutines() }
    testImplementation("io.insert-koin:koin-test:$koinVersion") { withoutCoroutines() }
    testRuntimeOnly("junit:junit:4.13.2") // the IntelliJ test framework still references JUnit 4 classes
}

tasks.test {
    useJUnitPlatform()
    // Keep IdeRegistry away from ~/.project-juggler (IdeRegistry.PROJECT_JUGGLER_BASE_DIR)
    val testBaseDir = layout.buildDirectory.dir("project-juggler-test").get().asFile.absolutePath
    systemProperty("project.juggler.base.dir", testBaseDir)
}
```

`:plugin:test` depends on `:sync-helper:installDist` through `processResources`, so the first run takes about a
minute; later runs are incremental.

### Plugin test support (`plugin/src/test/kotlin/com/projectjuggler/plugin/test/`)

- `TestApplicationExtension.kt`: `class TestApplicationExtension : TestListener` whose `beforeSpec` calls
  `initTestApplication().getOrThrow()`. The application lives for the test JVM; no teardown. Only specs listed as
  "(test application)" below register it.
- `RecordingNotificationHandler.kt`: `NotificationHandler` fake with `val infos: MutableList<String>` and
  `val errors: MutableList<String>`.
- `PluginTestSupport.kt`: `fun testRepository(baseDir: Path)` returning
  `IdeConfigRepository(baseDir, IdeInstallation("/test/ide/bin/idea", "Test IDE"))`;
  `fun registerProject(repository, dir: Path): ProjectMetadata` (`ProjectManager.registerOrUpdate` +
  `RecentProjectsIndex.recordOpen`); `fun markIsolatedInstanceOpen(repository, projectPath)` which writes
  `<repo.baseDir>/projects/<id>/config/.lock` containing `ProcessHandle.current().pid()` (a live PID, so the test also
  holds once `plans/core-gap-09-running-detection.md` lands); `fun writeGitHead(dir: Path, branch: String)` creating
  `dir/.git/HEAD` with `ref: refs/heads/<branch>` so `GitUtils.detectGitBranch` works without a git binary.

Every spec registers `KoinTestExtension()` (Koin is needed by all `getInstance(...)` calls) and uses `createTempDir`.
Mocks of `ProjectLauncher` are `mockk<ProjectLauncher>(relaxed = true)` passed through the new default parameters;
nothing is loaded into Koin for them.

## Implementation

Unless stated otherwise, new declarations are `internal`, live in `plugin/src/main/kotlin/com/projectjuggler/plugin/`
and keep the exact messages, order of operations and side effects of the code they replace.

### A. Isolated-instance detection (`services/IsolatedInstance.kt`, new)

Replaces the duplicated `idea.config.path` parsing in `actions/Sync.kt` (`isCurrentProject`) and
`shutdown/ShutdownSignalService.kt` (`getCurrentProjectId`).

```kotlin
internal object IsolatedInstance {
    const val CONFIG_PATH_PROPERTY = "idea.config.path"
    /** `.../projects/<id>/config` -> id; null otherwise. Splits on '/' exactly like today (plugin-spec gap 2). */
    fun projectIdFromConfigPath(configPath: String): ProjectId?
    fun currentProjectId(): ProjectId? = System.getProperty(CONFIG_PATH_PROPERTY)?.let(::projectIdFromConfigPath)
    fun isCurrentProject(
        repository: IdeConfigRepository,
        projectPath: ProjectPath,
        currentProjectId: ProjectId? = currentProjectId(),
    ): Boolean
}
```

`ShutdownSignalService.getCurrentProjectId` becomes
`IsolatedInstance.currentProjectId()?.let { loadProjectMetadata(it) }` with the existing error logging. Delete
`isCurrentProject` from `actions/Sync.kt`.

### B. Stop-request bookkeeping (`shutdown/StopRequestTracker.kt`, new)

```kotlin
internal class StopRequestTracker(
    private val maxAge: Duration = 5.minutes,
    private val now: () -> Instant = Instant::now,
) {
    /** True once per fresh request id; stale or unparseable timestamps are never acted on and never remembered. */
    fun shouldAct(signal: StopRequestSignal): Boolean
}
```

`ShutdownSignalService` keeps one instance instead of `processedRequestIds` + `isSignalValid` and calls
`initiateGracefulShutdown` when `shouldAct` is true. The polling coroutine and `Application.exit()` stay in the service.

### C. Sync decisions (`actions/recent/SyncType.kt`, `services/SyncPlanner.kt` new, `services/SyncProjects.kt` new)

- `SyncType` gains `val cliFlag: String` (`--all`, `--vmoptions`, `--config`, `--plugins`), replacing the `when` in
  `performSelfShutdownSync`, whose `syncType` parameter is dropped (the flag is already in `helperArgs`).
- `SyncPlanner`:

```kotlin
internal sealed interface SyncTarget {
    data class InProcess(val projects: List<ProjectMetadata>) : SyncTarget
    // helperArgs: --ide <exe> (--path <p> | --all-projects) <flag>
    data class SelfShutdown(val helperArgs: List<String>) : SyncTarget
    data object NoTarget : SyncTarget // single project without metadata
}
internal object SyncPlanner {
    fun forProject(repository, projectPath, syncType,
                   currentProjectId: ProjectId? = IsolatedInstance.currentProjectId()): SyncTarget
    fun forAllProjects(repository, syncType,
                       currentProjectId: ProjectId? = IsolatedInstance.currentProjectId()): SyncTarget
}
```

  `forAllProjects` uses `ProjectManager.listSyncable()`. `SyncSettingsSubAction` and `SyncAllProjectsAction` call
  the planner and dispatch: `InProcess` -> `performSyncWithProgress`, `SelfShutdown` -> `performSelfShutdownSync`,
  `NoTarget` -> return. Their hardcoded notification strings stay as they are (gap 3).
- `SyncProjects.kt`:

```kotlin
internal interface SyncProgressSink {
    fun setIndeterminate(value: Boolean)
    fun setText(text: String)
    fun setFraction(fraction: Double)
    fun checkCanceled()
}
internal fun syncProjects(launcher: ProjectLauncher, projects: List<ProjectMetadata>, syncType: SyncType,
                          sink: SyncProgressSink)
```

  Contains the body of the `Task.Backgroundable.run` in `performSyncWithProgress`: `SyncOptions(stopIfRunning = true,
  autoRestart = true, shutdownTimeout = 60, onProgress = ...)` mapping `SyncProgress` to the three bundle texts, the
  per-project text/fraction, `checkCanceled()` before each project, and propagation of the first exception.
  `performSyncWithProgress` keeps the task, an adapter from `ProgressIndicator`/`ProgressManager.checkCanceled()` to
  `SyncProgressSink`, and the try/catch that shows the notifications.

### D. Launch flow (`services/LaunchProject.kt`, `actions/Launch.kt`)

```kotlin
internal enum class LaunchStep { OPEN_IN_CURRENT_IDE, FOCUS, LAUNCH }
/** Steps launchOrFocus performs, in order. OPEN_IN_CURRENT_IDE is followed by FOCUS or LAUNCH (gap 1). */
internal fun planLaunch(repository: IdeConfigRepository, projectPath: ProjectPath,
                        currentConfigDir: Path): List<LaunchStep>
internal fun launchProject(projectPath, ideConfigRepository, notificationHandler,
                           launcher: ProjectLauncher = ProjectLauncher.getInstance(ideConfigRepository))
```

`planLaunch` holds the former private `isSameIdeInstance` (including its `ensureProjectDirectories` side effect) and
the `isProjectOpen` branch. `launchOrFocusUnderProgress` in `actions/Launch.kt` executes the returned steps
(`openProjectInCurrentIde`, `focusExistingProject`, `launchProject`). `askToOpenAsMainProject`, the prompt and the
`Task.Backgroundable` stay.

### E. Main project, remove, import (`services/`)

- `setMainProject`, `clearMainProject`, `removeRecentProject`, `focusExistingProject` keep their signatures; they are
  already testable.
- `services/RecentProjectsImport.kt` (new): `fun importableProjects(paths: List<String>): List<ProjectPath>`
  (existing directories only) and `fun importProjects(repository, projects: List<ProjectPath>)` (`registerOrUpdate`
  + `recordOpen` per project). `ImportRecentProjectsAction` keeps the `RecentProjectsManagerBase` cast, the two
  dialogs and the popup reshow.

### F. Popup items (`actions/recent/`)

- `RecentProjectPopupBuilder`: add
  `internal fun buildItems(repository: IdeConfigRepository, currentPaths: CurrentIdePaths)` returning
  `List<RecentProjectPopupAction>` and holding everything between loading recents and `application.invokeLater`.
  `show()` resolves
  `IdeInstallationService.currentIdeConfigRepository` and `CurrentIdePaths.detect()` inside its `try`, calls
  `buildItems`, shows the popup. `createMainProjectItem`/`createRecentProjectItem` take the repository they already
  receive.
- `RecentProjectPopup.kt`: extract the sub-menu list into a top-level
  `internal fun projectSubActions(item: OpenRecentProjectAction): List<RecentProjectSubAction>`; delete the unused
  `ideService`/`allInstallations`/`currentInstallation` vals (keep the todo comment for "Open with IDE").
- `RecentProjectPopupActionRenderer.kt`: `compactPath` becomes a top-level
  `internal fun compactPath(path: String, home: String = System.getProperty("user.home")): String`.

### G. IDE paths (`services/CurrentIdePaths.kt` new, `IdePathSyncDetector.kt`, `IdeInstallationService.kt`)

```kotlin
internal data class CurrentIdePaths(val configDir: Path, val pluginsDir: Path, val vmOptionsFile: Path) {
    companion object {
        fun detect() = CurrentIdePaths(PathManager.getConfigDir(), PathManager.getPluginsDir(),
            VMOptionsCopy.getUserOptionsFile() ?: VMOptionsCopy.getPlatformOptionsFile())
    }
}
```

`IdePathSyncDetector`: `detectMismatches(repository, current: CurrentIdePaths = CurrentIdePaths.detect())`,
`updatePaths(repository, notificationHandler: NotificationHandler, current = CurrentIdePaths.detect())` (notifications
go through the handler; `UpdatePathsAction` passes `IntelliJNotificationHandler(project)`), and
`autoPopulateIfNeeded(repository, current = CurrentIdePaths.detect())` moved here from `IdeInstallationService`, which
now calls it. This removes the three copies of `getUserOptionsFile() ?: getPlatformOptionsFile()`. Path comparison
stays string-based (gap 9).

### H. Startup activities and project copy (`services/`)

- `services/SyncPluginsOnIdeUpdate.kt` (new): `internal fun syncPluginsOnIdeUpdate(repository, currentConfigDir: Path,
  currentBuild: String, notificationHandler, launcher: ProjectLauncher = ProjectLauncher.getInstance(repository))`
  with the whole body of `PluginSyncOnUpdateActivity.execute` including both try/catch levels (per-project warning and
  continue; outer `notification.sync.on.update.failed`). The activity becomes `KoinInit.init(pluginModule)` +
  `withContext(Dispatchers.IO) { syncPluginsOnIdeUpdate(repo, PathManager.getConfigDir(), currentBuild, handler) }`
  with `currentBuild = ApplicationInfo.getInstance().build.asString()` and
  `handler = IntelliJNotificationHandler(project)`.
- `services/StaleMainProjectCleanup.kt` (new): `internal fun cleanStaleMainProjectEntryIfMainInstance(repository,
  currentConfigDir: Path): ProjectMetadata?`; the activity logs the result.
- `services/OpenProjectCopy.kt` (new):
  `internal fun validateProjectCopy(source: ProjectPath, destination: ProjectPath): String?` returning the bundle
  error message for the first failing check (not a directory, not a git repository, destination exists) or null;
  `internal fun createAndLaunchProjectCopy(repository, source, destination, branchName,
  notificationHandler, launcher = ProjectLauncher.getInstance(repository))` with the worktree creation, launch and the
  success/failure notifications (one catch of `Throwable`, rethrowing `ControlFlowException`, replacing the two
  identical blocks). `OpenProjectCopyAction` keeps the choosers, the input dialog, `resolvePath`, the "started"
  notification and the pooled thread.

Out of scope, stays untested and is listed as such in the spec: `showMainProjectPrompt`, `Messages` dialogs,
`RecentProjectPopup`/renderer Swing behavior, `performSelfShutdownSync` (process spawn + exit), the
`ShutdownSignalService` polling loop, `ProjectUtil.openOrImport`, `WindowFocuser` (core).

## Tests

All under `plugin/src/test/kotlin/com/projectjuggler/plugin/`, Kotest `StringSpec`, `extensions(KoinTestExtension())`,
temp dirs from `createTempDir`. Messages are compared against `ProjectJugglerBundle.message(key, ...)`, not literals.

- **`actions/recent/SyncTypeTest`**: `syncVmOptions/syncConfig/syncPlugins` per type; `cliFlag` per type; `displayName`
  values.

- **`util/BundledSyncHelperTest`**: `getExecutable()` returns an existing file named `bin/sync-helper` (`.bat` on
  Windows) under a `sync-helper-*` temp dir, executable on Unix, with `lib/*.jar` next to it; a second call returns the
  same path; after deleting the dir a third call re-extracts. Works with both `jar:` and file resources, so do not
  assert which. Cleans up the extracted dir in `afterSpec` (gap 11).

- **`util/IntelliJNotificationHandlerTest` (test application)**: Subscribe to `Notifications.TOPIC` on the application
  message bus; `showInfoNotification`/`showErrorNotification` post one notification each in group
  `project-juggler.notifications` with `NotificationType.INFORMATION`/`ERROR` and the given content.

- **`services/IsolatedInstanceTest`**: `projectIdFromConfigPath`: `/b/v2/ide/projects/p-1a2b/config` -> `p-1a2b`;
  trailing slash ok; `projects/x/system` -> null; no `projects` segment -> null; `C:\\b\\projects\\x\\config` -> null
  "(gap 2)". `isCurrentProject`: registered project with matching id -> true; other id -> false; unregistered -> false;
  null current -> false.

- **`shutdown/StopRequestTrackerTest`**: fresh signal -> true, same id again -> false; 6-minute-old -> false and still
  false; timestamp `"garbage"` -> false; exactly 5 minutes -> true. Use a fixed `now`.

- **`services/SyncPlannerTest`**: `forProject`: current project -> `SelfShutdown(["--ide", exe, "--path", path,
  "--vmoptions"])`; other registered -> `InProcess([metadata])`; unregistered -> `NoTarget`. `forAllProjects`: two
  isolated + main -> `InProcess` of the two (main excluded); one of them current -> `SelfShutdown([...,
  "--all-projects", "--all"])`; only the main project tracked -> `InProcess(empty)` "(sync-all with no syncable target
  still reports success for 0 projects; new gap)".

- **`services/SyncProjectsTest`**: mocked `ProjectLauncher`: two projects -> `syncProject` called for both with the
  type's three booleans and `stopIfRunning`/`autoRestart`/`60`; `setIndeterminate(false)`, fractions `0.0`, `0.5`, texts
  `progress.sync.project.type`; one project -> `setIndeterminate(true)`, no fraction; captured `onProgress` with
  `Stopping(3)`/`Syncing`/`Restarting` -> the three progress texts, `Error` -> nothing; first project throws -> second
  not called, exception propagates; `checkCanceled()` throwing stops before the first call.

- **`services/LaunchPlanTest`**: not registered -> `[LAUNCH]`; registered, no lock -> `[LAUNCH]`; lock via
  `markIsolatedInstanceOpen` -> `[FOCUS]`; `currentConfigDir == <projRoot>/config` + lock -> `[OPEN_IN_CURRENT_IDE,
  FOCUS]` "(gap 1)"; same without lock -> `[OPEN_IN_CURRENT_IDE, LAUNCH]`; `planLaunch` creates the isolated dirs for a
  registered project (side effect pinned).

- **`services/LaunchProjectTest`**: mocked launcher: `launch` called with the path; captured `onFailure` with stderr ->
  `notification.error.launch.crashed` containing exit code and stderr; blank stderr -> stdout; both blank -> `&lt;No
  output&gt;`; 600-char stderr -> 500 kept; `launch` throws -> `notification.error.launch.failed`.

- **`services/FocusExistingProjectTest`**: real `ProjectLockUtils` on a temp repo; `mockkObject(WindowFocuser)`: no lock
  -> `focus.no.pid`; lock with live PID and `mockkObject(ProjectLockUtils) { every { isProcessRunning(any()) } returns
  false }` -> `focus.process.not.found`; each `FocusResult` -> its message (`Success` -> none); `WindowFocuser.focus`
  throws `IllegalStateException("boom")` -> `focus.exception`. `unmockkAll()` in `afterTest`.

- **`services/MainProjectActionsTest`**: `setMainProject`: `mainProjectPath` set, the project's
  `projects/<id>/metadata.json` removed when it was tracked and no lock exists, kept when `markIsolatedInstanceOpen`,
  info `notification.success.set.main`; `clearMainProject`: null + `notification.success.unset.main`.

- **`services/RemoveProjectTest`**: registered project: gone from `getRecent(10)` and `<projRoot>` deleted; unknown
  path: no-op, no exception.

- **`services/RecentProjectsImportTest`**: `importableProjects`: keeps existing directories, drops files and missing
  paths, preserves order; `importProjects`: metadata and recent entry exist for each.

- **`actions/recent/RecentProjectPopupBuilderTest`**: `buildItems(repo, paths)` where `paths.configDir` is outside
  `repo.baseDir` and equal to the stored base config: no projects -> `[OpenFileChooserAction,
  ImportRecentProjectsAction]`; two recents -> `[Browse, p2, p1, SyncAll(All), (VmOptions), (Config), (Plugins),
  ToggleSyncOnUpdateAction]` with the toggle text for `syncPluginsOnIdeUpdate=false`, and the enabled text when true;
  main project not in recents -> directly after Browse with `isMainProject = true`; main project also in recents ->
  once; deleted directory filtered; stored `baseConfigPath` differs -> `UpdatePathsAction(["Config"])` first;
  `writeGitHead(dir, "feature")` -> `gitBranch == "feature"`, else null; `markIsolatedInstanceOpen` -> `isOpen`; 12
  recorded projects -> 10 items "(gap 8)".

- **`actions/recent/RecentProjectPopupTest`**: `projectSubActions`: isolated item -> `[OpenRecentProjectSubAction,
  ToggleMainProjectSubAction, Sync(All), Sync(VmOptions), Sync(Config), Sync(Plugins), RemoveProjectSubAction]`; main
  item -> the three without Sync. `OpenRecentProjectAction.getIndexedString()` = `"name branch path"` / `"name path"`;
  `getTextFor()` = `"name - [branch] - path"`. `UpdatePathsAction(["Config","Plugins"]).getTextFor()` contains `"Config,
  Plugins"`.

- **`actions/recent/CompactPathTest`**: `/home/u/work/x` with home `/home/u` -> `~/work/x`; 70-char path outside home ->
  `.../<parent>/<name>`; short path unchanged; long path under home -> `~` form.

- **`services/IdePathSyncDetectorTest`**: `detectMismatches`: `configDir` under `repo.baseDir` -> empty even if stored
  paths differ; nothing stored -> empty; each field differing -> its label; all -> `["Config","Plugins","VM Options"]`;
  stored `/a/b/` vs current `/a/b` -> mismatch "(gap 9)". `updatePaths`: config/plugins overwritten; vmoptions
  overwritten and `baseVmOptionsHash` set only when the file exists; `notification.success.paths.updated`;
  `repo.baseDir` being a regular file -> `notification.error.paths.update.failed`. `autoPopulateIfNeeded`: fresh repo ->
  three paths (vmoptions only if it exists) + hash; `baseConfigPath` already set -> untouched.

- **`services/SyncPluginsOnIdeUpdateTest`**: mocked launcher: isolated `currentConfigDir` -> nothing,
  `lastKnownBuildNumber` not stored; first run -> stored, no sync; same build -> no sync; new build, setting off ->
  stored, no sync; new build, on, two isolated + main -> `syncProject(meta, false, false, true, opts)` for the two only,
  started and complete notifications with `(build, 2)` / `(2)`; first project throws -> second still synced, complete
  notification shown; `repo.baseDir` a regular file -> `notification.sync.on.update.failed`.

- **`services/StaleMainProjectCleanupTest`**: isolated `currentConfigDir` -> null and entry kept; main instance with a
  tracked main project -> metadata returned and dir deleted.

- **`services/OpenProjectCopyTest`**: `validateProjectCopy`: source is a file -> `not.directory`; directory without
  `.git` -> `not.git.repository`; existing destination -> `destination.exists`; valid -> null.
  `createAndLaunchProjectCopy` with `mockkObject(GitWorktreeManager)` and a mocked launcher: `createWorktree(source,
  dest, branch, null)` then `launch(dest)`, info `notification.success.worktree.launched`; `createWorktree` throws ->
  `notification.error.worktree.failed`, no launch.

- **`services/IdeInstallationServiceTest` (test application)**: `loadKoinModules(pluginModule)` after
  `KoinTestExtension`; delete `<project.juggler.base.dir>/v2` in `beforeSpec`. `currentInstallation.executablePath ==
  PathManager.getHomePath()` (no `MacOS/idea` in the sandbox IDE: fallback pinned, "gap 10");
  `currentRepository.baseDir` is under the base dir property; its config has `baseConfigPath ==
  PathManager.getConfigDir().toString()` and `basePluginsPath == PathManager.getPluginsDir().toString()`.
  `VMOptionsCopy.getPlatformOptionsFile()` is under `PathManager.getBinPath()` and ends with `.vmoptions`;
  `getUserOptionsFile()` is null when `jb.vmOptionsFile` is unset.

Manual check (the refactoring touches the real flows): `./gradlew :plugin:runIde`, open the popup, open an isolated
project, use "Sync Plugins" on it from the main instance, remove a project, toggle the main project. Each must behave
as before.

## Docs

`docs/plugin-spec.md`:

- §1: replace "No tests." with "Tests: Kotest in `plugin/src/test`, see §13."
- New "## 13. Tests": the setup (core test fixtures, `TestApplicationExtension`, the `project.juggler.base.dir`
  property on the test task, the coroutines exclusion and why), what is covered (the table above, by spec), what is
  not (the out-of-scope list in Implementation), and the gaps pinned by tests (1, 2, 8, 9, 10, 11).
- Update function homes where the text names them: §2 (`autoPopulateIfNeeded` in `IdePathSyncDetector`), §4.2
  (`planLaunch`), §5.1 (`buildItems`), §5.2 (`projectSubActions`), §6 (`SyncPlanner`, `SyncType.cliFlag`,
  `syncProjects`), §6.3 (`IsolatedInstance`), §7 (`CurrentIdePaths`), §8, §10 (`StopRequestTracker`), §11.
- Known gaps: 4 keeps only `performSelfShutdownSync` catching `Exception`; 15 drops "tests"; add the new observation
  from `SyncPlannerTest` (sync-all with zero syncable targets reports success for 0 projects).

`CLAUDE.md`: Build Commands: `./gradlew test` comment becomes "core and plugin tests", add `./gradlew :plugin:test`;
Testing section: Kotest + MockK in `core/` and `plugin/`; helpers live in `core/src/testFixtures` (`KoinTestExtension`,
`createTempDir`); plugin specs needing the IDE register `TestApplicationExtension`; the plugin test task sets
`project.juggler.base.dir`; the coroutines-exclusion rule for any new plugin test dependency.

## Sessions

Fable 5.1, default effort. Session 1 first; 2, 3 and 4 are independent and can run in any order; 5 last (it
uses `CurrentIdePaths` from 4 and does the Finishing).

1. **Wiring and smoke tests** (~15%). Reads: this plan, `CLAUDE.md`, the three `build.gradle.kts`, the two core
   helpers. Does: Wiring section (root versions, core test fixtures move, plugin test deps and task), the
   `plugin/test` support files, `SyncTypeTest` (without `cliFlag`), `BundledSyncHelperTest`,
   `IntelliJNotificationHandlerTest`. Done when `./gradlew :core:test :plugin:test` passes and
   `./gradlew :plugin:buildPlugin` produces a zip whose `lib/` has no kotest/mockk/junit jars.
   **Done** in `wire plugin tests and share core test fixtures`. Deviations: `testFixtures(project(":core"))` is
   typed as `Dependency`, so the exclusion lambda needs `testFixtures(project(":core")) as ModuleDependency`;
   the plugin `test` task is configured inside the existing `tasks { }` block. The support functions in
   `PluginTestSupport.kt` are public top-level functions (like core's `createTempDir`), not `internal`.
2. **Sync and isolated instance** (~20%). Reads: `actions/Sync.kt`, `actions/recent/SyncSettingsSubAction.kt`,
   `SyncAllProjectsAction.kt`, `SyncType.kt`, `shutdown/ShutdownSignalService.kt`, spec §6 and §10. Does: A, B, C;
   `IsolatedInstanceTest`, `StopRequestTrackerTest`, `SyncPlannerTest`, `SyncProjectsTest`, add `cliFlag` cases to
   `SyncTypeTest`. Done when those pass and `:plugin:compileKotlin` is clean.
3. **Launch, main project, remove, import** (~20%). Reads: `actions/Launch.kt`, `services/LaunchProject.kt`,
   `FocusExistingProject.kt`, `SetMainProject.kt`, `UnsetMainProject.kt`, `RemoveProject.kt`,
   `actions/recent/ImportRecentProjectsAction.kt`, core `ProjectLockUtils.kt`, spec §4 and §5.4-5.5. Does: D, E;
   `LaunchPlanTest`, `LaunchProjectTest`, `FocusExistingProjectTest`, `MainProjectActionsTest`, `RemoveProjectTest`,
   `RecentProjectsImportTest`.
4. **Popup items and IDE paths** (~20%). Reads: `RecentProjectPopupBuilder.kt`, `RecentProjectPopup.kt`,
   `RecentProjectPopupActionRenderer.kt`, `OpenRecentProjectAction.kt`, `UpdatePathsAction.kt`,
   `services/IdePathSyncDetector.kt`, `IdeInstallationService.kt`, `VMOptionsCopy.kt`, spec §5 and §7. Does: F, G;
   `RecentProjectPopupBuilderTest`, `RecentProjectPopupTest`, `CompactPathTest`, `IdePathSyncDetectorTest`.
5. **Startup activities, project copy, service spec, finishing** (~22%). Reads: `startup/*.kt`,
   `actions/OpenProjectCopyAction.kt`, core `GitWorktreeManager.kt`, spec §8, §10a, §11, the Docs section. Does: H;
   `SyncPluginsOnIdeUpdateTest`, `StaleMainProjectCleanupTest`, `OpenProjectCopyTest`, `IdeInstallationServiceTest`;
   the manual check; Docs; Finishing.

Each session ends with its own commit (see Finishing for the rules), marks its entry here as
`**Done** in <commit subject>` and records any deviation later sessions must know (a renamed function, a test that
had to be dropped and why).

## Verification

1. `./gradlew :core:test`
2. `./gradlew :plugin:test`; a single spec:
   `./gradlew :plugin:test --tests "com.projectjuggler.plugin.services.SyncPlannerTest"`
3. `./gradlew build`
4. `./gradlew :plugin:buildPlugin`, then `unzip -l plugin/build/distributions/*.zip | grep -i -E "kotest|mockk|junit"`
   prints nothing
5. Manual check from the Tests section (session 5)

## Finishing

- Apply the Docs section; `CLAUDE.md` changes as listed (the Testing section is the one place that describes the plugin
  test setup, so later sessions do not rediscover the coroutines exclusion).
- Delete `plans/plugin-tests.md` in the same commit as session 5.
- Per session: `git status` and `git diff` first; stage only this session's files by path (never `git add -A`/`-a`);
  subject in the style of recent commits (lowercase imperative, e.g. `extract sync planning into services and test it`),
  a body with what moved where and why, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. List
  anything left uncommitted in the reply.
