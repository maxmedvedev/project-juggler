# Keep config I/O off the EDT, start Koin in one place, drop the hard RecentProjectsManagerBase cast (gap 14)

## Context

`docs/plugin-spec.md`, Known gaps 14: "`KoinInit.init` is called on EDT in several actions (marked `todo move to
BGT`). `ToggleMainProjectSubAction` performs config file I/O under a file lock on EDT. `ImportRecentProjectsAction`
casts `RecentProjectsManager` to `RecentProjectsManagerBase`, an implementation class."

**Depends on `plans/plugin-tests.md` landing first.** Written against its layout: `services/SyncPlanner.kt`
(`SyncPlanner.forProject/forAllProjects`, `SyncTarget`), `services/RecentProjectsImport.kt`
(`importableProjects`, `importProjects`), `services/OpenProjectCopy.kt` (`validateProjectCopy`,
`createAndLaunchProjectCopy`), `projectSubActions(item)` in `RecentProjectPopup.kt` (the unused
`getAllInstallations()` call is gone), `IdeInstallationServiceTest`, `RecentProjectPopupTest`. If a name differs,
follow the code and note it in the commit body.

What actually costs time on the EDT is not `KoinInit.init` (in-memory module registration) but the first touch of
`IdeInstallationService.currentIdeConfigRepository`: its lazy `currentRepository` runs `IntelliJLocator`,
`IdeRegistry.getRepository` and `autoPopulateIfNeeded`, which writes `config.json` under
`FileLockUtils.withFileLock`. Every later `repository.load()` also takes the file lock. EDT call sites today
(after plugin-tests):

| Site | Runs on | Work |
|---|---|---|
| `actions/ShowFileChooserAndLaunch.kt` after the chooser | EDT | `KoinInit`, repository init, `launchOrFocusProject` |
| `actions/Launch.kt` `launchOrFocusProject` | caller | `MainProjectService.shouldPromptForMainProject` -> `load()` |
| `OpenRecentProjectAction` / `OpenRecentProjectSubAction` `doFinalStep` | EDT | `launchOrFocusProject` |
| `ToggleMainProjectSubAction.toggleMainProject` and `getTextFor` | EDT (popup render) | `isMainProject` -> `load()` |
| `ToggleSyncOnUpdateAction` `doFinalStep` | EDT | `repository.update` |
| `SyncSettingsSubAction` / `SyncAllProjectsAction` `onChosen` | EDT | `SyncPlanner` (metadata, `listSyncable`), then `performSelfShutdownSync` (helper extraction, `Thread.sleep(100)`) |
| `OpenProjectCopyAction` after the branch dialog | EDT | `KoinInit`, repository init, `resolvePath`, `validateProjectCopy` (stat, git dir check) |
| `ImportRecentProjectsAction` `doFinalStep` | EDT | `importableProjects` (`Files.isDirectory` per recent path) |

`KoinInit.init(pluginModule)` call sites: the two actions above, `ShowRecentProjectsAction`, `ShutdownSignalService`
init, `PluginSyncOnUpdateActivity`, `StaleMainProjectCleanupActivity`. Re-calling it is harmless (Koin 4.1.1
`loadKoinModules` re-registers the same `SingleInstanceFactory` object from the `pluginModule` val, so the
`IdeInstallationService` instance survives; checked in `koin-core-jvm-4.1.1-sources.jar`,
`InstanceRegistry.saveMapping`), but six manual calls are easy to miss in a new entry point.

Platform API, IntelliJ 2025.1.1 (`~/.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIU/2025.1.1/
9e35671cd91372a0d02e4e6b3f18be783b17c59b/ideaIU-2025.1.1-sources.jar`, classes in `lib/app-client.jar`):

- `com.intellij.ide.RecentProjectsManager` (interface, public): no `getRecentPaths()` or icon accessor; only
  `reopenLastProjectsOnStart` and `setActivationTimestamp` are `@ApiStatus.Internal`.
- `com.intellij.ide.RecentProjectsManagerBase` (`open class`, module `intellij.platform.ide.impl`): the class is not
  annotated; `fun getRecentPaths(): List<String>` (line 467, takes `stateLock`, in-memory) and
  `fun getProjectIcon(path: String, isProjectValid: Boolean): Icon` (line 267) are public and not `@Internal`. Some
  other members are `@Internal`.
- `com.intellij.ide.RecentProjectIconHelper` is `@ApiStatus.Internal` (class level). Do not use it.
- `com.intellij.ide.RecentProjectListActionProvider` (open, public) `getActions(addClearListItem, useGroups)`
  returns `ReopenProjectAction`s with public `getProjectPath()`; a public but indirect alternative for paths.
- `com.intellij.util.concurrency.annotations.RequiresBackgroundThread` (`lib/util-8.jar`, public, retention CLASS,
  `generateAssertion = true`). Treat it as documentation; do not rely on build-time instrumentation.
  `ThreadingAssertions.assertBackgroundThread()` is public (not `@Internal`).
- `AllIcons.Nodes.IdeaProject` exists (fallback icon).
- `Task.Backgroundable.queue()` from a pooled thread is already used today (`askToOpenAsMainProject` ->
  `launchOrFocusProjectImpl`), so queueing tasks from BGT needs no extra hop.

Decision (made with the user, 2026-10-30): Koin starts lazily inside `IdeInstallationService.getInstance()`, because
every plugin path goes through it, it works on any thread and on dynamic plugin load, and it needs no extension point.
Rejected: an `AppLifecycleListener` (not fired on dynamic install), keeping six explicit calls moved to BGT.

Decision (made with the user, 2026-10-30): one adapter with a safe cast (`as?`) and fallbacks (empty path list,
`AllIcons.Nodes.IdeaProject`), because the class and both methods are public and not `@Internal`, so the real risk is
a `ClassCastException` on another implementation (remote dev / thin client). Rejected:
`RecentProjectListActionProvider.getActions()` (icon still needs the cast), keeping the hard cast.

Decision (made with the user, 2026-10-30): move all nine EDT sites in the table, because they share one pattern and
the two sites the gap names are not the slowest ones. Rejected: only the named sites.

Overlaps (whichever lands second merges):
- `plans/plugin-gap-05-project-copy-dialog.md` moves the project-copy flow into `services/OpenProjectCopy.kt` and
  onto a pooled thread, keeping a `KoinInit.init` call there. Gap 5 owns that flow: if it lands first, this plan only
  deletes the `KoinInit` line; if this plan lands first, it moves the block as below and gap 5 drops its `KoinInit`
  line.
- `plans/plugin-gap-11-sync-helper-extraction.md` runs `performSelfShutdownSync` in a `Task.Backgroundable` and owns
  its error handling. Compatible: this plan only moves the callers to a pooled thread and adds the annotation.
- `plans/plugin-gap-12-shutdown-service-startup.md` moves `ShutdownSignalService` init into a coroutine that still
  calls `KoinInit.init`; whichever lands second removes that call (this plan owns the Koin change).
- `plans/plugin-gap-01-launch-in-current-ide.md` and `plans/plugin-gap-06-open-with-ide.md` edit `actions/Launch.kt`
  and popup sub-actions; this plan only adds `@RequiresBackgroundThread` and the pooled-thread wrap. Independent.
- `plans/plugin-gap-03-bundle-strings-and-catches.md` edits strings and catches in the same sync actions.

## Target behavior

- No config, metadata, lock or git I/O on the EDT from any plugin action or popup item. Dialogs, choosers and the popup
  stay on EDT; everything after the user's choice runs on a pooled thread or in a `Task.Backgroundable`.
- No `KoinInit.init` call outside `IdeInstallationService.getInstance()`.
- The plugin does not throw `ClassCastException` when `RecentProjectsManager` is not a `RecentProjectsManagerBase`:
  import finds no projects, project icons fall back to the generic project icon.
- Main-project label and confirm text in the per-project sub-popup come from `OpenRecentProjectAction.isMainProject`
  (computed on BGT when the popup was built), not from a fresh `load()`.

## Implementation

### Koin (`plugin/di/PluginModule.kt`, `services/IdeInstallationService.kt`)

```kotlin
// PluginModule.kt
/** Starts Koin with [pluginModule] unless the plugin's services are already registered. */
internal fun ensurePluginKoin() {
    if (GlobalContext.getOrNull()?.getOrNull<IdeInstallationService>() == null) KoinInit.init(pluginModule)
}
```

`IdeInstallationService.getInstance()` becomes `ensurePluginKoin(); return GlobalContext.get().get()`. Delete the six
`KoinInit.init(pluginModule)` calls and their imports. Check each removed site reaches Koin only through
`IdeInstallationService` (they do as of 2026-10-30: `ShutdownSignalService` via `currentIdeConfigRepository`, the
activities via `currentIdeConfigRepository`).

### Platform recent projects (`services/PlatformRecentProjects.kt`, new)

```kotlin
/** The only place that touches RecentProjectsManagerBase (public, but an implementation class). */
internal object PlatformRecentProjects {
    fun recentPaths(manager: RecentProjectsManager = RecentProjectsManager.getInstance()): List<String>
    fun projectIcon(path: String, manager: RecentProjectsManager = RecentProjectsManager.getInstance()): Icon
}
```

`(manager as? RecentProjectsManagerBase)?.getRecentPaths().orEmpty()` (log once at info when the cast fails);
`?.getProjectIcon(path, true) ?: AllIcons.Nodes.IdeaProject`. `ImportRecentProjectsAction` and
`RecentProjectPopupActionRenderer` (drop its `recentProjectsManager` field) call it.

### Moving work off the EDT

- `actions/ShowFileChooserAndLaunch.kt`: after `chooseFile`, `application.executeOnPooledThread {
  launchOrFocusProject(project, ProjectManager.getInstance(repo).resolvePath(selectedFile.path)) }`.
- `actions/Launch.kt`: annotate `launchOrFocusProject` with `@RequiresBackgroundThread`. Its body already hops to EDT
  for the prompt (`invokeLater`) and queues a task otherwise.
- `OpenRecentProjectAction.onChosen`, `OpenRecentProjectSubAction.onChosen`: `step.doFinalStep {
  application.executeOnPooledThread { launchOrFocusProject(project, projectPath) } }`.
- `ToggleMainProjectSubAction`: use `item.isMainProject` in `toggleMainProject(project, item)` and `getTextFor(item)`;
  no `MainProjectService.isMainProject` call remains. The dialog stays in `invokeLater`, the write in the pooled thread.
- `ToggleSyncOnUpdateAction`: move `repository.update` into the existing `executeOnPooledThread` block, before
  `RecentProjectPopupBuilder(project).show()`.
- `SyncSettingsSubAction` / `SyncAllProjectsAction`: `onChosen` wraps planning and dispatch in
  `application.executeOnPooledThread { ... }`. `performSyncWithProgress` (task queue) and `performSelfShutdownSync`
  (notification, extraction, spawn, sleep, `invokeLater { exit() }`) are then on BGT; annotate
  `performSelfShutdownSync` with `@RequiresBackgroundThread`.
- `OpenProjectCopyAction`: after the branch dialog, everything (`resolvePath`, `validateProjectCopy` + its error
  notification, the "started" notification, `createAndLaunchProjectCopy`) moves into the existing
  `executeOnPooledThread` block. Notifications are thread-safe.
- `ImportRecentProjectsAction`: `doFinalStep { executeOnPooledThread { val projects =
  importableProjects(PlatformRecentProjects.recentPaths()); invokeLater { dialogs; on Yes executeOnPooledThread {
  importProjects(...); RecentProjectPopupBuilder(project).show() } } } }`. Keep dialog order and texts.

`ShowRecentProjectsAction` already runs on a pooled thread; only its `KoinInit` line goes.

## Tests

Under `plugin/src/test/kotlin/com/projectjuggler/plugin/`, infrastructure from plugin-tests.

- New `di/PluginKoinTest` (pure, **no** `KoinTestExtension`): `beforeTest` `stopKoin()`; `afterSpec`
  `KoinInit.stop()`. "getInstance starts Koin when nothing started it": `IdeInstallationService.getInstance()` returns
  an instance and `GlobalContext.get().getOrNull<IdeRegistry>()` is non-null. "getInstance twice returns the same
  instance". "getInstance loads pluginModule into a Koin started without it": `startKoin { modules(coreModule,
  ideScopedModule) }` first (what `KoinTestExtension` does), then `getInstance()` succeeds. Must not touch
  `currentRepository` (needs `PathManager`).
- `services/IdeInstallationServiceTest`: drop the explicit `loadKoinModules(pluginModule)`; the spec still passes.
- New `services/PlatformRecentProjectsTest` (pure, MockK): `mockk<RecentProjectsManager>()` -> `recentPaths()` empty
  and `projectIcon("/x")` is `AllIcons.Nodes.IdeaProject`;
  `mockk<RecentProjectsManagerBase> { every { getRecentPaths() } returns listOf("/a") }` -> `["/a"]`. If loading
  `AllIcons` needs an `Application`, register `TestApplicationExtension`.
- `actions/recent/RecentProjectPopupTest`: `ToggleMainProjectSubAction.getTextFor(item)` with
  `item.isMainProject = true` returns `popup.recent.projects.action.unset.main` while the repository has no main
  project (proves no `load()`), and `set.main` for `false`.

Not unit-tested: thread placement in actions (Swing/popup shells are out of scope in plugin-tests). Manual check in
`./gradlew :plugin:runIde` with Help > Diagnostic Tools > Activity Monitor or a thread dump, plus behavior:
Browse... and open a project; open from the popup; Set/Unset main project (labels correct); toggle auto-sync (popup
reshows with the new state); Sync Plugins on another project; Import recent projects; Open a Project Copy. Each
behaves as before. Delete `~/.project-juggler-test/v2` first once to exercise the first-touch path from the file
chooser.

## Docs

`docs/plugin-spec.md`:
- §2: "`KoinInit.init(pluginModule)` is called lazily by `IdeInstallationService.getInstance()` (`ensurePluginKoin`);
  no other call site."
- §3: "`actionPerformed` shows choosers/dialogs on EDT and does all config I/O on a pooled thread."
- §4.1: chooser on EDT, `resolvePath` and `launchOrFocusProject` on a pooled thread. §4.2: `launchOrFocusProject` is
  `@RequiresBackgroundThread`.
- §5.3: icon from `PlatformRecentProjects.projectIcon` (falls back to `AllIcons.Nodes.IdeaProject`). §5.4 import:
  `PlatformRecentProjects.recentPaths`, filtering on a pooled thread. §5.5: toggle text from `item.isMainProject`.
- §6.1/§6.2: planning and self-shutdown run on a pooled thread.
- §8: everything after the branch dialog on a pooled thread (remove "all on the calling thread").
- §12 table: replace the `showFileChooserAndLaunch config I/O ... caller (EDT for actions)` row with "All
  repository/lock/git I/O after a user choice | pooled thread or `Task.Backgroundable`".
- Known gaps: delete item 14 (keep the other numbers); in item 5 delete "Validation and git checks run on EDT." if
  gap 5 has not already been closed; in item 11 delete "Extraction happens on the
  calling thread." unless gap 11 already closed it.

`CLAUDE.md`, Architecture > Dependency injection: replace "the plugin passes `pluginModule`" with "the plugin starts
it lazily from `IdeInstallationService.getInstance()`". Development Guidelines: add "No repository, lock or git I/O on
the EDT; do it on a pooled thread after the dialog or popup choice."

## Sessions

Fable 5.1, default effort.

1. **Koin and the platform adapter** (~12%). Reads: this plan, `CLAUDE.md`, `di/PluginModule.kt`,
   `IdeInstallationService.kt`, core `di/KoinInit.kt`, the six `KoinInit` call sites,
   `ImportRecentProjectsAction.kt`, `RecentProjectPopupActionRenderer.kt` (top 60 lines), `IdeInstallationServiceTest`.
   Does: Koin and Platform-recent-projects sections, `PluginKoinTest`, `PlatformRecentProjectsTest`. Done when
   `:plugin:test` passes and `grep -rn 'KoinInit.init' plugin/src/main` shows only `PluginModule.kt`. Commit
   (e.g. `start plugin koin lazily and wrap the recent projects manager cast`).
2. **EDT moves and finishing** (~18%). Reads: this plan, `ShowFileChooserAndLaunch.kt`, `Launch.kt`,
   `OpenRecentProjectAction.kt`, `OpenRecentProjectSubAction.kt`, `ToggleMainProjectSubAction.kt`,
   `ToggleSyncOnUpdateAction.kt`, `SyncSettingsSubAction.kt`, `SyncAllProjectsAction.kt`, `Sync.kt`,
   `OpenProjectCopyAction.kt`, `ImportRecentProjectsAction.kt`, `RecentProjectPopupTest`. Does: "Moving work off
   the EDT", the toggle test, the manual check, Docs, Finishing.

## Verification

1. `./gradlew :plugin:test`
2. `./gradlew :core:test`
3. `grep -rn 'KoinInit.init\|as RecentProjectsManagerBase' plugin/src/main/kotlin` shows only `PluginModule.kt`
4. `./gradlew :plugin:verifyPlugin` (no new internal-API or impl-class warnings)
5. Manual check from the Tests section

## Finishing

- Apply the Docs section, including the `CLAUDE.md` edits.
- Delete `plans/plugin-gap-14-edt-io-and-koin-init.md` in the session-2 commit.
- Per session: `git status` / `git diff`; stage only touched files by path. Lowercase imperative subject, a body
  with what moved and why, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Suggested
  session-2 subject: `keep plugin config i/o off the edt`.
