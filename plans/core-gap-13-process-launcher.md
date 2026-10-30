# Core gap 13: capture launch output in log files, daemon watcher thread

## Context

`docs/core-spec.md` "Known gaps" item 13: `ProcessLauncherImpl` captures no output on Unix (inherited IO), reports
the exit code of `cmd start` on Windows, and keeps a CLI JVM alive up to 10 s via a non-daemon thread.

`core/src/main/kotlin/com/projectjuggler/process/ProcessLauncher.kt`:

- `createUnixProcess` calls `inheritIO()`, so the watcher's `process.inputStream.available()` reads are always 0
  and `LaunchFailure.stdout/stderr` are always empty. The plugin (`plugin/.../services/LaunchProject.kt`) then
  shows `notification.error.launch.crashed` with `<No output>`, which is useless when the IDE dies at startup
  (bad `.vmoptions` line, missing JDK, locked config).
- The watcher is an anonymous non-daemon `Thread` that polls for 10 s. In `sync-helper` (a CLI whose `main` returns
  right after the relaunch) this keeps the JVM alive for up to 10 s after the work is done.
- On Windows the process is `cmd /c start "" <exe> <args>`, so the exit code observed is `cmd`'s and the child's
  output is not reachable.

Decisions (planning session with the user):

- **Redirect child stdout/stderr to files, not pipes.** Pipes would need a reader for the lifetime of the IDE or
  the child blocks when the buffer fills; files cost nothing and survive the watcher.
- Windows keeps `cmd /c start` (needed to detach); its exit code/output limitation stays a documented gap.
- Daemon watcher thread with a name.

Related plans: `plans/core-gap-06-launch-main-env.md` changes the `environment` parameter type of
`ProcessLauncher.launch` (null value = remove variable). The changes here are independent of that; whichever is
implemented second merges mechanically.

## Target behavior

| Case (Unix)                        | Result                                                                        |
|------------------------------------|-------------------------------------------------------------------------------|
| child exits non-zero within 10 s   | `onFailure(LaunchFailure(code, stdout, stderr))` with both file tails; kept   |
| child exits 0 within 10 s          | no callback; both log files deleted                                           |
| child still running after 10 s     | no callback; both log files deleted (child keeps writing to unlinked inodes)  |
| log directory not writable         | launch proceeds with `DISCARD` redirects; stderr line logged; output empty    |
| each launch                        | files in the log directory older than 7 days deleted, best effort             |
| watcher thread                     | daemon named `project-juggler-launch-watcher`; CLI JVM exits after `main`   |
| Windows                            | unchanged: `cmd /c start`, no redirect, exit code of `cmd`                    |

## Implementation

### `process/ProcessLauncher.kt` (package `com.projectjuggler.process`)

- `class ProcessLauncherImpl(private val logDirectory: Path = IdeRegistry.getDefaultBaseDir().resolve("launcher-logs"))
  : ProcessLauncher`. `IdeRegistry.getDefaultBaseDir()` is in `config/IdeRegistry.kt` (companion). The interface
  `ProcessLauncher` and `LaunchFailure` are unchanged.
- `launch(...)`:
  1. `pruneOldLogs()` (best effort, `runCatching`): delete regular files in `logDirectory` whose
     `getLastModifiedTime()` is older than 7 days.
  2. Build the `ProcessBuilder` per platform as today. For Unix, replace `inheritIO()` with
     `redirectOutput(stdoutFile.toFile())` and `redirectError(stderrFile.toFile())` where
     `stdoutFile = logDirectory.resolve("launch-<epochMillis>.out")`, `stderrFile = ... ".err"`; create
     `logDirectory` first. If creating the directory or files fails, log to stderr and use
     `ProcessBuilder.Redirect.DISCARD` for both. Stdin: `Redirect.INHERIT` is fine (today's behavior).
  3. `process = processBuilder.start()`.
  4. Start the watcher: `Thread(::watch, "project-juggler-launch-watcher").apply { isDaemon = true; start() }`.
- `watch(process, stdoutFile?, stderrFile?, onFailure)`: `process.waitFor(10, TimeUnit.SECONDS)` instead of the
  100 ms polling loop. Then:
  - exited with code != 0: read `tail(stdoutFile)`, `tail(stderrFile)` (last 64 KiB, UTF-8, empty when the file is
    null or unreadable), log as today, call `onFailure`, keep the files.
  - exited 0 or still alive: `Files.deleteIfExists` both files (ignore errors).
  Wrap the body in `try { } catch (t: Throwable) { System.err.println(...) }` so a watcher failure never kills the
  thread silently.
- Keep `createWindowsProcess` as is (plus the shared env-merge code). Extract `applyEnvironment(builder, env)` used
  by both platform builders (deduplication).
- `di/CoreModule.kt`: `single<ProcessLauncher> { ProcessLauncherImpl() }` stays; the default argument supplies the
  log directory.

### Why `waitFor(timeout)` instead of polling

The polling loop existed only to drain the pipes. With files there is nothing to drain, so a single timed wait is
simpler and uses no CPU.

## Tests

New `core/src/test/kotlin/com/projectjuggler/process/ProcessLauncherImplTest.kt`, Kotest `StringSpec`, temp dir from
`com.projectjuggler.test.createTempDir`. Every test uses `enabledIf = { !Platform.isWindows() }`
(`com.projectjuggler.platform.Platform`) and `Paths.get("/bin/sh")` as the executable with `listOf("-c", script)`.
Wait for the callback with a `CountDownLatch` (timeout 15 s).

- `"should report exit code and output of a process that fails"`: script `echo out-line; echo err-line >&2; exit 3`.
  Expect `LaunchFailure(3, stdout contains "out-line", stderr contains "err-line")`; `.out`/`.err` files remain in
  the log dir.
- `"should not report a process that exits successfully and should remove its logs"`: script `echo ok; exit 0`.
  Sleep ~1 s, assert no callback and the log directory has no `launch-*` files.
- `"should pass environment variables to the child"`: script `test "$PJ_TEST" = yes || exit 7` with
  `environment = mapOf("PJ_TEST" to "yes")`; no callback.
- `"should not block the JVM: watcher thread is a daemon"`: after a launch, find the thread named
  `project-juggler-launch-watcher` in `Thread.getAllStackTraces().keys` and assert `isDaemon`.
- `"should prune log files older than seven days"`: create `launch-old.out` with `setLastModifiedTime` 8 days ago
  and `launch-new.out` now; launch `exit 0`; assert old gone, new present (then the test's own launch cleans itself).
- `"should still launch when the log directory cannot be created"`: construct `ProcessLauncherImpl(logDirectory =
  <path under a regular file>)`; script `exit 2`; expect the callback with exit 2 and empty output.

The "still running after 10 s" branch is not unit tested (it would take 10 s); verify manually.

## Docs

`docs/core-spec.md`:

- §7.3: "macOS/Linux: `<exe> <args>` with stdout/stderr redirected to `<base>/launcher-logs/launch-<millis>.out`
  and `.err`. A daemon thread waits up to 10 s; on non-zero exit it passes the tails of both files to
  `onFailure(LaunchFailure(exitCode, stdout, stderr))` and keeps them; on exit 0 or still running it deletes them.
  Files older than 7 days are pruned on each launch. Windows: `cmd /c start "" <exe> <args>`, no redirect."
- §3 layout tree: add `launcher-logs/` under `<base>/`.
- §12 last bullet: unchanged meaning ("later crashes are not observed").
- "Known gaps" item 13 becomes: "On Windows `ProcessLauncherImpl` reports the exit code of `cmd start` and captures
  no child output."

## Sessions

1. Single session (Fable 5.1, current effort), ~15% of context. Reads: this plan, `CLAUDE.md`,
   `process/ProcessLauncher.kt`, `process/IntelliJLauncher.kt` (caller, unchanged), `di/CoreModule.kt`,
   `config/IdeRegistry.kt` companion, `plugin/.../services/LaunchProject.kt` (consumer, unchanged),
   `test/TempDirExtension.kt`, spec §3, §7.3, "Known gaps". Implements all sections, runs Verification, does
   Finishing. Done when `./gradlew :core:test` is green including the new spec and the plugin compiles.

## Verification

1. `./gradlew :core:test --tests "com.projectjuggler.process.ProcessLauncherImplTest"`
2. `./gradlew :core:test`
3. `./gradlew :plugin:compileKotlin`
4. `./gradlew :sync-helper:build`
5. Manual, failure path: in a scratch `~/.project-juggler-test` setup, point an IDE installation's base vmoptions at a
   file containing an invalid line (e.g. `-Xmx1x`), `./gradlew :plugin:runIde`, open a project isolated; the
   "launch crashed" notification must now include the JVM's error text, and
   `~/.project-juggler-test/launcher-logs/` must hold the matching `.out`/`.err` pair.
6. Manual, success path: open a project that starts normally; after ~10 s `launcher-logs/` must contain no files for
   that launch. Then run `./gradlew :sync-helper:installDist` and invoke the CLI with `--all` on a running project;
   the CLI process must exit right after printing its last line, not 10 s later.

## Finishing

- Apply the Docs changes; `CLAUDE.md` is unchanged.
- Delete `plans/core-gap-13-process-launcher.md` in the same commit.
- `git status` / `git diff` first; stage by path only:
  `core/src/main/kotlin/com/projectjuggler/process/ProcessLauncher.kt`,
  `core/src/test/kotlin/com/projectjuggler/process/ProcessLauncherImplTest.kt`, `docs/core-spec.md`, the deleted
  plan (and `di/CoreModule.kt` only if it changed). Commit like the recent commits: lowercase imperative subject,
  body with what and why, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. List anything
  left out of the commit in the reply.
