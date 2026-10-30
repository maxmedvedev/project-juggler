# Start stop-request monitoring at application start and honor timeoutSeconds (plugin-spec gap 12)

## Context

`docs/plugin-spec.md`, Known gaps 12: "`ShutdownSignalService` starts only after a project is opened; an isolated IDE
sitting on the welcome screen ignores stop requests, so stop-and-sync times out against it. The JSON
`autoRestart`/`timeoutSeconds` fields are not honored by the receiver."

**Depends on `plans/plugin-tests.md` landing first.** After it, `ShutdownSignalService`
(`plugin/src/main/kotlin/com/projectjuggler/plugin/shutdown/ShutdownSignalService.kt`) gets the project via
`IsolatedInstance.currentProjectId()?.let { loadProjectMetadata(it) }` and delegates "act on this signal?" to
`StopRequestTracker.shouldAct(signal)` (`shutdown/StopRequestTracker.kt`, constructor
`(maxAge: Duration = 5.minutes, now: () -> Instant = Instant::now)`), pinned by `shutdown/StopRequestTrackerTest`
("6-minute-old -> false", "exactly 5 minutes -> true"). This plan changes that rule and flips those cases.

Today: the only trigger is `ShutdownSignalServiceStartupActivity` (`ProjectActivity`, `postStartupActivity` in
`plugin.xml`). An isolated IDE whose project was closed, failed to open, or was started without reopening one still
holds `<projRoot>/config/.lock`, so `ProjectLauncher.syncProject` (core-spec §8 step 3) writes `stop-request.json` and
waits `shutdownTimeout` (60 s) for an exit that never comes. The service constructor also does Koin init and file I/O
on whatever thread first touches the service.

Since commit 940f11b the requester writes the real `timeoutSeconds` (`SyncOptions.shutdownTimeout`) and `syncTypes`;
the receiver still uses a fixed 5-minute window. After the requester's timeout it gives up and step 6 deletes the
file, but in between a late poll (e.g. right after a slow IDE start) would close an IDE that nobody will restart.

Startup hook, checked in `ideaIU-2025.1.1-sources.jar` (Gradle cache
`modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIU/2025.1.1/.../ideaIU-2025.1.1-sources.jar`):

- `com.intellij.ide.AppLifecycleListener.appFrameCreated(commandLineArgs)`
  (`com/intellij/ide/AppLifecycleListener.java`) is public and non-experimental; `com/intellij/idea/IdeStarter.kt`
  (`openProjectIfNeeded`) calls it on every start before deciding between opening a project and the welcome frame
  (also headless). Registered as an
  `<applicationListeners>` lazy listener with `topic="com.intellij.ide.AppLifecycleListener"` (same form as bundled
  `clouds-kubernetes.jar` `KubernetesAppLifecycleListener`). **Chosen.**
- Rejected: `AppLifecycleListener.appStarted()` is `@ApiStatus.Internal`;
  `com.intellij.ide.ApplicationInitializedListener` is `@ApiStatus.Internal`; `com.intellij.ide.ApplicationActivity`
  is `@ApiStatus.Internal` and allowlist-only;
  `welcomeScreenDisplayed()` alone misses "project closed later" (the service must already run by then anyway).
- `appFrameCreated` does not fire for a plugin loaded dynamically into a running IDE, so the existing `ProjectActivity`
  stays as a fallback; both call an idempotent `start()`.

Decision (made with the user, 2026-10-30): `autoRestart` is informational (logged, documented), because restarting
is the requester's job: it holds the sync lock and relaunches with the new settings (core-spec §8 step 5). Rejected: a
balloon before exit (barely visible, delays the exit); `Application.restart()` (races the requester's relaunch and
restarts before the sync copied anything).

Decision (made with the user, 2026-10-30): skip the exit confirmation with
`ApplicationManagerEx.getApplicationEx().exit(/* force = */ false, /* exitConfirmed = */ true)`, because an unattended
IDE would otherwise sit on the "Confirm exit" dialog (on by default) until the requester times out; settings are still
saved (`SAVE` flag) and IntelliJ still asks when background tasks run. Neither `ApplicationEx.exit(boolean, boolean)`
nor `ApplicationManagerEx.getApplicationEx()` is annotated internal (`com/intellij/openapi/application/ex/
ApplicationEx.java`, `ApplicationManagerEx.java`). Rejected: keeping `Application.exit()` (an unattended IDE blocks
the sync for the full timeout).

`timeoutSeconds` decision (follows from its meaning, no question): a request is acted on while its age is at most
`timeoutSeconds`; `timeoutSeconds <= 0` (malformed or hand-written) falls back to 5 minutes. Older requesters always
wrote 60, so they keep working. `cleanupStaleSignals(project, maxAgeMinutes = 5)` at start is unchanged.

Related, not changed: plugin-spec gap 9 (`autoPopulateIfNeeded` may record isolated dirs as base paths when an
isolated instance touches the repository first) now triggers at app start instead of first project open, same
outcome. `plans/plugin-gap-02-windows-isolated-detection.md` is needed for any of this to work on Windows; the two
plans touch different files and land in any order.

Overlaps (whichever lands second merges):
- `plans/plugin-gap-03-bundle-strings-and-catches.md` turns the metadata-load and polling catches in
  `ShutdownSignalService` into `catch (t: Throwable)` + `t.rethrowIfControlFlow()`. Use that helper here if it exists;
  the catches this plan writes follow the same rule either way.
- `plans/plugin-gap-14-edt-io-and-koin-init.md` deletes every `KoinInit.init(pluginModule)` call in favor of
  `ensurePluginKoin()` inside `IdeInstallationService.getInstance()`. If it lands first, the `start()` coroutine has
  no `KoinInit.init` line; otherwise gap 14 deletes it there.

## Target behavior

| Situation | Before | After |
|---|---|---|
| isolated IDE on welcome screen receives a stop request | ignored, requester times out | exits within ~1 s |
| isolated IDE whose project was closed | monitoring (started earlier) | same |
| plugin loaded dynamically, then a project opens | starts at project open | same (fallback) |
| main instance / not isolated | monitoring disabled | same, decided at app start |
| request age <= `timeoutSeconds` | acted on if <= 5 min | acted on |
| `timeoutSeconds` < age <= 5 min | acted on | ignored, logged once as stale |
| `timeoutSeconds <= 0`, age <= 5 min | acted on | acted on (fallback) |
| "Confirm exit" setting on | dialog, sync may time out | no dialog; background-task prompt still possible |
| `autoRestart` | logged | logged; documented as informational |
| thread for Koin init + metadata load | first caller of `service<>()` | `Dispatchers.IO` coroutine |

## Implementation

`plugin/src/main/kotlin/com/projectjuggler/plugin/shutdown/ShutdownSignalService.kt`:

- Constructor only stores `scope`. Add `private val started = AtomicBoolean()` and
  `fun start() { if (!started.compareAndSet(false, true)) return; scope.launch { ... } }` (scope already has
  `Dispatchers.IO`). The coroutine body is the former `init`: `KoinInit.init(pluginModule)`, detect the project
  (`IsolatedInstance.currentProjectId()` + metadata, existing logging), then `cleanupStaleSignals` and the polling loop
  in the same coroutine (fold `startMonitoring` into it; `pollingJob` goes away). Wrap detection in
  `try/catch (Throwable)` with `ControlFlowException`/`CancellationException` rethrown, per the project rule.
- `initiateGracefulShutdown(signal)`: log `requestId`, `autoRestart`, `syncTypes`, `timeoutSeconds`, then
  `ApplicationManagerEx.getApplicationEx().exit(false, true)` (see the exit-confirmation decision).
- Class KDoc: started from `ShutdownSignalAppListener` at app start, `ProjectActivity` as fallback; `autoRestart` and
  `syncTypes` are informational.

`plugin/src/main/kotlin/com/projectjuggler/plugin/shutdown/ShutdownSignalAppListener.kt` (new):

```kotlin
internal class ShutdownSignalAppListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        service<ShutdownSignalService>().start()
    }
}
```

`ShutdownSignalServiceStartupActivity.execute`: `service<ShutdownSignalService>().start()`; KDoc "fallback for dynamic
plugin loading".

`plugin/src/main/resources/META-INF/plugin.xml`, after `</extensions>`:

```xml
<applicationListeners>
    <listener class="com.projectjuggler.plugin.shutdown.ShutdownSignalAppListener"
              topic="com.intellij.ide.AppLifecycleListener"/>
</applicationListeners>
```

`plugin/src/main/kotlin/com/projectjuggler/plugin/shutdown/StopRequestTracker.kt`:

```kotlin
internal class StopRequestTracker(
    private val fallbackMaxAge: Duration = 5.minutes,
    private val now: () -> Instant = Instant::now,
) {
    /** True once per request id while its age is at most `timeoutSeconds` (fallback when <= 0). */
    fun shouldAct(signal: StopRequestSignal): Boolean
}
```

`maxAge = if (signal.timeoutSeconds > 0) signal.timeoutSeconds.seconds else fallbackMaxAge`; otherwise unchanged
(stale or unparseable never acted on and never remembered). Log a stale request once per `requestId` (keep a second
small set, or return a reason enum if the service needs to log it; choose the smaller change).

No core change: `StopRequestSignal` already carries both fields.

## Tests

`plugin/src/test/kotlin/com/projectjuggler/plugin/shutdown/StopRequestTrackerTest.kt` (from plugin-tests), fixed `now`:

- Flip: "6-minute-old -> false" becomes "older than timeoutSeconds -> false" (`timeoutSeconds = 60`, age 61 s);
  "exactly 5 minutes -> true" becomes "age == timeoutSeconds -> true" (60 s).
- Add: `timeoutSeconds = 60`, age 4 min -> false (was true); `timeoutSeconds = 0`, age 4 min -> true and 6 min -> false
  (fallback); `timeoutSeconds = 600`, age 9 min -> true; a request rejected as stale
  is still rejected on the next poll.
- Keep: same id twice -> second false; `"garbage"` timestamp -> false.

New `plugin/src/test/kotlin/com/projectjuggler/plugin/shutdown/ShutdownSignalRegistrationTest.kt` (pure, no
application): read `/META-INF/plugin.xml` from the classpath and assert it declares a listener with class
`ShutdownSignalAppListener::class.java.name` and topic `AppLifecycleListener::class.java.name` (guards against a
rename silently dropping the start hook).

Not automated (as in plugin-tests): the polling loop, `start()` and `exit`. Manual check:

1. `./gradlew :plugin:runIde`; from the sandbox main instance open an isolated project; in the isolated IDE close the
   project (File > Close Project) so it shows the welcome screen.
2. From the main instance: Recent Projects -> that project -> Sync Plugins. The isolated IDE must exit within a few
   seconds without a "Confirm exit" dialog and come back with the project; `idea.log` of the isolated instance
   (`<projRoot>/logs`) shows "Received stop request ..." with the four fields.
3. Hand-write `<projRoot>/signals/stop-request.json` with a timestamp 2 minutes old and `"timeoutSeconds": 60` while
   the isolated IDE runs: it must stay open and log the request as stale.

## Docs

`docs/plugin-spec.md`:
- §1 registered extensions: add the `applicationListeners` entry; the `postStartupActivity` remains.
- §10: first bullet: `ShutdownSignalAppListener.appFrameCreated` calls `ShutdownSignalService.start()` at app start;
  the `ProjectActivity` calls it too (fallback for dynamic loading); `start()` is idempotent and does Koin init,
  detection and polling in one `Dispatchers.IO` coroutine. Third bullet: acted on while age <= `timeoutSeconds`
  (5 minutes when <= 0); exit via `ApplicationEx.exit(force = false, exitConfirmed = true)`; `autoRestart` and
  `syncTypes` are logged and informational, restart is the requester's job.
- §12 table: "`ShutdownSignalService` start and polling | `Dispatchers.IO` coroutine, from app start".
- Known gaps: delete item 12.

`docs/core-spec.md` §8.1: append "The plugin acts on a request only while its age is at most `timeoutSeconds`." No
other core doc change.

## Sessions

1. Single session, Fable 5.1 at default effort, ~15% context. Reads: this plan, `CLAUDE.md`, `shutdown/*.kt`,
   `StopRequestTrackerTest.kt`, `plugin.xml`, plugin-spec §1/§10/§12, core-spec §8. Implements, tests, manual check,
   docs, Finishing. Done when `:plugin:test` passes, `verifyPlugin` reports no new internal API use and gap 12 is gone.

## Verification

1. `./gradlew :plugin:test --tests "com.projectjuggler.plugin.shutdown.*"`
2. `./gradlew :plugin:test`
3. `./gradlew :plugin:verifyPlugin` (must not list `AppLifecycleListener`/`ApplicationEx` as internal or experimental)
4. `./gradlew :plugin:buildPlugin`
5. Manual check from Tests.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/plugin-gap-12-shutdown-service-startup.md` in the same commit.
- `git status` / `git diff`; stage by path only (the three `shutdown/` sources, the new listener, `plugin.xml`, both
  tests, both specs, this plan). Commit in the style of recent commits (lowercase imperative subject, body with the
  why, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`). Suggested subject:
  `watch for stop requests from application start and honor their timeout`. Report anything left uncommitted.
