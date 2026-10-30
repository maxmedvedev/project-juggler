# Core gap 11: one git command runner, no double-wrapped errors, SHA-256 detached heads

## Context

`docs/core-spec.md` "Known gaps" item 11: `GitWorktreeManager` double-wraps its own `IOException` and has no timeout;
`GitUtils` does not treat 64-hex (SHA-256) detached heads as detached.

Today in `core/src/main/kotlin/com/projectjuggler/util/`:

- `GitWorktreeManager.createWorktree` throws `IOException("Failed to create git worktree: <stderr>")` inside a
  `try` whose `catch (e: IOException)` rethrows `IOException("Failed to execute git command: Failed to create git
  worktree: <stderr>", e)`. The plugin (`OpenProjectCopyAction`) shows `ex.message`, so the user sees both prefixes.
- `GitWorktreeManager.executeGitCommand` reads stdout fully, then stderr, then `waitFor()` with no timeout. A `git`
  that blocks (credential prompt, hook, lock) hangs the pooled thread forever; a large stderr can deadlock the read.
- `GitUtils.executeGitCommand` is a second private runner (2 s timeout, `redirectErrorStream(true)`). Two copies of
  "run git in a dir and collect output" violate the project's deduplication rule.
- `GitUtils.detectGitBranch` matches `[0-9a-f]{40}` only. A repository with `extensions.objectFormat = sha256` has
  a 64-hex `HEAD` and falls through to `git rev-parse --abbrev-ref HEAD`, which prints `HEAD`, so the popup shows
  `HEAD` instead of `HEAD (detached)`.

Decisions (made with the user in the planning session): fix all three in core only; one shared runner with a
combined output stream; 2 s timeout for branch detection, 30 s for worktree creation.

Related plans: none touch these files. `plans/core-gap-12-copy-semantics.md` and `plans/core-gap-13-process-launcher.md`
are independent.

## Target behavior

| Case                                      | Result                                                          |
|-------------------------------------------|-----------------------------------------------------------------|
| `createWorktree`, git exits non-zero      | one `IOException("Failed to create git worktree: <output>")`, no cause |
| `createWorktree`, git runs longer than 30 s | process destroyed, `IOException("git worktree timed out after 30 s")` |
| `createWorktree`, git not on PATH         | `IOException` from `ProcessBuilder.start()` propagates unwrapped |
| `createWorktree`, invalid inputs          | `IllegalArgumentException` as today (unchanged)                 |
| `detectGitBranch`, `HEAD` is 40 hex       | `"HEAD (detached)"` (unchanged)                                 |
| `detectGitBranch`, `HEAD` is 64 hex       | `"HEAD (detached)"` (new)                                       |
| `detectGitBranch`, `HEAD` is anything else | `git rev-parse --abbrev-ref HEAD`, 2 s timeout, else null (unchanged) |

## Implementation

All in package `com.projectjuggler.util`, directory `core/src/main/kotlin/com/projectjuggler/util/`.

### New `GitCommandRunner.kt`

```kotlin
internal data class GitCommandResult(val exitCode: Int, val output: String)

internal object GitCommandRunner {
    /** Runs `git <args>` in [workingDir]; stdout and stderr are merged into [GitCommandResult.output]. */
    fun run(workingDir: Path, args: List<String>, timeout: Duration): GitCommandResult
}
```

- `ProcessBuilder(listOf("git") + args).directory(workingDir.toFile()).redirectErrorStream(true).start()`.
- Read `process.inputStream` to a string on the calling thread **before** `waitFor` (a merged stream cannot deadlock
  on a second pipe, but the pipe still has to be drained for a chatty command to finish). Then
  `process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)`; on `false`, `destroyForcibly()` and throw
  `IOException("git ${args.firstOrNull()} timed out after ${timeout.toSeconds()} s")`.
- Reading a pipe of a process that never exits can itself block. Drain it on a short-lived daemon thread
  (`Thread { ... }.apply { isDaemon = true; start() }`) and `join(timeoutMillis)`; if the join times out, destroy
  the process and throw the timeout `IOException`. Keep this inside the runner so both callers get it for free.
- No `catch`: `IOException` from `start()`/reading propagates to the caller. `java.time.Duration` for the timeout.

### `GitUtils.kt`

- Replace the private `executeGitCommand` with a call to `GitCommandRunner.run(startDir, listOf("rev-parse",
  "--abbrev-ref", "HEAD"), Duration.ofSeconds(2))`; return `output.trim()` when `exitCode == 0` and non-empty, else
  null. The outer `try { ... } catch (e: Exception) { return null }` in `detectGitBranch` already swallows the
  runner's `IOException`; change it to `catch (e: Throwable)` per `CLAUDE.md`.
- Detached regex: `Regex("[0-9a-f]{40}|[0-9a-f]{64}")`. Keep the `.trim()` on `HEAD` content.

### `GitWorktreeManager.kt`

- Delete the private `executeGitCommand` and `CommandResult`.
- In `createWorktree`, remove the `try`/`catch (e: IOException)`. Body becomes:

```kotlin
val result = GitCommandRunner.run(source, listOf("worktree", "add", "-b", branchName, destination.toString()),
    Duration.ofSeconds(30))
if (result.exitCode != 0) {
    val detail = result.output.trim().ifBlank { "git exited with code ${result.exitCode}" }
    throw IOException("Failed to create git worktree: $detail")
}
messageOutput?.invoke("Git worktree created successfully")
```

- Update the KDoc `@throws IOException` line to mention the timeout. `isGitRepository` is unchanged.

Plugin and sync-helper need no change: `OpenProjectCopyAction` already catches `Exception` and shows `ex.message`.

## Tests

Kotest `StringSpec`, `core/src/test/kotlin/com/projectjuggler/util/`, temp dirs via
`com.projectjuggler.test.createTempDir`. No Koin needed.

### `GitUtilsTest.kt` (extend)

- `"should report detached HEAD for a SHA-256 repository"`: `repo("detached-sha256", head = "b".repeat(64))`,
  expect `"HEAD (detached)"`.

### New `GitWorktreeManagerTest.kt`

Skip the whole spec when `git` is not available: compute `val gitAvailable = runCatching {
ProcessBuilder("git", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)` at spec construction
and pass `enabledIf = { gitAvailable }` on each test (or wrap with `if (gitAvailable) { ... }`). Create a real repo in
`beforeSpec` with `git init -q`, `git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init`
(run through `ProcessBuilder`; a small local helper is fine in the test).

- `"should create a worktree on a new branch"`: `createWorktree(repo, repo.parent.resolve("wt1"), "feature-1")`;
  assert `wt1/.git` is a regular file and `GitUtils.detectGitBranch(wt1) shouldBe "feature-1"`.
- `"should throw a single IOException when the branch already exists"`: call once with `"dup"`, call again with a new
  destination and `"dup"`; `shouldThrow<IOException>` and `message shouldStartWith "Failed to create git worktree: "`
  and `message shouldNotContain "Failed to execute git command"`, and `cause shouldBe null`.
- `"should reject invalid inputs"`: missing source, source without `.git`, existing destination, blank branch, each
  `shouldThrow<IllegalArgumentException>` (one test with four assertions is enough).
- `"should pass progress messages to the callback"`: collect `messageOutput` into a list; expect two entries.

Timeout path: not unit tested (would need a hanging `git`); note in the spec that it is covered by the runner's
`waitFor(timeout)` contract. Manual check under Verification.

## Docs

`docs/core-spec.md`:

- §11: `GitUtils` line: "40- or 64-hex SHA -> `"HEAD (detached)"`". `GitWorktreeManager` line: add "with a 30 s
  timeout; stdout and stderr are merged into the error message". Add a bullet: "`GitCommandRunner` (internal) runs
  `git` with merged output and a timeout; used by both."
- "Known gaps": delete item 11.

## Sessions

1. Single session (Fable 5.1, current effort), ~12% of context. Reads: this plan, `CLAUDE.md`, `GitUtils.kt`,
   `GitWorktreeManager.kt`, `GitUtilsTest.kt`, `test/TempDirExtension.kt`, spec §11 and "Known gaps". Implements all
   of Implementation, Tests and Docs, runs Verification, then does Finishing. Done when `./gradlew :core:test` is
   green with the new spec included and the plugin compiles.

## Verification

1. `./gradlew :core:test --tests "com.projectjuggler.util.GitUtilsTest"`
   `./gradlew :core:test --tests "com.projectjuggler.util.GitWorktreeManagerTest"`
2. `./gradlew :core:test`
3. `./gradlew :plugin:compileKotlin`
4. `./gradlew :sync-helper:build`
5. Manual (timeout path): temporarily put a `git` shim first on PATH (`#!/bin/sh` + `sleep 60`), run the
   worktree test or `GitCommandRunner.run` from a scratch test with a 2 s timeout, confirm the `IOException` with
   "timed out" arrives after ~2 s and no `sleep` process is left behind. Remove the shim.

## Finishing

- Apply the Docs changes above; nothing in `CLAUDE.md` changes.
- Delete `plans/core-gap-11-git-helpers.md` in the same commit as the implementation.
- Check `git status` and `git diff`; stage only `core/src/main/kotlin/com/projectjuggler/util/GitCommandRunner.kt`,
  `GitUtils.kt`, `GitWorktreeManager.kt`, the two test files, `docs/core-spec.md` and the deleted plan, by path
  (never `git add -A`). Commit in the style of recent commits: lowercase imperative subject, a short body with what
  changed and why, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Mention any file left
  uncommitted in the reply.
