# Ask before the post-update plugin sync and let sync batches continue past failures (plugin-spec gap 13)

## Context

`docs/plugin-spec.md`, Known gaps 13: "`PluginSyncOnUpdateActivity` performs network-free but potentially long sync
work (stopping and restarting every isolated IDE) on first project open with only a balloon as notice and no cancel.
`performSyncWithProgress` aborts the whole batch on the first failure, unlike the update activity which continues."

**Depends on `plans/plugin-tests.md` landing first.** This plan is written against the layout that plan introduces:

- Section C: `plugin/src/main/kotlin/com/projectjuggler/plugin/services/SyncProjects.kt` with
  `SyncProgressSink` and `syncProjects(launcher, projects, syncType, sink)`, which propagates the first exception;
  `performSyncWithProgress` in `actions/Sync.kt` keeps the `Task.Backgroundable`, the `ProgressIndicator` adapter and
  the notification try/catch.
- Section H: `services/SyncPluginsOnIdeUpdate.kt` with `syncPluginsOnIdeUpdate(repository, currentConfigDir,
  currentBuild, notificationHandler, launcher)` holding the whole activity body (own loop, per-project
  warn-and-continue, `notification.sync.on.update.started/complete/failed`), called by `PluginSyncOnUpdateActivity`
  on `Dispatchers.IO`.
- Tests pinning today's behavior, flipped here: `services/SyncProjectsTest` "first project throws -> second not called,
  exception propagates"; `services/SyncPluginsOnIdeUpdateTest` "new build, on, two isolated + main -> syncProject for
  the two only, started and complete notifications" and "first project throws -> second still synced".

If plugin-tests deviated (see its "Done in" notes), adapt names, not behavior.

Problems: (1) the update path stops and restarts every running isolated IDE right after the main IDE starts, with up to
60 s of shutdown wait per project, no progress UI and no cancel; the user only sees a balloon. (2) The two batch loops
disagree: the manual "Sync ... (all projects)" stops at the first failing project and reports only that error, the
update loop continues and reports "complete" even when projects failed (failures only reach `idea.log`). (3) There are
two loops doing the same thing, against the `CLAUDE.md` deduplication rule.

Decisions:

- **One batch loop.** `syncProjects` continues past per-project failures and returns a result listing them; both the
  manual actions and the update path use it through `performSyncWithProgress`. The update path loses its own loop.
- **Cancel between projects only.** `checkCanceled()` stays before each project. It is not called from the
  `SyncProgress.Stopping` callback: cancelling there would make core's `finally` delete `stop-request.json` while the
  target may already be exiting, leaving it stopped and never restarted. The 60 s wait per project is bounded.

Decision (made with the user, 2026-10-30): when the build changed and targets exist, post a balloon with an expiring
"Sync plugins" action that runs the sync as a cancellable background task with progress; ignoring it skips this update
(the build number is already stored), and the toggle becomes an offer, its label renamed to "Offer plugin sync on IDE
update", because stopping other running IDE windows must not happen without consent. Rejected: starting the cancellable
task immediately (still stops IDEs unasked), and syncing stopped projects at once while asking only before stopping
running ones (two code paths, two notifications).

Decision (made with the user, 2026-10-30): both the manual and the update path continue past per-project failures and
report one error balloon "N of M projects failed: <name>: <message>; ...", with the success balloon only when none
failed, because one bad project must not block the others and both paths should behave the same. Rejected: keeping
abort-on-first-failure for the manual path (the two keep differing), and aborting in both (one bad project blocks every
other project's plugin update).

Related, overlaps with sibling plans (whichever lands second merges):
`plans/plugin-gap-03-bundle-strings-and-catches.md` also edits `performSyncWithProgress` (`errorMessage: (Throwable) ->
String`, `catch (t: Throwable)`, a `userMessage()` helper in `Utils.kt`) and the `SyncPluginsOnIdeUpdateTest` failure
cases; keep this plan's `(String) -> String` detail callback and use `userMessage()` in place of `message ?:
javaClass.simpleName` below. `plans/plugin-gap-09-ide-path-drift.md` adds `showNotification(message, project, type,
vararg actions: AnAction)` to `Utils.kt`; reuse it instead of the list parameter below if it exists, and add it in that
shape if this plan lands first. `plans/plugin-gap-14-edt-io-and-koin-init.md` and
`plans/plugin-gap-11-sync-helper-extraction.md` move the sync actions' planning and `performSelfShutdownSync` off EDT;
neither changes `performSyncWithProgress`'s body. `plans/plugin-gap-15-settings-and-health-check.md` shows the same
`syncPluginsOnIdeUpdate` setting as a checkbox labelled by `settings.sync.plugins.on.update`; its value must read "Offer
plugin sync on IDE update" like the renamed toggle (set it here if gap 15 landed first, otherwise gap 15 copies this
text). New strings go to the bundle. Plugin gap 4 (catch `Exception` in `performSelfShutdownSync`) is a different
function. `docs/sync-helper-spec.md`'s own `--all-projects` loop is out of scope. Core is unchanged.

## Target behavior

- Manual sync of 3 projects, 2nd throws: before, 3rd not synced and the error balloon has the 2nd's message; after, 3rd
  synced and one error balloon `notification.error.sync.projects.failed` with "1 of 3 projects failed: p2: <msg>".
- Manual sync of 3 projects, none throws: success balloon, unchanged.
- Single-project sync throws: `notification.error.sync.settings.failed` with the message, unchanged.
- Cancel in the progress UI: stops before the next project, no balloon, unchanged (`ProcessCanceledException`
  propagates).
- Main IDE opens after an update, setting on, targets exist: before, balloon "Syncing plugins..." and an invisible IO
  loop that stops and restarts IDEs; after, balloon `notification.sync.on.update.available` with the action "Sync
  plugins", nothing stopped until it is clicked.
- User clicks "Sync plugins": cancellable `Task.Backgroundable` `progress.sync.on.update` over the captured targets,
  plugins only; result balloon `notification.sync.on.update.complete` or `.failed` with the failure summary; the action
  expires.
- User ignores or closes the balloon: no sync for this build; the next IDE update offers again.
- Update path, first run / same build / setting off / no targets / isolated instance: no sync and no balloon, unchanged.

## Implementation

All under `plugin/src/main/kotlin/com/projectjuggler/plugin/`.

### `services/SyncProjects.kt`

```kotlin
internal data class SyncFailure(val project: ProjectMetadata, val error: Throwable)
internal data class SyncBatchResult(val synced: List<ProjectMetadata>, val failures: List<SyncFailure>) {
    val succeeded: Boolean get() = failures.isEmpty()
}
internal fun syncProjects(launcher, projects, syncType, sink): SyncBatchResult
/** One failure -> its message; several -> `sync.failure.summary` with "<name>: <message>" joined by "; ". */
internal fun describeFailures(result: SyncBatchResult, total: Int): String
```

- Per project: `sink.checkCanceled()`, text and fraction as today, then `try { launcher.syncProject(...) } catch (t:
  Throwable) { if (t is ControlFlowException) throw t; LOG.warn(...); failures += SyncFailure(project, t) }`.
  `checkCanceled()` stays outside the `try`.
- The message of a failure is `error.message ?: error.javaClass.simpleName` (no new hardcoded "Unknown error").

### `actions/Sync.kt`, `performSyncWithProgress`

New signature:

```kotlin
fun performSyncWithProgress(project: Project?, projects: List<ProjectMetadata>, syncType: SyncType,
                            taskTitle: String, successMessage: (List<ProjectMetadata>) -> String,
                            errorMessage: (String) -> String)
```

`run`: `val result = syncProjects(...)`; `succeeded` -> `showInfoNotification(successMessage(result.synced))`, else
`showErrorNotification(errorMessage(describeFailures(result, projects.size)))`. The outer catch becomes `Throwable`
(rethrow `ControlFlowException`) and reports `errorMessage(t.message ?: t.javaClass.simpleName)` for failures outside a
project (e.g. resolving the launcher). Callers `SyncAllProjectsAction` and `SyncSettingsSubAction` pass
`{ detail -> message("notification.error.sync.projects.failed", detail) }` and
`{ detail -> message("notification.error.sync.settings.failed", detail) }`.

### `services/SyncPluginsOnIdeUpdate.kt`

Replace `syncPluginsOnIdeUpdate(...)` with a decision function, no launcher, no loop:

```kotlin
/** Stores the current build; returns the projects to offer a plugin sync for (empty when nothing to do). */
internal fun pluginSyncTargetsAfterUpdate(repository: IdeConfigRepository, currentConfigDir: Path,
                                          currentBuild: String, notificationHandler: NotificationHandler,
): List<ProjectMetadata>
```

Same checks and order as today (main instance, store build, previous build, setting, `listSyncable()`); the outer
`Throwable` catch keeps `notification.sync.on.update.failed` + logged error and returns `emptyList()`.

### `startup/PluginSyncOnUpdateActivity.kt`

```kotlin
val targets = withContext(Dispatchers.IO) {
    pluginSyncTargetsAfterUpdate(repo, PathManager.getConfigDir(), build, handler)
}
if (targets.isNotEmpty()) offerPluginSync(project, build, targets)
```

`offerPluginSync` (same file, UI shell): `showInfoNotification(message("notification.sync.on.update.available", build,
targets.size), project, actions = listOf(NotificationAction.createSimpleExpiring(message(
"notification.sync.on.update.action")) { performSyncWithProgress(project, targets, SyncType.Plugins,
message("progress.sync.on.update"), { message("notification.sync.on.update.complete", it.size) },
{ message("notification.sync.on.update.failed", it) }) }))`.

### `Utils.kt`

`showNotification` / `showInfoNotification` gain `actions: List<AnAction> = emptyList()`, applied with
`Notification.addActions(actions)` before `notify`. Existing callers compile unchanged.

### Bundle (`plugin/src/main/resources/messages/ProjectJugglerBundle.properties`)

- Add `sync.failure.summary={0} of {1} projects failed: {2}`,
  `notification.sync.on.update.available` = "IDE updated to {0}. Sync plugins to {1} isolated project(s)? Running ones
  are restarted.",
  `notification.sync.on.update.action=Sync plugins`, `progress.sync.on.update=Syncing plugins after IDE update`.
- Delete `notification.sync.on.update.started` (grep first).
- `popup.toggle.sync.on.update.enabled/disabled` values become "\u2713 Offer plugin sync on IDE update" / "Offer
  plugin sync on IDE update" (keys unchanged, so `ToggleSyncOnUpdateAction` and `RecentProjectPopupBuilderTest` need
  no code change; that test reads the text from the bundle). Gap 15's `settings.sync.plugins.on.update` uses the same
  "Offer plugin sync on IDE update" text.

### Platform APIs (IntelliJ 2025.1.1, stable in 251)

In `~/.gradle/caches/9.2.1/transforms/8403799d391fc2cc745f50c5e712a457/transformed/ideaIU-2025.1.1-aarch64/lib/`:
`com.intellij.notification.NotificationAction.createSimpleExpiring(String, Runnable)` and
`com.intellij.notification.Notification.addActions(Collection)` in `app-client.jar`;
`com.intellij.openapi.progress.Task.Backgroundable` in `util-8.jar` (already used). `withBackgroundProgress`
(`com.intellij.platform.ide.progress.TasksKt`, `app-client.jar`) is not used: it needs a non-null `Project` and the
manual path has `Project?`; keeping one `Task.Backgroundable` path serves both callers.

## Tests

Under `plugin/src/test/kotlin/com/projectjuggler/plugin/services/`, Kotest `StringSpec`, `KoinTestExtension()`,
`createTempDir`, messages via `ProjectJugglerBundle.message`.

- **`SyncProjectsTest`** (flip): "first project throws -> second not called, exception propagates" becomes "first
  project throws -> second still synced; result has `synced == [p2]` and one `SyncFailure(p1, ex)`". Add: a
  `ProcessCanceledException` thrown by `syncProject` propagates and the second is not called; `checkCanceled()`
  throwing before the second project propagates with `p1` synced (already pinned for the first; extend). Add
  `describeFailures`: one failure -> its message; message null -> exception simple name; two of three ->
  `sync.failure.summary` with `"p1: a; p3: b"`.
- **`SyncPluginsOnIdeUpdateTest`** (rename to `PluginSyncTargetsAfterUpdateTest`, flip): the no-op cases keep their
  assertions and now also assert `emptyList()` and no notifications; "new build, on, two isolated + main" returns the
  two metadata and posts no notification and makes no `syncProject` call (no launcher at all); drop "first project
  throws -> second still synced" (covered by `SyncProjectsTest`); `repo.baseDir` a regular file ->
  `notification.sync.on.update.failed` and `emptyList()`.

Not automated: the balloon action and the progress task (UI). Manual check: `./gradlew :plugin:runIde`, set
`lastKnownBuildNumber` in `<base>/v2/<ideDir>/config.json` to an older value, enable the toggle, have two tracked
isolated projects with one running; restart the sandbox IDE; the balloon appears and nothing is stopped; click "Sync
plugins": progress shows, the running IDE restarts; cancel during the second project's sync on a second attempt and
see no further projects synced. For the batch: make one project fail (e.g. hold its
`signals/sync-in-progress.lock` with `flock` or run two syncs) and run "Sync Plugins (all projects)"; the others sync
and one error balloon names the failed project.

## Docs

`docs/plugin-spec.md`:

- §6.4: the loop continues past per-project failures (logged as warnings) and returns `SyncBatchResult`; success
  balloon only when none failed, else one error balloon with `describeFailures`; cancel is checked between projects only
  and why not during the stop wait.
- §11: steps 3-4 become `pluginSyncTargetsAfterUpdate` (no sync), then the `notification.sync.on.update.available`
  balloon with the expiring "Sync plugins" action that runs §6.4 with `SyncType.Plugins`; drop "Running isolated IDEs
  are stopped and restarted without a prompt"; note that ignoring the balloon skips that build.
- §5.4 table: the toggle label text.
- §12 threading table: replace "`PluginSyncOnUpdateActivity` sync loop | `Dispatchers.IO`" with
  "`PluginSyncOnUpdateActivity` checks | `Dispatchers.IO`; offered sync | `Task.Backgroundable`".
- Known gaps: delete item 13 (keep other numbers). §13 tests table (from plugin-tests): rename the update spec row.

## Sessions

1. Single session, Fable 5.1 at default effort, ~20% context. Reads: this plan, `CLAUDE.md`, `actions/Sync.kt`,
   `services/SyncProjects.kt`, `services/SyncPluginsOnIdeUpdate.kt`, `startup/PluginSyncOnUpdateActivity.kt`,
   `actions/recent/SyncAllProjectsAction.kt`, `SyncSettingsSubAction.kt`, `Utils.kt`, the two test specs, spec §6.4,
   §11, §12. Implements everything above, runs Verification, does Finishing. Done when `:plugin:test` passes and the
   manual check succeeds.

## Verification

1. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.services.SyncProjectsTest"`
   `./gradlew :plugin:test --tests "com.projectjuggler.plugin.services.PluginSyncTargetsAfterUpdateTest"`
2. `./gradlew :plugin:test`
3. `./gradlew :plugin:verifyPlugin`
4. `./gradlew build`
5. Manual check from the Tests section.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/plugin-gap-13-sync-batch-consent.md` in the same commit.
- `git status` / `git diff`; stage only the touched files by path (never `git add -A`/`-a`). Commit in the style of
  recent commits (lowercase imperative subject, body with the two behavior changes and why, ending with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested subject:
  `offer plugin sync after ide update and keep sync batches going past failures`. List anything left uncommitted in
  the reply.
