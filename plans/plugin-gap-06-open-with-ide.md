# Plugin gap 6: "Open with <IDE>" sub-actions for other registered IDE installations

## Context

`docs/plugin-spec.md`, Known gaps 6: "`OpenWithIdeSubAction` is unimplemented (`TODO()`); multi-IDE launching is not
reachable from the UI even though the data model supports it."

**Depends on `plans/plugin-tests.md` landing first.** Written against what it introduces:
`projectSubActions(item: OpenRecentProjectAction): List<RecentProjectSubAction>` (top-level in
`actions/recent/RecentProjectPopup.kt`, with the unused `ideService`/`allInstallations`/`currentInstallation` vals
removed and the "Open with IDE" todo kept), `planLaunch(repository, projectPath, currentConfigDir)` and
`launchProject(..., launcher)` in `services/LaunchProject.kt`, the plugin test support and Kotest+MockK specs under
`plugin/src/test`. No plugin-tests spec pins gap 6.

Today: `actions/recent/OpenWithIdeSubAction.kt` is a `data class OpenWithIdeSubAction(installation)` whose `onChosen`
is `TODO("implement me")` and whose label `"Open with ${installation.displayName}"` is hardcoded (part of gap 3). The
popup step has the list-building code commented out. `launchOrFocusProject(project, projectPath)` (`actions/Launch.kt`)
always uses `IdeInstallationService.currentIdeConfigRepository`.

Everything below the plugin already works per installation: `IdeInstallationService.getAllInstallations()` /
`getRepository(installation)` (delegating to `IdeRegistry`), `ProjectLauncher.getInstance(repo).launch` (registers
metadata, records recents, starts `repo.installation.executablePath` with dirs under that `<ideDir>`),
`ProjectLockUtils.isProjectOpen(repo, path)` and `focusExistingProject(path, repo, handler)`.

The catch is base settings. Another installation's repository has `baseConfigPath`/`basePluginsPath` only if the plugin
has run in that IDE (`autoPopulateIfNeeded`). Otherwise `DirectoryManager.getBaseConfigPath()` falls back to
`ConfigLocator.findDefaultConfigDirectory()`, the newest `IntelliJIdea*` dir on the machine, and
`getBasePluginsPath()` to `PluginLocator`: for an older or different IDE that copies the wrong version's config and
plugins into the new isolated instance.

Decision (made with the user, 2026-10-30): offer only installations whose repository already has `baseConfigPath` set
(the plugin has run there once), excluding the current one, because only those copy the right IDE's settings and
plugins. Rejected: every detected installation with the locator fallback (wrong-version settings); every installation
launched with no base copy (needs a core option, empty settings and no plugins).

Decisions taken without a question (project conventions or no real alternative):
- No main-project prompt for another IDE: the prompt is about the *current* IDE's main instance
  (`shouldPromptForMainProject(repo, PathManager.getConfigDir())`). For another installation's repository that check is
  meaningless; with `baseConfigPath` unset it would even return true. Core still honors the other IDE's own main
  project (`ProjectLauncher.launch` -> `launchMain` with that IDE's executable).
- Installation discovery (`IntelliJLocator.findAllInstallations` scans the filesystem) runs on the popup's background
  thread in `RecentProjectPopupBuilder.show`, not in the popup step on EDT.
- The label moves to the bundle (CLAUDE.md: user-visible strings go in `ProjectJugglerBundle.properties`).

Related: `plans/plugin-gap-01-launch-in-current-ide.md` changes `planLaunch`'s return type and `openProjectInCurrentIde`
in the same files; `plans/plugin-gap-07-remove-project.md` also edits `projectSubActions`. No dependency in either
direction; whichever lands second rebases its edits. `plans/plugin-gap-03-bundle-strings-and-catches.md` moves the same
label to the same key `popup.recent.projects.action.open.with`; if it lands first, skip the bundle and `getTextFor`
bullets and the gap 3 docs edit.
`plans/plugin-gap-14-edt-io-and-koin-init.md` makes `launchOrFocusProject` `@RequiresBackgroundThread` and wraps the
sub-action calls in `executeOnPooledThread`; mirror `OpenRecentProjectSubAction` as it is when this lands.
`plans/plugin-gap-10-current-ide-identity.md` fixes the home-dir keyed current installation; the `currentHome` exclusion
below stays as a cheap guard either way.

## Target behavior

- The project sub-menu shows, right after "Open Project", one "Open with <displayName>" entry per installation that
  is not the current one and whose repository has `baseConfigPath` set, sorted by `displayName`. None -> no entries.
- An installation counts as current when its `directoryName` equals `currentInstallation.directoryName`, or its
  `executablePath` lies under `PathManager.getHomePath()` (gap 10 can key the current installation on the home dir,
  so the same IDE may also be detected by `IntelliJLocator` under a different key).
- Choosing an entry runs the launch flow against that installation's repository, without the main-project prompt:
  `planLaunch(otherRepo, path, PathManager.getConfigDir())` -> `FOCUS` if that IDE's isolated (or main) instance for
  the project is running, else `LAUNCH` (`ProjectLauncher.getInstance(otherRepo).launch`). Errors use the existing
  launch/focus notifications.
- The current IDE's recents are untouched; the project appears in the other IDE's recents.

## Implementation

- New `plugin/src/main/kotlin/com/projectjuggler/plugin/services/OpenWithIde.kt`:
  ```kotlin
  /** Other IDE installations a project can be opened with: not the current one, base settings already captured. */
  internal fun openWithInstallations(
      all: List<IdeInstallation>,
      current: IdeInstallation,
      currentHome: Path,
      repositoryFor: (IdeInstallation) -> IdeConfigRepository,
  ): List<IdeInstallation>
  ```
  Filter as in Target behavior (`Path.of(it.executablePath).toAbsolutePath().normalize().startsWith(currentHome)`),
  `repositoryFor(it).load().baseConfigPath != null`, `sortedBy { it.displayName }`.
  Also `internal fun shouldPromptForMainProject(target: IdeConfigRepository, current: IdeConfigRepository,
  currentConfigDir: Path): Boolean = target.installation.directoryName == current.installation.directoryName &&
  MainProjectService.shouldPromptForMainProject(target, currentConfigDir)`.
- `actions/Launch.kt`: `launchOrFocusProject(project, projectPath, repository: IdeConfigRepository =
  IdeInstallationService.currentIdeConfigRepository)`; the prompt condition becomes the new
  `shouldPromptForMainProject(repository, IdeInstallationService.currentIdeConfigRepository, currentConfigDir)`;
  thread `repository` through `askToOpenAsMainProject`, `launchOrFocusProjectImpl` and `launchOrFocusUnderProgress`
  (which today re-reads the current repository). Existing callers keep the default.
- `actions/recent/OpenWithIdeSubAction.kt`: mirror `OpenRecentProjectSubAction`:
  `step.doFinalStep { launchOrFocusProject(project, item.projectPath,
  IdeInstallationService.getInstance().getRepository(installation)) }; return PopupStep.FINAL_CHOICE`.
  `getTextFor` -> `ProjectJugglerBundle.message("popup.recent.projects.action.open.with", installation.displayName)`.
- `messages/ProjectJugglerBundle.properties`: `popup.recent.projects.action.open.with=Open with {0}` next to
  `popup.recent.projects.action.open.project`.
- `actions/recent/RecentProjectPopup.kt`:
  - `projectSubActions(item, openWith: List<IdeInstallation> = emptyList())` inserts
    `openWith.map(::OpenWithIdeSubAction)` after `OpenRecentProjectSubAction`; delete the commented-out block and the
    todo.
  - `RecentProjectPopup(itemsList, project, openWith: List<IdeInstallation>)` passes it to `RecentProjectPopupStep`,
    whose `createProjectSubmenu` calls `projectSubActions(item, openWith)`.
- `actions/recent/RecentProjectPopupBuilder.show()`: inside the existing `try`, after `buildItems`, compute
  `openWithInstallations(service.getAllInstallations(), service.currentInstallation, Path.of(PathManager.getHomePath())
  .toAbsolutePath().normalize(), service::getRepository)` with `service = IdeInstallationService.getInstance()`; pass
  it to `RecentProjectPopup`. `buildItems` is unchanged.

## Tests

Under `plugin/src/test/kotlin/com/projectjuggler/plugin/`, Kotest `StringSpec`, `KoinTestExtension()`, `createTempDir`.

- New `services/OpenWithIdeTest.kt` (repositories via `IdeConfigRepository(tempDir.resolve(i.directoryName), i)`):
  - current installation excluded by `directoryName`; another whose executable is under `currentHome` excluded.
  - installation whose repository has no `baseConfigPath` excluded; with `baseConfigPath` included.
  - result sorted by `displayName`; empty input -> empty.
  - `shouldPromptForMainProject`: target = current, no main project, `baseConfigPath` = `currentConfigDir` -> true;
    target = another installation with `baseConfigPath` unset -> false.
- `actions/recent/RecentProjectPopupTest.kt` (from plugin-tests): add "isolated item with two other IDEs ->
  `[Open, OpenWith(a), OpenWith(b), ToggleMain, Sync x4, Remove]`" and the same for the main item without Sync;
  `OpenWithIdeSubAction(i).getTextFor(item) == ProjectJugglerBundle.message("popup.recent.projects.action.open.with",
  i.displayName)`. Existing cases (default `openWith`) unchanged.
- `services/LaunchPlanTest.kt`: add "another installation's repository: not registered -> LAUNCH; its isolated lock
  present -> FOCUS" (planning is repository-driven, no new code; pins the multi-IDE path). Expect `[FOCUS]`-style lists
  or single steps, whichever `planLaunch` returns when this lands (plugin-gap-01 changes it).
- `services/LaunchProjectTest.kt`: add "launch uses the given repository": `launchProject(path, otherRepo, handler,
  launcher)` with a mocked launcher -> `launch(path, any())` called once.

Not automatable: the popup step and the real second IDE. Manual check below.

## Docs

`docs/plugin-spec.md`:
- §4.2: "`launchOrFocusProject(project, path, repository = current)`. The main-project prompt only runs for the current
  installation's repository."
- §5.1: add after step 3 "On the same thread, `openWithInstallations` picks the other installations offered in the
  sub-menu (§5.2)."
- §5.2: replace the "Open with <other IDE> ... commented out ... `TODO()`" sentence with the sub-menu order
  "Open Project, Open with <IDE> for each other installation whose base settings are recorded (not the current one,
  sorted by name), Set/Unset ..." and the selection rule.
- §5.5 table: add row "Open with <IDE> | `launchOrFocusProject` against that installation's repository; no main-project
  prompt; the project is tracked and recorded in that IDE's `<ideDir>`."
- Known gaps: delete item 6; in item 3 remove `"Open with <IDE>" label,`.

## Sessions

1. Single session, Fable 5.1 at default effort, ~18% context. Reads: this plan, `CLAUDE.md`, `actions/Launch.kt`,
   `actions/recent/RecentProjectPopup.kt`, `RecentProjectPopupBuilder.kt`, `OpenWithIdeSubAction.kt`,
   `OpenRecentProjectSubAction.kt`, `services/IdeInstallationService.kt`, `services/LaunchProject.kt`, core
   `config/IdeRegistry.kt` and `IdeInstallation.kt`, the plugin test support and `RecentProjectPopupTest.kt`.
   Implements everything above, runs Verification, does Finishing. Done when `:plugin:test` passes and gap 6 is gone.

## Verification

1. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.services.OpenWithIdeTest"`
2. `./gradlew :plugin:test`
3. `./gradlew test`
4. `./gradlew :plugin:buildPlugin`
5. Manual (needs a second IntelliJ installation with the plugin installed and started once, so its base paths exist):
   in `./gradlew :plugin:runIde`, open the popup, open a project's sub-menu: "Open with <other IDE>" is listed and the
   sandbox IDE itself is not. Choose it: the other IDE starts with `idea.config.path` under
   `<base>/v2/<other ideDir>/projects/<id>/config`. Choose it again while running: its window is focused. An
   installation where the plugin never ran is not listed.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/plugin-gap-06-open-with-ide.md` in the same commit.
- `git status` / `git diff`; stage only the touched files by path. Commit in the style of recent commits (lowercase
  imperative subject, explanatory body, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested
  subject: `offer opening a project with other registered IDE installations`.
