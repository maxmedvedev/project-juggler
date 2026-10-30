# Portable runIde base dir, a settings page, and a startup check for missing base paths (plugin-spec gap 15)

## Context

`docs/plugin-spec.md`, Known gaps 15: "`runIde` hardcodes a user-specific base dir. The plugin has no tests, settings
page, or startup health check for missing base paths." Tests are covered by `plans/plugin-tests.md`, which trims the
entry to the other three items.

**Depends on `plans/plugin-tests.md`** (layout, `CurrentIdePaths`, test setup) **and on
`plans/plugin-gap-09-ide-path-drift.md`** (`IdePathKind`, `PathUtils.isSamePath`, `IdePathsStartupCheck`,
`startup/IdePathsCheckActivity`, `showNotification(..., actions)`, per-field `autoPopulateIfNeeded`). This plan extends
that startup check instead of adding a second one. No test pins this gap.

Coordination:
- `plans/plugin-gap-08-popup-recents-and-open-state.md` makes the popup list `getRecent(maxRecentProjects)` with no
  other cap, so the spinner here directly sets how many recents the popup shows. Land either order; if gap 8 has not
  landed, the popup still cuts at 20 and the spinner's upper bound below keeps that harmless.
- `plans/plugin-gap-13-sync-batch-consent.md` renames the toggle to "Offer plugin sync on IDE update" (values of
  `popup.toggle.sync.on.update.enabled/disabled`, keys unchanged). The checkbox here uses that exact label under its
  own key `settings.sync.plugins.on.update` (the popup values carry a check-mark prefix); if gap 13 has not landed,
  still use the new label so both agree once it does.

Current state:

- `plugin/build.gradle.kts` `runIde` passes `-Dproject.juggler.base.dir=/Users/max/.project-juggler-test`. On any other
  machine the sandbox writes to a path that does not exist (created on first write) under another user's home layout.
  `~/.project-juggler-test/v2/idea-b4214b81/config.json` on this machine already shows the drift this causes:
  `baseConfigPath` points at `/Users/max/work/juggler/plugin/build/idea-sandbox/...`, an older checkout location.
- The only way to see or change `IdeConfig` fields is editing `<ideDir>/config.json` by hand, except
  `syncPluginsOnIdeUpdate` (popup toggle) and the main project (popup sub-action). `dontAskAboutMainProject` can be set
  but never cleared from the UI.
- A stored base path that no longer exists (IDE uninstalled/upgraded, config dir deleted) surfaces only when a launch
  silently skips the copy (core-spec §7.1, copy errors swallowed) or a sync throws `IllegalStateException`.

Decision (made with the user, 2026-10-30): default `runIde` base dir `plugin/build/project-juggler-sandbox`,
overridable with the Gradle property `projectJuggler.baseDir`, because it is portable, sits next to
`build/idea-sandbox` whose paths it stores, and `clean` resets both together. Rejected: `~/.project-juggler-test`
(stored paths drift when the checkout moves); no default (`runIde` fails out of the box).

Decision (made with the user, 2026-10-30): the page covers the current IDE only and contains the three base paths
with browse buttons and a "Use current IDE paths" button, "Offer plugin sync on IDE update", "Ask to choose a main
project" (inverse of `dontAskAboutMainProject`), the main project (folder chooser plus clear, applied through the
existing `setMainProject`/`clearMainProject` services) and `maxRecentProjects` (spinner 1..50), plus read-only rows
for IDE name, executable and data dir; everything is editable only in the main instance and read-only with a comment
in an isolated one, because that puts every `IdeConfig` setting a user may want in one place while keeping the
main-project cleanup and notifications identical to the popup flow. Rejected: paths and checkboxes only (main project
and limit stay `config.json`-only); a per-installation table for every `IdeRegistry` installation (nothing else in
the UI is multi-IDE yet, gap 6).

Decision (made with the user, 2026-10-30): the missing-path check runs in the main instance only, folded into gap 9's
once-per-session balloon with actions "Use current IDE paths" and "Open settings", because only there can the user act
on it. Rejected: also in isolated instances (the page is read-only there).

## Target behavior

- `./gradlew :plugin:runIde` passes `-Dproject.juggler.base.dir=<repo>/plugin/build/project-juggler-sandbox`;
  `./gradlew :plugin:runIde -PprojectJuggler.baseDir=/some/dir` passes that dir instead.
- Settings > Tools > Project Juggler shows the page from the page-contents decision. Apply validates, then writes
  `config.json`:

| Field input | Saved as | Validation error (on apply) |
|---|---|---|
| blank | `null` (main instance refills it from the current IDE at next start, gap 9) | none |
| `~/x` or relative | absolute, normalized, `~` expanded | — |
| missing path | — | `settings.error.path.missing` |
| config/plugins not a dir, vmoptions not a file | — | `settings.error.path.not.directory` / `.not.file` |
| under `<ideDir>/projects/` | — | `settings.error.path.isolated` |

  Changing the vmoptions path refreshes `baseVmOptionsHash`.
- Main project field (main instance only): file-or-folder chooser (file-based projects such as `MODULE.bazel` are
  valid main projects, as in `showFileChooserAndLaunch`) and a "Clear" link. On apply, only when the value changed:
  non-blank -> `ProjectManager.resolvePath`, must exist (`settings.error.main.project.missing`), then
  `setMainProject(path, handler, repo)`; blank -> `clearMainProject(handler, repo)`. Both run on a pooled thread, so
  `cleanStaleMainProjectEntry` and the `notification.success.set.main` / `unset.main` balloons behave exactly as from
  the popup sub-action. Unchanged value -> no call, no notification.
- `maxRecentProjects`: spinner 1..50, default 10. Saved as is; `recent.json` is trimmed at the next `recordOpen`
  (core-spec §7.1 step 3), and with gap 8 the popup shows at most that many immediately.
- `reset()` (dialog Reset / reopen) reloads everything from `config.json`, so a main project changed meanwhile from
  the popup is shown correctly.
- Startup (main instance, once per session): problems are computed per `IdePathKind`. A stored path that does not exist
  is `MISSING`; an existing one that differs from the current IDE path is `OUT_OF_SYNC` (gap 9). If any `MISSING`:
  warning balloon `notification.paths.missing` ("Project Juggler: base {0} no longer exist.") with actions "Use current
  IDE paths" and "Open settings". Else if any `OUT_OF_SYNC`: gap 9's balloon, plus "Open settings". Else nothing.
- The popup item `UpdatePathsAction` is unchanged.

## Implementation

`plugin/build.gradle.kts`, `runIde`:

```kotlin
val runIdeBaseDir = providers.gradleProperty("projectJuggler.baseDir")
    .orElse(layout.buildDirectory.dir("project-juggler-sandbox").map { it.asFile.absolutePath })
runIde {
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dproject.juggler.base.dir=${runIdeBaseDir.get()}")
    }
}
```

`sync-helper` spawned from the sandbox still ignores the property (sync-helper-spec gap 2); not in scope.

Plugin (`plugin/src/main/kotlin/com/projectjuggler/plugin/`):

- `services/JugglerSettings.kt` (new, no Swing):
  ```kotlin
  internal data class JugglerSettings(
      var baseConfigPath: String = "", var basePluginsPath: String = "", var baseVmOptionsPath: String = "",
      var syncPluginsOnIdeUpdate: Boolean = false, var askForMainProject: Boolean = true,
      var mainProjectPath: String = "", var maxRecentProjects: Int = 10,
  ) { companion object { val MAX_RECENT_RANGE = 1..50 } }
  internal fun loadJugglerSettings(repository: IdeConfigRepository): JugglerSettings
  /** Bundle error message for the path entered for [kind], or null when valid (blank is valid). */
  internal fun validateBasePath(repository: IdeConfigRepository, kind: IdePathKind, input: String): String?
  internal fun validateMainProject(input: String): String?
  /** Writes every field except the main project; returns the main-project change to apply, if any. */
  internal fun saveJugglerSettings(repository: IdeConfigRepository, settings: JugglerSettings): MainProjectChange?
  internal sealed interface MainProjectChange {
      data class Set(val path: ProjectPath) : MainProjectChange
      data object Clear : MainProjectChange
  }
  internal fun applyMainProjectChange(repository: IdeConfigRepository, change: MainProjectChange,
                                      notificationHandler: NotificationHandler)
  internal fun JugglerSettings.withCurrentIdePaths(current: CurrentIdePaths): JugglerSettings
  ```
  `saveJugglerSettings`: one `repository.update`; paths via `PathUtils.expandTilde` + `toAbsolutePath().normalize()`,
  blank -> null; `BaseVMOptionsTracker.getInstance(repository).updateHash()` when the vmoptions value changed;
  `maxRecentProjects.coerceIn(MAX_RECENT_RANGE)`. It compares `mainProjectPath` (resolved) with the stored value and
  returns `Set`/`Clear`/null without writing it, so the main project is changed only through the existing services.
  `applyMainProjectChange` dispatches to `setMainProject` / `clearMainProject` (`services/SetMainProject.kt`,
  `UnsetMainProject.kt`).
  `withCurrentIdePaths` fills the three strings from `CurrentIdePaths` (vmoptions only when the file exists).
  `IdePathKind` (gap 9) gains `val expectsDirectory: Boolean` (false for `VM_OPTIONS`) and the setter for its field, so
  load/save/validate iterate the kinds instead of repeating three blocks.
- `settings/ProjectJugglerConfigurable.kt` (new): `internal class ProjectJugglerConfigurable :
  BoundSearchableConfigurable(ProjectJugglerBundle.message("settings.display.name"), "", ID)` with `const val ID =
  "com.projectjuggler.plugin.settings"`. `createPanel()`: `KoinInit.init(pluginModule)`, resolve the repository and
  `CurrentIdePaths.detect()`,
  `val isolated = DirectoryManager.getInstance(repo).isIsolatedConfigDir(current.configDir)`,
  `val state = loadJugglerSettings(repo)`; `panel { }` (Kotlin UI DSL v2, `com.intellij.ui.dsl.builder.panel`):
  - group "IDE": read-only `label`s for `installation.displayName`, `executablePath`, `repository.baseDir`.
  - group "Projects": `textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFileOrDir())`
    `.bindText(state::mainProjectPath)` with `emptyText` `settings.main.project.none`, `validationOnApply` via
    `validateMainProject`, and a `link(settings.main.project.clear)` that empties the field;
    `spinner(JugglerSettings.MAX_RECENT_RANGE).bindIntValue(state::maxRecentProjects)` labeled
    `settings.max.recent.projects`, comment `settings.max.recent.projects.comment` ("Also the size of the Recent
    Juggled Projects popup").
  - group "Base settings": per kind `textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir()` /
    `.singleFile())` `.bindText(state::<field>)`,
    `.validationOnApply { validateBasePath(repo, kind, it.text)?.let(::error) }`,
    `.comment(<current IDE path for that kind>)`; a `button(settings.use.current.paths)` that writes
    `state.withCurrentIdePaths(current)` into the fields (`reset()` on the bound cells).
  - `checkBox(settings.sync.plugins.on.update).bindSelected(state::syncPluginsOnIdeUpdate)`,
    `checkBox(settings.ask.main.project).bindSelected(state::askForMainProject)`.
  - When `isolated`: `enabled(false)` on the editable rows and a top comment `settings.isolated.comment`.
  `apply()`: `super.apply()` (pushes UI into `state`), then `saveJugglerSettings(repo, state)`; a returned change runs
  `applyMainProjectChange(repo, it, IntelliJNotificationHandler(null))` in `application.executeOnPooledThread`
  (the cleanup does file I/O and must not run on EDT). Catch `Throwable` around the save (rethrow
  `ControlFlowException`), log, and rethrow as `ConfigurationException(message)` so the dialog shows it.
  `reset()`: copy `loadJugglerSettings(repo)` into `state`, then `super.reset()` so the bound cells re-read it.
  Config I/O happens on EDT (small JSON file, as every `Configurable` does); noted in the spec threading table.
  APIs (IntelliJ 2025.1.1, app-client.jar, all public, none `@Internal`/`@Experimental`):
  `com.intellij.openapi.options.BoundSearchableConfigurable(displayName, helpTopic, id)`,
  `com.intellij.ui.dsl.builder.Row.textFieldWithBrowseButton(FileChooserDescriptor, Project?, (VirtualFile) -> String)`,
  `TextFieldWithBrowseButtonKt.bindText`, `ButtonKt.bindSelected`, `Cell.validationOnApply`, `Cell.enabled`,
  `Row.spinner(IntRange, Int)` + `bindIntValue`, `Row.link`,
  `com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.singleDir()/singleFile()/singleFileOrDir()`.
- `plugin.xml`: `<applicationConfigurable parentId="tools"
  instance="com.projectjuggler.plugin.settings.ProjectJugglerConfigurable" id="com.projectjuggler.plugin.settings"
  bundle="messages.ProjectJugglerBundle" key="settings.display.name"/>`.
- `services/IdePathsStartupCheck.kt` (from gap 9): add `internal enum class IdePathProblem { MISSING, OUT_OF_SYNC }`
  and change `mismatchesToReport` to `problemsToReport(repository, current): Map<IdePathKind, IdePathProblem>`;
  `MISSING` when the stored value is non-null and `!Files.exists`, otherwise `OUT_OF_SYNC` from `detectMismatches`.
  Still empty in an isolated instance and after the first call.
- `startup/IdePathsCheckActivity.kt`: pick the message from the map (Target behavior); actions built with
  `NotificationAction.createSimpleExpiring` — "Use current IDE paths" -> `IdePathSyncDetector.updatePaths(...)` on a
  pooled thread; "Open settings" -> `ShowSettingsUtil.getInstance().showSettingsDialog(project,
  ProjectJugglerConfigurable::class.java)` (`com.intellij.openapi.options.ShowSettingsUtil`, app-client.jar, public).
- Bundle: `settings.display.name`, `settings.group.ide`, `settings.group.base`, labels for the four IDE rows and the
  three paths, `settings.main.project.none`, `settings.use.current.paths`,
  `settings.sync.plugins.on.update` (= "Offer plugin sync on IDE update", gap 13's label),
  `settings.ask.main.project`, `settings.group.projects`, `settings.main.project`, `settings.main.project.clear`,
  `settings.error.main.project.missing`, `settings.max.recent.projects`, `settings.max.recent.projects.comment`,
  `settings.isolated.comment`, the four `settings.error.path.*` keys,
  `notification.paths.missing`, `notification.action.open.settings`. Reuse gap 9's `notification.action.update.paths`
  text only if it reads right for both balloons; else add `notification.action.use.current.paths`.

## Tests

Plugin, Kotest `StringSpec`, `KoinTestExtension()`, `createTempDir`.

- `services/JugglerSettingsTest` (new, pure): load maps nulls to `""` and `dontAskAboutMainProject=true` to
  `askForMainProject=false`; save maps blank to null, expands `~`, normalizes `a/../b`; `validateBasePath`: blank ->
  null, missing -> `settings.error.path.missing`, file for `CONFIG` -> `.not.directory`, dir for `VM_OPTIONS` ->
  `.not.file`, `repo.baseDir/projects/x/config` (created) -> `.isolated`, valid dir -> null; changing the vmoptions
  path sets `baseVmOptionsHash` to the new file's hash, leaving it unchanged does not touch it;
  `withCurrentIdePaths` skips a missing vmoptions file.
  Main project and limit: load maps `mainProjectPath`/`maxRecentProjects`; `validateMainProject` blank -> null, missing
  -> `settings.error.main.project.missing`, existing dir or file -> null; save returns `Set(resolved path)` for a new
  value, `Clear` for blank when one was stored, null when unchanged, and never writes `mainProjectPath` itself;
  `maxRecentProjects` 0 and 99 are coerced to 1 and 50. `applyMainProjectChange(Set)` with a tracked project and no
  lock -> `mainProjectPath` set, `projects/<id>/` removed, `notification.success.set.main` in a
  `RecordingNotificationHandler`; `Clear` -> null and `notification.success.unset.main`.
- `services/IdePathsStartupCheckTest` (extend gap 9's): stored config dir deleted -> `{CONFIG: MISSING}`; existing but
  different -> `OUT_OF_SYNC`; both kinds present -> both entries; second call -> empty.
- `settings/ProjectJugglerConfigurableTest` (new, test application): on EDT
  (`ApplicationManager.getApplication().invokeAndWait`), `createComponent()` is non-null and `isModified()` is false
  after `reset()`; changing the stored `maxRecentProjects` in `config.json` and calling `reset()` again keeps
  `isModified()` false (reset reloads); then `disposeUIResources()`. If the headless test application cannot build
  the panel, drop this spec and record why in the spec's §13.

Manual check (`./gradlew :plugin:runIde` after `./gradlew clean`): the sandbox's `config.json` appears under
`plugin/build/project-juggler-sandbox/v2/`. Settings > Tools > Project Juggler shows the current values; set the
plugins path to a missing dir -> apply refused with the message; set it to `/tmp` -> saved. Restart the sandbox after
setting `baseConfigPath` (by hand in `config.json`) to a deleted dir -> one "no longer exist" balloon; "Open settings"
opens the page; "Use current IDE paths" fixes it. Pick a main project in the page and apply: the "set as main"
balloon appears and its `projects/<id>/` entry (if any) is gone; clear it: the "unset" balloon. Set the limit to 3:
after opening one more project the popup shows 3 recents (with gap 8). From an isolated project the page is
read-only.
`./gradlew :plugin:runIde -PprojectJuggler.baseDir=/tmp/pj` uses `/tmp/pj` (check with `ps aux | grep juggler`).

## Docs

`docs/plugin-spec.md`:
- §1: `runIde` base dir (default and property); registered extensions add the `applicationConfigurable`.
- New §7a "Settings page": contents, validation table, main project through `setMainProject`/`clearMainProject`
  (pooled thread, same notifications), `maxRecentProjects` range, isolated read-only, blank path = refill at next
  start.
- §5.5 Set/Unset Main Project: mention the settings page as a second entry point.
- §7: startup check reports `MISSING` before `OUT_OF_SYNC`, with the two actions.
- §12 threading: settings load/apply on EDT.
- Known gaps: delete item 15.

`CLAUDE.md`: Build Commands `runIde` comment becomes "uses `plugin/build/project-juggler-sandbox` as base dir;
override with `-PprojectJuggler.baseDir=...`"; Architecture plugin bullet mentions `settings/` (the configurable).

## Sessions

Fable 5.1, default effort. Session 1 first.

1. **runIde and settings model** (~18%). Reads: this plan, `CLAUDE.md`, `plugin/build.gradle.kts`, gap 9's
   `IdePathKind.kt`, `IdePathSyncDetector.kt`, `CurrentIdePaths.kt`, core `PathUtils.kt`, `BaseVMOptionsTracker.kt`,
   `IdeConfig.kt`, `services/SetMainProject.kt`, `UnsetMainProject.kt`, `RecordingNotificationHandler.kt`. Does:
   build change, `JugglerSettings.kt` (incl. main project and limit), `IdePathKind` additions, `JugglerSettingsTest`,
   the
   `CLAUDE.md` runIde line. Commit, e.g. `make the runide base dir portable and add the settings model`; marks this
   entry `**Done** in <subject>`.
2. **Configurable and startup check** (~24%). Reads: this plan, session 1's files, `IdePathsStartupCheck.kt`,
   `startup/IdePathsCheckActivity.kt`, `Utils.kt`, `plugin.xml`, the bundle, plugin-spec §1, §5.5, §7, §12, and the
   label decided in `plans/plugin-gap-13-sync-batch-consent.md` (or its commit if landed). Does: the
   configurable, plugin.xml, bundle keys, `IdePathProblem`, activity actions, the two remaining test specs, manual
   check, Docs, Finishing.

## Verification

1. `./gradlew :plugin:test`
2. `./gradlew :core:test`
3. `./gradlew :plugin:buildPlugin` and `./gradlew :plugin:verifyPlugin` (no internal API, configurable registered)
4. Manual check from Tests.

## Finishing

- Apply the Docs section and the `CLAUDE.md` edits.
- Delete `plans/plugin-gap-15-settings-and-health-check.md` in the commit of session 2.
- Per session: `git status` / `git diff`; stage only touched files by path. Commit in repo style (lowercase imperative
  subject, explanatory body, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested subject for
  session 2: `add a settings page and report missing base paths at startup`.
