# Move hardcoded strings to the bundle and apply the try-catch rule (plugin-spec gaps 3 and 4)

## Context

`docs/plugin-spec.md`, Known gaps:

- 3: "Hardcoded user-visible strings: self-shutdown notifications (...), "Open with <IDE>" label, `"Unknown error"`
  fallbacks. `SyncType.displayName` values are also hardcoded and interpolated into bundle messages."
- 4: "`performSelfShutdownSync` catches `Exception`, not `Throwable`; `OpenProjectCopyAction` has two catch blocks
  with identical bodies. `LaunchProject` catches `Throwable` without rethrowing `ControlFlowException`."

**Depends on `plans/plugin-tests.md` landing first.** This plan is written against the layout it introduces:
`services/SyncPlanner.kt` (`SyncTarget.SelfShutdown`), `services/SyncProjects.kt`, `SyncType.cliFlag`,
`performSelfShutdownSync(project, notificationMessage, helperArgs)` (no `syncType` parameter),
`services/OpenProjectCopy.kt` (`createAndLaunchProjectCopy`), `services/SyncPluginsOnIdeUpdate.kt`,
`IdePathSyncDetector.autoPopulateIfNeeded`, `shutdown/StopRequestTracker.kt`, and its test specs. If a name differs
when you start, follow the code and note it in the commit body.

plugin-tests fixes the gap-4 parts inside code it moves (`launchProject`, `createAndLaunchProjectCopy`,
`autoPopulateIfNeeded`, `StopRequestTracker`) and trims gap 4 to "`performSelfShutdownSync` catches `Exception`".
Gap 4 is folded into this plan because what is left of it is small and sits next to the gap-3 strings. A grep
(`grep -rn 'catch (' plugin/src/main/kotlin`, 2026-10-30) shows more sites that break the `CLAUDE.md` rule ("catch
`Throwable` and log it; rethrow `ControlFlowException`") and are not moved by plugin-tests. Sibling plans rewrite some
of those files anyway, so each site has exactly one owner:

| Site (after plugin-tests) | Today | Owner |
|---|---|---|
| `actions/Sync.kt` `performSelfShutdownSync` | `catch (e: Exception)`, no log | `plugin-gap-11-sync-helper-extraction.md` (rewrites it as a `Task.Backgroundable`) |
| `util/BundledSyncHelper.kt` | `setExecutable`, `extractBundledSyncHelper` catch `Exception` | `plugin-gap-11-sync-helper-extraction.md` |
| `shutdown/ShutdownSignalService.kt` | metadata load and the two polling catches use `Exception` | `plugin-gap-12-shutdown-service-startup.md` (rewrites startup and the loop) |
| `actions/Sync.kt` `performSyncWithProgress` | `catch (e: Exception)` + CFE rethrow, no log | this plan |
| `services/FocusExistingProject.kt` | `catch (e: Exception)` + CFE rethrow, no log | this plan |
| `services/IdeInstallationService.kt` `getIntelliJExecutablePath` | `catch (e: Exception)` | this plan |

This plan does not change the catch shape in the files owned by 11 and 12. It only replaces the self-shutdown error
literal with a bundle key if 11 has not landed yet (a gap-3 string change). 11 and 12 should use the helpers below when
they land after this plan.

Hardcoded user-visible strings found by `grep -rn '"[A-Z][a-zA-Z]* [a-zA-Z]' plugin/src/main/kotlin` plus the gap
text: the two self-shutdown info texts (`SyncSettingsSubAction`, `SyncAllProjectsAction`), the self-shutdown error
(`Sync.kt`), `"Open with ${installation.displayName}"` (`OpenWithIdeSubAction`, dead code but cheap),
`"Unknown error"` (5 sites: `LaunchProject`, `FocusExistingProject`, `IdePathSyncDetector.updatePaths`,
`SyncPluginsOnIdeUpdate`, `OpenProjectCopy`), `e.message ?: ""` in the two sync error lambdas, `"&lt;No output&gt;"`
(`LaunchProject`), the four `SyncType` names, and the mismatch labels `"Config"`, `"Plugins"`, `"VM Options"` in
`IdePathSyncDetector.detectMismatches` (shown through `popup.update.paths`; not named in the gap but the same rule).

Decision (made with the user, 2026-10-30): the self-shutdown texts keep the literal "IntelliJ", because the
existing `progress.sync.stopping.ide` / `restarting.ide` messages already do and tests need no `Application`.
Rejected: `ApplicationNamesInfo.fullProductName`.

Decision (made with the user, 2026-10-30): `SyncType` names become short-phrase keys (`sync.type.*`) interpolated into
the existing sentences, because the plugin is English-only and this is the smallest change. Rejected: one full message
per type and context (about 20 keys).

Decision (made with the user, 2026-10-30): an exception without a message is shown as bundle
`error.unknown=Unknown error`, because that is today's text. Rejected: the exception class name.

## Target behavior

- No user-visible text changes (English values are copied verbatim), except: the self-shutdown error shows
  "Unknown error" instead of "null" when the exception has no message, and the two sync failure notifications show
  "Unknown error" instead of an empty string.
- A `Throwable` that is not an `Exception` (e.g. `AssertionError`, `NoClassDefFoundError`) in self-shutdown sync,
  in-process sync and focus now produces the error notification instead of escaping to the IDE's error reporter.
- `ControlFlowException` (e.g. `ProcessCanceledException`) and `CancellationException` always propagate.

## Implementation

### Helpers (`plugin/src/main/kotlin/com/projectjuggler/plugin/Utils.kt`)

```kotlin
/** Rethrows exceptions that must never be swallowed. Call first in every `catch (t: Throwable)`. */
internal fun Throwable.rethrowIfControlFlow() {
    if (this is ControlFlowException || this is CancellationException) throw this
}

/** The exception message for a notification, or the bundle's "Unknown error". */
internal fun Throwable.userMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: ProjectJugglerBundle.message("error.unknown")
```

`CancellationException` is `kotlin.coroutines.cancellation.CancellationException` (alias of
`java.util.concurrent.CancellationException`). Replace the existing `if (e is ControlFlowException) throw e` lines in
`RecentProjectPopupBuilder`, `IdePathSyncDetector`, `StaleMainProjectCleanup*`, `SyncPluginsOnIdeUpdate`,
`OpenProjectCopy`, `LaunchProject` with the helper, and every `e.message ?: "Unknown error"` with `e.userMessage()`.

Logging at sites that also notify the user: `LOG.warn(message, t)`, not `LOG.error` (the failure is already
surfaced; `LOG.error` would also fail specs running under the test application's `TestLoggerFactory`). Sites that
only log keep their current level.

### Bundle (`plugin/src/main/resources/messages/ProjectJugglerBundle.properties`)

New keys (values verbatim from the code):

```properties
# Sync types (interpolated into sync messages)
sync.type.all=all settings
sync.type.vmoptions=VM options
sync.type.config=configuration
sync.type.plugins=plugins

# Self-shutdown sync
notification.info.sync.self.shutdown.project=IntelliJ will close to sync {0} and reopen automatically...
notification.info.sync.self.shutdown.all=IntelliJ will close to sync all projects and reopen automatically...
notification.error.sync.self.shutdown.failed=Failed to initiate self-shutdown sync: {0}

# IDE path sync labels (popup.update.paths)
ide.path.kind.config=Config
ide.path.kind.plugins=Plugins
ide.path.kind.vmoptions=VM Options

popup.recent.projects.action.open.with=Open with {0}
notification.error.launch.no.output=&lt;No output&gt;
error.unknown=Unknown error
```

`ProjectJugglerBundle.kt`: `private const val BUNDLE` becomes `internal const val BUNDLE` so `@PropertyKey` can be
used outside the file.

### Call sites

- `actions/recent/SyncType.kt`:
  `sealed class SyncType(@PropertyKey(resourceBundle = BUNDLE) private val displayNameKey: String,
  val cliFlag: String)` with `val displayName: String get() = ProjectJugglerBundle.message(displayNameKey)`;
  objects pass `"sync.type.all"`
  etc. The property name stays `displayName`, so `SyncProjects.kt` and the two actions compile unchanged.
- `SyncSettingsSubAction` / `SyncAllProjectsAction`: `notificationMessage =` the two `notification.info.sync.self.*`
  messages; `errorMessage = { e -> ProjectJugglerBundle.message("...failed", e.userMessage()) }`.
- `actions/Sync.kt`:
  - `performSelfShutdownSync` (catch owned by gap 11): if gap 11 has not landed, only replace the error literal with
    `ProjectJugglerBundle.message("notification.error.sync.self.shutdown.failed", e.userMessage())`; leave the
    `catch (e: Exception)`. If gap 11 has landed, use the key wherever its task reports the failure.
  - `performSyncWithProgress`: `errorMessage: (Throwable) -> String`; `catch (t: Throwable)` with the helper and
    `LOG.warn`. Add a file-level `private val LOG = Logger.getInstance("#com.projectjuggler.plugin.actions.Sync")`.
- `actions/recent/OpenWithIdeSubAction.getTextFor`:
  `ProjectJugglerBundle.message("popup.recent.projects.action.open.with", installation.displayName)`. Leave the
  `TODO()` (gap 6).
- `services/LaunchProject.kt`: `.ifBlank { ProjectJugglerBundle.message("notification.error.launch.no.output") }`.
- `services/IdePathSyncDetector.detectMismatches`: labels from `ide.path.kind.*`. Return type stays `List<String>`.
- `services/FocusExistingProject.kt`: `catch (t: Throwable)`, helper, `LOG.warn`, `userMessage()`.
- `services/IdeInstallationService.getIntelliJExecutablePath`: `Throwable` + helper.
- Not touched here: `shutdown/ShutdownSignalService.kt` (gap 12), `util/BundledSyncHelper.kt` (gap 11). Check that
  `StopRequestTracker` (moved by plugin-tests) follows the rule; fix it here if not, since no sibling owns it.

Overlaps (whichever lands second merges; keys are shared on purpose):
- `plans/plugin-gap-14-edt-io-and-koin-init.md`: same two sync actions and `Sync.kt` (threading, not strings).
- `plans/plugin-gap-11-sync-helper-extraction.md`: owns `performSelfShutdownSync`'s error handling and both
  `BundledSyncHelper` catches (see the table). This plan owns the self-shutdown bundle keys, which 11 already expects.
- `plans/plugin-gap-12-shutdown-service-startup.md`: owns every catch in `ShutdownSignalService`.
- `plans/plugin-gap-13-sync-batch-consent.md`: also edits `performSyncWithProgress` (keeps its own
  `(String) -> String` detail callback and uses `userMessage()` from here).
- `plans/plugin-gap-09-ide-path-drift.md`: moves the mismatch labels to the same `ide.path.kind.*` keys behind an
  `IdePathKind` enum. If it lands first, skip the label part here (and its test flips).
- `plans/plugin-gap-06-open-with-ide.md`: uses the same `popup.recent.projects.action.open.with` key.
- `plans/plugin-gap-05-project-copy-dialog.md`: rewrites the project-copy flow; its catch uses `userMessage()` if this
  plan landed first.

## Tests

All under `plugin/src/test/kotlin/com/projectjuggler/plugin/`, infrastructure from plugin-tests.

- New `UtilsTest` (pure): `rethrowIfControlFlow` rethrows `ProcessCanceledException()` and `CancellationException()`,
  returns for `IllegalStateException`; `userMessage()` returns the message, and `error.unknown` for null and blank.
- New `ProjectJugglerBundleTest` (pure): every new key above is present
  (`ResourceBundle.getBundle("messages.ProjectJugglerBundle").containsKey(key)`), and
  `SyncType.All/VmOptions/Config/Plugins.displayName` equal `ProjectJugglerBundle.message("sync.type.*")`.
- `actions/recent/SyncTypeTest`: change the `displayName` case from literals to the bundle keys (values unchanged).
- `services/IdePathSyncDetectorTest`, `RecentProjectPopupBuilderTest` (`UpdatePathsAction([...])`),
  `RecentProjectPopupTest`: build expected labels from `ProjectJugglerBundle.message("ide.path.kind.*")`.
- `services/LaunchProjectTest`: the "both blank" case expects `notification.error.launch.no.output`; add `launch`
  throwing `IllegalStateException()` (no message) -> `launch.failed` with `error.unknown`; add `launch` throwing
  `ProcessCanceledException()` -> propagates, no notification.
- `services/FocusExistingProjectTest`: add `WindowFocuser.focus` throwing `AssertionError("x")` -> `focus.exception`
  with `x`; throwing `ProcessCanceledException()` -> propagates, no notification.
- `services/OpenProjectCopyTest`, `services/SyncPluginsOnIdeUpdateTest`: in the existing failure cases use an exception
  without message and expect `error.unknown` in the notification.

Not unit-tested (out of scope in plugin-tests): `performSelfShutdownSync`, `performSyncWithProgress`'s catch.
Manual check: in `runIde`, start an isolated project from the popup, then in that
isolated IDE use "Sync Plugins" on itself; the balloon reads "IntelliJ will close to sync plugins and reopen
automatically..." and the IDE restarts.

## Docs

`docs/plugin-spec.md`:
- §5.3/§7: mismatch labels come from `ide.path.kind.*`.
- §6: "`displayName` from bundle keys `sync.type.*` ("all settings", ...)".
- §6.1/§6.2: self-shutdown texts are `notification.info.sync.self.shutdown.project` / `.all`.
- §6.4: "The first `Throwable` aborts ...; `ControlFlowException` is rethrown."
- §6.5 step 1: "Info notification (passed by the caller)"; step 5: "Any `Throwable` except `ControlFlowException` ->
  `notification.error.sync.self.shutdown.failed`, logged as a warning. The IDE does not exit in that case."
- §4.4: "Any other `Throwable` -> `focus.exception`". §4.5: "`&lt;No output&gt;`
  (`notification.error.launch.no.output`)".
- New sentence in Conventions: "Exceptions without a message are shown as `error.unknown`."
- Known gaps: delete item 3 (keep the other numbers). Delete item 4 if gap 11 has landed; otherwise replace it with
  "`performSelfShutdownSync` catches `Exception` (fixed by `plans/plugin-gap-11-sync-helper-extraction.md`)."

`CLAUDE.md`, Development Guidelines, try-catch bullet: append "Use `Throwable.rethrowIfControlFlow()` and
`Throwable.userMessage()` from `plugin/Utils.kt`."

## Sessions

Fable 5.1, default effort.

1. **Strings (gap 3)** (~15%). Reads: this plan, `CLAUDE.md`, the bundle and `ProjectJugglerBundle.kt`, `Utils.kt`,
   `SyncType.kt`, `SyncSettingsSubAction.kt`, `SyncAllProjectsAction.kt`, `Sync.kt`, `OpenWithIdeSubAction.kt`,
   `LaunchProject.kt`, `IdePathSyncDetector.kt`, the listed test specs. Does: bundle keys, `userMessage()`, all string
   call sites, `ProjectJugglerBundleTest`, `UtilsTest` (userMessage part), the test flips. Done when `:plugin:test`
   passes. Commit (subject e.g. `move the remaining plugin strings into the bundle`).
2. **Catch sweep (gap 4) and finishing** (~15%). Reads: this plan, the files in the Context table,
   `FocusExistingProjectTest`, `LaunchProjectTest`. Does: `rethrowIfControlFlow()`, all catch sites, the remaining
   tests, Docs, Finishing.

## Verification

1. `./gradlew :plugin:test`
2. `./gradlew :core:test`
3. `grep -rn 'catch (e: Exception)\|"Unknown error"' plugin/src/main/kotlin` prints nothing outside
   `BundledSyncHelper.kt`, `ShutdownSignalService.kt` and `performSelfShutdownSync` (owned by gaps 11 and 12)
4. `./gradlew :plugin:verifyPlugin`
5. Manual check from the Tests section via `./gradlew :plugin:runIde`

## Finishing

- Apply the Docs section.
- Delete `plans/plugin-gap-03-bundle-strings-and-catches.md` in the session-2 commit.
- Per session: `git status` / `git diff`; stage only the touched files by path. Lowercase imperative subject, a body
  with what changed and why, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Suggested
  session-2 subject: `catch Throwable and rethrow control-flow exceptions across the plugin`.
