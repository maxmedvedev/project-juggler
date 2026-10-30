# Plugin gap 8: popup recents follow maxRecentProjects; "open" badge from the current IDE's own projects

## Context

`docs/plugin-spec.md`, Known gaps 8: "Popup shows up to 20 recents while core caps the index at `maxRecentProjects`
(10 by default), so the limit is effectively 10. The main project item's `isOpen` relies on `isProjectOpen`, which is
false when `baseConfigPath` is unset."

**Depends on `plans/plugin-tests.md`** (it introduces `RecentProjectPopupBuilder.buildItems(repository, currentPaths:
CurrentIdePaths)`, `CurrentIdePaths`, the plugin test support, and `RecentProjectPopupBuilderTest`, which pins this gap
as "12 recorded projects -> 10 items (gap 8)") **and on `plans/plugin-gap-01-launch-in-current-ide.md`** (it adds
`belongsToCurrentIde(repository, projectPath, currentConfigDir)` in `plugin/.../services/CurrentIdeProject.kt`).

Two parts:

1. Limit. `RecentProjectPopupBuilder` calls `RecentProjectsIndex.getRecent(20)`, but `recordOpen` already trims
   `recent.json` to `IdeConfig.maxRecentProjects` (default 10; no UI, only `config.json`). The literal 20 only matters
   when a user raises `maxRecentProjects` above 20, and then it silently cuts the list.
2. Open state. The "half" named in the gap, `isProjectOpen` returning false for the main project when `baseConfigPath`
   is unset, belongs to core and is fixed by `plans/core-gap-09-running-detection.md` (falls back to
   `DirectoryManager.getBaseConfigPath()`); in the plugin `autoPopulateIfNeeded` sets `baseConfigPath` on first use,
   so it rarely shows. The plugin-side defect is different: a `.lock` means "an IDE process uses this config dir", not
   "this project is open". When the popup runs *in the project's own IDE*, that lock is the current IDE's own:
   - main instance: the main project item always gets the green badge, even when the main project is not open in it;
   - isolated instance whose project window was closed (welcome screen): its own item still shows as open.
   For the current IDE the platform knows the truth: `ProjectUtil.findProject(file: Path): Project?` (sources:
   `~/.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIU/2025.1.1/9e35671cd91372a0d02e4e6b3f18be783b17c59b/ideaIU-2025.1.1-sources.jar`,
   `com/intellij/ide/impl/ProjectUtil.kt` line 393, over `getOpenProjects()`). For projects of *other* IDEs the lock
   stays the only signal; for the main project seen from an isolated IDE it means "main IDE running", documented as
   such.

Decision (made with the user, 2026-10-30): bound the popup by `maxRecentProjects` alone (read from the config the
builder already loads), keeping the default 10, because one setting should decide how many recents exist and are shown.
Rejected: raising the default to 20; keeping both limits and documenting `min(20, maxRecentProjects)`.

Related: `plans/plugin-gap-15-settings-and-health-check.md` adds a settings page with a `maxRecentProjects` spinner
(1..50). No conflict: this plan only reads the value in `buildItems` on every popup open, so an edit there takes effect
on the next open; whichever plan lands second keeps the default 10 and touches no `IdeConfig` field. A lowered value
applies to the popup at once, while `recent.json` is trimmed on the next `recordOpen`.
`plans/plugin-gap-09-ide-path-drift.md` also edits `RecentProjectPopupBuilder.buildItems` (the `UpdatePathsAction`
argument); independent, the second to land rebases.

## Target behavior

| Case | Before | After |
|---|---|---|
| `maxRecentProjects` 10 (default), 12 recorded | 10 items | 10 items (unchanged) |
| `maxRecentProjects` 25, 25 recorded | 20 items | 25 items |
| main project, popup in the main instance, main project not open there | badge (base `.lock`) | no badge |
| main project, popup in the main instance, main project open there | badge | badge |
| isolated project, popup in its own isolated IDE, project window closed | badge (own `.lock`) | no badge |
| isolated project, popup in another IDE, its `.lock` present | badge | badge (unchanged) |
| main project, popup in an isolated IDE, base `.lock` present | badge | badge (means "main IDE running") |

Deleted project directories are still filtered after the limit is applied (recents are trimmed at record time, so a
deleted directory takes a slot until it is pushed out; unchanged).

## Implementation

- `plugin/src/main/kotlin/com/projectjuggler/plugin/services/CurrentIdeProject.kt` (created by plugin-gap-01), add:
  ```kotlin
  /** Whether a project is open in the current IDE, as the platform sees it. */
  internal fun isOpenInCurrentIde(projectPath: ProjectPath): Boolean = ProjectUtil.findProject(projectPath.path) != null

  /** Open state for display: the platform's view for the current IDE's own projects, the `.lock` for others. */
  internal fun isProjectRunning(repository: IdeConfigRepository, projectPath: ProjectPath, currentConfigDir: Path,
                                openInCurrentIde: (ProjectPath) -> Boolean = ::isOpenInCurrentIde): Boolean =
      if (belongsToCurrentIde(repository, projectPath, currentConfigDir)) openInCurrentIde(projectPath)
      else ProjectLockUtils.isProjectOpen(repository, projectPath)
  ```
- `actions/recent/RecentProjectPopupBuilder.kt`:
  - `buildItems(repository, currentPaths, openInCurrentIde: (ProjectPath) -> Boolean = ::isOpenInCurrentIde)`.
  - Load `val config = repository.load()` once at the top and use it for `getRecent(config.maxRecentProjects)`,
    `config.mainProjectPath` (`createMainProjectItem`) and `config.syncPluginsOnIdeUpdate` (toggle item), replacing
    the three separate `load()` calls and the literal 20.
  - Replace `detectIfProjectOpen` with `isProjectRunning(repository, path, currentPaths.configDir, openInCurrentIde)`
    in `createMainProjectItem` and `createRecentProjectItem`; delete `detectIfProjectOpen`.
  - `show()` keeps calling `buildItems(repository, CurrentIdePaths.detect())` (default lambda).
- No core change. `plans/core-gap-09-running-detection.md` covers the unset-`baseConfigPath` case in
  `ProjectLockUtils.isProjectOpen`, which this plan still uses for other IDEs' projects.

## Tests

`plugin/src/test/kotlin/com/projectjuggler/plugin/actions/recent/RecentProjectPopupBuilderTest.kt` (from plugin-tests),
calls pass an explicit `openInCurrentIde` lambda where it matters:

- Flip "(gap 8)": rename "12 recorded projects -> 10 items (gap 8)" to "12 recorded with default maxRecentProjects ->
  10 items" (drop the gap tag and comment), and add "maxRecentProjects = 25, 25 recorded -> 25 project items"
  (`repo.update { it.copy(maxRecentProjects = 25) }` before `registerProject`).
- New: "project whose isolated config dir is the current config dir": `paths.configDir = <projRoot>/config`,
  `markIsolatedInstanceOpen`, lambda `{ false }` -> `isOpen == false`; lambda `{ it == path }` -> `true`.
- New: "main project in the main instance": `mainProjectPath` set, `paths.configDir == baseConfigPath`, `.lock` with
  the current PID in that dir, lambda `{ false }` -> main item `isOpen == false`; lambda `{ true }` -> `true`.
- New: "main project seen from an isolated IDE": `paths.configDir` under `repo.baseDir/projects`, base `.lock` present,
  lambda `{ false }` -> `isOpen == true`.
- Existing `markIsolatedInstanceOpen -> isOpen` (popup in another IDE) unchanged.
- New `services/CurrentIdeProjectTest.kt` cases (file created by plugin-gap-01): `isProjectRunning` delegates to the
  lambda for the current IDE's project and to the lock otherwise.

`isOpenInCurrentIde` itself needs open platform projects and is not unit-tested; manual check below.

## Docs

`docs/plugin-spec.md`:
- §5.1 step 1: "`RecentProjectsIndex.getRecent(maxRecentProjects)`, filtered to paths that exist on disk."
- §5.1 step 2: "`isOpen` = `isProjectRunning`: for a project that belongs to the current IDE (the main project in the
  main instance, or the project of this isolated instance) whether the platform has it open
  (`ProjectUtil.findProject`); otherwise `ProjectLockUtils.isProjectOpen`. For the main project seen from an isolated
  IDE this means the main IDE is running."
- §13 (added by plugin-tests): drop gap 8 from the list of gaps pinned by tests.
- Known gaps: delete item 8.

## Sessions

1. Single session, Fable 5.1 at default effort, ~12% context. Precondition: plugin-tests and plugin-gap-01 are
   committed (`services/CurrentIdeProject.kt` exists). Reads: this plan, `CLAUDE.md`,
   `actions/recent/RecentProjectPopupBuilder.kt`, `services/CurrentIdeProject.kt`, `RecentProjectPopupBuilderTest.kt`,
   `CurrentIdeProjectTest.kt`, the plugin test support. Implements everything above, runs Verification, does
   Finishing. Done when `:plugin:test` passes and gap 8 is gone from the spec.

## Verification

1. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.actions.recent.RecentProjectPopupBuilderTest"`
2. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.services.CurrentIdeProjectTest"`
3. `./gradlew test`
4. `./gradlew :plugin:buildPlugin`
5. Manual, `./gradlew :plugin:runIde` with a main project set: with another project open in the main IDE, the popup
   shows the main project without the green badge; open it, reopen the popup, the badge appears. In an isolated IDE,
   close its project (welcome screen) and open the popup: its own item has no badge.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/plugin-gap-08-popup-recents-and-open-state.md` in the same commit.
- `git status` / `git diff`; stage only the touched files by path. Commit in the style of recent commits (lowercase
  imperative subject, explanatory body, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested
  subject: `size the recent popup by maxRecentProjects and badge the current IDE's projects by their open state`.
