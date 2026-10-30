# Write the real timeout and sync types into `stop-request.json`

Core-spec known gap 2.

## Problem

`ShutdownSignalManager.createStopRequest(project, autoRestart, syncTypes)` hardcodes `timeoutSeconds = 60`, and
`ProjectLauncher.requestShutdownAndWait` always passes `syncTypes = ["settings"]` with the comment "We don't have
access to the boolean flags here". `SyncOptions.shutdownTimeout` is used only for the local `ShutdownWaiter` call, so
a sync started with `shutdownTimeout = 120` writes `"timeoutSeconds": 60` to disk, and a vmoptions-only sync is
indistinguishable from a full one when a user inspects the signal or reads the plugin log line
`Received stop request: <id> (autoRestart=...)`.

The receiving side (`plugin/.../shutdown/ShutdownSignalService.kt`) reads only `requestId`, `timestamp` and
`autoRestart` today, so this is a diagnostics fix, but the file is the protocol between two processes and should not
lie.

Decision (with the user): `createStopRequest` gains a `timeoutSeconds: Int` parameter; `syncProject` derives
`syncTypes` from its three booleans using the names `"vmoptions"`, `"config"`, `"plugins"` (the same words the
sync-helper CLI flags use) and threads them through `doWithLock` to `requestShutdownAndWait`.

## Target behavior

| Call | `syncTypes` written | `timeoutSeconds` written |
|---|---|---|
| `syncProject(p, true, false, false, SyncOptions(stopIfRunning = true))` | `["vmoptions"]` | 60 (default) |
| `syncProject(p, false, true, true, SyncOptions(stopIfRunning = true, shutdownTimeout = 120))` | `["config", "plugins"]` | 120 |
| `syncProject(p, true, true, true, SyncOptions.STOP_AND_RESTART)` | `["vmoptions", "config", "plugins"]` | 60 |
| `createStopRequest(p, autoRestart = false, syncTypes = listOf("config"), timeoutSeconds = 15)` | `["config"]` | 15 |

Order is always vmoptions, config, plugins. The `StopRequestSignal` JSON shape is unchanged, so old and new
plugin/sync-helper builds stay compatible.

## Implementation

`core/src/main/kotlin/com/projectjuggler/core/ShutdownSignalManager.kt`:

- `createStopRequest(project: ProjectMetadata, autoRestart: Boolean, syncTypes: List<String>, timeoutSeconds: Int)`;
  set `timeoutSeconds = timeoutSeconds` in the `StopRequestSignal`. No default value, so every caller states it.

`core/src/main/kotlin/com/projectjuggler/process/ProjectLauncher.kt`:

- In `syncProject`, build `val syncTypes = buildList { if (syncVmOptions) add("vmoptions"); if (syncConfig)
  add("config"); if (syncPlugins) add("plugins") }` and pass it: `doWithLock(project, options, syncTypes) { ... }`.
- `private fun doWithLock(project, options, syncTypes: List<String>, block)` forwards `syncTypes` to
  `requestShutdownAndWait(project, options, syncTypes, signalManager)`.
- `requestShutdownAndWait` drops the local `mutableListOf("settings")` and its comment, and calls
  `signalManager.createStopRequest(project, options.autoRestart, syncTypes, options.shutdownTimeout)`.
- Put the three type names in a small `internal object SyncTypeNames { const val VM_OPTIONS = "vmoptions"; ... }` next
  to `SyncOptions` in `core/src/main/kotlin/com/projectjuggler/core/SyncOptions.kt` so tests reference the constants.

Related plans: `plans/core-gap-01-sync-lock-cleanup.md` edits other methods of `ShutdownSignalManager`;
`plans/core-gap-03-restart-via-launch.md` edits the restart branch of `doWithLock`. All three are independent; if
another one landed first, keep its changes and add the `syncTypes` parameter alongside.

## Tests

`core/src/test/kotlin/com/projectjuggler/core/ShutdownSignalManagerTest.kt`:

- Update the three existing `createStopRequest` calls to pass `timeoutSeconds` (e.g. 60).
- New case "should persist the given timeout": `createStopRequest(project, true, listOf("config"), 15)` then
  `readStopRequest(project)!!.timeoutSeconds shouldBe 15`.

`core/src/test/kotlin/com/projectjuggler/process/ProjectLauncherSyncTest.kt` (mocks `ProjectLockUtils` and
`ShutdownWaiter` with `mockkObject`; `ShutdownWaiter.waitForShutdown` is stubbed, so the stop request file is still on
disk when the stub returns):

- New case "should write sync types and timeout into the stop request": project running
  (`isProjectOpen` returns `true andThen false`), stub `ShutdownWaiter.waitForShutdown(...)` with an `answers` block
  that reads `<projRoot>/signals/stop-request.json` through `ShutdownSignalManager.getInstance(repo).readStopRequest`
  and stores it in a local `var`, then returns `ShutdownResult.Success`. Call
  `syncProject(project, false, true, true, SyncOptions(stopIfRunning = true, shutdownTimeout = 120))`. Assert the
  captured signal has `syncTypes == listOf("config", "plugins")`, `timeoutSeconds == 120`, `autoRestart == false`.
  (Capturing inside the stub is required because `doWithLock` deletes the signal in `finally`.)
- New case for a vmoptions-only sync asserting `syncTypes == listOf("vmoptions")`.

## Docs

`docs/core-spec.md`:

- §8 step 3: the JSON example becomes `"syncTypes": ["vmoptions", "config"]` with the sentence "`syncTypes` lists the
  requested parts in the order vmoptions, config, plugins; `timeoutSeconds` is `SyncOptions.shutdownTimeout`." Replace
  `"timeoutSeconds": 60` with `"timeoutSeconds": <shutdownTimeout>`.
- Known gaps: delete item 2.

`docs/plugin-spec.md` line 178 says `autoRestart` and `syncTypes` are logged but unused; still true, leave it.

## Sessions

1. Single session (Fable 5.1, default effort), ~10% context: read this plan, `ShutdownSignalManager.kt`,
   `ProjectLauncher.kt`, `SyncOptions.kt`, the two test files; implement, test, update the spec, finish.

## Verification

1. `./gradlew :core:test --tests "com.projectjuggler.core.ShutdownSignalManagerTest"` and
   `./gradlew :core:test --tests "com.projectjuggler.process.ProjectLauncherSyncTest"`
2. `./gradlew :core:test`
3. `./gradlew :plugin:compileKotlin` (the plugin calls `readStopRequest` and `cleanupStaleSignals` only; no caller of
   `createStopRequest` outside core, confirmed by grep)
4. `./gradlew :sync-helper:build`
5. Manual: run a stop-and-sync of a running isolated project with only "config" selected and read
   `signals/stop-request.json` before the IDE exits; it shows `"syncTypes": ["config"]`.

## Finishing

- Apply the Docs section; no `CLAUDE.md` change.
- Delete `plans/core-gap-02-stop-request-fields.md` in the same commit.
- Check `git status`/`git diff`; stage only the changed files by path. Commit in the style of recent commits:
  lowercase imperative subject (e.g. `record requested sync types and timeout in the stop request`), body, and
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. List anything left uncommitted in the reply.
