# Restart a synced project through `ProjectLauncher.launch`

Core-spec known gap 3.

## Problem

After a stop-and-sync, `ProjectLauncher.doWithLock`
(`core/src/main/kotlin/com/projectjuggler/process/ProjectLauncher.kt`) restarts the IDE with
`intellijLauncher.launch(project)`, bypassing `ProjectLauncher.launch`. Consequences:

- `ProjectManager.registerOrUpdate` is skipped, so `lastOpened`/`openCount` in `metadata.json` are not updated and the
  project does not move to the head of `recent.json` (`RecentProjectsIndex.recordOpen`), although the user sees the IDE
  reopen.
- `BaseVMOptionsTracker.hasChanged()/updateHash()` is skipped.
- No `onFailure` callback is passed, so an IDE that crashes within 10 s of the restart (`ProcessLauncherImpl`'s watch
  thread) is reported nowhere; the caller only ever sees `SyncProgress.Restarting` and then nothing.

Since commit c9fa25d `syncProject` rejects the main project with a `SyncException` before taking the lock, so the
restart branch only ever runs for isolated projects; the main-project half of the original gap no longer applies.

Decision (with the user): the restart calls
`launch(project.path) { failure -> options.onProgress(SyncProgress.Error(..)) }` so the normal launch bookkeeping
runs and a crash surfaces as a progress error. A restart counts as an open; that is
acceptable because the user does see a new IDE window.

## Target behavior

| Case | Before | After |
|---|---|---|
| Stop-and-sync with `autoRestart`, IDE restarts fine | `Restarting`; metadata and recents untouched | `Restarting`; `openCount + 1`, `lastOpened` refreshed, project at head of `recent.json` |
| Restart process exits non-zero within 10 s | silent | `SyncProgress.Error("IntelliJ exited with code <n> after restart of '<name>': <stderr or stdout tail>")` |
| `launch` throws (e.g. missing executable) | `Error("Failed to restart IntelliJ: ...")` | unchanged |
| `autoRestart = false` or project was not running | no launch | unchanged |

The sync itself still succeeds in every restart-failure case (errors are reported, not thrown).

## Implementation

`core/src/main/kotlin/com/projectjuggler/process/ProjectLauncher.kt`, `doWithLock`, restart branch:

```kotlin
options.onProgress(SyncProgress.Restarting)
try {
    launch(project.path) { failure ->
        val output = failure.stderr.ifBlank { failure.stdout }.trim().take(500)
        options.onProgress(SyncProgress.Error(
            "IntelliJ exited with code ${failure.exitCode} after restart of '${project.name}'" +
                if (output.isBlank()) "" else ": $output"))
    }
} catch (e: Throwable) {
    options.onProgress(SyncProgress.Error("Failed to restart IntelliJ: ${e.message}"))
}
```

Reuse: `launch(projectPath, onFailure)` already exists in the same class; `LaunchFailure(exitCode, stdout, stderr)` is
in `ProcessLauncher.kt`. The plugin's `services/LaunchProject.kt` formats a crash the same way (stderr, else stdout,
`take(500)`); extract that formatting into core as `fun LaunchFailure.describe(): String` in `ProcessLauncher.kt` and
use it in both places, keeping the plugin's bundle message for the notification title.

Note: the `onFailure` callback runs on `ProcessLauncherImpl`'s watch thread, so `options.onProgress` may now be called
after `syncProject` has returned. `SyncOptions.onProgress` consumers (plugin progress indicator, sync-helper stdout)
must tolerate a late `Error`; document this in the spec.

Related plans: `plans/core-gap-02-stop-request-fields.md` changes `doWithLock`'s signature (adds `syncTypes`);
`plans/core-gap-13-process-launcher.md` changes how `LaunchFailure` output is captured. Both are independent of this
change.

## Tests

`core/src/test/kotlin/com/projectjuggler/process/ProjectLauncherSyncTest.kt`. The mock `ProcessLauncher` is verified
today with `launch(any(), any(), any())`; MockK expands the omitted default parameter to `eq(null)`, so once the restart
passes a non-null `onFailure` these verifications no longer match. Change every `mockProcessLauncher.launch(any(),
any(), any())` in `verify`/`every` to the four-argument form `launch(any(), any(), any(), any())`.

New cases:

- "restart after sync records the open": register the project, note `openCount` and write a second project into
  recents so the target is not at the head; run a stop-and-sync (`isProjectOpen` returns `true andThen false`,
  `waitForShutdown` returns `Success`). Assert `projectManager.get(projectPath)!!.openCount` increased by one and
  `RecentProjectsIndex.getInstance(repo).getRecent(10).first().path == projectPath`.
- "restart crash is reported as a progress error": stub
  `every { mockProcessLauncher.launch(any(), any(), any(), captureLambda()) } answers
  { lambda<(LaunchFailure) -> Unit>().invoke(LaunchFailure(3, "", "boom")) }` and collect `onProgress` events. Assert
  one `SyncProgress.Error` whose message contains `"code 3"` and `"boom"`, and that `syncProject` did not throw.

## Docs

`docs/core-spec.md`:

- §8 step 5: "emit `Restarting` and call `ProjectLauncher.launch(project.path, onFailure)` (metadata, recents and
  vmoptions-hash bookkeeping as in §7.1). A crash within 10 s is reported as `SyncProgress.Error`, possibly after
  `syncProject` has returned; exceptions from `launch` become `SyncProgress.Error` as well. Neither fails the sync."
- §12: add "Restart crashes after a sync surface as `SyncProgress.Error` through `onFailure`."
- Known gaps: delete item 3.

## Sessions

1. Single session (Fable 5.1, default effort), ~12% context: read this plan, `ProjectLauncher.kt`,
   `ProcessLauncher.kt` (`LaunchFailure`), `plugin/.../services/LaunchProject.kt`, `ProjectLauncherSyncTest.kt`;
   implement, test, update the spec, finish.

## Verification

1. `./gradlew :core:test --tests "com.projectjuggler.process.ProjectLauncherSyncTest"`
2. `./gradlew :core:test`
3. `./gradlew :plugin:compileKotlin`
4. `./gradlew :sync-helper:build`
5. Manual: `:plugin:runIde`, open an isolated project A then B, stop-and-sync A from B; after A reopens, the Recent
   Projects popup lists A first and `projects/<A>/metadata.json` shows the incremented `openCount`.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/core-gap-03-restart-via-launch.md` in the same commit.
- Check `git status`/`git diff`; stage only the changed files by path. Commit in the style of recent commits:
  lowercase imperative subject (e.g. `restart synced projects through ProjectLauncher.launch`), body, and
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. List anything left uncommitted in the reply.
