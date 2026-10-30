# Plugin gap 1: stop after opening a project in its own IDE, never focus the current IDE by lock

## Context

`docs/plugin-spec.md`, Known gaps 1: "`launchOrFocusUnderProgress` does not return after `isSameIdeInstance` opens the
project in-process, so it also runs the focus/launch branch. In practice the isolated `.lock` exists, so it tries to
focus its own window."

**Depends on `plans/plugin-tests.md` landing first.** This plan is written against the layout it introduces:
`planLaunch(repository, projectPath, currentConfigDir): List<LaunchStep>` and `enum LaunchStep { OPEN_IN_CURRENT_IDE,
FOCUS, LAUNCH }` in `plugin/.../services/LaunchProject.kt`, `launchOrFocusUnderProgress` in `actions/Launch.kt`
executing the steps, the plugin test support (`testRepository`, `registerProject`, `markIsolatedInstanceOpen`) and
`services/LaunchPlanTest`, which pins this gap as `[OPEN_IN_CURRENT_IDE, FOCUS] "(gap 1)"`.

The same defect has a second, unlisted form. The "own IDE" check (`isSameIdeInstance`) only looks at the isolated
`<projRoot>/config`. For the main project, `ProjectLockUtils.isProjectOpen` checks `<baseConfigPath>/.lock`, which
exists whenever the main IDE runs. So:

- From the main IDE, choosing the main project yields `FOCUS` on the main IDE's own PID; if the main project is not
  open there (welcome screen or another project), nothing opens.
- From an isolated IDE, choosing the main project focuses the main IDE window but does not open the project in it.
  `ProjectLauncher.launch` -> `IntelliJLauncher.launchMain` would do it: a second `idea <path>` with the base dirs is
  forwarded to the running instance, which opens or focuses the project (core gap 6 already landed, so the child no
  longer inherits the isolated `IDEA_VM_OPTIONS`).

`isSameIdeInstance` also calls `DirectoryManager.ensureProjectDirectories`, creating dirs and copying base config just
to compute a path; `DirectoryManager.getProjectRoot(ProjectPath)` gives the same path without side effects.

`openProjectInCurrentIde` calls `ProjectUtil.openOrImport(path, currentProject, forceOpenInNewFrame = true)`. With
`forceOpenInNewFrame` the platform skips its own "already open -> focus" check (`openOrImportAsync`, first lines), so
reopening an already-open project from its own IDE is not a clean focus. Platform API used (sources:
`~/.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIU/2025.1.1/9e35671cd91372a0d02e4e6b3f18be783b17c59b/ideaIU-2025.1.1-sources.jar`,
entry `com/intellij/ide/impl/ProjectUtil.kt`): `ProjectUtil.findProject(file: Path): Project?` (line 393),
`ProjectUtil.focusProjectWindow(project, stealFocusIfAppInactive = false)` (line 476, `@RequiresEdt`),
`ProjectUtil.openOrImport(path: Path, projectToClose: Project?, forceOpenInNewFrame: Boolean)` (line 124).

Decision (made with the user, 2026-10-30): route the main project through the same rule (open in the current IDE when it
is the main instance, otherwise always `LAUNCH` and let the running main IDE open it), because it is the same
fall-through and lock-based `FOCUS` cannot open a main project the main IDE does not have open. Rejected: fixing only
the isolated case.

Related: `plans/plugin-gap-08-popup-recents-and-open-state.md` reuses `belongsToCurrentIde` from this plan.
`plans/plugin-gap-06-open-with-ide.md` adds a `repository` parameter to `launchOrFocusProject`; independent, both edit
`actions/Launch.kt`. `plans/core-gap-14-main-instance-detection.md` improves `isRunningInMainInstance`, which this plan
calls; no dependency.
`plans/plugin-gap-14-edt-io-and-koin-init.md` also edits `actions/Launch.kt` (threading only); independent.

## Target behavior

`planLaunch` returns one step.

| Target | Current IDE | `.lock` | Step (before -> after) |
|---|---|---|---|
| isolated project | its own isolated IDE (`<projRoot>/config` == current config dir) | present (self) | `[OPEN_IN_CURRENT_IDE, FOCUS]` -> `OPEN_IN_CURRENT_IDE` |
| isolated project | its own isolated IDE | absent | `[OPEN_IN_CURRENT_IDE, LAUNCH]` -> `OPEN_IN_CURRENT_IDE` |
| isolated project | other IDE | present / absent | `FOCUS` / `LAUNCH` (unchanged) |
| main project | main instance | base lock present (self) | `FOCUS` -> `OPEN_IN_CURRENT_IDE` |
| main project | isolated IDE | base lock present | `FOCUS` -> `LAUNCH` |
| main project | isolated IDE | absent | `LAUNCH` (unchanged) |
| any | any | any | no directories created by planning (was: isolated dirs created and base config copied) |

`OPEN_IN_CURRENT_IDE`: if the project is already open in this IDE, focus its frame; otherwise open it in a new frame.

## Implementation

- New `plugin/src/main/kotlin/com/projectjuggler/plugin/services/CurrentIdeProject.kt`:
  ```kotlin
  /** True when [projectPath]'s own IDE instance is the current IDE: the main project in the main instance, or an
   *  isolated project whose `<projRoot>/config` is [currentConfigDir]. Creates nothing. */
  internal fun belongsToCurrentIde(repository: IdeConfigRepository, projectPath: ProjectPath,
                                   currentConfigDir: Path): Boolean =
      if (MainProjectService.isMainProject(repository, projectPath)) {
          MainProjectService.isRunningInMainInstance(repository, currentConfigDir)
      } else {
          val isolatedConfig = DirectoryManager.getInstance(repository).getProjectRoot(projectPath).resolve("config")
          isolatedConfig.toAbsolutePath().normalize() == currentConfigDir.toAbsolutePath().normalize()
      }
  ```
  The metadata lookup goes away: an unregistered path cannot match unless the current IDE is the isolated instance of
  a project whose metadata was removed, where opening in place is right.
- `services/LaunchProject.kt`: `planLaunch(...)` returns `LaunchStep` (not a list):
  `belongsToCurrentIde -> OPEN_IN_CURRENT_IDE`; else `MainProjectService.isMainProject -> LAUNCH`;
  else `ProjectLockUtils.isProjectOpen -> FOCUS`; else `LAUNCH`. Delete the moved `isSameIdeInstance` code and its
  `ensureProjectDirectories` call. Update the KDoc (drop "(gap 1)").
- `actions/Launch.kt`:
  - `launchOrFocusUnderProgress`: `when (planLaunch(repository, projectPath, PathManager.getConfigDir()))` with one
    branch per step; no fall-through.
  - `openProjectInCurrentIde(projectPath, currentProject)` (also used by the `SetAsMain` prompt branch): on the pooled
    thread:
    ```kotlin
    ProjectUtil.findProject(projectPath.path)
        ?.let { application.invokeLater { ProjectUtil.focusProjectWindow(it) } }
        ?: ProjectUtil.openOrImport(projectPath.path, currentProject, true)
    ``` Keep it in `actions/Launch.kt`: it is pure
    platform effect, like the other UI shells.
- No core change.

## Tests

`plugin/src/test/kotlin/com/projectjuggler/plugin/services/LaunchPlanTest.kt` (from plugin-tests), adjusted to the
single-step return:

- Flip "(gap 1)": `currentConfigDir == <projRoot>/config` + `markIsolatedInstanceOpen` -> `OPEN_IN_CURRENT_IDE`;
  rename to "project of the current isolated IDE opens in place even though its lock exists". Same without lock ->
  `OPEN_IN_CURRENT_IDE`.
- Flip the pinned side effect: "planLaunch creates nothing": after `planLaunch` for a registered project,
  `<projRoot>/config` does not exist.
- New: `currentConfigDir` given as `<projRoot>/config/../config` -> `OPEN_IN_CURRENT_IDE` (normalization).
- New: main project (`MainProjectService.setMainProject`), `baseConfigPath` = `currentConfigDir` ->
  `OPEN_IN_CURRENT_IDE`.
- New: main project, `baseConfigPath` = temp dir with a `.lock` holding the current PID, `currentConfigDir` =
  `<other projRoot>/config` -> `LAUNCH`.
- Unchanged: unregistered -> `LAUNCH`; registered, no lock -> `LAUNCH`; lock -> `FOCUS`.
- New `services/CurrentIdeProjectTest.kt`: `belongsToCurrentIde` true/false for the isolated and main cases above,
  plus "unregistered path whose isolated config dir is the current config dir -> true".

Not automatable: `openProjectInCurrentIde` (platform). Manual check below.

## Docs

`docs/plugin-spec.md`:
- §4.2 step 2: replace the `isSameIdeInstance` bullet and the "falls through" sentence with: "`planLaunch` picks one
  step. If the project belongs to the current IDE (`belongsToCurrentIde`: the main project in the main instance, or an
  isolated project whose `<projRoot>/config` is `PathManager.getConfigDir()`), open it here: focus its frame if
  already open (`ProjectUtil.findProject`), else `ProjectUtil.openOrImport` in a new frame. Else the main project is
  always launched (`launchMain`; a running main IDE opens or focuses it). Else focus when `isProjectOpen`, launch
  otherwise." Delete the `ensureProjectDirectories` side-effect paragraph.
- §4.1 `SetAsMain` bullet: "open the project in the current IDE (`openProjectInCurrentIde`, focuses it if already
  open)".
- §13 (added by plugin-tests): drop gap 1 from the list of gaps pinned by tests.
- Known gaps: delete item 1; the other items keep their numbers (as in core-spec).

## Sessions

1. Single session, Fable 5.1 at default effort, ~12% context. Reads: this plan, `CLAUDE.md`, `actions/Launch.kt`,
   `services/LaunchProject.kt`, `services/LaunchPlanTest.kt`, `plugin/src/test/.../test/PluginTestSupport.kt`, core
   `MainProjectService.kt` and `DirectoryManager.getProjectRoot`. Implements everything above, runs Verification,
   does Finishing. Done when `:plugin:test` passes and gap 1 is gone from the spec.

## Verification

1. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.services.LaunchPlanTest"`
2. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.services.CurrentIdeProjectTest"`
3. `./gradlew test`
4. `./gradlew :plugin:buildPlugin`
5. Manual, `./gradlew :plugin:runIde` with a main project set:
   - Open an isolated project; in it close the project window (welcome screen), open the popup, choose the project:
     it reopens in the same IDE and no "Cannot focus" balloon appears. Choose it again while open: its frame is focused,
     no second frame.
   - In the main IDE with a different project open, choose the main project: it opens in the main IDE.
   - From an isolated IDE choose the main project while the main IDE runs without it: the main IDE opens it.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/plugin-gap-01-launch-in-current-ide.md` in the same commit.
- `git status` / `git diff`; stage only the touched files by path. Commit in the style of recent commits (lowercase
  imperative subject, explanatory body, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested
  subject: `open a project in place when it belongs to the current IDE instead of focusing by lock`.
