# Running detection: stale locks, PID liveness, main-project config path

Fixes known gap 9 of `docs/core-spec.md`.

## Problem

`ProjectLockUtils` (`core/src/main/kotlin/com/projectjuggler/util/ProjectLockUtils.kt`) decides whether an IDE
instance is running by checking that `<configDir>/.lock` exists. Three defects:

1. A stale `.lock` left by a crashed IDE reads as "running". A `stopIfRunning` sync then writes a stop request nobody
   polls and times out after `shutdownTimeout` seconds (`ProjectLauncher.requestShutdownAndWait`).
2. `isProcessRunning(pid)` spawns `tasklist` on Windows and `ps -p` elsewhere and returns `exitCode == 0`. `tasklist`
   exits 0 even when no process matches, so on Windows every PID is "alive". `focusExistingProject`
   (`plugin/.../services/FocusExistingProject.kt`) then calls `WindowFocuser` on a dead PID.
3. For the main project, `isProjectOpen` uses `config.baseConfigPath` and returns false when it is unset, while
   `DirectoryManager.getBaseConfigPath()` falls back to `ConfigLocator`. The object also keeps private copies of the
   main-project comparison and of the `projects/<id>/config` path that already exist in
   `MainProjectService.isMainProject`
   and `DirectoryManager.getProjectRoot(ProjectPath)`.

Decisions made with the user: fix all three in core; use `ProcessHandle` instead of spawning a process. On this
machine (macOS, IntelliJ 2027.1) every isolated `config/.lock` holds a bare PID (e.g.
`~/.project-juggler/v2/<ide>/projects/<id>/config/.lock` contains `95125`, a live `idea` process), so PID liveness is a
sound signal here. Whether other IDE versions or platforms write something else is not verified; the fallback below
keeps
the old behavior in that case.

Callers that stay as they are: `ShutdownWaiter.waitForShutdown`, `ProjectLauncher.doWithLock`,
`ProjectCleaner.cleanStaleMainProjectEntry` (uses `isIsolatedInstanceOpen`), plugin `actions/Launch.kt:84`,
`actions/recent/RecentProjectPopupBuilder.kt:108`, `services/FocusExistingProject.kt`. The main-project branch of
`isProjectOpen` is still reached from the plugin (launch-or-focus, popup "open" marker) even though `syncProject` now
rejects the main project.

## Target behavior

| Case | `isProjectOpen` | `readPidFromLock` | `isProcessRunning` |
|---|---|---|---|
| no `.lock` | false | null | n/a |
| `.lock` with PID of a live process | true | pid | true |
| `.lock` with PID of a dead process | **false** (was true) | pid | false |
| `.lock` whose content is not a bare int | true (existence only) | null | n/a |
| main project, `baseConfigPath` set | lock in that dir | same | same |
| main project, base unset, `ConfigLocator` finds a dir | **lock in detected dir** (was false) | same | same |
| main project, nothing configured or detected | false | null | n/a |
| Windows, dead PID | false | | **false** (was true) |

`ShutdownWaiter` and `doWithLock` need no change: a stale lock now reads as "not running", so no stop request is sent
and the sync proceeds immediately.

## Implementation

`core/src/main/kotlin/com/projectjuggler/util/ProjectLockUtils.kt` (object, public API unchanged):

- Delete the private `isMainProject` and `getIsolatedConfigPath`. Add
  `private fun lockFile(repo, projectPath): Path?`:
  - `MainProjectService.isMainProject(repo, projectPath)` decides the branch.
  - Main: `DirectoryManager.getInstance(repo).getBaseConfigPath()?.resolve(".lock")` (null when nothing is configured or
    detected).
  - Isolated: `DirectoryManager.getInstance(repo).getProjectRoot(projectPath).resolve("config").resolve(".lock")`.
  - `isIsolatedInstanceOpen` uses the isolated path directly (same `DirectoryManager` call), keeping its current
    semantics.
- `private fun isLockLive(lockFile: Path): Boolean`: false if the file is missing or not regular; otherwise read the
  trimmed content; if it parses with `toIntOrNull()` and `isProcessRunning(pid)` is false, return false; else true.
  `isProjectOpen`, `isIsolatedInstanceOpen` and `hasLockFile` route through it (rename `hasLockFile` to `isLockLive`).
- `readPidFromLock` reuses `lockFile(...)` and keeps `readText().trim().toIntOrNull()`.
- `isProcessRunning(pid: Int)`:
  `ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)`, wrapped in `try/catch (e: Throwable)` returning
  false. Remove the `os.name` branch and the `ProcessBuilder` calls.
- Catch blocks stay `Throwable`-based (core has no `ControlFlowException`), returning false/null.

`DirectoryManager.getInstance(repo)` resolves through the Koin IDE scope, which both the plugin and sync-helper have
started (`KoinInit.init`). `ProjectLockUtils` therefore requires a running Koin; all production call sites already have
one. Tests that call the real object must register `KoinTestExtension()`.

Add an import of `com.projectjuggler.config.MainProjectService` and `com.projectjuggler.core.DirectoryManager`; drop
`java.nio.file.Paths` if unused.

Related plans (independent changes to shared files): `plans/core-gap-14-main-instance-detection.md` also edits
`MainProjectService` and `DirectoryManager` (adds `isIsolatedConfigDir`); neither plan depends on the other.

## Tests

New `core/src/test/kotlin/com/projectjuggler/util/ProjectLockUtilsTest.kt` (Kotest `StringSpec`, `KoinTestExtension()`,
`createTempDir` from `test/TempDirExtension.kt`, repository built as
`IdeConfigRepository(tempDir, IdeInstallation("/test/ide", "Test IDE"))`). A "project" is a temp dir; the isolated
config
dir is `DirectoryManager.getInstance(repo).getProjectRoot(path).resolve("config")`, created in the test.

- "isolated project without lock is not open"
- "isolated project with lock holding the current PID is open": write `ProcessHandle.current().pid()` to `.lock`.
- "isolated project with lock holding a dead PID is not open": spawn `ProcessBuilder("true")` (Unix) / use
  `Int.MAX_VALUE - 7` as a PID that `ProcessHandle.of` reports absent; assert false.
- "isolated project with non-numeric lock content is open": content `"port=6942\ntoken"`; assert true.
- "readPidFromLock returns the pid or null": numeric and non-numeric content.
- "main project uses configured base config dir": `MainProjectService.setMainProject`, set `baseConfigPath` via
  `repo.update`, create `.lock` with current PID there; assert true, and false after deleting the lock.
- "main project falls back to detected config dir": `mockkObject(ConfigLocator)`,
  `every { ConfigLocator.findDefaultConfigDirectory() } returns tempDir`; `baseConfigPath` unset; assert true with a
  live
  lock; `unmockkObject` in `afterTest`.
- "main project with no config dir is not open": `ConfigLocator` mocked to return null; assert false, `readPidFromLock`
  null.
- "isProcessRunning": current PID true, absent PID false.

`process/ProjectLauncherSyncTest.kt` mocks `ProjectLockUtils` with `mockkObject` and must keep passing unchanged.

Not unit-testable: Windows `ProcessHandle` behavior. Check by hand on Windows if available: run `FocusExistingProject`
on a project whose IDE was killed; the "process not found" notification must appear instead of a focus attempt.

## Docs

`docs/core-spec.md`:
- §7.4 "Running detection": `.lock` must exist **and**, when its content is an integer, that PID must be alive
  (`ProcessHandle`); main project dir is `DirectoryManager.getBaseConfigPath()` (configured or detected).
- §8 step 3 note: a stale lock no longer triggers a stop request.
- §11 or §7.4: `isProcessRunning` uses `ProcessHandle`, no external command.
- Delete item 9 from "Known gaps"; in item 15 remove "main-project branch of `isProjectOpen`".

## Sessions

1. Single session (Fable 5.1, default effort, ~15% context): read this plan, `CLAUDE.md`, `ProjectLockUtils.kt`,
   `DirectoryManager.kt` (getBaseConfigPath/getProjectRoot), `MainProjectService.kt`, `ProjectLauncherSyncTest.kt`
   lines 75-90 (mock setup), `KoinTestExtension.kt`. Implement, add the test spec, update the spec sections, run
   verification, finish. Done when `:core:test` is green and the plugin compiles.

## Verification

1. `./gradlew :core:test --tests "com.projectjuggler.util.ProjectLockUtilsTest"`
2. `./gradlew :core:test`
3. `./gradlew :plugin:compileKotlin`
4. `./gradlew :sync-helper:build`
5. Manual: with an isolated project running, `cat ~/.project-juggler/v2/<ide>/projects/<id>/config/.lock` shows its
   PID; kill the IDE with `kill -9 <pid>` so the lock stays; in the main IDE run a config sync of that project with
   stop+restart. It must sync immediately (no 60 s wait) and relaunch.

## Finishing

- Fold the decision (PID liveness via `ProcessHandle`, existence fallback for non-numeric locks) into the spec sections
  above; CLAUDE.md needs no change.
- Delete `plans/core-gap-09-running-detection.md` in the same commit.
- `git status` / `git diff`; stage only the touched files by path (`ProjectLockUtils.kt`, the new test, the spec, this
  plan); commit in the repo's style, e.g. subject `treat stale lock files as not running and detect the main config
  dir`,
  a body with what changed and why, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. List any
  files left out of the commit in the reply.
