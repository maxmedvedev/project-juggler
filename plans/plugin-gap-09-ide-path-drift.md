# Compare IDE paths normalized, check them at startup, never capture isolated dirs as base (plugin-spec gap 9)

## Context

`docs/plugin-spec.md`, Known gaps 9: "`IdePathSyncDetector` compares paths as raw strings and is only checked from the
popup. `autoPopulateIfNeeded` runs the first time the repository is touched in whichever instance that is; if that is
an isolated instance started by `sync-helper` with no prior config, the isolated dirs become the 'base' paths."

**Depends on `plans/plugin-tests.md` landing first** and is written against its layout: `services/CurrentIdePaths.kt`
(`CurrentIdePaths(configDir, pluginsDir, vmOptionsFile)` + `detect()`), and `IdePathSyncDetector` with
`detectMismatches(repository, current)`, `updatePaths(repository, notificationHandler, current)` and
`autoPopulateIfNeeded(repository, current)` (moved from `IdeInstallationService`, plugin-tests section G). Its
`services/IdePathSyncDetectorTest` pins this gap ("stored `/a/b/` vs current `/a/b` -> mismatch (gap 9)"); this plan
flips it. Also **depends on `plans/core-gap-14-main-instance-detection.md`** for `DirectoryManager.isIsolatedConfigDir`
and its normalized comparison; if that has not landed, implement it first (it is a single ~10% session).

Defects found while reading the code (all in `plugin/.../services/IdePathSyncDetector.kt` / `CurrentIdePaths.kt`):

1. Raw string compare: `/a/b/`, `/a/./b` or a symlinked `Application Support` read as drift.
2. Only the popup item (`UpdatePathsAction`) ever checks. The class KDoc claims a "startup checker" that does not exist.
3. `CurrentIdePaths.vmOptionsFile` is `getUserOptionsFile() ?: getPlatformOptionsFile()`. `getUserOptionsFile()`
   returns `<customOptionsDir>/idea.vmoptions` whether or not it exists. With no user file, stored = platform file
   (written by `autoPopulateIfNeeded` only when it exists), current = missing user file -> permanent "VM Options"
   mismatch, and `updatePaths` never overwrites the vmoptions path when the current file is missing, so the item never
   clears. The value the IDE really uses is "user file if it exists, else platform file".
4. `autoPopulateIfNeeded` does not look at which instance it runs in. In an isolated IDE `PathManager.getConfigDir()`
   is `<projRoot>/config` and `jb.vmOptionsFile` is `<projRoot>/idea.vmoptions` (from `IDEA_VM_OPTIONS`), so an
   unset repository gets isolated dirs as base, and every later launch/sync copies from one isolated project into the
   others. It also only fills fields when `baseConfigPath` is null, so a vmoptions file that appears later is never
   picked up (the `todo detect when a user-options file appears`).
5. Mismatch labels `"Config"`, `"Plugins"`, `"VM Options"` are hardcoded user-visible strings.
   `plans/plugin-gap-03-bundle-strings-and-catches.md` moves them to the same `ide.path.kind.*` keys but keeps
   `List<String>`; whichever lands second reuses the keys, and this plan's `IdePathKind` replaces the strings.

The main instance cannot be recognized with `MainProjectService.isRunningInMainInstance` here: after drift the stored
`baseConfigPath` is exactly what is stale, so the main IDE would read as "not main". Use "not an isolated config dir"
(`DirectoryManager.isIsolatedConfigDir`, core-gap-14) instead, as `detectMismatches` already does with `startsWith`.

Decision (made with the user, 2026-10-30): surface drift at startup with one balloon per IDE session in the main
instance, with an "Update paths" action (the popup item stays), because the user decides and deliberately pointed base
paths are never clobbered. Rejected: silently overwriting at startup (clobbers custom base paths); popup only (gap
stays half-open).

## Target behavior

| Situation | Today | After |
|---|---|---|
| stored `/a/b/`, current `/a/b` (or `/a/./b`, or symlink to it) | mismatch | no mismatch |
| no user vmoptions, stored = platform file | permanent "VM Options" mismatch | no mismatch |
| user vmoptions created later, stored = platform file | mismatch, `updatePaths` fixes | mismatch, `updatePaths` fixes |
| main instance starts, a mismatch exists | nothing until popup opened | one balloon per session, "Update paths" |
| isolated instance, repository unset | isolated dirs stored as base | nothing stored |
| main instance, `baseConfigPath` set, other base paths null | untouched | null fields filled from current |
| main instance, a stored base path lies under `<ideDir>/projects/` | kept | overwritten with current (repair) |
| popup item label | `Config, Plugins` (hardcoded) | same text from the bundle |

`updatePaths` semantics are unchanged (overwrite config/plugins, vmoptions only when the current file exists, refresh
hash).

## Implementation

Core (`core/src/main/kotlin/com/projectjuggler/`):

- `util/PathUtils.kt`: add `fun isSamePath(a: Path, b: Path): Boolean` — `toAbsolutePath().normalize()` both; if both
  exist compare `toRealPath()` (catch `Throwable`, fall back to normalized). If core-gap-14 put this as a private
  `samePath` in `MainProjectService`, move it here and call it from there (one copy).

Plugin (`plugin/src/main/kotlin/com/projectjuggler/plugin/`):

- `services/CurrentIdePaths.kt`: `detect()` uses `vmOptionsFile = VMOptionsCopy.getUserOptionsFile()
  ?.takeIf(Files::exists) ?: VMOptionsCopy.getPlatformOptionsFile()` (defect 3). KDoc: "the file the IDE reads".
- `services/IdePathKind.kt` (new): `internal enum class IdePathKind(private val key: String) { CONFIG, PLUGINS,
  VM_OPTIONS; val displayName get() = ProjectJugglerBundle.message(key) }` with keys `ide.path.kind.config`,
  `ide.path.kind.plugins`, `ide.path.kind.vmoptions` ("Config", "Plugins", "VM Options"). Also
  `fun stored(config: IdeConfig): String?` and `fun current(paths: CurrentIdePaths): Path` so the three fields are
  iterated, not copy-pasted.
- `IdePathSyncDetector`:
  - private `isMainInstance(repository, current)` =
    `!DirectoryManager.getInstance(repository).isIsolatedConfigDir(current.configDir)`;
    replaces the `startsWith(repository.baseDir)` check.
  - `detectMismatches(...)`: returns `List<IdePathKind>`; a kind mismatches when its stored value is non-null and
    `!PathUtils.isSamePath(Path.of(stored), kind.current(current))`.
  - `autoPopulateIfNeeded(repository, current)`: return unless `isMainInstance`. In one `repository.update`, for each
    kind set the field to the current path when the stored value is null or lies under
    `repository.baseDir.resolve("projects")` (normalized `startsWith`); `VM_OPTIONS` only when the current file
    exists. Refresh `BaseVMOptionsTracker.updateHash()` when the vmoptions field changed. Log which kinds were filled.
    Catch `Throwable`, rethrow `ControlFlowException`, log (today it catches `Exception`).
  - Fix the class KDoc (startup check now exists).
- `actions/recent/UpdatePathsAction.kt`: `mismatches: List<IdePathKind>`; text joins `displayName`s.
  `RecentProjectPopupBuilder.buildItems` passes the list through unchanged.
- `services/IdePathsStartupCheck.kt` (new):
  ```kotlin
  internal object IdePathsStartupCheck {
      private val checked = AtomicBoolean(false)
      /** Mismatches to report, or empty; true only for the first call per IDE session. */
      fun mismatchesToReport(repository: IdeConfigRepository, current: CurrentIdePaths): List<IdePathKind>
      @TestOnly internal fun resetForTests()
  }
  ```
  `mismatchesToReport` returns empty when `checked.getAndSet(true)` was already true, otherwise `detectMismatches`
  (which itself is empty in an isolated instance).
- `startup/IdePathsCheckActivity.kt` (new, `ProjectActivity`, registered as a fourth `postStartupActivity` in
  `plugin.xml`): `KoinInit.init(pluginModule)`; on `Dispatchers.IO` resolve the repository and
  `mismatchesToReport(repo, CurrentIdePaths.detect())`; if non-empty, show a warning balloon
  `notification.paths.out.of.sync` ("Project Juggler: stored IDE paths are out of sync ({0}).") with action
  `notification.action.update.paths` ("Update paths") that runs `IdePathSyncDetector.updatePaths(repo,
  IntelliJNotificationHandler(project), CurrentIdePaths.detect())` on a pooled thread and expires the notification.
  Try/catch per the project rule. `ProjectActivity` (`com.intellij.openapi.startup.ProjectActivity`, util-8.jar of
  IntelliJ 2025.1.1, `@ApiStatus.OverrideOnly`, public) is used because the app-level alternatives are internal:
  `com.intellij.ide.ApplicationActivity` is `@ApiStatus.Internal`, `AppLifecycleListener.appStarted` is
  `@ApiStatus.Internal` (both in app-client.jar). An IDE that stays on the welcome screen gets no balloon; the popup
  item still covers it.
- `Utils.kt`: add `showNotification(message, project, type, vararg actions: AnAction)` (existing overload delegates)
  using `Notification.addAction`; `NotificationAction.createSimpleExpiring(text, runnable)` from
  `com.intellij.notification.NotificationAction` (app-client.jar, public) builds the action.
- Bundle: the three kind keys, `notification.paths.out.of.sync`, `notification.action.update.paths`.

`IdeInstallationService.detectCurrentRepository` keeps calling `autoPopulateIfNeeded(repository)`; no change there.

## Tests

Plugin, Kotest `StringSpec`, `KoinTestExtension()`, `createTempDir` (setup from plugin-tests).

- `services/IdePathSyncDetectorTest` (edit):
  - Flip "stored `/a/b/` vs current `/a/b` -> mismatch (gap 9)" to "trailing slash and `.` segments are not a
    mismatch" (no gap suffix, no spec comment).
  - Add: symlinked current config dir -> no mismatch (skip on Windows); stored path under `repo.baseDir` as
    `configDir` -> still empty (isolated).
  - Change label assertions from strings to `IdePathKind` values; one assertion that `UpdatePathsAction(listOf(CONFIG,
    PLUGINS)).getTextFor()` contains `"Config, Plugins"` stays in `RecentProjectPopupTest`.
  - `autoPopulateIfNeeded`: isolated `configDir` (`repo.baseDir/projects/x-1/config`) on a fresh repo -> config file
    not created; `baseConfigPath` set, others null, vmoptions file exists -> plugins and vmoptions filled, config kept,
    hash set; stored `basePluginsPath` under `repo.baseDir/projects` -> replaced by current; replaces "`baseConfigPath`
    already set -> untouched" with "all three set and outside the ide dir -> untouched".
- `services/CurrentIdePathsTest` (new, test application): with `jb.vmOptionsFile` unset, `detect().vmOptionsFile` is
  the platform file; set `jb.vmOptionsFile` to an existing temp file outside home -> that file; to a missing one ->
  platform file. Restore the property in `afterTest`.
- `services/IdePathsStartupCheckTest` (new): `resetForTests()` in `beforeTest`; mismatch -> list on first call,
  empty on second; no mismatch -> empty; isolated `configDir` -> empty.
- Core `util/PathUtilsTest` (extend or create next to existing util tests): `isSamePath` for trailing slash, `.`,
  symlink, different dirs, non-existent paths compared normalized.

Manual check: `./gradlew :plugin:runIde`; edit `<base>/v2/<ide>/config.json` so `basePluginsPath` has a trailing `/`:
no popup item, no balloon. Change it to `/nonexistent`: restart sandbox, one balloon; "Update paths" fixes it and the
popup item is gone. Open an isolated project with an empty `<base>`: its `config.json` gets no base paths.

## Docs

`docs/plugin-spec.md`:
- §2: `autoPopulateIfNeeded` runs only in the main instance, fills each null base path (vmoptions only if the file
  exists) and repairs paths under `<ideDir>/projects/`.
- §7: normalized comparison via `PathUtils.isSamePath`; "current vmoptions" = user file if it exists, else platform;
  labels from the bundle; replace "It is only invoked from the popup item..." with the startup check (once per session,
  main instance, balloon with "Update paths"); note the welcome-screen limitation.
- §1: four `postStartupActivity` entries.
- §13 (tests): drop gap 9 from the pinned-gaps list.
- Known gaps: delete item 9 (do not renumber the others).
`docs/core-spec.md` §11: add `PathUtils.isSamePath`.

## Sessions

1. Single session, Fable 5.1 at default effort, ~20% context. Reads: this plan, `CLAUDE.md`, `IdePathSyncDetector.kt`,
   `CurrentIdePaths.kt`, `VMOptionsCopy.kt`, `UpdatePathsAction.kt`, `RecentProjectPopupBuilder.kt`,
   `startup/StaleMainProjectCleanupActivity.kt` (activity pattern), `Utils.kt`, `IdePathSyncDetectorTest.kt`, core
   `PathUtils.kt` and `DirectoryManager.kt`, plugin-spec §2 and §7. Implements everything, runs Verification, does
   Finishing. Done when `:core:test` and `:plugin:test` pass and gap 9 is gone from the spec.

## Verification

1. `./gradlew :core:test`
2. `./gradlew :plugin:test`
3. `./gradlew :plugin:verifyPlugin` (no new internal-API usages)
4. Manual check from Tests.

## Finishing

- Apply the Docs section; `CLAUDE.md` unchanged.
- Delete `plans/plugin-gap-09-ide-path-drift.md` in the same commit.
- `git status` / `git diff`; stage only touched files by path. Commit in repo style (lowercase imperative subject,
  explanatory body, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested subject:
  `check ide paths at startup and stop capturing isolated dirs as base`.
